package com.crosspoint.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.model.AppearanceMode

/**
 * Black & white e-ink palette. Only pure black, pure white and a single mid value are used so
 * the UI stays crisp on grayscale e-ink panels.
 */
@Immutable
data class EinkColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val muted: Color,       // secondary text
    val divider: Color,
    val isDark: Boolean
)

val LightEink = EinkColors(
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    muted = Color(0xFF444444),
    divider = Color(0xFF000000),
    isDark = false
)

val DarkEink = EinkColors(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    muted = Color(0xFFBBBBBB),
    divider = Color(0xFFFFFFFF),
    isDark = true
)

/**
 * Layout metrics mirroring the reader's Lyra ThemeMetrics, expressed in dp. Values are adapted
 * per screen size / orientation in [com.crosspoint.launcher.ui.home.HomeScreen].
 */
@Immutable
data class Metrics(
    val topPadding: Dp = 8.dp,
    val headerHeight: Dp = 56.dp,
    val contentSidePadding: Dp = 20.dp,
    val verticalSpacing: Dp = 16.dp,
    val coverHeight: Dp = 200.dp,
    val coverAspect: Float = 0.66f,     // width = height * aspect
    val menuRowHeight: Dp = 60.dp,
    val menuSpacing: Dp = 12.dp,
    val cornerRadius: Dp = 6.dp,
    val listIconSize: Dp = 24.dp,
    val menuIconSize: Dp = 28.dp,
    val selectionPadding: Dp = 8.dp,
    val landscape: Boolean = false
)

val LocalEinkColors: ProvidableCompositionLocal<EinkColors> =
    staticCompositionLocalOf { LightEink }
val LocalMetrics: ProvidableCompositionLocal<Metrics> =
    staticCompositionLocalOf { Metrics() }

object CrossPointTheme {
    val colors: EinkColors
        @Composable get() = LocalEinkColors.current
    val metrics: Metrics
        @Composable get() = LocalMetrics.current
}

@Composable
fun CrossPointTheme(
    appearance: AppearanceMode,
    metrics: Metrics = Metrics(),
    content: @Composable () -> Unit
) {
    val dark = when (appearance) {
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkEink else LightEink
    CompositionLocalProvider(
        LocalEinkColors provides colors,
        LocalMetrics provides metrics,
        content = content
    )
}
