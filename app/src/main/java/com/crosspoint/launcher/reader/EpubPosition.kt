package com.crosspoint.launcher.reader

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Maps only the displayed chapter between Android text offsets and KOReader/CrossPoint XPaths, and to the firmware's
 * clipping offsets: visible Unicode codepoints counted from the start of <body> (raw text, whitespace included).
 */
class EpubPosition private constructor(private val nodes: List<Node>, private val rendered: String) {
    /** One XHTML text node: its XPath, raw text, rendered range and firmware visible-text offset. */
    class Node(val path: String, val raw: String, val start: Int, val end: Int, val source: Int)
    /** A body element and the rendered range of its text ([start, end), start = -1 when it has no text). */
    class HtmlElement(val tag: String, val id: String, val classes: List<String>, val style: String, val parent: Int) {
        var start = -1
        var end = -1
    }

    private data class Element(val path: String, val children: MutableMap<String, Int> = HashMap(), var textNodes: Int = 0)

    fun xpath(offset: Int): String? {
        val node = nodes.firstOrNull { offset < it.end } ?: nodes.lastOrNull() ?: return null
        return node.path + "." + node.raw.codePointCount(0, rawIndex(node, offset))
    }

    /** Firmware clipping offset for a rendered text offset. */
    fun sourceOffset(offset: Int): Int? {
        val node = nodes.firstOrNull { offset < it.end } ?: nodes.lastOrNull() ?: return null
        return node.source + node.raw.codePointCount(0, rawIndex(node, offset))
    }

    /** Rendered text offset for a firmware clipping offset. */
    fun renderedOffset(source: Int): Int? {
        val node = nodes.lastOrNull { it.source <= source } ?: return null
        val points = source - node.source
        if (points > node.raw.codePointCount(0, node.raw.length)) return node.end
        return rendered(node, node.raw.offsetByCodePoints(0, points))
    }

    /** Raw index of the character shown at [offset]; skipped whitespace before it counts, so offsets match the firmware's. */
    private fun rawIndex(node: Node, offset: Int): Int {
        var cursor = node.start
        var source = 0
        while (source < node.raw.length) {
            val code = node.raw.codePointAt(source)
            if (!ignored(code)) {
                while (cursor < rendered.length && ignored(rendered.codePointAt(cursor))) cursor += Character.charCount(rendered.codePointAt(cursor))
                if (cursor >= offset) break
                cursor += Character.charCount(code)
            }
            source += Character.charCount(code)
        }
        return source
    }

    fun offset(xpath: String): Int? {
        val match = Regex("^(.*?)(?:\\.(\\d+))?$").matchEntire(xpath) ?: return null
        val path = canonical(match.groupValues[1])
        val node = nodes.firstOrNull { canonical(it.path) == path }
            ?: return nodes.firstOrNull { canonical(it.path).startsWith("$path/") }?.start
        val points = match.groupValues[2].toIntOrNull() ?: 0
        if (points > node.raw.codePointCount(0, node.raw.length)) return null
        return rendered(node, node.raw.offsetByCodePoints(0, points))
    }

    private fun rendered(node: Node, limit: Int): Int {
        var cursor = node.start
        var source = 0
        while (source < limit) {
            val code = node.raw.codePointAt(source)
            if (!ignored(code)) {
                while (cursor < rendered.length && ignored(rendered.codePointAt(cursor))) cursor += Character.charCount(rendered.codePointAt(cursor))
                cursor += Character.charCount(code)
            }
            source += Character.charCount(code)
        }
        while (cursor < node.end && ignored(rendered.codePointAt(cursor))) cursor += Character.charCount(rendered.codePointAt(cursor))
        return cursor.coerceAtMost(node.end)
    }

