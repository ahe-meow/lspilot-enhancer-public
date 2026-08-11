package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed Markdown contract used by model-generated context summaries. */
final class SummaryProtocol {
    static final String[] SECTION_TITLES = {
            "核心任务状态",
            "行为设定",
            "分阶段的对话历史概要",
            "关键信息与关键上下文",
            "最近 N 轮格式化完整上下文",
            "当前激活的 skill/函数/工具/MCP 等包信息",
            "后续执行要求"
    };

    static final String BASELINE_PREFIX = "[LSPilot model context summary]\n";
    static final String BASELINE_SUFFIX = "\n[End of LSPilot model context summary]";
    private static final String ENHANCER_MARKER = "[系统提示 · 上下文压缩]";
    private static final Pattern ROUND_PATTERN = Pattern.compile("^\\s*(\\d+)[.)]\\s+.*$");
    private static final Pattern TOOL_ID_PATTERN = Pattern.compile(
            "(?:\\\"id\\\"\\s*:\\s*\\\"|\\bid\\s*=\\s*)([A-Za-z0-9_.:-]+)");

    private SummaryProtocol() {
    }

    static final class Validation {
        final boolean success;
        final String reason;
        final List<Round> rounds;

        Validation(boolean success, String reason, List<Round> rounds) {
            this.success = success;
            this.reason = reason == null ? "" : reason;
            this.rounds = rounds == null
                    ? java.util.Collections.<Round>emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(rounds));
        }

        static Validation ok(List<Round> rounds) {
            return new Validation(true, "", rounds);
        }

