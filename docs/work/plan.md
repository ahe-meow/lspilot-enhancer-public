# Current Plan

- Status: current
- Updated: 2026-08-13
- Purpose: define the ordered path from the present branch state to runtime acceptance.

## Current Phase

Waiting for a fresh manual compression run against the installed current-host stream event fix.

## Milestones

1. **Implementation complete:** protocol, state, persistence, coordinator, request reconstruction, UI projection, and integration guards are present in branch history.
2. **ANR remediation implemented:** the provider stream runs on a dedicated executor and its timeout is armed before invocation.
3. **Stuck-state remediation implemented:** provider-added system messages no longer hide internal summary requests, and a current task timeout cannot be invalidated by request-building side effects.
4. **Artifact identity complete:** release APK SHA-256 `460947023ad0afcdcd642049122393de63f2e4cd74b0ee5d199d82244b1cb107` was staged and installed; built, staged, and installed hashes match.
5. **Module load complete:** LSPilot PID 31402 was restarted and current LSPosed logs prove the module and compression hooks loaded.
6. **Manual acceptance pending:** the installed fix has not yet seen a new compression attempt; the first post-install log window only showed `chat route visible=false`.
7. **Closeout pending:** diagnose any remaining runtime failure or record acceptance and commit the final source/docs together.

## Execution Order

1. Have the user enter the affected chat and rerun manual compression with the latest installed APK while collecting current logs.
2. If the scenario fails, collect current ANR/logcat/module evidence and trace the terminal summary callback and record commit path.
3. If it passes, confirm the next provider request uses reduced effective context, rerun the release gate against the accepted source, and update the current work documents.

## Current Evidence Pointers

- Install output: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-install-20260813.out`
- Log capture script: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-capture-20260813.sh`
- First post-install capture: `/data/data/com.termux/files/usr/tmp/lspilot-stream-events-capture-20260813.out`

## Decision Points

- A hash mismatch returns the plan to artifact staging; do not test an unidentified install.
- A host freeze returns the plan to thread/stream diagnosis.
- A non-freezing failure returns the plan to callback, validation, or persistence diagnosis.
- A successful summary without reduced later context returns the plan to effective request reconstruction diagnosis.
