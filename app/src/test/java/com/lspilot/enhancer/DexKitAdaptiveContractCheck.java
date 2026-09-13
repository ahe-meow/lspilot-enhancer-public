package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class DexKitAdaptiveContractCheck {
    public DexKitAdaptiveContractCheck() {
    }

    @Test
    public void runsAssertions() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        assertStructuralEnumPredicate();
        assertResourceGetterPredicate();
        assertReasoningCapabilityCarriesEnumClass();
        assertScannerUsesNoFixedEnumLookups();
    }

    private static void assertStructuralEnumPredicate() {
        Assert.assertTrue("unknown enum names must be accepted",
                invokeEnumPredicate(Qx7.class));
        Assert.assertFalse("extra enum values must be rejected",
                invokeEnumPredicate(Qx8.class));
        Assert.assertFalse("string literals are not enum constants",
                invokeEnumPredicate(StringLiteralCarrier.class));
    }

    private static void assertResourceGetterPredicate() {
        try {
            Method predicate = DexKitAbiScanner.class.getDeclaredMethod(
                    "hasExactReasoningResourceGetter", Class.class, Method.class);
            predicate.setAccessible(true);
            Method resourceId = Qx7.class.getDeclaredMethod("resourceId");
            Method unrelatedBudget = Qx7.class.getDeclaredMethod("unrelatedBudget");
            Assert.assertTrue("instance Android resource getter must be accepted",
                    ((Boolean) predicate.invoke(null, Qx7.class, resourceId)).booleanValue());
            Assert.assertFalse("non-resource instance method must be rejected",
                    ((Boolean) predicate.invoke(null, Qx7.class, unrelatedBudget)).booleanValue());
        } catch (NoSuchMethodException exception) {
            Assert.fail("structural resource getter predicate is missing");
        } catch (Exception exception) {
            throw new AssertionError("resource getter predicate fixture failed", exception);
        }
    }

    private static boolean invokeEnumPredicate(Class<?> candidate) {
        try {
            Method predicate = DexKitAbiScanner.class.getDeclaredMethod(
                    "hasExactReasoningEnumContract", Class.class);
            predicate.setAccessible(true);
            return ((Boolean) predicate.invoke(null, candidate)).booleanValue();
        } catch (Exception exception) {
            throw new AssertionError("structural enum predicate fixture failed", exception);
        }
    }

    private static void assertReasoningCapabilityCarriesEnumClass() {
        try {
            Field field = HostAbi.ReasoningCapability.class.getField("reasoningEnumClass");
            Assert.assertEquals(Class.class, field.getType());
        } catch (NoSuchFieldException exception) {
            Assert.fail("reasoning capability must expose reasoningEnumClass");
        }
    }

    private static void assertScannerUsesNoFixedEnumLookups() {
        try {
            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            Assert.assertFalse("scanner must not hardcode the v12 reasoning class",
                    source.contains("\"a69\""));
            Assert.assertFalse("scanner must not hardcode the old enum getter",
                    source.contains("getDeclaredMethod(\n                    MENU_RESOURCE_ID_METHOD"));
            Assert.assertFalse("scanner must not require the old enum method d",
                    source.contains("getDeclaredMethod(\"d\")"));
            Assert.assertFalse("scanner must not require the old enum method m",
                    source.contains("getDeclaredMethod(\"m\")"));
        } catch (Exception exception) {
            throw new AssertionError("scanner source contract failed", exception);
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

    private enum Qx7 {
        OFF(0x7f010001), AUTO(0x7f010002), LOW(0x7f010003),
        MEDIUM(0x7f010004), HIGH(0x7f010005), MAX(0x7f010006);

        private final int resourceId;

        Qx7(int resourceId) {
            this.resourceId = resourceId;
        }

        public int resourceId() {
            return resourceId;
        }

        public int unrelatedBudget() {
            return ordinal() * 1024;
        }
    }

    private enum Qx8 {
        OFF, AUTO, LOW, MEDIUM, HIGH, MAX, EXTRA
    }

    private static final class StringLiteralCarrier {
        private static final String OFF = "OFF";
        private static final String AUTO = "AUTO";
        private static final String LOW = "LOW";
        private static final String MEDIUM = "MEDIUM";
        private static final String HIGH = "HIGH";
        private static final String MAX = "MAX";

        private StringLiteralCarrier() {
        }
    }
}
