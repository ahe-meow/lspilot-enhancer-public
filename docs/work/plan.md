# Current plan

- Status: current
- Updated: 2026-08-18
- Purpose: define the ordered path from the current repository state to runtime acceptance.

## Current phase

Repository-side feature removal, package migration to `com.lspilot.enhancer`, conservative request/SSE ABI discovery, incident repair, and stable artifact verification are complete. Installed `1.7.5 (67)` includes request-local tool-call sanitization and the separate host-native Miuix settings page.

The verified stable APK is installed as `com.lspilot.enhancer` and matches SHA-256 `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01` (679,443 bytes). Cold-start evidence confirms `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, and native settings hooks without automatic-retry installation or fatal/Compose errors.

Two cold starts passed: the first rebuilt the ABI descriptor for module version code `64`; the second logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, and `Native host settings page hooks installed with AutoAwesome icon`. Neither start logged automatic-retry hook installation or native-settings hook failure.

Stable `v1.7.4` is published as a non-prerelease GitHub Release at <https://github.com/ahe-meow/lspilot-enhancer-public/releases/tag/v1.7.4>. The exact installed and released APK is 678,659 bytes with SHA-256 `e3ba4ba5d7241c29a04923592996a67e91b8f2d90a2c1688add0880034972102`; release/lint, diagnostics, APK integrity/signature, focused Dalvik checks, exact current-host ABI, four-stage install hashes, LSPosed state/scope/integrity, module-update rebuild, subsequent `cache_hit`, user UI acceptance, and downloaded-asset hash verification pass.

## Current incident repair

The active host incident is resolved at the shared outbound JSON boundary. The read-only host baseline is `me.yun.lspilot` `1.1.0 (11)`, APK SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`, MT2 workspace `enml4cuy`. Its fixed `LIMIT 30` load can begin with four `role=tool` rows that have no preceding assistant `tool_calls`; `va.x` does not remove those rows, and `zj8.p` serializes them into the provider request. The reported 502 call ID exactly matches the first orphan output. The 400 body was not preserved for a one-request proof, so it remains documented as a high-probability gateway wrapper for the same malformed request class, not as a falsely claimed direct observation.

`ToolCallSanitizer` now runs in both request-body ABI paths. It repairs only the outbound JSON: orphan/duplicate/delayed outputs are removed, only contiguous completed assistant tool-call groups survive, normal text is preserved, and host database/UI/repository state is untouched. Dalvik replay and delayed-output red/green checks pass. On the final installed artifact, PID 9774 logged `Tool-call context repaired changes=12`, completed usage, and the user received a successful response with no 400/502/upstream error.

Stable module `1.7.5 (67)` is installed and startup-verified. Source/private/staged/installed APKs all match SHA-256 `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01` (679,443 bytes); release/lint, focused Dalvik checks, exact current-host ABI, APK integrity/signature, and request/SSE/settings startup hooks pass.

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
