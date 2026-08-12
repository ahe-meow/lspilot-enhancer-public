package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Runnable non-UI protocol checks; assertions are enabled by the dalvikvm command. */
public final class ModelContextCompressionCheck {
    private ModelContextCompressionCheck() {
    }

    public static void main(String[] args) throws Exception {
        JSONArray source = pairedToolMessages();
        String prompt = SummaryProtocol.buildPrompt(source, 3, 1000,
                "skill=brainstorming; tools=current request");
        assertContains(prompt, "## 核心任务状态");
        assertContains(prompt, "## 最近 3 轮格式化完整上下文");
        assertOrdered(prompt, SummaryProtocol.SECTION_TITLES);

        SummaryProtocol.Validation valid = SummaryProtocol.validateTerminalMarkdown(
                validMarkdown(3), source, 3);
        assertTrue(valid.success, "valid seven-section summary must pass");
        assertEquals(3, valid.rounds.size(), "three rounds must be parsed");
        assertTrue(!SummaryProtocol.validateTerminalMarkdown(
                malformedOrderMarkdown(), source, 3).success,
                "wrong section order must fail");
        assertTrue(!SummaryProtocol.validateTerminalMarkdown(
                toolCallOnlyResponse(), source, 3).success,
                "tool-call response must fail without executing a tool");
        assertTrue(!SummaryProtocol.validateTerminalMarkdown(
                markdownWithEnhancerMarker(), source, 3).success,
                "module marker must not enter a summary");
        assertEquals("user", SummaryProtocol.wrapBaseline(validMarkdown(3)).optString("role"),
                "baseline must be a user message");
        assertContains(SummaryProtocol.wrapBaseline(validMarkdown(3)).optString("content"),
                validMarkdown(3));
        assertTrue(SummaryProtocol.hasCompleteToolPairs(pairedToolMessages()),
                "paired tool calls must pass");
        assertTrue(!SummaryProtocol.hasCompleteToolPairs(unpairedToolMessages()),
                "unpaired tool calls must fail");
        assertTrue(SummaryProtocol.estimateTokens(source) > 0,
                "effective context must have a positive estimate");

        assertEquals(CompressionStateMachine.State.WAITING_SAFE_BOUNDARY,
                CompressionStateMachine.transition(CompressionStateMachine.State.IDLE,
                        CompressionStateMachine.Event.OVER_LIMIT_UNPAIRED),
                "unpaired over-limit context must wait");
        assertEquals(CompressionStateMachine.State.SUMMARIZING,
                CompressionStateMachine.transition(CompressionStateMachine.State.IDLE,
                        CompressionStateMachine.Event.OVER_LIMIT_SAFE),
                "safe over-limit context must summarize");
        assertEquals(CompressionStateMachine.State.RETRYING,
                CompressionStateMachine.transition(CompressionStateMachine.State.VALIDATING,
                        CompressionStateMachine.Event.FIRST_FAILURE),
                "first validation failure must retry");
        assertEquals(CompressionStateMachine.State.AWAITING_USER_ACTION,
                CompressionStateMachine.transition(CompressionStateMachine.State.RETRYING,
                        CompressionStateMachine.Event.FAILURE_EXHAUSTED),
                "exhausted retry must await action");

        CompressionStateMachine.Task task = CompressionStateMachine.newTask(
                "chat-1", "boundary", "provider-a", "model-a", 3);
        assertTrue(CompressionStateMachine.isCurrent(task, task.taskId, "chat-1"),
                "current task must accept its response");
        assertTrue(!CompressionStateMachine.isCurrent(task, task.taskId, "chat-2"),
                "switched chat must reject late response");

        String first = SummaryRecordStore.fingerprint(source);
        String same = SummaryRecordStore.fingerprint(copyWithTransientFields(source));
        String changed = SummaryRecordStore.fingerprint(copyWithChangedContent(source));
        assertEquals(first, same, "transient host fields must not alter boundary fingerprint");
        assertTrue(!first.equals(changed), "stable content edit must invalidate the boundary");

        MemoryStore memory = new MemoryStore();
        SummaryRecordStore.useStore(memory);
        SummaryRecordStore.Record record = new SummaryRecordStore.Record(
                "chat-1", "summary", source, first, "provider-a", "model-a",
                3, 100, 50, 50, 50d, true, 123L);
        SummaryRecordStore.writeComplete("chat-1", record);
        assertTrue(SummaryRecordStore.readUsable("chat-1", source, "provider-a", "model-a") != null,
                "complete record must round-trip");
        memory.values.put(memory.lastKey,
                new JSONObject(memory.values.get(memory.lastKey)).put("complete", false).toString());
        assertTrue(SummaryRecordStore.readUsable("chat-1", source, "provider-a", "model-a") == null,
                "incomplete record must be ignored");
        SummaryRecordStore.writeComplete("chat-1", record);
        assertTrue(SummaryRecordStore.readUsable("chat-1", source, "provider-b", "model-a") == null,
                "provider change must invalidate record");
        assertTrue(SummaryRecordStore.readUsable("chat-1", copyWithChangedContent(source),
                "provider-a", "model-a") == null,
                "history change must invalidate record");
        SummaryRecordStore.invalidate("chat-1");
        assertTrue(SummaryRecordStore.readUsable("chat-1", source, "provider-a", "model-a") == null,
                "explicit invalidation must remove record");

        JSONArray pendingUser = new JSONArray().put(message("user", "blocked request"));
        JSONArray sourceWithPending = copy(source);
        sourceWithPending.put(pendingUser.getJSONObject(0));
        JSONArray summarySource = ManualCompressionManager.excludePendingUser(
                sourceWithPending, pendingUser);
        assertEquals(source.length(), summarySource.length(),
                "blocked user message must be excluded from summary source");
        JSONArray recovery = new JSONArray().put(
                SummaryProtocol.wrapBaseline(validMarkdown(3)));
        recovery = ManualCompressionManager.appendPendingUserOnce(recovery, pendingUser);
        recovery = ManualCompressionManager.appendPendingUserOnce(recovery, pendingUser);
        assertEquals(1, countMessage(recovery, pendingUser.getJSONObject(0)),
                "blocked user message must be recovered exactly once");

        ManualCompressionManager.bufferRecoveryEvents(new JSONArray()
                .put(message("assistant", "assistant-1"))
                .put(message("assistant", "tool-call"))
                .put(message("tool", "tool-result")));
        JSONArray flushed = ManualCompressionManager.drainRecoveryEvents();
        assertEquals("assistant-1", flushed.getJSONObject(0).optString("content"),
                "recovery events must preserve assistant order");
        assertEquals("tool-call", flushed.getJSONObject(1).optString("content"),
                "recovery events must preserve tool-call order");
        assertEquals("tool-result", flushed.getJSONObject(2).optString("content"),
                "recovery events must preserve tool-result order");
        assertEquals(0, ManualCompressionManager.drainRecoveryEvents().length(),
                "recovery events must flush only once");
        assertTrue(ManualCompressionManager.blocksNewSend(
                        CompressionStateMachine.State.SUMMARIZING),
                "summary states must block new sends");
        assertTrue(!ManualCompressionManager.blocksNewSend(CompressionStateMachine.State.IDLE),
                "idle state must allow sends");
        ManualCompressionManager.UiProjection waiting =
                ManualCompressionManager.UiProjection.forState(
                        CompressionStateMachine.State.WAITING_SAFE_BOUNDARY, true);
        assertTrue(waiting.sendBlocked, "waiting must block new sends");
        assertTrue(waiting.stopUsesHostState, "waiting must preserve host stop state");
        ManualCompressionManager.UiProjection summarizing =
                ManualCompressionManager.UiProjection.forState(
                        CompressionStateMachine.State.SUMMARIZING, false);
        assertTrue(summarizing.sendDisabled && summarizing.stopDisabled,
                "formal compression must disable send and stop");
        ManualCompressionManager.UiProjection idle =
                ManualCompressionManager.UiProjection.forState(
                        CompressionStateMachine.State.IDLE, false);
        assertTrue(!idle.sendBlocked && !idle.sendDisabled,
                "idle must restore host controls");
        assertEquals(3, ModuleSettings.getSummaryKeepRecent(),
                "model summary retention must default to three rounds");
        assertEquals(SummaryProtocol.estimateTokens(source),
                ManualCompressionManager.completedContextTokens(source),
                "threshold must count completed messages without pending input");

        pendingSourceExcludesBlockedInput();

        String effectiveChat = "chat-effective";
        ManualCompressionManager.enterChat(effectiveChat);
        JSONArray boundary = pairedToolMessages();
        SummaryRecordStore.Record effectiveRecord = new SummaryRecordStore.Record(
                effectiveChat, validMarkdown(3), boundary,
                SummaryRecordStore.fingerprint(boundary), "provider-a", "model-a",
                3, 1000, 400, 600, 60d, true, 456L);
        SummaryRecordStore.writeComplete(effectiveChat, effectiveRecord);
        JSONArray hostMessages = copy(boundary);
        hostMessages.put(message("assistant", "post-boundary"));
        JSONArray effective = ManualCompressionManager.effectiveRequestMessages(
                hostMessages, "provider-a", "model-a");
        assertEquals(3, effective.length(),
                "usable summary must rebuild system, baseline and post-boundary context");
        assertEquals("system", effective.getJSONObject(0).optString("role"),
                "rebuilt context must retain current system message");
        assertContains(effective.getJSONObject(1).optString("content"),
                SummaryProtocol.BASELINE_PREFIX);
        assertEquals("post-boundary", effective.getJSONObject(2).optString("content"),
                "rebuilt context must retain post-boundary messages");

        JSONObject summaryBaseline = SummaryProtocol.wrapBaseline(validMarkdown(3));
        JSONObject pendingSingle = message("user", "blocked request");
        JSONArray dirtyPostBoundary = new JSONArray()
                .put(new JSONObject().put("role", "assistant")
                        .put("content", "[系统提示 · 上下文压缩]\nworking"))
                .put(new JSONObject().put("role", "assistant").put("content", "after")
                        .put("_lspilot_tool_calls", new JSONArray()
                                .put(new JSONObject().put("id", "call_hidden"))));
        JSONArray rebuiltRequest = SummaryProtocol.rebuildEffectiveRequest(
                new JSONArray().put(message("system", "system")),
                summaryBaseline, dirtyPostBoundary, pendingSingle);
        assertTrue(!hasEnhancerMarker(rebuiltRequest),
                "marker status must not be provider-visible");
        assertTrue(!hasInternalFields(rebuiltRequest),
                "internal fields must not be provider-visible");
        assertEquals(1, countRoleAndContent(rebuiltRequest, "user",
                        summaryBaseline.optString("content")),
                "summary baseline must be one stable user message");
        assertEquals(1, countMessage(rebuiltRequest, pendingSingle),
                "pending user request must appear once");
        assertEquals(0, countRoleAndContent(hostMessages, "assistant",
                        summaryBaseline.optString("content")),
                "summary response must not enter host history");
        assertTrue(ManualCompressionManager.sanitizeRequestMessagesOrThrow(
                        dirtyPostBoundary).length() == 1,
                "sanitization must remove marker status messages");
        try {
            ManualCompressionManager.sanitizeRequestMessagesOrThrow(null);
            throw new AssertionError("sanitization must fail closed");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertTrue(!ManualCompressionManager.isInternalSummaryRequest(new JSONArray()
                        .put(new JSONObject().put("role", "user")
                                .put("content", "## 核心任务状态\nordinary\n## 后续执行要求\ncontinue")
                                .put("_lspilot_host_index", 1))),
                "ordinary user content must not impersonate an internal summary request");
        JSONArray providerFallback = ManualCompressionManager.effectiveRequestMessages(
                hostMessages, "provider-b", "model-a");
        assertEquals(hostMessages.length(), providerFallback.length(),
                "provider change must fall back to unsummarized effective history");
        SummaryRecordStore.writeComplete(effectiveChat, effectiveRecord);
        JSONArray editedHistory = copyWithChangedContent(hostMessages);
        JSONArray historyFallback = ManualCompressionManager.effectiveRequestMessages(
                editedHistory, "provider-a", "model-a");
        assertEquals(editedHistory.length(), historyFallback.length(),
                "covered history change must invalidate and fall back");

        JSONArray pendingSource = copy(source);
        pendingSource.put(pendingUser.getJSONObject(0));
        JSONArray effectiveSummary = ManualCompressionManager.effectiveSummaryMessages(
                pendingSource, "provider-a", "model-a", pendingUser);
        assertEquals(source.length(), effectiveSummary.length(),
                "summary reconstruction must not re-append the blocked user");

        ManualCompressionManager.enterChat("chat-queue-a");
        ManualCompressionManager.bufferPendingUserRequest(pendingUser);
        ManualCompressionManager.bufferRecoveryEvents(new JSONArray()
                .put(message("assistant", "queued")));
        ManualCompressionManager.enterChat("chat-queue-b");
        assertEquals(0, ManualCompressionManager.drainRecoveryBatch().length(),
                "chat switch must clear pending and recovery queues");

        JSONArray nextMessages = copy(source);
        nextMessages.put(message("assistant", "new-event"));
        JSONArray suffix = ManualCompressionManager.recoveryEventsSince(
                source, nextMessages, true);
        assertEquals(1, suffix.length(),
                "complete snapshots must buffer only newly appended events");
        assertEquals(0, ManualCompressionManager.recoveryEventsSince(
                new JSONArray(), nextMessages, false).length(),
                "first complete snapshot must establish a baseline without replay");

        ManualCompressionManager.bufferRecoveryEvents(new JSONArray()
                .put(message("assistant", "assistant-event"))
                .put(message("tool", "tool-event")));
        ManualCompressionManager.bufferPendingUserRequest(pendingUser);
        JSONArray recoveryBatch = ManualCompressionManager.drainRecoveryBatch();
        assertEquals("assistant-event", recoveryBatch.getJSONObject(0).optString("content"),
                "recovery batch must preserve non-user events first");
        assertEquals("tool-event", recoveryBatch.getJSONObject(1).optString("content"),
                "recovery batch must preserve tool event order");
        assertEquals("blocked request", recoveryBatch.getJSONObject(2).optString("content"),
                "recovery batch must append pending user last");
        assertEquals(0, ManualCompressionManager.drainRecoveryBatch().length(),
                "recovery batch must drain exactly once");

        CompressionStateMachine.Task responseTask = CompressionStateMachine.newTask(
                "chat-stale", SummaryRecordStore.fingerprint(source),
                "provider-a", "model-a", 3);
        JSONArray postBoundary = copy(source);
        postBoundary.put(message("assistant", "post-boundary"));
        assertTrue(ManualCompressionManager.isCurrentSummaryResponse(
                        responseTask, responseTask.taskId, "chat-stale",
                        CompressionStateMachine.State.SUMMARIZING,
                        source, postBoundary, "provider-a", "model-a"),
                "current response may accept messages appended after its boundary");
        assertTrue(!ManualCompressionManager.isCurrentSummaryResponse(
                        responseTask, responseTask.taskId, "chat-stale",
                        CompressionStateMachine.State.AWAITING_USER_ACTION,
                        source, postBoundary, "provider-a", "model-a"),
                "late response after failure must be stale");
        assertTrue(!ManualCompressionManager.isCurrentSummaryResponse(
                        responseTask, responseTask.taskId, "chat-stale",
                        CompressionStateMachine.State.SUMMARIZING,
                        source, postBoundary, "provider-b", "model-a"),
                "provider changes must make a summary response stale");
        assertTrue(!ManualCompressionManager.isCurrentSummaryResponse(
                        responseTask, responseTask.taskId, "chat-stale",
                        CompressionStateMachine.State.SUMMARIZING,
                        source, copyWithChangedContent(postBoundary),
                        "provider-a", "model-a"),
                "covered history edits must make a summary response stale");

        assertTrue(ManualCompressionManager.isCompressionActionAllowed(
                        CompressionStateMachine.State.AWAITING_USER_ACTION,
                        3, true, CompressionStateMachine.Action.KEEP_2),
                "three-round over-limit failure must allow retain two");
        assertTrue(!ManualCompressionManager.isCompressionActionAllowed(
                        CompressionStateMachine.State.AWAITING_USER_ACTION,
                        3, true, CompressionStateMachine.Action.RETRY),
                "over-limit failure must not allow same-retention retry");
        assertTrue(!ManualCompressionManager.isCompressionActionAllowed(
                        CompressionStateMachine.State.AWAITING_USER_ACTION,
                        2, true, CompressionStateMachine.Action.KEEP_2),
                "two-round over-limit failure must narrow choices");
        assertTrue(ManualCompressionManager.isCompressionActionAllowed(
                        CompressionStateMachine.State.AWAITING_USER_ACTION,
                        2, true, CompressionStateMachine.Action.KEEP_1),
                "two-round over-limit failure must allow retain one");
        assertTrue(ManualCompressionManager.hasSummaryTimedOut(1_000L, 61_001L),
                "summary timeout must expire after the fixed deadline");
        assertTrue(!ManualCompressionManager.hasSummaryTimedOut(1_000L, 60_999L),
                "summary timeout must not fire early");
        assertLegacyCompressionPathsRemoved();
        internalSummaryIdentityCheck(source);
        SummaryRecordStore.useStore(null);
    }

    private static void internalSummaryIdentityCheck(JSONArray source) throws Exception {
        CompressionStateMachine.Task task = CompressionStateMachine.newTask(
                "chat-internal-summary-identity", SummaryRecordStore.fingerprint(source),
                "provider-a", "model-a", 3);
        String prompt = SummaryProtocol.buildPrompt(source, 3,
                ModuleSettings.DEFAULT_AUTO_CONTEXT_TOKENS, "");
        setManagerField("activeTask", task);
        setManagerField("state", CompressionStateMachine.State.SUMMARIZING);
        setManagerField("taskSource", copy(source));
        setManagerField("taskConfig", null);
        setManagerField("taskPrompt", prompt);
        try {
            ManualCompressionManager.SummaryTaskSnapshot snapshot =
                    ManualCompressionManager.currentSummaryTask();
            assertTrue(snapshot != null && prompt.equals(snapshot.prompt),
                    "active summary task must expose its stable prompt");
            assertTrue(ManualCompressionManager.isInternalSummaryRequest(new JSONArray()
                            .put(message("user", prompt))),
                    "active task prompt must identify the internal summary request");
            assertTrue(!ManualCompressionManager.isInternalSummaryRequest(new JSONArray()
                            .put(message("user", prompt + "x"))),
                    "similar ordinary content must not identify as the internal request");
        } finally {
            setManagerField("activeTask", null);
            setManagerField("state", CompressionStateMachine.State.IDLE);
            setManagerField("taskSource", new JSONArray());
            setManagerField("taskPrompt", null);
        }
    }

    private static void setManagerField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = ManualCompressionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void assertLegacyCompressionPathsRemoved() throws Exception {
        File sourceRoot = new File(System.getProperty("modelCompression.sourceRoot", "."));
        File managerFile = new File(sourceRoot,
                "app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java");
        File moduleFile = new File(sourceRoot,
                "app/src/main/java/dev/operit/lspilot/enhancer/LSPilotEnhancerModule.java");
        File sequenceFile = new File(sourceRoot,
                "app/src/test/java/dev/operit/lspilot/enhancer/CompressionSequenceCheck.java");
        assertTrue(managerFile.isFile() && moduleFile.isFile() && sequenceFile.isFile(),
                "Task 6 source guard requires sourceRoot=" + sourceRoot.getCanonicalPath());

        String manager = read(managerFile);
        String module = read(moduleFile);
        String sequence = read(sequenceFile);
        assertTrue(!manager.contains("ContextCompression.compact(")
                        && !module.contains("ContextCompression.compact("),
                "normal compression paths must not call the legacy local compactor");
        assertTrue(!sequence.contains("ContextCompression.compact(")
                        && !sequence.contains("firstInvalidToolCallIndex("),
                "sequence check must exercise the active model-summary protocol");
        assertContains(sequence, "SummaryProtocol.hasCompleteToolPairs(");
        assertContains(manager, "public static void main(String[] args)");

        assertContains(methodBody(manager, "static void onCompleteEvent("), "startTask(");
        assertContains(methodBody(manager,
                "static synchronized boolean beginPendingUserRequest("), "startTask(");
        assertContains(methodBody(manager, "private static void prepareCurrent("), "startTask(");
        String compressionAction = methodBody(manager,
                "static synchronized boolean onCompressionAction(long");
        assertContains(compressionAction, "startTask(");
        String startTask = methodBody(manager, "private static boolean startTask(");
        assertContains(startTask, "CompressionStateMachine.newTask(");
        assertContains(startTask, "taskPrompt = SummaryProtocol.buildPrompt(");
        String summaryFailure = methodBody(manager,
                "private static void handleSummaryFailure(String reason, boolean overThreshold)");
        assertTrue(!summaryFailure.contains("taskPrompt =")
                        && !summaryFailure.contains("SummaryProtocol.buildPrompt("),
                "automatic retry must preserve the task's dispatched prompt");
        assertContains(methodBody(manager, "private static void finishActive("),
                "taskPrompt = null;");
        assertContains(methodBody(manager, "private static void cancelActive("),
                "taskPrompt = null;");
        String internalSummary = methodBody(manager,
                "static boolean isInternalSummaryRequest(");
        assertContains(internalSummary, "currentSummaryTask()");
        assertContains(internalSummary, "text.equals(current.prompt)");
        String currentSummaryTask = methodBody(manager,
                "static synchronized SummaryTaskSnapshot currentSummaryTask(");
        assertContains(currentSummaryTask, "taskPrompt");
        assertTrue(!currentSummaryTask.contains("ModuleSettings.getAutoContextTokens()"),
                "active summary prompt must not be rebuilt from mutable settings");

        assertRequestHookSanitizesBeforePolicyBypass(
                methodBody(module, "private StartupProbe installRequestHook("), "named");
        assertRequestHookSanitizesBeforePolicyBypass(
                methodBody(module, "private boolean installMinifiedRequestBodyHook("), "minified");
    }

    private static String read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int signatureIndex = source.indexOf(signature);
        int start = source.indexOf('{', signatureIndex);
        assertTrue(signatureIndex >= 0 && start >= 0,
                "expected source method was absent: " + signature);
        int depth = 1;
        for (int index = start + 1; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return source.substring(start + 1, index);
        }
        throw new AssertionError("unterminated source method: " + signature);
    }

