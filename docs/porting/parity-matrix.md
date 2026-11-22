# Parity matrix

Single source of truth for "is it ported?". Every PR that changes behavior updates the affected row.

**Status:** ✅ ported · 🟡 partial · ⬜ not started · 🔁 Android equivalent · ⛔ not applicable

**Baseline:** launcher `main@6dc20db` (v1.5.4) · firmware `develop@177d069` (v2.0.0)

## 1. App shell & navigation

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| Home screen with menu entries | `activities/home/HomeActivity.cpp` | `ui/home` CrossPoint home | ⬜ |
| Cover-grid home | `components/CoverGridHomeUi.cpp` | Compose cover grid | ⬜ |
| Themes: Classic, Lyra, Lyra Extended, RoundedRaff | `components/themes/**` | `ui/theme` design system | 🟡 (launcher themes only) |
| UI scale rules | `components/UIScale.h` | dp scaling from 480×800 reference | ⬜ |
| Activity stack, back behavior, hold gestures | `activities/ActivityManager.cpp` | Compose navigation + back handler | ⬜ |
| Global tap/hold gestures | `activities/UiListActivity.cpp` | shared gesture layer | ⬜ |
| Status header (battery, clock, Wi-Fi) | `components/HeaderBackTapTarget.h` | Compose header | 🟡 |
| Crash screen | `activities/home/CrashActivity.cpp` | crash handler screen | ⬜ |
| Boot/sleep screens | `activities/boot_sleep/**` | splash + DreamService | ⬜ |
| Android launcher role (HOME, drawer, widgets, pins, icon packs) | — | **remove** | ⬜ (to delete) |

## 2. EPUB engine

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| Container → OPF → spine | `lib/Epub/Epub.cpp`, `parsers/ContainerParser.cpp`, `ContentOpfParser.cpp` | `EpubBook` | 🟡 |
| EPUB 3 nav + EPUB 2 NCX TOC | `parsers/TocNavParser.cpp`, `TocNcxParser.cpp` | `EpubBook.parseNav/parseNcx` | ✅ |
| Encrypted/DRM detection | `parsers/EncryptionManifestProbe.h`, `ContentProtection.cpp` | `EpubBook` encryption probe | ✅ |
| Chapter size limits & streaming | `Epub/` section loading | 1 MiB cap today | 🟡 |
| HTML tokenizer → blocks | `parsers/ChapterHtmlSlimParser.cpp` | `ChapterBuilder` | 🟡 |
| HTML entities | `Epub/htmlEntities.cpp` | parser entities | 🟡 |
| CSS parser & cascade | `Epub/css/CssParser.cpp`, `CssStyle.h` | `ReaderCss` | 🟡 |
| `display:none`, hidden elements | `test_display_none`, `test_hidden` | `ChapterBuilder` | ⬜ verify |
| Tables (adaptive columns, spans) | `test_tables`, `Epub/blocks/TextBlock.cpp` | `ChapterBuilder.Table` | 🟡 |
| Floats / wrapping images | `ChapterHtmlSlimParser.cpp` | — | ⬜ |
| Images: JPEG/PNG/GIF/WebP, sizing | `Epub/blocks/ImageBlock.cpp`, `converters/**` | `ReaderText.image` | 🟡 (JPEG/PNG only) |
| SVG images | converters | Android `VectorDrawable`/`Picture` | ⬜ |
| MathML | — | — | ⛔ (firmware has none) |
| Ruby annotations | `ParsedText.cpp` CJK ruby | `RubySpan` | ⬜ |
| Hyphenation (10 languages, Liang) | `Epub/hyphenation/**` | `Layout` hyphenation + patterns | 🟡 (system) |
| Justification & line breaking | `Epub/ParsedText.cpp` | `LineBreaker` | 🟡 |
| Kerning/ligatures | `test_kerning_ligature`, `lib/OtShaper` | Android shaping | 🟡 |
| RTL/bidi | `lib/MiniBidi` | Android bidi + `BidiFormatter` | ⬜ verify |
| CJK line breaking | `Epub/CjkBreaks.cpp`, `test/korean_line_breaking` | Android CJK breaks | ⬜ verify |
| Superscript/subscript | `test_supsub` | `SuperscriptSpan` | 🟡 |
| Text decorations, emphasis | `test_text_decorations` | spans | 🟡 |
| Section breaks (`<br>`, `hr`) | `test_br_section_break` | `ChapterBuilder` | 🟡 |
| Foot/margin notes & links | `FootnoteEntry.h`, `PageLink.h` | `ReaderText.footnote`, panels | 🟡 |
| Page layout & pagination | `Epub/Page.cpp`, `Section.cpp` | `ReaderActivity.paginate` | 🟡 |
| Position mapping (XPath) | `lib/KOReaderSync/ChapterXPathResolver.cpp` | `EpubPosition` | ✅ |
| Reading cache (`book.bin`, `section.bin`) | `Epub/BookMetadataCache.cpp` | `.crosspoint` writer | ⬜ |
| Large-chapter handling | `fix/handle-crashes-on-very-large-epub-chapters` | streaming sections | ⬜ |

