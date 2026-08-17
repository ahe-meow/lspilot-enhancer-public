# Current todo

- Status: current
- Updated: 2026-08-17
- Purpose: track the remaining work and acceptance evidence.

## In progress

- Manually verify the installed `v1.7.4-preview.27` independent icon, native page navigation, switch/dropdown persistence, request mutation, and usage.

## Next

- [ ] Complete the manual `preview.27` UI and live-request acceptance scenarios.
- [ ] Replace the private AAPT2 APK-container workaround when an arm64-compatible AGP 9.3 AAPT2 is available.

## Completed

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
