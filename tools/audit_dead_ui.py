#!/usr/bin/env python3
"""
Finds UI that does nothing.

The user-visible failure mode this catches: a button renders, looks finished, and taps into an empty
lambda. It compiles, it passes every other gate, and only a human finds it by tapping.

Three checks:
  1. `onX = {}` in MainActivity — a callback wired to nothing.
  2. A screen parameter that MainActivity passes but the screen body never references (the button
     exists, its handler is ignored).
  3. A ViewModel method that no screen calls and no test references (dead code, or worse, a feature
     that was meant to be wired and was not).

Exit code 1 on findings, so it can gate a release.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/omni/llm/MainActivity.kt"
UI = ROOT / "app/src/main/java/com/omni/llm/ui"

problems = []
notes = []


def balanced(src, marker):
    i = src.index(marker)
    j = src.index("(", i)
    d, k = 0, j
    while k < len(src):
        if src[k] == "(":
            d += 1
        elif src[k] == ")":
            d -= 1
            if d == 0:
                break
        k += 1
    return src[j:k + 1]


def main() -> int:
    main_src = MAIN.read_text(encoding="utf-8")

    # ---- 1. empty lambdas -------------------------------------------------
    for m in re.finditer(r"(on[A-Z]\w*)\s*=\s*\{\s*\}\s*,?", main_src):
        line = main_src[: m.start()].count("\n") + 1
        problems.append(f"MainActivity:{line}: {m.group(1)} is wired to an empty lambda (dead control)")

    # ---- 2. parameters the screen never uses ------------------------------
    for screen in ["AgentScreen", "ChatScreen", "ModelsScreen", "SettingsScreen"]:
        path = UI / f"{screen}.kt"
        if not path.exists():
            continue
        src = path.read_text(encoding="utf-8")
        if f"fun {screen}(" not in src:
            continue
        sig = balanced(src, f"fun {screen}(")
        params = re.findall(r"(on[A-Z]\w*)\s*:", sig)
        body = src[src.index(f"fun {screen}(") + len(sig):]
        for p in params:
            # referenced in the body?
            if not re.search(rf"\b{p}\b", body):
                problems.append(f"{screen}: parameter `{p}` is declared and passed but never used")

    # ---- 3. ViewModel methods nobody calls --------------------------------
    all_src = "\n".join(
        p.read_text(encoding="utf-8") for p in (ROOT / "app/src/main/java").rglob("*.kt")
    )
    tests = "\n".join(
        p.read_text(encoding="utf-8") for p in (ROOT / "app/src/test").rglob("*.kt")
    ) if (ROOT / "app/src/test").exists() else ""
    for vm in ["AgentViewModel", "ChatViewModel", "ModelsViewModel", "SettingsViewModel"]:
        path = UI / f"{vm}.kt"
        if not path.exists():
            continue
        src = path.read_text(encoding="utf-8")
        for name in re.findall(r"^\s{4}fun (\w+)\(", src, re.M):
            if name.startswith("_"):
                continue
            # Count references from OUTSIDE the declaring file, in either form:
            #   vm::method      (reference passed as a callback)
            #   vm.method(...)  (direct call)
            outside_src = all_src.replace(src, "")
            outside = len(re.findall(rf"::{name}\b", outside_src)) + len(
                re.findall(rf"\.{name}\s*\(", outside_src)
            )
            if outside <= 0 and name not in tests:
                notes.append(f"{vm}.{name}() is never called from anywhere")

    # ---- report ----------------------------------------------------------
    if problems:
        print("DEAD UI:")
        for p in problems:
            print("  FAIL " + p)
    if notes:
        print("\nUnreferenced ViewModel methods (may be intentional API):")
        for n in notes:
            print("  note " + n)
    if not problems:
        print("No dead UI found." if not notes else "\nNo dead UI found.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
