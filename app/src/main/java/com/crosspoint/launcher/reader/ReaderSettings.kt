package com.crosspoint.launcher.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.res.ResourcesCompat
import com.crosspoint.launcher.R
import android.graphics.Typeface
import android.os.Environment
import java.io.File

/**
 * Reader preferences mirroring CrossPoint firmware's Reader and Controls settings, stored in the "reader" prefs.
 * Each setting stores its chosen value; the UI works with option indices.
 */
class ReaderSettings(private val prefs: SharedPreferences) {
    class Setting(val key: String, val title: String, val labels: List<String>, val default: Int,
                  val values: List<Int> = labels.indices.toList(), val stepper: Boolean = false, val icon: String = "settings") {
        val toggle get() = labels == OFF_ON
    }

    init {
        // Earlier builds stored this as a boolean.
        if (prefs.all["keep_screen_on"] is Boolean) prefs.edit().putInt(KEEP_AWAKE.key, if (prefs.getBoolean("keep_screen_on", false)) 1 else 0)
            .remove("keep_screen_on").apply()
    }

    fun index(setting: Setting): Int = setting.values.indexOf(prefs.getInt(setting.key, setting.values[setting.default]))
        .takeIf { it >= 0 } ?: setting.default
    operator fun get(setting: Setting): Int = setting.values[index(setting)]
    fun on(setting: Setting) = get(setting) != 0
    fun set(setting: Setting, index: Int) = prefs.edit().putInt(setting.key, setting.values[index.coerceIn(0, setting.values.lastIndex)]).apply()
    fun label(setting: Setting) = setting.labels[index(setting)]

    var fontFamily: String
        get() = prefs.getString("font_family", null)?.let { LEGACY_FONTS[it] ?: it } ?: BUNDLED_FONTS.keys.first()
        set(value) = prefs.edit().putString("font_family", value).apply()

    /** The firmware's built-in reader fonts, then fonts added from the device and the shared Fonts folder. */
    fun fontFamilies(context: Context): List<String> = BUNDLED_FONTS.keys.toList() + deviceFonts(context).keys.sorted()

    /** Firmware getReaderLineCompression: per-family multipliers on the font's natural line height. */
    fun lineMultiplier(): Float = when (fontFamily) {
        "Noto Sans" -> floatArrayOf(0.90f, 0.95f, 1.0f, 1.05f)
        in BUNDLED_FONTS -> floatArrayOf(0.95f, 1.0f, 1.1f, 1.2f)
        else -> floatArrayOf(0.95f, 1.0f, 1.3f, 1.6f) // SD-card / device fonts get the firmware's wider scale
    }[index(LINE_SPACING)]

    fun typeface(context: Context, name: String = fontFamily): Typeface =
        BUNDLED_FONTS[name]?.let { ResourcesCompat.getFont(context, it) }
            ?: deviceFonts(context)[name]?.let { loadFamily(it) }
            ?: ResourcesCompat.getFont(context, R.font.noto_serif)!!

