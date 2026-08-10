# Changelog
## 1.7.4-preview.17

- Preserve the complete current host message list before automatic retry instead of accepting the native regeneration truncation at the most recent user message.
- Restore the immutable host chat state with a message-only copy after native retry starts, retaining loading, provider, model, draft, attachment, and session fields.
- Replace the repository message snapshot before retry so the truncated native state cannot overwrite the conversation tail in persistent storage.
- Add an Android-runtime regression check against LSPilot 1.1.0's real `cb`/`va`/`Lut7` ABI.

## 1.7.4-preview.16

- Preserve the first-attempt message list when the host regeneration path supplies an empty or replaced history during automatic retry.
- Detect Throwable and additional host failure event shapes, extract nested failure details, and include the concrete reason in retry status messages.
- Observe stream failure events before dispatching to the host callback so callback exceptions cannot hide retry scheduling.
- Keep retry status updates on the local chat UI and prevent them from reloading a possibly empty host session over the active conversation.

## 1.7.4-preview.15

- Ignore host-list bookkeeping indexes when matching prepared compression snapshots, so enhancer status messages cannot invalidate an otherwise unchanged compression baseline.
- Bound deterministic local summary blocks and reject any compacted request that is not strictly smaller in characters and UTF-8 bytes.
- Add a large-conversation regression check for compression expansion.

## 1.7.4-preview.13

- Stabilize GPT-5.6 explicit prompt-cache breakpoints around completed assistant prefixes instead of moving them across the changing user/tool suffix.
- Derive cache identity from the actual system/developer prompt and rotate the cache namespace after the breakpoint policy change.
- Prevent automatic pre-send compression status refreshes from opening the manual compression overlay.
- Improve automatic compression threshold estimation with serialized message/tool-call JSON and pending input text, and adapt the retained tail when a large context has few messages.
- Exclude both compression and retry status messages from context-length measurement and provider requests.

## 1.7.4-preview.12

- Add chat-scoped automatic response retries after provider or stream failures, with fixed delays of 5 seconds, 10 seconds, 30 seconds, 2 minutes, and 5 minutes.
- Reuse the host's native response-regeneration path so retries do not duplicate the user's message.
- Insert retry scheduling, progress, success, cancellation, and exhaustion notices into the conversation while filtering them from provider requests.
- Bind pending retries to the active ViewModel and chat, and cancel them through the host's native stop action, session changes, or a new user send.

## 1.7.4-preview.11

- Add user-selectable GPT-5.6 sol reasoning effort levels: `low`, `medium`, `high`, `xhigh`, `max`, and `ultra`.
- Inject the selected `reasoning_effort` into GPT-5.6 sol requests and normalize `delta.reasoning` SSE payloads for the host's native reasoning UI.
- Extend static and DEX-scanned host ABI resolution to include the raw SSE parser endpoint.

## 1.7.4-preview.10
- Restore request-body, stream-compression, raw SSE usage, chat-state, and repository hooks for LSPilot 1.1.0's updated minified ABI.
- Add startup endpoint probes that disable unsupported settings when a host update breaks a required hook.
- Add GPT-5.6 explicit prompt-cache breakpoints for the stable system anchor and recent user/tool messages.
- Add a runtime `DexFile` fallback that rediscovers obfuscated provider, config, ViewModel, state, session, message, repository, chat-route, and ArrowPreference endpoints after host updates.
- Restore the injected settings entry and minified AiChat route detection for the host's new obfuscation layout.
## 1.7.4-preview.9

