package com.crosspoint.launcher

import android.Manifest
import com.crosspoint.launcher.ui.components.nativeScreenHeader
import com.crosspoint.launcher.ui.components.headerAction

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.crosspoint.launcher.util.Launching

/** One permission per screen; the user always controls whether to grant or skip it. */
class PermissionSetupActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("onboarding", MODE_PRIVATE) }
    private val steps: List<String> by lazy {
        listOf("storage", "wifi", "home", "tip-apps", "tip-stats", "tip-home")
    }
    private var step = 0
    private val key get() = steps[step]
    private var message = ""
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = when {
            granted() -> ""
            key == "storage" && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> "Next, allow access to all files."
            else -> "Not granted. You can try again or skip this step."
        }
        render()
    }
    private val homeRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        step = (savedInstanceState?.getInt("step") ?: 0).coerceIn(0, steps.lastIndex)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (step > 0) move(-1) else finishSetup() }
        })
    }

    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("step", step); super.onSaveInstanceState(outState) }

    private fun wifiPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun granted(): Boolean = when (key) {
        "storage" -> (Build.VERSION.SDK_INT > 32 || has(Manifest.permission.READ_EXTERNAL_STORAGE)) &&
            (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
        "wifi" -> has(wifiPermission()) && (Build.VERSION.SDK_INT >= 33 || has(Manifest.permission.ACCESS_COARSE_LOCATION))
        "home" -> getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        else -> true  // tip screens are informational
    }

    private fun move(delta: Int) {
        if (step + delta > steps.lastIndex) { finishSetup(); return }
        step = (step + delta).coerceAtLeast(0); message = ""; render()
    }

    private fun finishSetup() {
        prefs.edit().putBoolean("permissions_complete", true).apply()
        finish()
    }

    private fun requestAccess() {
        try {
            when (key) {
                "storage" -> if (Build.VERSION.SDK_INT <= 32 && !has(Manifest.permission.READ_EXTERNAL_STORAGE))
                    requestRuntime(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                    else Launching.openManageStorageSettings(this)
                "wifi" -> requestRuntime(if (Build.VERSION.SDK_INT < 33)
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    else arrayOf(wifiPermission()))
                else -> {
                    val manager = getSystemService(RoleManager::class.java)
                    if (manager?.isRoleAvailable(RoleManager.ROLE_HOME) == true)
                        homeRequest.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    else Launching.openHomeSettings(this)
                }
            }
        } catch (_: Exception) {
            message = "Android could not open this request. You can skip and try again in Settings."
            render()
        }
    }

    private fun requestRuntime(permissions: Array<String>) {
        val permission = permissions.firstOrNull { !has(it) } ?: return
        if (prefs.getBoolean("asked_$permission", false) && !shouldShowRequestPermissionRationale(permission)) {
            Launching.openAppInfo(this, packageName)
        } else {
            prefs.edit().putBoolean("asked_$permission", true).apply()
            permissionRequest.launch(permissions)
        }
    }

    private fun render() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val allowed = granted()
        val title = when (key) {
            "storage" -> "Book Storage"; "wifi" -> "Nearby Wi-Fi"
            "tip-apps" -> "All Your Apps"; "tip-stats" -> "Reading Stats"; "tip-home" -> "Make It Yours"
            else -> "Default Launcher"
        }
        val description = when (key) {
            "storage" -> if (Build.VERSION.SDK_INT >= 30)
                if (Build.VERSION.SDK_INT <= 32) "Allow storage permission, then all-files access, so CrossPoint can find your EPUBs and save downloaded or transferred books. Return here after each request."
                else "Allow all-files access so CrossPoint can find your EPUBs and save downloaded or transferred books. Return here afterward."
                else "Allow storage access so CrossPoint can find the EPUB books on your device."
            "wifi" -> if (Build.VERSION.SDK_INT >= 33)
                "Allow nearby Wi-Fi access to create a hotspot for book transfers. CrossPoint does not use this to track your location."
                else "Android requires location permission to create a Wi-Fi hotspot for book transfers. Choose precise location if asked. CrossPoint does not track your location."
            "tip-apps" -> "Swipe up anywhere on the home screen to open all your apps."
            "tip-stats" -> "Swipe left to right on the home screen to see your reading stats."
            "tip-home" -> "Pick which shortcuts show on the home screen in Settings → Home Menu Items, and pin your favourite apps."
            else -> "Choose CrossPoint as your home app so your device opens your books and apps when you press Home."
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun text(value: String, size: Float) = TextView(this).also {
            it.text = value; it.textSize = size; it.setTextColor(android.graphics.Color.BLACK)
            it.setPadding(0, dp(8), 0, dp(8)); root.addView(it)
        }
        fun button(parent: LinearLayout, label: String, plain: Boolean = false, action: () -> Unit): Button = Button(this).also {
            it.text = label; it.textSize = 16f; it.isAllCaps = false
            if (plain) it.background = null
            it.setOnClickListener { action() }
            parent.addView(it, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(nativeScreenHeader(this, "", if (step > 0) ({ move(-1) }) else null, divider = false) {
            headerAction("Skip") { finishSetup() }
        })
        root.setPadding(dp(20), 0, dp(20), dp(8))
        screen.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        val last = step == steps.lastIndex
        text("SETUP · ${step + 1} OF ${steps.size}", 13f)
        text(title, 26f)
        text(description, 17f)
        val tip = key.startsWith("tip-")
        if (tip) {
            root.addView(GestureMock(this, key), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(16) })
        } else {
            root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            text(if (allowed) "Ready · Access granted" else message.ifBlank { "Optional · You can change this later in Settings." }, 15f)
        }
        button(root, if (allowed) { if (last) "Finish" else "Continue" } else if (key == "home") "Choose Home App" else "Allow Access") {
            if (allowed) move(1) else requestAccess()
        }
        if (!allowed) button(root, "Skip Step", true) { move(1) }
        ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(screen)
    }

    /** Simple line-art mock of a phone with the gesture arrow, for the tutorial screens. */
    private class GestureMock(context: Context, private val key: String) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }

        override fun onDraw(canvas: Canvas) {
            val pw = minOf(width * 0.40f, height * 0.46f / 1.6f)
            val ph = pw * 1.6f
            val l = (width - pw) / 2f; val t = height * 0.06f
            canvas.drawRoundRect(RectF(l, t, l + pw, t + ph), 14 * d, 14 * d, line)
            val cx = l + pw / 2
            // Gesture arrow sits in the empty space below the phone, never over the content.
            val arrowCy = t + ph + (height - (t + ph)) / 2f
            when (key) {
                "tip-apps" -> {
                    val gx = l + pw * 0.26f; val gy = t + ph * 0.4f; val stepX = pw * 0.24f; val rr = pw * 0.05f
                    for (i in 0..2) for (j in 0..1) canvas.drawCircle(gx + i * stepX, gy + j * stepX, rr, dot)
                    arrow(canvas, cx, arrowCy, (height - (t + ph)) * 0.5f, up = true)
                }
                "tip-stats" -> {
                    val bx = l + pw * 0.28f; val base = t + ph * 0.6f; val bw = pw * 0.11f
                    floatArrayOf(0.45f, 0.8f, 0.3f, 0.6f).forEachIndexed { i, h ->
                        canvas.drawRect(bx + i * bw * 1.5f, base - ph * 0.22f * h, bx + i * bw * 1.5f + bw, base, dot)
                    }
                    arrow(canvas, cx, arrowCy, pw * 0.5f, up = false)
                }
                else -> for (i in 0..3) canvas.drawLine(l + pw * 0.2f, t + ph * (0.34f + i * 0.13f), l + pw * 0.8f, t + ph * (0.34f + i * 0.13f), line)
            }
        }

        private fun arrow(canvas: Canvas, cx: Float, cy: Float, len: Float, up: Boolean) {
            val half = len / 2; val head = len * 0.22f
            val p = Path()
            if (up) {
                p.moveTo(cx, cy + half); p.lineTo(cx, cy - half)
                p.moveTo(cx - head, cy - half + head); p.lineTo(cx, cy - half); p.lineTo(cx + head, cy - half + head)
            } else {
                p.moveTo(cx - half, cy); p.lineTo(cx + half, cy)
                p.moveTo(cx + half - head, cy - head); p.lineTo(cx + half, cy); p.lineTo(cx + half - head, cy + head)
            }
            canvas.drawPath(p, arrowPaint)
        }
    }
}
