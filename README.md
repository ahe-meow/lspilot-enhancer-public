# LSPilot Enhancer

An LSPosed module for `me.yun.lspilot` that adds request-level enhancements and chat context-compression controls without modifying or repackaging the target app.

> This repository contains source code only. It intentionally excludes local SDKs, build outputs, APKs, logs, device-specific files, signing material, and user credentials.

## Features

- Adds OpenAI-compatible request enhancements such as prompt cache key generation, optional usage reporting, extended retention, and selectable GPT-5.6 sol reasoning effort.
- Automatically retries failed response retrieval up to five times at bounded intervals, with in-chat status notices and native stop-button cancellation.
- Adds a route-scoped native compression icon that appears only on the AI chat screen.
- Supports manual and pre-send chat context compression using the selected provider configuration at runtime.
- Inserts local system status messages for compression progress and metrics, while filtering those status messages out of provider requests.
- Tracks compression application and provider usage without logging message bodies, API keys, or raw request payloads.
- Resolves minified host ABI members with DexKit after host/module updates, then reuses a version-fingerprinted descriptor cache on normal starts.

## Current Behavior

Context compression is designed to avoid mutating an active model response:

- If the user sends a message and compression is needed, the module pauses the send, compresses while the chat is idle, then replays the same send.
- During provider request construction, the module only applies a previously prepared summary or sends the sanitized original messages.
- A prepared summary remains the chat baseline across responses until the chat or provider changes, or the baseline is explicitly cleared.

## Design References

The context-compression workflow was informed by the mature Android AI-agent patterns in:

- [AAswordman/Operit](https://github.com/AAswordman/Operit), particularly model-assisted conversation summaries and structured context handoff.
- [AAAelina/rikkahub-agent](https://github.com/AAAelina/rikkahub-agent), an extended fork of [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent), particularly configurable compression prompts, context budgeting, recent-tail retention, and tool-transaction boundaries.

These are architectural and behavioral references, not copied source. The current deterministic local window/excerpt implementation in `ContextCompression.java` is an independent Java adaptation for LSPilot's runtime request path.

## Build

Requirements:

- JDK 17 for Gradle/Android Gradle Plugin
- Android SDK Platform 29+
- Android Build Tools compatible with the project configuration

Build a release APK:

```bash
bash ./gradlew :app:assembleRelease --no-daemon --console=plain -x lintVitalRelease
```

The module uses `lib/libxposed-api-102.0.0.aar` as a `compileOnly` dependency and `org.luckypray:dexkit:2.2.0` for update-triggered runtime ABI discovery. DexKit is distributed under Apache-2.0 with its native Core under LGPL-3.0; upstream source and license texts are available at [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit).

## Installation

1. Build or install the module APK.
2. Enable it in an LSPosed environment that supports libxposed API 102.
3. Scope it to `me.yun.lspilot`.
4. Fully restart the target app process.
5. Check LSPosed logs for `LSPilotEnhancer loaded` and hook installation messages.

## Safety Notes

- No API keys, tokens, credentials, logs, APK outputs, or local SDK files are intended to be committed.
- Runtime provider keys are read from the target app's existing provider configuration and are not stored in this repository.
- Diagnostic logs are designed to avoid message bodies, request bodies, and credentials.

## Project Layout

```text
app/src/main/java/dev/operit/lspilot/enhancer/  Module source
app/src/main/resources/META-INF/xposed/         LSPosed metadata
docs/                                           Public design notes
lib/                                            Public compile-only dependency
gradle/                                         Gradle wrapper
```

## Disclaimer

This is an independent compatibility module for personal research and interoperability work. Use it only where you are authorized to run LSPosed modules and inspect/modify runtime behavior.