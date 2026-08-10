# Model-Driven Context Compression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current pre-send local compaction path with a request-layer, model-generated, session-persisted Markdown summary state machine while leaving the host chat history unchanged.

**Architecture:** Keep `ManualCompressionManager` as the chat-scoped coordinator, but move its behavior from local JSON compaction to a single-task state machine that owns safe-boundary waiting, pending user/recovery events, validation, persistence, retry and user-action outcomes. Add small pure-Java protocol/state/persistence helpers so the behavior can be checked without Android UI automation. Extend the existing `LSPilotEnhancerModule` and `HostAbi` hooks to submit an internal summary request through the host provider stream path, isolate its terminal response, and replace only later provider request messages with the stable summary baseline plus post-boundary messages.

**Tech Stack:** Android Java 8, Xposed/libxposed API 102.0.0, `org.json`, existing reflection-based `HostAbi`, existing single-thread/executor patterns, `assert`-based JVM checks, Gradle Android build and lint. No new dependency and no UI automation framework.

## Global Constraints

- Preserve the host screen/history as the source of truth: do not delete, rewrite, clear, reload, or restore the host chat history as part of compression.
- Use the current conversation Provider, model, system prompt, tools, request parameters, authentication, headers, serialization and cache-related configuration for the hidden summary request.
- The summary request is internal and terminal: never execute tools or let tool calls, thinking-only responses, malformed Markdown, or an error response commit a summary.
- The fixed summary is Markdown with exactly these ordered sections: `核心任务状态`, `行为设定`, `分阶段的对话历史概要`, `关键信息与关键上下文`, `最近 N 轮格式化完整上下文`, `当前激活的 skill/函数/工具/MCP 等包信息`, `后续执行要求`.
- Default `N` is 3; only the explicit user choices 2, 1 and cancel may change the retention count after the configured 3-round attempt remains over threshold.
- Count only the next effective provider context; exclude `pendingInput`, module status messages, internal fields, and summary-request usage from the session threshold.
- Automatic checks run after each complete assistant, thinking, tool-call or tool-result event, not after streaming chunks; an unpaired tool chain waits for a safe boundary.
- During `WAITING_SAFE_BOUNDARY`, the host request continues, the stop button keeps its host state, and the module blocks new sends. During `SUMMARIZING`, `RETRYING`, `VALIDATING` and `AWAITING_USER_ACTION`, send and stop are disabled while input remains editable.
- A blocked user message is displayed by the host first, is excluded from the summary source, and is sent exactly once as `summaryBaseline + pendingUserRequest` only after a successful commit. Cancellation does not auto-send it.
- Persist summaries per chat using a complete/atomic record. A record is usable only when its boundary fingerprint, Provider signature, model and configuration still match current history/configuration.
- Any history edit/delete, branch change, chat switch, Provider/model/critical system change, cancellation, timeout, failure or stale response invalidates the active summary/task without sending uncompressed fallback history.
- Module status messages use the existing marker path and remain visible/persistent, but request sanitization must fail closed if marker filtering cannot be completed.
- Preserve the preview.23 `ela$b` route and existing tool-call/tool-result field compatibility; do not expose `_lspilot_tool_calls` or `_lspilot_tool_call_id` to the Provider.
- Keep `minSdk 26`, `targetSdk 29`, Java source/target 8, and the current dependency set. Do not add UI automation tests.
- Every non-trivial parser, state transition, message ordering rule, retry path, persistence rule and request reconstruction rule must have one runnable `assert`-based check.

---

## File and Responsibility Map

The implementation should use the following files and ownership boundaries. Do not add another abstraction unless an existing ABI constraint makes one of these responsibilities impossible to keep testable.

