# Current todo

- Status: current
- Updated: 2026-08-18
- Purpose: track the remaining work and acceptance evidence.

## In progress

- None for the active incident repair.

## Next

- [ ] Replace the private AAPT2 APK-container workaround when an arm64-compatible AGP 9.3 AAPT2 is available.

## Completed

- [x] Diagnose the host's orphan/incomplete tool-call windows, add request-boundary `ToolCallSanitizer`, and prove the final installed artifact repairs `changes=12` in the affected conversation with no new 400/502 log.
- [x] Analyze the host chat UI history pager: exact top-trigger guards, `rowId` cursor query, silent parse/DB failures, hidden `role=tool` rows, and the likely stuck in-flight marker after an empty/error page; runtime confirmation remains separate.
- [x] Record that the host's `LIMIT 30` runs during every request build, excludes older rows from that request without deleting them from `chat_message`, and can split tool-call groups; the chat UI uses a separate cursor pager rather than a proven shared query.
- [x] Build/install stable `1.7.5 (67)`; verify source/private/staged/installed SHA-256 `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01`, release/lint, Dalvik checks, APK integrity/signature, current-host ABI, and cold-start hooks.
- [x] Write `docs/work/host-context-truncation-diagnosis.md` with the confirmed 502 root cause, bounded 400 evidence, repair design, and live validation result.
- [x] Commit, tag, push, and publish formal GitHub Release `v1.7.5`; verify the named online APK asset matches SHA-256 `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01` and 679,443 bytes.

