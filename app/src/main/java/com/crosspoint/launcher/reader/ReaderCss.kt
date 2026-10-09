package com.crosspoint.launcher.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import com.crosspoint.launcher.reader.EpubPosition.HtmlElement

/**
 * The book CSS the firmware's feat-epub-rendering parser honours, applied as spans over the rendered chapter.
 * Selectors: tag#id.class compounds with descendant/child combinators, ranked by specificity; ::first-letter;
 * inline style last. Properties: text-align, font-weight/style, text-decoration, vertical-align, font-size,
 * font-variant small-caps, text-indent, margins/padding, borders, light background shading, display:none,
 * list-style-type:none and drop caps (initial-letter / float:left). Text is never edited, so sync offsets hold.
 */
class ReaderCss private constructor(private val rules: List<Rule>) {
    private class Compound(val tag: String?, val id: String?, val classes: List<String>)
    private class Rule(val compounds: List<Compound>, val childOf: List<Boolean>, val firstLetter: Boolean,
                       val specificity: Int, val order: Int, val declarations: Map<String, String>)

    /** Paragraph starts that skip the reader's first-line indent, and offsets where CSS forces a new page. */
    class Applied(val unindented: Set<Int>, val pageBreaks: Set<Int>)

    class Page(val width: Int, val textSize: Float, val lineHeight: Int, val foreground: Int, val shade: Int)

    /**
     * Applies the chapter's styles. Returns paragraph starts that must not get the reader's first-line indent:
     * like firmware resolveFirstLineIndent, centered/right paragraphs, drop-cap paragraphs and hanging indents.
     */
    fun apply(content: Spannable, elements: List<HtmlElement>, page: Page): Applied {
        val styles = elements.indices.map { resolve(elements, it, firstLetter = false) }
        val size = FloatArray(elements.size) // absolute font scale per element (1 = reader body size)
        val alignments = HashMap<Int, Layout.Alignment>()
        val hanging = HashMap<Int, Int>()
        val noIndent = HashSet<Int>()
        val breaks = HashSet<Int>()
        val text = content.toString()
        fun paragraphStart(at: Int) = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (at == 0) 0 else it + 1 }
        fun paragraphEnd(at: Int) = text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
        fun paragraphs(start: Int, end: Int) = generateSequence(paragraphStart(start)) { paragraphEnd(it).takeIf { next -> next < end } }

