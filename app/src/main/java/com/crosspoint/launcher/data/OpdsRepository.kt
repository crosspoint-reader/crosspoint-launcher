package com.crosspoint.launcher.data

import android.content.Context
import android.os.Environment
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.crosspoint.launcher.model.OpdsEntry
import com.crosspoint.launcher.model.OpdsFeed
import com.crosspoint.launcher.model.OpdsServer
import com.crosspoint.launcher.model.OpdsLink
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Stores catalog accounts and streams OPDS 1/2 feeds and acquisitions.
 */
class OpdsRepository(private val context: Context) {

    val http = OpdsHttp(context)

    private val dataStore = settingsDataStore(context)
    private val key = stringPreferencesKey("opds_servers")

    val servers: Flow<List<OpdsServer>> = dataStore.data.map { p ->
        parseServers(p[key])
    }

    suspend fun currentServers(): List<OpdsServer> =
        parseServers(readRaw())

    suspend fun upsert(server: OpdsServer) {
        val url = URL(server.url)
        require(url.protocol in listOf("http", "https") && url.host.isNotBlank() && url.userInfo == null) { "Enter an HTTP or HTTPS catalog URL" }
        val list = currentServers().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) { if (list[idx] != server) http.clear(list[idx]); list[idx] = server } else list.add(server)
        save(list)
    }

    suspend fun delete(id: String) {
        currentServers().firstOrNull { it.id == id }?.let { http.clear(it) }
        save(currentServers().filter { it.id != id })
    }

    private suspend fun readRaw(): String? = dataStore.data.first()[key]

    private suspend fun save(list: List<OpdsServer>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("url", s.url)
                put("username", s.username)
                // Lightly obfuscated (base64); not strong encryption — matches reader's intent.
                put("password", Base64.encodeToString(s.password.toByteArray(), Base64.NO_WRAP))
            })
        }
        dataStore.edit { it[key] = arr.toString() }
    }

    private fun parseServers(raw: String?): List<OpdsServer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OpdsServer(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    username = o.optString("username"),
                    password = o.optString("password").let {
                        runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrDefault(it)
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchFeed(server: OpdsServer, path: String): OpdsFeed = withContext(Dispatchers.IO) {
        val conn = http.open(server, resolveUrl(server.url, path))
        try {
            val bytes = conn.inputStream.use { OpdsHttp.read(it) }
            coroutineContext.ensureActive()
            val source = String(bytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (source.startsWith("{")) OpdsJson.parse(OpdsJson.document(source), conn.url.toString())
            else OpdsXml.parse(bytes.inputStream(), conn.url.toString())
        } finally { conn.disconnect() }
    }

    suspend fun publication(server: OpdsServer, entry: OpdsEntry): OpdsEntry {
        val href = entry.selfHref ?: return entry
        return fetchFeed(server, href).entries.firstOrNull { it.type == OpdsEntry.Type.BOOK } ?: entry
    }

    suspend fun resolveSearchPath(
        server: OpdsServer, feedPath: String, searchReference: String, terms: String,
        fields: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val reference = resolveUrl(resolveUrl(server.url, feedPath), searchReference)
        val template = if ('{' in reference) reference else {
            val conn = http.open(server, reference)
            try {
                val xml = conn.inputStream.use { OpdsHttp.read(it) }
                resolveUrl(conn.url.toString(), OpdsXml.parseOpenSearchTemplate(xml.inputStream())
                    ?: error("Catalog has no search template"))
            } finally { conn.disconnect() }
        }
        OpdsJson.expandSearch(template, searchValues(terms) + fields.filterValues { it.isNotBlank() })
    }

    /** Explicit acquisition: intermediary documents are shown before another action is taken. */
    data class Acquisition(val file: File? = null, val publication: OpdsEntry? = null,
        val externalUrl: String? = null, val mime: String = EPUB_MIME)

    suspend fun acquire(server: OpdsServer, entry: OpdsEntry, link: OpdsLink,
        onProgress: (Long, Long) -> Unit): Acquisition = withContext(Dispatchers.IO) {
        if (link.mediaType.contains("html")) return@withContext Acquisition(externalUrl = link.href)
        val conn = http.open(server, link.href, "$EPUB_MIME, application/vnd.readium.lcp.license.v1.0+json, " + OpdsHttp.ACCEPT + ", */*;q=0.5")
        try {
            val mime = conn.contentType.orEmpty().substringBefore(';').trim().lowercase()
                .ifBlank { link.mediaType }
            if (mime.contains("html")) return@withContext Acquisition(externalUrl = conn.url.toString())
            if (mime.contains("json") && mime != "application/vnd.readium.lcp.license.v1.0+json") {
                val obj = OpdsJson.document(String(conn.inputStream.use { OpdsHttp.read(it) }))
                val publication = if (obj.has("metadata")) OpdsJson.parse(obj, conn.url.toString()).entries
                    .firstOrNull { it.type == OpdsEntry.Type.BOOK } else null
                return@withContext Acquisition(publication = publication ?: error(
                    obj.optString("detail").ifBlank { "Catalog returned no publication. Check your shelf for this loan." }))
            }
            val extension = when (mime) {
                EPUB_MIME -> "epub"
                "application/vnd.readium.lcp.license.v1.0+json" -> "lcpl"
                "application/pdf" -> "pdf"
                "application/octet-stream", "application/zip", "" -> when {
                    link.mediaType == EPUB_MIME || URL(link.href).path.endsWith(".epub", true) -> "epub"
                    else -> return@withContext Acquisition(externalUrl = conn.url.toString())
                }
                else -> return@withContext Acquisition(externalUrl = conn.url.toString())
            }
            val dir = File(Environment.getExternalStorageDirectory(), "Books").apply { mkdirs() }
            val partial = File.createTempFile(".opds-", ".part", dir)
            try {
                val total = conn.contentLengthLong
                require(total <= 512L * 1024 * 1024) { "Book exceeds the 512 MB download limit" }
                conn.inputStream.use { input -> partial.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        done += count
                        require(done <= 512L * 1024 * 1024) { "Book exceeds the 512 MB download limit" }
                        out.write(buffer, 0, count)
                        val percent = if (total > 0) (done * 100 / total).toInt() else (done / (1024 * 1024)).toInt()
                        coroutineContext.ensureActive()
                        if (percent != lastPercent) { onProgress(done, total); lastPercent = percent }
                    }
                    check(done > 0 && (total < 0 || done == total)) { "Incomplete book download" }
                } }
                if (extension == "epub") java.util.zip.ZipFile(partial).use {
                    check(it.getEntry("META-INF/container.xml") != null) { "The download is not an EPUB" }
                }
                if (extension == "lcpl") {
                    val license = partial.inputStream().use { OpdsJson.document(String(OpdsHttp.read(it))) }
                    check(license.has("id") && license.has("encryption") && license.has("links")) { "Invalid LCP license" }
                }
                if (extension == "pdf") partial.inputStream().use {
                    check(String(ByteArray(5).also { bytes -> it.read(bytes) }) == "%PDF-") { "Invalid PDF" }
                }
                coroutineContext.ensureActive()
                val name = sanitize((if (entry.author.isBlank()) "" else entry.author + " - ") + entry.title)
                var target = File(dir, "$name.$extension")
                var index = 1
                while (!target.createNewFile()) { target = File(dir, "$name (${index++}).$extension") }
                try { check(partial.renameTo(target)) { "Could not save book" } }
                catch (e: Exception) { target.delete(); throw e }
                Acquisition(file = target, mime = if (extension == "epub") EPUB_MIME else mime)
            } finally { partial.delete() }
        } finally { conn.disconnect() }
    }

    suspend fun cover(server: OpdsServer, href: String): File = withContext(Dispatchers.IO) {
        val conn = http.open(server, href, "image/*")
        try {
            val file = File.createTempFile("opds-cover-", ".image", context.cacheDir)
            try {
                conn.inputStream.use { input -> file.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 4 * 1024 * 1024) { "Cover exceeds the 4 MB limit" }
                        out.write(buffer, 0, count)
                    }
                } }
                file
            }
            catch (e: Exception) { file.delete(); throw e }
        } finally { conn.disconnect() }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "book" }

    companion object {
        private fun searchValues(terms: String) = mapOf("query" to terms, "q" to terms, "searchTerms" to terms,
            "count" to "50", "startIndex" to "1", "startPage" to "1", "language" to java.util.Locale.getDefault().toLanguageTag(),
            "inputEncoding" to "UTF-8", "outputEncoding" to "UTF-8")
        fun encodeSearch(template: String, terms: String) = OpdsJson.expandSearch(template, searchValues(terms))
        fun resolveUrl(base: String, href: String): String {
            // URL's query-only resolution drops the final path segment on Android/Java.
            if (href.startsWith("?")) return base.substringBefore('#').substringBefore('?') + href
            return URL(URL(base), href).toString()
        }
    }
}

