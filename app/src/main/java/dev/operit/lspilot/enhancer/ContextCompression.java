package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-backed request compaction. The host conversation database is never modified. */
final class ContextCompression {
    static final int KEEP_RECENT_MESSAGES = 32;
    static final int TRIGGER_MESSAGE_COUNT = 40;
    static final int MAX_CHUNK_MESSAGES = 96;
    private static final int MAX_CHUNK_CHARS = 80_000;
    private static final int MAX_TRANSCRIPT_MESSAGE_CHARS = 8_000;
    private static final int MAX_TRANSCRIPT_TOOL_CHARS = 3_000;
    private static final int MIN_RETRY_CHUNK_MESSAGES = 8;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_CACHE_ENTRIES = 24;

    private static final String SUMMARY_PROMPT =
            "Summarize the conversation provided below. Preserve key facts, user requirements, "
                    + "decisions, constraints, unresolved tasks, code identifiers, file paths, and "
                    + "important tool results. Keep the original language. Do not invent facts. "
                    + "Return only the summary, starting with [Summary of previous conversation].";

    private static final Map<String, String> SUMMARY_CACHE =
            new LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

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
        return result;
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
        if (message.has("tool_calls")) {
            length += compactedLength(String.valueOf(message.opt("tool_calls")),
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

    private static boolean hasToolCalls(JSONObject message) {
        if (message == null || !message.has("tool_calls")) return false;
        Object value = message.opt("tool_calls");
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        return value != null && !JSONObject.NULL.equals(value);
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
        StringBuilder excerpts = new StringBuilder();
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
            if (shouldKeepLocalExcerpt(role, text, index, start, end)) {
                excerpts.append(role).append(" #").append(index - start + 1).append(": ")
                        .append(compactTranscriptText(text, excerptLimit(role))).append('\n');
                if (message != null && message.has("tool_calls")) {
                    excerpts.append("tool_calls: ").append(compactTranscriptText(
                            String.valueOf(message.opt("tool_calls")), MAX_TRANSCRIPT_TOOL_CHARS))
                            .append('\n');
                }
            }
        }
        return "[Summary of previous conversation]\n"
                + "This is a deterministic local compression block; no model call was used.\n"
                + "Covered message indexes: " + start + "-" + (end - 1)
                + "; count=" + (end - start)
                + "; roles user=" + userCount + ", assistant=" + assistantCount
                + ", tool=" + toolCount + ", other=" + otherCount
                + "; originalChars=" + originalChars + ".\n"
                + "Key excerpts and anchors:\n" + excerpts;
    }

    private static boolean shouldKeepLocalExcerpt(String role, String text, int index,
            int start, int end) {
        if (index < start + 3 || index >= end - 3) return true;
        if ("user".equals(role)) return true;
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

    private static String summarizeChunkAdaptive(JSONArray messages, int start, int end,
            Object providerConfig) throws Exception {
        try {
            return summarizeChunk(messages, start, end, providerConfig);
        } catch (Exception error) {
            if (!isRetryableSocketFailure(error) || end - start <= MIN_RETRY_CHUNK_MESSAGES) {
                throw error;
            }
            int middle = findTurnBoundary(messages, start + Math.max(1, (end - start) / 2), end);
            if (middle <= start || middle >= end) {
                middle = start + Math.max(1, (end - start) / 2);
            }
            String first = summarizeChunkAdaptive(messages, start, middle, providerConfig);
            String second = summarizeChunkAdaptive(messages, middle, end, providerConfig);
            return first + "\n" + second;
        }
    }

    private static boolean isRetryableSocketFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            String name = current.getClass().getName();
            if (name.contains("SocketTimeoutException") || name.contains("SocketException")
                    || name.contains("EOFException")
                    || (message != null && (message.contains("Socket closed")
                    || message.contains("timeout") || message.contains("Connection reset")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String summarizeChunk(JSONArray messages, int start, int end,
            Object providerConfig) throws Exception {
        String model = invokeString(providerConfig, "getModelName");
        String endpoint = invokeString(providerConfig, "getFullApiUrl");
        String apiKey = invokeString(providerConfig, "getApiKey");
        String transcript = buildTranscript(messages, start, end);
        String cacheKey = sha256(endpoint + "\n" + model + "\n" + transcript);

        synchronized (SUMMARY_CACHE) {
            String cached = SUMMARY_CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("stream", false);
        request.put("temperature", 0.2);
        JSONArray requestMessages = new JSONArray();
        requestMessages.put(new JSONObject().put("role", "system")
                .put("content", SUMMARY_PROMPT));
        requestMessages.put(new JSONObject().put("role", "user")
                .put("content", transcript));
        request.put("messages", requestMessages);

        String summary = executeSummaryRequest(endpoint, apiKey, request.toString());
        if (summary == null || summary.trim().isEmpty()) {
            throw new JSONException("Compression model returned an empty summary");
        }
        summary = summary.trim();
        synchronized (SUMMARY_CACHE) {
            SUMMARY_CACHE.put(cacheKey, summary);
        }
        return summary;
    }

    private static String buildTranscript(JSONArray messages, int start, int end)
            throws JSONException {
        StringBuilder transcript = new StringBuilder();
        for (int index = start; index < end; index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) {
                transcript.append(messages.get(index)).append('\n');
                continue;
            }
            transcript.append(message.optString("role", "unknown")).append(": ");
            Object content = message.opt("content");
            transcript.append(content == null ? message.toString()
                    : compactTranscriptText(String.valueOf(content), MAX_TRANSCRIPT_MESSAGE_CHARS));
            if (message.has("tool_calls")) {
                transcript.append("\ntool_calls: ").append(compactTranscriptText(
                        String.valueOf(message.opt("tool_calls")), MAX_TRANSCRIPT_TOOL_CHARS));
            }
            transcript.append('\n');
        }
        return transcript.toString();
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

    private static String executeSummaryRequest(String endpoint, String apiKey, String body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Compression request failed with HTTP " + status);
            }
            JSONObject root = new JSONObject(response);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new JSONException("Compression response has no choices");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            return message == null ? null : message.optString("content", null);
        } finally {
            connection.disconnect();
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

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String invokeString(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        Object value = method.invoke(target);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalStateException(methodName + " returned an empty value");
        }
        return value.toString();
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