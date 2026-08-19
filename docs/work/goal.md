# Current goal

- Status: current
- Updated: 2026-08-19
- Purpose: define the single active outcome and its acceptance boundary.

## Current feature branch

This branch adds an opt-in host-history retention Hook. It intercepts `repository.b.r(chatId, currentList)`, verifies that `repository.b.i(chatId)` returned the same number of rows as `c7.o(chatId)`, merges persisted messages with the current UI list by message ID, and passes the complete list to the host's existing save path. If ABI discovery, database verification, parsing, or message IDs are ambiguous, the Hook skips the destructive save. The native settings page exposes `保留历史消息`, enabled by default and disabled only when the Hook is unavailable. This is targeted history preservation, not context compression or host message-state replacement.

## Current release gate

The user explicitly accepted the installed `v1.8.0 (68)` release candidate on 2026-08-19. The reliability review and build gates passed, so runtime acceptance is complete; exact artifact and verification evidence is recorded in [findings.md](findings.md).

Formal GitHub Release `v1.8.0` is published and online at <https://github.com/ahe-meow/lspilot-enhancer-public/releases/tag/v1.8.0> as a non-draft, non-prerelease release. Tag `v1.8.0` points to commit `5e62d81`, which is pushed on `feature/host-history-retention-hooks`; `main` was not merged or changed. The named `lspilot-enhancer-v1.8.0.apk` asset was downloaded and verified through the GitHub API endpoint against the accepted candidate; the exact size and SHA-256 are canonical in [findings.md](findings.md). The browser-style asset URL briefly returned HTTP 404 during propagation, but the API asset was valid, so publication is complete.

## Remaining next action

Replace the private AAPT2 APK-container workaround when a compatible AGP 9.3 arm64 AAPT2 becomes available.

## Current incident extension

The current acceptance target additionally includes the host conversation failure reported as HTTP 502 `No tool call found for function call output...` and HTTP 400 `Upstream request failed`. The target is met only when the malformed host history is proven, the module repairs it at the outbound JSON boundary without host persistence/state hooks, the affected conversation succeeds on the installed module, and the Chinese Markdown report is current.

The read-only incident extension also covers the host chat UI symptom “历史加载不出来”: distinguish guard rejection, query failure, hidden successful loading, and stale in-flight state with exact runtime evidence; determine whether the 76-row end is a database fact or pagination defect; reconstruct the separate load/request/save/content-truncation rules; then remove all temporary telemetry and restore the stable module.

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
- Automatic retry, host message-list replacement, and unguarded host repository persistence. The targeted opt-in history-retention guard on this feature branch is in scope.
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
- The 76-row UI pagination trace captures valid guards, two successful `repository.b.o` queries, state growth `30 → 60 → 76`, cursor movement, and marker clearing; temporary telemetry is absent from source and the installed stable APK.
- The feature-branch history-retention Hook is statically verified, installed as a module-only candidate, and manually tested with the switch enabled plus non-destructive switch persistence while disabled. Enabled mode preserves rows outside the current UI page across ordinary saves, a pagination-followed-by-save, a generation-stop save, and host re-entry; the target chat grew `76 → 98 → 110 → 112 → 116` with all prior IDs retained, no duplicates, and no parse errors. Disabled mode leaves the host path at direct `chain.proceed()` and is not exercised with a destructive real long-chat write. The module-settings sentinel route does not reuse host `Route.LogViewer`, and back navigation returns to host settings.
- Accepted `v1.8.0 (68)` source and documentation are committed and pushed on the feature branch; tag `v1.8.0` is created and pushed from that branch without merging `main`; the named GitHub Release APK is published and its online asset matches the accepted candidate. Exact artifact evidence is canonical in [findings.md](findings.md).
- Documentation distinguishes session-entry `LIMIT 30`, current-state request serialization, cursor pagination, seven full-list replacement paths, and tool-content character caps without claiming a provider-level fixed 30-message or token-budget truncation.
