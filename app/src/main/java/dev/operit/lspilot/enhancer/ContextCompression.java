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
import java.util.LinkedHashMap;
import java.util.Map;

/** Model-backed request compaction. The host conversation database is never modified. */
final class ContextCompression {
    static final int KEEP_RECENT_MESSAGES = 32;
    static final int TRIGGER_MESSAGE_COUNT = 40;
    static final int MAX_CHUNK_MESSAGES = 256;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
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
        int oldEnd = findTurnBoundary(messages, oldStart + oldCount, messages.length());
        if (oldEnd <= oldStart) {
            return messages;
        }
        int totalChunks = Math.max(1, (oldEnd - oldStart + MAX_CHUNK_MESSAGES - 1)
                / MAX_CHUNK_MESSAGES);
        int completedChunks = 0;
        for (int chunkStart = oldStart; chunkStart < oldEnd;
                chunkStart += MAX_CHUNK_MESSAGES) {
            int chunkEnd = Math.min(chunkStart + MAX_CHUNK_MESSAGES, oldEnd);
            String summary = summarizeChunk(messages, chunkStart, chunkEnd, providerConfig);
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
            transcript.append(content == null ? message.toString() : String.valueOf(content));
            if (message.has("tool_calls")) {
                transcript.append("\ntool_calls: ").append(message.opt("tool_calls"));
            }
            transcript.append('\n');
        }
        return transcript.toString();
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