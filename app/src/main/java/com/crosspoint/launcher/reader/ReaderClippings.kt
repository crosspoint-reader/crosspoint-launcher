package com.crosspoint.launcher.reader

import android.os.Environment
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-book clippings mirroring the firmware's ClippingStore and the CrossPoint Sync clipping item.
 * Items are kept in the sync wire format, so firmware layout hints (pages, words, signature) survive a round trip.
 */
class ReaderClippings(dir: File, bookPath: String) {
    private val file = AtomicFile(File(dir.apply { mkdirs() }, MessageDigest.getInstance("SHA-256")
        .digest(bookPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".json"))
    private val state: JSONObject = runCatching { JSONObject(String(file.readFully())) }.getOrDefault(JSONObject())

    /** Live clippings, oldest first. Each has id, spine, chapter, text, created_at and optional start/end_offset. */
    val items: List<JSONObject> @Synchronized get() = state.optJSONArray("items").objects()

    @Synchronized fun add(spine: Int, chapter: String, text: String, startOffset: Int?, endOffset: Int?): JSONObject {
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val item = JSONObject().put("id", id).put("spine", spine).put("start_page", 0).put("end_page", 0).put("pages", 1)
            .put("start_word", 0).put("end_word", 0).put("words", text.split(Regex("\\s+")).count { it.isNotEmpty() })
            .put("chapter", chapter.take(CHAPTER_MAX)).put("text", text).put("created_at", System.currentTimeMillis() / 1000)
            .put("layout_signature", 0).put("revision", 0).put("pending", true)
        if (startOffset != null && endOffset != null && endOffset > startOffset) item.put("start_offset", startOffset).put("end_offset", endOffset)
        save(items + item, deletions())
        return item
    }

    @Synchronized fun remove(id: String) {
        val removed = items.firstOrNull { it.optString("id") == id } ?: return
        // A clipping the server never saw needs no tombstone.
        val journal = if (removed.optLong("revision") == 0L && removed.optBoolean("pending")) deletions() else deletions() + id
        save(items.filterNot { it.optString("id") == id }, journal)
    }

    @Synchronized fun deletions(): List<String> = state.optJSONArray("deleted")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
    @Synchronized fun pending(): List<JSONObject> = items.filter { it.optBoolean("pending") }

    /** Server acknowledged these uploads and tombstones. */
    @Synchronized fun synced(uploaded: Collection<String>, deleted: Collection<String>) {
        save(items.map { if (it.optString("id") in uploaded) JSONObject(it.toString()).put("pending", false) else it }, deletions() - deleted.toSet())
    }

    /** Apply one remote item; a local edit that hasn't uploaded yet wins until it does. */
    @Synchronized fun applyRemote(remote: JSONObject) {
        val id = remote.optString("id")
        val local = items.firstOrNull { it.optString("id") == id }
        val rest = items.filterNot { it.optString("id") == id }
        if (remote.optInt("deleted") == 1) { save(rest, deletions() - id); return }
        if (id in deletions() || local?.optBoolean("pending") == true) return
        val item = JSONObject(remote.toString()).put("pending", false)
        listOf("deleted", "updated_at", "note", "color").forEach { item.remove(it) }
        save((rest + item).sortedBy { it.optLong("created_at") }, deletions())
    }

    private fun save(items: List<JSONObject>, deleted: List<String>) {
        state.put("items", JSONArray(items)).put("deleted", JSONArray(deleted.distinct()))
        val output = file.startWrite()
        try { output.write(state.toString().toByteArray()); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }

    companion object {
        const val TEXT_MAX_BYTES = 4096
        private const val CHAPTER_MAX = 47
        private const val KINDLE_TEXT_MAX = 2000

        fun JSONArray?.objects(): List<JSONObject> = this?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()

        /** Same Kindle "My Clippings.txt" entry the firmware appends at the storage root. */
        fun appendKindle(title: String, author: String, chapter: String, page: Int, text: String) = runCatching {
            val added = SimpleDateFormat("EEEE, MMMM d, yyyy, hh:mm a", Locale.US).format(Date())
            var location = "- Your Highlight on Page $page"
            if (chapter.isNotEmpty()) location += " | $chapter"
            location += " | Added on $added"
            val bytes = text.toByteArray()
            val clipped = if (bytes.size <= KINDLE_TEXT_MAX) text else String(bytes, 0, KINDLE_TEXT_MAX).trimEnd('�')
            File(Environment.getExternalStorageDirectory(), "My Clippings.txt")
                .appendText("$title ($author)\n$location\n\n$clipped\n==========\n")
        }.isSuccess
    }
}