## 3. Reader

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| Chapter selection | `EpubReaderChapterSelectionActivity.cpp` | `ReaderPanels.Contents` | ✅ |
| Bookmarks | `EpubReaderBookmarksActivity.cpp` | `ReaderActivity.bookmarks` | 🟡 (prefs JSON, not `.crosspoint`) |
| Highlights & clippings | `ClipSelectionActivity.cpp`, `ClippingStore.cpp` | `ReaderClippings` | 🟡 (JSON, no firmware binary) |
| Clipping sync | `clippings/ClippingSync.cpp` | `SyncRepository` clippings | 🟡 |
| Dictionary lookup (StarDict) | `util/Dictionary.cpp`, `DictZip.cpp` | `StarDict` | 🟡 |
| Dictionary picker & multiple dicts | `DictionaryRegistry.cpp` | `StarDict.discover` | 🟡 |
| Definition screen | `DictionaryDefinitionActivity.cpp` | `ReaderPanels.Definition` | 🟡 |
| Vocabulary history | `vocabulary/VocabularyStore.cpp` | — | ⬜ |
| Word selection UI (side buttons / touch) | `DictionaryWordSelectActivity.cpp` | long-press selection | 🟡 |
| Reader menu (list/tab styles) | `EpubReaderMenuActivity.cpp` | `ReaderPanels` | 🟡 |
| Toolbar/status bar customization | `ReaderToolbarUi.cpp`, `StatusBarSettingsActivity.cpp` | `ReaderStatusBar` | 🟡 |
| Text settings (14 options) | `TextSettingsActivity.cpp` | `ReaderSettings.TEXT` | 🟡 |
| Text settings preview | `TextSettingsPreview.cpp` | live preview panel | ⬜ |
| Font family & size, SD fonts | `FontDownloadActivity.cpp`, `lib/EpdFont` | `ReaderSettings.deviceFonts` | 🟡 (TTF/OTF only, no `.cpfont`) |
| Font install/manage | `FontInstaller.cpp` | font import panel | 🟡 |
| Go to percent / page | `EpubReaderPercentSelectionActivity.cpp` | `ReaderPanels.GoToDialog` | ✅ |
| Auto page turn | `ReaderUtils`, auto-turn rates | `ReaderActivity.startAutoTurn` | ✅ |
| Orientation control | `CrossPointSettings.orientation` | reader orientation | ✅ |
| Focus reading | `focusReadingEnabled` | `ReaderText.focus` | ✅ |
| Night mode / inverted screen | `screenInverted`, night mode | reader + app theme | 🟡 |
| Continuous scroll | `touchReaderControls` | reader scroll mode | ✅ |
| End-of-book options | `EndOfBookOptions.cpp` | — | ⬜ |
| Screenshots | `util/ScreenshotUtil.cpp` | `PixelCopy` | ⬜ |
| KOReader progress sync | `KOReaderSyncActivity.cpp`, `lib/KOReaderSync` | `SyncRepository` | 🟡 |
| CrossPoint sync + reading stats | `sync/**` | `SyncRepository`, stats screen | 🟡 |
| Sync-on-sleep | `sync/SyncOnSleep.cpp` | — | ⬜ |
| Reading session log | `sync/ReadingSessionLog.cpp` | `SyncRepository` stats | 🟡 |
| Remote page turn (BLE) | `util/RemoteButtonInput.h` | hardware module | ⬜ |
| Tilt page turn | `HalTiltSensor` | sensor adapter | ⬜ |
| Reading ruler / line guide | — | — | ⛔ |

