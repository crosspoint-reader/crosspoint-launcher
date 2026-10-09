package com.crosspoint.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import com.crosspoint.launcher.data.SyncRepository
import com.crosspoint.launcher.model.Book
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.MessageDigest

/** Native instrumentation, no test framework: adb shell am instrument -w ...test/...IntegrationChecks */
class IntegrationChecks : Instrumentation() {
    private var coversOnly = false
    private var pinsOnly = false
    private var statsOnly = false
    private var readerOnly = false
    private var syncOnly = false
    override fun onCreate(arguments: Bundle?) { coversOnly = arguments?.getString("coversOnly") == "true"; pinsOnly = arguments?.getString("pinsOnly") == "true"; statsOnly = arguments?.getString("statsOnly") == "true"; readerOnly = arguments?.getString("readerOnly") == "true"; syncOnly = arguments?.getString("syncOnly") == "true"; super.onCreate(arguments); start() }
    override fun onStart() {
        if (pinsOnly) { PinnedAppsChecks.run(this); return }
        val result = Bundle()

        if (syncOnly) {
            try {
                checkSync()
                ReaderSyncChecks.runSync(this, targetContext)
                result.putString("stream", "Offline reader and batched sync checks passed\n")
                finish(Activity.RESULT_OK, result)
            } catch (e: Throwable) { result.putString("stream", e.stackTraceToString()); finish(Activity.RESULT_CANCELED, result) }
            return
        }
        try { if (coversOnly) { CoverCacheChecks.run(targetContext); result.putString("stream", "Cover sizing, monochrome dithering, transparency and cache checks passed\n"); finish(Activity.RESULT_OK, result); return }; if (statsOnly) { CoverCacheChecks.run(targetContext); StatsChecks.run(this); ScrollbarChecks.run(this); result.putString("stream", "Stats API, offline cache, navigation and scrollbar checks passed\n"); finish(Activity.RESULT_OK, result); return }; if (readerOnly) { checkSync(); checkReader(); ReaderSyncChecks.run(this, targetContext); result.putString("stream", "Reader controls and sync checks passed\n"); finish(Activity.RESULT_OK, result); return }; checkAuroraVerification(); checkSync(); OpdsChecks.run(targetContext); checkReader(); ReaderSyncChecks.run(this, targetContext); CompactLayoutChecks.run(this); SetupAndCatalogChecks.run(this); HomeGestureChecks.run(this); result.putString("stream", "Setup, catalog transitions, compact layout, OPDS 1/2, sync and native reader integration checks passed\n"); finish(Activity.RESULT_OK, result) }
        catch (e: Throwable) { result.putString("stream", e.stackTraceToString()); finish(Activity.RESULT_CANCELED, result) }
    }

    private fun checkAuroraVerification() {
        val aurora = com.crosspoint.launcher.util.AuroraInstaller
        check(!aurora.isSignedByFdroid(targetContext, File(targetContext.packageCodePath))) { "Aurora check accepted a non-F-Droid APK" }
        runCatching { targetContext.packageManager.getApplicationInfo(aurora.PACKAGE, 0).sourceDir }.getOrNull()?.let {
            check(aurora.isSignedByFdroid(targetContext, File(it))) { "Aurora check rejected F-Droid's Aurora Store" }
        }
    }

