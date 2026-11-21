# Verification

Everything claimed about this project is checkable from this folder. No device required for most
of it. These are the commands, what they prove, and — importantly — what they do **not** prove.

## The three gates

```bash
./tools/typecheck_kotlin.sh    # compile every Kotlin file without Gradle
./tools/run_unit_tests.sh      # 25 JUnit tests: tool-call parsing + sandbox containment
python3 tools/audit_wiring.py  # MainActivity's composable calls match their declarations
```

| Gate | Proves | Does not prove |
| --- | --- | --- |
| `typecheck_kotlin.sh` | `:core` compiles; all 26 `:app` files pass parse, resolve and type-check | Compose codegen, resource linking, DEX |
| `run_unit_tests.sh` | The agent parser handles real messy replies; the sandbox refuses traversal and symlink escapes | Anything touching Android framework classes |
| `audit_wiring.py` | Each screen gets the right 9/7/10/22 arguments, in order, from the right ViewModel | Runtime behaviour |

### Why typecheck, not Gradle

This project is often maintained on a 2 GB machine where `./gradlew` dies of OOM. `kotlinc` with a
hand-assembled classpath needs ~1.5 GB and catches the only kind of error that has actually shipped
a bug here: unresolved references, wrong arity, stale field names.

The one file it cannot fully check is `MainActivity.kt`, because `viewModel()` is an inline function
that requires the Compose compiler plugin, which bare `kotlinc` cannot load (the plugin registrar is
incompatible with the plain launcher classloader). The script substitutes a stub for that file and
reports the codegen stop separately from real errors, so a green run means something.

**That gap is real, and it bit us.** During this work MainActivity's Chat and Models argument blocks
were accidentally exchanged. kotlinc never saw it; `audit_wiring.py` did. It is run alongside the
type check for exactly that reason.

## The parser tests

`app/src/test/java/com/omni/llm/agent/AgentToolParserTest.kt` — 11 cases. Each one is a reply shape
a free model actually produced, not an invented edge case:

- a clean ```` ```tool ```` block
- a ```` ```json ```` block
- raw JSON buried in "Sure! I'll create the file now." … "Let me know if you want changes!"
- braces and `return 1` inside the `content` string
- escaped quotes and Windows backslashes
- `create_file` / `writefile` / `done` / `ls` synonyms
- `code` and `text` instead of `content`
- prose with no call at all (must return null so the loop can nudge)
- empty, `{}`, and a reply truncated mid-string (must return null, never throw)

## The sandbox tests

`ProjectSandboxTest.kt` — 14 cases. The model supplies the paths, so it can supply
`../../databases/x`. Refused, with tests:

- `../`, `a/../../b`, `..\`, absolute `/data/...`
- blank, whitespace and NUL-byte paths
- **a symlinked subdirectory pointing outside** — the case a naive string-prefix check gets wrong
- empty content (a truncated stream must not become a real empty file)
- oversized content, with an actionable error rather than a silent drop
- delete and read cannot escape; the outside file survives

## Simulated end-to-end build

A scripted model drove the harness through 7 turns (write ×3, one reply with no tool call, one
traversal attempt, list, finish). Results:

```
turn 1: write_file index.html -> OK: wrote index.html (649 bytes, 21 lines)
turn 2: NO TOOL CALL -> harness nudges
turn 3: write_file styles.css -> OK: wrote styles.css (969 bytes, 15 lines)
turn 4: write_file app.js     -> OK: wrote app.js (800 bytes, 20 lines)
turn 5: write_file ../../escape.js -> ERROR: bad path. Absolute paths and '..' are refused.
turn 6: list_files -> app.js (800 B) styles.css (969 B) index.html (649 B)
turn 7: finish -> OK

