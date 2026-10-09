package com.crosspoint.launcher

import android.content.Context
import android.graphics.Bitmap
import com.crosspoint.launcher.ui.home.CoverCache
import java.io.File

object CoverCacheChecks {
    fun run(context: Context) {
        val file = File(context.cacheDir, "cover-cache-check.png")
        fun write(size: Int) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        try {
            write(16)
            val first = checkNotNull(CoverCache.load(file.path))
            check(CoverCache.peek(file.path) === first) // First frame after navigating back.
            check(CoverCache.load(file.path) === first) // No repeated decode.
            write(32)
            val replaced = checkNotNull(CoverCache.load(file.path))
            check(replaced !== first && replaced.width == 32)
            file.delete()
            check(CoverCache.load(file.path) == null && CoverCache.peek(file.path) == null)
        } finally { file.delete() }
    }
}
