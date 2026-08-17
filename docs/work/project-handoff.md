# Project handoff

- Status: current
- Updated: 2026-08-17
- Purpose: let the next agent resume without reconstructing host identity or removed feature scope.

## Repository

- Branch: `main`
- Release commit/tag: `2e44ff1` / `v1.7.4-preview.26`
- Inspect `git status` before changes and preserve unrelated tracked or untracked files.
- Module package: `com.lspilot.enhancer`
- Host package: `me.yun.lspilot`
- Android treats the module identity as a new package. GitHub pre-release `v1.7.4-preview.26` is published, installed, enabled, scoped, and hash-verified.
- Current source and installed `v1.7.4-preview.26` apply reasoning effort and 24-hour retention to every non-empty model name, and remove automatic retry/context writes.

## Active objective

Keep only request caching, usage, reasoning, diagnostics, and settings UI. Context compression and automatic retry—including host message-state replacement and repository persistence—are removed from current source.

Preserve conservative request/SSE DEX adaptation, unique coherent candidate selection, ambiguity failure, and host-content-hash descriptor caching.

## Current host

- Installed version: `1.1.0 (11)`
- Installed SHA-256: `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c` (content changed from the prior `d4eb3066...f5d56` while version remained `1.1.0 (11)`)
- Size: `16,700,026` bytes
- MT2 workspace: `rvlxvm8q`
- Earlier staged profile: `b6ea30f6...debe`, workspace `g66o2a9l`

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
- Published and installed `v1.7.4-preview.26`; source, GitHub download, Termux-private stage, `/data/local/tmp`, and installed `base.apk` match SHA-256 `7051672f6a2335927abf3cc5bb1e4af7ec70f897bec921a9e7553fb564397e73`.
- Verified LSPosed `enabled=1`, `scope_request_blocked=0`, scope `me.yun.lspilot`, and database integrity `ok` without modifying LSPosed data.
- Verified two cold starts: schema `7` rebuild then `cache_hit`, `provider=zj8`, request/SSE/settings hooks installed, version `1.7.4-preview.26 (63)`, and no automatic-retry hook log.

## Next action

Manual request/usage/settings acceptance remains user-driven.

## Constraints

- Never modify or install the host APK.
- Build/install only the module.
- Reject ambiguous DEX matches rather than choosing the first candidate.
- Preserve unrelated dirty files.
- Use JDK 17 and the Android build flags in `docs/project/constraints.md`.
- Manual UI acceptance must remain user-driven.
- Keep the AAPT2 container workaround private; replace it when an arm64-compatible AGP 9.3 AAPT2 is available.
