# Agent Handoff

- Status: current
- Updated: 2026-08-13
- Purpose: give the next agent the exact project state, evidence, and next action without relying on chat history.

## Start Here

- Worktree: `.worktrees/model-context-compression`
- Branch: `feat/model-context-compression`
- Active goal: finish runtime acceptance for model-driven context compression in LSPilot.
- Latest installed module APK SHA-256: `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107`
- Current blocker: no fresh manual compression attempt has run after installing that APK.

## What Changed

- `HostAbi.java` now centralizes stream event classification for legacy `nyb` and current `lwb` chunk/done/error events.
- `LSPilotEnhancerModule.java` runs internal summary streams on the `lspilot-summary-request` executor and arms timeout before dispatch.
- `ManualCompressionManager.java` accepts provider-added system messages when matching the active internal summary request and keeps timeout ownership independent of request reconstruction side effects.
- `AutoRetryManager.java` reuses the shared event classifier.
- `ModelContextCompressionCheck.java` covers provider-added system-message identity, timeout exit, and current host `lwb` Done termination.

## Verified Evidence

- Focused DEX check passed for the current source.
- Android `dalvikvm -ea` execution of `ModelContextCompressionCheck` exited 0 with empty stdout.
- Release build and lint passed with the project Android host flags.
- KernelSU install ran as Android `uid=0(root)` in SELinux domain `u:r:ksu:s0`.
- Built APK, Termux source APK, `/data/local/tmp` staged APK, and installed `base.apk` all matched SHA-256 `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107`.
- Restarted host PID 31402 loaded module version `1.7.4-preview.23 (60)` and installed request, compression, SSE, retry, and send hooks.

## Evidence Files

- Install script: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-install-20260813.sh`
- Install output: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-install-20260813.out`
- APK copied for install: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-460947.apk`
- Log capture script: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-capture-20260813.sh`
- First capture output: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-capture-20260813.out`

## Next Action

1. Ask the user to enter the affected LSPilot chat and trigger manual context compression once.
2. Capture current module logs during that attempt. The existing capture script writes to `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-capture-20260813.out`.
3. If compression leaves `SUMMARIZING`, confirm summary Markdown is not visible in chat history.
4. If it succeeds, send one later provider request and verify the request uses reduced effective context.
5. If it still times out or fails, diagnose from the new logs only; previous stuck attempts used superseded APK hashes.

## Useful Commands

```sh
cd '/mnt/sdcard/AI Workplace/lspilot-enhancer-public-ghsync/.worktrees/model-context-compression'

bash gradlew :app:buildModelCompressionCheckDex \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2 \
  -Pandroid.enableResourceOptimizations=false

bash gradlew :app:assembleRelease :app:lintRelease -x lintVitalRelease \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2 \
  -Pandroid.enableResourceOptimizations=false
```

## Do Not Skip

- Use `bash gradlew`; the shared-storage `gradlew` file may not be executable.
- Use per-command `git -c safe.directory=...` for this worktree.
- Use KernelSU through Termux `RunCommandService` for privileged Android operations; PRoot `uid=0` is not root evidence.
- Do not present install/load success as runtime acceptance. Only a fresh manual scenario against hash `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107` can close the goal.
