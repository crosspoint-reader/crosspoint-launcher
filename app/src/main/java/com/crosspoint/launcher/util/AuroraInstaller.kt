package com.crosspoint.launcher.util

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/** Downloads the latest Aurora Store from F-Droid and verifies F-Droid's signature before installing. */
object AuroraInstaller {
    const val PACKAGE = "com.aurora.store"
    // SHA-256 of the certificate F-Droid signs its Aurora Store builds with.
    private const val FDROID_CERT_SHA256 = "5c83c7672b929955dc0a1db89a5e6ae4389e2eae7ec939956041694e5815f532"

    suspend fun download(context: Context, onProgress: (Int) -> Unit): File {
        val code = withContext(Dispatchers.IO) {
            JSONObject(URL("https://f-droid.org/api/v1/packages/$PACKAGE").readText()).getInt("suggestedVersionCode")
        }
        val file = File(context.filesDir, "aurora-store.apk")
        ApkInstall.download("https://f-droid.org/repo/${PACKAGE}_$code.apk", file, onProgress)
        if (!isSignedByFdroid(context, file)) {
            file.delete()
            error("Download failed verification")
        }
        return file
    }

    internal fun isSignedByFdroid(context: Context, file: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        return info.packageName == PACKAGE && signers.any { ApkInstall.sha256(it.toByteArray()) == FDROID_CERT_SHA256 }
    }
}