- Create `app/src/main/java/dev/operit/lspilot/enhancer/SummaryProtocol.java`: fixed section constants, prompt construction, Markdown parser/validator, round/tool-pair validation and summary-baseline wrapper. This is pure logic except `org.json` usage.
- Create `app/src/main/java/dev/operit/lspilot/enhancer/CompressionStateMachine.java`: pure state enum, task identity, legal transitions and outcome decisions for safe-boundary waiting, retry, retention-choice and cancellation.
- Create `app/src/main/java/dev/operit/lspilot/enhancer/SummaryRecordStore.java`: per-chat complete-record persistence, stable boundary fingerprinting and invalidation. Use the module's existing preference/context access pattern; do not write a hidden host message.
- Create `app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java`: one executable non-UI check covering protocol, state machine, ordering, fingerprint/persistence semantics and request reconstruction contracts.
- Modify `app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java`: replace the local `ContextCompression.compact(...)` workflow with the state-machine coordinator; capture effective messages after each completed event, wait for safe boundaries, own pending/recovery queues, post marker statuses, validate/commit summaries and expose request-layer snapshots/actions.
- Modify `app/src/main/java/dev/operit/lspilot/enhancer/LSPilotEnhancerModule.java`: remove the send-before-local-compression replay path, intercept sends/events at the request/stream layer, invoke internal summary requests through the existing provider stream method, isolate summary callbacks, and apply the manager's effective provider message list to ordinary requests.
- Modify `app/src/main/java/dev/operit/lspilot/enhancer/HostAbi.java`: expose only the missing stable operations needed by the manager/module, including current state/message serialization, selected model/config identity, message event creation/submission if required by the existing callback path, and an internal provider stream invocation that preserves the existing config/request ABI.
- Modify `app/src/main/java/dev/operit/lspilot/enhancer/InjectedUiController.java` and `app/src/main/java/dev/operit/lspilot/enhancer/NativeChatTopBarAction.java`: project state-machine states to the existing compression overlay/panel and action callbacks; expose retry, retain-2, retain-1 and cancel operations while preserving existing UI injection patterns.
- Modify `app/src/main/java/dev/operit/lspilot/enhancer/ModuleSettings.java`: retain existing threshold/enabled settings, add only the fixed default retention constant if no existing setting can represent it, and ensure the manual action uses the shared model path.
- Do not modify `ContextCompression.java` for normal automatic/manual execution. Retain it only for legacy helper compatibility until all callers are removed; delete it only if the compiler and existing ABI checks prove it has no remaining caller.

---

### Task 1: Extract and Lock the Summary Protocol

**Files:**
- Create: `app/src/main/java/dev/operit/lspilot/enhancer/SummaryProtocol.java`
- Create: `app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `org.json.JSONArray` effective messages, `int keepRecent`, `int thresholdTokens`, and the current host-visible Provider context snapshot supplied by the coordinator.
- Produces: `SummaryProtocol.SECTION_TITLES`, `SummaryProtocol.buildPrompt(JSONArray source, int keepRecent, int thresholdTokens, String activeContext)`, `SummaryProtocol.validateTerminalMarkdown(String markdown, JSONArray source, int keepRecent)`, `SummaryProtocol.wrapBaseline(String markdown)`, `SummaryProtocol.hasCompleteToolPairs(JSONArray messages)`, `SummaryProtocol.estimateTokens(JSONArray messages)`, immutable `SummaryProtocol.Validation`/`SummaryProtocol.Round` values used by later tasks, and Gradle task `:app:buildModelCompressionCheckDex` producing `out/model-compression-check/classes.dex`.

- [ ] **Step 1: Write failing protocol assertions**

Add assertions to `ModelContextCompressionCheck.main` for:

```java
String prompt = SummaryProtocol.buildPrompt(source, 3, 1000,
        "skill=brainstorming; tools=current request");
assertContains(prompt, "## 核心任务状态");
assertContains(prompt, "## 最近 3 轮格式化完整上下文");
assertOrdered(prompt, SummaryProtocol.SECTION_TITLES);

SummaryProtocol.Validation valid = SummaryProtocol.validateTerminalMarkdown(
        validMarkdown(3), source, 3);
assertTrue(valid.success, "valid seven-section summary must pass");
assertTrue(!SummaryProtocol.validateTerminalMarkdown(
        malformedOrderMarkdown(), source, 3).success,
        "wrong section order must fail");
assertTrue(!SummaryProtocol.validateTerminalMarkdown(
        toolCallOnlyResponse(), source, 3).success,
        "tool-call response must fail without executing a tool");
assertTrue(!SummaryProtocol.validateTerminalMarkdown(
        markdownWithEnhancerMarker(), source, 3).success,
        "module marker must not enter a summary");
assertEquals("user", SummaryProtocol.wrapBaseline(validMarkdown(3)).optString("role"));
assertTrue(SummaryProtocol.hasCompleteToolPairs(pairedToolMessages()),
        "paired tool calls must pass");
assertTrue(!SummaryProtocol.hasCompleteToolPairs(unpairedToolMessages()),
        "unpaired tool calls must fail");
```

The helper methods in the check must construct the seven exact headings, three numbered rounds, paired IDs and malformed variants explicitly; do not use a network fixture or UI object.

- [ ] **Step 2: Run the focused check and verify failure**

Run:

```bash
bash ./gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac \
  --no-daemon --console=plain
