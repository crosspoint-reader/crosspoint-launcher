package com.crosspoint.launcher

import android.content.Context
import android.content.ContextWrapper
import com.crosspoint.launcher.data.*
import com.crosspoint.launcher.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.zip.*

/** Runs under IntegrationChecks' in-process HTTP transport; no live catalog or account needed. */
object OpdsChecks {
    private var route: (HttpURLConnection, ByteArray) -> Response = { _, _ -> error("Unexpected OPDS request") }
    data class Response(val code: Int = 200, val body: ByteArray = "{}".toByteArray(), val type: String = "application/opds+json", val location: String? = null)
    fun connection(url: URL): URLConnection = object : HttpURLConnection(url) {
        val output = ByteArrayOutputStream()
        val response by lazy { route(this, output.toByteArray()) }
        override fun getResponseCode() = response.code
        override fun getContentType() = response.type
        override fun getContentLengthLong() = response.body.size.toLong()
        override fun getInputStream() = response.body.inputStream()
        override fun getErrorStream() = response.body.inputStream()
        override fun getOutputStream() = output
        override fun getHeaderField(name: String?) = if (name == "Location") response.location else null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
    fun prepareUiCheck() {
        var requests = 0
        route = { _, _ ->
            Thread.sleep(800)
            requests++
            if (requests % 2 == 1) Response(500)
            else Response(body = """{"metadata":{"title":"UI Catalog"},"navigation":[]}""".toByteArray())
        }
    }
    fun run(base: Context) = runBlocking {
        val sandbox = File(base.cacheDir, "opds-checks").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = sandbox }
        val server = OpdsServer("test-opds", "Catalog", "https://opds.test/catalog/index.json", "reader", "secret")
        val repo = OpdsRepository(context)
        val publication = """{"metadata":{"title":{"en":"A book"},"author":[{"name":"Writer"}],"description":"<p>A story</p>","language":"en"},"images":[{"href":"cover.jpg","type":"image/jpeg","width":180}],"links":[{"rel":["self"],"href":"book.json","type":"application/opds-publication+json"},{"rel":"http://opds-spec.org/acquisition/borrow","href":"borrow","type":"application/opds-publication+json","properties":{"price":{"currency":"USD","value":2.5},"availability":{"state":"available"},"copies":{"available":1,"total":2},"holds":{"total":3,"position":2},"indirectAcquisition":[{"type":"application/epub+zip"}]}},{"rel":"http://opds-spec.org/acquisition/open-access","href":"book.epub","type":"application/epub+zip"}]}"""
        check(runCatching { OpdsJson.document("{\"nested\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}") }.isFailure)
        val catalog = """{"metadata":{"title":"Library","currentPage":2,"itemsPerPage":10,"numberOfItems":21},"links":[{"rel":"next","href":"?page=3"},{"rel":"first","href":"?page=1"},{"rel":"last","href":"?page=3"},{"rel":"search","href":"search{?query,title,author}","templated":true},{"rel":"http://opds-spec.org/shelf","href":"shelf","title":"My shelf"}],"groups":[{"metadata":{"title":"Featured"},"publications":[$publication],"links":[{"rel":"self","href":"featured"}]}],"facets":[{"metadata":{"title":"Language"},"links":[{"rel":["self"],"href":"?lang=en","title":"English","properties":{"numberOfItems":21}}]}]}"""
        route = { conn, _ ->
            check(conn.getRequestProperty("Accept").contains("application/opds+json"))
            // Basic only after a bare 401 challenge, then on every same-origin request.
            if (conn.getRequestProperty("Authorization") == null) Response(401, ByteArray(0), "text/plain")
            else if (conn.url.path.endsWith("book.json")) Response(body = publication.toByteArray()) else Response(body = catalog.toByteArray())
        }
        val feed = repo.fetchFeed(server, server.url)
        check(feed.entries.size == 4 && feed.currentPage == 2 && feed.totalPages == 3)
        check(feed.nextUrl == "https://opds.test/catalog/index.json?page=3")
        val book = feed.entries.first()
        check(book.heading == "Featured" && book.author == "Writer" && book.title == "A book")
        check(book.thumbnailHref == "https://opds.test/catalog/cover.jpg")
        check(book.links.first().acquisition == "download")
        check(book.links.first { it.acquisition == "borrow" }.properties.getJSONObject("holds").getInt("position") == 2)
        check(feed.entries[2].detail == "Selected · 21 titles")
        check(repo.publication(server, book).description == "<p>A story</p>")
        check(repo.resolveSearchPath(server, feed.url, feed.searchTemplate!!, "a & b", mapOf("author" to "A/B")) == "https://opds.test/catalog/search?query=a%20%26%20b&author=A%2FB")
        check(OpdsRepository.encodeSearch("https://opds.test/search?q={searchTerms}&n={count?}&unknown={unused?}", "one two") == "https://opds.test/search?q=one%20two&n=50&unknown=")
        check(OpdsJson.expandSearch("{+path}{?query}", mapOf("path" to "/book/a", "query" to "ä")) == "/book/a?query=%C3%A4")
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Old catalog</title><link rel="next" href="?page=2"/><link rel="search" type="application/opensearchdescription+xml" href="search.xml"/><entry><title>Legacy EPUB</title><author><name>Author</name></author><summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Description</p></div></summary><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="old.epub"/></entry></feed>"""
        val old = OpdsXml.parse(atom.byteInputStream(), server.url)
        check(old.entries.single().href == "https://opds.test/catalog/old.epub" && old.entries.single().description == "Description")
        check(old.entries.single().links.single().acquisition == "acquisition")
        route = { _, _ -> Response(body = """<OpenSearchDescription><Url type="application/atom+xml" template="find?q={searchTerms}&amp;n={count?}"/></OpenSearchDescription>""".toByteArray(), type = "application/xml") }
        check(repo.resolveSearchPath(server, old.url, old.searchTemplate!!, "word") == "https://opds.test/catalog/find?q=word&n=50")

        // Never forward Basic/Bearer credentials to a CDN, and refuse HTTPS downgrade.
        var calls = 0
        route = { conn, _ ->
            calls++
            if (conn.url.host == "opds.test") Response(302, location = "https://opds-cdn.test/book")
            else { check(conn.getRequestProperty("Authorization") == null); Response() }
        }
        repo.http.open(server, server.url).disconnect(); check(calls == 2)
        route = { _, _ -> Response(302, location = "http://opds-cdn.test/book") }
        check(runCatching { repo.http.open(server, server.url) }.isFailure)
        route = { _, _ -> Response(body = ByteArray(2 * 1024 * 1024 + 1)) }
        check(runCatching { repo.fetchFeed(server, server.url) }.isFailure)

        val auth = JSONObject("""{"id":"catalog-account","title":"Sign in","authentication":[{"type":"http://opds-spec.org/auth/oauth/password","links":[{"rel":"authenticate","href":"/token"},{"rel":"refresh","href":"/refresh"}]}]}""")
        route = { _, _ -> Response(401, auth.toString().toByteArray(), "application/opds-authentication+json") }
        val challenge = runCatching { repo.fetchFeed(server, server.url) }.exceptionOrNull() as OpdsLoginRequired
        route = { conn, body ->
            check(conn.requestMethod == "POST" && conn.url.path == "/token")
            check(String(body).contains("password=secret"))
            Response(body = """{"access_token":"expired","refresh_token":"refresh","token_type":"Bearer"}""".toByteArray())
        }
        repo.http.signIn(challenge, "reader", "secret")
        calls = 0
        route = { conn, body ->
            calls++
            when {
                conn.url.path == "/refresh" -> {
                    check(conn.getRequestProperty("Authorization") == null)
                    check(String(body) == "grant_type=refresh_token&refresh_token=refresh")
                    Response(body = """{"access_token":"valid","token_type":"bearer"}""".toByteArray())
                }
                conn.getRequestProperty("Authorization") == "Bearer expired" -> Response(401, auth.toString().toByteArray())
                else -> { check(conn.getRequestProperty("Authorization") == "Bearer valid"); Response(body = catalog.toByteArray()) }
            }
        }
        check(repo.fetchFeed(server, server.url).title == "Library" && calls == 3)
        check(runCatching { repo.http.saveToken(server, JSONObject("""{"access_token":null}""")) }.isFailure)
        repo.http.clear(server)
        val basic = OpdsLoginRequired(server, server.url, server.url, JSONObject("""{"authentication":[{"type":"http://opds-spec.org/auth/basic"}]}"""))
        repo.http.signIn(basic, "newuser", "newpass")
        route = { conn, _ -> check(conn.getRequestProperty("Authorization") == "Basic bmV3dXNlcjpuZXdwYXNz"); Response() }
        OpdsHttp(context).open(server, server.url).disconnect() // Login survives activity/repository instances.
        repo.http.clear(server)

        // Plain-HTTP catalogs can use Basic (LAN calibre-web), but never receive a stored Bearer token.
        val lan = server.copy(id = "lan", url = "http://opds.test/catalog/index.json")
        route = { conn, _ -> when (conn.getRequestProperty("Authorization")) {
            "Basic cmVhZGVyOnNlY3JldA==" -> Response(body = catalog.toByteArray())
            null -> Response(401, ByteArray(0), "text/plain")
            else -> error("Unexpected credentials over HTTP")
        } }
        check(repo.fetchFeed(lan, lan.url).title == "Library")
        repo.http.saveToken(lan, JSONObject("""{"access_token":"leak"}"""))
        repo.http.open(lan, lan.url).disconnect()
        repo.http.clear(lan)

        // Saved catalog credentials sign in automatically on a 401, as on the reader firmware.
        route = { conn, body -> when {
            conn.url.path == "/token" -> { check(String(body).contains("username=reader")); Response(body = """{"access_token":"auto"}""".toByteArray()) }
            conn.getRequestProperty("Authorization") == "Bearer auto" -> Response(body = catalog.toByteArray())
            else -> Response(401, auth.toString().toByteArray(), "application/opds-authentication+json")
        } }
        check(repo.fetchFeed(server, server.url).title == "Library")
        repo.http.clear(server)

        // Browserless implicit grant: skips the search form, submits the email step, then the password step.
        val implicit = """{"authentication":[{"type":"http://opds-spec.org/auth/oauth/implicit","links":[{"rel":"authenticate","href":"https://sso.opds.test/authorize?client_id=custom"}]}]}"""
        route = { conn, body -> when {
            conn.url.host == "sso.opds.test" && conn.url.path == "/authorize" -> {
                check(conn.url.query.contains("client_id=custom") && !conn.url.query.contains("opds-spec.org") && conn.url.query.contains("redirect_uri="))
                Response(body = """<form action="/search"><input name="q"></form><form method="post" action="/email"><input type="hidden" name="csrf" value="a&amp;b"><input type="email" name="login"></form>""".toByteArray(), type = "text/html")
            }
            conn.url.path == "/email" -> {
                check(String(body) == "csrf=a%26b&login=reader")
                Response(body = """<form method=post action=/password><input type=hidden name=user value=reader><input type=password name=pw><input type=checkbox name=remember checked></form>""".toByteArray(), type = "text/html")
            }
            conn.url.path == "/password" -> { check(String(body) == "user=reader&pw=secret&remember=on"); Response(302, location = "opds://authorize/#access_token=implicit&token_type=bearer") }
            conn.getRequestProperty("Authorization") == "Bearer implicit" -> Response(body = catalog.toByteArray())
            else -> Response(401, implicit.toByteArray(), "application/opds-authentication+json")
        } }
        check(repo.fetchFeed(server, server.url).title == "Library")
        repo.http.clear(server)

        val epub = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container/>".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        route = { _, _ -> Response(body = epub, type = EPUB_MIME) }
        val testBook = book.copy(title = "OPDS-check-${System.nanoTime()}", author = "")
        val download = book.links.first { it.acquisition == "download" }
        val first = repo.acquire(server, testBook, download) { _, _ -> }.file!!
        try {
            val second = repo.acquire(server, testBook, download) { _, _ -> }.file!!
            try { check(first != second && first.readBytes().contentEquals(epub)) } finally { second.delete() }
            val before = first.parentFile!!.list()!!.toSet()
            route = { _, _ -> Response(body = "Not a ZIP".toByteArray(), type = EPUB_MIME) }
            check(runCatching { repo.acquire(server, testBook, download) { _, _ -> } }.isFailure)
            check(first.parentFile!!.list()!!.toSet() == before)
            route = { _, _ -> Response(body = epub, type = EPUB_MIME) }
            check(runCatching { repo.acquire(server, testBook, download) { _, _ -> throw kotlinx.coroutines.CancellationException() } }.isFailure)
            check(first.parentFile!!.list()!!.toSet() == before)
            route = { _, _ -> Response(body = publication.toByteArray(), type = "application/opds-publication+json") }
            check(repo.acquire(server, book, book.links.first { it.acquisition == "borrow" }) { _, _ -> }.publication?.title == "A book")
            route = { _, _ -> error("HTML purchases must open the browser without a request") }
            check(repo.acquire(server, book, OpdsLink("https://opds.test/buy", "text/html", listOf("buy"))) { _, _ -> }.externalUrl != null)
        } finally { first.delete(); repo.http.clear(server); sandbox.deleteRecursively() }
    }
}
