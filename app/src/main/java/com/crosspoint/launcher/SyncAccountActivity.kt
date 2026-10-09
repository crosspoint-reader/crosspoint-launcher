package com.crosspoint.launcher

import android.app.AlertDialog
import com.crosspoint.launcher.ui.components.nativeScreenHeader
import com.crosspoint.launcher.ui.components.headerAction

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.crosspoint.launcher.data.SyncRepository
import kotlinx.coroutines.*

/** A single-screen account form, including on the 480 × 800 S4. */
class SyncAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sync = SyncRepository(applicationContext)
        val onboarding = intent.getBooleanExtra("onboarding", false)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true }
        fun row() = LinearLayout(this).also { it.gravity = Gravity.CENTER_VERTICAL; root.addView(it) }
        fun label(value: String, size: Float = 16f) = TextView(this).also {
            it.text = value; it.textSize = size; it.setPadding(0, dp(6), 0, dp(6)); root.addView(it)
        }
        fun button(parent: LinearLayout, value: String, action: () -> Unit) = Button(this).also {
            it.text = value; it.textSize = 14f; it.isAllCaps = false; it.setOnClickListener { action() }
            parent.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(nativeScreenHeader(this, if (onboarding) "" else "CrossPoint Sync", if (onboarding) null else ({ complete() }), divider = !onboarding) {
            if (onboarding) headerAction("Skip") { complete() }.contentDescription = "Skip sign-in"
        })
        root.setPadding(dp(12), 0, dp(12), 0)
        screen.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        fun spacer(weight: Float) = View(this).also {
            root.addView(it, LinearLayout.LayoutParams(1, 0, weight))
        }
        spacer(0.5f)
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.crosspoint_wordmark); contentDescription = "CrossPoint"
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(240), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val intro = label("Sync your reading, see your stats,\nand fill in book covers.").apply { gravity = Gravity.CENTER }
        val formGap = View(this).also { root.addView(it, LinearLayout.LayoutParams(1, dp(24))) }
        var server = sync.server
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).also {
            it.hint = hint; it.contentDescription = hint; it.setSingleLine(true); it.textSize = 16f
            it.inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
            it.isSaveEnabled = !secret; it.setText(value)
            form.addView(it, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val username = field("Username", sync.username)
        val password = field("Password", "", true)
        val status = TextView(this).apply { textSize = 14f; maxLines = 4; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val controls = mutableListOf<Button>()
        fun submit(signup: Boolean) {
            val user = username.text.toString(); val secret = password.text.toString()
            controls.forEach { it.isEnabled = false }; status.text = "Connecting…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { sync.connect(server, user, secret, signup) }
                    password.text.clear(); complete()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { status.text = e.message ?: "Could not connect"
                } finally { controls.forEach { it.isEnabled = true } }
            }
        }
        val actions = row()
        controls += button(actions, "Log in") { submit(false) }
        controls += button(actions, "Sign up") { submit(true) }
        root.addView(status)
        spacer(1f)
        val note = label("Optional · Connect later in Settings.", 13f).apply { gravity = Gravity.CENTER }
        val serverRow = row()
        button(serverRow, "Change server") {
            val address = EditText(this).apply { setSingleLine(true); setText(server); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            AlertDialog.Builder(this).setTitle("Sync server").setView(address)
                .setPositiveButton("Save") { _, _ -> server = address.text.toString().trim() }.setNegativeButton("Cancel", null).show()
        }.apply { background = null; textSize = 13f }
        val accountRow = row()
        if (sync.connected && !intent.getBooleanExtra("reauthenticate", false)) {
            form.visibility = View.GONE; actions.visibility = View.GONE; serverRow.visibility = View.GONE
            intro.text = "Connected as ${sync.username}"; note.text = "Your reading position, history and covers sync with your account."
            controls += button(accountRow, "Reading stats") {
                status.text = "Loading stats…"
                lifecycleScope.launch {
                    try {
                        val stats = withContext(Dispatchers.IO) { sync.flushProgress(); sync.activityStats() }
                        val books = stats.getJSONArray("books")
                        val finished = (0 until books.length()).count { !books.getJSONObject(it).isNull("finished_at") }
                        status.text = "${books.length()} books · $finished finished\n${stats.optInt("pages_total")} estimated pages\n${stats.getJSONArray("days").length()} reading days"
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { status.text = e.message }
                }
            }
            button(accountRow, "Disconnect") { lifecycleScope.launch { withContext(Dispatchers.IO) { sync.disconnect() }; recreate() } }
        } else accountRow.visibility = View.GONE
        ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
            status.maxLines = if (typing) 1 else 4
            logo.visibility = if (typing) View.GONE else View.VISIBLE
            formGap.visibility = if (typing) View.GONE else View.VISIBLE
            intro.visibility = if (typing) View.GONE else View.VISIBLE
            note.visibility = if (typing) View.GONE else View.VISIBLE
            if (!sync.connected) serverRow.visibility = if (typing) View.GONE else View.VISIBLE
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom))
            insets
        }
        setContentView(screen)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = complete()
        })
    }
    private fun complete() {
        getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("complete", true).apply(); finish()
    }
}
