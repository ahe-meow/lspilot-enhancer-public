# Current plan

- Status: current
- Updated: 2026-08-17
- Purpose: define the ordered path from the current repository state to runtime acceptance.

## Current phase

Repository-side feature removal, package migration to `com.lspilot.enhancer`, conservative request/SSE ABI discovery, and release verification are complete. Installed `1.7.4-preview.26` removes automatic retry and stores request/SSE-only descriptors with cache schema `7`. Current source adds a separate host-native Miuix settings page and `Rounded.AutoAwesome` entry.

The verified APK is published as GitHub pre-release `v1.7.4-preview.26`, installed as `com.lspilot.enhancer`, enabled in LSPosed, and scoped to `me.yun.lspilot`. Source, Termux-private stage, `/data/local/tmp`, installed `base.apk`, and re-downloaded release asset all match SHA-256 `7051672f6a2335927abf3cc5bb1e4af7ec70f897bec921a9e7553fb564397e73`.

Two cold starts passed: the first rebuilt cache schema `7` with reason `cache_schema_changed`; the second logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, and settings hooks. Neither start logged automatic-retry hook installation.

The `1.7.4-preview.27` candidate contains the native settings-page change. It is statically verified but not published or installed; the installed device remains on `v1.7.4-preview.26` until explicit release/install approval.

## Execution order

1. Remove context compression and automatic retry, including their hooks, host-state writes, persistence, tests, and current UI/docs.
2. Keep request/SSE and settings-entry ABI discovery only; reject ambiguous request candidates.
3. Cache validated request/SSE descriptors by APK content hash and scanner schema.
4. Keep focused request-policy, scanner ambiguity, cache invalidation, prompt-cache, and reasoning checks.
5. Run release verification, build the migrated package, and verify APK package metadata and integrity.
6. Install the new package, enable it, scope it to `me.yun.lspilot`, and verify two cold starts.
7. Manually verify cache request mutation, usage reporting, and settings.

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