- Add a versioned host ABI resolver for named and minified LSPilot builds, including request, chat-state, session reload, and repository bridges.
- Keep configuration exclusively in the injected LSPilot settings dialog and persist it directly in the host process; remove the standalone settings app and cross-process synchronization.
- Trigger automatic compression by estimated context tokens only, and make manual/automatic startup atomic to prevent overlapping tasks.
- Move diagnostic file writes to a daemon queue, throttle duplicate status updates, and cache hot-path reflection lookups.
- Remove the unused model-summary HTTP path; compression now uses deterministic local windows and bounded excerpts without an extra provider request.
- Preserve valid tool-call sequences, ignore empty tool-call metadata, and omit duplicate assistant `toolCallResults` from compression input.
- Reload the active chat after terminal compression statuses so completion, failure, and timeout notices become visible immediately.
- Cancel a pending send when automatic compression fails or times out instead of silently replaying the full uncompressed history.
- Keep prepared summaries as persistent per-chat baselines and support incremental compression of newly appended history.
- Disable unsupported module hot reload until static executors and host references have an explicit lifecycle cleanup protocol.
- Allow release builds to retain the host-compatible target SDK without failing Google Play's app-distribution lint check.
- Add an Android-runtime tool-sequence regression check, an API 102 integration audit, and public design-reference attribution.

## 1.7.4-preview.8

- Replace model-backed manual summarization with deterministic local window compression, eliminating the extra compression model request and its token cost.
- Keep manual compression as a persistent chat baseline and support incremental updates from the existing compacted baseline plus newly added tail messages.
- Cap oversized message/tool-call excerpts in compression blocks with head/tail retention and SHA-256 anchors to reduce prompt bloat while preserving traceability.
- Stop reopening the compression panel after completion on every later request; show the compression-baseline notice once per prepared baseline.

## 1.7.4

- Add project settings for automatic context compression thresholds: trigger by conversation turns or estimated context tokens.
- Preserve `assistant.tool_calls` / `tool` message adjacency when choosing compression boundaries, and reject compacted requests with invalid tool-message ordering before sending.
- Retain automatic prepared summaries as a rolling baseline after each response, so subsequent sends only retrigger compression after newly added turns or estimated tokens exceed the configured thresholds.
- Apply the configured thresholds before replaying `sendMessage()`, while still requiring enough messages to keep the recent-message tail intact.
- Show editable numeric settings in the injected settings dialog with clamped valid ranges and default reset behavior.

## 1.7.3

- Keep a prepared compression result active for every provider request in the same model response, instead of consuming it on the first request only.
- Clear the active prepared context only after the host chat leaves loading state, and log the apply count for verification.
- Reset prepared-context bookkeeping consistently when switching chats, changing providers, rejecting a prepared context, or manually clearing the summary.

## 1.7.2

- Add a pre-send hook on `AiChatViewModel.sendMessage()` that pauses the first send, compresses while the chat is still idle, then replays the same send after compression finishes.
- Keep provider request building free of live compression; it only applies a prepared summary or sends the sanitized original messages.
- Discard only in-flight compression when the chat becomes loading, while preserving already prepared summaries for the send path.

## 1.7.1

- Stop running automatic context compression inside the provider request-building hook; requests now only consume summaries that were already prepared while the chat was idle.
- Keep enhancer status-message filtering and manual prepared-context application in the send path.

## 1.7.0

- Add persisted system status messages for compression start, snapshot length, per-chunk progress, completion/failure, next-request usage, and provider usage confirmation.
- Record message count, JSON character count, UTF-8 bytes, estimated tokens, compression ratio, duration, request mode, and provider usage totals without logging message bodies or credentials.
- Refresh the current chat through the host `AiChatViewModel.loadSession` after status insertion; filter enhancer status messages out of provider requests and compression matching.
- Add an `AiChatRepository.addMessage` bridge using the host `AiChatMessage` format.

## 1.6.0

- Scope the compression entry to the APK-confirmed `Route.AiChat` branch in `SubScreenActivity.onCreate$lambda$0$1`; remove it immediately for every non-chat route.
- Replace the text button with a native icon button using a density-correct compression glyph, circular ripple, and dark/light colors.
- Add touch, click, panel, route-visible, and overlay-removal logs for end-to-end LSPosed verification.
- Stop attaching the entry merely because any `SubScreenActivity` was created.

## Earlier Work

- Added LSPosed request hooks for OpenAI-compatible request body enhancement.
- Added raw SSE usage observation for provider usage metrics.
- Added native settings entry rendering through host UI components.
- Iterated Activity and route hooks based on reverse-engineered host method signatures.