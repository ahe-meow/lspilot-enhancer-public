# Changelog

## 1.7.4

- Add project settings for automatic context compression thresholds: trigger by conversation turns or estimated context tokens.
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