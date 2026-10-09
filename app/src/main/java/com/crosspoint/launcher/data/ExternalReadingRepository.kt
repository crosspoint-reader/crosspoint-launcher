package com.crosspoint.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.Process
import com.crosspoint.launcher.model.ExternalReadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ExternalReadingRepository(private val context: Context, private val sync: SyncRepository) {
    private val launcherApps: LauncherApps? get() = context.getSystemService(LauncherApps::class.java)

    suspend fun loadExternalReadingState(): ExternalReadingState? = withContext(Dispatchers.IO) {
        listOfNotNull(
            runCatching { loadLibbyState() }.getOrNull(),
            runCatching { loadKindleState() }.getOrNull()
        ).maxByOrNull { it.updatedAt }
    }

    suspend fun warmKindleMetadataCache(): Boolean = withContext(Dispatchers.IO) {
        if (!sync.connected || !isOnline()) return@withContext false
        var changed = false
        kindleBooks()
            .sortedByDescending { it.second }
            .take(1)
            .forEach { (asin, _) ->
                val cacheKey = "kindle:$asin"
                val cached = cachedEnrichment(cacheKey)
                if (cached?.hasValidCover() == true) return@forEach
                val enriched = enrichFromSync("kindle:$asin") { sync.lookupKindle(asin) } ?: return@forEach
                if (enriched.title.isNotBlank()) changed = true
            }
        changed
    }

    fun kindleFilesRoot(): File =
        File(Environment.getExternalStorageDirectory(), KINDLE_FILES_PATH)

    fun launch(state: ExternalReadingState): Boolean {
        state.launchUri?.let { uri ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setPackage(state.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(intent)
            }.isSuccess
        }

        val shortcutId = state.shortcutId ?: return false
        return runCatching {
            launcherApps?.startShortcut(
                state.packageName,
                shortcutId,
                null,
                null,
                Process.myUserHandle()
            )
        }.isSuccess
    }

    private fun loadLibbyState(): ExternalReadingState? {
        val shortcuts = shortcutsFor(LIBBY_PACKAGE)
        val titleShortcut = shortcuts
            .filter { it.isEnabled }
            .firstOrNull { it.isLibbyTitleShortcut() }
            ?: shortcuts
                .filter { it.isEnabled }
                .firstOrNull { it.shortLabel?.toString()?.isBookLikeLibbyLabel() == true }

        val title = titleShortcut?.shortLabel?.toString()?.trim().orEmpty()
        if (title.isBlank()) return null
        val shortcut = titleShortcut ?: return null

        val cached = cachedEnrichment(title)
        val enriched = if (cached?.hasValidCover() == true || !isOnline()) {
            cached
        } else {
            enrichFromSync(title) { sync.lookupExternal(title) } ?: cached
        }

        return ExternalReadingState(
            source = "Libby",
            packageName = LIBBY_PACKAGE,
            title = title,
            author = enriched?.author.orEmpty(),
            coverPath = enriched?.coverPath,
            shortcutId = shortcut.id,
            updatedAt = shortcut.lastChangedTimestamp
        )
    }

    private fun loadKindleState(): ExternalReadingState? {
        val book = kindleBooks()
            .filter { it.second > 0L }
            .maxByOrNull { it.second }
            ?: return null

        val asin = book.first
        val cached = cachedEnrichment("kindle:$asin")
        val enriched = if (cached?.hasValidCover() == true || !isOnline()) {
            cached
        } else {
            enrichFromSync("kindle:$asin") { sync.lookupKindle(asin) } ?: cached
        }
        return ExternalReadingState(
            source = "Kindle",
            packageName = KINDLE_PACKAGE,
            title = enriched?.title?.takeIf { it.isNotBlank() } ?: "Kindle book",
            author = enriched?.author.orEmpty(),
            coverPath = enriched?.coverPath,
            launchUri = "kindle://book/?action=open&asin=$asin",
            updatedAt = book.second
        )
    }

    /** ASIN + recent-modified time, where the ROM lets an app read Kindle's folder directly (e.g. Boox). */
    private fun kindleBooks(): List<Pair<String, Long>> =
        kindleBookDirs().map { it.name to maxModified(it, depth = 2) }.toList()

    private fun maxModified(file: File, depth: Int): Long {
        var max = file.lastModified()
        if (depth <= 0 || !file.isDirectory) return max
        file.listFiles()?.forEach { child ->
            val modified = if (child.isDirectory) maxModified(child, depth - 1) else child.lastModified()
            if (modified > max) max = modified
        }
        return max
    }

    private fun kindleBookDirs(): Sequence<File> {
        val root = kindleFilesRoot()
        if (!root.isDirectory) return emptySequence()
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && KINDLE_ASIN_REGEX.matches(it.name) }
            ?: emptySequence()
    }

    private fun enrichFromSync(key: String, lookup: () -> com.crosspoint.launcher.model.Book?): EnrichedBook? {
        if (!sync.connected || !isOnline()) return null
        val attempt = File(metadataCacheFile(key).path + ".attempt")
        if (attempt.exists() && System.currentTimeMillis() - attempt.lastModified() < 15 * 60_000) return null
        attempt.writeText("")
        return runCatching { lookup() }.getOrNull()?.let {
            EnrichedBook(it.title, it.author, "", it.coverPath)
        }?.also { cacheEnrichment(key, it) }
    }

    private fun cachedEnrichment(title: String): EnrichedBook? {
        val file = metadataCacheFile(title)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val coverPath = json.optString("cover_path").ifBlank { null }
                ?.takeIf { isValidCoverImage(File(it)) }
            EnrichedBook(
                title = json.optString("title"),
                author = json.optString("author"),
                coverUrl = json.optString("cover_url"),
                coverPath = coverPath
            ).takeIf { it.title.isNotBlank() }
        }.getOrNull()
    }

    private fun cacheEnrichment(sourceTitle: String, book: EnrichedBook) {
        runCatching {
            val file = metadataCacheFile(sourceTitle)
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().apply {
                put("title", book.title)
                put("author", book.author)
                put("cover_url", book.coverUrl)
                put("cover_path", book.coverPath ?: "")
            }.toString())
        }
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun shortcutsFor(packageName: String): List<ShortcutInfo> =
        runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                )
            launcherApps?.getShortcuts(query, Process.myUserHandle()).orEmpty()
        }.getOrDefault(emptyList())

    private fun ShortcutInfo.isLibbyTitleShortcut(): Boolean {
        val shortcutExtra = safeIntents()
            .mapNotNull { it.extras?.getString("shortcut") }
            .firstOrNull()
        return shortcutExtra == "nav:title" || shortLabel?.toString()?.isBookLikeLibbyLabel() == true
    }

    private fun ShortcutInfo.safeIntents() =
        runCatching { intents?.toList().orEmpty() }.getOrDefault(emptyList())

    private fun String.isBookLikeLibbyLabel(): Boolean {
        val clean = trim()
        return clean.isNotBlank() && !clean.equals("Library", ignoreCase = true) &&
            !clean.equals("Shelf", ignoreCase = true)
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun safeHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun metadataCacheFile(title: String): File =
        File(File(context.cacheDir, "external_reading_metadata").apply { mkdirs() }, "${safeHash(normalizeTitle(title))}.json")

    private fun isValidCoverImage(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth >= 80 && opts.outHeight >= 120
    }

    private data class EnrichedBook(
        val title: String,
        val author: String,
        val coverUrl: String,
        val coverPath: String? = null
    ) {
        fun hasValidCover(): Boolean = coverPath?.let { isValidCoverPath(it) } == true
    }

    private companion object {
        const val LIBBY_PACKAGE = "com.overdrive.mobile.android.libby"
        const val KINDLE_PACKAGE = "com.amazon.kindle"
        const val KINDLE_FILES_PATH = "Android/data/com.amazon.kindle/files"
        val KINDLE_ASIN_REGEX = Regex("^[A-Z0-9]{10}$")

        fun isValidCoverPath(path: String): Boolean {
            val file = File(path)
            if (!file.exists() || file.length() < 1024) return false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            return opts.outWidth >= 80 && opts.outHeight >= 120
        }
    }
}
