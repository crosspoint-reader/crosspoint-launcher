package com.crosspoint.launcher.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.crosspoint.launcher.model.Book
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme

/**
 * Long-press context menu for a book: Open, Open with…, Rename, Delete. Rename and Delete show an
 * inline confirmation step. Styled for the e-ink B&W theme.
 */
@Composable
fun BookContextMenu(
    book: Book,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = CrossPointTheme.colors
    var mode by remember { mutableStateOf(Mode.MENU) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.background)
                .border(2.dp, colors.divider, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp)
        ) {
            // Title
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                BodyText(book.title, weight = FontWeight.Bold, size = 16, maxLines = 2)
                if (book.author.isNotBlank()) BodyText(book.author, muted = true, size = 12)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            when (mode) {
                Mode.MENU -> {
                    MenuRow("book_open", "Open") { onOpen() }
                    MenuRow("library", "Open with…") { onOpenWith() }
                    MenuRow("file_text", "Rename") { mode = Mode.RENAME }
                    MenuRow("trash_2", "Delete") { mode = Mode.DELETE }
                }
                Mode.RENAME -> RenamePanel(
                    initial = book.title,
                    onCancel = { mode = Mode.MENU },
                    onConfirm = onRename
                )
                Mode.DELETE -> ConfirmDeletePanel(
                    onCancel = { mode = Mode.MENU },
                    onConfirm = onDelete
                )
            }
        }
    }
}

private enum class Mode { MENU, RENAME, DELETE }

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

@Composable
private fun RenamePanel(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = CrossPointTheme.colors
    var text by remember { mutableStateOf(initial) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BodyText("New name", muted = true, size = 12, weight = FontWeight.Bold)
        Box(
            Modifier.fillMaxWidth()
                .border(1.5.dp, colors.divider, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.onBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton("Cancel", onCancel)
            Spacer(Modifier.width(8.dp))
            TextButton("Rename", { onConfirm(text) }, emphasized = true)
        }
    }
}

@Composable
private fun ConfirmDeletePanel(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BodyText("Delete this book permanently?", weight = FontWeight.Medium, size = 15)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton("Cancel", onCancel)
            Spacer(Modifier.width(8.dp))
            TextButton("Delete", onConfirm, emphasized = true)
        }
    }
}

@Composable
private fun TextButton(label: String, onClick: () -> Unit, emphasized: Boolean = false) {
    val colors = CrossPointTheme.colors
    Pressable(onClick = onClick) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(if (emphasized) Modifier.background(colors.onBackground) else Modifier)
                .border(1.5.dp, colors.divider, RoundedCornerShape(6.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            BodyText(
                label,
                weight = FontWeight.SemiBold,
                size = 14,
                color = if (emphasized) colors.background else colors.onBackground
            )
        }
    }
}