        elements.forEachIndexed { i, element ->
            val style = styles[i]
            val parentSize = if (element.parent >= 0) size[element.parent] else 1f
            size[i] = style["font-size"]?.let { fontScale(it, parentSize) } ?: parentSize
            val start = element.start
            val end = element.end
            if (start < 0 || end <= start) return@forEachIndexed
            val em = page.textSize * size[i]
            fun length(value: String?) = value?.let { lengthPx(it, em, page) }

            if (size[i] != parentSize) {
                // Html's own heading sizes would compound with the book's.
                if (element.tag.matches(Regex("h[1-6]"))) content.getSpans(start, end, RelativeSizeSpan::class.java)
                    .filter { content.getSpanStart(it) == start }.forEach(content::removeSpan)
                content.setSpan(RelativeSizeSpan(size[i] / parentSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            when (style["font-weight"]?.let(::weight)) {
                true -> content.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                false -> content.getSpans(start, end, StyleSpan::class.java).filter {
                    it.style == Typeface.BOLD && content.getSpanStart(it) >= start && content.getSpanEnd(it) <= paragraphEnd(end - 1)
                }.forEach(content::removeSpan)
                null -> Unit
            }
            when (style["font-style"]) {
                "italic", "oblique" -> content.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                "normal" -> content.getSpans(start, end, StyleSpan::class.java).filter {
                    it.style == Typeface.ITALIC && content.getSpanStart(it) >= start && content.getSpanEnd(it) <= paragraphEnd(end - 1)
                }.forEach(content::removeSpan)
            }
            (style["text-decoration-line"] ?: style["text-decoration"])?.let { decoration ->
                if ("underline" in decoration) content.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if ("line-through" in decoration) content.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            when (style["vertical-align"]) {
                "super" -> { content.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); content.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                "sub" -> { content.setSpan(SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); content.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            if ((style["font-variant"] ?: style["font-variant-caps"])?.contains("small-caps") == true)
                Regex("\\S+").findAll(text.substring(start, end)).forEach { word ->
                    content.setSpan(SmallCaps(), start + word.range.first, start + word.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            if (element.tag == "img" || element.tag == "image") sizeImage(content, start, end, length(style["width"]), length(style["height"]), page)
            if (style["display"] == "none") hide(content, text, start, end)
            // Firmware: (page-)break-before/after always|page|left|right|recto|verso start a new page.
            if ((style["page-break-before"] ?: style["break-before"]) in PAGE_BREAKS) breaks += paragraphStart(start)
            if ((style["page-break-after"] ?: style["break-after"]) in PAGE_BREAKS) paragraphEnd(end - 1).takeIf { it < text.length }?.let(breaks::add)
            if (style["list-style-type"] == "none") content.getSpans(start, end, BulletSpan::class.java).forEach(content::removeSpan)

            // Block properties work on whole paragraphs; inner elements run later, so they win.
            alignment(style["text-align"])?.let { align -> paragraphs(start, end).forEach { alignments[it] = align } }
            // The reader's own indent wins; only a negative (hanging) book indent is kept, as on the firmware.
            style["text-indent"]?.let(::length)?.takeIf { it < 0 }?.let { px -> paragraphs(start, end).forEach { hanging[it] = (-px).toInt().coerceAtMost(page.width / 3) } }
            val left = (length(style["margin-left"]) ?: 0f) + (length(style["padding-left"]) ?: 0f)
            if (left >= 1f) {
                val px = left.coerceAtMost(page.width / 3f).toInt()
                content.setSpan(LeadingMarginSpan.Standard(px), paragraphStart(start), paragraphEnd(end - 1), Spanned.SPAN_PARAGRAPH)
            }
            val top = ((length(style["margin-top"]) ?: 0f) + (length(style["padding-top"]) ?: 0f)).coerceIn(0f, page.lineHeight * 2f).toInt()
            if (top > 0) content.setSpan(Gap(top, before = true), paragraphStart(start), paragraphEnd(start), Spanned.SPAN_PARAGRAPH)
            val bottom = ((length(style["margin-bottom"]) ?: 0f) + (length(style["padding-bottom"]) ?: 0f)).coerceIn(0f, page.lineHeight * 2f).toInt()
            if (bottom > 0) content.setSpan(Gap(bottom, before = false), paragraphStart(end - 1), paragraphEnd(end - 1), Spanned.SPAN_PARAGRAPH)
            val borders = listOf("top", "right", "bottom", "left").map { side -> borderWidth(style, side) }
            val shaded = (style["background-color"] ?: style["background"])?.let(::shaded) == true
            if (shaded || borders.any { it > 0 }) content.setSpan(Box(borders.map { it * page.textSize / 16f }, shaded, page.foreground, page.shade),
                paragraphStart(start), paragraphEnd(end - 1), Spanned.SPAN_PARAGRAPH)

            dropCap(content, text, elements, i, styles[i], start, end, page)?.let(noIndent::add)
        }
        alignments.forEach { (at, align) -> content.setSpan(AlignmentSpan.Standard(align), at, paragraphEnd(at), Spanned.SPAN_PARAGRAPH) }
        hanging.forEach { (at, px) -> content.setSpan(LeadingMarginSpan.Standard(0, px), at, paragraphEnd(at), Spanned.SPAN_PARAGRAPH) }
        return Applied(noIndent + hanging.keys + alignments.filterValues { it != Layout.Alignment.ALIGN_NORMAL }.keys, breaks)
    }

    /** ::first-letter rules or a short floated/large span opening a paragraph become a drop cap, like the firmware. */
    private fun dropCap(content: Spannable, text: String, elements: List<HtmlElement>, index: Int, style: Map<String, String>,
                        start: Int, end: Int, page: Page): Int? {
        val letterStyle = resolve(elements, index, firstLetter = true)
        val (lines, bold, from) = when {
            letterStyle.isNotEmpty() -> Triple(dropCapLines(letterStyle), letterStyle["font-weight"]?.let(::weight) == true, start)
            end - start <= 4 -> Triple(dropCapLines(style), style["font-weight"]?.let(::weight) == true, start)
            else -> return null
        }
        val paragraph = text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let { if (from == 0) 0 else it + 1 }
        if (lines < 2 || text.substring(paragraph, from).isNotBlank()) return null
        // Leading punctuation (opening quotes) travels with the letter.
        var letterEnd = from
        while (letterEnd < end && !text[letterEnd].isLetterOrDigit()) letterEnd++
        if (letterEnd >= end) return null
        letterEnd += Character.charCount(text.codePointAt(letterEnd))
        hide(content, text, from, letterEnd)
        // The letter is drawn by DropCap; its own size spans would still inflate the hidden first line.
        content.getSpans(from, letterEnd, RelativeSizeSpan::class.java)
            .filter { content.getSpanStart(it) >= from && content.getSpanEnd(it) <= letterEnd }.forEach(content::removeSpan)
        content.setSpan(DropCap(text.substring(from, letterEnd), lines, bold, page.lineHeight), paragraph, text.indexOf('\n', from).let { if (it < 0) text.length else it + 1 },
            Spanned.SPAN_PARAGRAPH)
        return paragraph
    }

    private val whiteSpaceRules = rules.any { "white-space" in it.declarations }

    /** white-space: pre / pre-wrap / break-spaces keeps the element's spaces and line breaks (firmware preserveWhitespace). */
    fun preserves(elements: List<HtmlElement>, index: Int): Boolean =
        (whiteSpaceRules || "white-space" in elements[index].style) &&
            resolve(elements, index, firstLetter = false)["white-space"] in listOf("pre", "pre-wrap", "break-spaces")

    private fun resolve(elements: List<HtmlElement>, index: Int, firstLetter: Boolean): Map<String, String> {
        val result = HashMap<String, String>()
        rules.filter { it.firstLetter == firstLetter && matches(it, elements, index) }
            .sortedWith(compareBy({ it.specificity }, { it.order })).forEach { result.putAll(it.declarations) }
        if (!firstLetter) result.putAll(declarations(elements[index].style))
        return result
    }

    private fun matches(rule: Rule, elements: List<HtmlElement>, index: Int): Boolean {
        fun matchFrom(compound: Int, element: Int): Boolean {
            if (!matchesCompound(rule.compounds[compound], elements[element])) return false
            if (compound == 0) return true
            var ancestor = elements[element].parent
            if (rule.childOf[compound - 1]) return ancestor >= 0 && matchFrom(compound - 1, ancestor)
            while (ancestor >= 0) {
                if (matchFrom(compound - 1, ancestor)) return true
                ancestor = elements[ancestor].parent
            }
            return false
        }
        return matchFrom(rule.compounds.lastIndex, index)
    }

    private fun matchesCompound(compound: Compound, element: HtmlElement) =
        (compound.tag == null || compound.tag == element.tag) && (compound.id == null || compound.id == element.id) &&
            compound.classes.all { it in element.classes.take(4) }

    private class Gap(private val px: Int, private val before: Boolean) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val spanned = text as Spanned
            if (before && start == spanned.getSpanStart(this)) { fm.ascent -= px; fm.top -= px }
            if (!before && end == spanned.getSpanEnd(this)) { fm.descent += px; fm.bottom += px }
        }
    }

    /** display:none without removing text: the characters take no space. */
    private class Hidden : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
    }

    /** Firmware small caps: lowercase letters drawn as smaller capitals. */
    private class SmallCaps : ReplacementSpan() {
        private fun runs(text: CharSequence, start: Int, end: Int) = text.subSequence(start, end).toString()
            .let { word -> Regex("\\p{Ll}+|[^\\p{Ll}]+").findAll(word).map { it.value } }
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            val size = paint.textSize
            return runs(text, start, end).sumOf { run ->
                val small = run[0].isLowerCase()
                paint.textSize = if (small) size * 0.78f else size
                paint.measureText(run.uppercase()).toDouble().also { paint.textSize = size }
            }.toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val size = paint.textSize
            var left = x
            runs(text, start, end).forEach { run ->
                paint.textSize = if (run[0].isLowerCase()) size * 0.78f else size
                canvas.drawText(run.uppercase(), left, y.toFloat(), paint)
                left += paint.measureText(run.uppercase())
            }
            paint.textSize = size
        }
    }

    /** Borders and light shading drawn around a block (firmware border boxes). */
    private class Box(private val widths: List<Float>, private val shaded: Boolean, private val color: Int, private val shade: Int) : LineBackgroundSpan {
        override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
                                    text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            val spanned = text as Spanned
            val first = start <= spanned.getSpanStart(this)
            val last = end >= spanned.getSpanEnd(this)
            val saved = paint.color
            val style = paint.style
            paint.style = Paint.Style.FILL
            if (shaded) { paint.color = shade; canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint) }
            paint.color = color
            val (t, r, b, l) = widths
            if (first && t > 0) canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), top + t, paint)
            if (last && b > 0) canvas.drawRect(left.toFloat(), bottom - b, right.toFloat(), bottom.toFloat(), paint)
            if (l > 0) canvas.drawRect(left.toFloat(), top.toFloat(), left + l, bottom.toFloat(), paint)
            if (r > 0) canvas.drawRect(right - r, top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = saved
            paint.style = style
        }
    }

