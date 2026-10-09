package com.crosspoint.launcher.ui.home

import android.util.LruCache
import com.crosspoint.launcher.data.CoverImages
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shared decoded covers, capped at 4 MiB for low-memory readers. */
internal object CoverCache {
    private class Entry(val image: ImageBitmap, val modified: Long, val length: Long, val bytes: Int)
    private val images = object : LruCache<String, Entry>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry) = value.bytes
    }

    fun peek(path: String?): ImageBitmap? = path?.let { images.get(it)?.image }

    // Decode one image at a time off the UI thread, including concurrent library rows.
    @Synchronized fun load(path: String): ImageBitmap? {
        val file = File(path)
        if (!file.isFile) { images.remove(path); return null }
        val modified = file.lastModified()
        val length = file.length()
        images.get(path)?.let { if (it.modified == modified && it.length == length) return it.image }
        return runCatching {
            CoverImages.decode { file.inputStream() }?.let { decoded ->
                val image = decoded.asImageBitmap()
                images.put(path, Entry(image, modified, length, decoded.allocationByteCount))
                image
            }
        }.getOrNull().also { if (it == null) images.remove(path) }
        // Do not recycle evicted bitmaps: a visible Compose image may still reference them.
    }
}

/** Cached pixels are available on the first frame; file changes are checked off-thread. */
@Composable
fun rememberCover(path: String?): ImageBitmap? {
    var cover by remember(path) { mutableStateOf(CoverCache.peek(path)) }
    LaunchedEffect(path) {
        cover = if (path == null) null else withContext(Dispatchers.IO) { CoverCache.load(path) }
    }
    return cover
}
