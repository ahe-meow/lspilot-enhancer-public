package dev.operit.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONObject;

/** Runnable non-UI protocol checks; assertions are enabled by the dalvikvm command. */
public final class ModelContextCompressionCheck {
    private ModelContextCompressionCheck() {
    }

    public static void main(String[] args) throws Exception {
        JSONArray source = pairedToolMessages();
        String prompt = SummaryProtocol.buildPrompt(source, 3, 1000,
                "skill=brainstorming; tools=current request");
        assertContains(prompt, "## 核心任务状态");
        assertContains(prompt, "## 最近 3 轮格式化完整上下文");
        assertOrdered(prompt, SummaryProtocol.SECTION_TITLES);

        SummaryProtocol.Validation valid = SummaryProtocol.validateTerminalMarkdown(
                validMarkdown(3), source, 3);
        assertTrue(valid.success, "valid seven-section summary must pass");
        assertEquals(3, valid.rounds.size(), "three rounds must be parsed");
        assertTrue(!SummaryProtocol.validateTerminalMarkdown(
                malformedOrderMarkdown(), source, 3).success,
                "wrong section order must fail");
        assertTrue(!SummaryProtocol.validateTerminalMarkdown(
                toolCallOnlyResponse(), source, 3).success,
                "tool-call response must fail without executing a tool");
        assertTrue(!SummaryProtocol.validateTerminalMarkdown(
                markdownWithEnhancerMarker(), source, 3).success,
                "module marker must not enter a summary");
        assertEquals("user", SummaryProtocol.wrapBaseline(validMarkdown(3)).optString("role"),
                "baseline must be a user message");
        assertContains(SummaryProtocol.wrapBaseline(validMarkdown(3)).optString("content"),
                validMarkdown(3));
        assertTrue(SummaryProtocol.hasCompleteToolPairs(pairedToolMessages()),
                "paired tool calls must pass");
        assertTrue(!SummaryProtocol.hasCompleteToolPairs(unpairedToolMessages()),
                "unpaired tool calls must fail");
        assertTrue(SummaryProtocol.estimateTokens(source) > 0,
                "effective context must have a positive estimate");
    }

    private static String validMarkdown(int rounds) {
        StringBuilder result = new StringBuilder();
        result.append("## 核心任务状态\n任务正在执行。\n")
                .append("## 行为设定\n保持当前行为设定。\n")
                .append("## 分阶段的对话历史概要\n阶段一完成。\n")
                .append("## 关键信息与关键上下文\n关键上下文保留。\n")
                .append("## 最近 ").append(rounds).append(" 轮格式化完整上下文\n");
        for (int index = 1; index <= rounds; index++) {
            result.append(index).append(". 第").append(index).append("轮：用户与助手的完整上下文。\n");
        }
        return result.append("## 当前激活的 skill/函数/工具/MCP 等包信息\n当前包信息已保留。\n")
                .append("## 后续执行要求\n继续执行后续任务。\n").toString();
    }

    private static String malformedOrderMarkdown() {
        return validMarkdown(3).replace("## 行为设定", "## 后续执行要求")
                .replace("## 后续执行要求", "## 行为设定");
    }

    private static String toolCallOnlyResponse() {
        return validMarkdown(3).replace("任务正在执行。", "tool_calls: call_1");
    }

    private static String markdownWithEnhancerMarker() {
        return validMarkdown(3).replace("任务正在执行。", "[系统提示 · 上下文压缩]");
    }

    private static JSONArray pairedToolMessages() throws Exception {
        JSONArray result = new JSONArray();
        result.put(message("system", "system"));
        result.put(message("user", "question"));
        result.put(message("assistant", "answer"));
        result.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "call_1"))));
        result.put(new JSONObject().put("role", "tool").put("content", "result")
                .put("tool_call_id", "call_1"));
        result.put(message("user", "next"));
        return result;
    }

    private static JSONArray unpairedToolMessages() throws Exception {
        JSONArray result = pairedToolMessages();
        result.remove(result.length() - 2);
        return result;
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static void assertOrdered(String value, String[] titles) {
        int previous = -1;
        for (int index = 0; index < titles.length; index++) {
            String title = titles[index].replace("N", "3");
            int current = value.indexOf("## " + title);
            assertTrue(current >= 0 && current > previous,
                    "heading order failed for " + title);
            previous = current;
        }
    }

    private static void assertContains(String value, String expected) {
        assertTrue(value != null && value.contains(expected),
                "expected text was absent: " + expected);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertTrue(expected == null ? actual == null : expected.equals(actual),
                message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}