package com.lspilot.enhancer;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolCallSanitizerCheck {
    private ToolCallSanitizerCheck() {}

    public static void main(String[] args) throws Exception {
        JSONObject body = new JSONObject().put("messages", new JSONArray()
                .put(tool("orphan_before_window"))
                .put(assistant("kept_call", "missing_call"))
                .put(tool("kept_call"))
                .put(assistant("empty_call"))
                .put(new JSONObject().put("role", "assistant").put("content", "assistant text"))
                .put(tool("orphan_after_window")));

        int changes = ToolCallSanitizer.repair(body);
        JSONArray repaired = body.getJSONArray("messages");
        assertEquals(5, changes, "all orphan fragments must be repaired");
        assertEquals(3, repaired.length(), "only valid assistant/tool and text remain");
        assertEquals("assistant", repaired.getJSONObject(0).getString("role"),
                "assistant must remain first");
        assertEquals(1, repaired.getJSONObject(0).getJSONArray("tool_calls").length(),
                "missing call must be removed from assistant");
        assertEquals("tool", repaired.getJSONObject(1).getString("role"),
                "matching tool output must remain");
        assertEquals("assistant", repaired.getJSONObject(2).getString("role"),
                "text-only assistant must remain");

        JSONObject unchanged = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", "stable")));
        assertEquals(0, ToolCallSanitizer.repair(unchanged),
                "ordinary messages must not be rewritten");
        assertEquals(1, unchanged.getJSONArray("messages").length(),
                "ordinary message must remain");

        replayObservedThirtyMessageWindow();
        rejectDelayedAndDuplicateOutputs();
        System.out.println("ToolCallSanitizerCheck: PASS");
    }

    private static void replayObservedThirtyMessageWindow() throws Exception {
        JSONArray window = new JSONArray();
        for (int index = 0; index < 4; index++) {
            window.put(tool("orphan_" + index));
        }
        appendPair(window, 6, "first");
        appendPair(window, 8, "second");
        appendPair(window, 8, "third");
        window.put(new JSONObject().put("role", "user").put("content", "continue"));

        JSONObject body = new JSONObject().put("messages", window);
        assertEquals(4, ToolCallSanitizer.repair(body),
                "observed 30-message boundary has four orphan outputs");
        JSONArray repaired = body.getJSONArray("messages");
        assertEquals(26, repaired.length(), "four orphan outputs are removed only");
        Set<String> declared = new HashSet<>();
        for (int index = 0; index < repaired.length(); index++) {
            JSONObject message = repaired.getJSONObject(index);
            if (!"assistant".equals(message.optString("role"))) continue;
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null) continue;
            for (int callIndex = 0; callIndex < calls.length(); callIndex++) {
                declared.add(calls.getJSONObject(callIndex).getString("id"));
            }
        }
        for (int index = 0; index < repaired.length(); index++) {
            JSONObject message = repaired.getJSONObject(index);
            if ("tool".equals(message.optString("role"))) {
                assertEquals(true, declared.contains(message.getString("tool_call_id")),
                        "every retained tool output must have a declaration");
            }
        }
        assertValidToolSequence(repaired);
    }

    private static void rejectDelayedAndDuplicateOutputs() throws Exception {
        JSONObject body = new JSONObject().put("messages", new JSONArray()
                .put(assistant("kept"))
                .put(tool("kept"))
                .put(tool("kept"))
                .put(assistant("late"))
                .put(new JSONObject().put("role", "user").put("content", "boundary"))
                .put(tool("late"))
                .put(assistant("kept"))
                .put(tool("kept")));

        ToolCallSanitizer.repair(body);
        JSONArray repaired = body.getJSONArray("messages");
        assertEquals(3, repaired.length(),
                "only the first contiguous unique call/output pair and user remain");
        assertEquals("assistant", repaired.getJSONObject(0).getString("role"),
                "first valid assistant remains");
        assertEquals("tool", repaired.getJSONObject(1).getString("role"),
                "first matching output remains");
        assertEquals("user", repaired.getJSONObject(2).getString("role"),
                "ordinary boundary message remains");
        assertValidToolSequence(repaired);
    }

    private static void assertValidToolSequence(JSONArray messages) throws Exception {
        Set<String> pending = new HashSet<>();
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.getJSONObject(index);
            String role = message.optString("role");
            if ("tool".equals(role)) {
                assertEquals(true, pending.remove(message.getString("tool_call_id")),
                        "tool output must belong to the immediately preceding assistant");
                continue;
            }
            assertEquals(true, pending.isEmpty(),
                    "all declared calls must finish before the next non-tool message");
            if (!"assistant".equals(role)) continue;
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null) continue;
            for (int callIndex = 0; callIndex < calls.length(); callIndex++) {
                assertEquals(true, pending.add(calls.getJSONObject(callIndex).getString("id")),
                        "tool call ids must be unique within a group");
            }
        }
        assertEquals(true, pending.isEmpty(), "all declared calls must have outputs");
    }

    private static void appendPair(JSONArray messages, int count, String prefix)
            throws Exception {
        String[] ids = new String[count];
        for (int index = 0; index < count; index++) ids[index] = prefix + "_" + index;
        messages.put(assistant(ids));
        for (String id : ids) messages.put(tool(id));
    }

    private static JSONObject assistant(String... ids) throws Exception {
        JSONArray calls = new JSONArray();
        for (String id : ids) {
            calls.put(new JSONObject().put("id", id).put("type", "function"));
        }
        return new JSONObject().put("role", "assistant").put("tool_calls", calls);
    }

    private static JSONObject tool(String id) throws Exception {
        return new JSONObject().put("role", "tool").put("tool_call_id", id)
                .put("content", "tool output");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
