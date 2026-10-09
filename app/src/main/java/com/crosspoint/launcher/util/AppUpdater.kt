package com.crosspoint.launcher.util

import android.content.Context
import com.crosspoint.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL

/**
 * Self-update from android.crosspointreader.com (published by scripts/release.sh).
 * The SHA-256 catches corrupt downloads; Android itself refuses an update not signed with our release key.
 */
object AppUpdater {
    private const val MANIFEST = "https://android.crosspointreader.com/latest.json"

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)

    /** The published release if it is newer than this build, else null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(URL(MANIFEST).readText())
        Release(
            json.getInt("versionCode"),
            json.getString("versionName"),
            URI(MANIFEST).resolve(json.getString("apk")).toString(),
            json.getString("sha256")
        ).takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val file = File(context.filesDir, "crosspoint-update.apk")
        ApkInstall.download(release.apkUrl, file, onProgress)
        if (withContext(Dispatchers.IO) { ApkInstall.sha256(file) } != release.sha256) {
            file.delete()
            error("Update failed verification")
        }
        return file
    }
}
