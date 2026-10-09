package com.crosspoint.launcher

import android.content.Context
import com.crosspoint.launcher.util.Dither
import android.graphics.Color
import android.graphics.Bitmap
import com.crosspoint.launcher.ui.home.CoverCache
import java.io.File

object CoverCacheChecks {
    fun run(context: Context) {
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val gray = x * 255 / (source.width - 1)
                source.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
            for ((width, height) in listOf(24 to 36, 64 to 24)) {
                val output = Dither.cover(source, width, height)
                try {
                    check(output.width == width && output.height == height)
                    val pixels = IntArray(width * height)
                    output.getPixels(pixels, 0, width, 0, 0, width, height)
                    check(pixels.all { it == Color.BLACK || it == Color.WHITE }) { "Cover has intermediate grays" }
                    check(pixels.any { it == Color.BLACK } && pixels.any { it == Color.WHITE }) { "Gradient lost its tones" }
                } finally { output.recycle() }
            }
            source.eraseColor(Color.WHITE)
            for (y in 0 until 80) for (x in 40 until 80) source.setPixel(x, y, Color.BLACK)
            val cropped = Dither.cover(source, 20, 80)
            try {
                val pixels = IntArray(20 * 80)
                cropped.getPixels(pixels, 0, 20, 0, 0, 20, 80)
                check(pixels.all { it == Color.BLACK }) { "Cover was stretched instead of center-cropped" }
            } finally { cropped.recycle() }
            source.eraseColor(Color.TRANSPARENT)
            val transparent = Dither.cover(source, 16, 24)
            try { check(transparent.getPixel(8, 12) == Color.WHITE) { "Transparent cover background became black" } }
            finally { transparent.recycle() }
            check(!source.isRecycled) { "Rendering recycled the shared source" }
        } finally { source.recycle() }
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
