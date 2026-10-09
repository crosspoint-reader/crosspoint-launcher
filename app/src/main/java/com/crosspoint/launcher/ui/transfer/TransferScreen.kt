package com.crosspoint.launcher.ui.transfer

import com.crosspoint.launcher.ui.components.verticalScrollWithIndicator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.crosspoint.launcher.data.FileTransferServer
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface

private enum class TransferMode { PICK, NETWORK, HOTSPOT }
private const val TAG = "TransferScreen"

private data class HotspotInfo(
    val ssid: String = "",
    val password: String = "",
    val error: String? = null
)

@Composable
fun TransferScreen(
    onBooksChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val scope = rememberCoroutineScope()
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val port = FileTransferServer.DEFAULT_PORT
    var uploadCount by remember { mutableIntStateOf(0) }
    var lastFile by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(TransferMode.PICK) }
    var hotspotInfo by remember { mutableStateOf<HotspotInfo?>(null) }
    var hotspotStartAttempt by remember { mutableIntStateOf(0) }

    var ip by remember { mutableStateOf(localIpAddress()) }
    val server = remember {
        FileTransferServer(context, port) { name ->
            uploadCount += 1; lastFile = name; onBooksChanged()
        }
    }
    var showQr by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var hotspotReservation by remember { mutableStateOf<WifiManager.LocalOnlyHotspotReservation?>(null) }

    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            mode = TransferMode.HOTSPOT
        } else {
            hotspotInfo = HotspotInfo(error = "Hotspot permission denied")
        }
    }
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (mode == TransferMode.HOTSPOT && context.isLocationEnabled()) {
            hotspotStartAttempt += 1
        }
    }

    DisposableEffect(mode, hotspotStartAttempt) {
        if (mode == TransferMode.PICK) {
            onDispose {}
        } else {
            if (mode == TransferMode.NETWORK) {
                server.start(scope)
                running = true
            } else {
                running = false
                hotspotInfo = null
            }
            ip = localIpAddress()
            if (mode == TransferMode.HOTSPOT) {
                val manager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                try {
                    manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                            hotspotReservation = reservation
                            hotspotInfo = runCatching { reservation.toHotspotInfo() }
                                .onFailure { Log.e(TAG, "Failed to read hotspot credentials", it) }
                                .getOrElse {
                                    HotspotInfo(error = "Hotspot started, but Android did not expose the network credentials.")
                                }
                            ip = localIpAddress()
                            server.start(scope)
                            running = true
                        }

                        override fun onStopped() {
                            hotspotReservation = null
                        }

                        override fun onFailed(reason: Int) {
                            hotspotInfo = HotspotInfo(error = hotspotFailureMessage(reason))
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing permission for hotspot", e)
                    hotspotInfo = HotspotInfo(error = "Android blocked hotspot creation because Location is off. Turn on Location, then return here.")
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to start hotspot", e)
                    hotspotInfo = HotspotInfo(error = e.message ?: "Hotspot failed to start")
                }
            }
            onDispose {
                hotspotReservation?.close()
                hotspotReservation = null
                server.stop()
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = topPad)) {
        ScreenHeader("File Transfer", onBack)

        if (mode == TransferMode.PICK) {
            Column(
                Modifier.fillMaxSize().verticalScrollWithIndicator().padding(horizontal = m.contentSidePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BodyText("Choose how another device should connect to CrossPoint Launcher.", size = 16, maxLines = Int.MAX_VALUE)
                ModeRow(
                    title = "Join Network",
                    subtitle = "Use the Wi-Fi network this device is already connected to",
                    icon = "wifi",
                    onClick = { mode = TransferMode.NETWORK }
                )
                ModeRow(
                    title = "Create Hotspot",
                    subtitle = "Create a local Wi-Fi network for direct uploads",
                    icon = "smartphone",
                    onClick = {
                        val missing = hotspotPermissions().filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }.toTypedArray()
                        if (missing.isEmpty()) mode = TransferMode.HOTSPOT
                        else hotspotPermissionLauncher.launch(missing)
                    }
                )
                hotspotInfo?.error?.let { BodyText(it, muted = true, size = 13, maxLines = Int.MAX_VALUE) }
            }
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScrollWithIndicator().padding(horizontal = m.contentSidePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BodyText(
                if (mode == TransferMode.HOTSPOT)
                    "Join this hotspot, then open the address below."
                else
                    "On the same Wi-Fi, open this address to send EPUBs.",
                size = 14, maxLines = Int.MAX_VALUE
            )
            if (mode == TransferMode.HOTSPOT) {
                val info = hotspotInfo
                if (info?.error != null) {
                    BodyText(info.error, muted = true, size = 13, maxLines = Int.MAX_VALUE)
                    Pressable(onClick = {
                        locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) {
                        Row(
                            Modifier
                                .border(2.dp, colors.divider, RoundedCornerShape(8.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LucideIcon("settings", Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            BodyText("Open Location Settings", weight = FontWeight.SemiBold, size = 15)
                        }
                    }
                    if (context.isLocationEnabled()) {
                        Pressable(onClick = { hotspotStartAttempt += 1 }) {
                            Row(
                                Modifier
                                    .border(2.dp, colors.divider, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LucideIcon("rotate_cw", Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                BodyText("Retry Hotspot", weight = FontWeight.SemiBold, size = 15)
                            }
                        }
                    }
                } else if (info != null) {
                    CredentialRow("Network", info.ssid)
                    CredentialRow("Password", info.password.ifBlank { "Open network" })
                    Pressable(onClick = { showQr = true }) { BodyText("Show Wi-Fi QR code", size = 16, modifier = Modifier.padding(vertical = 12.dp)) }
                    if (showQr) androidx.compose.ui.window.Dialog(onDismissRequest = { showQr = false }) {
                        Column(Modifier.background(colors.background)) {
                            WifiQrCode(info)
                            Pressable(onClick = { showQr = false }) { BodyText("Close", size = 16, modifier = Modifier.padding(16.dp)) }
                        }
                    }
                } else {
                    BodyText("Starting hotspot...", muted = true, size = 13)
                }
            }
            if (running) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(2.dp, colors.divider, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BodyText(
                        "http://${FileTransferServer.LOCAL_HOST}:$port",
                        weight = FontWeight.Bold,
                        size = 20, maxLines = Int.MAX_VALUE
                    )
                }
                if (ip != null) {
                    BodyText("Fallback: http://$ip:$port", muted = true, size = 13, maxLines = Int.MAX_VALUE)
                } else {
                    BodyText("Not connected to Wi-Fi", muted = true, size = 13)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(if (running) "wifi" else "wifi_off", Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BodyText(
                    when {
                        mode == TransferMode.HOTSPOT && hotspotInfo?.error == null -> "Hotspot server running"
                        running -> "Server running"
                        else -> "Server stopped"
                    },
                    muted = true,
                    size = 13
                )
            }
            if (uploadCount > 0) {
                BodyText("Received $uploadCount file(s). Last: ${lastFile ?: ""}", size = 14, weight = FontWeight.Medium, maxLines = Int.MAX_VALUE)
            }
            BodyText(
                "Files are saved to the Books folder and appear in your library automatically.",
                muted = true, size = 12, maxLines = Int.MAX_VALUE
            )
        }
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
    val colors = CrossPointTheme.colors
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(2.dp, colors.divider, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LucideIcon(icon, Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BodyText(title, weight = FontWeight.SemiBold, size = 17)
                BodyText(subtitle, muted = true, size = 14, maxLines = Int.MAX_VALUE)
            }
            LucideIcon("chevron_right", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String) {
    val colors = CrossPointTheme.colors
    Column(
        Modifier.fillMaxWidth().border(1.5.dp, colors.divider, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BodyText(label, muted = true, size = 13)
        BodyText(value, weight = FontWeight.Bold, size = 15, maxLines = Int.MAX_VALUE)
    }
}

@Composable
private fun WifiQrCode(info: HotspotInfo) {
    val colors = CrossPointTheme.colors
    val payload = remember(info.ssid, info.password) { wifiQrPayload(info.ssid, info.password) }
    val bitmap = remember(payload) { qrBitmap(payload, 240) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BodyText("Scan to join hotspot", weight = FontWeight.SemiBold, size = 15)
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Hotspot QR code", modifier = Modifier.size(220.dp))
    }
}

private fun hotspotPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hotspotFailureMessage(reason: Int): String =
    when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "Hotspot failed: Android could not find an available Wi-Fi channel."
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "Hotspot failed: Android rejected the request."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Hotspot failed: Wi-Fi is currently in an incompatible mode."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Hotspot failed: this device or policy does not allow hotspot mode."
        else -> "Hotspot failed to start ($reason)"
    }

@Suppress("DEPRECATION")
private fun WifiManager.LocalOnlyHotspotReservation.toHotspotInfo(): HotspotInfo =
    if (Build.VERSION.SDK_INT >= 30) {
        HotspotInfo(
            ssid = softApConfiguration.ssid ?: "",
            password = softApConfiguration.passphrase ?: ""
        )
    } else {
        HotspotInfo(
            ssid = wifiConfiguration?.SSID?.trim('"') ?: "",
            password = wifiConfiguration?.preSharedKey?.trim('"') ?: ""
        )
    }

private fun wifiQrPayload(ssid: String, password: String): String =
    if (password.isBlank()) {
        "WIFI:T:nopass;S:${escapeWifiQrValue(ssid)};;"
    } else {
        "WIFI:T:WPA;S:${escapeWifiQrValue(ssid)};P:${escapeWifiQrValue(password)};;"
    }

private fun escapeWifiQrValue(value: String): String =
    value.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")
        .replace("\"", "\\\"")

private fun Context.isLocationEnabled(): Boolean =
    runCatching {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.isLocationEnabled
    }.getOrDefault(false)

private fun qrBitmap(contents: String, size: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2
    )
    val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private fun localIpAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()
