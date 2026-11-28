package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.recordProgramme
import com.titanejstudios.tuner.recordingFor
import com.titanejstudios.tuner.stopRecording
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ALL
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.Sports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import com.titanejstudios.tuner.chooseFixtureChannel
import kotlinx.coroutines.withContext
import java.util.Calendar

/** One match/event, possibly on several channels (HD/SD/regional feeds). */
private class SportEvent(val programme: Programme, val type: String, val channels: List<Channel>, val live: Boolean)

private fun endOfDay(t: Long, plusDays: Int): Long = Calendar.getInstance().apply {
    timeInMillis = t
    add(Calendar.DAY_OF_YEAR, plusDays)
    set(Calendar.HOUR_OF_DAY, 23)
    set(Calendar.MINUTE, 59)
    set(Calendar.SECOND, 59)
}.timeInMillis

@Composable
fun SportsScreen(vm: TunerViewModel) {
    val now = vm.now
    val pool = remember(vm.channels, vm.unlocked, vm.favourites) { vm.channelsFor(ALL) }
    var events by remember { mutableStateOf<List<SportEvent>?>(null) }
    LaunchedEffect(vm.epg, pool, now / 300_000) {
        val guide = vm.epg
        val favs = vm.favouriteSet
        events = withContext(Dispatchers.Default) {
            val until = endOfDay(now, 1)
            val found = LinkedHashMap<String, MutableList<Pair<Channel, Programme>>>()
            val types = HashMap<String, String>()
            for (c in pool) {
                val list = guide[c.id] ?: continue
                for (raw in list) {
                    // Event channels list "Next: A v B — Sun 29 Nov 16:30" all day; turn that into the real event.
                    val p = Sports.normalise(raw, now) ?: continue
                    if (p.stop <= now || p.start > until) continue
                    val type = Sports.classify(p, c.group) ?: continue
                    // "Arsenal vs Man City" on one channel and "Arsenal v Man City" on another are the same match.
                    val key = p.title.lowercase().replace(Regex("\\s+"), " ").replace(" vs. ", " v ").replace(" vs ", " v ").trim() +
                        "@" + p.start / 60_000
                    found.getOrPut(key) { ArrayList() } += c to p
                    types[key] = type
                }
            }
            found.map { (key, hits) ->
                // Favourite channels first, then the order of the channel list.
                val chans = hits.map { it.first }.sortedBy { if (it.id in favs) 0 else 1 }
                val p = hits.first().second
                val onNow = p.start <= now && p.stop > now
                SportEvent(p, types[key] ?: "Other", chans.distinctBy { it.id }, onNow && Sports.looksLive(p))
            }.sortedWith(compareBy({ it.programme.start }, { if (it.live) 0 else 1 }))
        }
    }

    // "guide": sport in your TV guide today and tomorrow; "fixtures": every match this week (ESPN).
    var view by rememberSaveable { mutableStateOf("guide") }
    if (view == "fixtures") {
        AllFixtures(vm, onBack = { view = "guide" })
        return
    }
    var filter by remember { mutableStateOf("All") }
    var liveOnly by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SportEvent?>(null) }
    val restorer = rememberFocusRestorer()
    RestoreFocusWhenClosed(menuFor != null, restorer)
    val first = remember { FocusRequester() }
    LaunchedEffect(events != null) {
        delay(60)
        runCatching { first.requestFocus() }
    }

    val all = events
    val shown = all.orEmpty().filter { (filter == "All" || it.type == filter) && (!liveOnly || it.live) }
    val counts = all.orEmpty().groupingBy { it.type }.eachCount()
    val todayEnd = endOfDay(now, 0)

    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = if (narrowScreen()) 12.dp else 48.dp, vertical = if (narrowScreen()) 10.dp else 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sport", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Text("Today and tomorrow, from your TV guide", fontSize = 16.sp, color = TunerColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PillButton("All (${all?.size ?: 0})", { filter = "All" }, Modifier.focusRequester(first), selected = filter == "All", compact = true)
            }
            item(key = "fixtures") {
                PillButton("📅 All fixtures this week", { view = "fixtures" }, compact = true)
            }
            items(Sports.TYPES.filter { (counts[it] ?: 0) > 0 }, key = { it }) { t ->
                PillButton("$t (${counts[t]})", { filter = t }, selected = filter == t, compact = true)
            }
            item {
                PillButton(if (liveOnly) "Live only ✓" else "Live only", { liveOnly = !liveOnly }, selected = liveOnly, compact = true)
            }
            item(key = "teams") {
                PillButton("★ My teams", { vm.navigate(com.titanejstudios.tuner.Screen.Teams) }, compact = true)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (all == null) {
            CenterMessage("Looking through the guide…")
            return@Column
        }
        if (shown.isEmpty()) {
            CenterMessage("No sport found in the guide for now.\nIt depends on your provider's guide listing sport properly.")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val onNow = shown.filter { it.programme.start <= now }
            val today = shown.filter { it.programme.start in (now + 1)..todayEnd }
            val tomorrow = shown.filter { it.programme.start > todayEnd }
            listOf("On now" to onNow, "Later today" to today, "Tomorrow" to tomorrow).forEach { (title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h-$title") { SectionTitle(title) }
                    items(list.distinctBy { it.programme.title + it.programme.start + it.channels.first().id }, key = { it.programme.title + it.programme.start + it.channels.first().id }) { ev ->
                        SportRow(vm, ev, now, restorer) {
                            restorer.key = ev.programme.title + ev.programme.start
                            menuFor = ev
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { ev ->
        val p = ev.programme
        val t = System.currentTimeMillis()
        val items = buildList {
            ev.channels.forEach { ch ->
                if (p.start <= t) add(MenuItem("Watch on ${ch.name}") { vm.play(ch, pool) })
            }
            val main = ev.channels.first()
            if (p.start > t) add(MenuItem(if (vm.hasReminder(main, p)) "Cancel reminder" else "Remind me") { vm.toggleReminder(main, p) })
            if (!vm.activeProfile.kids) {
                val rec = vm.recordingFor(main, p)
                if (rec == null) add(MenuItem("● Record") { vm.recordProgramme(main, p) })
                else add(MenuItem("Cancel recording") { vm.stopRecording(rec) })
                add(MenuItem(if (vm.seriesLinkFor(main, p) != null) "Cancel series link" else "🔁 Series link (record every one)") { vm.toggleSeriesLink(main, p) })
            }
        }
        MenuDialog(p.title, items, onDismiss = { menuFor = null }, subtitle = "${dayLabel(p.start)} ${hhmm(p.start)} – ${hhmm(p.stop)} · ${ev.type}")
    }
}

@Composable
private fun SportRow(vm: TunerViewModel, ev: SportEvent, now: Long, restorer: FocusRestorer, onMenu: () -> Unit) {
    val p = ev.programme
    val main = ev.channels.first()
    val onNow = p.start <= now && p.stop > now
    val reminder = p.start > now && vm.hasReminder(main, p)
    val recording = vm.recordingFor(main, p) != null
    val key = p.title + p.start
    FocusSurface(
        memoryKey = "sp|$key",
        onClick = {
            if (onNow) vm.play(main, vm.channelsFor(ALL)) else vm.toggleReminder(main, p)
        },
        onLongClick = onMenu,
        baseColor = TunerColors.Panel,
        modifier = Modifier.fillMaxWidth().height(74.dp).then(restorer.mod(key)),
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(if (narrowScreen()) 64.dp else 120.dp)) {
                Text(
                    if (onNow) "On now" else hhmm(p.start),
                    color = if (onNow) TunerColors.NowLine else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(dayLabel(p.start), color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = 13.sp)
            }
            ChannelLogo(main.logo, main.name, Modifier.size(width = 64.dp, height = 42.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (recording) "● " else if (reminder) "⏰ " else "") + p.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ev.live) {
                        Spacer(Modifier.width(8.dp))
                        Text("LIVE", color = TunerColors.NowLine, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    main.name + (if (ev.channels.size > 1) "  +${ev.channels.size - 1} more" else "") + "  ·  ${ev.type}",
                    color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                if (onNow) ProgressBar(progressOf(p, now), Modifier.padding(top = 4.dp).width(if (narrowScreen()) 120.dp else 200.dp), color = if (focused) Color.White else TunerColors.NowLine)
            }
            Text(
                when {
                    onNow -> "OK to watch"
                    reminder -> "OK to cancel reminder"
                    else -> "OK to remind me"
                },
                color = if (focused) Color.White else TunerColors.TextSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * Every match this week, from ESPN (football leagues and cups, rugby, NFL, NBA, NHL, MLB), by day.
 * Choosing one finds it in your TV guide: pick the channel to watch it on, or for a reminder.
 */
@Composable
private fun AllFixtures(vm: TunerViewModel, onBack: () -> Unit) {
    val feature = vm.allFixtures
    LaunchedEffect(Unit) { feature.load() }
    val now = vm.now
    var sport by rememberSaveable { mutableStateOf("all") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val narrow = narrowScreen()
    val entries = feature.entries
    val sports = entries.orEmpty().groupingBy { it.sport.key }.eachCount()
    val shown = entries.orEmpty().filter { sport == "all" || it.sport.key == sport }
    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().skyBackground().padding(horizontal = if (narrow) 12.dp else 48.dp, vertical = if (narrow) 10.dp else 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All fixtures", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Text("This week · choose one to pick its channel", fontSize = 16.sp, color = TunerColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "back") { PillButton("← Sport in the guide", onBack, Modifier.focusRequester(first), compact = true) }
            item(key = "all") { PillButton("All (${entries?.size ?: 0})", { sport = "all" }, selected = sport == "all", compact = true) }
            items(com.titanejstudios.tuner.integrations.Espn.SPORTS.filter { (sports[it.key] ?: 0) > 0 }, key = { it.key }) { s ->
                PillButton("${s.emoji} ${s.label} (${sports[s.key]})", { sport = s.key }, selected = sport == s.key, compact = true)
            }
            item(key = "refresh") { PillButton(if (feature.loading) "⟳ " + (feature.progress ?: "") else "↻ Refresh", { feature.load(force = true) }, compact = true) }
        }
        Spacer(Modifier.height(14.dp))
        when {
            entries.isNullOrEmpty() && feature.loading -> { CenterMessage("Getting this week's fixtures… " + (feature.progress ?: "")); return@Column }
            shown.isEmpty() -> { CenterMessage(feature.problem ?: "No fixtures found this week."); return@Column }
        }
        val todayEnd = endOfDay(now, 0)
        val tomorrowEnd = endOfDay(now, 1)
        val byDay = shown.groupBy { e ->
            val k = e.fixture.kickoff
            when {
                e.fixture.live || k <= now -> "On now"
                k <= todayEnd -> "Today"
                k <= tomorrowEnd -> "Tomorrow"
                else -> dayLabel(k)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            byDay.forEach { (day, list) ->
                item(key = "h-$day") { Text(day, color = TunerColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                items(list, key = { it.fixture.id + it.fixture.kickoff }) { e ->
                    val f = e.fixture
                    val on = f.live || f.kickoff <= now
                    FixtureRow(
                        time = if (on) "ON NOW" else if (f.timeKnown) hhmm(f.kickoff) else "TBC",
                        live = on,
                        title = "${e.sport.emoji}  ${f.home} v ${f.away}",
                        detail = f.competition ?: e.sport.label,
                        narrow = narrow,
                        onClick = { vm.chooseFixtureChannel(f) },
                    )
                }
            }
        }
    }
}

