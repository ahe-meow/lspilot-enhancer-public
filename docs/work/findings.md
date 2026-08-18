# Current findings

- Status: current
- Updated: 2026-08-18
- Purpose: record current verified implementation and host compatibility facts.

## Tool-call incident repair — 2026-08-18

- The read-only host baseline is `me.yun.lspilot` `1.1.0 (11)`, APK SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`, MT2 workspace `enml4cuy`.
- The initial 30-message window began with four `role=tool` rows whose `toolCallId` values had no preceding assistant `toolCalls`; the first ID hashes to `ddb4f4b1a425`, exactly matching the reported `call_NMvsSJWSiSvOA8T0hs66v4Il`. The structure validator is RED and its paired control is GREEN.
- Current-host Smali confirms `va$f -> repository.b.n(chatId, 30) -> c7.a(chatId, 30)`, `va.w -> va.x -> provider`, and `zj8.i/p` serialize the invalid tool sequence into the outbound `messages`. `va.x` adds cancellation outputs for missing results but leaves orphan outputs in place. The host's `c7.b` replacement path is separate and was not modified.
- Added `ToolCallSanitizer` at the shared request-body hook. It removes orphan/duplicate tool outputs, drops assistant calls without outputs, preserves ordinary content, and never writes host state or the host database. It runs even when optional cache-policy settings are disabled so the structural safety invariant is independent of those settings.
- `ToolCallSanitizerCheck` passes on Dalvik, including a replay of the observed four-orphan/6+8+8-call window. The installed candidate logged `Tool-call context repaired changes=4` while the user sent `ping` in the original affected chat; the request returned successfully, with no filtered 400/502/upstream error. A post-request SQLite snapshot remains `integrity_check=ok` and still contains the four orphan rows, proving request-local repair.
- Stable module `1.7.5 (67)` is installed. Source APK, Termux-private stage, `/data/local/tmp`, and installed `base.apk` match SHA-256 `5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01` (679,443 bytes). Release/lint, Dalvik checks, current-host native-settings ABI, APK structure/signature, and cold-start hook checks pass.
- Final installed-artifact verification: PID 9774 loaded `1.7.5 (67)`, the original affected chat logged `Tool-call context repaired changes=12`, normal request enhancement, and completed usage (`35374` input / `742` output / `36116` total); the user received a successful response and the captured window contained no HTTP 400/502/upstream error.

## Source state

- Module identity uses `com.lspilot.enhancer`; stable `1.7.5 (67)` is installed, enabled, and scoped to `me.yun.lspilot` in LSPosed.
- Context compression was explicitly removed from production source, chat UI, settings, tests, and active documentation.
- Removed legacy preference keys and stored summary-record prefixes are cleaned from host-local module preferences during initialization; other settings are untouched.
- Retained features are Prompt Cache policy, compatible retention, usage reporting, reasoning effort, diagnostics, and settings UI. Automatic retry and its host-context writes are removed from source and the installed artifact.
- Current source and installed `v1.7.4` apply the configured `reasoning_effort` and `prompt_cache_retention=24h` fallback to every non-empty model name. Blank models remain unchanged and GPT-5.6 explicit breakpoints take precedence.
- The user accepted the locally installed `v1.7.4-preview.28` navigation and settings behavior on 2026-08-18. The exact stable `1.7.4 (66)` artifact is built, installed, runtime-verified, and published as a non-prerelease GitHub Release.
- DexKit runtime packaging was removed. Runtime discovery uses platform `dalvik.system.DexFile` to avoid colliding with the host's DexKit/JNI state.

## Repository verification — 2026-08-18

- Stable APK `app/build/outputs/apk/release/app-release.apk` is 678,659 bytes with SHA-256 `e3ba4ba5d7241c29a04923592996a67e91b8f2d90a2c1688add0880034972102`; badging reports package `com.lspilot.enhancer`, version code `66`, and version name `1.7.4`.
- JDK 17 release assembly, lint, debug test compilation, `RequestAbiCheck`, `HostUpdateDetectionCheck`, `NativeSettingsNavigationCheck`, current-host `HostNativeSettingsAbiCheck`, APK ZIP/resources/Xposed entries, v2 signature, retained/forbidden DEX markers, LSP, pi-lens, and `git diff --check` pass.
- Non-prerelease GitHub Release <https://github.com/ahe-meow/lspilot-enhancer-public/releases/tag/v1.7.4> points to tag commit `23bbcbbe476cb2685d1f1d57121af480993ee049`. Its uploaded and freshly downloaded asset `lspilot-enhancer-v1.7.4.apk` is 678,659 bytes and matches SHA-256 `e3ba4ba5d7241c29a04923592996a67e91b8f2d90a2c1688add0880034972102`.

## Historical repository verification — 2026-08-17

- Source/package migration to `com.lspilot.enhancer` is complete.
- With JDK 17 and the documented arm64 AAPT2 flags, the `1.7.4-preview.26` release build, lint, and test compilation succeeded in 33s.
- APK `app/build/outputs/apk/release/app-release.apk` is 675,895 bytes with SHA-256 `7051672f6a2335927abf3cc5bb1e4af7ec70f897bec921a9e7553fb564397e73`.
- `aapt2 dump badging` reports package `com.lspilot.enhancer`, version code `63`, and version name `1.7.4-preview.26`; ZIP integrity, required Xposed entries, APK v2 signature, signer continuity, DEX markers, `git diff --check`, pi-lens, and LSP diagnostics passed.
- `RequestAbiCheck`, `HostUpdateDetectionCheck`, `PromptCachePolicy`, and `ReasoningPolicy` passed on Dalvik; retry-only checks were removed with the feature.
- Production and release DEX scans contain no automatic retry manager, retry/stop/session/stream/repository hooks, StateFlow message replacement, repository message persistence, or retry-specific ABI descriptor fields. The request ABI cache schema is `7`.
- The corrected `v1.7.4-preview.28` candidate release build, lint, LSP, pi-lens, DEX marker, APK integrity/signature checks, `NativeSettingsNavigationCheck`, dedicated-route state checks, and exact current-host multi-DEX `HostNativeSettingsAbiCheck` pass. APK is 678,687 bytes with SHA-256 `999d40e423cdbb5f1d0127fb27c8f7ba6f0211d70e8872717bfdcda87f381f44`; badging reports version code `65`, version name `1.7.4-preview.28`. It is locally installed but uncommitted and unpublished.
- The first local `preview.28` candidate (`a6378fc3...ad4c`) was rejected after startup reproduced `Compose Runtime internal error: No nodes can be emitted before calling skipAndEndGroup`. Injecting nodes from the bottom-padding Composable was removed; the corrected build registers the module LazyColumn item before the host's fourth/final item.
- The three pi-lens `unsafe-reflection` findings were removed by replacing equivalent `Class.forName(name, false, loader)` calls with `ClassLoader.loadClass(name)`. A final pi-lens session scan reports no issues; LSP reports no diagnostics for `HostAbi.java` and `DexAbiScanner.java`.
- GitHub pre-release `v1.7.4-preview.27` is published at <https://github.com/ahe-meow/lspilot-enhancer-public/releases/tag/v1.7.4-preview.27>. The downloaded asset is 677,939 bytes and reproduces SHA-256 `24702f702ee67c6b37a5305f7f5e983f4523f7d2da18c4475c82609b16d2dff1`.

## Device runtime verification — 2026-08-18

- Local build output, Termux-private stage, `/data/local/tmp`, and installed `base.apk` match stable SHA-256 `e3ba4ba5d7241c29a04923592996a67e91b8f2d90a2c1688add0880034972102`; installed package reports `1.7.4 (66)`.
- First stable cold start rebuilt the descriptor with `reason=module_update`; the second logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, native settings hooks, and `LSPilotEnhancer loaded version=1.7.4 (66)`. Neither process log contains automatic-retry hooks, native-settings hook failure, FATAL, or Compose runtime errors.
- The read-only LSPosed database copy reports integrity `ok`, module `enabled=1`, `scope_request_blocked=0`, and the sole scope `me.yun.lspilot` for user `0`; its APK path matches the installed stable package path.

## Historical device runtime verification — 2026-08-17

- KernelSU RunCommandService reported Android `uid=0(root)` with SELinux domain `u:r:ksu:s0`.
- Local source APK, Termux-private stage, `/data/local/tmp`, and installed `base.apk` match SHA-256 `999d40e423cdbb5f1d0127fb27c8f7ba6f0211d70e8872717bfdcda87f381f44`; installed package reports version code `65` and version name `1.7.4-preview.28`.
- LSPosed state remains `enabled=1`, `scope_request_blocked=0`, scope `me.yun.lspilot`, and database integrity `ok`. Both corrected-build starts logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, native settings hooks, and version `preview.28 (65)`; no FATAL/Compose error or settings-hook failure occurred. The second start recorded successful `enabled` and `cache_key_enabled` toggle commits.
- LSPosed automatically registered the new package at the installed APK path. State is `enabled=1`, `scope_request_blocked=0`, scope is exactly `me.yun.lspilot`, and `pragma integrity_check` returned `ok`.
- The installed host remains SHA-256 `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c`.
- First cold start rebuilt the ABI descriptor with reason `module_update`; the second logged `cache_hit`, `provider=zj8`, `requestBody=true`, `sseUsage=true`, `Native host settings page hooks installed with AutoAwesome icon`, and `LSPilotEnhancer loaded version=1.7.4-preview.27 (64)`. Neither log contains automatic-retry hook installation or native-settings hook failure.
- Manual request/usage/settings scenarios remain user-driven and are not claimed from startup logs. Automatic retry acceptance is no longer applicable to current source.

## Host context audit — 2026-08-17

- Normal request enhancement modifies a newly parsed outbound JSON string, not the host message `List` or chat database directly. GPT-5.6 explicit cache mode changes the outbound JSON message-content shape while preserving text.
- Installed `v1.7.4-preview.26` contains no automatic-retry host-context write path and no longer discovers or invokes host ViewModel StateFlow, retry/session/stream methods, message accessors, or repository message methods.
- Production source contains no direct Room, SQLite, `android.database`, `ContentResolver`, chat-database file API, or indirect host repository persistence path.
- SSE `reasoning` normalization remains active independently of the request master switch.
- The module also writes its own host-private SharedPreferences, ABI descriptor cache, and optional diagnostic log; it injects a separate native settings card/page. No module-created Android overlay or dialog remains.
- Current installed all-model retention sends `prompt_cache_retention=24h` for every non-empty model request. If the upstream accepts it, external context retention expands to those models; actual provider storage is outside this repository and unverified.

## Git ownership recovery — 2026-08-17

- The user authorized per-repository ownership repair for this personal checkout. The native Gentle AI check failed because the `/storage/emulated/0` view of `.git` was owned by Android UID `10320` while PRoot reported euid `0`. Host-side `chown 0:0` changed the exact `/data/media/0/.../.git` backing directory to `0:0` with mode `2770`, but the FUSE path still presents `10320:1023`; the PRoot-native review check remains blocked, so the safe fallback is an Ubuntu-native local checkout, not a global trust bypass.

## Current host identity

- Installed package: `me.yun.lspilot`
- Version: `1.1.0 (11)`
- Installed host content SHA-256: `af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c` (version metadata remains `1.1.0 (11)` despite the content update).
- Size: `16,716,410` bytes
- Runtime staging path: `/storage/emulated/0/MT2Explorer/mcp/lspilot_installed_20260817_host_update/base.apk`
- MT2 workspace: `enml4cuy`
- The earlier installed host SHA-256 `d4eb3066c82a791f6d11bbe8a4c6a9f0d2990c8282f91a815d14d7bfc05f5d56`, size `16,700,026`, is workspace `rvlxvm8q`; staged SHA-256 `b6ea30f6...debe` remains separate compatibility evidence.
- The prior `8541...` APK and workspace `tpmuti9o` are historical evidence only.

## Verified retained ABI

- Current request/SSE: `zj8.p(cb,List,String,boolean):String` and `zj8.t(String,Function1):boolean`
- Compatibility request/SSE profiles: `xj8` for `d4eb3066...f5d56` and `vj8` for `b6ea30f6...debe`, with the same method shapes.
- Host retry/ViewModel/state/message/repository mappings below are historical compatibility evidence only; current source does not discover or hook them.
- Historical ViewModel/retry: `va.w(cb,List,Function1):void`, `va.F`, `va.P`, `va.J`, `va.Q`
- State/session: `va.b:sk7 -> oa`, `oa.e():List`, `oa.j():na`, `na.d():String`
- Message: `u7`; ID `f()`, role `i()`, content `c()`
- Repository singleton: `me.yun.lspilot.data.repository.b`; add/replace `c(String,u7)` / `r(String,List)`

- Candidate resolution enumerates coherent combinations and rejects zero or ambiguous winners.
- Known minified profiles are attempted conservatively (`vj8`, then `xj8`) before structural DEX fallback.
- Descriptor caching is schema `7`, hashes every base/split APK, and stores request/SSE ABI only.
- Structural discovery resolves a unique coherent request/SSE candidate and rejects zero or ambiguous matches.
- Historical predecessor-package runtime evidence (2026-08-17): host update adaptation passed Java 17 compilation, D8, Dalvik (`HostUpdateDetectionCheck: PASS`, `LegacyContextRepairCheck` exit 0), release assembly, `git diff --check`, APK ZIP/signature/DEX-marker checks, and private hash-verified install. The recorded module hash and subsequent startup logs are historical and do not establish installation of `com.lspilot.enhancer`.
- 2026-08-16: With the documented AArch64 override `-Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2 -Pandroid.enableResourceOptimizations=false`, `:app:assembleRelease` and `:app:lintRelease -x lintVitalRelease` pass. No source or lint errors were reported.
- The release classes.dex contains no active deleted-feature classes, compression UI/resource labels, or compression-related manifest entries. It intentionally retains only the old preference/record identifiers `context_compression_enabled`, `lspilot.summary.record.v1./v2.`, and `purgeRemovedCompressionSettings` for narrow one-time cleanup; these are not exposed settings or active compression behavior.
- Historical predecessor artifact (2026-08-16): the valid private module APK is `/storage/emulated/0/MT2Explorer/mcp/lspilot-cache-adaptive-20260816-fixed.apk`, size `702,264` bytes, SHA-256 `7bc3a22f6a43ef98cd843922c89a0b57ced474cc26583fdca0e4f2089d8c44e8`. `aapt2 dump badging`, `unzip -t`, and APK v2/v3 signature verification passed. It contains `AndroidManifest.xml`, `resources.arsc`, and the new `classes.dex`.
- Historical predecessor build-tool evidence (2026-08-16): the direct AGP output after the AArch64 AAPT2 override was rejected: it repeatedly produced a 14-entry ZIP without `AndroidManifest.xml` or `resources.arsc`, despite `BUILD SUCCESSFUL`. The private workaround reused only the previously valid module container's manifest/resources, replaced `classes.dex`, then ran arm64 `zipalign` and `apksigner`; no APK or signing material was committed.
- Historical predecessor install evidence (2026-08-16): the fixed module was installed through KernelSU RunCommandService. Source, shared staging, `/data/local/tmp`, and installed `base.apk` all matched `7bc3a22f6a43ef98cd843922c89a0b57ced474cc26583fdca0e4f2089d8c44e8`. LSPosed DB integrity was `ok`, module state was enabled, and scope included `me.yun.lspilot`.
- Historical predecessor runtime startup evidence (2026-08-16): startup on installed host `d4eb3066...f5d56` succeeded: `Resolved LSPilot host ABI minified=true provider=xj8 config=cb viewModel=va`; stream/retry, cache request (`xj8#p`), raw SSE (`xj8#t`), load-session, and auto-retry hooks all installed; `Startup hook probe: minified=true requestBody=true sseUsage=true`. The previous `state candidate count=0` and `Unavailable setting persistence failed` errors did not recur.
- 2026-08-16: Manual request/usage/reasoning/retry acceptance remains user-driven and is not claimed from startup logs alone.
- Module builds and repository work run in Ubuntu PRoot with JDK 17.
- Privileged package, LSPosed, and global log operations use Termux RunCommandService with KernelSU and hash-verified private staging.
- Engram remained unavailable at `http://127.0.0.1:7437`; the user's Simplified Chinese preference could not be persisted there, but is active for this session.

