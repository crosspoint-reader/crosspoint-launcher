package com.crosspoint.launcher.data

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.AtomicFile
import android.util.Base64
import com.crosspoint.launcher.model.OpdsServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

class OpdsLoginRequired(val server: OpdsServer, val resource: String, val documentUrl: String, val document: JSONObject) :
    Exception(document.optString("description").ifBlank { "This catalog requires sign-in" })

/** One transport for feeds, details, covers and acquisitions. Credentials never follow CDN links. */
class OpdsHttp(private val context: Context) {
    private fun key(server: OpdsServer) = MessageDigest.getInstance("SHA-256")
        .digest("${server.id}\n${server.url}\n${server.username}\n${server.password}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun tokenFile(server: OpdsServer) = AtomicFile(File(context.noBackupFilesDir, "opds-${key(server)}.json"))
    private fun token(server: OpdsServer) = runCatching { tokenFile(server).openRead().use { OpdsJson.document(String(read(it, 64 * 1024))) } }.getOrNull()

    fun clear(server: OpdsServer) { tokenFile(server).delete(); basicSessions.remove(key(server)) }

    fun open(server: OpdsServer, address: String, accept: String = ACCEPT): HttpURLConnection {
        var url = address
        var authAttempt = 0
        var redirects = 0
        while (true) {
            val conn = connection(url)
            conn.setRequestProperty("Accept", accept)
            if (sameOrigin(server.url, url)) {
                // Bearer tokens only travel over HTTPS. Basic is sent only once a 401 asked for it:
                // sending it preemptively breaks OAuth-only servers.
                val bearer = if (URL(url).protocol == "https") token(server)?.optString("access_token").orEmpty() else ""
                val credentials = basicSessions[key(server)]
                if (bearer.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
                else if (credentials != null) {
                    val encoded = Base64.encodeToString("${credentials.first}:${credentials.second}".toByteArray(), Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $encoded")
                }
            }
            val status = try { conn.responseCode } catch (e: Exception) { conn.disconnect(); throw e }
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                check(++redirects <= 5 && !location.isNullOrBlank()) { "Invalid or excessive catalog redirects" }
                val next = OpdsRepository.resolveUrl(url, location)
                check(!(URL(url).protocol == "https" && URL(next).protocol != "https")) { "Insecure catalog redirect" }
                url = next
                continue
            }
            if (status == 401 || conn.contentType.orEmpty().substringBefore(';') == "application/opds-authentication+json") {
                val header = conn.getHeaderField("Link").orEmpty()
                val body = try { (if (status == 401) conn.errorStream else conn.inputStream)?.use { read(it, 64 * 1024) } }
                    finally { conn.disconnect() }
                if (!sameOrigin(server.url, url)) error("Sign-in required on ${URL(url).host}; add it as a separate catalog")
                val firstAttempt = authAttempt++ == 0
                if (firstAttempt && refresh(server)) continue
                var documentUrl = url
                var doc = runCatching { OpdsJson.document(String(body ?: byteArrayOf())) }.getOrNull()
                if (doc?.has("authentication") != true) {
                    val authLink = Regex("<([^>]+)>[^,]*").findAll(header).firstOrNull {
                        it.value.contains("http://opds-spec.org/auth/document") || it.value.contains("application/opds-authentication+json")
                    }?.groupValues?.get(1)
                    if (authLink != null) {
                        documentUrl = OpdsRepository.resolveUrl(url, authLink)
                        val auth = connection(documentUrl)
                        try {
                            check(auth.responseCode in 200..299) { "Could not fetch authentication information" }
                            doc = OpdsJson.document(String(auth.inputStream.use { read(it, 64 * 1024) }))
                        } finally { auth.disconnect() }
                    }
                }
                if (doc?.has("authentication") != true) doc = JSONObject().put("title", server.name)
                    .put("authentication", org.json.JSONArray().put(JSONObject().put("type", "http://opds-spec.org/auth/basic")))
                val challenge = OpdsLoginRequired(server, address, documentUrl, doc!!)
                // Like the reader firmware: try the catalog's saved credentials once before prompting.
                if (firstAttempt && server.username.isNotBlank() && server.password.isNotBlank() &&
                    runCatching { signIn(challenge, server.username, server.password) }.isSuccess) continue
                throw challenge
            }
            if (status !in 200..299) {
                val message = try { conn.errorStream?.use { read(it, 64 * 1024) }?.let { bytes ->
                    runCatching { OpdsJson.document(String(bytes)).optString("detail") }.getOrNull()
                } } finally { conn.disconnect() }
                error(message?.takeIf { it.isNotBlank() } ?: "Catalog returned HTTP $status")
            }
            return conn
        }
    }

    fun signIn(challenge: OpdsLoginRequired, username: String, password: String) {
        require(username.isNotBlank() && password.isNotBlank()) { "Enter your login and password" }
        val secure = URL(challenge.resource).protocol == "https"
        val flows = OpdsJson.objects(challenge.document.optJSONArray("authentication"))
        fun flow(type: String) = flows.firstOrNull { it.optString("type") == "http://opds-spec.org/auth/$type" }
        fun links(flow: JSONObject) = OpdsJson.links(flow.optJSONArray("links"), challenge.documentUrl)
        flow("oauth/password")?.takeIf { secure }?.let { flow ->
            val links = links(flow)
            val endpoint = links.firstOrNull { it.hasRel("authenticate") }?.href ?: error("Missing sign-in endpoint")
            val response = post(endpoint, mapOf("grant_type" to "password", "username" to username, "password" to password))
            saveToken(challenge.server, response, links.firstOrNull { it.hasRel("refresh") }?.href ?: endpoint)
            return
        }
        if (flow("basic") != null) {
            tokenFile(challenge.server).delete()
            basicSessions[key(challenge.server)] = username to password
            return
        }
        // Plain-HTTP catalogs (e.g. calibre-web on a LAN) can use Basic; token flows need HTTPS.
        require(secure) { "Catalog sign-in requires HTTPS" }
        val implicit = flow("oauth/implicit") ?: error("This catalog advertises no supported sign-in method")
        val links = links(implicit)
        val endpoint = links.firstOrNull { it.hasRel("authenticate") }?.href ?: error("Missing sign-in endpoint")
        saveCallback(challenge, implicitLogin(implicitUrl(endpoint), username, password), null,
            links.firstOrNull { it.hasRel("refresh") }?.href.orEmpty())
    }

    /** Validates an opds://authorize callback and stores its token. */
    fun saveCallback(challenge: OpdsLoginRequired, callback: String, state: String?, refreshUrl: String) {
        val raw = Uri.parse(callback)
        require(raw.scheme == "opds" && raw.host == "authorize") { "Invalid login callback" }
        val values = Uri.parse("https://callback/?" + raw.encodedQuery.orEmpty() + "&" + raw.encodedFragment.orEmpty())
        // Spec says the callback carries the document id, but Cantook-style providers omit it; validate whenever both exist.
        val id = challenge.document.optString("id")
        require(id.isBlank() || values.getQueryParameter("id")?.let { it == id } != false) { "Login belongs to a different catalog" }
        // OPDS shared-client providers may omit state; validate it whenever returned.
        require(state == null || values.getQueryParameter("state")?.let { it == state } != false) { "Invalid login state" }
        require(values.getQueryParameter("error") == null) { values.getQueryParameter("error").orEmpty() }
        saveToken(challenge.server, JSONObject().put("access_token", values.getQueryParameter("access_token"))
            .put("token_type", values.getQueryParameter("token_type") ?: "bearer")
            .put("refresh_token", values.getQueryParameter("refresh_token") ?: ""), refreshUrl)
    }

    /**
     * Browserless OAuth implicit grant: follows the login chain (including federated SSO hosts), fills each
     * HTML login form with the credentials and returns the opds://authorize callback URL.
     */
    private fun implicitLogin(start: String, username: String, password: String): String {
        val cookies = CookieManager()
        var url = start
        var body: String? = null
        var passwordSent = false
        repeat(10) {
            require(URL(url).protocol == "https") { "Catalog sign-in requires HTTPS" }
            val conn = connection(url)
            try {
                val uri = URI(url)
                cookies.get(uri, emptyMap()).forEach { (name, values) -> values.forEach { conn.addRequestProperty(name, it) } }
                body?.let { form ->
                    conn.requestMethod = "POST"; conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(form.toByteArray()) }
                }
                val status = conn.responseCode
                cookies.put(uri, conn.headerFields)
                body = null
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location") ?: error("Invalid sign-in redirect")
                    if (location.startsWith("opds://")) return location
                    url = OpdsRepository.resolveUrl(url, location)
                } else {
                    check(status == 200) { "Catalog sign-in failed ($status)" }
                    val form = loginForm(String(conn.inputStream.use { read(it, 256 * 1024) }), username, password)
                        ?: error("Catalog sign-in page has no login form")
                    // The password page reappearing after we submitted it means the credentials were rejected.
                    check(!(form.hasPassword && passwordSent)) { "Login or password rejected" }
                    passwordSent = passwordSent || form.hasPassword
                    if (form.action.isNotBlank()) url = OpdsRepository.resolveUrl(url, form.action)
                    body = form.body
                }
            } finally { conn.disconnect() }
        }
        error("Catalog sign-in took too many steps")
    }

