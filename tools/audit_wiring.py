#!/usr/bin/env python3
"""
Checks that every composable call in MainActivity.kt matches its declaration.

WHY: `MainActivity.kt` is the one file that cannot be type-checked without the Compose compiler
plugin (it calls the inline `viewModel()`), so kotlinc never validates it. Kotlin routes composable
arguments by NAME, so swapping two screens' argument blocks compiles fine whenever the parameter
names happen to overlap — which is exactly what happened during development: the Chat and Models
branches got exchanged, ChatScreen received `onDownload`/`onLoad`, and the app would have failed at
runtime instead of at build time. This script is the missing check.

It verifies, per call site:
  - every declared parameter is passed
  - no parameter is passed that the screen does not declare
  - the order matches (catches swapped blocks even when names coincide)
  - ViewModel method references exist, and are zero-argument where a () -> Unit is expected
  - UiState fields read by the screen are actually declared

Exit code 0 = clean. Run it after touching MainActivity or any screen.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/omni/llm/MainActivity.kt"
UI = ROOT / "app/src/main/java/com/omni/llm/ui"


def block(src: str, anchor: str) -> str:
    """Text inside the parentheses that open at `anchor`."""
    i = src.index(anchor)
    j = src.index("(", i)
    depth, k = 0, j
    while k < len(src):
        if src[k] == "(":
            depth += 1
        elif src[k] == ")":
            depth -= 1
            if depth == 0:
                break
        k += 1
    return src[j + 1:k]


def split_top(inner: str):
    inner = inner.replace("->", "@@")  # a lambda arrow is not a bracket
    parts, depth, cur = [], 0, ""
    for ch in inner:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return [p.replace("@@", "->") for p in parts]


def names(inner: str, pattern: str):
    out = []
    for p in split_top(inner):
        p = p.strip()
        if not p or p.startswith("@"):
            continue
        m = re.match(pattern, p)
        if m:
            out.append(m.group(1))
    return out


def main() -> int:
    main_src = MAIN.read_text(encoding="utf-8")
    problems = []

    call_sites = [
        ("0 -> AgentScreen(", "AgentScreen"),
        ("1 -> ChatScreen(", "ChatScreen"),
        ("2 -> ModelsScreen(", "ModelsScreen"),
        ("else -> SettingsScreen(", "SettingsScreen"),
    ]

    for anchor, screen in call_sites:
        screen_file = UI / f"{screen}.kt"
        if not screen_file.exists():
            problems.append(f"{screen}: screen file missing")
            continue
        src = screen_file.read_text(encoding="utf-8")
        if f"fun {screen}(" not in src:
            problems.append(f"{screen}: no declaration found")
            continue
        declared = names(block(src, f"fun {screen}("), r"(\w+)\s*:")
        passed = names(block(main_src, anchor), r"(\w+)\s*=")

        missing = [d for d in declared if d not in passed]
        unknown = [p for p in passed if p not in declared]
        if missing:
            problems.append(f"{screen}: declared but NOT passed -> {missing}")
        if unknown:
            problems.append(f"{screen}: passed but NOT declared -> {unknown}")
        if not missing and not unknown and declared != passed:
            problems.append(
                f"{screen}: same arguments in a different order "
                f"(declared {declared} vs passed {passed}) — likely swapped call blocks"
            )
        # A NAME match is not enough: `state = modelsState` passed to ChatScreen has the right
        # name and the wrong type, and MainActivity is never type-checked. Derive which state
        # variable each screen must receive from the `val xState by vm.state.collectAsState()` lines.
        passed_block = block(main_src, anchor)
        m = re.search(r"state\s*=\s*(\w+)", passed_block)
        if m:
            state_var = m.group(1)
            # Which ViewModel does this call actually use? Take it from the method references
            # (`chatVm::send`), not from the screen name — ChatScreen is driven by ChatViewModel,
            # not ChatScreenViewModel, and guessing wrong silently disabled this check once.
            refs = re.findall(r"(\w+)::", passed_block)
            vm_vars = {r for r in refs}
            owners = {}
            for mm in re.finditer(r"val (\w+) by (\w+)\.state\.collectAsState", main_src):
                owners[mm.group(2)] = mm.group(1)
            for vm_var in vm_vars:
                owner = owners.get(vm_var)
                if owner and state_var != owner:
                    problems.append(
                        f"{screen}: receives state={state_var} but is driven by {vm_var}, which "
                        f"publishes {owner} — wrong ViewModel's state (a type error Kotlin cannot "
                        f"see here, because MainActivity is never type-checked)"
                    )
                    break

        if not missing and not unknown:
            print(f"  ok   {screen}: {len(declared)} parameters match")

        # ViewModel references: must exist, and take no arguments for a () -> Unit slot.
        vm_name = screen[0].lower() + screen[1:].replace("Screen", "ViewModel")
        vm_file = UI / f"{vm_name}.kt"
        if vm_file.exists():
            vm = vm_file.read_text(encoding="utf-8")
            methods = set(re.findall(r"fun (\w+)", vm))
            zero = set(re.findall(r"fun (\w+)\(\)", vm))
            for ref in re.findall(rf"{vm_name}::(\w+)", main_src):
                if ref not in methods:
                    problems.append(f"{vm_name}::{ref} does not exist")
                elif ref not in zero:
                    problems.append(f"{vm_name}::{ref} takes parameters but is passed as a callback")

            # UiState fields the screen reads must be declared.
            if "data class UiState(" in vm:
                ui = vm.split("data class UiState(", 1)[1].split("\n    )", 1)[0]
                fields = set(re.findall(r"val (\w+):", ui))
                used = set(re.findall(r"state\.(\w+)", src))
                undeclared = sorted(used - fields)
                if undeclared:
                    problems.append(f"{vm_name}.UiState: used but not declared -> {undeclared}")

    if problems:
        print("\nWIRING PROBLEMS:")
        for p in problems:
            print("  FAIL " + p)
        return 1
    print("\nAll call sites wired correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
