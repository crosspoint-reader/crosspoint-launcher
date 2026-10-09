package com.crosspoint.launcher.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri

/**
 * Sets the e-ink sleep screen via the standard Android lock/system wallpaper (no root needed — on the
 * S4 the standby image follows WallpaperManager). The image is centre-cropped to the panel and
 * dithered to the four panel greys so covers look crisp instead of muddy.
 */
object SleepScreen {
    fun applyCover(context: Context, coverPath: String?): Boolean =
        apply(context, coverPath?.let { BitmapFactory.decodeFile(it) })

    fun applyUri(context: Context, uri: Uri): Boolean =
        apply(context, context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) })

    private fun apply(context: Context, source: Bitmap?): Boolean {
        if (source == null) return false
        val m = context.resources.displayMetrics
        val out = Dither.toEink(fillCrop(source, m.widthPixels, m.heightPixels))
        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(out, null, true, WallpaperManager.FLAG_SYSTEM)
        wm.setBitmap(out, null, true, WallpaperManager.FLAG_LOCK)
        return true
    }

    /** Centre-crops [source] to the w:h panel aspect, then scales to fill it exactly (no stretch, no bars). */
    private fun fillCrop(source: Bitmap, w: Int, h: Int): Bitmap {
        val targetAspect = w.toFloat() / h
        val src = if (source.width.toFloat() / source.height > targetAspect) {
            val cropW = (source.height * targetAspect).toInt()
            Rect((source.width - cropW) / 2, 0, (source.width + cropW) / 2, source.height)
        } else {
            val cropH = (source.width / targetAspect).toInt()
            Rect(0, (source.height - cropH) / 2, source.width, (source.height + cropH) / 2)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}
