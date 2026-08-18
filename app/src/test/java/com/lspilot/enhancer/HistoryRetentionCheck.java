package com.lspilot.enhancer;

import java.util.Arrays;
import java.util.List;

public final class HistoryRetentionCheck {
    private HistoryRetentionCheck() {
    }

    public static void main(String[] args) throws Exception {
        Message old = new Message("old", "old");
        Message shared = new Message("shared", "before");
        Message updated = new Message("shared", "after");
        Message added = new Message("added", "new");

        List<Object> merged = HistoryRetention.merge(
                Arrays.asList(old, shared),
                Arrays.asList(updated, added),
                message -> ((Message) message).id);
        assertTrue(merged != null && merged.size() == 3, "merge must preserve all rows");
        assertSame(old, merged.get(0), "older row must remain first");
        assertSame(updated, merged.get(1), "current row must replace its persisted version");
        assertSame(added, merged.get(2), "new row must be appended");

        List<Object> unchanged = HistoryRetention.merge(
                Arrays.asList(old, shared), Arrays.asList(), message -> ((Message) message).id);
        assertTrue(unchanged != null && unchanged.size() == 2,
                "empty current state must not delete persisted rows");

        List<Object> duplicate = HistoryRetention.merge(
                Arrays.asList(old, new Message("old", "duplicate")),
                Arrays.asList(updated), message -> ((Message) message).id);
        assertTrue(duplicate == null, "duplicate persisted IDs must fail closed");

        List<Object> missingId = HistoryRetention.merge(
                Arrays.asList(old), Arrays.asList(new Message("", "unsafe")),
                message -> ((Message) message).id);
        assertTrue(missingId == null, "missing IDs must fail closed");
        System.out.println("HistoryRetentionCheck: PASS");
    }

    private static final class Message {
        final String id;
        final String content;

        Message(String id, String content) {
            this.id = id;
            this.content = content;
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) throw new AssertionError(message);
    }
}
