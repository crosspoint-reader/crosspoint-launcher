package com.crosspoint.launcher

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import com.crosspoint.launcher.data.OpdsRepository
import com.crosspoint.launcher.model.OpdsServer
import com.crosspoint.launcher.ui.opds.OpdsBrowserScreen
import com.crosspoint.launcher.ui.theme.CrossPointTheme

object SetupAndCatalogChecks {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun waitFor(message: String, condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(50) }; error(message)
    }
    fun run(test: Instrumentation) {
        val context = test.targetContext
        val prefs = context.getSharedPreferences("onboarding", 0)
        val old = prefs.getBoolean("permissions_complete", false)
        val setup = test.startActivitySync(Intent(context, PermissionSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            listOf("Book Storage", "Nearby Wi-Fi", "Default Launcher", "All Your Apps", "Reading Stats", "Make It Yours").forEach { title ->
                test.waitForIdleSync()
                test.runOnMainSync {
                    val all = views(setup.window.decorView)
                    check(all.filterIsInstance<TextView>().any { it.text.toString() == title })
                    check(all.any { it.contentDescription == "Back" } == (title != "Book Storage")) { "Back should only appear after the first step" }
                    check(all.filterIsInstance<Button>().none { it.text == "Back" }) { "Back belongs in the header" }
                    val frame = Rect().also { setup.window.decorView.getWindowVisibleDisplayFrame(it) }
                    val buttons = all.filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
                    buttons.forEach { button ->
                        val bounds = Rect()
                        check(button.getGlobalVisibleRect(bounds) && bounds.height() == button.height && bounds.bottom <= frame.bottom) { "Setup button clipped: ${button.text}" }
                    }
                    buttons.first { it.text in listOf("Continue", "Finish", "Skip Step") }.performClick()
                }
            }
            check(prefs.getBoolean("permissions_complete", false))
        } finally {
            test.runOnMainSync { setup.finish() }
            prefs.edit().putBoolean("permissions_complete", old).commit()
        }
        val oldAccount = prefs.getBoolean("complete", false)
        prefs.edit().putBoolean("permissions_complete", false).putBoolean("complete", false).commit()
        val main = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitFor("Permission setup did not open") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Book Storage" } }
            check(nodes(test.uiAutomation.rootInActiveWindow).first { it.text?.toString() == "Skip" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Account onboarding did not follow permissions") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Log in" } }
            check(nodes(test.uiAutomation.rootInActiveWindow).first { it.text?.toString() == "Skip" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Onboarding did not complete") { prefs.getBoolean("complete", false) }
        } finally {
            test.runOnMainSync { main.finish() }
            prefs.edit().putBoolean("permissions_complete", old).putBoolean("complete", oldAccount).commit()
        }
        // An actual paged catalog UI catches stale loading/error rows that parser tests cannot.
        val activity = test.startActivitySync(Intent(context, SyncAccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            OpdsChecks.prepareUiCheck()
            repeat(5) { iteration ->
                test.runOnMainSync {
                    activity.setContent {
                        key(iteration) {
                            CrossPointTheme(com.crosspoint.launcher.model.AppearanceMode.LIGHT) {
                                OpdsBrowserScreen(listOf(OpdsServer("ui", "Test Catalog", "https://opds.test/ui")), OpdsRepository(context), {}, {})
                            }
                        }
                    }
                }
                waitFor("Centered loading state did not render") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Loading…" } }
                if (iteration == 0) {
                    val loading = nodes(test.uiAutomation.rootInActiveWindow).first { it.text?.toString() == "Loading…" }
                    val bounds = Rect().also { loading.getBoundsInScreen(it) }
                    check(kotlin.math.abs(bounds.centerX() - context.resources.displayMetrics.widthPixels / 2) < 20)
                    check(bounds.centerY() > context.resources.displayMetrics.heightPixels / 3)
                    test.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(context.cacheDir, "opds-loading.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                }
                waitFor("Catalog error did not render") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Retry" } }
                var retry = nodes(test.uiAutomation.rootInActiveWindow).first { it.text?.toString() == "Retry" }
                while (!retry.isClickable) retry = retry.parent ?: error("Retry has no clickable target")
                check(retry.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("Catalog retry did not finish") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "No entries" } }
            }
            test.runOnMainSync { activity.setContent { CrossPointTheme(com.crosspoint.launcher.model.AppearanceMode.LIGHT) {
                com.crosspoint.launcher.ui.opds.OpdsServersScreen(emptyList(), {}, {}, {})
            } } }
            waitFor("Server list did not render") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Add Server" } }
            var add = nodes(test.uiAutomation.rootInActiveWindow).first { it.text?.toString() == "Add Server" }
            while (!add.isClickable) add = add.parent ?: error("Add Server has no clickable target")
            check(add.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Server editor did not render") { nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "Save" } }
            val save = nodes(test.uiAutomation.rootInActiveWindow).first { it.text?.toString() == "Save" }
            val bounds = Rect().also { save.getBoundsInScreen(it) }
            check(bounds.right <= context.resources.displayMetrics.widthPixels - 12) { "Save lacks an edge inset" }
            test.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(context.cacheDir, "opds-editor.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        } finally { test.runOnMainSync { activity.finish() } }
    }
}