    fun saveToken(server: OpdsServer, response: JSONObject, refreshUrl: String = "") {
        require(response.optString("token_type", "bearer").equals("bearer", true)) { "Unsupported catalog token type" }
        val access = response.optString("access_token")
        require(access.isNotBlank() && access != "null" && access.length <= 16_384 && access.none { it == '\r' || it == '\n' }) { "Invalid catalog access token" }
        val value = JSONObject().put("access_token", access).put("refresh_token", response.optString("refresh_token"))
            .put("refresh_url", refreshUrl)
        val file = tokenFile(server)
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    private fun refresh(server: OpdsServer): Boolean {
        val old = token(server) ?: return false
        val refresh = old.optString("refresh_token")
        val url = old.optString("refresh_url")
        if (refresh.isBlank() || url.isBlank()) { tokenFile(server).delete(); return false }
        return runCatching {
            val response = post(url, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
            if (!response.has("refresh_token")) response.put("refresh_token", refresh)
            saveToken(server, response, url)
            true
        }.getOrElse { tokenFile(server).delete(); false }
    }

    private fun post(url: String, fields: Map<String, String>): JSONObject {
        require(URL(url).protocol == "https") { "Catalog sign-in requires HTTPS" }
        val conn = connection(url)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { out -> out.write(fields.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }.toByteArray()) }
            check(conn.responseCode in 200..299) { "Catalog sign-in failed (${conn.responseCode})" }
            return OpdsJson.document(String(conn.inputStream.use { read(it, 64 * 1024) }))
        } finally { conn.disconnect() }
    }

