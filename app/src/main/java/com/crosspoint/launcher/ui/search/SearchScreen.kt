package com.crosspoint.launcher.ui.search

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crosspoint.launcher.data.LauncherSettings
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.HeaderIconButton
import com.crosspoint.launcher.ui.components.LauncherAppIcon
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme

/**
 * Full-screen app search with optional automatic keyboard display. When [autoOpenSingleResult] is on and the query narrows to exactly one app, it
 * launches automatically.
 */
@Composable
fun SearchScreen(
    apps: List<AppInfo>,
    settings: LauncherSettings,
    autoOpenSingleResult: Boolean,
    onLaunch: (String, String) -> Unit,
    onClose: () -> Unit,
    /** Launcher items hidden from Home (File Transfer, Browse Books, …) stay reachable from search. */
    hiddenItems: List<com.crosspoint.launcher.model.HomeMenuItem> = emptyList(),
    onOpenItem: (com.crosspoint.launcher.model.HomeMenuItem) -> Unit = {}
) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }

    val results = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<AppInfo> {
                it.label.startsWith(query.trim(), ignoreCase = true)
            }.thenBy { it.label.lowercase() })
    }

    val items = remember(hiddenItems, query) {
        hiddenItems.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    }

    fun open(item: com.crosspoint.launcher.model.HomeMenuItem) {
        keyboard?.hide()
        onOpenItem(item)
    }

    fun launch(app: AppInfo) {
        keyboard?.hide()
        onLaunch(app.packageName, app.activityName)
        onClose()
    }

    // Auto-open when the query resolves to a single result.
    LaunchedEffect(results, query, autoOpenSingleResult) {
        if (autoOpenSingleResult && query.isNotBlank() && results.size + items.size == 1) {
            results.firstOrNull()?.let(::launch) ?: open(items.first())
        }
    }

    LaunchedEffect(settings.showKeyboardAutomatically) {
        if (settings.showKeyboardAutomatically) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = topPad)
            .imePadding()
    ) {
        ScreenHeader("Search Apps", onClose, titleContent = {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onBackground, fontSize = 18.sp),
                cursorBrush = SolidColor(colors.onBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    items.firstOrNull()?.let { open(it) } ?: results.firstOrNull()?.let { launch(it) }
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) BodyText("Search apps", muted = true, size = 18)
                    inner()
                }
            )
        }) {
            if (query.isNotEmpty()) HeaderIconButton("x", onClick = { query = "" }, iconSize = 22.dp)
        }

        ScrollList(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = m.contentSidePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items, key = { "menu:" + it.id }) { item ->
                Pressable(onClick = { open(item) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            com.crosspoint.launcher.ui.icons.LucideIcon(item.icon, Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        BodyText(item.label, weight = FontWeight.Medium, size = 17)
                    }
                }
            }
            items(results, key = { it.packageName + "/" + it.activityName }) { app ->
                Pressable(onClick = { launch(app) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LauncherAppIcon(app, settings, size = 32.dp)
                        Spacer(Modifier.width(16.dp))
                        BodyText(app.label, weight = FontWeight.Medium, size = 17)
                    }
                }
            }
        }
    }
}
