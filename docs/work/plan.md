# Current plan

- Status: current
- Updated: 2026-08-18
- Purpose: define the ordered path from the current repository state to runtime acceptance.

## Current phase

Repository-side feature removal, package migration to `com.lspilot.enhancer`, conservative request/SSE ABI discovery, incident repair, and stable artifact verification are complete. Installed `1.7.5 (67)` includes request-local tool-call sanitization and the separate host-native Miuix settings page.

The verified feature candidate is installed as `com.lspilot.enhancer` and matches SHA-256 `52bd0aad6fd4dc13ea8d3cea1b906334a7a3d2126027a3e613c3088317db7fbe` (681,063 bytes). Cold start confirms request/SSE/history/settings hooks. The module-settings back-navigation regression is fixed by moving the sentinel route away from the host `eca$m` `Route.LogViewer` class to `eca$c`; the user confirmed return to host settings. Enabled history retention passed two real saves (`76 → 98 → 110`) with all prior message IDs preserved, no duplicate IDs, and no parse errors.

Two cold starts passed: the first rebuilt the ABI descriptor for module version code `64`; the second logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, and `Native host settings page hooks installed with AutoAwesome icon`. Neither start logged automatic-retry hook installation or native-settings hook failure.

The formal GitHub Release `v1.7.5` is now published non-draft/non-prerelease with the named APK asset; its online SHA-256 matches the installed artifact.

## Current feature branch

`feature/host-history-retention-hooks` has completed the first prevention step: a default-enabled native `保留历史消息` switch and a fail-closed Hook around `repository.b.r`. The Hook reads the current persisted list through `repository.b.i`, verifies its size against DAO `c7.o`, merges it with the partial UI list by message ID, and then allows the host's existing transactional replacement to proceed with the complete merged list. Setting off calls the original host method unchanged. Static code, focused merge check, release assembly, lint, module-only installation, cold-start injection, settings back-navigation, two ordinary enabled-mode saves, pagination-followed-by-save, stop/cancel save, and host re-entry all pass. The target chat grew `76 → 98 → 110 → 112 → 116` while every prior ID remained present; the disabled destructive write path is intentionally not exercised on a real long chat.

## Current incident repair

The active host incident is resolved at the shared outbound JSON boundary. The read-only host baseline is `me.yun.lspilot` `1.1.0 (11)`, APK SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`, MT2 workspace `enml4cuy`. Its fixed `LIMIT 30` load can begin with four `role=tool` rows that have no preceding assistant `tool_calls`; `va.x` does not remove those rows, and `zj8.p` serializes them into the provider request. The reported 502 call ID exactly matches the first orphan output. The 400 body was not preserved for a one-request proof, so it remains documented as a high-probability gateway wrapper for the same malformed request class, not as a falsely claimed direct observation.

`ToolCallSanitizer` now runs in both request-body ABI paths. It repairs only the outbound JSON: orphan/duplicate/delayed outputs are removed, only contiguous completed assistant tool-call groups survive, normal text is preserved, and host database/UI/repository state is untouched. Dalvik replay and delayed-output red/green checks pass. On the final installed artifact, PID 9774 logged `Tool-call context repaired changes=12`, completed usage, and the user received a successful response with no 400/502/upstream error.

Stable module `1.7.5 (67)` is installed and startup-verified. Source/private/staged/installed APKs all match SHA-256 `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01` (679,443 bytes); release/lint, focused Dalvik checks, exact current-host ABI, APK integrity/signature, and request/SSE/settings startup hooks pass.

## Pagination runtime diagnosis

The separate chat-UI pager investigation is complete. Temporary user-authorized module-only telemetry captured two successful old-page loads in the 76-row chat: state changed `30 → 60 → 76`, cursors changed `1015862 → 1015832 → 1015816`, both guards passed, and the in-flight marker cleared after each scroll restoration. The visible symptom is caused by most loaded rows being hidden `role=tool` messages while `ka$d` restores the old viewport anchor; it is not a failed query or stale marker in this reproduction. Temporary telemetry was removed and the exact stable APK restored; no runtime pager hook remains in source or release artifacts.

A fresh database count confirms 76 is the entire currently persisted chat, not a pagination cap. The missing older history is attributable to the host's full-list replacement design: streaming `va.K` starts `va$k`, which periodically persists partial `AiChatUiState.messages` through `repository.b.r`; DAO `c7.b` deletes all rows for the chat before inserting that current list. The database has 76 contiguous fresh row IDs, consistent with a batch rewrite. The exact historical caller among seven replacement paths was not runtime-captured, so no single path is overstated; the feature-branch module now guards the host repository save only through the opt-in retention Hook and does not replace host message state.

The corrected request model is: `LIMIT 30` initializes the UI working set when a chat is entered; it is not rerun before each provider request. `va.P -> va.K -> va.w` sends the entire currently loaded state, so loading older pages first expands model-visible context. `zj8` performs no message-count/token trimming. Separately, stream autosave polls every 120 ms and saves on a 400 ms interval, and tool content has 4,000/12,000-character caps.

## Execution order

1. Remove context compression and automatic retry, including their hooks, host-state writes, persistence, tests, and current UI/docs.
2. Keep request/SSE and settings-entry ABI discovery only; reject ambiguous request candidates.
3. Cache validated request/SSE descriptors by APK content hash and scanner schema.
4. Keep focused request-policy, scanner ambiguity, cache invalidation, prompt-cache, and reasoning checks.
5. Run release verification, build the migrated package, and verify APK package metadata and integrity.
6. Install the new package, enable it, scope it to `me.yun.lspilot`, and verify two cold starts.
7. Manually verify cache request mutation, usage reporting, and settings.
8. Capture the host's malformed tool-call window read-only and prove the orphan sequence with a red/green structural validator.
9. Repair the sequence at the shared request-body boundary, add a minimal Dalvik regression check, and verify the current host ABI remains intact.
10. Install the repaired module, validate the original affected conversation, record the Chinese diagnosis, then rebuild/install the stable artifact and run final source/runtime gates.

## Current host evidence

The installed host is version `1.1.0 (11)`, SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`, size `16,716,410` bytes, at staged MT2 workspace `enml4cuy`.

- Current request provider: `zj8`
- Config: `cb`
- Request builder: `zj8.p(cb, List, String, boolean): String`
- SSE parser: `zj8.t(String, Function1): boolean`
- Current module ABI use stops at request builder `zj8.p` and SSE parser `zj8.t`; the host retry/ViewModel/repository mappings below are historical evidence only and are no longer discovered or hooked.
- Historical host retry mappings: `va.w`, `va.F`, `va.P`, `va.J`, `va.Q`, state `oa`/`na`, message `u7`, repository `me.yun.lspilot.data.repository.b`.

The earlier installed host SHA-256 `d4eb3066...f5d56`, size `16,700,026`, is workspace `rvlxvm8q`; it uses provider `xj8`. The staged `b6ea30f6...debe` profile uses provider `vj8`.

## Decision points

- Ambiguous request/SSE scans disable request enhancement rather than choosing the first match.
- A changed host content fingerprint invalidates cached descriptors before hooks are installed and enables structural `DexFile` discovery; unchanged startup reuses only the validated descriptor.
- Native settings-page navigation uses the host's `SettingPagerMiuix` route and Miuix `SwitchPreference`/`OverlayDropdownPreference` components; the module no longer creates an Android `AlertDialog` or overlay settings view.
