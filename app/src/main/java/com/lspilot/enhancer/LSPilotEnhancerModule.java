package com.lspilot.enhancer;

import android.content.pm.ApplicationInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** API 102 entry point for one target package and one host process. */
public final class LSPilotEnhancerModule extends XposedModule {
    private static final String TARGET_PACKAGE = "me.yun.lspilot";

    private final Object lifecycleLock = new Object();
    private String moduleProcessName;
    private HookRegistry registry;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            synchronized (lifecycleLock) {
                if (param == null) {
                    moduleProcessName = null;
                } else {
                    moduleProcessName = param.getProcessName();
                }
            }
            DebugLogger.capability("lifecycle", "module_loaded");
        } catch (Throwable exception) {
            synchronized (lifecycleLock) {
                moduleProcessName = null;
            }
            DebugLogger.capability("lifecycle", "disabled", exception);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null) {
            DebugLogger.capability("routing", "package_rejected");
            return;
        }

        String packageName;
        try {
            packageName = param.getPackageName();
        } catch (Throwable exception) {
            DebugLogger.capability("routing", "package_rejected", exception);
            return;
        }

        synchronized (lifecycleLock) {
            if (!TARGET_PACKAGE.equals(packageName)) {
                DebugLogger.capability("routing", "package_rejected");
                return;
            }
            if (!TARGET_PACKAGE.equals(moduleProcessName)) {
                DebugLogger.capability("routing", "process_rejected");
                return;
            }
            DebugLogger.capability("routing", "package_accepted");
            DebugLogger.capability("routing", "process_accepted");
            try {
                handleAcceptedPackage(param);
            } catch (Throwable exception) {
                closeRegistryLocked();
                DebugLogger.capability("lifecycle", "disabled", exception);
                disableCapabilities();
            }
        }
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        DexKitAbiScanner.clearCache();
        synchronized (lifecycleLock) {
            if (registry != null) {
                try {
                    registry.close();
                } catch (Throwable exception) {
                    DebugLogger.capability("lifecycle", "disabled", exception);
                }
                registry = null;
            }
        }
        DebugLogger.capability("lifecycle", "hot_reloading");
        return false;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        DebugLogger.capability("lifecycle", "hot_reloaded");
    }

    @android.annotation.SuppressLint("NewApi")
    private void handleAcceptedPackage(
            XposedModuleInterface.PackageReadyParam param) {
        closeRegistryLocked();

        ApplicationInfo applicationInfo;
        try {
            applicationInfo = param.getApplicationInfo();
        } catch (Throwable exception) {
            DebugLogger.capability("routing", "disabled", exception);
            disableCapabilities();
            return;
        }
        if (applicationInfo == null) {
            DebugLogger.capability("routing", "disabled");
            disableCapabilities();
            return;
        }

        String sourceDir = applicationInfo.sourceDir;
        List<String> sourcePaths = new ArrayList<String>();
        sourcePaths.add(sourceDir);
        if (applicationInfo.splitSourceDirs != null) {
            for (String splitSourceDir : applicationInfo.splitSourceDirs) {
                sourcePaths.add(splitSourceDir);
            }
        }
        if (!areReadable(sourcePaths)) {
            DebugLogger.capability("routing", "source_paths",
                    Integer.valueOf(sourcePaths.size()), Boolean.FALSE);
            disableCapabilities();
            return;
        }
        DebugLogger.capability("routing", "source_paths",
                Integer.valueOf(sourcePaths.size()), Boolean.TRUE);

        ClassLoader loader;
        try {
            loader = param.getClassLoader();
        } catch (Throwable ignored) {
            loader = null;
        }
        if (loader == null) {
            try {
                loader = param.getDefaultClassLoader();
            } catch (Throwable exception) {
                loader = null;
                DebugLogger.capability("routing", "disabled", exception);
            }
        }
        if (loader == null) {
            DebugLogger.capability("routing", "disabled");
            disableCapabilities();
            return;
        }

        registry = new HookRegistry(this);
        HookRegistry currentRegistry = registry;

        DexKitAbiScanner.ScanResult scanResult;
        try {
            scanResult = DexKitAbiScanner.resolveDetailed(loader, sourcePaths);
        } catch (Throwable exception) {
            scanResult = null;
            DebugLogger.capability("lifecycle", "disabled", exception);
        }
        if (scanResult == null) {
            closeRegistryLocked();
            disableCapabilities();
            return;
        }

        logResolutionCounts(scanResult);
        if (!hasAnyCapability(scanResult.abi)) {
            closeRegistryLocked();
            disableCapabilities();
            return;
        }

        installCapabilities(scanResult, currentRegistry);
        if (currentRegistry.resourceCount() == 0) {
            closeRegistryLocked();
        }
    }

    static boolean hasAnyCapability(HostAbi abi) {
        return abi != null && (abi.reasoningSource != null
                || abi.menuLabels != null
                || abi.genericRequest != null
                || abi.thinkingRequest != null);
    }

    private void installCapabilities(
            final DexKitAbiScanner.ScanResult scanResult,
            final HookRegistry currentRegistry) {
        final HostAbi abi = scanResult.abi;
        attemptCapability("menuLabels",
                abi.menuLabels != null,
                scanResult.menuLabelsCount,
                currentRegistry,
                new CapabilityAction() {
                    @Override
                    public void install() {
                        ReasoningMenuHook.install(abi.menuLabels, currentRegistry);
                    }
                });
        attemptCapability("genericRequest",
                abi.reasoningSource != null && abi.genericRequest != null,
                scanResult.genericRequestCount,
                currentRegistry,
                new CapabilityAction() {
                    @Override
                    public void install() {
                        RequestPolicyHook.install(
                                abi.genericRequest, abi.reasoningSource, currentRegistry);
                    }
                });
        attemptCapability("thinkingRequest",
                abi.reasoningSource != null && abi.thinkingRequest != null,
                scanResult.thinkingRequestCount,
                currentRegistry,
                new CapabilityAction() {
                    @Override
                    public void install() {
                        RequestPolicyHook.install(
                                abi.thinkingRequest, abi.reasoningSource, currentRegistry);
                    }
                });
    }

    private void attemptCapability(
            String capability,
            boolean available,
            int candidateCount,
            HookRegistry currentRegistry,
            CapabilityAction action) {
        if (!available || currentRegistry == null || action == null) {
            DebugLogger.capability(capability, "disabled", Integer.valueOf(candidateCount));
            return;
        }
        try {
            if (CapabilityAttempt.run(currentRegistry, action)) {
                DebugLogger.capability(capability, "installed");
            } else {
                DebugLogger.capability(capability, "disabled",
                        Integer.valueOf(candidateCount));
            }
        } catch (Throwable exception) {
            DebugLogger.capability(capability, "disabled", exception);
        }
    }

    private void closeRegistryLocked() {
        if (registry == null) {
            return;
        }
        try {
            registry.close();
        } catch (Throwable exception) {
            DebugLogger.capability("lifecycle", "disabled", exception);
        }
        registry = null;
    }

    private static boolean areReadable(List<String> sourcePaths) {
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            return false;
        }
        for (String sourcePath : sourcePaths) {
            if (sourcePath == null || sourcePath.trim().isEmpty()) {
                return false;
            }
            File path = new File(sourcePath);
            if (!path.isFile() || !path.canRead()) {
                return false;
            }
        }
        return true;
    }

    private static void logResolutionCounts(DexKitAbiScanner.ScanResult scanResult) {
        DebugLogger.capability("lifecycle", "fingerprint", scanResult.contentFingerprint);
        DebugLogger.capability("lifecycle", "cache_hit",
                Boolean.valueOf(scanResult.cacheHit));
        DebugLogger.capability("lifecycle", "scan_fresh",
                Boolean.valueOf(scanResult.contentFingerprint != null && !scanResult.cacheHit));
        DebugLogger.capability("reasoningSource", "candidates",
                Integer.valueOf(scanResult.reasoningSourceCount));
        DebugLogger.capability("menuLabels", "candidates",
                Integer.valueOf(scanResult.menuLabelsCount));
        DebugLogger.capability("genericRequest", "candidates",
                Integer.valueOf(scanResult.genericRequestCount));
        DebugLogger.capability("thinkingRequest", "candidates",
                Integer.valueOf(scanResult.thinkingRequestCount));
    }

    private static void disableCapabilities() {
        DebugLogger.capability("reasoningSource", "disabled", Integer.valueOf(0));
        DebugLogger.capability("menuLabels", "disabled", Integer.valueOf(0));
        DebugLogger.capability("genericRequest", "disabled", Integer.valueOf(0));
        DebugLogger.capability("thinkingRequest", "disabled", Integer.valueOf(0));
    }

    private interface CapabilityAction extends CapabilityAttempt.Action {
        @Override
        void install();
    }
}

final class CapabilityAttempt {
    private CapabilityAttempt() {
    }

    interface Action {
        void install();
    }

    static boolean run(HookRegistry registry, Action action) {
        if (registry == null || action == null) {
            return false;
        }
        try {
            int resourcesBefore = registry.resourceCount();
            action.install();
            return registry.resourceCount() > resourcesBefore;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
