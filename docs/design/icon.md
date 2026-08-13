# Application Icon

- Status: current
- Updated: 2026-08-12
- Purpose: record the visual rationale and source locations for the module icon.

## Concept

- The geometric `LSP` wordmark preserves immediate association with the host.
- A green `+` badge distinguishes the enhancement module.
- The short underline retains the visual language of a terminal and system tool.
- The wordmark is reduced inside the adaptive safe zone so it survives circular and rounded launcher masks.

## Palette

| Use | Value |
| --- | --- |
| Gradient start | `#1037F4` |
| Gradient middle | `#087CF2` |
| Gradient end | `#05C8E7` |
| Wordmark | `#C3E0FF` |
| Status badge | `#34C759` |
| Badge foreground | `#FFFFFF` |

## Resources

- `app/src/main/res/drawable/ic_launcher_background.xml`: electric-blue to cyan background.
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: inset wordmark and badge.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml`: Android 8+ adaptive icons.
- `app/src/main/res/mipmap-anydpi/ic_launcher*.xml`: compatibility resources.
- `docs/assets/icon-source.svg`: editable 1024 x 1024 source.