```

Expected: FAIL during compilation because `SummaryProtocol` and its returned types do not yet exist, while the existing project compiles unchanged.

- [ ] **Step 3: Write the minimal protocol and check-dex task**

Use one shared title array for both prompt and parser. The validator must reject missing/duplicate/out-of-order headings, empty sections, wrong round count, marker/internal fields, non-terminal/tool-call response metadata and unpaired tool IDs. `wrapBaseline` must return one JSON user message with a fixed plain-text wrapper around the exact Markdown, while preserving the Markdown text byte-for-byte inside the wrapper. `estimateTokens` must use the existing conservative character/UTF-8 byte estimator rather than adding a tokenizer dependency.

In `app/build.gradle.kts`, add `buildModelCompressionCheckDex` as the one reusable test facility. It must depend on `compileDebugJavaWithJavac` and `compileDebugUnitTestJavaWithJavac`, delete/recreate `out/model-compression-check`, and invoke `${android.sdkDirectory}/build-tools/29.0.3/d8` with both compiled class directories plus the existing compile/runtime classpath. The output is `out/model-compression-check/classes.dex`; do not add JUnit or another dependency.

- [ ] **Step 4: Run the protocol check and verify pass**

Run:

```bash
bash ./gradlew :app:buildModelCompressionCheckDex --no-daemon --console=plain
adb shell mkdir -p /data/local/tmp/model-compression-check
adb push out/model-compression-check/classes.dex /data/local/tmp/model-compression-check/classes.dex
adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
  dev.operit.lspilot.enhancer.ModelContextCompressionCheck
```

Expected: PASS for the new assertions and all protocol fixtures. The task must create the dex from the debug main classes plus debug unit-test classes, and the Android runtime must print no assertion failure.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/operit/lspilot/enhancer/SummaryProtocol.java \
        app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java \
        app/build.gradle.kts
git commit -m "feat: add fixed model summary protocol"
```

---

### Task 2: Add Pure State and Summary Record Semantics

**Files:**
- Create: `app/src/main/java/dev/operit/lspilot/enhancer/CompressionStateMachine.java`
- Create: `app/src/main/java/dev/operit/lspilot/enhancer/SummaryRecordStore.java`
- Modify: `app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java`

**Interfaces:**
- Consumes: `SummaryProtocol.Validation`, chat ID, task ID, Provider signature, model, boundary messages and retention count.
- Produces: `CompressionStateMachine.State` (`IDLE`, `WAITING_SAFE_BOUNDARY`, `SUMMARIZING`, `RETRYING`, `VALIDATING`, `AWAITING_USER_ACTION`), `CompressionStateMachine.Event`, `CompressionStateMachine.Action` (`RETRY`, `KEEP_2`, `KEEP_1`, `CANCEL`), `CompressionStateMachine.Task`, `CompressionStateMachine.transition(State, Event)`, `CompressionStateMachine.isCurrent(Task, long activeTaskId, String chatId)`, `SummaryRecordStore.Record`, `SummaryRecordStore.fingerprint(JSONArray messages)`, `SummaryRecordStore.writeComplete(String chatId, Record record)`, `SummaryRecordStore.readUsable(String chatId, JSONArray currentMessages, String providerSignature, String model)`, and `SummaryRecordStore.invalidate(String chatId)`.

- [ ] **Step 1: Write failing state/persistence assertions**

Extend `ModelContextCompressionCheck.main` with:

```java
assertEquals(CompressionStateMachine.State.WAITING_SAFE_BOUNDARY,
        CompressionStateMachine.transition(CompressionStateMachine.State.IDLE,
                CompressionStateMachine.Event.OVER_LIMIT_UNPAIRED));
assertEquals(CompressionStateMachine.State.SUMMARIZING,
        CompressionStateMachine.transition(CompressionStateMachine.State.IDLE,
                CompressionStateMachine.Event.OVER_LIMIT_SAFE));
assertEquals(CompressionStateMachine.State.RETRYING,
        CompressionStateMachine.transition(CompressionStateMachine.State.VALIDATING,
                CompressionStateMachine.Event.FIRST_FAILURE));
assertEquals(CompressionStateMachine.State.AWAITING_USER_ACTION,
        CompressionStateMachine.transition(CompressionStateMachine.State.RETRYING,
                CompressionStateMachine.Event.FAILURE_EXHAUSTED));
assertTrue(CompressionStateMachine.isCurrent(task, task.taskId, "chat-1"),
        "current task must accept its response");
assertTrue(!CompressionStateMachine.isCurrent(task, task.taskId, "chat-2"),
        "switched chat must reject late response");

String first = SummaryRecordStore.fingerprint(source);
String same = SummaryRecordStore.fingerprint(copyWithTransientFields(source));
String changed = SummaryRecordStore.fingerprint(copyWithChangedContent(source));
assertEquals(first, same, "transient host fields must not alter boundary fingerprint");
assertTrue(!first.equals(changed), "stable content edit must invalidate the boundary");
```