    /** The first lines wrap beside a large initial letter. */
    private class DropCap(private val letter: String, private val lines: Int, private val bold: Boolean, private val lineHeight: Int) :
        LeadingMarginSpan.LeadingMarginSpan2 {
        private var width = 0
        override fun getLeadingMarginLineCount() = lines
        override fun getLeadingMargin(first: Boolean) = if (first) width else 0
        override fun drawLeadingMargin(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
                                       text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            if (start != (text as Spanned).getSpanStart(this)) return
            val saved = Paint(paint)
            paint.textSize = lineHeight * lines * 0.92f
            if (bold) paint.typeface = Typeface.create(paint.typeface, Typeface.BOLD)
            canvas.drawText(letter, x.toFloat(), baseline + (lines - 1f) * lineHeight, paint)
            paint.set(saved)
        }
        fun measure(paint: Paint) {
            val probe = Paint(paint).apply { textSize = lineHeight * lines * 0.92f; if (bold) typeface = Typeface.create(typeface, Typeface.BOLD) }
            width = (probe.measureText(letter) + lineHeight * 0.3f).toInt()
        }
    }

    companion object {
        private val cache = object : LinkedHashMap<String, List<Rule>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Rule>>?) = size > 24
        }

        /** The chapter's linked stylesheets and <style> blocks, in document order. */
        fun forChapter(book: EpubBook, chapter: Int, html: String): ReaderCss {
            val head = html.substringBefore("<body", html)
            val sheets = Regex("<link\\b[^>]*>|<style\\b[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE).findAll(head).mapNotNull { tag ->
                if (tag.value.startsWith("<style", true)) return@mapNotNull "inline:${tag.groupValues[1].hashCode()}" to tag.groupValues[1]
                val rel = attribute(tag.value, "rel")
                val href = attribute(tag.value, "href")
                if (!rel.contains("stylesheet", true) || href.isEmpty()) return@mapNotNull null
                "${book.file.path}:${EpubBook.resolve(book.chapters[chapter], href)}" to href
            }.toList()
            var order = 0
            val rules = sheets.flatMap { (key, source) ->
                val parsed = synchronized(cache) { cache[key] } ?: runCatching {
                    parse(if (key.startsWith("inline:")) source else String(book.resource(chapter, source), Charsets.UTF_8))
                }.getOrDefault(emptyList()).also { synchronized(cache) { cache[key] = it } }
                parsed.map { Rule(it.compounds, it.childOf, it.firstLetter, it.specificity, order++, it.declarations) }
            }
            return ReaderCss(rules)
        }

        /** Measure drop caps against the reader's paint once styles are on the text. */
        fun measure(content: Spanned, paint: Paint) =
            content.getSpans(0, content.length, DropCap::class.java).forEach { it.measure(paint) }

        private fun attribute(tag: String, name: String) =
            Regex("\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }.orEmpty()

        private fun parse(css: String): List<Rule> {
            val text = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ").take(512 * 1024)
            val rules = ArrayList<Rule>()
            var i = 0
            while (i < text.length) {
                val open = text.indexOf('{', i)
                if (open < 0) break
                var depth = 1
                var j = open + 1
                while (j < text.length && depth > 0) { if (text[j] == '{') depth++ else if (text[j] == '}') depth--; j++ }
                val prelude = text.substring(i, open).substringAfterLast(';').substringAfterLast('}').trim()
                val body = text.substring(open + 1, (j - 1).coerceAtLeast(open + 1))
                i = j
                if (prelude.startsWith("@")) continue // media queries, @font-face, @page: skipped like the firmware
                val declarations = declarations(body)
                if (declarations.isEmpty()) continue
                prelude.split(',').forEach { selector(it.trim(), declarations)?.let(rules::add) }
            }
            return rules
        }

        private fun selector(source: String, declarations: Map<String, String>): Rule? {
            var text = source
            var firstLetter = false
            for (suffix in listOf("::first-letter", ":first-letter")) if (text.endsWith(suffix, true)) { text = text.dropLast(suffix.length); firstLetter = true; break }
            if (text.isEmpty() || text.any { it in "[]+~:*()" }) return null
            val tokens = text.replace(">", " > ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            val compounds = ArrayList<Compound>()
            val childOf = ArrayList<Boolean>()
            var child = false
            for (token in tokens) {
                if (token == ">") { child = true; continue }
                val match = Regex("^([a-zA-Z][\\w-]*)?((?:[#.][\\w-]+)*)$").matchEntire(token) ?: return null
                val parts = Regex("[#.][\\w-]+").findAll(match.groupValues[2]).map { it.value }.toList()
                if (compounds.isNotEmpty()) childOf += child
                child = false
                compounds += Compound(match.groupValues[1].lowercase().ifEmpty { null }, parts.firstOrNull { it.startsWith("#") }?.drop(1),
                    parts.filter { it.startsWith(".") }.map { it.drop(1) })
            }
            if (compounds.isEmpty() || child) return null
            val specificity = compounds.sumOf { (if (it.id != null) 10_000 else 0) + it.classes.size * 100 + (if (it.tag != null) 1 else 0) }
            return Rule(compounds, childOf, firstLetter, specificity, 0, declarations)
        }

        private fun declarations(body: String): Map<String, String> = body.split(';').mapNotNull { declaration ->
            val name = declaration.substringBefore(':', "").trim().lowercase()
            val value = declaration.substringAfter(':', "").replace(Regex("!\\s*important", RegexOption.IGNORE_CASE), "").trim().lowercase()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.flatMap { (name, value) -> expand(name, value) }.toMap()

        /** margin/padding shorthands into sides; other properties as-is. */
        private fun expand(name: String, value: String): List<Pair<String, String>> {
            if (name != "margin" && name != "padding") return listOf(name to value)
            val parts = value.split(Regex("\\s+"))
            val (top, right, bottom, left) = when (parts.size) {
                1 -> listOf(parts[0], parts[0], parts[0], parts[0])
                2 -> listOf(parts[0], parts[1], parts[0], parts[1])
                3 -> listOf(parts[0], parts[1], parts[2], parts[1])
                else -> parts.take(4)
            }
            return listOf("$name-top" to top, "$name-right" to right, "$name-bottom" to bottom, "$name-left" to left)
        }

        private fun weight(value: String) = when (value) {
            "bold", "bolder" -> true
            "normal", "lighter" -> false
            else -> value.toIntOrNull()?.let { it >= 600 }
        }

        private fun alignment(value: String?) = when (value) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            "left", "start", "justify" -> Layout.Alignment.ALIGN_NORMAL
            else -> null
        }

        private val PAGE_BREAKS = setOf("always", "page", "left", "right", "recto", "verso")
        private val SIZE_KEYWORDS = mapOf("xx-small" to 0.6f, "x-small" to 0.75f, "small" to 0.89f, "medium" to 1f, "large" to 1.2f,
            "x-large" to 1.5f, "xx-large" to 2f, "xxx-large" to 3f)

        /** Absolute scale against the reader's body size; em and % follow the parent, rem and keywords the body. */
        private fun fontScale(value: String, parent: Float): Float? {
            SIZE_KEYWORDS[value]?.let { return it }
            if (value == "smaller") return parent * 0.83f
            if (value == "larger") return parent * 1.2f
            val match = Regex("^(-?[\\d.]+)(px|pt|em|rem|%)?$").matchEntire(value) ?: return null
            val number = match.groupValues[1].toFloatOrNull() ?: return null
            return when (match.groupValues[2]) {
                "em" -> parent * number
                "%" -> parent * number / 100f
                "rem" -> number
                "pt" -> number / 12f
                else -> number / 16f
            }.coerceIn(0.5f, 4f)
        }

        private fun lengthPx(value: String, em: Float, page: Page): Float? {
            if (value == "0" || value == "auto") return 0f
            val match = Regex("^(-?[\\d.]+)(px|pt|em|rem|%|ex|ch)?$").matchEntire(value.split(Regex("\\s+")).first()) ?: return null
            val number = match.groupValues[1].toFloatOrNull() ?: return null
            return when (match.groupValues[2]) {
                "em" -> number * em
                "rem" -> number * page.textSize
                "ex", "ch" -> number * em / 2f
                "%" -> number * page.width / 100f
                "pt" -> number * page.textSize / 12f
                else -> number * page.textSize / 16f
            }
        }

        private fun borderWidth(style: Map<String, String>, side: String): Float {
            val value = style["border-$side"] ?: style["border"] ?: return style["border-$side-width"]?.let(::widthOf)
                ?.takeIf { style["border-$side-style"] ?: style["border-style"] in listOf("solid", "double", "dotted", "dashed", "groove", "ridge", "inset", "outset") } ?: 0f
            val tokens = value.split(Regex("\\s+"))
            if (tokens.none { it in listOf("solid", "double", "dotted", "dashed", "groove", "ridge", "inset", "outset") }) return 0f
            return tokens.firstNotNullOfOrNull(::widthOf) ?: 1f
        }

        private fun widthOf(token: String): Float? = when (token) {
            "thin" -> 1f; "medium" -> 3f; "thick" -> 5f
            else -> Regex("^([\\d.]+)(px)?$").matchEntire(token)?.groupValues?.get(1)?.toFloatOrNull()
        }

        /** Firmware interpretShade: only light, non-white backgrounds shade the box. */
        private fun shaded(value: String): Boolean {
            val named = mapOf("white" to 255, "whitesmoke" to 245, "gainsboro" to 220, "lightgray" to 211, "lightgrey" to 211, "silver" to 192,
                "darkgray" to 169, "darkgrey" to 169, "gray" to 128, "grey" to 128, "dimgray" to 105, "dimgrey" to 105, "black" to 0,
                "ivory" to 254, "beige" to 243, "linen" to 244, "lavender" to 235, "transparent" to 255)
            fun luminance(r: Int, g: Int, b: Int) = (r * 299 + g * 587 + b * 114) / 1000
            val level = Regex("rgba?\\(\\s*(\\d+)(%?)\\s*,\\s*(\\d+)(%?)\\s*,\\s*(\\d+)(%?)").find(value)?.let { m ->
                val c = listOf(1, 3, 5).map { i -> m.groupValues[i].toInt().let { v -> if (m.groupValues[i + 1] == "%") v * 255 / 100 else v.coerceAtMost(255) } }
                luminance(c[0], c[1], c[2])
            } ?: Regex("#([0-9a-f]{3,8})\\b").find(value)?.groupValues?.get(1)?.let { hex ->
                when (hex.length) {
                    3, 4 -> (0..2).map { hex[it].digitToInt(16) * 17 }
                    6, 8 -> (0..2).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
                    else -> null
                }?.let { luminance(it[0], it[1], it[2]) }
            } ?: value.split(Regex("\\s+")).firstNotNullOfOrNull { named[it] } ?: return false
            return level in 128..239
        }

        /** Firmware dropCapLines: initial-letter wins; else a floated or large letter spans 2-4 lines. */
        private fun dropCapLines(style: Map<String, String>): Int {
            style["initial-letter"]?.split(Regex("\\s+"))?.firstOrNull()?.toFloatOrNull()?.let { return if (it >= 2) minOf(it.toInt(), 6) else 0 }
            val size = style["font-size"]?.let { Regex("^([\\d.]+)(em|rem|%)?$").matchEntire(it) }
                ?.let { m -> m.groupValues[1].toFloat().let { v -> if (m.groupValues[2] == "%") v / 100f else v } }
            val large = size != null && size >= 1.5f
            if (!large && style["float"] != "left") return 0
            return if (large) (size!! + 0.5f).toInt().coerceIn(2, 4) else 3
        }

        /** Firmware imageWidth/imageHeight: one dimension keeps the aspect ratio; never larger than the page. */
        private fun sizeImage(content: Spannable, start: Int, end: Int, width: Float?, height: Float?, page: Page) {
            if ((width ?: 0f) <= 0f && (height ?: 0f) <= 0f) return
            content.getSpans(start, end, android.text.style.ImageSpan::class.java).forEach { span ->
                val drawable = span.drawable
                val bounds = drawable.bounds
                if (bounds.width() <= 0 || bounds.height() <= 0) return@forEach
                val aspect = bounds.height().toFloat() / bounds.width()
                var w = width?.takeIf { it > 0 } ?: (height!! / aspect)
                var h = height?.takeIf { it > 0 } ?: (w * aspect)
                val fit = minOf(1f, page.width / w)
                w *= fit; h *= fit
                drawable.setBounds(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            }
        }

        private fun hide(content: Spannable, text: String, start: Int, end: Int) {
            var from = start
            while (from < end) {
                val newline = text.indexOf('\n', from).let { if (it < 0 || it >= end) end else it }
                if (newline > from) content.setSpan(Hidden(), from, newline, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                from = newline + 1
            }
        }
    }
}
