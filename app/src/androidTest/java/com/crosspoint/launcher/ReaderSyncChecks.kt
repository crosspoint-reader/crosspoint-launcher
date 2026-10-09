package com.crosspoint.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.crosspoint.launcher.data.SyncRepository
import com.crosspoint.launcher.reader.EpubPosition
import com.crosspoint.launcher.reader.ReaderActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.*

object ReaderSyncChecks {
    @Volatile private var remote = JSONObject()
    @Volatile private var uploaded: JSONObject? = null
    @Volatile private var offline = false
    /** Fake CrossPoint Sync clipping store: id -> item, with a revision clock like the server's. */
    private val serverClips = LinkedHashMap<String, JSONObject>()
    private var clipRevision = 0L
    fun connection(url: URL): URLConnection = object : HttpURLConnection(url) {
        val out = ByteArrayOutputStream()
        val result by lazy {
            if (offline) throw java.io.IOException("Offline")
            check(getRequestProperty("x-auth-user") == "reader")
            when {
                url.path == "/users/auth" -> JSONObject().put("authorized", "OK")
                url.path == "/api/v1/documents" -> JSONObject().put("items", org.json.JSONArray())
                url.path.startsWith("/api/v1/progress/") -> JSONObject().put("devices", org.json.JSONArray().put(remote))
                url.path.startsWith("/api/v1/clippings/") && out.size() == 0 -> synchronized(serverClips) {
                    val cursor = url.query.substringAfter("cursor=").substringBefore('&').toLong()
                    val items = serverClips.values.filter { it.getLong("revision") > cursor }.sortedBy { it.getLong("revision") }
                    JSONObject().put("sync_version", 2).put("more", false).put("items", org.json.JSONArray(items))
                        .put("cursor", items.maxOfOrNull { it.getLong("revision") } ?: cursor)
                }
                url.path.startsWith("/api/v1/clippings/") -> synchronized(serverClips) {
                    val items = JSONObject(out.toString()).getJSONArray("items")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        check(!item.has("pending") && !item.has("revision")) { "Local bookkeeping leaked into the upload" }
                        if (item.optInt("deleted") != 1) check(item.has("spine") && item.has("text") && item.has("created_at"))
                        serverClips[item.getString("id")] = JSONObject(item.toString()).put("revision", ++clipRevision).put("deleted", item.optInt("deleted"))
                    }
                    JSONObject().put("accepted", items.length())
                }
                url.path == "/api/v1/progress" -> {
                    val body = JSONObject(out.toString())
                    uploaded = body
                    remote = JSONObject(body.toString()).put("timestamp", System.currentTimeMillis() / 1000)
                    JSONObject()
                }
                else -> error("Unexpected reader sync endpoint $url")
            }
        }
        override fun getResponseCode(): Int { result; return 200 }
        override fun getInputStream() = result.toString().byteInputStream()
        override fun getOutputStream() = out
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
    /** Book CSS like the firmware's feat-epub-rendering: selectors, cascade and every supported property, text untouched. */
    private fun checkCss(context: Context) {
        val css = "p { text-indent: 3em } .c { text-align: center } div.poem > p { margin-left: 2em } #x { font-weight: bold } " +
            ".sc { font-variant: small-caps } .h { display: none } .box { border: 1px solid black; background: #eee } " +
            "p.d::first-letter { float: left; font-size: 3em } .big { font-size: 2em } h2 { font-weight: normal } " +
            ".pb { page-break-before: always } img.icon { height: 2em } @media print { .c { text-align: right } } .late { text-align: left } p.late { text-align: right } .late { text-align: center }"
        val chapter = "<html><head><link rel='stylesheet' href='s.css'/><style>.u { text-decoration: underline }</style></head><body>" +
            "<h2>Heading</h2><p class='c'>Centered</p><div class='poem'><p>Verse</p></div><p id='x'>Strong</p><p class='sc'>Small caps</p>" +
            "<p>Shown <span class='h'>hidden</span></p><div class='box'><p>Boxed</p></div><p class='d'>Dropped capital</p><p class='big'>Big</p>" +
            "<p class='u late'>Under</p><p>Dash&mdash;here</p><pre>a  b\n c</pre><ol><li>One</li><li>Two</li></ol><p class='pb'>Break<img class='icon' src='i.png' alt='icon'/></p><table><tr><th>Name</th><td><p>Long cell</p><p>text</p></td></tr><tr><td>A</td><td>B</td></tr></table></body></html>"
        val file = File(context.cacheDir, "css-check.epub")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>")
            entry("book.opf", "<package><metadata><title>CSS</title></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>")
            entry("a.xhtml", chapter); entry("s.css", css)
            zip.putNextEntry(java.util.zip.ZipEntry("i.png"))
            android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.RGB_565).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
        }
        val book = com.crosspoint.launcher.reader.EpubBook(file)
        val prefs = context.getSharedPreferences("css-check", Context.MODE_PRIVATE).apply { edit().clear().putInt("paragraph_indent", 2).commit() }
        val settings = com.crosspoint.launcher.reader.ReaderSettings(prefs)
        val page = com.crosspoint.launcher.reader.ReaderText.Page(600, 800, 32f, android.graphics.Color.BLACK, false, 8f, 40)
        val built = com.crosspoint.launcher.reader.ReaderText.render(book.chapterHtml(0), book, 0, settings, page, context.resources)
        val (styled, map) = built
        prefs.edit().putInt("embedded_style", 0).commit()
        val (plain, _) = com.crosspoint.launcher.reader.ReaderText.render(book.chapterHtml(0), book, 0, settings, page, context.resources)
        check(styled.toString() == plain.toString()) { "CSS changed the text, which would break sync offsets" }
        check(map != null) { "Builder positions missing" }
        check(built.ids["x"] == styled.toString().indexOf("Strong")) { "Element id did not resolve" }
        check("Dash—here" in styled.toString()) { "HTML entity not rendered" }
        check(styled.toString().indexOf("Break") in built.pageBreaks) { "CSS page break not reported" }
        // CSS height 2em at a 32px body: 64px tall, width from the 2:1 aspect ratio.
        val icon = styled.getSpans(0, styled.length, android.text.style.ImageSpan::class.java).single().drawable.bounds
        check(icon.height() == 64 && icon.width() == 128) { "CSS image size: ${icon.width()}x${icon.height()}" }
        // Each table row is one line drawn in columns; cell text (and so positions) stays in the chapter text.
        check("Name Long cell text\nA B" in styled.toString()) { "Table text: ${styled.toString().substringAfter("Break")}" }
        check("a  b\n c" in styled.toString()) { "<pre> whitespace collapsed" }
        // XPaths still resolve against builder text, counting elements as KOReader does.
        check(map.offset("/body/DocFragment[1]/body/p[3]/text().0") == styled.toString().indexOf("Small caps")) { "XPath p[3] should be the small-caps paragraph" }
        check(map.xpath(styled.toString().indexOf("Under")) == "/body/DocFragment[1]/body/p[7]/text()[1].0") { "XPath for Under: ${map.xpath(styled.toString().indexOf("Under"))}" }
        val text = styled.toString()
        fun at(word: String) = text.indexOf(word).also { check(it >= 0) { "$word missing" } }
        fun <T> spans(word: String, type: Class<T>) = styled.getSpans(at(word), at(word) + 1, type).toList()
        fun align(word: String) = spans(word, android.text.style.AlignmentSpan::class.java).lastOrNull()?.alignment
        check(align("Centered") == android.text.Layout.Alignment.ALIGN_CENTER) { "Class text-align missed" }
        check(align("Under") == android.text.Layout.Alignment.ALIGN_OPPOSITE) { "Specificity: p.late should beat the later .late" }
        check(spans("Verse", android.text.style.LeadingMarginSpan.Standard::class.java).any { it.getLeadingMargin(false) > 0 }) { "Child combinator margin missed" }
        check(spans("Strong", android.text.style.StyleSpan::class.java).any { it.style == android.graphics.Typeface.BOLD }) { "Id selector missed" }
        check(spans("Heading", android.text.style.StyleSpan::class.java).none { it.style == android.graphics.Typeface.BOLD }) { "font-weight: normal did not unbold the heading" }
        check(spans("Small", android.text.style.ReplacementSpan::class.java).isNotEmpty()) { "Small caps missed" }
        check(spans("hidden", android.text.style.ReplacementSpan::class.java).isNotEmpty()) { "display: none missed" }
        check(spans("Boxed", android.text.style.LineBackgroundSpan::class.java).isNotEmpty()) { "Border box missed" }
        check(spans("Dropped", android.text.style.LeadingMarginSpan.LeadingMarginSpan2::class.java).single().leadingMarginLineCount == 3) { "Drop cap missed" }
        check(spans("Big", android.text.style.RelativeSizeSpan::class.java).any { it.sizeChange == 2f }) { "font-size missed" }
        check(spans("Under", android.text.style.UnderlineSpan::class.java).isNotEmpty()) { "<style> block missed" }
        check(spans("Two", android.text.style.LeadingMarginSpan::class.java).any { it.getLeadingMargin(true) > 0 }) { "List marker missing" }
        check(spans("Name", android.text.style.ReplacementSpan::class.java).isNotEmpty() && spans("A B", android.text.style.ReplacementSpan::class.java).isNotEmpty()) { "Table rows not laid out" }
        // The reader's paragraph indent wins over CSS text-indent; centered text and drop caps get none.
        val indentOf = { word: String -> spans(word, android.text.style.LeadingMarginSpan.Standard::class.java).sumOf { it.getLeadingMargin(true) } }
        check(indentOf("Strong") == 16) { "Reader indent should be 2 spaces (16px), got ${indentOf("Strong")}" }
        check(indentOf("Centered") == 0 && indentOf("Dropped") == 0)
        file.delete()
    }

    fun run(test: Instrumentation, context: Context) {
        val html = "<html><body><p>Hello <b>wide 🌍</b> world.</p><p>Second &amp; last.</p></body></html>"
        val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
        val map = EpubPosition.build(html, text, 2) ?: error("XHTML mapping failed")
        for (word in listOf("Hello", "wide", "🌍", "world", "Second", "last")) {
            val offset = text.indexOf(word)
            val xpath = map.xpath(offset)!!
            check(map.offset(xpath) == offset) { "$word: $xpath -> ${map.offset(xpath)} != $offset" }
            check(EpubPosition.spine(xpath) == 2)
        }
        check(map.offset("/body/DocFragment[3]/body/p[2]/text().10000") == null)
        check(map.offset("/body/DocFragment[3]/body/p[2]") == text.indexOf("Second"))

        // Firmware clipping offsets count raw body text codepoints, whitespace included, skipping <style>/<title>.
        val firmwareHtml = "<html><head><title>T</title></head><body>\n<p>Hello <b>wide 🌍</b> world.</p><p>Second &amp; last.</p></body></html>"
        val firmwareText = android.text.Html.fromHtml(firmwareHtml, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
        val firmwareMap = EpubPosition.build(firmwareHtml, firmwareText, 0) ?: error("Clipping offset mapping failed")
        check(firmwareMap.sourceOffset(firmwareText.indexOf("world")) == 14) { "world -> ${firmwareMap.sourceOffset(firmwareText.indexOf("world"))}" }
        check(firmwareMap.sourceOffset(firmwareText.indexOf("last")) == 29)
        check(firmwareMap.renderedOffset(14) == firmwareText.indexOf("world"))
        check(firmwareMap.renderedOffset(29) == firmwareText.indexOf("last"))
        val styled = "<html><body><style>p{}</style><p>Hello world.</p></body></html>"
        val styledText = android.text.Html.fromHtml(styled, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
        check(EpubPosition.build(styled, styledText, 0)?.sourceOffset(styledText.indexOf("world")) == 6) { "Style text was counted" }

        checkCss(context)
        runSync(test, context)
    }

    fun runSync(test: Instrumentation, context: Context) {
        val file = File(context.cacheDir, "reader-sync-check.epub")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>")
            entry("book.opf", "<package><metadata><title>Sync Reader Check</title></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>")
            entry("a.xhtml", "<html><body>" + (1..150).joinToString("") { "<p>Paragraph $it. This passage has a portable text location shared with CrossPoint.</p>" } + "</body></html>")
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun open() = test.startActivitySync(Intent(context, ReaderActivity::class.java).putExtra("book_path", file.path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun waitUntil(message: String, condition: () -> Boolean) {
            repeat(150) { if (condition()) return; Thread.sleep(40) }
            error(message)
        }
        val credentials = File(context.noBackupFilesDir, "sync-account.json")
        val original = credentials.takeIf { it.exists() }?.readBytes()?.takeUnless { String(it).contains("reader-sync.test") }
        val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val key = file.canonicalPath
        prefs.edit().remove("$key.chapter").remove("$key.offset").remove("$key.finished").remove("$key.visible").remove("$key.at").commit()
        val repo = SyncRepository(context)
        var active: Activity? = null
        try {
            repo.connect("https://reader-sync.test", "reader", "secret")
            remote = JSONObject().put("device", "CrossPoint firmware").put("device_id", "firmware")
                .put("timestamp", System.currentTimeMillis() / 1000 - 60).put("percentage", 0.5)
                .put("progress", "/body/DocFragment[1]/body/p[70]/text().0")
            val firmwarePosition = remote
            val now = System.currentTimeMillis()
            val ownDevice = context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE).getString("device_id", null)
            remote = JSONObject().put("device_id", ownDevice).put("device", "CrossPoint Android")
                .put("timestamp", now / 1000 + 120).put("percentage", 0.1).put("progress", "crosspoint-launcher:0:10")
            check(repo.latestProgress("echo", now) == null) { "Our delayed upload replaced a newer local position" }
            check(repo.latestProgress("echo", 0) != null) { "A fresh local reader cannot recover its own remote backup" }
            repo.queueProgress("echo", "Echo", "", "echo.epub", 0, 40, 0.5, atMillis = now)
            repo.flushProgress()
            check(uploaded?.getString("progress") == "crosspoint-launcher:0:40") { "Own upload timestamp discarded pending local progress" }
            remote = firmwarePosition
            uploaded = null
            check(repo.latestProgress("test", System.currentTimeMillis()) == null)
            active = open()
            waitUntil("Remote text position was not restored") { prefs.getInt("$key.offset", 0) > 4000 }
            check(uploaded == null) // Opening must not upload the stale local position over the remote.
            test.runOnMainSync {
                val all = views(active!!.window.decorView)
                check(all.filterIsInstance<ScrollView>().single().scrollY > 0)
                active!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)); active!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN))
            }
            Thread.sleep(31_000) // Cross a local checkpoint without triggering a network upload.
            check(uploaded == null) { "The 30-second checkpoint uploaded instead of batching sync" }
            check(prefs.getInt("$key.offset", 0) > 4000) { "Local checkpoint was not saved" }
            test.runOnMainSync { active!!.finish() }
            test.waitForIdleSync()
            waitUntil("Reader did not upload its visible text position") { uploaded != null }
            check(uploaded!!.getString("progress").startsWith("/body/DocFragment[1]/body/p["))
            check(uploaded!!.getDouble("percentage") > 0.45)
            val sent = uploaded!!.getString("progress")
            val position = prefs.getInt("$key.offset", -1)
            prefs.edit().remove("$key.chapter").remove("$key.offset").remove("$key.finished").remove("$key.visible").remove("$key.at").commit()
            active = open()
            waitUntil("Uploaded position was not restored on a fresh local state") { prefs.getInt("$key.offset", 0) > 4000 }
            check(prefs.getInt("$key.offset", -1) == position) { "Restored ${prefs.getInt("$key.offset", -1)}, expected $position" }
            check(remote.getString("progress") == sent)

            // An older remote position cannot replace a more recent local session during offline use.
            offline = true
            test.runOnMainSync {
                active!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)); active!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN))
                active!!.finish()
            }
            test.waitForIdleSync()
            waitUntil("Offline position was not journaled") {
                context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE).all.keys.any { it.startsWith(repo.accountScope + ":") }
            }
            Thread.sleep(100) // Let the intentionally failed background flush return.
            val offlinePosition = prefs.getInt("$key.offset", -1)
            active = open()
            waitUntil("Offline reader did not reopen") {
                var loaded = false
                test.runOnMainSync { loaded = views(active!!.window.decorView).filterIsInstance<ScrollView>().single().scrollY > 0 }
                loaded
            }
            check(prefs.getInt("$key.offset", -1) == offlinePosition) { "Offline reopening lost the saved position" }
            test.runOnMainSync { active!!.finish() }
            test.waitForIdleSync()
            val pendingPosition = context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE).all
                .filterKeys { it.startsWith(repo.accountScope + ":") }.values
                .map { JSONObject(it as String).getJSONObject("body").getString("progress") }.single()
            offline = false
            SyncRepository(context).flushProgress() // A new repository must recover the persisted offline journal.
            check(uploaded!!.getString("progress") != sent)
            check(uploaded!!.getString("progress") == pendingPosition) { "Reconnect did not upload the latest offline position" }

            // A newer timestamp on another device must never move a saved reader backwards.
            prefs.edit().putLong("$key.at", System.currentTimeMillis() - 3000).commit()
            remote = JSONObject().put("device_id", "firmware").put("device", "CrossPoint firmware")
                .put("timestamp", System.currentTimeMillis() / 1000 + 60).put("percentage", 0.01)
                .put("progress", "/body/DocFragment[1]/body/p[2]/text().0")
            uploaded = null
            active = open()
            val syncJobField = ReaderActivity::class.java.getDeclaredField("syncJob").apply { isAccessible = true }
            val readyField = ReaderActivity::class.java.getDeclaredField("ready").apply { isAccessible = true }
            waitUntil("Reader did not finish checking the backward remote position") {
                var complete = false
                test.runOnMainSync {
                    complete = (syncJobField.get(active) as? kotlinx.coroutines.Job)?.isCompleted == true && readyField.getBoolean(active)
                }
                complete
            }
            check(prefs.getInt("$key.offset", -1) >= offlinePosition) { "Backward sync overwrote local progress" }
            check(test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Continue there")?.isEmpty() != false) {
                "Backward sync offered to move the reader backwards"
            }
            test.runOnMainSync { active!!.finish() }
            test.waitForIdleSync()

            // Clipping sync: firmware items come down verbatim, local clips go up, deletions travel both ways.
            val syncedBefore = repo.syncClippings
            val clips = com.crosspoint.launcher.reader.ReaderClippings(File(context.cacheDir, "clipping-check").apply { deleteRecursively() }, "book")
            synchronized(serverClips) {
                serverClips.clear()
                serverClips["fw1"] = JSONObject().put("id", "fw1").put("revision", ++clipRevision).put("deleted", 0).put("spine", 3)
                    .put("start_page", 4).put("end_page", 4).put("pages", 20).put("start_word", 2).put("end_word", 9).put("words", 8)
                    .put("chapter", "Firmware").put("text", "From the firmware").put("created_at", 1752300000)
                    .put("layout_signature", 77).put("start_offset", 100).put("end_offset", 117)
            }
            repo.syncClippings = false
            repo.syncClippings("doc", clips)
            check(clips.items.isEmpty()) { "Clipping sync ran while switched off" }
            repo.syncClippings = true
            val mine = clips.add(0, "Chapter 1", "From Android", 5, 17)
            repo.syncClippings("doc", clips)
            check(clips.items.map { it.getString("id") }.toSet() == setOf("fw1", mine.getString("id")))
            check(clips.items.first { it.getString("id") == "fw1" }.getInt("layout_signature") == 77) // Firmware layout hints survive.
            check(synchronized(serverClips) { serverClips[mine.getString("id")]!!.getInt("end_offset") == 17 })
            check(clips.pending().isEmpty())
            clips.remove(mine.getString("id"))
            synchronized(serverClips) { serverClips["fw1"] = JSONObject().put("id", "fw1").put("deleted", 1).put("revision", ++clipRevision) }
            repo.syncClippings("doc", clips)
            check(clips.items.isEmpty()) { "Remote deletion was not applied" }
            check(synchronized(serverClips) { serverClips[mine.getString("id")]!!.getInt("deleted") == 1 }) { "Local deletion was not uploaded" }
            check(clips.deletions().isEmpty())
            repo.syncClippings = syncedBefore
        } finally {
            offline = false
            test.runOnMainSync { active?.finish() }
            val pending = context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE)
            val prefix = repo.accountScope + ":"
            pending.edit().also { edit -> pending.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) } }.commit()
            repo.disconnect()
            if (original != null) credentials.writeBytes(original)
            prefs.edit().remove("$key.chapter").remove("$key.offset").remove("$key.finished").remove("$key.visible").remove("$key.at").commit()
            file.delete()
        }
    }
}
