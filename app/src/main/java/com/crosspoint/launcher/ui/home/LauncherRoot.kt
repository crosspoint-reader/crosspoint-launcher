package com.crosspoint.launcher.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.LauncherViewModel
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.model.SearchGesture
import com.crosspoint.launcher.model.ThemeType
import com.crosspoint.launcher.ui.books.BooksScreen
import com.crosspoint.launcher.ui.drawer.AppDrawer
import com.crosspoint.launcher.ui.opds.OpdsBrowserScreen
import com.crosspoint.launcher.ui.opds.OpdsServersScreen
import com.crosspoint.launcher.ui.search.SearchScreen
import com.crosspoint.launcher.ui.settings.SettingsScreen
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import com.crosspoint.launcher.ui.transfer.TransferScreen
import com.crosspoint.launcher.util.Launching
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

enum class Screen { HOME, STATS, DRAWER, SETTINGS, BOOKS, SEARCH, OPDS, OPDS_SERVERS, TRANSFER }

@Composable
fun LauncherRoot(
    vm: LauncherViewModel,
    homeResetSignal: Int,
    isDefaultHome: Boolean,
    onRequestDefaultHome: () -> Unit,
    onPickWidget: () -> Unit
) {
    val context = LocalContext.current
    val colors = CrossPointTheme.colors
    var booksTitle by rememberSaveable { mutableStateOf("Library") }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var bannerDismissed by rememberSaveable { mutableStateOf(false) }
    var bookMenu by remember { mutableStateOf<com.crosspoint.launcher.model.Book?>(null) }

    // HOME button pressed while in the app -> always go back to the home screen.
    LaunchedEffect(homeResetSignal) { if (homeResetSignal > 0) screen = Screen.HOME }

    // Some readers map their capacitive home key to BACK. Consume it at home too.
    BackHandler { screen = Screen.HOME }

    val settings by vm.settings.collectAsState()
    val apps by vm.apps.collectAsState()
    val books by vm.books.collectAsState()
    val system by vm.system.collectAsState()
    val hasStorage by vm.hasStorage.collectAsState()
    val booksLoading by vm.booksLoading.collectAsState()
    val externalReading by vm.externalReading.collectAsState()
    val opdsServers by vm.opdsServers.collectAsState()

    // Sleep screen set to "Book Cover" tracks the most recent book automatically.
    if (settings.sleepScreenMode == com.crosspoint.launcher.model.SleepScreenMode.BOOK_COVER) {
        val recentCover = remember(books, settings.lastOpenedBookPath) {
            (books.firstOrNull { it.filePath == settings.lastOpenedBookPath } ?: books.maxByOrNull { it.lastModified })?.coverPath
        }
        LaunchedEffect(recentCover) {
            if (recentCover != null && recentCover != settings.lastAppliedSleepCover) {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.crosspoint.launcher.util.SleepScreen.applyCover(context, recentCover)
                }
                if (ok) vm.setLastAppliedSleepCover(recentCover)
            }
        }
    }

    val onMenu: (HomeMenuItem) -> Unit = { item ->
        when (item) {
            HomeMenuItem.APPS -> screen = Screen.DRAWER
            HomeMenuItem.SETTINGS -> screen = Screen.SETTINGS
            HomeMenuItem.BROWSE_BOOKS, HomeMenuItem.RECENT_BOOKS -> {
                booksTitle = if (item == HomeMenuItem.RECENT_BOOKS) "Recent Books" else "Library"
                screen = Screen.BOOKS
            }
            HomeMenuItem.OPDS_BROWSER -> screen = Screen.OPDS
            HomeMenuItem.FILE_TRANSFER -> screen = Screen.TRANSFER
        }
    }

    val effectiveMenu = settings.effectiveHomeMenu(opdsServers.isNotEmpty())

    val openBook: (String) -> Unit = { path ->
        vm.markBookOpened(path)
        val launch = { Launching.openBook(context, path, settings.defaultReaderPackage) }
        // Clear the cover's ghosting with a full refresh before the reader takes over.
        (context as? android.app.Activity)?.let { com.crosspoint.launcher.util.ScreenRefresh.flashThen(it, launch) } ?: launch()
    }
    val openBookChooser: (String) -> Unit = { path ->
        vm.markBookOpened(path)
        Launching.openBookChooser(context, path)
    }
    val launchApp: (String, String) -> Unit = { pkg, act ->
        val launch = { Launching.launchApp(context, pkg, act) }
        // Full refresh clears launcher ghosting before the app takes the screen.
        (context as? android.app.Activity)?.let { com.crosspoint.launcher.util.ScreenRefresh.flashThen(it, launch) } ?: launch()
    }

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val gesture = settings.searchGesture
    val appSwipeThreshold = with(density) { 40.dp.toPx() }
    val homeGestureModifier = Modifier.pointerInput(gesture, appSwipeThreshold) {
        awaitEachGesture {
            // Claim configured vertical swipes before child rows and lists consume them.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dy = 0f
            var released = false
            var claimed = false
            fun enabled(delta: Float) = when (gesture) {
                SearchGesture.SWIPE_UP -> delta < 0
                SearchGesture.SWIPE_DOWN -> delta > 0
                SearchGesture.BOTH -> true
                SearchGesture.OFF -> false
            }
            val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    dy = change.position.y - down.position.y
                    val dx = change.position.x - down.position.x
                    if (!change.pressed) { released = true; return@withTimeoutOrNull false }
                    if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                        claimed = abs(dy) > abs(dx) && enabled(dy)
                        if (claimed) change.consume()
                        return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (moved == null) {
                // Leave book/app long-press actions to the child; empty space opens the drawer.
                if (!down.isConsumed) screen = Screen.DRAWER
                return@awaitEachGesture
            }
            if (!claimed) return@awaitEachGesture
            while (!released) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                dy = change.position.y - down.position.y
                change.consume()
                released = !change.pressed
            }
            if (released && abs(dy) >= appSwipeThreshold && enabled(dy)) screen = Screen.SEARCH
        }
    }

    val statsSwipe = if (screen == Screen.HOME || screen == Screen.STATS) Modifier.pointerInput(screen, swipeThresholdPx) {
        var horizontalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { horizontalDrag = 0f },
            onDragCancel = { horizontalDrag = 0f },
            onDragEnd = {
                if (screen == Screen.HOME && horizontalDrag > swipeThresholdPx) screen = Screen.STATS
                else if (screen == Screen.STATS && horizontalDrag < -swipeThresholdPx) screen = Screen.HOME
            }
        ) { change, amount -> change.consume(); horizontalDrag += amount }
    } else Modifier
    Box(Modifier.fillMaxSize().background(colors.background).then(statsSwipe)) {
        when (screen) {
            Screen.STATS -> com.crosspoint.launcher.ui.stats.StatsScreen(onBack = { screen = Screen.HOME })
            Screen.HOME -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().then(homeGestureModifier)) {
              if (!isDefaultHome && !bannerDismissed) {
                DefaultHomeBanner(
                    onSet = onRequestDefaultHome,
                    onDismiss = { bannerDismissed = true }
                )
              }
              Box(
                  Modifier
                      .fillMaxSize()
              ) {
                val pinned = vm.pinnedAppInfos(settings, apps)
                val hasLastOpenedBook = books.any { it.filePath == settings.lastOpenedBookPath }
                val localBookOpenedAt = if (hasLastOpenedBook) settings.lastOpenedBookAt else 0L
                val orderedBooks = if (hasLastOpenedBook) {
                    books.sortedByDescending { if (it.filePath == settings.lastOpenedBookPath) Long.MAX_VALUE else it.lastModified }
                } else {
                    books
                }
                val visibleExternalReading = externalReading
                    ?.takeIf { localBookOpenedAt <= 0L || it.updatedAt >= localBookOpenedAt }
                val state = HomeUiState(
                    settings = settings,
                    menuItems = effectiveMenu,
                    books = orderedBooks,
                    externalReading = visibleExternalReading,
                    pinnedApps = pinned,
                    system = system,
                    hasStorage = hasStorage,
                    booksLoading = booksLoading
                )
                val callbacks = HomeCallbacks(
                    onMenu = onMenu,
                    onOpenBook = openBook,
                    onOpenBookChooser = openBookChooser,
                    onBookLongPress = { bookMenu = it },
                    onLaunchApp = launchApp,
                    onOpenExternalReading = { vm.launchExternalReading(it) },
                    onAppInfo = { Launching.openAppInfo(context, it.packageName) },
                    onOpenDrawer = { screen = Screen.DRAWER },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onPickWidget = onPickWidget,
                    onGrantStorage = { Launching.openManageStorageSettings(context) },
                    onRefreshBooks = { vm.refreshBooks() }
                )
                if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600) CompactHome(state, callbacks) else when (settings.theme) {
                    ThemeType.LYRA -> LyraHome(state, callbacks)
                    ThemeType.LYRA_3_COVERS -> Lyra3CoversHome(state, callbacks)
                    ThemeType.CAROUSEL -> CarouselHome(state, callbacks)
                    ThemeType.MINIMAL -> MinimalHome(state, callbacks)
                }
              }
            }
            Screen.DRAWER -> AppDrawer(
                apps = apps,
                hiddenItems = HomeMenuItem.entries.filter { it != HomeMenuItem.APPS && it !in effectiveMenu },
                onOpenItem = onMenu,
                pinnedKeys = settings.orderedHomeKeys(effectiveMenu,
                    vm.pinnedAppInfos(settings, apps).map { "${it.packageName}/${it.activityName}" }).toSet(),
                onLaunch = launchApp,
                onTogglePin = { vm.togglePinned(it) },
                onAppInfo = { Launching.openAppInfo(context, it) },
                onOpenSettings = { screen = Screen.SETTINGS },
                onBack = { screen = Screen.HOME }
            )
            Screen.SETTINGS -> SettingsScreen(
                vm = vm,
                onOpenOpdsServers = { screen = Screen.OPDS_SERVERS },
                onPickWidget = onPickWidget,
                onClearWidget = { vm.clearCoverWidget() },
                onBack = { screen = Screen.HOME }
            )
            Screen.OPDS_SERVERS -> OpdsServersScreen(
                servers = opdsServers,
                onSave = { vm.upsertOpdsServer(it) },
                onDelete = { vm.deleteOpdsServer(it) },
                onBack = { screen = Screen.SETTINGS }
            )
            Screen.OPDS -> OpdsBrowserScreen(
                servers = opdsServers,
                repo = vm.opdsRepo,
                onBooksChanged = { vm.refreshBooks() },
                onBack = { screen = Screen.HOME }
            )
            Screen.TRANSFER -> TransferScreen(
                onBooksChanged = { vm.refreshBooks() },
                onBack = { screen = Screen.HOME }
            )
            Screen.BOOKS -> BooksScreen(
                title = booksTitle,
                books = books,
                loading = booksLoading,
                hasStorage = hasStorage,
                onOpen = openBook,
                onOpenChooser = openBookChooser,
                onBookLongPress = { bookMenu = it },
                onGrantStorage = { Launching.openManageStorageSettings(context) },
                onRefresh = { vm.refreshBooks() },
                onBack = { screen = Screen.HOME }
            )
            Screen.SEARCH -> SearchScreen(
                apps = apps,
                settings = settings,
                autoOpenSingleResult = settings.autoOpenSingleResult,
                onLaunch = launchApp,
                onClose = { screen = Screen.HOME },
                hiddenItems = HomeMenuItem.entries.filter { it != HomeMenuItem.APPS && it !in effectiveMenu },
                onOpenItem = onMenu
            )
        }

        bookMenu?.let { book ->
            com.crosspoint.launcher.ui.books.BookContextMenu(
                book = book,
                onOpen = { openBook(book.filePath); bookMenu = null },
                onOpenWith = { openBookChooser(book.filePath); bookMenu = null },
                onRename = { newName -> vm.renameBook(book.filePath, newName); bookMenu = null },
                onDelete = { vm.deleteBook(book.filePath); bookMenu = null },
                onDismiss = { bookMenu = null }
            )
        }
    }
}

/** Snapshot passed to each home theme layout. */
data class HomeUiState(
    val settings: com.crosspoint.launcher.data.LauncherSettings,
    val menuItems: List<HomeMenuItem>,
    val books: List<com.crosspoint.launcher.model.Book>,
    val externalReading: com.crosspoint.launcher.model.ExternalReadingState?,
    val pinnedApps: List<com.crosspoint.launcher.model.AppInfo>,
    val system: com.crosspoint.launcher.SystemState,
    val hasStorage: Boolean,
    val booksLoading: Boolean
)

data class HomeCallbacks(
    val onMenu: (HomeMenuItem) -> Unit,
    val onOpenBook: (String) -> Unit,
    val onOpenBookChooser: (String) -> Unit,
    val onBookLongPress: (com.crosspoint.launcher.model.Book) -> Unit,
    val onLaunchApp: (String, String) -> Unit,
    val onOpenExternalReading: (com.crosspoint.launcher.model.ExternalReadingState) -> Unit,
    val onAppInfo: (com.crosspoint.launcher.model.AppInfo) -> Unit,
    val onOpenDrawer: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onPickWidget: () -> Unit,
    val onGrantStorage: () -> Unit,
    val onRefreshBooks: () -> Unit
)