OK: 3 files, ids=6, js-bindings=6, dangling=[]
```

`dangling=[]` means every `getElementById` in the generated JS matches an `id` in the generated
HTML. The JS was then executed against a DOM stub: setting the tip slider to 20 % on a ₹1000 bill
produced `₹1200.00` and `Tip ₹200.00` — correct.

`demo/agent-output-example.html` is that output, inlined into one file so it renders in any viewer.

## Catalogue and thread isolation

`ChatStoreLogicTest` (9 cases) pins down the two rules that are easy to break silently:

- **no two models share a thread key.** This test failed on first run: Kimi was listed under both
  Coding and Agentic with the same key, which would have merged two visibly separate chats into one
  conversation. Fixed with an explicit `threadKey`, and the test now guards it.
- **the cross-model digest** carries recent turns, marks itself read-only, truncates long messages,
  takes the tail rather than the head, drops system prompts, and returns empty for an empty thread.

## Every free model probed live

```
22 unique chat models in the catalogue
  answered OK: 10/22
```

The 12 that did not answer were **not** broken — they were `429 Daily token quota exceeded`, because
this session had already spent the shared free quota while measuring. `401` appeared for exactly one
model (`deepseek-v4-flash-0731-fast`), which is labelled `NEEDS_KEY` in the catalogue rather than
presented as free.

The useful part of that run: **all six OVH models answered**, and all five tested answered the real
agent prompt with a valid `write_file` in 3.6–19 s. The catalogue was re-tiered on that evidence —
`agentReady` flags and the default builder moved from LLM7 to OVH.

## Live model probe

```bash
python3 tools/probe_agent_loop.py --verbose
```

Runs the **real** system prompt (extracted from `AgentPrompts.kt`, so it cannot drift) against every
free endpoint, twice per model, and reports protocol compliance. Last run:

```
provider     model                       protocol right tool continues   secs
pollinations openai                           yes        yes        NO   12.5
llm7         gpt-oss:20b                       NO         NO        NO    7.1
llm7         gemma4:31b, DeepSeek, kimi, gemma-3-27b   HTTP 429 (quota)
ovh          Meta-Llama-3_3-70B-Instruct      yes        yes        NO   29.6

protocol compliance: 2/3   correct first tool: 2/3   stays in loop: 0/3
```

**Read that honestly.** The protocol works: given the production prompt, models reliably emit one
valid `write_file` with a real path and 400–950 bytes of content. What fails is turn 2, and it fails
for a reason that has nothing to do with the models:

- Pollinations: `402 FREE_LIMIT — 1 reply every 45 seconds per IP, retry after 20s`
- LLM7: `429 — Daily token quota exceeded. Retry after 85268 seconds` (per-model; `gemma4:31b`
  wanted only 60 s)
- OVH: read timeout

So a multi-turn build **cannot** be done by firing requests back to back. That is why the router
honours `retry_after`, keys cooldowns on `provider/model` rather than provider, and the loop waits
out a quota window (up to 90 s) with a visible countdown instead of failing a build halfway.

`stays in loop: 0/3` in that table is therefore a measurement of the free tiers' patience, not of
the harness. What the harness does under quota pressure is tested by the simulated build above.

## What still needs a device

- The Compose build itself: `./gradlew assembleDebug`, and that `AgentScreen` renders.
- `WebView` actually displaying a generated `index.html` from `file://` with
  `allowFileAccessFromFileURLs`. The setting is correct for this use, but only a real WebView
  proves it.
- `FileProvider` sharing the export zip.

## Native engine

The shipped `core/src/main/jniLibs/arm64-v8a/libomnillm.so` (26,004,224 B) was verified
independently: 19/19 JNI symbols present before and after `llvm-strip`, `NEEDED` =
`libandroid, liblog, libvulkan, libm, libdl, libc`, `ggml_vk_*` present, 922 SPIR-V modules
(12.8 MB). Provenance is in `PREBUILT_INFO.txt` next to it.

---

## Round: reply controls, file browser, auto-switch, uncensored models, defaults audit

Run on 2026-11-18. Every number below came from an actual run, not an estimate.

```
./tools/typecheck_kotlin.sh   → 0 source errors
./tools/run_unit_tests.sh     → OK (57 tests)
python3 tools/audit_wiring.py → all call sites OK
python3 tools/audit_dead_ui.py→ 0 dead controls
```

### What the live probes proved

