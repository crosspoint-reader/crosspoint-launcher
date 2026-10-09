package com.crosspoint.launcher.ui.opds

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.crosspoint.launcher.OpdsAccountActivity
import com.crosspoint.launcher.data.*
import com.crosspoint.launcher.model.*
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.components.HeaderIconButton
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import kotlinx.coroutines.*

private data class Page(val path: String, val search: String?)

@Composable
fun OpdsBrowserScreen(servers: List<OpdsServer>, repo: OpdsRepository, onBooksChanged: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = CrossPointTheme.colors
    val metrics = CrossPointTheme.metrics
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(servers.singleOrNull()) }
    val history = remember { mutableStateListOf<Page>() }
    var feed by remember { mutableStateOf<OpdsFeed?>(null) }
    var book by remember { mutableStateOf<OpdsEntry?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var searching by remember { mutableStateOf(false) }
    val searchValues = remember { mutableStateMapOf<String, String>() }
    var challenge by remember { mutableStateOf<OpdsLoginRequired?>(null) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { challenge = null; retry?.invoke() }
    }

    fun work(message: String, action: suspend () -> Unit) {
        job?.cancel()
        retry = { work(message, action) }
        error = null; challenge = null; busy = message
        job = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: OpdsLoginRequired) { challenge = e; error = e.message }
            catch (e: Exception) { error = e.message ?: "Catalog request failed" }
            finally { if (currentCoroutineContext()[Job] == job) busy = null }
        }
    }
    fun external(href: String) {
        require(Uri.parse(href).scheme in listOf("https", "http")) { "Unsupported catalog link" }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
    }
    fun load(path: String, push: Boolean) {
        val selected = server ?: return
        searching = false
        work("Loading…") {
            val result = repo.fetchFeed(selected, path)
            val page = Page(result.url.ifBlank { path }, result.searchTemplate ?: history.lastOrNull()?.search)
            if (push || history.isEmpty()) history.add(page) else history[history.lastIndex] = page
            feed = result; book = null
        }
    }
    fun back() {
        job?.cancel(); job = null; busy = null; error = null; challenge = null
        when {
            searching -> searching = false
            book != null -> book = null
            history.size > 1 -> { history.removeAt(history.lastIndex); load(history.last().path, false) }
            server != null && servers.size > 1 -> { server = null; history.clear(); feed = null }
            else -> onBack()
        }
    }
    fun follow(link: OpdsLink) {
        if (link.mediaType.contains("html")) {
            try { external(link.href) } catch (e: Exception) { error = e.message }
        } else load(link.href, true)
    }
    fun acquire(entry: OpdsEntry, link: OpdsLink) {
        val selected = server ?: return
        work("Downloading ${entry.title}…") {
            val result = repo.acquire(selected, entry, link) { done, total ->
                busy = if (total > 0) "Downloading… ${done * 100 / total}%" else "Downloading… ${done / 1024} KB"
            }
            result.publication?.let { book = it }
            result.externalUrl?.let { external(it) }
            result.file?.let { file ->
                onBooksChanged()
                Toast.makeText(context, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
                if (file.extension != "epub") {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, result.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: android.content.ActivityNotFoundException) { error = "Saved ${file.name}. Install a compatible ${if (file.extension == "lcpl") "Readium LCP" else "PDF"} reader to open it." }
                }
            }
        }
    }
    val selectedServer = server
    val selectedBook = book
    // Paged rows can recompose after these states change; capture the values for this frame.
    val busyMessage = busy
    val failureMessage = error
    val loginChallenge = challenge
    LaunchedEffect(selectedServer) { if (selectedServer != null && history.isEmpty()) load(selectedServer.url, true) }
    BackHandler { back() }

    Column(Modifier.fillMaxSize().imePadding().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())) {
        ScreenHeader(book?.title ?: feed?.title ?: server?.name ?: "OPDS Browser", { back() }) {
            if (history.lastOrNull()?.search != null && book == null) HeaderIconButton("search", onClick = { searching = !searching })
        }
        if (busyMessage != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BodyText(busyMessage, size = 18, maxLines = 3)
                    Pressable(onClick = { job?.cancel(); job = null; busy = null }) {
                        Box(Modifier.height(48.dp).width(112.dp), contentAlignment = Alignment.Center) { BodyText("Cancel", size = 16) }
                    }
                }
            }
        } else ScrollList(Modifier.fillMaxSize(), contentPadding = PaddingValues(metrics.contentSidePadding), verticalArrangement = Arrangement.spacedBy(6.dp), state = remember(feed?.url, book?.href, searching) { androidx.compose.foundation.lazy.LazyListState() }) {
            if (selectedServer == null) {
                itemsIndexed(servers) { _, s -> Action(s.name.ifBlank { s.url }) { server = s } }

            } else if (failureMessage != null) {
                item {
                    BodyText(failureMessage, size = 16, maxLines = 4)
                    val auth = loginChallenge
                    if (auth != null) Action("Sign in to ${auth.server.name}") {
                        login.launch(Intent(context, OpdsAccountActivity::class.java)
                            .putExtra("id", auth.server.id).putExtra("name", auth.server.name).putExtra("server", auth.server.url)
                            .putExtra("username", auth.server.username).putExtra("password", auth.server.password)
                            .putExtra("resource", auth.resource).putExtra("documentUrl", auth.documentUrl).putExtra("document", auth.document.toString()))
                    } else Action("Retry") { retry?.invoke() }
                    Action("Back") { back() }
                }
            } else if (searching) {
                val template = history.lastOrNull()?.search.orEmpty()
                val fields = Regex("\\{([^}]+)\\}").findAll(template).flatMap {
                    it.groupValues[1].trimStart('?', '&', '+', '#', '/', '.', ';').split(',').asSequence()
                }.map { it.removeSuffix("?").removeSuffix("*").substringBefore(':') }
                    .filter { it !in listOf("count", "startIndex", "startPage", "language", "inputEncoding", "outputEncoding") }.toList().distinct().ifEmpty { listOf("searchTerms") }
                fields.forEach { field -> item {
                    BodyText(if (field in listOf("q", "query", "searchTerms")) "Search catalog" else field.replaceFirstChar(Char::uppercase), size = 14)
                    BasicTextField(searchValues[field].orEmpty(), { searchValues[field] = it }, singleLine = true,
                        textStyle = TextStyle(color = colors.onBackground, fontSize = 18.sp), cursorBrush = SolidColor(colors.onBackground),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = field })
                } }
                item { Action("Search") {
                    val page = history.lastOrNull() ?: return@Action
                    val values = searchValues.filterValues { it.isNotBlank() }.toMap()
                    if (values.isNotEmpty()) work("Searching…") {
                        val terms = values["query"] ?: values["q"] ?: values["searchTerms"].orEmpty()
                        val path = repo.resolveSearchPath(selectedServer, page.path, template, terms, values)
                        val result = repo.fetchFeed(selectedServer, path)
                        history.add(Page(result.url, result.searchTemplate ?: template)); feed = result; book = null; searching = false
                    }
                } }
            } else if (selectedBook != null) {
                val entry = selectedBook
                item { BookInfo(entry, selectedServer, repo) }
                val description = Html.fromHtml(entry.description, Html.FROM_HTML_MODE_COMPACT).toString()
                items(description.chunked(280)) { part -> BodyText(part, size = 15, maxLines = 16) }
                val actions = entry.links.filter { it.acquisition != null }
                itemsIndexed(actions) { _, link ->
                    Column {
                        val details = acquisitionDetails(link)
                        if (details.isNotBlank()) BodyText(details, muted = true, size = 13)
                        Action(acquisitionLabel(link)) { acquire(entry, link) }
                    }
                }
                itemsIndexed(entry.links.filter { it.acquisition == null && !it.hasRel("self") && !it.hasRel("http://opds-spec.org/image") && !it.hasRel("http://opds-spec.org/image/thumbnail") }) { _, link ->
                    Action(link.title.ifBlank { link.rels.firstOrNull()?.substringAfterLast('/') ?: "Related catalog" }) { follow(link) }
                }
                if (actions.isEmpty()) item {
                    BodyText("No download is offered. Check the catalog links or your shelf.", muted = true, size = 14)
                    Action("Open publication in browser / compatible app") {
                        try { external(entry.selfHref ?: entry.href) } catch (e: Exception) { error = e.message }
                    }
                }
                entry.selfHref?.let { item { Action("Refresh availability") { work("Loading details…") { book = repo.publication(selectedServer, entry) } } } }
            } else {
                val current = feed
                if (current != null) {
                    if (current.currentPage > 0) item { BodyText("Page ${current.currentPage}" + if (current.totalPages > 0) " of ${current.totalPages}" else "", muted = true, size = 13) }
                    current.firstUrl?.let { item { Action("First page") { load(it, false) } } }
                    current.prevUrl?.let { item { Action("Previous page") { load(it, false) } } }
                    itemsIndexed(current.entries) { index, entry ->
                        Column {
                            if (entry.heading.isNotBlank() && (index == 0 || current.entries[index - 1].heading != entry.heading))
                                BodyText(entry.heading, weight = FontWeight.Bold, size = 16, modifier = Modifier.padding(top = 12.dp))
                            Pressable(onClick = {
                                if (entry.type == OpdsEntry.Type.NAVIGATION) follow(entry.links.firstOrNull() ?: OpdsLink(entry.href))
                                else { book = entry; if (entry.selfHref != null) work("Loading details…") { book = repo.publication(selectedServer, entry) } }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LucideIcon(if (entry.type == OpdsEntry.Type.BOOK) "book" else "folder", Modifier.size(22.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        BodyText(entry.title, size = 16, maxLines = 2)
                                        if (entry.author.isNotBlank()) BodyText(entry.author, muted = true, size = 12)
                                        if (entry.detail.isNotBlank()) BodyText(entry.detail, muted = true, size = 12)
                                    }
                                    LucideIcon("chevron_right", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    current.nextUrl?.let { item { Action("Next page") { load(it, false) } } }
                    current.lastUrl?.let { item { Action("Last page") { load(it, false) } } }
                    if (current.entries.isEmpty()) item { BodyText("No entries", muted = true, size = 14) }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        BodyText(label, weight = FontWeight.SemiBold, size = 16, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
    }
}

@Composable
private fun BookInfo(entry: OpdsEntry, server: OpdsServer, repo: OpdsRepository) {
    var cover by remember(entry.thumbnailHref) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.thumbnailHref) {
        entry.thumbnailHref?.let { href ->
            try { cover = withContext(Dispatchers.IO) {
                val file = repo.cover(server, href)
                try { CoverImages.decode { file.inputStream() } } finally { file.delete() }
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Cover failure does not block reading. */ }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cover?.let { Image(it.asImageBitmap(), contentDescription = "Cover of ${entry.title}", modifier = Modifier.height(96.dp)) }
        BodyText(entry.title, weight = FontWeight.Bold, size = 20, maxLines = 2)
        if (entry.author.isNotBlank()) BodyText(entry.author, size = 16, maxLines = 2)
        if (entry.metadata.isNotBlank()) BodyText(entry.metadata, muted = true, size = 14, maxLines = 2)
    }
}

private fun acquisitionLabel(link: OpdsLink): String {
    val action = link.title.ifBlank { when (link.acquisition) {
        "borrow" -> "Borrow / reserve"; "buy" -> "Buy"; "subscribe" -> "Subscribe"; "preview" -> "Read sample"; else -> "Download"
    } }
    fun formats(array: org.json.JSONArray?): List<String> = OpdsJson.objects(array).flatMap {
        listOf(it.optString("type")) + formats(it.optJSONArray("child"))
    }
    val types = (listOf(link.mediaType) + formats(link.properties.optJSONArray("indirectAcquisition")))
        .map { when { it == EPUB_MIME -> "EPUB"; it.contains("lcp") -> "LCP · external reader"; it == "application/pdf" -> "PDF"; else -> "" } }
        .filter { it.isNotBlank() }.distinct()
    return action + if (types.isEmpty()) "" else " · " + types.joinToString(" / ")
}

private fun acquisitionDetails(link: OpdsLink): String = with(link.properties) {
    listOfNotNull(
        optJSONObject("price")?.let { "${it.optString("value")} ${it.optString("currency")}" },
        optJSONObject("availability")?.let { listOf(it.optString("state"), it.optString("since").takeIf(String::isNotBlank)?.let { date -> "From $date" }, it.optString("until").takeIf(String::isNotBlank)?.let { date -> "Until $date" }).filterNotNull().filter(String::isNotBlank).joinToString(" · ") },
        optJSONObject("copies")?.let { "Copies: ${it.optInt("available")} available / ${it.optInt("total")}" },
        optJSONObject("holds")?.let { "Holds: ${it.optInt("total")}" + if (it.has("position")) " · Your position: ${it.optInt("position")}" else "" }
    ).joinToString(" · ")
}
