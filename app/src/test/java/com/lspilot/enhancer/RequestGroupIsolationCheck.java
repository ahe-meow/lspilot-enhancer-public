package com.lspilot.enhancer;

import java.util.Arrays;
import java.util.List;

import kotlin.jvm.functions.Function1;

public final class RequestGroupIsolationCheck {
    private RequestGroupIsolationCheck() {
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = RequestGroupIsolationCheck.class.getClassLoader();
        HostAbi requestOnly = DexAbiScanner.resolveCandidates(loader, Arrays.asList(
                ProviderOne.class.getName(), Config.class.getName()));
        assertTrue(requestOnly.hasRequestAbi(), "request/SSE ABI must survive retry failure");
        assertTrue(!requestOnly.hasRetryAbi(), "incomplete retry ABI must stay disabled");

        HostAbi cached = HostAbiDescriptor.decode(loader, HostAbiDescriptor.encode(requestOnly));
        assertTrue(cached.hasRequestAbi(), "request-only descriptor must round-trip");
        assertTrue(!cached.hasRetryAbi(), "cached retry ABI must remain disabled");

        List<String> ambiguous = Arrays.asList(
                ProviderOne.class.getName(), ProviderTwo.class.getName(), Config.class.getName());
        try {
            DexAbiScanner.resolveCandidates(loader, ambiguous);
            throw new AssertionError("ambiguous request/SSE candidates must be rejected");
        } catch (DexAbiScanner.AmbiguousRequestException expected) {
            assertTrue(expected.getMessage().contains("Ambiguous request/SSE ABI candidates=2"),
                    "unexpected ambiguity error: " + expected.getMessage());
        }
        System.out.println("RequestGroupIsolationCheck: PASS");
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
