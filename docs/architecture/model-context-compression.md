# Model Context Compression

- Status: current; runtime acceptance pending
- Updated: 2026-08-13
- Purpose: define the durable architecture and safety invariants for request-layer context compression.

## Goal

Replace local pre-send compaction with a model-generated, session-persisted summary baseline while leaving the host conversation visible and unchanged.

## Boundaries

- Compression changes provider request messages, not host chat history.
- The active provider, model, system prompt, tools, parameters, authentication, headers, serialization, and cache policy are reused for the internal summary request.
- Summary responses are internal terminal data. They do not enter the host repository, UI, or tool execution path.
- Module status messages and internal fields are removed before provider serialization; sanitization failure blocks the request.
- There is no uncompressed fallback for a user request blocked by compression failure.

## Components

| Component | Responsibility |
| --- | --- |
| `SummaryProtocol` | Build and validate the fixed seven-section Markdown summary and rebuild a stable baseline message |
| `CompressionStateMachine` | Define task identity, legal state transitions, retry, retention, cancellation, and stale-response rejection |
| `SummaryRecordStore` | Persist complete summary records and invalidate them on boundary, provider, model, or history changes |
| `ManualCompressionManager` | Coordinate one chat-scoped task, pending input, safe boundaries, validation, persistence, and effective messages |
| `LSPilotEnhancerModule` | Hook request/stream paths, isolate internal summaries, and apply effective messages to ordinary requests |
| `InjectedUiController` | Project state and available actions without rewriting or reloading chat history |
| `HostAbi` | Adapt message, provider, stream, repository, route, UI access, and stream event shapes across host versions |

The exact runtime summary headings and validation rules live in `SummaryProtocol` and its focused assertions. Documentation does not duplicate those constants.

## State Model

```text
IDLE
  -> WAITING_SAFE_BOUNDARY
  -> SUMMARIZING
  -> RETRYING
  -> VALIDATING
  -> AWAITING_USER_ACTION
  -> IDLE
```

- One chat can own at most one active compression task.
- An incomplete tool chain waits for a safe boundary while the existing host request continues.
- Formal summary states block new sends; the input remains editable.
- Task ID, chat ID, and boundary fingerprint guard every response and action.
- A late response from a stale, cancelled, timed-out, or invalidated task cannot commit state.

## Request Flow

### Automatic compression

1. Observe complete assistant, thinking, tool-call, or tool-result events rather than streaming chunks.
2. Estimate the next effective provider context after removing status messages and internal fields.
3. When over threshold, start immediately at a safe boundary or enter `WAITING_SAFE_BOUNDARY`.
4. Run the internal summary stream outside the Android main thread with the timeout armed first.
5. Validate and persist a complete summary record, then use it as the stable baseline for later requests.

Current host stream events use `lwb` chunk/done/error carriers. Legacy `nyb` carriers remain supported. Nested current chunks are parsed by part type: text parts feed the summary accumulator, while reasoning and tool parts do not become summary Markdown.

### User-triggered send

1. Let the host display the pending user message.
2. Exclude that message from the summary source and prevent the provider request from escaping early.
3. After summary commit, send exactly one effective request containing the summary baseline and pending user request.
4. On failure or cancellation, retain host history and input without sending full uncompressed history.

### Manual compression

Manual and automatic triggers enter the same coordinator and state machine. Manual initiation does not create a second compression implementation.

## Persistence and Invalidation

A usable record contains the chat, summary, covered boundary, stable boundary fingerprint, provider signature, model, retention count, token metrics, completion marker, and update time.

Invalidate the record when covered history, conversation branch, provider, model, or relevant system configuration changes. Failure, cancellation, timeout, or stale response invalidates only the active task or candidate; it never deletes visible host history.

## Validation and Failure

- Accept only terminal assistant Markdown satisfying the protocol, retention, tool-pair, marker, and token constraints.
- Reject tool calls, thinking-only output, malformed responses, incomplete tool pairs, internal markers, and over-threshold rebuilt contexts.
- Retry one failed summary automatically; then expose only valid retention or cancellation actions for the current task.
- Preserve original context on every error path.

## Verification Boundary

Pure assertions cover protocol, state, persistence, message ordering, request sanitization, ABI routing, and source-level integration guards. Release build and lint cover packaging. Only installation of the exact APK plus current LSPosed logs and manual scenarios can establish runtime acceptance.
