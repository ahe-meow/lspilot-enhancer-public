package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class ReasoningPolicy {
    static final String DEFAULT_EFFORT = "high";
    private static final String MODEL = "gpt-5.6-sol";

    private ReasoningPolicy() {}

    static boolean applyRequest(JSONObject body, String model, String effort) throws Exception {
        if (body == null || !MODEL.equals(normalize(model)) || !isSupportedEffort(effort)) {
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

    private static String normalize(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    public static void main(String[] args) throws Exception {
        JSONObject request = new JSONObject().put("model", MODEL);
        for (String effort : new String[]{"low", "medium", "high", "xhigh", "max", "ultra"}) {
            assert isSupportedEffort(effort);
            assert applyRequest(request, MODEL, effort);
            assert effort.equals(request.getString("reasoning_effort"));
        }
        assert !applyRequest(request, MODEL, "ultra");
        assert !applyRequest(new JSONObject(), "gpt-5.6", DEFAULT_EFFORT);
        assert !applyRequest(new JSONObject(), MODEL, "invalid");

        String payload = "{\"choices\":[{\"delta\":{\"reasoning\":\"check\"}}]}";
        JSONObject normalized = new JSONObject(normalizeSseDelta(payload));
        assert "check".equals(normalized.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getString("reasoning_content"));
    }
}