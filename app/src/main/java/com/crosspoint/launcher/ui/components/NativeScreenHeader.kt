package com.crosspoint.launcher.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.crosspoint.launcher.R

/** Native counterpart of ScreenHeader, keeping the EPUB reader's view hierarchy lightweight. */
fun nativeScreenHeader(context: Context, title: String, onBack: (() -> Unit)?, divider: Boolean = true,
                       actions: LinearLayout.() -> Unit = {}): LinearLayout {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
            if (onBack != null) addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_left)
                contentDescription = "Back"
                background = null
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                id = android.R.id.title
                text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions()
        }, LinearLayout.LayoutParams(-1, dp(if (context.resources.configuration.smallestScreenWidthDp >= 600) 64 else 48)))
        if (divider) addView(View(context).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
    }
}

fun LinearLayout.headerAction(label: String, action: () -> Unit): Button {
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    return Button(context).also {
        it.text = label; it.textSize = 14f; it.isAllCaps = false; it.background = null
        it.minWidth = dp(48); it.minimumWidth = dp(48); it.setPadding(dp(8), 0, dp(8), 0)
        it.setOnClickListener { action() }
        addView(it, LinearLayout.LayoutParams(-2, dp(48)))
    }
}
