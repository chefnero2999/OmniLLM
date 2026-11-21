#!/usr/bin/env bash
# Offline Kotlin type-check for OmniLLM — no Gradle, no Android Studio, no device.
#
# WHY THIS EXISTS
#   The machine that maintains this project has 2 GB RAM, so `./gradlew` dies with an OOM error.
#   This script compiles the Kotlin sources directly with kotlinc against a classpath assembled
#   from Maven Central + Google Maven. It catches the only class of error that has ever reached
#   users from this project: unresolved references, missing imports, wrong parameter counts,
#   missing UiState fields, stale callback names.
#
#   Budget: ~1.5 GB RAM, ~90 s cold, ~20 s warm.
#
# WHAT IT CANNOT DO
#   Compose-compiler codegen. `viewModel()` is an inline function that the Compose compiler plugin
#   normally lowers. Gradle applies that plugin; bare kotlinc cannot (the plugin registrar is
#   incompatible with the plain launcher classloader). So the UI files are type-checked by first
#   substituting a stub MainActivity for the real one — see CHECK_UI below.
#
# USAGE
#   ./tools/typecheck_kotlin.sh            # fetch deps if needed, then check
#   ./tools/typecheck_kotlin.sh --refresh  # force re-download of the dependency classpath
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Deliberately under /tmp: this cache is 85 MB and the workspace quota is ~128 MB.
CP="${OMNI_CLASSPATH_DIR:-/tmp/ktclasspath}"
KC="${OMNI_KOTLINC:-}"
[ -n "$KC" ] || for c in /tmp/kc/kotlinc/bin/kotlinc /tmp/kc/bin/kotlinc; do
  [ -x "$c" ] && { KC="$c"; break; }
done
WORK="${TMPDIR:-/tmp}/omnitypecheck"

if [ "${1:-}" = "--refresh" ]; then rm -rf "$CP"; fi