Use a temporary preference-backed store or the store's injected key/value adapter in the check so the test verifies incomplete records are ignored and complete records are round-trippable without a host database.

- [ ] **Step 2: Run the focused check and verify failure**

Run:

```bash
bash ./gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac \
  --no-daemon --console=plain
```

Expected: FAIL because the state enum/events, current-task guard, fingerprint and complete-record read/write APIs are not implemented.

- [ ] **Step 3: Implement legal transitions and record semantics**

Make illegal transitions return the current state or a rejected result, never silently enter a sendable state. `Task` must contain chat ID, monotonically increasing task ID, boundary fingerprint, Provider signature, model and keepRecent. `Record` must contain chat ID, summary text, covered boundary, fingerprint, Provider signature, model, keepRecent, before/after token counts, reduction and ratio, completion flag and update time. Fingerprints must serialize only stable role/content/tool-call ID/tool-result ID/tool-call-presence fields after removing module status messages and host transient fields. Write all fields before setting the completion flag; read only complete records and revalidate fingerprint/provider/model. Use an injected small key/value adapter or existing module preferences so the pure check does not require an Android UI or host repository.

- [ ] **Step 4: Run the state/persistence check and verify pass**

Run:

```bash
bash ./gradlew :app:buildModelCompressionCheckDex --no-daemon --console=plain
adb push out/model-compression-check/classes.dex /data/local/tmp/model-compression-check/classes.dex
adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
  dev.operit.lspilot.enhancer.ModelContextCompressionCheck
```

Expected: PASS for all legal transitions, stale-task rejection, transient-field stability, content-change invalidation, incomplete-record rejection and complete-record round trip.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/operit/lspilot/enhancer/CompressionStateMachine.java \
        app/src/main/java/dev/operit/lspilot/enhancer/SummaryRecordStore.java \
        app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java
git commit -m "feat: add compression state and summary persistence"
```

---

### Task 3: Rebuild the Compression Coordinator Around Effective Context

**Files:**
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/ModuleSettings.java`
- Modify: `app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java`

**Interfaces:**
- Consumes: `SummaryProtocol`, `CompressionStateMachine`, `SummaryRecordStore`, `HostAbi` snapshots, complete event callbacks and existing marker-status functions.
- Produces: `ManualCompressionManager.onCompleteEvent(...)`, `ManualCompressionManager.beginPendingUserRequest(...)`, `ManualCompressionManager.onSummaryResponse(...)`, `ManualCompressionManager.effectiveRequestMessages(JSONArray hostMessages, Object config, String model)`, `ManualCompressionManager.isSummaryTaskActive()`, `ManualCompressionManager.onCompressionAction(CompressionStateMachine.Action)`, `ManualCompressionManager.applyPrepared(...)` compatibility replacement or removal, and state projections consumed by the module/UI hooks.

- [ ] **Step 1: Write failing coordinator assertions**

Add pure manager-facing assertions or a small injected coordinator seam to verify:

```java
JSONArray summarySource = managerSummarySource(messagesBeforeUser, pendingUser);
assertFalse(containsMessage(summarySource, pendingUser),
        "blocked user message must be excluded from summary source");

JSONArray recovery = managerRecoveryMessages(summaryBaseline, pendingUser);
assertEquals(1, countMessage(recovery, pendingUser),
        "blocked user message must be sent exactly once");

assertEquals(Arrays.asList("assistant-1", "tool-call", "tool-result"),
        managerFlushOrder(pendingRecovery));
assertTrue(managerBlocksNewSend(CompressionStateMachine.State.SUMMARIZING),
        "summary states must block sends");
assertFalse(managerCountsPendingInput("draft text"),
        "unsent input must not affect threshold");
```

Also assert that an existing usable summary causes the next effective context to contain `system + one user summary baseline + post-boundary messages`, while a changed Provider signature or boundary fingerprint falls back to current unsummarized effective history and marks the record invalid.

- [ ] **Step 2: Run the focused check and verify failure**

Run:

```bash
bash ./gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac \
  --no-daemon --console=plain
```

Expected: FAIL because the old manager still calls `ContextCompression.compact(...)`, includes pending input in `updateScreen(...)`, and only has pre-send `PREPARING` behavior rather than complete-event state handling.

- [ ] **Step 3: Implement the coordinator with one task per chat**

Replace the normal automatic/manual path in `ManualCompressionManager` with:

```java
static void onCompleteEvent(String chatId, JSONArray effectiveMessages,
        boolean toolChainComplete, boolean replyFinished);
static boolean beginPendingUserRequest(String chatId, JSONArray userRequest);
static void onSummaryResponse(long taskId, String chatId, String markdown,
        boolean terminalAssistant, boolean returnedToolCall, boolean thinkingOnly);
static JSONArray effectiveRequestMessages(JSONArray hostMessages, Object config, String model);
static void onCompressionAction(CompressionStateMachine.Action action);
```

