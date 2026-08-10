package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Deterministic local request compaction. The host conversation database is never modified. */
final class ContextCompression {
    static final int KEEP_RECENT_MESSAGES = 32;
    static final int TRIGGER_MESSAGE_COUNT = 40;
    static final int MAX_CHUNK_MESSAGES = 96;
    private static final int MAX_CHUNK_CHARS = 80_000;
    private static final int MAX_TRANSCRIPT_MESSAGE_CHARS = 8_000;
    private static final int MAX_TRANSCRIPT_TOOL_CHARS = 3_000;

    private ContextCompression() {
    }

    static JSONArray compact(JSONArray messages, Object providerConfig) throws Exception {
        return compact(messages, providerConfig, false);
    }

    static JSONArray compact(JSONArray messages, Object providerConfig, boolean force)
            throws Exception {
        return compact(messages, providerConfig, force, KEEP_RECENT_MESSAGES);
    }

    interface ProgressListener {
        void onProgress(int completedChunks, int totalChunks);
    }

    static JSONArray compact(JSONArray messages, Object providerConfig, boolean force,
            int keepRecentMessages) throws Exception {
        return compact(messages, providerConfig, force, keepRecentMessages, null);
    }

    static JSONArray compact(JSONArray messages, Object providerConfig, boolean force,
            int keepRecentMessages, ProgressListener progressListener) throws Exception {
        if (messages == null || providerConfig == null) {
            return messages;
        }

        int leadingSystemCount = countLeadingSystemMessages(messages);
        int historyCount = messages.length() - leadingSystemCount;
        if (!force && historyCount <= TRIGGER_MESSAGE_COUNT) {
            return messages;
        }

        int safeKeepCount = Math.max(4, Math.min(keepRecentMessages, 128));
        int oldCount = historyCount - safeKeepCount;
        if (oldCount <= 0) {
            return messages;
        }

        JSONArray result = new JSONArray();
        for (int index = 0; index < leadingSystemCount; index++) {
            result.put(messages.get(index));
        }

        int oldStart = leadingSystemCount;
        int oldEnd = findSafeCompressionBoundary(messages, oldStart + oldCount,
                oldStart, messages.length());
        if (oldEnd <= oldStart) {
            return messages;
        }
        List<Chunk> chunks = buildChunks(messages, oldStart, oldEnd, MAX_CHUNK_MESSAGES,
                MAX_CHUNK_CHARS);
        int totalChunks = chunks.size();
        int completedChunks = 0;
        for (Chunk chunk : chunks) {
            String summary = summarizeChunkLocally(messages, chunk.start, chunk.end);
            JSONObject summaryMessage = new JSONObject();
            summaryMessage.put("role", "user");
            summaryMessage.put("content", summary);
            result.put(summaryMessage);
            completedChunks++;
            if (progressListener != null) {
                progressListener.onProgress(completedChunks, totalChunks);
            }
        }
        for (int index = oldEnd; index < messages.length(); index++) {
            result.put(messages.get(index));
        }
        // Do not hand a larger request to the caller. The coordinator performs the same
        // validation after serialization, but this guard also protects direct callers.
        if (!isStrictlySmaller(messages, result)) {
            return messages;
        }
        return result;
    }

    private static boolean isStrictlySmaller(JSONArray before, JSONArray after) {
        if (before == null || after == null || before == after) return false;
        String beforeText = before.toString();
        String afterText = after.toString();
        return afterText.length() < beforeText.length()
                && afterText.getBytes(StandardCharsets.UTF_8).length
                        < beforeText.getBytes(StandardCharsets.UTF_8).length;
    }


