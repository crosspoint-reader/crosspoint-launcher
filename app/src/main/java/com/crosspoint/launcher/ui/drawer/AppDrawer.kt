package com.crosspoint.launcher.ui.drawer

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.ui.components.AppIcon
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.HeaderIconButton
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme

@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    /** Launcher items hidden from Home (File Transfer, OPDS, …); they stay reachable here. */
    hiddenItems: List<com.crosspoint.launcher.model.HomeMenuItem>,
    onOpenItem: (com.crosspoint.launcher.model.HomeMenuItem) -> Unit,
    pinnedKeys: Set<String>,
    onLaunch: (String, String) -> Unit,
    onTogglePin: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var query by remember { mutableStateOf("") }
    var appMenu by remember { mutableStateOf<AppInfo?>(null) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(top = topPad)) {
        ScreenHeader("All Apps", onBack, titleContent = {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onBackground, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.onBackground),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) BodyText("Search apps", muted = true, size = 16)
                    inner()
                }
            )
        }) {
            HeaderIconButton("settings", onClick = onOpenSettings, iconSize = 20.dp)
        }

        ScrollList(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = m.contentSidePadding,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(hiddenItems.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { "menu:" + it.id }) { item ->
                Pressable(onClick = { onOpenItem(item) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { LucideIcon(item.icon, Modifier.size(24.dp)) }
                        Spacer(Modifier.width(16.dp))
                        BodyText(item.label, weight = FontWeight.Medium, size = 17, modifier = Modifier.weight(1f))
                        Pressable(onClick = { onTogglePin("menu:${item.id}") }) { LucideIcon("pin", Modifier.padding(8.dp).size(20.dp)) }
                    }
                }
            }
            items(filtered, key = { it.packageName + "/" + it.activityName }) { app ->
                val key = "${app.packageName}/${app.activityName}"
                val pinned = pinnedKeys.contains(key) || pinnedKeys.contains(app.packageName)
                DrawerRow(
                    app = app,
                    pinned = pinned,
                    onLaunch = { onLaunch(app.packageName, app.activityName) },
                    onTogglePin = { onTogglePin(key) },
                    onLongPress = { appMenu = app }
                )
            }
        }

        appMenu?.let { app ->
            val key = "${app.packageName}/${app.activityName}"
            AppContextMenu(
                app = app,
                pinned = pinnedKeys.contains(key) || pinnedKeys.contains(app.packageName),
                onTogglePin = { onTogglePin(key) },
                onAppInfo = { onAppInfo(app.packageName) },
                onDismiss = { appMenu = null }
            )
        }
    }
}

@Composable
private fun DrawerRow(
    app: AppInfo,
    pinned: Boolean,
    onLaunch: () -> Unit,
    onTogglePin: () -> Unit,
    onLongPress: () -> Unit
) {
    Pressable(onClick = onLaunch, onLongClick = onLongPress, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app.icon, app.label, size = 32.dp)
            Spacer(Modifier.width(16.dp))
            BodyText(app.label, weight = FontWeight.Medium, size = 17, modifier = Modifier.weight(1f))
            Pressable(onClick = onTogglePin) {
                LucideIcon(
                    if (pinned) "check" else "pin",
                    Modifier.padding(8.dp).size(20.dp)
                )
            }
        }
    }
}
