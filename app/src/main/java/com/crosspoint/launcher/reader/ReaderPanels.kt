package com.crosspoint.launcher.reader

import com.crosspoint.launcher.ui.components.ScrollList
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.crosspoint.launcher.reader.ReaderSettings.Companion as S
import com.crosspoint.launcher.ui.components.BodyText
import com.crosspoint.launcher.ui.components.Pressable
import com.crosspoint.launcher.ui.components.ScreenHeader
import com.crosspoint.launcher.ui.icons.LucideIcon
import com.crosspoint.launcher.ui.settings.ActionRow
import com.crosspoint.launcher.ui.settings.SelectRow
import com.crosspoint.launcher.ui.settings.ToggleRow
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the reader screens need from the open book. */
interface ReaderPanelHost {
    fun syncPosition()
    val readerSettings: ReaderSettings
    var autoTurnIndex: Int
    /** A setting changed; null means the font family. */
    fun settingChanged(setting: ReaderSettings.Setting?)
    val tableOfContents: List<EpubBook.TocEntry>
    val currentChapter: Int
    fun openChapter(index: Int, fragment: String = "")
    fun bookmarkList(): List<JSONObject>
    fun openBookmark(bookmark: JSONObject)
    fun deleteBookmark(bookmark: JSONObject)
    fun bookPercent(): Int
    fun goToPercent(percent: Int)
    fun pageFootnotes(): List<Pair<String, String>>
    suspend fun footnoteText(href: String): String?
    val batteryPercent: Int?
    fun clippingList(): List<JSONObject>
    fun openClipping(clipping: JSONObject)
    fun deleteClipping(clipping: JSONObject)
    fun dictionaryChoices(): List<String>
    var dictionary: String
    fun openDictionaryApp(word: String)
    fun fontChoices(): List<String>
    fun fontTypeface(name: String): android.graphics.Typeface
    /** "Built in", "Sent with Wi-Fi transfer" or "Fonts folder". */
    fun fontSource(name: String): String
    fun removeFont(name: String)
}

sealed class ReaderPanel(val title: String, val parent: ReaderPanel?) {
    object Font : ReaderPanel("Font", null)
    class Dictionaries(val stamp: Long = System.nanoTime()) : ReaderPanel("Dictionary", Settings)
    /** A new instance after an import, so the list recomposes. */
    class Fonts(val stamp: Long = System.nanoTime()) : ReaderPanel("Choose Font", Font)
    object Settings : ReaderPanel("Reader Settings", null)
    object Display : ReaderPanel("Display", Settings)
    object StatusBar : ReaderPanel("Status Bar", Settings)
    object Controls : ReaderPanel("Controls", Settings)
    class Contents(val tab: Int = 0) : ReaderPanel("Chapters", null)
    class Clipping(val item: JSONObject) : ReaderPanel(item.optString("chapter").ifBlank { "Clipping" }, Contents(2))
    class Definition(val word: String, val entry: StarDict.Entry?) : ReaderPanel(word, null)
    object Footnotes : ReaderPanel("Footnotes", null)
    class Note(title: String, val text: String) : ReaderPanel(title, Footnotes)
    class Choice(title: String, val labels: List<String>, parent: ReaderPanel, val selected: () -> Int, val pick: (Int) -> Unit) :
        ReaderPanel(title, parent)
}

