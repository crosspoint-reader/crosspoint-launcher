package com.crosspoint.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.crosspoint.launcher.util.Launching
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crosspoint.launcher.ui.home.LauncherRoot
import com.crosspoint.launcher.ui.home.CROSSPOINT_WIDGET_HOST_ID
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import com.crosspoint.launcher.ui.theme.Metrics
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    // Lets HOME-button re-entry reset navigation back to the home screen.
    private val homeResetSignal = mutableIntStateOf(0)
    private val isDefaultHome = mutableStateOf(true)
    private var showingOnboarding = false
    private val onboardingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        showingOnboarding = false
    }
    private var statusBarHidden = false
    private lateinit var roleLauncher: ActivityResultLauncher<Intent>
    private lateinit var widgetPickLauncher: ActivityResultLauncher<Intent>
    private lateinit var widgetConfigureLauncher: ActivityResultLauncher<Intent>
    private val widgetHost by lazy { AppWidgetHost(this, CROSSPOINT_WIDGET_HOST_ID) }
    private var pendingWidgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { refreshDefaultHome() }
        widgetPickLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                ?: pendingWidgetId
            if (result.resultCode == RESULT_OK && id > 0) {
                configureOrSaveWidget(id)
            } else {
                if (id > 0) widgetHost.deleteAppWidgetId(id)
            }
            pendingWidgetId = -1
        }
        widgetConfigureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                ?: pendingWidgetId
            if (result.resultCode == RESULT_OK && id > 0) {
                viewModelOrNull()?.setCoverWidget(id)
            } else if (id > 0) {
                widgetHost.deleteAppWidgetId(id)
            }
            pendingWidgetId = -1
        }
        enableEdgeToEdge()
        setContent {
            val vm: LauncherViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            LaunchedEffect(settings.hideStatusBar) {
                statusBarHidden = settings.hideStatusBar
                applyStatusBarVisibility()
            }
            CrossPointTheme(appearance = settings.appearance, metrics = Metrics()) {
                AdaptiveMetrics {
                    LauncherRoot(
                        vm = vm,
                        homeResetSignal = homeResetSignal.value,
                        isDefaultHome = isDefaultHome.value,
                        onRequestDefaultHome = { requestDefaultHome() },
                        onPickWidget = { pickCoverWidget() }
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyStatusBarVisibility()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing HOME while already here: bump the signal so the UI returns to the home screen.
        homeResetSignal.value += 1
    }

    override fun onResume() {
        super.onResume()
        val onboarding = getSharedPreferences("onboarding", MODE_PRIVATE)
        val setup = when {
            !onboarding.getBoolean("permissions_complete", false) -> PermissionSetupActivity::class.java
            !onboarding.getBoolean("complete", false) -> SyncAccountActivity::class.java
            else -> null
        }
        if (setup != null) {
            if (!showingOnboarding) {
                showingOnboarding = true
                onboardingLauncher.launch(Intent(this, setup).putExtra("onboarding", true))
            }
            return
        }
        showingOnboarding = false
        // Storage permission may have been granted in system settings while we were away.
        (viewModelOrNull())?.let {
            it.onStoragePermissionMaybeChanged()
            it.refreshExternalReading()
            it.syncReading()
        }
        refreshDefaultHome()

    }

    override fun onPause() {
        viewModelOrNull()?.pauseCoverFetch()
        super.onPause()
    }

    private fun refreshDefaultHome() {
        val rm = getSystemService(RoleManager::class.java)
        isDefaultHome.value = rm?.isRoleHeld(RoleManager.ROLE_HOME) ?: false
    }

    private fun requestDefaultHome() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                .onFailure { Launching.openHomeSettings(this) }
        } else if (rm?.isRoleHeld(RoleManager.ROLE_HOME) != true) {
            Launching.openHomeSettings(this)
        }
    }

    private fun pickCoverWidget() {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        runCatching { widgetPickLauncher.launch(intent) }
            .onFailure {
                widgetHost.deleteAppWidgetId(id)
                pendingWidgetId = -1
            }
    }

    private fun configureOrSaveWidget(appWidgetId: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        val configure: ComponentName? = info?.configure
        if (configure != null) {
            pendingWidgetId = appWidgetId
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            runCatching { widgetConfigureLauncher.launch(intent) }
                .onFailure {
                    widgetHost.deleteAppWidgetId(appWidgetId)
                    pendingWidgetId = -1
                }
        } else {
            viewModelOrNull()?.setCoverWidget(appWidgetId)
        }
    }

    private fun applyStatusBarVisibility() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (statusBarHidden) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun viewModelOrNull(): LauncherViewModel? = runCatching {
        androidx.lifecycle.ViewModelProvider(this)[LauncherViewModel::class.java]
    }.getOrNull()
}

/**
 * Derives layout [Metrics] from the available size + orientation so the same themes adapt across
 * phones, tablets and large e-ink panels in both portrait and landscape.
 */
@Composable
internal fun AdaptiveMetrics(includeNavigationBars: Boolean = true, content: @Composable () -> Unit) {
    val base = CrossPointTheme.metrics
    val colors = CrossPointTheme.colors
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (includeNavigationBars) Modifier.navigationBarsPadding() else Modifier)
            .background(colors.background)
    ) {
        val w = maxWidth
        val h = maxHeight
        val landscape = w > h
        val shortest = if (landscape) h else w
        // Scale cover and spacing with the panel's shorter edge; larger panels get bigger covers.
        val coverH = (shortest * if (landscape) 0.42f else 0.34f).coerceIn(150.dp, 360.dp)
        val sidePad = when {
            shortest >= 720.dp -> 64.dp
            shortest >= 600.dp -> 56.dp
            else -> 12.dp
        }
        val headerH = if (shortest >= 600.dp) 64.dp else 48.dp
        val metrics = base.copy(
            coverHeight = coverH,
            contentSidePadding = sidePad,
            headerHeight = headerH,
            menuRowHeight = if (shortest >= 600.dp) 68.dp else 48.dp,
            menuSpacing = 2.dp,
            verticalSpacing = 8.dp,
            landscape = landscape
        )
        androidx.compose.runtime.CompositionLocalProvider(
            com.crosspoint.launcher.ui.theme.LocalMetrics provides metrics
        ) {
            content()
        }
    }
}
