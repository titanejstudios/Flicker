package com.titanejstudios.tuner.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.SeriesItem
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import com.titanejstudios.tuner.integrations.Tmdb
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.integrations.GoogleDrive
import com.titanejstudios.tuner.integrations.Plex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Page(val label: String, val icon: String) {
    Search("Search", "🔍"), Home("Home", "⌂"), Live("Live TV", "📺"), Guide("TV Guide", "▦"), Catchup("Catch-up", "↺"), WhatsOn("What's On", "☰"), Sport("Sport", "⚽"),
    Multi("Multi-view", "⊞"), Films("Films", "🎬"), Series("Series", "🎞"),
    Plex("Plex", "▶"), YouTube("YouTube", "▷"), Recordings("Recordings", "●"), Settings("Settings", "⚙"),
}

/** What's playing in the big player (films, episodes, Plex, catch-up). Live TV plays on the Live page. */
class Watching(
    val id: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String,
    val seriesId: String?,
    /** Saved for Continue watching (not catch-up). */
    val resumable: Boolean,
    /** What to play when it ends (the next episode). */
    val next: (() -> Unit)? = null,
)

/** Everything the screens show, in one place. Runs on the UI thread; work happens on IO. */
class AppState(val config: Config, val player: Player, private val scope: CoroutineScope) {
    val sync = Sync(config)

    var page by mutableStateOf(Page.Home)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var status by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var problems by mutableStateOf<List<String>>(emptyList())

    var channels by mutableStateOf<List<Channel>>(emptyList())
    var guide by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
    var movies by mutableStateOf<List<VodItem>?>(null)
    var series by mutableStateOf<List<SeriesItem>?>(null)
    var vodLoading by mutableStateOf(false)

    var playingChannel by mutableStateOf<Channel?>(null)
    /** The channels in the list on Live TV (for channel up and down). */
    var liveList by mutableStateOf<List<Channel>>(emptyList())
    var catchupOf by mutableStateOf<Programme?>(null)
    var watching by mutableStateOf<Watching?>(null)
    var englishOnly by mutableStateOf(config.englishOnly)
    /** A series to open on the Series page (from Home's My list). */
    var openSeriesId by mutableStateOf<String?>(null)
    /** A film page to open (from the player's "Go to film page"). */
    var openFilmId by mutableStateOf<String?>(null)
    /** Something to search for (from the player's "More of…"). */
    var searchFor by mutableStateOf<String?>(null)

    // Lists that sync with the TV (bumped so screens redraw).
    var listsVersion by mutableStateOf(0)
    var profileId by mutableStateOf(config.profileKey)
    var askWhoIsWatching by mutableStateOf(false)

    // Google sign-in (for sync).
    var signInCode by mutableStateOf<GoogleDrive.DeviceCode?>(null)
    var syncStatus by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf(config.googleRefresh != null)

    // Plex.
    val plex = Plex(config.plexClientId)
    /** The server of the Plex film or episode that's playing. */
    var plexPlayingServer: Plex.Server? = null
    var plexLinked by mutableStateOf(config.plexToken != null)
    var plexCode by mutableStateOf<String?>(null)
    var plexServers by mutableStateOf<List<Plex.Server>>(emptyList())
    var plexDeck by mutableStateOf<List<Plex.Item>>(emptyList())
    var plexSections by mutableStateOf<List<Plex.Section>>(emptyList())
    var plexRecent by mutableStateOf<List<Plex.Entry>>(emptyList())

    // Reminders.
    var dueReminder by mutableStateOf<Reminder?>(null)

    private var saveJob: Job? = null

    fun say(message: String) {
        notice = message
        scope.launch {
            delay(4_000)
            if (notice == message) notice = null
        }
    }

    // ---------------- Profiles ----------------

    val profile: PcProfile? get() = config.profiles.firstOrNull { it.id == profileId }

    fun chooseProfile(p: PcProfile) {
        if (kids && !p.kids && config.pin != null && !grownUp) {
            askPin("Enter the PIN to switch to ${p.name}") { chooseProfile(p) }
            return
        }
        config.activeProfile = p.id
        config.save()
        profileId = p.id
        askWhoIsWatching = false
        listsVersion++
    }

    /** Kids see only their groups (and no films or series). */
    val visibleChannels: List<Channel>
        get() {
            val p = profile
            val list = managedChannels
            return if (p != null && p.kids) list.filter { it.group in p.groups || it.originalGroup() in p.groups } else list
        }

    // ---------------- Channels and categories (Settings › Manage channels) ----------------

    /** Bumped when hiding, renaming, ordering or locking changes. */
    var manageVersion by mutableStateOf(0)
    /** Locked groups opened with the PIN (until Flicker closes). */
    var unlockedGroups by mutableStateOf<Set<String>>(emptySet())
    private var managedKey: Any? = null
    private var managedCache: List<Channel> = emptyList()
    /** Renamed group → its name in the playlist. */
    private var originalNames: Map<String, String> = emptyMap()

    private fun Channel.originalGroup(): String = originalNames[group] ?: group

    /** Your channels: hidden ones and groups left out, groups renamed and in your order, locked groups shut. */
    val managedChannels: List<Channel>
        get() {
            val key = listOf(channels, manageVersion, unlockedGroups, grownUp)
            if (key == managedKey) return managedCache
            val names = config.groupNames
            val hiddenG = config.hiddenGroups
            val hiddenC = config.hiddenChannels
            val locked = config.lockedGroups - unlockedGroups
            val offline = if (config.hideOffline) config.offline else emptySet()
            val order = config.groupOrder
            originalNames = names.entries.associate { (k, v) -> v to k }
            val list = channels.asSequence()
                .filter { it.group !in hiddenG && it.id !in hiddenC && it.id !in offline && (grownUp || it.group !in locked) }
                .map { c -> names[c.group]?.let { c.copy(group = it) } ?: c }
                .toList()
            val rank = order.withIndex().associate { (i, g) -> g to i }
            val firstSeen = HashMap<String, Int>()
            list.forEachIndexed { i, c -> firstSeen.putIfAbsent(c.originalGroup(), i) }
            managedCache = list.sortedWith(compareBy({ rank[it.originalGroup()] ?: (order.size + (firstSeen[it.originalGroup()] ?: 0)) }))
            managedKey = key
            return managedCache
        }

    /** Every group in the playlists (original names), in your order. */
    val allGroups: List<String>
        get() {
            val order = config.groupOrder
            val seen = channels.map { it.group }.distinct()
            return order.filter { it in seen } + seen.filter { it !in order }
        }

    fun groupLabel(g: String) = config.groupNames[g] ?: g

    private fun managed(change: () -> Unit) {
        change()
        manageVersion++
        changed(sync = false)
    }

    fun toggleGroupHidden(g: String) = managed { config.hiddenGroups = if (g in config.hiddenGroups) config.hiddenGroups - g else config.hiddenGroups + g }
    fun toggleChannelHidden(id: String) = managed { config.hiddenChannels = if (id in config.hiddenChannels) config.hiddenChannels - id else config.hiddenChannels + id }

    fun renameGroup(g: String, name: String) = managed {
        val n = name.trim()
        config.groupNames = if (n.isEmpty() || n == g) config.groupNames - g else config.groupNames + (g to n)
    }