## Endpoint error diagnosis — 2026-08-16

- Latest module log capture: `/data/data/com.termux/files/usr/tmp/lspilot-endpoint-narrow-root.out`; host debug capture: `/data/data/com.termux/files/usr/tmp/lspilot-debuglog-current-root.out`.
- The module hook is not failing locally. At 20:49–20:55 it enhanced repeated requests with `model=gpt-5.6-sol`, `reasoning=xhigh`, `explicitBreakpoints=4`, `retention=false`, and `usage=true`; there was no request-enhancement exception.
- No subsequent raw SSE usage/completion event or module-side HTTP error body was recorded after those requests. The failure therefore occurs upstream or in the host's endpoint response handling; the current logs do not expose the provider's response JSON.
- Current host-local module preferences contain `reasoning_effort=xhigh`; other policy booleans are absent and therefore default enabled.
- The request policy currently applies Chat Completions fields to every endpoint: `reasoning_effort` and `stream_options.include_usage`. Responses requests require endpoint-specific reasoning/usage handling. The Completions path also receives GPT-5.6 explicit cache markers, which a non-OpenAI gateway may reject or translate into `502`.
- Ranked next probe: temporarily use reasoning effort `high`; retry both endpoints. If both still fail, disable cache-key/prompt-cache mutation and retry. This separates the reasoning field from cache-field compatibility without modifying the host APK or database.

