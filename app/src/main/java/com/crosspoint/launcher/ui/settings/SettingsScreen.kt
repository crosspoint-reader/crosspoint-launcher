package com.crosspoint.launcher.ui.settings

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crosspoint.launcher.LauncherViewModel
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.model.AppearanceMode
import com.crosspoint.launcher.model.CoverSlotMode
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.model.LauncherIconStyle
import com.crosspoint.launcher.model.SearchGesture
import com.crosspoint.launcher.model.ThemeType
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.HeaderIconButton
import com.crosspoint.launcher.ui.components.LauncherAppIcon
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.components.defaultIconForApp
import com.crosspoint.launcher.ui.components.key
import com.crosspoint.launcher.ui.icons.LucideIconLibrary
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.home.LauncherColumnItem
import com.crosspoint.launcher.ui.home.orderedHomeItems
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import com.crosspoint.launcher.BuildConfig
import com.crosspoint.launcher.util.ApkInstall
import com.crosspoint.launcher.util.AppUpdater
import com.crosspoint.launcher.util.AuroraInstaller
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import java.io.File
import com.crosspoint.launcher.util.Launching
import com.crosspoint.launcher.util.DeviceSettings
import com.crosspoint.launcher.util.SleepScreen
import com.crosspoint.launcher.model.SleepScreenMode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    vm: LauncherViewModel,
    onOpenOpdsServers: () -> Unit,
    onPickWidget: () -> Unit,
    onClearWidget: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val settings by vm.settings.collectAsState()
    val apps by vm.apps.collectAsState()
    val readerApps by vm.readerApps.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val syncBusy by vm.syncBusy.collectAsState()
    val hasStorage by vm.hasStorage.collectAsState()
    val opdsServers by vm.opdsServers.collectAsState()
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var editingIconFor by remember { mutableStateOf<AppInfo?>(null) }

    editingIconFor?.let { app ->
        IconPickerScreen(
            app = app,
            selected = settings.appIconOverrides[app.key] ?: defaultIconForApp(app),
            onSelect = { icon ->
                vm.setAppIconOverride(app.key, icon)
                editingIconFor = null
            },
            onReset = {
                vm.setAppIconOverride(app.key, null)
                editingIconFor = null
            },
            onBack = { editingIconFor = null }
        )
        return
    }

    var category by remember { mutableStateOf<String?>(null) }
    val storeInstalled = apps.any { it.packageName == AuroraInstaller.PACKAGE }
    val scope = rememberCoroutineScope()
    val books by vm.books.collectAsState()
    val recentBook = remember(books, settings.lastOpenedBookPath) {
        books.firstOrNull { it.filePath == settings.lastOpenedBookPath } ?: books.maxByOrNull { it.lastModified }
    }
    val imagePickerAvailable = remember { context.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*"), 0) != null }
    val pickSleepImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { withContext(Dispatchers.Default) { SleepScreen.applyUri(context, uri) } }
    }
    var storeStatus by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    // Download, verify and hand an APK to the system installer, reporting progress through setStatus.
    fun downloadAndInstall(setStatus: (String?) -> Unit, download: suspend (onProgress: (Int) -> Unit) -> File) {
        if (installing) return
        if (!ApkInstall.ensureCanInstall(context)) { setStatus("Allow installs, then tap again"); return }
        installing = true
        scope.launch {
            try {
                setStatus("Downloading…")
                val apk = download { setStatus("Downloading… ${it / 10 * 10}%") }
                setStatus(null)
                ApkInstall.install(context, apk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(e.message ?: "Download failed")
            } finally {
                installing = false
            }
        }
    }
    fun getStore() = downloadAndInstall({ storeStatus = it }) { AuroraInstaller.download(context, it) }

    // Version in the header doubles as the update button.
    var update by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    fun checkForUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            if (manual) updateStatus = "Checking for updates…"
            val result = runCatching { AppUpdater.check() }
            update = result.getOrNull()
            updateStatus = when {
                !manual || update != null -> null
                result.isFailure -> "Check failed"
                else -> "Up to date"
            }
            checking = false
            if (updateStatus != null) { delay(2000); updateStatus = null }
        }
    }
    LaunchedEffect(Unit) { checkForUpdate(manual = false) }
    fun onVersionTap() {
        val release = update
        if (release == null) checkForUpdate(manual = true)
        else downloadAndInstall({ updateStatus = it }) { AppUpdater.download(context, release, it) }
    }
    val categories = listOf(
        "Home Theme" to "layout_grid", "Appearance" to "sun",
        "App Search" to "search", "CrossPoint Sync" to "crosspoint", "Default Reader" to "book_open",
        "Home Screen Items" to "rows_3", "OPDS Catalogs" to "library",
        "Library" to "folder_open", (if (storeInstalled) "App Store" else "Get App Store") to "store",
        "Device Settings" to "settings"
    )
    androidx.activity.compose.BackHandler(category != null) { category = null }
    Column(Modifier.fillMaxSize().padding(top = topPad)) {
        ScreenHeader(category ?: "Settings", { if (category == null) onBack() else category = null }) {
            if (category == null) Pressable(onClick = ::onVersionTap) {
                BodyText(updateStatus ?: update?.let { "Update" } ?: "v${BuildConfig.VERSION_NAME}",
                    weight = if (update != null) FontWeight.Bold else FontWeight.Normal, size = 14, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
        val bookCount = vm.books.collectAsState().value.size
        if (category == null) {
            ScrollList(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = m.contentSidePadding)) {
                items(categories) { (title, icon) ->
                    Pressable(onClick = {
                        when (title) {
                            "App Store" -> Launching.launchPackage(context, AuroraInstaller.PACKAGE)
                            "Get App Store" -> getStore()
                            else -> category = title
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            LucideIcon(icon, Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            BodyText(if (title == "Get App Store") storeStatus ?: title else title, size = 18, modifier = Modifier.weight(1f))
                            LucideIcon("chevron_right", Modifier.size(18.dp))
                        }
                    }
                }
            }
        } else ScrollList(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = m.contentSidePadding), state = remember(category) { androidx.compose.foundation.lazy.LazyListState() }) {
            when (category) {
                "Home Theme" -> items(ThemeType.entries.filter { it != ThemeType.CAROUSEL }) { theme -> SelectRow(theme.label, theme.description, settings.theme == theme) { vm.setTheme(theme) } }
                "Appearance" -> {
                    items(AppearanceMode.entries) { mode -> SelectRow(mode.label, "", settings.appearance == mode) { vm.setAppearance(mode) } }
                    items(CoverSlotMode.entries) { mode -> SelectRow(mode.label, "Cover area", settings.coverSlotMode == mode) { vm.setCoverSlotMode(mode) } }
                    if (settings.coverSlotMode == CoverSlotMode.WIDGET) {
                        item { ActionRow("Choose Widget", "", "app_window", onPickWidget) }
                        item { ActionRow("Clear Widget", "", "x", onClearWidget) }
                    }
                    item { BodyText("Sleep Screen", weight = FontWeight.Bold, size = 14, muted = true, modifier = Modifier.padding(top = 12.dp, start = 6.dp)) }
                    items(SleepScreenMode.entries.filter { it != SleepScreenMode.CUSTOM || imagePickerAvailable }) { mode ->
                        val sub = when (mode) {
                            SleepScreenMode.BOOK_COVER -> recentBook?.title ?: "No book cover found"
                            SleepScreenMode.CUSTOM -> "Pick an image"
                            SleepScreenMode.OFF -> ""
                        }
                        SelectRow(mode.label, sub, settings.sleepScreenMode == mode) {
                            vm.setSleepScreenMode(mode)
                            when (mode) {
                                SleepScreenMode.BOOK_COVER -> recentBook?.coverPath?.let { cover ->
                                    scope.launch { withContext(Dispatchers.Default) { SleepScreen.applyCover(context, cover) }; vm.setLastAppliedSleepCover(cover) }
                                }
                                SleepScreenMode.CUSTOM -> pickSleepImage.launch("image/*")
                                SleepScreenMode.OFF -> {}
                            }
                        }
                    }
                    item { ToggleRow("Show Date", "calendar", settings.showLauncherDate) { vm.setShowLauncherDate(!settings.showLauncherDate) } }
                    item { ToggleRow("Show Clock", "clock", settings.showLauncherClock) { vm.setShowLauncherClock(!settings.showLauncherClock) } }
                    item { ToggleRow("Show Battery", "battery", settings.showLauncherBattery) { vm.setShowLauncherBattery(!settings.showLauncherBattery) } }
                    item { ToggleRow("Show Wi-Fi", "wifi", settings.showLauncherWifi) { vm.setShowLauncherWifi(!settings.showLauncherWifi) } }
                }
                "App Search" -> {
                    items(SearchGesture.entries) { gesture -> SelectRow(gesture.label, "", settings.searchGesture == gesture) { vm.setSearchGesture(gesture) } }
                    item { ToggleRow("Show Keyboard Automatically", "search", settings.showKeyboardAutomatically) { vm.setShowKeyboardAutomatically(!settings.showKeyboardAutomatically) } }
                    item { ToggleRow("Auto-Open Single Result", "check", settings.autoOpenSingleResult) { vm.setAutoOpenSingleResult(!settings.autoOpenSingleResult) } }
                }
                "CrossPoint Sync" -> {
                    item { ActionRow("Account and Reading Stats", "Log in or sign up", "globe") { context.startActivity(android.content.Intent(context, com.crosspoint.launcher.SyncAccountActivity::class.java)) } }
                    item { ActionRow("Fetch Missing Covers", "Adds books to your synced library", "download") { if (!syncBusy) vm.fetchMissingCovers() } }
                    item {
                        // Same toggle as the firmware's CrossPoint Sync > Sync Clippings; syncs with each book's reading position.
                        val sync = remember { com.crosspoint.launcher.data.SyncRepository(context) }
                        var syncClippings by remember { mutableStateOf(sync.syncClippings) }
                        ToggleRow("Sync Clippings", "file_text", syncClippings) { syncClippings = !syncClippings; sync.syncClippings = syncClippings }
                    }
                    item { ActionRow("Disconnect", "Keep downloaded covers", "x") { if (!syncBusy) vm.disconnectSync() } }
                    if (syncStatus.isNotBlank()) item { BodyText(syncStatus, muted = true, size = 14, maxLines = 4) }
                }
                "Default Reader" -> {
                    item { SelectRow("CrossPoint Reader", "Built-in EPUB reader", settings.defaultReaderPackage == null) { vm.setDefaultReader(null) } }
                    items(readerApps) { app -> SelectRow(app.label, app.packageName, settings.defaultReaderPackage == app.packageName) { vm.setDefaultReader(app.packageName) } }
                }
                "Home Screen Items" -> {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BodyText("App Icon Style", muted = true, size = 14)
                            Row(Modifier.fillMaxWidth()) {
                                LauncherIconStyle.entries.forEach { style ->
                                    Pressable(onClick = { vm.setLauncherIconStyle(style) }, modifier = Modifier.weight(1f)) {
                                        Column(Modifier.fillMaxWidth().height(56.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                            RadioDot(settings.launcherIconStyle == style)
                                            BodyText(style.label, size = 14)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val selected = orderedHomeItems(settings.effectiveHomeMenu(opdsServers.isNotEmpty()), vm.pinnedAppInfos(settings, apps), settings)
                    val entries = selected.map { it.key }
                    val available = HomeMenuItem.entries.map { LauncherColumnItem.MenuAction(it) } + apps.map { LauncherColumnItem.PinnedApp(it) }
                    item { BodyText("${entries.size} of 5 Items Selected", weight = FontWeight.Medium, size = 16) }
                    item { BodyText("Choose up to five Home shortcuts. Tap an app icon to change it.", muted = true, size = 14, maxLines = Int.MAX_VALUE) }
                    items(selected + available.filterNot { it.key in entries }, key = { it.key }) { entry ->
                        HomeItemSettingsRow(entry, settings, entries, { vm.setHomeItems(it) }, {
                            if (entry is LauncherColumnItem.PinnedApp) editingIconFor = entry.app
                        })
                    }
                }
                "OPDS Catalogs" -> item { ActionRow("OPDS Servers", "${opdsServers.size} configured", "library", onOpenOpdsServers) }
                "Library" -> {
                    item { ActionRow(if (hasStorage) "Storage Access Granted" else "Grant Storage Access", "Read local EPUB books", "folder_open") { Launching.openManageStorageSettings(context) } }
                    item { ActionRow("Rescan Library", "$bookCount books found", "rotate_cw") { vm.refreshBooks() } }
                }
                "Device Settings" -> {
                    item { ActionRow("Restart Onboarding", "Re-run storage, Wi-Fi and launcher setup, and the tips", "rotate_cw") {
                        context.startActivity(android.content.Intent(context, com.crosspoint.launcher.PermissionSetupActivity::class.java))
                    } }
                    item { ActionRow("Set as Default Launcher", "Open system home settings", "smartphone") { Launching.openHomeSettings(context) } }
                    item { ToggleRow("Double-Tap Header to Refresh", "rotate_cw", settings.doubleTapRefresh) { vm.setDoubleTapRefresh(!settings.doubleTapRefresh) } }
                    items(DeviceSettings.available(context)) { panel ->
                        ActionRow(panel.label, "", panel.icon) { DeviceSettings.open(context, panel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPickerScreen(
    app: AppInfo,
    selected: String,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) LucideIconLibrary
        else LucideIconLibrary.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(top = topPad)) {
        ScreenHeader("Choose Icon", onBack) {
            Pressable(onClick = onReset) {
                Box(Modifier.height(48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    BodyText("Default", weight = FontWeight.SemiBold, size = 14)
                }
            }
        }
        BodyText(app.label, muted = true, size = 14, modifier = Modifier.padding(horizontal = m.contentSidePadding, vertical = 8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.contentSidePadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon("search", Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.onBackground),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) BodyText("Search icons", muted = true, size = 16)
                    inner()
                }
            )
        }
        ScrollList(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = m.contentSidePadding,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(filtered, key = { it }) { icon ->
                Pressable(onClick = { onSelect(icon) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon(icon, Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        BodyText(icon.replace("_", " "), weight = FontWeight.Medium, size = 16, modifier = Modifier.weight(1f))
                        if (icon == selected) LucideIcon("check", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BodyText(title, weight = FontWeight.SemiBold, size = 16, maxLines = Int.MAX_VALUE)
                if (subtitle.isNotBlank()) BodyText(subtitle, muted = true, size = 12, maxLines = Int.MAX_VALUE)
            }
            Spacer(Modifier.width(12.dp))
            RadioDot(selected)
        }
    }
}

@Composable
internal fun ToggleRow(title: String, icon: String, enabled: Boolean, onToggle: () -> Unit) {
    Pressable(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(icon, Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            BodyText(title, weight = FontWeight.Medium, size = 16, maxLines = Int.MAX_VALUE, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            CheckBox(enabled)
        }
    }
}


@Composable
private fun HomeItemSettingsRow(
    item: LauncherColumnItem,
    settings: com.crosspoint.launcher.data.LauncherSettings,
    selectedEntries: List<String>,
    onSetItems: (List<String>) -> Unit,
    onChooseIcon: () -> Unit
) {
    val context = LocalContext.current
    val entry = item.key
    val index = selectedEntries.indexOf(entry)
    Column(Modifier.fillMaxWidth()) {
        Pressable(
            onClick = {
                if (index < 0 && selectedEntries.size >= com.crosspoint.launcher.data.LauncherSettings.MAX_HOME_ITEMS) {
                    android.widget.Toast.makeText(context, "Home can show up to 5 items", android.widget.Toast.LENGTH_SHORT).show()
                } else onSetItems(if (index >= 0) selectedEntries - entry else selectedEntries + entry)
            },
            modifier = Modifier.fillMaxWidth().semantics {
                role = Role.Checkbox
                stateDescription = if (index >= 0) "Selected" else "Not Selected"
            }
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    when (item) {
                        is LauncherColumnItem.MenuAction -> LucideIcon(item.item.icon, Modifier.size(32.dp))
                        is LauncherColumnItem.PinnedApp -> Pressable(onClick = onChooseIcon,
                            modifier = Modifier.semantics { contentDescription = "Change Icon for ${item.label}" }) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                LauncherAppIcon(item.app, settings, size = 32.dp)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    BodyText(item.label, weight = FontWeight.Medium, size = 16, maxLines = Int.MAX_VALUE)

                }
                Spacer(Modifier.width(12.dp))
                CheckBox(index >= 0)
            }
        }
        if (index >= 0 && selectedEntries.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = 62.dp, end = 6.dp)) {
                for (direction in listOf(-1, 1)) {
                    val target = index + direction
                    Box(Modifier.weight(1f)) {
                        if (target in selectedEntries.indices) {
                            Pressable(onClick = {
                                val next = selectedEntries.toMutableList()
                                java.util.Collections.swap(next, index, target)
                                onSetItems(next)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LucideIcon(if (direction < 0) "chevron_up" else "chevron_down", Modifier.size(20.dp))
                                    BodyText(if (direction < 0) "Move Up" else "Move Down", size = 14, maxLines = Int.MAX_VALUE)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActionRow(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(icon, Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BodyText(title, weight = FontWeight.SemiBold, size = 16, maxLines = Int.MAX_VALUE)
                if (subtitle.isNotBlank()) BodyText(subtitle, muted = true, size = 12, maxLines = Int.MAX_VALUE)
            }
            Spacer(Modifier.width(12.dp))
            LucideIcon("chevron_right", Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun RadioDot(selected: Boolean) {
    val colors = CrossPointTheme.colors
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(2.dp, colors.divider, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.onBackground)
            )
        }
    }
}

@Composable
internal fun CheckBox(checked: Boolean) {
    val colors = CrossPointTheme.colors
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(if (checked) Modifier.background(colors.onBackground) else Modifier)
            .border(1.5.dp, colors.onBackground, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (checked) LucideIcon("check", Modifier.size(14.dp), tintOverride = colors.background)
    }
}
