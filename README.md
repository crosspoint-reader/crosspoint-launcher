# CrossPoint Android Launcher

Android 10+ launcher with a lightweight built-in EPUB reader and optional CrossPoint Sync account.

The welcome screen offers **Log in**, **Sign up**, and **Skip for now**. The same account screen and reading stats are available in **Settings → CrossPoint Sync**. Use a sync username/password (the same credentials as a reader), not a website master token. The default server is `https://sync.crosspointreader.com`; self-hosted HTTPS servers are supported.

When connected, the built-in reader pulls the latest reading position on open/resume and uploads the visible text location every 30 seconds and when paused. Its **Sync** button also runs this exchange. Newer remote locations restore automatically unless reading has already moved, in which case the reader offers a choice. Opening a book never uploads a stale local location over a newer remote one. Offline changes are journaled and retried, and missing covers are looked up automatically. Read books and books needing covers are added to the synced library. Covers remain available offline after disconnecting. Stats come from the server's reading activity API; print-page counts and progress percentages are estimates. Older queued positions do not overwrite a newer server position. No new server routes or deployment are required.

The integration uses `/users/create`, `/users/auth`, `/api/v1/documents`, `/api/v1/documents/:id/cover`, `/api/v1/progress`, and `/api/v1/stats/activity`. Kindle metadata additionally uses `/api/v1/connectors/kindle/lookup` and requires a linked Kindle connector. Provider API keys stay on the sync server; the APK no longer includes Librario/SerpAPI keys. Sync credentials are stored in app-private, non-backed-up storage, and never sent to cover hosts.

The reader adapts the container → OPF → spine and single-section approach from `crosspoint-reader-main/lib/Epub` and its `EpubReaderActivity`, using Android text views instead of the ESP32 renderer. It supports basic HTML formatting, chapter selection, font sizing, page/volume keys, and saved text offsets. It does not implement the firmware's full typography, illustrations, publisher CSS, DRM, or bookmarks. Portable KOReader/CrossPoint XPaths map text locations in well-formed XHTML, including nested elements and Unicode. Existing launcher chapter/offset records remain readable; unresolvable locations fall back to a chapter-weighted percentage. Page numbers can differ with screen size and font. Chapters over 1 MiB are rejected with an explanation; long-press a book and choose **Open with** for a full-featured external reader. Local position survives rotation and reopening; sync keeps the latest unsent position per book, not an offline session timeline. New document IDs use cached full-file MD5 unless an unambiguous existing library match is available.

Low-memory changes include sampled RGB565 covers capped at 600 pixels on either dimension, disk metadata caching, deduplicated directory traversal and background jobs, smaller app icons on low-RAM devices, no artificial boot delay, and R8/resource shrinking. Cover fetching stops between requests when leaving the launcher. The reader loads no WebView and lays out only the current chapter. No new runtime dependencies were added; unused Coil and Navigation dependencies were removed.

OPDS 1 Atom and [OPDS 2 JSON](https://specs.opds.io/opds-2.0) catalogs are detected automatically. The browser supports navigation, grouped publications, facets, first/previous/next/last pages, RFC 6570 search fields, OpenSearch descriptors, publication details and covers, shelf links, and acquisition choices with prices, availability, copies, and holds. Indirect acquisition responses open their publication details for the next action. HTML purchases/subscriptions open in the browser; PDF and Readium LCP licenses open in compatible installed apps. DRM decryption is not part of the built-in EPUB reader.

[OPDS authentication](https://drafts.opds.io/authentication-for-opds-1.0.html) supports Basic, OAuth password, and browser/implicit flows with token refresh. Credentials require HTTPS and are never forwarded to another origin. Browser login allocates a WebView only during sign-in. Anonymous HTTP catalogs remain available for local servers. Feed bodies are bounded to 2 MiB and 64 JSON nesting levels; covers stream to disk before sampled decoding. History stores URLs rather than previous feed bodies. Downloads are capped at 512 MiB, validated before publication, cancellable, and never overwrite an existing book.

Build and checks:

```sh
./scripts/check-reader.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.crosspoint.launcher.test/com.crosspoint.launcher.IntegrationChecks
```

The standalone parser check covers spine order, encoded paths, missing chapters, oversized entries, XML entities, and encryption. Native instrumentation checks the sync contract with fake HTTPS responses (no production accounts), redirects without credentials, cover downsampling/cache reuse, offline retry/conflicts, reader paging/font changes/saved-position restoration, OPDS parsing/search/authentication/acquisitions, and a reader sync round trip from a firmware-style XPath through upload and fresh-local-state restoration. Offline reading and retries are exercised without production accounts. The release APK is unsigned. Real low-RAM/e-ink hardware, live catalog OAuth providers, and cross-device sync accounts still need device validation.

Lists use Android's standard lazy scrolling for menus, libraries, catalogs, search results and settings. Chapter selection uses a native scrollable dialog. The 480×800 / 219 dpi S4 retains compact spacing and a single-screen sign-in form with a top-right Skip action. The CrossPoint wordmark uses the original outlined Lora SemiBold lettering from `crosspoint-tools`, rendered as an Android vector. Compact-layout instrumentation checks the form with the keyboard open and verifies that scrolling reaches every row without paging controls.

### Android landing page

`update-service/public/index.html` and `style.css` serve the landing page at
<https://android.crosspointreader.com>. It uses the same static Worker as the update
feed; `/crosspoint.apk` always points to the current signed release. No frontend
build or JavaScript is required. The screenshots are actual emulator captures
of the app at 480 × 720 with a public-domain sample book: Home (including OPDS Browser), EPUB reader, and Settings. The reader capture uses `update-service/screenshot-book.css` applied to the first
chapter of the sample EPUB for a compact heading and three-line drop cap. It is
rendered by the app, not composited onto the screenshot. The favicon comes from
the Android vector asset.

Preview with `python3 -m http.server 8765 --directory update-service/public`.
Before deploying, run `python3 scripts/check-landing.py`; this also checks that the
ignored APKs match `latest.json`. A fresh checkout needs the current `latest.json`,
its versioned APK, and `crosspoint.apk` in `update-service/public` before a site-only
deploy. Preserve these release files so the existing updater keeps working.
Deploy website-only changes with `cd update-service && npx -y wrangler deploy`.
Normal `scripts/release.sh` runs also publish the landing page without removing it.
