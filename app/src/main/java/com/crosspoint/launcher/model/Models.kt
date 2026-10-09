package com.crosspoint.launcher.model

import androidx.compose.ui.graphics.ImageBitmap

/** Visual layout themes, mirroring the CrossPoint reader "Lyra" family plus two new ones. */
enum class ThemeType(val id: String, val label: String, val description: String) {
    LYRA("lyra", "Lyra", "One featured cover with a two-column icon menu"),
    LYRA_3_COVERS("lyra3", "Lyra Covers 3", "Three recent covers in a row"),
    CAROUSEL("carousel", "Carousel", "Swipeable cover carousel, centered focus"),
    MINIMAL("minimal", "Minimal", "No covers — a Niagara-style pinned list");

    companion object {
        fun from(id: String?): ThemeType = entries.firstOrNull { it.id == id } ?: LYRA
    }
}

/** Light/dark selection for the black & white e-ink palette. */
enum class AppearanceMode(val id: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    SYSTEM("system", "Follow System");

    companion object {
        fun from(id: String?): AppearanceMode = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

enum class LauncherIconStyle(val id: String, val label: String) {
    ICONS("icons", "Icons"),
    DOTS("dots", "Dots"),
    SQUARES("squares", "Squares"),
    CIRCLES("circles", "Circles");

    companion object {
        fun from(id: String?): LauncherIconStyle = entries.firstOrNull { it.id == id } ?: ICONS
    }
}

enum class CoverSlotMode(val id: String, val label: String) {
    BOOKS("books", "Books"),
    WIDGET("widget", "Widget");

    companion object {
        fun from(id: String?): CoverSlotMode = entries.firstOrNull { it.id == id } ?: BOOKS
    }
}

/** What the e-ink sleep screen shows. BOOK_COVER tracks the most recent book automatically. */
enum class SleepScreenMode(val id: String, val label: String) {
    OFF("off", "Off"),
    BOOK_COVER("book", "Book Cover"),
    CUSTOM("custom", "Custom");

    companion object {
        fun from(id: String?): SleepScreenMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** A parsed local EPUB book. [coverPath] points to a cached cover image file, if extracted. */
data class Book(
    val filePath: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val lastModified: Long
)

/** An installed, launchable Android application. */
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: ImageBitmap?,
    val category: Int = -1
)

data class ExternalReadingState(
    val source: String,
    val packageName: String,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
    val shortcutId: String? = null,
    val launchUri: String? = null,
    val updatedAt: Long = 0L
)

/** Which vertical swipe gesture (if any) opens the app-search overlay on the home screen. */
enum class SearchGesture(val id: String, val label: String) {
    OFF("off", "Off"),
    SWIPE_UP("up", "Swipe Up"),
    SWIPE_DOWN("down", "Swipe Down"),
    BOTH("both", "Swipe Up or Down");

    companion object {
        fun from(id: String?): SearchGesture = entries.firstOrNull { it.id == id } ?: SWIPE_UP
    }
}

/**
 * Home-screen menu entries, mirroring the CrossPoint reader's Lyra home menu. OPDS_BROWSER is
 * shown automatically when at least one OPDS server is configured (as the reader does); APPS is
 * an extra launcher-specific entry the user can enable.
 */
enum class HomeMenuItem(val id: String, val label: String, val icon: String) {
    BROWSE_BOOKS("browse", "Browse Books", "folder"),
    RECENT_BOOKS("recent", "Recent Books", "book"),
    OPDS_BROWSER("opds", "OPDS Browser", "library"),
    FILE_TRANSFER("transfer", "File Transfer", "send_horizontal"),
    APPS("apps", "All Apps", "layout_grid"),
    SETTINGS("settings", "Settings", "settings");

    companion object {
        fun from(id: String): HomeMenuItem? = entries.firstOrNull { it.id == id }
    }
}

/** A configured OPDS catalog server (matches the reader's OpdsServer struct). */
data class OpdsServer(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = ""
)

/** A catalog link, including acquisition and lending information. */
data class OpdsLink(
    val href: String,
    val mediaType: String = "",
    val rels: List<String> = emptyList(),
    val title: String = "",
    val properties: org.json.JSONObject = org.json.JSONObject()
) {
    fun hasRel(rel: String) = rel in rels
    val acquisition: String? get() = rels.firstNotNullOfOrNull { rel ->
        when (rel) {
            "http://opds-spec.org/acquisition" -> "acquisition"
            "http://opds-spec.org/acquisition/open-access", "open-access" -> "download"
            "http://opds-spec.org/acquisition/sample", "sample" -> "preview"
            else -> rel.removePrefix("http://opds-spec.org/acquisition/")
                .takeIf { it in listOf("download", "acquisition", "borrow", "buy", "subscribe", "preview") }
        }
    }
}

/** A publication or navigation row shared by OPDS 1.x and 2.0. */
data class OpdsEntry(
    val type: Type,
    val title: String,
    val author: String,
    val href: String,
    val thumbnailHref: String? = null,
    val id: String = "",
    val heading: String = "",
    val description: String = "",
    val metadata: String = "",
    val links: List<OpdsLink> = emptyList(),
    val selfHref: String? = null,
    val detail: String = ""
) {
    enum class Type { NAVIGATION, BOOK }
}

/** A parsed OPDS feed page: entries plus pagination + search metadata. */
data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String? = null,
    val prevUrl: String? = null,
    val searchTemplate: String? = null,
    val firstUrl: String? = null,
    val lastUrl: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val url: String = ""
)
