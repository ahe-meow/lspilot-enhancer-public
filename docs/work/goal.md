# Current goal

- Status: current
- Updated: 2026-08-17
- Purpose: define the single active outcome and its acceptance boundary.

## Outcome

Ship a cache-focused LSPilot LSPosed module that adapts conservatively to the current host and future obfuscation changes without retaining any context-compression business logic, UI entry, or setting.

## In scope

- Prompt cache keys, explicit cache breakpoints, compatible retention, usage reporting, and reasoning effort.
- Bounded automatic response retries and native stop cancellation.
- Structural `DexFile` endpoint discovery with ambiguity rejection.
- ABI descriptor caching by host APK and split-APK content hashes; changed content invalidates the cache before hooks and triggers structural DEX self-adaptation.
- Read-only reverse engineering of host APKs and runtime evidence.

## Out of scope

- Context compression, summaries, compacted baselines, compression status rows, chat overlays, or compression settings.
- Patching, rebuilding, signing, or installing the host APK.
- Guessing hook endpoints from strings alone.

## Done when

- Production source, UI, settings, tests, and current documentation contain no active context-compression feature.
- The updated installed host APK SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c` (still version `1.1.0 (11)`) resolves provider `zj8` with the retained request/SSE and retry endpoint groups; the staged `b6ea30f6...debe` and prior `d4eb3066...f5d56` profiles remain compatibility evidence.
- Ambiguous or incomplete scans disable only the affected feature group.
- Focused checks, release build, lint, and `git diff --check` pass.
- The exact module APK is hash-verified, installed, loaded by LSPosed, and startup-verified against the updated host: `provider=zj8`, `requestBody=true`, `sseUsage=true`, and `Host ABI resolution reason=cache_hit` on the subsequent startup.
