package com.crosspoint.launcher.reader

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.crosspoint.launcher.R
import com.crosspoint.launcher.reader.ReaderSettings.Companion as S
import java.util.Date

/**
 * The firmware's reader status bar: battery, left clock and bookmark on the left, title centered, right clock and
 * page progress on the right, and the progress bar along the bottom edge.
 */
class ReaderStatusBar(context: Context) : LinearLayout(context) {
    class State(val title: String, val page: Int, val pages: Int, val bookPercent: Int, val battery: Int?, val bookmarked: Boolean)

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun label(gravity: Int) = TextView(context).apply {
        textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        this.gravity = gravity or Gravity.CENTER_VERTICAL; compoundDrawablePadding = dp(4f)
    }
    private val startText = label(Gravity.START)
    private val titleText = label(Gravity.CENTER)
    private val endText = label(Gravity.END)
    private val lane = LinearLayout(context).apply {
        setPadding(dp(12f), dp(2f), dp(12f), dp(4f))
        addView(startText, LinearLayout.LayoutParams(-2, -2))
        addView(titleText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f); marginEnd = dp(8f) })
        addView(endText, LinearLayout.LayoutParams(-2, -2))
    }
    private val filled = View(context)
    private val bar = LinearLayout(context).apply {
        addView(filled, LayoutParams(0, -1, 0f))
        addView(View(context), LayoutParams(0, -1, 1f))
    }

    init {
        orientation = VERTICAL
        addView(lane, LayoutParams(-1, -2))
        addView(bar, LayoutParams(-1, 0))
    }

    private fun icon(resource: Int, color: Int): Drawable? = ContextCompat.getDrawable(context, resource)?.mutate()?.apply {
        setTint(color); setBounds(0, 0, dp(14f), dp(14f))
    }

    fun bind(settings: ReaderSettings, state: State, foreground: Int) {
        val clock = DateFormat.getTimeFormat(context).format(Date())
        val clockMode = settings[S.STATUS_CLOCK]
        val pages = settings.on(S.STATUS_PAGES)
        val percent = settings.on(S.STATUS_PERCENT)
        val progress = when {
            pages && percent -> "${state.page}/${state.pages}  ${state.bookPercent}%"
            percent -> "${state.bookPercent}%"
            pages -> "${state.page}/${state.pages}"
            else -> null
        }
        endText.text = listOfNotNull(clock.takeIf { clockMode == 1 }, progress).joinToString("  ")
        val battery = state.battery.takeIf { settings.on(S.STATUS_BATTERY) }
        startText.text = listOfNotNull(battery?.takeIf { settings[S.HIDE_BATTERY_PERCENT] == 0 }?.let { "$it%" }, clock.takeIf { clockMode == 2 })
            .joinToString("  ")
        startText.setCompoundDrawablesRelative(battery?.let { icon(batteryIcon(it), foreground) }, null,
            if (state.bookmarked) icon(R.drawable.ic_bookmark, foreground) else null, null)
        titleText.text = state.title
        listOf(startText, titleText, endText).forEach { it.setTextColor(foreground) }

        val mode = settings[S.STATUS_BAR]
        val height = if (mode == 2) 0 else dp((settings.index(S.STATUS_BAR_THICKNESS) + 1) * 1.5f)
        val fraction = (if (mode == 0) state.bookPercent / 100f else if (state.pages > 0) state.page.toFloat() / state.pages else 0f).coerceIn(0f, 1f)
        filled.setBackgroundColor(foreground)
        (filled.layoutParams as LayoutParams).weight = fraction
        (bar.getChildAt(1).layoutParams as LayoutParams).weight = 1f - fraction
        bar.layoutParams.height = height
        val text = startText.text.isNotEmpty() || endText.text.isNotEmpty() || titleText.text.isNotEmpty() || battery != null || state.bookmarked
        lane.visibility = if (text) VISIBLE else GONE
        visibility = if (settings.on(S.STATUS_VISIBLE) && (text || height > 0)) VISIBLE else GONE
        bar.requestLayout()
    }

    private fun batteryIcon(percent: Int) = when {
        percent >= 60 -> R.drawable.ic_battery_full
        percent >= 25 -> R.drawable.ic_battery_medium
        else -> R.drawable.ic_battery_low
    }
}
