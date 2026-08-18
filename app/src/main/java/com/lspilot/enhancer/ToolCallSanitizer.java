package com.lspilot.enhancer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Removes tool-call fragments that a host-side context window can leave behind. */
final class ToolCallSanitizer {
    private ToolCallSanitizer() {}

    static int repair(JSONObject body) throws Exception {
        if (body == null) return 0;
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) return 0;

        Map<String, JSONObject> declaredIds = new HashMap<>();
        JSONArray repaired = new JSONArray();
        int changes = 0;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) {
                changes++;
                continue;
            }
            String role = message.optString("role", "");
            if ("tool".equals(role)) {
                changes++;
                continue;
            }
            if (!"assistant".equals(role) || !message.has("tool_calls")) {
                repaired.put(message);
                continue;
            }

            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null) {
                message.remove("tool_calls");
                changes++;
                if (!hasMeaningfulAssistantContent(message)) {
                    changes++;
                    continue;
                }
                repaired.put(message);
                continue;
            }

            Map<String, JSONObject> groupCalls = new LinkedHashMap<>();
            for (int callIndex = 0; callIndex < calls.length(); callIndex++) {
                JSONObject call = calls.optJSONObject(callIndex);
                String id = call == null ? "" : call.optString("id", "");
                if (id.isEmpty() || declaredIds.containsKey(id) || groupCalls.containsKey(id)) {
                    changes++;
                    continue;
                }
                groupCalls.put(id, call);
            }

            int end = index + 1;
            while (end < messages.length()) {
                JSONObject output = messages.optJSONObject(end);
                if (output == null || !"tool".equals(output.optString("role", ""))) {
                    break;
                }
                end++;
            }

            Map<String, JSONObject> groupOutputs = new HashMap<>();
            for (int outputIndex = index + 1; outputIndex < end; outputIndex++) {
                JSONObject output = messages.optJSONObject(outputIndex);
                String id = output == null ? "" : output.optString("tool_call_id", "");
                if (groupCalls.containsKey(id) && !groupOutputs.containsKey(id)) {
                    groupOutputs.put(id, output);
                }
            }

            JSONArray completeCalls = new JSONArray();
            Set<String> completeIds = new HashSet<>();
            for (Map.Entry<String, JSONObject> entry : groupCalls.entrySet()) {
                if (groupOutputs.containsKey(entry.getKey())) {
                    completeCalls.put(entry.getValue());
                    completeIds.add(entry.getKey());
                    declaredIds.put(entry.getKey(), entry.getValue());
                } else {
                    changes++;
                }
            }

            if (completeCalls.length() != calls.length()) {
                message.remove("tool_calls");
                if (completeCalls.length() > 0) message.put("tool_calls", completeCalls);
            }
            if (completeCalls.length() == 0 && !hasMeaningfulAssistantContent(message)) {
                changes++;
            } else {
                repaired.put(message);
            }

            Set<String> emittedIds = new HashSet<>();
            for (int outputIndex = index + 1; outputIndex < end; outputIndex++) {
                JSONObject output = messages.optJSONObject(outputIndex);
                String id = output == null ? "" : output.optString("tool_call_id", "");
                if (completeIds.contains(id) && emittedIds.add(id)) {
                    repaired.put(output);
                } else {
                    changes++;
                }
            }
            index = end - 1;
        }

        if (changes > 0) body.put("messages", repaired);
        return changes;
    }

    private static boolean hasMeaningfulAssistantContent(JSONObject message) {
        for (String key : new String[]{"content", "reasoning_content"}) {
            Object value = message.opt(key);
            if (value != null && !JSONObject.NULL.equals(value)
                    && (!value.toString().trim().isEmpty())) {
                return true;
            }
        }
        return false;
    }
}
