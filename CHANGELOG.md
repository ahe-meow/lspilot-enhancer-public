# Changelog

## Unreleased

- Replace the settings Overlay/Dialog with a separate host-native Miuix settings page reached through host navigation.
- Add the module entry as an independent settings card with the unused-in-settings `Rounded.AutoAwesome` icon, separate from the host `关于` row.

## 1.7.4-preview.26

- Remove automatic retry, including retry/stop/session/repository hooks, host StateFlow message replacement, repository persistence, and retry-specific ABI discovery/cache fields.
- Apply the 24-hour Prompt Cache retention fallback to every named model request instead of using model-family whitelists.

## 1.7.4-preview.25

- Apply the configured reasoning effort to every named model instead of only `gpt-5.6-sol`; retain the reasoning SSE compatibility normalization.
- Remove the weak hash-code fallback when SHA-256 is unavailable; request enhancement now fails open to the unchanged host request.
- Replace flagged reflective class lookup with non-initializing `ClassLoader.loadClass` calls; pi-lens now reports no issues.

## 1.7.4-preview.24

- Rename the module package to `com.lspilot.enhancer`; LSPosed must enable and scope it as a new module package.
- Remove the context-compression implementation, chat entry, settings, stored baselines, tests, documentation, and request-time legacy repair.
- Stop writing retry progress rows into host chat history; retain redacted retry diagnostics without logging raw SSE content.
- Remove the unused packaged/compile-time DexKit fallback and use platform `DexFile` discovery.
- Detect startup host content changes independently of unchanged version metadata, invalidate stale ABI descriptors, and run structural DEX self-adaptation before cache hooks.
- Resolve request/SSE independently from retry endpoints so an incomplete or ambiguous retry graph does not disable request caching.
- Cache request-only descriptors with schema 6 and reject ambiguous request/SSE candidates.
- Refresh retained endpoint evidence for host APK SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`.

## 1.7.4-preview.21

- Bind host config and message accessors from structural evidence instead of relying only on obfuscation names.
- Validate bound accessors before caching the ABI descriptor.
- Bump the ABI cache schema when descriptor semantics change.

## 1.7.4-preview.20

- Adapt request and retry hooks after host provider and ViewModel obfuscation changes.
- Ignore static Kotlin wrapper methods during DEX ABI matching.
- Persist validated ViewModel lifecycle methods in the ABI descriptor cache.

## 1.7.4-preview.19

- Fingerprint host APK and split contents so host updates invalidate stale descriptors.
- Broaden DEX class candidates and accept private singleton fields.
- Report the actual module version and guard request-policy normalization when the model is missing.

## 1.7.4-preview.18

- Regenerate failed assistant responses from their original position.
- Preserve the following conversation tail and replace the failed assistant in place.
- Keep the complete host message list visible and persistent during retry.

## 1.7.4-preview.17

- Preserve host chat state before native retry truncation.
- Restore message state and repository persistence after native retry starts.
- Add host-state restoration regression coverage.

## 1.7.4-preview.16

- Preserve the first-attempt message list when host regeneration supplies an empty or replaced history.
- Detect additional host error-event shapes and nested failure details.
- Observe stream failures before dispatching to the host callback.

## 1.7.4-preview.13

- Stabilize GPT-5.6 explicit prompt-cache breakpoints around completed assistant prefixes.
- Derive cache identity from the actual system/developer prompt and rotate the namespace after policy changes.

## 1.7.4-preview.12

- Add chat-scoped automatic response retries with delays of 5 seconds, 10 seconds, 30 seconds, 2 minutes, and 5 minutes.
- Reuse the host's native response-regeneration path and bind retries to the active ViewModel/chat.
- Cancel retries through native stop, session changes, or a new user send.

## 1.7.4-preview.11

- Add GPT-5.6 sol reasoning effort levels: `low`, `medium`, `high`, `xhigh`, `max`, and `ultra`.
- Inject `reasoning_effort` and normalize compatible `delta.reasoning` SSE payloads.
- Extend host ABI resolution with the raw SSE parser endpoint.

## 1.7.4-preview.10

- Restore request-body and raw SSE usage hooks for a changed minified host ABI.
- Add startup probes that disable unsupported settings after host updates.
- Add explicit prompt-cache breakpoints and a platform `DexFile` fallback.
- Restore the injected settings entry after host obfuscation changes.

## Earlier work

- Added LSPosed request hooks for OpenAI-compatible request enhancement.
- Added stable Prompt Cache keys, compatible retention, and streaming usage reporting.
- Added native settings entry rendering through host UI components.
- Added host-hash ABI descriptor caching and bounded automatic retries.
