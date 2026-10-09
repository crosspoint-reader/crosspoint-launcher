package com.crosspoint.launcher.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.ui.theme.CrossPointTheme

internal fun scrollbarBounds(height: Float, visible: Float, progress: Float, minimum: Float): Pair<Float, Float>? {
    if (height <= 0f || visible >= 1f || visible <= 0f) return null
    val thumb = (height * visible).coerceIn(minimum.coerceAtMost(height), height)
    return (height - thumb) * progress.coerceIn(0f, 1f) to thumb
}

private fun DrawScope.scrollbar(color: Color, visible: Float, progress: Float) {
    val inset = 2.dp.toPx()
    val (top, height) = scrollbarBounds(size.height - inset * 2, visible, progress, 24.dp.toPx()) ?: return
    val width = 3.dp.toPx()
    drawRect(color, Offset(size.width - inset - width, inset + top), Size(width, height))
}

/** A static thumb stays visible on e-ink whenever there is more content to scroll. */
@Composable
fun ScrollList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit
) {
    val color = CrossPointTheme.colors.onBackground
    LazyColumn(modifier.drawWithContent {
        drawContent()
        val layout = state.layoutInfo
        val rows = layout.visibleItemsInfo
        if ((state.canScrollForward || state.canScrollBackward) && rows.isNotEmpty()) {
            // Variable-height lists use the visible fraction of each row, without measuring offscreen content.
            val visible = rows.sumOf { row ->
                (minOf(row.offset + row.size, layout.viewportEndOffset) - maxOf(row.offset, layout.viewportStartOffset))
                    .coerceAtLeast(0).toDouble() / row.size.coerceAtLeast(1)
            }.toFloat()
            val first = rows.first()
            val position = first.index + (layout.viewportStartOffset - first.offset).coerceAtLeast(0).toFloat() / first.size.coerceAtLeast(1)
            val progress = when {
                !state.canScrollBackward -> 0f
                !state.canScrollForward -> 1f
                else -> position / (layout.totalItemsCount - visible).coerceAtLeast(0.01f)
            }
            scrollbar(color, (visible / layout.totalItemsCount).coerceIn(0.001f, 0.99f), progress)
        }
    }, state = state, contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)
}

@Composable
fun Modifier.verticalScrollWithIndicator(state: ScrollState = rememberScrollState()): Modifier {
    val color = CrossPointTheme.colors.onBackground
    return drawWithContent {
        drawContent()
        if (state.maxValue > 0 && state.maxValue != Int.MAX_VALUE) {
            scrollbar(color, size.height / (size.height + state.maxValue), state.value.toFloat() / state.maxValue)
        }
    }.verticalScroll(state)
}
