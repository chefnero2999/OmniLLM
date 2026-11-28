<div align="center">

# 🌌 OmniLLM Studio

### Autonomous On-Device & Keyless Cloud AI Agent Workstation for Android

[![Platform](https://img.shields.io/badge/Platform-Android%209%2B%20(API%2028%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue?style=for-the-badge&logo=arm&logoColor=white)](https://arm.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12.01-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Engine](https://img.shields.io/badge/Local%20Engine-llama.cpp-005CED?style=for-the-badge)](https://github.com/ggerganov/llama.cpp)
[![License](https://img.shields.io/badge/License-Apache%202.0-black?style=for-the-badge)](./LICENSE)

**Authored & Created by [chefnero2999](https://github.com/chefnero2999)**

[**Download Latest APK (v1.0.0)**](https://github.com/chefnero2999/OmniLLM/releases) • [**Architecture**](#-architecture--under-the-hood) • [**Key Features**](#-key-capabilities) • [**Model Fleet**](#-model-fleet) • [**Build from Source**](#-building-from-source)

---

</div>

## 🌟 Overview

**OmniLLM Studio** is an agentic AI workstation engineered specifically for Android. It turns simple sentences into working apps and provides conversational AI on models you never pay for.

- **Zero Accounts. Zero API Keys. Zero Subscriptions.**
- **Autonomous Coding Agent**: Generates multi-file codebases, runs web previews live on your device, and exports complete `.zip` archives.
- **Dual Execution Engine**: Dynamically routes between keyless, public cloud endpoints (OVHcloud, LLM7, Pollinations) and 100% offline, zero-quota on-device quantized models (`llama.cpp` ARM64 SIMD).

```
"Build a split-bill calculator with interactive tip sliders"
                           ↓
        Agent plans → writes HTML/CSS/JS in sandbox
                           ↓
        Live interactive WebView preview renders on-device
                           ↓
        One-tap ZIP export or continuing refinement
```

---

## 📸 App Screenshots (Real Device — v3.3.0)

<p align="center">
  <img src="docs/screenshots/v330_workspace.png" width="210" alt="Workspace" />
  <img src="docs/screenshots/v330_forge.png" width="210" alt="Forge" />
  <img src="docs/screenshots/v330_vault.png" width="210" alt="Vault" />
  <img src="docs/screenshots/v330_core.png" width="210" alt="Core" />
</p>

---

## ⚡ Key Capabilities

### 🤖 1. Autonomous Agent Loop
- **Multi-Turn Tool Calling**: Equips models with file tools (`write_file`, `read_file`, `list_files`, `delete_file`, `fetch_url`).
- **Resilient Sandboxing**: Enforces path containment inside `filesDir/projects/<slug>`, strictly blocking path traversal attempts (`..`) and absolute path leaks.
- **Mid-Task Resume**: State checkpoints saved to `.omni/build-state.json` on every step. Interrupt or hit turn limits anytime and resume where you left off.
- **Live Progress Counter**: Real-time turn tracking (`Step X of max 24`) displaying categorized agent activities (thoughts, file writes, actions, waits).
- **Interactive WebView Preview**: Web projects render immediately on an embedded sandboxed WebView with console error logging.
- **Project Management**: Built-in file browser, code editor, deletion controls, and clean `.zip` packaging.

### 💬 2. Isolated Chat Experience
- **Dedicated Thread Per Model**: Every model keeps its own conversation state keyed by `providerId|modelId`. Contexts never bleed into each other.
- **Rich Markdown Formatting**: Real-time rendering of **bold**, *italics*, inline `` `code` ``, and multiline ` ``` ` code blocks.
- **Long-Press Quick Copy**: Instant clipboard copying with feedback.
- **In-Chat Full-Text Search**: Filter through chat history with immediate query highlights and count badges.
- **One-Tap Conversation Export**: Share complete transcripts directly to any app via native Android `Intent.ACTION_SEND`.
- **Context Bridging**: Pull cross-model historical context into active sessions without corrupting thread isolation.
- **Live Metrics**: Token generation speeds (`tok/s`), context window consumption meters, and instant mid-stream cancellation.

### 🎨 3. UI & Personalization
- **Theme Engine**: Seamless toggle between **System Default**, **Dark Mode**, and **Light Mode**.
- **Panic Reset Safe-Switch**: Drops overcommitted KV cache state and restores conservative CPU parameters in one click.

---

## 🏗 Architecture & Under the Hood

```
app/src/main/java/com/omni/llm/
├── agent/
│   ├── AgentEngine.kt             # 24-turn autonomous tool loop & state compaction
│   ├── AgentPrompts.kt            # Structured scaffolding prompts
│   ├── AgentToolParser.kt         # Fenced ```tool block parser
│   └── ProjectSandbox.kt          # Filesystem containment & path security
├── data/
│   ├── KeylessCloudRouter.kt      # Dynamic failover routing, rate-limit backoff
│   ├── ModelCatalog.kt            # Manifest for cloud & local GGUF models
│   ├── ModelRepository.kt         # GGUF storage & download integrity
│   └── SettingsManager.kt         # Jetpack DataStore persistence
├── core/
│   ├── LlamaBridge.kt             # JNI bridge to libomnillm.so
│   └── omni_engine.cpp            # Native llama.cpp inference pipeline
└── ui/
    ├── AgentScreen.kt             # Activity pane & interactive WebView preview
    ├── ChatScreen.kt              # Formatted markdown, search & share topbar
    └── SettingsScreen.kt          # Hardware diagnostics & creator credits
```

---

## 🚀 Model Fleet

### Keyless Cloud Models (No Account Needed)
| Provider | Highlights | Best For |
| :--- | :--- | :--- |
| **OVHcloud** | DeepSeek V4.1 Flash (1M ctx), Qwen 3.6 35B, Gemma 4 26B | High throughput, verified tool-calling agent lane |
| **LLM7** | GPT-OSS 120B/20B, Kimi K2.7 Code, Codestral | Complex reasoning and code refactoring |
| **Pollinations** | GPT-OSS 20B, Sana Image | Anonymous failover & text-to-image generation |

### Local On-Device Models (100% Offline & Limitless)
Runs directly on phone hardware with zero data leaving the device:
- **Everyday & Coding**: LLaMA 3.2 1B / 3B, Qwen 2.5 1.5B / 3B
- **Uncensored & Abliterated**: Refusal-free quantized weights (`Llama 3.2 1B/3B Abliterated`, `Qwen 2.5 1.5B Abliterated`, `Dolphin 3.0 Llama 3.2 3B`)

---

## 🛠 Building from Source

### Requirements
- **JDK 17+** (Recommended: Eclipse Adoptium Temurin 17)
- **Android SDK** (API 28+ / Android 9.0 Pie or newer)
- Target architecture: **`arm64-v8a`**

```bash
# 1. Clone the repository
git clone https://github.com/chefnero2999/OmniLLM.git
cd OmniLLM

# 2. Specify Android SDK path in local.properties
echo "sdk.dir=C:\\Users\\<YourUser>\\AppData\\Local\\Android\\Sdk" > local.properties

# 3. Compile Debug APK
./gradlew assembleDebug
```

Output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License & Attribution

Distributed under the **Apache 2.0 License**. See [LICENSE](./LICENSE) for details.

Developed with ❤️ by **[chefnero2999](https://github.com/chefnero2999)**.

