package com.crosspoint.launcher.util

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Shortcuts straight to individual system settings panels. The stock YXTSettings landing page renders
 * as a clipped two-pane layout on the S4's narrow panel, but each panel opens fine on its own — this
 * is the "settings layer" the stock launcher provided.
 */
object DeviceSettings {
    data class Panel(val label: String, val icon: String, val action: String)

    private val ALL = listOf(
        Panel("Wi-Fi", "wifi", Settings.ACTION_WIFI_SETTINGS),
        Panel("Bluetooth", "settings", Settings.ACTION_BLUETOOTH_SETTINGS),
        Panel("Display", "sun", Settings.ACTION_DISPLAY_SETTINGS),
        Panel("Sound", "settings", Settings.ACTION_SOUND_SETTINGS),
        Panel("Battery", "battery", Intent.ACTION_POWER_USAGE_SUMMARY),
        Panel("Storage", "folder_open", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Panel("Apps", "layout_grid", Settings.ACTION_APPLICATION_SETTINGS),
        Panel("Date & Time", "clock", Settings.ACTION_DATE_SETTINGS),
        Panel("Language & Input", "globe", Settings.ACTION_LOCALE_SETTINGS),
        Panel("Security", "check", Settings.ACTION_SECURITY_SETTINGS),
        Panel("Accessibility", "check", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Panel("Developer Options", "settings", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Panel("About", "smartphone", Settings.ACTION_DEVICE_INFO_SETTINGS),
    )

    /** Only the panels this ROM can actually open. */
    fun available(context: Context): List<Panel> =
        ALL.filter { context.packageManager.resolveActivity(Intent(it.action), 0) != null }

    fun open(context: Context, panel: Panel) {
        runCatching {
            context.startActivity(Intent(panel.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