/** Reader menus drawn with the launcher's settings rows, header and theme. */
@Composable
fun ReaderPanelScreen(host: ReaderPanelHost, panel: ReaderPanel, navigate: (ReaderPanel?) -> Unit) {
    val colors = CrossPointTheme.colors
    val m = CrossPointTheme.metrics
    val settings = host.readerSettings
    var version by remember { mutableIntStateOf(0) }
    var showGoTo by remember { mutableStateOf(false) }
    val back = { navigate(panel.parent) }
    fun changed(setting: ReaderSettings.Setting?) { host.settingChanged(setting); version++ }
    fun choice(setting: ReaderSettings.Setting) = ReaderPanel.Choice(setting.title, setting.labels, panel, { settings.index(setting) }) {
        settings.set(setting, it); changed(setting)
    }
    BackHandler(onBack = back)
    if (showGoTo) GoToDialog(host.bookPercent(), dismiss = { showGoTo = false }) {
        showGoTo = false
        host.goToPercent(it)
        navigate(null)
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        ScreenHeader(panel.title, back) {
            if (panel is ReaderPanel.Contents) {
                com.crosspoint.launcher.ui.components.HeaderIconButton("cloud_backup", host::syncPosition,
                    modifier = Modifier.semantics { contentDescription = "Sync Reading Position" })
            }
            if (panel is ReaderPanel.Contents) Pressable(onClick = { showGoTo = true }) {
                BodyText("Go to %", weight = FontWeight.SemiBold, size = 14, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
            }
        }
        if (panel is ReaderPanel.Contents) Tabs(panel.tab) { navigate(ReaderPanel.Contents(it)) }

        @Composable
        fun Setting(setting: ReaderSettings.Setting) {
            version // Re-read the preference after every change.
            when {
                setting.toggle -> ToggleRow(setting.title, setting.icon, settings.on(setting)) {
                    settings.set(setting, 1 - settings.index(setting)); changed(setting)
                }
                else -> ActionRow(setting.title, settings.label(setting), setting.icon) { navigate(choice(setting)) }
            }
        }

        val current = host.tableOfContents.indexOfLast { it.chapter <= host.currentChapter }.coerceAtLeast(0)
        val list = rememberLazyListState(if (panel is ReaderPanel.Contents && panel.tab == 0) (current - 3).coerceAtLeast(0) else 0)
        ScrollList(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = m.contentSidePadding, vertical = 0.dp)) {
            when (panel) {
                ReaderPanel.Font -> {
                    item {
                        version
                        ActionRow("Font", settings.fontFamily, "book_text") { navigate(ReaderPanel.Fonts()) }
                    }
                    items(S.TEXT) { Setting(it) }
                }
                ReaderPanel.Settings -> {
                    item { ActionRow("Display", "Scrolling, orientation and night mode", "sun") { navigate(ReaderPanel.Display) } }
                    item { ActionRow("Status Bar", "What shows along the bottom of the page", "rows_3") { navigate(ReaderPanel.StatusBar) } }
                    item { ActionRow("Controls", "Taps, swipes, back button and side buttons", "smartphone") { navigate(ReaderPanel.Controls) } }
                    item {
                        version
                        ActionRow("Dictionary", host.dictionary, "book_open_text") { navigate(ReaderPanel.Dictionaries()) }
                    }
                    item {
                        version
                        ActionRow("Auto Page Turn", S.AUTO_TURN[host.autoTurnIndex] + " · this session", "play") {
                            navigate(ReaderPanel.Choice("Auto Page Turn", S.AUTO_TURN, panel, { host.autoTurnIndex }) { host.autoTurnIndex = it; version++ })
                        }
                    }
                }
                ReaderPanel.Display -> items(S.DISPLAY) { Setting(it) }
                ReaderPanel.Controls -> items(S.CONTROLS) { Setting(it) }
                ReaderPanel.StatusBar -> {
                    item { Setting(S.STATUS_VISIBLE) }
                    items(S.STATUS) { Setting(it) }
                    item {
                        BodyText("Preview", muted = true, size = 14, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                        val foreground = colors.onBackground.toArgb()
                        AndroidView({ ReaderStatusBar(it) }, Modifier.fillMaxWidth()) { bar ->
                            version
                            val title = when (settings[S.STATUS_TITLE]) { 0 -> "Example Book"; 1 -> "Example Chapter"; else -> "" }
                            bar.bind(settings, ReaderStatusBar.State(title, 8, 32, 75, host.batteryPercent ?: 80, false), foreground)
                        }
                    }
                }
                is ReaderPanel.Choice -> itemsIndexed(panel.labels) { index, label ->
                    version
                    SelectRow(label, "", panel.selected() == index) { panel.pick(index); version++ }
                }
                is ReaderPanel.Contents -> if (panel.tab == 2) {
                    version
                    val clips = host.clippingList()
                    if (clips.isEmpty()) item {
                        BodyText("No clippings yet. Long-press the page, adjust the selection, then choose Clip.", muted = true, size = 14, maxLines = 3,
                            modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        items(clips) { clip ->
                            Pressable(onClick = { navigate(ReaderPanel.Clipping(clip)) }, onLongClick = { host.deleteClipping(clip); version++ },
                                modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LucideIcon("file_text", Modifier.size(22.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        BodyText(clip.optString("text"), weight = FontWeight.SemiBold, size = 16, maxLines = 3)
                                        BodyText(clip.optString("chapter"), muted = true, size = 12)
                                    }
                                }
                            }
                        }
                        item { BodyText("Long-press a clipping to delete it.", muted = true, size = 12, modifier = Modifier.padding(vertical = 12.dp)) }
                    }
                } else if (panel.tab == 0) itemsIndexed(host.tableOfContents) { index, entry ->
                    Pressable(onClick = { host.openChapter(entry.chapter, entry.fragment); navigate(null) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = (6 + entry.depth.coerceIn(0, 3) * 16).dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            BodyText(entry.title, size = 16, maxLines = Int.MAX_VALUE, modifier = Modifier.weight(1f),
                                weight = if (index == current) FontWeight.Bold else FontWeight.Normal)
                            if (index == current) LucideIcon("check", Modifier.size(20.dp))
                        }
                    }
                } else {
                    version
                    val bookmarks = host.bookmarkList()
                    if (bookmarks.isEmpty()) item {
                        BodyText("No bookmarks yet. Use the bookmark button at the top of the page.", muted = true, size = 14, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        items(bookmarks) { bookmark ->
                            Pressable(onClick = { host.openBookmark(bookmark); navigate(null) },
                                onLongClick = { host.deleteBookmark(bookmark); version++ }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LucideIcon("bookmark", Modifier.size(22.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        BodyText(bookmark.optString("text"), weight = FontWeight.SemiBold, size = 16, maxLines = Int.MAX_VALUE)
                                        BodyText("${bookmark.optInt("percent")}%", muted = true, size = 12)
                                    }
                                }
                            }
                        }
                        item { BodyText("Long-press a bookmark to delete it.", muted = true, size = 12, modifier = Modifier.padding(vertical = 12.dp)) }
                    }
                }
                ReaderPanel.Footnotes -> {
                    val notes = host.pageFootnotes()
                    if (notes.isEmpty()) item { BodyText("No footnotes on this page.", muted = true, size = 14, modifier = Modifier.padding(vertical = 16.dp)) }
                    items(notes) { (label, href) -> FootnoteRow(host, label, href, navigate) }
                }
                is ReaderPanel.Dictionaries -> {
                    items(host.dictionaryChoices()) { name ->
                        version
                        SelectRow(name, "", name == host.dictionary) { host.dictionary = name; version++ }
                    }
                    item {
                        BodyText("Unzip a StarDict dictionary into its own folder in /dictionaries on this device, " +
                            "or send the .zip or .tar.gz to Dictionaries with Wi-Fi transfer.", muted = true, size = 12, maxLines = 4,
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                is ReaderPanel.Fonts -> {
                    items(host.fontChoices()) { name ->
                        version
                        val source = host.fontSource(name)
                        FontRow(name, host.fontTypeface(name), source, name == settings.fontFamily,
                            onClick = { settings.fontFamily = name; changed(null) },
                            onLongClick = if (source == "Sent with Wi-Fi transfer") ({ host.removeFont(name); version++ }) else null)
                    }
                    item {
                        BodyText("Copy .ttf or .otf files into /fonts on this device, or send them with Wi-Fi transfer. Long-press a transferred font to remove it.",
                            muted = true, size = 12, maxLines = 3, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                is ReaderPanel.Clipping -> {
                    item { BodyText(panel.item.optString("text"), size = 16, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(vertical = 16.dp)) }
                    item { ActionRow("Go to Passage", "", "book_open") { host.openClipping(panel.item); navigate(null) } }
                    item { ActionRow("Delete Clipping", "", "trash_2") { host.deleteClipping(panel.item); navigate(ReaderPanel.Contents(2)) } }
                }
                is ReaderPanel.Definition -> {
                    val entry = panel.entry
                    if (entry == null) item {
                        BodyText("No definition for “${panel.word}” in ${host.dictionary}.", muted = true, size = 14, maxLines = 3, modifier = Modifier.padding(vertical = 16.dp))
                    } else item {
                        val foreground = colors.onBackground.toArgb()
                        AndroidView({ android.widget.TextView(it).apply { textSize = 16f } }, Modifier.fillMaxWidth().padding(vertical = 16.dp)) { view ->
                            view.setTextColor(foreground)
                            view.text = if (entry.html) android.text.Html.fromHtml(entry.definition, android.text.Html.FROM_HTML_MODE_COMPACT) else entry.definition
                        }
                    }
                    item { ActionRow("Look Up in Dictionary App", "Translators and installed dictionaries", "search") { host.openDictionaryApp(panel.word) } }
                }
                is ReaderPanel.Note -> item { BodyText(panel.text, size = 16, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(vertical = 16.dp)) }

            }
        }
    }
}

@Composable
private fun Tabs(selected: Int, select: (Int) -> Unit) {
    val colors = CrossPointTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = CrossPointTheme.metrics.contentSidePadding)) {
        listOf("Chapters", "Bookmarks", "Clippings").forEachIndexed { value, label ->
            Pressable(onClick = { select(value) }, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BodyText(label, size = 16, weight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        muted = value != selected, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (value == selected) colors.onBackground else colors.background))
                }
            }
        }
    }
}

@Composable
private fun GoToDialog(initial: Int, dismiss: () -> Unit, go: (Int) -> Unit) {
    val colors = CrossPointTheme.colors
    var percent by remember { mutableIntStateOf(initial.coerceIn(0, 100)) }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().background(colors.background).border(1.dp, colors.divider).padding(16.dp)) {
            BodyText("Go to Position", size = 18, weight = FontWeight.Bold)
            BodyText("$percent%", size = 18, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
            AndroidView(factory = { context ->
                android.widget.SeekBar(context).apply {
                    max = 100
                    contentDescription = "Reading Position"
                    keyProgressIncrement = 1
                    isFocusableInTouchMode = true
                    setOnKeyListener { _, keyCode, event ->
                        val delta = when (keyCode) {
                            android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 1
                            android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> -1
                            else -> 0
                        }
                        if (delta == 0) false else {
                            if (event.action == android.view.KeyEvent.ACTION_DOWN) percent = (percent + delta).coerceIn(0, 100)
                            true
                        }
                    }
                    post { requestFocus() }
                    thumb = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(android.graphics.Color.BLACK)
                        val density = resources.displayMetrics.density
                        setSize((14 * density).toInt(), (24 * density).toInt())
                    }
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                            if (fromUser) percent = value
                        }
                        override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                        override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
                    })
                }
            }, modifier = Modifier.fillMaxWidth().height(48.dp), update = {
                it.progress = percent
                it.thumbTintList = android.content.res.ColorStateList.valueOf(colors.onBackground.toArgb())
                it.progressTintList = android.content.res.ColorStateList.valueOf(colors.onBackground.toArgb())
                it.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(colors.muted.toArgb())
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                listOf("Cancel" to dismiss, "Go" to { go(percent) }).forEach { (label, action) ->
                    Pressable(onClick = action) {
                        Box(Modifier.height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            BodyText(label, size = 14, weight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FootnoteRow(host: ReaderPanelHost, label: String, href: String, navigate: (ReaderPanel?) -> Unit) {
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(false) }
    ActionRow(label, if (missing) "Footnote not found" else "", "file_text") {
        scope.launch { host.footnoteText(href)?.let { navigate(ReaderPanel.Note(label, it)) } ?: run { missing = true } }
    }
}

@Composable
private fun FontRow(name: String, typeface: android.graphics.Typeface, source: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = CrossPointTheme.colors
    Pressable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(name, color = colors.onBackground, fontSize = 20.sp, maxLines = 1,
                    fontFamily = androidx.compose.ui.text.font.FontFamily(typeface))
                androidx.compose.material3.Text("The quick brown fox jumps over the lazy dog", color = colors.muted, fontSize = 14.sp, maxLines = 1,
                    fontFamily = androidx.compose.ui.text.font.FontFamily(typeface))
                BodyText(source, muted = true, size = 12)
            }
            com.crosspoint.launcher.ui.settings.RadioDot(selected)
        }
    }
}
