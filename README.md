# LSPilot Enhancer

An API 102 LSPosed module for `me.yun.lspilot`. It adapts the host reasoning controls without modifying or repackaging the host APK.

> Use this module only on devices and applications you are authorized to inspect and modify.

## What it does

- Relabels the host reasoning menu and current-value button with:

  ```text
  off · low · medium · high · xhigh · max
  ```

- Keeps the host menu and its persisted enum value authoritative.
- Reads the host reasoning value for every supported request.
- Maps the host values as follows:

  | Host value | Display and request policy |
  | --- | --- |
  | `OFF` | `off` |
  | `AUTO` | `low` |
  | `LOW` | `medium` |
  | `MEDIUM` | `high` |
  | `HIGH` | `xhigh` |
  | `MAX` | `max` |

- Preserves host messages, provider context state, `max_tokens`, unknown JSON fields, and provider credentials.
- Fails closed when required host ABI discovery is missing or ambiguous. Hook and reflection failures preserve the original host value or request.

The removed context-length feature, module settings UI, Remote Preferences policy store, synchronization listener, and service dependency are intentionally not part of this version.

## Compatibility

| Component | Requirement |
| --- | --- |
| Host package | `me.yun.lspilot` |
| Tested host | Version `1.1.1`, version code `12` (current verified runtime sample) |
| Newer-host compatibility | Not empirically verified; another host APK/runtime sample is required |
| Hook API | libxposed API `102` |
| Compile SDK | Android `37` |
| Target SDK | Android `29` |
| Minimum SDK | Android `26` |
| Java | Source/target compatibility `8` |
| ABI discovery | DexKit `2.2.0` |

### Adaptive discovery

Compatibility discovery uses SHA-256 content fingerprinting across the primary APK (`sourceDir`) and every non-null split APK (`splitSourceDirs`). The in-process cache key is the exact `ClassLoader` identity plus the combined content fingerprint, and hot reload clears that cache. A content change triggers a fresh scan even when `versionCode` and `versionName` stay the same; neither version field is a production compatibility gate.

The module uses structural DexKit discovery for enum, request, and menu capabilities. Candidate metadata, reflected signatures, required JSON literals, invoke relationships, caller relationships, and uniqueness are validated before hooks are installed. Repeated caller records for the same reflected method are treated as duplicate evidence, while distinct ambiguous callers remain rejected. Missing, ambiguous, malformed, or unsupported evidence produces a fail closed result for the affected capability; dependent request capabilities remain disabled when their reasoning prerequisite is unavailable, while unrelated capability attempts remain independent.

Diagnostics are privacy-safe and allowlisted: capability and candidate events, cache state, a validated SHA-256 fingerprint, and exception class names only. APK paths and bytes, request bodies, message contents, credentials, and persistent ABI or policy state are not logged or stored.

The `1.1.1` / version code `12` host sample is the current verified v12 runtime acceptance boundary recorded for this project. Newer-host compatibility is not empirically verified here and requires another host APK/runtime sample; JVM structural and content-change checks do not replace that evidence.

The module is scoped to the host package and has no Activity or Service entry point.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37
- Android Build Tools

The API 102 AAR is included as the compile-only dependency at `lib/libxposed-api-102.0.0.aar`.

Set the SDK variables for your environment, then run:

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

bash ./gradlew :app:testDebugUnitTest --no-daemon --console=plain
bash ./gradlew :app:assembleRelease --no-daemon --console=plain -x lintVitalRelease
```

`targetSdk = 29` is intentional for host compatibility. Android lint may report `ExpiredTargetSdkVersion`; this project does not raise the target SDK to silence that check.

The release build currently produces `app-release-unsigned.apk`. Sign it with a release key that you control before distributing it.

## Installation

1. Build or obtain the APK.
2. Install the module on an authorized Android device.
3. Enable it in an LSPosed environment that supports API 102.
4. Scope it to `me.yun.lspilot`.
5. Fully restart the host process.
6. Open the host reasoning menu and verify the six lowercase labels.

The current `v1.10.0` GitHub Release is available at:

<https://github.com/ahe-meow/lspilot-enhancer-public/releases/tag/v1.10.0>

Its uploaded `app-release-unsigned.apk` is 2,386,612 bytes with SHA-256 `d5cce4f5ed2106c99e1d5f8f3cef50e7f085c99b08eb31d8bb7a7b414932d86e`. It is intentionally unsigned because no public signing key is stored in this repository.

## Safety boundaries

- The host APK is read-only compatibility evidence; this module does not patch or repackage it.
- Host persistence remains owned by the host menu.
- Request changes are limited to supported reasoning fields.
- Diagnostics do not intentionally log request bodies, message contents, credentials, or signing material.
- Keep keystores, passwords, local APKs, logs, reverse-engineering outputs, and build directories out of Git.

## Project layout

```text
app/src/main/java/                  Module source
app/src/main/resources/META-INF/   LSPosed metadata
app/src/test/java/                  Unit and ABI contract checks
gradle/                             Gradle wrapper files
lib/                                Compile-only API 102 AAR
```

## Release verification

The current v12 host/device/LSPosed acceptance is recorded for the `1.1.1` / version code `12` sample. Local verification of the adaptive implementation includes JVM tests, source/static checks, manifest checks, and Debug/Release assembly. Release assembly requires `-x lintVitalRelease` because the required `targetSdk = 29` triggers the existing `ExpiredTargetSdkVersion` lint error. No newer-host runtime acceptance is claimed.
