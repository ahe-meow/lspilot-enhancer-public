# Project Constraints

- Status: current
- Updated: 2026-08-17
- Purpose: preserve the long-term environment, safety, build, and verification rules for this Android module.

## Artifact Boundary

- This repository builds an LSPosed module for host package `me.yun.lspilot`; read the current module package from `app/build.gradle.kts`.
- Modify, build, sign, and install only the module APK.
- Treat the host APK as read-only compatibility evidence. Do not patch, rebuild, sign, or install a modified host APK unless the user explicitly changes scope.
- Keep credentials, logs, APKs, SDKs, device snapshots, and signing material out of Git.

## Host Environment

- The expected stack is Android arm64, Termux, then `proot-distro` Ubuntu.
- Ubuntu `/mnt/sdcard` maps to Android `/storage/emulated/0`.
- Quote complete paths at every shell boundary because workspace paths contain spaces.
- Stage Android command inputs under a no-space host path such as `/data/local/tmp/`.
- PRoot filesystem visibility and a reported Linux `uid=0` do not prove Android root.
- Do not treat this as a normal physical-machine Ubuntu: no `sudo`/systemd assumptions, no direct private-app or LSPosed reads from PRoot, and no implicit Android root from `/root`.
- For host DB, LSPosed config, and global log capture, run a Termux-side root script via `RunCommandService`, copy complete DB sidecars when WAL is active, and analyze the copied files from Ubuntu only after capture.

## Execution Routing

Pi runs in Ubuntu PRoot inside Termux on Android arm64. Choose the execution layer before running a command:

| Operation | Execution layer |
| --- | --- |
| Repository inspection, source edits, JDK, Gradle, and Pi | Ubuntu PRoot |
| Non-privileged Android reads | An available Termux bridge command on Ubuntu's `PATH` |
| Privileged Android package, process, and global log operations | Termux RunCommandService with KernelSU |
| Files passed to Android shell commands | A quoted shared-storage source, then `/data/local/tmp/` when needed |

- Run the first command in the selected layer. Switch layers only when its error demonstrates a layer or permission mismatch.
- Ubuntu `uid=0` is PRoot-local. `/proc/self/status` exposes the real Android app UID and is more informative than `id` for the host boundary.
- Termux binaries visible on Ubuntu's `PATH` are bridge commands, not proof that the process is an Android shell or has Android root.

## Exploration Budget

- Read this baseline before probing the environment. Probe only a capability required by the current task.
- Check command availability once with `command -v`, then cache the result for the task. At the 2026-08-13 baseline, `am`, `pm`, `cmd`, and `logcat` are available Termux bridge commands; `gh`, `adb`, and `dumpsys` are absent from Ubuntu's `PATH`.
- Verify an uncertain capability with one minimal, non-destructive command and a timeout. Use the error to select one documented alternate; do not repeat equivalent probes through path aliases or nested shells.
- Do not install tools merely to continue discovery. Install only when the requested work requires the tool and no existing route covers it.
- Record changed durable facts here so later agents reuse evidence instead of rediscovering it.

## Android Root

Run privileged Android commands outside PRoot through Termux `RunCommandService` and KernelSU:

```text
am startservice --user 0 \
  -n com.termux/.app.RunCommandService \
  -a com.termux.RUN_COMMAND \
  --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/su \
  --esa com.termux.RUN_COMMAND_ARGUMENTS '-c,<android-shell-command>' \
  --es com.termux.RUN_COMMAND_WORKDIR /data/data/com.termux/files/home \
  --ez com.termux.RUN_COMMAND_BACKGROUND true
```

- `~/.termux/termux.properties` must contain `allow-external-apps=true`; reload settings after changing it.
- Capture host-side output in a unique file under `/data/data/com.termux/files/usr/tmp/` and read it from Ubuntu.
- Accept root only when host-side evidence reports Android `uid=0(root)` or `Uid: 0` and an appropriate SELinux domain such as `u:r:ksu:s0`.
- Prefer a short staged script for complex host commands to avoid nested quoting errors.

## Build

- Use JDK 17.
- Use installed arm64 Android SDK tools. Downloaded x86_64 build tools can fail with misleading loader or AAPT2 errors.
- On this host, run AGP builds with:

```text
-Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
-Pandroid.enableResourceOptimizations=false
```

- Do not add permanent project configuration solely to hide a host-specific tool mismatch.
- Verify APK structure with `aapt2 dump badging`, ZIP inspection when relevant, and SHA-256.

## Device Operations

- Copy an APK from the PRoot build output to a unique Termux-private path under `/data/data/com.termux/files/usr/tmp/`, verify both hashes there, then stage it as `/data/local/tmp/<task>.apk` through the proven host-side root channel before `pm install -r`.
- Do not let a root install script read the shared-storage build path directly. On this device, the Android shell observed stale bytes at `/sdcard/...` after PRoot produced a newer APK at the corresponding `/mnt/sdcard/...` path.
- Make install scripts fail closed on an expected SHA-256 before and after every boundary. Verify that PRoot build, Termux-private copy, `/data/local/tmp` stage, and installed `base.apk` hashes match; `Success` from `pm install` is insufficient evidence.
- Use `adb` only when `adb devices -l` shows an authorized transport.
- Run permission-sensitive `pm`, `cmd`, `am`, `dumpsys`, and global `logcat` operations through the proven host-side root channel.
- Recheck KernelSU, Zygisk, LSPosed, scope, daemon, and package state after reboot or user action.

## Git and Workspace Safety

- For repositories on shared storage, use the exact repository path printed by Git's `dubious ownership` error. PRoot may expose the checkout as `/storage/emulated/0/...` while Git identifies it as `/mnt/sdcard/...`; `pwd` and `readlink` do not reliably choose the value Git accepts.
- Retry the failed Git command once with per-command trust. For the current main checkout:

```text
git -c safe.directory='/mnt/sdcard/AI Workplace/lspilot-enhancer-public-ghsync' <command>
```

- This is a personal repository even though it lives under Android shared storage. Per-repository ownership/permission repair via `chown` or `chmod` is allowed only with explicit user authorization, only for the exact repository path, and, when actual Android ownership must change, through the appropriate host-side privileged route. Inspect owner and mode before and after; if `/storage/emulated/0` presents a FUSE-mapped owner that still differs from the repaired backing path, use an Ubuntu-native local checkout instead of claiming the PRoot review path is fixed. Never alter parent/shared-storage ownership, global `safe.directory`, or unrelated files.
- Preserve unrelated dirty and untracked files.
- Diagnose Git object and ref health read-only before repair.
- Require explicit user authorization before reset, checkout, ref repair, worktree deletion, or overwriting user files.

## Runtime Acceptance

- Static checks and successful builds do not prove hooks work on the device.
- Install the exact artifact, prove its installed hash, restart the target process, and collect current LSPosed/module logs.
- Let the user perform manual UI scenarios unless automated control is explicitly requested.
- For APK reverse engineering, use the connected MT2 tooling and keep the target APK read-only.
