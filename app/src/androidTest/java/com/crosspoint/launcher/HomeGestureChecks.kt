package com.crosspoint.launcher

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.crosspoint.launcher.data.SettingsRepository
import com.crosspoint.launcher.model.SearchGesture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object HomeGestureChecks {
    fun run(test: Instrumentation) {
        val context = test.targetContext
        val repo = SettingsRepository(context)
        val original = runBlocking { repo.settings.first().searchGesture }
        val prefs = context.getSharedPreferences("onboarding", 0)
        val completed = prefs.getBoolean("complete", false)
        val permissions = prefs.getBoolean("permissions_complete", false)
        prefs.edit().putBoolean("complete", true).putBoolean("permissions_complete", true).commit()
        runBlocking { repo.setSearchGesture(SearchGesture.BOTH) }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun waitText(text: String) {
            repeat(100) {
                if (nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == text }) return
                Thread.sleep(50)
            }
            error("Missing $text")
        }
        fun command(value: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(value)).use { it.readBytes() }
        }
        try {
            // Header, cover area, list rows in both directions, and the bottom of Home.
            for ((from, to) in listOf(30 to 170, 200 to 90, 420 to 280, 420 to 560, 760 to 620)) {
                waitText("Browse Books")
                command("input swipe 220 $from 220 $to 250")
                waitText("Search apps")
                command("input keyevent KEYCODE_BACK")
            }
            waitText("Browse Books")
        } finally {
            test.runOnMainSync { activity.finish() }
            runBlocking { repo.setSearchGesture(original) }
            prefs.edit().putBoolean("complete", completed).putBoolean("permissions_complete", permissions).commit()
        }
    }
}