## 4. Formats & library

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| EPUB | `lib/Epub` | `EpubBook` | 🟡 |
| XTC / XTCH | `lib/Xtc`, `XtcReaderActivity.cpp` | `:formats` | ⬜ |
| TXT | `lib/Txt` | `:formats` | ⬜ |
| BMP viewer | `activities/util/BmpViewerActivity.cpp` | `:formats` | ⬜ |
| Cover generation | `components/HomeCoverCache.cpp` | `CoverImages`, cover cache | 🟡 |
| Library index (`library.idx`) | `lib/LibraryIndex/**` | `:library` | ⬜ |
| Library tabs: title/author/recent | `LibraryListActivity.cpp` | library screens | ⬜ |
| Language grouping | `LibraryText.cpp` | library screens | ⬜ |
| Search | `LibraryListActivity` search | search screen | 🟡 (file names only) |
| Duplicate detection | `library.idx` flags | library index | ⬜ |
| Recent books | `RecentBooksStore.cpp` | `AppRepository` recents | 🟡 |
| File browser | `FileBrowserActivity.cpp` | file browser screen | ⬜ |
| File ops (rename/move/delete/mkdir) | `CrossPointWebServer.cpp` + browser | document provider + own FS | ⬜ |
| Cache management | `ClearCacheActivity.cpp` | settings action | 🟡 |
| Move finished to Read folder | `moveFinishedToReadFolder` | library action | ⬜ |
| EPUB Optimizer | `html/js/epub-optimizer.js` | web UI (Phase 5) | ⬜ |

## 5. Network

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| STA mode (join Wi-Fi) | `WifiSelectionActivity.cpp` | `WifiManager` + settings | 🟡 |
| AP/hotspot mode | `NetworkModeSelectionActivity.cpp` | `WifiManager` local-only hotspot | ⬜ |
| QR helpers | `util/QrUtils.cpp` | `zxing` QR rendering | 🟡 |
| Web file server | `network/CrossPointWebServer.cpp` | foreground service + assets web UI | 🟡 (file transfer only) |
| Web settings UI/API | `network/html/SettingsPage.html` | server endpoints | ⬜ |
| WebSocket fast upload (port 81) | `CrossPointWebServer.cpp` | server endpoint | ⬜ |
| WebDAV | `network/WebDAVHandler.cpp` | WebDAV handler | ⬜ |
| Calibre wireless | `CalibreConnectActivity.cpp` | Calibre protocol | ⬜ |
| OPDS 1/2 browser | `OpdsBookBrowserActivity.cpp` | `OpdsRepository` + screens | ✅ |
| OPDS auth (Basic, OAuth, browser) | `OpdsHttpTransport.cpp`, `OpdsTokenStore` | `OpdsHttp` | ✅ |
| OPDS saved servers | `OpdsServerStore.cpp` | `OpdsServersScreen` | ✅ |
| OPDS acquisition (buy/borrow/hold) | `OpdsBookBrowserActivity.cpp` | browser screen | 🟡 |
| OTA firmware update | `OtaUpdater.cpp`, `SdFirmwareUpdateActivity.cpp` | app self-update | 🔁 (`AppUpdater`) |
| UDP discovery (`crosspoint.local`) | `CrossPointWebServer.cpp` | mDNS (`jmdns`) | 🟡 |
| Clock sync (NTP) | `ClockSyncActivity.cpp` | system time | 🔁 |
| USB drive mode | `UsbDriveActivity.cpp` | MTP/document provider | ⛔ (OS-level) |

## 6. Plugins

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| Plugin enable + hub prompt | `PluginHubInstallActivity.cpp` | settings + install screen | ⬜ |
| Browser plugins (`plugin.js`) | `docs/sd-plugins.md` | WebView-based host | ⬜ |
| Plugin job queue | `/api/plugin-jobs` | service queue | ⬜ |
| Device manifests (`device.json`) | `PluginCatalogActivity.cpp` | Compose catalogs | ⬜ |
| Plugin events | `util/PluginEvents.cpp` | event bus | ⬜ |
| Plugin HTTP/crypto/fetch/FS APIs | `PluginHttp.cpp`, server `/api/*` | scoped APIs | ⬜ |
| Protected content / loan expiry | `network/ProtectedPaths.h` | policy module | ⬜ |

