# Current todo

- Status: current
- Updated: 2026-08-17
- Purpose: track the remaining work and acceptance evidence.

## In progress

- `1.7.4-preview.24` is built and verified. Publishing is pending; device runtime acceptance still requires installing, enabling, and scoping the new package.

## Next

- [ ] Complete any remaining manual automatic-retry and stop-behavior acceptance separately.
- [ ] Replace the private AAPT2 APK-container workaround when an arm64-compatible AGP 9.3 AAPT2 is available.
- [ ] Publish `v1.7.4-preview.24` with the verified APK.
- [ ] Install the new package `com.lspilot.enhancer`; after installation, enable it and re-scope it to `me.yun.lspilot` in LSPosed. The previous installed package must not be treated as changed in place.

## Completed

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
- [x] Build and verify the migrated release artifact at `app/build/outputs/apk/release/app-release.apk`; device installation of `com.lspilot.enhancer` remains pending.
- [x] Pass `RequestGroupIsolationCheck`, `HostUpdateDetectionCheck`, `AutoRetryManagerCheck`, prompt-cache/reasoning checks, release assembly, lint, APK integrity/signature/DEX-marker checks, and `git diff --check` for `1.7.4-preview.24`.
