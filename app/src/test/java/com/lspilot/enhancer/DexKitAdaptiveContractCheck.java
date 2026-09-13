package com.lspilot.enhancer;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

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
        assertAmbiguousResourceGetterSelection();
        assertReasoningCapabilityCarriesEnumClass();
        assertScannerUsesNoFixedEnumLookups();
        assertMenuCallerRequiresBothInvokes();
        assertMenuResolverSelectionUsesCallerEvidence();
        assertMenuResolverBundleSelection();
        assertRequestDiscoveryUsesStructuralMatchers();
        assertMenuDiscoveryUsesStructuralCallers();
    }

    private static void assertMenuCallerRequiresBothInvokes() {
        Assert.assertTrue("menu callers need both invoke anchors",
                DexKitAbiScanner.hasRequiredMenuCallerInvokes(true, true));
        Assert.assertFalse("resolver-only callers must be rejected",
                DexKitAbiScanner.hasRequiredMenuCallerInvokes(true, false));
        Assert.assertFalse("getter-only callers must be rejected",
                DexKitAbiScanner.hasRequiredMenuCallerInvokes(false, true));
        Assert.assertFalse("callers with neither anchor must be rejected",
                DexKitAbiScanner.hasRequiredMenuCallerInvokes(false, false));
        try {
            String scannerSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            Assert.assertTrue("caller filtering must use the combined evidence gate",
                    scannerSource.contains("hasRequiredMenuCallerInvokes("));
            Assert.assertTrue("caller filtering must pass the getter descriptor",
                    scannerSource.contains("resolverDescriptor, resourceDescriptor"));
            Assert.assertTrue("menu caller selection must remain unique",
                    scannerSource.contains("chooseUnique(\"menuCaller\""));
            Assert.assertTrue("button caller selection must remain unique",
                    scannerSource.contains("chooseUnique(\"buttonCaller\""));
        } catch (Exception exception) {
            throw new AssertionError("menu caller evidence fixture failed", exception);
        }
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

    private static void assertAmbiguousResourceGetterSelection() {
        try {
            Method first = AmbiguousReasoning.class.getDeclaredMethod("firstResourceId");
            Method second = AmbiguousReasoning.class.getDeclaredMethod("secondResourceId");
            Assert.assertNull("ambiguous resource getters must fail closed",
                    DexKitAbiScanner.selectUniqueReasoningResourceGetter(
                            AmbiguousReasoning.class, Arrays.asList(first, second)));
            Assert.assertSame("one structural resource getter must be selected",
                    first,
                    DexKitAbiScanner.selectUniqueReasoningResourceGetter(
                            AmbiguousReasoning.class, Collections.singletonList(first)));
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("resource getter selection fixture failed", exception);
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
            Method getter = Qx7.class.getDeclaredMethod("resourceId");
            Constructor<?> repository = StringLiteralCarrier.class.getDeclaredConstructor();
            HostAbi.ReasoningCapability capability = new HostAbi.ReasoningCapability(
                    getter, repository, Qx7.class);
            Assert.assertSame("capability must retain the discovered enum class",
                    Qx7.class, capability.reasoningEnumClass);

            String source = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            Assert.assertTrue("reasoning capability must use the discovered enum class",
                    source.contains("enumPrerequisite.capability.enumClass"));
            Assert.assertTrue("menu resolution must use the discovered enum prerequisite",
                    source.contains("resolveMenu(loader, enumPrerequisite.capability)"));
            Assert.assertTrue(
                    "generic request resolution must use the discovered enum prerequisite",
                    source.contains(
                            "resolveGenericRequest(bridge, loader, enumPrerequisite.capability)"));
            Assert.assertTrue(
                    "thinking request resolution must use the discovered enum prerequisite",
                    source.contains(
                            "resolveThinkingRequest(bridge, loader, enumPrerequisite.capability)"));
        } catch (NoSuchFieldException exception) {
            Assert.fail("reasoning capability must expose reasoningEnumClass");
        } catch (Exception exception) {
            throw new AssertionError("reasoning capability fixture failed", exception);
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

    private static void assertRequestDiscoveryUsesStructuralMatchers() {
        try {
            String scannerSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            Assert.assertTrue("generic request query must use a broad return matcher",
                    scannerSource.contains("MethodMatcher genericMatcher"));
            Assert.assertTrue("generic request query must use parameter count",
                    scannerSource.contains(".paramCount(6)"));
            Assert.assertTrue("thinking request query must use parameter count",
                    scannerSource.contains(".paramCount(4)"));
            Assert.assertTrue("request query must match JSON literals",
                    scannerSource.contains(
                            "usingEqStrings(\"reasoning_effort\", \"messages\", \"stream\")"));
            Assert.assertTrue("thinking query must match JSON literals",
                    scannerSource.contains(
                            "usingEqStrings(\"max_tokens\", \"thinking\", \"budget_tokens\")"));
            Assert.assertTrue("request metadata must inspect parameter positions",
                    scannerSource.contains("getParamTypeNames()"));
            Assert.assertTrue("request metadata must reject primitive providers",
                    scannerSource.contains("isPrimitive()"));
            Assert.assertTrue("request candidates must be unique per capability",
                    scannerSource.contains("chooseUnique(\"genericRequest\"")
                            && scannerSource.contains("chooseUnique(\"thinkingRequest\""));
            Assert.assertFalse("request discovery must not use fixed provider parameter names",
                    scannerSource.contains("parameterNames = new String[]{\n                    TYPE_PROVIDER"));

            String hookSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/RequestPolicyHook.java");
            Assert.assertTrue("installer must receive the reasoning capability",
                    hookSource.contains(
                            "isInstallable(capability, reasoningCapability)"));
            Assert.assertTrue("installer must compare the final discovered enum type",
                    hookSource.contains("actual[actual.length - 1]"));
            Assert.assertFalse("installer must not compare the fixed provider type",
                    hookSource.contains("\"vb\""));
            Assert.assertFalse("installer must not compare the fixed reasoning type",
                    hookSource.contains("\"a69\""));
        } catch (Exception exception) {
            throw new AssertionError("type-driven request discovery contract failed", exception);
        }
    }

    private static void assertMenuResolverSelectionUsesCallerEvidence() {
        try {
            String scannerSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            String normalizedSource = scannerSource.replaceAll("\\s+", "");
            int callerEvidence = normalizedSource.indexOf("getCallers()");
            int rawSelection = normalizedSource.indexOf("chooseUnique(\"menuResolver\"");
            Assert.assertTrue("menu resolver uniqueness must be decided after caller evidence",
                    callerEvidence >= 0 && rawSelection >= 0 && callerEvidence < rawSelection);
        } catch (Exception exception) {
            throw new AssertionError("menu resolver selection contract failed", exception);
        }
    }

    private static void assertMenuResolverBundleSelection() {
        try {
            Method completeResolver = MenuResolverFixtures.class.getDeclaredMethod(
                    "resolveComplete", int.class, MenuResolverFixtures.ComposerQ.class, int.class);
            Method missingResolver = MenuResolverFixtures.class.getDeclaredMethod(
                    "resolveMissing", int.class, MenuResolverFixtures.ComposerQ.class, int.class);
            Method ambiguousResolver = MenuResolverFixtures.class.getDeclaredMethod(
                    "resolveAmbiguous", int.class, MenuResolverFixtures.ComposerQ.class, int.class);
            Method secondCompleteResolver = MenuResolverFixtures.class.getDeclaredMethod(
                    "resolveSecondComplete", int.class,
                    MenuResolverFixtures.ComposerQ.class, int.class);
            Method menu = MenuResolverFixtures.class.getDeclaredMethod(
                    "renderChoices", Qx7.class);
            Method alternateMenu = MenuResolverFixtures.class.getDeclaredMethod(
                    "renderAlternateChoices", Qx7.class);
            Method button = MenuResolverFixtures.class.getDeclaredMethod(
                    "renderCurrent", Qx7.class);

            DexKitAbiScanner.MenuResolverBundle complete = newMenuBundle(
                    completeResolver, Collections.singletonList(menu),
                    Collections.singletonList(button));
            DexKitAbiScanner.MenuResolverBundle missingCaller = newMenuBundle(
                    missingResolver, Collections.<Method>emptyList(),
                    Collections.singletonList(button));
            DexKitAbiScanner.MenuResolverBundle ambiguousCaller = newMenuBundle(
                    ambiguousResolver, Arrays.asList(menu, alternateMenu),
                    Collections.singletonList(button));

            DexKitAbiScanner.MenuResolverSelection selected =
                    DexKitAbiScanner.selectUniqueCompleteMenuBundle(
                            Arrays.asList(complete, missingCaller, ambiguousCaller),
                            kotlin.Unit.class);
            Assert.assertEquals("one complete raw bundle must be selected", 1,
                    selected.completeCount);
            Assert.assertSame("the complete resolver bundle must win",
                    completeResolver, selected.capability.labelResolver);

            DexKitAbiScanner.MenuResolverSelection none =
                    DexKitAbiScanner.selectUniqueCompleteMenuBundle(
                            Arrays.asList(missingCaller, ambiguousCaller), kotlin.Unit.class);
            Assert.assertEquals("zero complete bundles must fail closed", 0,
                    none.completeCount);
            Assert.assertNull("zero complete bundles must not produce a capability",
                    none.capability);

            DexKitAbiScanner.MenuResolverBundle secondComplete = newMenuBundle(
                    secondCompleteResolver, Collections.singletonList(menu),
                    Collections.singletonList(button));
            DexKitAbiScanner.MenuResolverSelection ambiguous =
                    DexKitAbiScanner.selectUniqueCompleteMenuBundle(
                            Arrays.asList(complete, secondComplete), kotlin.Unit.class);
            Assert.assertEquals("two complete bundles must remain ambiguous", 2,
                    ambiguous.completeCount);
            Assert.assertNull("two complete bundles must fail closed",
                    ambiguous.capability);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("menu resolver bundle fixture method missing", exception);
        }
    }

    private static DexKitAbiScanner.MenuResolverBundle newMenuBundle(
            Method resolver, java.util.List<Method> menuCallers,
            java.util.List<Method> buttonCallers) {
        java.util.Map<Integer, String> labels = new java.util.HashMap<Integer, String>();
        labels.put(Integer.valueOf(0x7f010001), "off");
        labels.put(Integer.valueOf(0x7f010002), "low");
        labels.put(Integer.valueOf(0x7f010003), "medium");
        labels.put(Integer.valueOf(0x7f010004), "high");
        labels.put(Integer.valueOf(0x7f010005), "xhigh");
        labels.put(Integer.valueOf(0x7f010006), "max");
        return new DexKitAbiScanner.MenuResolverBundle(
                resolver, menuCallers, buttonCallers, labels, Qx7.class);
    }

    private static void assertMenuDiscoveryUsesStructuralCallers() {
        try {
            String scannerSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/DexKitAbiScanner.java");
            Assert.assertTrue("menu query must search three-parameter String methods",
                    scannerSource.contains(".paramCount(3)"));
            Assert.assertTrue("menu query must require the Resources string invoke",
                    scannerSource.contains(
                            ".addInvoke(\"Landroid/content/res/Resources;->getString(I)Ljava/lang/String;\")"));
            Assert.assertTrue("menu discovery must inspect direct resolver callers",
                    scannerSource.contains("getCallers()"));
            Assert.assertTrue("menu discovery must retain reflected menu methods",
                    scannerSource.contains("buttonEnumIndex"));
            Assert.assertTrue("menu installation must support atomic rollback",
                    readSource("app/src/main/java/com/lspilot/enhancer/ReasoningMenuHook.java")
                            .contains("registry.removeHook"));

            String[] fixedLookupExpressions = {
                    "loadHostClass(loader, TYPE_MENU_RESOLVER)",
                    "loadHostClass(loader, TYPE_MENU_COMPOSER)",
                    "loadHostClass(loader, TYPE_MENU_OWNER)",
                    "loadHostClass(loader, TYPE_MENU_RESOURCES)",
                    "getDeclaredMethod(\n                    MENU_RESOLVER_METHOD",
                    "getDeclaredMethod(\n                    MENU_CALLER_METHOD",
                    "getDeclaredMethod(\n                    MENU_BUTTON_METHOD",
                    "getDeclaredField(\n                    MENU_BUTTON_RESOURCE_FIELD"
            };
            for (String expression : fixedLookupExpressions) {
                Assert.assertFalse("menu discovery must not use fixed lookup: " + expression,
                        scannerSource.contains(expression));
            }

            String hostAbiSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/HostAbi.java");
            Assert.assertTrue("menu capability must store the discovered menu method",
                    hostAbiSource.contains("public final Method menuMethod"));
            Assert.assertTrue("menu capability must store the discovered button method",
                    hostAbiSource.contains("public final Method buttonMethod"));
            Assert.assertTrue("menu capability must store the discovered enum class",
                    hostAbiSource.contains("public final Class<?> reasoningEnumClass"));
            Assert.assertTrue("menu capability must store the enum parameter index",
                    hostAbiSource.contains("public final int buttonEnumIndex"));
            Assert.assertFalse("menu capability must not store a fixed owner name",
                    hostAbiSource.contains("menuOwner"));

            String menuHookSource = readSource(
                    "app/src/main/java/com/lspilot/enhancer/ReasoningMenuHook.java");
            Assert.assertTrue("stack guard must compare reflected method targets",
                    menuHookSource.contains("Method... targets"));
            Assert.assertFalse("menu guard must not inspect the old composer name",
                    menuHookSource.contains("\"id2\""));
        } catch (Exception exception) {
            throw new AssertionError("structural menu discovery contract failed", exception);
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

    private enum AmbiguousReasoning {
        OFF(0x7f010001, 0x7f020001), AUTO(0x7f010002, 0x7f020002),
        LOW(0x7f010003, 0x7f020003), MEDIUM(0x7f010004, 0x7f020004),
        HIGH(0x7f010005, 0x7f020005), MAX(0x7f010006, 0x7f020006);

        private final int firstResourceId;
        private final int secondResourceId;

        AmbiguousReasoning(int firstResourceId, int secondResourceId) {
            this.firstResourceId = firstResourceId;
            this.secondResourceId = secondResourceId;
        }

        public int firstResourceId() {
            return firstResourceId;
        }

        public int secondResourceId() {
            return secondResourceId;
        }
    }

    private static final class MenuResolverFixtures {
        private MenuResolverFixtures() {
        }

        static String resolveComplete(int resourceId, ComposerQ composer, int flags) {
            return composer == null ? null : String.valueOf(resourceId + flags);
        }

        static String resolveMissing(int resourceId, ComposerQ composer, int flags) {
            return composer == null ? null : String.valueOf(resourceId + flags);
        }

        static String resolveAmbiguous(int resourceId, ComposerQ composer, int flags) {
            return composer == null ? null : String.valueOf(resourceId + flags);
        }

        static String resolveSecondComplete(int resourceId, ComposerQ composer, int flags) {
            return composer == null ? null : String.valueOf(resourceId + flags);
        }

        static kotlin.Unit renderChoices(Qx7 mode) {
            return kotlin.Unit.INSTANCE;
        }

        static kotlin.Unit renderAlternateChoices(Qx7 mode) {
            return kotlin.Unit.INSTANCE;
        }

        static void renderCurrent(Qx7 mode) {
        }

        private static final class ComposerQ {
        }
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