## 7. Settings

Firmware settings are enumerated in `src/CrossPointSettings.h`; the UI list is built in
`src/SettingsList.h`. Port them by category, keeping keys compatible where the firmware persists JSON.

| Category | Firmware settings | Status |
|---|---|---|
| Reader | font family, font size, line spacing, word spacing, character spacing, margin, alignment, extra paragraph spacing, indent, hyphenation, focus reading, embedded style, anti-aliasing, hinting, images | 🟡 (launcher subset) |
| Display | theme, orientation, night mode, refresh cadence, sleep timeout, sleep screen, cover mode/filter, fading fix, quick resume | 🟡 |
| Status bar | title mode, chapter pages, book %, progress bar + thickness, battery, clock, XTC bar | 🟡 |
| Controls | short/double power button, home button tap/double/long, long-press menu, front/side button layout + remap + orientation, touch controls, gestures, reader menu gesture, tilt | 🟡 |
| System | language, keyboard layouts, Wi-Fi, clock/timezone/DST, libraries, hidden files, extensions, vocabulary history, clear cache, plugins, about, updates | 🟡 |
| Frontlight | brightness, warmth, restore on wake, slide switch action | ⬜ 🔁 |
| Sleep | timeout, screen, cover, quick resume | 🟡 |
| KOReader | server, username, password, auth, sync behavior | 🟡 |
| OPDS | server list, download folder, filename format | ✅ |
| Dictionary | selected dictionary, vocabulary history | 🟡 |
| Network | hotspot/join, credentials, mDNS name | 🟡 |

## 8. Hardware

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| E-ink refresh cadence / full refresh | `HalDisplay`, `refreshFrequency` | best-effort `ScreenRefresh` | 🔁 |
| Frontlight | `HalFrontlight` | brightness / vendor SDK | 🔁 |
| Haptics | `HalHaptics` | `Vibrator` | 🔁 |
| Tilt page turn | `HalTiltSensor` | `SensorManager` | ⬜ |
| BLE page turner | `lib/BlePageTurner` | BLE HID client | ⬜ |
| Button remap | `ButtonRemapActivity.cpp` | key mapping | 🟡 |
| Sleep screen / cover | `SleepActivity.cpp`, `SleepScreen.kt` | DreamService / overlay | 🟡 |
| Power button shortcuts | `ShortPowerButtonOption` | key + admin where allowed | 🔁 |
| Screenshots | `ScreenshotUtil.cpp` | `PixelCopy` | ⬜ |
| USB drive mode | `UsbDriveActivity.cpp` | MTP | ⛔ |
| Battery/charging UI | `HalPowerManager` | `BatteryManager` | 🟡 |

## 9. Localization & accessibility

| Feature | Firmware reference | Android target | Status |
|---|---|---|---|
| 38 UI languages | `lib/I18n/translations/*.yaml` | assets + resource strings | ⬜ |
| Language picker | `LanguageSelectActivity.cpp` | settings | ⬜ |
| RTL layout | translations `arabic`, `hebrew`, `persian` | `supportsRtl` + mirrored layouts | 🟡 (`supportsRtl` declared) |
| CJK font fallback | `SdCardFontSystem.cpp` | font fallback chain | 🟡 |
| Screen reader labels | — | `contentDescription` everywhere | ⬜ |
| Keyboard layout options | `KeyboardLayoutsActivity.cpp` | IME choice | ⛔ (system IME) |

## 10. Known gaps accepted for now

| Gap | Reason | Revisit |
|---|---|---|
| No `.cpfont` compiler | Android can render TTF/OTF directly | Phase 3 if card parity needs `.cpfont` |
| Reader uses Android shaping, not `OtShaper` | Matches semantics, not identical glyph positioning | Phase 2 fidelity review |
| Clippings/bookmarks stored as JSON | Firmware binary format not parsed yet | Phase 3 writer |
| Firmware `section.bin` cache not read | Layout cache not implemented | Phase 2 |
| No e-ink waveform control | API not exposed to apps | Phase 7 vendor adapters |
| Plugins run in WebView, not a native sandbox | Closest Android equivalent | Phase 6 security review |
