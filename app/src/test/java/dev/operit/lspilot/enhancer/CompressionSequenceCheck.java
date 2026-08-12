package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CompressionSequenceCheck {
    private CompressionSequenceCheck() {
    }

    public static void main(String[] args) throws Exception {
        JSONArray paired = new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                        .put("tool_calls", new JSONArray()
                                .put(new JSONObject().put("id", "call_1"))
                                .put(new JSONObject().put("id", "call_2"))))
                .put(new JSONObject().put("role", "tool").put("content", "one")
                        .put("tool_call_id", "call_1"))
                .put(new JSONObject().put("role", "tool").put("content", "two")
                        .put("tool_call_id", "call_2"));
        if (!SummaryProtocol.hasCompleteToolPairs(paired)) {
            throw new AssertionError("complete tool-call sequence must pass");
        }
        paired.remove(paired.length() - 1);
        if (SummaryProtocol.hasCompleteToolPairs(paired)) {
            throw new AssertionError("missing tool result must fail");
        }
    }
}