    private static void assertRequestHookSanitizesBeforePolicyBypass(
            String body, String hookName) {
        int internalSummary = body.indexOf("isInternalSummaryRequest(");
        int sanitize = body.indexOf("sanitizeRequestMessagesOrThrow(");
        int writeBack = body.indexOf("body.put(\"messages\", messages)", sanitize);
        int disabled = body.indexOf("if (!policyEnabled");
        assertTrue(internalSummary >= 0 && sanitize > internalSummary
                        && writeBack > sanitize && disabled > writeBack,
                hookName + " request hook must sanitize and write back before policy bypass");
        assertTrue(!body.substring(internalSummary, sanitize).contains("!policyEnabled"),
                hookName + " request hook has a pre-sanitization policy bypass");
        int branchEnd = body.indexOf('}', disabled);
        assertTrue(branchEnd > disabled
                        && body.substring(disabled, branchEnd).contains("return body.toString();"),
                hookName + " disabled-policy bypass must return sanitized JSON");
    }

    private static void pendingSourceExcludesBlockedInput() throws Exception {
        String chatId = "chat-pending-threshold";
        ManualCompressionManager.enterChat(chatId);
        JSONArray completed = pairedToolMessages();
        ManualCompressionManager.effectiveRequestMessages(completed, null, "");
        JSONArray pending = new JSONArray()
                .put(message("user", largeText(70_000)));
        assertTrue(!ManualCompressionManager.beginPendingUserRequest(chatId, pending),
                "blocked user input must not trigger completed-context compression");
        assertEquals(CompressionStateMachine.State.IDLE,
                ManualCompressionManager.getCompressionState(),
                "below-threshold completed context must remain idle");
    }

