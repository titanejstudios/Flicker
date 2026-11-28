package com.titanejstudios.tuner

import android.app.Application
import android.content.Intent
import org.json.JSONObject
import android.net.Uri
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import com.titanejstudios.tuner.apps.InstalledApps
import com.titanejstudios.tuner.apps.TvApp
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Catchup
import com.titanejstudios.tuner.data.Channel
import com.titanejstudios.tuner.data.Database
import com.titanejstudios.tuner.data.HealthCheck
import com.titanejstudios.tuner.data.Genres
import com.titanejstudios.tuner.data.HomeFeed
import com.titanejstudios.tuner.data.VoiceMatch
import com.titanejstudios.tuner.data.WatchStats
import com.titanejstudios.tuner.tv.LiveChannelSync
import com.titanejstudios.tuner.data.WhatsOn
import com.titanejstudios.tuner.ui.SubtitleLook
import com.titanejstudios.tuner.ui.TunerColors
import com.titanejstudios.tuner.data.RecordingPaths
import com.titanejstudios.tuner.data.SeriesLink
import com.titanejstudios.tuner.data.SeriesLinks
import com.titanejstudios.tuner.data.StreamAlternates
import com.titanejstudios.tuner.data.Episode
import com.titanejstudios.tuner.data.Profile
import com.titanejstudios.tuner.data.Programme
import com.titanejstudios.tuner.data.RecStatus
import com.titanejstudios.tuner.data.Recording
import com.titanejstudios.tuner.data.Reminder
import com.titanejstudios.tuner.data.Repository
import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.SeriesItem
import com.titanejstudios.tuner.data.FilmName
import com.titanejstudios.tuner.data.Taste
import com.titanejstudios.tuner.data.Recommend
import com.titanejstudios.tuner.data.Languages
import com.titanejstudios.tuner.data.CatchupApps
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.data.VodItem
import com.titanejstudios.tuner.data.XmltvChannel
import com.titanejstudios.tuner.data.XmltvParser
import com.titanejstudios.tuner.data.XtreamClient
import com.titanejstudios.tuner.data.FollowedSeries
import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.SafeMode
import com.titanejstudios.tuner.data.VodCache
import com.titanejstudios.tuner.data.WatchItem
import com.titanejstudios.tuner.data.Watchlist
import com.titanejstudios.tuner.data.FamilyPick
import com.titanejstudios.tuner.data.ComingEpisode
import com.titanejstudios.tuner.features.FootballFeature
import com.titanejstudios.tuner.features.PlexFeature
import com.titanejstudios.tuner.features.SelfTest
import com.titanejstudios.tuner.features.UpdateFeature
import com.titanejstudios.tuner.integrations.CinemaLights
import com.titanejstudios.tuner.playback.StreamCushion
import com.titanejstudios.tuner.playback.VolumeLevels
import com.titanejstudios.tuner.data.SportFollow
import com.titanejstudios.tuner.data.ChannelMerge
import com.titanejstudios.tuner.data.Sports
import com.titanejstudios.tuner.integrations.NasBackup
import com.titanejstudios.tuner.integrations.OpenSubtitles
import com.titanejstudios.tuner.integrations.Tmdb
import com.titanejstudios.tuner.integrations.Omdb
import com.titanejstudios.tuner.integrations.TvSync
import com.titanejstudios.tuner.integrations.AppState
import com.titanejstudios.tuner.integrations.HomeAssistantWebhook
import com.titanejstudios.tuner.integrations.ReminderAlarms
import com.titanejstudios.tuner.integrations.TvShare
import com.titanejstudios.tuner.integrations.Plex
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import com.titanejstudios.tuner.integrations.RemoteHandler
import com.titanejstudios.tuner.playback.MultiViewController
import com.titanejstudios.tuner.playback.PlaybackController
import com.titanejstudios.tuner.recording.RecordingScheduler
import com.titanejstudios.tuner.tv.GuideRefreshWorker
import com.titanejstudios.tuner.tv.HomeScreen
import com.titanejstudios.tuner.ui.hhmm
import com.titanejstudios.tuner.ui.dayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Part of TunerViewModel, split out by subject (functions only; its state stays in the view model).

