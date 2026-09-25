package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

public final class StreamLifecycleCheck {
    @Test
    public void runsFocusedContracts() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        assertReferenceCountTransitions();
        assertReferenceCountRejectsOverflow();
        assertConnectionRecoveryRequestsRebindWhileActive();
        assertLifecycleScannerSelectsUniqueOwnerPaths();
        assertLifecycleScannerFailsClosedOnAmbiguousOwner();
        assertLifecycleScannerFailsClosedOnAmbiguousStarter();
        assertLifecycleScannerDeduplicatesRepeatedStarterEvidence();
        assertLifecycleScannerFailsClosedWithIncompleteAmbiguousOwner();
        assertLifecycleCapabilityRejectsDuplicateCleanupMethods();
        assertLifecycleHookInstallsCleanupBeforeStarter();
        assertStarterPreservesNormalResultAndHoldsReference();
        assertStarterFailsOpenWhenAcquireReturnsFalse();
        assertStarterFailsOpenWhenAcquireThrows();
        assertOwnerCorrelatedLeasesReleaseOutOfOrder();
        assertDuplicateCleanupReleasesOwnerOnce();
        assertOwnerGenerationReplacesPriorLease();
        assertLedgerCloseDrainsAndDisablesAcquisition();
        assertStarterReleasesOnFailure();
        assertCleanupReleasesAfterNormalReturn();
        assertCleanupReleasesAfterFailure();
        assertCleanupReleaseFailurePreservesHostOutcome();
    }

    private static void assertReferenceCountTransitions() {
        HostForegroundKeepAliveController.ReferenceCountPolicy policy =
                new HostForegroundKeepAliveController.ReferenceCountPolicy();
        Assert.assertEquals(0, policy.count());
        Assert.assertTrue(policy.acquire());
        Assert.assertEquals(1, policy.count());
        Assert.assertTrue(policy.acquire());
        Assert.assertEquals(2, policy.count());
        Assert.assertFalse(policy.release());
        Assert.assertEquals(1, policy.count());
        Assert.assertTrue(policy.release());
        Assert.assertEquals(0, policy.count());
        Assert.assertFalse(policy.release());
        Assert.assertEquals(0, policy.count());
    }

    private static void assertReferenceCountRejectsOverflow() {
        try {
            HostForegroundKeepAliveController.ReferenceCountPolicy policy =
                    new HostForegroundKeepAliveController.ReferenceCountPolicy();
            Field references = HostForegroundKeepAliveController.ReferenceCountPolicy.class
                    .getDeclaredField("references");
            references.setAccessible(true);
            references.setInt(policy, Integer.MAX_VALUE);
            Assert.assertFalse("MAX_VALUE acquire must fail closed", policy.acquire());
            Assert.assertEquals(Integer.MAX_VALUE, policy.count());
        } catch (Exception exception) {
            throw new AssertionError("reference overflow contract failed", exception);
        }
    }

    private static void assertConnectionRecoveryRequestsRebindWhileActive() {
        HostForegroundKeepAliveController.ConnectionRecoveryPolicy policy =
                new HostForegroundKeepAliveController.ConnectionRecoveryPolicy();
        Assert.assertTrue("initial acquisition must create a binding",
                policy.needsBinding());
        policy.bindingStarted();
        Assert.assertFalse("an active binding must be reused",
                policy.needsBinding());
        Assert.assertTrue("binding death with active requests must request rebind",
                policy.bindingLost(2));
        Assert.assertTrue("a lost binding must be retried by the next acquisition",
                policy.needsBinding());
        policy.bindingStarted();
        Assert.assertFalse(policy.bindingLost(0));
        policy.bindingReleased();
        Assert.assertTrue(policy.needsBinding());
    }

    private static void assertLifecycleScannerSelectsUniqueOwnerPaths() {
        try {
            Method starter = LifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            DexKitAbiScanner.StreamLifecycleSelection selection =
                    DexKitAbiScanner.selectUniqueStreamLifecycle(
                            Arrays.asList(starter), kotlin.jvm.functions.Function1.class);
            Assert.assertNotNull("unique stream starter must be selected", selection);
            Assert.assertNotNull("complete owner must produce a capability",
                    selection.capability);
            Assert.assertEquals(1, selection.candidateCount);
            Assert.assertEquals(2, selection.capability.cleanupMethods.size());
            for (Method cleanup : selection.capability.cleanupMethods) {
                Assert.assertSame(LifecycleOwner.class, cleanup.getDeclaringClass());
                Assert.assertFalse(Modifier.isStatic(cleanup.getModifiers()));
                Assert.assertEquals(void.class, cleanup.getReturnType());
                Assert.assertEquals(0, cleanup.getParameterTypes().length);
            }
            Assert.assertSame(LifecycleOwner.class,
                    selection.capability.starter.getDeclaringClass());
        } catch (Exception exception) {
            throw new AssertionError("lifecycle scanner fixture failed", exception);
        }
    }

    private static void assertLifecycleScannerFailsClosedOnAmbiguousOwner() {
        try {
            Method starter = AmbiguousLifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            DexKitAbiScanner.StreamLifecycleSelection selection =
                    DexKitAbiScanner.selectUniqueStreamLifecycle(
                            Arrays.asList(starter), kotlin.jvm.functions.Function1.class);
            Assert.assertNotNull(selection);
            Assert.assertNull("ambiguous cleanup paths must disable only lifecycle",
                    selection.capability);
            Assert.assertEquals(3, selection.candidateCount);
        } catch (Exception exception) {
            throw new AssertionError("ambiguous lifecycle fixture failed", exception);
        }
    }

    private static void assertLifecycleScannerDeduplicatesRepeatedStarterEvidence() {
        try {
            Method starter = LifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            DexKitAbiScanner.StreamLifecycleSelection selection =
                    DexKitAbiScanner.selectUniqueStreamLifecycle(
                            Arrays.asList(starter, starter),
                            kotlin.jvm.functions.Function1.class);
            Assert.assertNotNull(selection);
            Assert.assertNotNull("duplicate metadata must represent one starter",
                    selection.capability);
            Assert.assertEquals(1, selection.candidateCount);
        } catch (Exception exception) {
            throw new AssertionError("duplicate starter fixture failed", exception);
        }
    }

    private static void assertLifecycleScannerFailsClosedOnAmbiguousStarter() {
        try {
            Method objectStarter = AmbiguousStarterLifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            Method stringStarter = AmbiguousStarterLifecycleOwner.class.getDeclaredMethod(
                    "start", String.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            DexKitAbiScanner.StreamLifecycleSelection selection =
                    DexKitAbiScanner.selectUniqueStreamLifecycle(
                            Arrays.asList(objectStarter, stringStarter),
                            kotlin.jvm.functions.Function1.class);
            Assert.assertNotNull(selection);
            Assert.assertNull("ambiguous structural starters must disable lifecycle",
                    selection.capability);
            Assert.assertEquals(2, selection.candidateCount);
        } catch (Exception exception) {
            throw new AssertionError("ambiguous starter fixture failed", exception);
        }
    }

    private static void assertLifecycleScannerFailsClosedWithIncompleteAmbiguousOwner() {
        try {
            Method validStarter = LifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            Method incompleteStarter = IncompleteLifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            DexKitAbiScanner.StreamLifecycleSelection selection =
                    DexKitAbiScanner.selectUniqueStreamLifecycle(
                            Arrays.asList(validStarter, incompleteStarter),
                            kotlin.jvm.functions.Function1.class);
            Assert.assertNotNull(selection);
            Assert.assertNull("structural ambiguity must disable lifecycle",
                    selection.capability);
            Assert.assertEquals(2, selection.candidateCount);
        } catch (Exception exception) {
            throw new AssertionError("incomplete ambiguous owner fixture failed", exception);
        }
    }

    private static void assertLifecycleCapabilityRejectsDuplicateCleanupMethods() {
        try {
            Method starter = LifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            Method cleanup = LifecycleOwner.class.getDeclaredMethod("reset");
            new HostAbi.StreamLifecycleCapability(
                    starter,
                    Arrays.asList(cleanup, cleanup),
                    kotlin.jvm.functions.Function1.class);
            Assert.fail("duplicate cleanup methods must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected constructor rejection.
        } catch (Exception exception) {
            throw new AssertionError("duplicate cleanup contract fixture failed", exception);
        }
    }

    private static void assertLifecycleHookInstallsCleanupBeforeStarter() {
        try {
            Method starter = LifecycleOwner.class.getDeclaredMethod(
                    "start", Object.class, List.class,
                    kotlin.jvm.functions.Function1.class);
            Method reset = LifecycleOwner.class.getDeclaredMethod("reset");
            Method cancel = LifecycleOwner.class.getDeclaredMethod("cancel");
            HostAbi.StreamLifecycleCapability capability =
                    new HostAbi.StreamLifecycleCapability(
                            starter, Arrays.asList(reset, cancel),
                            kotlin.jvm.functions.Function1.class);
            Assert.assertEquals(
                    Arrays.asList(reset, cancel, starter),
                    StreamLifecyclePolicy.installationOrder(capability));
        } catch (Exception exception) {
            throw new AssertionError("lifecycle hook order contract failed", exception);
        }
    }

    private static void assertStarterPreservesNormalResultAndHoldsReference() {
        final int[] acquisitions = {0};
        final int[] releases = {0};
        try {
            Object result = StreamLifecyclePolicy.interceptStarter(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() {
                            return "stream-started";
                        }
                    },
                    new StreamLifecyclePolicy.Acquirer() {
                        @Override
                        public boolean acquire() {
                            acquisitions[0]++;
                            return true;
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            releases[0]++;
                        }
                    });
            Assert.assertEquals("stream-started", result);
            Assert.assertEquals(1, acquisitions[0]);
            Assert.assertEquals(0, releases[0]);
        } catch (Throwable throwable) {
            throw new AssertionError("normal starter lifecycle failed", throwable);
        }
    }

    private static void assertStarterFailsOpenWhenAcquireReturnsFalse() {
        try {
            Object result = StreamLifecyclePolicy.interceptStarter(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() {
                            return "host-result";
                        }
                    },
                    new StreamLifecyclePolicy.Acquirer() {
                        @Override
                        public boolean acquire() {
                            return false;
                        }
                    },
                    null);
            Assert.assertEquals("host-result", result);
        } catch (Throwable actual) {
            throw new AssertionError("false acquisition must fail open", actual);
        }
    }

    private static void assertStarterFailsOpenWhenAcquireThrows() {
        final Throwable acquireFailure = new IllegalStateException("keep-alive unavailable");
        final Throwable hostFailure = new UnsupportedOperationException("host failure");
        try {
            Object result = StreamLifecyclePolicy.interceptStarter(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() {
                            return "host-result";
                        }
                    },
                    new StreamLifecyclePolicy.Acquirer() {
                        @Override
                        public boolean acquire() {
                            throw (RuntimeException) acquireFailure;
                        }
                    },
                    null);
            Assert.assertEquals("host-result", result);

            try {
                StreamLifecyclePolicy.interceptStarter(
                        new StreamLifecyclePolicy.Chain() {
                            @Override
                            public Object proceed() throws Throwable {
                                throw hostFailure;
                            }
                        },
                        new StreamLifecyclePolicy.Acquirer() {
                            @Override
                            public boolean acquire() {
                                throw (RuntimeException) acquireFailure;
                            }
                        },
                        null);
                Assert.fail("host exception must be preserved");
            } catch (Throwable actual) {
                Assert.assertSame(hostFailure, actual);
            }
        } catch (Throwable actual) {
            throw new AssertionError("acquire failures must fail open", actual);
        }
    }

    private static void assertOwnerCorrelatedLeasesReleaseOutOfOrder() {
        final int[] releases = {0};
        final Object ownedOwner = new Object();
        final Object failedOwner = new Object();
        final StreamLifecyclePolicy.AcquisitionLedger ledger =
                new StreamLifecyclePolicy.AcquisitionLedger();
        try {
            Object ownedResult = StreamLifecyclePolicy.interceptStarter(
                    ownedOwner,
                    returningChain("owned-host-result"),
                    returningAcquirer(true),
                    countingRelease(releases),
                    ledger);
            Assert.assertEquals("owned-host-result", ownedResult);

            Object failedResult = StreamLifecyclePolicy.interceptStarter(
                    failedOwner,
                    returningChain("failed-host-result"),
                    returningAcquirer(false),
                    countingRelease(releases),
                    ledger);
            Assert.assertEquals("failed-host-result", failedResult);

            StreamLifecyclePolicy.interceptCleanup(
                    ownedOwner, returningChain(null), ledger,
                    countingRelease(releases));
            Assert.assertEquals(
                    "cleanup must release the matching owner, not the newest outcome",
                    1, releases[0]);

            StreamLifecyclePolicy.interceptCleanup(
                    failedOwner, returningChain(null), ledger,
                    countingRelease(releases));
            Assert.assertEquals(
                    "a failed acquisition must never release another owner's lease",
                    1, releases[0]);
        } catch (Throwable throwable) {
            throw new AssertionError("owner-correlated cleanup failed", throwable);
        }
    }

    private static void assertDuplicateCleanupReleasesOwnerOnce() {
        final int[] releases = {0};
        final Object owner = new Object();
        final StreamLifecyclePolicy.AcquisitionLedger ledger =
                new StreamLifecyclePolicy.AcquisitionLedger();
        try {
            StreamLifecyclePolicy.interceptStarter(
                    owner, returningChain(null), returningAcquirer(true),
                    countingRelease(releases), ledger);
            StreamLifecyclePolicy.interceptCleanup(
                    owner, returningChain(null), ledger,
                    countingRelease(releases));
            StreamLifecyclePolicy.interceptCleanup(
                    owner, returningChain(null), ledger,
                    countingRelease(releases));
            Assert.assertEquals("duplicate cleanup must release exactly once",
                    1, releases[0]);
        } catch (Throwable throwable) {
            throw new AssertionError("duplicate cleanup contract failed", throwable);
        }
    }

    private static void assertOwnerGenerationReplacesPriorLease() {
        final int[] releases = {0};
        final Object owner = new Object();
        final StreamLifecyclePolicy.AcquisitionLedger ledger =
                new StreamLifecyclePolicy.AcquisitionLedger();
        try {
            StreamLifecyclePolicy.interceptStarter(
                    owner, returningChain("first"), returningAcquirer(true),
                    countingRelease(releases), ledger);
            StreamLifecyclePolicy.interceptStarter(
                    owner, returningChain("second"), returningAcquirer(true),
                    countingRelease(releases), ledger);
            Assert.assertEquals(
                    "a new owner generation must release the displaced lease",
                    1, releases[0]);

            StreamLifecyclePolicy.interceptCleanup(
                    owner, returningChain(null), ledger,
                    countingRelease(releases));
            Assert.assertEquals("cleanup must release only the current generation",
                    2, releases[0]);
            StreamLifecyclePolicy.interceptCleanup(
                    owner, returningChain(null), ledger,
                    countingRelease(releases));
            Assert.assertEquals(2, releases[0]);
        } catch (Throwable throwable) {
            throw new AssertionError("owner generation contract failed", throwable);
        }
    }

    private static void assertLedgerCloseDrainsAndDisablesAcquisition() {
        final int[] acquisitions = {0};
        final int[] releases = {0};
        final Object firstOwner = new Object();
        final Object secondOwner = new Object();
        final Object lateOwner = new Object();
        final StreamLifecyclePolicy.AcquisitionLedger ledger =
                new StreamLifecyclePolicy.AcquisitionLedger();
        try {
            StreamLifecyclePolicy.Acquirer acquirer = new StreamLifecyclePolicy.Acquirer() {
                @Override
                public boolean acquire() {
                    acquisitions[0]++;
                    return true;
                }
            };
            StreamLifecyclePolicy.interceptStarter(
                    firstOwner, returningChain(null), acquirer,
                    countingRelease(releases), ledger);
            StreamLifecyclePolicy.interceptStarter(
                    secondOwner, returningChain(null), acquirer,
                    countingRelease(releases), ledger);

            ledger.close(countingRelease(releases));
            Assert.assertEquals("close must release every outstanding owned lease",
                    2, releases[0]);

            Object lateResult = StreamLifecyclePolicy.interceptStarter(
                    lateOwner, returningChain("late-host-result"), acquirer,
                    countingRelease(releases), ledger);
            Assert.assertEquals("late-host-result", lateResult);
            Assert.assertEquals("closed ledger must disable new acquisition",
                    2, acquisitions[0]);

            StreamLifecyclePolicy.interceptCleanup(
                    firstOwner, returningChain(null), ledger,
                    countingRelease(releases));
            Assert.assertEquals("cleanup after close must not double release",
                    2, releases[0]);
            ledger.close(countingRelease(releases));
            Assert.assertEquals("close must be idempotent", 2, releases[0]);
        } catch (Throwable throwable) {
            throw new AssertionError("ledger close contract failed", throwable);
        }
    }

    private static StreamLifecyclePolicy.Chain returningChain(final Object result) {
        return new StreamLifecyclePolicy.Chain() {
            @Override
            public Object proceed() {
                return result;
            }
        };
    }

    private static StreamLifecyclePolicy.Acquirer returningAcquirer(
            final boolean result) {
        return new StreamLifecyclePolicy.Acquirer() {
            @Override
            public boolean acquire() {
                return result;
            }
        };
    }

    private static Runnable countingRelease(final int[] releases) {
        return new Runnable() {
            @Override
            public void run() {
                releases[0]++;
            }
        };
    }

    private static void assertStarterReleasesOnFailure() {
        final int[] releases = {0};
        final Throwable expected = new IllegalStateException("starter failure");
        try {
            StreamLifecyclePolicy.interceptStarter(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() throws Throwable {
                            throw expected;
                        }
                    },
                    new StreamLifecyclePolicy.Acquirer() {
                        @Override
                        public boolean acquire() {
                            return true;
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            releases[0]++;
                        }
                    });
            Assert.fail("starter exception must be preserved");
        } catch (Throwable actual) {
            Assert.assertSame(expected, actual);
        }
        Assert.assertEquals(1, releases[0]);
    }

    private static void assertCleanupReleasesAfterNormalReturn() {
        final int[] releases = {0};
        try {
            Object result = StreamLifecyclePolicy.interceptCleanup(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() {
                            return Integer.valueOf(7);
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            releases[0]++;
                        }
                    });
            Assert.assertEquals(Integer.valueOf(7), result);
        } catch (Throwable throwable) {
            throw new AssertionError("normal cleanup lifecycle failed", throwable);
        }
        Assert.assertEquals(1, releases[0]);
    }

    private static void assertCleanupReleaseFailurePreservesHostOutcome() {
        try {
            Object result = StreamLifecyclePolicy.interceptCleanup(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() {
                            return "host-result";
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            throw new IllegalStateException("release failure");
                        }
                    });
            Assert.assertEquals("host-result", result);

            final Throwable hostFailure = new UnsupportedOperationException("host failure");
            try {
                StreamLifecyclePolicy.interceptCleanup(
                        new StreamLifecyclePolicy.Chain() {
                            @Override
                            public Object proceed() throws Throwable {
                                throw hostFailure;
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                throw new IllegalStateException("release failure");
                            }
                        });
                Assert.fail("host exception must be preserved");
            } catch (Throwable actual) {
                Assert.assertSame(hostFailure, actual);
            }
        } catch (Throwable actual) {
            throw new AssertionError("cleanup release failure changed host behavior", actual);
        }
    }

    private static void assertCleanupReleasesAfterFailure() {
        final int[] releases = {0};
        final Throwable expected = new UnsupportedOperationException("cleanup failure");
        try {
            StreamLifecyclePolicy.interceptCleanup(
                    new StreamLifecyclePolicy.Chain() {
                        @Override
                        public Object proceed() throws Throwable {
                            throw expected;
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            releases[0]++;
                        }
                    });
            Assert.fail("cleanup exception must be preserved");
        } catch (Throwable actual) {
            Assert.assertSame(expected, actual);
        }
        Assert.assertEquals(1, releases[0]);
    }

    private static class LifecycleOwner {
        void start(Object request, List<?> messages,
                   kotlin.jvm.functions.Function1 callback) {
        }

        void reset() {
        }

        void cancel() {
        }
    }

    private static final class IncompleteLifecycleOwner {
        void start(Object request, List<?> messages,
                   kotlin.jvm.functions.Function1 callback) {
        }

        void reset() {
        }
    }

    private static final class AmbiguousStarterLifecycleOwner {
        void start(Object request, List<?> messages,
                   kotlin.jvm.functions.Function1 callback) {
        }

        void start(String request, List<?> messages,
                   kotlin.jvm.functions.Function1 callback) {
        }

        void reset() {
        }

        void cancel() {
        }
    }

    private static final class AmbiguousLifecycleOwner {
        void start(Object request, List<?> messages,
                   kotlin.jvm.functions.Function1 callback) {
        }

        void reset() {
        }

        void cancel() {
        }

        void clear() {
        }
    }
}
