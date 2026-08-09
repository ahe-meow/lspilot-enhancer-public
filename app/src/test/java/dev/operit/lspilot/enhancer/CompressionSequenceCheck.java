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