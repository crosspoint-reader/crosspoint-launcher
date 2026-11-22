# Target architecture

## Modules

The current app is a single Gradle module. The port adds modules only where they pay for themselves
on build times and test isolation. Target layout:

```
:app        application, MainActivity, services, DI wiring, resources
:core       models, settings persistence, storage, logging, i18n, utilities
:epub       container, OPF, TOC, HTML, CSS, layout, positions, cache
:formats    XTC/XTCH, TXT, BMP, cover generation
:reader     reader surface, selection, highlights, dictionary, reader menus
:library    scanner, index, recent books, cover cache, file browser
:network    HTTP/WebSocket/WebDAV server, Calibre, OPDS, Wi-Fi, OTA, sync
:plugins    plugin host, job queue, events
:ui         CrossPoint design system, themes, screens, navigation
:hardware   refresh, frontlight, tilt, BLE, keys, haptics, sleep, screenshots
```

Phase 1 keeps everything in `:app`; modules are extracted as each area stabilizes, so no phase is
blocked on Gradle restructuring.

## UI approach

- **Compose** for screens, lists, dialogs, and settings.
- **Custom `View` + Canvas** for the reader surface: pagination, selection handles, and image layout
  need pixel-level control over `StaticLayout`.
- **Reference layout:** 480×800 at 219 dpi (the S4 the launcher targets), scaled with the firmware's
  `UIScale` rules. Line heights and margins come from the firmware's settings model, not Material.
- **1-bit design language:** black/white palette, hairline rules, block icons, no elevation or ripples.
  Themes: Classic, Lyra, Lyra Extended, RoundedRaff, Cover Grid.
- **Navigation:** Home → Library / Browse Files / File Transfer / Settings / OPDS, with the firmware's
  back behavior and hold gestures. No Android app drawer.

## Reader engine

Pipeline ported from `lib/Epub`:

1. `EpubBook`-style container → OPF → spine, one section at a time.
2. HTML tokenizer producing text runs plus a style stack (`ChapterBuilder` today).
3. CSS cascade (`CssParser` → `ReaderCss`): selectors, specificity, inheritance, box model,
   `display:none`, page breaks, drop caps, tables, images.
4. Line breaking through `StaticLayout`, wrapped with a compatibility layer for:
   hyphenation (Liang patterns from `lib/Epub/hyphenation`), justification, kerning/ligatures,
   ruby, RTL/bidi (`lib/MiniBidi`), CJK breaks, supsub, and decorations.
5. Pagination into pages, then position mapping (`EpubPosition`, KOReader-compatible XPath).
6. Cache: `.crosspoint/epub_<hash>/` (`book.bin`, `section/*.bin`, `progress.bin`) so Android and
   firmware can share a card.

The firmware's `test/epubs` corpus is the acceptance suite. `test/` ports in `lib/Epub` define the
behavioral expectations; port the relevant tests alongside each subsystem.

## Storage contract

Prefer the firmware's on-card layout so a card works in both a reader and a phone:

```
.crosspoint/
├── epub_<hash>/{book.bin, progress.bin, cover.bmp, sections/*.bin}
├── clippings/
├── settings.json
├── state.json
└── recent.json
```

- Binary formats follow `docs/file-formats.md` (versions noted there).
- When shared storage is unavailable, mirror the layout under app-private storage and migrate on
  permission grant.
- Never write a format version the app cannot read back; bump versions together with the parser.

## Hardware abstraction

Each firmware HAL maps to a Kotlin interface with a default no-op and vendor-specific implementations:

| Firmware | Android |
|---|---|
| `HalDisplay` waveforms, refresh cadence | `ScreenRefresh` (best effort; no true waveform control) |
| `HalFrontlight` | system brightness / vendor SDK where exposed |
| `HalTiltSensor` | `SensorManager` accelerometer |
| `BlePageTurner` | BLE HID client over `BluetoothGatt` |
| `HalGPIO` buttons | volume/media keys, home key where the device reports it |
| `HalHaptics` | `Vibrator` |
| Sleep/wake activities | sleep timer + `DreamService`, `KeyguardManager` |
| Screenshots | `PixelCopy` / media projection |
| USB drive mode | document provider / MTP where the OS permits |

Unavailable features must render a clear "not available on this device" row instead of disappearing.

## Testing

| Layer | Tool |
|---|---|
| JVM unit tests | JUnit 4 (container, OPF, CSS, positions, index, endpoints) |
| Fixtures | firmware `test/epubs` in `app/src/test/resources` |
| Instrumentation | existing framework-free `IntegrationChecks` suite |
| UI checks | existing compact-layout instrumentation checks |
| Golden text/layout | recorded expectations from firmware tests, compared as text, not pixels |
