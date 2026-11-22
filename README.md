# OmniLLM

**An agentic AI app for Android that turns a sentence into a working app — and runs on models you never pay for.**

No account. No API key. No subscription. Type what you want, press Build, and a model writes the files, runs them, and shows you the result on your phone.

```
"a tip calculator with a bill split slider"
        ↓
   Build tab → model writes 4 files → preview loads → export .zip
```

---

## What Actually Happens Under The Hood

Most "AI apps" are a text box in front of a single proprietary paid API. OmniLLM operates on a decoupled multi-engine architecture:

| Component | Role | Source Location |
| :--- | :--- | :--- |
| **Keyless Cloud Router** | Dispatches requests across keyless, public inference endpoints (OVH, LLM7, Pollinations) with automated failover and quota retry backoff. | `data/KeylessCloudRouter.kt` |
| **Local Inference Engine** | Runs offline GGUF quantized models completely on-device via JNI bindings to `libomnillm.so` (`llama.cpp`). | `core/` + `data/ModelRepository.kt` |
| **Autonomous Agent Loop** | Provides autonomous multi-turn tool calling (`write_file`, `read_file`, `list_files`, `delete_file`, `fetch_url`) in an isolated sandbox. | `agent/AgentEngine.kt` |

### The Agent Loop, Honestly Described

The model is not given a magic "build me an app" button. It is given atomic filesystem tools and a continuous loop:

```
1. Model thought:   "Let's create the interactive UI in index.html first."
2. Model asks:      write_file(path="index.html", content="<html>…")
3. App executes it: File lands in filesDir/projects/<slug>/
4. App reports:     "wrote index.html (1.2 KB)"
5. Repeat:          Until model outputs finish() or reaches turn limit
```

Every write is protected by `ProjectSandbox`, which enforces path containment, blocking directory traversal (`..`) and absolute path leaks.

---

## Tab Guide & Latest Features

### 1. Chat Tab
- **Isolated Thread Per Model**: Every model keeps its own conversation thread keyed by `providerId|modelId`. Switching models gives a dedicated workspace; switching back restores your conversation untouched.
- **Context Bridging**: Cross-reference past cloud conversations into your current chat session without mixing threads.
- **Rich Markdown & Syntax Highlighting**: Clean rendering of **bold**, *italics*, inline `` `code` ``, and multiline ` ``` ` code blocks.
- **Long-Press Copy**: Hold down on any message bubble to copy its contents immediately.
- **In-Chat Search**: Instant substring message filter with active result counts and one-tap clear.
- **Conversation Sharing**: Export full transcripts via Android's native share sheet (`Intent.ACTION_SEND`).
- **Live Performance Metrics**: Real-time generation speed (`tokens/second`), context memory meter, and instant generation stop.

### 2. Build Tab
- **Resume Capability**: Real-time state checkpoints to `.omni/build-state.json`. If a build pauses or runs out of turns, tap Resume to continue exactly where it stopped.
- **Auto Model Switching**: When a free cloud model exhausts its rate-limit or quota, the build automatically switches to the next agent-ready model and resumes mid-task.
- **Live Step Progress Indicator**: Displays turn counters (`Step X of max 24`) and detailed step categories (thought, action, write, wait, error).
- **Interactive WebView Preview**: Web projects (`HTML`, `JS`, `CSS`) load directly inside the embedded WebView preview with console error reflection.
- **Full Project Management**: Inspect file trees, view, inline edit, delete individual project files, delete all scratch projects, or export full ZIP archives.
- **Text-to-Image Generation**: Keyless image generation using Sana.

### 3. Models Tab
- **Offline GGUF Inference**: Download verified open-weight models directly onto device storage.
- **Uncensored / Abliterated Local Models**: Dedicated category featuring refusal-free quantized models (`Llama 3.2 1B/3B Abliterated`, `Qwen 2.5 1.5B Abliterated`, `Dolphin 3.0 Llama 3.2 3B`) running 100% on-device with zero network calls.
- **Storage & RAM Aware**: Calculates KV cache footprint and dynamically verifies whether models fit safely in device memory.

### 4. Settings Tab
- **Theme Selection**: Switch between **System Default**, **Dark Mode**, and **Light Mode** anytime.
- **Crash Recovery & Panic Button**: Safe fallback resetting model threads and CPU-only parameters in case of memory overcommitment.
- **Inference Knobs**: Context length, GPU layer offloading (Vulkan), batch sizes, thread allocation, sampling temperatures, and customizable system prompts.

---

## Free & Keyless Model Fleet

OmniLLM routes requests to providers serving open models without API keys or accounts:

| Provider | Model Families | Characteristics |
| :--- | :--- | :--- |
| **OVH AI Endpoints** | DeepSeek V4.1 Flash, Qwen 3.6 35B, Gemma 4 26B, Nemotron 3 | High throughput, reliable tool-calling agent lane. |
| **LLM7** | GPT-OSS 120B/20B, Kimi K2.7 Code, Codestral, GLM 5.3 | Large variety; shared quota tier. |
| **Pollinations** | GPT-OSS 20B, Sana Image | Anonymous failover endpoint. |
| **Local Engine** | LLaMA 3.2, Qwen 2.5, Dolphin, DeepSeek (GGUF) | 100% offline, private, zero limits, zero rate caps. |

> **Fair Expectations on Free Cloud**: Public no-key endpoints share bandwidth across the internet. If an endpoint encounters rate-limiting (HTTP 429), OmniLLM's automatic router pauses briefly and pivots to the next compatible model so your session stays uninterrupted.

---

## Building from Source

### Requirements
- **JDK 17+**
- **Android SDK** (API 28+ / Android 9.0 Pie or newer)
- Target architecture: **`arm64-v8a`** (standard 64-bit Android)

### Quick Build (CLI)
```bash
# Clone the repository
git clone https://github.com/your-username/OmniLLM.git
cd OmniLLM

# Ensure local.properties points to your Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build Debug APK
./gradlew assembleDebug
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## License
Open source under the [Apache 2.0 License](LICENSE).
