package com.crosspoint.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.crosspoint.launcher.model.AppearanceMode
import com.crosspoint.launcher.model.CoverSlotMode
import com.crosspoint.launcher.model.SleepScreenMode
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.model.LauncherIconStyle
import com.crosspoint.launcher.model.SearchGesture
import com.crosspoint.launcher.model.ThemeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Immutable snapshot of all user settings. */
data class LauncherSettings(
    val theme: ThemeType = ThemeType.LYRA,
    val appearance: AppearanceMode = AppearanceMode.LIGHT,
    val defaultReaderPackage: String? = null,
    val pinnedApps: List<String> = emptyList(),          // "package/activity" entries
    val homeMenuItems: List<HomeMenuItem> = DEFAULT_MENU,
    val homeItemOrder: List<String>? = null,
    val bookFolders: List<String> = emptyList(),         // extra custom scan folders
    val searchGesture: SearchGesture = SearchGesture.SWIPE_UP,
    val autoOpenSingleResult: Boolean = true,
    val showKeyboardAutomatically: Boolean = false,
    val hideStatusBar: Boolean = true,
    val showLauncherDate: Boolean = true,
    val showLauncherClock: Boolean = true,
    val showLauncherBattery: Boolean = true,
    val showLauncherWifi: Boolean = true,
    val doubleTapRefresh: Boolean = false,
    val launcherIconStyle: LauncherIconStyle = LauncherIconStyle.ICONS,
    val appIconOverrides: Map<String, String> = emptyMap(),
    val coverSlotMode: CoverSlotMode = CoverSlotMode.BOOKS,
    val sleepScreenMode: SleepScreenMode = SleepScreenMode.OFF,
    val lastAppliedSleepCover: String = "",
    val coverWidgetId: Int = -1,
    val lastOpenedBookPath: String? = null,
    val lastOpenedBookAt: Long = 0L
) {
    fun effectiveHomeMenu(hasOpds: Boolean): List<HomeMenuItem> {
        if (homeItemOrder != null) return homeMenuItems
        val menu = homeMenuItems.filter { it != HomeMenuItem.OPDS_BROWSER }.toMutableList()
        if (hasOpds) menu.add(menu.indexOf(HomeMenuItem.FILE_TRANSFER).takeIf { it >= 0 } ?: menu.size, HomeMenuItem.OPDS_BROWSER)
        return menu
    }

    fun orderedHomeKeys(menu: List<HomeMenuItem>, apps: List<String>): List<String> {
        val available = menu.map { "menu:${it.id}" } + apps
        return (homeItemOrder.orEmpty() + available).distinct().filter { it in available }.take(MAX_HOME_ITEMS)
    }

    companion object {
        const val MAX_HOME_ITEMS = 5
        val DEFAULT_MENU = listOf(
            HomeMenuItem.BROWSE_BOOKS,
            HomeMenuItem.RECENT_BOOKS,
            HomeMenuItem.FILE_TRANSFER,
            HomeMenuItem.SETTINGS
        )
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val APPEARANCE = stringPreferencesKey("appearance")
        val DEFAULT_READER = stringPreferencesKey("default_reader")
        val PINNED = stringSetPreferencesKey("pinned_apps")          // unordered set
        val PINNED_ORDER = stringPreferencesKey("pinned_order")      // ordered csv
        val MENU = stringPreferencesKey("home_menu")                 // ordered csv of ids
        val HOME_ORDER = stringPreferencesKey("home_item_order")
        val FOLDERS = stringSetPreferencesKey("book_folders")
        val SEARCH_GESTURE = stringPreferencesKey("search_gesture")
        val AUTO_OPEN_SINGLE = booleanPreferencesKey("auto_open_single")
        val SHOW_KEYBOARD_AUTOMATICALLY = booleanPreferencesKey("show_keyboard_automatically")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val SHOW_LAUNCHER_DATE = booleanPreferencesKey("show_launcher_date")
        val SHOW_LAUNCHER_CLOCK = booleanPreferencesKey("show_launcher_clock")
        val SHOW_LAUNCHER_BATTERY = booleanPreferencesKey("show_launcher_battery")
        val SHOW_LAUNCHER_WIFI = booleanPreferencesKey("show_launcher_wifi")
        val DOUBLE_TAP_REFRESH = booleanPreferencesKey("double_tap_refresh")
        val LAUNCHER_ICON_STYLE = stringPreferencesKey("launcher_icon_style")
        val APP_ICON_OVERRIDES = stringPreferencesKey("app_icon_overrides")
        val COVER_SLOT_MODE = stringPreferencesKey("cover_slot_mode")
        val SLEEP_SCREEN_MODE = stringPreferencesKey("sleep_screen_mode")
        val SLEEP_COVER_APPLIED = stringPreferencesKey("sleep_cover_applied")
        val COVER_WIDGET_ID = intPreferencesKey("cover_widget_id")
        val LAST_OPENED_BOOK_PATH = stringPreferencesKey("last_opened_book_path")
        val LAST_OPENED_BOOK_AT = androidx.datastore.preferences.core.longPreferencesKey("last_opened_book_at")
    }

    val settings: Flow<LauncherSettings> = context.launcherDataStore.data.map { p ->
        val orderCsv = p[Keys.PINNED_ORDER]
        val pinned = if (!orderCsv.isNullOrBlank()) {
            orderCsv.split("\n").filter { it.isNotBlank() }
        } else {
            (p[Keys.PINNED] ?: emptySet()).toList()
        }
        val menu = p[Keys.MENU]?.split(",")?.mapNotNull { HomeMenuItem.from(it) }
            ?: LauncherSettings.DEFAULT_MENU
        LauncherSettings(
            theme = ThemeType.from(p[Keys.THEME]),
            appearance = AppearanceMode.from(p[Keys.APPEARANCE]),
            defaultReaderPackage = p[Keys.DEFAULT_READER],
            pinnedApps = pinned,
            homeMenuItems = menu,
            homeItemOrder = p[Keys.HOME_ORDER]?.split("\n")?.filter { it.isNotBlank() },
            bookFolders = (p[Keys.FOLDERS] ?: emptySet()).toList(),
            searchGesture = SearchGesture.from(p[Keys.SEARCH_GESTURE]),
            autoOpenSingleResult = p[Keys.AUTO_OPEN_SINGLE] ?: true,
            showKeyboardAutomatically = p[Keys.SHOW_KEYBOARD_AUTOMATICALLY] ?: false,
            hideStatusBar = p[Keys.HIDE_STATUS_BAR] ?: true,
            showLauncherDate = p[Keys.SHOW_LAUNCHER_DATE] ?: true,
            showLauncherClock = p[Keys.SHOW_LAUNCHER_CLOCK] ?: true,
            showLauncherBattery = p[Keys.SHOW_LAUNCHER_BATTERY] ?: true,
            showLauncherWifi = p[Keys.SHOW_LAUNCHER_WIFI] ?: true,
            doubleTapRefresh = p[Keys.DOUBLE_TAP_REFRESH] ?: false,
            launcherIconStyle = LauncherIconStyle.from(p[Keys.LAUNCHER_ICON_STYLE]),
            appIconOverrides = parseIconOverrides(p[Keys.APP_ICON_OVERRIDES]),
            coverSlotMode = CoverSlotMode.from(p[Keys.COVER_SLOT_MODE]),
            sleepScreenMode = SleepScreenMode.from(p[Keys.SLEEP_SCREEN_MODE]),
            lastAppliedSleepCover = p[Keys.SLEEP_COVER_APPLIED] ?: "",
            coverWidgetId = p[Keys.COVER_WIDGET_ID] ?: -1,
            lastOpenedBookPath = p[Keys.LAST_OPENED_BOOK_PATH],
            lastOpenedBookAt = p[Keys.LAST_OPENED_BOOK_AT] ?: 0L
        )
    }

    suspend fun setTheme(t: ThemeType) = context.launcherDataStore.edit { it[Keys.THEME] = t.id }

    suspend fun setAppearance(a: AppearanceMode) =
        context.launcherDataStore.edit { it[Keys.APPEARANCE] = a.id }

    suspend fun setDefaultReader(pkg: String?) = context.launcherDataStore.edit {
        if (pkg == null) it.remove(Keys.DEFAULT_READER) else it[Keys.DEFAULT_READER] = pkg
    }

    suspend fun setHomeItems(entries: List<String>) = context.launcherDataStore.edit { p ->
        val ordered = entries.filter { it.isNotBlank() }.distinct().take(LauncherSettings.MAX_HOME_ITEMS)
        val apps = ordered.filterNot { it.startsWith("menu:") }
        p[Keys.HOME_ORDER] = ordered.joinToString("\n")
        p[Keys.MENU] = ordered.filter { it.startsWith("menu:") }
            .mapNotNull { HomeMenuItem.from(it.removePrefix("menu:")) }.joinToString(",") { it.id }
        p[Keys.PINNED_ORDER] = apps.joinToString("\n")
        p[Keys.PINNED] = apps.toSet()
    }

    suspend fun setBookFolders(folders: List<String>) = context.launcherDataStore.edit {
        it[Keys.FOLDERS] = folders.toSet()
    }

    suspend fun setSearchGesture(g: SearchGesture) =
        context.launcherDataStore.edit { it[Keys.SEARCH_GESTURE] = g.id }

    suspend fun setAutoOpenSingleResult(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.AUTO_OPEN_SINGLE] = v }

    suspend fun setShowKeyboardAutomatically(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.SHOW_KEYBOARD_AUTOMATICALLY] = v }

    suspend fun setHideStatusBar(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.HIDE_STATUS_BAR] = v }

    suspend fun setShowLauncherDate(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.SHOW_LAUNCHER_DATE] = v }

    suspend fun setShowLauncherClock(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.SHOW_LAUNCHER_CLOCK] = v }

    suspend fun setShowLauncherBattery(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.SHOW_LAUNCHER_BATTERY] = v }

    suspend fun setShowLauncherWifi(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.SHOW_LAUNCHER_WIFI] = v }

    suspend fun setDoubleTapRefresh(v: Boolean) =
        context.launcherDataStore.edit { it[Keys.DOUBLE_TAP_REFRESH] = v }

    suspend fun setLauncherIconStyle(style: LauncherIconStyle) =
        context.launcherDataStore.edit { it[Keys.LAUNCHER_ICON_STYLE] = style.id }

    suspend fun setAppIconOverride(appKey: String, icon: String?) =
        context.launcherDataStore.edit { p ->
            val next = parseIconOverrides(p[Keys.APP_ICON_OVERRIDES]).toMutableMap()
            if (icon.isNullOrBlank()) next.remove(appKey) else next[appKey] = icon
            p[Keys.APP_ICON_OVERRIDES] = JSONObject(next.toMap()).toString()
        }

    suspend fun setCoverSlotMode(mode: CoverSlotMode) =
        context.launcherDataStore.edit { it[Keys.COVER_SLOT_MODE] = mode.id }

    suspend fun setSleepScreenMode(mode: SleepScreenMode) =
        context.launcherDataStore.edit { it[Keys.SLEEP_SCREEN_MODE] = mode.id }

    suspend fun setLastAppliedSleepCover(path: String) =
        context.launcherDataStore.edit { it[Keys.SLEEP_COVER_APPLIED] = path }

    suspend fun setCoverWidget(id: Int) =
        context.launcherDataStore.edit {
            it[Keys.COVER_WIDGET_ID] = id
            it[Keys.COVER_SLOT_MODE] = CoverSlotMode.WIDGET.id
        }

    suspend fun clearCoverWidget() =
        context.launcherDataStore.edit {
            it[Keys.COVER_WIDGET_ID] = -1
            it[Keys.COVER_SLOT_MODE] = CoverSlotMode.BOOKS.id
        }

    suspend fun markBookOpened(path: String, openedAt: Long = System.currentTimeMillis()) =
        context.launcherDataStore.edit {
            it[Keys.LAST_OPENED_BOOK_PATH] = path
            it[Keys.LAST_OPENED_BOOK_AT] = openedAt
        }

    private fun parseIconOverrides(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }
}
