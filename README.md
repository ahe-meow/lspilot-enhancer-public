# LSPilot Enhancer

An LSPosed module for `me.yun.lspilot` that adds request-level caching, usage reporting, reasoning controls, and bounded automatic retries without modifying or repackaging the host app.

> This repository contains source code only. It intentionally excludes local SDKs, build outputs, APKs, logs, device-specific files, signing material, and user credentials.

## Features

- Generates stable, non-plaintext `prompt_cache_key` values from the model and system/developer prompt.
- Adds explicit prompt-cache breakpoints where supported, with a compatible 24-hour retention fallback.
- Requests streaming usage data and records redacted cache-token metrics.
- Adds selectable GPT-5.6 sol reasoning effort and normalizes compatible reasoning SSE fields.
- Retries failed responses up to five times with bounded delays and native stop-button cancellation.
- Resolves minified host endpoints with conservative `DexFile` structural discovery and rejects ambiguous candidates.
- Detects host APK content changes at startup, invalidates stale ABI mappings, and re-adapts before installing cache hooks.
- Caches validated ABI descriptors by host APK and split-APK content hashes.

## Compatibility

The host APK is read-only compatibility evidence. Known descriptors are validated before use; unknown or changed hosts are scanned at startup. A feature stays disabled when its endpoint graph is missing or ambiguous instead of installing a speculative hook.

The maintained project state is indexed in [docs/README.md](docs/README.md).

## Build

Requirements:

- JDK 17
- Android SDK Platform 29+
- Android Build Tools compatible with the project configuration

Build a release APK:

```bash
bash ./gradlew :app:assembleRelease --no-daemon --console=plain -x lintVitalRelease
```

The module uses `lib/libxposed-api-102.0.0.aar` as a `compileOnly` dependency. Runtime discovery uses Android's platform `dalvik.system.DexFile`; the module does not package a second DexKit runtime into the host process.

## Installation

1. Build or install the module APK.
2. Enable it in an LSPosed environment that supports libxposed API 102.
3. Scope it to `me.yun.lspilot`.
4. Fully restart the host process.
5. Check LSPosed logs for the resolved ABI groups and installed hooks.

## Safety

- Do not commit API keys, tokens, credentials, logs, APK outputs, local SDK files, or signing material.
- Runtime provider credentials remain owned by the host and are never persisted by this module.
- Diagnostics avoid message bodies, request bodies, and credentials.
- The module never edits the host APK or manipulates its database directly; retry persistence uses the host repository API.

## Project layout

```text
app/src/main/java/                      Module source
app/src/main/resources/META-INF/xposed/ LSPosed metadata
docs/                                   Project documentation and current work state
lib/                                    Public compile-only API dependency
gradle/                                 Gradle wrapper
```

## Disclaimer

This is an independent compatibility module for personal research and interoperability work. Use it only where you are authorized to run LSPosed modules and inspect runtime behavior.