internal fun TunerViewModel.trackStats(flush: Boolean) {
    val m = mode
    if (m != null && playback.isPlaying && inForeground) {
        val (name, genre) = when (m) {
            is PlayMode.Live -> m.channel.name to nowNext(m.channel.id, System.currentTimeMillis()).first
                .let { p -> Genres.of(p?.category, p?.title ?: "", m.channel.group) }
            is PlayMode.CatchupPlay -> m.channel.name to Genres.of(m.programme.category, m.programme.title, m.channel.group)
            is PlayMode.Vod -> (if (m.seriesId != null) "Series" else "Films") to (if (m.seriesId != null) "Drama" else "Film")
            is PlayMode.RecordingPlay -> "Recordings" to Genres.of(m.recording.category, m.recording.title, null)
        }
        val key = name to (genre ?: "Other")
        pendingStats[key] = (pendingStats[key] ?: 0L) + 15L
        noteWatchingSlot(System.currentTimeMillis())
        if (m is PlayMode.Live) pendingHabits[m.channel.id] = (pendingHabits[m.channel.id] ?: 0L) + 15L
        if (m is PlayMode.Live) {
            val now = System.currentTimeMillis()
            val p = nowNext(m.channel.id, now).first
            val w = liveWatch
            if (p != null && w != null && w.channelId == m.channel.id && w.start == p.start) {
                w.seconds += 15
                w.lastSeen = now
            } else {
                finishLiveWatch()
                if (p != null) liveWatch = LiveWatch(m.channel.id, p.start, 15, now)
            }
        } else finishLiveWatch()
        // Learning what you like: the programme or film, and its kind.
        val title = when (m) {
            is PlayMode.Live -> nowNext(m.channel.id, System.currentTimeMillis()).first?.title
            is PlayMode.CatchupPlay -> m.programme.title
            is PlayMode.Vod -> m.title
            is PlayMode.RecordingPlay -> m.recording.title
        }
        if (title != null) pendingTitles[title] = (pendingTitles[title] ?: 0L) + 15L
        if (title != null && learnTaste) {
            val tk = title to genre
            pendingTaste[tk] = (pendingTaste[tk] ?: 0L) + 15L
        }
    }
    // YouTube counts too (it plays outside the main player).
    val yt = ytVideo
    if (yt != null && screen == Screen.YouTubePlayer && inForeground) {
        val key = "YouTube" to "YouTube"
        pendingStats[key] = (pendingStats[key] ?: 0L) + 15L
        val t = "▶ " + yt.title
        pendingTitles[t] = (pendingTitles[t] ?: 0L) + 15L
        noteWatchingSlot(System.currentTimeMillis())
    }
    if (flush) saveWatchingSlots()
    if (flush && pendingStats.isNotEmpty()) {
        val pid = activeProfile.id
        val day = WatchStats.dayKey(System.currentTimeMillis())
        var json = store.stats(pid)
        for ((k, secs) in pendingStats) json = WatchStats.add(json, day, k.first, k.second, secs)
        for ((title, secs) in pendingTitles) json = WatchStats.addTitle(json, day, title, secs)
        pendingTitles.clear()
        pendingStats.clear()
        store.setStats(pid, json)
    }
    if (flush && pendingHabits.isNotEmpty()) {
        val pid = activeProfile.id
        val t = System.currentTimeMillis()
        var json = store.habits(pid)
        for ((id, secs) in pendingHabits) json = com.titanejstudios.tuner.data.Habits.add(json, t, id, secs)
        pendingHabits.clear()
        store.setHabits(pid, json)
    }
    if (flush && pendingTaste.isNotEmpty()) {
        val t = System.currentTimeMillis()
        for ((k, secs) in pendingTaste) taste.record(k.first, k.second, secs, t)
        pendingTaste.clear()
        store.setTaste(activeProfile.id, taste.toJson())
    }
}

