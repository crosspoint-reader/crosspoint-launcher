# Upstream pinning

The port tracks the firmware source, not its releases. Releases lag `develop` and the reader engine
changes constantly.

## Pinned commits

| Repository | Branch | Commit | Date | Notes |
|---|---|---|---|---|
| `crosspoint-reader/crosspoint-reader` | `develop` | `177d0694013feb89e6ae93e16569df28ac819746` | 2026-11-22 | Source of truth for engine behavior, settings, strings, and UI |
| `crosspoint-reader/crosspoint-launcher` | `main` | `6dc20dbf4a03c7d4787f1afa01d06598aee9aaf2` | 2026-10-30 | Port baseline (v1.5.4) |

Update this table whenever the reference commit moves, and say why in the PR.

## What is copied from the firmware

| Asset | Source | Destination | Notes |
|---|---|---|---|
| EPUB fixtures | `test/epubs/*.epub` | `app/src/test/resources/epubs/` | Verbatim, universe-test corpus |
| UI translations | `lib/I18n/translations/*.yaml` | `app/src/main/assets/i18n/` (Phase 8) | Converted to an Android-friendly format |
| Web UI | `src/network/html/**` | `app/src/main/assets/webui/` (Phase 5) | HTML/CSS/JS served by the Android web server |
| Built-in fonts | `lib/EpdFont/builtinFonts` + `.cpfont` toolchain | Android `res/font` / assets (Phase 3) | Licensing recorded in `app/src/main/assets/licenses/` |
| Icons | `src/components/icons/**` | Compose vectors / drawables (Phase 1) | 1-bit source art is the design reference |

Do not copy C++ sources into the Android tree. They are reference material only.

## Sync procedure

```sh
git clone --depth 1 --branch develop https://github.com/crosspoint-reader/crosspoint-reader.git
cd crosspoint-reader
git rev-parse HEAD
```

1. Diff the new commit against the pinned one, grouped by porting area (`src/activities/reader`,
   `lib/Epub`, `src/network`, `lib/I18n`, `src/CrossPointSettings.*`).
2. For each area with changes, open a matrix row update or a follow-up task.
3. Refresh fixtures if `test/epubs` changed, and update `EpubFixturesTest`'s expected count.
4. Record the new commit here.

## License

Both projects are MIT.

- Firmware: `Copyright (c) 2025 CrossPoint Reader organization`
- Launcher: `Copyright (c) 2026 Justin Mitchell`

The Android app keeps the launcher's license header and credits the firmware project in About.
