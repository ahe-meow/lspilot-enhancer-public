package dev.operit.lspilot.enhancer;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete per-chat summary records with history/configuration invalidation. */
final class SummaryRecordStore {
    interface KeyValueStore {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    static final class Record {
        final String chatId;
        final String summaryText;
        final JSONArray coveredBoundary;
        final String fingerprint;
        final String providerSignature;
        final String model;
        final int keepRecent;
        final int beforeTokens;
        final int afterTokens;
        final int reduction;
        final double ratio;
        final boolean complete;
        final long updatedAt;

        Record(String chatId, String summaryText, JSONArray coveredBoundary, String fingerprint,
                String providerSignature, String model, int keepRecent, int beforeTokens,
                int afterTokens, int reduction, double ratio, boolean complete, long updatedAt) {
            this.chatId = chatId;
            this.summaryText = summaryText == null ? "" : summaryText;
            this.coveredBoundary = copyArray(coveredBoundary);
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.providerSignature = providerSignature == null ? "" : providerSignature;
            this.model = model == null ? "" : model;
            this.keepRecent = keepRecent;
            this.beforeTokens = beforeTokens;
            this.afterTokens = afterTokens;
            this.reduction = reduction;
            this.ratio = ratio;
            this.complete = complete;
            this.updatedAt = updatedAt;
        }

        Record withComplete(boolean value) {
            return new Record(chatId, summaryText, coveredBoundary, fingerprint,
                    providerSignature, model, keepRecent, beforeTokens, afterTokens,
                    reduction, ratio, value, updatedAt);
        }
    }

    private static final String KEY_PREFIX = "lspilot.summary.record.v1.";
    private static final KeyValueStore DEFAULT_STORE = new KeyValueStore() {
        @Override
        public String get(String key) {
            SharedPreferences preferences = ModuleSettings.preferences();
            return preferences == null ? null : preferences.getString(key, null);
        }

        @Override
        public void put(String key, String value) {
            SharedPreferences preferences = ModuleSettings.preferences();
            if (preferences != null) preferences.edit().putString(key, value).commit();
        }

        @Override
        public void remove(String key) {
            SharedPreferences preferences = ModuleSettings.preferences();
            if (preferences != null) preferences.edit().remove(key).commit();
        }
    };
    private static volatile KeyValueStore store = DEFAULT_STORE;

    private SummaryRecordStore() {
    }

    static void useStore(KeyValueStore replacement) {
        store = replacement == null ? DEFAULT_STORE : replacement;
    }

    static void writeComplete(String chatId, Record record) throws Exception {
        if (chatId == null || chatId.trim().isEmpty() || record == null) {
            throw new IllegalArgumentException("chatId and record are required");
        }
        String key = key(chatId);
        store.put(key, toJson(record.withComplete(false)).toString());
        store.put(key, toJson(record.withComplete(true)).toString());
    }

    static Record readUsable(String chatId, JSONArray currentMessages,
            String providerSignature, String model) {
        if (chatId == null || currentMessages == null) return null;
        try {
            String value = store.get(key(chatId));
            if (value == null || value.trim().isEmpty()) return null;
            Record record = fromJson(new JSONObject(value));
            if (!record.complete || !chatId.equals(record.chatId)) return null;
            if (!equalsOrBothEmpty(record.fingerprint, fingerprint(currentMessages))) return null;
            if (!equalsOrBothEmpty(record.providerSignature, providerSignature)) return null;
            if (!equalsOrBothEmpty(record.model, model)) return null;
            return record;
        } catch (Throwable error) {
            return null;
        }
    }

    static void invalidate(String chatId) {
        if (chatId != null) store.remove(key(chatId));
    }

    static String fingerprint(JSONArray messages) {
        StringBuilder stable = new StringBuilder();
        if (messages != null) {
            for (int index = 0; index < messages.length(); index++) {
                JSONObject message = messages.optJSONObject(index);
                if (message == null || isStatusMessage(message)) continue;
                stable.append("role=").append(message.optString("role", ""))
                        .append("\ncontent=").append(stableContent(message.opt("content")))
                        .append("\ntoolCallId=").append(toolCallId(message))
                        .append("\ntoolCallPresence=").append(hasToolCalls(message))
                        .append("\n;");
            }
        }
        return sha256(stable.toString());
    }

    private static String key(String chatId) {
        return KEY_PREFIX + chatId;
    }

    private static JSONObject toJson(Record record) throws Exception {
        return new JSONObject()
                .put("chatId", record.chatId)
                .put("summaryText", record.summaryText)
                .put("coveredBoundary", record.coveredBoundary)
                .put("fingerprint", record.fingerprint)
                .put("providerSignature", record.providerSignature)
                .put("model", record.model)
                .put("keepRecent", record.keepRecent)
                .put("beforeTokens", record.beforeTokens)
                .put("afterTokens", record.afterTokens)
                .put("reduction", record.reduction)
                .put("ratio", record.ratio)
                .put("complete", record.complete)
                .put("updatedAt", record.updatedAt);
    }

    private static Record fromJson(JSONObject value) throws Exception {
        return new Record(
                value.optString("chatId", ""),
                value.optString("summaryText", ""),
                value.optJSONArray("coveredBoundary"),
                value.optString("fingerprint", ""),
                value.optString("providerSignature", ""),
                value.optString("model", ""),
                value.optInt("keepRecent", 0),
                value.optInt("beforeTokens", 0),
                value.optInt("afterTokens", 0),
                value.optInt("reduction", 0),
                value.optDouble("ratio", 0d),
                value.optBoolean("complete", false),
                value.optLong("updatedAt", 0L));
    }

    private static boolean equalsOrBothEmpty(String first, String second) {
        return first == null ? second == null || second.isEmpty()
                : first.equals(second == null ? "" : second);
    }

    private static JSONArray copyArray(JSONArray source) {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        for (int index = 0; index < source.length(); index++) result.put(source.opt(index));
        return result;
    }

    private static boolean isStatusMessage(JSONObject message) {
        String content = message.optString("content", "");
        return ("system".equals(message.optString("role", ""))
                || "assistant".equals(message.optString("role", "")))
                && (content.startsWith("[系统提示") || content.startsWith("[LSPilot"));
    }

    private static String stableContent(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return "";
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    private static String toolCallId(JSONObject message) {
        String result = message.optString("tool_call_id", "");
        return result.isEmpty() ? message.optString("_lspilot_tool_call_id", "") : result;
    }

    private static boolean hasToolCalls(JSONObject message) {
        Object value = message.has("tool_calls") ? message.opt("tool_calls")
                : message.opt("_lspilot_tool_calls");
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return !text.isEmpty() && !"[]".equals(text) && !"null".equalsIgnoreCase(text);
        }
        return value != null && !JSONObject.NULL.equals(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}