Keep the pending user request as a copied JSON message/event, not reconstructed from the input box. Trigger automatic checks only after complete events and exclude pending input from token counts. Enter `WAITING_SAFE_BOUNDARY` when tool calls are unpaired; post only the waiting marker and keep the host stop state. On the safe boundary, enter `SUMMARIZING`, post the start marker, disable send/stop through the UI projection, snapshot current Provider/config/messages and submit the internal task through the module hook. Store assistant/thinking/tool events during the task without committing them to the host display. On a valid summary, flush each stored event once before any recovery request, atomically persist the record, post token metrics and rebuild the effective request. On failure, timeout, cancellation or stale response, leave host history and pending input untouched, invalidate the active record and post a message that the original context was unchanged. Implement the exact retry and 3-to-2-to-1 retention-choice rules from the spec; no uncompressed fallback send. Remove all normal callers of `ContextCompression.compact(...)`, `adaptiveKeepRecent(...)` and pre-send local replay behavior.

Update `ModuleSettings` only as needed to expose the fixed default `3` retention value and preserve existing threshold/settings availability behavior; do not create a separate summary Provider/model setting.

- [ ] **Step 4: Run coordinator assertions and verify pass**

Run:

```bash
bash ./gradlew :app:buildModelCompressionCheckDex --no-daemon --console=plain
adb push out/model-compression-check/classes.dex /data/local/tmp/model-compression-check/classes.dex
adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
  dev.operit.lspilot.enhancer.ModelContextCompressionCheck
```

Expected: PASS for pending-user exclusion/once-only recovery, event ordering, safe-boundary blocking, pending-input exclusion, usable-baseline reconstruction, provider/history invalidation and all failure/cancel branches.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java \
        app/src/main/java/dev/operit/lspilot/enhancer/ModuleSettings.java \
        app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java
git commit -m "feat: coordinate model-driven compression by chat"
```

---

### Task 4: Wire Internal Summary Requests and Ordinary Request Reconstruction

**Files:**
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/LSPilotEnhancerModule.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/HostAbi.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java`
- Modify: `app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java`

**Interfaces:**
- Consumes: `ManualCompressionManager.effectiveRequestMessages(...)`, summary task snapshot/prompt, `HostAbi.streamMessagesMethod`, `HostAbi.buildRequestMethod`, existing SSE normalization and AutoRetry callbacks.
- Produces: internal request marker/thread-local guard, terminal-summary callback into `ManualCompressionManager.onSummaryResponse(...)`, ordinary request body with sanitized marker-free messages, copied current Provider/config/model/system/tool parameters, `SummaryProtocol.rebuildEffectiveRequest(JSONArray systemMessages, JSONObject summaryBaseline, JSONArray postBoundaryMessages, JSONObject pendingUser)`, and no summary response insertion into the host conversation.

- [ ] **Step 1: Write failing request-layer assertions**

Add assertions for the request contract:

```java
JSONArray request = SummaryProtocol.rebuildEffectiveRequest(
        systemMessages, summaryBaseline, postBoundary, pendingUser);
assertFalse(hasEnhancerMarker(request), "marker status must not be provider-visible");
assertEquals(1, countRoleAndContent(request, "user", summaryBaselineText),
        "summary baseline must be one stable user message");
assertEquals(1, countMessage(request, pendingUser),
        "pending user request must appear once");
assertEquals(0, countInternalSummaryResponse(hostMessages),
        "summary response must not enter host history");
assertTrue(summaryPromptUsesCurrentModel(snapshot),
        "summary task must use current model/provider snapshot");
```

Include a fixture where the host request contains module status messages and `_lspilot_*` fields; the expected serialized request must contain neither marker/status messages nor internal fields. Include a failure fixture where sanitization throws; assert that the request is rejected rather than returning the original unsanitized body.

- [ ] **Step 2: Run the focused check and verify failure**

Run:

```bash
bash ./gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac \
  --no-daemon --console=plain
```

Expected: FAIL because the current Hook calls `applyPrepared(...)`, returns the original body on sanitization/enhancement failure, and has no internal summary stream isolation.

- [ ] **Step 3: Implement the request-layer hook**

Refactor both named and minified request-body paths to:

```java
JSONArray clean = ManualCompressionManager.sanitizeRequestMessagesOrThrow(messages);
JSONArray effective = ManualCompressionManager.effectiveRequestMessages(clean, config, model);
body.put("messages", effective);
applyOpenAiRequestPolicy(body, model, systemPrompt, policyEnabled);
```

