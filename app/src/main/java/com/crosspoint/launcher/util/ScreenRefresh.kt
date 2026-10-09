package com.crosspoint.launcher.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.crosspoint.launcher.data.SettingsRepository
import kotlinx.coroutines.flow.map

/** Full-screen black-then-white flash: the ebc driver redraws the whole panel, clearing e-ink ghosting. */
object ScreenRefresh {
    // Each colour must stay up long enough for the panel to finish a full update (the S4 driver reports ~570 ms).
    private const val FLASH_MS = 600L

    fun flash(activity: Activity) {
        val root = activity.window.decorView as ViewGroup
        val overlay = View(activity).apply { setBackgroundColor(Color.BLACK) }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.postDelayed({ overlay.setBackgroundColor(Color.WHITE) }, FLASH_MS)
        overlay.postDelayed({ root.removeView(overlay) }, FLASH_MS * 2)
    }

    /** Show a brief black frame to clear the panel, then run [action] (e.g. launch a reader) on a clean screen. */
    fun flashThen(activity: Activity, action: () -> Unit) {
        val root = activity.window.decorView as ViewGroup
        val overlay = View(activity).apply { setBackgroundColor(Color.BLACK) }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.postDelayed({ action(); root.removeView(overlay) }, 300)
    }
}

/** Double-tap to run [ScreenRefresh.flash], when Settings > Launcher > Double-Tap Header to Refresh is on. */
fun Modifier.doubleTapToRefresh(): Modifier = composed {
    val context = LocalContext.current
    val enabled by remember { SettingsRepository(context).settings.map { it.doubleTapRefresh } }.collectAsState(false)
    if (!enabled) this
    else pointerInput(Unit) { detectTapGestures(onDoubleTap = { context.activity()?.let(ScreenRefresh::flash) }) }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
