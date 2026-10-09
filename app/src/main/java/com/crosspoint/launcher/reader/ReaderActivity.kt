package com.crosspoint.launcher.reader

import android.app.AlertDialog
import com.crosspoint.launcher.ui.components.nativeScreenHeader
import com.crosspoint.launcher.ui.components.headerAction

import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.view.KeyEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Button
import android.view.Gravity
import com.crosspoint.launcher.AdaptiveMetrics
import com.crosspoint.launcher.R
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.crosspoint.launcher.data.SyncRepository
import com.crosspoint.launcher.model.Book
import java.io.File
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import android.text.Selection
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import com.crosspoint.launcher.data.SettingsRepository
import com.crosspoint.launcher.model.AppearanceMode
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import kotlinx.coroutines.flow.map
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.text.Layout
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.URLSpan
import android.view.HapticFeedbackConstants
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import com.crosspoint.launcher.reader.ReaderSettings.Companion as S
import com.crosspoint.launcher.util.ScreenRefresh
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import kotlin.math.abs

/** Text-first reader: no browser process, scripts, network resources, or whole-book layout. */
class ReaderActivity : ComponentActivity(), ReaderPanelHost {
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private var book: EpubBook? = null
    private var chapter = 0
    private var ready = false
    private var loadJob: Job? = null
    private var key = ""
    private val settings by lazy { ReaderSettings(prefs) }
    private lateinit var page: LinearLayout
    private lateinit var statusBar: ReaderStatusBar
    private lateinit var panelHost: ComposeView
    private lateinit var bookmarkButton: ImageButton
    private lateinit var notesButton: ImageButton
    private val panel = mutableStateOf<ReaderPanel?>(null)
    private var continuous = false
    private var turnsSinceRefresh = 0
    private var battery: Int? = null
    private var autoTurn = 0
    private var autoTurnJob: Job? = null
    private var document: String? = null
    private val sync by lazy { com.crosspoint.launcher.data.SyncRepository(applicationContext) }
    private var finished = false
    private var positionMap: EpubPosition? = null
    private var chapterIds: Map<String, Int> = emptyMap()
    private var pageBreaks: Set<Int> = emptySet()
    /** First layout line of each page in paged mode. */
    private var pages = IntArray(0)
    /** Text offset kept across relayouts (font size, margins…) until the reader turns the page, so they don't drift. */
    private var anchor: Int? = null
    private var loadFailed = false
    private lateinit var mask: View
    private lateinit var topMask: View
    private lateinit var spacer: View
    private var syncJob: Job? = null
    private var checkpointJob: Job? = null
    private var lastQueuedAt = 0L
    private var accountScope: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            visibility = View.GONE
        }
        // Page actions live in the reader's top bar; Chapters, Font and Settings in the bottom bar.
        controls.addView(nativeScreenHeader(this, "Opening Book…", { finish() }) {
            fun action(label: String, icon: Int, run: () -> Unit) = ImageButton(this@ReaderActivity).apply {
                contentDescription = label; setImageResource(icon); background = null
                setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { run() }
            }.also { addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
            notesButton = action("Footnotes", R.drawable.ic_file_text) { openPanel(ReaderPanel.Footnotes) }
            bookmarkButton = action("Bookmark Page", R.drawable.ic_bookmark) { toggleBookmark() }
        })
        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            visibility = View.GONE
            addView(View(this@ReaderActivity).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }, LinearLayout.LayoutParams(-1, resources.displayMetrics.density.toInt().coerceAtLeast(1)))
            addView(LinearLayout(this@ReaderActivity).apply {
                gravity = Gravity.CENTER
                fun icon(label: String, resource: Int, action: () -> Unit) {
                    addView(ImageButton(this@ReaderActivity).apply {
                        contentDescription = label
                        setImageResource(resource)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        val inset = (16 * resources.displayMetrics.density).toInt()
                        setPadding(inset, inset, inset, inset)
                        setOnClickListener { action() }
                    }, LinearLayout.LayoutParams(0, (64 * resources.displayMetrics.density).toInt(), 1f))
                }
                icon("Font", R.drawable.ic_type) { openPanel(ReaderPanel.Font) }
                icon("Chapters", R.drawable.ic_list_filter) { openPanel(ReaderPanel.Contents()) }
                icon("Settings", R.drawable.ic_settings) { openPanel(ReaderPanel.Settings) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        status = TextView(this).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(16, 4, 16, 4); text = "Opening book…" }
        controls.addView(status)
        text = TextView(this).apply {
            // Disable link activation: EPUB content must never launch remote resources; footnotes open from Chapters.
            linksClickable = false
            // Long press selects text with native handles; the toolbar offers the firmware's Look Up, Clip and Bookmark.
            setTextIsSelectable(true)
            // Side/page keys put Android in keyboard mode, where the focused text view would get a grey focus wash.
            defaultFocusHighlightEnabled = false
            revealOnFocusHint = false
            customSelectionActionModeCallback = selectionActions
        }
        // In paged mode an empty screen-tall spacer follows the text, so even a chapter's last page can scroll
        // exactly to its first line instead of clamping at the end and showing the previous page's cut-off tail.
        spacer = View(this)
        scroll = ReaderScroll().apply {
            isSmoothScrollingEnabled = false
            defaultFocusHighlightEnabled = false
            addView(LinearLayout(this@ReaderActivity).apply {
                orientation = LinearLayout.VERTICAL
                defaultFocusHighlightEnabled = false
                addView(text, LinearLayout.LayoutParams(-1, -2))
                addView(spacer, LinearLayout.LayoutParams(-1, 0))
            })
            setOnScrollChangeListener { _, _, _, _, _ -> updateFooter() }
        }
        statusBar = ReaderStatusBar(this)
        // Paged mode covers the partial line below each page, so pages always end on a whole line.
        mask = View(this).apply { visibility = View.GONE }
        // The chapter is one tall view, so a page's top margin would otherwise show the previous page's last line.
        topMask = View(this).apply { visibility = View.GONE }
        val viewport = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(mask, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
            addView(topMask, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
        }
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) sizeSpacer()
            if (ready && bottom - top != oldBottom - oldTop) { val offset = anchor ?: currentOffset(); anchor = offset; restoreOffset(offset) }
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(statusBar, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2))
        root.addView(bottomControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        selectionBar = buildSelectionBar()
        root.addView(selectionBar, FrameLayout.LayoutParams(-2, -2))
        // Font, Chapters and Settings use the launcher's own settings screens and theme.
        panelHost = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                val appearance by remember { SettingsRepository(applicationContext).settings.map { it.appearance } }.collectAsState(AppearanceMode.LIGHT)
                panel.value?.let { current ->
                    CrossPointTheme(appearance) {
                        AdaptiveMetrics(includeNavigationBars = false) {
                            androidx.compose.runtime.key(current) { ReaderPanelScreen(this@ReaderActivity, current, ::openPanel) }
                        }
                    }
                }
            }
        }
        root.addView(panelHost, FrameLayout.LayoutParams(-1, -1))
        applyTextSettings()
        applyDisplaySettings()
        setContentView(root)
        // Full-screen reading: the system status bar stays hidden; a swipe from the top edge shows it briefly.
        WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lifecycleScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    val path = intent.getStringExtra("book_path") ?: error("No book selected")
                    EpubBook(File(path))
                }
                book = opened
                key = opened.file.canonicalPath
                title = opened.title
                controls.findViewById<TextView>(android.R.id.title).text = opened.title
                // Hyphenation and line breaking follow the book's language, like the firmware, not the device's.
                if (opened.language.isNotEmpty()) text.textLocale = java.util.Locale.forLanguageTag(opened.language)
                chapter = (savedInstanceState?.getInt("chapter") ?: prefs.getInt("$key.chapter", 0))
                    .coerceIn(0, opened.chapters.lastIndex)
                val offset = savedInstanceState?.getInt("offset") ?: prefs.getInt("$key.offset", 0)
                // Like the firmware's progress.bin, the visible-text offset is the layout- and renderer-independent anchor.
                val visible = (savedInstanceState?.getInt("visible", -1) ?: prefs.getInt("$key.visible", -1)).takeIf { it >= 0 }
                showChapter(chapter, offset, savedInstanceState?.getBoolean("finished") ?: prefs.getBoolean("$key.finished", false), visible = visible)
                synchronizePosition()

            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e) }
        }
    }

    private fun showChapter(index: Int, offset: Int = 0, complete: Boolean = false, remote: SyncRepository.RemoteProgress? = null,
                            fraction: Double? = null, visible: Int? = null, save: Boolean = true, then: (() -> Unit)? = null) {
        val current = book ?: return
        if (index !in current.chapters.indices) return
        if (remote == null && save) savePosition()
        anchor = null // Navigation starts fresh; restyle() re-sets the anchor it relayouts around.
        pages = IntArray(0) // The old page table indexes lines of the outgoing text.
        loadJob?.cancel()
        ready = false
        loadFailed = false
        positionMap = null
        finished = complete
        status.text = "Loading chapter ${index + 1}…"
        text.text = ""
        loadJob = lifecycleScope.launch {
            try {
                chapter = index
                val metrics = resources.displayMetrics
                val spec = ReaderText.Page((scroll.width.takeIf { it > 0 } ?: metrics.widthPixels) - text.paddingLeft - text.paddingRight,
                    (scroll.height.takeIf { it > 0 } ?: metrics.heightPixels) - text.paddingTop - text.paddingBottom,
                    text.textSize, text.currentTextColor, settings.on(S.NIGHT_MODE), text.paint.measureText(" "), text.lineHeight)
                val (content, mapping, ids, breaks) = withContext(Dispatchers.IO) {
                    val html = current.chapterHtml(index)
                    ReaderText.render(html, current, index, settings, spec, resources)
                }
                positionMap = mapping
                chapterIds = ids
                pageBreaks = breaks
                ReaderCss.measure(content, text.paint)
                text.setText(content, TextView.BufferType.SPANNABLE)
                applyHighlights()
                status.text = if (finished) "Book finished" else "Chapter ${chapter + 1} of ${current.chapters.size}"
                val restoredOffset = if (fraction != null) (fraction * content.length).toInt()
                    else if (visible != null && remote == null) mapping?.renderedOffset(visible) ?: offset
                    else if (remote == null) offset else {
                    mapping?.offset(remote.locator) ?: if (remote.locator.startsWith("crosspoint-launcher:")) offset
                    else ((remote.percentage * current.chapters.size - index).coerceIn(0.0, 1.0) * content.length).toInt()
                }
                battery = batteryLevel()
                restoreOffset(restoredOffset) {
                    ready = true
                    updateFooter()
                    then?.invoke()
                    if (remote != null) {
                        lastQueuedAt = remote.timestamp
                        prefs.edit().putInt("$key.chapter", chapter).putInt("$key.offset", currentOffset())
                            .putInt("$key.visible", positionMap?.sourceOffset(currentOffset()) ?: -1).putInt("$key.pages", pages.size)
                            .putBoolean("$key.finished", finished).putLong("$key.at", remote.timestamp).apply()
                        android.widget.Toast.makeText(this@ReaderActivity, "Position Restored", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e) }
        }
    }

    // --- Selection: Look Up, Clip, Bookmark (firmware ClipSelectionActivity actions) ---

    private var selectionMode: ActionMode? = null
    private val clippings by lazy { ReaderClippings(File(filesDir, "clippings"), key) }

    // Firmware-style selection: our own Define / Clip bar above the selected text. Android's floating toolbar is
    // suppressed (it doesn't show reliably on e-ink builds like the S4); the action mode only keeps the handles alive.
    private val selectionActions = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { selectionMode = mode; menu.clear(); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { menu.clear(); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = null
            selectionBar.visibility = View.GONE
            // Dragging a handle may have scrolled off the page grid; settle back onto a page.
            if (paged) restoreOffset(currentOffset())
        }
    }

    private lateinit var selectionBar: LinearLayout

    private fun buildSelectionBar() = LinearLayout(this).apply {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        fun action(label: String, run: (Int, Int) -> Unit) = addView(Button(this@ReaderActivity).apply {
            text = label; textSize = 15f; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
            minWidth = dp(88); minimumWidth = dp(88)
            setOnClickListener {
                val start = minOf(this@ReaderActivity.text.selectionStart, this@ReaderActivity.text.selectionEnd).coerceAtLeast(0)
                val end = maxOf(this@ReaderActivity.text.selectionStart, this@ReaderActivity.text.selectionEnd).coerceAtMost(this@ReaderActivity.text.length())
                clearSelection()
                if (end > start) run(start, end)
            }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        action("Define") { start, end -> lookUp(this@ReaderActivity.text.text.subSequence(start, end).toString()) }
        addView(View(this@ReaderActivity).apply { setBackgroundColor(Color.WHITE) }, LinearLayout.LayoutParams(dp(1), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL })
        action("Clip") { start, end -> clip(start, end) }
    }

    /** Shows the Define / Clip bar just above the selection (below it when there's no room), or hides it. */
    private fun updateSelectionBar() {
        val layout = text.layout
        if (!text.hasSelection() || layout == null) { selectionBar.visibility = View.GONE; return }
        val start = minOf(text.selectionStart, text.selectionEnd)
        val end = maxOf(text.selectionStart, text.selectionEnd)
        val textAt = IntArray(2).also { text.getLocationInWindow(it) }
        val rootAt = IntArray(2).also { selectionBar.parent.let { p -> (p as View).getLocationInWindow(it) } }
        val left = textAt[0] - rootAt[0] + text.totalPaddingLeft
        val top = textAt[1] - rootAt[1] + text.totalPaddingTop
        val lineTop = top + layout.getLineTop(layout.getLineForOffset(start))
        val lineBottom = top + layout.getLineBottom(layout.getLineForOffset(end))
        selectionBar.visibility = View.VISIBLE
        selectionBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = selectionBar.measuredWidth
        val height = selectionBar.measuredHeight
        val anchorX = left + layout.getPrimaryHorizontal(start).toInt()
        val parentWidth = (selectionBar.parent as View).width
        val params = selectionBar.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = (anchorX - width / 2).coerceIn(dp(8), (parentWidth - width - dp(8)).coerceAtLeast(dp(8)))
        // Room for the bar plus the selection handle's drag area above the text.
        params.topMargin = if (lineTop - height - dp(8) > dp(8)) lineTop - height - dp(8) else lineBottom + dp(28)
        selectionBar.layoutParams = params
        selectionBar.bringToFront()
    }

    private fun clearSelection() {
        selectionMode?.finish()
        (text.text as? Spannable)?.let { Selection.removeSelection(it) }
    }

    private fun clip(start: Int, end: Int) {
        val current = book ?: return
        val quote = text.text.subSequence(start, end).toString().replace('￼', ' ')
            .replace(Regex("[ \\t\\u00a0]+"), " ").replace(Regex(" *\\n+ *"), "\n").trim()
        if (quote.isEmpty()) return
        if (quote.toByteArray().size > ReaderClippings.TEXT_MAX_BYTES) { toast("Selection is too long to clip"); return }
        val map = positionMap
        clippings.add(current.spineIndices[chapter], chapterTitle(), quote, map?.sourceOffset(start), map?.sourceOffset(end))
        ReaderClippings.appendKindle(current.title, current.author, chapterTitle(), pageNumber(), quote)
        toast("Clipping saved")
        applyHighlights()
        syncClippingsInBackground()
    }

    private fun bookmarkAt(offset: Int) {
        if (!ready) return
        val list = bookmarks()
        if (list.none { it.optInt("chapter") == chapter && it.optInt("offset") == offset }) {
            list += JSONObject().put("chapter", chapter).put("offset", offset).put("percent", bookPercent())
                .put("text", text.text.subSequence(offset, (offset + 80).coerceAtMost(text.length())).toString().replace(Regex("\\s+"), " ").trim())
            prefs.edit().putString("$key.bookmarks", JSONArray(list.sortedWith(compareBy({ it.optInt("chapter") }, { it.optInt("offset") }))).toString()).apply()
        }
        toast("Bookmark added")
        updateFooter(); refreshHeaderActions()
    }

    private fun pageNumber(): Int = if (paged) pageIndex() + 1 else scroll.scrollY / pageStep() + 1

    private class ClipHighlight(color: Int) : BackgroundColorSpan(color)

    /** Rendered ranges of this chapter's clippings: firmware offsets when mappable, else the quote's text. */
    private fun highlightRanges(): List<Pair<JSONObject, IntRange>> {
        val spine = book?.spineIndices?.getOrNull(chapter) ?: return emptyList()
        val content = text.text.toString()
        val map = positionMap
        return clippings.items.filter { it.optInt("spine", -1) == spine }.mapNotNull { item ->
            val mapped = if (map != null && item.has("start_offset") && item.has("end_offset")) {
                val first = map.renderedOffset(item.getInt("start_offset")); val last = map.renderedOffset(item.getInt("end_offset"))
                if (first != null && last != null && last > first) first until last else null
            } else null
            val range = mapped ?: item.optString("text").lineSequence().firstOrNull()?.take(120)?.takeIf { it.isNotBlank() }
                ?.let { probe -> content.indexOf(probe).takeIf { it >= 0 }?.let { it until (it + item.optString("text").length).coerceAtMost(content.length) } }
            range?.let { item to it }
        }
    }

    private fun applyHighlights() {
        val content = text.text as? Spannable ?: return
        content.getSpans(0, content.length, ClipHighlight::class.java).forEach(content::removeSpan)
        val color = if (settings.on(S.NIGHT_MODE)) 0xFF555555.toInt() else 0xFFCCCCCC.toInt()
        highlightRanges().forEach { (_, range) -> content.setSpan(ClipHighlight(color), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** Firmware: a long press on a highlighted passage removes that clipping. */
    private fun removeClippingAt(event: MotionEvent) {
        if (!ready) return
        val offset = text.getOffsetForPosition(event.x, event.y + scroll.scrollY)
        val hit = highlightRanges().firstOrNull { offset in it.second }?.first ?: return
        clippings.remove(hit.optString("id"))
        text.post { clearSelection() }
        applyHighlights()
        toast("Clipping removed")
        syncClippingsInBackground()
    }

    private fun syncClippingsInBackground(manual: Boolean = false) {
        val current = book ?: return
        if (!sync.syncClippings || !sync.connected) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { sync.syncClippings(sync.documentId(Book(key, current.title, current.author, null, current.file.lastModified())), clippings) }
            }
            if (ready) applyHighlights()
            if (manual && result.isFailure) toast("Clippings saved locally. ${result.exceptionOrNull()?.message.orEmpty()}")
        }
    }

    /** Firmware dictionary: the chosen StarDict folder, or an installed dictionary app when there is none. */
    private fun lookUp(selected: String) {
        val word = selected.trim().trim { !it.isLetterOrDigit() }.take(64)
        if (word.isEmpty()) return
        lifecycleScope.launch {
            val dictionaries = withContext(Dispatchers.IO) { StarDict.discover(this@ReaderActivity) }
            val chosen = prefs.getString(DICTIONARY_KEY, null)
            val base = if (chosen == DICTIONARY_APP) null else dictionaries[chosen] ?: dictionaries.values.firstOrNull()
            if (base == null) { openDictionaryApp(word); return@launch }
            val entry = withContext(Dispatchers.IO) { runCatching { StarDict.open(base).lookup(StarDict.candidates(word)) }.getOrNull() }
            openPanel(ReaderPanel.Definition(word, entry))
        }
    }

    override fun openDictionaryApp(word: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        if (packageManager.queryIntentActivities(intent, 0).isEmpty()) toast("Add a dictionary to the dictionaries folder or install a dictionary app")
        else startActivity(Intent.createChooser(intent, "Look up “$word”"))
    }

    override fun dictionaryChoices(): List<String> = StarDict.discover(this).keys.toList() + DICTIONARY_APP

    // Dictionaries are added like fonts: browse for a StarDict archive and install it into the reader.
    private val dictionaryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::installDictionary) }
    override fun addDictionary() = dictionaryPicker.launch(arrayOf("*/*"))

    private fun installDictionary(uri: android.net.Uri) {
        toast("Installing dictionary…")
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Dictionary.zip"
                    contentResolver.openInputStream(uri)!!.use { StarDict.install(this@ReaderActivity, it, name) }
                }
            }
            installed.onSuccess { name ->
                dictionary = name
                toast("Added $name")
                if (panel.value is ReaderPanel.Dictionaries) openPanel(ReaderPanel.Dictionaries())
            }.onFailure { toast(it.message ?: "Could not add that dictionary") }
        }
    }
    override var dictionary: String
        get() = prefs.getString(DICTIONARY_KEY, null) ?: dictionaryChoices().first()
        set(value) { prefs.edit().putString(DICTIONARY_KEY, value).apply() }

    // --- Fonts: bundled firmware fonts plus fonts browsed from device storage ---
    // Pick one file or a family's style files (Regular, Bold, Italic, Bold Italic) together.
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) importFonts(uris) }

    override fun fontChoices() = settings.fontFamilies(this)
    override fun fontTypeface(name: String) = settings.typeface(this, name)
    override fun fontSource(name: String) = when {
        name in ReaderSettings.BUNDLED_FONTS -> "Built in"
        ReaderSettings.deviceFonts(this)[name]?.firstOrNull()?.path?.startsWith(ReaderSettings.importedFontDir(this).path) == true -> "Added from this device"
        else -> "Fonts folder"
    }
    // File managers label fonts inconsistently, so browse everything and validate the pick.
    override fun addFont() = fontPicker.launch(arrayOf("*/*"))

    /** Saves the picked files as one family folder, like the firmware's /.fonts/<Family>/. */
    private fun importFonts(uris: List<android.net.Uri>) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val picks = uris.map { uri ->
                        uri to (contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Font.ttf")
                    }
                    require(picks.all { it.second.substringAfterLast('.', "").lowercase() in listOf("ttf", "otf", "ttc") }) { "Choose .ttf, .otf or .ttc font files" }
                    val family = ReaderSettings.familyName(picks.map { it.second })
                    val dir = File(ReaderSettings.importedFontDir(this@ReaderActivity), family).apply { deleteRecursively(); mkdirs() }
                    picks.forEach { (uri, display) -> saveFont(uri, File(dir, File(display).name.replace(Regex("[^\\w .-]"), "_"))) }
                    requireNotNull(ReaderSettings.loadFamily(dir.listFiles().orEmpty().toList())) { dir.deleteRecursively(); "Those files aren't usable fonts" }
                    family
                }
            }
            added.onSuccess { name ->
                settings.fontFamily = name
                restyle()
                toast("Added $name")
                if (panel.value is ReaderPanel.Fonts) openPanel(ReaderPanel.Fonts())
            }.onFailure { toast(it.message ?: "Could not add that font") }
        }
    }

    private fun saveFont(uri: android.net.Uri, target: File) {
        contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count
                    require(total <= 32L * 1024 * 1024) { "${target.name} is larger than 32 MB" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    override fun removeFont(name: String) {
        val root = ReaderSettings.importedFontDir(this)
        File(root, name).deleteRecursively()
        root.listFiles { it.isFile && it.nameWithoutExtension == name }?.forEach { it.delete() }
        if (settings.fontFamily == name) { settings.fontFamily = ReaderSettings.BUNDLED_FONTS.keys.first(); restyle() }
    }

    override fun clippingList(): List<JSONObject> = clippings.items.sortedByDescending { it.optLong("created_at") }
    override fun deleteClipping(clipping: JSONObject) {
        clippings.remove(clipping.optString("id")); applyHighlights(); syncClippingsInBackground()
    }
    override fun openClipping(clipping: JSONObject) {
        val index = book?.spineIndices?.indexOf(clipping.optInt("spine")) ?: return
        if (index < 0) { toast("This passage isn't in this copy of the book"); return }
        showChapter(index) {
            highlightRanges().firstOrNull { it.first.optString("id") == clipping.optString("id") }?.let { restoreOffset(it.second.first) { updateFooter() } }
        }
    }

    private fun showError(e: Exception) {
        loadFailed = true
        status.text = "Unable to read this EPUB"
        text.text = e.message ?: "Try opening this book with another reader."
        // A bad chapter must not prevent navigation to the remaining book.
        showControls(true)
    }

    private fun currentOffset(): Int {
        val layout = text.layout ?: return 0
        if (paged) return layout.getLineStart(pages[pageIndex()])
        return layout.getLineStart(layout.getLineForVertical((scroll.scrollY - text.paddingTop).coerceAtLeast(0)))
    }

    /** Page table valid for the current layout (it's rebuilt after every text or size change). */
    private val paged get() = !continuous && pages.isNotEmpty() && text.layout?.let { pages.last() < it.lineCount } == true

    /** Slices the laid-out chapter into pages of whole lines, filled like the firmware; CSS page breaks start a new page. */
    private fun paginate() {
        val layout = text.layout
        val height = scroll.height - text.paddingTop - text.paddingBottom
        if (layout == null || height <= 0 || layout.lineCount == 0) { pages = IntArray(0); return }
        val content = text.text
        val breakLines = pageBreaks.filter { it in 1 until content.length }.map { layout.getLineForOffset(it) }.toHashSet()
        val result = ArrayList<Int>()
        var first = 0
        while (first < layout.lineCount) {
            result += first
            var last = first
            while (last + 1 < layout.lineCount && last + 1 !in breakLines && layout.getLineBottom(last + 1) - layout.getLineTop(first) <= height) last++
            first = last + 1
        }
        pages = result.toIntArray()
    }

    private fun pageIndex(): Int {
        val layout = text.layout ?: return 0
        val y = scroll.scrollY + 1
        var index = 0
        while (index + 1 < pages.size && layout.getLineTop(pages[index + 1]) <= y) index++
        return index
    }

    private fun lastLine(page: Int) = (if (page + 1 < pages.size) pages[page + 1] else text.layout!!.lineCount) - 1

    private fun showPage(index: Int) {
        val layout = text.layout ?: return
        scroll.scrollTo(0, layout.getLineTop(pages[index]))
        updateMask()
    }

    private fun sizeSpacer() {
        val height = if (continuous) 0 else scroll.height
        if (spacer.layoutParams.height != height) { spacer.layoutParams.height = height; spacer.requestLayout() }
    }

    /** Covers everything below the page's last whole line, bottom margin included. */
    private fun updateMask() {
        val layout = text.layout
        if (!paged || layout == null) { mask.visibility = View.GONE; topMask.visibility = View.GONE; return }
        topMask.layoutParams.height = text.paddingTop
        topMask.visibility = View.VISIBLE
        topMask.requestLayout()
        val index = pageIndex()
        val shown = text.paddingTop + layout.getLineBottom(lastLine(index)) - layout.getLineTop(pages[index])
        mask.layoutParams.height = (scroll.height - shown).coerceAtLeast(0)
        mask.visibility = View.VISIBLE
        mask.requestLayout()
    }

    /** Runs after any pending layout pass: new text isn't measured until then, and scrolling earlier would clamp. */
    private fun afterLayout(action: () -> Unit) {
        if (!text.isLayoutRequested && !scroll.isLayoutRequested) { text.post(action); return }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                text.post(action)
            }
        })
    }

    private fun restoreOffset(offset: Int, done: () -> Unit = {}) {
        afterLayout {
            paginate()
            if (paged) {
                val layout = text.layout!!
                val line = if (offset == Int.MAX_VALUE) layout.lineCount - 1 else layout.getLineForOffset(offset.coerceIn(0, text.length()))
                showPage(pages.indexOfLast { it <= line }.coerceAtLeast(0))
                done()
                return@afterLayout
            }
            mask.visibility = View.GONE
            topMask.visibility = View.GONE
            val layout = text.layout
            val y = if (offset == Int.MAX_VALUE) text.height else layout?.let {
                it.getLineTop(it.getLineForOffset(offset.coerceIn(0, text.length()))) + text.paddingTop
            } ?: 0
            scroll.scrollTo(0, y)
            done()
        }
    }

    /** Paged mode swallows drags so only page turns move the text; continuous scroll keeps native scrolling. */
    private inner class ReaderScroll : ScrollView(this@ReaderActivity) {
        private var downX = 0f
        private var downY = 0f
        private var atTop = false
        private var atBottom = false
        private var selecting = false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // While text is selected, touches belong to the selection handles and toolbar.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { selecting = text.hasSelection(); if (continuous) anchor = null }
            if (!selecting) {
                gestures.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; atTop = !canScrollVertically(-1); atBottom = !canScrollVertically(1) }
                    MotionEvent.ACTION_UP -> if (!text.hasSelection()) swiped(event.x - downX, event.y - downY, downY, atTop, atBottom)
                }
            }
            val handled = super.dispatchTouchEvent(event)
            // Selections start on long press and change with handle drags; place the bar once the finger lifts.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) text.post { updateSelectionBar() }
            return handled
        }
        // The selectable text asks to bring its cursor into view (and to be revealed on focus); in paged mode that
        // smooth-scrolls the page off its alignment, so only page turns may move it.
        override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean) =
            continuous && super.requestChildRectangleOnScreen(child, rectangle, immediate)
        // Paged mode never scrolls on a drag; the text still gets long presses for selection.
        override fun onInterceptTouchEvent(event: MotionEvent) = continuous && super.onInterceptTouchEvent(event)
        override fun onTouchEvent(event: MotionEvent) = if (continuous) super.onTouchEvent(event) else true
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean { tap(event.x); return true }
            override fun onLongPress(event: MotionEvent) { removeClippingAt(event) }
        })
    }

    private fun taps(gesture: Int) = gesture == S.TAP_AND_SWIPE || gesture == S.TAP_ONLY || gesture == S.INVERTED_TAP
    private fun swipes(gesture: Int) = gesture == S.TAP_AND_SWIPE || gesture == S.SWIPE_ONLY

    /** Tap zones: the full-height center strip opens the menu, the left third goes back, the right third goes forward. */
    private fun tap(x: Float) {
        val w = scroll.width
        val center = x >= w / 3f && x < w - w / 3f
        if (center && settings[S.MENU_GESTURE] == 1) { showControls(controls.visibility != View.VISIBLE); return }
        if (controls.visibility == View.VISIBLE) { showControls(false); return }
        if (!settings.on(S.TOUCH_CONTROLS)) return
        val next = settings[S.NEXT_GESTURE]; val previous = settings[S.PREVIOUS_GESTURE]
        val nextTaps = taps(next); val previousTaps = taps(previous)
        if (!nextTaps && !previousTaps) return
        val inverted = next == S.INVERTED_TAP || previous == S.INVERTED_TAP
        val nextZone = if (inverted) x < w * 2 / 3f else x >= w / 3f
        if (nextZone && nextTaps) turn(1)
        else if (!nextZone && previousTaps) turn(-1)
    }

    private fun swiped(dx: Float, dy: Float, startY: Float, atTop: Boolean, atBottom: Boolean) {
        val min = 48 * resources.displayMetrics.density
        when {
            abs(dx) > min && abs(dx) > abs(dy) * 1.5f -> {
                val direction = if (dx < 0) 1 else -1
                if (settings.on(S.TOUCH_CONTROLS) && swipes(settings[if (direction > 0) S.NEXT_GESTURE else S.PREVIOUS_GESTURE])) turn(direction)
            }
            -dy > min && abs(dy) > abs(dx) && settings[S.MENU_GESTURE] == 2 && startY > scroll.height * 0.8f -> showControls(true)
            // Continuous scroll: pulling past either end of a chapter continues into the neighbouring one.
            continuous && -dy > min && atBottom -> turn(1)
            continuous && dy > min && atTop && chapter > 0 -> turn(-1)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun showControls(visible: Boolean) {
        if (visible) refreshHeaderActions()
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        bottomControls.visibility = controls.visibility
    }

    private fun applyTextSettings() {
        val density = resources.displayMetrics.density
        text.typeface = settings.typeface(this)
        text.textSize = settings[S.FONT_SIZE].toFloat()
        text.setLineSpacing(0f, settings.lineMultiplier())
        // ponytail: Android justification undershoots the right margin when letter spacing is set (span or view
        // property alike); character spacing still applies, justified lines may end a little short.
        text.letterSpacing = settings[S.CHARACTER_SPACING] * 0.025f
        val margin = (settings[S.MARGIN] * density).toInt()
        // The firmware's screen margin applies to every edge.
        text.setPadding(margin, margin, margin, margin)
        val alignment = settings[S.ALIGNMENT]
        // Book's style (4) falls back to justified text, as on the firmware.
        // Before Android 12 the selectable text's DynamicLayout breaks lines for justification (letting them run a
        // little wide, to be squeezed) but never draws them justified, so lines overflow the right margin. There,
        // lay out ragged-right instead.
        // ponytail: true justification on Android 11 needs a non-selectable StaticLayout while not selecting.
        val justify = (alignment == 0 || alignment == 4) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        text.justificationMode = if (justify) android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD else android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE
        text.gravity = Gravity.TOP or when (alignment) { 2 -> Gravity.CENTER_HORIZONTAL; 3 -> Gravity.END; else -> Gravity.START }
        val hyphenate = settings.on(S.HYPHENATION)
        text.hyphenationFrequency = if (hyphenate) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE
        // Always the high-quality breaker: Android 11's simple (greedy) breaker let indented first lines of ragged
        // text run past the right margin.
        text.breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        text.paint.isAntiAlias = settings.on(S.ANTI_ALIASING)
        // Keep outline coverage instead of snapping stems to the pixel grid; allow font-specific comparison.
        text.paint.hinting = if (settings.on(S.FONT_HINTING)) android.graphics.Paint.HINTING_ON else android.graphics.Paint.HINTING_OFF
        // Fractional glyph positions, as Chromium draws them; without this every glyph snaps to a whole pixel and spacing goes uneven.
        text.paint.isSubpixelText = true
        text.invalidate()
    }

    private fun applyDisplaySettings() {
        continuous = settings.on(S.CONTINUOUS_SCROLL)
        scroll.isVerticalScrollBarEnabled = false
        sizeSpacer()
        scroll.keepScreenOn = settings.on(S.KEEP_AWAKE)
        val orientation = ORIENTATIONS[settings.index(S.ORIENTATION)]
        if (requestedOrientation != orientation) requestedOrientation = orientation
        val night = settings.on(S.NIGHT_MODE)
        val foreground = if (night) Color.WHITE else Color.BLACK
        page.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        mask.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        topMask.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        text.setTextColor(foreground); text.setLinkTextColor(foreground)
        updateFooter()
    }

    /** Re-renders the chapter at the same text position after a setting that changes styling. */
    private fun restyle() {
        // Read the place before applying: new text metrics relayout at once, and the page table would no longer match.
        val offset = if (ready) anchor ?: currentOffset() else null
        if (offset != null) savePosition() // While the page table still matches the layout.
        applyTextSettings()
        if (offset == null) return
        showChapter(chapter, offset, finished, save = false)
        anchor = offset
    }

    private fun pageStep() = (scroll.height - text.lineHeight).coerceAtLeast(1)

    private fun chapterFraction(): Double {
        if (paged) return if (pages.size <= 1) 1.0 else pageIndex().toDouble() / (pages.size - 1)
        val scrollable = (text.height - scroll.height).coerceAtLeast(0)
        return if (scrollable == 0) 1.0 else scroll.scrollY.toDouble() / scrollable
    }

    private fun bookFraction(): Double = book?.let { if (finished) 1.0 else (chapter + chapterFraction()) / it.chapters.size } ?: 0.0

    private fun chapterTitle(): String = book?.toc?.lastOrNull { it.chapter <= chapter }?.title ?: "Chapter ${chapter + 1}"

    private fun batteryLevel(): Int? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { level -> level >= 0 && scale > 0 }?.let { level -> level * 100 / scale }
    }

    private fun updateFooter() {
        if (!::statusBar.isInitialized) return
        val current = book
        val step = pageStep()
        val scrollable = (text.height - scroll.height).coerceAtLeast(0)
        val pages = if (paged) this.pages.size else (scrollable + step - 1) / step + 1
        val pageNumber = if (paged) pageIndex() + 1 else ((scroll.scrollY + step - 1) / step + 1).coerceAtMost(pages)
        val title = when (settings[S.STATUS_TITLE]) { 0 -> current?.title.orEmpty(); 1 -> if (current != null && ready) chapterTitle() else ""; else -> "" }
        statusBar.bind(settings, ReaderStatusBar.State(title, pageNumber, pages, bookPercent(), battery, pageBookmark() != null),
            if (settings.on(S.NIGHT_MODE)) Color.WHITE else Color.BLACK)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun openPanel(next: ReaderPanel?) {
        panel.value = next
        panelHost.visibility = if (next == null) View.GONE else View.VISIBLE
        if (next != null) showControls(false)
    }

    private fun refreshHeaderActions() {
        if (!::bookmarkButton.isInitialized) return
        val marked = pageBookmark() != null
        bookmarkButton.contentDescription = if (marked) "Remove Bookmark" else "Bookmark Page"
        bookmarkButton.setBackgroundColor(if (marked) Color.BLACK else Color.TRANSPARENT)
        bookmarkButton.imageTintList = ColorStateList.valueOf(if (marked) Color.WHITE else Color.BLACK)
        notesButton.visibility = if (ready && pageFootnotes().isNotEmpty()) View.VISIBLE else View.GONE
    }

    // --- ReaderPanelHost ---
    override val readerSettings get() = settings
    override fun syncPosition() = synchronizePosition(manual = true)
    override var autoTurnIndex: Int
        get() = autoTurn
        set(value) { autoTurn = value; startAutoTurn() }
    override fun settingChanged(setting: ReaderSettings.Setting?) {
        val offset = if (ready) currentOffset() else 0
        applyDisplaySettings()
        if (ready && setting == S.CONTINUOUS_SCROLL) restoreOffset(offset)
        if (setting == null || setting in S.TEXT || setting == S.NIGHT_MODE) restyle()
    }
    override val tableOfContents: List<EpubBook.TocEntry>
        get() = book?.let { current ->
            current.toc.ifEmpty {
                current.chapters.mapIndexed { index, path -> EpubBook.TocEntry("${index + 1}. ${path.substringAfterLast('/').substringBeforeLast('.')}", index, 0, "") }
            }
        }.orEmpty()
    override val currentChapter get() = chapter
    /** Contents entries open at their #fragment when the chapter has that id, else at the chapter start. */
    override fun openChapter(index: Int, fragment: String) = showChapter(index) {
        chapterIds[fragment]?.let { restoreOffset(it) { updateFooter() } }
    }
    override fun bookmarkList(): List<JSONObject> = bookmarks()
    override fun openBookmark(bookmark: JSONObject) = showChapter(bookmark.optInt("chapter"), bookmark.optInt("offset"))
    override fun deleteBookmark(bookmark: JSONObject) {
        val list = bookmarks().filterNot { it.optInt("chapter") == bookmark.optInt("chapter") && it.optInt("offset") == bookmark.optInt("offset") }
        prefs.edit().putString("$key.bookmarks", JSONArray(list).toString()).apply()
        updateFooter()
    }
    override fun bookPercent() = (bookFraction() * 100).toInt()
    override fun goToPercent(percent: Int) {
        val current = book ?: return
        // Percentages are chapter-weighted, matching the progress this reader reports.
        val target = percent / 100.0 * current.chapters.size
        val index = target.toInt().coerceAtMost(current.chapters.lastIndex)
        showChapter(index, fraction = (target - index).coerceIn(0.0, 1.0))
    }
    override fun pageFootnotes(): List<Pair<String, String>> {
        val content = text.text as? Spanned ?: return emptyList()
        val range = visibleRange()
        if (range.isEmpty()) return emptyList()
        return content.getSpans(range.first, range.last + 1, URLSpan::class.java).filter { !it.url.contains(':') }.map { link ->
            content.subSequence(content.getSpanStart(link), content.getSpanEnd(link)).toString().trim().ifEmpty { link.url } to link.url
        }
    }
    override suspend fun footnoteText(href: String): String? {
        val current = book ?: return null
        val shownChapter = chapter
        return withContext(Dispatchers.IO) {
            runCatching {
                val id = href.substringAfter('#', "")
                val html = if (href.startsWith("#")) current.chapterHtml(shownChapter) else String(current.resource(shownChapter, href), Charsets.UTF_8)
                ReaderText.footnote(html, id.ifEmpty { return@runCatching null })?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
            }.getOrNull()
        }
    }
    override val batteryPercent get() = battery

    private fun turn(direction: Int) {
        anchor = null
        showControls(false)
        if (loadJob?.isActive == true) return
        if (direction < 0) finished = false
        // A chapter that failed to load can't page, but must not block moving on to its neighbours.
        // Presses while a chapter is still laying out are dropped (they used to chain into chapter skips); only a
        // chapter that failed to load hands the press to its neighbour, so a bad chapter can't trap the reader.
        if (!ready) { if (loadFailed) showChapter(chapter + direction, if (direction < 0) Int.MAX_VALUE else 0); return }
        val canTurn = if (paged) pageIndex() + direction in pages.indices else scroll.canScrollVertically(direction)
        if (ready && canTurn) {
            if (paged) showPage(pageIndex() + direction) else scroll.scrollBy(0, direction * pageStep())
        } else if (direction > 0 && chapter == book?.chapters?.lastIndex && ready) {
            finished = true
            status.text = "Book finished"
            autoTurnJob?.cancel()
            savePosition()
        } else {
            showChapter(chapter + direction, if (direction < 0) Int.MAX_VALUE else 0)
        }
        val every = settings[S.REFRESH]
        if (every > 0 && ++turnsSinceRefresh >= every) { turnsSinceRefresh = 0; ScreenRefresh.flash(this) }
        updateFooter()
    }

    private fun startAutoTurn() {
        autoTurnJob?.cancel()
        val rate = S.AUTO_TURN_RATES[autoTurn]
        if (rate == 0) return
        autoTurnJob = lifecycleScope.launch { while (!finished) { delay(60_000L / rate); turn(1) } }
    }


    private fun visibleRange(): IntRange {
        val layout = text.layout ?: return 0 until 0
        if (paged) return pageIndex().let { layout.getLineStart(pages[it]) until layout.getLineEnd(lastLine(it)) }
        val start = currentOffset()
        val end = layout.getLineEnd(layout.getLineForVertical((scroll.scrollY + scroll.height - text.paddingTop).coerceAtLeast(0)))
        return start until end.coerceAtLeast(start)
    }

    private fun bookmarks(): MutableList<JSONObject> = runCatching {
        JSONArray(prefs.getString("$key.bookmarks", "[]")).let { array -> MutableList(array.length()) { array.getJSONObject(it) } }
    }.getOrDefault(mutableListOf())

    private fun pageBookmark(): JSONObject? = if (!ready || key.isEmpty()) null else visibleRange().let { range ->
        bookmarks().firstOrNull { it.optInt("chapter") == chapter && it.optInt("offset") in range }
    }

    private fun toggleBookmark() {
        if (!ready) return
        val list = bookmarks()
        val existing = pageBookmark()
        if (existing != null) list.removeAll { it.optInt("chapter") == chapter && it.optInt("offset") == existing.optInt("offset") }
        else {
            val offset = currentOffset()
            list += JSONObject().put("chapter", chapter).put("offset", offset).put("percent", (bookFraction() * 100).toInt())
                .put("text", text.text.subSequence(offset, (offset + 80).coerceAtMost(text.length())).toString().replace(Regex("\\s+"), " ").trim())
        }
        prefs.edit().putString("$key.bookmarks", JSONArray(list.sortedWith(compareBy({ it.optInt("chapter") }, { it.optInt("offset") }))).toString()).apply()
        toast(if (existing != null) "Bookmark removed" else "Bookmark added")
        updateFooter()
        refreshHeaderActions()
    }

    private fun savePosition(force: Boolean = false) {
        if (!ready || key.isEmpty()) return
        val offset = currentOffset()
        val changed = !prefs.contains("$key.at") || chapter != prefs.getInt("$key.chapter", 0) || offset != prefs.getInt("$key.offset", 0) || finished != prefs.getBoolean("$key.finished", false)
        val at = if (changed || force) maxOf(System.currentTimeMillis(), prefs.getLong("$key.at", 0) + 1) else prefs.getLong("$key.at", 0)
        if (changed || force) prefs.edit().putInt("$key.chapter", chapter).putInt("$key.offset", offset)
            .putInt("$key.visible", positionMap?.sourceOffset(offset) ?: -1).putInt("$key.pages", pages.size)
            .putBoolean("$key.finished", finished).putLong("$key.at", at).apply()
        val current = book ?: return
        // ponytail: percentages are chapter-weighted; the XPath carries the actual text location.
        val fraction = if (finished) 1.0 else (chapter + offset.toDouble() / text.length().coerceAtLeast(1)) / current.chapters.size
        if (at > lastQueuedAt && accountScope == sync.accountScope) document?.let {
            val locator = positionMap?.xpath(offset)?.takeIf { path -> path.length <= 4096 } ?: "crosspoint-launcher:$chapter:$offset"
            sync.queueProgress(it, current.title, current.author, current.file.name, chapter, offset, fraction, locator, at)
            lastQueuedAt = at
        }
    }

    private fun synchronizePosition(manual: Boolean = false) {
        val current = book ?: return
        if (syncJob?.isActive == true) return
        if (!sync.connected) {
            if (manual) android.widget.Toast.makeText(this, "Log in to CrossPoint Sync in Settings", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        savePosition()
        val startedChapter = chapter
        val startedOffset = if (ready) currentOffset() else prefs.getInt("$key.offset", 0)
        val localAt = prefs.getLong("$key.at", 0)
        val account = sync.accountScope
        syncJob = lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    sync.documentId(Book(key, current.title, current.author, null, current.file.lastModified()))
                }
                if (account != sync.accountScope) return@launch
                if (accountScope != account) lastQueuedAt = 0
                accountScope = account
                document = id
                val remote = withContext(Dispatchers.IO) { sync.latestProgress(id, localAt) }
                if (account != sync.accountScope) return@launch
                loadJob?.join()
                if (remote != null) {
                    val legacy = Regex("^crosspoint-launcher:(\\d+):(\\d+)$").matchEntire(remote.locator)
                    val mappedChapter = EpubPosition.spine(remote.locator)?.let { current.spineIndices.indexOf(it).takeIf { index -> index >= 0 } }
                    val target = (mappedChapter ?: legacy?.groupValues?.get(1)?.toIntOrNull()
                        ?: (remote.percentage * current.chapters.size).toInt()).coerceIn(0, current.chapters.lastIndex)
                    val offset = legacy?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    fun wouldMoveBack(): Boolean {
                        if (maxOf(localAt, prefs.getLong("$key.at", 0)) == 0L) return false
                        if (finished && remote.percentage < 1.0) return true
                        if (mappedChapter == null && legacy == null) return true // No reliable location to compare.
                        val localChapter = if (ready) chapter else prefs.getInt("$key.chapter", chapter)
                        val localOffset = if (ready) currentOffset() else prefs.getInt("$key.offset", 0)
                        if (target != localChapter) return target < localChapter
                        val mappedOffset = positionMap?.offset(remote.locator) ?: legacy?.groupValues?.get(2)?.toIntOrNull()
                        return mappedOffset == null || mappedOffset < localOffset
                    }
                    fun applyRemote() {
                        if (!wouldMoveBack()) showChapter(target, offset, remote.percentage >= 1.0, remote)
                    }
                    if (wouldMoveBack()) {
                        savePosition(force = true)
                        withContext(Dispatchers.IO) { sync.flushProgress() }
                        if (manual) toast("Local reading position kept")
                    } else if (chapter == startedChapter && currentOffset() == startedOffset && prefs.getLong("$key.at", 0) == localAt) applyRemote()
                    else AlertDialog.Builder(this@ReaderActivity).setTitle("Synced reading position")
                        .setMessage("${remote.device} saved a newer position (${(remote.percentage * 100).toInt()}%). Continue there?")
                        .setPositiveButton("Continue there") { _, _ -> applyRemote() }
                        .setNegativeButton("Keep this position") { _, _ -> savePosition(force = true); SyncRepository.flushInBackground(applicationContext) }.show()
                } else {
                    savePosition()
                    withContext(Dispatchers.IO) { sync.flushProgress() }
                    if (manual) android.widget.Toast.makeText(this@ReaderActivity, "Reading position synced", android.widget.Toast.LENGTH_SHORT).show()
                    syncClippingsInBackground(manual)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Keep the local book usable offline. A later resume or Sync tap retries the pull.
                savePosition()
                if (manual) android.widget.Toast.makeText(this@ReaderActivity, "Position saved locally. ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        synchronizePosition()
        checkpointJob?.cancel()
        checkpointJob = lifecycleScope.launch {
            while (true) {
                // Keep local checkpoints frequent; batch network uploads to once every five minutes.
                repeat(10) {
                    delay(30_000)
                    savePosition()
                    battery = batteryLevel()
                    updateFooter()
                }
                SyncRepository.flushInBackground(applicationContext)
            }
        }
        startAutoTurn()
    }
    override fun onPause() {
        checkpointJob?.cancel()
        autoTurnJob?.cancel()
        savePosition()
        val current = book
        val account = sync.accountScope
        if (ready && document == null && current != null && account != null) {
            val offset = currentOffset()
            val fraction = if (finished) 1.0 else (chapter + offset.toDouble() / text.length().coerceAtLeast(1)) / current.chapters.size
            SyncRepository.queueBookInBackground(applicationContext, Book(key, current.title, current.author, null, current.file.lastModified()),
                chapter, offset, fraction, positionMap?.xpath(offset)?.takeIf { it.length <= 4096 } ?: "crosspoint-launcher:$chapter:$offset", prefs.getLong("$key.at", 0), account)
        }
        SyncRepository.flushInBackground(applicationContext)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (ready) {
            outState.putInt("chapter", chapter); outState.putInt("offset", currentOffset()); outState.putBoolean("finished", finished)
            positionMap?.sourceOffset(anchor ?: currentOffset())?.let { outState.putInt("visible", it) }
        }
        super.onSaveInstanceState(outState)
    }


    /** Volume keys follow the firmware's side-button layout; null leaves them to the system volume. */
    private fun volumeKey(keyCode: Int): Int? {
        val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        return when (settings.index(S.VOLUME_KEYS)) {
            0 -> if (up) -1 else 1
            1 -> if (up) 1 else -1
            3 -> 1
            4 -> -1
            else -> null
        }
    }

    // Reader keys reach the reader before the selectable text view, which would otherwise take page keys.
    @android.annotation.SuppressLint("RestrictedApi") // Public Android callback; retain ComponentActivity dispatch for other keys.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val reader = event.keyCode in listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        if (reader && panel.value == null) return event.dispatch(this, window.decorView.keyDispatcherState, this)
        return super.dispatchKeyEvent(event)
    }

    /** Side buttons (volume keys per the layout setting, or page keys): direction, or null when not a reader button. */
    private fun sideButton(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN -> 1
        KeyEvent.KEYCODE_PAGE_UP -> -1
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> volumeKey(keyCode)
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = sideButton(keyCode) ?: return super.onKeyDown(keyCode, event)
        // One page per press; ignore held-key repeats.
        if (event.repeatCount == 0) turn(direction)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (sideButton(keyCode) != null) true else super.onKeyUp(keyCode, event)

    companion object {
        private const val DICTIONARY_KEY = "dictionary"
        const val DICTIONARY_APP = "Android dictionary app"
        private val ORIENTATIONS = intArrayOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
    }
}
