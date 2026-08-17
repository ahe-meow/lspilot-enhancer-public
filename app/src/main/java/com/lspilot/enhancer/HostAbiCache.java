package com.lspilot.enhancer;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Version-fingerprinted persistent cache for resolved host reflection descriptors. */
final class HostAbiCache {
    private static final String TAG = "LSPilotEnhancer";
    private static final int CACHE_SCHEMA = 6;
    private static final int MAX_CACHE_BYTES = 1024 * 1024;
    private static final String CACHE_DIRECTORY = "lspilot-enhancer";
    private static final String CACHE_FILE = "host-abi-v1.json";

    private static volatile String lastResolutionReason = "unknown";

    private HostAbiCache() {}

    static HostAbi resolve(ClassLoader loader, String[] dexPaths, ApplicationInfo appInfo)
            throws Exception {
        return resolve(loader, dexPaths,
                appInfo == null ? null : appInfo.packageName,
                appInfo == null ? null : appInfo.dataDir,
                appInfo == null ? 0 : appInfo.targetSdkVersion);
    }

    static HostAbi resolve(ClassLoader loader, String[] dexPaths, String hostPackage,
            String hostDataDir, int hostTargetSdk) throws Exception {
        File cacheFile = cacheFile(hostDataDir);
        if (cacheFile == null) {
            lastResolutionReason = "cache_unavailable";
            log("ABI descriptor cache unavailable; host dataDir is missing");
            return HostAbi.resolveFresh(loader, dexPaths);
        }

        String hostFingerprint = hostFingerprint(
                hostPackage, hostTargetSdk, dexPaths);
        String fingerprint = compositeFingerprint(hostFingerprint);
        JSONObject cached = null;
        String rebuildReason = "first_start";
        try {
            cached = readJson(cacheFile);
            rebuildReason = rebuildReason(cached, hostFingerprint, fingerprint);
            lastResolutionReason = rebuildReason == null ? "cache_hit" : rebuildReason;
            if (rebuildReason == null) {
                HostAbi abi = HostAbiDescriptor.decode(loader, cached.getJSONObject("abi"));
                log("ABI descriptor cache hit fingerprint=" + shortFingerprint(fingerprint));
                return abi;
            }
        } catch (Throwable error) {
            lastResolutionReason = "invalid_cache";
            rebuildReason = cached == null ? "invalid_cache" : "cached_descriptor_invalid";
            log("ABI descriptor cache rejected: " + shortError(error));
        }

        lastResolutionReason = rebuildReason;
        log("ABI descriptor cache rebuild reason=" + rebuildReason
                + " hostFingerprint=" + shortFingerprint(hostFingerprint)
                + " moduleVersionCode=" + BuildConfig.VERSION_CODE
                + " fingerprint=" + shortFingerprint(fingerprint));
        boolean hostUpdated = isHostUpdateReason(rebuildReason);
        boolean dexSelfAdapt = hostUpdated || "first_start".equals(rebuildReason);
        if (hostUpdated) {
            log("HOST_UPDATE_DETECTED contentFingerprint="
                    + shortFingerprint(hostFingerprint)
                    + "; starting DEX self-adaptation");
        }
        if (dexSelfAdapt) {
            log("DEX_SELF_ADAPTATION starting reason=" + rebuildReason);
        }
        HostAbi abi = HostAbi.resolveFresh(loader, dexPaths, dexSelfAdapt);
        try {
            JSONObject root = new JSONObject();
            root.put("schema", CACHE_SCHEMA);
            root.put("fingerprint", fingerprint);
            root.put("hostFingerprint", hostFingerprint);
            root.put("moduleVersionCode", BuildConfig.VERSION_CODE);
            root.put("moduleVersionName", BuildConfig.VERSION_NAME);
            root.put("abi", HostAbiDescriptor.encode(abi));
            writeJson(cacheFile, root);
            log("ABI descriptor cache stored fingerprint="
                    + shortFingerprint(fingerprint));
        } catch (Throwable error) {
            log("ABI descriptor cache write failed: " + shortError(error));
        }
        return abi;
    }

