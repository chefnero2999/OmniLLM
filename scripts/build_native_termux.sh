#!/usr/bin/env bash
# Build libomnillm.so **on the phone** in Termux (arm64-v8a), without Gradle and without the
# official NDK (whose binaries are x86_64 host builds and cannot execute on an aarch64 phone).
#
#   pkg install clang cmake ninja ndk-sysroot curl unzip
#   ./scripts/build_native_termux.sh [path-to-OmniLLM-project]
#
# Output: <project>/core/src/main/jniLibs/arm64-v8a/libomnillm.so
# Then build the APK with:   ./gradlew assembleDebug -Pomni.usePrebuilt=true
#
# YOU PROBABLY DO NOT NEED THIS SCRIPT.
# The project already ships a verified core/src/main/jniLibs/arm64-v8a/libomnillm.so built with the real
# NDK r27b. Use it and go straight to Gradle. This script exists only for people who want to
# reproduce the native build on-device, or who need an ABI the prebuilt does not cover.
#
# Caveat: this uses Termux's clang + the ndk-sysroot package instead of the official NDK. It
# produces a valid arm64 Android shared object, but it is the less-travelled path.
set -euo pipefail

PROJECT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SRC="$PROJECT/core/src/main/cpp"
OUT="$PROJECT/core/src/main/jniLibs/arm64-v8a"
BUILD="${TMPDIR:-${PREFIX:-/tmp}/tmp}/omni-build"
JOBS="${JOBS:-1}"          # keep 1: llama-model.cpp alone can exceed 1 GB RSS
API_CANDIDATES="${API_CANDIDATES:-28 24 21}"

