package com.lspilot.enhancer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class RequestJsonPolicyCheck {
    private static final String INPUT = "{\"model\":\"demo\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"x\"}],"
            + "\"stream\":true,\"max_tokens\":321,\"reasoning_effort\":\"low\","
            + "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024},"
            + "\"unknown\":{\"keep\":true,\"nested\":[1,2]}}";

    private static final String GENERIC_WITHOUT_REASONING = "{\"model\":\"demo\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],"
            + "\"stream\":true,\"unknown\":{\"keep\":true}}";

    private static final String THINKING_WITHOUT_REASONING = "{\"model\":\"demo\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],"
            + "\"max_tokens\":321,\"unknown\":{\"keep\":true}}";

    private static final String MALFORMED = "{\"model\":\"demo\",\"messages\":[";

    public RequestJsonPolicyCheck() {
    }

    @org.junit.Test
    public void runsAssertions() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        assertProviderKinds();
        assertOffRemovesOnlyRecognizedFields();
        assertGenericRewritesOnlyItsField();
        assertGenericAddsMissingField();
        assertThinkingRewritesOnlyItsField();
        assertAllPolicyMappings();
        assertSourceIsNotMutated();
        assertExactFallbacksAndNoOutput();
    }

    private static void assertProviderKinds() {
        RequestJsonPolicy.ProviderKind[] kinds = RequestJsonPolicy.ProviderKind.values();
        assert kinds.length == 3;
        assert kinds[0] == RequestJsonPolicy.ProviderKind.GENERIC;
        assert kinds[1] == RequestJsonPolicy.ProviderKind.THINKING;
        assert kinds[2] == RequestJsonPolicy.ProviderKind.UNKNOWN;
    }

    private static void assertOffRemovesOnlyRecognizedFields() {
        JSONObject off = parse(RequestJsonPolicy.rewrite(
                INPUT, RequestJsonPolicy.ProviderKind.GENERIC, "off"));
        assert !off.has("reasoning_effort");
        assert !off.has("thinking");
        assert "demo".equals(getString(off, "model"));
        assert getBoolean(getObject(off, "unknown"), "keep");
        assert getArray(off, "messages").length() == 1;
        assert "user".equals(getString(getArrayObject(getArray(off, "messages"), 0), "role"));
        assert "x".equals(getString(getArrayObject(getArray(off, "messages"), 0), "content"));
        assert getBoolean(off, "stream");
        assert getInt(off, "max_tokens") == 321;

        JSONObject thinkingOff = parse(RequestJsonPolicy.rewrite(
                INPUT, RequestJsonPolicy.ProviderKind.THINKING, "  "));
        assert !thinkingOff.has("reasoning_effort");
        assert !thinkingOff.has("thinking");
        assert "demo".equals(getString(thinkingOff, "model"));
        assert getInt(thinkingOff, "max_tokens") == 321;
    }

    private static void assertGenericRewritesOnlyItsField() {
        JSONObject generic = parse(RequestJsonPolicy.rewrite(
                INPUT, RequestJsonPolicy.ProviderKind.GENERIC, " MAX "));
        assert "max".equals(getString(generic, "reasoning_effort"));
        JSONObject thinking = getObject(generic, "thinking");
        assert "enabled".equals(getString(thinking, "type"));
        assert getInt(thinking, "budget_tokens") == 1024;
        assert "demo".equals(getString(generic, "model"));
        assert getBoolean(generic, "stream");
        assert getInt(generic, "max_tokens") == 321;
        assert getBoolean(getObject(generic, "unknown"), "keep");
        assert getArray(generic, "messages").length() == 1;
        assert "x".equals(getString(
                getArrayObject(getArray(generic, "messages"), 0), "content"));

        JSONObject helperBody = parse(INPUT);
        JSONArray messages = getArray(helperBody, "messages");
        JSONObject unknown = getObject(helperBody, "unknown");
        assert RequestJsonPolicy.rewriteObject(
                helperBody, RequestJsonPolicy.ProviderKind.GENERIC, "max") == helperBody;
        assert getArray(helperBody, "messages") == messages;
        assert getObject(helperBody, "unknown") == unknown;
        assert "max".equals(getString(helperBody, "reasoning_effort"));
    }

    private static void assertGenericAddsMissingField() {
        JSONObject generic = parse(RequestJsonPolicy.rewrite(
                GENERIC_WITHOUT_REASONING, RequestJsonPolicy.ProviderKind.GENERIC, "high"));
        assert "high".equals(getString(generic, "reasoning_effort"));
        assert "demo".equals(getString(generic, "model"));
        assert getBoolean(generic, "stream");
        assert getBoolean(getObject(generic, "unknown"), "keep");
        assert getArray(generic, "messages").length() == 1;
    }

    private static void assertThinkingRewritesOnlyItsField() {
        JSONObject thinking = parse(RequestJsonPolicy.rewrite(
                THINKING_WITHOUT_REASONING, RequestJsonPolicy.ProviderKind.THINKING, "xhigh"));
        assert !thinking.has("reasoning_effort");
        JSONObject thinkingField = getObject(thinking, "thinking");
        assert "enabled".equals(getString(thinkingField, "type"));
        assert getInt(thinkingField, "budget_tokens") == 16384;
        assert getInt(thinking, "max_tokens") == 321;
        assert getArray(thinking, "messages").length() == 1;
        assert getBoolean(getObject(thinking, "unknown"), "keep");

        JSONObject preserved = parse(RequestJsonPolicy.rewrite(
                INPUT, RequestJsonPolicy.ProviderKind.THINKING, "medium"));
        assert "low".equals(getString(preserved, "reasoning_effort"));
        assert getInt(getObject(preserved, "thinking"), "budget_tokens") == 4096;
    }

    private static void assertAllPolicyMappings() {
        String[] values = {"off", "low", "medium", "high", "xhigh", "max"};
        Integer[] budgets = {
                null,
                Integer.valueOf(1024),
                Integer.valueOf(4096),
                Integer.valueOf(8192),
                Integer.valueOf(16384),
                Integer.valueOf(16384)
        };

        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            JSONObject generic = parse(RequestJsonPolicy.rewrite(
                    INPUT, RequestJsonPolicy.ProviderKind.GENERIC, value));
            JSONObject thinking = parse(RequestJsonPolicy.rewrite(
                    INPUT, RequestJsonPolicy.ProviderKind.THINKING, value));
            if (i == 0) {
                assert !generic.has("reasoning_effort");
                assert !generic.has("thinking");
                assert !thinking.has("reasoning_effort");
                assert !thinking.has("thinking");
            } else {
                assert value.equals(getString(generic, "reasoning_effort"));
                assert "enabled".equals(getString(
                        getObject(thinking, "thinking"), "type"));
                assert budgets[i].intValue() == getInt(
                        getObject(thinking, "thinking"), "budget_tokens");
            }
        }

        assert "off".equals(ReasoningPolicy.normalize(null));
        assert "off".equals(ReasoningPolicy.normalize("  "));
        assert "off".equals(ReasoningPolicy.normalize("unsupported"));
        JSONObject normalizedOff = parse(RequestJsonPolicy.rewrite(
                INPUT, RequestJsonPolicy.ProviderKind.GENERIC, null));
        assert !normalizedOff.has("reasoning_effort");
        assert !normalizedOff.has("thinking");
    }

    private static void assertSourceIsNotMutated() {
        JSONObject source = parse(INPUT);
        JSONArray sourceMessages = getArray(source, "messages");
        JSONObject sourceUnknown = getObject(source, "unknown");
        String sourceMessagesBefore = sourceMessages.toString();
        String sourceUnknownBefore = sourceUnknown.toString();
        String serializedSource = source.toString();

        JSONObject rewritten = parse(RequestJsonPolicy.rewrite(
                serializedSource, RequestJsonPolicy.ProviderKind.GENERIC, "low"));
        assert sourceMessages == getArray(source, "messages");
        assert sourceUnknown == getObject(source, "unknown");
        assert sourceMessagesBefore.equals(sourceMessages.toString());
        assert sourceUnknownBefore.equals(sourceUnknown.toString());
        assert "low".equals(getString(rewritten, "reasoning_effort"));
    }

    private static void assertExactFallbacksAndNoOutput() {
        String unsupportedThinkingShape = "{\"model\":\"demo\",\"messages\":[],"
                + "\"reasoning_effort\":\"low\",\"unknown\":7}";
        String blankModel = "{\"model\":\" \",\"messages\":[],"
                + "\"reasoning_effort\":\"low\"}";
        String missingMessages = "{\"model\":\"demo\",\"stream\":true} ";

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(output);
        try {
            System.setOut(capture);
            System.setErr(capture);
            String input = INPUT;
            assert input.equals(RequestJsonPolicy.rewrite(
                    input, RequestJsonPolicy.ProviderKind.UNKNOWN, "high"));
            assert input.equals(RequestJsonPolicy.rewrite(
                    input, null, "high"));
            assert MALFORMED.equals(RequestJsonPolicy.rewrite(
                    MALFORMED, RequestJsonPolicy.ProviderKind.GENERIC, "high"));
            assert "[]".equals(RequestJsonPolicy.rewrite(
                    "[]", RequestJsonPolicy.ProviderKind.GENERIC, "high"));
            assert unsupportedThinkingShape.equals(RequestJsonPolicy.rewrite(
                    unsupportedThinkingShape, RequestJsonPolicy.ProviderKind.THINKING, "high"));
            assert unsupportedThinkingShape.equals(RequestJsonPolicy.rewrite(
                    unsupportedThinkingShape, RequestJsonPolicy.ProviderKind.THINKING, "off"));
            assert blankModel.equals(RequestJsonPolicy.rewrite(
                    blankModel, RequestJsonPolicy.ProviderKind.GENERIC, "high"));
            assert missingMessages.equals(RequestJsonPolicy.rewrite(
                    missingMessages, RequestJsonPolicy.ProviderKind.GENERIC, "high"));
            String trailingText = "{\"model\":\"demo\",\"messages\":[]} trailing";
            assert trailingText.equals(RequestJsonPolicy.rewrite(
                    trailingText, RequestJsonPolicy.ProviderKind.GENERIC, "high"));
            assert RequestJsonPolicy.rewrite(
                    null, RequestJsonPolicy.ProviderKind.GENERIC, "high") == null;
        } finally {
            capture.flush();
            System.setOut(oldOut);
            System.setErr(oldErr);
            capture.close();
        }
        assert output.size() == 0;
    }

    private static JSONObject parse(String value) {
        try {
            return new JSONObject(value);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }

    private static String getString(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }

    private static JSONObject getObject(JSONObject object, String key) {
        try {
            return object.getJSONObject(key);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }

    private static JSONArray getArray(JSONObject object, String key) {
        try {
            return object.getJSONArray(key);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }

    private static JSONObject getArrayObject(JSONArray array, int index) {
        try {
            return array.getJSONObject(index);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }

    private static boolean getBoolean(JSONObject object, String key) {
        try {
            return object.getBoolean(key);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }

    private static int getInt(JSONObject object, String key) {
        try {
            return object.getInt(key);
        } catch (JSONException exception) {
            throw new AssertionError();
        }
    }
}
