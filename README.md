# OmniLLM

**An agentic AI app for Android that turns a sentence into a working app — and runs on models you never pay for.**

No account. No API key. No subscription. Type what you want, press Build, and a model writes the files, runs them, and shows you the result on your phone.

```
"a tip calculator with a bill split slider"
        ↓
   Build tab → model writes 4 files → preview loads → export .zip
```

---

## What actually happens under the hood

Most "AI apps" are a text box in front of one API. OmniLLM has three moving parts:

| Part | What it does | Where it lives |
| --- | --- | --- |
| **Cloud router** | Sends your request to a free, keyless model. Rotates between providers when one fails. | `data/KeylessCloudRouter.kt` |
| **Local engine** | Runs GGUF models entirely on the phone via a bundled llama.cpp build. | `core/` + `data/ModelRepository.kt` |
| **Agent loop** | Gives the model tools (write a file, read a file, run a shell command) and loops until the task is done. | `agent/AgentEngine.kt` |

### The agent loop, honestly described

The model is not given a magic "build me an app" button. It is given four tools and a loop:

```
1. Model asks:      write_file(path="index.html", content="<html>…")
2. App executes it: file lands in filesDir/projects/<slug>/
3. App reports:     "wrote index.html (1.2 KB)"
4. Repeat          until the model stops asking, or 40 turns pass
```

Every write goes through a sandbox that refuses `..` traversal and absolute paths. The model cannot escape its own project folder, and neither can you — which is why the file browser exists.

---

## The four tabs

### Chat
One conversation per model, and they never mix. Pick a model, and that model keeps its own history under its own thread key. Switch to another model and you get a blank thread — switch back and your old conversation is exactly where you left it. Each model is effectively its own container.

Every reply has **retry**, **stop mid-generation**, and a live **tokens/second** readout, because "it feels slow" is not actionable feedback.

### Build
Describe an app, pick the model that writes it, watch the agent work. The Build tab shows each step — thought, tool call, file written, error — as it happens, and the preview pane reloads as files change.

New in this version:

- **Resume, not restart.** Build state is checkpointed to `.omni/build-state.json` after every turn. If a build dies at step 12, it picks up at step 12.
- **Auto model switching.** When a free model hits its daily quota or rate limit, the build continues on the next available model with the files already on disk — mid-task, not from scratch.
- **Full file access.** A **Files** button opens every artifact the agent produced: view, edit, delete, or export the whole project as a `.zip` through the system share sheet.

### Models
Local GGUF models, categorised, with real specs and verified SHA-256 hashes. Download over Wi-Fi, switch when you want privacy or no internet.

### Settings
Everything that affects generation: context length, GPU layers, threads, sampling, system prompt. Plus the two toggles that matter most here:

- **Auto-switch models** — on by default.
- **Unrestricted mode** — see below.

---

## Free, keyless models

OmniLLM talks to providers that serve requests without an account. There is no key to paste because there is no key:

| Provider | Models | Reality |
| --- | --- | --- |
| **OVH AI Endpoints** | DeepSeek, Qwen, Gemma, Nemotron | The reliable lane. Zero-price models, real tool-calling. |
| **LLM7** | 80+ models | A **daily token quota**, shared globally. Expect `429` by the afternoon. |
| **Pollinations** | Text + image | Best-effort anonymous access. Image generation works and is watermarked. |

> **Be realistic about free.** These are genuinely free and genuinely limited. Quotas reset, endpoints disappear, and a model that worked yesterday may be rate-limited today. That is exactly why auto-switching exists: instead of showing you an error, the app moves to the next working model and keeps going.

Models that need an account are listed in the picker under **Needs an account**, greyed out and unselectable. They are shown so the gap is visible, not faked.

---

## Uncensored and unrestricted

Two different things, often confused:

**Unrestricted mode** (Settings) removes OmniLLM's *own* hedging from the system prompt and puts the least-restrictive lanes first in the picker. It cannot remove a cloud provider's server-side moderation — that filter runs before the text reaches your phone, and no client setting can turn it off. Anyone who claims otherwise is selling something.

**Uncensored models** (Models tab → Uncensored) are abliterated local weights: the refusal direction is removed from the model itself. Four are included, from 0.96 GB up. Nothing leaves the device, so there is nothing to filter:

- Llama 3.2 1B Abliterated
- Qwen 2.5 1.5B Abliterated
- Llama 3.2 3B Abliterated
- Dolphin 3.0 Llama 3.2 3B

That is the only honest way to get a truly unfiltered model on a phone.

---

## Running the local models

No hard blocks. Every local model in the catalogue can be loaded, even one that probably will not fit. You get a warning and the choice, because a warning you can ignore is more useful than a wall you cannot pass.

| Setting | Default | Why |
| --- | --- | --- |
| GPU layers | **0** | Vulkan offload is the most common cause of a hard crash. Opt in when you want it. |
| Batch size | 256 | Halves peak memory during prompt processing versus 512. |
| Context | 2048–4096 | Sized to your device's RAM tier. |
| `mmap` | on | Lets the OS page the model in instead of demanding it all at once. |
| `mlock` | **off** | Pinning a whole model in RAM invites the low-memory killer. |

If a model fails to load, the Settings screen has a **device defaults** reset and a **panic** button that unloads everything and drops back to safe values.

---

## Building from source

Requirements: JDK 17, Android SDK (API 34), NDK if you rebuild native code.

```bash
./gradlew assembleDebug          # build
./build-apk.sh                   # signed release APK
```

The bundled native library is prebuilt:

```
core/src/main/jniLibs/arm64-v8a/libomnillm.so   26 MB, arm64-v8a
```

`arm64-v8a` only. The Vulkan backend is compiled in and will not run on x86 emulators — use a physical device.

### Checks that run without a device

```bash
./tools/typecheck_kotlin.sh      # parse + resolve + type-check every file
./tools/run_unit_tests.sh        # 48 tests, no network
python3 tools/audit_wiring.py    # Compose call sites match screen signatures
python3 tools/audit_dead_ui.py   # no button wired to an empty lambda
```

`audit_dead_ui.py` exists because a control that renders and does nothing is indistinguishable from a working one until someone taps it. It caught five such controls during development.

---

## Project layout

```
app/src/main/java/com/omni/llm/
├── agent/          Agent loop, tool parser, project sandbox
├── data/           Cloud router, model catalogue, chat store, settings, build state
├── ui/             Compose screens + view models
└── MainActivity.kt 4-tab shell
core/               Native llama.cpp wrapper (JNI)
tools/              Typecheck, tests, audits
```

---

## Known limits

Stated plainly, because a README that only lists wins is marketing:

- **Free tiers expire.** A provider can change its terms overnight. Auto-switching softens this; it does not remove it.
- **Agent builds are not Claude Code.** On a 7B-class model, expect a working single-page app, not a full-stack repo. Bigger cloud models do better.
- **No iOS, no desktop.** Android, arm64.
- **Local models are slow on old phones.** A 3B model on a 4 GB device produces a few tokens per second. That is the hardware, not the app.

---

## License

MIT. See [LICENSE](LICENSE).

Bundled model weights carry their own licenses — Llama 3.2 uses the Llama 3.2 Community License, Qwen 2.5 is Apache-2.0. The Models tab shows each one before you download.
