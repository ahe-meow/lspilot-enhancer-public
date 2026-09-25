package com.lspilot.enhancer;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class HookArgumentContractCheck {
    public HookArgumentContractCheck() {
    }

    @Test
    public void runsFocusedContracts() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        assertPublicInstallGuards();
        assertRequestInstallableGuards();
        assertHostValueRewritesGenericRequest();
        assertHostValueRewritesThinkingRequest();
        assertHostGetterFailurePreservesOriginal();
        assertRequestNonStringFallbackAndSingleProceed();
        assertRegistryRunIfOpenLifecycle();
        assertRegistryCleanupIsIdempotentAndIndependent();
        assertRegistryClosesLifecycleBeforeHooks();
    }

    private static void assertPublicInstallGuards() {
        try {
            String requestSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/RequestPolicyHook.java");
            Assert.assertFalse(requestSource.contains("SharedPreferences"));
            Assert.assertTrue(requestSource.contains("reasoningCapability"));
            Assert.assertTrue(requestSource.contains("registry.isClosed()"));
            Assert.assertTrue(requestSource.contains("ProviderKind.UNKNOWN"));
            Assert.assertFalse(requestSource.contains("context_limit_tokens"));
        } catch (Exception exception) {
            throw new AssertionError("public install guard fixture failed", exception);
        }
    }

    private static void assertRequestInstallableGuards() {
        try {
            Method guard = RequestPolicyHook.class.getDeclaredMethod(
                    "isInstallable", HostAbi.RequestCapability.class,
                    HostAbi.ReasoningCapability.class);
            Assert.assertFalse("isInstallable must be package-visible",
                    Modifier.isPrivate(guard.getModifiers())
                            || Modifier.isProtected(guard.getModifiers())
                            || Modifier.isPublic(guard.getModifiers()));

            HostAbi.ReasoningCapability reasoning = newReasoningCapability();
            Method unknownGeneric = GuardFixtures.class.getDeclaredMethod(
                    "requestWithUnknownTypes", GuardFixtures.ProviderQ.class, List.class,
                    String.class, boolean.class, String.class, GuardFixtures.ModeR.class);
            Method unknownThinking = GuardFixtures.class.getDeclaredMethod(
                    "thinkingWithUnknownTypes", GuardFixtures.ProviderQ.class, List.class,
                    String.class, GuardFixtures.ModeR.class);
            Method wrongFinal = GuardFixtures.class.getDeclaredMethod(
                    "requestWithWrongFinalEnum", GuardFixtures.ProviderQ.class, List.class,
                    String.class, boolean.class, String.class, GuardFixtures.a69.class);
            Method staticMethod = GuardFixtures.class.getDeclaredMethod(
                    "requestStaticWithUnknownTypes", GuardFixtures.ProviderQ.class, List.class,
                    String.class, boolean.class, String.class, GuardFixtures.ModeR.class);
            Method wrongReturn = GuardFixtures.class.getDeclaredMethod(
                    "requestWrongReturnWithUnknownTypes", GuardFixtures.ProviderQ.class,
                    List.class, String.class, boolean.class, String.class,
                    GuardFixtures.ModeR.class);
            Method wrongPositions = GuardFixtures.class.getDeclaredMethod(
                    "requestWithWrongParameterPositions", GuardFixtures.ProviderQ.class,
                    String.class, List.class, boolean.class, String.class,
                    GuardFixtures.ModeR.class);

            Assert.assertTrue("unknown request types must be accepted",
                    invokeRequestInstallable(new HostAbi.RequestCapability(
                            unknownGeneric, RequestJsonPolicy.ProviderKind.GENERIC), reasoning));
            Assert.assertTrue("unknown thinking types must be accepted",
                    invokeRequestInstallable(new HostAbi.RequestCapability(
                            unknownThinking, RequestJsonPolicy.ProviderKind.THINKING), reasoning));
            Assert.assertFalse("wrong final enum must be rejected",
                    invokeRequestInstallable(new HostAbi.RequestCapability(
                            wrongFinal, RequestJsonPolicy.ProviderKind.GENERIC), reasoning));
            Assert.assertFalse("static methods must be rejected",
                    invokeRequestInstallable(new HostAbi.RequestCapability(
                            staticMethod, RequestJsonPolicy.ProviderKind.GENERIC), reasoning));
            Assert.assertFalse("wrong return types must be rejected",
                    invokeRequestInstallable(new HostAbi.RequestCapability(
                            wrongReturn, RequestJsonPolicy.ProviderKind.GENERIC), reasoning));
            Assert.assertFalse("wrong parameter positions must be rejected",
                    invokeRequestInstallable(new HostAbi.RequestCapability(
                            wrongPositions, RequestJsonPolicy.ProviderKind.GENERIC), reasoning));
            Assert.assertFalse(invokeRequestInstallable(new HostAbi.RequestCapability(
                    unknownGeneric, RequestJsonPolicy.ProviderKind.UNKNOWN), reasoning));
            Assert.assertFalse(invokeRequestInstallable(null, reasoning));
            Assert.assertFalse(invokeRequestInstallable(
                    new HostAbi.RequestCapability(
                            unknownGeneric, RequestJsonPolicy.ProviderKind.GENERIC), null));
        } catch (Exception exception) {
            throw new AssertionError("request installer guard fixture failed", exception);
        }
    }

    private static boolean invokeRequestInstallable(
            HostAbi.RequestCapability capability,
            HostAbi.ReasoningCapability reasoningCapability)
            throws Exception {
        Method guard = RequestPolicyHook.class.getDeclaredMethod(
                "isInstallable", HostAbi.RequestCapability.class,
                HostAbi.ReasoningCapability.class);
        guard.setAccessible(true);
        return ((Boolean) guard.invoke(null, capability, reasoningCapability)).booleanValue();
    }

    private static void assertHostValueRewritesGenericRequest() {
        FakeHostRepository.value = "HIGH";
        FakeHostRepository.throwOnRead = false;
        FakeHostRepository.getterCalls = 0;
        String original = "{\"model\":\"demo\",\"messages\":[],"
                + "\"stream\":true,\"reasoning_effort\":\"low\","
                + "\"max_tokens\":99,\"unknown\":{\"keep\":true}}";
        FakeChain chain = new FakeChain(original);
        try {
            Object result = invokeHostAwareIntercept(
                    chain, newReasoningCapability(), RequestJsonPolicy.ProviderKind.GENERIC);
            JSONObject body = new JSONObject((String) result);
            Assert.assertEquals("xhigh", body.getString("reasoning_effort"));
            Assert.assertEquals(99, body.getInt("max_tokens"));
            Assert.assertTrue(body.getJSONObject("unknown").getBoolean("keep"));
            Assert.assertEquals(1, FakeHostRepository.getterCalls);
        } catch (Throwable exception) {
            throw new AssertionError("host generic policy fixture failed", exception);
        }
        Assert.assertEquals(1, chain.proceedCalls);
    }

    private static void assertHostValueRewritesThinkingRequest() {
        FakeHostRepository.value = "MAX";
        FakeHostRepository.throwOnRead = false;
        String original = "{\"model\":\"demo\",\"messages\":[],"
                + "\"max_tokens\":99,\"thinking\":{\"type\":\"disabled\","
                + "\"budget_tokens\":1}}";
        FakeChain chain = new FakeChain(original);
        try {
            Object result = invokeHostAwareIntercept(
                    chain, newReasoningCapability(), RequestJsonPolicy.ProviderKind.THINKING);
            JSONObject body = new JSONObject((String) result);
            Assert.assertEquals(99, body.getInt("max_tokens"));
            Assert.assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
            Assert.assertEquals(16384,
                    body.getJSONObject("thinking").getInt("budget_tokens"));
        } catch (Throwable exception) {
            throw new AssertionError("host thinking policy fixture failed", exception);
        }
        Assert.assertEquals(1, chain.proceedCalls);
    }

    private static void assertHostGetterFailurePreservesOriginal() {
        FakeHostRepository.throwOnRead = true;
        String original = "{\"model\":\"demo\",\"messages\":[],"
                + "\"reasoning_effort\":\"low\"}";
        FakeChain chain = new FakeChain(original);
        try {
            Object result = invokeHostAwareIntercept(
                    chain, newReasoningCapability(), RequestJsonPolicy.ProviderKind.GENERIC);
            Assert.assertEquals(original, result);
        } catch (Throwable exception) {
            throw new AssertionError("host getter failure fixture failed", exception);
        } finally {
            FakeHostRepository.throwOnRead = false;
        }
        Assert.assertEquals(1, chain.proceedCalls);
    }

    private static Object invokeHostAwareIntercept(
            FakeChain chain,
            HostAbi.ReasoningCapability capability,
            RequestJsonPolicy.ProviderKind provider) throws Throwable {
        Method target = null;
        for (Method method : RequestPolicyHook.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("intercept".equals(method.getName())
                    && parameters.length == 3
                    && parameters[0] == RequestPolicyHook.Chain.class
                    && parameters[1] == HostAbi.ReasoningCapability.class
                    && parameters[2] == RequestJsonPolicy.ProviderKind.class) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new AssertionError("host-aware intercept method is missing");
        }
        target.setAccessible(true);
        try {
            return target.invoke(null, chain, capability, provider);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static HostAbi.ReasoningCapability newReasoningCapability() throws Exception {
        Method getter = FakeHostRepository.class.getDeclaredMethod("getReasoning");
        Constructor<?> repository = FakeHostRepository.class.getDeclaredConstructor();
        return new HostAbi.ReasoningCapability(getter, repository, GuardFixtures.ModeR.class);
    }

    private static void assertRequestNonStringFallbackAndSingleProceed() {
        Object original = new Object();
        FakeChain chain = new FakeChain(original);
        try {
            Object result = invokeHostAwareIntercept(
                    chain, newReasoningCapability(), RequestJsonPolicy.ProviderKind.GENERIC);
            Assert.assertSame(original, result);
        } catch (Throwable exception) {
            throw new AssertionError("non-String result must not throw", exception);
        }
        Assert.assertEquals(1, chain.proceedCalls);
    }

    private static void assertRegistryRunIfOpenLifecycle() {
        final int[] calls = {0};
        HookRegistry registry = new HookRegistry();
        Assert.assertTrue(registry.runIfOpen(new Runnable() {
            @Override
            public void run() {
                calls[0]++;
            }
        }));
        registry.close();
        Assert.assertFalse(registry.runIfOpen(new Runnable() {
            @Override
            public void run() {
                Assert.fail("closed registry must not run lifecycle work");
            }
        }));
        Assert.assertEquals(1, calls[0]);
    }

    private static void assertRegistryCleanupIsIdempotentAndIndependent() {
        List<String> events = new ArrayList<String>();
        HookRegistry registry = new HookRegistry();
        CleanupHandle throwingHandle = new CleanupHandle(events, true);
        CleanupHandle normalHandle = new CleanupHandle(events, false);
        registry.addForTest(throwingHandle);
        registry.addForTest(normalHandle);

        registry.close();
        Assert.assertEquals(1, throwingHandle.unhookCalls);
        Assert.assertEquals(1, normalHandle.unhookCalls);

        registry.close();
        Assert.assertEquals(1, throwingHandle.unhookCalls);
        Assert.assertEquals(1, normalHandle.unhookCalls);
        Assert.assertFalse(registry.runIfOpen(new Runnable() {
            @Override
            public void run() {
                Assert.fail("closed registry must not run lifecycle work");
            }
        }));
    }

    private static void assertRegistryClosesLifecycleBeforeHooks() {
        final List<String> events = new ArrayList<String>();
        HookRegistry registry = new HookRegistry();
        final Object lifecycleIdentity = new Object();
        Assert.assertTrue(registry.addCloseResource(
                lifecycleIdentity,
                new Runnable() {
                    @Override
                    public void run() {
                        events.add("drain");
                    }
                }));
        registry.addForTest(new HookRegistry.TestHookHandle() {
            @Override
            public void unhook() {
                events.add("cleanup-hook");
            }
        });
        registry.addForTest(new HookRegistry.TestHookHandle() {
            @Override
            public void unhook() {
                events.add("starter-hook");
            }
        });

        registry.close();
        Assert.assertEquals(
                Arrays.asList("drain", "cleanup-hook", "starter-hook"),
                events);
        registry.close();
        Assert.assertEquals("registry close must remain idempotent",
                3, events.size());
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

    private static final class FakeHostRepository {
        private static String value = "AUTO";
        private static boolean throwOnRead;
        private static int getterCalls;

        public FakeHostRepository() {
        }

        public String getReasoning() {
            getterCalls++;
            if (throwOnRead) {
                throw new IllegalStateException("deliberate getter failure");
            }
            return value;
        }
    }

    private static final class GuardFixtures {
        private static final class ProviderQ {
        }

        private enum ModeR {
            OFF, AUTO, LOW, MEDIUM, HIGH, MAX
        }

        private static final class vb {
        }

        private static final class a69 {
        }

        private static final class wb {
        }

        private static final class oi9 {
        }

        String requestWithUnknownTypes(
                ProviderQ provider, List<?> messages, String model, boolean stream,
                String ignored, ModeR mode) {
            return "{}";
        }

        String thinkingWithUnknownTypes(
                ProviderQ provider, List<?> messages, String model, ModeR mode) {
            return "{}";
        }

        String requestWithWrongFinalEnum(
                ProviderQ provider, List<?> messages, String model, boolean stream,
                String ignored, a69 mode) {
            return "{}";
        }

        static String requestStaticWithUnknownTypes(
                ProviderQ provider, List<?> messages, String model, boolean stream,
                String ignored, ModeR mode) {
            return "{}";
        }

        int requestWrongReturnWithUnknownTypes(
                ProviderQ provider, List<?> messages, String model, boolean stream,
                String ignored, ModeR mode) {
            return model == null ? 0 : model.length();
        }

        String requestWithWrongParameterPositions(
                ProviderQ provider, String model, List<?> messages, boolean stream,
                String ignored, ModeR mode) {
            return "{}";
        }
        String requestCurrentGeneric(
                vb provider, List<?> messages, String model, boolean stream,
                String effort, a69 reasoning) {
            return model;
        }

        String requestStaleGeneric(
                wb provider, List<?> messages, String model, boolean stream,
                String effort, oi9 reasoning) {
            return model;
        }

        static String requestStatic(
                vb provider, List<?> messages, String model, boolean stream,
                String effort, a69 reasoning) {
            return model;
        }

        int requestWrongReturn(
                vb provider, List<?> messages, String model, boolean stream,
                String effort, a69 reasoning) {
            return model == null ? 0 : model.length();
        }
    }

    private static final class FakeChain implements RequestPolicyHook.Chain {
        private final Object result;
        private int proceedCalls;

        private FakeChain(Object result) {
            this.result = result;
        }

        @Override
        public Object proceed() {
            proceedCalls++;
            return result;
        }
    }

    private static final class CleanupHandle implements HookRegistry.TestHookHandle {
        private final List<String> events;
        private final boolean throwOnUnhook;
        private int unhookCalls;

        private CleanupHandle(List<String> events, boolean throwOnUnhook) {
            this.events = events;
            this.throwOnUnhook = throwOnUnhook;
        }

        @Override
        public void unhook() {
            unhookCalls++;
            events.add("unhook");
            if (throwOnUnhook) {
                throw new IllegalStateException("deliberate cleanup failure");
            }
        }
    }
}