    private fun connection(address: String): HttpURLConnection {
        val url = URL(address)
        require(url.protocol in listOf("http", "https") && url.userInfo == null) { "Unsupported catalog URL" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "CrossPointLauncher/1.0")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }
    }

    class LoginForm(val action: String, val body: String, val hasPassword: Boolean)

    companion object {
        /** Adds the OPDS shared-client parameters an implicit grant needs, keeping any the server already set. */
        fun implicitUrl(endpoint: String, state: String? = null): String {
            val uri = Uri.parse(endpoint)
            val builder = uri.buildUpon()
            mapOf("response_type" to "token", "client_id" to "http://opds-spec.org/auth/client",
                "redirect_uri" to "opds://authorize/", "state" to state).forEach { (name, value) ->
                if (value != null && uri.getQueryParameter(name) == null) builder.appendQueryParameter(name, value)
            }
            return builder.build().toString()
        }

        /**
         * First form with a password input, else the first identifier-only step (email page of a two-step
         * login) whose field clearly names a login, so a search box is never mistaken for one.
         */
        fun loginForm(html: String, username: String, password: String): LoginForm? {
            val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            var identity: LoginForm? = null
            for (form in Regex("<form\\b([^>]*)>(.*?)(?:</form\\s*>|$)", options).findAll(html)) {
                val fields = ArrayList<Pair<String, String>>()
                var user = false; var secret = false; var strong = false
                for (input in Regex("<input\\b([^>]*)>", options).findAll(form.groupValues[2])) {
                    val tag = input.groupValues[1]
                    val name = attr(tag, "name")?.takeIf { it.isNotEmpty() } ?: continue
                    when (val type = attr(tag, "type")?.lowercase() ?: "text") {
                        "password" -> { fields += name to password; secret = true }
                        "text", "email" -> if (!user) {
                            fields += name to username; user = true
                            strong = type == "email" || attr(tag, "autocomplete").equals("username", true) ||
                                listOf(name, attr(tag, "id").orEmpty()).any { value -> LOGIN_HINTS.any { value.contains(it, true) } }
                        }
                        "hidden" -> fields += name to attr(tag, "value").orEmpty()
                        "checkbox", "radio" -> if (Regex("(^|\\s)checked(\\s|=|/|$)", RegexOption.IGNORE_CASE).containsMatchIn(tag))
                            fields += name to (attr(tag, "value") ?: "on")
                    }
                }
                val result = LoginForm(attr(form.groupValues[1], "action").orEmpty(), fields.joinToString("&") {
                    URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8")
                }, secret)
                if (secret) return result
                if (user && strong && identity == null) identity = result
            }
            return identity
        }

        private val LOGIN_HINTS = listOf("email", "user", "login", "ident", "account", "signin")

        private fun attr(tag: String, name: String): String? {
            val match = Regex("(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return null
            return Html.fromHtml(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString()
        }

        private val basicSessions = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        const val ACCEPT = "application/opds+json, application/opds-publication+json, application/atom+xml;q=0.9, application/xml;q=0.8"
        fun sameOrigin(first: String, second: String): Boolean = runCatching {
            val a = URL(first); val b = URL(second)
            a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) &&
                (if (a.port == -1) a.defaultPort else a.port) == (if (b.port == -1) b.defaultPort else b.port)
        }.getOrDefault(false)

        fun read(input: InputStream, limit: Int = 2 * 1024 * 1024): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= limit) { "Catalog response exceeds the low-memory limit ($limit bytes)" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
