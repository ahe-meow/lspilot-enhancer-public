# Current Findings

- Status: current
- Updated: 2026-08-13
- Purpose: preserve verified facts, unresolved runtime evidence, and implications for the next action.

## Branch State

- Worktree: `.worktrees/model-context-compression`
- Branch: `feat/model-context-compression`
- Current branch history implements the planned protocol, state, persistence, coordinator, request integration, UI projection, and static guards.
- Current local source changes cover `ManualCompressionManager.java`, `LSPilotEnhancerModule.java`, `HostAbi.java`, `AutoRetryManager.java`, and `ModelContextCompressionCheck.java`.
- Documentation has been reorganized into `docs/{architecture,design,project,reference,work}`; legacy dated planning docs and old top-level docs are deleted in this worktree.

## Runtime Blocker

- Manual compression previously froze LSPilot, followed by ANR/exit.
- ANR evidence placed the host main thread inside `requestInternalSummary(...)` while synchronously invoking the host provider stream.
- The timeout was registered only after that invocation returned, so a blocking stream prevented timeout registration.
- After the ANR fix, a 2026-08-13 manual attempt kept the host responsive but remained in `SUMMARIZING` indefinitely.
- Current LSPosed logs showed the internal request had two serialized messages and entered the ordinary request hook, which applied normal context reconstruction and `reasoning=xhigh`.
- The active task identity check required exactly one message. A provider-added system message therefore hid the exact internal summary prompt.
- Ordinary request reconstruction then replaced `latestHostMessages`; the timeout reused response-boundary validation and silently rejected its own current task.

## Local Fix

- A daemon single-thread executor named `lspilot-summary-request` now invokes the internal provider stream.
- The main-thread handler registers `ManualCompressionManager.onSummaryTimeout(...)` before dispatching the stream work.
- The internal-build guard still surrounds provider stream invocation.
- Internal summary identity now accepts provider-added system messages only when the sole non-system message is the exact active summary prompt.
- Timeout ownership now depends only on `SUMMARIZING` state plus task ID and chat ID; request reconstruction cannot invalidate the timeout.
- The shared failure path normalizes a timed-out `SUMMARIZING` task through `VALIDATING`, allowing one automatic retry and then an actionable failure state.
- Stream event recognition now supports both legacy `nyb` events and the current host `lwb` chunk/done/error events.
- Current nested chunks are read through their part list, and only text parts contribute to the internal summary; reasoning and tool parts stay internal.
- Auto retry uses the same shared event classification, and non-JSON SSE diagnostics no longer attach throwable text that can contain summary content.

## Verification Evidence

- `:app:buildModelCompressionCheckDex` passed after the fix.
- Android `dalvikvm -ea` execution of `ModelContextCompressionCheck` exited 0 with empty stdout.
- `:app:assembleRelease :app:lintRelease -x lintVitalRelease` passed with the required host build flags.
- A forced release rebuild on 2026-08-13 passed and produced SHA-256 `561f3b536821a5f7fa5246ce11ef3e6f4db5d998e1c25377b0f2fed68d8e6eb4`.
- `aapt2 dump badging` confirmed package `dev.operit.lspilot.cache`, version code 60, version `1.7.4-preview.23`, minimum SDK 26, and target SDK 29.
- Focused assertions reproduce the provider-added-system-message identity failure and a timeout after `latestHostMessages` changes; both pass after the fix.
- The release/lint gate passed after the stuck-state fix and produced SHA-256 `4d34fe83e541a0fcfa631f4f4b19b28beb7dec26c245a988097f5d95e5bde037`.
- Focused assertions now cover the current host `lwb` event structure and prove that its Done event terminates the internal summary flow.
- The focused DEX check, Android `dalvikvm -ea` assertions, release build, and lint passed for APK SHA-256 `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107`.

## Installation Evidence

- A fresh KernelSU probe on 2026-08-13 reported Android `uid=0(root)` and SELinux domain `u:r:ksu:s0`.
- The previously installed module hash was `7353e6d093e7fa7854d79eadaeb7b345237becf3c5660dc00522360146a64dc2`.
- `pm install -r` replaced it successfully. The built APK, `/data/local/tmp` staged APK, and installed `base.apk` all have SHA-256 `561f3b536821a5f7fa5246ce11ef3e6f4db5d998e1c25377b0f2fed68d8e6eb4`.
- LSPosed database state shows `dev.operit.lspilot.cache` enabled for user 0 and scoped to `me.yun.lspilot`.
- After force-stop and restart, current LSPosed logs for host PID 27868 reported the resolved minified ABI, `requestBody=true compression=true sseUsage=true`, installed compression/send hooks, and `LSPilotEnhancer loaded version=1.7.4-preview.23 (60)`.
- Manual compression behavior remains unresolved; install and load evidence must not be presented as scenario acceptance.
- The latest built, staged, and installed APKs all match SHA-256 `4d34fe83e541a0fcfa631f4f4b19b28beb7dec26c245a988097f5d95e5bde037`.
- After restart, host PID 31682 loaded the module and all compression hooks without a module startup error.
- The latest lifecycle fix still requires a fresh manual compression scenario; the previous stuck attempt ran the superseded APK.
- On 2026-08-13, KernelSU installation ran as Android `uid=0(root)` in `u:r:ksu:s0`; built, staged, and installed APKs all matched SHA-256 `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107`.
- Restarted host PID 31402 resolved the current minified ABI (`provider=yr8`, `viewModel=ab`), installed request, compression, SSE, retry, and send hooks, and loaded module version `1.7.4-preview.23 (60)`.
- The first post-install log window contained no new compression attempt and reported `chat route visible=false`; runtime acceptance still needs a fresh attempt from the affected chat.
- The handoff source of truth is `docs/work/handoff.md`; do not infer acceptance from old attempts that ran hashes `561f3b...` or `4d34fe...`.

## Diagnostic Branches

- If the host still freezes, capture a new ANR and verify which thread owns the provider stream.
- If it stays responsive but compression fails, verify terminal assistant Markdown reaches `ManualCompressionManager.onSummaryResponse(...)`.
- If validation succeeds without reduced context, verify the summary record commits and later requests call effective message reconstruction.
