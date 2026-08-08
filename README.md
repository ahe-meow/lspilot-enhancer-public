# LSPilot Enhancer

An LSPosed module for `me.yun.lspilot` that adds request-level enhancements and chat context-compression controls without modifying or repackaging the target app.

> This repository contains source code only. It intentionally excludes local SDKs, build outputs, APKs, logs, device-specific files, signing material, and user credentials.

## Features

- Adds OpenAI-compatible request enhancements such as prompt cache key generation, optional usage reporting, and extended retention for supported models.
- Adds a route-scoped native compression icon that appears only on the AI chat screen.
- Supports manual and pre-send chat context compression using the selected provider configuration at runtime.
- Inserts local system status messages for compression progress and metrics, while filtering those status messages out of provider requests.
- Tracks compression application and provider usage without logging message bodies, API keys, or raw request payloads.

## Current Behavior

Context compression is designed to avoid mutating an active model response:

- If the user sends a message and compression is needed, the module pauses the send, compresses while the chat is idle, then replays the same send.
- During provider request construction, the module only applies a previously prepared summary or sends the sanitized original messages.
- A prepared summary remains active for every provider request in the same model response, then clears when the chat leaves loading state.

## Build

Requirements:

- JDK 17 for Gradle/Android Gradle Plugin
- Android SDK Platform 29+
- Android Build Tools compatible with the project configuration

Build a release APK:

```bash
bash ./gradlew :app:assembleRelease --no-daemon --console=plain -x lintVitalRelease
```

The module uses `lib/libxposed-api-102.0.0.aar` as a `compileOnly` dependency.

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