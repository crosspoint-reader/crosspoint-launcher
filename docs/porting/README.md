# CrossPoint Reader → CrossPoint Android port

Program docs for porting [crosspoint-reader](https://github.com/crosspoint-reader/crosspoint-reader) to
[crosspoint-launcher](https://github.com/crosspoint-reader/crosspoint-launcher).

| Doc | Purpose |
|---|---|
| [upstream.md](upstream.md) | Pinned upstream commits, sync procedure, fixture provenance |
| [parity-matrix.md](parity-matrix.md) | Feature-by-feature port status and owner of each area |
| [architecture.md](architecture.md) | Target module layout, engine approach, storage contract |

## Decisions

| Decision | Choice |
|---|---|
| Scope | Full workflow parity (reader, library, network, plugins, settings) |
| UI | CrossPoint's screens, themes, and navigation — not the Android launcher UI |
| App role | Pure CrossPoint reader app; the HOME intent, app drawer, widgets, and icon packs are removed |
| Reader engine | Kotlin reimplementation on Android's text stack; revisit native only if measured fidelity fails |
| Hardware features | Best-effort Android equivalents with documented fallbacks (see matrix) |
| Android baseline | `minSdk 29` (Android 10), `targetSdk 35` |
| Delivery | Fork `volkanturkut/crosspoint-launcher`, phased PRs against upstream `main` |

## Phases

| Phase | Theme | Exit criteria |
|---|---|---|
| 0 | Setup & parity audit | Baseline builds; fixtures and unit-test harness in CI; matrix complete |
| 1 | CrossPoint UI shell | Homescreen, themes, components, navigation, settings shell; launcher role removed |
| 2 | EPUB engine | Container/OPF/TOC/HTML/CSS/layout/pagination/positions; fixture corpus passes |
| 3 | Reader features | Menus, bookmarks, highlights, dictionary, fonts, status bar, reader settings |
| 4 | Formats & library | XTC, TXT, BMP, covers, library index/search/recent, file browser |
| 5 | Network workflows | Wi-Fi, web server, WebDAV, Calibre, OPDS, OTA, sync, stats |
| 6 | Plugins | Browser plugins, device catalogs, job queue, events, plugin hub |
| 7 | Hardware equivalents | Refresh, frontlight, tilt, BLE, keys, haptics, sleep, screenshots |
| 8 | Localization | 38 firmware languages, RTL, CJK fallback |
| 9 | Hardening & release | Low-RAM profiling, device matrix, signed release |

## Working rules

- Keep `main` buildable. Each PR is independently reviewable and maps to one matrix area.
- No new runtime dependency without a note in the PR: the app targets low-RAM e-ink hardware.
- Firmware behavior is the reference. When Android cannot match pixels, match semantics and document the gap.
- Every ported parser or format gets a test built from real firmware fixtures, not hand-written strings.
- Update `parity-matrix.md` in the same PR that changes a status.

## Verifying

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
sh scripts/check-reader.sh
```