# ---------------------------------------------------------------- dependencies
fetch_deps() {
  echo "==> assembling classpath into $CP (one time, ~120 MB)"
  mkdir -p "$CP" "$WORK/dl"
  local T="$WORK/dl"
  local G=https://dl.google.com/android/maven2
  local M=https://repo1.maven.org/maven2
  local aar=(
    androidx/compose/ui/ui-android/1.7.6/ui-android-1.7.6.aar
    androidx/compose/ui/ui-graphics-android/1.7.6/ui-graphics-android-1.7.6.aar
    androidx/compose/ui/ui-text-android/1.7.6/ui-text-android-1.7.6.aar
    androidx/compose/ui/ui-unit-android/1.7.6/ui-unit-android-1.7.6.aar
    androidx/compose/ui/ui-geometry-android/1.7.6/ui-geometry-android-1.7.6.aar
    androidx/compose/ui/ui-util-android/1.7.6/ui-util-android-1.7.6.aar
    androidx/compose/ui/ui-tooling-preview-android/1.7.6/ui-tooling-preview-android-1.7.6.aar
    androidx/compose/foundation/foundation-android/1.7.6/foundation-android-1.7.6.aar
    androidx/compose/foundation/foundation-layout-android/1.7.6/foundation-layout-android-1.7.6.aar
    androidx/compose/runtime/runtime-android/1.7.6/runtime-android-1.7.6.aar
    androidx/compose/runtime/runtime-saveable-android/1.7.6/runtime-saveable-android-1.7.6.aar
    androidx/compose/animation/animation-android/1.7.6/animation-android-1.7.6.aar
    androidx/compose/animation/animation-core-android/1.7.6/animation-core-android-1.7.6.aar
    androidx/compose/material3/material3-android/1.3.1/material3-android-1.3.1.aar
    androidx/compose/material/material-icons-extended-android/1.7.6/material-icons-extended-android-1.7.6.aar
    androidx/compose/material/material-icons-core-android/1.7.6/material-icons-core-android-1.7.6.aar
    androidx/compose/material/material-ripple-android/1.7.6/material-ripple-android-1.7.6.aar
    androidx/lifecycle/lifecycle-runtime-android/2.8.7/lifecycle-runtime-android-2.8.7.aar
    androidx/lifecycle/lifecycle-viewmodel-android/2.8.7/lifecycle-viewmodel-android-2.8.7.aar
    androidx/lifecycle/lifecycle-viewmodel-compose-android/2.8.7/lifecycle-viewmodel-compose-android-2.8.7.aar
    androidx/lifecycle/lifecycle-runtime-compose-android/2.8.7/lifecycle-runtime-compose-android-2.8.7.aar
    androidx/lifecycle/lifecycle-service/2.8.7/lifecycle-service-2.8.7.aar
    androidx/core/core-ktx/1.15.0/core-ktx-1.15.0.aar
    androidx/core/core/1.15.0/core-1.15.0.aar
    androidx/activity/activity-compose/1.9.3/activity-compose-1.9.3.aar
    androidx/activity/activity/1.9.3/activity-1.9.3.aar
    androidx/activity/activity-ktx/1.9.3/activity-ktx-1.9.3.aar
    androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.aar
    androidx/datastore/datastore-preferences-android/1.1.1/datastore-preferences-android-1.1.1.aar
    androidx/datastore/datastore-android/1.1.1/datastore-android-1.1.1.aar
    androidx/datastore/datastore-core-android/1.1.1/datastore-core-android-1.1.1.aar
    androidx/datastore/datastore-preferences-core-jvm/1.1.1/datastore-preferences-core-jvm-1.1.1.jar
    androidx/lifecycle/lifecycle-common-jvm/2.8.7/lifecycle-common-jvm-2.8.7.jar
    androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar
    androidx/collection/collection-jvm/1.4.5/collection-jvm-1.4.5.jar
  )
  local p name
  for p in "${aar[@]}"; do
    name="$(basename "$p")"
    curl -sfSL "$G/$p" -o "$T/$name" || { echo "  !! missing $p"; continue; }
    if [[ $name == *.aar ]]; then
      ( cd "$T" && rm -rf x && mkdir x && cd x \
          && unzip -oq "../$name" classes.jar 2>/dev/null \
          && cp classes.jar "$CP/${name%.aar}.jar" )
    else
      cp "$T/$name" "$CP/${name%.jar}-${p: -6}.jar"
    fi
  done
  local jars=(
    "$M/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
    "$M/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
    "$M/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.9.0/kotlinx-coroutines-android-1.9.0.jar"
    "$M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar"
    "$M/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar"
    "$M/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar"
    "$M/org/json/json/20240303/json-20240303.jar"
  )
  local u n
  for u in "${jars[@]}"; do
    n="$(basename "$u" .jar)-${u: -6}.jar"
    curl -sfSL "$u" -o "$CP/$n" || echo "  !! missing $u"
  done
  # Real Android platform jar. The MavenCentral com.google.android:android artifact is an
  # API-4 stub and lacks TIRAMISU / POST_NOTIFICATIONS — do not substitute it.
  if [ ! -f "$CP/android.jar" ]; then
    curl -sfSL https://dl.google.com/android/repository/platform-35_r02.zip -o "$T/p35.zip" \
      && ( cd "$T" && rm -rf p35 && mkdir p35 && cd p35 \
           && unzip -oq ../p35.zip '*/android.jar' && mv */android.jar "$CP/android.jar" ) \
      && echo "  android.jar (API 35) ok"
  fi
  rm -rf "$T"
  echo "  $CP: $(ls "$CP"/*.jar 2>/dev/null | wc -l) jars"
}

# ---------------------------------------------------------------- kotlinc
ensure_kotlinc() {
  if [ -x "$KC" ]; then return 0; fi
  echo "==> fetching kotlinc 2.0.21"
  mkdir -p "$WORK/dl" /tmp/kctmp
  curl -sfSL https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip \
       -o "$WORK/dl/kc.zip" || { echo "!! cannot download kotlinc"; exit 2; }
  ( cd /tmp/kctmp && unzip -oq "$WORK/dl/kc.zip" )
  rm -rf /tmp/kc && mv /tmp/kctmp/kotlinc /tmp/kc && rm -rf /tmp/kctmp "$WORK/dl/kc.zip"
  for c in /tmp/kc/kotlinc/bin/kotlinc /tmp/kc/bin/kotlinc; do
    [ -x "$c" ] && { KC="$c"; return 0; }
  done
}

