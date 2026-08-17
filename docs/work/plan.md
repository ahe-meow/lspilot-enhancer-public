# Current plan

- Status: current
- Updated: 2026-08-17
- Purpose: define the ordered path from the current repository state to runtime acceptance.

## Current phase

Repository-side feature removal, package migration to `com.lspilot.enhancer`, conservative request/SSE ABI discovery, and release verification are complete. The `1.7.4-preview.26` candidate removes automatic retry and stores request/SSE-only descriptors with cache schema `7`.

The verified APK is published as GitHub pre-release `v1.7.4-preview.25`, installed as `com.lspilot.enhancer`, enabled in LSPosed, and scoped to `me.yun.lspilot`. Source, Termux-private stage, `/data/local/tmp`, installed `base.apk`, and re-downloaded release asset all match SHA-256 `596635b240f1b6733e54dabc784862e9afccf73edcbad702773f274779df9e5a`.

Two cold starts passed for the installed release: the first rebuilt cache schema `6`, and the second logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, and installed retry/settings hooks. That installed artifact still contains automatic retry.

The current source and installed `v1.7.4-preview.25` apply the selected `reasoning_effort` to every non-empty model name instead of only `gpt-5.6-sol`; Java compilation, focused Dalvik checks, and installed-host startup verification pass.

The `1.7.4-preview.26` candidate applies the 24-hour Prompt Cache retention fallback to every non-empty model name and removes automatic retry, retry-specific hooks, host StateFlow message replacement, and repository message persistence. The installed `v1.7.4-preview.25` predates both changes. User-driven request/usage/settings acceptance remains separate.

## Execution order

1. Remove context compression and automatic retry, including their hooks, host-state writes, persistence, tests, and current UI/docs.
2. Keep request/SSE and settings-entry ABI discovery only; reject ambiguous request candidates.
3. Cache validated request/SSE descriptors by APK content hash and scanner schema.
4. Keep focused request-policy, scanner ambiguity, cache invalidation, prompt-cache, and reasoning checks.
5. Run release verification, build the migrated package, and verify APK package metadata and integrity.
6. Install the new package, enable it, scope it to `me.yun.lspilot`, and verify two cold starts.
7. Manually verify cache request mutation, usage reporting, and settings.

## Current host evidence

The installed host is version `1.1.0 (11)`, SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`, size `16,700,026` bytes, at staged MT2 workspace `rvlxvm8q`.

- Current request provider: `zj8`
- Config: `cb`
- Request builder: `zj8.p(cb, List, String, boolean): String`
- SSE parser: `zj8.t(String, Function1): boolean`
- Current module ABI use stops at request builder `zj8.p` and SSE parser `zj8.t`; the host retry/ViewModel/repository mappings below are historical evidence only and are no longer discovered or hooked by unreleased source.
- Historical host retry mappings: `va.w`, `va.F`, `va.P`, `va.J`, `va.Q`, state `oa`/`na`, message `u7`, repository `me.yun.lspilot.data.repository.b`.

The earlier staged host SHA-256 `b6ea30f6...debe` uses the same `va/cb/u7` groups with provider `vj8`; `HostAbi` now tries both verified provider profiles.

## Decision points

- Ambiguous request/SSE scans disable request enhancement rather than choosing the first match.
- A changed host content fingerprint invalidates cached descriptors before hooks are installed and enables structural `DexFile` discovery; unchanged startup reuses only the validated descriptor.
- Static startup acceptance is complete for the installed artifact. Current unreleased source requires a later build/install verification; manual request/usage/settings behavior remains user-driven.