- [x] Commit stable source, tag `v1.7.4`, push main/tag, publish a non-prerelease GitHub Release, and verify the downloaded 678,659-byte asset matches SHA-256 `e3ba4ba5d7241c29a04923592996a67e91b8f2d90a2c1688add0880034972102`.
- [x] Receive explicit user acceptance for alternating About/module navigation, native settings layout/labels, and setting persistence.
- [x] Build and install the exact `1.7.4 (66)` stable APK; verify release/lint, Dalvik/ABI checks, APK integrity/signature, four-stage SHA-256 equality, LSPosed state/scope/integrity, and two cold starts.
- [x] Install the corrected uncommitted `preview.28` locally; verify source/private/staged/installed SHA-256 equality, LSPosed state/scope/integrity, complete first-start hooks, and two starts without Compose/FATAL errors.
- [x] Reject the first local `preview.28` candidate after its bottom-Composable injection crashed Compose; replace it with LazyColumn item registration before the fourth host item and add a regression check.
- [x] Match host entry layout by rendering before the bottom safe-area item with the host full-width modifier and 12dp card spacing; rename visible `Prompt Cache` summary to `缓存`.
- [x] Build and statically verify the `preview.28` candidate: release/lint, LSP, pi-lens, APK/DEX, navigation, and exact current-host ABI checks pass.
- [x] Publish and install `v1.7.4-preview.27`; verify five-stage SHA-256 equality, LSPosed state/scope/database integrity, module-update ABI rebuild, subsequent `cache_hit`, request/SSE hooks, and the AutoAwesome native-settings hook.
- [x] Replace the module settings Overlay/Dialog with host-native Miuix navigation; separate the entry from `关于`, use `Rounded.AutoAwesome`, and add navigation-state regression coverage.
- [x] Pass release/lint, LSP, pi-lens, DEX marker, APK integrity/signature, and `NativeSettingsNavigationCheck` verification for the `preview.27` candidate.
- [x] Publish and install `v1.7.4-preview.26`; verify SHA-256 across source, GitHub download, Termux-private stage, `/data/local/tmp`, and installed `base.apk`.
- [x] Verify LSPosed state/scope/database integrity and two host cold starts: schema `7` rebuild followed by `cache_hit`, request/SSE/settings hooks installed, and no automatic-retry hook log.
- [x] Remove automatic retry, host retry/session/stream/repository hooks, StateFlow message replacement, repository persistence, retry ABI discovery/cache fields, and retry-only tests.
- [x] Remove the 24-hour retention model-family whitelist; retain blank-model protection and explicit GPT-5.6 breakpoint precedence, then pass Java and Dalvik policy checks.
- [x] Clear all three pi-lens `unsafe-reflection` warnings with equivalent non-initializing `ClassLoader.loadClass` calls; pass LSP, Java compilation, and focused Dalvik checks.
- [x] Remove the `gpt-5.6-sol` reasoning-effort restriction for all non-empty model names; pass Java compilation and the Dalvik `ReasoningPolicy` check.
- [x] Install `com.lspilot.enhancer` with SHA-256 `51e0c3c044ee1f79a4b93ac5e6c6767e2a22cb81c5dd96ac2a1086fd7f7928aa`; source, Termux-private stage, `/data/local/tmp`, and installed `base.apk` hashes match.
- [x] Verify LSPosed state `enabled=1`, `scope_request_blocked=0`, scope `me.yun.lspilot`, and database integrity `ok`.
- [x] Verify two cold starts: schema `6` rebuild followed by `cache_hit`, `provider=zj8`, request/SSE probes true, and retry/settings hooks installed.
- [x] Publish GitHub pre-release `v1.7.4-preview.24` and verify the downloaded APK matches SHA-256 `51e0c3c044ee1f79a4b93ac5e6c6767e2a22cb81c5dd96ac2a1086fd7f7928aa`.
- [x] Stop the full-history context-compression implementation.
- [x] Remove compression production classes, UI, settings, persistence, tests, build task, dependency, and active documentation.
- [x] Adapt the retained request/SSE and retry groups with conservative unique DEX candidates and host-content-hash descriptor caching.
- [x] Support verified minified provider profiles `vj8` (staged host `b6ea30f6...debe`) and `xj8` (installed host `d4eb3066...f5d56`).
- [x] Prevent startup writes to LSPosed's read-only remote preferences; unavailable-setting persistence now waits for host-local preferences.
- [x] Complete source/package migration to `com.lspilot.enhancer` and verify the `1.7.4-preview.24` candidate; exact artifact evidence is recorded in `docs/work/findings.md`.
- [x] Detect and reject the incomplete AGP/AAPT2 APK output instead of deploying it.
- [x] Build a private valid module APK by replacing only `classes.dex` in the prior valid module container, then zipaligning and signing it.
- [x] Verify private module APK `7bc3a22f...c44e8` with `aapt2 dump badging`, ZIP integrity, and APK v2/v3 signature checks.
- Historical device-install and LSPosed entries below refer to the predecessor artifact; they do not verify installation, enablement, or re-scoping of `com.lspilot.enhancer`.
- [x] Install only the module package active at that time; source, private stage, `/data/local/tmp` stage, and installed `base.apk` all matched SHA-256 `7bc3a22f6a43ef98cd843922c89a0b57ced474cc26583fdca0e4f2089d8c44e8`.
- [x] Verify LSPosed module scope enabled for `me.yun.lspilot`, config integrity `ok`, and runtime startup hooks: `provider=xj8`, `requestBody=true`, `sseUsage=true`, stream/retry, cache, SSE, load-session, and auto-retry hooks installed.
- [x] Verify the prior ABI failure is gone and no `Unavailable setting persistence failed` log appeared after the fixed module restart.
- [x] Confirm `新对话` succeeds on `https://pasw.shop/v1/chat/completions`; its approximately 30K input is host system prompt/tool definitions, not persisted history.
- [x] Detect host APK content changes on every module startup without trusting version metadata; invalidate stale descriptors and prefer structural DEX self-adaptation on changes.
- [x] Adapt the updated host (`af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`) to provider `zj8`; verify cache/SSE/retry hooks after restart.
- [x] Build and verify the migrated release artifact at `app/build/outputs/apk/release/app-release.apk`; installed-artifact evidence is recorded in `docs/work/findings.md`.
- [x] Pass `RequestGroupIsolationCheck`, `HostUpdateDetectionCheck`, `AutoRetryManagerCheck`, prompt-cache/reasoning checks, release assembly, lint, APK integrity/signature/DEX-marker checks, and `git diff --check` for `1.7.4-preview.24`.
