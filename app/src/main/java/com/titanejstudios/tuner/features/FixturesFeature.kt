package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.integrations.Espn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Every fixture this week across the competitions Flicker knows (football leagues and cups, rugby,
 * NFL, NBA, NHL, MLB), from ESPN – for the Sports page's "All fixtures". Kept for half an hour.
 */
class FixturesFeature(private val scope: CoroutineScope) {
    class Entry(val fixture: Espn.Fixture, val sport: Espn.SportChoice)

    var entries by mutableStateOf<List<Entry>?>(null); private set
    var loading by mutableStateOf(false); private set
    private var loadedAt = 0L

    /** Every competition with its sport. */
    private fun competitions(): List<Triple<String, String, Espn.SportChoice>> {
        val football = Espn.SPORTS.first()
        return Espn.SPORTS.flatMap { s -> s.leagues.map { (lg, name) -> Triple(lg, name, s) } } +
            Espn.CUPS.map { (lg, name) -> Triple(lg, name, football) }
    }

    /** Why nothing came, in plain words (shown on the page), or null. */
    var problem by mutableStateOf<String?>(null); private set
    /** "12 of 35 competitions" while loading. */
    var progress by mutableStateOf<String?>(null); private set

    fun load(force: Boolean = false) {
        if (loading) return
        if (!force && entries != null && System.currentTimeMillis() - loadedAt < 30 * 60_000L) return
        loading = true
        problem = null
        scope.launch {
            val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.UK)
            val now = System.currentTimeMillis()
            val days = (-1..7).map { dayFmt.format(now + it * 86_400_000L) }
            val comps = competitions()
            val gate = Semaphore(6)
            val failures = java.util.concurrent.atomic.AtomicInteger(0)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            var firstError: String? = null
            val found = java.util.Collections.synchronizedList(ArrayList<Entry>())
            fun publish() {
                entries = tidy(found.toList(), now)
            }
            withContext(Dispatchers.IO) {
                comps.map { (lg, name, sport) ->
                    async {
                        gate.withPermit {
                            val list = runCatching { Espn.scoreboard(lg, name, days.first(), days.last()) }
                                .recoverCatching {
                                    // Some competitions don't take a range of dates: one day at a time.
                                    days.flatMap { d -> runCatching { Espn.scoreboardDay(lg, name, d) }.getOrDefault(emptyList()) }
                                        .ifEmpty { throw it }
                                }
                            list.onSuccess { l -> found += l.map { Entry(it, sport) } }
                                .onFailure { e ->
                                    failures.incrementAndGet()
                                    if (firstError == null) firstError = "$name: ${e.message ?: e.javaClass.simpleName}"
                                }
                            progress = "${done.incrementAndGet()} of ${comps.size} competitions"
                            // Show what's here so far.
                            withContext(Dispatchers.Main) { publish() }
                        }
                    }
                }.awaitAll()
            }
            publish()
            progress = null
            loadedAt = System.currentTimeMillis()
            loading = false
            val failed = failures.get()
            if (failed > 0) AppLog.warn("All fixtures: $failed of ${comps.size} competition(s) couldn't be read from ESPN – first: $firstError")
            if (entries.isNullOrEmpty()) {
                problem = if (failed == comps.size) "Couldn't reach ESPN for the fixtures ($firstError). Check the internet connection – if a VPN is on, try with it off."
                else "ESPN didn't list any matches for this week."
            }
        }
    }

    /** From three hours ago (still on, or just finished) to a week ahead, the same match once, soonest first. */
    private fun tidy(all: List<Entry>, now: Long): List<Entry> =
        all.filter { it.fixture.kickoff > now - 3 * 3_600_000L && !it.fixture.finished }
            .distinctBy { it.fixture.home + "|" + it.fixture.away + "|" + it.fixture.kickoff / 3_600_000L }
            .sortedBy { it.fixture.kickoff }
}