| Check | Result |
| --- | --- |
| OVH plain chat (DeepSeek-V4.1, qwen3.6, gemma-4) | 3/3 `200` with text |
| Agent tool protocol, `max_tokens = 400` | DeepSeek + nemotron OK; **qwen3.6 and gemma-4 returned reasoning only** |
| Agent tool protocol, `max_tokens = 2000` | 3/3 emit a valid `write_file` call |
| Uncensored local models, HEAD on download URL | 4/4 match declared byte size exactly |
| LLM7 `gemma-4-uncensored`, `L3-8B-Lunaris-v1-Turbo` | `401` — these need a paid key, so they are **not** shipped as free |

### Bugs this round found and fixed

1. **Reasoning models starve on a small budget.** `gemma-4-26b-a4b` at `max_tokens = 400` spends
   the entire budget thinking and returns no tool call. Fixed with a per-model `minOutputTokens`
   floor that chat raises the request to; builds already had 4096.
2. **`batchSize` default was still 512** in the data class and the preference read, independently
   of the device-tier table. Now 256, and the panic button drops to 128.
3. **A fresh install had no working route.** `cloudFallbackEnabled` defaulted to `false`, so first
   launch could not reach any model until the user found the toggle. Now `true`.
4. **`topP` had a setter and no control.** A stored setting with no UI. Slider added.
5. **Five dead controls**, found by the new `audit_dead_ui.py`: export, open/close context sheet,
   chat `onClear`, and the `topP` gap. All wired or removed.
6. **The resume budget did not fit the smallest context.** A fixed 24 000 chars is ~6.7 K tokens;
   local models load at 2048. `BuildStateStore.budgetFor(contextTokens)` now sizes the digest to
   the model that reads it (about 45% of its window).
7. **Unrestricted mode never reached local models.** Only the cloud path applied
   `effectiveSystemPrompt()`. Now both.
8. **The device-default maths could not be unit-tested** because it lived in a class importing
   `android.*`. Extracted to `DeviceDefaults`, and `DefaultsSanityTest` now walks every RAM tier.

### Architectural note

All 24 settings fields were audited mechanically: each must be (a) read from DataStore, (b) have a
setter, and (c) be referenced outside `SettingsManager`. All 24 pass all three.

---

## Regression: the Gradle build broke on a file the gate could not see

A user build failed with five errors, all in `MainActivity.kt`:

```
MainActivity.kt:190:29 Unresolved reference 'ProjectExport'.
MainActivity.kt:191:37 Unresolved label.
MainActivity.kt:192:46 Unresolved reference 'filesDir'.
MainActivity.kt:209:50 Unresolved reference 'openContextSheet'.
MainActivity.kt:210:51 Unresolved reference 'closeContextSheet'.
```

Every other gate was green when this shipped, because `typecheck_kotlin.sh` replaced MainActivity
with a one-line stub and never compiled the real file.

### Root causes

| Symptom | Cause |
| --- | --- |
| `Unresolved reference 'ProjectExport'` | The new `ProjectExport` object was used but never imported. |
| `Unresolved label` + `Unresolved reference 'filesDir'` | `this@MainActivity` and `filesDir` used inside a composable lambda, where `this` is the lambda receiver, not the Activity. |
| `openContextSheet` / `closeContextSheet` | The two methods were added to `ChatViewModel`, then a later edit replaced the region they lived in and removed them silently. |

### What changed

1. **`typecheck_kotlin.sh` gained PASS 0**, which compiles the real `MainActivity.kt` together with
   the real ViewModels and data layer, stubbing only the `@Composable` screen functions. Screen
   signatures are generated from the source by `tools/gen_screen_stubs.py`, so they cannot drift.
2. **`tools/filter_kotlinc_log.py`** drops the one unavoidable artifact — an overload ambiguity on
   `viewModel()` that only exists because bare kotlinc cannot apply the Compose compiler plugin —
   and nothing else. Any other error fails the pass.
3. **`ProjectFile` moved from `ui/ProjectFilesScreen.kt` to `data/ProjectFile.kt`.** The pass
   exposed that a ViewModel was importing a Compose screen file for a data type.
4. The export lambda now resolves the Activity through `LocalContext`.

### Proof the gate works

Re-introducing the user's bugs into `MainActivity.kt` makes PASS 0 fail with the same diagnostics
the Gradle build produced. That was verified before this was called fixed.
