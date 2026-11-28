package com.titanejstudios.tuner

import androidx.lifecycle.viewModelScope
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.integrations.Espn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Part of TunerViewModel: the Sports page's "All fixtures" – which of your channels show a match.

/** Short names British guides use for some clubs, and the full ones (both ways). */
private val ALIASES = listOf(
    "Man Utd" to "Manchester United", "Man United" to "Manchester United", "Man City" to "Manchester City",
    "Spurs" to "Tottenham Hotspur", "Tottenham" to "Tottenham Hotspur", "Wolves" to "Wolverhampton Wanderers",
    "Nott'm Forest" to "Nottingham Forest", "Nottm Forest" to "Nottingham Forest", "Forest" to "Nottingham Forest",
    "Sheff Utd" to "Sheffield United", "Sheff Wed" to "Sheffield Wednesday", "QPR" to "Queens Park Rangers",
    "West Brom" to "West Bromwich Albion", "Brighton" to "Brighton and Hove Albion", "Newcastle" to "Newcastle United",
    "West Ham" to "West Ham United", "Leeds" to "Leeds United", "Ipswich" to "Ipswich Town", "Coventry" to "Coventry City",
    "Leicester" to "Leicester City", "Norwich" to "Norwich City", "Stoke" to "Stoke City", "Hull" to "Hull City",
    "Bayern" to "Bayern Munich", "PSG" to "Paris Saint-Germain", "Inter" to "Inter Milan", "Atletico" to "Atletico Madrid",
)

/** The names a team might have in the guide ("Man Utd", "Manchester United", without "FC", "&" or "and"). */
internal fun teamNames(vararg names: String): List<String> {
    val base = names.map { it.trim() }.filter { it.isNotEmpty() }
    val withAliases = base + base.flatMap { n -> ALIASES.filter { (a, b) -> a.equals(n, true) || b.equals(n, true) }.flatMap { listOf(it.first, it.second) } }
    return withAliases.flatMap { n ->
        val plain = n.removeSuffix(" FC").removeSuffix(" AFC").removePrefix("AFC ").trim()
        listOf(n, plain, plain.replace(" & ", " and "), plain.replace(" and ", " & "))
    }.filter { it.length >= 3 }.distinctBy { it.lowercase() }
}

/**
 * Every channel of yours with this match on (both teams named, on at kick-off), best first:
 * your favourites, live broadcasts, and the one starting nearest kick-off. Event-channel listings
 * ("Next: A v B — Sun 29 Nov 16:30", "EPL02: Man Utd 15:00 Brentford 28/11") count too. Blocking.
 */
internal fun TunerViewModel.channelsShowing(m: Espn.Fixture): List<Pair<Channel, Programme>> {
    val ids = activeSources().map { it.id }
    val chans = allowedAll
    val byId = chans.associateBy { it.id }
    val favs = favouriteSet
    val homes = teamNames(m.home, m.homeFull)
    val aways = teamNames(m.away, m.awayFull)
    // Listings from a day before (all-day event channels) up to a couple of hours after kick-off.
    val from = m.kickoff - 30 * 3_600_000L
    val hits = HashMap<String, MutableList<Programme>>()
    val seen = HashSet<String>()
    for (term in (homes + aways)) {
        for ((cid, p) in db.searchProgrammes(ids, term, from, 400)) {
            if (cid !in byId || p.start > m.kickoff + 2 * 3_600_000L) continue
            if (seen.add(cid + "@" + p.start)) hits.getOrPut(cid) { ArrayList() } += p
        }
    }
    val guide = shiftGuide(chans, hits)
    val kickTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(m.kickoff))
    val now = System.currentTimeMillis()
    val found = ArrayList<Triple<Channel, Programme, Int>>()
    for ((cid, list) in guide) {
        val c = byId[cid] ?: continue
        for (raw in list) {
            val both = homes.any { SportFollow.mentions(raw.title, it) } && aways.any { SportFollow.mentions(raw.title, it) }
            if (!both) continue
            // Event-channel listings say when the match is; otherwise the programme itself must be on at kick-off.
            val p = Sports.normalise(raw, now)
                ?: if (raw.title.contains(kickTime)) raw.copy(start = m.kickoff, stop = m.kickoff + 2 * 3_600_000L) else continue
            if (p.stop <= m.kickoff || p.start > m.kickoff + 30 * 60_000L) continue
            var score = 100
            if (c.id in favs) score += 20
            if (Sports.looksLive(p)) score += 10
            if (p.title.startsWith("Next", ignoreCase = true)) score -= 15
            score -= (kotlin.math.abs(p.start - (m.kickoff - 30 * 60_000L)) / 60_000L).toInt().coerceAtMost(60) / 10
            found += Triple(c, p, score)
        }
    }
    return found.sortedByDescending { it.third }.distinctBy { it.first.id }.map { it.first to it.second }
}

/** Clicked a fixture: find its channels, then choose one (to watch now, or a reminder for later). */
fun TunerViewModel.chooseFixtureChannel(m: Espn.Fixture) {
    viewModelScope.launch {
        val all = withContext(Dispatchers.IO) {
            runCatching { channelsShowing(m) }.onFailure { AppLog.warn("All fixtures: couldn't search the guide", it) }.getOrDefault(emptyList())
        }
        val guideEnd = withContext(Dispatchers.IO) { runCatching { db.guideEnd(activeSources().map { it.id }) }.getOrDefault(0L) }
        if (all.isEmpty()) {
            showNotice(
                if (guideEnd > 0 && m.kickoff > guideEnd) "${m.home} v ${m.away} isn't in your TV guide yet – the guide doesn't reach that day"
                else "${m.home} v ${m.away} isn't on any of your channels",
            )
            return@launch
        }
        fixtureChoice = TeamFixture(m, all.first().first, all.first().second, true, all)
    }
}
