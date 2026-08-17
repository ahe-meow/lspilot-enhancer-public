package com.lspilot.enhancer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HostUpdateDetectionCheck {
    private HostUpdateDetectionCheck() {
    }

    public static void main(String[] args) throws Exception {
        File base = File.createTempFile("lspilot-base-", ".apk");
        File split = File.createTempFile("lspilot-split-", ".apk");
        try {
            write(base, "base-content");
            write(split, "split-content-a");
            String first = HostAbiCache.hostFingerprint(
                    "ignored", 0, new String[]{base.getPath(), split.getPath()});
            String reordered = HostAbiCache.hostFingerprint(
                    "ignored", 0, new String[]{split.getPath(), base.getPath()});
            assertEquals(first, reordered, "split order must not change the fingerprint");

            write(split, "split-content-b");
            String changed = HostAbiCache.hostFingerprint(
                    "ignored", 0, new String[]{base.getPath(), split.getPath()});
            assertNotEquals(first, changed, "split content change must invalidate the cache");
        } finally {
            base.delete();
            split.delete();
        }

        JSONObject cached = new JSONObject()
                .put("schema", 7)
                .put("hostFingerprint", "old-host-content")
                .put("moduleVersionCode", BuildConfig.VERSION_CODE)
                .put("fingerprint", "old-composite")
                .put("abi", new JSONObject());
        assertEquals("host_update",
                HostAbiCache.rebuildReason(cached, "new-host-content", "new-composite"),
                "content change must trigger host update");

        cached.put("hostFingerprint", "new-host-content").put("fingerprint", "new-composite");
        assertEquals(null,
                HostAbiCache.rebuildReason(cached, "new-host-content", "new-composite"),
                "unchanged content must keep the cache");

        cached.put("schema", 6);
        assertEquals("cache_schema_changed",
                HostAbiCache.rebuildReason(cached, "new-host-content", "new-composite"),
                "scanner schema change must invalidate cache");
        System.out.println("HostUpdateDetectionCheck: PASS");
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": " + actual);
        }
    }

    private static void assertNotEquals(String first, String second, String message) {
        if (first == null ? second == null : first.equals(second)) {
            throw new AssertionError(message + ": " + first);
        }
    }
}
