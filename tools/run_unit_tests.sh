#!/usr/bin/env bash
# Runs the pure-Kotlin unit tests (agent parser + sandbox) on a desktop JVM.
#
# These are the two parts of the agent that can be proven correct without a device:
#   - AgentToolParser: free models produce messy replies; every test case is a real reply shape
#   - ProjectSandbox:   path containment, including the symlink-escape case
#
# Requires tools/typecheck_kotlin.sh to have run once (it populates the classpath).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Cache lives outside the project: 85 MB would consume most of the workspace quota.
CP="${OMNI_CLASSPATH_DIR:-/tmp/ktclasspath}"
KC="${OMNI_KOTLINC:-/tmp/kc/bin/kotlinc}"
[ -x "$KC" ] || KC=/tmp/kc/kotlinc/bin/kotlinc
OUT="${TMPDIR:-/tmp}/omni-junit"

JARS="$(ls "$CP"/*.jar 2>/dev/null | tr '\n' ':')"
[ -n "$JARS" ] || { echo "!! classpath empty — run ./tools/typecheck_kotlin.sh first"; exit 2; }
STDLIB="$(dirname "$KC")/../lib/kotlin-stdlib.jar"

rm -rf "$OUT"; mkdir -p "$OUT"
"$KC" -nowarn -Xplugin="$(dirname "$KC")/../lib/kotlinx-serialization-compiler-plugin.jar" -cp "$JARS" -d "$OUT" \
    "$ROOT"/app/src/main/java/com/omni/llm/agent/AgentToolParser.kt \
    "$ROOT"/app/src/main/java/com/omni/llm/agent/ProjectSandbox.kt \
    "$ROOT"/app/src/main/java/com/omni/llm/data/ModelCatalog.kt \
    "$ROOT"/app/src/main/java/com/omni/llm/data/ChatStore.kt \
    "$ROOT"/app/src/main/java/com/omni/llm/data/BuildStateStore.kt \
    "$ROOT"/app/src/main/java/com/omni/llm/data/OmniSettings.kt \
    "$ROOT"/app/src/main/java/com/omni/llm/data/DeviceDefaults.kt \
    "$ROOT"/app/src/test/java/com/omni/llm/agent/*.kt \
    "$ROOT"/app/src/test/java/com/omni/llm/data/*.kt || exit 1

java -cp "$OUT:$STDLIB:$JARS" org.junit.runner.JUnitCore \
    com.omni.llm.agent.AgentToolParserTest \
    com.omni.llm.agent.ProjectSandboxTest \
    com.omni.llm.data.ChatStoreLogicTest \
    com.omni.llm.data.ProviderRequestShapeTest \
    com.omni.llm.data.IsolationAndActionsTest \
    com.omni.llm.data.DefaultsSanityTest
