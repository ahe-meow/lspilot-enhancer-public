package com.lspilot.enhancer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

public final class ContextFeatureRemovalCheck {
    public ContextFeatureRemovalCheck() {
    }

    @Test
    public void removesObsoleteFeatureAndSettingsSurface() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        String[] removedFiles = {
                "app/src/main/java/com/lspilot/enhancer/ContextLimitParser.java",
                "app/src/main/java/com/lspilot/enhancer/ContextDisplayHook.java",
                "app/src/main/java/com/lspilot/enhancer/ContextLimitInput.java",
                "app/src/main/java/com/lspilot/enhancer/ModuleSettingsActivity.java",
                "app/src/main/java/com/lspilot/enhancer/PolicyApplication.java",
                "app/src/main/java/com/lspilot/enhancer/RemotePolicyStore.java",
                "app/src/main/java/com/lspilot/enhancer/ReasoningSync.java"
        };
        for (String removedFile : removedFiles) {
            Assert.assertFalse("obsolete source remains: " + removedFile,
                    sourceFile(removedFile).isFile());
        }

        String abi = readSource("app/src/main/java/com/lspilot/enhancer/HostAbi.java");
        requireAbsent(abi, "reasoningMirror");
        requireAbsent(abi, "writer");
        requireAbsent(abi, "DisplayCapability");
        requireAbsent(abi, "TextCapability");
        requireAbsent(abi, "LongPressAdapterCapability");

        String scanner = readSource(
                "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
        requireAbsent(scanner, "contextDisplay");
        requireAbsent(scanner, "contextText");
        requireAbsent(scanner, "longPressAdapter");
        requireAbsent(scanner, "CONTEXT_");
        requireAbsent(scanner, "EDITOR_PUT_STRING_DESCRIPTOR");

        String module = readSource(
                "app/src/main/java/com/lspilot/enhancer/LSPilotEnhancerModule.java");
        requireAbsent(module, "getRemotePreferences");
        requireAbsent(module, "ReasoningSync");
        requireAbsent(module, "ContextDisplayHook");
        requireAbsent(module, "ContextLimitInput");
        requireAbsent(module, "contextDisplay");
        requireAbsent(module, "contextText");
        requireAbsent(module, "longPressAdapter");

        String request = readSource(
                "app/src/main/java/com/lspilot/enhancer/RequestPolicyHook.java");
        requireAbsent(request, "SharedPreferences");
        requireAbsent(request, "RemotePolicyStore");
        requireAbsent(request, "context_limit_tokens");

        String manifest = readSource("app/src/main/AndroidManifest.xml");
        requireAbsent(manifest, "PolicyApplication");
        requireAbsent(manifest, "ModuleSettingsActivity");

        String build = readSource("app/build.gradle.kts");
        requireAbsent(build, "io.github.libxposed:service");
    }

    private static void requireAbsent(String source, String text) {
        Assert.assertFalse("obsolete code remains: " + text, source.contains(text));
    }

    private static File sourceFile(String repositoryPath) {
        File current = new File(System.getProperty("user.dir", "."))
                .getAbsoluteFile();
        while (current != null) {
            File candidate = new File(current, repositoryPath);
            if (candidate.exists()) {
                return candidate;
            }
            current = current.getParentFile();
        }
        return new File(repositoryPath);
    }

    private static String readSource(String repositoryPath) {
        File file = sourceFile(repositoryPath);
        if (!file.isFile()) {
            throw new AssertionError("source file missing: " + repositoryPath);
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("cannot read source: " + repositoryPath, exception);
        }
    }
}
