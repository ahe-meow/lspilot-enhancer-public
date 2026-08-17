# Current plan

- Status: current
- Updated: 2026-08-17
- Purpose: define the ordered path from the current repository state to runtime acceptance.

## Current phase

Repository-side feature removal, package migration to `com.lspilot.enhancer`, conservative ABI discovery, and release verification are complete. The `1.7.4-preview.24` candidate keeps request/SSE available when retry discovery fails, rejects ambiguous request candidates, and stores request-only descriptors with cache schema `6`.

The candidate APK at `app/build/outputs/apk/release/app-release.apk` passed the focused Dalvik checks, release build, lint, badging, ZIP integrity, v2 signature, retired-feature marker scan, and `git diff --check`. Publishing and device runtime work remain pending; after publication, install the new package, enable it, and re-scope it to `me.yun.lspilot` in LSPosed.

## Execution order

1. Remove all context-compression classes, hooks, UI, settings, tests, and current documentation.
2. Split retained ABI discovery into independently validated request/SSE, retry, and settings groups.
3. Resolve candidates structurally, require a unique coherent winner, and cache validated descriptors by APK content hash and scanner schema.
4. Add focused request-policy, scanner ambiguity, and cache invalidation checks.
5. Run release verification, build the migrated package, and verify APK package metadata and integrity.
6. Install the new package, re-enable it, re-scope it to `me.yun.lspilot` in LSPosed, and manually verify cache request mutation, usage reporting, settings, and retry behavior.

## Current host evidence

The installed host is version `1.1.0 (11)`, SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`, size `16,700,026` bytes, at staged MT2 workspace `rvlxvm8q`.

- Current request provider: `zj8`
- Config: `cb`
- Request builder: `zj8.p(cb, List, String, boolean): String`
- SSE parser: `zj8.t(String, Function1): boolean`
- Retry ViewModel: `va`
- Stream bridge: `va.w(cb, List, Function1): void`
- Load/send/retry/stop: `va.F(String, String, Context)`, `va.P()`, `va.J()`, `va.Q()`
- State/session: `va.b:sk7 -> oa`, `oa.j() -> na`, `na.d() -> String`
- Message: `u7`, with ID `f()`, role `i()`, content `c()`
- Repository: `me.yun.lspilot.data.repository.b`, add `c(String,u7)`, replace `r(String,List)`

The earlier staged host SHA-256 `b6ea30f6...debe` uses the same `va/cb/u7` groups with provider `vj8`; `HostAbi` now tries both verified provider profiles.

## Decision points

- A scan with multiple plausible candidates disables that feature group; it does not pick the first match.
- A retry ABI failure must not disable request caching when the request/SSE ABI is valid.
- A changed host content fingerprint invalidates cached descriptors before hooks are installed and enables structural `DexFile` discovery; unchanged startup reuses only the validated descriptor.
- Runtime acceptance remains pending until `com.lspilot.enhancer` is installed, enabled, and scoped to `me.yun.lspilot`; use the exact installed module hash and current host hash for that verification.
