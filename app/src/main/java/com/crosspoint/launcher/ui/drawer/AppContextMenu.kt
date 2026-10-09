package com.crosspoint.launcher.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.ui.components.AppIcon
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme

@Composable
fun AppContextMenu(
    app: AppInfo,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = CrossPointTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.background)
                .border(2.dp, colors.divider, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app.icon, app.label, size = 32.dp)
                Spacer(Modifier.width(14.dp))
                BodyText(app.label, weight = FontWeight.Bold, size = 16, maxLines = 2)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            MenuRow(if (pinned) "pin_off" else "pin", if (pinned) "Unpin" else "Pin") {
                onTogglePin()
                onDismiss()
            }
            MenuRow("info", "App info") {
                onAppInfo()
                onDismiss()
            }
        }
    }
}

@Composable
private fun MenuRow(icon: String, label: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth(), cornerRadius = 0.dp) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(icon, Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            BodyText(label, weight = FontWeight.Medium, size = 16)
        }
    }
}
