#!/usr/bin/env python3
"""
Probes whether the *free* keyless models can actually drive the agent loop.

The agent's whole design assumes a model will answer with exactly one ```tool JSON block per turn.
That assumption is the feature's single point of failure, so it gets measured rather than hoped for.

For each provider/model it runs a two-turn mini-loop using the REAL production system prompt
(AgentPrompts.kt) and reports:
  turn 1  did it emit a parseable tool call?      -> "protocol"
           did it pick write_file for a new file?  -> "right tool"
  turn 2  (given the tool result) did it continue?- "continues"

Usage: python3 tools/probe_agent_loop.py [--models a,b] [--verbose]
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------- the real prompt

Q = '"' * 3


def system_prompt():
    """
    Extracted from AgentPrompts.kt so the probe cannot silently drift from what ships.
    The text between `fun system(` and the closing triple quote for the WEB stack.
    """
    src = open("app/src/main/java/com/omni/llm/agent/AgentPrompts.kt", encoding="utf-8").read()
    start = src.index('fun system(stack: AgentEngine.Stack): String = ' + Q)
    start = src.index(Q, start) + 3
    end = src.index(Q + '.trim()', start)
    text = src[start:end]
    # Mirror stackRules(WEB): take the WEB/HTML_GAME branch
    rs = src.index('fun stackRules(')
    a = src.index('AgentEngine.Stack.WEB, AgentEngine.Stack.HTML_GAME -> ' + Q, rs)
    a = src.index(Q, a) + 3
    b = src.index(Q + '.trim()', a)
    rules = src[a:b]
    # The game-only sentence is inside a Kotlin conditional; keep the website one.
    rules = rules.replace('${if (stack == AgentEngine.Stack.HTML_GAME)', '')
    text = text.replace('${stack.label}', 'Website / web app')
    text = text.replace('${stackRules(stack)}', rules)
    return text.strip()

TOOL_SPEC = """
## Tools
Reply with exactly ONE fenced block and nothing else:

```tool
{"tool":"write_file","path":"index.html","content":"..."}
```

Tools: write_file {path,content}, read_file {path}, list_files {}, delete_file {path},
fetch_url {path,url}, finish {summary}."""

# ---------------------------------------------------------------- providers

PROVIDERS = [
    ("pollinations", "https://text.pollinations.ai/openai", None,
     ["openai"]),
    ("llm7", "https://api.llm7.io/v1/chat/completions", "Bearer unused",
     ["gpt-oss:20b", "gemma4:31b", "DeepSeek-V4-Flash-0731", "kimi-k2.7-code", "gemma-3-27b"]),
    ("ovh", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions", None,
     ["Meta-Llama-3_3-70B-Instruct"]),
]


def call(url, auth, model, messages, timeout=120):
    body = json.dumps({
        "model": model, "messages": messages, "stream": False,
        "max_tokens": 1200, "temperature": 0.2,
    }).encode()
    headers = {"Content-Type": "application/json", "User-Agent": "OmniLLM-probe/1.0"}
    if auth:
        headers["Authorization"] = auth
    req = urllib.request.Request(url, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001
        return None, type(e).__name__
    try:
        data = json.loads(raw)
        return data["choices"][0]["message"].get("content") or "", None
    except Exception:
        return raw[:300], None


FENCE = re.compile(r"```([A-Za-z]*)[ \t]*\r?\n([\s\S]*?)```")


def parse(reply):
    """Mirror of AgentToolParser.parse for the probe."""
    cands = [m.group(2) for m in FENCE.finditer(reply or "")] + [reply or ""]
    for raw in cands:
        for span in objects(raw):
            try:
                obj = json.loads(span)
            except Exception:
                continue
            if isinstance(obj, dict) and isinstance(obj.get("tool"), str):
                return obj
    return None


def objects(text):
    """Every balanced {...} in text, outer-first."""
    out, start = [], 0
    while True:
        i = text.find("{", start)
        if i < 0:
            return out
        depth, instr, esc, end = 0, False, False, -1
        for k in range(i, len(text)):
            c = text[k]
            if esc:
                esc = False
                continue
            if instr:
                if c == "\\":
                    esc = True
                elif c == '"':
                    instr = False
                continue
            if c == '"':
                instr = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = k
                    break
        if end > 0:
            out.append(text[i:end + 1])
            start = i + 1
        else:
            return out


def probe(provider, url, auth, models, verbose=False):
    rows = []
    for model in models:
        sysmsg = system_prompt() + TOOL_SPEC
        task = ("TASK: a tip calculator with a dark theme\n\nTARGET: Website / web app.\n"
                "The project folder is empty. Start from nothing.\n\n"
                "Reply with exactly one tool call, in a ```tool block. No prose outside it.")
        msgs = [{"role": "system", "content": sysmsg}, {"role": "user", "content": task}]

        t0 = time.time()
        reply, err = call(url, auth, model, msgs)
        dt = time.time() - t0
        if err:
            rows.append((model, f"unreachable ({err})", 0, 0, 0, dt))
            continue
        act = parse(reply)
        protocol = 1 if act else 0
        right = 1 if (act and act.get("tool") == "write_file" and act.get("path")) else 0

        cont = 0
        if act:
            msgs.append({"role": "assistant", "content": reply})
            msgs.append({"role": "user", "content":
                         "TOOL RESULT (%s):\nOK: wrote %s (%d bytes)"
                         % (act.get("tool"), act.get("path", "?"),
                            len(act.get("content") or ""))})
            reply2, err2 = call(url, auth, model, msgs)
            act2 = None if err2 else parse(reply2)
            cont = 1 if act2 else 0
            if verbose and act:
                print(f"    turn1 tool={act.get('tool')} path={act.get('path')} "
                      f"content={len(act.get('content') or '')}B")
                if act2:
                    print(f"    turn2 tool={act2.get('tool')} path={act2.get('path')}")
                else:
                    print(f"    turn2 RAW: {(reply2 or err2 or '')[:160]!r}")
        rows.append((model, "ok", protocol, right, cont, dt))
    return rows


def main():
    only = None
    verbose = "--verbose" in sys.argv
    if "--models" in sys.argv:
        only = sys.argv[sys.argv.index("--models") + 1].split(",")

    print("Free-model agent-protocol probe — real system prompt, 2 turns, no API key\n")
    hdr = f"{'provider':<13}{'model':<27}{'protocol':>9}{'right tool':>11}{'continues':>10}{'secs':>7}"
    print(hdr)
    print("-" * len(hdr))
    totals = [0, 0, 0, 0]
    for name, url, auth, models in PROVIDERS:
        if only:
            models = [m for m in models if m in only]
            if not models:
                continue
        for (model, status, p, r, c, dt) in probe(name, url, auth, models, verbose):
            if status != "ok":
                print(f"{name:<13}{model:<27}{status:>37}")
                continue
            print(f"{name:<13}{model:<27}{'yes' if p else 'NO':>9}"
                  f"{'yes' if r else 'NO':>11}{'yes' if c else 'NO':>10}{dt:>7.1f}")
            totals[0] += p
            totals[1] += r
            totals[2] += c
            totals[3] += 1
    if totals[3]:
        print(f"\nprotocol compliance: {totals[0]}/{totals[3]}   "
              f"correct first tool: {totals[1]}/{totals[3]}   "
              f"stays in loop: {totals[2]}/{totals[3]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
