# Project Constraints

- Status: current
- Updated: 2026-08-13
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

- Copy an APK to a quoted Termux-visible source, then stage it as `/data/local/tmp/<task>.apk` through the proven host-side channel before `pm install -r`.
- Verify that built, staged, and installed APK hashes match. `Success` from `pm install` is insufficient evidence.
- Use `adb` only when `adb devices -l` shows an authorized transport.
- Run permission-sensitive `pm`, `cmd`, `am`, `dumpsys`, and global `logcat` operations through the proven host-side root channel.
- Recheck KernelSU, Zygisk, LSPosed, scope, daemon, and package state after reboot or user action.

## Git and Workspace Safety

- For this worktree, prefer per-command trust:

```text
git -c safe.directory='/mnt/sdcard/AI Workplace/lspilot-enhancer-public-ghsync/.worktrees/model-context-compression' <command>
```

- Preserve unrelated dirty and untracked files.
- Diagnose Git object and ref health read-only before repair.
- Require explicit user authorization before reset, checkout, ref repair, worktree deletion, or overwriting user files.

## Runtime Acceptance

- Static checks and successful builds do not prove hooks work on the device.
- Install the exact artifact, prove its installed hash, restart the target process, and collect current LSPosed/module logs.
- Let the user perform manual UI scenarios unless automated control is explicitly requested.
- For APK reverse engineering, use the connected MT2 tooling and keep the target APK read-only.
