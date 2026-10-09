package com.crosspoint.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.ui.components.key
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.LauncherAppIcon
import com.crosspoint.launcher.ui.components.LyraHeader
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import com.crosspoint.launcher.util.Launching

sealed class LauncherColumnItem {
    data class PinnedApp(val app: AppInfo) : LauncherColumnItem()
    data class MenuAction(val item: HomeMenuItem) : LauncherColumnItem()
    val key: String get() = when (this) {
        is MenuAction -> "menu:${item.id}"
        is PinnedApp -> app.key
    }
    val label: String get() = when (this) {
        is MenuAction -> item.label
        is PinnedApp -> app.label
    }
}

fun orderedHomeItems(menu: List<HomeMenuItem>, apps: List<AppInfo>, settings: com.crosspoint.launcher.data.LauncherSettings): List<LauncherColumnItem> {
    val items = menu.map { LauncherColumnItem.MenuAction(it) } + apps.map { LauncherColumnItem.PinnedApp(it) }
    val byKey = items.associateBy { it.key }
    return settings.orderedHomeKeys(menu, apps.map { it.key }).mapNotNull { byKey[it] }
}

/** Shared top header with date as title (Niagara-style) plus clock/battery/wifi. */
@Composable
fun HomeHeader(state: HomeUiState) {
    val context = LocalContext.current
    val statusPad = WindowInsets.statusBars.asPaddingValues()
    Column(Modifier.padding(top = statusPad.calculateTopPadding())) {
        LyraHeader(
            title = state.system.date.ifEmpty { "CrossPoint" },
            clock = state.system.clock,
            batteryPct = state.system.batteryPct,
            charging = state.system.charging,
            wifiConnected = state.system.wifiConnected,
            showTitle = state.settings.showLauncherDate,
            showClock = state.settings.showLauncherClock,
            showBattery = state.settings.showLauncherBattery,
            showWifi = state.settings.showLauncherWifi,
            onTitleClick = { Launching.openCalendar(context) }
        )
    }
}

/**
 * Lyra home menu. A single vertical list in portrait; two columns in landscape to use the wider
 * space. Buttons have no borders — just icon + label rows.
 */