    private fun checkReader() {
        val controlPrefs = targetContext.getSharedPreferences("reader-controls-check", Context.MODE_PRIVATE)
        val controls = com.crosspoint.launcher.reader.ReaderSettings(controlPrefs)
        val menu = com.crosspoint.launcher.reader.ReaderSettings.MENU_GESTURE
        controlPrefs.edit().putInt(menu.key, 0).commit()
        check(controls[menu] == 1) { "Old hold-only menu preference must fall back to Tap Center" }
        controlPrefs.edit().putInt(menu.key, 2).commit()
        check(controls[menu] == 2) { "Existing Swipe Up preference changed" }
        check(com.crosspoint.launcher.reader.ReaderSettings.CONTROLS.none { it.key in listOf("long_press_action", "volume_long_press") })
        controlPrefs.edit().clear().commit()

        // Bundled firmware fonts are listed first; a non-font file is rejected; added fonts are listed.
        val fonts = com.crosspoint.launcher.reader.ReaderSettings
        val readerSettings = com.crosspoint.launcher.reader.ReaderSettings(targetContext.getSharedPreferences("font-check", Context.MODE_PRIVATE))
        check(readerSettings.fontFamilies(targetContext).take(3) == listOf("Noto Serif", "Noto Sans", "OpenDyslexic"))
        val junk = File(targetContext.cacheDir, "not-a-font.ttf").apply { writeText("not a font") }
        check(fonts.loadFont(junk) == null) { "A non-font file was accepted" }
        junk.delete()
        val added = File(fonts.importedFontDir(targetContext).apply { mkdirs() }, "Check Font.ttf")
        targetContext.resources.openRawResource(com.crosspoint.launcher.R.font.noto_sans_regular).use { input -> added.outputStream().use { input.copyTo(it) } }
        check("Check Font" in readerSettings.fontFamilies(targetContext) && fonts.loadFont(added) != null)
        added.delete()
        // A folder is one family with real styles, like the firmware's /.fonts/<Family>/: its bold face is the Bold
        // file (wider glyphs), not Android's fake bold (same advance widths).
        val familyDir = File(fonts.importedFontDir(targetContext), "Check Family").apply { deleteRecursively(); mkdirs() }
        for ((resource, name) in listOf(com.crosspoint.launcher.R.font.noto_sans_regular to "CheckSans-Regular.ttf",
            com.crosspoint.launcher.R.font.noto_sans_bold to "CheckSans-Bold.ttf", com.crosspoint.launcher.R.font.noto_sans_italic to "CheckSans-Italic.ttf")) {
            targetContext.resources.openRawResource(resource).use { input -> File(familyDir, name).outputStream().use { input.copyTo(it) } }
        }
        check(readerSettings.fontFamilies(targetContext).count { it.startsWith("Check") || it.startsWith("CheckSans") } == 1 && "Check Family" in readerSettings.fontFamilies(targetContext)) { "Family folder not listed as one family" }
        val family = readerSettings.typeface(targetContext, "Check Family")
        fun width(typeface: android.graphics.Typeface) = android.graphics.Paint().apply { this.typeface = typeface; textSize = 40f }.measureText("Hamburgefonstiv")
        check(width(android.graphics.Typeface.create(family, android.graphics.Typeface.BOLD)) > width(family) * 1.03f) { "Family bold is not the real Bold face" }
        check(fonts.familyName(listOf("Literata-Regular.ttf", "Literata-Bold.ttf", "Literata-BoldItalic.ttf")) == "Literata")
        familyDir.deleteRecursively()

        val file = File(targetContext.cacheDir, "reader-integration.epub")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>")
            entry("book.opf", "<package><metadata><title>Reader Check</title></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>")
            entry("a.xhtml", "<html><body>" + (1..80).joinToString("") { "<p>Paragraph $it. The native reader keeps just this chapter in memory, notwithstanding extraordinarily incomprehensible internationalization requirements.</p>" } + "</body></html>")
            entry("b.xhtml", "<html><body><h1>Last chapter</h1><p>The end.</p></body></html>")
        }
        fun views(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun open(): Activity = startActivitySync(android.content.Intent(targetContext, com.crosspoint.launcher.reader.ReaderActivity::class.java)
            .putExtra("book_path", file.path).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun loaded(activity: Activity) {
            repeat(100) {
                var done = false
                runOnMainSync { done = views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString().startsWith("Chapter 1 of ") } }
                if (done) { waitForIdleSync(); return }
                Thread.sleep(50)
            }
            error("Reader did not load its chapter")
        }
        val readerPrefs = targetContext.getSharedPreferences("reader", Context.MODE_PRIVATE)
        // Run with default reader settings and put the device's own back afterwards.
        val savedPrefs = readerPrefs.all.toMap()
        readerPrefs.edit().clear().commit()
        val activity = open()
        loaded(activity)
        runOnMainSync {
            val all = views(activity.window.decorView)
            check(activity.findViewById<android.widget.TextView>(android.R.id.title).text.toString() == "Reader Check")
            check(all.filterIsInstance<android.widget.Button>().none { it.text.toString() in listOf("Previous", "Next", "Sync") })
            val font = all.first { it.contentDescription == "Font" }
            check(!font.isShown)
            val readerScroll = all.filterIsInstance<android.widget.ScrollView>().single()
            fun centerTap() {
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, now + if (action == android.view.MotionEvent.ACTION_UP) 50 else 0,
                        action, readerScroll.width / 2f, readerScroll.height / 2f, 0)
                    readerScroll.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            centerTap()
            check(font.isShown)
            centerTap()
            check(!font.isShown)
            centerTap()
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)); activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN))
            check(all.filterIsInstance<android.widget.ScrollView>().single().scrollY > 0)
            // Real pages: each starts on a line, the previous one ended on a whole line, and the page number is exact.
            val reader = all.filterIsInstance<android.widget.TextView>().maxByOrNull { it.text.length }!!
            val pageLayout = reader.layout
            val top = readerScroll.scrollY
            val firstLine = pageLayout.getLineForVertical(top)
            check(pageLayout.getLineTop(firstLine) == top) { "Page does not start on a line" }
            check(pageLayout.getLineBottom(firstLine - 1) <= readerScroll.height - reader.paddingTop - reader.paddingBottom) { "Previous page cut a line" }
            check(all.filterIsInstance<android.widget.TextView>().any { Regex("^2/\\d+").containsMatchIn(it.text) }) { "Status bar page number is not exact" }

            // Firmware touch controls: left third back, right side forward, horizontal swipes, drags never scroll a page.
            fun touch(x: Float, y: Float, toX: Float = x, toY: Float = y) {
                val now = android.os.SystemClock.uptimeMillis()
                fun send(action: Int, ex: Float, ey: Float, at: Long) = android.view.MotionEvent.obtain(now, now + at, action, ex, ey, 0)
                    .also { readerScroll.dispatchTouchEvent(it); it.recycle() }
                send(android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                for (step in 1..5) send(android.view.MotionEvent.ACTION_MOVE, x + (toX - x) * step / 5, y + (toY - y) * step / 5, step * 10L)
                send(android.view.MotionEvent.ACTION_UP, toX, toY, 60)
            }
            val w = readerScroll.width.toFloat(); val h = readerScroll.height.toFloat()
            var y = readerScroll.scrollY
            touch(w * 0.9f, h * 0.5f); check(readerScroll.scrollY > y) { "Right tap did not turn the page" }
            y = readerScroll.scrollY
            touch(w * 0.1f, h * 0.5f); check(readerScroll.scrollY < y) { "Left tap did not go back" }
            y = readerScroll.scrollY
            touch(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f); check(readerScroll.scrollY > y) { "Swipe left did not turn the page" }
            y = readerScroll.scrollY
            touch(w * 0.5f, h * 0.8f, w * 0.5f, h * 0.2f); check(readerScroll.scrollY == y) { "Paged mode scrolled on a drag" }
            readerPrefs.edit().putInt("next_page_gesture", 2).commit() // Swipe only
            touch(w * 0.9f, h * 0.5f); check(readerScroll.scrollY == y) { "Swipe-only next page accepted a tap" }
            readerPrefs.edit().putInt("touch_reader_controls", 0).commit()
            touch(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f); check(readerScroll.scrollY == y) { "Touch controls off still turned the page" }
            readerPrefs.edit().remove("touch_reader_controls").remove("next_page_gesture").putInt("volume_key_layout", 2).commit()
            check(!activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)))
            readerPrefs.edit().putInt("volume_key_layout", 1).commit() // Up: next
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP)); activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_VOLUME_UP))
            check(readerScroll.scrollY > y) { "Swapped volume keys did not turn forward" }
            readerPrefs.edit().remove("volume_key_layout").commit()
            check(all.filterIsInstance<android.widget.TextView>().any { Regex("^\\d+/\\d+  \\d+%$").matches(it.text) }) { "Status bar missing page count" }
            val pressed = android.os.SystemClock.uptimeMillis()
            val pageKey = android.view.KeyEvent.KEYCODE_PAGE_DOWN
            activity.dispatchKeyEvent(android.view.KeyEvent(pressed, pressed, android.view.KeyEvent.ACTION_DOWN, pageKey, 0))
            val afterPress = readerScroll.scrollY
            activity.dispatchKeyEvent(android.view.KeyEvent(pressed, pressed + 800, android.view.KeyEvent.ACTION_DOWN, pageKey, 1))
            activity.dispatchKeyEvent(android.view.KeyEvent(pressed, pressed + 900, android.view.KeyEvent.ACTION_UP, pageKey, 0))
            check(readerScroll.scrollY == afterPress) { "Holding a side button performed an extra action" }

        }
        waitForIdleSync()
        // Relayouts keep the place like the firmware's offset-anchored progress: font size changes reopen on the page
        // holding the same text, and stepping back and forth doesn't drift.
        var anchorOffset = -1
        fun readerViews() = views(activity.window.decorView).let { all ->
            all.filterIsInstance<android.widget.TextView>().maxByOrNull { it.text.length }!! to all.filterIsInstance<android.widget.ScrollView>().single()
        }
        runOnMainSync { val (reader, scroll) = readerViews(); anchorOffset = reader.layout.getLineStart(reader.layout.getLineForVertical(scroll.scrollY)) }
        for (size in listOf(28, 20, 28)) {
            readerPrefs.edit().putInt("font_size", size).commit()
            runOnMainSync { (activity as com.crosspoint.launcher.reader.ReaderPanelHost).settingChanged(com.crosspoint.launcher.reader.ReaderSettings.FONT_SIZE) }
            loaded(activity)
            var shown = ""
            val kept = (1..40).any {
                Thread.sleep(100); waitForIdleSync()
                var ok = false
                runOnMainSync {
                    val (reader, scroll) = readerViews()
                    val layout = reader.layout
                    val top = layout.getLineStart(layout.getLineForVertical(scroll.scrollY))
                    val bottom = layout.getLineEnd(layout.getLineForVertical(scroll.scrollY + scroll.height - reader.paddingTop - reader.paddingBottom))
                    shown = "$top..$bottom"; ok = anchorOffset in top until bottom
                }
                ok
            }
            check(kept) { "Font size $size lost the place: $anchorOffset not in $shown" }
        }
        readerPrefs.edit().remove("font_size").commit()
        runOnMainSync { activity.finish() }
        waitForIdleSync()
        val resumed = open()
        loaded(resumed)
        // The saved place is restored once the new layout has been measured.
        check((1..30).any { Thread.sleep(100); var y = 0; runOnMainSync { y = views(resumed.window.decorView).filterIsInstance<android.widget.ScrollView>().single().scrollY }; y > 0 }) { "Reopening lost the place" }
        runOnMainSync { resumed.finish() }
        waitForIdleSync()
        // Continuous scroll: drags scroll natively, and every text setting re-renders without losing the chapter.
        readerPrefs.edit().putInt("continuous_scroll", 1).putInt("word_spacing", 150).putInt("focus_reading", 1)
            .putInt("paragraph_indent", 4).putInt("night_mode", 1).putInt("image_rendering", 1).commit()
        val continuous = open()
        loaded(continuous)
        runOnMainSync {
            val readerScroll = views(continuous.window.decorView).filterIsInstance<android.widget.ScrollView>().single()
            readerScroll.scrollTo(0, 0)
            val now = android.os.SystemClock.uptimeMillis()
            val x = readerScroll.width / 2f
            for ((i, action) in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP).withIndex()) {
                android.view.MotionEvent.obtain(now, now + i * 16L, action, x, readerScroll.height * (0.8f - i * 0.15f), 0)
                    .also { readerScroll.dispatchTouchEvent(it); it.recycle() }
            }
            check(readerScroll.scrollY > 0) { "Continuous scroll did not follow the drag" }
            continuous.finish()
        }
        waitForIdleSync()
        // Every text setting must reach the laid-out page, not just the preference file.
        fun layoutWith(vararg values: Pair<String, Any>, inspect: (android.widget.TextView) -> Unit) {
            readerPrefs.edit().clear().apply { values.forEach { (k, v) -> if (v is String) putString(k, v) else putInt(k, v as Int) } }.commit()
            val reader = open()
            loaded(reader)
            runOnMainSync {
                inspect(views(reader.window.decorView).filterIsInstance<android.widget.TextView>().maxByOrNull { it.text.length }!!)
                reader.finish()
            }
            waitForIdleSync()
        }
        val metrics = targetContext.resources.displayMetrics
        layoutWith("paragraph_alignment" to 0, "hyphenation" to 1, "font_size" to 30, "line_spacing" to 3,
            "screen_margin" to 40, "paragraph_indent" to 4, "word_spacing" to 150, "focus_reading" to 1, "text_anti_aliasing" to 0,
            "font_family" to "Noto Sans") { view ->
            val layout = view.layout!!
            val content = view.text.toString()
            val width = view.width - view.totalPaddingLeft - view.totalPaddingRight
            check(kotlin.math.abs(view.textSize - android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 30f, metrics)) < 1f) { "Font size not applied" }
            check(view.lineSpacingMultiplier == 1.05f) { "Noto Sans extra-wide spacing not applied: ${view.lineSpacingMultiplier}" }
            val margin = (40 * metrics.density).toInt()
            check(view.paddingLeft == margin && view.paddingRight == margin && view.paddingTop == margin && view.paddingBottom == margin) { "Screen margin not applied to every edge" }
            check(!view.paint.isAntiAlias) { "Anti-aliasing toggle not applied" }
            // Aliased text draws only solid pixels; anti-aliased text has grey edges.
            fun greyPixels(antiAlias: Boolean): Int {
                view.paint.isAntiAlias = antiAlias
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, minOf(view.height, 400), android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE); view.draw(this) }
                val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                bitmap.recycle()
                return pixels.count { val v = it and 0xff; v in 40..215 }
            }
            val aliased = greyPixels(false)
            val smooth = greyPixels(true)
            view.paint.isAntiAlias = false
            check(smooth > aliased * 3 && smooth > 200) { "Anti-aliasing has no visible effect: $smooth grey pixels on vs $aliased off" }
            val spans = view.text as android.text.Spanned
            check(spans.getSpans(0, spans.length, android.text.style.LeadingMarginSpan::class.java).isNotEmpty()) { "Paragraph indent missing" }
            // Every glyph stays inside the text width (trailing spaces don't count, they're invisible).
            fun glyphRight(line: Int): Float {
                var last = layout.getLineEnd(line) - 1
                while (last > layout.getLineStart(line) && content[last].isWhitespace()) last--
                return layout.getPrimaryHorizontal(last) + view.paint.measureText(content, last, last + 1)
            }
            check((0 until layout.lineCount).all { glyphRight(it) <= layout.width + 1 }) {
                "A line runs past the right margin: " + (0 until layout.lineCount).filter { glyphRight(it) > layout.width + 1 }.take(3)
                    .joinToString { "line $it right=${glyphRight(it)} width=${layout.width} '${content.substring(layout.getLineStart(it), layout.getLineEnd(it)).take(30)}'" }
            }
            check(spans.getSpans(0, spans.length, android.text.style.ScaleXSpan::class.java).isNotEmpty()) { "Word spacing missing" }
            check(spans.getSpans(0, spans.length, android.text.style.StyleSpan::class.java).isNotEmpty()) { "Focus reading missing" }
            // Wrapped lines with a space to stretch (a lone long word or hyphen fragment can't be justified).
            val wrapped = (0 until layout.lineCount).filter {
                layout.getLineEnd(it) < content.length && content[layout.getLineEnd(it) - 1] != '\n' &&
                    content.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim().contains(' ')
            }
            check(wrapped.isNotEmpty() && wrapped.all { layout.getLineWidth(it) >= width - 2 }) { "Justified lines do not fill the text width: " + wrapped.filter { layout.getLineWidth(it) < width - 2 }.take(4).joinToString { "line $it ${layout.getLineWidth(it)}/$width '${content.substring(layout.getLineStart(it), layout.getLineEnd(it)).take(40)}'" } }
            // A break with letters on both sides only happens inside a hyphenated word.
            fun midWord(line: Int) = layout.getLineEnd(line).let { end -> end in 1 until content.length && content[end - 1].isLetter() && content[end].isLetter() }
            check((0 until layout.lineCount).any(::midWord)) { "Hyphenation did not hyphenate any line" }
        }
        layoutWith("character_spacing" to 2) { view -> check(view.letterSpacing == 0.05f) { "Character spacing not applied" } }
        layoutWith("paragraph_alignment" to 1, "hyphenation" to 0, "font_size" to 30) { view ->
            val layout = view.layout!!
            val width = view.width - view.totalPaddingLeft - view.totalPaddingRight
            val content = view.text.toString()
            check((0 until layout.lineCount).none { line -> layout.getLineEnd(line).let { end -> end in 1 until content.length && content[end - 1].isLetter() && content[end].isLetter() } }) { "Hyphenation off still hyphenated" }
            check((0 until layout.lineCount).any { layout.getLineWidth(it) < width - 20 }) { "Left alignment still justified" }
            check(view.justificationMode == android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE)
        }
        readerPrefs.edit().clear().apply {
            savedPrefs.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is Long -> putLong(k, v); is Boolean -> putBoolean(k, v); is String -> putString(k, v); is Float -> putFloat(k, v) } }
        }.commit()
        file.delete()
    }

    private fun checkSync() {
        val sandbox = File(targetContext.cacheDir, "integration-checks").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir() = File(sandbox, "private").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("integration-checks-$name", mode)
        }
        context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE).edit().clear().commit()
        val passwordHash = MessageDigest.getInstance("MD5").digest("secret".toByteArray()).joinToString("") { "%02x".format(it) }
        val image = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.RGB_565)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); bitmap.recycle()
        }.toByteArray()
        val requests = mutableListOf<String>()
        var uploaded: JSONObject? = null
        var newest = 0L
        var offline = false
        var progressWrites = 0
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = if (url.host in listOf("opds.test", "opds-cdn.test")) OpdsChecks.connection(url) else if (url.host == "reader-sync.test") ReaderSyncChecks.connection(url) else object : HttpURLConnection(url) {
                    val output = ByteArrayOutputStream()
                    var body = ByteArray(0)
                    var code = 0
                    fun respond() {
                        if (code != 0) return
                        if (offline) throw java.io.IOException("Offline")
                        requests += "$requestMethod ${url.host}${url.path}"
                        var json = "{}"
                        code = 200
                        if (url.host == "covers.test") {
                            check(getRequestProperty("x-auth-key") == null)
                            if (url.path == "/redirect") code = 302 else body = image
                            return
                        }
                        check(url.host == "sync.test")
                        check(getRequestProperty("x-auth-user") == "reader")
                        if (getRequestProperty("x-auth-key") != passwordHash) { code = 401; return }
                        when {
                            url.path == "/users/create" -> {
                                check(requestMethod == "POST")
                                check(JSONObject(output.toString()).getString("password") == passwordHash)
                                code = 201
                            }
                            url.path == "/users/auth" -> json = "{\"authorized\":\"OK\"}"
                            url.path == "/api/v1/documents" && requestMethod == "GET" -> json = "{\"items\":[]}"
                            url.path == "/api/v1/documents" -> {
                                check(requestMethod == "PUT")
                                uploaded = JSONObject(output.toString()).getJSONArray("items").getJSONObject(0)
                            }
                            url.path.endsWith("/cover") -> json = "{\"url\":\"https://covers.test/redirect\",\"pages\":100}"
                            url.path.startsWith("/api/v1/progress/") -> json = "{\"devices\":[{\"timestamp\":$newest}]}"
                            url.path == "/api/v1/progress" -> {
                                check(requestMethod == "PUT")
                                val progress = JSONObject(output.toString())
                                check(progress.getDouble("percentage") == 0.5)
                                check(progress.getString("progress") == "crosspoint-launcher:1:40")
                                progressWrites++
                            }
                            url.path == "/api/v1/stats/activity" -> json = "{\"pages_total\":50,\"books\":[],\"days\":[]}"
                            else -> error("Unexpected endpoint: $url")
                        }
                        body = json.toByteArray()
                    }
                    override fun getResponseCode(): Int { respond(); return code }
                    override fun getInputStream(): java.io.InputStream { respond(); return ByteArrayInputStream(body) }
                    override fun getOutputStream() = output
                    override fun getHeaderField(name: String?) = if (name == "Location") "https://covers.test/cover.jpg" else null
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                }
            }
        }
        val repo = SyncRepository(context)
        check(!repo.connected)
        check(runCatching { repo.connect("http://sync.test", "reader", "secret") }.isFailure)
        repo.connect("https://sync.test", "reader", "secret", signup = true)
        check(repo.connected)
        check(runCatching { repo.connect("https://sync.test", "reader", "wrong") }.isFailure)
        check(repo.connected && repo.username == "reader") // Failed login preserves account.
        val epub = File(sandbox, "Book.epub").apply { writeText("test content") }
        val book = Book(epub.path, "A Book", "An Author", null, epub.lastModified())
        val enriched = repo.fetchCover(book)
        check(uploaded?.getString("title") == book.title)
        check(uploaded?.getString("document") == repo.documentId(book))
        val bitmap = BitmapFactory.decodeFile(enriched.coverPath)
        check(maxOf(bitmap.width, bitmap.height) <= 600); bitmap.recycle()
        val count = requests.size
        check(repo.fetchCover(book).coverPath == enriched.coverPath)
        check(requests.size == count) // Warm cover load makes no requests.
        repo.queueProgress(repo.documentId(book), book.title, book.author, epub.name, 1, 40, 0.5)
        offline = true
        check(SyncRepository(context).fetchCover(book).coverPath == enriched.coverPath) { "Saved cover unavailable offline" }
        val firstOfflineFile = File(sandbox, "First Offline.epub").apply { writeText("new offline book") }
        val firstOfflineBook = Book(firstOfflineFile.path, "First Offline", "", null, firstOfflineFile.lastModified())
        check(SyncRepository(context).documentId(firstOfflineBook).matches(Regex("[a-f0-9]{32}"))) {
            "A book first opened offline could not get a local sync identity"
        }
        check(runCatching { repo.flushProgress() }.isFailure)
        offline = false
        repo.flushProgress()
        check(progressWrites == 1)
        repo.flushProgress()
        check(progressWrites == 1) // Acknowledged records are removed.
        repo.queueProgress(repo.documentId(book), book.title, book.author, epub.name, 1, 40, 0.5)
        newest = System.currentTimeMillis() / 1000 + 1000
        repo.flushProgress()
        check(progressWrites == 1) // Don't overwrite a newer device's progress.
        check(repo.activityStats().getInt("pages_total") == 50)
        repo.disconnect()
        check(!repo.connected)
        check(repo.cachedCover(book) == enriched.coverPath)
        sandbox.deleteRecursively()
        context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
