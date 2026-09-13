# Task 7 Verification Plan

**Status:** `COMPLETE WITH LIMITATIONS`; documentation and local verification are complete for the content-adaptive DexKit work. The exact v12 runtime acceptance is retained as recorded evidence; newer-host empirical compatibility and this task's device rerun remain pending.

1. Preserve the existing exact-v12 acceptance records and document the adaptive boundary separately.
2. Verify the README contract for primary/split SHA-256 fingerprinting, the `ClassLoader` plus fingerprint cache, structural DexKit discovery, fail-closed independent capabilities, privacy-safe diagnostics, and the v12/newer-host boundary.
3. Run the full JVM suite and Debug/Release assembly with `-x lintVitalRelease`; record the unmodified `targetSdk = 29` lint blocker from normal Release/lint execution.
4. Run static/source, manifest, API 102, Java 8, DexKit 2.2.0, whitespace, artifact, and available diagnostics checks without touching host APK or device state.
5. Keep publication, installation, push, tags, and newer-host empirical compatibility outside this verification task.

---

The host-authoritative six-label menu redesign is locally implemented, installed through Termux RunCommandService as KernelSU root, and manually runtime-accepted. Unit tests, `assembleDebug`, source/static checks, manifest inspection, and targeted LSP diagnostics passed. The known target-SDK lint failure remains intentionally unresolved because `targetSdk = 29` is required.

The current APK is `app/build/outputs/apk/debug/app-debug.apk` (SHA-256 `18344adb7784f3c6ed45f2b89544d53d4f111f8c3c66876cab67c67d253d2331`). No Git publication was performed.

# Task 9 Review Plan

**Status:** `COMPLETE`; local documentation, source/build verification, installation, and user-confirmed exact-host/device/LSPosed acceptance are recorded.

1. Verify the staged host identity and preserve the read-only boundary.
2. Recheck the existing `HostV12AbiCheck` invocation contract. It must take the staged APK path as argument zero, verify package/version metadata and current-APK byte identity through `AppGlobals`, resolve with the host classloader, print only capability counts, and fail closed for missing or ambiguous required request/display seams.
3. Record static implementation evidence for the v12 provider persistence chain, `Lne1.n`/`Ly98.p` request seams, `Lua.Y1 -> Lso2.b` display argument, exact-label long-press resolver, policy mappings, and forbidden-behavior review.
4. Record local test, lint, assembly, diagnostics, whitespace, and artifact evidence without changing `targetSdk = 29` or adding a lint suppression.
5. Record the separately authorized installation and manual runtime acceptance without publishing the artifact.

The final Debug APK was installed with `pm install -r`, and the user confirmed manual runtime acceptance passed. Git publication remains outside this task and requires separate authorization.

## Adaptive menu resolver follow-up — 2026-09-14

- [x] Reproduce the remaining v12 `menuLabels:candidates 0` result with read-only DexKit evidence.
- [x] Add the failing duplicate-caller regression and deduplicate identical reflected `Method` records before uniqueness selection.
- [x] Re-run the full JVM/build gate and verify fresh Debug/Release artifact hashes.
- [x] Reinstall the rebuilt module and confirm fresh LSPosed logs install the menu and both request capabilities.
- [ ] Validate against a newer real host APK.
- [ ] Obtain publication authorization before committing or pushing the worktree patch.