## Legacy conversation repair — 2026-08-16

- Live host database integrity remains `ok`; the host database was not modified.
- The affected legacy chat is titled `0813` (`d81826d8-9a3c-4220-a5b4-f46164446c19`). Failed compression left 11 `[系统提示 · 上下文压缩]` rows and a truncated request-history boundary that began with orphan `tool` messages.
- Removing only the 11 compression-status rows still returned HTTP 403. Removing those rows plus two orphan `tool` messages also still returned HTTP 403.
- The successful control additionally removed the damaged historical `assistant.tool_calls` / `tool` blocks while preserving plain assistant text and all user messages. The real request logged `removed messages=24 compression=11 orphanTools=2 toolBlocks=11`, then returned `pong` from `https://pasw.shop/v1/chat/completions`.
- Provider usage confirmed success: `input_tokens=39856`, `output_tokens=34`, `total_tokens=39890`. This isolates the failure to the legacy tool-call history produced around the failed compression boundary, not the endpoint, API key, model, reasoning effort, or cache fields.
- The historical predecessor repair was request-local and did not rewrite the host database. It is not included in `1.7.4-preview.24`.
- Historical predecessor release artifact (2026-08-16): the installed release APK is `app/build/outputs/apk/release/app-release.apk`, size `697,819` bytes, SHA-256 `e25102ded11e50e20c0b26f59cd1ad9bf16ceee19cbd24af67eeb41e276c589a`; badging, ZIP integrity, APK signature, DEX-string, staging-hash, and installed-hash checks passed.

