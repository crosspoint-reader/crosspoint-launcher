package com.crosspoint.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.crosspoint.launcher.util.Dither
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.ApplicationInfo
import com.crosspoint.launcher.data.LauncherSettings
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.model.LauncherIconStyle
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import com.crosspoint.launcher.util.doubleTapToRefresh

/** A pressable surface with no shaded overlay, keeping controls crisp on e-ink panels. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    cornerRadius: Dp = CrossPointTheme.metrics.cornerRadius,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) { content() }
}

@Composable
fun HeaderIconButton(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LucideIcon(name, Modifier.size(iconSize))
    }
}

/** Shared navigation bar: fixed back target, flexible title, inset trailing actions. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, titleContent: (@Composable () -> Unit)? = null, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().height(CrossPointTheme.metrics.headerHeight).doubleTapToRefresh().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton("arrow_left", onBack)
            Box(Modifier.weight(1f)) {
                if (titleContent != null) titleContent()
                else BodyText(title, weight = FontWeight.Bold, size = 18, maxLines = 1)
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CrossPointTheme.colors.divider))
    }
}

/** Header with title + live clock + battery, mirroring the Lyra header bar. */
@Composable
fun LyraHeader(
    title: String,
    clock: String,
    batteryPct: Int,
    charging: Boolean,
    wifiConnected: Boolean,
    showTitle: Boolean = true,
    showClock: Boolean = true,
    showBattery: Boolean = true,
    showWifi: Boolean = true,
    onTitleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(m.headerHeight)
                .doubleTapToRefresh()
                .padding(horizontal = m.contentSidePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (showTitle) {
                    if (onTitleClick != null) {
                        Pressable(onClick = onTitleClick) {
                            BodyText(
                                text = title,
                                weight = FontWeight.Bold,
                                size = 18,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        BodyText(text = title, weight = FontWeight.Bold, size = 18)
                    }
                }
            }
            if (showClock) {
                BodyText(text = clock, weight = FontWeight.Medium, size = 16)
            }
            if (showWifi) {
                if (showClock) Spacer(Modifier.width(12.dp))
                LucideIcon(
                    name = if (wifiConnected) "wifi" else "wifi_off",
                    modifier = Modifier.size(18.dp),
                    tintOverride = colors.onBackground
                )
            }
            if (showBattery) {
                if (showClock || showWifi) Spacer(Modifier.width(10.dp))
                LucideIcon(
                    name = batteryIcon(batteryPct),
                    modifier = Modifier.size(20.dp),
                    tintOverride = colors.onBackground
                )
                Spacer(Modifier.width(4.dp))
                BodyText(text = "$batteryPct%", size = 13, weight = FontWeight.Medium)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.divider)
        )
    }
}

private fun batteryIcon(pct: Int): String = when {
    pct >= 80 -> "battery_full"
    pct >= 45 -> "battery_medium"
    pct >= 15 -> "battery_low"
    else -> "battery"
}

/** Themed text using the current e-ink foreground color. */
@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    muted: Boolean = false,
    maxLines: Int = 1,
    color: Color? = null
) {
    val colors = CrossPointTheme.colors
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color ?: if (muted) colors.muted else colors.onBackground,
        fontSize = size.sp,
        lineHeight = (size * 1.25f).sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** A book cover: shows the parsed cover image, or a bordered placeholder with title text. */
@Composable
fun BookCover(
    title: String,
    author: String,
    cover: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    var coverSize by remember { mutableStateOf(IntSize.Zero) }
    var rendered by remember(cover, coverSize) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover, coverSize) {
        if (cover != null && coverSize.width > 0 && coverSize.height > 0) {
            rendered = withContext(Dispatchers.Default) {
                Dither.cover(cover.asAndroidBitmap(), coverSize.width, coverSize.height).asImageBitmap()
            }
        }
    }
    Box(
        modifier = modifier
            .onSizeChanged { coverSize = it }
            .clip(RoundedCornerShape(m.cornerRadius))
            .border(1.5.dp, colors.divider, RoundedCornerShape(m.cornerRadius))
            .background(colors.surface),
        contentAlignment = Alignment.Center
    ) {
        val image = rendered
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.None,
                filterQuality = FilterQuality.None
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                LucideIcon("book_open", modifier = Modifier.size(22.dp))
                Column {
                    BodyText(title, weight = FontWeight.Bold, size = 13, maxLines = 4)
                    Spacer(Modifier.height(4.dp))
                    BodyText(author, muted = true, size = 11, maxLines = 2)
                }
            }
        }
    }
}

