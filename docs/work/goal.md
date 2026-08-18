# Current goal

- Status: current
- Updated: 2026-08-18
- Purpose: define the single active outcome and its acceptance boundary.

## Current incident extension

The current acceptance target additionally includes the host conversation failure reported as HTTP 502 `No tool call found for function call output...` and HTTP 400 `Upstream request failed`. The target is met only when the malformed host history is proven, the module repairs it at the outbound JSON boundary without host persistence/state hooks, the affected conversation succeeds on the installed module, and the Chinese Markdown report is current.

## Outcome

Ship a cache-focused LSPilot LSPosed module that adapts conservatively to the current host and future obfuscation changes without retaining any context-compression business logic, UI entry, or setting.

## In scope

- Prompt cache keys, explicit cache breakpoints, compatible retention, usage reporting, and reasoning effort.
- Structural `DexFile` request/SSE endpoint discovery with ambiguity rejection.
- ABI descriptor caching by host APK and split-APK content hashes; changed content invalidates the cache before hooks and triggers structural DEX self-adaptation.
- Read-only reverse engineering of host APKs and runtime evidence.
- Host-native Miuix module settings entry/page, separate from `关于`, with no module-created Overlay/Dialog.

## Out of scope

- Context compression, summaries, compacted baselines, compression status rows, chat overlays, or compression settings.
- Automatic retry, host message-list replacement, or host repository persistence.
- Patching, rebuilding, signing, or installing the host APK.
- Guessing hook endpoints from strings alone.

## Done when

- Production source, UI, settings, tests, and current documentation contain no active context-compression feature.
- The updated installed host APK SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c` (still version `1.1.0 (11)`) resolves provider `zj8` with the retained request/SSE group; the staged `b6ea30f6...debe` and prior `d4eb3066...f5d56` profiles remain compatibility evidence.
- The module entry is an independent host-native settings card using `Rounded.AutoAwesome`; its page uses host navigation and native preference components.
- Ambiguous or incomplete scans disable only the affected feature group.
- Focused checks, release build, lint, and `git diff --check` pass.
- The host tool-call failure is documented in Chinese at `docs/work/host-context-truncation-diagnosis.md`, including the exact confirmed 502 evidence, the bounded uncertainty around 400, repair rules, and live verification.
- `ToolCallSanitizerCheck` passes on Dalvik, the final installed artifact logs `Tool-call context repaired changes=12` in the affected conversation and succeeds, and the host database remains intact and unmodified by the module.
- Stable module `1.7.5 (67)` is hash-verified, installed, and startup-verified against the current host after the repair; source, APK, runtime, and documentation audits have no unresolved required item.
