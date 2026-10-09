package com.crosspoint.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crosspoint.launcher.data.AppRepository
import com.crosspoint.launcher.data.EpubScanner
import com.crosspoint.launcher.data.ExternalReadingRepository
import com.crosspoint.launcher.data.LauncherSettings
import com.crosspoint.launcher.data.SettingsRepository
import com.crosspoint.launcher.model.AppInfo
import com.crosspoint.launcher.model.AppearanceMode
import com.crosspoint.launcher.model.Book
import com.crosspoint.launcher.model.CoverSlotMode
import com.crosspoint.launcher.model.ExternalReadingState
import com.crosspoint.launcher.model.HomeMenuItem
import com.crosspoint.launcher.model.LauncherIconStyle
import com.crosspoint.launcher.model.ThemeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SystemState(
    val clock: String = "",
    val date: String = "",
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val wifiConnected: Boolean = false
)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val appRepo = AppRepository(app)
    val syncRepo = com.crosspoint.launcher.data.SyncRepository(app)
    private val externalReadingRepo = ExternalReadingRepository(app, syncRepo)
    private val scanner = EpubScanner(app, syncRepo)
    val syncStatus = MutableStateFlow("")
    val syncBusy = MutableStateFlow(false)
    val opdsRepo = com.crosspoint.launcher.data.OpdsRepository(app)

    val opdsServers: StateFlow<List<com.crosspoint.launcher.model.OpdsServer>> =
        opdsRepo.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun upsertOpdsServer(s: com.crosspoint.launcher.model.OpdsServer) =
        viewModelScope.launch { opdsRepo.upsert(s) }
    fun deleteOpdsServer(id: String) = viewModelScope.launch { opdsRepo.delete(id) }

    val settings: StateFlow<LauncherSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _readerApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val readerApps: StateFlow<List<AppInfo>> = _readerApps.asStateFlow()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _externalReading = MutableStateFlow<ExternalReadingState?>(null)
    val externalReading: StateFlow<ExternalReadingState?> = _externalReading.asStateFlow()

    private val _booksLoading = MutableStateFlow(false)
    val booksLoading: StateFlow<Boolean> = _booksLoading.asStateFlow()

    private val _system = MutableStateFlow(SystemState())
    val system: StateFlow<SystemState> = _system.asStateFlow()

    private val _hasStorage = MutableStateFlow(hasStoragePermission())
    val hasStorage: StateFlow<Boolean> = _hasStorage.asStateFlow()

    private val timeFmt = SimpleDateFormat("h:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private var kindleObserver: FileObserver? = null
    private var kindleWarmJob: Job? = null
    private var booksJob: Job? = null
    private var appsJob: Job? = null
    private var externalJob: Job? = null
    private var coverJob: Job? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED -> refreshSystem()
                Intent.ACTION_BATTERY_CHANGED -> refreshSystem()
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    refreshSystem()
                    scheduleKindleMetadataWarm()
                }
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> refreshApps()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        getApplication<Application>().registerReceiver(systemReceiver, filter)
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        getApplication<Application>().registerReceiver(packageReceiver, packageFilter)
        refreshSystem()
        refreshApps()
        refreshExternalReading()
        if (_hasStorage.value) refreshBooks()
        startKindleMetadataWatcher()
        scheduleKindleMetadataWarm(delayMs = 0)
    }

    fun refreshSystem() {
        val ctx = getApplication<Application>()
        val now = Date()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val charging = bm.isCharging
        _system.value = SystemState(
            clock = timeFmt.format(now),
            date = dateFmt.format(now),
            batteryPct = if (pct == 0) 100 else pct,
            charging = charging,
            wifiConnected = isWifiConnected()
        )
    }

    fun refreshApps() {
        if (appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            _apps.value = appRepo.loadLaunchableApps()
            _readerApps.value = appRepo.loadReaderApps()
        }
    }

    fun refreshExternalReading() {
        if (externalJob?.isActive == true) return
        externalJob = viewModelScope.launch {
            _externalReading.value = externalReadingRepo.loadExternalReadingState()
        }
        scheduleKindleMetadataWarm()
    }

    fun launchExternalReading(state: ExternalReadingState) {
        if (!externalReadingRepo.launch(state)) {
            com.crosspoint.launcher.util.Launching.launchPackage(getApplication(), state.packageName)
        }
    }

    fun syncReading() = viewModelScope.launch {
        if (syncBusy.value || !syncRepo.connected) return@launch
        syncBusy.value = true
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { syncRepo.flushProgress() }
            syncStatus.value = "Reading history synced"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { syncStatus.value = "Reading saved offline; sync will retry when you return"
        } finally { syncBusy.value = false }
        if (_books.value.any { it.coverPath == null }) fetchMissingCovers()
    }

    fun disconnectSync() = viewModelScope.launch {
        if (syncBusy.value) return@launch
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { syncRepo.disconnect() }
        syncStatus.value = "Disconnected"
    }

    fun pauseCoverFetch() { coverJob?.cancel() }

    fun fetchMissingCovers() {
        if (syncBusy.value || coverJob?.isActive == true) return
        coverJob = viewModelScope.launch {
            if (syncBusy.value) return@launch
            syncBusy.value = true
            var found = 0
            try {
                val pending = _books.value.filter { it.coverPath == null }
                for ((index, book) in pending.withIndex()) {
                    syncStatus.value = "Looking up covers: ${index + 1}/${pending.size}"
                    val enriched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { syncRepo.fetchCover(book) }
                    if (enriched.coverPath != null) {
                        found++
                        _books.value = _books.value.map { if (it.filePath == book.filePath) enriched else it }
                    }
                }
                syncStatus.value = "Fetched $found covers (${pending.size} books checked)"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { syncStatus.value = e.message ?: "Cover lookup failed; saved covers are available offline"
            } finally { syncBusy.value = false }
        }
    }

    fun markBookOpened(path: String) {
        viewModelScope.launch {
            settingsRepo.markBookOpened(path)
        }
    }

    fun refreshBooks() {
        if (!hasStoragePermission() || booksJob?.isActive == true) return
        booksJob = viewModelScope.launch {
            _booksLoading.value = true
            try {
                _books.value = scanner.scan(settings.value.bookFolders)
                if (syncRepo.connected) fetchMissingCovers()
            } finally { _booksLoading.value = false }
        }
    }

    fun onStoragePermissionMaybeChanged() {
        val granted = hasStoragePermission()
        _hasStorage.value = granted
        if (granted && _books.value.isEmpty()) refreshBooks()
        if (granted) {
            startKindleMetadataWatcher()
            scheduleKindleMetadataWarm(delayMs = 0)
        }
    }

    /** Delete a book file, then rescan. */
    fun deleteBook(path: String) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { java.io.File(path).delete() }
            }
            refreshBooks()
        }
    }

    /** Rename a book file (keeping its .epub extension), then rescan. */
    fun renameBook(path: String, newBaseName: String) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(path)
                    val ext = f.extension
                    val clean = newBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .ifBlank { f.nameWithoutExtension }
                    val target = java.io.File(f.parentFile, if (ext.isNotEmpty()) "$clean.$ext" else clean)
                    if (target.absolutePath != f.absolutePath) f.renameTo(target)
                }
            }
            refreshBooks()
        }
    }

    /** Resolves pinned "package/activity" entries to currently-installed apps, preserving order. */
    fun pinnedAppInfos(settings: LauncherSettings, apps: List<AppInfo>): List<AppInfo> {
        val byKey = apps.associateBy { "${it.packageName}/${it.activityName}" }
        val byPkg = apps.associateBy { it.packageName }
        return settings.pinnedApps.mapNotNull { entry ->
            byKey[entry] ?: byPkg[entry.substringBefore("/")]
        }
    }

    // --- settings mutations ---
    fun setTheme(t: ThemeType) = viewModelScope.launch { settingsRepo.setTheme(t) }
    fun setAppearance(a: AppearanceMode) = viewModelScope.launch { settingsRepo.setAppearance(a) }
    fun setDefaultReader(pkg: String?) = viewModelScope.launch { settingsRepo.setDefaultReader(pkg) }
    fun togglePinned(entry: String) = viewModelScope.launch {
        val current = settings.value
        val selected = current.orderedHomeKeys(current.effectiveHomeMenu(opdsServers.value.isNotEmpty()),
            pinnedAppInfos(current, apps.value).map { "${it.packageName}/${it.activityName}" })
        if (entry !in selected && selected.size >= LauncherSettings.MAX_HOME_ITEMS) {
            android.widget.Toast.makeText(getApplication(), "Home can show up to 5 items", android.widget.Toast.LENGTH_SHORT).show()
        } else settingsRepo.setHomeItems(if (entry in selected) selected - entry else selected + entry)
    }
    fun setHomeItems(entries: List<String>) = viewModelScope.launch { settingsRepo.setHomeItems(entries) }
    fun setSearchGesture(g: com.crosspoint.launcher.model.SearchGesture) =
        viewModelScope.launch { settingsRepo.setSearchGesture(g) }
    fun setAutoOpenSingleResult(v: Boolean) =
        viewModelScope.launch { settingsRepo.setAutoOpenSingleResult(v) }
    fun setShowKeyboardAutomatically(v: Boolean) =
        viewModelScope.launch { settingsRepo.setShowKeyboardAutomatically(v) }
    fun setHideStatusBar(v: Boolean) =
        viewModelScope.launch { settingsRepo.setHideStatusBar(v) }
    fun setShowLauncherDate(v: Boolean) =
        viewModelScope.launch { settingsRepo.setShowLauncherDate(v) }
    fun setShowLauncherClock(v: Boolean) =
        viewModelScope.launch { settingsRepo.setShowLauncherClock(v) }
    fun setShowLauncherBattery(v: Boolean) =
        viewModelScope.launch { settingsRepo.setShowLauncherBattery(v) }
    fun setShowLauncherWifi(v: Boolean) =
        viewModelScope.launch { settingsRepo.setShowLauncherWifi(v) }
    fun setDoubleTapRefresh(v: Boolean) =
        viewModelScope.launch { settingsRepo.setDoubleTapRefresh(v) }
    fun setSleepScreenMode(mode: com.crosspoint.launcher.model.SleepScreenMode) =
        viewModelScope.launch { settingsRepo.setSleepScreenMode(mode) }
    fun setLastAppliedSleepCover(path: String) =
        viewModelScope.launch { settingsRepo.setLastAppliedSleepCover(path) }
    fun setLauncherIconStyle(style: LauncherIconStyle) =
        viewModelScope.launch { settingsRepo.setLauncherIconStyle(style) }
    fun setAppIconOverride(appKey: String, icon: String?) =
        viewModelScope.launch { settingsRepo.setAppIconOverride(appKey, icon) }
    fun setCoverSlotMode(mode: CoverSlotMode) =
        viewModelScope.launch { settingsRepo.setCoverSlotMode(mode) }
    fun setCoverWidget(id: Int) = viewModelScope.launch { settingsRepo.setCoverWidget(id) }
    fun clearCoverWidget() = viewModelScope.launch { settingsRepo.clearCoverWidget() }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            getApplication<Application>().checkSelfPermission(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    private fun startKindleMetadataWatcher() {
        if (kindleObserver != null || !hasStoragePermission()) return
        val root = externalReadingRepo.kindleFilesRoot()
        if (!root.isDirectory) return
        kindleObserver = object : FileObserver(
            root.absolutePath,
            CREATE or MOVED_TO or CLOSE_WRITE or MODIFY or DELETE or MOVED_FROM
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrBlank()) return
                scheduleKindleMetadataWarm()
            }
        }.also { it.startWatching() }
    }

    private fun scheduleKindleMetadataWarm(delayMs: Long = 1_500L) {
        if (!hasStoragePermission()) return
        if (kindleWarmJob?.isActive == true) return
        kindleWarmJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val changed = externalReadingRepo.warmKindleMetadataCache()
            if (changed) {
                _externalReading.value = externalReadingRepo.loadExternalReadingState()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unregisterReceiver(systemReceiver) }
        runCatching { getApplication<Application>().unregisterReceiver(packageReceiver) }
        runCatching { kindleObserver?.stopWatching() }
    }
}
