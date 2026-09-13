# Task 7 Verification Checklist

**Status:** `DONE WITH LIMITATIONS`; all available local gates passed, while prohibited/unavailable runtime actions remain explicitly pending.

- [x] Read the approved Task 7 brief/spec, progress ledger, prior reports/reviews, README, source, and tests.
- [x] Add and pass the README documentation contract for content fingerprint, split APK, structural DexKit, version metadata independence, fail-closed behavior, target SDK, and unsigned release output.
- [x] Run the full JVM suite and combined Debug/Release assembly with `-x lintVitalRelease`.
- [x] Record the normal Release/lint failure caused by the required `targetSdk = 29` `ExpiredTargetSdkVersion` check.
- [x] Run source/static, manifest, API/SDK/Java/DexKit, whitespace, and changed-path inspection checks.
- [x] Attempt available LSP and exact-v12 host-process checks; record their environment blockers without changing runtime state.
- [ ] Empirically test a newer host APK; no newer sample was supplied.
- [ ] Install or rerun device/LSPosed acceptance; installation is prohibited for this task.
- [ ] Publish, push, tag, or release; publication is outside this task.

---

**Status:** `PASSED`; APK installation and manual host/device/LSPosed runtime acceptance completed through the Termux RunCommandService route.

- [x] Implement host enum-to-label mapping and guarded menu hook.
- [x] Read host reasoning getter per request and preserve protected JSON fields.
- [x] Remove module settings UI, Remote Preferences policy storage, sync path, and service dependency.
- [x] Run unit tests, Debug assembly, source/static scans, manifest inspection, and targeted LSP diagnostics.
- [x] Install the current APK through Termux RunCommandService and verify `Success` / `PM_EXIT=0` plus package metadata.
- [x] Perform six-label/menu persistence/request runtime acceptance and verify LSPosed injection.
- [ ] Obtain explicit publication authorization before any Git commit, tag, push, or release publication.

# Task 9 Todo

**Status:** `DONE`; Task 9 local, installation, and user-confirmed runtime acceptance are recorded.

- [x] Inspect the approved brief, plan, design, current findings, progress ledger, Task 8 report, ABI entry point, and implementation sources.
- [x] Confirm the staged host artifact identity record and local debug artifact evidence.
- [x] Confirm that `HostV12AbiCheck.java` already satisfies the final invocation, metadata, read-only, count-output, and required-capability failure contract.
- [x] Record Task 2-8 implementation and forbidden-behavior evidence in reusable documentation.
- [x] Record local checks, their exit codes, the approved target-SDK lint blocker, and the publication boundary.
- [x] Remove the context-limit parser, key, display/long-press hooks, ABI fields, settings control, and context-only tests after the host-process write failure was confirmed; update the current design/plan/handoff.
- [x] Run `HostV12AbiCheck` in the exact current host process with the byte-identical staged APK and record candidate counts.
- [x] Perform separately authorized device/LSPosed runtime acceptance.
- [ ] Obtain explicit publication authorization before any Git commit, tag, push, or release publication.