The sanitization method must remove marker messages and internal fields or fail closed. Preserve the already validated `ela$b` route and `HostAbi.serializeMessages(...)` tool metadata compatibility. Remove the current `AUTO_REPLAY_SEND` pre-send compression path and intercept the send entry only to register/display the pending user request and block the provider invocation while the state machine owns the task.

Use the existing `streamMessagesMethod(config, List, Function1)` route for the hidden summary request. Build a copied config/current request snapshot with the same Provider, model, system prompt, tools, parameters, authentication and headers; append the fixed summary prompt only to the internal message list; set an internal task guard so the response cannot enter normal repository/UI paths. In the stream Hook, detect the internal task before forwarding callback events to host repository/state/UI code; route every summary event only to the module collector, and restore the normal callback path only after the task is closed. If the ABI does not permit reliable callback isolation, fail the task closed and retain the original context. Wrap the isolated callback/SSE path so only a terminal assistant Markdown response is passed to `onSummaryResponse`; tool calls, thinking-only output, errors, timeout and malformed response are terminal failures and do not execute tools. Drop callbacks whose task ID, chat ID or boundary fingerprint no longer matches.

Keep normal OpenAI cache policy application on ordinary requests and ensure the stable summary baseline and copied configuration are used in subsequent request order so later prefixes remain identical until invalidation.

- [ ] **Step 4: Run request contract assertions and verify pass**

Run:

```bash
bash ./gradlew :app:buildModelCompressionCheckDex --no-daemon --console=plain
adb push out/model-compression-check/classes.dex /data/local/tmp/model-compression-check/classes.dex
adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
  dev.operit.lspilot.enhancer.ModelContextCompressionCheck
```

Expected: PASS for current-provider/model use, summary-response isolation, marker/internal-field removal, fail-closed sanitization and exact effective message ordering. Existing cache and tool-field assertions must remain green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/operit/lspilot/enhancer/LSPilotEnhancerModule.java \
        app/src/main/java/dev/operit/lspilot/enhancer/HostAbi.java \
        app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java \
        app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java
git commit -m "feat: route summaries through host provider requests"
```

---

### Task 5: Project State to Existing Compression UI

**Files:**
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/InjectedUiController.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/NativeChatTopBarAction.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java`
- Modify: `app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java`

**Interfaces:**
- Consumes: `CompressionStateMachine.State`, current task ID/chat ID, `ManualCompressionManager.onCompressionAction(CompressionStateMachine.Action)`, and existing `refreshPanel`/overlay hooks.
- Produces: `ManualCompressionManager.UiProjection.forState(CompressionStateMachine.State state, boolean hostStopActive)`, action callbacks `ManualCompressionManager.onCompressionAction(CompressionStateMachine.Action)`, visible state projection for waiting/active/awaiting-action/idle, disabled send/stop projection for active summary states, and retained editable input.

- [ ] **Step 1: Write failing UI-projection assertions without UI automation**

Add pure projection assertions to `ModelContextCompressionCheck`:

```java
ManualCompressionManager.UiProjection waiting = ManualCompressionManager.UiProjection.forState(
        CompressionStateMachine.State.WAITING_SAFE_BOUNDARY, true);
assertTrue(waiting.sendBlocked, "waiting must block new sends");
assertTrue(waiting.stopUsesHostState, "waiting must preserve host stop state");
ManualCompressionManager.UiProjection summarizing = ManualCompressionManager.UiProjection.forState(
        CompressionStateMachine.State.SUMMARIZING, false);
assertTrue(summarizing.sendDisabled && summarizing.stopDisabled,
        "formal compression must disable send and stop");
ManualCompressionManager.UiProjection idle = ManualCompressionManager.UiProjection.forState(
        CompressionStateMachine.State.IDLE, false);
assertTrue(!idle.sendBlocked && !idle.sendDisabled, "idle must restore host controls");
```

- [ ] **Step 2: Run the projection check and verify failure**

Run:

```bash
bash ./gradlew :app:compileDebugJavaWithJavac :app:compileDebugUnitTestJavaWithJavac \
  --no-daemon --console=plain
```

Expected: FAIL because the current overlay exposes only local `PREPARING` state and does not distinguish safe-boundary waiting from formal summary/retry/action states.

- [ ] **Step 3: Implement the existing UI projection**

Keep the existing panel/overlay injection and add state-driven rendering. Show the exact waiting and start/success/failure/cancel status text through the existing marker message path. Wire the existing manual compression action to `onCompressionAction(RETRY)` and add only the actions required by the spec: retain 2, retain 1 and cancel. The callbacks must include the active task/chat identity and be ignored when stale. Keep the text field editable and do not clear or auto-send its content. Disable send/stop only for `SUMMARIZING`, `RETRYING`, `VALIDATING` and `AWAITING_USER_ACTION`; do not forcibly disable stop during `WAITING_SAFE_BOUNDARY`.

