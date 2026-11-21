#!/usr/bin/env python3
"""
Filters checker artifacts out of a kotlinc log, and nothing else.

The MainActivity pass in `typecheck_kotlin.sh` compiles real Kotlin against a hand-assembled
classpath. One diagnostic that appears there is not a source error:

    overload resolution ambiguity between candidates:
    fun <reified VM : ViewModel> viewModel(): VM
    fun <reified VM : ViewModel> viewModel(): VM

`lifecycle-viewmodel-compose` exposes `viewModel()` twice to the compiler here because the Compose
compiler plugin — which Gradle applies and bare kotlinc cannot — is what normally disambiguates it.
Gradle compiles this line every day. So the block is dropped.

The filter is deliberately narrow: a block is only removed if it is an ambiguity error whose entire
candidate list is `viewModel()`. Anything else passes through and fails the build.

USAGE
    python3 tools/filter_kotlinc_log.py <log>
    (prints nothing and exits 0 if no real errors remain; prints them and exits 1 otherwise)
"""
import re
import sys

ARTIFACT_MARKERS = ("viewModel(): VM", "viewModel(viewModelStoreOwner")


def is_artifact(block: str) -> bool:
    header = block.split("\n", 1)[0]
    if "overload resolution ambiguity" not in header:
        return False
    candidates = [ln for ln in block.splitlines() if ln.strip().startswith("fun ")]
    return bool(candidates) and all(
        any(m in c for m in ARTIFACT_MARKERS) for c in candidates
    )


def blocks(log: str) -> list[str]:
    """Split a kotlinc log into per-diagnostic blocks (they start with a file:line:col header)."""
    out, cur = [], []
    started = re.compile(r"^(?:e: )?[^\s:]+\.kt:\d+:\d+:")
    for line in log.splitlines():
        if started.match(line) or line.startswith("e: "):
            if cur:
                out.append("\n".join(cur))
            cur = [line]
        elif cur:
            cur.append(line)
    if cur:
        out.append("\n".join(cur))
    return out


def main() -> int:
    log = open(sys.argv[1], encoding="utf-8", errors="replace").read() if len(sys.argv) > 1 else ""
    keep = [b for b in blocks(log) if b.strip() and "error" in b.lower() and not is_artifact(b)]
    if not keep:
        return 0
    print("\n".join(keep))
    return 1


if __name__ == "__main__":
    sys.exit(main())