say() { printf '%s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

say "==> project: $PROJECT"

# ---------------------------------------------------------------------------------------------
# 0. Do not waste an hour if the answer is already on disk.
# ---------------------------------------------------------------------------------------------
if [ -f "core/src/main/jniLibs/arm64-v8a/libomnillm.so" ]; then
  say "==> core/src/main/jniLibs/arm64-v8a/libomnillm.so already exists ($(du -h "core/src/main/jniLibs/arm64-v8a/libomnillm.so" | cut -f1))."
  say "    That prebuilt was verified and is almost certainly what you want."
  say "    Delete it first if you really want to rebuild it."
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# 1. Toolchain
# ---------------------------------------------------------------------------------------------
for tool in clang cmake ninja; do
  command -v "$tool" >/dev/null 2>&1 || die "missing '$tool'. Run: pkg install clang cmake ninja ndk-sysroot"
done
[ -n "${PREFIX:-}" ] || die "PREFIX is not set — this script must run inside Termux."

if [ ! -f "$SRC/llama.cpp/CMakeLists.txt" ]; then
  say "==> llama.cpp is not vendored yet, fetching it (~33 MB)"
  USE_GIT=0 bash "$PROJECT/scripts/fetch_llama.sh"
fi

# ---------------------------------------------------------------------------------------------
# 2. Find the Android sysroot. Package layouts differ between Termux versions, so probe instead
#    of assuming one hardcoded path.
# ---------------------------------------------------------------------------------------------
find_sysroot() {
  local candidates=(
    "$PREFIX/opt/ndk-sysroot/sysroot"
    "$PREFIX/opt/ndk-sysroot"
    "$PREFIX/ndk-sysroot/sysroot"
    "$PREFIX/ndk-sysroot"
    "$PREFIX/sysroot"
  )
  local c
  for c in "${candidates[@]}"; do
    if [ -d "$c/usr/include" ]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  # Last resort: any directory under $PREFIX/opt that contains usr/include/android
  local found
  found="$(find "$PREFIX/opt" -maxdepth 4 -type d -path '*/usr/include/android' -print -quit 2>/dev/null || true)"
  if [ -n "$found" ]; then
    printf '%s\n' "${found%/usr/include/android}"
    return 0
  fi
  return 1
}

if ! SYSROOT="$(find_sysroot)"; then
  say ""
  say "Could not find the Android sysroot anywhere under \$PREFIX."
  say "Installed packages under \$PREFIX/opt:"
  ls -1 "$PREFIX/opt" 2>/dev/null | sed 's/^/    /' || say "    (none)"
  say ""
  say "Fix with:   pkg install ndk-sysroot"
  say "Then re-run this script. If ndk-sysroot IS installed and this still fails, tell me the"
  say "output of:   ls \$PREFIX/opt && find \$PREFIX/opt -name 'api-level.h' | head"
  die "no Android sysroot"
fi
say "==> sysroot: $SYSROOT"

# ---------------------------------------------------------------------------------------------
# 3. Probe which API level the sysroot actually supports, by compiling a file that includes a
#    bionic Android header. Guessing here is what produces confusing link errors later.
# ---------------------------------------------------------------------------------------------
PROBE_DIR="$(mktemp -d)"
trap 'rm -rf "$PROBE_DIR"' EXIT
cat > "$PROBE_DIR/probe.c" <<'EOF'
#include <android/api-level.h>
#include <android/log.h>
#include <pthread.h>
int omni_probe(void) { return __ANDROID_API__; }
EOF

API=""
for candidate in $API_CANDIDATES; do
  if clang --target="aarch64-linux-android${candidate}" --sysroot="$SYSROOT" \
       -c "$PROBE_DIR/probe.c" -o "$PROBE_DIR/probe.o" >"$PROBE_DIR/probe.log" 2>&1; then
    API="$candidate"
    break
  fi
done
if [ -z "$API" ]; then
  say "Probe compile against $SYSROOT failed. clang said:"
  sed 's/^/    /' "$PROBE_DIR/probe.log" | head -20
  die "cannot compile for aarch64-linux-android with this sysroot"
fi
say "==> targeting aarch64-linux-android$API"

# ---------------------------------------------------------------------------------------------
# 4. Configure + build
# ---------------------------------------------------------------------------------------------
rm -rf "$BUILD"
say "==> configuring (static ggml, no Vulkan, no KleidiAI)"
cmake -B "$BUILD" -S "$SRC" -G Ninja \
  -DCMAKE_SYSTEM_NAME=Android \
  -DCMAKE_SYSTEM_VERSION="$API" \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -DCMAKE_SYSROOT="$SYSROOT" \
  -DCMAKE_C_COMPILER=clang \
  -DCMAKE_CXX_COMPILER=clang++ \
  -DCMAKE_C_FLAGS="--target=aarch64-linux-android$API --sysroot=$SYSROOT" \
  -DCMAKE_CXX_FLAGS="--target=aarch64-linux-android$API --sysroot=$SYSROOT" \
  -DCMAKE_EXE_LINKER_FLAGS="--target=aarch64-linux-android$API -landroid -llog" \
  -DCMAKE_SHARED_LINKER_FLAGS="--target=aarch64-linux-android$API -landroid -llog" \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_STATIC=ON \
  -DGGML_OPENMP=OFF \
  -DGGML_CPU_KLEIDIAI=OFF \
  -DOMNI_VULKAN=OFF \
  -DOMNI_KLEIDIAI=OFF \
  -DCMAKE_BUILD_TYPE=Release

say "==> compiling with -j$JOBS (the slow part: expect 20-60 min on a phone)"
cmake --build "$BUILD" -j"$JOBS"

SO="$BUILD/libomnillm.so"
[ -f "$SO" ] || die "build finished but $SO is missing"

mkdir -p "$OUT"
cp "$SO" "core/src/main/jniLibs/arm64-v8a/libomnillm.so"
for strip_tool in llvm-strip strip; do
  if command -v "$strip_tool" >/dev/null 2>&1; then
    "$strip_tool" --strip-unneeded "core/src/main/jniLibs/arm64-v8a/libomnillm.so" 2>/dev/null && break
  fi
done

say ""
say "==> done: core/src/main/jniLibs/arm64-v8a/libomnillm.so ($(du -h "core/src/main/jniLibs/arm64-v8a/libomnillm.so" | cut -f1))"
if command -v readelf >/dev/null 2>&1; then
  readelf -h "core/src/main/jniLibs/arm64-v8a/libomnillm.so" | grep -E "Class|Machine|Type" | sed 's/^/    /'
fi
say ""
say "    Now build the APK — no NDK needed at this point:"
say "        ./gradlew assembleDebug -Pomni.usePrebuilt=true"
