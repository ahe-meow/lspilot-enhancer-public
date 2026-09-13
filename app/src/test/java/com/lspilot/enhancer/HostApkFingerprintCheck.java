package com.lspilot.enhancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class HostApkFingerprintCheck {
    @Test
    public void changedPrimaryOrSplitBytesChangeTheFingerprint() throws Exception {
        File primary = File.createTempFile("host-primary", ".apk");
        File split = File.createTempFile("host-split", ".apk");
        try {
            Files.write(primary.toPath(), new byte[]{1, 2, 3});
            Files.write(split.toPath(), new byte[]{4, 5});
            String first = HostApkFingerprint.compute(Arrays.asList(
                    primary.getAbsolutePath(), split.getAbsolutePath()));
            String reversed = HostApkFingerprint.compute(Arrays.asList(
                    split.getAbsolutePath(), primary.getAbsolutePath()));
            assertNotNull(first);
            assertEquals(64, first.length());
            assertEquals(first, reversed);

            Files.write(split.toPath(), new byte[]{4, 6});
            String changedSplit = HostApkFingerprint.compute(Arrays.asList(
                    primary.getAbsolutePath(), split.getAbsolutePath()));
            assertNotEquals(first, changedSplit);
        } finally {
            assertTrue(primary.delete());
            assertTrue(split.delete());
        }
    }

    @Test
    public void sameBytesAtDifferentPathsProduceTheSameFingerprint() throws Exception {
        File first = File.createTempFile("host-first", ".apk");
        File second = File.createTempFile("host-second", ".apk");
        try {
            byte[] bytes = new byte[]{9, 8, 7, 6};
            Files.write(first.toPath(), bytes);
            Files.write(second.toPath(), bytes);

            String firstFingerprint = HostApkFingerprint.compute(
                    Collections.singletonList(first.getAbsolutePath()));
            String secondFingerprint = HostApkFingerprint.compute(
                    Collections.singletonList(second.getAbsolutePath()));
            assertNotNull(firstFingerprint);
            assertTrue(firstFingerprint.matches("[0-9a-f]{64}"));
            assertEquals(firstFingerprint, secondFingerprint);
        } finally {
            assertTrue(first.delete());
            assertTrue(second.delete());
        }
    }

    @Test
    public void invalidOrMissingSourceDisablesFingerprinting() throws Exception {
        assertNull(HostApkFingerprint.compute(null));
        assertNull(HostApkFingerprint.compute(Collections.<String>emptyList()));
        assertNull(HostApkFingerprint.compute(Arrays.asList("/missing/host.apk")));
        assertNull(HostApkFingerprint.compute(Arrays.asList((String) null)));
        assertNull(HostApkFingerprint.compute(Arrays.asList("")));
    }

    @Test
    public void directoryPathDisablesFingerprinting() throws Exception {
        File directory = Files.createTempDirectory("host-directory").toFile();
        try {
            assertNull(HostApkFingerprint.compute(
                    Collections.singletonList(directory.getAbsolutePath())));
        } finally {
            assertTrue(directory.delete());
        }
    }
}
