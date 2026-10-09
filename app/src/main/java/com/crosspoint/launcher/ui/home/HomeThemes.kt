package com.crosspoint.launcher.ui.home

import com.crosspoint.launcher.ui.components.verticalScrollWithIndicator
import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.model.Book
import com.crosspoint.launcher.model.CoverSlotMode
import com.crosspoint.launcher.model.ExternalReadingState
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.BookCover
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme

/* ----------------------------- LYRA: one featured cover ----------------------------- */

@Composable
fun LyraHome(state: HomeUiState, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    Column(Modifier.fillMaxWidth()) {
        HomeHeader(state)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollWithIndicator()
                .padding(horizontal = m.contentSidePadding, vertical = homeCoverVisualGap()),
            verticalArrangement = Arrangement.spacedBy(homeCoverVisualGap())
        ) {
            if (state.settings.coverSlotMode == CoverSlotMode.WIDGET) {
                WidgetCoverSlot(state.settings.coverWidgetId, cb.onPickWidget)
            } else {
                when {
                    state.externalReading != null -> ExternalReadingFeature(state.externalReading, cb)
                    state.books.firstOrNull() != null -> FeaturedBook(state.books.first(), cb)
                    else -> BooksPlaceholder(state, cb)
                }
            }
            LauncherColumns(
                pinnedApps = state.pinnedApps,
                menuItems = state.menuItems,
                settings = state.settings,
                onLaunch = cb.onLaunchApp,
                onLongPressApp = cb.onAppInfo,
                onMenu = cb.onMenu,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FeaturedBook(book: Book, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    val cover = rememberCover(book.coverPath)
    Pressable(
        onClick = { cb.onOpenBook(book.filePath) },
        onLongClick = { cb.onBookLongPress(book) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            BookCover(
                title = book.title,
                author = book.author,
                cover = cover,
                modifier = Modifier
                    .height(m.coverHeight)
                    .width(m.coverHeight * m.coverAspect)
            )
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .height(m.coverHeight),
                verticalArrangement = Arrangement.Center
            ) {
                BodyText(book.title, weight = FontWeight.Bold, size = 28, maxLines = 4)
                if (book.author.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    BodyText(book.author, muted = true, size = 18, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun ExternalReadingFeature(item: ExternalReadingState, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    val cover = rememberCover(item.coverPath)
    val subtitle = item.author.ifBlank { item.source }
    Pressable(
        onClick = { cb.onOpenExternalReading(item) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            BookCover(
                title = item.title,
                author = subtitle,
                cover = cover,
                modifier = Modifier
                    .height(m.coverHeight)
                    .width(m.coverHeight * m.coverAspect)
            )
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .height(m.coverHeight),
                verticalArrangement = Arrangement.Center
            ) {
                BodyText(item.title, weight = FontWeight.Bold, size = 28, maxLines = 4)
                Spacer(Modifier.height(10.dp))
                BodyText(subtitle, muted = true, size = 18, maxLines = 1)
            }
        }
    }
}

/* ----------------------------- LYRA 3 COVERS ----------------------------- */

@Composable
fun Lyra3CoversHome(state: HomeUiState, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    Column(Modifier.fillMaxWidth()) {
        HomeHeader(state)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollWithIndicator()
                .padding(horizontal = m.contentSidePadding, vertical = homeCoverVisualGap()),
            verticalArrangement = Arrangement.spacedBy(homeCoverVisualGap())
        ) {
            if (state.settings.coverSlotMode == CoverSlotMode.WIDGET) {
                WidgetCoverSlot(state.settings.coverWidgetId, cb.onPickWidget)
            } else if (state.externalReading != null) {
                ExternalReadingFeature(state.externalReading, cb)
            } else {
                val recent = state.books.take(3)
                if (recent.isEmpty()) {
                    BooksPlaceholder(state, cb)
                } else {
                    BodyText("Recent books", muted = true, size = 12, weight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        recent.forEach { book ->
                            CoverTile(book, cb, Modifier.weight(1f))
                        }
                        repeat(3 - recent.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            LauncherColumns(
                pinnedApps = state.pinnedApps,
                menuItems = state.menuItems,
                settings = state.settings,
                onLaunch = cb.onLaunchApp,
                onLongPressApp = cb.onAppInfo,
                onMenu = cb.onMenu,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CoverTile(book: Book, cb: HomeCallbacks, modifier: Modifier = Modifier) {
    val cover = rememberCover(book.coverPath)
    Pressable(
        onClick = { cb.onOpenBook(book.filePath) },
        onLongClick = { cb.onBookLongPress(book) },
        modifier = modifier
    ) {
        Column(Modifier.padding(4.dp)) {
            BookCover(
                title = book.title,
                author = book.author,
                cover = cover,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.66f)
            )
            Spacer(Modifier.height(6.dp))
            BodyText(book.title, weight = FontWeight.SemiBold, size = 12, maxLines = 2)
            if (book.author.isNotEmpty()) BodyText(book.author, muted = true, size = 11, maxLines = 1)
        }
    }
}

/* ----------------------------- CAROUSEL ----------------------------- */

@Composable
fun CarouselHome(state: HomeUiState, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    Column(Modifier.fillMaxWidth()) {
        HomeHeader(state)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollWithIndicator()
                .padding(vertical = homeCoverVisualGap()),
            verticalArrangement = Arrangement.spacedBy(homeCoverVisualGap())
        ) {
            if (state.settings.coverSlotMode == CoverSlotMode.WIDGET) {
                Box(Modifier.padding(horizontal = m.contentSidePadding)) {
                    WidgetCoverSlot(state.settings.coverWidgetId, cb.onPickWidget)
                }
            } else if (state.externalReading != null) {
                Box(Modifier.padding(horizontal = m.contentSidePadding)) {
                    ExternalReadingFeature(state.externalReading, cb)
                }
            } else {
                if (state.books.isEmpty()) {
                    Box(Modifier.padding(horizontal = m.contentSidePadding)) {
                        BooksPlaceholder(state, cb)
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = m.contentSidePadding),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.books.take(12).forEachIndexed { index, book ->
                            val focused = index == 0
                            CarouselCover(book, focused, cb)
                        }
                    }
                    state.books.firstOrNull()?.let { lead ->
                        Column(Modifier.padding(horizontal = m.contentSidePadding)) {
                            BodyText(lead.title, weight = FontWeight.Bold, size = 18, maxLines = 2)
                            if (lead.author.isNotEmpty())
                                BodyText(lead.author, muted = true, size = 13)
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = m.contentSidePadding)) {
                LauncherColumns(
                    pinnedApps = state.pinnedApps,
                    menuItems = state.menuItems,
                    settings = state.settings,
                    onLaunch = cb.onLaunchApp,
                    onLongPressApp = cb.onAppInfo,
                    onMenu = cb.onMenu,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CarouselCover(book: Book, focused: Boolean, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    val cover = rememberCover(book.coverPath)
    val h = if (focused) m.coverHeight else m.coverHeight * 0.8f
    Pressable(
        onClick = { cb.onOpenBook(book.filePath) },
        onLongClick = { cb.onBookLongPress(book) }
    ) {
        Box(Modifier.padding(4.dp)) {
            BookCover(
                title = book.title,
                author = book.author,
                cover = cover,
                modifier = Modifier.height(h).width(h * m.coverAspect)
            )
        }
    }
}

/* ----------------------------- MINIMAL (Niagara list) ----------------------------- */

@Composable
fun MinimalHome(state: HomeUiState, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    Column(Modifier.fillMaxWidth()) {
        HomeHeader(state)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollWithIndicator()
                .padding(horizontal = m.contentSidePadding, vertical = m.verticalSpacing),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(homeLowerContentGap()))

            if (state.pinnedApps.isEmpty()) {
                Pressable(onClick = cb.onOpenDrawer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        LucideIcon("plus", Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        BodyText("Pin apps from the drawer", muted = true, size = 18)
                    }
                }
            }

            if (!state.hasStorage) {
                StorageHint(cb.onGrantStorage)
            } else if (state.externalReading != null) {
                BodyText("Recent reading", muted = true, size = 12, weight = FontWeight.Medium)
                BookListRow(
                    title = state.externalReading.title,
                    author = state.externalReading.author.ifBlank { state.externalReading.source },
                    onOpen = { cb.onOpenExternalReading(state.externalReading) },
                    onLongPress = { cb.onOpenExternalReading(state.externalReading) }
                )
            } else if (state.books.isNotEmpty()) {
                BodyText("Recent books", muted = true, size = 12, weight = FontWeight.Medium)
                state.books.take(4).forEach { book ->
                    BookListRow(
                        title = book.title,
                        author = book.author,
                        onOpen = { cb.onOpenBook(book.filePath) },
                        onLongPress = { cb.onBookLongPress(book) }
                    )
                }
            }

            DividerLine()
            LauncherColumns(
                pinnedApps = state.pinnedApps,
                menuItems = state.menuItems,
                settings = state.settings,
                onLaunch = cb.onLaunchApp,
                onLongPressApp = cb.onAppInfo,
                onMenu = cb.onMenu,
                modifier = Modifier.fillMaxWidth(),
                menuStyle = LauncherMenuStyle.Minimal
            )
        }
    }
}

/* ----------------------------- shared bits ----------------------------- */

@Composable
private fun DividerLine() {
    val colors = CrossPointTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.divider)
    )
}

@Composable
private fun BooksPlaceholder(state: HomeUiState, cb: HomeCallbacks) {
    if (!state.hasStorage) {
        StorageHint(cb.onGrantStorage)
        return
    }
    // No featured book: a simple left-aligned prompt, aligned to the menu buttons below.
    Pressable(
        onClick = { cb.onMenu(HomeMenuItem.BROWSE_BOOKS) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 28.dp)
        ) {
            BodyText("No open book", weight = FontWeight.Bold, size = 20)
            Spacer(Modifier.height(6.dp))
            BodyText("Start reading", muted = true, size = 15)
        }
    }
}

@Composable
private fun homeLowerContentGap() =
    if (CrossPointTheme.metrics.landscape) 48.dp else 96.dp

@Composable
private fun homeCoverVisualGap() = CrossPointTheme.metrics.run {
    menuSpacing + ((menuRowHeight - menuIconSize) / 2)
}

/** Small displays keep a compact reading card above a scrollable launcher list. */
@Composable
fun CompactHome(state: HomeUiState, cb: HomeCallbacks) {
    val m = CrossPointTheme.metrics
    Column(Modifier.fillMaxWidth()) {
        HomeHeader(state)
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp)) {
            Spacer(Modifier.height(12.dp))
            if (state.settings.coverSlotMode == CoverSlotMode.WIDGET) {
                Box(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = m.contentSidePadding)) {
                    WidgetCoverSlot(state.settings.coverWidgetId, cb.onPickWidget)
                }
            } else {
                var selectedBook by remember(state.books) { mutableIntStateOf(0) }
                val carousel = state.settings.theme == com.crosspoint.launcher.model.ThemeType.CAROUSEL
                val book = state.books.getOrNull(if (carousel) selectedBook else 0)
                val external = state.externalReading
                if (external == null && state.settings.theme == com.crosspoint.launcher.model.ThemeType.LYRA_3_COVERS && state.books.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().height(136.dp).padding(horizontal = m.contentSidePadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.books.take(3).forEach { recent ->
                            Pressable(onClick = { cb.onOpenBook(recent.filePath) }, onLongClick = { cb.onBookLongPress(recent) }, modifier = Modifier.weight(1f)) {
                                Column {
                                    BookCover(recent.title, recent.author, rememberCover(recent.coverPath), Modifier.height(96.dp).width(64.dp))
                                    BodyText(recent.title, size = 12, maxLines = 2)
                                }
                            }
                        }
                    }
                } else if (book != null || external != null) {
                    Pressable(onClick = { if (external != null) cb.onOpenExternalReading(external) else cb.onOpenBook(book!!.filePath) },
                        onLongClick = { book?.let { cb.onBookLongPress(it) } }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().height(152.dp).padding(horizontal = m.contentSidePadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.settings.theme != com.crosspoint.launcher.model.ThemeType.MINIMAL) {
                                BookCover(external?.title ?: book!!.title, external?.author ?: book!!.author,
                                    rememberCover(external?.coverPath ?: book?.coverPath), Modifier.width(90.dp).height(136.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                BodyText(external?.title ?: book!!.title, weight = FontWeight.Bold, size = 20, maxLines = 2)
                                BodyText(external?.author ?: book!!.author, muted = true, size = 14, maxLines = 1)
                                if (carousel && external == null && state.books.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                                    com.crosspoint.launcher.ui.components.HeaderIconButton("chevron_left", { selectedBook = (selectedBook - 1).coerceAtLeast(0) }, iconSize = 18.dp)
                                    BodyText("${selectedBook + 1}/${state.books.size}", size = 13)
                                    com.crosspoint.launcher.ui.components.HeaderIconButton("chevron_right", { selectedBook = (selectedBook + 1).coerceAtMost(state.books.lastIndex) }, iconSize = 18.dp)
                                }
                            }
                        }
                    }
                } else if (!state.hasStorage) StorageHint(cb.onGrantStorage)
            }
            val entries = orderedHomeItems(state.menuItems, state.pinnedApps, state.settings)
            ScrollList(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = m.contentSidePadding)) {
                items(entries) { entry ->
                    val label = when (entry) { is LauncherColumnItem.MenuAction -> entry.item.label; is LauncherColumnItem.PinnedApp -> entry.app.label }
                    Pressable(onClick = { when (entry) { is LauncherColumnItem.MenuAction -> cb.onMenu(entry.item); is LauncherColumnItem.PinnedApp -> cb.onLaunchApp(entry.app.packageName, entry.app.activityName) } },
                        onLongClick = { if (entry is LauncherColumnItem.PinnedApp) cb.onAppInfo(entry.app) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (entry) {
                                is LauncherColumnItem.MenuAction -> LucideIcon(entry.item.icon, Modifier.size(28.dp))
                                is LauncherColumnItem.PinnedApp -> com.crosspoint.launcher.ui.components.LauncherAppIcon(entry.app, state.settings, size = 28.dp)
                            }
                            Spacer(Modifier.width(16.dp))
                            BodyText(label, size = 22, maxLines = 2, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