- [ ] **Step 4: Run the projection check and verify pass**

Run:

```bash
bash ./gradlew :app:buildModelCompressionCheckDex --no-daemon --console=plain
adb push out/model-compression-check/classes.dex /data/local/tmp/model-compression-check/classes.dex
adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
  dev.operit.lspilot.enhancer.ModelContextCompressionCheck
```

Expected: PASS for all state projections and action identity checks. No UI automation command is added or run.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/operit/lspilot/enhancer/InjectedUiController.java \
        app/src/main/java/dev/operit/lspilot/enhancer/NativeChatTopBarAction.java \
        app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java \
        app/src/test/java/dev/operit/lspilot/enhancer/ModelContextCompressionCheck.java
git commit -m "feat: expose compression state and actions"
```

---

### Task 6: Replace All Legacy Entry Points and Run Integration Checks

**Files:**
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/LSPilotEnhancerModule.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java`
- Modify: `app/src/main/java/dev/operit/lspilot/enhancer/ContextCompression.java` only if no non-test callers remain
- Modify: `CHANGELOG.md` only if the project release workflow requires a feature entry
- Modify: `app/build.gradle.kts`: retain the Task 1 `buildModelCompressionCheckDex` task and include every final check class; do not change version values

**Interfaces:**
- Consumes: all task 1-5 APIs and existing ABI/cache/retry checks.
- Produces: no runtime path that invokes `ContextCompression.compact(...)` for normal automatic/manual compression; one integrated request/state path for both triggers.

- [ ] **Step 1: Write the legacy-path guard assertion**

Add a source-level or reflection assertion in `ModelContextCompressionCheck` that the normal manager/module path has no call to `ContextCompression.compact(...)` and that manual and automatic entry points both dispatch to the same `onCompressionAction`/task creation path. Keep `CompressionSequenceCheck` only for the still-supported tool-boundary helper if it remains referenced.

- [ ] **Step 2: Run all focused and existing checks before build**

Run:

```bash
bash ./gradlew :app:buildModelCompressionCheckDex \
  :app:assembleRelease :app:lintRelease --no-daemon --console=plain -x lintVitalRelease
adb shell mkdir -p /data/local/tmp/model-compression-check
adb push out/model-compression-check/classes.dex /data/local/tmp/model-compression-check/classes.dex
adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
  dev.operit.lspilot.enhancer.ModelContextCompressionCheck
```

Expected: the new model compression check passes on Android; the release APK assembles; lint reports no new errors. Do not run UI automation.

- [ ] **Step 3: Run the existing runtime/ABI checks**

Run each `main` already included in `classes.dex`:

```bash
for check in \
  ModelContextCompressionCheck CompressionSequenceCheck HostStateRestoreCheck \
  AutoRetryManagerCheck ManualCompressionManager HostAbi HostAbiCache \
  DexAbiScanner DexKitAbiScanner AutoRetryPolicy PromptCachePolicy ReasoningPolicy
do
  adb shell dalvikvm -ea -cp /data/local/tmp/model-compression-check/classes.dex \
    dev.operit.lspilot.enhancer.$check
done
```

Expected: every check exits 0. Pass the established host-Dex arguments to `DexAbiScanner`/`DexKitAbiScanner` when their `main` entry requires them; retain the exact arguments from the preview.23 verification environment. This covers compression sequence, host state restoration, AutoRetry, legacy DEX ABI, DexKit ABI, ABI cache, prompt cache and reasoning policies, including `ela$b` route resolution and tool-call/tool-result field preservation.

- [ ] **Step 4: Run static plan-to-code guards**

Run:

```bash
rg -n 'ContextCompression\.compact|AUTO_REPLAY_SEND|prepared context|return originalResult' \
  app/src/main/java/dev/operit/lspilot/enhancer
rg -n '_lspilot_tool_calls|_lspilot_tool_call_id|系统提示|正在压缩上下文|等待当前工具调用完成' \
  app/src/main/java/dev/operit/lspilot/enhancer
```

Expected: no normal compression path calls the old local compactor or silently falls back to an unsanitized/original full-history request; internal tool fields appear only in controlled serialization/sanitization code; required status strings have one marker-filtered display path.

- [ ] **Step 5: Commit the integrated implementation**

```bash
git add app/src/main/java app/src/test/java
# Add CHANGELOG.md or app/build.gradle.kts only when the release workflow explicitly requires it.
git commit -m "feat: replace local compaction with model-driven context compression"
```

