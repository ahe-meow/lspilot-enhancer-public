# Current Goal

- Status: current
- Updated: 2026-08-13
- Purpose: define the single active outcome and its acceptance boundary.

## Outcome

Deliver model-driven context compression for LSPilot that reduces provider context without changing visible host chat history, blocking the Android main thread, exposing internal summary content, or sending unsafe fallback requests.

## In Scope

- Manual and automatic compression through one request-layer state machine.
- Internal summary requests using the active provider configuration.
- Validated, session-persisted summary baselines for later requests.
- Safe handling of pending user messages and tool-call boundaries.
- Device installation and manual runtime acceptance of the current-host stream event fix.

## Done When

- The exact built module APK is installed and its built, staged, and installed SHA-256 values match.
- `me.yun.lspilot` loads the module and required hooks without disabling compression.
- Manual compression no longer freezes or ANRs the host.
- Compression reaches a visible success or actionable failure/cancel state.
- Summary content stays out of host chat history, visible history remains unchanged, and a successful later provider request uses reduced effective context.
- Focused checks, release build, lint, and device-side assertions pass for the accepted source.

## Current Boundary

The latest APK is already installed and loaded. Completion now depends on a fresh manual compression attempt from the affected chat proving that the installed fix leaves `SUMMARIZING` and either succeeds or reaches an actionable failure state.
