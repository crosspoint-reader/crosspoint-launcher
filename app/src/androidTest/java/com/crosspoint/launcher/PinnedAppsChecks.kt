package com.crosspoint.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.crosspoint.launcher.data.AppRepository
import com.crosspoint.launcher.data.SettingsRepository
import com.crosspoint.launcher.ui.components.key
import com.crosspoint.launcher.data.launcherDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.crosspoint.launcher.data.LauncherSettings
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.ui.home.orderedHomeItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Run on an isolated 480x800 emulator: empty pins, selection, ordering, and removal. */
object PinnedAppsChecks {
    fun run(test: Instrumentation) = with(test) {
        val result = Bundle()
        val repo = SettingsRepository(targetContext)
        val original = runBlocking { repo.settings.first() }
        val onboarding = targetContext.getSharedPreferences("onboarding", 0)
        val complete = onboarding.getBoolean("complete", false)
        val permissions = onboarding.getBoolean("permissions_complete", false)
        var activity: Activity? = null
        var passed = false
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            node.run { refresh(); listOf(this) + (0 until childCount).flatMap { nodes(getChild(it)) } }
        fun waitFor(check: () -> Boolean) { repeat(100) { if (check()) return; Thread.sleep(50) }; error("Pinned apps check timed out") }
        fun tap(label: String) {
            uiAutomation.waitForIdle(250, 5000)
            fun scroll(action: Int): Boolean {
                val moved = nodes(uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }?.performAction(action) == true
                if (moved) { Thread.sleep(250); uiAutomation.waitForIdle(250, 5000) }
                return moved
            }
            repeat(12) { if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return@repeat }
            repeat(12) {
                val match = nodes(uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
                if (match != null) {
                    var button = match
                    while (!button!!.isClickable) button = button.parent ?: error("$label has no clickable parent")
                    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not tap $label" }
                    return
                }
                scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            error("Missing $label")
        }
        fun pins() = runBlocking { repo.settings.first().pinnedApps }
        try {
            val apps = runBlocking { AppRepository(targetContext).loadLaunchableApps() }.take(2)
            check(apps.size == 2)
            val mixed = listOf(apps[0].key, "menu:settings", apps[1].key)
            runBlocking { repo.setHomeItems(mixed) }
            val saved = runBlocking { SettingsRepository(targetContext).settings.first() }
            check(orderedHomeItems(saved.homeMenuItems, apps, saved).map { it.key } == mixed)
            runBlocking { repo.setHomeItems(mixed + HomeMenuItem.entries.map { "menu:${it.id}" }) }
            val capped = runBlocking { repo.settings.first() }
            check(capped.homeItemOrder!!.size == 5 && orderedHomeItems(capped.homeMenuItems, apps, capped).size == 5)
            runBlocking { repo.setHomeItems(capped.homeItemOrder!! + "extra.app/.Main") }
            check(runBlocking { repo.settings.first().pinnedApps } == apps.map { it.key }) { "Selection bypassed the five-item limit" }
            check(saved.effectiveHomeMenu(true) == listOf(HomeMenuItem.SETTINGS)) { "OPDS bypassed the chosen items" }
            runBlocking { repo.setHomeItems(LauncherSettings.DEFAULT_MENU.map { "menu:${it.id}" }) }
            onboarding.edit().putBoolean("complete", true).putBoolean("permissions_complete", true).commit()
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            tap("Settings")
            tap("Home Theme")
            uiAutomation.waitForIdle(250, 5000)
            check(nodes(uiAutomation.rootInActiveWindow).none { it.text?.toString() == "Carousel" }) { "Carousel is still offered" }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
            tap("Home Screen Items")
            runBlocking { repo.setHomeItems(emptyList()) }
            waitFor { runBlocking { repo.settings.first().homeMenuItems.isEmpty() } }
            tap(apps[0].label)
            waitFor { pins() == listOf(apps[0].key) }
            tap(apps[1].label)
            waitFor { pins() == apps.map { it.key } }
            tap("Move Up")
            waitFor { pins() == apps.reversed().map { it.key } }
            tap("Browse Books")
            tap("Recent Books")
            tap("Settings")
            waitFor { runBlocking { repo.settings.first().homeItemOrder?.size == 5 } }
            tap("File Transfer")
            check(runBlocking { repo.settings.first().homeItemOrder?.size } == 5)
            check(HomeMenuItem.FILE_TRANSFER !in runBlocking { repo.settings.first().homeMenuItems })
            uiAutomation.waitForIdle(250, 5000)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "pinned-apps.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            tap(apps[0].label)
            waitFor { pins() == listOf(apps[1].key) }
            passed = true
            result.putString("stream", "Combined Home selection, ordering, limits and unpin checks passed\n")
        } catch (e: Throwable) {
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "pinned-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            result.putString("stream", "Pins: ${pins()}\n" + e.stackTraceToString())
        }
        finally {
            runOnMainSync { activity?.finish() }
            runBlocking {
                repo.setHomeItems(original.homeItemOrder ?: (original.homeMenuItems.map { "menu:${it.id}" } + original.pinnedApps))
                if (original.homeItemOrder == null) targetContext.launcherDataStore.edit { it.remove(stringPreferencesKey("home_item_order")) }
            }
            onboarding.edit().putBoolean("complete", complete).putBoolean("permissions_complete", permissions).commit()
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
