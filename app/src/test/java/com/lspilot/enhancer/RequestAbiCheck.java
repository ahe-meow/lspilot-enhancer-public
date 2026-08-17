package com.lspilot.enhancer;

import java.util.Arrays;
import java.util.List;

import kotlin.jvm.functions.Function1;

public final class RequestAbiCheck {
    private RequestAbiCheck() {
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = RequestAbiCheck.class.getClassLoader();
        HostAbi resolved = DexAbiScanner.resolveCandidates(loader, Arrays.asList(
                ProviderOne.class.getName(), Config.class.getName()));
        assertTrue(resolved.hasRequestAbi(), "request/SSE ABI must resolve independently");

        HostAbi cached = HostAbiDescriptor.decode(loader, HostAbiDescriptor.encode(resolved));
        assertTrue(cached.hasRequestAbi(), "request descriptor must round-trip");

        List<String> ambiguous = Arrays.asList(
                ProviderOne.class.getName(), ProviderTwo.class.getName(), Config.class.getName());
        try {
            DexAbiScanner.resolveCandidates(loader, ambiguous);
            throw new AssertionError("ambiguous request/SSE candidates must be rejected");
        } catch (DexAbiScanner.AmbiguousRequestException expected) {
            assertTrue(expected.getMessage().contains("Ambiguous request/SSE ABI candidates=2"),
                    "unexpected ambiguity error: " + expected.getMessage());
        }
        System.out.println("RequestAbiCheck: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static final class Config {
    }

    public static final class ProviderOne {
        public String build(Config config, List<?> messages, String prompt, boolean stream) {
            return "{}";
        }

        public boolean scan(String payload, Function1<?, ?> callback) {
            return false;
        }
    }

    public static final class ProviderTwo {
        public String build(Config config, List<?> messages, String prompt, boolean stream) {
            return "{}";
        }

        public boolean scan(String payload, Function1<?, ?> callback) {
            return false;
        }
    }
}
