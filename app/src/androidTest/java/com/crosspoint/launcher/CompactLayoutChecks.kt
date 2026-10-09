package com.crosspoint.launcher

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.model.AppearanceMode
import com.crosspoint.launcher.ui.components.BodyText
import androidx.compose.foundation.lazy.LazyColumn
import com.crosspoint.launcher.ui.theme.CrossPointTheme

/** Run on a 480x800 / 219 dpi emulator: native form and normal list scrolling. */
object CompactLayoutChecks {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return; Thread.sleep(50) }; error(message)
    }
    fun run(test: Instrumentation) {
        val context = test.targetContext
        val account = test.startActivitySync(Intent(context, SyncAccountActivity::class.java)
            .putExtra("onboarding", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            test.waitForIdleSync()
            var problem: String? = null
            fun checkControls() {
                val all = views(account.window.decorView)
                check(all.none { it is ScrollView })
                val frame = Rect().also { account.window.decorView.getWindowVisibleDisplayFrame(it) }
                for (button in all.filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.text in listOf("Skip", "Log in", "Sign up") }) {
                    val bounds = Rect()
                    if (!button.getGlobalVisibleRect(bounds) || bounds.height() < button.height || bounds.bottom > frame.bottom)
                        problem = "${button.text} is clipped: $bounds / $frame"
                }
                val skip = all.filterIsInstance<Button>().first { it.text == "Skip" }
                val position = IntArray(2).also { skip.getLocationOnScreen(it) }
                if (position[0] < account.resources.displayMetrics.widthPixels / 2) problem = "Skip is not on the right"
            }
            test.runOnMainSync { checkControls() }
            check(problem == null) { problem!! }
            test.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(context.cacheDir, "compact-onboarding.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            test.runOnMainSync {
                val login = views(account.window.decorView).filterIsInstance<EditText>().first()
                login.requestFocus()
                (account.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(login, InputMethodManager.SHOW_IMPLICIT)
            }
            Thread.sleep(600)
            test.waitForIdleSync()
            test.runOnMainSync { checkControls() }
            check(problem == null) { problem!! }
            test.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(context.cacheDir, "compact-keyboard.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        } finally { test.runOnMainSync { account.finish() }; test.waitForIdleSync() }

        val prefs = context.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
        val completed = prefs.getBoolean("complete", false)
        prefs.edit().putBoolean("complete", true).commit()
        val activity = test.startActivitySync(Intent(context, SyncAccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as androidx.activity.ComponentActivity
        try {
            // Short lists fit without controls; long lists remain accessible through native scrolling.
            test.runOnMainSync { activity.setContent {
                CrossPointTheme(AppearanceMode.LIGHT) {
                    LazyColumn(Modifier.fillMaxWidth().height(100.dp)) {
                        items(listOf(1, 2)) { index -> BodyText("Exact fit $index", modifier = Modifier.height(50.dp)) }
                    }
                }
            } }
            waitFor("Exact-fit rows did not render together") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Exact fit 2" } }
            check(nodes(test.uiAutomation.rootInActiveWindow).none { it.text?.toString() in listOf("Next", "Previous") }) { "Unnecessary pagination footer" }
            test.runOnMainSync { activity.setContent {
                CrossPointTheme(AppearanceMode.LIGHT) {
                    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
                        items((1..25).toList()) { index -> BodyText("Test row $index", modifier = Modifier.fillMaxWidth().height(56.dp), size = 16) }
                    }
                }
            } }
            waitFor("Scrollable list did not render") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Test row 1" } }
            val seen = linkedSetOf<Int>()
            repeat(25) {
                val tree = nodes(test.uiAutomation.rootInActiveWindow)
                check(tree.none { it.text?.toString() in listOf("Next", "Previous") }) { "Paging controls returned" }
                val labels = tree.mapNotNull { it.text?.toString() }.filter { it.startsWith("Test row ") }
                val current = labels.map { it.substringAfterLast(' ').toInt() }
                check(current.isNotEmpty())
                seen.addAll(current)
                if (25 in seen) {
                    check(seen == (1..25).toSet()) { "Scrolling skipped rows: $seen" }
                    return
                }
                val list = tree.first { it.isScrollable }
                val bounds = Rect().also { list.getBoundsInScreen(it) }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                    "input swipe ${bounds.centerX()} ${bounds.bottom - 24} ${bounds.centerX()} ${bounds.top + 24} 400"
                )).use { it.readBytes() }
                test.uiAutomation.waitForIdle(300, 5000)
                waitFor("List did not scroll") {
                    nodes(test.uiAutomation.rootInActiveWindow).none { it.text?.toString() == "Test row ${current.first()}" }
                }
            }
            error("Scrolling did not reach the end")
        } finally {
            test.runOnMainSync { activity.finish() }; test.waitForIdleSync()
            prefs.edit().putBoolean("complete", completed).commit()
        }
    }
}
