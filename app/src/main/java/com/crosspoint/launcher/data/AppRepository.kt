package com.crosspoint.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.crosspoint.launcher.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val EPUB_MIME = "application/epub+zip"

class AppRepository(private val context: Context) {

    private val iconSize = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
        .let { if (it.isLowRamDevice || it.memoryClass <= 128) 48 else 96 }
    private val pm: PackageManager get() = context.packageManager

    // The stock Settings launcher entry opens a two-pane landing page that renders broken on this
    // narrow panel; Settings > Device Settings replaces it, so keep it out of the drawer.
    private val hiddenPackages = setOf("com.android.settings")

    /** All launchable apps, alphabetically, excluding this launcher itself. */
    suspend fun loadLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        queryActivities(intent)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName !in hiddenPackages }
            .map { it.toAppInfo() }
            .sortedBy { it.label.lowercase() }
    }

    /** Apps able to open an EPUB (ACTION_VIEW with the epub mime type). */
    suspend fun loadReaderApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse("file:///book.epub"), EPUB_MIME)
        }
        queryActivities(intent)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { it.toAppInfo() }
            .sortedBy { it.label.lowercase() }
    }

    fun appLabel(packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    private fun ResolveInfo.toAppInfo(): AppInfo {
        val label = loadLabel(pm).toString()
        val bmp = runCatching { loadIcon(pm).toImageBitmap(iconSize) }.getOrNull()
        return AppInfo(
            packageName = activityInfo.packageName,
            activityName = activityInfo.name,
            label = label,
            icon = bmp,
            category = activityInfo.applicationInfo?.category ?: -1
        )
    }
}

fun Drawable.toImageBitmap(size: Int = 96): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp.asImageBitmap()
}
