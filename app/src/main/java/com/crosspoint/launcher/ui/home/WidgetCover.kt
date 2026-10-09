package com.crosspoint.launcher.ui.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.theme.CrossPointTheme

const val CROSSPOINT_WIDGET_HOST_ID = 7401

@Composable
fun WidgetCoverSlot(widgetId: Int, onPickWidget: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val host = remember(context) { AppWidgetHost(context.applicationContext, CROSSPOINT_WIDGET_HOST_ID) }
    val manager = remember(context) { AppWidgetManager.getInstance(context) }

    DisposableEffect(host) {
        host.startListening()
        onDispose { host.stopListening() }
    }

    if (widgetId <= 0) {
        Box(
            modifier
                .fillMaxWidth()
                .height(m.coverHeight)
                .border(2.dp, colors.divider, RoundedCornerShape(8.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BodyText("No widget selected", muted = true, size = 15)
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                com.crosspoint.launcher.ui.components.Pressable(onClick = onPickWidget) {
                    BodyText("Choose widget", weight = androidx.compose.ui.text.font.FontWeight.SemiBold, size = 16)
                }
            }
        }
        return
    }

    val info = manager.getAppWidgetInfo(widgetId)
    if (info == null) {
        Box(
            modifier
                .fillMaxWidth()
                .height(m.coverHeight),
            contentAlignment = Alignment.Center
        ) {
            BodyText("Widget unavailable", muted = true, size = 15)
        }
        return
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(m.coverHeight),
        factory = { ctx ->
            host.createView(ctx, widgetId, info).apply {
                setAppWidget(widgetId, info)
            }
        },
        update = { view ->
            view.setAppWidget(widgetId, manager.getAppWidgetInfo(widgetId))
        }
    )
}

fun deleteHostedWidget(context: Context, appWidgetId: Int) {
    if (appWidgetId > 0) {
        AppWidgetHost(context.applicationContext, CROSSPOINT_WIDGET_HOST_ID).deleteAppWidgetId(appWidgetId)
    }
}
