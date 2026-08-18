# Project handoff

- Status: current
- Updated: 2026-08-18
- Purpose: let the next agent resume without reconstructing host identity or removed feature scope.

## Repository

- Branch: `main`
- Stable release commit/tag: `23bbcbbe476cb2685d1f1d57121af480993ee049` / `v1.7.4`
- Inspect `git status` before changes and preserve unrelated tracked or untracked files.
- Module package: `com.lspilot.enhancer`
- Host package: `me.yun.lspilot`
- Android treats the module identity as a new package. GitHub pre-release `v1.7.4-preview.27` is published, installed, enabled, scoped, and hash-verified.
- Installed `v1.7.4-preview.27` has the first host-native Miuix settings implementation; user testing found its hook targeted legacy `a2.f` instead of active Miuix `t1.J`, and its card was appended after the host bottom safe-area item.
- Current stable module: `com.lspilot.enhancer` `1.7.5 (67)`, installed and hash-verified at `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01` (679,443 bytes). It is the repaired request-boundary build; the public `v1.7.4` release remains historical and is not the installed artifact.

## Active objective

Keep only request caching, usage, reasoning, diagnostics, and settings UI. Context compression and automatic retry—including host message-state replacement and repository persistence—are removed from current source.

Preserve conservative request/SSE DEX adaptation, unique coherent candidate selection, ambiguity failure, and host-content-hash descriptor caching.

## Active incident handoff

- Diagnosis: the host's fixed 30-message context window can start with four orphan `role=tool` outputs. `va.x` only adds cancellation outputs for declared-but-missing calls; it does not remove orphan outputs. `zj8.p` serializes the malformed sequence, producing the reported 502. The 400 wrapper is high-probability related but lacks a preserved request body for direct proof.
- Fix: `app/src/main/java/com/lspilot/enhancer/ToolCallSanitizer.java` runs at the shared request JSON boundary in both minified request hook paths. It removes invalid tool fragments without touching host persistence/UI/retry behavior.
- Live evidence: the original affected chat accepted `ping` on the final installed artifact; PID 9774 logged `Tool-call context repaired changes=12`, normal request enhancement, and completed usage (`35374` input / `742` output / `36116` total) with no 400/502/upstream error. The host DB stayed `integrity_check=ok`.
- Formal GitHub Release `v1.7.5` is published non-draft/non-prerelease at <https://github.com/ahe-meow/lspilot-enhancer-public/releases/tag/v1.7.5>; named asset hash matches the installed APK.
- Host chat UI history diagnosis is complete: it uses `ka$c -> va.E() -> va$e -> repository.b.o(chatId, oldestRowId, 30)`. Current DB/WAL/SHM replay confirms raw/visible/tool page counts `30/7/23`, `30/3/27`, and `16/2/14`. User-authorized temporary module-only telemetry in PID `32576` captured valid guards, queries returning `30` then `16` rows, state growth `30 → 60 → 76`, cursor movement `1015862 → 1015832 → 1015816`, `hasMoreOlder=false` at the end, and marker clearing after viewport restoration to `firstIndex=9/10`. The concrete current cause of “loads nothing” is hidden tool rows plus anchor-preserving scroll restoration, not guard rejection, DB failure, or a stale marker. Temporary telemetry was removed, never published, and the exact stable APK was restored.

## Current host

- Installed SHA-256: `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c` (current host update)
- Size: `16,716,410` bytes
- MT2 workspace: `enml4cuy`
- Earlier installed host SHA-256: `d4eb3066...f5d56`, size `16,700,026`, workspace `rvlxvm8q`

Verified request/SSE mappings used by current source:

```text
request: zj8.p(cb, List, String, boolean) -> String
SSE:     zj8.t(String, Function1) -> boolean
```

The host's `va` ViewModel, retry/session/state/message and repository mappings are historical evidence only; current source no longer discovers, caches, or hooks them.

The current update resolved provider `zj8`; the previous `d4eb3066...f5d56` host used `xj8`, and the earlier `b6ea30f6...debe` host used `vj8`. The resolver now prefers structural DEX discovery on a content change and caches the validated descriptor afterward.

## Completed in this work unit

