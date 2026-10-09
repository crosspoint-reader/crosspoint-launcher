package com.crosspoint.launcher.ui.stats

import com.crosspoint.launcher.ui.components.ScrollList
import android.content.Intent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosspoint.launcher.SyncAccountActivity
import com.crosspoint.launcher.data.SyncRepository
import com.crosspoint.launcher.ui.components.*
import com.crosspoint.launcher.ui.settings.ActionRow
import com.crosspoint.launcher.ui.theme.CrossPointTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun readingDays(summary: JSONObject, activity: JSONObject): Set<LocalDate> {
    val result = mutableSetOf<LocalDate>()
    val dates = activity.optJSONArray("reading_days")
    if (dates != null) for (i in 0 until dates.length()) {
        runCatching { LocalDate.parse(dates.getString(i)) }.getOrNull()?.let(result::add)
    }
    activity.optJSONArray("days").objects().forEach {
        runCatching { LocalDate.parse(it.getString("day")) }.getOrNull()?.let(result::add)
    }
    val history = runCatching { Base64.decode(summary.optString("history_b64"), Base64.DEFAULT) }.getOrDefault(byteArrayOf())
    val anchor = LocalDate.of(2000, 1, 1).plusDays(summary.optLong("anchor_day"))
    for (i in 0 until minOf(history.size * 8, 730)) {
        if ((history[i / 8].toInt() shr (i % 8)) and 1 != 0) result.add(anchor.minusDays(i.toLong()))
    }
    return result.filterTo(mutableSetOf()) { !it.isAfter(LocalDate.now()) }
}

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SyncRepository(context.applicationContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf(repo.memoryReadingStats()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        data = null
        loading = true
        error = null
        if (repo.connected) refresh++ else onBack()
    }
    fun signIn(reauthenticate: Boolean = false) {
        login.launch(Intent(context, SyncAccountActivity::class.java)
            .putExtra("onboarding", true).putExtra("reauthenticate", reauthenticate))
    }
    LaunchedEffect(refresh) {
        if (!repo.connected) { signIn(); return@LaunchedEffect }
        loading = true
        error = null
        val account = repo.accountScope
        try {
            val cached = withContext(Dispatchers.IO) { repo.cachedReadingStats() }
            if (repo.accountScope == account && cached != null) data = cached
            val loaded = withContext(Dispatchers.IO) { repo.readingStats() }
            if (repo.accountScope == account) data = loaded else data = null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load reading stats"
        } finally { loading = false }
    }
    val colors = CrossPointTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        ScreenHeader("Reading Stats", onBack) {
            HeaderIconButton("rotate_cw", { if (!loading) refresh++ })
        }
        if (data != null) {
            StatsContent(data!!, error)
        } else if (loading || error == null) {
            StatsSkeleton()
        } else {
            Column(Modifier.fillMaxSize().padding(CrossPointTheme.metrics.contentSidePadding)) {
                BodyText(error.orEmpty(), size = 16, maxLines = Int.MAX_VALUE)
                ActionRow("Try Again", "", "rotate_cw") { refresh++ }
                ActionRow("Sign In", "", "crosspoint") { signIn(true) }
            }
        }
    }
}