    private static JSONArray copyWithTransientFields(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject copy = new JSONObject(source.getJSONObject(index).toString())
                    .put("_lspilot_host_index", index)
                    .put("pendingInput", "draft text");
            result.put(copy);
        }
        result.put(new JSONObject().put("role", "system")
                .put("content", "[系统提示 · 上下文压缩]\nwaiting"));
        return result;
    }

    private static JSONArray copyWithChangedContent(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        boolean changed = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject copy = new JSONObject(source.getJSONObject(index).toString());
            if (!changed && "user".equals(copy.optString("role"))) {
                copy.put("content", "edited content");
                changed = true;
            }
            result.put(copy);
        }
        return result;
    }

    private static JSONArray copy(JSONArray source) throws Exception {
        return new JSONArray(source.toString());
    }

    private static int countMessage(JSONArray messages, JSONObject expected) {
        int count = 0;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message != null
                    && message.optString("role").equals(expected.optString("role"))
                    && message.optString("content").equals(expected.optString("content"))) {
                count++;
            }
        }
        return count;
    }

    private static int countRoleAndContent(JSONArray messages, String role, String content) {
        int count = 0;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message != null
                    && role.equals(message.optString("role"))
                    && content.equals(message.optString("content"))) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasEnhancerMarker(JSONArray messages) {
        return messages.toString().contains("[系统提示 · 上下文压缩]");
    }

    private static boolean hasInternalFields(JSONArray messages) {
        return messages.toString().contains("_lspilot_");
    }

    private static final class MemoryStore implements SummaryRecordStore.KeyValueStore {
        final Map<String, String> values = new HashMap<>();
        String lastKey;

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void put(String key, String value) {
            lastKey = key;
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }

    private static String validMarkdown(int rounds) {
        StringBuilder result = new StringBuilder();
        result.append("## 核心任务状态\n任务正在执行。\n")
                .append("## 行为设定\n保持当前行为设定。\n")
                .append("## 分阶段的对话历史概要\n阶段一完成。\n")
                .append("## 关键信息与关键上下文\n关键上下文保留。\n")
                .append("## 最近 ").append(rounds).append(" 轮格式化完整上下文\n");
        for (int index = 1; index <= rounds; index++) {
            result.append(index).append(". 第").append(index).append("轮：用户与助手的完整上下文。\n");
        }
        return result.append("## 当前激活的 skill/函数/工具/MCP 等包信息\n当前包信息已保留。\n")
                .append("## 后续执行要求\n继续执行后续任务。\n").toString();
    }

    private static String malformedOrderMarkdown() {
        return validMarkdown(3).replace("## 行为设定", "## 后续执行要求")
                .replace("## 后续执行要求", "## 行为设定");
    }

    private static String toolCallOnlyResponse() {
        return validMarkdown(3).replace("任务正在执行。", "tool_calls: call_1");
    }

    private static String markdownWithEnhancerMarker() {
        return validMarkdown(3).replace("任务正在执行。", "[系统提示 · 上下文压缩]");
    }

    private static JSONArray pairedToolMessages() throws Exception {
        JSONArray result = new JSONArray();
        result.put(message("system", "system"));
        result.put(message("user", "question"));
        result.put(message("assistant", "answer"));
        result.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "call_1"))));
        result.put(new JSONObject().put("role", "tool").put("content", "result")
                .put("tool_call_id", "call_1"));
        result.put(message("user", "next"));
        return result;
    }

    private static JSONArray unpairedToolMessages() throws Exception {
        JSONArray result = pairedToolMessages();
        result.remove(result.length() - 2);
        return result;
    }

    private static String largeText(int length) {
        StringBuilder result = new StringBuilder(length);
        while (result.length() < length) result.append('x');
        return result.toString();
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static void assertOrdered(String value, String[] titles) {
        int previous = -1;
        for (int index = 0; index < titles.length; index++) {
            String title = titles[index].replace("N", "3");
            int current = value.indexOf("## " + title);
            assertTrue(current >= 0 && current > previous,
                    "heading order failed for " + title);
            previous = current;
        }
    }

    private static void assertContains(String value, String expected) {
        assertTrue(value != null && value.contains(expected),
                "expected text was absent: " + expected);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertTrue(expected == null ? actual == null : expected.equals(actual),
                message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
