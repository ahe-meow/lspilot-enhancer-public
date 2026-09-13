package com.lspilot.enhancer;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
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

                File source = new File(sourcePath);
                if (!source.isFile() || !source.canRead()) {
                    return null;
                }

                long lengthBeforeRead = source.length();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long byteCount = 0;
                try (FileInputStream input = new FileInputStream(source)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        byteCount += read;
                    }
                }

                if (!source.isFile() || !source.canRead()
                        || lengthBeforeRead != byteCount || source.length() != byteCount) {
                    return null;
                }
                records.add(new Record(byteCount, toHex(digest.digest())));
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

    private static final class Record {
        final String decimalLength;
        final String digest;

        Record(long length, String digest) {
            this.decimalLength = Long.toString(length);
            this.digest = digest;
        }
    }
}