## Manual settings inspection — 2026-08-17

- Before the dedicated-route fix, the user confirmed that both `关于` and `模型请求增强` opened the module settings surface; alternating the entries after back navigation reproduced the shared-destination bug.
- Root cause of the failed `max` selection: the host `OverlayDropdownPreference` builds each option click with its second `Function1` parameter (method parameter 16), while the module had passed its persistence callback as parameter 15. The corrected candidate passes the callback as parameter 16, verifies both callback ABI slots, and shows the selected value in the row summary.
- The module settings route now uses a dedicated host `eca$m` sentinel instance (`__lspilot_enhancer_settings__`) intercepted at `vub.k`; normal About (`eca$a`) and real LogViewer routes proceed untouched. The route identity and one-shot About-to-sentinel rewrite pass current-host Dalvik/ABI checks. The dedicated-route candidate starts cleanly with request/SSE/settings hooks installed and no FATAL or Compose error.
- Current live values are: `enabled=true`, `reasoning_effort=xhigh`, `cache_key_enabled=true`, `retention_enabled=false`, `include_usage_enabled=false`, `debug_log_enabled=false`, and `verbose_debug_log_enabled=false`. Internal state is `hook_success_notice_v2=true` and `settings_host_migrated_v1=true`.

- The newest database chat titled `新对话` contains only 4 rows (`ping`, `pong`, `你是什么模型？`, and the answer), totaling 132 content bytes. Therefore its roughly 30K provider input tokens are not 30K tokens of persisted chat history.
- Host DEX inspection confirms `xj8.p` constructs `model`, `messages`, `stream`, `tools`, `tool_choice`, and `stream_options`; `xj8.o` prepends the host-provided system prompt as a `role=system` message and converts the current chat list into request messages.
- The 30K is consequently dominated by the host-generated system prompt plus tool/function definitions and request JSON overhead. The raw system prompt is not stored in `chat_message` and was not logged, so its exact text cannot be reconstructed from the database without a separate host-side request capture.
