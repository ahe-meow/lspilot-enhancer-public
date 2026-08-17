package com.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** GPT-5.6 explicit prompt-cache breakpoints for append-only chat requests. */
public final class PromptCachePolicy {
    private static final int MAX_BREAKPOINTS = 4;

    private PromptCachePolicy() {
    }

    static boolean supportsExplicitBreakpoints(String model) {
        return normalize(model).startsWith("gpt-5.6");
    }

    static int applyExplicitBreakpoints(JSONObject body) throws Exception {
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return 0;
        }

        int anchor = findAnchor(messages);
        int applied = 0;
        if (anchor >= 0 && markContent(messages.optJSONObject(anchor))) {
            applied++;
        }

        // GPT-5.6 needs breakpoints at reusable completed-response prefixes. Marking
        // the changing latest user/tool suffix makes explicit mode rewrite the cache.
        for (int index = messages.length() - 1;
                index >= 0 && applied < MAX_BREAKPOINTS; index--) {
            if (index == anchor) continue;
            JSONObject message = messages.optJSONObject(index);
            if (message == null || !"assistant".equals(message.optString("role"))) continue;
            if (markContent(message)) applied++;
        }

        // Tool-only conversations may not contain assistant text blocks. Use an older
        // completed tool message as a fallback, never the latest changing suffix.
        for (int index = messages.length() - 2;
                index >= 0 && applied < MAX_BREAKPOINTS; index--) {
            if (index == anchor) continue;
            JSONObject message = messages.optJSONObject(index);
            if (message == null || !"tool".equals(message.optString("role"))) continue;
            if (markContent(message)) applied++;
        }

        if (applied > 0) {
            body.put("prompt_cache_options", new JSONObject().put("mode", "explicit"));
            body.remove("prompt_cache_retention");
        }
        return applied;
    }

    private static int findAnchor(JSONArray messages) {
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) {
                continue;
            }
            String role = message.optString("role", "");
            if ("system".equals(role) || "developer".equals(role)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean markContent(JSONObject message) throws Exception {
        if (message == null) {
            return false;
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            String text = (String) content;
            if (text.isEmpty()) {
                return false;
            }
            JSONArray blocks = new JSONArray();
            blocks.put(markedText(text));
            message.put("content", blocks);
            return true;
        }
        if (!(content instanceof JSONArray)) {
            return false;
        }

        JSONArray blocks = (JSONArray) content;
        for (int index = blocks.length() - 1; index >= 0; index--) {
            JSONObject block = blocks.optJSONObject(index);
            if (block != null && supportsBreakpoint(block.optString("type", ""))) {
                block.put("prompt_cache_breakpoint", explicitMarker());
                return true;
            }
        }
        return false;
    }

    private static JSONObject markedText(String text) throws Exception {
        return new JSONObject()
                .put("type", "text")
                .put("text", text)
                .put("prompt_cache_breakpoint", explicitMarker());
    }

    private static JSONObject explicitMarker() throws Exception {
        return new JSONObject().put("mode", "explicit");
    }

    private static boolean supportsBreakpoint(String type) {
        return "text".equals(type)
                || "image_url".equals(type)
                || "input_audio".equals(type)
                || "file".equals(type)
                || "refusal".equals(type);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static void main(String[] args) throws Exception {
        JSONObject body = new JSONObject().put("prompt_cache_retention", "24h");
        JSONArray messages = new JSONArray()
                .put(message("system", "instructions"))
                .put(message("user", "one"))
                .put(message("assistant", "answer"))
                .put(message("tool", "two"))
                .put(message("user", "three"))
                .put(message("assistant", "answer two"))
                .put(message("tool", "four"))
                .put(message("user", "latest"));
        body.put("messages", messages);

        int applied = applyExplicitBreakpoints(body);
        check(applied == 4, "expected four stable breakpoints");
        check("explicit".equals(body.getJSONObject("prompt_cache_options").getString("mode")),
                "explicit mode missing");
        check(!body.has("prompt_cache_retention"), "legacy retention was not removed");
        check(hasBreakpoint(messages.getJSONObject(0)), "system anchor missing");
        check(hasBreakpoint(messages.getJSONObject(2)), "completed assistant breakpoint missing");
        check(hasBreakpoint(messages.getJSONObject(6)), "older tool breakpoint missing");
        check(hasBreakpoint(messages.getJSONObject(5)), "latest completed assistant missing");
        check(!hasBreakpoint(messages.getJSONObject(7)), "latest user must remain unmarked");
        check(applyExplicitBreakpoints(body) == 4, "policy is not idempotent");
        System.out.println("PromptCachePolicy check passed");
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static boolean hasBreakpoint(JSONObject message) {
        JSONArray content = message.optJSONArray("content");
        return content != null
                && content.length() > 0
                && content.optJSONObject(content.length() - 1) != null
                && content.optJSONObject(content.length() - 1)
                        .optJSONObject("prompt_cache_breakpoint") != null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}