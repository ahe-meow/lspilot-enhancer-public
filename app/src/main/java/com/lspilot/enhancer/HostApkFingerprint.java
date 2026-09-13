package com.lspilot.enhancer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class HostApkFingerprint {
    private static final int BUFFER_SIZE = 8192;

    private HostApkFingerprint() {
    }

    static String compute(List<String> sourcePaths) {
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            return null;
        }

        try {
            List<Record> records = new ArrayList<Record>(sourcePaths.size());
            for (String sourcePath : sourcePaths) {
                if (sourcePath == null || sourcePath.trim().length() == 0) {
                    return null;
                }

                Path source = new File(sourcePath).toPath();
                if (!source.toFile().canRead()) {
                    return null;
                }
                Snapshot before = readSnapshot(source);
                if (!before.isUsable()) {
                    return null;
                }

                DigestResult first = readDigest(source);
                Snapshot between = readSnapshot(source);
                if (!source.toFile().canRead() || !between.isUsable()) {
                    return null;
                }

                DigestResult second = readDigest(source);
                Snapshot after = readSnapshot(source);
                if (!source.toFile().canRead()
                        || !after.isUsable()
                        || first.length != before.size
                        || second.length != after.size
                        || !isStableSnapshot(before, between, after,
                        first.digest, second.digest)) {
                    return null;
                }
                records.add(new Record(first.length, first.digest));
            }

            Collections.sort(records, new Comparator<Record>() {
                @Override
                public int compare(Record left, Record right) {
                    int lengthComparison = left.decimalLength.compareTo(right.decimalLength);
                    if (lengthComparison != 0) {
                        return lengthComparison;
                    }
                    return left.digest.compareTo(right.digest);
                }
            });

            MessageDigest combined = MessageDigest.getInstance("SHA-256");
            for (Record record : records) {
                combined.update((record.decimalLength + ":" + record.digest + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return toHex(combined.digest());
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isStableSnapshot(
            Snapshot before, Snapshot between, Snapshot after,
            String firstDigest, String secondDigest) {
        return before != null
                && between != null
                && after != null
                && before.isUsable()
                && between.isUsable()
                && after.isUsable()
                && before.matches(between)
                && between.matches(after)
                && firstDigest != null
                && firstDigest.equals(secondDigest);
    }

    private static Snapshot readSnapshot(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Snapshot(attributes.isRegularFile(), attributes.size(),
                attributes.lastModifiedTime(), attributes.fileKey());
    }

    private static DigestResult readDigest(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long length = 0;
        try (FileInputStream input = new FileInputStream(source.toFile())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                length += read;
            }
        }
        return new DigestResult(length, toHex(digest.digest()));
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            hex[index * 2] = digits[value >>> 4];
            hex[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(hex);
    }

    static final class Snapshot {
        final boolean regularFile;
        final long size;
        final FileTime lastModifiedTime;
        final Object fileKey;

        Snapshot(boolean regularFile, long size, FileTime lastModifiedTime, Object fileKey) {
            this.regularFile = regularFile;
            this.size = size;
            this.lastModifiedTime = lastModifiedTime;
            this.fileKey = fileKey;
        }

        boolean isUsable() {
            return regularFile && size >= 0 && lastModifiedTime != null && fileKey != null;
        }

        boolean matches(Snapshot other) {
            return other != null
                    && regularFile == other.regularFile
                    && size == other.size
                    && lastModifiedTime != null
                    && lastModifiedTime.equals(other.lastModifiedTime)
                    && fileKey != null
                    && fileKey.equals(other.fileKey);
        }
    }

    private static final class DigestResult {
        final long length;
        final String digest;

        DigestResult(long length, String digest) {
            this.length = length;
            this.digest = digest;
        }
    }

    private static final class Record {
        final String decimalLength;
        final String digest;

        Record(long length, String digest) {
            this.decimalLength = Long.toString(length);
            this.digest = digest;
        }
    }
}
