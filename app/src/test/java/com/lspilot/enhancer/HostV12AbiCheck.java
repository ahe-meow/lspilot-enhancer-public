package com.lspilot.enhancer;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/**
 * Read-only exact-host ABI check. This entry point is intended for an Android
 * host-process route; a desktop JVM fails clearly before resolver execution.
 */
public final class HostV12AbiCheck {
    private static final String EXPECTED_PACKAGE = "me.yun.lspilot";
    private static final String EXPECTED_VERSION = "1.1.1";
    private static final int EXPECTED_VERSION_CODE = 12;

    private HostV12AbiCheck() {
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0 || args[0] == null
                || args[0].trim().length() == 0) {
            throw new IllegalArgumentException("staged APK path required");
        }
        String apkPath = args[0];
        Metadata metadata = readMetadata(apkPath);
        if (!EXPECTED_PACKAGE.equals(metadata.currentPackageName)
                || !EXPECTED_PACKAGE.equals(metadata.packageName)
                || !EXPECTED_VERSION.equals(metadata.versionName)
                || metadata.versionCode != EXPECTED_VERSION_CODE) {
            throw new IllegalStateException("APK metadata mismatch");
        }

        ClassLoader loader = metadata.applicationClassLoader;
        if (loader == null) {
            throw new IllegalStateException("host class loader unavailable");
        }
        DexKitAbiScanner.ScanResult result =
                DexKitAbiScanner.resolveDetailed(loader, apkPath);
        printCount("reasoningSource", result.reasoningSourceCount);
        printCount("menuLabels", result.menuLabelsCount);
        printCount("genericRequest", result.genericRequestCount);
        printCount("thinkingRequest", result.thinkingRequestCount);

        if (result.reasoningSourceCount != 1
                || result.menuLabelsCount != 1
                || result.genericRequestCount != 1
                || result.thinkingRequestCount != 1) {
            throw new IllegalStateException("required ABI capability missing or ambiguous");
        }
    }

    static Metadata readMetadata(String apkPath) {
        File apk = new File(apkPath);
        if (!apk.isFile() || !apk.canRead()) {
            throw new IllegalArgumentException("staged APK is not readable");
        }
        try {
            Class<?> appGlobalsClass = Class.forName("android.app.AppGlobals");
            Method getInitialApplication = appGlobalsClass.getMethod("getInitialApplication");
            Object application = getInitialApplication.invoke(null);
            if (application == null) {
                throw new IllegalStateException("Android PackageManager unavailable");
            }
            ClassLoader applicationClassLoader = application.getClass().getClassLoader();
            if (applicationClassLoader == null) {
                throw new IllegalStateException("host class loader unavailable");
            }
            Method getApplicationInfo = application.getClass().getMethod("getApplicationInfo");
            Object applicationInfo = getApplicationInfo.invoke(application);
            if (applicationInfo == null) {
                throw new IllegalStateException("Android application metadata unavailable");
            }
            Field currentPackageName = applicationInfo.getClass().getField("packageName");
            Object currentPackageValue = currentPackageName.get(applicationInfo);
            if (!(currentPackageValue instanceof String)) {
                throw new IllegalStateException("Android application package unavailable");
            }
            Field sourceDirField = applicationInfo.getClass().getField("sourceDir");
            Object sourceDirValue = sourceDirField.get(applicationInfo);
            if (!(sourceDirValue instanceof String)) {
                throw new IllegalStateException("Android application source unavailable");
            }
            File sourceApk = new File((String) sourceDirValue);
            if (!sameBytes(apk, sourceApk)) {
                throw new IllegalStateException(
                        "staged APK bytes do not match current application source");
            }
            Method getPackageManager = application.getClass().getMethod("getPackageManager");
            Object packageManager = getPackageManager.invoke(application);
            if (packageManager == null) {
                throw new IllegalStateException("Android PackageManager unavailable");
            }
            Method getPackageArchiveInfo = packageManager.getClass().getMethod(
                    "getPackageArchiveInfo", String.class, int.class);
            Object packageInfo = getPackageArchiveInfo.invoke(packageManager, apkPath, 0);
            if (packageInfo == null) {
                throw new IllegalStateException("Android PackageManager returned no metadata");
            }
            Field packageName = packageInfo.getClass().getField("packageName");
            Field versionName = packageInfo.getClass().getField("versionName");
            Field versionCode = packageInfo.getClass().getField("versionCode");
            Object packageNameValue = packageName.get(packageInfo);
            Object versionNameValue = versionName.get(packageInfo);
            Object versionCodeValue = versionCode.get(packageInfo);
            if (!(packageNameValue instanceof String)
                    || !(versionNameValue instanceof String)
                    || !(versionCodeValue instanceof Integer)) {
                throw new IllegalStateException("Android PackageManager metadata incomplete");
            }
            return new Metadata(
                    (String) currentPackageValue,
                    (String) packageNameValue,
                    (String) versionNameValue,
                    ((Integer) versionCodeValue).intValue(),
                    applicationClassLoader);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException(
                    "Android PackageManager unavailable; exact host check blocked");
        }
    }

    private static boolean sameBytes(File staged, File source) {
        if (staged == null || source == null || !staged.isFile() || !staged.canRead()
                || !source.isFile() || !source.canRead() || staged.length() != source.length()) {
            return false;
        }
        try {
            return MessageDigest.isEqual(sha256(staged), sha256(source));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static void printCount(String capability, int count) {
        System.out.println(capability + "=" + Math.max(0, count));
    }

    static final class Metadata {
        final String currentPackageName;
        final String packageName;
        final String versionName;
        final int versionCode;
        final ClassLoader applicationClassLoader;

        Metadata(
                String currentPackageName,
                String packageName,
                String versionName,
                int versionCode,
                ClassLoader applicationClassLoader) {
            this.currentPackageName = currentPackageName;
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.applicationClassLoader = applicationClassLoader;
        }
    }
}
