package com.crosspoint.launcher.data

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.crosspoint.launcher.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Scans common storage folders for EPUB files and extracts their cover image + metadata by
 * parsing the EPUB container/OPF directly (no third-party dependency). Covers are cached as
 * downscaled grayscale-friendly JPEGs under cacheDir/covers.
 */
class EpubScanner(private val context: Context, private val sync: SyncRepository) {

    private val coverDir: File by lazy {
        File(context.cacheDir, "covers").apply { mkdirs() }
    }

    private fun defaultScanRoots(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOfNotNull(
            File(ext, "Books"),
            File(ext, "Download"),
            File(ext, "Downloads"),
            File(ext, "Documents"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(ext, "KOReader"),
            ext
        ).distinctBy { it.absolutePath }
    }

    suspend fun scan(extraFolders: List<String>): List<Book> = withContext(Dispatchers.IO) {
        val roots = (defaultScanRoots() + extraFolders.map { File(it) })
            .filter { it.exists() && it.canRead() }
        val seen = HashSet<String>()
        val files = ArrayList<File>()
        val visited = HashMap<String, Int>()
        for (root in roots) {
            collectEpubs(root, files, seen, visited, depth = 4)
        }
        files.sortedByDescending { it.lastModified() }
            .mapNotNull {
                coroutineContext.ensureActive()
                runCatching { cachedParse(it).let { book ->
                    book.copy(coverPath = book.coverPath ?: sync.cachedCover(book))
                } }.getOrNull()
            }
    }

    private fun collectEpubs(dir: File, out: MutableList<File>, seen: HashSet<String>, visited: MutableMap<String, Int>, depth: Int) {
        if (depth < 0) return
        val path = dir.safeCanonicalPath()
        if ((visited[path] ?: -1) >= depth) return
        visited[path] = depth
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (f.name.startsWith(".")) continue
                collectEpubs(f, out, seen, visited, depth - 1)
            } else if (f.extension.equals("epub", ignoreCase = true)) {
                val key = f.safeCanonicalPath()
                if (seen.add(key)) out.add(File(key))
            }
        }
    }

    private fun cachedParse(file: File): Book {
        val cache = File(context.cacheDir, "epub_metadata").apply { mkdirs() }
        val entry = File(cache, sha1(file.safeCanonicalPath()) + ".json")
        runCatching {
            val json = JSONObject(entry.readText())
            val cover = json.optString("cover").ifBlank { null }
            if (json.getLong("modified") == file.lastModified() && json.getLong("size") == file.length() &&
                (cover == null || File(cover).isFile)) {
                return Book(file.safeCanonicalPath(), json.getString("title"), json.getString("author"), cover, file.lastModified())
            }
        }
        val book = parse(file)
        runCatching {
            val temp = File(entry.path + ".tmp")
            temp.writeText(JSONObject().apply {
                put("modified", file.lastModified()); put("size", file.length())
                put("title", book.title); put("author", book.author); put("cover", book.coverPath.orEmpty())
            }.toString())
            if (!temp.renameTo(entry)) temp.delete()
        }
        return book
    }

    private fun parse(file: File): Book {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip)
            var title = file.nameWithoutExtension
            var author = ""
            var coverHref: String? = null
            var coverId: String? = null
            val manifest = HashMap<String, String>()        // id -> href
            val manifestProps = HashMap<String, String>()   // id -> properties

            if (opfPath != null) {
                zip.getEntry(opfPath)?.let { entry ->
                    com.crosspoint.launcher.reader.EpubBook.readEntry(zip, entry.name).inputStream().use { input ->
                        val parser = newParser(input)
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            if (event == XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "title" -> parser.nextText().trim().let { if (it.isNotEmpty()) title = it }
                                    "creator" -> parser.nextText().trim().let { if (it.isNotEmpty() && author.isEmpty()) author = it }
                                    "meta" -> {
                                        if (parser.getAttributeValue(null, "name") == "cover") {
                                            coverId = parser.getAttributeValue(null, "content")
                                        }
                                    }
                                    "item" -> {
                                        val id = parser.getAttributeValue(null, "id")
                                        val href = parser.getAttributeValue(null, "href")
                                        val props = parser.getAttributeValue(null, "properties")
                                        if (id != null && href != null) {
                                            manifest[id] = href
                                            if (props != null) manifestProps[id] = props
                                            if (props != null && props.contains("cover-image")) coverHref = href
                                        }
                                    }
                                }
                            }
                            event = parser.next()
                        }
                    }
                }
                if (coverHref == null && coverId != null) coverHref = manifest[coverId]
                if (coverHref != null) coverHref = resolveRelative(opfPath, coverHref!!)
            }

            // Fallbacks: guess a cover entry by common naming.
            if (coverHref == null) {
                coverHref = guessCover(zip)
            }

            val coverFile = coverHref?.let { extractCover(zip, it, file) }
            return Book(
                filePath = file.safeCanonicalPath(),
                title = title,
                author = author,
                coverPath = coverFile?.absolutePath,
                lastModified = file.lastModified()
            )
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerPath = zip.getEntry("META-INF/container.xml")?.name
            ?: zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith("/META-INF/container.xml", ignoreCase = true) }
                .minByOrNull { it.length }
            ?: return null
        val container = zip.getEntry(containerPath) ?: return null
        val containerBase = containerPath.removeSuffix("META-INF/container.xml")
        com.crosspoint.launcher.reader.EpubBook.readEntry(zip, container.name).inputStream().use { input ->
            val parser = newParser(input)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val fullPath = parser.getAttributeValue(null, "full-path") ?: return null
                    return if (containerBase.isBlank() || fullPath.startsWith("/")) {
                        fullPath.trimStart('/')
                    } else {
                        containerBase + fullPath
                    }
                }
                event = parser.next()
            }
        }
        return null
    }

    private fun guessCover(zip: ZipFile): String? {
        val candidates = zip.entries().asSequence()
            .map { it.name }
            .filter { name ->
                val lower = name.lowercase()
                (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) &&
                    lower.contains("cover")
            }
            .toList()
        return candidates.minByOrNull { it.length }
    }

    private fun resolveRelative(opfPath: String, href: String): String =
        com.crosspoint.launcher.reader.EpubBook.resolve(opfPath, href)

    private fun extractCover(zip: ZipFile, href: String, book: File): File? {
        val entry = zip.getEntry(href) ?: zip.getEntry(href.removePrefix("/")) ?: return null
        val hash = sha1(book.absolutePath + ":" + book.lastModified() + ":" + book.length())
        val out = File(coverDir, "$hash.jpg")
        if (out.exists() && out.length() > 0) return out
        return try {
            if (entry.size > 16 * 1024 * 1024) return null
            if (CoverImages.save({ zip.getInputStream(entry) }, out)) out else null
        } catch (e: Exception) {
            Log.w(TAG, "cover extract failed for ${book.name}: ${e.message}")
            null
        }
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply { setInput(input, null) }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun File.safeCanonicalPath(): String =
        runCatching { canonicalPath }.getOrElse { absolutePath }

    companion object {
        private const val TAG = "EpubScanner"
    }
}
