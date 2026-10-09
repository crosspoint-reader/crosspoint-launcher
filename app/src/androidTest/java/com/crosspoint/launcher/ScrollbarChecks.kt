package com.crosspoint.launcher

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.model.AppearanceMode
import com.crosspoint.launcher.ui.components.*
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Check both list types at the top/bottom, and that a short screen has no indicator. */
object ScrollbarChecks {
    fun run(test: Instrumentation) {
        check(scrollbarBounds(100f, 1f, 0f, 24f) == null)
        check(scrollbarBounds(100f, 0.1f, 0f, 24f) == (0f to 24f))
        check(scrollbarBounds(100f, 0.1f, 1f, 24f) == (76f to 24f))
        val screen = test.startActivitySync(Intent(test.targetContext, SyncAccountActivity::class.java)
            .putExtra("onboarding", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SyncAccountActivity
        val rows = mutableIntStateOf(30)
        val lazy = mutableStateOf(true)
        val list = LazyListState()
        val scroll = ScrollState(0)
        var bounds = Rect.Zero
        try {
            test.runOnMainSync { screen.setContent {
                CrossPointTheme(AppearanceMode.LIGHT) {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
                        val area = Modifier.fillMaxWidth().height(240.dp).onGloballyPositioned { bounds = it.boundsInWindow() }
                        if (lazy.value) ScrollList(area, state = list) {
                            items(rows.intValue) { BodyText("Row $it", modifier = Modifier.height(48.dp)) }
                        } else Column(area.verticalScrollWithIndicator(scroll)) {
                            repeat(rows.intValue) { BodyText("Row $it", modifier = Modifier.height(48.dp)) }
                        }
                    }
                }
            } }
            fun thumbPixels(): List<Int> {
                test.waitForIdleSync()
                Thread.sleep(250)
                val bitmap = test.uiAutomation.takeScreenshot() ?: error("No screenshot")
                try {
                    val density = screen.resources.displayMetrics.density
                    val x = (bounds.right - 3.5f * density).toInt()
                    return (bounds.top.toInt() until bounds.bottom.toInt()).filter { y ->
                        val pixel = bitmap.getPixel(x, y)
                        android.graphics.Color.red(pixel) < 100 && android.graphics.Color.green(pixel) < 100
                    }
                } finally { bitmap.recycle() }
            }
            for (useLazy in listOf(true, false)) {
                test.runOnMainSync { lazy.value = useLazy; rows.intValue = 30 }
                val top = thumbPixels()
                check(top.isNotEmpty()) { "Missing ${if (useLazy) "list" else "column"} scrollbar" }
                runBlocking { withContext(Dispatchers.Main) {
                    if (useLazy) list.scrollToItem(29) else scroll.scrollTo(scroll.maxValue)
                } }
                val bottom = thumbPixels()
                check(bottom.isNotEmpty() && bottom.first() > top.first()) { "Scrollbar did not follow scrolling" }
                test.runOnMainSync { rows.intValue = 1 }
                check(thumbPixels().isEmpty()) { "Scrollbar shown for content that fits" }
            }
        } finally { test.runOnMainSync { screen.finish() } }
    }
}
