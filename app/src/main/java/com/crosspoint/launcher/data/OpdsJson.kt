package com.crosspoint.launcher.data

import com.crosspoint.launcher.model.OpdsEntry
import com.crosspoint.launcher.model.OpdsFeed
import com.crosspoint.launcher.model.OpdsLink
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** OPDS 2 collections and publication documents; all links are resolved at their source. */
object OpdsJson {
    fun document(source: String): JSONObject {
        var depth = 0
        var quoted = false
        var escaped = false
        source.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) { "Catalog JSON nesting exceeds the low-memory limit" } }
                '}', ']' -> depth--
            }
        }
        return JSONObject(source)
    }

    fun objects(value: JSONArray?): List<JSONObject> = if (value == null) emptyList() else
        (0 until value.length()).mapNotNull { value.optJSONObject(it) }

    fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString(", ") { text(value.opt(it)) }
        is JSONObject -> {
            if (value.has("name")) text(value.opt("name")) else {
                val locale = Locale.getDefault()
                val key = listOf(locale.toLanguageTag(), locale.language, "en", "und")
                    .firstOrNull { value.has(it) } ?: value.keys().asSequence().firstOrNull()
                key?.let { text(value.opt(it)) }.orEmpty()
            }
        }
        else -> ""
    }

    fun links(array: JSONArray?, base: String): List<OpdsLink> = objects(array).mapNotNull { obj ->
        val href = obj.optString("href").takeUnless { it.isBlank() || it == "null" } ?: return@mapNotNull null
        val rel = obj.opt("rel")
        OpdsLink(
            OpdsRepository.resolveUrl(base, href),
            obj.optString("type").substringBefore(';').trim().lowercase(Locale.ROOT),
            if (rel is JSONArray) (0 until rel.length()).map { rel.optString(it) } else (rel as? String)?.split(' ').orEmpty(),
            text(obj.opt("title")), obj.optJSONObject("properties") ?: JSONObject()
        )
    }

    fun publication(obj: JSONObject, base: String, heading: String = ""): OpdsEntry {
        val metadata = obj.optJSONObject("metadata") ?: error("Publication has no metadata")
        val links = links(obj.optJSONArray("links"), base)
        val self = links.firstOrNull { it.hasRel("self") }?.href
        val acquisitions = links.filter { it.acquisition != null }.sortedWith(
            compareByDescending<OpdsLink> { when (it.acquisition) {
                "download" -> 5; "acquisition" -> 4; "borrow" -> 3; "subscribe" -> 2; "buy" -> 1; else -> 0
            } }.thenByDescending { it.mediaType == EPUB_MIME }
        )
        val images = objects(obj.optJSONArray("images")).filter {
            it.optString("type").substringBefore(';') in listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")
        }.sortedBy { it.optInt("width", Int.MAX_VALUE).let { w -> if (w > 0) w else Int.MAX_VALUE } }
        val cover = images.firstOrNull()?.optString("href")?.takeIf { it.isNotBlank() }
            ?.let { OpdsRepository.resolveUrl(base, it) }
        val description = text(metadata.opt("description")).take(16_384)
        val contributors = listOf("author", "translator", "editor", "artist", "illustrator", "narrator", "publisher", "subject")
            .flatMap { key ->
                val value = metadata.opt(key)
                val items = if (value is JSONArray) objects(value) else listOfNotNull(value as? JSONObject)
                items.flatMap { item -> links(item.optJSONArray("links"), base).map { it.copy(title = it.title.ifBlank { text(item.opt("name")) }) } }
            }
        val series = metadata.optJSONObject("belongsTo")
        val seriesLinks = listOf("series", "collection").flatMap { key ->
            val value = series?.opt(key)
            (if (value is JSONArray) objects(value) else listOfNotNull(value as? JSONObject)).flatMap { item ->
                links(item.optJSONArray("links"), base).map { it.copy(title = it.title.ifBlank { text(item.opt("name")) }) }
            }
        }
        return OpdsEntry(
            OpdsEntry.Type.BOOK, text(metadata.opt("title")).ifBlank { "Untitled" }, text(metadata.opt("author")),
            acquisitions.firstOrNull()?.href ?: self ?: base, cover,
            text(metadata.opt("identifier")), heading, description,
            listOf(text(metadata.opt("publisher")), text(metadata.opt("language")),
                metadata.optInt("numberOfPages").takeIf { it > 0 }?.let { "$it pages" }.orEmpty(),
                text(series?.opt("series"))).filter { it.isNotBlank() }.joinToString(" · "),
            acquisitions + links.filter { it.acquisition == null } + contributors + seriesLinks, self
        )
    }

    fun parse(obj: JSONObject, base: String): OpdsFeed {
        val metadata = obj.optJSONObject("metadata") ?: error("Not an OPDS catalog: missing metadata")
        val feedLinks = links(obj.optJSONArray("links"), base)
        val entries = ArrayList<OpdsEntry>()
        fun navigation(links: List<OpdsLink>, heading: String) {
            links.forEach { link ->
                val count = link.properties.optInt("numberOfItems", -1)
                entries.add(OpdsEntry(OpdsEntry.Type.NAVIGATION, link.title.ifBlank { "See all" }, "", link.href,
                    heading = heading, links = listOf(link), detail = listOfNotNull(
                        if (link.hasRel("self")) "Selected" else null, if (count >= 0) "$count titles" else null
                    ).joinToString(" · ")))
            }
        }
        fun collection(collection: JSONObject, heading: String) {
            navigation(links(collection.optJSONArray("navigation"), base), heading)
            objects(collection.optJSONArray("publications")).forEach { entries.add(publication(it, base, heading)) }
        }
        if (obj.has("navigation") || obj.has("publications") || obj.has("groups")) {
            collection(obj, "")
            objects(obj.optJSONArray("groups")).forEach { group ->
                val heading = text(group.optJSONObject("metadata")?.opt("title"))
                collection(group, heading)
                navigation(links(group.optJSONArray("links"), base).filter { it.hasRel("self") }.map {
                    it.copy(title = "See all", rels = emptyList())
                }, heading)
            }
        } else entries.add(publication(obj, base))
        objects(obj.optJSONArray("facets")).forEach { facet ->
            navigation(links(facet.optJSONArray("links"), base), text(facet.optJSONObject("metadata")?.opt("title")))
        }
        navigation(feedLinks.filter { link -> link.rels.any {
            it in listOf("http://opds-spec.org/shelf", "http://opds-spec.org/wishlist", "http://opds-spec.org/history", "start", "up", "http://opds-spec.org/auth/document")
        } }.map { it.copy(title = it.title.ifBlank { it.rels.first().substringAfterLast('/').replaceFirstChar(Char::uppercase) }) }, "Catalog")
        val perPage = metadata.optInt("itemsPerPage")
        val count = metadata.optLong("numberOfItems")
        fun href(rel: String) = feedLinks.firstOrNull { it.hasRel(rel) }?.href
        return OpdsFeed(text(metadata.opt("title")).ifBlank { "Catalog" }, entries,
            href("next"), href("previous") ?: href("prev"), href("search"), href("first"), href("last"),
            metadata.optInt("currentPage"), if (perPage > 0 && count > 0) ((count + perPage - 1) / perPage).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0, base)
    }

    /** RFC 6570 query expansion plus the OPDS 1 OpenSearch spelling. */
    fun expandSearch(template: String, values: Map<String, String>): String = Regex("\\{([^}]+)\\}").replace(template) { match ->
        val expression = match.groupValues[1]
        val operator = expression.firstOrNull()?.takeIf { it in "?&+#/;." }
        val variables = (if (operator == null) expression else expression.drop(1)).split(',')
        val expanded = variables.mapNotNull { variable ->
            val name = variable.removeSuffix("?").removeSuffix("*").replace(Regex(":\\d+$"), "")
            val bare = name.substringAfterLast(':')
            val value = values[bare] ?: return@mapNotNull null
            val limit = Regex(":(\\d+)$").find(variable)?.groupValues?.get(1)?.toIntOrNull()
            val raw = if (limit != null) value.take(limit) else value
            val encoded = if (operator == '+' || operator == '#') {
                var value = encode(raw)
                ":/?#[]@!$&'()*+,;=".forEach { value = value.replace(encode(it.toString()), it.toString()) }
                value
            } else encode(raw)
            if (operator == '?' || operator == '&' || operator == ';') "${encode(name)}=$encoded" else encoded
        }
        if (expanded.isEmpty()) "" else when (operator) {
            '?', '&' -> operator + expanded.joinToString("&")
            '/', '.', ';' -> operator + expanded.joinToString(operator.toString())
            '#' -> "#" + expanded.joinToString(",")
            else -> expanded.joinToString(",")
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
}
