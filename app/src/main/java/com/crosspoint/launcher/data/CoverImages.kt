package com.crosspoint.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream

/** Bounds both dimensions before allocating pixels; never buffers compressed images in RAM. */
object CoverImages {
    fun decode(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 600) sample *= 2
        return open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
    }

    fun save(open: () -> InputStream, output: File): Boolean {
        val bitmap = decode(open) ?: return false
        val temp = File(output.path + ".tmp")
        try {
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            check(temp.renameTo(output))
            return true
        } finally { bitmap.recycle(); temp.delete() }
    }
}
