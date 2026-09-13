package com.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class RequestJsonPolicy {
    private static final String MODEL_FIELD = "model";
    private static final String MESSAGES_FIELD = "messages";
    private static final String MAX_TOKENS_FIELD = "max_tokens";
    private static final String REASONING_EFFORT_FIELD = "reasoning_effort";
    private static final String THINKING_FIELD = "thinking";

    private RequestJsonPolicy() {
    }

    public enum ProviderKind {
        GENERIC,
        THINKING,
        UNKNOWN
    }

    public static String rewrite(String original, ProviderKind provider, String effort) {
        if (original == null || !isSupported(provider)) {
            return original;
        }

        try {
            JSONTokener tokener = new JSONTokener(original);
            JSONObject body = new JSONObject(tokener);
            if (tokener.nextClean() != '\0') {
                return original;
            }
            if (!supportsShape(body, provider)) {
                return original;
            }
            return rewriteObject(body, provider, effort).toString();
        } catch (JSONException ignored) {
            return original;
        } catch (RuntimeException ignored) {
            return original;
        }
    }

    static JSONObject rewriteObject(JSONObject body, ProviderKind provider, String effort) {
        if (!supportsShape(body, provider)) {
            return body;
        }

        String normalized = ReasoningPolicy.normalize(effort);
        try {
            if ("off".equals(normalized)) {
                body.remove(REASONING_EFFORT_FIELD);
                body.remove(THINKING_FIELD);
            } else if (provider == ProviderKind.GENERIC) {
                body.put(REASONING_EFFORT_FIELD, ReasoningPolicy.genericWireValue(normalized));
            } else {
                JSONObject thinking = new JSONObject();
                thinking.put("type", "enabled");
                thinking.put("budget_tokens", ReasoningPolicy.thinkingBudget(normalized));
                body.put(THINKING_FIELD, thinking);
            }
            return body;
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isSupported(ProviderKind provider) {
        return provider == ProviderKind.GENERIC || provider == ProviderKind.THINKING;
    }

    private static boolean supportsShape(JSONObject body, ProviderKind provider) {
        if (body == null || !isSupported(provider)) {
            return false;
        }

        Object model = body.opt(MODEL_FIELD);
        Object messages = body.opt(MESSAGES_FIELD);
        if (!(model instanceof String)
                || ((String) model).trim().length() == 0
                || !(messages instanceof JSONArray)) {
            return false;
        }
        return provider == ProviderKind.GENERIC
                || body.has(MAX_TOKENS_FIELD)
                || body.has(THINKING_FIELD);
    }
}
