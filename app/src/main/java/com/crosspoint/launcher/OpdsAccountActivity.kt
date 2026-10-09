package com.crosspoint.launcher

import android.app.Activity
import com.crosspoint.launcher.ui.components.nativeScreenHeader
import com.crosspoint.launcher.ui.components.headerAction

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.crosspoint.launcher.data.*
import com.crosspoint.launcher.model.OpdsServer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** WebView is allocated only when the catalog advertises OAuth browser authentication. */
class OpdsAccountActivity : ComponentActivity() {
    private var browser: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val server = OpdsServer(intent.getStringExtra("id")!!, intent.getStringExtra("name").orEmpty(),
            intent.getStringExtra("server")!!, intent.getStringExtra("username").orEmpty(), intent.getStringExtra("password").orEmpty())
        val doc = JSONObject(intent.getStringExtra("document")!!)
        val challenge = OpdsLoginRequired(server, intent.getStringExtra("resource")!!, intent.getStringExtra("documentUrl")!!, doc)
        val http = OpdsHttp(applicationContext)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nativeScreenHeader(this@OpdsAccountActivity, "Catalog Sign-In", { finish() }))
            addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val descriptions = ArrayList<TextView>()
        val actionRows = ArrayList<LinearLayout>()
        var actionRow: LinearLayout? = null
        var actionCount = 0
        fun label(value: String) = TextView(this).also { it.text = value; it.textSize = 15f; it.maxLines = 2; it.setPadding(0, 4, 0, 4); root.addView(it); descriptions.add(it) }
        fun button(value: String, action: () -> Unit) = Button(this).also {
            if (actionCount++ % 2 == 0) actionRow = LinearLayout(this).also { row -> root.addView(row); actionRows.add(row) }
            it.text = value; it.textSize = 13f; it.isAllCaps = false; it.maxLines = 2; it.setOnClickListener { action() }
            actionRow!!.addView(it, LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f))
        }
        fun field(hint: String, secret: Boolean = false) = EditText(this).also {
            it.hint = hint; it.contentDescription = hint; it.setSingleLine(true); it.isSaveEnabled = false
            it.inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
            root.addView(it)
        }
        label(doc.optString("title").ifBlank { server.name })
        label(challenge.message.orEmpty())
        label(Uri.parse(server.url).host.orEmpty())
        val status = label("").also { descriptions.remove(it) }
        val flows = OpdsJson.objects(doc.optJSONArray("authentication"))
        val implicit = flows.firstOrNull { it.optString("type") == "http://opds-spec.org/auth/oauth/implicit" }
        // Implicit-grant catalogs also accept typed credentials; OpdsHttp drives their HTML login without a browser.
        val passwordFlow = flows.firstOrNull { it.optString("type") == "http://opds-spec.org/auth/oauth/password" }
            ?: flows.firstOrNull { it.optString("type") == "http://opds-spec.org/auth/basic" } ?: implicit
        if (passwordFlow != null) {
            val endpoint = OpdsJson.links(passwordFlow.optJSONArray("links"), challenge.documentUrl).firstOrNull { it.hasRel("authenticate") }?.href
            if (endpoint != null) label("Sign in through ${Uri.parse(endpoint).host}")
            val labels = passwordFlow.optJSONObject("labels")
            val login = field(labels?.optString("login")?.ifBlank { "Login" } ?: "Login")
            val password = field(labels?.optString("password")?.ifBlank { "Password" } ?: "Password", true)
            lateinit var submit: Button
            submit = button("Sign in") {
                val user = login.text.toString(); val secret = password.text.toString()
                submit.isEnabled = false; status.text = "Signing in…"
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { http.signIn(challenge, user, secret) }
                        password.text.clear(); setResult(Activity.RESULT_OK); finish()
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { status.text = e.message
                    } finally { submit.isEnabled = true }
                }
            }
        }
        if (implicit != null) {
            val links = OpdsJson.links(implicit.optJSONArray("links"), challenge.documentUrl)
            val endpoint = links.firstOrNull { it.hasRel("authenticate") }?.href
            if (endpoint != null) button("Browser sign-in · ${Uri.parse(endpoint).host}") {
                try {
                    require(Uri.parse(endpoint).scheme == "https" && Uri.parse(server.url).scheme == "https") { "Sign-in requires HTTPS" }
                    val state = UUID.randomUUID().toString()
                    val uri = OpdsHttp.implicitUrl(endpoint, state)
                    val web = WebView(this)
                    browser = web
                    web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false }
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val callback = request.url
                            if (callback.scheme == "opds" && callback.host == "authorize") {
                                try {
                                    require(request.isForMainFrame) { "Invalid login callback" }
                                    http.saveCallback(challenge, callback.toString(), state, links.firstOrNull { it.hasRel("refresh") }?.href.orEmpty())
                                    setResult(Activity.RESULT_OK); finish()
                                } catch (e: Exception) { status.text = e.message; setContentView(scroll); browser?.destroy(); browser = null }
                                return true
                            }
                            return callback.scheme != "https"
                        }
                    }
                    setContentView(web); web.loadUrl(uri)
                } catch (e: Exception) { status.text = e.message }
            }
        }
        OpdsJson.links(doc.optJSONArray("links"), challenge.documentUrl).filter { it.hasRel("register") || it.hasRel("help") }.forEach { link ->
            button(link.title.ifBlank { if (link.hasRel("register")) "Sign up" else "Help" }) {
                try { require(Uri.parse(link.href).scheme == "https"); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.href))) }
                catch (e: Exception) { status.text = e.message }
            }
        }
        if (passwordFlow == null) status.text = "This catalog advertises no supported sign-in method."
        button("Cancel") { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
            actionRows.drop(1).forEach { it.visibility = if (typing) android.view.View.GONE else android.view.View.VISIBLE }
            descriptions.forEach { it.visibility = if (typing) android.view.View.GONE else android.view.View.VISIBLE }
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)); insets
        }
        setContentView(scroll)
    }
    override fun onDestroy() { browser?.destroy(); browser = null; super.onDestroy() }
}