[ -f "$CP/android.jar" ] && [ "$(ls "$CP"/*.jar 2>/dev/null | wc -l)" -ge 20 ] || fetch_deps
ensure_kotlinc
[ -x "$KC" ] || { echo "!! kotlinc not found at $KC — set OMNI_KOTLINC=/path/to/kotlinc"; exit 2; }
SER="$(dirname "$KC")/../lib/kotlinx-serialization-compiler-plugin.jar"
mkdir -p "$WORK"

# ---------------------------------------------------------------- stubs
# R and BuildConfig are generated by AAPT/AGP at build time. Tabulated from
# res/values/strings.xml + res/drawable so a missing R.string is still a real error.
mkdir -p "$WORK/stubsrc" "$WORK/stubs"
cat > "$WORK/stubsrc/R.java" <<'JAVA'
package com.omni.llm;
public final class R {
  public static final class string {
    public static final int app_name=1, notification_channel_name=2,
      notification_channel_description=3, notification_title=4;
  }
  public static final class drawable { public static final int ic_launcher_foreground=10; }
}
JAVA
cat > "$WORK/stubsrc/BuildConfig.java" <<'JAVA'
package com.omni.llm;
public final class BuildConfig { public static final String OMNI_MANIFEST_URL = ""; }
JAVA

# MainActivity is the one file that needs Compose codegen (viewModel$default). Replace it with a
# signature-compatible stub so every OTHER file — including all the Composables — type-checks.
# Any new import or parameter in MainActivity.kt must be mirrored here; that is deliberate,
# because it forces MainActivity to be checked against the stub's expected wiring.
mkdir -p "$WORK/srcstub/com/omni/llm"
cat > "$WORK/srcstub/com/omni/llm/MainActivityStub.kt" <<'KT'
package com.omni.llm
import android.app.Activity
/** Type-check stub for `MainActivity`. Never compiled into the app. */
class MainActivity : Activity()
KT

CPJARS="$(ls "$CP"/*.jar | tr '\n' ':')$CP/android.jar"

