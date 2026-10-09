package com.crosspoint.launcher.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.crosspoint.launcher.R
import com.crosspoint.launcher.ui.theme.CrossPointTheme

/**
 * Maps Lucide icon names (as used by the reader's Lyra theme) to the bundled vector drawables
 * generated from the official Lucide SVGs. Use underscores, e.g. "send_horizontal".
 */
@DrawableRes
fun lucideRes(name: String): Int = when (name) {
    "cloud_backup" -> R.drawable.ic_cloud_backup
    "crosspoint" -> R.drawable.ic_crosspoint_logo
    "folder" -> R.drawable.ic_folder
    "folder_open" -> R.drawable.ic_folder_open
    "book" -> R.drawable.ic_book
    "book_open" -> R.drawable.ic_book_open
    "book_marked" -> R.drawable.ic_book_marked
    "book_open_text" -> R.drawable.ic_book_open_text
    "book_text" -> R.drawable.ic_book_text
    "library_big" -> R.drawable.ic_library_big
    "tablet" -> R.drawable.ic_tablet
    "store" -> R.drawable.ic_store
    "shopping_bag" -> R.drawable.ic_shopping_bag
    "shopping_basket" -> R.drawable.ic_shopping_basket
    "globe" -> R.drawable.ic_globe
    "app_window" -> R.drawable.ic_app_window
    "mail" -> R.drawable.ic_mail
    "calendar" -> R.drawable.ic_calendar
    "camera" -> R.drawable.ic_camera
    "map" -> R.drawable.ic_map
    "headphones" -> R.drawable.ic_headphones
    "music" -> R.drawable.ic_music
    "film" -> R.drawable.ic_film
    "gamepad_2" -> R.drawable.ic_gamepad_2
    "newspaper" -> R.drawable.ic_newspaper
    "package" -> R.drawable.ic_package
    "package_open" -> R.drawable.ic_package_open
    "boxes" -> R.drawable.ic_boxes
    "play" -> R.drawable.ic_play
    "badge_dollar_sign" -> R.drawable.ic_badge_dollar_sign
    "circle" -> R.drawable.ic_circle
    "square" -> R.drawable.ic_square
    "dot" -> R.drawable.ic_dot
    "library" -> R.drawable.ic_library
    "settings" -> R.drawable.ic_settings
    "send_horizontal" -> R.drawable.ic_send_horizontal
    "wifi" -> R.drawable.ic_wifi
    "wifi_off" -> R.drawable.ic_wifi_off
    "bookmark" -> R.drawable.ic_bookmark
    "cpu" -> R.drawable.ic_cpu
    "file_text" -> R.drawable.ic_file_text
    "layout_grid" -> R.drawable.ic_layout_grid
    "list_filter" -> R.drawable.ic_list_filter
    "sun" -> R.drawable.ic_sun
    "moon" -> R.drawable.ic_moon
    "search" -> R.drawable.ic_search
    "x" -> R.drawable.ic_x
    "chevron_left" -> R.drawable.ic_chevron_left
    "chevron_right" -> R.drawable.ic_chevron_right
    "chevron_up" -> R.drawable.ic_chevron_up
    "chevron_down" -> R.drawable.ic_chevron_down
    "plus" -> R.drawable.ic_plus
    "clock" -> R.drawable.ic_clock
    "battery" -> R.drawable.ic_battery
    "battery_low" -> R.drawable.ic_battery_low
    "battery_medium" -> R.drawable.ic_battery_medium
    "battery_full" -> R.drawable.ic_battery_full
    "image" -> R.drawable.ic_image
    "rows_3" -> R.drawable.ic_rows_3
    "grid_3x3" -> R.drawable.ic_grid_3x3
    "columns_3" -> R.drawable.ic_columns_3
    "gallery_horizontal_end" -> R.drawable.ic_gallery_horizontal_end
    "smartphone" -> R.drawable.ic_smartphone
    "trash_2" -> R.drawable.ic_trash_2
    "check" -> R.drawable.ic_check
    "pin" -> R.drawable.ic_pin
    "arrow_left" -> R.drawable.ic_arrow_left
    "rotate_cw" -> R.drawable.ic_rotate_cw
    "download" -> R.drawable.ic_download
    "type" -> R.drawable.ic_type
    "a_large_small" -> R.drawable.ic_a_large_small
    "align_justify" -> R.drawable.ic_align_justify
    "indent_increase" -> R.drawable.ic_indent_increase
    "square_dashed" -> R.drawable.ic_square_dashed
    "scroll_text" -> R.drawable.ic_scroll_text
    "eye" -> R.drawable.ic_eye
    "sparkles" -> R.drawable.ic_sparkles
    "paintbrush" -> R.drawable.ic_paintbrush
    else -> R.drawable.ic_book
}

val LucideIconLibrary = listOf(
    "book", "book_open", "book_marked", "book_open_text", "book_text", "library", "library_big",
    "tablet", "store", "shopping_bag", "shopping_basket", "globe", "app_window", "mail",
    "calendar", "camera", "map", "music", "headphones", "film", "gamepad_2", "newspaper", "package",
    "package_open", "boxes", "play", "badge_dollar_sign", "folder", "folder_open", "settings",
    "send_horizontal", "layout_grid", "search", "pin", "circle", "square", "dot"
)

/** A Lucide icon tinted with the current foreground color (or [tintOverride]). */
@Composable
fun LucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    tintOverride: androidx.compose.ui.graphics.Color? = null
) {
    val tint = tintOverride ?: CrossPointTheme.colors.onBackground
    Image(
        painter = painterResource(id = lucideRes(name)),
        contentDescription = name,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}
