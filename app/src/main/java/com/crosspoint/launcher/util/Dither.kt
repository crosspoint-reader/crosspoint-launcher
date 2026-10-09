package com.crosspoint.launcher.util

import android.graphics.Bitmap

/**
 * Photo -> e-ink: luminance, auto-levels, then Floyd-Steinberg error diffusion to the panel's four
 * greys. Ported from crosspoint-sync's wallpaper pipeline (quantize.ts / tone.ts / dither.ts,
 * originally crosspoint-pxc-converter by zgredex) so the launcher matches the sleep-screen look there.
 */
object Dither {
    private val GRAY_DISP = intArrayOf(0, 85, 170, 255)
    private val THRESHOLDS = intArrayOf(43, 128, 213)

    /** Returns a new bitmap of the same size, dithered to the four panel greys. */
    fun toEink(src: Bitmap, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }

        // Luminance (Rec.601), as used by the converter.
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = 0.299f * ((p ushr 16) and 0xFF) + 0.587f * ((p ushr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }

        applyAutoLevels(lum)

        // Floyd-Steinberg to four levels.
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val v = lum[i].coerceIn(0f, 255f)
                val q = quantize(v)
                out[i] = 0xFF000000.toInt() or (GRAY_DISP[q] * 0x010101)
                val e = v - GRAY_DISP[q]
                if (x + 1 < w) lum[i + 1] += e * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) lum[i + w - 1] += e * 3 / 16
                    lum[i + w] += e * 5 / 16
                    if (x + 1 < w) lum[i + w + 1] += e * 1 / 16
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, config)
    }

    private fun quantize(v: Float): Int =
        if (v < THRESHOLDS[0]) 0 else if (v < THRESHOLDS[1]) 1 else if (v < THRESHOLDS[2]) 2 else 3

    /** Stretch the 0.5%/99.5% luminance points to full black/white, so covers aren't muddy. */
    private fun applyAutoLevels(lum: FloatArray) {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0f, 255f).toInt()]++
        val clip = (lum.size * 0.005f).toInt().coerceAtLeast(1)
        var lo = 0; var loCount = 0
        while (lo < 255 && (loCount + hist[lo]).also { loCount = it } < clip) lo++
        var hi = 255; var hiCount = 0
        while (hi > 0 && (hiCount + hist[hi]).also { hiCount = it } < clip) hi--
        if (hi - lo < 20) { val mid = (lo + hi) / 2; lo = (mid - 10).coerceAtLeast(0); hi = (mid + 10).coerceAtMost(255) }
        if (hi <= lo) return
        val range = (hi - lo).toFloat()
        for (i in lum.indices) lum[i] = ((lum[i] - lo) / range * 255f).coerceIn(0f, 255f)
    }
}
