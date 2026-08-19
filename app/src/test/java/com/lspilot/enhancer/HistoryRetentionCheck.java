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

        assertTrue(HistoryRetention.hasExpectedPersistedCount(Arrays.asList(old), 1),
                "matching persisted count must pass");
        assertTrue(!HistoryRetention.hasExpectedPersistedCount(Arrays.asList(old), 0),
                "count mismatch must fail closed");
        assertTrue(HistoryRetention.hasExpectedPersistedCount(Arrays.asList(), 0),
                "empty persisted list must match zero rows");
        assertTrue(!HistoryRetention.hasExpectedPersistedCount(Arrays.asList(), 1),
                "empty persisted list must reject nonzero rows");

        List<Object> onlyCurrent = HistoryRetention.merge(
                Arrays.asList(), Arrays.asList(added), message -> ((Message) message).id);
        assertTrue(onlyCurrent != null && onlyCurrent.size() == 1,
                "empty persisted state must accept current rows");
        assertSame(added, onlyCurrent.get(0),
                "current row must be retained after empty persisted state");

        List<Object> unchanged = HistoryRetention.merge(
                Arrays.asList(old, shared), Arrays.asList(), message -> ((Message) message).id);
        assertTrue(unchanged != null && unchanged.size() == 2,
                "empty current state must not delete persisted rows");

        List<Object> empty = HistoryRetention.merge(
                Arrays.asList(), Arrays.asList(), message -> ((Message) message).id);
        assertTrue(empty != null && empty.isEmpty(),
                "empty persisted and current states must remain empty");

        List<Object> duplicateOnly = HistoryRetention.merge(
                Arrays.asList(), Arrays.asList(added, new Message("added", "duplicate")),
                message -> ((Message) message).id);
        assertTrue(duplicateOnly == null,
                "duplicate current IDs must fail closed with no persisted rows");

        List<Object> blankOnly = HistoryRetention.merge(
                Arrays.asList(), Arrays.asList(new Message("", "unsafe")),
                message -> ((Message) message).id);
        assertTrue(blankOnly == null,
                "blank current IDs must fail closed with no persisted rows");

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
