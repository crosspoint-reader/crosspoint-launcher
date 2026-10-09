package com.crosspoint.launcher.data

import com.crosspoint.launcher.reader.ReaderClippings
import android.content.Context
import android.util.AtomicFile
import com.crosspoint.launcher.model.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.*

/** CrossPoint Sync's device API. Call on Dispatchers.IO; requests are deliberately sequential. */
class SyncRepository(private val context: Context) {
    private val credentials = AtomicFile(File(context.noBackupFilesDir, "sync-account.json"))
    private val cacheDir = File(context.cacheDir, "sync").apply { mkdirs() }
    private fun account(): JSONObject? = runCatching { JSONObject(credentials.openRead().use { it.reader().readText() }) }.getOrNull()
    val connected: Boolean get() = account() != null
    val server: String get() = account()?.optString("server") ?: DEFAULT_SERVER
    val username: String get() = account()?.optString("username").orEmpty()

    @Synchronized fun connect(server: String, username: String, password: String, signup: Boolean = false) {
        val url = URL(server.trim().trimEnd('/'))
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) {
            "Enter an HTTPS server address"
        }
        require(Regex("[A-Za-z0-9._@+\\-]{1,64}").matches(username.trim()) && password.isNotEmpty()) { "Enter your sync username and password" }
        require(username.none { it == '\r' || it == '\n' }) { "Invalid username" }
        val candidate = JSONObject().put("server", url.toString().trimEnd('/'))
            .put("username", username.trim()).put("key", hash(password.toByteArray(), "MD5"))
        if (signup) request(candidate, "/users/create", JSONObject()
            .put("username", username.trim()).put("password", candidate.getString("key")), "POST")
        request(candidate, "/users/auth") // Verify before replacing a working account.
        val output = credentials.startWrite()
        try { output.write(candidate.toString().toByteArray()); credentials.finishWrite(output) }
        catch (e: Exception) { credentials.failWrite(output); throw e }
    }

    @Synchronized fun disconnect() {
        accountScope?.let { id ->
            AtomicFile(File(cacheDir, "$id-stats.json")).delete()
            if (statsMemory?.first == id) statsMemory = null
        }
        credentials.delete()
    }

    val accountScope: String? get() = account()?.let { scope(it) }

    private fun scope(account: JSONObject) = hash((account.getString("server") + account.getString("username")).toByteArray())

    private fun documents(account: JSONObject): JSONArray {
        val cache = File(cacheDir, "${scope(account)}-documents.json")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < 15 * 60_000) {
            runCatching { return JSONObject(cache.readText()).getJSONArray("items") }
        }
        return try {
            val json = request(account, "/api/v1/documents")
            cache.writeText(json.toString())
            json.getJSONArray("items")
        } catch (e: Exception) {
            // Offline copy is scoped to this server/account, never another user's library.
            if (e is SecurityException) throw e
            if (cache.isFile) JSONObject(cache.readText()).getJSONArray("items") else throw e
        }
    }

    // ponytail: one request sequence per account; use a work queue if parallel sync becomes necessary.
    /** Exact, unambiguous matching; never guess a cover for another author. */
    @Synchronized fun lookup(title: String, author: String = ""): Book? {
        val account = account() ?: return null
        val match = matchingDocument(account, title, author) ?: return null
        val document = match.getString("document")
        val cover = cover(account, document, match.optString("cover_url").takeUnless { it == "null" }.orEmpty())
        return Book("", match.optString("title").takeUnless { it == "null" }.orEmpty(),
            match.optString("author").takeUnless { it == "null" }.orEmpty(), cover, 0)
    }

    private fun matchingDocument(account: JSONObject, title: String, author: String): JSONObject? {
        val docs = documents(account)
        val matches = (0 until docs.length()).map { docs.getJSONObject(it) }.filter {
            val sameTitle = normalize(it.optString("title")) == normalize(title) && title.isNotBlank()
            val sameAuthor = author.isBlank() || normalize(it.optString("author")) == normalize(author)
            sameTitle && sameAuthor
        }
        return matches.singleOrNull()
    }

    @Synchronized fun lookupExternal(title: String, author: String = "", sourceId: String? = null): Book? {
        lookup(title, author)?.let { return it }
        val account = account() ?: return null
        val document = sourceId ?: "external-" + hash((title + "\n" + author).toByteArray()).take(32)
        request(account, "/api/v1/documents", JSONObject().put("items", JSONArray().put(JSONObject()
            .put("document", document).put("title", title).put("author", author))))
        return Book("", title, author, cover(account, document, ""), 0)
    }

    @Synchronized fun lookupKindle(asin: String): Book? {
        val account = account() ?: return null
        val result = request(account, "/api/v1/connectors/kindle/lookup",
            JSONObject().put("external_id", "EBOK:$asin"), "POST")
        val metadata = result.optJSONObject("book") ?: return null
        val title = metadata.optString("title").takeUnless { it.isBlank() || it == asin } ?: return null
        return lookupExternal(title, metadata.optString("author").takeUnless { it == "null" }.orEmpty(), "kindle-$asin")
    }

    /** Once signed in, register missing book metadata so the service can look up its cover. */
    @Synchronized fun fetchCover(book: Book): Book {
        val account = account() ?: error("Connect CrossPoint Sync first")
        cachedCover(book)?.let { return book.copy(coverPath = it) }
        val attempt = File(localCoverFile(book).path + ".attempt")
        if (attempt.isFile && System.currentTimeMillis() - attempt.lastModified() < 24 * 60 * 60_000L) return book
        lookup(book.title, book.author)?.let { found ->
            found.coverPath?.let { localCoverFile(book).writeText(it) }
            if (found.coverPath == null) attempt.writeText("") else attempt.delete()
            return book.copy(coverPath = found.coverPath ?: book.coverPath)
        }
        val file = File(book.filePath)
        val document = documentId(book)
        request(account, "/api/v1/documents", JSONObject().put("items", JSONArray().put(JSONObject()
            .put("document", document).put("title", book.title).put("author", book.author)
            .put("filename", file.name).put("filesize", file.length()))))
        File(cacheDir, "${scope(account)}-documents.json").delete()
        val cover = cover(account, document, "")
        cover?.let { localCoverFile(book).writeText(it) }
        if (cover == null) attempt.writeText("") else attempt.delete()
        return book.copy(coverPath = cover ?: book.coverPath)
    }

    fun documentId(book: Book): String {
        val account = account()
        val cached = File(localCoverFile(book).path + ".${account?.let { scope(it) }.orEmpty()}.document")
        if (cached.isFile) cached.readText().takeIf { Regex("[A-Za-z0-9._-]{1,64}").matches(it) }?.let { return it }
        runCatching {
            account?.let { matchingDocument(it, book.title, book.author)?.getString("document") }
        }.getOrNull()?.takeIf { Regex("[A-Za-z0-9._-]{1,64}").matches(it) }?.let {
            cached.writeText(it)
            return it
        }
        val digest = MessageDigest.getInstance("MD5")
        File(book.filePath).inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { cached.writeText(it) }
    }

    private val pending = context.getSharedPreferences("sync_pending", Context.MODE_PRIVATE)
    private val deviceId: String = pending.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
        pending.edit().putString("device_id", it).apply()
    }

    /** Apply journals locally before any networking, so leaving the reader offline is safe. */
    fun queueProgress(document: String, title: String, author: String, filename: String, chapter: Int, offset: Int, percentage: Double,
        locator: String = "crosspoint-launcher:$chapter:$offset", atMillis: Long = System.currentTimeMillis()) = synchronized(journalLock) {
        val account = account() ?: return@synchronized
        val pendingKey = "${scope(account)}:$document"
        val previous = pending.getString(pendingKey, null)?.let { JSONObject(it) }
        if (previous != null && previous.optLong("at_ms", previous.optLong("at") * 1000) > atMillis) return@synchronized
        val body = JSONObject().put("document", document).put("device_id", deviceId).put("device", DEVICE_NAME)
            .put("progress", locator).put("percentage", percentage.coerceIn(0.0, 1.0))
            .put("metadata", JSONObject().put("title", title).put("authors", author).put("filename", filename))
        pending.edit().putString(pendingKey, JSONObject()
            .put("at_ms", atMillis).put("at", atMillis / 1000).put("body", body).toString()).apply()
    }

    /** Off by default, like the firmware's Sync Clippings toggle in CrossPoint Sync settings. */
    var syncClippings: Boolean
        get() = pending.getBoolean("sync_clippings", false)
        set(value) { pending.edit().putBoolean("sync_clippings", value).apply() }

    /**
     * The firmware's clipping sync for one book (sync_version 2): tombstones, then pending uploads, then every remote
     * revision. Server ids, revisions and layout hints are kept verbatim so the firmware can restore them.
     */
    fun syncClippings(document: String, clippings: ReaderClippings) {
        if (!syncClippings) return
        val account = account() ?: return
        val path = "/api/v1/clippings/${URLEncoder.encode(document, "UTF-8")}"
        check(request(account, "$path?cursor=0&limit=1&format=reader").optInt("sync_version") == 2) {
            "Clipping sync needs a CrossPoint Sync server with clipping support"
        }
        clippings.deletions().chunked(50).forEach { ids ->
            request(account, path, JSONObject().put("items", JSONArray(ids.map { JSONObject().put("id", it).put("deleted", 1) })))
            clippings.synced(emptyList(), ids)
        }
        clippings.pending().chunked(50).forEach { batch ->
            request(account, path, JSONObject().put("items", JSONArray(batch.map { item ->
                JSONObject(item.toString()).apply { remove("pending"); remove("revision") }
            })))
            clippings.synced(batch.map { it.getString("id") }, emptyList())
        }
        // ponytail: rescans the book's whole clipping history each sync, like the firmware; persist the cursor if it grows.
        var cursor = 0L
        do {
            val page = request(account, "$path?cursor=$cursor&limit=100&format=reader")
            val items = page.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) clippings.applyRemote(items!!.getJSONObject(i))
            val next = page.optLong("cursor", cursor)
            check(next >= cursor && (next > cursor || !page.optBoolean("more"))) { "Invalid clipping pagination" }
            cursor = next
        } while (page.optBoolean("more"))
    }

    data class RemoteProgress(val locator: String, val percentage: Double, val timestamp: Long, val device: String)

    /** Newest record wins, including reading backwards; never replace a newer offline position. */
    fun latestProgress(document: String, localAtMillis: Long): RemoteProgress? {
        val account = account() ?: return null
        val devices = request(account, "/api/v1/progress/${URLEncoder.encode(document, "UTF-8")}").getJSONArray("devices")
        val latest = (0 until devices.length()).map { devices.getJSONObject(it) }
            .maxByOrNull { it.optLong("timestamp") } ?: return null
        val timestamp = latest.optLong("timestamp") * 1000
        val queuedKey = "${scope(account)}:$document"
        val queuedValue = pending.getString(queuedKey, null)
        val queuedAt = queuedValue?.let { JSONObject(it).let { value -> value.optLong("at_ms", value.optLong("at") * 1000) } } ?: 0
        // Upload timestamps describe receipt, not when this device last turned a page.
        if (latest.optString("device_id") == deviceId && maxOf(localAtMillis, queuedAt) > 0) return null
        if (timestamp <= maxOf(localAtMillis, queuedAt)) return null
        val percentage = latest.optDouble("percentage", Double.NaN)
        if (!percentage.isFinite() || percentage !in 0.0..1.0) return null
        val locator = latest.optString("progress").takeUnless { it == "null" }.orEmpty()
        return RemoteProgress(locator, percentage, timestamp, latest.optString("device").ifBlank { "another device" })
    }

    fun flushProgress() = synchronized(progressLock) {
        val account = account() ?: return@synchronized
        val prefix = scope(account) + ":"
        for ((key, value) in pending.all) {
            if (!key.startsWith(prefix) || value !is String) continue
            val queued = JSONObject(value)
            val body = queued.getJSONObject("body").put("device", DEVICE_NAME)
            val remote = request(account, "/api/v1/progress/${body.getString("document")}").getJSONArray("devices")
            val newest = (0 until remote.length()).map { remote.getJSONObject(it) }
                .filter { it.optString("device_id") != deviceId }.maxOfOrNull { it.optLong("timestamp") } ?: 0
            // An old offline position must not overwrite a newer reading session on another device.
            if (newest <= queued.getLong("at")) request(account, "/api/v1/progress", body)
            synchronized(journalLock) {
                if (pending.getString(key, null) == value) pending.edit().remove(key).apply()
            }
        }
    }

    // One account snapshot in memory; disk survives process restarts without growing the heap.
    fun memoryReadingStats(): JSONObject? = statsMemory?.takeIf { it.first == accountScope }?.second

    fun cachedReadingStats(): JSONObject? {
        val id = accountScope ?: return null
        memoryReadingStats()?.let { return it }
        return runCatching {
            val data = AtomicFile(File(cacheDir, "$id-stats.json")).openRead().use { JSONObject(it.reader().readText()) }
            data.getJSONObject("summary"); data.getJSONObject("activity"); data.getJSONArray("books")
            if (accountScope != id) return null
            statsMemory = id to data
            data
        }.getOrNull()
    }

    @Synchronized fun readingStats(): JSONObject {
        val account = account() ?: error("Log in to view your reading stats")
        val data = JSONObject().put("summary", request(account, "/api/v1/stats/summary"))
            .put("activity", activityStats()).put("books", request(account, "/api/v1/progress?limit=500").getJSONArray("items"))
        val id = scope(account)
        if (accountScope == id) {
            statsMemory = id to data
            val file = AtomicFile(File(cacheDir, "$id-stats.json"))
            runCatching {
                val output = file.startWrite()
                try { output.write(data.toString().toByteArray()); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
        }
        return data
    }

    @Synchronized fun activityStats(): JSONObject {
        val account = account() ?: error("Log in to view your reading stats")
        val timezone = -java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        return request(account, "/api/v1/stats/activity?tz=$timezone")
    }

    private fun localCoverFile(book: Book) = File(cacheDir, "local-" + hash(
        (book.filePath + ":" + book.lastModified + ":" + File(book.filePath).length()).toByteArray()))

    fun cachedCover(book: Book): String? = runCatching {
        localCoverFile(book).readText().takeIf { File(it).isFile }
    }.getOrNull()

    private fun cover(account: JSONObject, document: String, knownUrl: String): String? {
        val id = hash((scope(account) + document).toByteArray())
        val output = File(cacheDir, "$id.jpg")
        if (output.length() > 0) return output.absolutePath
        val attempt = File(cacheDir, "$id.attempt")
        if (attempt.isFile && System.currentTimeMillis() - attempt.lastModified() < 24 * 60 * 60_000L) return null
        val url = knownUrl.ifBlank {
            request(account, "/api/v1/documents/${URLEncoder.encode(document, "UTF-8")}/cover")
                .optString("url").takeUnless { it == "null" }.orEmpty()
        }
        if (url.isBlank()) { attempt.writeText(""); return null }
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Cover URL must use HTTPS" }
        // Cover hosts receive no sync credentials. Never follow redirects with authentication.
        val conn = coverConnection(parsed)
        val download = File(cacheDir, "$id.download")
        try {
            check(conn.responseCode in 200..299) { "Cover download failed (${conn.responseCode})" }
            conn.inputStream.use { input ->
                download.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        check(total <= 8 * 1024 * 1024) { "Cover image is too large" }
                        out.write(buffer, 0, count)
                    }
                }
            }
            check(CoverImages.save({ download.inputStream() }, output)) { "Invalid cover image" }
            return output.absolutePath
        } finally { conn.disconnect(); download.delete() }
    }

    private fun request(account: JSONObject, path: String, body: JSONObject? = null, method: String = "PUT"): JSONObject {
        val conn = connection(URL(account.getString("server") + path))
        try {
            conn.setRequestProperty("x-auth-user", account.getString("username"))
            conn.setRequestProperty("x-auth-key", account.getString("key"))
            if (body != null) {
                conn.requestMethod = method
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (conn.responseCode == 403 && path == "/users/create") error("Sign-up is disabled or these account details are invalid. Try logging in.")
            if (conn.responseCode == 401 || conn.responseCode == 403) throw SecurityException("Sync sign-in expired or incorrect")
            if (conn.responseCode == 402) error("That username is already registered. Try Log in.")
            check(conn.responseCode in 200..299) { "Sync request failed (${conn.responseCode}); check the server address or registration settings" }
            return conn.inputStream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer); if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "Sync response is too large" }
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8"))
            }
        } finally { conn.disconnect() }
    }

    private fun coverConnection(initial: URL): HttpURLConnection {
        var url = initial
        repeat(4) {
            require(url.protocol == "https" && url.userInfo == null) { "Unsafe cover redirect" }
            val connection = connection(url)
            val status = try { connection.responseCode } catch (e: Exception) { connection.disconnect(); throw e }
            if (status !in listOf(301, 302, 303, 307, 308)) return connection
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            require(!next.isNullOrBlank()) { "Invalid cover redirect" }
            url = URL(url, next)
        }
        error("Too many cover redirects")
    }

    private fun connection(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 12_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json, image/*")
    }

    companion object {
        private const val DEVICE_NAME = "CrossPoint Android"
        @Volatile private var statsMemory: Pair<String, JSONObject>? = null
        private val progressLock = Any()
        private val journalLock = Any()
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var flushJob: Job? = null
        private var flushAgain = false
        @Synchronized fun flushInBackground(context: Context) {
            flushAgain = true
            if (flushJob?.isActive == true) return
            val app = context.applicationContext
            flushJob = background.launch {
                do {
                    synchronized(Companion) { flushAgain = false }
                    runCatching { SyncRepository(app).flushProgress() }
                } while (synchronized(Companion) {
                    if (flushAgain) true else { flushJob = null; false }
                })
            }
        }
        /** Resolve a first-open book even if its reader activity closes before networking finishes. */
        fun queueBookInBackground(context: Context, book: Book, chapter: Int, offset: Int, percentage: Double,
            locator: String, atMillis: Long, expectedAccount: String) {
            val app = context.applicationContext
            background.launch {
                runCatching {
                    val repo = SyncRepository(app)
                    if (repo.accountScope != expectedAccount) return@runCatching
                    val document = repo.documentId(book)
                    if (repo.accountScope != expectedAccount) return@runCatching
                    repo.queueProgress(document, book.title, book.author, File(book.filePath).name, chapter, offset, percentage, locator, atMillis)
                    repo.flushProgress()
                }
            }
        }
        const val DEFAULT_SERVER = "https://sync.crosspointreader.com"
        private fun hash(bytes: ByteArray, algorithm: String = "SHA-256") =
            MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
        private fun normalize(value: String) = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}