---

### Task 7: Device Manual Acceptance and Release Gate

**Files:**
- No production file changes expected; collect logs/screenshots outside the repository only if the project release process requires them.

**Interfaces:**
- Consumes: release APK from Task 6, current host app `me.yun.lspilot` version 1.1.0(11), and configured Provider/model.
- Produces: manual acceptance result for the exact scenarios in the approved specification.

- [ ] **Step 1: Install the release APK on the target device and open a chat**

Confirm LSPosed logs contain ABI resolution, request Hook installation, stream Hook installation and model-compression initialization without an exception that disables context compression.

- [ ] **Step 2: Verify automatic safe-boundary behavior**

Create enough completed context to exceed the configured threshold. Verify after a complete event that waiting-tool status appears for an unpaired tool chain, the host request continues, stop retains its host state, and new sends are blocked. After the chain closes, verify the persistent start message appears exactly when the hidden summary request begins, send/stop disable, and the input field remains editable.

- [ ] **Step 3: Verify summary success and recovery**

Confirm the summary Markdown itself never appears in the host chat. Confirm original screen history is unchanged, non-user events generated during compression are restored once and in original order before recovery, success status includes before/after token counts, reduction and ratio, and a pending user message appears on screen first but is sent exactly once as `summaryBaseline + pendingUserRequest`.

- [ ] **Step 4: Verify failure, cancellation and retention choices**

Force or observe provider error/timeout/malformed summary behavior. Confirm one automatic retry, then original context/input preservation, no uncompressed fallback send, and explicit failure status. Verify retain 2, retain 1 and cancel paths, including 2-round over-limit leading to 1/cancel and 1-round over-limit leading to cancel only. Cancellation must not auto-send the pending request.

- [ ] **Step 5: Verify persistence and invalidation**

Restart the host/module and confirm a completed summary remains usable for the same chat. Edit/delete a message covered by the boundary or switch branch/provider/model/system configuration; confirm the summary is immediately unusable and the next compression rebuilds from current visible history. Confirm module status messages remain visible but are absent from captured Provider requests.

- [ ] **Step 6: Record the final release gate**

The feature is complete only when automatic and manual paths use the model state machine, all validation/failure invariants hold, existing non-UI checks/build/lint pass, and all device scenarios above pass. Do not add UI automation tests or publish a new version in this plan unless explicitly requested after acceptance.

---

## Verification Matrix

- Protocol: `ModelContextCompressionCheck` validates seven headings, order, non-empty sections, N=3/2/1, terminal Markdown, no marker/internal fields and complete tool pairs.
- State: the same check validates legal transitions, one active task, stale response rejection, waiting boundary, retry exhaustion, retention choices and cancel.
- Message flow: the same check validates pending-user exclusion, once-only recovery, event buffering/order and effective request reconstruction.
- Persistence: the same check validates complete-record writes, incomplete-record rejection, transient-field-insensitive fingerprints and history/provider/model invalidation.
- Existing behavior: `CompressionSequenceCheck`, `HostStateRestoreCheck`, `AutoRetryManagerCheck`, legacy DEX ABI, DexKit ABI, ABI cache and AutoRetry checks remain green.
- Build: `bash ./gradlew :app:assembleRelease --no-daemon --console=plain -x lintVitalRelease`.
- Lint: `bash ./gradlew :app:lintRelease --no-daemon --console=plain`.
- Device: manual acceptance only; no UI automation.

## Plan Self-Review

- Spec coverage: sections 1-3 are covered by Tasks 1-3; request-layer/cache requirements in sections 4-6 are covered by Task 4; the fixed protocol and validation in section 7 are covered by Task 1 and Task 4; persistence/invalidation in section 8 are covered by Task 2 and Task 3; UI and button states in section 9 are covered by Task 5; concurrency/error rules in section 10 are covered by Tasks 2-4; compatibility boundaries in section 11 are enforced by Task 6; non-UI/build/device verification in sections 12-13 are covered by Tasks 6-7.
- Placeholder scan: no forbidden placeholder or unassigned test step is used. Every task names exact files, interfaces, failing assertions, commands, expected failure/pass behavior and commit commands.
- Type consistency: `SummaryProtocol.Validation`, `CompressionStateMachine.State/Event/Task/Action`, `SummaryRecordStore.Record`, `ManualCompressionManager.UiProjection`, `onSummaryResponse(...)` and `effectiveRequestMessages(...)` are defined in the file map or producing task before later tasks consume them.
- Deliberate scope: no new Provider client, tokenizer, database schema, hidden host message or UI automation framework is planned. The existing `ContextCompression` helper is retained only if a verified caller remains; normal automatic/manual execution must not use it.