/** Minimal OPDS/Atom feed parser using XmlPullParser. */
object OpdsXml {
    fun parseOpenSearchTemplate(input: java.io.InputStream): String? {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(input, null) }
        val candidates = ArrayList<Pair<String, String>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Url") {
                val template = parser.getAttributeValue(null, "template")
                val type = parser.getAttributeValue(null, "type").orEmpty()
                if (template != null && Regex("\\{searchTerms\\??\\}", RegexOption.IGNORE_CASE).containsMatchIn(template)) {
                    candidates.add(type to template)
                }
            }
            event = parser.next()
        }

        return candidates.firstOrNull { (type, _) ->
            type.contains("atom", ignoreCase = true) ||
                type.contains("opds", ignoreCase = true) ||
                type.contains("xml", ignoreCase = true)
        }?.second ?: candidates.firstOrNull()?.second
    }

    fun parse(input: java.io.InputStream, feedUrl: String): OpdsFeed {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(input, null) }

        var feedTitle = "Catalog"
        var sawFeedTitle = false
        val entries = ArrayList<OpdsEntry>()
        var nextUrl: String? = null
        var prevUrl: String? = null
        var searchTemplate: String? = null
        var firstUrl: String? = null
        var lastUrl: String? = null

        // entry-local accumulators
        var inEntry = false
        var title = ""
        var author = ""
        var acquisitionHref: String? = null
        var navHref: String? = null
        var thumb: String? = null
        var inAuthor = false
        var description = ""
        val entryLinks = ArrayList<OpdsLink>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = true; title = ""; author = ""
                        acquisitionHref = null; navHref = null; thumb = null; description = ""; entryLinks.clear()
                    }
                    "author" -> inAuthor = true
                    "title" -> {
                        val t = readText(parser)
                        if (inEntry) title = t else if (!sawFeedTitle) { feedTitle = t; sawFeedTitle = true }
                    }
                    "summary", "content" -> if (inEntry) description = readText(parser).take(16384)
                    "name" -> if (inAuthor && inEntry) author = readText(parser)
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel") ?: ""
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val href = OpdsRepository.resolveUrl(feedUrl, parser.getAttributeValue(null, "href") ?: "")
                        if (inEntry) {
                            entryLinks.add(OpdsLink(href, type.substringBefore(';'), listOf(rel.ifBlank { if (type.contains("epub")) "acquisition" else "" }), parser.getAttributeValue(null, "title").orEmpty()))
                            when {
                                rel.contains("acquisition") || type.contains("epub") ->
                                    if (acquisitionHref == null) acquisitionHref = href
                                rel.contains("image") || rel.contains("thumbnail") ->
                                    thumb = href
                                type.contains("atom") -> if (navHref == null) navHref = href
                                else -> if (navHref == null && acquisitionHref == null) navHref = href
                            }
                        } else {
                            when {
                                rel == "first" -> firstUrl = href
                                rel == "last" -> lastUrl = href
                                rel == "next" -> nextUrl = href
                                rel == "previous" || rel == "prev" -> prevUrl = href
                                rel == "search" -> {
                                    // Usually an OpenSearch descriptor URL; resolve before searching.
                                    if (searchTemplate == null) searchTemplate = href
                                }
                            }
                        }
                    }
                    "Url" -> {
                        // OpenSearch inline template: <Url type="application/atom+xml" template="...{searchTerms}"/>
                        val tmpl = parser.getAttributeValue(null, "template")
                        if (tmpl != null && tmpl.contains("{searchTerms}")) searchTemplate = tmpl
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        if (inEntry) {
                            val isBook = acquisitionHref != null
                            val href = acquisitionHref ?: navHref
                            if (href != null && title.isNotBlank()) {
                                entries.add(
                                    OpdsEntry(
                                        type = if (isBook) OpdsEntry.Type.BOOK else OpdsEntry.Type.NAVIGATION,
                                        title = title.trim(),
                                        author = author.trim(),
                                        href = href,
                                        thumbnailHref = thumb, description = description, links = entryLinks.toList(),
                                        selfHref = entryLinks.firstOrNull { it.hasRel("self") && it.mediaType.contains("atom") }?.href
                                    )
                                )
                            }
                        }
                        inEntry = false
                    }
                }
            }
            event = parser.next()
        }
        return OpdsFeed(feedTitle, entries, nextUrl, prevUrl, searchTemplate, firstUrl, lastUrl, url = feedUrl)
    }

    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType == XmlPullParser.TEXT) text.append(parser.text)
        }
        return text.toString()
    }
}