internal fun TunerViewModel.openStats(profile: Profile = activeProfile) {
    trackStats(flush = true)
    if (screen != Screen.Stats) navigate(Screen.Stats)
    statsProfile = profile
    statsSummary = null
    val pid = profile.id
    statsBlocked = blockedThisWeek(profile)
    viewModelScope.launch {
        statsSummary = withContext(Dispatchers.Default) { WatchStats.summary(store.stats(pid), System.currentTimeMillis()) }
        // A kids' profile: add in what they watched on the other linked devices (the TVs).
        val k = store.linkKey
        // No linked devices seen yet: look, then add theirs in.
        if (profile.kids && k != null && link.peers.isEmpty()) {
            link.search { if (statsProfile?.id == pid && screen == Screen.Stats && link.peers.isNotEmpty()) openStats(profile) }
            return@launch
        }
        if (profile.kids && k != null && link.peers.isNotEmpty()) {
            val peers = link.peers
            val (merged, blocked) = withContext(Dispatchers.IO) {
                var json = store.stats(pid)
                val lines = ArrayList(store.blockedLog(pid))
                for (p in peers) runCatching {
                    val o = com.titanejstudios.tuner.integrations.LinkServer.state(p.host, p.port, k).optJSONObject("kidsweek")?.optJSONObject(pid)
                    if (o != null) {
                        json = WatchStats.merge(json, o.optString("stats", "{}"))
                        o.optJSONArray("blocked")?.let { a -> for (i in 0 until a.length()) lines += a.optString(i) }
                    }
                }
                json to lines
            }
            if (statsProfile?.id != pid) return@launch
            statsSummary = withContext(Dispatchers.Default) { WatchStats.summary(merged, System.currentTimeMillis()) }
            val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
            statsBlocked = blocked.distinct().mapNotNull { l ->
                val parts = l.split('\t')
                val t = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                if (t < weekAgo) null else Triple(t, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            }.sortedByDescending { it.first }
        }
    }
}

internal fun TunerViewModel.toggleAudioPassthrough() {
    audioPassthrough = !audioPassthrough
    store.audioPassthrough = audioPassthrough
    showNotice("Saved – close and reopen Flicker for this to take effect")
}

internal fun TunerViewModel.toggleStreamCompatibility() {
    streamCompatibility = !streamCompatibility
    store.streamCompatibility = streamCompatibility
    showNotice("Saved – close and reopen Flicker for this to take effect")
}

internal fun TunerViewModel.toggleTimeshift() {
    timeshiftEnabled = !timeshiftEnabled
    store.timeshiftEnabled = timeshiftEnabled
}

internal fun TunerViewModel.toggleHideDead() {
    hideDead = !hideDead
    store.hideDead = hideDead
    recomputeVisible()
}

internal fun TunerViewModel.toggleHealthNightly() {
    healthNightly = !healthNightly
    store.healthNightly = healthNightly
}

internal fun TunerViewModel.toggleHealthScope() {
    healthScope = if (healthScope == "all") "favourites" else "all"
    store.healthScope = healthScope
}

/** Checks channels now. Stops playback first, since many providers allow only one connection. */
internal fun TunerViewModel.runHealthCheck() {
    if (healthJob?.isActive == true) return
    stopPlayback()
    val targets = if (healthScope == "all") allChannels.filter { it.group !in hiddenGroups && it.id !in hiddenChannels }
    else allChannels.filter { it.id in favouriteSet }
    if (targets.isEmpty()) {
        showNotice(if (healthScope == "all") "No channels to check" else "Add some favourites first, or check all channels")
        return
    }
    healthCancel = false
    healthJob = viewModelScope.launch {
        healthProgress = "Starting…"
        var done = 0
        val dead = withContext(Dispatchers.IO) {
            HealthCheck.run(targets, { healthCancel }) { d, total, deadCount ->
                done = d
                viewModelScope.launch { healthProgress = "Checked $d of $total · $deadCount offline" }
            }
        }
        val checked = targets.take(done).map { it.id }.toSet()
        deadChannels = (store.deadChannels - checked) + dead
        store.deadChannels = deadChannels
        healthCheckedAt = System.currentTimeMillis()
        store.healthCheckedAt = healthCheckedAt
        healthProgress = null
        recomputeVisible()
        offlineChanged()
        showNotice("Checked $done channels – ${dead.size} offline")
    }
}

internal fun TunerViewModel.cancelHealthCheck() {
    healthCancel = true
}

/** Offline, and nothing on it in the guide soon: shown greyed as "off air". */
internal fun TunerViewModel.isOffAir(channelId: String): Boolean = channelId in deadChannels && channelId !in onAirSoon

internal fun TunerViewModel.toggleMatchDay(channelId: String) {
    matchDayChannels = if (channelId in matchDayChannels) matchDayChannels - channelId else matchDayChannels + channelId
    store.matchDayChannels = matchDayChannels
    recomputeVisible()
    showNotice(if (channelId in matchDayChannels) "⚽ Match-day channel – never hidden, just \"off air\" between matches" else "No longer a match-day channel")
}

/**
 * Event channels are only live on match days, so a check on a quiet day marks them offline.
 * Every few minutes: any offline channel the guide says has a real programme on now or within
 * the hour comes back ("🔴 On today"); every few hours (while nothing's playing, since many
 * providers allow one connection) the offline ones are quietly checked again.
 */
internal fun TunerViewModel.matchDayCheck() {
    val dead = deadChannels
    if (dead.isEmpty()) {
        if (onAirSoon.isNotEmpty()) onAirSoon = emptySet()
        return
    }
    val t = System.currentTimeMillis()
    val byId = allChannels.associateBy { it.id }
    val soon = dead.filterTo(HashSet()) { id ->
        val ch = byId[id] ?: return@filterTo false
        // Only live sport counts: ordinary programmes in the guide don't mean a dead channel works.
        programmesFor(id).any { p ->
            p.stop > t && p.start < t + 3_600_000L && isRealProgramme(p.title, ch.name) &&
                com.titanejstudios.tuner.data.Sports.classify(p, ch.group) != null && com.titanejstudios.tuner.data.Sports.looksLive(p)
        }
    }
    if (soon != onAirSoon) {
        val newlyOn = soon - onAirSoon
        onAirSoon = soon
        recomputeVisible()
        if (newlyOn.isNotEmpty()) AppLog.info("Match day: ${newlyOn.size} offline channel(s) have something on soon – showing them")
    }
    val every = if (liteOn) 6 * 3_600_000L else 3 * 3_600_000L
    if (t - offlineRecheckedAt < every || mode != null || healthJob?.isActive == true) return
    offlineRecheckedAt = t
    // The ones on soon first, then the rest (up to 60, so it's over in a minute or two).
    val targets = dead.mapNotNull { byId[it] }.sortedByDescending { it.id in soon }.take(60)
    if (targets.isEmpty()) return
    healthJob = viewModelScope.launch {
        var done = 0
        val stillDead = withContext(Dispatchers.IO) { HealthCheck.run(targets, { mode != null || healthCancel }) { d, _, _ -> done = d } }
        // Only the channels it actually got to count (it stops part-way if something starts playing).
        val back = targets.take(done).map { it.id }.toSet() - stillDead
        if (back.isNotEmpty()) {
            deadChannels = store.deadChannels - back
            store.deadChannels = deadChannels
            recomputeVisible()
            AppLog.info("Offline recheck: ${back.size} channel(s) working again")
            offlineChanged()
        }
    }
}

/** A programme worth showing a channel for (not "No event", "Off air" or just the channel's name). */
internal fun TunerViewModel.isRealProgramme(title: String, channelName: String): Boolean {
    val t = title.trim().lowercase()
    if (t.length < 3 || t == channelName.trim().lowercase()) return false
    val empty = listOf("no event", "no events", "off air", "no information", "no programme", "no program", "closedown", "to be announced", "tba", "no stream", "event not started")
    return empty.none { t == it || t.startsWith("$it ") || t.startsWith("$it:") || t.startsWith("$it -") }
}

internal fun TunerViewModel.clearDead(channelId: String? = null) {
    deadChannels = if (channelId == null) emptySet() else store.deadChannels - channelId
    if (channelId == null) com.titanejstudios.tuner.data.AppLog.info("Offline list cleared by hand")
    store.deadChannels = deadChannels
    recomputeVisible()
    offlineChanged()
}

internal fun TunerViewModel.addSourceFromPhone(s: Source) {
    val first = sources.isEmpty()
    sources = sources + s
    store.sources = sources
    if (first) {
        activeSourceId = s.id
        store.activeSourceId = s.id
        stopPhoneSetup()
        resetTo(Screen.Home)
    }
    showNotice("${s.name} added from your phone – loading channels…")
    movies = null
    series = null
    refreshJob?.cancel()
    refresh(listOf(s))
}
