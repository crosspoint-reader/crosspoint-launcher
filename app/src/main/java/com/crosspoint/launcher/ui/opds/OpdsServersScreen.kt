package com.crosspoint.launcher.ui.opds

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crosspoint.launcher.model.OpdsServer
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import java.util.UUID

@Composable
fun OpdsServersScreen(
    servers: List<OpdsServer>,
    onSave: (OpdsServer) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<OpdsServer?>(null) }
    var isNew by remember { mutableStateOf(false) }

    if (editing != null) {
        OpdsServerEditor(
            server = editing!!,
            isNew = isNew,
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(it); editing = null },
            onBack = { editing = null }
        )
        return
    }

    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().padding(top = topPad)) {
        ScreenHeader("OPDS Servers", onBack)

        ScrollList(
            Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = m.contentSidePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (servers.isEmpty()) {
                item { BodyText("Add a catalog to browse and download books.", muted = true, size = 16, maxLines = 2, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            }
            items(servers) { s ->
                Pressable(onClick = { isNew = false; editing = s }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon("library", Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            BodyText(s.name.ifBlank { s.url }, weight = FontWeight.SemiBold, size = 16)
                            BodyText(s.url, muted = true, size = 12)
                        }
                        LucideIcon("chevron_right", Modifier.size(20.dp))
                    }
                }
            }
            item {
            Pressable(
                onClick = {
                    isNew = true
                    editing = OpdsServer(id = UUID.randomUUID().toString(), name = "", url = "https://")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LucideIcon("plus", Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    BodyText("Add Server", size = 18)
                }
            }
            }
        }
    }
}

@Composable
private fun OpdsServerEditor(
    server: OpdsServer,
    isNew: Boolean,
    onSave: (OpdsServer) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.url) }
    var username by remember { mutableStateOf(server.username) }
    var password by remember { mutableStateOf(server.password) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(top = topPad).imePadding()) {
        ScreenHeader(if (isNew) "Add Server" else "Edit Server", onBack) {
            Pressable(onClick = {
                val valid = runCatching {
                    val parsed = java.net.URL(url.trim())
                    require(parsed.protocol in listOf("http", "https") && parsed.host.isNotBlank() && parsed.userInfo == null) { "Enter an HTTP or HTTPS catalog URL" }
                }
                if (valid.isSuccess) onSave(server.copy(name = name.trim(), url = url.trim(), username = username.trim(), password = password))
                else error = valid.exceptionOrNull()?.message ?: "Invalid catalog URL"
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp).padding(horizontal = 8.dp)) {
                    LucideIcon("check", Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    BodyText("Save", weight = FontWeight.SemiBold, size = 15)
                }
            }
        }

        ScrollList(
            Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = m.contentSidePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { BodyText("OPDS 1 / 2 · Sign in when prompted", muted = true, size = 14) }
            error?.let { item { BodyText(it, size = 14, maxLines = 3) } }
            item { Field("Server Name", name, { name = it }, hint = "My Library") }
            item { Field("Server URL", url, { url = it }, hint = "https://example.com/opds") }
            item { Field("Username", username, { username = it }, hint = "optional") }
            item { Field("Password", password, { password = it }, hint = "optional", isPassword = true) }

            if (!isNew) item {
                Pressable(onClick = { onDelete(server.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LucideIcon("trash_2", Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        BodyText("Delete Server", weight = FontWeight.SemiBold, size = 16)
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    isPassword: Boolean = false
) {
    val colors = CrossPointTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BodyText(label.uppercase(), muted = true, size = 12, weight = FontWeight.Bold)
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.5.dp, colors.divider, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.onBackground),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (isPassword) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) BodyText(hint, muted = true, size = 16)
                    inner()
                }
            )
        }
    }
}