    companion object {
        /** Positions recorded directly by a renderer that built [rendered] itself (see ChapterBuilder). */
        fun of(nodes: List<Node>, rendered: String): EpubPosition? = EpubPosition(nodes, rendered).takeIf { nodes.isNotEmpty() }

        /** Elements whose text the firmware leaves out of clipping offsets (VisibleTextUtils::isNonVisibleElement). */
        val NON_VISIBLE = setOf("head", "style", "script", "title", "rp")
        fun ignored(code: Int) = Character.isWhitespace(code) || Character.isSpaceChar(code) || code == 0xfffc
        private fun canonical(path: String) = path.replace("[1]", "")
        fun spine(xpath: String): Int? = Regex("^/body/DocFragment(?:\\[(\\d+)\\])?/body(?:/|$)")
            .find(xpath)?.let { (it.groupValues[1].toIntOrNull() ?: 1) - 1 }

        /** Invalid/non-XHTML chapters fall back to chapter/percentage; never invent a text anchor. */
        /** [elements], when given, receives every body element in document order (for the reader's CSS). */
        fun build(html: String, rendered: String, spine: Int, elements: MutableList<HtmlElement>? = null): EpubPosition? = runCatching {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(html.reader())
            parser.defineEntityReplacementText("nbsp", "\u00a0")
            val stack = ArrayList<Element>()
            val nodes = ArrayList<Node>()
            var inBody = false
            var cursor = 0
            var visible = 0
            var hidden = 0
            val open = ArrayList<Int>()
            fun record() {
                if (elements == null || hidden > 0) return
                fun attribute(name: String) = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == name }
                    ?.let { parser.getAttributeValue(it) }.orEmpty()
                elements += HtmlElement(parser.name.lowercase(), attribute("id"), attribute("class").split(Regex("\\s+")).filter { it.isNotEmpty() },
                    attribute("style"), open.lastOrNull() ?: -1)
                open += elements.lastIndex
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> require(!parser.text.contains("[")) { "Internal entities are not supported" }
                    XmlPullParser.START_TAG -> {
                        if (inBody && (hidden > 0 || parser.name.lowercase() in NON_VISIBLE)) hidden++
                        if (parser.name == "body" && !inBody) {
                            inBody = true; stack.add(Element("/body/DocFragment[${spine + 1}]/body"))
                            record()
                        } else if (inBody) {
                            record()
                            require(stack.size < 64) { "EPUB nesting is too deep" }
                            val parent = stack.last()
                            val index = (parent.children[parser.name] ?: 0) + 1
                            parent.children[parser.name] = index
                            stack.add(Element("${parent.path}/${parser.name}[$index]"))
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (inBody && hidden > 0) {
                        // Html may or may not render <style>/<script> text; step over it when it does, never count it.
                        var probe = cursor
                        var source = 0
                        val raw = parser.text.orEmpty()
                        while (source < raw.length) {
                            val code = raw.codePointAt(source)
                            if (!ignored(code)) {
                                while (probe < rendered.length && ignored(rendered.codePointAt(probe))) probe += Character.charCount(rendered.codePointAt(probe))
                                if (probe >= rendered.length || rendered.codePointAt(probe) != code) { probe = cursor; break }
                                probe += Character.charCount(code)
                            }
                            source += Character.charCount(code)
                        }
                        cursor = probe
                    } else if (inBody) {
                        val parent = stack.last()
                        parent.textNodes++
                        val raw = parser.text.orEmpty()
                        val sourceStart = visible
                        visible += raw.codePointCount(0, raw.length)
                        // Html.fromHtml may render head text (<title>) before the body; skip to the body's first character.
                        if (nodes.isEmpty()) raw.codePoints().filter { !ignored(it) }.findFirst().ifPresent { code ->
                            rendered.indexOf(String(Character.toChars(code)), cursor).takeIf { it >= 0 }?.let { cursor = it }
                        }
                        while (cursor < rendered.length && ignored(rendered.codePointAt(cursor))) cursor += Character.charCount(rendered.codePointAt(cursor))
                        val start = cursor
                        var source = 0
                        while (source < raw.length) {
                            val code = raw.codePointAt(source)
                            if (!ignored(code)) {
                                while (cursor < rendered.length && ignored(rendered.codePointAt(cursor))) cursor += Character.charCount(rendered.codePointAt(cursor))
                                require(cursor < rendered.length && rendered.codePointAt(cursor) == code) { "Text rendering differs from XHTML" }
                                cursor += Character.charCount(code)
                            }
                            source += Character.charCount(code)
                        }
                        if (cursor > start) {
                            nodes.add(Node("${parent.path}/text()[${parent.textNodes}]", raw, start, cursor, sourceStart))
                            elements?.let { list -> open.forEach { if (list[it].start < 0) list[it].start = start } }
                        }
                    }
                    XmlPullParser.END_TAG -> if (inBody) {
                        if (hidden > 0) hidden--
                        else if (elements != null && open.isNotEmpty()) elements[open.removeAt(open.lastIndex)].end = cursor
                        stack.removeAt(stack.lastIndex); if (stack.isEmpty()) inBody = false
                    }
                }
                // next() coalesces entities into their enclosing text node, as XML DOM text does.
                event = parser.next()
            }
            EpubPosition(nodes, rendered).takeIf { nodes.isNotEmpty() }
        }.getOrNull()
    }
}