    fun moveGroup(g: String, by: Int) = managed {
        val list = allGroups.toMutableList()
        val i = list.indexOf(g)
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i >= 0 && i != j) {
            list.removeAt(i)
            list.add(j, g)
        }
        config.groupOrder = list
    }

    fun toggleGroupLocked(g: String) {
        val change = { managed { config.lockedGroups = if (g in config.lockedGroups) config.lockedGroups - g else config.lockedGroups + g } }
        if (config.pin == null) say("Set a PIN first (Settings › Profiles) – locked groups need it to open") else askPin("The PIN to change which groups are locked") { change() }
    }

    fun unlockGroups() {
        if (config.lockedGroups.isEmpty()) return
        askPin("The PIN to show locked groups") { unlockedGroups = config.lockedGroups; say("Locked groups shown until Flicker closes") }
    }

    fun resetChannelChanges() = managed {
        config.hiddenGroups = emptySet()
        config.hiddenChannels = emptySet()
        config.groupNames = emptyMap()
        config.groupOrder = emptyList()
    }

    /** "Use the guide from…": a channel with no guide borrows another's. */
    fun useGuideFrom(c: Channel, from: Channel?) {
        config.epgMap = if (from == null) config.epgMap - c.id else config.epgMap + (c.id to from.id)
        if (from == null) reload() else applyEpgMap()
        changed(sync = false)
        say(if (from == null) "${c.name} uses its own guide again" else "${c.name} now shows ${from.name}'s guide")
    }

    private fun applyEpgMap() {
        val map = config.epgMap
        if (map.isEmpty()) return
        val g = guide.toMutableMap()
        var any = false
        for ((cid, from) in map) guide[from]?.let { g[cid] = it; any = true }
        if (any) guide = g
    }

    // ---------------- Channel health ----------------

    var checking by mutableStateOf<String?>(null)

    /** Tries each channel (favourites first, then [group] or all) and notes the ones that don't answer. */
    fun checkChannels(group: String? = null) {
        if (checking != null) return
        val favs = favourites
        val list = (channels.filter { it.id in favs } + channels.filter { group == null || it.group == group }).distinctBy { it.id }.take(600)
        if (list.isEmpty()) return
        checking = "Checking 0 of ${list.size}…"
        scope.launch {
            val dead = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val done = java.util.concurrent.atomic.AtomicInteger()
            withContext(Dispatchers.IO) {
                list.chunked(8).forEach { batch ->
                    batch.map { c ->
                        launch {
                            if (!answers(c.url)) dead += c.id
                            val n = done.incrementAndGet()
                            if (n % 8 == 0) scope.launch { checking = "Checking $n of ${list.size}…" }
                        }
                    }.forEach { it.join() }
                }
            }
            val checkedIds = list.map { it.id }.toSet()
            config.offline = (config.offline - checkedIds) + dead
            config.offlineCheckedAt = System.currentTimeMillis()
            manageVersion++
            changed(sync = false)
            checking = null
            say(if (dead.isEmpty()) "All ${list.size} channels answered ✓" else "${dead.size} of ${list.size} channels didn't answer")
        }
    }

    private fun answers(url: String): Boolean = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 6_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "VLC/3.0.21 LibVLC/3.0.21")
            c.setRequestProperty("Range", "bytes=0-1023")
            val code = c.responseCode
            if (code !in 200..399) return@runCatching false
            c.inputStream.use { it.read(ByteArray(512)) } > 0
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    fun setHideOffline(on: Boolean) = managed { config.hideOffline = on }

    fun clearOffline() = managed { config.offline = emptySet() }

    /** Recently watched channels, newest first. */
    val recentChannels: List<Channel>
        get() {
            val byId = managedChannels.associateBy { it.id }
            return config.recents.mapNotNull { byId[it] }
        }

    val kids: Boolean get() = profile?.kids == true

    // ---------------- Favourites, My list, Continue watching ----------------

    val favourites: Set<String> get() = config.favourites[profileId].orEmpty().toSet()

    fun toggleFavourite(c: Channel) {
        val mine = config.favourites[profileId].orEmpty()
        config.favourites = config.favourites + (profileId to if (c.id in mine) mine - c.id else mine + c.id)
        config.favouritesAt = System.currentTimeMillis()
        changed()
    }

    val watchlist: List<WatchItem> get() = Watchlist.decode(config.watchlistJson)

    fun inWatchlist(id: String): Boolean = watchlist.any { it.id == id }

    fun toggleWatchlist(item: WatchItem) {
        config.watchlistJson = Watchlist.encode(Watchlist.toggle(watchlist, item))
        config.watchlistAt = System.currentTimeMillis()
        changed()
    }

    fun removeFromContinue(id: String) {
        config.resume = config.resume - id
        config.done = config.done + (id to System.currentTimeMillis())
        changed()
    }

    /** Part-watched films and episodes, newest first. */
    val continueWatching: List<Resume>
        get() = config.resume.values.filter { it.dur <= 0 || it.pos < it.dur * 95 / 100 }.sortedByDescending { it.at }

    /** Saves soon, and (unless it's just the every-10-seconds place in a film) syncs. */
    private fun changed(sync: Boolean = true) {
        listsVersion++
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_500)
            withContext(Dispatchers.IO) { config.save() }
            if (sync) syncSoon()
        }
    }

    // ---------------- Loading ----------------

    fun reload() {
        scope.launch {
            status = "Loading channels…"
            val loaded = withContext(Dispatchers.IO) {
                Library.load(config.sources, config.englishOnly, config.mergeVersions) { msg -> scope.launch { status = msg } }
            }
            channels = loaded.channels
            guide = loaded.guide
            versions = loaded.versions
            applyEpgMap()
            scheduleSeriesLinks()
            fillBackupGuide()
            problems = loaded.problems
            status = null
            now = System.currentTimeMillis()
            movies = null
            series = null
        }
    }

    // ---------------- Backup guide (remove with the module) ----------------

    /** "Use extra guide sources", as set on the TV (or here). */
    var backupGuideOn by mutableStateOf(runCatching { JSONObject(config.backupGuide).optBoolean("enabled") }.getOrDefault(false)); private set
    var backupGuideStatus by mutableStateOf<String?>(null)

    fun setBackupGuide(on: Boolean) {
        val o = runCatching { JSONObject(config.backupGuide) }.getOrDefault(JSONObject())
        config.backupGuide = o.put("enabled", on).put("at", System.currentTimeMillis()).toString()
        backupGuideOn = on
        config.save()
        if (on) fillBackupGuide() else reload()
    }

    /** Fills channels with no listings, and gaps, from the free guides – like the TV does. */
    fun fillBackupGuide() {
        backupGuideOn = runCatching { JSONObject(config.backupGuide).optBoolean("enabled") }.getOrDefault(false)
        if (!backupGuideOn) return
        val o = runCatching { JSONObject(config.backupGuide) }.getOrDefault(JSONObject())
        val core = com.titanejstudios.backupguide.BackupGuideCore
        val settings = com.titanejstudios.backupguide.BackupGuideCore.Settings(
            core.readList(o.optJSONArray("custom")?.toString()),
            core.readMap(o.optJSONObject("overrides")?.toString()),
            core.readMap(config.backupGuideRemembered).toMutableMap(),
        )
        val chans = channels
        val base = guide
        scope.launch {
            backupGuideStatus = "Filling gaps in the guide from free sources…"
            val t = System.currentTimeMillis()
            val from = t - 3 * 3_600_000L
            val to = t + 7 * 86_400_000L
            val added = HashMap<String, MutableList<Programme>>()
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    core.fill(
                        settings, chans.map { com.titanejstudios.backupguide.BackupGuideCore.Channel(it.id, it.epgId, it.name, it.group) }, from, to,
                        open = { url -> com.titanejstudios.tuner.data.Http.open(url.trim()) },
                        covered = { id ->
                            base[id]?.sortedBy { it.start }?.let { l -> LongArray(l.size * 2).also { a -> l.forEachIndexed { i, p -> a[i * 2] = p.start; a[i * 2 + 1] = p.stop } } }
                        },
                        add = { id, p -> added.getOrPut(id) { ArrayList() } += Programme(p.start, p.stop, p.title, p.desc, p.category, p.image) },
                        log = { com.titanejstudios.tuner.data.AppLog.info(it) },
                        newParser = { android.util.Xml.newPullParser() },
                        saveChannels = { _, _ -> },
                    )
                }.onFailure { com.titanejstudios.tuner.data.AppLog.warn("Backup guide failed", it) }.getOrDefault(0)
            }
            if (added.isNotEmpty()) {
                val g = guide.toMutableMap()
                for ((id, l) in added) g[id] = (g[id].orEmpty() + l).sortedBy { it.start }
                guide = g
            }
            config.backupGuideRemembered = JSONObject(settings.remembered as Map<*, *>).toString()
            config.save()
            backupGuideStatus = if (n > 0) "Backup guide: $n programmes added" else null
        }
    }

    private fun xtream(): List<Source.Xtream> = config.sources.filterIsInstance<Source.Xtream>()

    fun loadVod() {
        if (vodLoading || (movies != null && series != null)) return
        vodLoading = true
        scope.launch {
            val (m, s) = withContext(Dispatchers.IO) {
                val films = xtream().flatMap { src -> runCatching { XtreamClient.loadMovies(src) }.getOrDefault(emptyList()) }
                val shows = xtream().flatMap { src -> runCatching { XtreamClient.loadSeries(src) }.getOrDefault(emptyList()) }
                films to shows
            }
            movies = m
            series = s
            vodLoading = false
        }
    }

    suspend fun episodes(s: SeriesItem): List<Episode> {
        val src = xtream().firstOrNull { it.id == s.sourceId } ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { XtreamClient.loadEpisodes(src, s.seriesId) }.getOrDefault(emptyList()) }
    }

    // ---------------- Playing ----------------

    fun playChannel(c: Channel) {
        if (blockedForKids()) return
        previewJob?.cancel()
        previewing = null
        endTimeshift()
        stopWatching(save = true)
        playingChannel = c
        catchupOf = null
        page = Page.Live
        player.play(c.url)
        player.setVolume(config.volume)
        pickLanguages()
        config.lastChannel = c.id
        config.recents = (listOf(c.id) + config.recents.filter { it != c.id }).take(20)
        scope.launch(Dispatchers.IO) { config.save() }
    }

    fun playCatchup(c: Channel, p: Programme) {
        if (blockedForKids()) return
        endTimeshift()
        val url = Catchup.url(c, p, System.currentTimeMillis()) ?: return say("Catch-up isn't available for that")
        stopWatching(save = true)
        playingChannel = c
        catchupOf = p
        page = Page.Live
        player.play(url)
    }

    /** Films, episodes and Plex: the big player, from where you left off. */
    fun watch(w: Watching, fromStart: Boolean = false) {
        if (blockedForKids()) return
        endTimeshift()
        stopWatching(save = true)
        playingChannel = null
        val start = if (fromStart) 0L else config.resume[w.id]?.pos?.takeIf { w.resumable && it > 10_000L } ?: 0L
        watching = w
        upNextCancelled = null
        endPreview()
        // Downloaded: play the copy on this PC.
        val local = config.downloads.firstOrNull { it.id == w.id && it.state == "done" && java.io.File(it.file).exists() }
        player.play(local?.let { java.io.File(it.file).toURI().toString() } ?: w.url, start)
        player.setVolume(config.volume)
        pickLanguages()
        maybeRecap(w, start)
    }

    // ---------------- "Go to…" from the player ----------------

    /** Keeps what's playing going in the mini player, and opens [to]. */
    private fun browseWhileWatching(to: Page) {
        if (watching != null && !mini) toggleMini()
        page = to
    }

    fun playingSeries(): SeriesItem? = watching?.seriesId?.let { id -> series?.firstOrNull { it.id == id } }

    fun playingFilm(): VodItem? = watching?.takeIf { it.seriesId == null }?.let { w -> movies?.firstOrNull { it.id == w.id } }

    fun goToSeries() {
        val s = playingSeries() ?: return
        openSeriesId = s.id
        browseWhileWatching(Page.Series)
    }

    fun goToFilm() {
        val m = playingFilm() ?: return
        openFilmId = m.id
        browseWhileWatching(Page.Films)
    }

    /** Search everything for the show (other episodes, showings, films, Plex, YouTube). */
    fun findMoreOf(name: String) {
        searchFor = com.titanejstudios.tuner.integrations.Tmdb.cleanTitle(name).first
        if (watching != null && !mini) toggleMini()
        page = Page.Search
    }

    fun playFilm(m: VodItem, fromStart: Boolean = false) {
        config.lastFilm = m.id
        watch(Watching(m.id, m.name, m.category, m.poster, m.url, null, resumable = true), fromStart)
    }

    fun playEpisode(s: SeriesItem, list: List<Episode>, index: Int, fromStart: Boolean = false) {
        val e = list.getOrNull(index) ?: return
        val next = list.getOrNull(index + 1)?.let { { playEpisode(s, list, index + 1, fromStart = true) } }
        watch(
            Watching(e.id, s.name, "S${e.season} E${e.number}  ${e.title}", e.image ?: s.cover, e.url, s.id, resumable = true, next = next),
            fromStart,
        )
    }

    /** Saves where you got to (every few seconds while playing, and when you stop). */
    fun saveProgress(sync: Boolean = false) {
        val w = watching ?: return
        if (!w.resumable) return
        val pos = player.positionMs
        val dur = player.durationMs
        if (pos < 5_000L || dur <= 0L) return
        val t = System.currentTimeMillis()
        if (pos > dur * 95 / 100) {
            config.resume = config.resume - w.id
            config.done = config.done + (w.id to t)
        } else {
            config.resume = config.resume + (w.id to Resume(w.id, w.title, w.subtitle, w.poster, w.url, pos, dur, t, w.seriesId))
        }
        changed(sync)
    }

    fun stopWatching(save: Boolean) {
        if (watching != null) {
            if (save) saveProgress(sync = true)
            player.stop()
            watching = null
        }
    }

    /** The film or episode reached its end. */
    fun finished() {
        // Paused live TV caught up with live: back to the channel itself.
        if (timeshift != null && watching == null) {
            goLive()
            return
        }
        val w = watching ?: return
        if (w.resumable) {
            config.resume = config.resume - w.id
            config.done = config.done + (w.id to System.currentTimeMillis())
            changed()
        }
        logToTrakt(w)
        val next = w.next.takeIf { upNextCancelled != w.id }
        watching = null
        if (next != null) {
            say("Playing the next episode")
            next()
        } else if (w.seriesId == null && !w.id.startsWith("plex:") && !w.id.startsWith("rec:")) offerNextInSeries(w)
    }

    // ---------------- Next in the series, actors ----------------

    /** A film in a series just ended: the next one, if you have it. */
    var nextInSeries by mutableStateOf<VodItem?>(null)

    private fun offerNextInSeries(w: Watching) {
        val key = Keys.TMDB_KEY.ifBlank { return }
        val films = movies ?: return
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    val info = com.titanejstudios.tuner.integrations.Tmdb.lookup(key, w.title, isSeries = false) ?: return@runCatching null
                    val cid = info.collectionId ?: return@runCatching null
                    val parts = com.titanejstudios.tuner.integrations.Tmdb.collection(key, cid)
                    fun norm(s: String) = com.titanejstudios.tuner.data.FilmName.parse(s).first.lowercase().filter { it.isLetterOrDigit() }
                    val i = parts.indexOfFirst { norm(it.title) == norm(info.title) }
                    val nextPart = parts.getOrNull(i + 1) ?: return@runCatching null
                    films.firstOrNull { norm(it.name) == norm(nextPart.title) }
                }.getOrNull()
            }
            if (next != null && watching == null) nextInSeries = next
        }
    }

    /** "What else are they in?": an actor's films, the ones you have first. */
    var actor by mutableStateOf<Pair<String, List<Pair<com.titanejstudios.tuner.integrations.Tmdb.Part, VodItem?>>>?>(null)

    fun openActor(id: Long, name: String) {
        val key = Keys.TMDB_KEY.ifBlank { return say("Film info needs the TMDB key") }
        val films = movies.orEmpty()
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                fun norm(s: String) = com.titanejstudios.tuner.data.FilmName.parse(s).first.lowercase().filter { it.isLetterOrDigit() }
                val by = films.groupBy { norm(it.name) }
                runCatching { com.titanejstudios.tuner.integrations.Tmdb.personFilms(key, id) }.getOrDefault(emptyList())
                    .map { p -> p to by[norm(p.title)]?.firstOrNull() }
                    .sortedBy { if (it.second != null) 0 else 1 }
            }
            actor = name to list
        }
    }

    // ---------------- Plex extras ----------------

    /** A Plex show to open on the Plex page (from the player's "Go to show"). */
    var plexShowRequest by mutableStateOf<Plex.Entry?>(null)

    /** Plex libraries this profile may see (kids: only the ones picked on the TV). */
    val plexSectionsForMe: List<Plex.Section>
        get() = if (kids) plexSections.filter { it.id in config.plexKidsSections } else plexSections

    fun goToPlexShow() {
        val w = watching ?: return
        val key = w.id.removePrefix("plex:").takeIf { w.id.startsWith("plex:") } ?: return
        val server = plexPlayingServer ?: return
        scope.launch {
            val e = withContext(Dispatchers.IO) { plex.entryFor(server, key) }
            val show = e?.showEntry() ?: return@launch say("That isn't an episode of a show")
            plexShowRequest = show
            if (!mini) toggleMini()
            page = Page.Plex
        }
    }

    fun setPlexWatched(e: Plex.Entry, watched: Boolean, then: () -> Unit = {}) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { plex.setWatched(e, watched) }.isSuccess }
            say(if (!ok) "Plex didn't answer" else if (watched) "✓ Marked as watched" else "Marked as unwatched")
            then()
        }
    }

    /** Plex and your provider's films together: each title once, saying where it is. */
    var everything by mutableStateOf<List<Triple<String, VodItem?, Plex.Entry?>>?>(null)

    fun loadEverything() {
        if (everything != null) return
        val films = movies ?: return
        scope.launch {
            val plexFilms = withContext(Dispatchers.IO) {
                plexSectionsForMe.filter { !it.isShows }.flatMap { s -> runCatching { plex.sectionItems(s) }.getOrDefault(emptyList()) }
            }
            everything = withContext(Dispatchers.Default) {
                fun norm(n: String) = com.titanejstudios.tuner.data.FilmName.parse(n).let { (t, y) -> t.lowercase().filter { it.isLetterOrDigit() } to y }
                val plexBy = plexFilms.groupBy { norm(it.title).first }
                val used = HashSet<String>()
                val seen = HashSet<String>()
                val out = ArrayList<Triple<String, VodItem?, Plex.Entry?>>()
                for (f in films) {
                    val (t, y) = norm(f.name)
                    if (t.isEmpty() || !seen.add(t + "|" + (y ?: ""))) continue
                    val p = plexBy[t]?.firstOrNull { it.ratingKey !in used && (y == null || it.year == null || it.year == y) }
                    if (p != null) used += p.ratingKey
                    out += Triple(com.titanejstudios.tuner.data.FilmName.parse(f.name).first, f, p)
                }
                for (p in plexFilms) if (p.ratingKey !in used && seen.add(norm(p.title).first + "|" + (p.year ?: ""))) out += Triple(p.title, null, p)
                out.sortedBy { it.first.lowercase() }
            }
        }
    }

    // ---------------- Pause live TV ----------------

    /** Live TV paused: the channel's being saved to a file from that moment, and plays from there. */
    class Timeshift(val channel: Channel, val file: java.io.File, val pausedAt: Long, val rec: PcRecording)

    var timeshift by mutableStateOf<Timeshift?>(null)
    var timeshiftPlaying by mutableStateOf(false)

    /** Space on Live TV: pause (and keep recording), carry on from where you paused, pause again… */
    fun togglePauseLive() {
        val ts = timeshift
        val c = playingChannel
        when {
            ts != null && !timeshiftPlaying -> {
                timeshiftPlaying = true
                player.play(ts.file.toURI().toString())
                player.setVolume(config.volume)
            }
            ts != null -> player.togglePause()
            c != null && catchupOf == null -> {
                val file = java.io.File(System.getProperty("java.io.tmpdir"), "flicker-pause-${System.currentTimeMillis()}.ts")
                val rec = PcRecording("timeshift", c.id, c.name, c.name, System.currentTimeMillis(), Long.MAX_VALUE, file.absolutePath, "recording")
                // One connection to the provider: stop watching, record from here.
                player.stop()
                if (!recorder.start(rec, c.url)) {
                    player.play(c.url)
                    return say("Couldn't pause live TV")
                }
                timeshift = Timeshift(c, file, System.currentTimeMillis(), rec)
                timeshiftPlaying = false
            }
            else -> player.togglePause()
        }
    }

    /** How far behind live you are. */
    fun behindLiveMs(): Long {
        val ts = timeshift ?: return 0L
        val watchedTo = if (timeshiftPlaying) ts.pausedAt + player.positionMs else ts.pausedAt
        return (System.currentTimeMillis() - watchedTo).coerceAtLeast(0L)
    }

    fun goLive() {
        val c = timeshift?.channel ?: playingChannel ?: return
        endTimeshift()
        playingChannel = c
        player.play(c.url)
        player.setVolume(config.volume)
    }

    fun endTimeshift() {
        val ts = timeshift ?: return
        timeshift = null
        timeshiftPlaying = false
        player.stop()
        recorder.stop(ts.rec.id)
        runCatching { ts.file.delete() }
    }

    // ---------------- Kids' limits (the TV's PIN, daily time and bedtime) ----------------

    /** A grown-up's PIN lets kids carry on for a while. */
    private var grownUpUntil = 0L
    val grownUp: Boolean get() = System.currentTimeMillis() < grownUpUntil
    var timesUp by mutableStateOf<String?>(null)
    var pinPrompt by mutableStateOf<Pair<String, () -> Unit>?>(null)

    fun askPin(why: String, then: () -> Unit) {
        if (config.pin == null) return then()
        pinPrompt = why to then
    }

    fun checkPin(entered: String): Boolean {
        val p = pinPrompt ?: return false
        if (entered.trim() != config.pin) return false
        pinPrompt = null
        grownUpUntil = System.currentTimeMillis() + 30 * 60_000L
        p.second()
        return true
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date())

    private fun usedSeconds(p: PcProfile): Long {
        val v = config.usage[p.id] ?: return 0L
        return if (v.substringBefore(':') == today()) v.substringAfter(':').toLongOrNull() ?: 0L else 0L
    }

    /** Why a kids' profile can't watch now (null = it can). */
    fun kidsReason(): String? {
        val p = profile ?: return null
        if (!p.kids || grownUp) return null
        val cal = java.util.Calendar.getInstance()
        val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        if (p.bedtime >= 0 && (mins >= p.bedtime || mins < 6 * 60)) return "It's bedtime – TV's finished for today"
        if (p.limitMinutes > 0 && usedSeconds(p) >= p.limitMinutes * 60L) return "That's all the TV time for today"
        return null
    }

    private fun blockedForKids(): Boolean {
        val r = kidsReason() ?: return false
        timesUp = r
        return true
    }

    /** Every 10 seconds while something's playing: count kids' time, and stop at the limit or bedtime. */
    fun tickKids() {
        val p = profile ?: return
        if (!p.kids) return
        val playing = watching != null || playingChannel != null
        if (playing && !grownUp) {
            config.usage = config.usage + (p.id to "${today()}:${usedSeconds(p) + 10}")
        }
        if (playing) kidsReason()?.let { r ->
            stopWatching(save = true)
            endTimeshift()
            player.stop()
            playingChannel = null
            timesUp = r
        }
    }

    fun extendWithPin() {
        askPin("A grown-up's PIN gives 30 more minutes") { timesUp = null }
    }

    // ---------------- Kids: ask a grown-up for more time ----------------

    class TimeRequest(val from: String, val profileId: String, val kidName: String)
    var timeRequest by mutableStateOf<TimeRequest?>(null)
    /** The keyboard shortcuts page (F1 or ?). */
    var showKeys by mutableStateOf(false)
    /** The mini player stays on top of other windows. */
    var miniOnTop by mutableStateOf(config.miniOnTop)

    fun toggleMiniOnTop() {
        miniOnTop = !miniOnTop
        config.miniOnTop = miniOnTop
        changed(sync = false)
        say(if (miniOnTop) "📌 The mini player stays on top" else "The mini player can go behind other windows")
    }

    fun askForMoreTime() {
        val kid = profile ?: return
        if (link.key == null) return say("Ask a grown-up for the PIN")
        say("📱 Asking a grown-up…")
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }
            val what = JSONObject().put("kind", "askmore").put("profile", kid.id).put("name", kid.name)
            val asked = withContext(Dispatchers.IO) { found.count { link.send(it, what, sync.deviceLabel()) } }
            say(if (asked > 0) "📱 Asked! You'll see when a grown-up answers" else "Couldn't find a grown-up's phone – is Flicker open on it?")
        }
    }

    fun answerTimeRequest(minutes: Int) {
        val r = timeRequest ?: return
        timeRequest = null
        val send = {
            scope.launch {
                val found = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }.filter { !it.phone }
                val what = if (minutes > 0) JSONObject().put("kind", "bonus").put("profile", r.profileId).put("minutes", minutes)
                else JSONObject().put("kind", "askno").put("profile", r.profileId)
                val told = withContext(Dispatchers.IO) { found.count { link.send(it, what, sync.deviceLabel()) } }
                if (minutes > 0) say(if (told > 0) "+$minutes minutes for ${r.kidName} ✓" else "Couldn't reach the TV")
            }
        }
        if (minutes > 0) askPin("The PIN to give ${r.kidName} $minutes more minutes") { send() } else send()
    }

    /** From a linked device: extra time, or "not now". */
    private fun receiveKids(from: String, j: JSONObject) {
        val p = profile
        when (j.optString("kind")) {
            "askmore" -> if (p == null || !p.kids) timeRequest = TimeRequest(from.ifBlank { "The TV" }, j.optString("profile"), j.optString("name").ifBlank { "Your child" })
            "bonus" -> if (p != null && p.id == j.optString("profile")) {
                grownUpUntil = System.currentTimeMillis() + j.optInt("minutes").coerceIn(0, 240) * 60_000L
                timesUp = null
                say("🎁 ${from.ifBlank { "A grown-up" }} gave ${p.name} ${j.optInt("minutes")} more minutes")
            }
            "askno" -> if (p != null && p.id == j.optString("profile")) say("A grown-up said not now – sorry!")
        }
    }

    // ---------------- Sound and subtitle languages (each profile) ----------------

    private val languageNames = mapOf(
        "en" to listOf("english", "eng"), "fr" to listOf("french", "français", "fre", "fra"), "de" to listOf("german", "deutsch", "ger", "deu"),
        "es" to listOf("spanish", "español", "spa"), "it" to listOf("italian", "ita"), "pt" to listOf("portuguese", "por"),
        "pl" to listOf("polish", "pol"), "nl" to listOf("dutch", "nld", "dut"), "ar" to listOf("arabic", "ara"), "tr" to listOf("turkish", "tur"),
    )
    val languageChoices = listOf(null) + languageNames.keys
    fun languageLabel(code: String?) = code?.let { languageNames[it]?.first()?.replaceFirstChar { c -> c.uppercase() } } ?: "Stream's default"

    /** A couple of seconds after something starts: its sound and subtitles in this profile's languages. */
    private fun pickLanguages() {
        val (audio, subs, describe) = config.languagesOf(profileId)
        if (audio == null && subs == null && !describe) return
        scope.launch {
            delay(2_500)
            fun matches(name: String, code: String) = languageNames[code].orEmpty().any { name.lowercase().contains(it) } || name.lowercase().contains("[$code]")
            val tracks = player.audioTracks().filter { it.first >= 0 }
            if (tracks.size > 1) {
                val inLang = if (audio != null) tracks.filter { matches(it.second, audio) }.ifEmpty { tracks } else tracks
                val ad = inLang.firstOrNull { t -> val n = t.second.lowercase(); "descri" in n || Regex("\\bad\\b").containsMatchIn(n) }
                val pick = if (describe) ad ?: inLang.first() else inLang.firstOrNull { it != ad } ?: inLang.first()
                player.setAudioTrack(pick.first)
            }
            if (subs != null) player.subtitleTracks().firstOrNull { it.first >= 0 && matches(it.second, subs) }?.let { player.setSubtitleTrack(it.first) }
        }
    }

    fun setLanguages(audio: String?, subs: String?, describe: Boolean) {
        config.saveLanguages(profileId, audio, subs, describe)
        changed(sync = false)
    }

    // ---------------- Previously on…, skip credits ----------------

    var previously by mutableStateOf<Pair<String, String>?>(null)
    private val lastEpisodeStart = HashMap<String, Long>()

    private fun maybeRecap(w: Watching, startMs: Long) {
        previously = null
        val m = Regex("S(\\d+) ?E(\\d+)").find(w.subtitle ?: "") ?: return
        val season = m.groupValues[1].toInt()
        val ep = m.groupValues[2].toInt()
        val now = System.currentTimeMillis()
        val last = lastEpisodeStart.put(w.title, now)
        val key = Keys.TMDB_KEY
        if (!config.recaps || key.isBlank() || (season <= 1 && ep <= 1) || startMs > 60_000L) return
        if (last != null && now - last < 4 * 3600_000L) return
        scope.launch {
            val recap = withContext(Dispatchers.IO) {
                runCatching {
                    val info = Tmdb.lookup(key, w.title, isSeries = true) ?: return@runCatching null
                    val (ps, pe) = if (ep > 1) season to ep - 1 else (season - 1) to Tmdb.seasonLength(key, info.id, season - 1)
                    if (ps < 1 || pe < 1) return@runCatching null
                    val (name, plot) = Tmdb.episode(key, info.id, ps, pe) ?: return@runCatching null
                    plot?.let { "Previously on ${w.title} – S$ps E$pe $name".trim() to it }
                }.getOrNull()
            } ?: return@launch
            if (watching !== w) return@launch
            previously = recap
            delay(15_000)
            if (previously === recap) previously = null
        }
    }

    /** How long before the end this series' credits start (learnt on the TV or here). */
    fun creditsFor(seriesId: String?): Long? = seriesId?.let { config.intros["credits:$it"] }

    fun setCreditsHere() {
        val w = watching ?: return
        val sid = w.seriesId ?: return
        val fromEnd = player.durationMs - player.positionMs
        if (player.durationMs <= 0 || fromEnd !in 15_000L..900_000L) return say("That's not near enough the end")
        config.intros = config.intros + ("credits:$sid" to fromEnd)
        changed(sync = false)
        say("Got it – Up next will start when the credits do")
    }

    // ---------------- 👍 / 👎 ----------------

    private fun thumbKey(title: String) = com.titanejstudios.tuner.data.FilmName.parse(title).first.lowercase()
    fun thumbOf(title: String): Int = config.thumbs[thumbKey(title)] ?: 0

    fun thumb(title: String, like: Boolean) {
        val k = thumbKey(title)
        val v = if (like) 1 else -1
        config.thumbs = if (config.thumbs[k] == v) config.thumbs - k else config.thumbs + (k to v)
        // Recommendations learn from it too.
        if (config.thumbs[k] != null) {
            if (like) taste.like(title, null) else taste.dislike(title, null)
            saveTaste()
        }
        changed(sync = false)
        say(if (config.thumbs[k] == null) "Rating removed" else if (like) "👍 More like \"$title\"" else "👎 Fewer like \"$title\"")
    }

    // ---------------- Family picks ----------------

    val familyPicks: List<com.titanejstudios.tuner.data.FamilyPick> get() = Watchlist.decodePicks(config.familyPicksJson)
    val familyPicksForMe: List<com.titanejstudios.tuner.data.FamilyPick>
        get() = if (kids) emptyList() else familyPicks.filter { !it.from.equals(profile?.name ?: "", ignoreCase = true) }

    fun recommendToFamily(item: WatchItem) {
        if (kids) return say("Only grown-ups' profiles can recommend things")
        val now = System.currentTimeMillis()
        val from = profile?.name ?: "Someone"
        config.familyPicksJson = Watchlist.encodePicks(Watchlist.addPick(familyPicks, com.titanejstudios.tuner.data.FamilyPick(item, from, now), now))
        config.familyPicksAt = now
        changed()
        say("👪 ${item.name} is on everyone's Home under Family picks")
    }

    fun removeFamilyPick(id: String) {
        config.familyPicksJson = Watchlist.encodePicks(familyPicks.filter { it.item.id != id })
        config.familyPicksAt = System.currentTimeMillis()
        changed()
    }

    /** What's playing, as something to recommend (films and series from your provider). */
    fun playingWatchItem(): WatchItem? =
        playingSeries()?.let { WatchItem.of(it, System.currentTimeMillis()) } ?: playingFilm()?.let { WatchItem.of(it, System.currentTimeMillis()) }

    // ---------------- Coming soon ----------------

    val comingSoon: List<com.titanejstudios.tuner.data.ComingEpisode>
        get() = runCatching {
            val today = today()
            val a = org.json.JSONArray(config.comingSoonJson)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val w = Watchlist.decode(org.json.JSONArray().put(o.getJSONObject("w")).toString()).firstOrNull() ?: return@mapNotNull null
                com.titanejstudios.tuner.data.ComingEpisode(w, o.optString("date"), o.optInt("s"), o.optInt("e"), o.optString("name").takeIf { it.isNotBlank() })
            }.filter { it.date >= today }
        }.getOrDefault(emptyList())

    /** Twice a day: when the series you follow have their next episode (from TMDB). */
    fun refreshComingSoon() {
        val key = Keys.TMDB_KEY
        if (key.isBlank() || kids) return
        val now = System.currentTimeMillis()
        if (now - config.comingSoonAt < 12 * 3600_000L) return tellComingToday()
        config.comingSoonAt = now
        val shows = (Watchlist.decodeFollowed(config.followedJson).map { it.series } + watchlist.filter { it.isSeries })
            .distinctBy { com.titanejstudios.tuner.data.FilmName.parse(it.name).first.lowercase() }.take(40)
        if (shows.isEmpty()) return
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                val out = org.json.JSONArray()
                shows.mapNotNull { w -> runCatching { Tmdb.upcoming(key, w.name) }.getOrNull()?.let { w to it.second } }
                    .sortedBy { it.second.date }
                    .forEach { (w, up) ->
                        out.put(JSONObject().put("w", org.json.JSONArray(Watchlist.encode(listOf(w))).getJSONObject(0))
                            .put("date", up.date).put("s", up.season).put("e", up.episode).put("name", up.name ?: ""))
                    }
                out
            }
            config.comingSoonJson = a.toString()
            changed(sync = false)
            tellComingToday()
        }
    }

    private fun tellComingToday() {
        val today = today()
        if (config.comingTold == today) return
        val due = comingSoon.filter { it.date == today }
        if (due.isEmpty()) return
        config.comingTold = today
        val text = due.joinToString(", ") { "${com.titanejstudios.tuner.data.FilmName.parse(it.item.name).first} S${it.season} E${it.episode}" }
        notify("📅 New episodes today", text)
        say("📅 New today: $text")
    }

    // ---------------- Drag and drop ----------------

    private val videoTypes = setOf("mp4", "mkv", "avi", "mov", "ts", "m4v", "webm", "wmv", "mpg", "mpeg", "flv", "m2ts")

    /** A video file plays; an .m3u file or playlist link is added; any other link plays. */
    fun dropped(files: List<java.io.File>, text: String?) {
        val f = files.firstOrNull()
        if (f != null) {
            val ext = f.extension.lowercase()
            when {
                ext in videoTypes -> watch(Watching("file:" + f.absolutePath, f.nameWithoutExtension, "From this PC", null, f.toURI().toString(), null, resumable = true))
                ext == "m3u" || ext == "m3u8" -> addDroppedPlaylist(f.nameWithoutExtension, f.toURI().toString())
                else -> say("Drop a video, an .m3u playlist or a link")
            }
            return
        }
        val url = text?.trim()?.lineSequence()?.firstOrNull { it.startsWith("http") }?.trim() ?: return say("Drop a video, an .m3u playlist or a link")
        val low = url.lowercase()
        if (low.contains(".m3u") || low.contains("get.php") || low.contains("type=m3u")) addDroppedPlaylist("Playlist", url)
        else watch(Watching("url:$url", url.substringAfterLast('/').substringBefore('?').ifBlank { "Video" }, url, null, url, null, resumable = false))
    }

    private fun addDroppedPlaylist(name: String, url: String) {
        if (config.sources.any { it is com.titanejstudios.tuner.data.Source.M3u && it.playlistUrl == url }) return say("That playlist's already added")
        config.sources = config.sources + com.titanejstudios.tuner.data.Source.M3u("pc-" + System.currentTimeMillis(), name, url, null)
        config.save()
        say("Added $name – loading its channels")
        reload()
    }

    // ---------------- Downloads (watch without the internet) ----------------

    var downloads by mutableStateOf(config.downloads)
    var downloadProgress by mutableStateOf<Map<String, Float>>(emptyMap())
    val downloadFolder: java.io.File get() = java.io.File(recorder.folder, "Downloads").apply { mkdirs() }

    fun downloadFor(id: String) = downloads.firstOrNull { it.id == id }

    fun download(id: String, title: String, sub: String?, poster: String?, url: String) {
        if (downloadFor(id) != null) return say("Already downloaded (or downloading)")
        val ext = url.substringAfterLast('.', "mp4").substringBefore('?').take(4).ifBlank { "mp4" }
        val safe = (title + (sub?.let { " $it" } ?: "")).replace(Regex("[\\\\/:*?\"<>|]"), "").take(80).trim()
        val d = PcDownload(id, title, sub, poster, url, java.io.File(downloadFolder, "$safe.$ext").absolutePath, "downloading")
        downloads = downloads + d
        saveDownloads()
        say("⤓ Downloading $title")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 15_000
                    c.readTimeout = 60_000
                    c.setRequestProperty("User-Agent", "Flicker")
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        java.io.File(d.file).outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var got = 0L
                            var lastShown = 0L
                            while (true) {
                                if (downloadFor(id) == null) throw java.util.concurrent.CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (total > 0 && got - lastShown > 4_000_000L) {
                                    lastShown = got
                                    val f = got.toFloat() / total
                                    scope.launch { downloadProgress = downloadProgress + (id to f) }
                                }
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            downloadProgress = downloadProgress - id
            val now = downloadFor(id) ?: return@launch
            now.state = if (ok) "done" else "failed"
            downloads = downloads.toList()
            saveDownloads()
            say(if (ok) "✓ $title downloaded" else "Couldn't download $title")
        }
    }

    fun deleteDownload(d: PcDownload) {
        downloads = downloads.filterNot { it.id == d.id }
        saveDownloads()
        runCatching { java.io.File(d.file).delete() }
    }

    fun playDownload(d: PcDownload) {
        watch(Watching(d.id, d.title, d.sub, d.poster, d.url, null, resumable = true))
    }

    private fun saveDownloads() {
        config.downloads = downloads
        scope.launch(Dispatchers.IO) { config.save() }
    }

    // ---------------- Windows notifications ----------------

    /** Shows a notification in the corner of the screen (set up by the tray icon). */
    var notifier: ((String, String) -> Unit)? = null

    fun notify(title: String, message: String) {
        if (config.notifications) notifier?.invoke(title, message)
    }

    // ---------------- Up next, skip intro ----------------

    /** Where this episode's series intro ends (learnt on the TV), if it does. */
    fun introEnd(): Long? = watching?.seriesId?.let { config.intros[it] }

    /** Skips the intro (and remembers where it ends for this series). */
    fun skipIntro() {
        val end = introEnd() ?: return
        player.seekTo(end)
    }

    /** "Skip intro" without a saved end: 85 seconds on, saved for the rest of the series. */
    fun skipIntroGuess() {
        val w = watching ?: return
        val to = player.positionMs + 85_000L
        player.seekTo(to)
        w.seriesId?.let { sid ->
            config.intros = config.intros + (sid to to)
            changed()
        }
    }

    /** The next episode's countdown was cancelled. */
    var upNextCancelled by mutableStateOf<String?>(null)

    // ---------------- Volume ----------------

    fun setVolume(v: Int) {
        config.volume = v.coerceIn(0, 150)
        player.setVolume(config.volume)
        scope.launch(Dispatchers.IO) { config.save() }
    }

    // ---------------- Full screen on another monitor ----------------

    /** The other screen's position and size (in Windows' units), or null with one screen. */
    fun otherScreen(): java.awt.Rectangle? = runCatching {
        val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screens = env.screenDevices
        if (screens.size < 2) return null
        val main = env.defaultScreenDevice
        (screens.firstOrNull { it != main } ?: screens[1]).defaultConfiguration.bounds
    }.getOrNull()

    var secondScreen by mutableStateOf<java.awt.Rectangle?>(null)

    /** Full screen on this monitor (its own borderless window – Windows' own full screen leaves the title bar). */
    var fullHere by mutableStateOf(false)

    fun goFull(r: java.awt.Rectangle) {
        if (watching == null && playingChannel == null) return say("Play something first")
        mini = false
        fullHere = true
        secondScreen = r
        scope.launch {
            delay(500)
            player.restart()
        }
    }

    fun leaveFull() {
        secondScreen = null
        fullHere = false
        scope.launch {
            delay(500)
            player.restart()
        }
    }

    fun toggleSecondScreen() {
        if (secondScreen != null) {
            secondScreen = null
            fullHere = false
        } else {
            val r = otherScreen() ?: return say("Only one screen found")
            if (watching == null && playingChannel == null) return say("Play something first")
            mini = false
            secondScreen = r
        }
        scope.launch {
            delay(500)
            player.restart()
        }
    }

    /** The video's in its own window (mini player or the other screen). */
    val videoElsewhere: Boolean get() = mini || secondScreen != null

    // ---------------- Hover preview (Live TV, when nothing's playing) ----------------

    var previewing by mutableStateOf<Channel?>(null)
    private var previewJob: Job? = null

    fun hoverChannel(c: Channel?) {
        previewJob?.cancel()
        if (c == null || playingChannel != null || watching != null) return
        previewJob = scope.launch {
            delay(1_200)
            if (playingChannel != null || watching != null) return@launch
            previewing = c
            player.play(c.url)
            player.setVolume(0)
        }
    }

    fun endPreview() {
        previewJob?.cancel()
        if (previewing != null && playingChannel == null) player.stop()
        previewing = null
    }

    // ---------------- Profiles (edited here, back to the TV) ----------------

    fun editProfile(id: String, name: String, kids: Boolean, limitMinutes: Int, bedtime: Int, colour: Int, icon: String) {
        val arr = runCatching { JSONArray(config.profilesRaw) }.getOrDefault(JSONArray())
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            o.put("name", name.trim().ifEmpty { o.optString("name") }).put("kids", kids).put("limit", limitMinutes).put("bedtime", bedtime)
                .put("colour", colour).put("icon", icon)
            found = true
        }
        if (!found) return say("That profile hasn't come from the TV yet – sync first")
        config.profilesRaw = arr.toString()
        config.profiles = config.profilesFromJson(arr)
        config.profilesAt = System.currentTimeMillis()
        changed()
        say("Saved – the TV gets it at the next sync")
    }

    // ---------------- Start with Windows ----------------

    /** Adds (or removes) Flicker to the programs Windows starts when you sign in – in the tray, out of the way. */
    fun setStartWithWindows(on: Boolean) {
        val exe = System.getProperty("jpackage.app-path")
        if (exe.isNullOrBlank()) return say("That only works in the installed Flicker")
        val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        val cmd = if (on) listOf("reg", "add", key, "/v", "Flicker", "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f")
        else listOf("reg", "delete", key, "/v", "Flicker", "/f")
        val ok = runCatching { ProcessBuilder(cmd).start().waitFor() == 0 }.getOrDefault(false)
        if (ok || !on) {
            config.startWithWindows = on
            config.save()
            listsVersion++
        }
        say(if (!ok && on) "Windows didn't allow it" else if (on) "Flicker will start with Windows (in the tray)" else "Flicker won't start with Windows")
    }

    // ---------------- Type a channel number ----------------

    var typedNumber by mutableStateOf("")
    private var numberJob: Job? = null

    /** The number shown for a channel: the provider's, else its place in the list. */
    fun numberOf(c: Channel, index: Int): Int = if (c.number > 0) c.number else index + 1

    fun typeDigit(d: Int) {
        typedNumber = (typedNumber + d).takeLast(4)
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(1_500)
            val n = typedNumber.toIntOrNull()
            typedNumber = ""
            if (n != null) {
                val list = visibleChannels
                val c = list.firstOrNull { it.number == n } ?: list.getOrNull(n - 1)
                if (c != null) playChannel(c) else say("No channel $n")
            }
        }
    }

    // ---------------- Mini player ----------------

    /** The video in a small window that stays on top. */
    var mini by mutableStateOf(false)

    fun toggleMini() {
        if (watching == null && playingChannel == null) return say("Play something first")
        mini = !mini
        if (mini) {
            secondScreen = null
            fullHere = false
        }
        // VLC has to be told where to draw once the video's moved window.
        scope.launch {
            delay(500)
            player.restart()
        }
    }

    // ---------------- Multi-view ----------------

    val multi = MultiPlayers(player)
    var multiChannels by mutableStateOf<List<Channel?>>(List(4) { null })
    var multiSound by mutableStateOf(0)
    var multiCount by mutableStateOf(4)

    fun enterMulti() {
        stopWatching(save = true)
        player.stop()
        playingChannel = null
        mini = false
    }

    fun leaveMulti() {
        multi.stopAll()
    }

    fun setMulti(i: Int, c: Channel) {
        multiChannels = multiChannels.toMutableList().also { it[i] = c }
        multi.play(i, c.url, withSound = i == multiSound)
    }

    fun multiSoundFrom(i: Int) {
        multiSound = i
        multi.soundFrom(i)
    }

    /** One of the four full size on Live TV. */
    fun multiToFull(i: Int) {
        val c = multiChannels[i] ?: return
        multi.stopAll()
        playChannel(c)
    }

    // ---------------- Recordings ----------------

    val recorder = Recorder(player).also { r ->
        config.recordFolder.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.isDirectory || it.mkdirs() }?.let { r.folder = it }
    }
    var recordings by mutableStateOf(config.recordings)

    private fun saveRecordings() {
        config.recordings = recordings
        scope.launch(Dispatchers.IO) { config.save() }
    }

    fun recordingFor(c: Channel, p: Programme) = recordings.firstOrNull { it.channelId == c.id && it.start == p.start }

    fun toggleRecording(c: Channel, p: Programme) {
        val existing = recordingFor(c, p)
        if (existing != null) {
            recorder.stop(existing.id)
            recordings = recordings.filterNot { it.id == existing.id }
            saveRecordings()
            return say("Recording cancelled")
        }
        val safe = p.title.replace(Regex("[\\\\/:*?\"<>|]"), "").take(60).trim().ifEmpty { "Recording" }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.UK).format(java.util.Date(p.start))
        val file = java.io.File(recorder.folder, "$safe – ${c.name.replace(Regex("[\\\\/:*?\"<>|]"), "")} – $stamp.ts").absolutePath
        recordings = recordings + PcRecording(java.util.UUID.randomUUID().toString(), c.id, c.name, p.title, p.start, p.stop, file, "scheduled")
        saveRecordings()
        checkRecordings()
        say(if (p.start <= System.currentTimeMillis()) "● Recording ${p.title}" else "● ${p.title} will record (keep Flicker open – it can be minimised)")
    }

    /** Starts and stops recordings on time (a couple of minutes either side). */
    fun checkRecordings() {
        val t = System.currentTimeMillis()
        var changed = false
        for (r in recordings) {
            when {
                r.state == "scheduled" && t >= r.start - 2 * 60_000L && t < r.stop -> {
                    val c = channels.firstOrNull { it.id == r.channelId }
                    r.state = if (c != null && recorder.start(r, c.url)) "recording" else "failed"
                    changed = true
                }
                r.state == "recording" && (t >= r.stop + 2 * 60_000L || !recorder.isRunning(r.id)) -> {
                    recorder.stop(r.id)
                    r.state = if (java.io.File(r.file).length() > 0) "done" else "failed"
                    notify(if (r.state == "done") "● Recorded" else "⚠ Didn't record", r.title)
                    changed = true
                }
                r.state == "scheduled" && t >= r.stop -> {
                    r.state = "failed"
                    changed = true
                }
            }
        }
        if (changed) {
            recordings = recordings.toList()
            saveRecordings()
        }
    }

    fun playRecording(r: PcRecording) {
        watch(Watching("rec:" + r.id, r.title, "Recording · ${r.channelName}", null, java.io.File(r.file).toURI().toString(), null, resumable = false))
    }

    fun deleteRecording(r: PcRecording) {
        recorder.stop(r.id)
        runCatching { java.io.File(r.file).delete() }
        recordings = recordings.filterNot { it.id == r.id }
        saveRecordings()
    }

    // ---------------- Sleep timer ----------------

    /** When to stop (0 = off; -1 = at the end of this programme). */
    var sleepAt by mutableStateOf(0L)

    fun setSleep(minutes: Int) {
        sleepAt = when {
            minutes == 0 -> 0L
            minutes < 0 -> playingChannel?.let { c -> guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }?.stop } ?: -1L
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
        say(when {
            sleepAt == 0L -> "Sleep timer off"
            sleepAt < 0 -> "Nothing to time – stops when this ends"
            else -> "😴 Stops at ${time(sleepAt)}"
        })
    }

    val sleepLabel: String get() = if (sleepAt <= 0L) "Off" else "Stops at ${time(sleepAt)}"

    fun tickSleep() {
        val at = sleepAt
        if (at > 0 && System.currentTimeMillis() >= at) {
            sleepAt = 0L
            stopWatching(save = true)
            endTimeshift()
            player.stop()
            playingChannel = null
            say("😴 Sleep timer – stopped. Goodnight!")
        } else if (at < 0 && watching == null) sleepAt = 0L
    }

    // ---------------- Picture and sound ----------------

    var soundVersion by mutableStateOf(0)

    fun toggleNightMode() {
        config.nightMode = !config.nightMode
        player.nightMode = config.nightMode
        player.restart()
        soundChanged(if (config.nightMode) "🌙 Night mode: quiet bangs, louder voices" else "Night mode off")
    }

    fun toggleClearSpeech() {
        config.clearSpeech = !config.clearSpeech
        player.clearSpeech = config.clearSpeech
        player.applySoundTweaks()
        soundChanged(if (config.clearSpeech) "🗣 Clearer speech on" else "Clearer speech off")
    }

    val subtitleSizes = listOf(75 to "Small", 100 to "Normal", 130 to "Large", 160 to "Extra large")
    fun cycleSubtitleSize() {
        val i = subtitleSizes.indexOfFirst { it.first == config.subtitleScale }
        val next = subtitleSizes[(i + 1) % subtitleSizes.size]
        config.subtitleScale = next.first
        player.subtitleScale = next.first
        player.restart()
        soundChanged("Subtitles: ${next.second}")
    }
    val subtitleSizeLabel: String get() = subtitleSizes.firstOrNull { it.first == config.subtitleScale }?.second ?: "Normal"

    fun setLipSync(ms: Long) {
        config.lipSyncMs = ms
        player.audioDelayMs = ms
        player.applySoundTweaks()
        soundChanged("Sound timing: " + lipSyncLabel(ms))
    }

    fun lipSyncLabel(ms: Long = config.lipSyncMs): String = when {
        ms == 0L -> "In sync"
        ms < 0 -> "Sound ${-ms} ms earlier"
        else -> "Sound $ms ms later"
    }

    private fun soundChanged(message: String) {
        soundVersion++
        changed(sync = false)
        say(message)
    }

    // ---------------- HD / SD copies, Fix it ----------------

    /** Merged channels' copies (HD, SD, backups), best first. */
    var versions by mutableStateOf<Map<String, List<Channel>>>(emptyMap())

    fun versionsFor(c: Channel): List<Channel> = versions[c.id].orEmpty()

    /** Plays another copy of the channel that's on (keeps it in the list as the same channel). */
    fun playVersion(main: Channel, copy: Channel) {
        playingChannel = main
        catchupOf = null
        player.play(copy.url)
        player.setVolume(config.volume)
        say("Playing ${copy.name}")
    }

    var fixing by mutableStateOf(false)

    /** 🛠 Fix it: starts it again, then tries the other copies of the channel. */
    fun fixIt() {
        if (fixing) return
        val c = playingChannel
        fixing = true
        say("🛠 Starting it again…")
        scope.launch {
            player.restart()
            delay(8_000)
            if (!player.isPlaying && c != null) {
                for (v in versionsFor(c).drop(1)) {
                    say("🛠 Trying ${v.name}…")
                    player.play(v.url)
                    delay(8_000)
                    if (player.isPlaying) break
                }
            }
            fixing = false
            say(
                if (player.isPlaying) "🛠 Playing again ✓"
                else "🛠 Still not playing – the channel may be down. Is the internet OK? (Settings › Problem log has details)"
            )
        }
    }

    var showStreamInfo by mutableStateOf(false)

    // ---------------- Catch-up ----------------

    /** Live TV, joined part way: watch this programme from its start (with catch-up). */
    fun startOverProgramme(): Pair<Channel, Programme>? {
        val c = playingChannel ?: return null
        if (catchupOf != null || timeshift != null) return null
        val p = guide[c.id]?.firstOrNull { it.start <= now && it.stop > now } ?: return null
        if (now - p.start < 3 * 60_000L || !com.titanejstudios.tuner.data.Catchup.isAvailable(c, p, now)) return null
        return c to p
    }

    /** Channels with catch-up (for the Catch-up page). */
    val catchupChannels: List<Channel> get() = visibleChannels.filter { it.hasCatchup }

    // ---------------- Watch together ----------------

    var togetherWith by mutableStateOf<String?>(null)
    private var togetherPeer: PcLink.Peer? = null
    private var togetherJob: kotlinx.coroutines.Job? = null
    private var tPaused = false
    private var tPos = 0L
    private var tAt = 0L

    /** Plays what's on here on [peer] too, and keeps pauses and skips in step. */
    fun watchTogether(peer: PcLink.Peer) {
        val what = sendable()?.put("together", true) ?: return say("This can't be watched together")
        if (watching != null) saveProgress()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { link.send(peer, what, sync.deviceLabel()) }
            if (!ok) return@launch say("${peer.name} didn't answer")
            startTogether(peer)
            say("👫 Watching together with ${peer.name} – pause or skip on either")
        }
    }

    private fun togetherBaseline() {
        tPaused = player.isPaused
        tPos = player.positionMs
        tAt = System.currentTimeMillis()
    }

    private fun startTogether(peer: PcLink.Peer) {
        togetherJob?.cancel()
        togetherPeer = peer
        togetherWith = peer.name
        togetherJob = scope.launch {
            delay(3_000)
            togetherBaseline()
            while (togetherPeer === peer) {
                delay(700)
                if (watching == null && playingChannel == null) {
                    stopTogether()
                    break
                }
                val paused = player.isPaused
                val pos = player.positionMs
                val t = System.currentTimeMillis()
                val expected = if (tPaused) tPos else tPos + (t - tAt)
                when {
                    paused != tPaused -> sendTogether(peer, if (paused) "pause" else "play", pos)
                    watching != null && kotlin.math.abs(pos - expected) > 6_000 -> sendTogether(peer, "seek", pos, paused)
                }
                tPaused = paused
                tPos = pos
                tAt = t
            }
        }
    }

    fun stopTogether(tell: Boolean = true) {
        val p = togetherPeer
        togetherJob?.cancel()
        togetherPeer = null
        togetherWith = null
        if (tell && p != null) sendTogether(p, "leave")
    }

    private fun sendTogether(peer: PcLink.Peer, act: String, pos: Long = 0L, paused: Boolean = false) {
        val what = JSONObject().put("kind", "together").put("act", act).put("pos", pos).put("paused", paused)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { link.send(peer, what, sync.deviceLabel()) }
            if (!ok && act != "leave" && togetherPeer === peer) {
                togetherJob?.cancel()
                togetherPeer = null
                togetherWith = null
                say("Lost ${peer.name} – stopped watching together")
            }
        }
    }

    /** The other device paused, played, skipped or left (or started watching together with us). */
    private fun handleTogether(from: String, j: JSONObject) {
        when (j.optString("act")) {
            "pause" -> {
                player.pause()
                if (watching != null) player.seekTo(j.optLong("pos"))
                say("⏸ Paused on $from")
            }
            "play" -> {
                if (watching != null) player.seekTo(j.optLong("pos"))
                player.resume()
            }
            "seek" -> {
                player.seekTo(j.optLong("pos"))
                if (j.optBoolean("paused")) player.pause() else player.resume()
            }
            "leave" -> {
                togetherJob?.cancel()
                togetherPeer = null
                togetherWith = null
                say("$from stopped watching together")
                return
            }
        }
        scope.launch {
            delay(400)
            togetherBaseline()
        }
    }

    /** Something sent with "together": follow that device. */
    private fun joinTogether(from: String) {
        scope.launch {
            val peers = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }
            val p = peers.firstOrNull { it.name == from } ?: return@launch
            startTogether(p)
            say("👫 Watching together with ${p.name}")
        }
    }

    // ---------------- Subtitles online (OpenSubtitles) ----------------

    var subtitleHits by mutableStateOf<List<com.titanejstudios.tuner.integrations.OpenSubtitles.Hit>?>(null)

    fun findSubtitles() {
        val key = config.openSubtitlesKey ?: return say("Add your free OpenSubtitles key in Settings › This PC first")
        val w = watching ?: return say("Subtitles online are for films and episodes")
        val lang = config.languagesOf(profileId).second ?: "en"
        val se = com.titanejstudios.tuner.integrations.OpenSubtitles.seasonEpisode(w.subtitle)
        val (title, year) = com.titanejstudios.tuner.data.FilmName.parse(w.title)
        say("Looking for subtitles…")
        scope.launch {
            val hits = withContext(Dispatchers.IO) {
                runCatching { com.titanejstudios.tuner.integrations.OpenSubtitles.search(key, title, lang, year?.toIntOrNull(), se?.first, se?.second) }
                    .onFailure { com.titanejstudios.tuner.data.AppLog.warn("OpenSubtitles search failed", it) }.getOrNull()
            }
            if (hits.isNullOrEmpty()) say("No subtitles found") else subtitleHits = hits.take(15)
        }
    }

    fun useSubtitle(hit: com.titanejstudios.tuner.integrations.OpenSubtitles.Hit) {
        val key = config.openSubtitlesKey ?: return
        subtitleHits = null
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    com.titanejstudios.tuner.integrations.OpenSubtitles.download(key, hit, java.io.File(System.getProperty("java.io.tmpdir"), "flicker-subs").apply { mkdirs() })
                }.onFailure { com.titanejstudios.tuner.data.AppLog.warn("Subtitle download failed", it) }.getOrNull()
            }
            say(if (file != null && player.addSubtitleFile(file)) "Subtitles on ✓" else "Couldn't add those subtitles")
        }
    }

    fun setOpenSubtitlesKey(key: String?) {
        config.openSubtitlesKey = key?.trim()?.ifBlank { null }
        changed(sync = false)
        say(if (config.openSubtitlesKey != null) "OpenSubtitles key saved" else "OpenSubtitles key removed")
    }

    // ---------------- Series links, tidying recordings, the folder ----------------

    fun seriesLinkFor(c: Channel, p: Programme): Boolean = "${c.id}\t${p.title}" in config.seriesLinks

    fun toggleSeriesLink(c: Channel, p: Programme) {
        val key = "${c.id}\t${p.title}"
        if (key in config.seriesLinks) {
            config.seriesLinks = config.seriesLinks - key
            // Scheduled ones it made go too (not ones already recording or done).
            recordings = recordings.filterNot { it.channelId == c.id && it.title == p.title && it.state == "scheduled" && it.start > System.currentTimeMillis() }
            saveRecordings()
            changed(sync = false)
            say("Series link cancelled")
        } else {
            config.seriesLinks = config.seriesLinks + key
            changed(sync = false)
            scheduleSeriesLinks()
            say("🔁 Every ${p.title} on ${c.name} will record (keep Flicker open – it can be in the tray)")
        }
    }

    /** Puts the next showings of your series links in the recordings list (the next day's worth). */
    fun scheduleSeriesLinks() {
        val links = config.seriesLinks
        if (links.isEmpty()) return
        val t = System.currentTimeMillis()
        val add = ArrayList<PcRecording>()
        for (l in links) {
            val cid = l.substringBefore('\t')
            val title = l.substringAfter('\t')
            val c = channels.firstOrNull { it.id == cid } ?: continue
            for (p in guide[cid].orEmpty()) {
                if (p.title != title || p.stop <= t || p.start > t + 24 * 3_600_000L) continue
                if (recordings.any { it.channelId == cid && it.start == p.start } || add.any { it.channelId == cid && it.start == p.start }) continue
                val safe = p.title.replace(Regex("[\\\\/:*?\"<>|]"), "").take(60).trim().ifEmpty { "Recording" }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.UK).format(java.util.Date(p.start))
                val file = java.io.File(recorder.folder, "$safe – ${c.name.replace(Regex("[\\\\/:*?\"<>|]"), "")} – $stamp.ts").absolutePath
                add += PcRecording(java.util.UUID.randomUUID().toString(), c.id, c.name, p.title, p.start, p.stop, file, "scheduled")
            }
        }
        if (add.isNotEmpty()) {
            recordings = recordings + add
            saveRecordings()
        }
    }

    private var tidiedAt = 0L

    /** Hourly: series links' next showings, and deleting finished recordings older than you keep them. */
    fun tidyRecordings() {
        val t = System.currentTimeMillis()
        if (t - tidiedAt < 3_600_000L) return
        tidiedAt = t
        scheduleSeriesLinks()
        val days = config.keepRecordingsDays
        if (days <= 0) return
        val old = recordings.filter { it.state == "done" && t - it.stop > days * 86_400_000L }
        if (old.isEmpty()) return
        old.forEach { runCatching { java.io.File(it.file).delete() } }
        recordings = recordings - old.toSet()
        saveRecordings()
        com.titanejstudios.tuner.data.AppLog.info("Deleted ${old.size} recordings older than $days days")
    }

    fun setKeepRecordings(days: Int) {
        config.keepRecordingsDays = days
        changed(sync = false)
        tidiedAt = 0L
        tidyRecordings()
    }

    /** Picks where recordings go (any folder, a network drive too). */
    fun chooseRecordFolder() {
        val chooser = javax.swing.JFileChooser(recorder.folder).apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Where should recordings go?"
        }
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            val dir = chooser.selectedFile
            if (!dir.exists() && !dir.mkdirs() || !dir.canWrite()) return say("Flicker can't save to that folder")
            config.recordFolder = dir.absolutePath
            recorder.folder = dir
            changed(sync = false)
            say("Recordings will go to ${dir.absolutePath}")
        }
    }

    fun resetRecordFolder() {
        config.recordFolder = ""
        recorder.folder = java.io.File(System.getProperty("user.home"), "Videos" + java.io.File.separator + "Flicker").apply { mkdirs() }
        changed(sync = false)
    }

    // ---------------- What you like (for Top picks, Your shows, Missed it?) ----------------

    private var tasteOwner = ""
    private var tasteCache = com.titanejstudios.tuner.data.Taste()
    /** This profile's viewing taste, learnt here on the PC as you watch. */
    val taste: com.titanejstudios.tuner.data.Taste
        get() {
            if (tasteOwner != profileId) {
                tasteOwner = profileId
                tasteCache = com.titanejstudios.tuner.data.Taste.fromJson(config.tasteOf(profileId))
            }
            return tasteCache
        }
    var tasteVersion by mutableStateOf(0)
    private var learning: Triple<String, String?, Long>? = null

    private fun saveTaste() {
        config.saveTaste(profileId, taste.toJson())
        tasteVersion++
    }

    /** Every 10 seconds of live TV counts towards what you like (saved every 5 minutes of a programme). */
    fun learnTaste() {
        val c = playingChannel
        val p = c?.let { ch -> (catchupOf ?: guide[ch.id]?.firstOrNull { it.start <= now && it.stop > now }) }
        val l = learning
        if (p == null || c == null || player.isPaused) {
            if (l != null && l.third >= 60) {
                taste.record(l.first, l.second, l.third, System.currentTimeMillis())
                saveTaste()
            }
            learning = null
            return
        }
        val genre = com.titanejstudios.tuner.data.Genres.of(p.category, p.title, c.group)
        learning = if (l == null || l.first != p.title) {
            if (l != null && l.third >= 60) {
                taste.record(l.first, l.second, l.third, System.currentTimeMillis())
                saveTaste()
            }
            Triple(p.title, genre, 10L)
        } else Triple(l.first, l.second, l.third + 10)
        val now2 = learning!!
        if (now2.third >= 300) {
            taste.record(now2.first, now2.second, now2.third, System.currentTimeMillis())
            saveTaste()
            changed(sync = false)
            learning = Triple(now2.first, now2.second, 0L)
        }
    }

    fun forgetTaste() {
        tasteCache = com.titanejstudios.tuner.data.Taste()
        saveTaste()
        changed(sync = false)
        say("Recommendations reset – Flicker will learn again from what you watch")
    }

    /** Programmes of shows you watch that ended in the last two days and can still be caught up. */
    fun missed(now: Long): List<Pair<Channel, Programme>> {
        val t = taste
        if (t.isEmpty) return emptyList()
        val out = ArrayList<Pair<Channel, Programme>>()
        val seen = HashSet<String>()
        for (c in visibleChannels) {
            if (!c.hasCatchup) continue
            for (p in guide[c.id].orEmpty()) {
                if (p.stop > now || now - p.stop > 2 * 86_400_000L) continue
                if (!t.isYourShow(p.title) || !seen.add(p.title.lowercase())) continue
                if (com.titanejstudios.tuner.data.Catchup.isAvailable(c, p, now)) out += c to p
            }
        }
        return out.sortedByDescending { it.second.start }.take(20)
    }

    /** Films in the same category as the last film you watched. */
    fun becauseFilms(): Pair<String, List<VodItem>>? {
        val all = movies ?: return null
        val last = all.firstOrNull { it.id == config.lastFilm } ?: return null
        val list = all.filter { it.category == last.category && it.id != last.id && it.id !in config.done && it.id !in config.resume && thumbOf(it.name) >= 0 }
            .sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }.take(20)
        return if (list.isEmpty()) null else com.titanejstudios.tuner.data.FilmName.parse(last.name).first to list
    }

    /** What's trending this week (TMDB) that you've got. */
    var trending by mutableStateOf<List<Pair<com.titanejstudios.tuner.integrations.Tmdb.Trend, Any>>>(emptyList())
    private var trendingAt = 0L

    fun loadTrending() {
        val key = Keys.TMDB_KEY
        if (key.isBlank() || kids || System.currentTimeMillis() - trendingAt < 6 * 3_600_000L) return
        val films = movies ?: return
        val shows = series ?: return
        trendingAt = System.currentTimeMillis()
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val trends = runCatching { com.titanejstudios.tuner.integrations.Tmdb.trending(key) }.getOrDefault(emptyList())
                fun norm(s: String) = com.titanejstudios.tuner.data.FilmName.parse(s).first.lowercase().filter { it.isLetterOrDigit() }
                val filmBy = films.groupBy { norm(it.name) }
                val showBy = shows.groupBy { norm(it.name) }
                trends.mapNotNull { t ->
                    val k = norm(t.title)
                    val hit: Any? = if (t.isTv) showBy[k]?.firstOrNull() else filmBy[k]?.firstOrNull { f ->
                        val y = com.titanejstudios.tuner.data.FilmName.parse(f.name).second
                        y == null || t.year == null || y == t.year
                    }
                    hit?.let { t to it }
                }
            }
            trending = list
        }
    }

    // ---------------- Home rows ----------------

    val homeRowNames = linkedMapOf(
        "continue" to "Continue watching", "channels" to "Your channels", "picks" to "Top picks for you", "yourshows" to "Your shows tonight",
        "onnow" to "On now", "missed" to "Missed it?", "sport" to "Sport", "filmstv" to "Films on TV today", "mylist" to "My list",
        "family" to "Family picks", "coming" to "Coming soon", "plexdeck" to "Continue on Plex", "because" to "Because you watched",
        "trending" to "Trending this week", "tonight" to "Tonight", "topfilms" to "Top rated films", "plexnew" to "New on Plex",
        "collections" to "Your collections", "bookmarks" to "Bookmarks",
    )

    /** Home rows in your order, with whether each is shown. */
    val homeRows: List<Pair<String, Boolean>>
        get() {
            val saved = config.homeRows
            val order = saved.map { it.removePrefix("-") }.filter { it in homeRowNames } + homeRowNames.keys.filter { k -> saved.none { it.removePrefix("-") == k } }
            return order.map { k -> k to ("-$k" !in saved) }
        }

    private fun saveRows(list: List<Pair<String, Boolean>>) {
        config.homeRows = list.map { (k, on) -> if (on) k else "-$k" }
        changed(sync = false)
    }

    fun toggleHomeRow(key: String) = saveRows(homeRows.map { (k, on) -> k to if (k == key) !on else on })

    fun moveHomeRow(key: String, by: Int) {
        val list = homeRows.toMutableList()
        val i = list.indexOfFirst { it.first == key }
        val j = (i + by).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        val item = list.removeAt(i)
        list.add(j, item)
        saveRows(list)
    }

    fun resetHomeRows() {
        config.homeRows = emptyList()
        changed(sync = false)
    }

    // ---------------- Profiles: add, delete, kids' channels and age rating ----------------

    private fun saveProfilesRaw(arr: JSONArray, message: String) {
        config.profilesRaw = arr.toString()
        config.profiles = config.profilesFromJson(arr)
        config.profilesAt = System.currentTimeMillis()
        changed()
        say(message)
    }

    fun addProfile(name: String, kids: Boolean) {
        val n = name.trim().ifEmpty { return say("Give the profile a name") }
        val arr = runCatching { JSONArray(config.profilesRaw) }.getOrDefault(JSONArray())
        if (arr.length() == 0) config.profiles.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("kids", p.kids).put("groups", JSONArray(p.groups.toList()))
                .put("limit", p.limitMinutes).put("bedtime", p.bedtime).put("colour", p.colour).put("icon", p.icon))
        }
        val id = java.util.UUID.randomUUID().toString()
        arr.put(JSONObject().put("id", id).put("name", n).put("kids", kids).put("groups", JSONArray()).put("limit", 0).put("bedtime", -1).put("colour", -1).put("icon", ""))
        saveProfilesRaw(arr, "Added $n – the TV gets it at the next sync")
    }

    fun deleteProfile(id: String) {
        val arr = runCatching { JSONArray(config.profilesRaw) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        var grownUps = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) continue
            if (!o.optBoolean("kids")) grownUps++
            keep.put(o)
        }
        if (grownUps == 0) return say("Keep at least one grown-up's profile")
        if (profileId == id) config.profiles.firstOrNull { it.id != id && !it.kids }?.let { chooseProfile(it) }
        saveProfilesRaw(keep, "Profile deleted – on the TV too at the next sync")
    }

    /** Kids: which groups they can see, and the oldest age rating ("U", "PG", "12", "15"; "" = any). */
    fun setKidsChannels(id: String, groups: Set<String>, cert: String) {
        val arr = runCatching { JSONArray(config.profilesRaw) }.getOrDefault(JSONArray())
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            o.put("groups", JSONArray(groups.toList())).put("cert", cert)
            found = true
        }
        if (!found) return say("That profile hasn't come from the TV yet – sync first")
        manageVersion++
        saveProfilesRaw(arr, "Saved – the TV gets it at the next sync")
    }

    fun certOf(id: String): String = runCatching {
        val arr = JSONArray(config.profilesRaw)
        (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == id }?.optString("cert").orEmpty()
    }.getOrDefault("")

    /** Minutes of TV a profile has watched today (kids' profiles count it). */
    fun minutesToday(p: PcProfile): Int = (usedSeconds(p) / 60).toInt()

    // ---------------- Backup ----------------

    /** Saves all Flicker's settings to a file you pick (playlists, profiles, lists, recordings list…). */
    fun backUp() {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Save Flicker's settings"
            selectedFile = java.io.File("Flicker backup ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date())}.json")
        }
        if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        config.save()
        runCatching { config.file.copyTo(chooser.selectedFile, overwrite = true) }
            .onSuccess { say("Saved to ${chooser.selectedFile.name} – it includes your playlists' passwords, so keep it private") }
            .onFailure { say("Couldn't save the backup: ${it.message}") }
    }

    /** Puts back settings from a backup file (Flicker restarts its lists from it). */
    fun restore() {
        val chooser = javax.swing.JFileChooser().apply { dialogTitle = "Pick a Flicker backup" }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val f = chooser.selectedFile
        val ok = runCatching { org.json.JSONObject(f.readText()).has("sources") }.getOrDefault(false)
        if (!ok) return say("That isn't a Flicker backup")
        runCatching {
            config.file.copyTo(java.io.File(config.file.parentFile, "settings-before-restore.json"), overwrite = true)
            f.copyTo(config.file, overwrite = true)
            config.load()
        }.onFailure { return say("Couldn't restore: ${it.message}") }
        manageVersion++
        listsVersion++
        reload()
        say("Restored ✓ – your old settings are kept as settings-before-restore.json")
    }

    // ---------------- Artwork screensaver ----------------

    /** Last time you moved the mouse or pressed a key. */
    var lastActivity = System.currentTimeMillis()
    var saverOn by mutableStateOf(false)

    fun activity() {
        lastActivity = System.currentTimeMillis()
        if (saverOn) saverOn = false
    }

    /** Every 10 seconds: idle long enough with nothing playing? */
    fun tickSaver() {
        val m = config.screensaverMin
        if (m <= 0 || saverOn || watching != null || playingChannel != null || mini) return
        if (System.currentTimeMillis() - lastActivity >= m * 60_000L) saverOn = true
    }

    /** Pictures for the screensaver: what's on now, films and Plex. */
    fun saverPictures(): List<Pair<String, String>> {
        val t = System.currentTimeMillis()
        val out = ArrayList<Pair<String, String>>()
        for (c in visibleChannels.take(400)) {
            val p = guide[c.id]?.firstOrNull { it.start <= t && it.stop > t } ?: continue
            if (!p.image.isNullOrBlank()) out += p.image!! to "${p.title} · on ${c.name} now"
        }
        plexRecent.forEach { e -> (e.art ?: e.image)?.let { out += it to e.title } }
        if (!kids) movies.orEmpty().filter { !it.poster.isNullOrBlank() && (it.rating?.toDoubleOrNull() ?: 0.0) >= 7.5 }.shuffled().take(40)
            .forEach { out += it.poster!! to it.name }
        return out.shuffled()
    }

    fun setScreensaver(minutes: Int) {
        config.screensaverMin = minutes
        changed(sync = false)
    }

    // ---------------- The PIN (set here, it goes to the TV too) ----------------

    var settingPin by mutableStateOf(false)

    fun changePin() {
        if (config.pin != null) askPin("Your current PIN first") { settingPin = true } else settingPin = true
    }

    fun savePin(pin: String?) {
        settingPin = false
        config.pin = pin?.takeIf { it.length >= 4 }
        config.pinAt = System.currentTimeMillis()
        changed()
        syncSoon(0)
        say(if (config.pin != null) "PIN saved – the TV gets it at the next sync" else "PIN removed – on the TV too at the next sync")
    }

    // ---------------- "Flicker closed unexpectedly" ----------------

    var lastCrash by mutableStateOf(
        runCatching {
            val f = java.io.File(com.titanejstudios.tuner.data.AppLog.file.parentFile, "crashed.txt")
            if (f.exists()) f.readText().also { f.delete() }.ifBlank { null } else null
        }.getOrNull()
    )

    fun copyLog() {
        val text = "Flicker for Windows ${Keys.VERSION} · ${System.getProperty("os.name")}\n\n" +
            runCatching { com.titanejstudios.tuner.data.AppLog.file.readText().takeLast(60_000) }.getOrDefault("")
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
        say("Problem log copied – paste it into a message")
    }

    // ---------------- Bookmarks ----------------

    val bookmarks: List<Watchlist.Bookmark> get() = Watchlist.decodeBookmarks(config.bookmarksJson)

    fun bookmarkHere() {
        val w = watching ?: return say("Bookmarks are for films and episodes")
        val pos = player.positionMs
        val b = Watchlist.Bookmark(java.util.UUID.randomUUID().toString(), w.id, w.title, w.subtitle, w.poster, w.url, w.seriesId, pos, System.currentTimeMillis())
        config.bookmarksJson = Watchlist.encodeBookmarks((listOf(b) + bookmarks).take(50))
        changed(sync = false)
        say("🔖 Bookmarked at ${clock(pos)}")
    }

    fun playBookmark(b: Watchlist.Bookmark) {
        if (blockedForKids()) return
        stopWatching(save = true)
        playingChannel = null
        watching = Watching(b.contentId, b.title, b.subtitle, b.poster, b.url, b.seriesId, resumable = !b.contentId.startsWith("plex:") && !b.contentId.startsWith("rec:"))
        player.play(b.url, b.posMs)
        player.setVolume(config.volume)
    }

    fun deleteBookmark(id: String) {
        config.bookmarksJson = Watchlist.encodeBookmarks(bookmarks.filter { it.id != id })
        changed(sync = false)
    }

    // ---------------- Your collections ----------------

    val collections: Map<String, List<WatchItem>> get() = Watchlist.decodeCollections(config.collectionsJson)
    var collectingItem by mutableStateOf<WatchItem?>(null)

    private fun saveCollections(map: Map<String, List<WatchItem>>) {
        config.collectionsJson = Watchlist.encodeCollections(map)
        config.collectionsAt = System.currentTimeMillis()
        changed()
    }

    fun addToCollection(name: String, item: WatchItem) {
        val n = name.trim().take(40).ifEmpty { return }
        val list = collections[n].orEmpty()
        if (list.any { it.id == item.id }) return say("Already in $n")
        saveCollections(collections + (n to list + item))
        say("Added ${item.name} to $n")
    }

    fun removeFromCollection(name: String, id: String) {
        val list = collections[name].orEmpty().filter { it.id != id }
        saveCollections(if (list.isEmpty()) collections - name else collections + (name to list))
    }

    fun deleteCollection(name: String) = saveCollections(collections - name)

    fun openWatchItem(w: WatchItem) {
        if (w.isSeries) {
            openSeriesId = w.id
            page = Page.Series
        } else w.toVod()?.let { playFilm(it) }
    }

    // ---------------- Trakt ----------------

    val traktAvailable: Boolean get() = Keys.TRAKT_ID.isNotBlank() && Keys.TRAKT_SECRET.isNotBlank()
    var traktCode by mutableStateOf<com.titanejstudios.tuner.integrations.Trakt.DeviceCode?>(null)
    val traktUser: String? get() = config.traktAuth?.split('\t')?.getOrNull(3)?.ifBlank { "your account" }
    private val trakt by lazy { com.titanejstudios.tuner.integrations.Trakt(Keys.TRAKT_ID, Keys.TRAKT_SECRET) }

    fun linkTrakt() {
        if (!traktAvailable) return say("Trakt isn't set up in this build")
        scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { trakt.deviceCode() }.getOrNull() } ?: return@launch say("Couldn't reach Trakt")
            traktCode = code
            val until = System.currentTimeMillis() + code.expiresIn * 1000L
            while (traktCode === code && System.currentTimeMillis() < until) {
                delay(code.interval * 1000L)
                val t = withContext(Dispatchers.IO) { runCatching { trakt.checkCode(code) }.getOrNull() } ?: continue
                val user = withContext(Dispatchers.IO) { runCatching { trakt.username(t.access) }.getOrNull() } ?: ""
                config.traktAuth = listOf(t.access, t.refresh, t.expiresAt.toString(), user).joinToString("\t")
                traktCode = null
                changed(sync = false)
                say("Trakt linked ✓ – films and episodes you finish are logged")
                importTraktWatched()
                return@launch
            }
            if (traktCode === code) traktCode = null
        }
    }

    fun unlinkTrakt() {
        config.traktAuth = null
        traktCode = null
        changed(sync = false)
        say("Trakt unlinked")
    }

    /** A working Trakt token (refreshed when it's run out). Blocking. */
    private fun traktAccess(): String? {
        val parts = config.traktAuth?.split('\t') ?: return null
        val expires = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < expires - 86_400_000L) return parts[0]
        val t = runCatching { trakt.refresh(parts[1]) }.getOrNull() ?: return null
        config.traktAuth = listOf(t.access, t.refresh, t.expiresAt.toString(), parts.getOrNull(3) ?: "").joinToString("\t")
        return t.access
    }

    /** Logs a finished film or episode to Trakt. */
    fun logToTrakt(w: Watching) {
        if (config.traktAuth == null || w.id.startsWith("rec:") || w.id.startsWith("url:") || w.id.startsWith("file:")) return
        scope.launch(Dispatchers.IO) {
            val access = traktAccess() ?: return@launch
            val se = Regex("S(\\d+) ?E(\\d+)").find(w.subtitle ?: "")
            runCatching {
                if (se != null) trakt.addEpisode(access, com.titanejstudios.tuner.data.FilmName.parse(w.title).first, se.groupValues[1].toInt(), se.groupValues[2].toInt())
                else com.titanejstudios.tuner.data.FilmName.parse(w.title).let { (t, y) -> trakt.addFilm(access, t, y) }
            }
        }
    }

    /** What you've watched on Trakt counts as watched here (Surprise me and Top rated skip them). */
    fun importTraktWatched() {
        val films = movies ?: return
        scope.launch {
            val watched = withContext(Dispatchers.IO) { traktAccess()?.let { a -> runCatching { trakt.watchedFilms(a) }.getOrDefault(emptyList()) } }.orEmpty()
            if (watched.isEmpty()) return@launch
            fun norm(s: String) = com.titanejstudios.tuner.data.FilmName.parse(s).first.lowercase().filter { it.isLetterOrDigit() }
            val keys = watched.map { norm(it.title) }.toSet()
            val add = films.filter { norm(it.name) in keys && it.id !in config.done }.associate { it.id to System.currentTimeMillis() }
            if (add.isNotEmpty()) {
                config.done = config.done + add
                changed()
                say("Trakt: ${add.size} films you've seen are marked as watched")
            }
        }
    }

    // ---------------- Cancel a reminder ----------------
    fun cancelReminder(r: Reminder) {
        config.reminders = config.reminders - r
        changed(sync = false)
    }

    // ---------------- Updates ----------------

    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableStateOf<Float?>(null)

    /** At start and every few hours (or now): is there a newer Flicker for Windows? */
    fun checkForUpdate(manual: Boolean = false) {
        // Every start, then every few hours while it's open (not more than once an hour).
        if (!manual && System.currentTimeMillis() - config.lastUpdateCheck < 3_600_000L) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.latest() }.getOrNull() }
            config.lastUpdateCheck = System.currentTimeMillis()
            withContext(Dispatchers.IO) { config.save() }
            if (r != null && Updater.newer(r.version, Keys.VERSION)) update = r
            else if (manual) say(if (r == null) "Couldn't check for updates" else "You've got the latest Flicker (${Keys.VERSION})")
        }
    }

    fun installUpdate() {
        val r = update ?: return
        if (updateProgress != null) return
        updateProgress = 0f
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Updater.download(r) { p -> scope.launch { updateProgress = p } } }.getOrNull()
            }
            if (file == null) {
                updateProgress = null
                return@launch say("Couldn't download the update – try again later")
            }
            stopWatching(save = true)
            config.save()
            Updater.install(file)
            kotlin.system.exitProcess(0)
        }
    }

    // ---------------- Linked devices (phone remote, Watch on…) ----------------

    val link = PcLink(config)
    var peers by mutableStateOf<List<PcLink.Peer>>(emptyList())
    var findingPeers by mutableStateOf(false)

    fun startLink() {
        link.host = object : PcLink.Host {
            override fun deviceName() = sync.deviceLabel()
            override fun stateJson() = sync.snapshot()
            override fun remoteState(): JSONObject {
                val o = JSONObject()
                val w = watching
                val c = playingChannel
                when {
                    w != null -> o.put("title", w.title).put("detail", w.subtitle ?: "Film").put("what", w.title).put("series", w.seriesId != null)
                    c != null -> {
                        val p = catchupOf ?: guide[c.id]?.firstOrNull { it.start <= now && it.stop > now }
                        o.put("title", c.name).put("detail", p?.title ?: "Live")
                        if (p != null) o.put("what", p.title).put("desc", p.desc ?: "").put("series", true)
                    }
                    else -> o.put("title", "Flicker").put("detail", "Nothing playing · ${page.label}")
                }
                return o
            }
            override fun remoteChannels(): JSONArray {
                val a = JSONArray()
                val t = System.currentTimeMillis()
                visibleChannels.take(4000).forEachIndexed { i, c ->
                    val cur = guide[c.id]?.firstOrNull { it.start <= t && it.stop > t }
                    a.put(JSONObject().put("id", c.id).put("n", i + 1).put("name", c.name).put("group", c.group).put("logo", c.logo).put("now", cur?.title))
                }
                return a
            }
            override fun remoteKey(code: Int) = pressFromRemote(code)
            override fun remotePlay(channelId: String) {
                channels.firstOrNull { it.id == channelId }?.let { playChannel(it) }
            }
            override fun play(from: String, json: JSONObject): Boolean = playFromLink(from, json)
        }
        link.start()
    }

    /** Buttons from the phone's remote (Android key codes). */
    private fun pressFromRemote(code: Int) {
        val w = watching != null
        when (code) {
            166, 19 -> if (!w) zapBy(1)          // channel up / ▲
            167, 20 -> if (!w) zapBy(-1)         // channel down / ▼
            21 -> if (w) player.skip(-10_000L)    // ◀
            22 -> if (w) player.skip(30_000L)     // ▶
            23, 66, 85, 126, 127 -> player.togglePause()
            24 -> { config.volume = (config.volume + 10).coerceAtMost(150); player.setVolume(config.volume) }
            25 -> { config.volume = (config.volume - 10).coerceAtLeast(0); player.setVolume(config.volume) }
            164 -> player.toggleMute()
            4 -> if (w) stopWatching(save = true) else page = Page.Home
            -1 -> {
                stopWatching(save = true)
                page = Page.Home
            }
        }
    }

    fun zapBy(d: Int) {
        val list = liveList.ifEmpty { visibleChannels }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == playingChannel?.id }
        playChannel(list[(i + d).mod(list.size)])
    }

    /** The TV or phone sent something to play here. */
    private fun playFromLink(from: String, j: JSONObject): Boolean {
        val who = from.ifBlank { "another device" }
        when (j.optString("kind")) {
            "askmore", "bonus", "askno" -> {
                receiveKids(from, j)
                return true
            }
            "together" -> {
                handleTogether(who, j)
                return true
            }
            "live" -> {
                val c = channels.firstOrNull { it.id == j.optString("channel") } ?: return false
                playChannel(c)
            }
            "catchup" -> {
                val c = channels.firstOrNull { it.id == j.optString("channel") } ?: return false
                val p = guide[c.id]?.firstOrNull { it.start == j.optLong("start") }
                    ?: Programme(j.optLong("start"), j.optLong("stop"), j.optString("title"), null, j.optString("category").ifBlank { null })
                playCatchup(c, p)
            }
            "vod" -> {
                val url = j.optString("url").ifBlank { return false }
                val id = j.optString("id")
                val w = Watching(id, j.optString("title"), j.optString("sub").ifBlank { null }, j.optString("poster").ifBlank { null }, url,
                    j.optString("series").ifBlank { null }?.takeIf { it != "null" }, resumable = !id.startsWith("plex:"))
                stopWatching(save = true)
                playingChannel = null
                watching = w
                player.play(url, j.optLong("pos"))
                player.setVolume(config.volume)
            }
            "youtube" -> {
                val id = j.optString("id").ifBlank { return false }
                openInBrowser("https://www.youtube.com/watch?v=$id")
            }
            else -> return false
        }
        say("▶ Sent from $who")
        if (j.optBoolean("together")) joinTogether(from)
        return true
    }

    fun findPeers() {
        if (link.key == null) return say("Sign in to Sync first – the TV shares its link through it")
        findingPeers = true
        scope.launch {
            peers = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }
            findingPeers = false
            if (peers.isEmpty()) say("No TVs found – is Flicker open on them, and on the same Wi-Fi?")
        }
    }

    /** What's playing here, for "Watch on the TV". */
    private fun sendable(): JSONObject? {
        val w = watching
        val c = playingChannel
        return when {
            w != null && !w.id.startsWith("rec:") -> JSONObject().put("kind", "vod").put("id", w.id).put("title", w.title).put("sub", w.subtitle)
                .put("poster", w.poster).put("url", w.url).put("series", w.seriesId).put("pos", player.positionMs)
            c != null && catchupOf != null -> catchupOf?.let { p ->
                JSONObject().put("kind", "catchup").put("channel", c.id).put("name", c.name).put("url", c.url)
                    .put("start", p.start).put("stop", p.stop).put("title", p.title).put("category", p.category).put("pos", player.positionMs)
            }
            c != null -> JSONObject().put("kind", "live").put("channel", c.id)
            else -> null
        }
    }

    /** A channel (or a catch-up programme) straight to the TV, from the guide. */
    fun watchOnTv(c: Channel, catchup: Programme?) {
        if (link.key == null) return say("Sign in to Sync first – the TV shares its link through it")
        val what = if (catchup != null) JSONObject().put("kind", "catchup").put("channel", c.id).put("name", c.name).put("url", c.url)
            .put("start", catchup.start).put("stop", catchup.stop).put("title", catchup.title).put("category", catchup.category).put("pos", 0L)
        else JSONObject().put("kind", "live").put("channel", c.id)
        say("Finding your TV…")
        scope.launch {
            val tvs = withContext(Dispatchers.IO) { runCatching { link.findPeers() }.getOrDefault(emptyList()) }.filter { !it.phone }
            val tv = tvs.firstOrNull() ?: return@launch say("No TV found – is Flicker open on it, and on the same Wi-Fi?")
            val ok = withContext(Dispatchers.IO) { link.send(tv, what, sync.deviceLabel()) }
            say(if (ok) "▶ Playing on ${tv.name}" else "${tv.name} didn't answer")
        }
    }

    fun sendTo(peer: PcLink.Peer) {
        val what = sendable() ?: return say("Nothing to send")
        if (watching != null) saveProgress(sync = true)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { link.send(peer, what, sync.deviceLabel()) }
            if (ok) {
                say("▶ Playing on ${peer.name}")
                stopWatching(save = false)
                player.stop()
                playingChannel = null
            } else say("${peer.name} didn't answer")
        }
    }

    // ---------------- Reminders ----------------

    fun hasReminder(c: Channel, p: Programme) = config.reminders.any { it.channelId == c.id && it.start == p.start }

    fun toggleReminder(c: Channel, p: Programme) {
        config.reminders = if (hasReminder(c, p)) config.reminders.filterNot { it.channelId == c.id && it.start == p.start }
        else config.reminders + Reminder(c.id, c.name, p.title, p.start)
        scope.launch(Dispatchers.IO) { config.save() }
        say(if (hasReminder(c, p)) "⏰ Flicker will remind you when ${p.title} starts" else "Reminder cancelled")
    }

    /** Every 20 seconds: a reminder that's due. */
    fun checkReminders() {
        val t = System.currentTimeMillis()
        if (dueReminder != null) return
        val due = config.reminders.firstOrNull { t >= it.start - 60_000L } ?: return
        config.reminders = config.reminders - due
        scope.launch(Dispatchers.IO) { config.save() }
        if (t - due.start < 30 * 60_000L) {
            dueReminder = due
            notify("⏰ ${due.title}", "Starting now on ${due.channelName}")
        }
    }

    // ---------------- Sync ----------------

    private var syncJob: Job? = null

    fun startSignIn() {
        if (!sync.configured) return say("This copy of Flicker was built without Google sign-in (see the README)")
        scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { sync.drive.startSignIn() } }.getOrElse {
                syncStatus = "Couldn't reach Google: ${it.message}"
                return@launch
            }
            signInCode = code
            syncStatus = null
            var interval = code.intervalSec.coerceAtLeast(5) * 1000L
            while (signInCode === code && System.currentTimeMillis() < code.expiresAt) {
                delay(interval)
                when (val r = withContext(Dispatchers.IO) { runCatching { sync.drive.poll(code) }.getOrNull() }) {
                    is GoogleDrive.Poll.Done -> {
                        config.googleRefresh = r.tokens.refresh
                        config.googleEmail = r.tokens.email
                        withContext(Dispatchers.IO) { config.save() }
                        signInCode = null
                        signedIn = true
                        say("Signed in – bringing in your TV's channels and lists")
                        syncSoon(0)
                        return@launch
                    }
                    is GoogleDrive.Poll.Failed -> {
                        signInCode = null
                        syncStatus = r.reason
                        return@launch
                    }
                    GoogleDrive.Poll.SlowDown -> interval += 5_000L
                    else -> Unit
                }
            }
            if (signInCode === code) {
                signInCode = null
                syncStatus = "The code expired – try again"
            }
        }
    }

    fun cancelSignIn() {
        signInCode = null
    }

    fun signOut() {
        config.googleRefresh = null
        config.googleEmail = null
        config.save()
        signedIn = false
        say("Signed out – the TV's lists stay here")
    }

    fun syncSoon(delayMs: Long = 5_000L) {
        if (config.googleRefresh == null) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            syncStatus = "Syncing…"
            val r = withContext(Dispatchers.IO) { runCatching { sync.syncNow() } }
            r.onSuccess { res ->
                syncStatus = null
                if (res.linkKeyChanged) startLink()
                if (res.listsChanged) listsVersion++
                if (config.activeProfile == null && config.profiles.size > 1) askWhoIsWatching = true
                profileId = config.profileKey
                if (res.playlistsChanged) reload()
                else if (res.guideChanged) fillBackupGuide()
            }.onFailure { syncStatus = "Couldn't sync: ${it.message}" }
        }
    }

    // ---------------- Plex ----------------

    fun linkPlex() {
        scope.launch {
            val pin = withContext(Dispatchers.IO) { runCatching { plex.createPin() }.getOrNull() } ?: return@launch say("Couldn't reach Plex")
            plexCode = pin.code
            repeat(120) {
                delay(3_000)
                if (plexCode != pin.code) return@launch
                val token = withContext(Dispatchers.IO) { runCatching { plex.checkPin(pin) }.getOrNull() }
                if (token != null) {
                    config.plexToken = token
                    withContext(Dispatchers.IO) { config.save() }
                    plexCode = null
                    plexLinked = true
                    say("Plex linked ✓")
                    loadPlex()
                    return@launch
                }
            }
            plexCode = null
        }
    }

    fun cancelPlexLink() {
        plexCode = null
    }

    fun unlinkPlex() {
        config.plexToken = null
        config.save()
        plexLinked = false
        plexServers = emptyList()
        plexDeck = emptyList()
        plexSections = emptyList()
    }

    fun loadPlex() {
        val token = config.plexToken ?: return
        scope.launch {
            val servers = withContext(Dispatchers.IO) { runCatching { plex.servers(token) }.getOrDefault(emptyList()) }
            plexServers = servers
            if (servers.isEmpty()) return@launch
            plexDeck = withContext(Dispatchers.IO) { runCatching { plex.onDeck(servers) }.getOrDefault(emptyList()) }
            plexSections = withContext(Dispatchers.IO) { runCatching { plex.sections(servers) }.getOrDefault(emptyList()) }
            plexRecent = withContext(Dispatchers.IO) { runCatching { plex.recentlyAdded(servers, 30) }.getOrDefault(emptyList()) }
        }
    }

    /** Plays a Plex film or episode (asks the server for the file if the list didn't say). */
    fun playPlex(ratingKey: String, title: String, subtitle: String?, image: String?, playUrl: String?, server: Plex.Server, offsetMs: Long, fromStart: Boolean = false) {
        scope.launch {
            val url = playUrl ?: withContext(Dispatchers.IO) { runCatching { plex.playUrlFor(server, ratingKey) }.getOrNull() }
            if (url == null) return@launch say("Plex didn't give the file for $title")
            stopWatching(save = true)
            playingChannel = null
            watching = Watching("plex:$ratingKey", title, subtitle, image, url, null, resumable = false)
            plexPlayingServer = server
            player.play(url, if (fromStart) 0L else offsetMs)
            player.setVolume(config.volume)
        }
    }
}