    static String rebuildReason(JSONObject cached, String hostFingerprint,
            String fingerprint) {
        if (cached == null) return "first_start";
        String cachedHost = cached.optString("hostFingerprint", "");
        boolean hostChanged = !hostFingerprint.equals(cachedHost);
        int cachedModule = cached.optInt("moduleVersionCode", Integer.MIN_VALUE);
        boolean moduleChanged = cachedModule != BuildConfig.VERSION_CODE;
        if (hostChanged && moduleChanged) return "host_and_module_update";
        if (hostChanged) return "host_update";
        if (cached.optInt("schema", -1) != CACHE_SCHEMA) return "cache_schema_changed";
        if (moduleChanged) return "module_update";
        if (!fingerprint.equals(cached.optString("fingerprint", ""))) {
            return "composite_fingerprint_changed";
        }
        if (cached.optJSONObject("abi") == null) return "descriptor_missing";
        return null;
    }

    static String lastResolutionReason() {
        return lastResolutionReason;
    }

    private static boolean isHostUpdateReason(String reason) {
        return "host_update".equals(reason) || "host_and_module_update".equals(reason);
    }

    static String hostFingerprint(String hostPackage, int hostTargetSdk,
            String[] dexPaths) throws Exception {
        List<String> hashes = new ArrayList<>();
        if (dexPaths != null) {
            for (String path : dexPaths) {
                if (path == null || path.trim().isEmpty()) continue;
                File file = new File(path);
                if (!file.isFile()) {
                    hashes.add("missing");
                    continue;
                }
                try {
                    // Only APK/split content identifies the host; ignore metadata, path, size, and mtime.
                    hashes.add(fileSha256(file));
                } catch (Throwable error) {
                    hashes.add("sha256Error:" + shortError(error));
                }
            }
        }
        Collections.sort(hashes);
        StringBuilder source = new StringBuilder();
        for (String hash : hashes) source.append("apkSha256=").append(hash).append('\n');
        return sha256(source.toString());
    }

    private static String compositeFingerprint(String hostFingerprint) throws Exception {
        return sha256("schema=" + CACHE_SCHEMA
                + "\nmoduleCode=" + BuildConfig.VERSION_CODE
                + "\nmoduleName=" + BuildConfig.VERSION_NAME
                + "\nhostFingerprint=" + hostFingerprint);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String fileSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] hash) {
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte item : hash) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static File cacheFile(String hostDataDir) {
        if (hostDataDir == null || hostDataDir.trim().isEmpty()) return null;
        return new File(new File(new File(hostDataDir, "files"), CACHE_DIRECTORY), CACHE_FILE);
    }

    private static JSONObject readJson(File file) throws Exception {
        if (!file.isFile()) return null;
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CACHE_BYTES) {
                    throw new IllegalStateException("ABI cache exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            input.close();
        }
    }

    private static void writeJson(File file, JSONObject value) throws Exception {
        File directory = file.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("Could not create ABI cache directory");
        }
        File temporary = new File(directory,
                CACHE_FILE + ".tmp-" + Thread.currentThread().getId() + '-'
                        + Long.toHexString(System.nanoTime()));
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = new FileOutputStream(temporary);
        try {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
        try {
            if (!temporary.renameTo(file)) {
                throw new IllegalStateException("Could not replace ABI cache file");
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint == null || fingerprint.length() <= 12
                ? String.valueOf(fingerprint) : fingerprint.substring(0, 12);
    }

    private static void log(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable unavailableAndroidRuntime) {
            System.out.println(TAG + ": " + message);
        }
    }

    private static String shortError(Throwable error) {
        if (error == null) return "none";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("host APK path required");
        String dataDir = args.length > 1
                ? args[1] : "/data/local/tmp/lspilot-abi-cache-test";
        String[] paths = {args[0]};
        HostAbi first = resolve(HostAbiCache.class.getClassLoader(), paths,
                "me.yun.lspilot", dataDir, 35);
        HostAbi second = resolve(HostAbiCache.class.getClassLoader(), paths,
                "me.yun.lspilot", dataDir, 35);
        if (!first.buildRequestMethod.toString().equals(second.buildRequestMethod.toString())) {
            throw new AssertionError("ABI cache round-trip changed the request method");
        }
        if (!cacheFile(dataDir).isFile()) {
            throw new AssertionError("ABI cache file was not persisted");
        }
        System.out.println("ABI cache round-trip resolved provider=" + second.providerClass.getName());
    }
}