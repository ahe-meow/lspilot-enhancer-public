package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ForegroundKeepAliveContractCheck {
    @Test
    public void runsSourceContracts() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        assertHostOwnedForegroundServiceContract();
        assertControllerContract();
        assertScannerAndAbiContract();
        assertModuleWiringContract();
    }

    private static void assertHostOwnedForegroundServiceContract() {
        try {
            String manifest = readSource("app/src/main/AndroidManifest.xml");
            Assert.assertFalse("the enhancer package must not own a foreground helper service",
                    manifest.contains("ForegroundKeepAliveService"));
            Assert.assertFalse("the enhancer package must not request foreground-service privileges",
                    manifest.contains("android.permission.FOREGROUND_SERVICE"));
        } catch (Exception exception) {
            throw new AssertionError("host-owned foreground-service contract failed", exception);
        }
    }

    private static void assertControllerContract() {
        try {
            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/HostForegroundKeepAliveController.java");
            Assert.assertTrue(source.contains("AppGlobals"));
            Assert.assertTrue(source.contains("ActivityThread"));
            Assert.assertTrue(source.contains("android.app.Application"));
            Assert.assertTrue(source.contains("bindingContext"));
            Assert.assertTrue(source.contains("bindService"));
            Assert.assertTrue(source.contains("unbindService"));
            Assert.assertTrue(source.contains("BIND_AUTO_CREATE"));
            Assert.assertTrue(source.contains("BIND_IMPORTANT"));
            Assert.assertTrue(source.contains("com.rk.terminal.SessionService"));
            Assert.assertTrue(source.contains("ReferenceCountPolicy"));
            Assert.assertFalse("the host process must not start a service in the stopped enhancer package",
                    source.contains("startForegroundService"));
            Assert.assertFalse("the host process must not stop a service in another package",
                    source.contains("stopService"));
            Assert.assertFalse("release must not re-resolve the host Application",
                    source.contains("release(resolveHostContext())"));
            Assert.assertFalse("release must not fall back to its caller Context",
                    source.contains("unbindContext = hostContext"));
            int lock = source.indexOf("synchronized (LOCK)");
            int captured = source.indexOf("Context startedContext", lock);
            Assert.assertTrue("binding Context must be captured under LOCK",
                    lock >= 0 && captured >= 0);
            int publicLookup = source.indexOf("method = owner.getMethod(methodName);");
            int fallbackLookup = source.indexOf(
                    "method = owner.getDeclaredMethod(methodName);");
            String accessibilityGuard = "if (!Modifier.isPublic(method.getModifiers())\n"
                    + "                        || !Modifier.isPublic(owner.getModifiers()))";
            int fallbackGuard = source.indexOf(accessibilityGuard, fallbackLookup);
            int previousCount = source.indexOf("int previousCount = REFERENCES.count();");
            int activeReferenceBranch = source.indexOf(
                    "if (previousCount != 0 && !CONNECTIONS.needsBinding())",
                    previousCount);
            int contextResolution = source.indexOf(
                    "Context startedContext = resolveServiceContext(hostContext);");
            Assert.assertTrue("fallback reflection must guard setAccessible with Java-8-safe modifiers",
                    source.contains("import java.lang.reflect.Modifier;")
                            && publicLookup >= 0 && fallbackLookup > publicLookup
                            && fallbackGuard > fallbackLookup);
            Assert.assertTrue("active references must not re-resolve binding context",
                    previousCount >= 0 && activeReferenceBranch > previousCount
                            && contextResolution > activeReferenceBranch);
        } catch (Exception exception) {
            throw new AssertionError("controller contract failed", exception);
        }
    }

    private static void assertScannerAndAbiContract() {
        try {
            String scanner = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            Assert.assertTrue(scanner.contains("TYPE_KOTLIN_FUNCTION1"));
            Assert.assertTrue(scanner.contains("resolveStreamLifecycle"));
            Assert.assertTrue(scanner.contains(".paramCount(3)"));
            Assert.assertTrue(scanner.contains("getDeclaredMethods()"));
            Assert.assertTrue(scanner.contains("selectUniqueStreamLifecycle"));
            Assert.assertTrue(scanner.contains("new HostAbi.StreamLifecycleCapability"));
            Assert.assertTrue(scanner.contains("streamLifecycleCount"));
            Assert.assertTrue(scanner.contains("getUsingFields()"));
            Assert.assertTrue(scanner.contains("getUsingStrings()"));
            Assert.assertTrue(scanner.contains("getInvokes()"));
            Assert.assertTrue(scanner.contains("getDeclaredClassName()"));
            Assert.assertTrue(scanner.contains("Current v13 owners"));
            Assert.assertTrue(scanner.contains("unrelated no-arg methods"));
            Assert.assertTrue(scanner.contains("selectUniqueStructuralStreamLifecycle"));
            Assert.assertTrue(scanner.contains("sameMethodIdentity"));
            int streamScan = scanner.indexOf("resolveStreamLifecycle(bridge, loader)");
            int networkScan = scanner.indexOf("resolveNetwork(bridge, loader)");
            int reasoningScan = scanner.indexOf("resolveReasoningEnum(bridge, loader)");
            Assert.assertTrue("lifecycle scan must stay independent",
                    streamScan >= 0 && networkScan > streamScan
                            && reasoningScan > networkScan);
            Assert.assertTrue(scanner.contains("AtomicBoolean"));
            Assert.assertTrue(scanner.contains("CancellationException"));
            Assert.assertTrue(scanner.contains("ConcurrentHashMap"));
            Assert.assertTrue(scanner.contains("__ask_user_cancelled__"));
            Assert.assertFalse(scanner.contains("\"ub\""));
            Assert.assertFalse(scanner.contains("\"G\""));
            Assert.assertFalse(scanner.contains("\"t0\""));
            int lifecycle = scanner.indexOf("resolveStreamLifecycle(bridge, loader)");
            int reasoning = scanner.indexOf("resolveReasoningEnum(bridge, loader)");
            Assert.assertTrue("lifecycle scan must not depend on reasoning scan",
                    lifecycle >= 0 && reasoning >= 0 && lifecycle < reasoning);

            String abi = readSource("app/src/main/java/com/lspilot/enhancer/HostAbi.java");
            Assert.assertTrue(abi.contains("StreamLifecycleCapability"));
            Assert.assertTrue(abi.contains("public final Method starter"));
            Assert.assertTrue(abi.contains("cleanupMethods"));

            String hook = readSource(
                    "app/src/main/java/com/lspilot/enhancer/StreamLifecycleHook.java");
            Assert.assertTrue(hook.contains("stream-lifecycle-starter"));
            Assert.assertTrue(hook.contains("stream-lifecycle-cleanup-"));
            Assert.assertTrue(hook.contains("removeHook"));
        } catch (Exception exception) {
            throw new AssertionError("scanner/ABI contract failed", exception);
        }
    }

    private static void assertModuleWiringContract() {
        try {
            String module = readSource(
                    "app/src/main/java/com/lspilot/enhancer/LSPilotEnhancerModule.java");
            Assert.assertTrue(module.contains("abi.streamLifecycle != null"));
            Assert.assertTrue(module.contains("boolean streamCandidate"));
            Assert.assertTrue(module.contains("StreamLifecycleHook.install"));
            Assert.assertTrue(module.contains(
                    "DebugLogger.capability(\"streamLifecycle\", \"candidates\""));

            String logger = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DebugLogger.java");
            Assert.assertTrue(logger.contains("\"streamLifecycle\""));
        } catch (Exception exception) {
            throw new AssertionError("module wiring contract failed", exception);
        }
    }


    private static String readSource(String path) throws IOException {
        File current = new File(System.getProperty("user.dir", "."))
                .getAbsoluteFile();
        while (current != null) {
            File candidate = new File(current, path);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()),
                        StandardCharsets.UTF_8);
            }
            current = current.getParentFile();
        }
        throw new IOException("source file missing: " + path);
    }
}
