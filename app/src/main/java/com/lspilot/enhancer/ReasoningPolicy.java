package com.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

final class ReasoningPolicy {
    static final String DEFAULT_EFFORT = "high";

    private ReasoningPolicy() {}

    static boolean applyRequest(JSONObject body, String model, String effort) throws Exception {
        if (body == null || model == null || model.trim().isEmpty()
                || !isSupportedEffort(effort)) {
            return false;
        }
        if (effort.equals(body.optString("reasoning_effort", null))) return false;
        body.put("reasoning_effort", effort);
        return true;
    }

    static boolean isSupportedEffort(String effort) {
        return "low".equals(effort) || "medium".equals(effort) || "high".equals(effort)
                || "xhigh".equals(effort) || "max".equals(effort) || "ultra".equals(effort);
    }

    static String normalizeSseDelta(String payload) {
        if (payload == null || !payload.contains("\"reasoning\"")) return payload;
        try {
            JSONObject root = new JSONObject(payload);
            JSONArray choices = root.optJSONArray("choices");
            boolean changed = false;
            for (int index = 0; choices != null && index < choices.length(); index++) {
                JSONObject choice = choices.optJSONObject(index);
                JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                Object reasoning = delta == null ? null : delta.opt("reasoning");
                if (reasoning instanceof String && !((String) reasoning).isEmpty()
                        && !hasReasoningAlias(delta)) {
                    delta.put("reasoning_content", reasoning);
                    changed = true;
                }
            }
            return changed ? root.toString() : payload;
        } catch (Throwable ignored) {
            return payload;
        }
    }

    private static boolean hasReasoningAlias(JSONObject delta) {
        for (String key : new String[]{"reasoning_content", "thinking_content", "thinking"}) {
            Object value = delta.opt(key);
            if (value instanceof String && !((String) value).isEmpty()) return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        JSONObject request = new JSONObject().put("model", "gpt-5.6-sol");
        for (String model : new String[]{"gpt-5.6-sol", "gpt-5", "claude-3-7-sonnet"}) {
            for (String effort : new String[]{"low", "medium", "high", "xhigh", "max", "ultra"}) {
                assert isSupportedEffort(effort);
                assert applyRequest(request, model, effort);
                assert effort.equals(request.getString("reasoning_effort"));
            }
        }
        assert !applyRequest(request, " ", DEFAULT_EFFORT);
        assert !applyRequest(request, null, DEFAULT_EFFORT);
        assert !applyRequest(request, "gpt-5", "invalid");

        String payload = "{\"choices\":[{\"delta\":{\"reasoning\":\"check\"}}]}";
        JSONObject normalized = new JSONObject(normalizeSseDelta(payload));
        assert "check".equals(normalized.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getString("reasoning_content"));
    }
}