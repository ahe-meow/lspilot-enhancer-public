# libxposed API 102 Reference

- Status: reference; revalidate when the libxposed version or module lifecycle changes
- Updated: 2026-08-12
- Purpose: record verified API 102 integration facts and remaining compatibility risks without stale implementation claims.

## Baseline

- `libxposed/api` `102.0.0`, reviewed at commit `39cac08`.
- `libxposed/service` `102.0.0`, reviewed at commit `3318940`.
- `libxposed/example`, reviewed at commit `b94ee7e`.
- API 103 development branches are outside the production baseline.

Primary references:

- <https://github.com/libxposed/api>
- <https://github.com/libxposed/service>
- <https://github.com/libxposed/example>
- <https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API>

## Confirmed Integration

- The entry class extends `io.github.libxposed.api.XposedModule` and has the required constructor.
- `META-INF/xposed/java_init.list`, `scope.list`, and `module.prop` are packaged in the modern layout.
- `module.prop` targets API 102, uses static scope, and sets `autoHotReload=false`.
- Hooks use `hook(Executable).intercept(...)` and install only for `me.yun.lspilot`.
- Remote preferences are connected as a legacy migration source; active settings remain host-local by design.
- Release minification is disabled, so entry-point keep rules are not currently required.
- `compileSdk=29`, `targetSdk=29`, and Java 8 are deliberate host compatibility choices and have produced release APKs.

## Open Risks

### Hook lifecycle

Hook handles do not use stable IDs or replacement management. This is acceptable only while hot reload remains disabled. Enabling hot reload requires tracked handles, asynchronous resource shutdown, callback invalidation, host-reference cleanup, and duplicate-install protection.

### Exception mode

The module does not declare a uniform exception mode. Before changing this, verify API 102 behavior and ensure hook code distinguishes module failures from exceptions raised by `chain.proceed()`.

### Manifest description

The application manifest has a label but no `android:description`. Add one when module-manager presentation or release metadata requires it.

### R8

If release minification is enabled, add and verify libxposed entry/resource adaptation rules and inspect the final APK's `java_init.list`.

### Toolchain age

The project intentionally differs from newer official examples. Revalidate API 102 AAR compatibility before changing AGP, SDK levels, Java version, or the local compile-only dependency.

### Host ABI

Obfuscated host routes and message/provider signatures remain the dominant compatibility risk. Keep resolver probes, cache versioning, isolated feature degradation, and focused ABI checks current for every supported host update.

## Stable Rules

- Use stable API 102 sources for production decisions.
- Keep hot reload disabled until every hook and asynchronous resource has a complete lifecycle.
- Treat SDK/toolchain upgrades as compatibility work, not routine cleanup.
- Re-run package, version, SDK, entry-list, scope-list, and module-property inspection on release artifacts.