- Stopped the unfinished full-history compression work.
- Read-only verified and staged the installed host APK; the host APK and database were not modified.
- Removed compression-only production classes, tests, UI controls, settings, build task, dependency, and architecture document.
- Removed compression coupling from request, chat, send, stream, and automatic retry paths.
- Kept host chat UI unchanged and stopped writing retry status rows into chat history.
- Reduced ABI resolution and descriptor caching to unique coherent request/SSE candidates; removed retry capability discovery.
- Made unavailable-setting persistence host-local only; LSPosed remote preferences remain migration input and are not written.
- Completed source/package migration to `com.lspilot.enhancer` and verified `1.7.4-preview.24`; exact artifact and check evidence is recorded in `docs/work/findings.md`.
- Historical `preview.24` install: installed the exact APK through KernelSU RunCommandService; source, private stage, `/data/local/tmp`, installed `base.apk`, and release asset hashes matched `51e0c3c...928aa`.
- Historical `preview.24` runtime: verified LSPosed `enabled=1`, unblocked scope `me.yun.lspilot`, database integrity `ok`, then two cold starts ending in `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, and retry/settings hooks installed.
- Rejected the direct AGP output because the arm64 AAPT2 override produced a 14-entry ZIP without manifest/resources.
- Historical predecessor artifact: built and privately staged valid module APK `7bc3a22f6a43ef98cd843922c89a0b57ced474cc26583fdca0e4f2089d8c44e8` by replacing only `classes.dex` in a valid module container, then arm64 `zipalign` and `apksigner`.
- Historical predecessor install evidence: installed only the module; source/stage/install hashes matched. LSPosed config integrity was `ok`, module state was enabled, and scope included `me.yun.lspilot`.
- Historical predecessor startup evidence: runtime startup succeeded with `provider=xj8`, request/SSE probes true, stream/retry, cache, SSE, load-session, and auto-retry hooks installed. The previous ABI and read-only persistence errors did not recur.
- Historical predecessor release artifact/install evidence: built and installed release APK `e25102ded11e50e20c0b26f59cd1ad9bf16ceee19cbd24af67eeb41e276c589a` (697,819 bytes); source, staging, and installed hashes matched. APK structure, ZIP integrity, signature, and DEX checks passed.
- Historical predecessor runtime acceptance: `0813` logged `removed messages=24 compression=11 orphanTools=2 toolBlocks=11`, returned `pong`, and reported usage `39856` input / `34` output / `39890` total tokens.
- Detects host content changes at every `onPackageReady` startup, independent of version name/code.
- Invalidates stale descriptors before hook installation and runs structural `dalvik.system.DexFile` adaptation only for first start or changed host content.
- Historical predecessor final artifact/runtime evidence: module APK `69c25122d70e97bb506712ea3b611a463eef43fdf4f16105f6d13eedeb5a53a6` was staged through `/data/data/com.termux/files/usr/tmp` and `/data/local/tmp`; all hashes matched. Final startup logged `Host ABI resolution reason=cache_hit`, `provider=zj8`, `requestBody=true`, and `sseUsage=true`.
- Removed automatic retry manager/policy, retry/stop/session/stream/repository hooks, host StateFlow writes, repository message persistence, retry ABI discovery/cache fields, and retry-only tests.
- Published and installed `v1.7.4-preview.27`; source, GitHub download, Termux-private stage, `/data/local/tmp`, and installed `base.apk` match SHA-256 `24702f702ee67c6b37a5305f7f5e983f4523f7d2da18c4475c82609b16d2dff1`.
- Verified LSPosed `enabled=1`, `scope_request_blocked=0`, scope `me.yun.lspilot`, database integrity `ok`, module-update descriptor rebuild, subsequent `cache_hit`, request/SSE hooks, and AutoAwesome native-settings hooks.
- Replaced the old settings Overlay/Dialog with host-native Miuix navigation; the module entry is independent from `关于`, uses `Rounded.AutoAwesome`, and settings state is backed by host Compose `MutableState` bridges.
- Corrected `preview.28` release/lint/LSP/pi-lens/APK checks, `NativeSettingsNavigationCheck`, dedicated-route state checks, and exact current-host multi-DEX `HostNativeSettingsAbiCheck` pass. Installed candidate SHA-256 is `999d40e423cdbb5f1d0127fb27c8f7ba6f0211d70e8872717bfdcda87f381f44` (678,687 bytes).
- The module settings surface now uses a dedicated sentinel host route rather than reusing the host About route. Manual acceptance passed after alternating About and module entries in both orders following back navigation.
- The rejected first local `preview.28` candidate injected nodes from the host bottom-padding Composable and crashed Compose. The installed correction performs LazyColumn item registration instead; two corrected start captures contain no FATAL/Compose error.

## Next action

Replace the private AAPT2 APK-container workaround when an arm64-compatible AGP 9.3 AAPT2 is available.

## Constraints

- Never modify or install the host APK.
- Build/install only the module.
- Reject ambiguous DEX matches rather than choosing the first candidate.
- Preserve unrelated dirty files.
- Use JDK 17 and the Android build flags in `docs/project/constraints.md`.
- Manual UI acceptance must remain user-driven.
- Local install must precede user acceptance; user acceptance must precede commit, tag, push, or GitHub release. Commit and release may be performed together only after acceptance.
- Keep the AAPT2 container workaround private; replace it when an arm64-compatible AGP 9.3 AAPT2 is available.
