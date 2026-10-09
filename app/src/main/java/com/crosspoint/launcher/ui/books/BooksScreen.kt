package com.crosspoint.launcher.ui.books

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.model.Book
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.BookCover
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.HeaderIconButton
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.home.StorageHint
import com.crosspoint.launcher.ui.home.rememberCover
import com.crosspoint.launcher.ui.theme.CrossPointTheme

@Composable
fun BooksScreen(
    books: List<Book>,
    loading: Boolean,
    hasStorage: Boolean,
    onOpen: (String) -> Unit,
    onOpenChooser: (String) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onGrantStorage: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    title: String = "Library"
) {
    val m = CrossPointTheme.metrics
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().padding(top = topPad)) {
        ScreenHeader(title, onBack) {
            BodyText("${books.size}", muted = true, size = 14)
            HeaderIconButton("rotate_cw", onClick = onRefresh, iconSize = 20.dp)
        }

        when {
            !hasStorage -> Box(Modifier.padding(m.contentSidePadding)) { StorageHint(onGrantStorage) }
            books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BodyText(if (loading) "Scanning…" else "No EPUB books found", muted = true, size = 15)
            }
            else -> ScrollList(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = m.contentSidePadding, vertical = 8.dp)
            ) {
                items(books, key = { it.filePath }) { book ->
                    BookRow(book, onOpen, onBookLongPress)
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onOpen: (String) -> Unit, onLongPress: (Book) -> Unit) {
    val cover = rememberCover(book.coverPath)
    Pressable(
        onClick = { onOpen(book.filePath) },
        onLongClick = { onLongPress(book) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().height(112.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, book.author, cover, Modifier.height(96.dp).width(64.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BodyText(book.title, weight = FontWeight.SemiBold, size = 18, maxLines = 2)
                if (book.author.isNotBlank() && !book.author.equals("Unknown", ignoreCase = true)) {
                    BodyText(book.author, muted = true, size = 14, maxLines = 2)
                }
            }
        }
    }
}
