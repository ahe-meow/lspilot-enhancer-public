package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class NetworkStabilityHookCheck {
    private static final String VERIFIED_V14_FINGERPRINT =
            "6e3e12bd40f1c1156a1a967a57780fa62380d0cd78c41c16a06434b8b043a238";

    @Test
    public void runsFocusedNetworkContracts() throws Throwable {
        assertExactV14FingerprintGate();
        assertV14ReadSetterSelectionIgnoresInvokeOrderAndRejectsAmbiguity();
        assertCapabilityRequiresInitializerOwner();
        assertReadTimeoutChangesOnlyInsideInitializerScope();
        assertProtectionOutcomeMatchesInstalledHooks();
    }

    private static void assertExactV14FingerprintGate() {
        Assert.assertTrue(DexKitAbiScanner.isVerifiedV14NetworkFingerprint(
                VERIFIED_V14_FINGERPRINT));
        Assert.assertFalse(DexKitAbiScanner.isVerifiedV14NetworkFingerprint(null));
        Assert.assertFalse(DexKitAbiScanner.isVerifiedV14NetworkFingerprint(
                "1695dfabeda2b89529bfe61499cb96e4bbf62f7702f2a8fae90bafd68a7882d9"));
        Assert.assertFalse(DexKitAbiScanner.isVerifiedV14NetworkFingerprint(
                VERIFIED_V14_FINGERPRINT.substring(0, 63) + "9"));
    }

    private static void assertV14ReadSetterSelectionIgnoresInvokeOrderAndRejectsAmbiguity() {
        List<DexKitAbiScanner.NetworkSetterEvidence> reordered = Arrays.asList(
                new DexKitAbiScanner.NetworkSetterEvidence("P", "p-sign", "field-A"),
                new DexKitAbiScanner.NetworkSetterEvidence("M", "m-sign", "field-Z"),
                new DexKitAbiScanner.NetworkSetterEvidence("c", "c-sign", "field-Y"));
        Assert.assertEquals("m-sign",
                DexKitAbiScanner.selectV14ReadTimeoutSetterSign(reordered));

        List<DexKitAbiScanner.NetworkSetterEvidence> ambiguous = Arrays.asList(
                new DexKitAbiScanner.NetworkSetterEvidence("c", "c-sign", "field-Y"),
                new DexKitAbiScanner.NetworkSetterEvidence("M", "m-sign-1", "field-Z"),
                new DexKitAbiScanner.NetworkSetterEvidence("M", "m-sign-2", "field-A"));
        Assert.assertNull(DexKitAbiScanner.selectV14ReadTimeoutSetterSign(ambiguous));

        List<DexKitAbiScanner.NetworkSetterEvidence> sharedField = Arrays.asList(
                new DexKitAbiScanner.NetworkSetterEvidence("c", "c-sign", "field-Y"),
                new DexKitAbiScanner.NetworkSetterEvidence("M", "m-sign", "field-Y"),
                new DexKitAbiScanner.NetworkSetterEvidence("P", "p-sign", "field-A"));
        Assert.assertNull(DexKitAbiScanner.selectV14ReadTimeoutSetterSign(sharedField));
    }

    private static void assertCapabilityRequiresInitializerOwner() throws Exception {
        Method setter = TimeoutBuilder.class.getDeclaredMethod(
                "readTimeout", long.class, TimeUnit.class);
        HostAbi.NetworkCapability capability = new HostAbi.NetworkCapability(
                NetworkInitializer.class,
                setter,
                120L,
                TimeUnit.SECONDS,
                NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS);
        Assert.assertSame(NetworkInitializer.class, capability.initializerClass);
        Assert.assertSame(setter, capability.readTimeoutSetter);

        try {
            new HostAbi.NetworkCapability(
                    null,
                    setter,
                    120L,
                    TimeUnit.SECONDS,
                    NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS);
            Assert.fail("network capability must require its exact initializer owner");
        } catch (IllegalArgumentException expected) {
            // Exact initializer ownership is required.
        }
    }

    private static void assertReadTimeoutChangesOnlyInsideInitializerScope()
            throws Throwable {
        NetworkStabilityPolicy.InitializationScope scope =
                new NetworkStabilityPolicy.InitializationScope();

        FakeChain outside = new FakeChain(120L, TimeUnit.SECONDS);
        Object outsideResult = NetworkStabilityPolicy.intercept(
                outside,
                scope,
                NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS,
                120L,
                TimeUnit.SECONDS);
        Assert.assertSame(outside.result, outsideResult);
        Assert.assertEquals(120L,
                ((Long) outside.forwardedArgs[0]).longValue());

        scope.enter();
        try {
            FakeChain inside = new FakeChain(120L, TimeUnit.SECONDS);
            Object insideResult = NetworkStabilityPolicy.intercept(
                    inside,
                    scope,
                    NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS,
                    120L,
                    TimeUnit.SECONDS);
            Assert.assertSame(inside.result, insideResult);
            Assert.assertEquals(600L,
                    ((Long) inside.forwardedArgs[0]).longValue());
            Assert.assertSame(TimeUnit.SECONDS, inside.forwardedArgs[1]);

            FakeChain longer = new FakeChain(900L, TimeUnit.SECONDS);
            NetworkStabilityPolicy.intercept(
                    longer,
                    scope,
                    NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS,
                    120L,
                    TimeUnit.SECONDS);
            Assert.assertEquals(900L,
                    ((Long) longer.forwardedArgs[0]).longValue());
        } finally {
            scope.exit();
        }

        FakeChain after = new FakeChain(120L, TimeUnit.SECONDS);
        NetworkStabilityPolicy.intercept(
                after,
                scope,
                NetworkStabilityHook.DEFAULT_MINIMUM_TIMEOUT_SECONDS,
                120L,
                TimeUnit.SECONDS);
        Assert.assertEquals(120L,
                ((Long) after.forwardedArgs[0]).longValue());
    }

    private static void assertProtectionOutcomeMatchesInstalledHooks() {
        Assert.assertFalse(NetworkStabilityInstallPolicy.hasFullProtection(false, true));
        Assert.assertEquals("scope_unavailable",
                NetworkStabilityInstallPolicy.protectionEvent(false, true));
        Assert.assertTrue(NetworkStabilityInstallPolicy.hasFullProtection(true, true));
        Assert.assertEquals("installed",
                NetworkStabilityInstallPolicy.protectionEvent(true, true));
        Assert.assertFalse(NetworkStabilityInstallPolicy.hasFullProtection(true, false));
        Assert.assertEquals("disabled",
                NetworkStabilityInstallPolicy.protectionEvent(true, false));
    }

    private static final class NetworkInitializer {
    }

    private static final class TimeoutBuilder {
        TimeoutBuilder readTimeout(long timeout, TimeUnit unit) {
            return this;
        }
    }


    private static final class FakeChain implements NetworkStabilityPolicy.Chain {
        private final Object result = new Object();
        private final List<Object> args;
        private Object[] forwardedArgs;
        private int proceedCalls;

        private FakeChain(long timeout, TimeUnit unit) {
            args = Arrays.<Object>asList(Long.valueOf(timeout), unit);
        }

        @Override
        public List<Object> getArgs() {
            return args;
        }

        @Override
        public Object proceed() {
            proceedCalls++;
            forwardedArgs = args.toArray(new Object[2]);
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            proceedCalls++;
            forwardedArgs = args;
            return result;
        }
    }
}
