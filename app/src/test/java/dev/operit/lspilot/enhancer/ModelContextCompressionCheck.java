package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

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
        assertEquals(3, ModuleSettings.getSummaryKeepRecent(),
                "model summary retention must default to three rounds");
        assertEquals(SummaryProtocol.estimateTokens(source),
                ManualCompressionManager.completedContextTokens(source),
                "threshold must count completed messages without pending input");

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
        SummaryRecordStore.useStore(null);
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