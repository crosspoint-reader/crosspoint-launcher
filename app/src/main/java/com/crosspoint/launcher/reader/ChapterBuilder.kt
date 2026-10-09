package com.crosspoint.launcher.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import com.crosspoint.launcher.reader.EpubPosition.HtmlElement
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Builds the displayed chapter straight from its XHTML in one pass, replacing Html.fromHtml: text with the reader's
 * own whitespace and block rules, inline styles, list markers, images, element ids, the element ranges the book CSS
 * needs, and the sync position map (XPaths and firmware offsets) recorded directly instead of re-matched.
 * XPath counting matches EpubPosition.build so saved KOReader/firmware positions keep resolving.
 */
class ChapterBuilder(
    private val html: String,
    private val spine: Int,
    private val textSize: Float,
    /** Text column width in pixels, for table column planning. */
    private val contentWidth: Int,
    /** Book styles on: honour presentational align="..." attributes too. */
    private val bookStyles: Boolean,
    /** Whether an element's resolved CSS asks for preserved whitespace (white-space: pre / pre-wrap). */
    private val preserves: (List<HtmlElement>, Int) -> Boolean,
    /** Drawable for an image source and its alt text. */
    private val image: (String, String) -> Drawable,
) {
    class Chapter(val text: SpannableStringBuilder, val positions: EpubPosition?, val elements: List<HtmlElement>, val ids: Map<String, Int>)

    private class XPath(val path: String, val children: MutableMap<String, Int> = HashMap(), var textNodes: Int = 0)
    private class Open(val tag: String, val element: Int, val xpath: XPath, val start: Int, val pre: Boolean, var items: Int = 0)

    private val text = SpannableStringBuilder()
    private val elements = ArrayList<HtmlElement>()
    private val ids = HashMap<String, Int>()
    private val nodes = ArrayList<EpubPosition.Node>()
    private val open = ArrayList<Open>()
    private val tables = ArrayList<Table>()
    private var row: MutableList<IntRange>? = null
    private var rowStart = 0
    private var cellStart = 0
    private val inCell get() = open.any { it.tag == "td" || it.tag == "th" }
    private var visible = 0

    fun build(): Chapter {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // Undeclared HTML entities (&mdash; in hand-made EPUBs) shouldn't sink the chapter.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(html.reader())
        ENTITIES.forEach { (name, code) -> parser.defineEntityReplacementText(name, String(Character.toChars(code))) }
        var inBody = false
        var hidden = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> require(!parser.text.orEmpty().contains("[")) { "Internal entities are not supported" }
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    when {
                        !inBody && tag == "body" -> { inBody = true; start(parser, tag, XPath("/body/DocFragment[${spine + 1}]/body")) }
                        !inBody -> Unit
                        hidden > 0 || tag in EpubPosition.NON_VISIBLE -> hidden++
                        else -> {
                            require(open.size < 64) { "EPUB nesting is too deep" }
                            val parent = open.last().xpath
                            val index = (parent.children[parser.name] ?: 0) + 1
                            parent.children[parser.name] = index
                            start(parser, tag, XPath("${parent.path}/${parser.name}[$index]"))
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (inBody && hidden == 0 && open.isNotEmpty()) characters(parser.text.orEmpty())
                XmlPullParser.END_TAG -> if (inBody) {
                    if (hidden > 0) hidden-- else if (open.isNotEmpty()) { end(open.removeAt(open.lastIndex)); if (open.isEmpty()) inBody = false }
                }
            }
            // next() coalesces entities into their enclosing text node, as XML DOM text does.
            event = parser.next()
        }
        while (text.isNotEmpty() && text[text.length - 1].let { it == ' ' || it == '\n' }) text.delete(text.length - 1, text.length)
        return Chapter(text, EpubPosition.of(nodes, text.toString()), elements, ids)
    }

    private fun start(parser: XmlPullParser, tag: String, xpath: XPath) {
        fun attribute(name: String) = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == name }
            ?.let { parser.getAttributeValue(it) }.orEmpty()
        // Inside a table cell, blocks flow as spaces: a row is one line drawn by TableRow.
        if (tag in BLOCKS) { if (inCell) space() else newline() }
        when (tag) {
            "table" -> tables += Table(contentWidth)
            "tr" -> { row = ArrayList(); rowStart = text.length }
            "td", "th" -> { space(); cellStart = text.length }
        }
        val element = HtmlElement(tag, attribute("id"), attribute("class").split(Regex("\\s+")).filter { it.isNotEmpty() },
            attribute("style"), open.lastOrNull()?.element ?: -1)
        elements += element
        if (tag == "a" || tag in BLOCKS) attributes[elements.lastIndex] =
            mapOf("href" to attribute("href"), "align" to attribute("align").lowercase()).filterValues { it.isNotEmpty() }
        attribute("id").takeIf { it.isNotEmpty() }?.let { ids.putIfAbsent(it, text.length) }
        val pre = tag == "pre" || (open.lastOrNull()?.pre ?: false) || preserves(elements, elements.lastIndex)
        open += Open(tag, elements.lastIndex, xpath, text.length, pre)
        when (tag) {
            "br" -> if (inCell) space() else { trimSpace(); text.append('\n') }
            "img", "image" -> {
                val source = attribute("src").ifEmpty { attribute("href") }
                if (source.isNotEmpty()) {
                    val at = text.length
                    text.append('￼')
                    text.setSpan(ImageSpan(image(source, attribute("alt")), ImageSpan.ALIGN_BASELINE), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // The image is its element's content, so CSS width/height can find and size it.
                    element.start = at; element.end = at + 1
                }
            }
            "hr" -> {
                val at = text.length
                text.append(' ')
                text.setSpan(Rule(), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "li" -> open.getOrNull(open.size - 2)?.let { if (it.tag == "ol" || it.tag == "ul") it.items++ }
        }
    }

    private fun end(node: Open) {
        val element = elements[node.element]
        // Images set their own range when added; keep it.
        if (element.start >= 0 && element.end < 0) element.end = nodes.lastOrNull()?.end?.coerceAtLeast(element.start) ?: element.start
        val start = node.start
        when (node.tag) {
            "td", "th" -> { trimSpace(); if (text.length > cellStart) row?.add(cellStart until text.length) }
            "tr" -> {
                trimSpace()
                val cells = row
                row = null
                val table = tables.lastOrNull()
                if (cells != null && cells.isNotEmpty() && table != null && text.length > rowStart) {
                    table.rows += cells
                    text.setSpan(TableRow(table, cells), rowStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            "table" -> tables.removeLastOrNull()
        }
        if (node.tag in BLOCKS) { if (inCell) space() else newline() }
        val end = text.length
        if (end <= start) return
        fun span(what: Any) = text.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Not SPAN_PARAGRAPH: a paragraph span ending at the buffer's end grows with every later append (nested margins cascade).
        fun paragraph(what: Any) = text.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val em = textSize
        when (node.tag) {
            "b", "strong" -> span(StyleSpan(Typeface.BOLD))
            "i", "em", "cite", "var", "dfn", "address" -> span(StyleSpan(Typeface.ITALIC))
            "u", "ins" -> span(UnderlineSpan())
            "s", "strike", "del" -> span(StrikethroughSpan())
            "sup" -> { span(SuperscriptSpan()); span(RelativeSizeSpan(0.75f)) }
            "sub" -> { span(SubscriptSpan()); span(RelativeSizeSpan(0.75f)) }
            "small" -> span(RelativeSizeSpan(0.83f))
            "big" -> span(RelativeSizeSpan(1.2f))
            "tt", "code", "kbd", "samp" -> span(TypefaceSpan("monospace"))
            "pre" -> span(TypefaceSpan("monospace"))
            "a" -> attributeOf(node, "href")?.let { span(URLSpan(it)) }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                span(StyleSpan(Typeface.BOLD))
                span(RelativeSizeSpan(HEADING_SIZES[node.tag[1] - '1']))
            }
            "blockquote", "dd" -> paragraph(LeadingMarginSpan.Standard((em * 1.5f).toInt()))
            "center" -> paragraph(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
            "li" -> {
                val list = open.lastOrNull { it.tag == "ol" || it.tag == "ul" }
                paragraph(ListMarker(if (list?.tag == "ol") "${list.items}." else "•", (em * 1.6f).toInt()))
            }
        }
        if (bookStyles && node.tag in BLOCKS) when (attributeOf(node, "align")) {
            "center" -> paragraph(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
            "right" -> paragraph(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE))
        }
    }

    private val attributes = HashMap<Int, Map<String, String>>()
    private fun attributeOf(node: Open, name: String): String? = attributes[node.element]?.get(name)

    /** Collapse whitespace like HTML unless an enclosing element preserves it; record the XPath text node. */
    private fun characters(raw: String) {
        val node = open.last()
        node.xpath.textNodes++
        val sourceStart = visible
        visible += raw.codePointCount(0, raw.length)
        var first = -1
        var last = -1
        var i = 0
        while (i < raw.length) {
            val code = raw.codePointAt(i)
            i += Character.charCount(code)
            if (node.pre) {
                if (code == '\r'.code) continue
                text.append(String(Character.toChars(code)))
            } else if (code == ' '.code || code == '\t'.code || code == '\n'.code || code == '\r'.code || code == 0x0c) {
                if (text.isNotEmpty() && text[text.length - 1] != ' ' && text[text.length - 1] != '\n') text.append(' ')
                continue
            } else text.append(String(Character.toChars(code)))
            if (!EpubPosition.ignored(code)) {
                if (first < 0) first = text.length - Character.charCount(code)
                last = text.length
            }
        }
        if (first < 0) return
        nodes += EpubPosition.Node("${node.xpath.path}/text()[${node.xpath.textNodes}]", raw, first, last, sourceStart)
        open.forEach { if (elements[it.element].start < 0) elements[it.element].start = first }
    }

    private fun space() { if (text.isNotEmpty() && text[text.length - 1] != ' ' && text[text.length - 1] != '\n') text.append(' ') }

    private fun trimSpace() { while (text.isNotEmpty() && text[text.length - 1] == ' ') text.delete(text.length - 1, text.length) }

    private fun newline() {
        trimSpace()
        if (text.isNotEmpty() && text[text.length - 1] != '\n') text.append('\n')
    }

    /** One table's rows; columns are planned once from every row's natural cell widths (firmware-style). */
    private class Table(val width: Int) {
        val rows = ArrayList<List<IntRange>>()
        private var columns: IntArray? = null

        fun columns(paint: TextPaint, text: CharSequence): IntArray = columns ?: run {
            val count = rows.maxOf { it.size }
            val natural = FloatArray(count)
            rows.forEach { cells -> cells.forEachIndexed { i, cell -> natural[i] = maxOf(natural[i], paint.measureText(text, cell.first, cell.last + 1) + PAD * 2) } }
            val total = natural.sum().coerceAtLeast(1f)
            // Natural widths when they fit; otherwise proportional, with every column at least a sixth of its share.
            val floor = width / count / 6f
            val widths = if (total <= width) natural.map { it + (width - total) / count } else natural.map { maxOf(floor, it / total * width) }
            val scale = width / widths.sum()
            widths.map { (it * scale).toInt() }.toIntArray().also { columns = it }
        }
    }

    /** A table row drawn as one line: each cell is its own wrapped layout in its column, with a thin grid. */
    private class TableRow(private val table: Table, private val cells: List<IntRange>) : ReplacementSpan(), WholeLine {
        private var layouts: List<StaticLayout>? = null
        private var key = 0f

        private fun layouts(paint: Paint, text: CharSequence): List<StaticLayout> {
            if (layouts != null && key == paint.textSize) return layouts!!
            val textPaint = TextPaint(paint)
            val columns = table.columns(textPaint, text)
            return cells.mapIndexed { i, cell ->
                // Copy the cell without row spans, so laying it out can't recurse into this span.
                val copy = SpannableStringBuilder(text, cell.first, cell.last + 1)
                copy.getSpans(0, copy.length, TableRow::class.java).forEach(copy::removeSpan)
                StaticLayout.Builder.obtain(copy, 0, copy.length, textPaint, (columns.getOrElse(i) { columns.last() } - PAD * 2).coerceAtLeast(1)).build()
            }.also { layouts = it; key = paint.textSize }
        }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val height = layouts(paint, text).maxOf { it.height } + PAD * 2
            if (fm != null) {
                val descent = paint.fontMetricsInt.descent
                fm.descent = descent; fm.bottom = descent
                fm.ascent = -(height - descent); fm.top = fm.ascent
            }
            return table.width
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val columns = table.columns(TextPaint(paint), text)
            var left = x
            val grid = Paint(paint).apply { style = Paint.Style.FILL; alpha = 140 }
            canvas.drawRect(x, top.toFloat(), x + table.width, top + 1f, grid)
            canvas.drawRect(x, bottom - 1f, x + table.width, bottom.toFloat(), grid)
            layouts(paint, text).forEachIndexed { i, layout ->
                canvas.drawRect(left, top.toFloat(), left + 1f, bottom.toFloat(), grid)
                canvas.save()
                canvas.translate(left + PAD, top + PAD.toFloat())
                layout.draw(canvas)
                canvas.restore()
                left += columns.getOrElse(i) { columns.last() }
            }
            canvas.drawRect(x + table.width - 1f, top.toFloat(), x + table.width, bottom.toFloat(), grid)
        }
    }

    /** Bullet or number drawn in the paragraph's leading margin; never part of the text. */
    private class ListMarker(private val label: String, private val width: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = width
        override fun drawLeadingMargin(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
                                       text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            if (!first || start != (text as Spanned).getSpanStart(this)) return
            val labelWidth = paint.measureText(label)
            canvas.drawText(label, x + dir * (width - labelWidth - width * 0.25f).coerceAtLeast(0f), baseline.toFloat(), paint)
        }
    }

    /** <hr>: a thin centered rule across the line. */
    private class Rule : LineBackgroundSpan {
        override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
                                    text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            val width = (right - left) / 3f
            val middle = (top + bottom) / 2f
            canvas.drawRect((left + right) / 2f - width / 2, middle, (left + right) / 2f + width / 2, middle + 1.5f, paint)
        }
    }

    /** Spans drawn across the full text width (table rows); paragraph indents must leave them alone. */
    interface WholeLine

    companion object {
        private const val PAD = 6
        private val BLOCKS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "ul", "ol", "dl", "dt", "dd",
            "section", "article", "aside", "header", "footer", "figure", "figcaption", "pre", "table", "tr", "caption", "hr", "nav",
            "address", "center", "main", "body", "hgroup", "details", "summary")
        private val HEADING_SIZES = floatArrayOf(1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1.0f)

        /** HTML named entities XHTML parsers don't know: Latin-1 plus the common typographic set. */
        private val ENTITIES: Map<String, Int> = run {
            val latin1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute " +
                "micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil " +
                "Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute " +
                "Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute " +
                "icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(' ')
            latin1.mapIndexed { i, name -> name to 160 + i }.toMap() + mapOf(
                "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402, "circ" to 710,
                "tilde" to 732, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205, "lrm" to 8206,
                "rlm" to 8207, "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218, "ldquo" to 8220,
                "rdquo" to 8221, "bdquo" to 8222, "dagger" to 8224, "Dagger" to 8225, "bull" to 8226, "hellip" to 8230, "permil" to 8240,
                "prime" to 8242, "Prime" to 8243, "lsaquo" to 8249, "rsaquo" to 8250, "euro" to 8364, "trade" to 8482, "larr" to 8592,
                "uarr" to 8593, "rarr" to 8594, "darr" to 8595, "harr" to 8596, "minus" to 8722, "infin" to 8734, "ne" to 8800,
                "le" to 8804, "ge" to 8805, "loz" to 9674, "spades" to 9824, "clubs" to 9827, "hearts" to 9829, "diams" to 9830)
        }
    }
}
