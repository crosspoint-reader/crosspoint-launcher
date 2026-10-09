package com.crosspoint.launcher.reader

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.ScaleXSpan
import android.text.style.StyleSpan
import com.crosspoint.launcher.reader.ReaderSettings.Companion as S

/**
 * Renders one chapter with the firmware's text settings. Every option is applied with spans or drawables, never by
 * editing text, so the rendered string (and with it EpubPosition's XPath mapping) is identical for every setting.
 */
object ReaderText {
    class Page(val width: Int, val height: Int, val textSize: Float, val color: Int, val night: Boolean, val spaceWidth: Float, val lineHeight: Int)

    /** A styled chapter, its position map (null when not mappable) and element ids → text offsets. */
    data class Rendered(val text: Spannable, val positions: EpubPosition?, val ids: Map<String, Int> = emptyMap(), val pageBreaks: Set<Int> = emptySet())

    fun render(html: String, book: EpubBook, chapter: Int, settings: ReaderSettings, page: Page, resources: Resources): Rendered {
        val embedded = settings.on(S.EMBEDDED_STYLE)
        val images = settings[S.IMAGES]
        fun drawable(src: String): Drawable = when (images) {
            2 -> ColorDrawable(0).apply { setBounds(0, 0, 0, 0) }
            1 -> Placeholder(page)
            else -> runCatching { image(book.resource(chapter, src), page, resources) }.getOrNull() ?: Placeholder(page)
        }
        val css = if (embedded) runCatching { ReaderCss.forChapter(book, chapter, html) }.getOrNull() else null
        val cssPage = ReaderCss.Page(page.width, page.textSize, page.lineHeight, page.color, if (page.night) 0xFF333333.toInt() else 0xFFDDDDDD.toInt())
        // Our own XHTML builder; chapters it can't parse fall back to Html.fromHtml so a book always opens.
        val built = runCatching {
            ChapterBuilder(html, book.spineIndices[chapter], page.textSize, page.width, embedded, { elements, i -> css?.preserves(elements, i) == true },
                { src, _ -> drawable(src) }).build()
        }.getOrNull()
        val content: SpannableStringBuilder
        val mapping: EpubPosition?
        val ids: Map<String, Int>
        val applied: ReaderCss.Applied?
        if (built != null) {
            content = built.text
            mapping = built.positions
            ids = built.ids
            applied = css?.let { runCatching { it.apply(content, built.elements, cssPage) }.getOrNull() }
        } else {
            val source = if (embedded) html else html.replace(Regex("""\sstyle\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE), "")
            content = SpannableStringBuilder(Html.fromHtml(source, Html.FROM_HTML_MODE_COMPACT, { drawable(it) }, null))
            // Night mode inverts the page; book colors would vanish on black.
            if (page.night) {
                content.getSpans(0, content.length, ForegroundColorSpan::class.java).forEach(content::removeSpan)
                content.getSpans(0, content.length, BackgroundColorSpan::class.java).forEach(content::removeSpan)
            }
            if (!embedded) content.getSpans(0, content.length, AlignmentSpan::class.java).forEach(content::removeSpan)
            val elements = if (embedded) ArrayList<EpubPosition.HtmlElement>() else null
            mapping = EpubPosition.build(html, content.toString(), book.spineIndices[chapter], elements)
            ids = emptyMap()
            applied = if (elements != null && mapping != null && css != null) runCatching { css.apply(content, elements, cssPage) }.getOrNull() else null
        }
        // Firmware: no first-line indent on centered or right-aligned text.
        val indent = if (settings[S.ALIGNMENT] in 2..3) 0 else (settings[S.INDENT] * page.spaceWidth * settings[S.WORD_SPACING] / 100f).toInt()
        // Firmware: half a line after each paragraph.
        val gap = if (settings.on(S.EXTRA_SPACING)) page.lineHeight / 2 else 0
        var start = 0
        while (start < content.length) {
            val newline = content.indexOf('\n', start)
            val end = if (newline < 0) content.length else newline + 1
            if (end - start > 1 && content[start] != '￼') {
                if (indent > 0 && start !in applied?.unindented.orEmpty() && content.getSpans(start, end, ChapterBuilder.WholeLine::class.java).isEmpty())
                    content.setSpan(LeadingMarginSpan.Standard(indent, 0), start, end, Spanned.SPAN_PARAGRAPH)
                if (gap > 0) content.setSpan(ParagraphGap(gap), start, end, Spanned.SPAN_PARAGRAPH)
            }
            start = end
        }

        // ponytail: one span per space/word; fine for typical chapters, slow near the 1 MiB chapter cap.
        val wordScale = settings[S.WORD_SPACING] / 100f
        if (wordScale != 1f) for (i in content.indices) if (content[i] == ' ') content.setSpan(ScaleXSpan(wordScale), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (settings.on(S.FOCUS_READING)) focus(content)
        return Rendered(content, mapping, ids, applied?.pageBreaks.orEmpty())
    }

    /** Firmware focus reading: bold the first ~45% (1..9 letters) of each word that isn't already bold. */
    private fun focus(content: SpannableStringBuilder) {
        val bold = content.getSpans(0, content.length, StyleSpan::class.java).filter { it.style and Typeface.BOLD != 0 }
            .map { content.getSpanStart(it) until content.getSpanEnd(it) }
        for (word in Regex("\\p{L}+").findAll(content)) {
            val first = word.range.first
            if (bold.any { first in it }) continue
            val count = (word.value.length * 45 / 100).coerceIn(1, 9).coerceAtMost(word.value.length)
            content.setSpan(StyleSpan(Typeface.BOLD), first, first + count, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ponytail: every image in the chapter is decoded up front (RGB_565, downsampled to the page); stream if RAM bites.
    private fun image(bytes: ByteArray, page: Page, resources: Resources): Drawable? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= page.width || bounds.outHeight / (sample * 2) >= page.height) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: return null
        val scale = minOf(page.width.toFloat() / bitmap.width, page.height.toFloat() / bitmap.height, resources.displayMetrics.density)
        return BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) }
    }

    private class Placeholder(page: Page) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = page.textSize * 0.8f; color = page.color }
        init { setBounds(0, 0, paint.measureText(LABEL).toInt(), (page.textSize * 1.2f).toInt()) }
        override fun draw(canvas: Canvas) = canvas.drawText(LABEL, 0f, bounds.height() * 0.8f, paint)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        companion object { const val LABEL = "[Image]" }
    }

    /** Extra space after each paragraph's last line. */
    private class ParagraphGap(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (end == (text as Spanned).getSpanEnd(this)) { fm.descent += gap; fm.bottom += gap }
        }
    }

    /**
     * Text of the element a footnote link points at. Anchors inside a note paragraph resolve to that paragraph.
     * ponytail: regex scan stops at the first matching close tag, so a note nesting its own tag type is cut short.
     */
    fun footnote(html: String, id: String): String? {
        val target = Regex("""<([a-zA-Z][\w:-]*)\b[^>]*\sid\s*=\s*["']${Regex.escape(id)}["'][^>]*>""").find(html) ?: return null
        var tag = target.groupValues[1].lowercase()
        var start = target.range.first
        if (tag in listOf("a", "span", "sup")) Regex("<(p|li|aside|div|dd|section)\\b[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(html.substring(0, start)).lastOrNull()?.let { start = it.range.first; tag = it.groupValues[1].lowercase() }
        val end = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).find(html, start)?.range?.last?.plus(1) ?: (start + 4000)
        return html.substring(start, end.coerceAtMost(html.length)).take(16_000)
    }
}