    private static List<Chunk> buildChunks(JSONArray messages, int start, int end,
            int maxMessages, int maxChars) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkStart = start;
        while (chunkStart < end) {
            int chunkEnd = chunkStart;
            int chars = 0;
            while (chunkEnd < end && chunkEnd - chunkStart < maxMessages) {
                int nextChars = messageCharLength(messages, chunkEnd);
                if (chunkEnd > chunkStart && chars + nextChars > maxChars) {
                    break;
                }
                chars += nextChars;
                chunkEnd++;
            }
            if (chunkEnd <= chunkStart) {
                chunkEnd = chunkStart + 1;
            }
            int turnBoundary = findTurnBoundary(messages, chunkEnd, end);
            if (turnBoundary > chunkStart && turnBoundary < chunkEnd) {
                chunkEnd = turnBoundary;
            }
            chunks.add(new Chunk(chunkStart, chunkEnd));
            chunkStart = chunkEnd;
        }
        return chunks;
    }

    private static int messageCharLength(JSONArray messages, int index) {
        JSONObject message = messages.optJSONObject(index);
        if (message == null) {
            Object item = messages.opt(index);
            return item == null ? 0 : Math.min(String.valueOf(item).length(), MAX_TRANSCRIPT_MESSAGE_CHARS);
        }
        int length = message.optString("role", "unknown").length() + 2;
        Object content = message.opt("content");
        length += compactedLength(content == null ? message.toString() : String.valueOf(content),
                MAX_TRANSCRIPT_MESSAGE_CHARS);
        Object toolCalls = toolCalls(message);
        if (toolCalls != null) {
            length += compactedLength(String.valueOf(toolCalls),
                    MAX_TRANSCRIPT_TOOL_CHARS) + 12;
        }
        return length + 1;
    }

    private static int compactedLength(String text, int maxChars) {
        return text == null ? 0 : Math.min(text.length(), maxChars + 96);
    }

    private static int findSafeCompressionBoundary(JSONArray messages, int preferred,
            int start, int end) {
        int candidate = findTurnBoundary(messages, preferred, end);
        while (candidate > start && !isSafeBoundary(messages, candidate, end)) {
            candidate = findTurnBoundary(messages, candidate - 1, end);
        }
        return candidate;
    }

    private static boolean isSafeBoundary(JSONArray messages, int boundary, int end) {
        JSONObject firstTail = messages.optJSONObject(boundary);
        if (firstTail != null && "tool".equals(firstTail.optString("role"))) {
            return false;
        }
        JSONObject previous = boundary <= 0 ? null : messages.optJSONObject(boundary - 1);
        if (previous != null && hasToolCalls(previous)) {
            return false;
        }
        for (int index = boundary; index < end; index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) continue;
            if ("tool".equals(message.optString("role"))) {
                return hasPrecedingToolCalls(messages, boundary, index);
            }
            if (hasToolCalls(message)) {
                return true;
            }
        }
        return true;
    }

    private static boolean hasPrecedingToolCalls(JSONArray messages, int start, int toolIndex) {
        for (int index = toolIndex - 1; index >= start; index--) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) continue;
            String role = message.optString("role");
            if ("tool".equals(role)) continue;
            return "assistant".equals(role) && hasToolCalls(message);
        }
        return false;
    }

    static boolean hasToolCalls(JSONObject message) {
        Object value = toolCalls(message);
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return !text.isEmpty() && !"[]".equals(text) && !"null".equals(text);
        }
        return value != null && !JSONObject.NULL.equals(value);
    }

    static int firstInvalidToolCallIndex(JSONArray messages) {
        boolean awaitingToolResult = false;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) continue;
            String role = message.optString("role");
            if ("tool".equals(role)) {
                if (!awaitingToolResult) return index;
                continue;
            }
            awaitingToolResult = "assistant".equals(role) && hasToolCalls(message);
        }
        return -1;
    }

    private static Object toolCalls(JSONObject message) {
        if (message == null) return null;
        return message.has("tool_calls") ? message.opt("tool_calls")
                : message.opt("_lspilot_tool_calls");
    }

    private static int findTurnBoundary(JSONArray messages, int preferred, int end) {
        for (int index = Math.min(preferred, end - 1); index > 0; index--) {
            JSONObject message = messages.optJSONObject(index);
            if (message != null && "user".equals(message.optString("role"))) {
                return index;
            }
        }
        return preferred;
    }

    private static int countLeadingSystemMessages(JSONArray messages) {
        int count = 0;
        while (count < messages.length()) {
            JSONObject message = messages.optJSONObject(count);
            if (message == null || !"system".equals(message.optString("role"))) {
                break;
            }
            count++;
        }
        return count;
    }

    private static String summarizeChunkLocally(JSONArray messages, int start, int end)
            throws Exception {
        int userCount = 0;
        int assistantCount = 0;
        int toolCount = 0;
        int otherCount = 0;
        int originalChars = 0;
        int chunkCharEstimate = 0;
        for (int index = start; index < end; index++) {
            JSONObject message = messages.optJSONObject(index);
            String role = message == null ? "unknown" : message.optString("role", "unknown");
            if ("user".equals(role)) userCount++;
            else if ("assistant".equals(role)) assistantCount++;
            else if ("tool".equals(role)) toolCount++;
            else otherCount++;
            String text = message == null ? String.valueOf(messages.opt(index))
                    : String.valueOf(message.opt("content"));
            originalChars += text.length();
            chunkCharEstimate += messageCharLength(messages, index);
        }

        // Excerpts are useful anchors, but retaining every user message made the local
        // summary approach the size of the source. Keep a bounded fraction of each chunk.
        int summaryBudget = Math.max(768, Math.min(12_000, chunkCharEstimate / 4));
        String header = "[Summary of previous conversation]\n"
                + "This is a deterministic local compression block; no model call was used.\n"
                + "Covered message indexes: " + start + "-" + (end - 1)
                + "; count=" + (end - start)
                + "; roles user=" + userCount + ", assistant=" + assistantCount
                + ", tool=" + toolCount + ", other=" + otherCount
                + "; originalChars=" + originalChars + ".\n"
                + "Key excerpts and anchors:\n";
        StringBuilder summary = new StringBuilder(Math.min(summaryBudget, header.length()));
        appendWithinBudget(summary, header, summaryBudget);
        for (int index = start; index < end && summary.length() < summaryBudget; index++) {
            JSONObject message = messages.optJSONObject(index);
            String role = message == null ? "unknown" : message.optString("role", "unknown");
            String text = message == null ? String.valueOf(messages.opt(index))
                    : String.valueOf(message.opt("content"));
            if (!shouldKeepLocalExcerpt(role, text, index, start, end)) continue;
            appendWithinBudget(summary, role + " #" + (index - start + 1) + ": "
                    + compactTranscriptText(text, excerptLimit(role)) + '\n', summaryBudget);
            Object toolCalls = toolCalls(message);
            if (toolCalls != null && summary.length() < summaryBudget) {
                appendWithinBudget(summary, "tool_calls: " + compactTranscriptText(
                        String.valueOf(toolCalls), MAX_TRANSCRIPT_TOOL_CHARS) + '\n', summaryBudget);
            }
        }
        return summary.toString();
    }

    private static void appendWithinBudget(StringBuilder target, String value, int budget) {
        if (value == null || target.length() >= budget) return;
        int remaining = budget - target.length();
        target.append(value, 0, Math.min(remaining, value.length()));
    }

    private static boolean shouldKeepLocalExcerpt(String role, String text, int index,
            int start, int end) {
        if (index < start + 2 || index >= end - 2) return true;
        if ("assistant".equals(role)) {
            return containsAny(text, "commit", "error", "failed", "失败", "修复", "完成", "TODO", "Phase", "测试", "PASS");
        }
        if ("tool".equals(role)) {
            return containsAny(text, "ERROR", "FAIL", "Exception", "Traceback", "PASS", "commit", "diff", "mismatch");
        }
        return false;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private static int excerptLimit(String role) {
        if ("tool".equals(role)) return 1_200;
        if ("assistant".equals(role)) return 2_400;
        return 3_200;
    }

    private static String compactTranscriptText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int head = Math.max(1, maxChars * 2 / 3);
        int tail = Math.max(1, maxChars - head);
        return text.substring(0, head)
                + "\n[... omitted " + (text.length() - maxChars)
                + " chars; sha256=" + safeSha256(text) + " ...]\n"
                + text.substring(text.length() - tail);
    }

    private static String safeSha256(String value) {
        try {
            return sha256(value);
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static final class Chunk {
        final int start;
        final int end;

        Chunk(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}