package com.lspilot.enhancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class HostApkFingerprintCheck {
    @Test
    public void sameSizeRewriteIsRejectedByStabilityGuard() throws Exception {
        File apk = File.createTempFile("host-stability", ".apk");
        try {
            Files.write(apk.toPath(), new byte[]{1, 2, 3, 4});
            Files.setLastModifiedTime(apk.toPath(), FileTime.fromMillis(1000L));
            HostApkFingerprint.Snapshot before = snapshot(apk);

            Files.write(apk.toPath(), new byte[]{4, 3, 2, 1});
            Files.setLastModifiedTime(apk.toPath(), FileTime.fromMillis(2000L));
            HostApkFingerprint.Snapshot between = snapshot(apk);
            HostApkFingerprint.Snapshot after = snapshot(apk);

            assertFalse(HostApkFingerprint.isStableSnapshot(
                    before, between, after, "same-digest", "same-digest"));
        } finally {
            assertTrue(apk.delete());
        }
    }

    @Test
    public void differingContentDigestsAreRejectedByStabilityGuard() {
        HostApkFingerprint.Snapshot snapshot = new HostApkFingerprint.Snapshot(
                true, 4L, FileTime.fromMillis(1000L), "identity");
        assertFalse(HostApkFingerprint.isStableSnapshot(
                snapshot, snapshot, snapshot, "digest-a", "digest-b"));
    }

    @Test
    public void unreadableSourceDisablesFingerprintingWhenReadPermissionIsRemoved() throws Exception {
        File unreadable = File.createTempFile("host-unreadable", ".apk");
        try {
            Files.write(unreadable.toPath(), new byte[]{1});
            unreadable.setReadable(false, false);
            if (!unreadable.canRead()) {
                assertNull(HostApkFingerprint.compute(
                        Collections.singletonList(unreadable.getAbsolutePath())));
            }
        } finally {
            unreadable.setReadable(true, false);
            assertTrue(unreadable.delete());
        }
    }

    private static HostApkFingerprint.Snapshot snapshot(File file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new HostApkFingerprint.Snapshot(
                attributes.isRegularFile(), attributes.size(),
                attributes.lastModifiedTime(), attributes.fileKey());
    }

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