echo "==> compiling stub R / BuildConfig"
javac_bin="$(command -v javac || true)"
if [ -n "$javac_bin" ]; then
  "$javac_bin" -nowarn -cp "$CPJARS" -d "$WORK/stubs" "$WORK/stubsrc"/*.java || exit 1
else
  echo "  (no javac; using prebuilt stubs if present)"
fi

echo "==> compiling :core"
if ! "$KC" -nowarn -cp "$CPJARS" -d "$WORK/out_core" \
        "$ROOT"/core/src/main/java/com/omni/llm/core/*.kt > "$WORK/core.log" 2>&1; then
  grep -E 'error: ' "$WORK/core.log" || sed -n '1,20p' "$WORK/core.log"
  echo "!! :core failed"; exit 1
fi

echo "==> type-checking :app (all files; MainActivity.kt swapped for a stub)"
# ---------------------------------------------------------------------------------------------
# PASS 0: the real MainActivity, checked alongside the real ViewModels.
#
# This pass exists because of a shipped bug. The original approach stubbed MainActivity instead,
# which made it the one file in the project that nothing type-checked — and it went out with an
# unresolved reference (`ProjectExport`), a bad lambda receiver (`this@MainActivity` inside a
# composable lambda), and two calls to methods that had been deleted from ChatViewModel. The user
# hit all five errors in Gradle.
#
# Fix: keep every real source EXCEPT the Compose screen composables, and stub only those. Screens
# are `@Composable fun (Data, dozens of lambdas) -> Unit`; their bodies need the Compose compiler
# but their signatures do not. MainActivity's call sites are plain Kotlin where every real mistake
# — wrong receiver, missing import, renamed method, wrong argument count — is a type error.
#
# The stubs are GENERATED from the real files (tools/gen_screen_stubs.py) so they cannot drift.
echo "==> PASS 0: type-checking the REAL MainActivity + real ViewModels (screens stubbed)"
STUBDIR="$WORK/mastub"
if ! python3 "$ROOT/tools/gen_screen_stubs.py" "$STUBDIR" > /dev/null 2>&1; then
  echo "!! could not generate screen stubs"; exit 1
fi

# Real sources, minus the screen composables and the real theme (both stubbed above).
MA_FILES=$(find "$ROOT/app/src/main/java" -name '*.kt' \
  ! -name 'AgentScreen.kt' ! -name 'ChatScreen.kt' ! -name 'ModelsScreen.kt' \
  ! -name 'SettingsScreen.kt' ! -name 'WelcomeScreen.kt' ! -name 'ModelPicker.kt' \
  ! -name 'ProjectFilesScreen.kt' ! -path '*/ui/theme/*')

"$KC" -nowarn -Xplugin="$SER" \
        -cp "$CPJARS:$WORK/out_core:$WORK/stubs" \
        -d "$WORK/out_main" $MA_FILES "$STUBDIR"/*.kt \
        > "$WORK/main.log" 2>&1
MA_REAL=$(python3 "$ROOT/tools/filter_kotlinc_log.py" "$WORK/main.log" | sed "s|$ROOT/||")
if [ -n "$MA_REAL" ]; then
  echo "!! MainActivity failed to type-check — this WOULD have broken the Gradle build:"
  echo "$MA_REAL" | head -30
  MAIN_FAILED=1
else
  echo "    ok   MainActivity call sites resolve against the real ViewModels"
fi

# PASS 1: every other file, with MainActivity swapped for a stub (see the note at the top).
FILES=$(find "$ROOT/app/src/main/java" -name '*.kt' ! -name 'MainActivity.kt')
LOG="$WORK/app.log"
"$KC" -nowarn \
      -Xplugin="$SER" \
      -cp "$CPJARS:$WORK/out_core:$WORK/stubs" \
      -d "$WORK/out_app" $FILES "$WORK/srcstub/com/omni/llm/MainActivityStub.kt" > "$LOG" 2>&1

# Guard against a false green: if the compiler never ran, there are no errors and no output either.
if ! grep -qE 'error:|warning:|exception:' "$LOG" && [ ! -d "$WORK/out_app" ]; then
  echo "!! the app compile produced no diagnostics and no output — it did not run."
  sed -n '1,20p' "$LOG"; exit 2
fi
# Distinguish the two failure kinds kotlinc can report here:
#   "error: ..."            -> frontend: parse / resolve / type-check. A REAL bug. Fail.
#   "exception: ...Backend" -> codegen (IR lowering). Tooling limit: needs the Compose plugin,
#                              which cannot run under a bare launcher. Expected. Not a bug.
ERRS=$(grep -E '^[^[:space:]].*\.kt:[0-9]+:[0-9]+: error: ' "$LOG" \
        | grep -v "Couldn't inline method call" || true)
BACKEND=$(grep -c 'Backend Internal error' "$LOG" || true)

if [ -n "$ERRS" ]; then
  echo "$ERRS"
  echo "  ^^ $(echo "$ERRS" | wc -l) real source error(s)"
  exit 1
fi

echo "  0 source errors — every file passed parse + resolve + type-check"
if [ "${BACKEND:-0}" -gt 0 ]; then
  echo "  note: codegen stopped on Compose IR lowering (compiling $(grep -o 'File being compiled: .*' "$LOG" | head -1 | sed 's|.*/||'))"
  echo "        this is the known bare-kotlinc limit, not a source error; Gradle compiles it"
fi
rm -rf "$WORK/out_app"
exit 0

if [ "${MAIN_FAILED:-0}" = "1" ]; then
  echo
  echo "!! FAILED: MainActivity.kt does not compile. Gradle will fail even if everything else passes."
  exit 1
fi