        static Validation fail(String reason) {
            return new Validation(false, reason, null);
        }
    }

    static final class Round {
        final int number;
        final String text;

        Round(int number, String text) {
            this.number = number;
            this.text = text == null ? "" : text;
        }
    }

    static String buildPrompt(JSONArray source, int keepRecent, int thresholdTokens,
            String activeContext) {
        int rounds = Math.max(1, keepRecent);
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是当前对话的上下文压缩器。请只返回严格 Markdown，不要返回解释、工具调用、函数调用、思考元数据或 JSON。\n")
                .append("上下文有效模型阈值：").append(Math.max(0, thresholdTokens)).append(" tokens。\n")
                .append("必须保留以下七个二级标题，顺序、拼写和数量都不能改变；每个标题下必须有非空内容。\n")
                .append("第五节必须包含最近 ").append(rounds).append(" 轮完整格式化上下文，使用 1. 到 ")
                .append(rounds).append(". 编号。不要把模块状态消息或内部字段写入摘要。\n")
                .append("当前激活上下文：").append(activeContext == null ? "" : activeContext).append("\n\n")
                .append("固定输出结构：\n");
        for (int index = 0; index < SECTION_TITLES.length; index++) {
            String title = SECTION_TITLES[index];
            if (index == 4) title = title.replace("N", Integer.toString(rounds));
            prompt.append("## ").append(title).append("\n")
                    .append(index == 4 ? "1. [完整轮次内容]\n" : "[填写本节内容]\n");
        }
        prompt.append("\n待压缩的有效 Provider 上下文（仅作为事实来源）：\n")
                .append(source == null ? "[]" : source.toString());
        return prompt.toString();
    }

    static Validation validateTerminalMarkdown(String markdown, JSONArray source, int keepRecent) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return Validation.fail("empty summary");
        }
        if (source == null || !hasCompleteToolPairs(source)) {
            return Validation.fail("source has incomplete tool pairs");
        }
        if (containsForbiddenMetadata(markdown)) {
            return Validation.fail("summary contains internal or non-terminal metadata");
        }
        int roundsRequired = Math.max(1, keepRecent);
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<StringBuilder> sections = new ArrayList<>();
        StringBuilder preamble = new StringBuilder();
        int current = -1;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                String title = line.substring(3).trim();
                int expectedIndex = sections.size();
                if (expectedIndex >= SECTION_TITLES.length
                        || !expectedTitle(expectedIndex, roundsRequired).equals(title)) {
                    return Validation.fail("wrong section order or heading: " + title);
                }
                sections.add(new StringBuilder());
                current = expectedIndex;
            } else if (current < 0) {
                preamble.append(line);
            } else {
                sections.get(current).append(line).append('\n');
            }
        }
        if (current < 0 || sections.size() != SECTION_TITLES.length || !preamble.toString().trim().isEmpty()) {
            return Validation.fail("missing or extra sections");
        }
        for (StringBuilder section : sections) {
            if (section.toString().trim().isEmpty()) return Validation.fail("empty section");
        }
        List<Round> rounds = parseRounds(sections.get(4).toString(), roundsRequired);
        if (rounds == null) return Validation.fail("wrong recent-round count");
        return Validation.ok(rounds);
    }

    static JSONObject wrapBaseline(String markdown) throws Exception {
        if (markdown == null) throw new IllegalArgumentException("markdown == null");
        return new JSONObject().put("role", "user")
                .put("content", BASELINE_PREFIX + markdown + BASELINE_SUFFIX);
    }

    static JSONArray rebuildEffectiveRequest(JSONArray systemMessages,
            JSONObject summaryBaseline, JSONArray postBoundaryMessages,
            JSONObject pendingUser) throws Exception {
        if (summaryBaseline == null) {
            throw new IllegalArgumentException("summaryBaseline == null");
        }
        JSONArray result = new JSONArray();
        appendSanitized(result, systemMessages);
        result.put(sanitizedMessage(summaryBaseline));
        appendSanitized(result, postBoundaryMessages);
        if (pendingUser != null) {
            JSONObject pending = sanitizedMessage(pendingUser);
            boolean seen = false;
            for (int index = 0; index < result.length(); index++) {
                JSONObject message = result.optJSONObject(index);
                if (message != null
                        && message.optString("role").equals(pending.optString("role"))
                        && message.optString("content").equals(pending.optString("content"))) {
                    seen = true;
                    break;
                }
            }
            if (!seen) result.put(pending);
        }
        return result;
    }

    static boolean hasCompleteToolPairs(JSONArray messages) {
        if (messages == null) return false;
        Set<String> pending = new HashSet<>();
        int legacyPending = 0;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) return false;
            String role = message.optString("role", "");
            if ("assistant".equals(role) && hasToolCalls(message)) {
                if (!pending.isEmpty() || legacyPending > 0) return false;
                List<String> ids = toolCallIds(message);
                if (ids.isEmpty()) {
                    legacyPending = 1;
                } else {
                    pending.addAll(ids);
                }
            } else if ("tool".equals(role)) {
                String id = message.optString("tool_call_id",
                        message.optString("_lspilot_tool_call_id", ""));
                if (!id.isEmpty()) {
                    if (!pending.remove(id)) return false;
                } else if (legacyPending == 1 && pending.isEmpty()) {
                    legacyPending = 0;
                } else {
                    return false;
                }
            } else if (!pending.isEmpty() || legacyPending > 0) {
                return false;
            }
        }
        return pending.isEmpty() && legacyPending == 0;
    }

    static int estimateTokens(JSONArray messages) {
        if (messages == null) return 0;
        String text = messages.toString();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(0, Math.max(Math.round(text.length() / 4f), Math.round(bytes / 3f)));
    }

    private static String expectedTitle(int index, int keepRecent) {
        return index == 4
                ? SECTION_TITLES[index].replace("N", Integer.toString(keepRecent))
                : SECTION_TITLES[index];
    }

    private static List<Round> parseRounds(String text, int required) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<Round> result = new ArrayList<>();
        StringBuilder current = null;
        int expected = 1;
        for (String line : lines) {
            Matcher matcher = ROUND_PATTERN.matcher(line);
            if (matcher.matches()) {
                int number = Integer.parseInt(matcher.group(1));
                if (number != expected) return null;
                if (current != null) result.add(new Round(expected - 1, current.toString().trim()));
                current = new StringBuilder(line.trim());
                expected++;
            } else if (current != null) {
                current.append('\n').append(line);
            } else if (!line.trim().isEmpty()) {
                return null;
            }
        }
        if (current != null) result.add(new Round(expected - 1, current.toString().trim()));
        return result.size() == required ? result : null;
    }

    private static boolean containsForbiddenMetadata(String markdown) {
        String lower = markdown.toLowerCase(java.util.Locale.ROOT);
        return markdown.contains(ENHANCER_MARKER)
                || markdown.contains("[系统提示")
                || markdown.contains("_lspilot_")
                || lower.contains("tool_calls")
                || lower.contains("tool_call_id")
                || lower.contains("function_call")
                || lower.contains("<tool_call")
                || lower.contains("finish_reason")
                || lower.contains("\"role\":\"tool\"");
    }

    private static void appendSanitized(JSONArray target, JSONArray source) throws Exception {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject message = source.optJSONObject(index);
            if (message == null) throw new IllegalArgumentException("message must be object");
            if (!isEnhancerStatus(message)) target.put(sanitizedMessage(message));
        }
    }

    static JSONObject sanitizedMessage(JSONObject message) throws Exception {
        JSONObject copy = new JSONObject(message.toString());
        java.util.Iterator<String> names = copy.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (name != null && name.startsWith("_lspilot_")) copy.remove(name);
        }
        return copy;
    }

    static boolean isEnhancerStatus(JSONObject message) {
        return message != null && ("system".equals(message.optString("role"))
                || "assistant".equals(message.optString("role")))
                && message.optString("content", "").startsWith(ENHANCER_MARKER);
    }

    private static boolean hasToolCalls(JSONObject message) {
        Object value = message.opt("tool_calls");
        if (value == null || JSONObject.NULL.equals(value)) value = message.opt("_lspilot_tool_calls");
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return !text.isEmpty() && !"[]".equals(text) && !"null".equalsIgnoreCase(text);
        }
        return value != null && !JSONObject.NULL.equals(value);
    }

    private static List<String> toolCallIds(JSONObject message) {
        List<String> result = new ArrayList<>();
        Object value = message.has("tool_calls") ? message.opt("tool_calls")
                : message.opt("_lspilot_tool_calls");
        if (value instanceof JSONArray) {
            JSONArray calls = (JSONArray) value;
            for (int index = 0; index < calls.length(); index++) {
                JSONObject call = calls.optJSONObject(index);
                if (call != null && !call.optString("id", "").isEmpty()) {
                    result.add(call.optString("id"));
                }
            }
        } else if (value != null) {
            Matcher matcher = TOOL_ID_PATTERN.matcher(String.valueOf(value));
            while (matcher.find()) result.add(matcher.group(1));
        }
        return result;
    }
}
