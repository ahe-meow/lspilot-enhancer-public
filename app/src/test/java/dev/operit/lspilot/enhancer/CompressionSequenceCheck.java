package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CompressionSequenceCheck {
    private CompressionSequenceCheck() {
    }

    public static void main(String[] args) throws Exception {
        check(-1, messages(
                message("assistant", "[]"),
                message("user", null)));
        check(-1, messages(
                message("assistant", "[call]"),
                message("tool", null)));
        check(-1, messages(
                message("assistant", "[call-1, call-2]"),
                message("tool", null),
                message("tool", null)));
        check(0, messages(message("tool", null)));
        checkCompactionIsStrictlySmaller();
    }

    private static void checkCompactionIsStrictlySmaller() throws Exception {
        JSONArray source = new JSONArray();
        source.put(new JSONObject().put("role", "system").put("content", "system"));
        for (int index = 0; index < 80; index++) {
            String role = index % 2 == 0 ? "user" : "assistant";
            source.put(new JSONObject().put("role", role).put("content", repeated(index))
                    .put("_lspilot_host_index", index + 1));
        }
        JSONArray compacted = ContextCompression.compact(source, new JSONObject(), true, 4);
        if (compacted == source) {
            throw new AssertionError("expected a compacted result");
        }
        if (compacted.length() >= source.length()
                || compacted.toString().length() >= source.toString().length()) {
            throw new AssertionError("compaction expanded the request: "
                    + source.toString().length() + " -> " + compacted.toString().length());
        }
        String summary = compacted.optJSONObject(1).optString("content");
        if (summary.length() > 12_000) {
            throw new AssertionError("summary exceeded hard budget: " + summary.length());
        }
    }

    private static String repeated(int index) {
        StringBuilder result = new StringBuilder();
        while (result.length() < 600) {
            result.append("message ").append(index)
                    .append(" contains stable context and implementation details. ");
        }
        return result.toString();
    }

    private static JSONObject message(String role, String toolCalls) throws Exception {
        JSONObject result = new JSONObject().put("role", role).put("content", "test");
        if (toolCalls != null) result.put("_lspilot_tool_calls", toolCalls);
        return result;
    }

    private static JSONArray messages(JSONObject... values) {
        JSONArray result = new JSONArray();
        for (JSONObject value : values) result.put(value);
        return result;
    }

    private static void check(int expected, JSONArray messages) {
        int actual = ContextCompression.firstInvalidToolCallIndex(messages);
        if (actual != expected) {
            throw new AssertionError("expected " + expected + ", got " + actual + ": " + messages);
        }
    }
}