/** Static placeholders avoid shimmer and repeated e-ink refreshes. */
@Composable
private fun StatsSkeleton() {
    val shade = CrossPointTheme.colors.onBackground.copy(alpha = 0.14f)
    Column(Modifier.fillMaxSize().padding(CrossPointTheme.metrics.contentSidePadding)
        .semantics { contentDescription = "Loading Reading Stats" }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceAround) {
            repeat(2) { Box(Modifier.width(88.dp).height(20.dp).background(shade)) }
        }
        repeat(3) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                repeat(2) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.width(72.dp).height(30.dp).background(shade))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(104.dp).height(16.dp).background(shade))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(132.dp).height(22.dp).background(shade))
        Spacer(Modifier.height(16.dp))
        repeat(5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { Box(Modifier.weight(1f).height(28.dp).background(shade)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun StatsContent(data: JSONObject, error: String? = null) {
    val summary = data.getJSONObject("summary")
    val activity = data.getJSONObject("activity")
    val library = data.optJSONArray("books").objects()
    val titles = library.associate { it.optString("document") to it.optString("title").ifBlank { it.optString("filename", "Untitled") } }
    val books = activity.optJSONArray("books").objects().filter { titles.containsKey(it.optString("document")) }
    val days = remember(data) { readingDays(summary, activity) }
    val finished = books.filter { it.optLong("finished_at") > 0 }
    val timeAvailable = (summary.optJSONArray("devices")?.length() ?: 0) > 0
    var timeline by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = CrossPointTheme.metrics.contentSidePadding)) {
        listOf(false to "Overview", true to "Timeline").forEach { (selected, label) ->
            Pressable(onClick = { timeline = selected }, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BodyText(label, size = 16, weight = if (timeline == selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(
                        if (timeline == selected) CrossPointTheme.colors.divider else CrossPointTheme.colors.background))
                }
            }
        }
    }
    ScrollList(Modifier.fillMaxSize(), contentPadding = PaddingValues(CrossPointTheme.metrics.contentSidePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error != null) item { BodyText("Could not refresh. $error", muted = true, maxLines = Int.MAX_VALUE) }
        if (!timeline) {
            item {
                val totals = listOf(
                    "Pages Read" to books.sumOf { it.optLong("pages_read") }.toString(),
                    "Books Finished" to finished.size.toString(),
                    "Reading Now" to library.count { it.optString("status") == "reading" }.toString(),
                    "Reading Days" to days.size.toString(),
                    "Finished This Year" to finished.count {
                        java.time.Instant.ofEpochSecond(it.optLong("finished_at")).atZone(java.time.ZoneId.systemDefault()).year == LocalDate.now().year
                    }.toString(),
                    "Pages per Book" to books.filter { it.optLong("page_count") > 0 }.let { known ->
                        if (known.isEmpty()) "—" else (known.sumOf { it.optLong("page_count") } / known.size).toString()
                    }
                ) + if (timeAvailable) listOf(
                    "Time Read" to duration(summary.optLong("seconds")),
                    "Current Streak" to "${summary.optInt("current_streak")} days",
                    "Longest Streak" to "${summary.optInt("streak")} days",
                    "Sessions" to summary.optInt("sessions").toString()
                ) else emptyList()
                totals.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { (label, value) ->
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                BodyText(value, size = 24, weight = FontWeight.Bold, maxLines = 2)
                                BodyText(label, size = 14, muted = true)
                            }
                        }
                    }
                }
            }
            item { ReadingCalendar(days) }
            item {
                val today = LocalDate.now()
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                val daily = activity.optJSONArray("days").objects()
                val weeks = (7 downTo 0).map { monday.minusWeeks(it.toLong()) }
                StatBars("Weekly Pages", weeks.map { it.format(DateTimeFormatter.ofPattern("MMM d")) },
                    weeks.map { start -> daily.filter { it.optString("day") >= start.toString() && it.optString("day") < start.plusWeeks(1).toString() }
                        .sumOf { it.optLong("pages") } })
                BodyText("Print-page estimates based on synced progress and known book lengths.", muted = true, size = 12, maxLines = Int.MAX_VALUE,
                    modifier = Modifier.padding(top = 8.dp))
            }
            if (timeAvailable) {
                item { StatBars("Time of Day", listOf("Morning", "Afternoon", "Evening", "Night"),
                    (0..3).map { summary.optJSONArray("tod")?.optLong(it) ?: 0 }, true) }
                item { StatBars("Day of Week", listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
                    (0..6).map { summary.optJSONArray("dow")?.optLong(it) ?: 0 }, true) }
            }
            if (finished.isNotEmpty()) item { SectionTitle("Finished Books") }
            items(finished.sortedByDescending { it.optLong("finished_at") }) { book ->
                BodyText(titles[book.optString("document")].orEmpty(), size = 16, maxLines = Int.MAX_VALUE)
                BodyText(java.time.Instant.ofEpochSecond(book.optLong("finished_at")).atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy")), muted = true, size = 12)
            }
            if (books.isEmpty() && days.isEmpty()) item {
                BodyText("Your reading activity will appear here as your books sync.", size = 16, maxLines = Int.MAX_VALUE)
            }
        } else {
            val entries = activity.optJSONArray("days").objects().asReversed()
            if (entries.isEmpty()) item { BodyText("No reading activity yet.", size = 16, maxLines = Int.MAX_VALUE) }
            items(entries) { day ->
                SectionTitle(day.optString("day"))
                day.optJSONArray("books").objects().forEach { book ->
                    val title = titles[book.optString("document")] ?: return@forEach
                    BodyText(title, size = 16, weight = FontWeight.SemiBold, maxLines = Int.MAX_VALUE,
                        modifier = Modifier.padding(top = 10.dp))
                    val percent = (book.optDouble("to", 0.0) * 100).toInt().coerceIn(0, 100)
                    BodyText("${book.optInt("pages")} pages · $percent%", muted = true, size = 14)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(CrossPointTheme.colors.divider))
        BodyText(title, size = 18, weight = FontWeight.Bold, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    }
}

@Composable
private fun ReadingCalendar(days: Set<LocalDate>) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val colors = CrossPointTheme.colors
    Column {
        SectionTitle("Reading Days")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton("chevron_left", { month = month.minusMonths(1) })
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BodyText(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), size = 16)
            }
            HeaderIconButton("chevron_right", { if (month < YearMonth.now()) month = month.plusMonths(1) })
        }
        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
            Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) { BodyText(label, size = 12, muted = true) }
        } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        for (week in 0 until (offset + month.lengthOfMonth() + 6) / 7) Row {
            for (weekday in 0..6) {
                val day = week * 7 + weekday - offset + 1
                val date = if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
                val read = date in days
                Box(Modifier.weight(1f).padding(2.dp).height(28.dp)
                    .background(if (read) colors.onBackground else colors.background)
                    .then(if (date == LocalDate.now()) Modifier.border(1.dp, colors.divider) else Modifier),
                    contentAlignment = Alignment.Center) {
                    if (date != null) BodyText(day.toString(), size = 12, color = if (read) colors.background else colors.onBackground)
                }
            }
        }
        BodyText("Filled days = reading · ${days.count { YearMonth.from(it) == month }} this month", size = 12, muted = true,
            maxLines = Int.MAX_VALUE, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatBars(title: String, labels: List<String>, values: List<Long>, time: Boolean = false) {
    val colors = CrossPointTheme.colors
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        SectionTitle(title)
        labels.forEachIndexed { index, label ->
            val value = values[index]
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                BodyText(label, size = 12, modifier = Modifier.width(76.dp))
                Box(Modifier.weight(1f).height(8.dp).border(1.dp, colors.divider)) {
                    if (value > 0) Box(Modifier.fillMaxWidth((value.toFloat() / max).coerceIn(0f, 1f)).fillMaxHeight().background(colors.onBackground))
                }
                BodyText(if (time) duration(value) else value.toString(), size = 12,
                    modifier = Modifier.padding(start = 8.dp).width(64.dp))
            }
        }
    }
}

private fun duration(seconds: Long): String =
    if (seconds >= 3600) "${seconds / 3600}h ${seconds % 3600 / 60}m" else "${seconds / 60}m"