/** App icon rendered as a B&W mono glyph by default to fit the e-ink aesthetic. */
@Composable
fun AppIcon(
    icon: ImageBitmap?,
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val colors = CrossPointTheme.colors
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = contentDescription,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun LauncherAppIcon(
    app: AppInfo,
    settings: LauncherSettings,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val colors = CrossPointTheme.colors
    when (settings.launcherIconStyle) {
        LauncherIconStyle.ICONS -> {
            val override = settings.appIconOverrides[app.key]
            if (override == null && app.icon != null && (app.packageName.startsWith("com.wetao.") || app.packageName == "com.android.settings")) {
                AppIcon(app.icon, app.label, size, modifier)
            } else {
                LucideIcon(override ?: defaultIconForApp(app), modifier.size(size))
            }
        }
        LauncherIconStyle.DOTS -> Box(
            modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(size * 0.36f).clip(RoundedCornerShape(size)).background(colors.onBackground))
        }
        LauncherIconStyle.SQUARES -> Box(
            modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(size * 0.7f).border(2.dp, colors.divider, RoundedCornerShape(2.dp)))
        }
        LauncherIconStyle.CIRCLES -> Box(
            modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(size * 0.72f).clip(RoundedCornerShape(size)).border(2.dp, colors.divider, RoundedCornerShape(size)))
        }
    }
}

val AppInfo.key: String
    get() = "$packageName/$activityName"

fun defaultIconForApp(app: AppInfo): String {
    val label = app.label.lowercase()
    val pkg = app.packageName.lowercase()
    return when {
        "audible" in pkg || "audible" in label -> "headphones"
        "koreader" in pkg || "koreader" in label -> "book_open_text"
        "moon" in label && "reader" in label -> "book_text"
        "moonreader" in pkg || "moondownload" in pkg -> "book_text"
        "libby" in label || "libby" in pkg || "overdrive" in pkg -> "library_big"
        "cloudlibrary" in label.replace(" ", "") || "cloudlibrary" in pkg ||
            "bibliotheca" in pkg -> "book_marked"
        "kindle" in label || "kindle" in pkg || "amazon.kindle" in pkg -> "book_open"
        "kobo" in label || "kobo" in pkg -> "tablet"
        "play store" in label || "vending" in pkg -> "store"
        "store" in label || "shop" in label -> "shopping_bag"
        "calendar" in label -> "calendar"
        "mail" in label || "gmail" in label || "email" in label -> "mail"
        "chrome" in label || "browser" in label || "firefox" in label || "edge" in label -> "globe"
        "camera" in label -> "camera"
        "maps" in label || label == "map" -> "map"
        "music" in label || "spotify" in label -> "music"
        "video" in label || "youtube" in label || "netflix" in label -> "film"
        "game" in label -> "gamepad_2"
        app.category == ApplicationInfo.CATEGORY_AUDIO -> "music"
        app.category == ApplicationInfo.CATEGORY_VIDEO -> "film"
        app.category == ApplicationInfo.CATEGORY_IMAGE -> "camera"
        app.category == ApplicationInfo.CATEGORY_GAME -> "gamepad_2"
        app.category == ApplicationInfo.CATEGORY_MAPS -> "map"
        app.category == ApplicationInfo.CATEGORY_NEWS -> "newspaper"
        app.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> "app_window"
        else -> "app_window"
    }
}