@Composable
fun MenuGrid(items: List<HomeMenuItem>, onMenu: (HomeMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val m = CrossPointTheme.metrics
    TwoColumnLauncherList(
        items = items,
        modifier = modifier,
        verticalSpacing = m.menuSpacing
    ) { item ->
        MenuButton(item, onMenu)
    }
}

@Composable
fun MenuButton(item: HomeMenuItem, onMenu: (HomeMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val m = CrossPointTheme.metrics
    Pressable(onClick = { onMenu(item) }, modifier = modifier.wrapContentWidth()) {
        Row(
            Modifier
                .height(m.menuRowHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(item.icon, Modifier.size(m.menuIconSize))
            Spacer(Modifier.width(14.dp))
            BodyText(item.label, weight = FontWeight.SemiBold, size = 16)
        }
    }
}

/** Niagara-style list rows for the menu (used by the Minimal theme). */
@Composable
fun MenuList(items: List<HomeMenuItem>, onMenu: (HomeMenuItem) -> Unit, modifier: Modifier = Modifier) {
    TwoColumnLauncherList(
        items = items,
        modifier = modifier,
        verticalSpacing = 4.dp
    ) { item ->
        MenuListButton(item, onMenu)
    }
}

@Composable
fun PinnedAppsGrid(
    apps: List<AppInfo>,
    settings: com.crosspoint.launcher.data.LauncherSettings,
    onLaunch: (String, String) -> Unit,
    onLongPress: (AppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    TwoColumnLauncherList(
        items = apps.take(12),
        modifier = modifier,
        verticalSpacing = 2.dp
    ) { app ->
        PinnedAppRow(
            app = app,
            settings = settings,
            onLaunch = onLaunch,
            onLongPress = { onLongPress(app) },
            menuStyle = LauncherMenuStyle.Minimal
        )
    }
}

@Composable
fun LauncherColumns(
    pinnedApps: List<AppInfo>,
    menuItems: List<HomeMenuItem>,
    settings: com.crosspoint.launcher.data.LauncherSettings,
    onLaunch: (String, String) -> Unit,
    onLongPressApp: (AppInfo) -> Unit,
    onMenu: (HomeMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    menuStyle: LauncherMenuStyle = LauncherMenuStyle.Standard
) {
    val items = orderedHomeItems(menuItems, pinnedApps, settings)
    TwoColumnLauncherList(
        items = items,
        modifier = modifier,
        verticalSpacing = if (menuStyle == LauncherMenuStyle.Minimal) 4.dp else CrossPointTheme.metrics.menuSpacing
    ) { item ->
        when (item) {
            is LauncherColumnItem.PinnedApp ->
                PinnedAppRow(
                    item.app,
                    settings = settings,
                    onLaunch = onLaunch,
                    onLongPress = { onLongPressApp(item.app) },
                    menuStyle = menuStyle
                )
            is LauncherColumnItem.MenuAction ->
                if (menuStyle == LauncherMenuStyle.Minimal) {
                    MenuListButton(item.item, onMenu)
                } else {
                    MenuButton(item.item, onMenu)
                }
        }
    }
}

enum class LauncherMenuStyle { Standard, Minimal }

@Composable
private fun <T> TwoColumnLauncherList(
    items: List<T>,
    modifier: Modifier = Modifier,
    verticalSpacing: androidx.compose.ui.unit.Dp,
    itemContent: @Composable (T) -> Unit
) {
    val left = items.take(6)
    val right = items.drop(6).take(6)
    BoxWithConstraints(modifier) {
        val columnGap = (maxWidth * 0.18f).coerceIn(32.dp, 140.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(columnGap),
            verticalAlignment = Alignment.Top
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
                left.forEach { itemContent(it) }
            }
            if (right.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
                    right.forEach { itemContent(it) }
                }
            }
        }
    }
}

@Composable
private fun MenuListButton(item: HomeMenuItem, onMenu: (HomeMenuItem) -> Unit) {
    Pressable(onClick = { onMenu(item) }, modifier = Modifier.wrapContentWidth()) {
        Row(
            Modifier
                .height(CrossPointTheme.metrics.menuRowHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(item.icon, Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            BodyText(item.label, weight = FontWeight.Medium, size = 19)
        }
    }
}

/** A single pinned app as a large left-aligned row (Niagara aesthetic). */
@Composable
fun PinnedAppRow(
    app: AppInfo,
    settings: com.crosspoint.launcher.data.LauncherSettings,
    onLaunch: (String, String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    menuStyle: LauncherMenuStyle = LauncherMenuStyle.Standard
) {
    val m = CrossPointTheme.metrics
    val iconSize = if (menuStyle == LauncherMenuStyle.Minimal) 22.dp else m.menuIconSize
    val horizontalPadding = if (menuStyle == LauncherMenuStyle.Minimal) 4.dp else 8.dp
    val spacerWidth = if (menuStyle == LauncherMenuStyle.Minimal) 16.dp else 14.dp
    val textSize = if (menuStyle == LauncherMenuStyle.Minimal) 19 else 16
    val textWeight = if (menuStyle == LauncherMenuStyle.Minimal) FontWeight.Medium else FontWeight.SemiBold
    Pressable(
        onClick = { onLaunch(app.packageName, app.activityName) },
        onLongClick = onLongPress,
        modifier = modifier.wrapContentWidth()
    ) {
        Row(
            Modifier
                .height(m.menuRowHeight)
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LauncherAppIcon(app, settings, size = iconSize)
            Spacer(Modifier.width(spacerWidth))
            BodyText(app.label, weight = textWeight, size = textSize)
        }
    }
}

/** Compact recent-book text rows, for themes without cover art. */
@Composable
fun BookListRow(
    title: String,
    author: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Pressable(onClick = onOpen, onLongClick = onLongPress, modifier = modifier.wrapContentWidth()) {
        Row(
            Modifier
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon("book", Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                BodyText(title, weight = FontWeight.Medium, size = 16)
                if (author.isNotEmpty()) BodyText(author, muted = true, size = 12)
            }
        }
    }
}

/** Banner shown on the home screen when this app is not the default launcher. */
@Composable
fun DefaultHomeBanner(onSet: () -> Unit, onDismiss: () -> Unit) {
    val colors = CrossPointTheme.colors
    val statusPad = WindowInsets.statusBars.asPaddingValues()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = statusPad.calculateTopPadding())
            .background(colors.onBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BodyText("Set CrossPoint as your home app", weight = FontWeight.SemiBold, size = 14,
                color = colors.background)
            BodyText("Tap Set to make it your default launcher", size = 12, color = colors.background)
        }
        Pressable(onClick = onSet) {
            Box(
                Modifier.padding(start = 8.dp).background(colors.background)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { BodyText("Set", weight = FontWeight.Bold, size = 14, color = colors.onBackground) }
        }
        Spacer(Modifier.width(4.dp))
        Pressable(onClick = onDismiss) {
            LucideIcon("x", Modifier.padding(6.dp).size(20.dp), tintOverride = colors.background)
        }
    }
}

/** Prompt shown when storage access hasn't been granted yet. */
@Composable
fun StorageHint(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(onClick = onGrant, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon("folder_open", Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                BodyText("Grant storage access", weight = FontWeight.SemiBold, size = 15)
                BodyText("Allow file access to scan your EPUB library", muted = true, size = 12)
            }
        }
    }
}
