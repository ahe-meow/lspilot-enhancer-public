# Current Todo

- Status: current
- Updated: 2026-08-13
- Purpose: track only concrete actions and their evidence requirements for the active goal.

## Completed

- [x] Implement the model summary protocol, compression state machine, persistence, request coordinator, request reconstruction, UI projection, and legacy-path guards.
- [x] Move the internal provider stream off the Android main thread.
- [x] Arm the 60-second summary timeout before provider stream invocation.
- [x] Pass the focused DEX assertion check and release/lint gate for the local fix.
- [x] Force-rebuild `app/build/outputs/apk/release/app-release.apk` with SHA-256 `561f3b536821a5f7fa5246ce11ef3e6f4db5d998e1c25377b0f2fed68d8e6eb4`.
- [x] Copy the exact APK to a Termux-visible no-space path.
- [x] Stage and install it through KernelSU `RunCommandService`.
- [x] Match built, staged, and installed APK SHA-256 values.
- [x] Force-stop and restart `me.yun.lspilot`.
- [x] Capture current LSPosed/module logs proving the module and compression hooks loaded.
- [x] Reproduce the infinite `SUMMARIZING` state and capture the misclassified internal request in current LSPosed logs.
- [x] Accept provider-added system messages when identifying the active internal summary request.
- [x] Make current-task timeouts leave `SUMMARIZING` even after request-building side effects.
- [x] Add focused assertions for internal request identity and timeout exit behavior.
- [x] Build, install, hash-match, and load APK SHA-256 `4d34fe83e541a0fcfa631f4f4b19b28beb7dec26c245a988097f5d95e5bde037`.
- [x] Recognize the current host `lwb` chunk/done/error stream events and extract only text parts from nested chunks.
- [x] Pass the focused DEX check, device-side assertions, release build, and lint for the current-host stream event fix.
- [x] Build, install, hash-match, and load APK SHA-256 `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107`.
- [x] Create `docs/work/handoff.md` and point `AGENTS.md` at it for the next agent.

## Next

- [ ] Have the user enter the affected chat and rerun manual compression while current logs are collected.
- [ ] Confirm the flow reaches success or a clear failure/cancel state and never displays summary Markdown as chat content.
- [ ] After success, confirm a later provider request uses reduced effective context.
- [ ] Update `goal.md`, `plan.md`, `todo.md`, `findings.md`, and `handoff.md` with final evidence.
