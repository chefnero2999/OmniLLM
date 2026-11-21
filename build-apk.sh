#!/usr/bin/env bash
# Build the OmniLLM APK using the checked-in prebuilt native library (no NDK, no C++ compile).
#
#   bash build-apk.sh            # debug
#   bash build-apk.sh release    # release (minified, signed with the debug key unless configured)
#
# Why run this through `bash` instead of ./gradlew:
#   /storage/emulated/0 is mounted noexec and Android's FUSE layer ignores Unix permission bits,
#   so `chmod +x gradlew` reports success and then still fails with "Permission denied".
#   Invoking the script as an argument to bash reads it instead of exec'ing it, which works
#   on shared storage. Everything else (SDK, JDK, Gradle) is unaffected.
set -euo pipefail

MODE="${1:-debug}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

say() { printf '%s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

say "==> project: $HERE"

# ---------------------------------------------------------------------------------------------
# 1. JDK — Gradle needs 17+.
# ---------------------------------------------------------------------------------------------
if [ -x "${JAVA_HOME:-}/bin/java" ] || command -v java >/dev/null 2>&1; then
  say "==> java: $(java -version 2>&1 | head -1)"
else
  say ""
  die "No JDK found. In Termux:  pkg install openjdk-17 && export JAVA_HOME=\$PREFIX"
fi

# ---------------------------------------------------------------------------------------------
# 2. Native library — must be present, otherwise the APK installs and then crashes on load.
# ---------------------------------------------------------------------------------------------
if [ ! -f core/src/main/jniLibs/arm64-v8a/libomnillm.so ]; then
  say ""
  say "!! core/src/main/jniLibs/arm64-v8a/libomnillm.so is missing."
  say "   Either re-download the archive that contains it (it is 2.4 MB, the .so inside is 5.3 MB),"
  say "   or build it on-device:  bash scripts/build_native_termux.sh"
  exit 1
fi
say "==> native: core/src/main/jniLibs/arm64-v8a/libomnillm.so ($(du -h core/src/main/jniLibs/arm64-v8a/libomnillm.so | cut -f1))"

# ---------------------------------------------------------------------------------------------
# 3. Android SDK — compileSdk 35 and build-tools 35.0.0 are required.
# ---------------------------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for candidate in \
      "$HOME/Android/Sdk" \
      "$HOME/android-sdk" \
      "/storage/emulated/0/AndroidIDE/sdk" \
      "/storage/emulated/0/Android/sdk" \
      "${PREFIX:-/nonexistent}/opt/android-sdk"; do
    if [ -d "$candidate/platforms" ]; then SDK="$candidate"; break; fi
  done
fi

if [ -z "$SDK" ] || [ ! -d "$SDK/platforms" ]; then
  say ""
  say "!! Android SDK not found."
  say "   Find the path in your Android IDE (Settings → SDK location), then:"
  say "       export ANDROID_HOME=/path/from/your/ide"
  say "       bash build-apk.sh"
  say ""
  say "   Or install one in Termux:"
  say "       pkg install openjdk-17"
  say "       mkdir -p \$HOME/android-sdk/cmdline-tools"
  say "       # download commandlinetools-linux, unzip into that cmdline-tools dir, then:"
  say "       sdkmanager --sdk_root=\$HOME/android-sdk 'platforms;android-35' 'build-tools;35.0.0'"
  exit 1
fi

if [ ! -d "$SDK/platforms/android-35" ]; then
  say ""
  say "!! $SDK exists but has no platforms/android-35 (needed by compileSdk = 35)."
  say "   Install it:  sdkmanager --sdk_root=\"$SDK\" 'platforms;android-35' 'build-tools;35.0.0'"
  exit 1
fi

printf 'sdk.dir=%s\n' "$SDK" > local.properties
say "==> sdk: $SDK"

# ---------------------------------------------------------------------------------------------
# 4. Build. --no-daemon and a smaller heap because a phone is not a CI box: the 3 GB default in
#    gradle.properties gets the Gradle JVM killed by the LMK on 6-8 GB devices.
# ---------------------------------------------------------------------------------------------
GRADLE_FLAGS=(
  --no-daemon
  "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m"
  "-Pomni.usePrebuilt=true"
)

say "==> building ($MODE); first run downloads Gradle 8.11.1 (~130 MB)"
if [ "$MODE" = "release" ]; then
  bash gradlew :app:assembleRelease "${GRADLE_FLAGS[@]}"
else
  bash gradlew :app:assembleDebug "${GRADLE_FLAGS[@]}"
fi

say ""
say "==> APKs:"
find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \; 2>/dev/null | awk '{print "    "$5"  "$9}' || true
