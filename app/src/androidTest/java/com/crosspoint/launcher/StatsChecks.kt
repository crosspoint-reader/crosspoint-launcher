package com.crosspoint.launcher

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.crosspoint.launcher.data.SyncRepository
import com.crosspoint.launcher.ui.stats.readingDays
import org.json.JSONObject
import java.net.*
import java.time.LocalDate

/** Isolated emulator checks: never run against a user's connected account. */
object StatsChecks {
    fun run(test: Instrumentation) {
        val context = test.targetContext
        val today = LocalDate.now()
        val summary = JSONObject("""{"seconds":3600,"sessions":3,"current_streak":1,"streak":4,"tod":[0,3600,0,0],"dow":[0,0,3600,0,0,0,0],"devices":[{"device":"Reader"}],"history_b64":"Aw=="}""")
            .put("anchor_day", java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2000,1,1), today))
        val activity = JSONObject("""{"pages_total":42,"books":[{"document":"book","pages_read":42,"page_count":200,"finished_at":null}],"days":[],"reading_days":[]}""")
        check(readingDays(summary, activity) == setOf(today, today.minusDays(1)))
        val documents = """{"items":[{"document":"book","title":"Stats Test Book","status":"reading","percentage":0.21}]}"""
        var bookStatus = "reading"
        var offline = false
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                    override fun getResponseCode(): Int { if (offline) throw java.io.IOException("Offline"); return 200 }
                    override fun getInputStream(): java.io.InputStream {
                        check(url.host == "stats.test")
                        check(getRequestProperty("x-auth-user") in listOf("stats-check", "stats-check-other"))
                        val response = when (url.path) {
                            "/users/auth" -> """{"authorized":"OK"}"""
                            "/api/v1/stats/summary" -> summary.toString()
                            "/api/v1/stats/activity" -> activity.toString()
                            "/api/v1/documents" -> """{"items":[{"document":"book","title":"Stats Test Book","status":null}]}"""
                            "/api/v1/progress" -> {
                                check(url.query == "limit=500")
                                documents.replace("reading", bookStatus)
                            }
                            else -> error("Unexpected stats request: ${url.path}")
                        }
                        return response.byteInputStream()
                    }
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                }
            }
        }
        val credentials = java.io.File(context.noBackupFilesDir, "sync-account.json")
        val saved = credentials.takeIf { it.exists() }?.readBytes()
        val prefs = context.getSharedPreferences("onboarding", 0)
        val completed = prefs.getBoolean("complete", false)
        val permissions = prefs.getBoolean("permissions_complete", false)
        val repo = SyncRepository(context)
        var screen: android.app.Activity? = null
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun waitText(text: String) {
            repeat(100) {
                if (nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == text }) return
                Thread.sleep(100)
            }
            error("Missing UI text: $text")
        }
        fun swipe(from: Int, to: Int) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input swipe $from 400 $to 400 350"))
                .use { it.readBytes() }
        }
        try {
            repo.connect("https://stats.test", "stats-check", "secret")
            val data = repo.readingStats()
            check(data.getJSONObject("activity").getInt("pages_total") == 42)
            check(data.getJSONArray("books").getJSONObject(0).getString("status") == "reading") { "Reading Now used raw metadata instead of resolved status" }
            bookStatus = "paused"
            check(repo.readingStats().getJSONArray("books").getJSONObject(0).getString("status") == "paused") { "Stats refresh reused stale book status" }
            bookStatus = "reading"
            repo.readingStats()
            check(repo.memoryReadingStats() != null)
            // Simulate process death: the persisted snapshot must still load.
            SyncRepository::class.java.getDeclaredField("statsMemory").apply { isAccessible = true }.set(null, null)
            check(SyncRepository(context).cachedReadingStats()?.getJSONObject("activity")?.getInt("pages_total") == 42)
            repo.connect("https://stats.test", "stats-check-other", "secret")
            check(repo.memoryReadingStats() == null && repo.cachedReadingStats() == null) { "Another account saw cached stats" }
            repo.connect("https://stats.test", "stats-check", "secret")
            offline = true
            check(runCatching { repo.readingStats() }.isFailure)
            check(repo.cachedReadingStats() != null) { "Failed refresh discarded cached stats" }
            prefs.edit().putBoolean("complete", true).putBoolean("permissions_complete", true).commit()
            screen = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.waitForIdleSync()
            swipe(50,430)
            waitText("Reading Stats")
            waitText("Pages Read")
            check(nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString() == "42" })
            swipe(430,50)
            waitText("Browse Books")
            repo.disconnect()
            check(repo.cachedReadingStats() == null && repo.memoryReadingStats() == null)
            offline = false
            repo.connect("https://stats.test", "stats-check", "secret")
            check(repo.cachedReadingStats() == null) { "Logout kept the account cache" }
            repo.disconnect()
            swipe(50,430)
            waitText("Sign up")
        } finally {
            test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            test.runOnMainSync { screen?.finish() }
            repo.disconnect()
            if (saved != null) credentials.writeBytes(saved)
            prefs.edit().putBoolean("complete", completed).putBoolean("permissions_complete", permissions).commit()
        }
    }
}