    companion object {
        /** Bundled from the firmware's builtinFonts sources (OFL, see assets/licenses). */
        val BUNDLED_FONTS = linkedMapOf("Noto Serif" to R.font.noto_serif, "Noto Sans" to R.font.noto_sans,
            "OpenDyslexic" to R.font.open_dyslexic)
        private val LEGACY_FONTS = mapOf("Serif" to "Noto Serif", "Sans" to "Noto Sans", "Monospace" to "Noto Sans")
        /** The firmware's font roots (/.fonts preferred, then /fonts), plus Android's usual /Fonts. */
        private val FONT_DIRS = listOf(".fonts", "fonts", "Fonts")
        private val FONT_TYPES = listOf("ttf", "otf", "ttc")
        private val loaded = HashMap<String, Typeface?>()

        /** Fonts the reader copied in from the device (removable), stored privately. */
        fun importedFontDir(context: Context) = File(context.filesDir, "fonts")

        /**
         * Font families like the firmware's SdCardFontRegistry: each folder under a font root is one family (its
         * Regular/Bold/Italic/Bold Italic files), and a loose .ttf/.otf/.ttc at a root is a one-style family.
         * Fonts added from the device come first, then /.fonts, /fonts and /Fonts; the first family of a name wins.
         */
        fun deviceFonts(context: Context): Map<String, List<File>> {
            val families = LinkedHashMap<String, List<File>>()
            fun isFont(file: File) = file.isFile && file.extension.lowercase() in FONT_TYPES
            (listOf(importedFontDir(context)) + FONT_DIRS.map { File(Environment.getExternalStorageDirectory(), it) }).forEach { root ->
                root.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { entry ->
                    if (entry.isDirectory && !entry.name.startsWith(".") && !entry.name.startsWith("_")) {
                        val files = entry.listFiles(::isFont).orEmpty().toList()
                        if (files.isNotEmpty()) families.putIfAbsent(entry.name, files)
                    } else if (isFont(entry)) families.putIfAbsent(entry.nameWithoutExtension, listOf(entry))
                }
            }
            return families
        }

        /**
         * One Typeface from a family's files with real bold and italic faces. Like the firmware's refineVectorStyles,
         * each file's weight and italic come from the font itself (falling back to "bold"/"italic"/"oblique" in its
         * name), then the upright faces nearest 400 and 700 become Regular and Bold, and the same for italics.
         */
        @Synchronized fun loadFamily(files: List<File>): Typeface? = loaded.getOrPut(files.joinToString("|") { "${it.path}:${it.lastModified()}" }) {
            runCatching {
                class Face(val file: File, val weight: Int, val italic: Boolean)
                val faces = files.mapNotNull { file ->
                    val font = runCatching { android.graphics.fonts.Font.Builder(file).build() }.getOrNull() ?: return@mapNotNull null
                    val name = file.nameWithoutExtension.lowercase()
                    val weight = font.style.weight.takeIf { it in 1..1000 } ?: if ("bold" in name) 700 else 400
                    Face(file, weight, font.style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC || "italic" in name || "oblique" in name)
                }
                if (faces.isEmpty()) return@runCatching null
                val roles = listOf(400 to false, 700 to false, 400 to true, 700 to true).mapNotNull { (target, italic) ->
                    faces.filter { it.italic == italic }.minByOrNull { kotlin.math.abs(it.weight - target) }?.let { Triple(target, italic, it) }
                }.distinctBy { it.third.file }
                fun font(role: Triple<Int, Boolean, Face>) = android.graphics.fonts.Font.Builder(role.third.file)
                    .setWeight(role.first).setSlant(if (role.second) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT).build()
                val family = android.graphics.fonts.FontFamily.Builder(font(roles.first()))
                roles.drop(1).forEach { family.addFont(font(it)) }
                val regular = roles.first()
                Typeface.CustomFallbackBuilder(family.build())
                    .setStyle(android.graphics.fonts.FontStyle(regular.first, if (regular.second) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
                    .build()
            }.getOrNull()
        }

        /** Family name for a set of style files ("Literata-Bold.ttf" → "Literata"), used when none is given. */
        fun familyName(fileNames: List<String>): String {
            val bases = fileNames.map { it.substringBeforeLast('.').replace(Regex("(?i)[-_ ]?(bold|italic|oblique|regular|semibold|medium|light|book|roman)+$"), "") }
            val common = bases.reduceOrNull { a, b -> a.commonPrefixWith(b) }.orEmpty().trimEnd('-', '_', ' ')
            return (common.ifEmpty { bases.firstOrNull().orEmpty() }).replace(Regex("[^\\w .-]"), "_").trim().take(60).ifEmpty { "Font" }
        }

        /** Null when the file isn't a usable font. */
        @Synchronized fun loadFont(file: File): Typeface? = loaded.getOrPut("${file.path}:${file.lastModified()}") {
            runCatching { Typeface.Builder(file).build() }.getOrNull()
        }

        val OFF_ON = listOf("Off", "On")
        private val GESTURES = listOf("Tap & swipe", "Tap only", "Swipe only", "Inverted tap", "Disabled")
        const val TAP_AND_SWIPE = 0; const val TAP_ONLY = 1; const val SWIPE_ONLY = 2; const val INVERTED_TAP = 3

        // --- Text (Font button) ---
        val FONT_SIZE = Setting("font_size", "Font Size", (12..40 step 2).map { "$it" }, 4, (12..40 step 2).toList(), stepper = true, icon = "a_large_small")
        val LINE_SPACING = Setting("line_spacing", "Line Spacing", listOf("Tight", "Normal", "Wide", "Extra wide"), 1, icon = "rows_3")
        val WORD_SPACING = Setting("word_spacing", "Word Spacing", (50..200 step 25).map { "$it%" }, 2, (50..200 step 25).toList(), stepper = true, icon = "columns_3")
        val CHARACTER_SPACING = Setting("character_spacing", "Character Spacing", listOf("−2", "−1", "0", "+1", "+2"), 2, (-2..2).toList(), stepper = true, icon = "columns_3")
        val MARGIN = Setting("screen_margin", "Screen Margin", (5..40 step 5).map { "$it" }, 2, (5..40 step 5).toList(), stepper = true, icon = "square_dashed")
        val ALIGNMENT = Setting("paragraph_alignment", "Paragraph Alignment", listOf("Justify", "Left", "Center", "Right", "Book's style"), 0, icon = "align_justify")
        val EXTRA_SPACING = Setting("extra_paragraph_spacing", "Extra Paragraph Spacing", OFF_ON, 1, icon = "rows_3")
        val INDENT = Setting("paragraph_indent", "Paragraph Indent", (0..5).map { "$it" }, 2, stepper = true, icon = "indent_increase")
        val HYPHENATION = Setting("hyphenation", "Hyphenation", OFF_ON, 0, icon = "file_text")
        val FOCUS_READING = Setting("focus_reading", "Focus Reading", OFF_ON, 0, icon = "book_open_text")
        val EMBEDDED_STYLE = Setting("embedded_style", "Book's Embedded Style", OFF_ON, 1, icon = "paintbrush")
        val ANTI_ALIASING = Setting("text_anti_aliasing", "Text Anti-Aliasing", OFF_ON, 1, icon = "sparkles")
        val IMAGES = Setting("image_rendering", "Images", listOf("Display", "Placeholder", "Hide"), 0, icon = "image")
        val TEXT = listOf(FONT_SIZE, LINE_SPACING, WORD_SPACING, CHARACTER_SPACING, MARGIN, ALIGNMENT, EXTRA_SPACING,
            INDENT, HYPHENATION, FOCUS_READING, EMBEDDED_STYLE, ANTI_ALIASING, IMAGES)

        // --- Display (Settings button) ---
        val CONTINUOUS_SCROLL = Setting("continuous_scroll", "Continuous Scroll", OFF_ON, 0, icon = "scroll_text")
        val ORIENTATION = Setting("orientation", "Orientation", listOf("Follow device", "Portrait", "Landscape", "Portrait (inverted)", "Landscape (reversed)"), 0, icon = "rotate_cw")
        val NIGHT_MODE = Setting("night_mode", "Night Mode", OFF_ON, 0, icon = "moon")
        val REFRESH = Setting("refresh_frequency", "Full Refresh", listOf("Every page", "Every 5 pages", "Every 10 pages", "Every 15 pages", "Every 30 pages", "Never"), 5, listOf(1, 5, 10, 15, 30, 0), icon = "rotate_cw")
        val KEEP_AWAKE = Setting("keep_screen", "Keep Screen On", OFF_ON, 0, icon = "eye")

        // --- Status bar ---
        val STATUS_TITLE = Setting("status_title", "Title", listOf("Book", "Chapter", "Hide"), 1, icon = "book")
        val STATUS_PAGES = Setting("status_chapter_pages", "Chapter Page Count", OFF_ON, 1, icon = "file_text")
        val STATUS_PERCENT = Setting("status_book_percent", "Book Progress %", OFF_ON, 1, icon = "book_open")
        val STATUS_BAR = Setting("status_progress_bar", "Progress Bar", listOf("Book", "Chapter", "Hide"), 2, icon = "rows_3")
        val STATUS_BAR_THICKNESS = Setting("status_progress_thickness", "Progress Bar Thickness", listOf("Thin", "Medium", "Thick"), 1, icon = "square")
        val STATUS_BATTERY = Setting("status_battery", "Battery", OFF_ON, 1, icon = "battery")
        val STATUS_CLOCK = Setting("status_clock", "Clock", listOf("Hide", "Right", "Left"), 0, icon = "clock")
        val STATUS_VISIBLE = Setting("status_bar", "Show Status Bar", OFF_ON, 1, icon = "rows_3")
        val HIDE_BATTERY_PERCENT = Setting("hide_battery_percent", "Hide Battery %", listOf("Never", "In reader", "Always"), 0, icon = "battery")
        val DISPLAY = listOf(CONTINUOUS_SCROLL, ORIENTATION, NIGHT_MODE, REFRESH, KEEP_AWAKE, HIDE_BATTERY_PERCENT)
        /** Same items, order and defaults as the firmware's Customise Status Bar screen. */
        val STATUS = listOf(STATUS_PAGES, STATUS_PERCENT, STATUS_BAR, STATUS_BAR_THICKNESS, STATUS_TITLE, STATUS_BATTERY, STATUS_CLOCK)

        // --- Controls ---
        val TOUCH_CONTROLS = Setting("touch_reader_controls", "Touch Page Turns", OFF_ON, 1, icon = "smartphone")
        val NEXT_GESTURE = Setting("next_page_gesture", "Next Page Gesture", GESTURES, TAP_AND_SWIPE, icon = "chevron_right")
        val PREVIOUS_GESTURE = Setting("previous_page_gesture", "Previous Page Gesture", GESTURES, TAP_AND_SWIPE, icon = "chevron_left")
        val MENU_GESTURE = Setting("show_reader_menu", "Show Reader Menu", listOf("Tap Center", "Swipe Up"), 0, listOf(1, 2), icon = "layout_grid")
        val VOLUME_KEYS = Setting("volume_key_layout", "Side Buttons", listOf("Previous / Next", "Next / Previous", "Disabled", "Next / Next", "Previous / Previous"), 1, icon = "tablet")
        val CONTROLS = listOf(TOUCH_CONTROLS, NEXT_GESTURE, PREVIOUS_GESTURE, MENU_GESTURE, VOLUME_KEYS)

        /** Firmware's auto page turn rates, in pages per minute (session only). */
        val AUTO_TURN = listOf("Off", "1 page/min", "3 pages/min", "6 pages/min", "12 pages/min")
        val AUTO_TURN_RATES = intArrayOf(0, 1, 3, 6, 12)
    }
}
