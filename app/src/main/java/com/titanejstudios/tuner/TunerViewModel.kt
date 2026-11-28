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

enum class Screen {
    Home, Channels, Guide, Player, Search, Movies, Series, SeriesDetail, Settings, SourceEdit, Manage,
    ProfilePicker, ProfileEdit, PhoneSetup, EpgMap, Recordings, MultiView, Sports, Apps, Log,
    WhatsOn, Stats, ShareTv, Catchup, Categories, Plex, Update, SelfTest, PlexLibrary, PlexShow, HomeRows, Languages, Recommendations, Vpn,
    Link, Downloads, Teams, Remote, Christmas, Cloud, YouTube, YouTubePlayer, YouTubeList, More, Library, Collections,
}

const val FAVOURITES = "★ Favourites"
const val RECENT = "Recently watched"
const val ALL = "All channels"
const val ALL_SOURCES = "all"

/** Presses of ▲▼ closer together than this count as "flicking through" channels. */
private const val ZAP_RAPID_MS = 700L
/** How long after the last quick press the channel is actually tuned. */
private const val ZAP_SETTLE_MS = 450L

/** What the player is showing. */
sealed class PlayMode {
    data class Live(val channel: Channel) : PlayMode()
    data class CatchupPlay(val channel: Channel, val programme: Programme) : PlayMode()
    data class Vod(
        val contentId: String,
        val title: String,
        val subtitle: String?,
        val poster: String?,
        val url: String,
        val seriesId: String?,
    ) : PlayMode()
    data class RecordingPlay(val recording: Recording) : PlayMode()
}

class PinPrompt(val title: String, val onEntered: (String) -> Unit)

class SearchResults(
    val channels: List<Channel>,
    val programmes: List<Pair<Channel, Programme>>,
    val films: List<VodItem> = emptyList(),
    val seriesFound: List<SeriesItem> = emptyList(),
    /** The actor whose films are first in [films] ("Tom Hanks"), when the search was a name. */
    val actor: String? = null,
)

/** Tracks how long a live programme has been watched (for "finish what you started"). */
internal class LiveWatch(val channelId: String, val start: Long, var seconds: Long, var lastSeen: Long)

/** One of your teams' matches, and which of your channels shows it (if the guide says yet). */
class TeamFixture(
    val match: com.titanejstudios.tuner.integrations.Espn.Fixture,
    val channel: Channel?,
    val programme: Programme?,
    /** The guide reaches that day, so "no channel" really means none of yours shows it. */
    val guideCoversIt: Boolean,
    /** Every channel showing it, best first ([channel] is the first). */
    val channels: List<Pair<Channel, Programme>> = listOfNotNull(if (channel != null && programme != null) channel to programme else null),
) {
    /** "Newcastle v Arsenal" (or the guide's title for games found only in the guide). */
    val title: String get() = if (match.away.isBlank()) match.home else "${match.home} v ${match.away}"
}


/** One programme in the Christmas TV planner. [section] is "specials", "films" or "sport". */
class FestiveItem(val channel: Channel, val programme: Programme, val section: String)

/** A programme you switched off half-way, and where you got to in it. */
class Unfinished(val channelId: String, val programme: Programme, val positionMs: Long, val at: Long)

/** A trending film or show, and where you have it. */
class TrendingItem(
    val trend: com.titanejstudios.tuner.integrations.Tmdb.Trend,
    val plex: com.titanejstudios.tuner.integrations.Plex.Entry?,
    val film: VodItem?,
    val series: SeriesItem?,
) {
    val available: Boolean get() = plex != null || film != null || series != null
}

/** A film that's in more than one place (Plex Films and Films 4K, or IPTV too): which to play. */
class PlexCopyChoice(
    val title: String,
    val copies: List<com.titanejstudios.tuner.integrations.Plex.Entry>,
    val fromStart: Boolean,
    /** The IPTV copy, when the choice came from Films & TV. */
    val iptv: VodItem?,
)

/** A film on live TV you joined part way, that you also have on Plex or in your provider's films. */
class StartOverOffer(
    val channelId: String,
    val programme: Programme,
    val title: String,
    val plex: List<com.titanejstudios.tuner.integrations.Plex.Entry>,
    val film: VodItem?,
)

/** A place recordings can be saved (internal storage or a USB drive). */
class StorageOption(val path: String, val label: String, val freeBytes: Long)

class TunerViewModel(internal val app: Application) : AndroidViewModel(app), RemoteHandler {
    internal val store = Store(app)
    internal val db = Database.get(app)
    private val repo = Repository(app, store)
    val playback = PlaybackController(app)
    val multi = MultiViewController(app)

    // ---------------- Navigation ----------------

    private val stack = mutableStateListOf(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** One key per place in the back stack ("2/Catchup"): each screen's saved state and last highlighted item. */
    val screenKey: String get() = "${stack.size - 1}/${stack.last().name}"
    val stackKeys: List<String> get() = stack.mapIndexed { i, s -> "$i/${s.name}" }
    /** Where the highlight was on each screen, so Back puts you back there. */
    val places = com.titanejstudios.tuner.ui.PlaceMemory { screenKey }

    /** What the screen under the player should highlight when you come back (the episode now playing). */
    private fun rememberUnderPlayer(key: String) {
        val i = stack.indexOfLast { it != Screen.Player }
        if (i >= 0) places.set("$i/${stack[i].name}", key)
    }

    fun navigate(to: Screen) {
        // Kids get their own small Settings page; everything else needs a grown-up.
        if (to == Screen.Settings) grownUpSettings = false
        if (activeProfile.kids && !grownUpSettings && to in grownUpScreens) {
            unlockGrownUpSettings { if (stack.last() != to) stack.add(to); afterNavigation() }
            return
        }
        if (stack.last() != to) stack.add(to)
        afterNavigation()
    }

    /** A grown-up has unlocked the full Settings on a kids' profile (until they leave Settings). */
    var grownUpSettings by mutableStateOf(false); private set

    /** Screens only reachable from the full Settings. */
    private val grownUpScreens = setOf(
        Screen.SourceEdit, Screen.ProfileEdit, Screen.PhoneSetup, Screen.EpgMap, Screen.Log, Screen.Stats, Screen.ShareTv,
        Screen.Update, Screen.SelfTest, Screen.HomeRows, Screen.Languages, Screen.Recommendations, Screen.Vpn, Screen.Link,
        Screen.Cloud, Screen.Recordings, Screen.Apps, Screen.Manage,
    )

    /** "Grown-up settings" on a kids' profile: the parental PIN, or (with no PIN set) a nudge to set one. */
    fun unlockGrownUpSettings(then: () -> Unit = {}) {
        if (!activeProfile.kids) {
            then()
            return
        }
        if (pin == null) {
            showNotice("Grown-ups: switch to your own profile and set a PIN in Settings › Parental controls")
            return
        }
        requestPin {
            grownUpSettings = true
            then()
        }
    }

    fun back() {
        if (stack.size <= 1) return
        val left = stack.removeAt(stack.size - 1)
        if (Screen.Settings !in stack) grownUpSettings = false
        places.returnedTo(screenKey)
        when (left) {
            // Phones: leaving the player keeps it going in the mini player along the bottom.
            Screen.Player -> when (mode) {
                is PlayMode.Vod -> {
                    saveResume(syncHome = true)
                    if (!isPhone) stopPlayback()
                }
                is PlayMode.CatchupPlay, is PlayMode.RecordingPlay -> if (!isPhone) stopPlayback()
                else -> Unit
            }
            Screen.MultiView -> {
                // Leaving multi-view: carry on full screen with the channel you were listening to.
                val ch = multi.slots.getOrNull(multi.audioSlot)?.channel
                multi.release()
                if (ch != null) {
                    startLive(ch)
                    if (stack.last() != Screen.Player) stack.add(Screen.Player)
                }
            }
            Screen.PhoneSetup -> stopPhoneSetup()
            Screen.ShareTv -> stopShare()
            else -> Unit
        }
        afterNavigation()
    }

    internal fun resetTo(to: Screen) {
        if (screen == Screen.PhoneSetup) stopPhoneSetup()
        if (screen == Screen.ShareTv) stopShare()
        stack.clear()
        stack.add(to)
        afterNavigation()
    }

    /** Live TV keeps playing in the preview on Channels/Guide; anything else stops it. */
    private fun afterNavigation() {
        places.keepOnly(stackKeys)
        // The guide's corner preview: kept if you went full screen, stopped if you left the guide another way.
        if (guidePreviewing && screen != Screen.Guide) {
            guidePreviewing = false
            guidePreviewJob?.cancel()
            if (screen != Screen.Player && mode is PlayMode.Live) stopPlayback()
        }
        // (Phones keep playing in the mini player – except where something else plays.)
        val phoneMini = isPhone && screen !in setOf(Screen.MultiView, Screen.YouTubePlayer, Screen.Downloads)
        if (mode != null && !phoneMini && screen !in setOf(Screen.Player, Screen.Channels, Screen.Guide, Screen.Home, Screen.WhatsOn)) {
            if (mode is PlayMode.Vod) saveResume(syncHome = true)
            stopPlayback()
        }
    }

    /** Back to the Home screen from anywhere (runs the normal "leaving" steps on the way). */
    fun goHome() {
        var guard = 0
        while (stack.size > 1 && stack.last() != Screen.Home && guard++ < 20) back()
        if (stack.last() != Screen.Home) resetTo(Screen.Home)
    }

    fun openGuide() {
        if (stack.contains(Screen.Guide)) {
            while (stack.last() != Screen.Guide) stack.removeAt(stack.size - 1)
            places.returnedTo(screenKey)
            afterNavigation()
        } else navigate(Screen.Guide)
    }

    // ---------------- Playlists & channels ----------------

    var sources by mutableStateOf<List<Source>>(emptyList()); internal set
    var activeSourceId by mutableStateOf(ALL_SOURCES); internal set
    var editingSource by mutableStateOf<Source?>(null); private set

    /** Every channel in the active playlist(s), including hidden ones. */
    var allChannels by mutableStateOf<List<Channel>>(emptyList()); internal set
    /** Channels that aren't hidden. */
    var channels by mutableStateOf<List<Channel>>(emptyList()); internal set
    /** Every group in the user's order, including hidden ones (for the Manage screen). */
    var allGroups by mutableStateOf<List<String>>(emptyList()); private set
    var groupNames by mutableStateOf(listOf(FAVOURITES, RECENT, ALL)); private set
    var epg by mutableStateOf<Map<String, List<Programme>>>(emptyMap()); private set
    private var selectedGroupState by mutableStateOf(ALL)
    /** The TV Guide category being shown – remembered so Tuner reopens where you were. */
    var selectedGroup: String
        get() = selectedGroupState
        set(value) {
            if (selectedGroupState == value) return
            selectedGroupState = value
            store.setLastGroup(activeProfile.id, value)
        }

    var favourites by mutableStateOf<List<String>>(emptyList()); internal set
    var favouriteSet by mutableStateOf<Set<String>>(emptySet()); internal set
    var recents by mutableStateOf<List<String>>(emptyList()); private set
    var hiddenGroups by mutableStateOf<Set<String>>(emptySet()); internal set
    var hiddenChannels by mutableStateOf<Set<String>>(emptySet()); internal set
    var lockedGroups by mutableStateOf<Set<String>>(emptySet()); private set
    private var groupOrder: List<String> = emptyList()

    // Pre-sorted lists so switching category is a lookup, not a filter over every channel.
    internal var allowedAll: List<Channel> = emptyList()
    private var byGroup: Map<String, List<Channel>> = emptyMap()
    internal var byId: Map<String, Channel> = emptyMap()
    /** Same-named channels, for backup links when a stream fails. Built off the main thread. */
    private var altIndex: Map<String, List<Channel>> = emptyMap()

    /** Home rows worked out in the background (they look at every channel's guide). */
    var sportNow by mutableStateOf<List<Channel>>(emptyList()); private set
    var topPicks by mutableStateOf<List<HomeFeed.Pick>>(emptyList()); private set
    private var homeFeedJob: Job? = null

    var status by mutableStateOf<String?>(null); internal set
    /** False until the saved channels have been read for the first time after start-up. */
    var initialLoadDone by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var now by mutableLongStateOf(System.currentTimeMillis()); internal set

    internal var refreshJob: Job? = null
    private var noticeJob: Job? = null

    // ---------------- Profiles ----------------

    var profiles by mutableStateOf<List<Profile>>(emptyList()); internal set
    var activeProfile by mutableStateOf(Profile(Store.MAIN_PROFILE, "Everyone")); internal set
    var editingProfile by mutableStateOf<Profile?>(null); private set
    var timesUp by mutableStateOf(false); private set
    /** The gentle "bedtime in 10 minutes" card on screen (kids' profiles with a bedtime). */
    var bedtimeSoon by mutableStateOf<String?>(null)

    // ---------------- Playback state ----------------

    var mode by mutableStateOf<PlayMode?>(null); internal set
    var previousChannel by mutableStateOf<Channel?>(null); private set
    /** The channel to put focus back on when returning to a list. */
    var focusChannelId by mutableStateOf<String?>(null); private set
    var inPip by mutableStateOf(false)
    private var zapList: List<Channel> = emptyList()
    private var episodeQueue: List<Episode> = emptyList()
    private var lastAnnouncedProgramme: Long = 0L

    val playing: Channel?
        get() = when (val m = mode) {
            is PlayMode.Live -> m.channel
            is PlayMode.CatchupPlay -> m.channel
            else -> null
        }

    // ---------------- PIN, reminders, search, VOD, recordings, settings ----------------

    var pin by mutableStateOf<String?>(null); private set
    var unlocked by mutableStateOf(false); private set
    var pinPrompt by mutableStateOf<PinPrompt?>(null); private set

    var reminders by mutableStateOf<List<Reminder>>(emptyList()); private set
    var dueReminder by mutableStateOf<Reminder?>(null); private set

    var searchQuery by mutableStateOf(""); internal set
    var searchResults by mutableStateOf(SearchResults(emptyList(), emptyList())); internal set
    /** Plex films and shows matching the search. */
    var searchPlex by mutableStateOf<List<Plex.Entry>>(emptyList()); internal set
    /** YouTube videos for the main Search (arrive a moment after the rest). */
    var searchYouTube by mutableStateOf<List<com.titanejstudios.tuner.integrations.YouTube.Video>>(emptyList()); internal set
    internal var searchYouTubeJob: Job? = null
    internal var searchPlexJob: Job? = null
    private val startOverOffered = HashSet<String>()
    private var liveSinceId: String? = null
    private var liveSince = 0L
    var plexCopyChoice by mutableStateOf<PlexCopyChoice?>(null); private set
    var preferPlexFilms by mutableStateOf(store.preferPlexFilms); private set
    internal var searchJob: Job? = null
    var homeRowOrder by mutableStateOf(store.homeRowOrder); private set
    var homeRowsHidden by mutableStateOf(store.homeRowsHidden); private set
    var homeSmart by mutableStateOf(store.homeSmart); private set
    var voiceSearchPending by mutableStateOf(false)
    var tintFromPicture by mutableStateOf(store.tintFromPicture); internal set
    /** "tv" or "phone" (asked when Tuner first opens on something that isn't a TV). */
    var deviceMode by mutableStateOf(
        store.deviceMode.ifEmpty {
            val ui = app.getSystemService(android.app.UiModeManager::class.java)
            if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "tv".also { store.deviceMode = it } else ""
        }
    ); private set
    val isPhone: Boolean get() = deviceMode == "phone"
    /** Still to ask "TV or phone?". */
    val askDevice: Boolean get() = deviceMode.isEmpty()

    fun chooseDevice(mode: String) {
        deviceMode = mode
        store.deviceMode = mode
    }

    /** Tuner's own VPN (WireGuard). */
    val vpn = com.titanejstudios.tuner.features.VpnFeature(app, store, viewModelScope)
    /** Films and episodes saved on a phone for watching offline. */
    val downloader = com.titanejstudios.tuner.features.DownloadsFeature(app, store)
    val downloads: List<com.titanejstudios.tuner.features.DownloadEntry> get() = downloader.items
    /** Linked TVs and phones: send to TV, carry on where you left off. */
    val link = com.titanejstudios.tuner.features.LinkFeature(
        app, store, viewModelScope,
        phoneMode = { isPhone },
        onPlay = { from, json -> handleLinkPlay(from, json) },
        onSynced = { changed -> applyLinkChanges(changed) },
        remote = this,
    )
    /** YouTube: search, your subscriptions, and the player (trailers too). */
    val youtube = com.titanejstudios.tuner.features.YouTubeFeature(store, viewModelScope, store.activeProfileId)
    /** Grown-ups always; kids if their profile allows it. */
    val youTubeAllowed: Boolean get() = !activeProfile.kids || activeProfile.youtube
    /** A kids' profile that only sees videos from channels a grown-up picked. */
    val youTubeApprovedOnly: Boolean get() = activeProfile.kids && activeProfile.youtube && activeProfile.ytApprovedOnly

    /** Updates this profile's YouTube rows: approved channels for kids that have them, else subscriptions. */
    fun refreshYouTube(force: Boolean = false) {
        if (!youTubeAllowed) return
        if (youTubeApprovedOnly) youtube.loadApproved(activeProfile.ytChannelIds, force) else {
            youtube.loadFeed(force)
            youtube.loadRecommended(force)
            youtube.loadMine(force)
        }
    }

    /** The YouTube player closed: remember how far this profile got (and learn what it likes). */
    /** Every half minute while a YouTube video plays: keep its place (in case Flicker's closed mid-video). */
    fun youTubeProgress(v: com.titanejstudios.tuner.integrations.YouTube.Video, position: Float, length: Float) {
        youtube.watched(v, position, length, closed = false)
    }

    fun youTubeStopped(v: com.titanejstudios.tuner.integrations.YouTube.Video, position: Float, length: Float) {
        youtube.watched(v, position, length)
        // Carry on from here on the other TVs and phones.
        if (position >= 15f && v.channel.isNotBlank()) {
            link.tellOthers()
            cloud.syncSoon()
        }
    }

    /** The video id in a YouTube link ("youtu.be/ID", "watch?v=ID", "/shorts/ID", "/live/ID"). */
    internal fun youTubeIdIn(text: String): String? =
        Regex("(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)

    /** A shared YouTube link: on a phone linked to a TV it plays on the TV, otherwise here. */
    internal fun playSharedYouTube(id: String) {
        viewModelScope.launch {
            val v = youtube.lookup(id)
            if (isPhone && link.linked) {
                showNotice("Sending \"${v.title}\" to the TV…")
                sendYouTubeToTv(v)
            } else {
                if (screen == Screen.YouTubePlayer) back()
                playYouTube(v)
            }
        }
    }

    // ---------------- Phones ----------------

    /** The mini player along the bottom of a phone: what's playing. */
    val miniTitle: String?
        get() = when (val m = mode) {
            is PlayMode.Live -> m.channel.name + (nowNext(m.channel.id, now).first?.let { " · " + it.title } ?: "")
            is PlayMode.CatchupPlay -> m.programme.title
            is PlayMode.Vod -> listOfNotNull(m.title, m.subtitle).joinToString(" · ")
            is PlayMode.RecordingPlay -> m.recording.title
            null -> null
        }

    /** Films & TV on a phone: hide adult titles (always, for kids). */
    var hideAdult by mutableStateOf(store.hideAdult); private set

    fun toggleHideAdult() {
        if (activeProfile.kids) return
        hideAdult = !hideAdult
        store.hideAdult = hideAdult
    }

    // ---------------- Proper ratings for films and series (IMDb, else TMDB) ----------------

    /** A looked-up rating: [value] out of 10, from "IMDb" or "TMDB". */
    class VodRating(val value: Double, val source: String, val certificate: String? = null)

    /** "f:<name>" / "s:<name>" → its rating (or null: looked up, none found). */
    val vodRatings = androidx.compose.runtime.mutableStateMapOf<String, VodRating?>()
    private val ratingQueue = java.util.concurrent.LinkedBlockingDeque<Pair<String, Boolean>>()
    private val ratingAsked = HashSet<String>()
    private var ratingJob: Job? = null
    private var ratingsSaveJob: Job? = null
    private val ratingsLoaded = run {
        runCatching {
            val o = JSONObject(store.vodRatings ?: "{}")
            val monthAgo = System.currentTimeMillis() - 30L * 86_400_000L
            for (k in o.keys()) {
                val a = o.optJSONArray(k) ?: continue
                if (a.optLong(2) < monthAgo) continue
                val cert = a.optString(3).takeIf { it.isNotBlank() }
                vodRatings[k] = if (a.optDouble(0, 0.0) > 0.0 || cert != null) VodRating(a.optDouble(0, 0.0), a.optString(1), cert) else null
                ratingAsked += k
            }
        }
        true
    }

    private fun ratingKey(name: String, isSeries: Boolean) = (if (isSeries) "s:" else "f:") + name.trim()

    /** The rating to show for a film or series ("IMDb 7.3"), asking for it in the background the first time. */
    fun ratingLabelFor(name: String, isSeries: Boolean, providerRating: String?): String? {
        val key = ratingKey(name, isSeries)
        vodRatings[key]?.let { r ->
            val score = if (r.value > 0.0) (if (r.source == "IMDb") "IMDb " else "TMDB ") + String.format(Locale.UK, "%.1f", r.value) else null
            val label = listOfNotNull(score, r.certificate).joinToString("  ·  ")
            if (label.isNotEmpty()) return label
        }
        // Without a TMDB key, the provider's own number is all there is.
        return if (tmdbKey == null) com.titanejstudios.tuner.ui.ratingLabel(providerRating) else null
    }

    /** The best known rating out of 10 (for sorting and "Top rated"). */
    fun ratingValueFor(name: String, isSeries: Boolean, providerRating: String?): Double =
        vodRatings[ratingKey(name, isSeries)]?.value
            ?: if (tmdbKey == null) providerRating?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 else 0.0

    /** Look these titles' ratings up (newest asks first – what's on screen now). */
    fun requestRatings(names: List<String>, isSeries: Boolean) {
        val key = tmdbKey ?: return
        for (n in names.asReversed()) {
            val k = ratingKey(n, isSeries)
            if (ratingAsked.add(k)) ratingQueue.addFirst(n to isSeries)
        }
        if (ratingJob?.isActive == true) return
        val omdb = BuildConfig.OMDB_KEY.takeIf { it.isNotBlank() }
        ratingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val (name, series) = ratingQueue.pollFirst() ?: break
                val info = runCatching { Tmdb.lookup(key, name, series) }.getOrNull()
                val imdb = if (omdb != null && info?.imdbId != null) runCatching { Omdb.imdbRating(omdb, info.imdbId) }.getOrNull() else null
                val cert = info?.certificate
                val r = when {
                    imdb != null && imdb > 0.0 -> VodRating(imdb, "IMDb", cert)
                    // TMDB's own score, once enough people have rated it.
                    info?.rating != null && info.votes >= 20 -> VodRating(info.rating, "TMDB", cert)
                    cert != null -> VodRating(0.0, "", cert)
                    else -> null
                }
                withContext(Dispatchers.Main) {
                    vodRatings[ratingKey(name, series)] = r
                    saveRatingsSoon()
                }
            }
        }
    }

    private fun saveRatingsSoon() {
        if (ratingsSaveJob?.isActive == true) return
        ratingsSaveJob = viewModelScope.launch {
            delay(5_000)
            val o = JSONObject()
            val t = System.currentTimeMillis()
            for ((k, v) in vodRatings.toMap()) o.put(k, org.json.JSONArray().put(v?.value ?: 0.0).put(v?.source ?: "").put(t).put(v?.certificate ?: ""))
            store.vodRatings = o.toString()
        }
    }

    // ---------------- Age ratings for kids ----------------

    private val certOrder = listOf("U", "PG", "12", "15", "18")

    /**
     * Kids' profiles with an age limit: films and series above it need the parental PIN (or a
     * grown-up's profile). Unrated titles are allowed. Checks with TMDB the first time (up to 4 s).
     */
    /** A title a grown-up just allowed with the PIN (so the same film doesn't ask twice). */
    private var ageApproved: Pair<String, Long>? = null

    private fun ageApprovedNow(title: String): Boolean =
        ageApproved?.let { (t, at) -> t == Tmdb.cleanTitle(title).first.lowercase() && System.currentTimeMillis() - at < 5 * 60_000L } == true

    /** Over the limit: the PIN lets it through (or it's blocked). */
    private fun ageBlocked(title: String, cert: String, limit: String, then: () -> Unit) {
        noteBlocked(title, "Rated $cert")
        if (pin == null) showNotice("$title is rated $cert – ${activeProfile.name}'s profile is for $limit and under")
        else {
            showNotice("$title is rated $cert – a grown-up's PIN is needed")
            requestPin {
                ageApproved = Tmdb.cleanTitle(title).first.lowercase() to System.currentTimeMillis()
                then()
            }
        }
    }

    /** As [checkAgeRating], with the rating already known (Plex's). */
    private fun checkAgeRatingKnown(title: String, cert: String, then: () -> Unit) {
        val p = activeProfile
        val limit = p.maxCertificate
        if (!p.kids || limit.isBlank() || ageApprovedNow(title) || certOrder.indexOf(cert) <= certOrder.indexOf(limit)) {
            then()
            return
        }
        ageBlocked(title, cert, limit, then)
    }

    fun checkAgeRating(name: String, isSeries: Boolean, then: () -> Unit) {
        val p = activeProfile
        val limit = p.maxCertificate
        if (!p.kids || limit.isBlank() || ageApprovedNow(name)) {
            then()
            return
        }
        viewModelScope.launch {
            val k = ratingKey(name, isSeries)
            var cert = vodRatings[k]?.certificate
            val key = tmdbKey
            if (cert == null && key != null) {
                cert = withTimeoutOrNull(4_000) { withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, name, isSeries)?.certificate }.getOrNull() } }
            }
            val over = cert != null && certOrder.indexOf(cert) > certOrder.indexOf(limit)
            if (!over) {
                then()
                return@launch
            }
            ageBlocked(com.titanejstudios.tuner.integrations.Tmdb.cleanTitle(name).first, cert!!, limit, then)
        }
    }

    // ---------------- 🛠 Fix it ----------------

    /** Working through the fix-it checks. */
    var fixing by mutableStateOf(false); private set

    /**
     * One press when something's wrong: checks the internet and the VPN, restarts what's playing
     * from scratch (forgetting it was "flaky"), clears stuck pictures, and reloads an old guide.
     * Says what it did.
     */
    fun fixIt() {
        if (fixing) return
        fixing = true
        showNotice("🛠 Checking…")
        viewModelScope.launch {
            val did = ArrayList<String>()
            try {
                AppLog.info("Fix it pressed")
                // 1. The internet.
                val online = withContext(Dispatchers.IO) {
                    runCatching { java.net.InetAddress.getAllByName("www.google.com").isNotEmpty() }.getOrDefault(false)
                }
                if (!online) {
                    showNotice("🛠 This device isn't online – check its Wi-Fi or network cable, then try again")
                    return@launch
                }
                // 2. The VPN (with its kill switch, nothing plays while it's down).
                if (vpn.enabled && !vpn.connected) {
                    vpn.connect()
                    did += "reconnected the VPN"
                    delay(3_000)
                }
                // 3. Your provider's server.
                val host = when (val src = activeSources().firstOrNull()) {
                    is Source.Xtream -> src.server
                    is Source.M3u -> src.playlistUrl
                    null -> null
                }?.let { u -> runCatching { android.net.Uri.parse(if ("://" in u) u else "http://$u").host }.getOrNull() }
                if (host != null) {
                    val reachable = withContext(Dispatchers.IO) { runCatching { java.net.InetAddress.getAllByName(host).isNotEmpty() }.getOrDefault(false) }
                    if (!reachable) did += "couldn't reach your provider ($host) – it may be down"
                }
                // 4. What's playing: from scratch.
                when (val m = mode) {
                    is PlayMode.Live -> {
                        cushion.forget(m.channel.id)
                        qualityBack = null
                        startLive(m.channel, force = true)
                        did += "restarted ${m.channel.name}"
                    }
                    is PlayMode.Vod -> {
                        saveResume(syncHome = false)
                        playback.resume()
                        did += "picked up ${m.title} again"
                    }
                    else -> Unit
                }
                // 5. Stuck pictures.
                withContext(Dispatchers.Main) { runCatching { coil.Coil.imageLoader(app).memoryCache?.clear() } }
                did += "cleared stuck pictures"
                // 6. An old guide.
                if (System.currentTimeMillis() - epgLoadedAt > 6 * 3_600_000L) {
                    loadGuide()
                    did += "reloaded the TV guide"
                }
                // 7. Sync and the TV link.
                if (cloud.signedIn) cloud.syncSoon(0L)
                link.reload()
                showNotice("🛠 Done – " + did.joinToString(", "))
                AppLog.info("Fix it: " + did.joinToString(", "))
            } catch (e: Exception) {
                AppLog.warn("Fix it failed", e)
                showNotice("🛠 Something went wrong while checking – try again")
            } finally {
                fixing = false
            }
        }
    }

    /** Pull down to refresh (phones): playlists and guide on Home and the guide, the list on Films & TV. */
    var pullRefreshing by mutableStateOf(false); private set

    fun pullRefresh() {
        if (pullRefreshing) return
        pullRefreshing = true
        when (screen) {
            Screen.Movies -> loadMovies(force = true)
            Screen.Series -> loadSeries(force = true)
            else -> {
                refresh()
                refreshYouTube(force = true)
            }
        }
        viewModelScope.launch {
            delay(1_200)
            var waited = 0
            while ((status != null || vodUpdating) && waited < 90) {
                delay(1_000)
                waited++
            }
            pullRefreshing = false
        }
    }

    /** Phone mini player's 📺: carry on with this on the TV. */
    fun sendPlayingToTv() {
        val what = sendable() ?: run {
            showNotice("This can't be sent to the TV")
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        watchOnTv(what)
    }

    /** The hold-OK menu on a YouTube video. */
    var ytMenu by mutableStateOf<com.titanejstudios.tuner.integrations.YouTube.Video?>(null); private set
    var ytMenuRecommended by mutableStateOf(false); private set

    fun openYouTubeMenu(v: com.titanejstudios.tuner.integrations.YouTube.Video, recommended: Boolean) {
        ytMenuRecommended = recommended
        ytMenu = v
    }

    fun closeYouTubeMenu() {
        ytMenu = null
    }

    /** From the phone: play this YouTube video on the TV. */
    fun sendYouTubeToTv(v: com.titanejstudios.tuner.integrations.YouTube.Video) {
        watchOnTv(
            JSONObject().put("kind", "youtube").put("id", v.id).put("title", v.title).put("channel", v.channel)
                .put("thumb", v.thumb).put("cid", v.channelId).put("pos", youtube.resumeAt(v.id).toDouble())
        )
    }

    fun toggleYouTubeLater(v: com.titanejstudios.tuner.integrations.YouTube.Video) {
        val adding = !youtube.isLater(v.id)
        youtube.toggleLater(v)
        youtube.refreshLaterPage()
        showNotice(if (adding) "Added to Watch later" else "Removed from Watch later")
        link.tellOthers()
        cloud.syncSoon()
    }

    /** A channel's page (newest videos, or most popular). Kids on "only channels I pick" stay on theirs. */
    fun openYouTubeChannel(v: com.titanejstudios.tuner.integrations.YouTube.Video) {
        val cid = v.channelId
        if (youTubeApprovedOnly || cid == null) {
            if (cid == null && !youTubeApprovedOnly) moreFromYouTubeChannel(v)
            return
        }
        youtube.openChannel(cid, v.channel, activeProfile.kids)
        if (screen == Screen.YouTubePlayer) back()
        navigate(Screen.YouTubeList)
    }

    fun openYouTubePlaylist(p: com.titanejstudios.tuner.integrations.YouTube.Playlist) {
        youtube.openPlaylist(p)
        navigate(Screen.YouTubeList)
    }

    fun openYouTubeLater() {
        youtube.openLater()
        navigate(Screen.YouTubeList)
    }

    /** The real YouTube Kids app, if it's on this device (it keeps its own kids' profiles and parent settings). */
    val youTubeKidsPackage: String?
        get() {
            val pm = app.packageManager
            return listOf("com.google.android.youtube.tvkids", "com.google.android.apps.youtube.kids")
                .firstOrNull { pm.getLeanbackLaunchIntentForPackage(it) != null || pm.getLaunchIntentForPackage(it) != null }
        }

    fun openYouTubeKids() {
        val pkg = youTubeKidsPackage ?: run {
            showNotice("YouTube Kids isn't installed on this device")
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        if (!InstalledApps.launch(app, pkg)) showNotice("Couldn't open YouTube Kids")
    }
    /** A YouTube video is actually playing (kids' TV time counts it). */
    private var ytPlaying = false
    fun youTubePlaying(on: Boolean) {
        ytPlaying = on
    }
    /** The YouTube video on screen. */
    var ytVideo by mutableStateOf<com.titanejstudios.tuner.integrations.YouTube.Video?>(null); internal set
    /** Sync between houses through your own Google Drive. */
    val cloud = com.titanejstudios.tuner.features.CloudSyncFeature(
        store, viewModelScope,
        deviceId = { store.linkDeviceId },
        deviceName = { link.name },
        isPhone = { isPhone },
        isForeground = { inForeground },
        onSynced = { changed -> applyLinkChanges(changed) },
        // Backup guide (remove with the module).
        backupGuideExport = { com.titanejstudios.backupguide.BackupGuide.exportSettings(app) },
        backupGuideImport = { o -> o.length() > 0 && com.titanejstudios.backupguide.BackupGuide.importSettings(app, o) },
    )

    /** YouTube sign-ins, history and Watch later go to the other devices as they change. */
    private val youTubeSyncHook = run {
        youtube.onSyncWorthy = {
            link.tellOthers()
            cloud.syncSoon()
        }
    }
    /** What this profile likes, learned from what it watches. */
    internal var taste: Taste = Taste.fromJson(store.taste(store.activeProfileId))
    internal val pendingTaste = HashMap<Pair<String, String?>, Long>()
    var learnTaste by mutableStateOf(store.learnTaste); internal set
    /** "Your shows tonight" on Home. */
    var yourShows by mutableStateOf<List<HomeFeed.Pick>>(emptyList()); private set
    /** Films like the last one you watched from your provider, and its name. */
    var becauseFilms by mutableStateOf<List<VodItem>>(emptyList()); private set
    var becauseFilmOf by mutableStateOf<String?>(null); private set
    /** This week's trending films and TV, with where you can watch each (Plex, your provider) if you have it. */
    var trending by mutableStateOf<List<TrendingItem>>(emptyList()); private set
    private var trendingAt = 0L
    /** Bumped when tastes change, so Settings › Recommendations redraws. */
    var tasteVersion by mutableStateOf(0); private set
    /** Countries/languages hidden (Settings › Languages & countries). */
    var hiddenLanguages by mutableStateOf(store.hiddenLanguages); private set
    var artSaverMinutes by mutableStateOf(store.artSaverMinutes); internal set
    /** Tuner's artwork screensaver is showing. */
    var saverOn by mutableStateOf(false); internal set
    /** When CH+/CH− was last pressed (the player shows the channel strip). */
    var zappedAt by mutableStateOf(0L); private set

    var movies by mutableStateOf<List<VodItem>?>(null); internal set
    var series by mutableStateOf<List<SeriesItem>?>(null); internal set
    var vodStatus by mutableStateOf<String?>(null); private set
    var openSeries by mutableStateOf<SeriesItem?>(null); private set
    var episodes by mutableStateOf<List<Episode>?>(null); private set
    var resume by mutableStateOf<Map<String, ResumeEntry>>(emptyMap()); internal set

    var recordings by mutableStateOf<List<Recording>>(emptyList()); internal set
    var webhookUrl by mutableStateOf<String?>(null); internal set
    var openLastChannel by mutableStateOf(false); private set
    /** "home", "guide" or "channel". */
    var startScreen by mutableStateOf("home"); private set
    var refreshOnStart by mutableStateOf(true); private set
    var seriesLinks by mutableStateOf<List<SeriesLink>>(emptyList()); private set

    // Other apps on the TV (Plex, BBC iPlayer, Netflix…)
    var tvApps by mutableStateOf<List<TvApp>?>(null); private set
    var hiddenApps by mutableStateOf<Set<String>>(emptySet()); private set
    private var appsJob: Job? = null

    var logText by mutableStateOf(""); private set

    // Sleep timer: when to stop (0 = off); endOfProgramme follows the programme if it overruns the guide.
    var sleepAt by mutableLongStateOf(0L); private set
    private var sleepJob: Job? = null

    // Look & sound
    /** This profile's colour theme (TunerColors.themes) and highlight colour ("" = the theme's own). */
    var themeName by mutableStateOf("evening"); private set
    var themeAccent by mutableStateOf(""); private set
    /** Settings › Look › Colour theme is open. */
    var themePickerOpen by mutableStateOf(false)

    /** Lite mode: "auto", "on" or "off" (data/Lite.kt). */
    var liteChoice by mutableStateOf(com.titanejstudios.tuner.data.Lite.choice); private set
    val liteOn: Boolean get() = com.titanejstudios.tuner.data.Lite.decide(liteChoice, com.titanejstudios.tuner.data.Lite.lowMemoryDevice)

    fun cycleLite() {
        val m = com.titanejstudios.tuner.data.Lite.next(liteChoice)
        com.titanejstudios.tuner.data.Lite.set(app, m)
        liteChoice = m
        AppLog.info("Lite mode changed: " + com.titanejstudios.tuner.data.Lite.summary(m))
        showNotice(if (liteOn) "🪶 Lite mode on – fully in place next time Flicker starts" else "Lite mode off – fully in place next time Flicker starts")
    }

    /** How film and series pages are sorted (remembered). */
    var vodSort by mutableStateOf(store.vodSort); private set

    fun nextVodSort() {
        val keys = com.titanejstudios.tuner.ui.VOD_SORTS.map { it.first }
        vodSort = keys[(keys.indexOf(vodSort) + 1).mod(keys.size)]
        store.vodSort = vodSort
    }

    /** The TV guide plays the highlighted channel in its corner (off in lite mode). */
    var guidePreview by mutableStateOf(store.guidePreview); private set

    fun toggleGuidePreview() {
        guidePreview = !guidePreview
        store.guidePreview = guidePreview
        if (!guidePreview) {
            guidePreviewJob?.cancel()
            if (screen == Screen.Guide && mode is PlayMode.Live) stopPlayback()
        }
    }
    private var guidePreviewJob: Job? = null
    /** The live picture in the guide's corner was started by the guide (not something already playing). */
    private var guidePreviewing = false

    /** The highlighted programme in the guide: after a moment on it, its channel plays in the corner (if it's on now). */
    fun guideFocused(c: Channel, p: Programme?) {
        guidePreviewJob?.cancel()
        if (!guidePreview || liteOn || isPhone || screen != Screen.Guide) return
        val t = System.currentTimeMillis()
        if (p != null && (p.start > t || p.stop <= t)) return
        if (!isAllowed(c) || timeLeftSeconds() <= 0 || isBedtimeNow()) return
        val current = mode
        if (current is PlayMode.Live && current.channel.id == c.id) return
        if (current != null && current !is PlayMode.Live) return
        val wasPlaying = current != null
        guidePreviewJob = viewModelScope.launch {
            delay(1_500)
            if (screen != Screen.Guide) return@launch
            startLive(c, preview = true)
            // Only a preview the guide started is stopped when you leave it (not what you were already watching).
            if (!wasPlaying) guidePreviewing = true
        }
    }

    /** OK on a programme that's on now in the guide: full screen (straight away if it's already in the corner). */
    fun watchFromGuide(c: Channel, list: List<Channel>) {
        guidePreviewJob?.cancel()
        guidePreviewing = false
        val current = mode
        if (current is PlayMode.Live && current.channel.id == c.id && isAllowed(c)) {
            zapList = list.ifEmpty { listOf(c) }
            addRecent(c.id)
            store.setLastChannel(activeProfile.id, c.id)
            navigate(Screen.Player)
        } else play(c, list)
    }
    var subtitleScale by mutableStateOf(1f); private set
    var subtitleBox by mutableStateOf(false); private set
    var nightMode by mutableStateOf(false); private set
    var clearSpeech by mutableStateOf(store.clearSpeech); private set
    /** Text size across Flicker (%). */
    var textScale by mutableStateOf(store.textScale); private set
    var frameRateMatch by mutableStateOf(false); private set

    // What's on
    /** Days from today (0–6). */
    var whatsOnDay by mutableStateOf(0); private set
    /** Minutes after midnight, or null for "now" (today only). */
    var whatsOnMinute by mutableStateOf<Int?>(null); private set
    var whatsOnGenre by mutableStateOf<String?>(null)
    var whatsOnFavouritesOnly by mutableStateOf(false)
    /** Hide shopping, adult, radio and +1 channels. */
    var whatsOnTidy by mutableStateOf(store.whatsOnTidy); private set
    /** A small live picture of the highlighted channel. */
    var whatsOnPreview by mutableStateOf(store.whatsOnPreview); private set
    var whatsOnResult by mutableStateOf<WhatsOn.Result?>(null); private set
    private var whatsOnJob: Job? = null
    /** Film details from TMDB for What's On (title → info, or null when not found). */
    val filmLookups = androidx.compose.runtime.mutableStateMapOf<String, Tmdb.Info?>()
    private val filmLookupsAsked = HashSet<String>()
    private var whatsOnPreviewJob: Job? = null

    // Watch stats (seconds not yet saved, flushed every minute)
    var statsSummary by mutableStateOf<WatchStats.Summary?>(null); internal set
    var statsProfile by mutableStateOf<Profile?>(null); internal set

    // Per-channel picture size and lip sync; preferred languages
    var pictureModes by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var lipSyncMap by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var audioLanguage by mutableStateOf<String?>(null); private set
    var subtitleLanguage by mutableStateOf<String?>(null); private set
    var audioDescription by mutableStateOf(false); private set
    /** "Previously on…": the episode before's name and plot, shown for a few seconds. */
    var previously by mutableStateOf<Pair<String, String>?>(null); private set
    var recapsOn by mutableStateOf(store.recaps); private set
    /** When an episode of each series last started (no recap when you're binge-watching). */
    private val lastEpisodeStart = HashMap<String, Long>()
    // (Kept above init: start-up code can reach these before the rest of the class is set up.)
    var collections by mutableStateOf(Watchlist.decodeCollections(store.collectionsJson)); private set
    var collectingItem by mutableStateOf<WatchItem?>(null)
    var openCollection by mutableStateOf<String?>(null); private set
    var bookmarks by mutableStateOf(Watchlist.decodeBookmarks(store.bookmarksJson)); private set
    var crashNotice by mutableStateOf<String?>(
        SafeMode.lastCrash?.takeIf { SafeMode.lastCrashAt > store.crashShownAt }
    )
    var playingPlexEntry by mutableStateOf<com.titanejstudios.tuner.integrations.Plex.Entry?>(null); private set
    var familyPicks by mutableStateOf(Watchlist.decodePicks(store.familyPicksJson)); private set
    var comingSoon by mutableStateOf(decodeComing(store.comingSoonJson)); private set
    var library by mutableStateOf<List<LibraryTitle>?>(null); internal set
    var libraryShows by mutableStateOf(false); internal set
    var libraryStatus by mutableStateOf<String?>(null); private set
    var timeRequest by mutableStateOf<TimeRequest?>(null); private set

    // Recording tidy-up and network drive
    var keptRecordings by mutableStateOf<Set<String>>(emptySet()); internal set
    var autoDeleteDays by mutableStateOf(0); internal set
    var nasUrl by mutableStateOf<String?>(null); internal set
    var nasKeepLocal by mutableStateOf(true); internal set
    var nasStatus by mutableStateOf<String?>(null); internal set

    // Plex, football scores and app updates live in their own classes (features/); the
    // properties below just forward to them so the screens didn't need to change.
    val plexFeature = PlexFeature(store, viewModelScope).also { f ->
        // Each profile can watch as its own Plex Home user.
        store.plexUserOf(store.activeProfileId)?.let { f.useUser(it.third) }
    }

    /** The Plex Home user a profile uses ("" = the main account). */
    fun plexUserNameOf(profileId: String?): String = profileId?.let { store.plexUserOf(it)?.second } ?: ""

    /** Plex Home users for the profile editor (loaded when asked). */
    var plexHomeUsers by mutableStateOf<List<Plex.HomeUser>?>(null); private set

    fun loadPlexHomeUsers() {
        if (!plexLinked) return
        viewModelScope.launch {
            val list = plexFeature.homeUsers()
            plexHomeUsers = list
            if (list.isEmpty()) showNotice("No Plex Home users found – set them up at plex.tv › Plex Home")
        }
    }

    /** Signs a profile in as a Plex Home user (or back to the main account with null). */
    fun setProfilePlexUser(profileId: String, user: Plex.HomeUser?, pin: String?, done: (Boolean) -> Unit = {}) {
        if (user == null || user.admin) {
            store.savePlexUser(profileId, null)
            if (activeProfile.id == profileId) plexFeature.useUser(null)
            showNotice("Plex: the main account")
            done(true)
            return
        }
        viewModelScope.launch {
            plexFeature.switchTo(user, pin).onSuccess { token ->
                store.savePlexUser(profileId, Triple(user.uuid, user.title, token))
                if (activeProfile.id == profileId) plexFeature.useUser(token)
                showNotice("Plex: watching as ${user.title} ✓")
                done(true)
            }.onFailure {
                showNotice(if (user.protected) "Couldn't switch to ${user.title} – check the Plex PIN" else "Couldn't switch Plex user: ${it.message}")
                done(false)
            }
        }
    }
    val plexLinked: Boolean get() = plexFeature.linked
    val plexCode: String? get() = plexFeature.code
    val plexStatus: String? get() = plexFeature.status
    val plexItems: List<Plex.Item> get() = plexFeature.items
    val plexRecent: List<Plex.Entry>
        get() = if (activeProfile.kids) plexFeature.recent.filter { plexFeature.isKidsEntry(it) } else plexFeature.recent
    private var plexPlaying: Plex.Item? = null

    val football = FootballFeature(store, viewModelScope) { showNotice(it) }
    /** Every fixture this week (Sports page). */
    val allFixtures = com.titanejstudios.tuner.features.FixturesFeature(viewModelScope)
    /** Your teams' coming matches with their channels (My teams, Home). */
    var teamFixtures by mutableStateOf<List<TeamFixture>>(emptyList()); private set
    /** Your teams' recent games you can still watch on catch-up (newest first). */
    var teamCatchup by mutableStateOf<List<Pair<Channel, Programme>>>(emptyList()); private set
    private var teamFixturesJob: Job? = null
    val footballTeams: Set<String> get() = football.teams

    val updates = UpdateFeature(app, store, viewModelScope)
    val updateRepo: String? get() = updates.repo
    val updateStatus: String? get() = updates.status

    // ---------------- 5.3 ----------------

    /** True when Tuner started in safe mode after crashes (extras switched off). */
    val safeMode: Boolean get() = SafeMode.active
    val lastCrash: String? get() = SafeMode.lastCrash
    var safeModeBannerHidden by mutableStateOf(false)

    // Self-test (Settings › Help › Check everything)
    var selfTestResults by mutableStateOf<List<SelfTest.Result>>(emptyList()); private set
    var selfTestRunning by mutableStateOf(false); private set
    private var selfTestJob: Job? = null

    /** While ▲▼ are pressed quickly: the channel shown on the info bar, tuned when you stop. */
    var zapPreview by mutableStateOf<Channel?>(null); private set
    private var zapJob: Job? = null
    private var lastZapAt = 0L

    /** Bigger start-up buffer for channels that keep dropping out. */
    private val cushion = StreamCushion({ store.flakyStreams }, { store.flakyStreams = it })
    private var cleanPlaySeconds = 0

    /** When the guide in memory was read from the database (re-read every few hours). */
    private var epgLoadedAt = 0L
    private val startedAt = System.currentTimeMillis()

    /** Backup guide (remove with the module): extra guide addresses from the last version move into it. */
    private val backupGuideMigrated = run {
        val old = store.extraGuides
        if (old.isNotEmpty()) {
            com.titanejstudios.backupguide.BackupGuide.setCustomUrls(app, old)
            com.titanejstudios.backupguide.BackupGuide.setEnabled(app, true)
            store.extraGuides = emptyList()
        }
    }

    /** The TV guide again in the background (after changing guide settings). */
    fun updateGuideNow() {
        refresh(activeSources(), channels = false)
        showNotice("📅 Updating the TV guide in the background")
    }
    private var guideTriedAt = 0L
    /** How far ahead the guide in memory goes. */
    private var epgUntil = 0L
    private var epgMoreJob: Job? = null

    /**
     * The guide is being looked at up to [until]: reads the next few hours if they aren't in memory
     * yet (lite mode reads the guide bit by bit instead of a day and a half at once).
     */
    fun guideNeeds(until: Long) {
        if (epgUntil == 0L || until + 3600_000L <= epgUntil || epgMoreJob?.isActive == true) return
        val from = epgUntil
        val to = maxOf(until, from) + com.titanejstudios.tuner.data.Lite.guideHours * 3600_000L
        val ids = activeSources().map { it.id }
        val chs = allChannels
        epgMoreJob = viewModelScope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { shiftGuide(chs, db.loadEpg(ids, from, to, emptyMap())) } }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) AppLog.warn("Couldn't read more of the guide", it) }
                .getOrNull() ?: return@launch
            if (epgUntil != from) return@launch // the whole guide was re-read meanwhile
            val merged = HashMap(epg)
            for ((id, list) in more) {
                val have = merged[id].orEmpty()
                val last = have.lastOrNull()?.start ?: Long.MIN_VALUE
                merged[id] = have + list.filter { it.start > last }
            }
            epg = merged
            epgUntil = to
        }
    }
    /** Background extras (Plex, scores, apps…) start a few seconds after the channels. */
    private var extrasStarted = false

    // My list and new episodes
    var watchlist by mutableStateOf<List<WatchItem>>(emptyList()); private set
    var followedSeries by mutableStateOf<List<FollowedSeries>>(emptyList()); private set
    private val vodCache = VodCache(app.filesDir)
    /** Set while saved film/series lists are being refreshed in the background. */
    var vodUpdating by mutableStateOf(false); private set

    // Kids
    private var lastKidsWarning = ""
    private var bedtimeOverrideUntil = 0L

    // Home Assistant cinema lights
    var cinemaLights by mutableStateOf("off"); private set
    private var lightsDimmed = false

    /** How many streams the provider allows at once (for the recordings planner); null = unknown. */
    var accountStreams by mutableStateOf<Int?>(null); private set
    private var accountStreamsLoaded = false

    // ---------------- 5.4 ----------------

    // "Are you still watching?"
    var stillWatchingHours by mutableStateOf(4); internal set
    var stillWatchingAsk by mutableStateOf(false); internal set
    internal var lastUserActivity = System.currentTimeMillis()
    internal var stillWatchingDeadline = 0L

    // Per-channel volume level and picture quality
    var volumeLevels by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var qualityModes by mutableStateOf<Map<String, String>>(emptyMap()); private set

    // Followed teams' matches
    var sportFollowMode by mutableStateOf("remind"); private set

    // Film info (TMDB) and subtitles (OpenSubtitles)
    var tmdbKey by mutableStateOf<String?>(null); internal set
    var openSubtitlesKey by mutableStateOf<String?>(null); private set
    /** The film/series info card: shown while [filmInfoOpen]; null info while loading or not found. */
    var filmInfoOpen by mutableStateOf(false); private set
    var filmInfo by mutableStateOf<Tmdb.Info?>(null); private set
    /** IMDb's score for the film info card (shown instead of TMDB's when known). */
    var filmInfoImdb by mutableStateOf<Double?>(null); private set
    var filmInfoStatus by mutableStateOf<String?>(null); private set
    var subtitleHits by mutableStateOf<List<OpenSubtitles.Hit>?>(null); private set
    var subtitleStatus by mutableStateOf<String?>(null); private set

    // Skip intro (learned per series)
    var introEnds by mutableStateOf<Map<String, String>>(emptyMap()); private set

    // Keeping TVs in sync, and nightly backups, via the network drive
    var syncEnabled by mutableStateOf(false); private set
    var syncStatus by mutableStateOf<String?>(null); private set
    private var syncJob: Job? = null
    var nasBackupOn by mutableStateOf(true); private set

    // One entry for HD/SD copies of a channel
    var mergeVersions by mutableStateOf(true); private set
    private var preferredVersions: Map<String, String> = emptyMap()
    /** Shown channel id → all its versions (best first), when copies were combined. */
    private var versionsOf: Map<String, List<Channel>> = emptyMap()

    // Category editor
    var groupRenames by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var customGroups by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    /** Channel waiting to be added to one of your categories (shows the picker). */
    var categoryPickFor by mutableStateOf<Channel?>(null)
    private var originalGroups: Set<String> = emptySet()

    // TV's own channel list / Live tab
    var liveTabScope by mutableStateOf("off"); private set
    var liveTabStatus by mutableStateOf<String?>(null); private set
    private var liveSyncJob: Job? = null

    // Copy settings to / from another TV
    /** "send" (this TV shows a code) or "receive" (this TV looks for others). */
    var shareMode by mutableStateOf<String?>(null); internal set
    var shareCode by mutableStateOf<String?>(null); internal set
    var shareFound by mutableStateOf<List<TvShare.Found>>(emptyList()); private set
    var shareStatus by mutableStateOf<String?>(null); private set
    private var tvShare: TvShare? = null
    internal val pendingStats = HashMap<Pair<String, String>, Long>()
    internal val pendingTitles = HashMap<String, Long>()
    /** When something was playing (quarter-hours, last week) – so "Missed it?" knows what aired while you were away. */
    private val watchingSlots: HashSet<Long> by lazy {
        store.watchingSlots.split(',').mapNotNull { it.toLongOrNull() }.toHashSet()
    }
    private var watchingSlotsDirty = false

    internal fun noteWatchingSlot(t: Long) {
        if (watchingSlots.add(t / 900_000L)) watchingSlotsDirty = true
    }

    internal fun saveWatchingSlots() {
        if (!watchingSlotsDirty) return
        watchingSlotsDirty = false
        val weekAgo = (System.currentTimeMillis() - 8 * 86_400_000L) / 900_000L
        watchingSlots.removeAll { it < weekAgo }
        store.watchingSlots = watchingSlots.joinToString(",")
    }

    /** Nothing played in Flicker at any point while [p] was on. */
    private fun wereAway(p: Programme, slots: Set<Long>): Boolean {
        var s = p.start / 900_000L
        val e = (p.stop - 1) / 900_000L
        while (s <= e) {
            if (s in slots) return false
            s++
        }
        return true
    }

    /** A kids' profile tried to watch something it isn't allowed (for the weekly report). */
    fun noteBlocked(what: String, why: String) {
        if (activeProfile.kids) store.addBlocked(activeProfile.id, what, why)
    }

    /** The report's "Tried to watch" list for [profile]: (when, what, why), newest first, this week. */
    fun blockedThisWeek(profile: Profile): List<Triple<Long, String, String>> {
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        return store.blockedLog(profile.id).mapNotNull { l ->
            val parts = l.split('\t')
            val t = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            if (t < weekAgo) null else Triple(t, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        }.asReversed()
    }
    /** Live viewing per channel since the last save, for "your usual channel". */
    internal val pendingHabits = HashMap<String, Long>()
    var offerUsual by mutableStateOf(store.offerUsual); private set
    var scrubPreviewsIptv by mutableStateOf(store.scrubPreviewsIptv); private set
    /** The channel you usually watch at this time, offered on Home. */
    var usualOffer by mutableStateOf<Channel?>(null); private set
    /** A better version of the channel you're on is working again (shown once, as a message on the picture). */
    var qualityBack by mutableStateOf<Channel?>(null); private set
    private var upgradeJob: Job? = null
    /** Channels already told about this session ("only display the message once"). */
    private val upgradeTold = HashSet<String>()
    /** The Christmas TV planner's programmes (null until loaded). */
    var festive by mutableStateOf<List<FestiveItem>?>(null); private set
    /** Ask once per device: "Set up sync between houses?" (shown on Home). */
    var cloudPrompt by mutableStateOf(false); private set
    /** What this profile has started watching (its own Continue watching). */
    var started by mutableStateOf(store.started(store.activeProfileId)); internal set
    /** Missed it? Programmes from the last week you'd probably like, ready on catch-up. */
    var missed by mutableStateOf<List<HomeFeed.Pick>>(emptyList()); private set
    /** Programmes you switched off half-way (newest first). */
    var unfinished by mutableStateOf(loadUnfinished()); private set
    /** The live programme being watched: channel, programme start, seconds watched, last seen. */
    internal var liveWatch: LiveWatch? = null
    var epgOverrides by mutableStateOf<Map<String, String>>(emptyMap()); internal set

    var mappingChannel by mutableStateOf<Channel?>(null); internal set
    var epgCandidates by mutableStateOf<List<XmltvChannel>?>(null); internal set

    var phoneUrl by mutableStateOf<String?>(null); internal set
    var remoteUrl by mutableStateOf<String?>(null); internal set
    internal var phoneServer: PhoneSetupServer? = null
    internal var phoneBase: String? = null
    internal var setupOpen = false
    internal var inForeground = true

    /** Key presses from the phone remote; MainActivity turns them into real key events. */
    val remoteKeys = MutableSharedFlow<Int>(extraBufferCapacity = 32)

    // Channel health, fast switching, timeshift, phone remote settings
    var deadChannels by mutableStateOf<Set<String>>(emptySet()); internal set
    /** Your match-day channels (hold OK on a channel): never hidden when offline. */
    var matchDayChannels by mutableStateOf(store.matchDayChannels); internal set
    /** Offline channels the guide says have something on now or within the hour (a match day). */
    var onAirSoon by mutableStateOf<Set<String>>(emptySet()); internal set
    internal var offlineRecheckedAt = System.currentTimeMillis()
    var hideDead by mutableStateOf(false); internal set
    var healthNightly by mutableStateOf(false); internal set
    var healthScope by mutableStateOf("favourites"); internal set
    var healthCheckedAt by mutableLongStateOf(0L); internal set
    var healthProgress by mutableStateOf<String?>(null); internal set
    internal var healthJob: Job? = null
    @Volatile internal var healthCancel = false
    var fastSwitch by mutableStateOf(false); private set
    var timeshiftEnabled by mutableStateOf(true); internal set
    var remoteEnabled by mutableStateOf(false); internal set
    var audioPassthrough by mutableStateOf(false); internal set
    var streamCompatibility by mutableStateOf(false); internal set
    private var lastZapDirection = 1
    private var standbyJob: Job? = null

    internal var pendingLink: Uri? = null
    internal var startupHandled = false

    init {
        store.forgetFootballScores()
        loadSettingsFromStore()
        playback.onEnded = { onPlaybackEnded() }
        playback.onRecovery = { showNotice(it) }
        playback.onAlternate = { url -> onBackupLink(url) }
        playback.cushionFor = { id -> cushion.cushionMs(id) }
        playback.onStreamTrouble = { id -> cushion.trouble(id) }
        playback.timeshiftDir = {
            if (!timeshiftEnabled) null
            else File(recordingDir).parentFile?.let { File(it, "Timeshift") } ?: File(app.filesDir, "Timeshift")
        }

        if (sources.isEmpty()) {
            editingSource = null
            resetTo(Screen.SourceEdit)
        } else {
            if (profiles.size > 1) resetTo(Screen.ProfilePicker)
            viewModelScope.launch {
                loadActive()
                // A new playlist: everything now. Otherwise only the channel list, and only with
                // "update on start-up" on and over a day old – the TV guide updates in the background
                // once a day (keepGuideFresh), so opening Flicker never waits for it.
                val t = System.currentTimeMillis()
                val empty = activeSources().filter { !db.hasChannels(it.id) }
                if (empty.isNotEmpty()) refresh(empty)
                else {
                    val stale = activeSources().filter { refreshOnStart && t - store.lastChannels(it.id) > 24 * 3600_000L }
                    if (stale.isNotEmpty()) refresh(stale, guide = false)
                }
            }
        }

        // Lip-sync follows the channel being watched (ignored in safe mode).
        viewModelScope.launch {
            snapshotFlow { playing?.id to lipSyncMap }.collect { (id, map) ->
                val ms = if (SafeMode.active) 0L else (id?.let { map[it] } ?: map["*"])?.toLongOrNull() ?: 0L
                com.titanejstudios.tuner.playback.LipSync.offsetUs = ms * 1000L
            }
        }
        // Volume level and picture quality follow the channel being watched.
        viewModelScope.launch {
            snapshotFlow { Triple(playing?.id, volumeLevels, qualityModes) }.collect { (id, vols, quals) ->
                playback.volumeDb = id?.let { vols[it]?.toIntOrNull() } ?: 0
                playback.quality = id?.let { quals[it] } ?: "auto"
            }
        }
        com.titanejstudios.tuner.apps.Updater.onStatus = { msg -> viewModelScope.launch { showNotice(msg) } }
        watchPlaybackForHomeAssistant()

        // The VPN first, so nothing streams before it's up.
        runCatching { vpn.autoStart() }
        // Start-up: channels first; everything else a few seconds later so the first screen is quick.
        viewModelScope.launch {
            delay(4_000)
            startExtras()
        }

        viewModelScope.launch {
            var ticks = 0
            while (true) {
                now = System.currentTimeMillis()
                checkReminders()
                offerStartOver()
                checkSaver()
                trackUsage()
                announceProgrammeChange()
                trackCleanPlay()
                checkStillWatching()
                if (ticks % 2 == 0 && mode is PlayMode.Vod && playback.isPlaying) saveResume(syncHome = false)
                if (ticks % 4 == 3) {
                    refreshHomeFeed()
                    refreshMoodPicks()
                }
                if (ticks % 4 == 1) keepGuideFresh()
                // Every 5 minutes: offline channels with a match on come back (see matchDayCheck).
                if (ticks % 20 == 2) matchDayCheck()
                // Tuner can stay open for days: look for updates (at most once a day).
                if (ticks % 240 == 239) updates.autoCheck()
                // A minute without crashing: start-up crashes no longer count towards safe mode.
                if (ticks == 4) SafeMode.markStable()
                trackStats(flush = ticks % 4 == 0)
                recordings = store.recordings
                ticks++
                delay(15_000)
            }
        }
    }

    /** Things that don't need to be ready for the first screen. Most are skipped in safe mode. */
    private fun startExtras() {
        if (extrasStarted) return
        extrasStarted = true
        // Even in safe mode – a newer version may be the fix.
        updates.autoCheck()
        updates.checkWhatsNew()
        runCatching { GuideRefreshWorker.schedule(app) }
        runCatching { RecordingScheduler.rescheduleAll(app) }
        loadApps()
        reportCrashToHomeAssistant()
        if (SafeMode.active) {
            AppLog.info("Safe mode: Plex, Live tab, TV sync and update checks are off")
            return
        }
        plexFeature.refresh()
        checkNewEpisodes()
        startSync()
    }

    internal fun loadSettingsFromStore() {
        profiles = store.profiles
        activeProfile = profiles.firstOrNull { it.id == store.activeProfileId } ?: profiles.first()
        favourites = store.favourites(activeProfile.id)
        favouriteSet = favourites.toSet()
        recents = store.recents(activeProfile.id)
        hiddenGroups = store.hiddenGroups
        hiddenChannels = store.hiddenChannels
        lockedGroups = store.lockedGroups
        groupOrder = store.groupOrder
        pin = store.pin
        reminders = store.reminders
        resume = store.resume
        sources = store.sources
        activeSourceId = store.activeSourceId
        recordings = store.recordings
        webhookUrl = store.webhookUrl
        openLastChannel = store.openLastChannel
        startScreen = store.startScreen
        refreshOnStart = store.refreshOnStart
        seriesLinks = store.seriesLinks
        hiddenApps = store.hiddenApps
        selectedGroupState = store.lastGroup(activeProfile.id) ?: ALL
        loadTheme()
        loadHomeRows()
        subtitleScale = store.subtitleScale
        subtitleBox = store.subtitleBox
        SubtitleLook.scale = subtitleScale
        SubtitleLook.background = subtitleBox
        // Safe mode leaves the newer extras off without changing the saved settings.
        nightMode = store.nightMode && !SafeMode.active
        playback.nightModeOn = nightMode
        clearSpeech = store.clearSpeech && !SafeMode.active
        playback.speechOn = clearSpeech
        frameRateMatch = store.frameRateMatch && !SafeMode.active
        playback.setSeamlessFrameRate(frameRateMatch)
        liveTabScope = store.liveTabScope
        pictureModes = store.pictureModes
        groupRenames = store.groupRenames
        loadRecordingSettings()
        customGroups = store.customGroups
        lipSyncMap = store.lipSync
        loadLanguages()
        epgOverrides = store.epgOverrides
        deadChannels = store.deadChannels
        com.titanejstudios.tuner.data.AppLog.info("Offline list: ${deadChannels.size} channel(s), last checked " +
            (store.healthCheckedAt.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(it)) } ?: "never"))
        hideDead = store.hideDead
        healthNightly = store.healthNightly
        healthScope = store.healthScope
        healthCheckedAt = store.healthCheckedAt
        fastSwitch = store.fastSwitch && !SafeMode.active
        playback.fastSwitch = fastSwitch
        timeshiftEnabled = store.timeshiftEnabled
        remoteEnabled = store.remoteEnabled
        audioPassthrough = store.audioPassthrough
        streamCompatibility = store.streamCompatibility
        watchlist = Watchlist.decode(store.watchlistJson)
        followedSeries = Watchlist.decodeFollowed(store.followedSeriesJson)
        cinemaLights = store.cinemaLights
        stillWatchingHours = store.stillWatchingHours
        volumeLevels = store.volumeLevels
        qualityModes = store.qualityModes
        sportFollowMode = store.sportFollowMode
        tmdbKey = store.tmdbKey
        openSubtitlesKey = store.openSubtitlesKey
        introEnds = store.introEnds
        syncEnabled = store.syncEnabled
        nasBackupOn = store.nasBackup
        mergeVersions = store.mergeVersions
        preferredVersions = store.preferredVersions
        updateServer()
    }

    override fun onCleared() {
        link.stop()
        healthCancel = true
        tvShare?.stop()
        phoneServer?.stop()
        multi.release()
        playback.release()
    }

    fun showNotice(message: String) {
        notice = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(3_500)
            notice = null
        }
    }

    internal fun sendHa(event: String, data: Map<String, Any?> = emptyMap()) {
        HomeAssistantWebhook.send(webhookUrl, event, data + ("profile" to activeProfile.name))
    }

    // ---------------- Loading ----------------

    fun activeSources(): List<Source> =
        if (activeSourceId == ALL_SOURCES) sources
        else sources.filter { it.id == activeSourceId }.ifEmpty { sources }

    val activeSourceName: String
        get() = if (activeSourceId == ALL_SOURCES || sources.size <= 1) {
            if (sources.size == 1) sources[0].name else "All playlists"
        } else sources.firstOrNull { it.id == activeSourceId }?.name ?: ""

    val hasVod: Boolean get() = !activeProfile.kids && activeSources().any { it is Source.Xtream }

    /**
     * Reads the saved playlists in two steps: channels first (quick – lists and Home appear straight
     * away), then the guide (can be hundreds of thousands of programmes).
     */
    internal suspend fun loadActive() {
        val ids = activeSources().map { it.id }
        val list = withContext(Dispatchers.IO) {
            db.loadChannels(ids).also {
                altIndex = StreamAlternates.index(it)
                // The name matching for merging HD/SD copies and languages, worked out here rather than on screen.
                runCatching {
                    ChannelMerge.warm(it.mapTo(HashSet()) { c -> c.name })
                    com.titanejstudios.tuner.data.Languages.warm(it)
                }
            }
        }
        allChannels = list
        recomputeVisible()
        initialLoadDone = true
        handlePendingLink()
        handleStartup()
        publishFavouritesRow()
        // Start-up: "now and next" first (a few hours of guide reads in a moment), then the lot.
        if (epg.isEmpty()) runCatching {
            val t = System.currentTimeMillis()
            val quick = withContext(Dispatchers.IO) { shiftGuide(list, db.loadEpg(ids, t - 3 * 3600_000L, t + 4 * 3600_000L, emptyMap())) }
            if (epg.isEmpty()) {
                epg = quick
                refreshHomeFeed()
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        loadGuide()
    }

    /** Reads the next 36 hours of guide (plus catch-up history) for the channels in memory. */
    private suspend fun loadGuide() {
        val ids = activeSources().map { it.id }
        val chs = allChannels
        val t = System.currentTimeMillis()
        val guide = withContext(Dispatchers.IO) {
            val catchupFrom = HashMap<String, Long>()
            // A copy without catch-up (e.g. the 1080p one) still gets the history if another copy
            // of the same channel has catch-up, so the guide can offer it.
            val familyDays = HashMap<String, Int>()
            for (c in chs) if (c.hasCatchup) {
                val k = c.group + "|" + ChannelMerge.key(c.name)
                familyDays[k] = maxOf(familyDays[k] ?: 0, c.catchupDays)
            }
            for (c in chs) {
                val days = if (c.hasCatchup) c.catchupDays else familyDays[c.group + "|" + ChannelMerge.key(c.name)] ?: 0
                if (days > 0) catchupFrom[c.id] = t - days.coerceAtMost(com.titanejstudios.tuner.data.Lite.catchupDays) * 86_400_000L
            }
            // Lite mode reads 12 hours now; scrolling the guide further reads more (guideNeeds).
            shiftGuide(chs, db.loadEpg(ids, t - 3 * 3600_000L, t + com.titanejstudios.tuner.data.Lite.guideHours * 3600_000L, catchupFrom))
        }
        epg = guide
        epgLoadedAt = t
        epgUntil = t + com.titanejstudios.tuner.data.Lite.guideHours * 3600_000L
        applySeriesLinks()
        refreshHomeFeed()
        updateWidget()
        checkUsual()
        applySportFollow()
        refreshTeamFixtures()
    }

    /**
     * Tuner can stay open for days. Re-read the guide when the overnight update has saved a newer
     * one, or every 6 hours so "now and next" never runs off the end of what's in memory.
     */
    private fun keepGuideFresh() {
        if (!initialLoadDone || epgLoadedAt == 0L || refreshJob?.isActive == true) return
        checkNewEpisodes()
        importTraktWatched()
        refreshTeamFixtures()
        val t = System.currentTimeMillis()
        val newer = activeSources().any { store.lastRefresh(it.id) > epgLoadedAt }
        if (newer) {
            // The overnight job may also have changed the channel list.
            viewModelScope.launch { loadActive() }
        } else if (t - epgLoadedAt > 6 * 3600_000L) {
            viewModelScope.launch { loadGuide() }
        }
        // Once a day: if the overnight update didn't happen (the TV was off), the guide updates in
        // the background – a few minutes after Flicker opens, never while it's starting.
        if (t - startedAt > 3 * 60_000L && t - guideTriedAt > 3600_000L) {
            val old = activeSources().filter { t - store.lastGuide(it.id) > 24 * 3600_000L }
            if (old.isNotEmpty()) {
                guideTriedAt = t // a failed attempt waits an hour before trying again
                refresh(old, channels = false)
            }
        }
    }

    /**
     * Downloads [targets]' channel lists and (with [guide]) their TV guides. With [channels] false,
     * just the guide (the once-a-day background update).
     */
    fun refresh(targets: List<Source> = activeSources(), guide: Boolean = true, channels: Boolean = true) {
        if (targets.isEmpty() || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            error = null
            val problems = ArrayList<String>()
            for ((i, s) in targets.withIndex()) {
                val which = if (targets.size > 1) " – ${s.name} (${i + 1} of ${targets.size})" else ""
                if (!channels) {
                    try {
                        status = "Updating the TV guide$which… (everything works meanwhile)"
                        val list = withContext(Dispatchers.IO) { db.loadChannels(listOf(s.id)) }
                        val guideError = withContext(Dispatchers.IO) { inBackgroundPriority { repo.refreshGuide(s, list, null) } }
                        guideError?.let { problems += "${s.name}: TV guide failed ($it)" }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        AppLog.warn("Couldn't update the TV guide for ${s.name}", e)
                    }
                    continue
                }
                try {
                    status = "Updating channels$which…"
                    val started = System.currentTimeMillis()
                    val startBytes = com.titanejstudios.tuner.data.Http.received.get()
                    // Show it's still moving: how much has come down so far.
                    val ticker = launch {
                        while (true) {
                            delay(1_000)
                            val mb = (com.titanejstudios.tuner.data.Http.received.get() - startBytes) / 1_000_000.0
                            val secs = (System.currentTimeMillis() - started) / 1000
                            status = "Updating channels$which · " + String.format(java.util.Locale.UK, "%.1f MB", mb) + " · ${secs}s"
                        }
                    }
                    val (list, playlistEpg) = try {
                        withContext(Dispatchers.IO) { inBackgroundPriority { repo.refreshChannels(s) } }
                    } finally {
                        ticker.cancel()
                    }
                    AppLog.info("Channels updated: ${list.size} from ${s.name} in ${(System.currentTimeMillis() - started) / 1000}s")
                    // Show the channels straight away; the guide fills in when it's ready.
                    loadActive()
                    if (guide) {
                        status = "Updating the TV guide$which… (everything works meanwhile)"
                        val guideError = withContext(Dispatchers.IO) { inBackgroundPriority { repo.refreshGuide(s, list, playlistEpg) } }
                        guideError?.let { problems += "${s.name}: TV guide failed ($it)" }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // The provider stopped answering (a minute with nothing) or sent nonsense: keep the channels you had.
                    AppLog.warn("Couldn't update ${s.name} – kept the channels from last time", e)
                    problems += "${s.name}: ${e.message ?: e.javaClass.simpleName} – using the channels from last time"
                }
            }
            loadActive()
            status = null
            if (problems.isNotEmpty()) error = problems.joinToString("  ·  ")
            scheduleLiveSync()
        }
    }

    /** Run heavy work (guide parsing) at low CPU priority so the UI and video stay smooth. */
    private fun <T> inBackgroundPriority(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val old = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return block()
        } finally {
            android.os.Process.setThreadPriority(old)
        }
    }

    internal fun recomputeVisible() {
        val hiddenCh = hiddenChannels
        // Offline channels stay hidden, except match-day ones and any with something on soon.
        val dead = if (hideDead) deadChannels - onAirSoon - matchDayChannels else emptySet()
        originalGroups = allChannels.mapTo(HashSet()) { it.group }
        // Your renamed / merged categories.
        val renames = groupRenames
        val renamed = if (renames.isEmpty()) allChannels else allChannels.map { c -> renames[c.group]?.let { c.copy(group = it) } ?: c }
        // Languages & countries you've hidden (e.g. everything not in English).
        val hiddenLang = hiddenLanguages
        val source = if (hiddenLang.isEmpty()) renamed else renamed.filter { languageShown(Languages.tagOf(it)?.code, hiddenLang) }
        channels = source.filter { it.id !in hiddenCh && it.id !in dead }
        // Categories in the provider's order (playlist by playlist), like TiviMate.
        val sourceIndex = sources.withIndex().associate { (i, src) -> src.id to i }
        val firstOf = LinkedHashMap<String, Channel>()
        for (c in source) firstOf.putIfAbsent(c.group, c)
        val distinct = firstOf.values
            .sortedWith(compareBy({ sourceIndex[it.sourceId] ?: 0 }, { it.groupPos }))
            .map { it.group }
        val custom = customGroups.keys.filter { it != FAVOURITES && it != RECENT && it != ALL }
        val customSet = custom.toHashSet()
        val present = distinct.toHashSet().also { it.addAll(custom) }
        val ordered = groupOrder.filter { it in present }
        val orderedSet = ordered.toHashSet()
        // Your own categories come first (unless you've moved them), then the provider's.
        allGroups = ordered + custom.filter { it !in orderedSet } + distinct.filter { it !in orderedSet && it !in customSet }
        // Skip provider groups that clash with the built-in names (duplicate list keys crash Compose).
        val visibleGroups = allGroups.filter {
            it !in hiddenGroups && profileAllows(it) && it != FAVOURITES && it != RECENT && it != ALL
        }
        groupNames = listOf(FAVOURITES, RECENT, ALL) + visibleGroups
        if (selectedGroup !in groupNames) selectedGroup = ALL
        rebuildChannelCaches()
    }

    private fun rebuildChannelCaches() {
        if (mergeVersions) {
            // HD/SD copies of a channel show once (best quality, or the version you picked).
            val merged = ChannelMerge.merge(channels, preferredVersions)
            allowedAll = merged.channels.filter { isAllowed(it) }
            versionsOf = merged.versions
            byGroup = merged.channels.groupBy { it.group }
        } else {
            allowedAll = channels.filter { isAllowed(it) }
            versionsOf = emptyMap()
            byGroup = channels.groupBy { it.group }
        }
        // Every copy stays findable by id (favourites, recents, deep links, your own categories).
        byId = channels.associateBy { it.id }
        refreshHomeFeed()
    }

    /** Other versions of [channel] (HD/SD…), best first; empty when there's only one. */
    fun versionsFor(channel: Channel): List<Channel> {
        versionsOf[channel.id]?.let { return it }
        // Playing a version that isn't the one shown in the list: find its family.
        if (!mergeVersions) return emptyList()
        val k = ChannelMerge.key(channel.name)
        return versionsOf.values.firstOrNull { list -> list.any { it.id == channel.id } }
            ?: versionsOf.values.firstOrNull { list -> ChannelMerge.key(list[0].name) == k && list[0].group == channel.group }
            ?: emptyList()
    }

    fun versionLabel(channel: Channel): String = ChannelMerge.versionLabel(channel)

    /** Player menu › Version: switch to another copy, and remember it for this channel. */
    fun watchVersion(channel: Channel) {
        preferredVersions = preferredVersions + (ChannelMerge.key(channel.name) to channel.id)
        store.preferredVersions = preferredVersions
        startLive(channel)
        rebuildChannelCaches()
        showNotice("${channel.name} – Flicker will use this version from now on")
    }

    fun toggleMergeVersions() {
        mergeVersions = !mergeVersions
        store.mergeVersions = mergeVersions
        recomputeVisible()
        showNotice(if (mergeVersions) "HD and SD copies now show as one channel" else "Every copy of a channel is shown")
    }

    /**
     * For Home's "In the mood for…" tiles: films (title, picture) that fit each mood – a different
     * film on every tile, changing daily. From your whole Plex film library (read once a day) and
     * your provider's films when they're loaded.
     */
    var moodPicks by mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()); private set
    private var moodPicksFrom = ""
    private var plexMoodFilms: List<com.titanejstudios.tuner.features.PlexFeature.MoodFilm> = emptyList()
    private var plexMoodFilmsAt = 0L
    private var moodJob: Job? = null

    private fun refreshMoodPicks() {
        if (moodJob?.isActive == true) return
        val films = if (hasVod && !activeProfile.kids) movies.orEmpty() else emptyList()
        val t = System.currentTimeMillis()
        val day = t / 86_400_000L
        val needPlex = plexLinked && t - plexMoodFilmsAt > 24 * 3_600_000L
        val from = "${films.size}|${plexMoodFilms.size}|$day|${activeProfile.id}"
        if (from == moodPicksFrom && !needPlex) return
        val looked = filmLookups
        val kids = activeProfile.kids
        moodJob = viewModelScope.launch {
            if (needPlex) {
                plexMoodFilmsAt = t
                // Lite mode: just the recently added films (a whole library is a lot to hold on an older stick).
                plexMoodFilms = if (liteOn) plexFeature.recent.filter { it.type == "movie" }.map { e ->
                    com.titanejstudios.tuner.features.PlexFeature.MoodFilm(e.title, e.art, e.image, e.genres, e.durationMs.takeIf { it > 0 }?.let { (it / 60_000).toInt() }, plexFeature.isKidsEntry(e))
                } else withContext(Dispatchers.IO) { runCatching { plexFeature.allFilmsForMoods() }.getOrDefault(emptyList()) }
            }
            val plex = plexMoodFilms
            moodPicksFrom = "${films.size}|${plex.size}|$day|${activeProfile.id}"
            val picks = withContext(Dispatchers.Default) {
                val used = HashSet<String>()
                val out = LinkedHashMap<String, List<Pair<String, String>>>()
                for (m in com.titanejstudios.tuner.data.Moods.all) {
                    val fits = ArrayList<Pair<String, String>>()
                    for (f in plex) {
                        if (kids && !f.kids) continue
                        val pic = f.picture ?: f.poster ?: continue
                        if (com.titanejstudios.tuner.data.Moods.fits(m, f.genres, f.minutes)) fits += f.title to pic
                    }
                    for (f in films) {
                        val l = looked[f.name.trim()]
                        val pic = l?.backdrop ?: f.poster ?: continue
                        if (com.titanejstudios.tuner.data.Moods.fits(m, listOf(f.category) + l?.genres.orEmpty(), l?.runtimeMinutes)) {
                            fits += FilmName.parse(f.name).first to pic
                        }
                    }
                    // Different films each day, and never the same film on two moods.
                    val chosen = fits.distinctBy { it.first.lowercase() }
                        .shuffled(kotlin.random.Random(day * 31 + m.key.hashCode()))
                        .filter { it.first.lowercase() !in used }
                        .take(3)
                    chosen.firstOrNull()?.let { used += it.first.lowercase() }
                    out[m.key] = chosen
                }
                out
            }
            moodPicks = picks
        }
    }

    private fun refreshHomeFeed() {
        // Picks come from your own country's channels (no shopping, religious, adult or radio
        // either) – unless it's a channel you've watched or made a favourite.
        val mine = (recents + favourites).toHashSet()
        val all = allowedAll
        val guide = epg
        val rec = recents
        val favs = favourites
        homeFeedJob?.cancel()
        homeFeedJob = viewModelScope.launch {
            val t = System.currentTimeMillis()
            // A copy: viewing keeps updating the real one (on the main thread) while this works in the background.
            val learned = Taste.fromJson(taste.toJson())
            // Checking tens of thousands of channel names for clutter is slow: never on the screen's thread.
            val chans = withContext(Dispatchers.Default) { all.filter { c -> c.id in mine || (!WhatsOn.isClutter(c) && !WhatsOn.isWorld(c)) } }
            val result = withContext(Dispatchers.Default) {
                // Once Tuner has learned a little about you, picks follow your taste (with reasons).
                val picks = if (learned.isEmpty) HomeFeed.topPicks(chans, guide, rec, favs, t)
                else Recommend.topPicks(chans, guide, learned, rec, t).ifEmpty { HomeFeed.topPicks(chans, guide, rec, favs, t) }
                Triple(HomeFeed.sport(chans, guide, t), picks, Recommend.yourShowsTonight(chans, guide, learned, t))
            }
            sportNow = result.first
            topPicks = result.second
            yourShows = result.third
            val slots = HashSet(watchingSlots)
            missed = if (learned.isEmpty) emptyList() else withContext(Dispatchers.Default) { missedPicks(chans, guide, learned, t, slots) }
        }
    }

    /** Opens where you left off – Home, the TV Guide on your last category and channel, or the last channel. */
    private fun handleStartup() {
        if (cloud.available && !cloud.signedIn && !store.cloudPrompted && !activeProfile.kids) cloudPrompt = true
        if (startupHandled || allChannels.isEmpty() || pendingLink != null) return
        if (screen == Screen.ProfilePicker) return
        startupHandled = true
        if (mode != null) return
        // Safe mode always opens on Home, in case the last channel is what keeps crashing.
        if (SafeMode.active) return
        val id = store.lastChannel(activeProfile.id)
        val ch = id?.let { byId[it] }?.takeIf { isAllowed(it) }
        if (ch != null) focusChannelId = ch.id
        when (startScreen) {
            "channel" -> if (ch != null) {
                val group = selectedGroup
                val list = channelsFor(group).takeIf { l -> l.any { it.id == ch.id } } ?: channelsFor(ALL)
                play(ch, list)
            }
            "guide" -> if (screen == Screen.Home) navigate(Screen.Channels)
            else -> Unit
        }
    }

    // ---------------- Lists ----------------

    private fun profileAllows(group: String): Boolean =
        !activeProfile.kids || group in activeProfile.allowedGroups

    fun isGroupLocked(group: String): Boolean = pin != null && !unlocked && group in lockedGroups
    fun isAllowed(channel: Channel): Boolean = profileAllows(channel.group) && !isGroupLocked(channel.group)

    fun channelsFor(group: String): List<Channel> = when (group) {
        ALL -> allowedAll
        FAVOURITES -> favourites.mapNotNull { byId[it] }.filter { isAllowed(it) }
        RECENT -> recents.mapNotNull { byId[it] }.filter { isAllowed(it) }
        else -> when {
            isGroupLocked(group) || !profileAllows(group) -> emptyList()
            group in customGroups -> customGroups[group].orEmpty().mapNotNull { byId[it] }.filter { isAllowed(it) }
            else -> byGroup[group].orEmpty()
        }
    }

    // ---------------- Guide time fix ----------------

    /** Moves each playlist's guide by its chosen number of minutes. */
    internal fun shiftGuide(chs: List<Channel>, guide: Map<String, List<Programme>>): Map<String, List<Programme>> {
        val shifts = store.epgShift.mapValues { (_, v) -> (v.toLongOrNull() ?: 0L) * 60_000L }.filterValues { it != 0L }
        if (shifts.isEmpty()) return guide
        val sourceOf = chs.associate { it.id to it.sourceId }
        return guide.mapValues { (id, list) ->
            val d = shifts[sourceOf[id]] ?: 0L
            if (d == 0L) list else list.map { it.copy(start = it.start + d, stop = it.stop + d) }
        }
    }

    fun guideShiftMinutes(sourceId: String): Int = store.epgShift[sourceId]?.toIntOrNull() ?: 0

    fun guideShiftLabel(sourceId: String): String {
        val m = guideShiftMinutes(sourceId)
        return when {
            m == 0 -> "No change"
            m % 60 == 0 -> (if (m > 0) "+" else "−") + "${kotlin.math.abs(m / 60)} hour" + if (kotlin.math.abs(m) > 60) "s" else ""
            else -> (if (m > 0) "+" else "−") + "${kotlin.math.abs(m)} min"
        }
    }

    /** Cycles 0 → +1h → +2h → −2h → −1h → −30 min → +30 min → 0. */
    fun cycleGuideShift(sourceId: String) {
        val steps = listOf(0, 60, 120, -120, -60, -30, 30)
        val i = steps.indexOf(guideShiftMinutes(sourceId)).coerceAtLeast(0)
        val next = steps[(i + 1) % steps.size]
        store.epgShift = if (next == 0) store.epgShift - sourceId else store.epgShift + (sourceId to next.toString())
        viewModelScope.launch { loadActive() }
        showNotice("Guide times for ${sources.firstOrNull { it.id == sourceId }?.name ?: "this playlist"}: ${guideShiftLabel(sourceId)}")
    }

    // ---------------- Category editor ----------------

    fun isCustomGroup(group: String) = group in customGroups

    private fun nameTaken(name: String) =
        name == FAVOURITES || name == RECENT || name == ALL || name in customGroups ||
            channels.any { it.group == name }

    /** Renames a category. Renaming to an existing category's name merges the two. */
    fun renameGroup(current: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == current) return
        if (name == FAVOURITES || name == RECENT || name == ALL) {
            showNotice("That name is used by Flicker")
            return
        }
        if (current in customGroups) {
            if (name in customGroups || channels.any { it.group == name }) {
                showNotice("There's already a category called $name")
                return
            }
            customGroups = customGroups.toMutableMap().also { m -> m[name] = m.remove(current).orEmpty() }
            store.customGroups = customGroups
        } else {
            val merging = channels.any { it.group == name }
            val r = groupRenames.toMutableMap()
            for (o in originalGroups) {
                if ((r[o] ?: o) == current) {
                    if (o == name) r.remove(o) else r[o] = name
                }
            }
            groupRenames = r
            store.groupRenames = r
            if (merging) showNotice("Merged $current into $name") else showNotice("Renamed to $name")
        }
        // Keep its place, hidden and locked settings.
        fun Set<String>.swap() = if (current in this) this - current + name else this
        hiddenGroups = hiddenGroups.swap().also { store.hiddenGroups = it }
        lockedGroups = lockedGroups.swap().also { store.lockedGroups = it }
        groupOrder = groupOrder.map { if (it == current) name else it }.distinct().also { store.groupOrder = it }
        if (selectedGroup == current) selectedGroup = name
        recomputeVisible()
    }

    /** Puts a category's original name back (undoes renames and merges into it). */
    fun resetGroupName(current: String) {
        groupRenames = groupRenames.filterValues { it != current }
        store.groupRenames = groupRenames
        recomputeVisible()
        showNotice("Original category names restored")
    }

    fun createCustomGroup(name: String, firstChannel: Channel?) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (nameTaken(n)) {
            showNotice("There's already a category called $n")
            return
        }
        customGroups = customGroups + (n to listOfNotNull(firstChannel?.id))
        store.customGroups = customGroups
        recomputeVisible()
        showNotice(if (firstChannel != null) "${firstChannel.name} added to $n" else "Category $n made")
    }

    fun toggleInCustomGroup(group: String, channel: Channel) {
        val ids = customGroups[group] ?: return
        val now = if (channel.id in ids) ids - channel.id else ids + channel.id
        customGroups = customGroups + (group to now)
        store.customGroups = customGroups
        recomputeVisible()
        showNotice(if (channel.id in now) "${channel.name} added to $group" else "${channel.name} removed from $group")
    }

    fun deleteCustomGroup(group: String) {
        customGroups = customGroups - group
        store.customGroups = customGroups
        if (selectedGroup == group) selectedGroup = ALL
        recomputeVisible()
        showNotice("Category $group deleted (the channels are still in the others)")
    }

    fun programmesFor(channelId: String): List<Programme> = epg[channelId].orEmpty()

    fun nowNext(channelId: String, t: Long): Pair<Programme?, Programme?> {
        val list = epg[channelId] ?: return null to null
        val i = list.indexOfFirst { it.stop > t }
        if (i < 0) return null to null
        val current = list[i].takeIf { it.start <= t }
        val next = if (current != null) list.getOrNull(i + 1) else list[i]
        return current to next
    }

    /** How far back the guide can scroll – the longest catch-up window, at least 2 hours. */
    val guideHistoryMs: Long
        get() = maxOf(2 * 3600_000L, (allChannels.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(7) * 86_400_000L)

    // ---------------- Playback ----------------

    private fun liveMetadata(channel: Channel): MediaMetadata {
        val current = nowNext(channel.id, System.currentTimeMillis()).first
        val b = MediaMetadata.Builder().setTitle(current?.title ?: channel.name).setArtist(channel.name)
        channel.logo?.let { b.setArtworkUri(Uri.parse(it)) }
        return b.build()
    }

    fun play(channel: Channel, list: List<Channel>) {
        if (!isAllowed(channel)) {
            requestUnlock { play(channel, list) }
            return
        }
        if (timeLeftSeconds() <= 0 || isBedtimeNow()) {
            timesUp = true
            return
        }
        zapList = list.ifEmpty { listOf(channel) }
        startLive(channel)
        navigate(Screen.Player)
    }

    /** Switch channel from the in-player channel list (stays in the player). */
    fun playFromOverlay(channel: Channel, list: List<Channel>) {
        zapList = list.ifEmpty { listOf(channel) }
        startLive(channel)
    }

    private fun startLive(channel: Channel, force: Boolean = false, preview: Boolean = false) {
        com.titanejstudios.tuner.data.SafeMode.doing = "Watching live TV: ${channel.name}"
        if (vpnBlocks()) return
        if (zapPreview != null) {
            zapJob?.cancel()
            zapPreview = null
        }
        cleanPlaySeconds = 0
        usualOffer = null
        val current = mode
        if (!force && current is PlayMode.Live && current.channel.id == channel.id && playback.isActive) return
        upgradeJob?.cancel()
        qualityBack = null
        startOver = null
        startOverChoosing = false
        if (current is PlayMode.Live && current.channel.id != channel.id) previousChannel = current.channel
        if (current is PlayMode.Vod) saveResume(syncHome = true)
        mode = PlayMode.Live(channel)
        focusChannelId = channel.id
        playback.play(
            channel.url,
            live = true,
            metadata = liveMetadata(channel),
            alternates = StreamAlternates.forChannel(channel, altIndex).filter { it != channel.url },
            key = channel.id,
        )
        // A preview isn't "watching" yet: no recents or last channel until you choose it.
        if (!preview) {
            addRecent(channel.id)
            store.setLastChannel(activeProfile.id, channel.id)
        }
        scheduleStandby(channel)
        val p = nowNext(channel.id, System.currentTimeMillis()).first
        lastAnnouncedProgramme = p?.start ?: 0L
        sendHa(
            "playing",
            mapOf(
                "kind" to "live", "channel" to channel.name, "group" to channel.group, "logo" to channel.logo,
                "title" to (p?.title ?: channel.name), "category" to p?.category,
                "start" to p?.start?.div(1000), "stop" to p?.stop?.div(1000),
            ),
        )
    }

    /**
     * ▲▼ in the player. One press changes channel straight away. Pressing quickly shows each
     * channel's name on the info bar and only tunes the one you stop on – so flicking past five
     * channels doesn't open five streams.
     */
    fun zap(delta: Int) {
        if (zapList.isEmpty()) return
        lastZapDirection = if (delta < 0) -1 else 1
        zappedAt = System.currentTimeMillis()
        val fromId = zapPreview?.id ?: playing?.id
        val idx = zapIndexOf(fromId)
        val target = zapList[(idx + delta).mod(zapList.size)]
        val t = System.currentTimeMillis()
        val rapid = t - lastZapAt < ZAP_RAPID_MS
        lastZapAt = t
        zapJob?.cancel()
        if (!rapid) {
            zapPreview = null
            startLive(target)
            return
        }
        zapPreview = target
        zapJob = viewModelScope.launch {
            delay(ZAP_SETTLE_MS)
            val ch = zapPreview ?: return@launch
            zapPreview = null
            if (screen == Screen.Player && mode is PlayMode.Live) startLive(ch)
        }
    }

    /** Typing a number on the remote: the provider's channel number if the playlist has them. */
    fun zapToNumber(number: Int) {
        val all = channelsFor(ALL)
        if (all.any { it.number > 0 }) {
            val ch = zapList.firstOrNull { it.number == number } ?: all.firstOrNull { it.number == number }
            if (ch == null) {
                showNotice("No channel $number")
                return
            }
            if (zapList.none { it.id == ch.id }) zapList = all
            startLive(ch)
            return
        }
        val ch = zapList.getOrNull(number - 1)
        if (ch == null) showNotice("No channel $number") else startLive(ch)
    }

    /** [each] channels either side of [id] in the channel list (for the channel strip). */
    fun zapNeighbours(id: String?, each: Int): List<Channel> {
        if (zapList.isEmpty()) return emptyList()
        val i = zapIndexOf(id).coerceAtLeast(0)
        val n = minOf(each, (zapList.size - 1) / 2)
        return (-n..n).map { zapList[(i + it).mod(zapList.size)] }.distinctBy { it.id }
    }

    /** Position in the zap list, counting any version of a combined channel as that channel. */
    private fun zapIndexOf(id: String?): Int {
        if (id == null) return -1
        val i = zapList.indexOfFirst { it.id == id }
        if (i >= 0) return i
        return zapList.indexOfFirst { c -> versionsOf[c.id]?.any { it.id == id } == true }
    }

    fun playPrevious() {
        val prev = previousChannel ?: return
        if (!zapList.any { it.id == prev.id }) zapList = channelsFor(ALL)
        startLive(prev)
    }

    val zapChannels: List<Channel> get() = zapList

    /** The provider's channel number if it has one, otherwise the position in the list being shown. */
    fun displayNumber(channel: Channel, indexInList: Int): Int =
        if (channel.number > 0) channel.number else indexInList + 1

    fun channelNumber(channel: Channel): Int =
        if (channel.number > 0) channel.number else zapList.indexOfFirst { it.id == channel.id } + 1

    /**
     * The copy of [channel] that has catch-up. With HD/SD copies combined, the best-quality copy
     * shown in the list often has no catch-up while the 720p one does – use that one for catch-up.
     */
    fun catchupVersion(channel: Channel): Channel? =
        if (channel.hasCatchup) channel else versionsFor(channel).firstOrNull { it.hasCatchup }

    fun hasAnyCatchup(channel: Channel): Boolean = catchupVersion(channel) != null

    fun canCatchup(channel: Channel, p: Programme): Boolean =
        catchupVersion(channel)?.let { Catchup.isAvailable(it, p, now) } == true

    fun playCatchup(requested: Channel, p: Programme, list: List<Channel>, startAt: Long = 0L) {
        if (vpnBlocks()) return
        val channel = catchupVersion(requested) ?: requested
        if (!isAllowed(channel)) {
            requestUnlock { playCatchup(channel, p, list, startAt) }
            return
        }
        val url = Catchup.url(channel, p, System.currentTimeMillis())
        if (url == null) {
            showNotice("Catch-up isn't available for ${channel.name}")
            return
        }
        if (mode is PlayMode.Live) previousChannel = playing
        zapList = list.ifEmpty { listOf(channel) }
        mode = PlayMode.CatchupPlay(channel, p)
        focusChannelId = channel.id
        val meta = MediaMetadata.Builder().setTitle(p.title).setArtist(channel.name).build()
        playback.play(url, live = false, startPositionMs = startAt, metadata = meta)
        sendHa("playing", mapOf("kind" to "catchup", "channel" to channel.name, "title" to p.title, "category" to p.category))
        navigate(Screen.Player)
    }

    fun backToLive() {
        val m = mode as? PlayMode.CatchupPlay ?: return
        mode = null
        startLive(m.channel)
    }

    fun stopPlayback() {
        trackStats(flush = true)
        finishLiveWatch()
        upgradeJob?.cancel()
        qualityBack = null
        val wasPlaying = mode != null
        playback.stop()
        mode = null
        if (wasPlaying) sendHa("stopped")
        com.titanejstudios.tuner.data.SafeMode.doing = "Browsing"
    }

    fun onAppStopped() {
        inForeground = false
        // Leaving mid-video: send where it got to (the player only reports when it closes).
        if (screen == Screen.YouTubePlayer) {
            link.tellOthers()
            cloud.syncSoon(0L)
        }
        finishLiveWatch()
        updateWidget()
        AppState.foreground = false
        trackStats(flush = true)
        updateServer()
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        playback.suspend()
        if (pin != null && unlocked) {
            unlocked = false
            recomputeVisible()
        }
    }

    fun onAppStarted() {
        inForeground = true
        onScreensaverEnded()
        checkUsual()
        if (link.linked) {
            link.start()
            link.syncSoon(2_500L)
        }
        if (cloud.signedIn) {
            cloud.start()
            cloud.syncSoon(4_000L)
        }
        refreshYouTube()
        if (extrasStarted) refreshPlex()
        AppState.foreground = true
        loadApps()
        updateServer()
        deadChannels = store.deadChannels
        if (mode is PlayMode.Live) playback.resumeAfterSuspend()
        recordings = store.recordings
    }

    private fun announceProgrammeChange() {
        val m = mode as? PlayMode.Live ?: return
        val p = nowNext(m.channel.id, System.currentTimeMillis()).first ?: return
        if (p.start == lastAnnouncedProgramme) return
        lastAnnouncedProgramme = p.start
        sendHa(
            "programme_changed",
            mapOf(
                "channel" to m.channel.name, "title" to p.title, "category" to p.category,
                "start" to p.start / 1000, "stop" to p.stop / 1000,
            ),
        )
    }

    // ---------------- Multi-view ----------------

    fun startMultiView(count: Int) {
        val current = playing
        val pool = zapList.ifEmpty { channelsFor(ALL) }
        val start = pool.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        val picks = (0 until count).mapNotNull { pool.getOrNull((start + it) % maxOf(1, pool.size)) }
        if (picks.isEmpty()) return
        stopPlayback()
        multi.start(count, picks)
        // Replace the player with multi-view so BACK returns to full screen.
        if (stack.last() == Screen.Player) stack.removeAt(stack.size - 1)
        stack.add(Screen.MultiView)
    }

    // ---------------- Favourites, recents, hiding, ordering ----------------

    fun toggleFavourite(channelId: String) {
        favourites = if (channelId in favouriteSet) favourites - channelId else favourites + channelId
        favouriteSet = favourites.toSet()
        store.setFavourites(activeProfile.id, favourites)
        updateWidget()
        publishFavouritesRow()
        markChanged(TvSync.FAVOURITES)
    }

    fun moveFavourite(channelId: String, delta: Int) {
        val i = favourites.indexOf(channelId)
        val j = i + delta
        if (i < 0 || j !in favourites.indices) return
        val list = favourites.toMutableList()
        list.add(j, list.removeAt(i))
        favourites = list
        store.setFavourites(activeProfile.id, list)
        publishFavouritesRow()
        markChanged(TvSync.FAVOURITES)
    }

    private fun addRecent(channelId: String) {
        recents = (listOf(channelId) + recents.filter { it != channelId }).take(30)
        store.setRecents(activeProfile.id, recents)
    }

    fun toggleHiddenChannel(channelId: String) {
        hiddenChannels = if (channelId in hiddenChannels) hiddenChannels - channelId else hiddenChannels + channelId
        store.hiddenChannels = hiddenChannels
        recomputeVisible()
    }

    fun toggleHiddenGroup(group: String) {
        hiddenGroups = if (group in hiddenGroups) hiddenGroups - group else hiddenGroups + group
        store.hiddenGroups = hiddenGroups
        recomputeVisible()
    }

    fun toggleLockedGroup(group: String) {
        if (pin == null) {
            showNotice("Set a parental PIN in Settings first")
            return
        }
        requestUnlock {
            lockedGroups = if (group in lockedGroups) lockedGroups - group else lockedGroups + group
            store.lockedGroups = lockedGroups
            recomputeVisible()
        }
    }

    fun moveGroup(group: String, delta: Int) {
        val list = allGroups.toMutableList()
        val i = list.indexOf(group)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        groupOrder = list
        store.groupOrder = list
        recomputeVisible()
    }

    fun resetGroupOrder() {
        groupOrder = emptyList()
        store.groupOrder = emptyList()
        recomputeVisible()
    }

    private fun publishFavouritesRow() {
        val favs = channelsFor(FAVOURITES)
        viewModelScope.launch(Dispatchers.IO) { HomeScreen.publishFavourites(app, store, favs) }
        if (liveTabScope == "favourites") scheduleLiveSync()
    }

    // ---------------- Parental PIN ----------------

    /** Asks for the PIN unless this session is already unlocked. */
    fun requestUnlock(then: () -> Unit) {
        if (pin == null || unlocked) then() else requestPin(then)
    }

    /** Always asks for the PIN (used for leaving a kids profile and extra time). */
    fun requestPin(then: () -> Unit) {
        val current = pin
        if (current == null) {
            then()
            return
        }
        pinPrompt = PinPrompt("Enter your PIN") { entered ->
            if (entered == current) {
                unlocked = true
                pinPrompt = null
                recomputeVisible()
                then()
            } else showNotice("Wrong PIN")
        }
    }

    fun cancelPin() {
        pinPrompt = null
    }

    fun changePin() {
        val askNew = {
            pinPrompt = PinPrompt("Choose a 4-digit PIN") { first ->
                pinPrompt = PinPrompt("Enter it again to confirm") { second ->
                    if (first == second) {
                        pin = first
                        store.pin = first
                        store.pinAt = System.currentTimeMillis()
                        cloud.syncSoon()
                        unlocked = true
                        pinPrompt = null
                        showNotice("PIN saved")
                    } else {
                        pinPrompt = null
                        showNotice("The PINs didn't match – try again")
                    }
                }
            }
        }
        if (pin == null) askNew() else requestPin { askNew() }
    }

    fun removePin() {
        if (pin == null) return
        requestPin {
            pin = null
            store.pin = null
            store.pinAt = System.currentTimeMillis()
            cloud.syncSoon()
            lockedGroups = emptySet()
            store.lockedGroups = emptySet()
            recomputeVisible()
            showNotice("PIN removed and all groups unlocked")
        }
    }

    // ---------------- Profiles & kids limits ----------------

    internal fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Seconds of TV left today for a kids profile with a limit (Long.MAX_VALUE otherwise). */
    fun timeLeftSeconds(): Long {
        val p = activeProfile
        if (!p.kids || p.dailyLimitMinutes <= 0) return Long.MAX_VALUE
        val day = today()
        val allowed = (p.dailyLimitMinutes + store.bonusMinutes(p.id, day)) * 60L
        return allowed - store.usageSeconds(p.id, day)
    }

    /** Kids profile with a bedtime, and it's between bedtime and 6am (unless a grown-up allowed more). */
    fun isBedtimeNow(): Boolean {
        val p = activeProfile
        if (!p.kids || p.bedtimeMinutes < 0) return false
        val t = System.currentTimeMillis()
        if (t < bedtimeOverrideUntil) return false
        return KidsRules.isBedtime(p.bedtimeMinutes, KidsRules.minutesOfDay(t))
    }

    private fun trackUsage() {
        val p = activeProfile
        if (!p.kids) return
        val watching = (mode != null && playback.isPlaying) || (screen == Screen.YouTubePlayer && ytPlaying)
        if (!watching) return
        if (p.dailyLimitMinutes > 0) store.addUsage(p.id, today(), 15)
        val bedtime = isBedtimeNow()
        if (timeLeftSeconds() <= 0 || bedtime) {
            stopPlayback()
            resetTo(Screen.Home)
            timesUp = true
            noteBlocked("TV stopped", if (bedtime) "Bedtime" else "Daily limit reached")
            sendHa(if (bedtime) "bedtime_reached" else "time_limit_reached")
            return
        }
        // A heads-up at 5 minutes and 1 minute, so it doesn't stop mid-scene without warning.
        val t = System.currentTimeMillis()
        val toBed = if (t < bedtimeOverrideUntil) null else KidsRules.minutesToBedtime(p.bedtimeMinutes, KidsRules.minutesOfDay(t))
        // Ten minutes before bedtime: a gentle heads-up, so there's time to finish the episode.
        if (KidsRules.bedtimeHeadsUp(toBed)) {
            val key = "${p.id}|${today()}|bed10"
            if (key != lastKidsWarning) {
                lastKidsWarning = key
                bedtimeSoon = "🌙 Bedtime in 10 minutes, ${p.name} – time to find a good place to stop"
                showNotice("🌙 Bedtime in 10 minutes")
                viewModelScope.launch {
                    delay(20_000)
                    bedtimeSoon = null
                }
            }
        }
        val warn = KidsRules.warningMinutes(timeLeftSeconds(), toBed) ?: return
        val key = "${p.id}|${today()}|$warn"
        if (key != lastKidsWarning) {
            lastKidsWarning = key
            showNotice(if (warn == 1) "⏰ 1 minute of TV left" else "⏰ $warn minutes of TV left")
        }
    }

    fun grantExtraTime(minutes: Int) {
        requestPin {
            if (isBedtimeNow()) bedtimeOverrideUntil = System.currentTimeMillis() + minutes * 60_000L
            else store.addBonus(activeProfile.id, today(), minutes)
            timesUp = false
            showNotice("$minutes more minutes added")
        }
    }

    /** From a grown-up's phone (or another TV): extra minutes today for a kids' profile on every linked TV. */
    fun sendBonusTime(kid: Profile, minutes: Int) {
        val give = {
            val tvs = link.tvs
            // This device too, if it's a TV that profile uses.
            if (!isPhone) store.addBonus(kid.id, today(), minutes)
            if (tvs.isEmpty()) showNotice(if (isPhone) "No linked TVs found – is Flicker open on them?" else "+$minutes minutes for ${kid.name} today")
            else {
                var told = 0
                tvs.forEach { tv ->
                    link.send(tv, JSONObject().put("kind", "bonus").put("profile", kid.id).put("minutes", minutes)) { problem ->
                        told++
                        if (told == tvs.size) showNotice(if (problem == null) "+$minutes minutes for ${kid.name} today ✓" else problem)
                    }
                }
            }
        }
        if (activeProfile.kids) showNotice("Only grown-ups can give extra time") else if (pin != null) requestPin { give() } else give()
    }

    private fun receiveBonus(from: String, j: JSONObject) {
        val pid = j.optString("profile")
        val minutes = j.optInt("minutes").coerceIn(0, 240)
        val kid = profiles.firstOrNull { it.id == pid } ?: return
        if (minutes == 0) return
        if (isBedtimeNow() && activeProfile.id == pid) bedtimeOverrideUntil = System.currentTimeMillis() + minutes * 60_000L
        else store.addBonus(pid, today(), minutes)
        if (activeProfile.id == pid) timesUp = false
        showNotice("🎁 ${from.ifBlank { "A grown-up" }} gave ${kid.name} $minutes more minutes")
    }

    fun dismissTimesUp() {
        timesUp = false
    }

    fun switchProfile(p: Profile) {
        val doSwitch = {
            trackStats(flush = true)
            activeProfile = p
            store.activeProfileId = p.id
            youtube.useProfile(p.id)
            loadLanguages()
            plexFeature.useUser(store.plexUserOf(p.id)?.third)
            favourites = store.favourites(p.id)
            favouriteSet = favourites.toSet()
            taste = Taste.fromJson(store.taste(p.id))
            started = store.started(p.id)
            becauseFilms = emptyList()
            becauseFilmOf = null
            recents = store.recents(p.id)
            selectedGroupState = store.lastGroup(p.id) ?: ALL
            loadTheme()
            loadHomeRows()
            unlocked = false
            timesUp = false
            stopPlayback()
            refreshYouTube()
            recomputeVisible()
            publishFavouritesRow()
            movies = null
            series = null
            resetTo(Screen.Home)
            startupHandled = false
            handleStartup()
        }
        // Leaving a kids profile needs the PIN.
        if (activeProfile.kids && p.id != activeProfile.id && pin != null) requestPin { doSwitch() } else doSwitch()
    }

    fun startAddProfile() {
        editingProfile = null
        navigate(Screen.ProfileEdit)
    }

    fun startEditProfile(p: Profile) {
        editingProfile = p
        navigate(Screen.ProfileEdit)
    }

    fun saveProfile(p: Profile) {
        val list = profiles.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        profiles = list
        store.profiles = list
        profilesChanged()
        if (p.id == activeProfile.id) {
            activeProfile = p
            recomputeVisible()
            refreshYouTube()
        }
        back()
    }

    /** Any profile can go (Everyone too), as long as a grown-up profile is left. */
    fun canDeleteProfile(p: Profile): Boolean = profiles.any { it.id != p.id && !it.kids }

    fun deleteProfile(p: Profile) {
        if (!canDeleteProfile(p)) {
            showNotice("Keep at least one grown-up profile")
            return
        }
        profiles = profiles.filter { it.id != p.id }
        store.profiles = profiles
        com.titanejstudios.tuner.ui.ProfilePhotos.file(p.id)?.delete()
        store.profilesGone = store.profilesGone + (p.name.trim().lowercase() to System.currentTimeMillis())
        profilesChanged()
        if (activeProfile.id == p.id) switchProfile(profiles.first())
    }

    fun newProfileId(): String = "p" + System.currentTimeMillis().toString(36)

    fun toggleOpenLastChannel() {
        openLastChannel = !openLastChannel
        store.openLastChannel = openLastChannel
    }

    fun cycleStartScreen() {
        startScreen = when (startScreen) {
            "home" -> "guide"
            "guide" -> "channel"
            else -> "home"
        }
        store.startScreen = startScreen
    }

    val startScreenLabel: String
        get() = when (startScreen) {
            "guide" -> "TV Guide (where you left it)"
            "channel" -> "Last channel watched"
            else -> "Home"
        }

    fun toggleRefreshOnStart() {
        refreshOnStart = !refreshOnStart
        store.refreshOnStart = refreshOnStart
        showNotice(if (refreshOnStart) "Playlists update on start-up when over 12 hours old" else "Playlists update overnight only")
    }

    /** Resume the last channel watched (the TV button on the remote). */
    fun playLastChannel() {
        val id = (mode as? PlayMode.Live)?.channel?.id ?: store.lastChannel(activeProfile.id)
        val ch = id?.let { byId[it] }
        if (ch == null) {
            showNotice("No channel watched yet")
            return
        }
        if (screen == Screen.Player) return
        play(ch, zapList.takeIf { l -> l.any { it.id == ch.id } } ?: channelsFor(ALL))
    }

    /**
     * Remote shortcuts, from anywhere: red = Sport, green = Recordings, yellow = Search,
     * blue = Favourites, Guide = TV guide, DVR = Recordings, TV = back to the last channel.
     */
    fun handleShortcut(code: Int): Boolean {
        if (pinPrompt != null || timesUp) return false
        if (screen == Screen.SourceEdit || screen == Screen.ProfilePicker || screen == Screen.ProfileEdit) return false
        when (code) {
            android.view.KeyEvent.KEYCODE_PROG_RED -> navigate(Screen.Sports)
            android.view.KeyEvent.KEYCODE_PROG_GREEN, android.view.KeyEvent.KEYCODE_DVR -> {
                if (activeProfile.kids) return false
                navigate(Screen.Recordings)
            }
            android.view.KeyEvent.KEYCODE_PROG_YELLOW -> navigate(Screen.Search)
            android.view.KeyEvent.KEYCODE_PROG_BLUE -> {
                selectedGroup = FAVOURITES
                navigate(Screen.Channels)
            }
            android.view.KeyEvent.KEYCODE_GUIDE -> openGuide()
            android.view.KeyEvent.KEYCODE_TV -> playLastChannel()
            else -> return false
        }
        return true
    }

    // ---------------- Other apps ----------------

    fun loadApps() {
        if (appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            tvApps = withContext(Dispatchers.IO) { runCatching { InstalledApps.load(app) }.getOrDefault(emptyList()) }
        }
    }

    /** Apps for the Home row / Apps screen (hidden ones only on the Apps screen when showAll). */
    fun visibleApps(showAll: Boolean = false): List<TvApp> =
        tvApps.orEmpty().filter { showAll || it.packageName !in hiddenApps }

    fun launchApp(app: TvApp) {
        if (activeProfile.kids && pin != null && !unlocked) {
            requestPin { openApp(app) }
            return
        }
        openApp(app)
    }

    private fun openApp(tvApp: TvApp) {
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        if (!InstalledApps.launch(app, tvApp.packageName)) showNotice("Couldn't open ${tvApp.label}")
    }

    // ---------------- BBC iPlayer, ITVX, Channel 4, My5 ----------------

    /** The broadcaster's app for this channel, if it's installed on this TV (package name). */
    fun catchupAppFor(channel: Channel): Pair<CatchupApps.Provider, String>? {
        val p = CatchupApps.forChannel(channel.name) ?: return null
        // Asked for every row of a list, so the answer is kept for a minute.
        val t = System.currentTimeMillis()
        val cached = catchupAppCache[p.name]
        val pkg = if (cached != null && t - cached.second < 60_000L) cached.first else {
            val pm = app.packageManager
            val found = p.packages.firstOrNull { pm.getLeanbackLaunchIntentForPackage(it) != null || pm.getLaunchIntentForPackage(it) != null }
                ?: tvApps.orEmpty().firstOrNull { p.label.containsMatchIn(it.label.trim()) }?.packageName
            catchupAppCache[p.name] = found to t
            found
        } ?: return null
        return p to pkg
    }

    private val catchupAppCache = HashMap<String, Pair<String?, Long>>()

    /** Opens the broadcaster's app on [title]: its own search when it has one, else just the app. */
    fun openInCatchupApp(channel: Channel, title: String?) {
        val (provider, pkg) = catchupAppFor(channel) ?: run {
            showNotice("The broadcaster's app isn't installed on this TV")
            return
        }
        if (activeProfile.kids && pin != null && !unlocked) {
            requestPin { openInCatchupApp(channel, title) }
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        val clean = title?.replace(Regex("^(live|new)\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE), "")?.trim()
        if (!clean.isNullOrBlank()) {
            val search = android.content.Intent(android.content.Intent.ACTION_SEARCH)
                .setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, clean)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(search) }.isSuccess) {
                showNotice("Searching ${provider.name} for \"$clean\"")
                return
            }
        }
        if (InstalledApps.launch(app, pkg)) {
            if (!clean.isNullOrBlank()) showNotice("In ${provider.name}, search for \"$clean\"")
        } else showNotice("Couldn't open ${provider.name}")
    }

    fun toggleHideApp(tvApp: TvApp) {
        hiddenApps = if (tvApp.packageName in hiddenApps) hiddenApps - tvApp.packageName else hiddenApps + tvApp.packageName
        store.hiddenApps = hiddenApps
        showNotice(if (tvApp.packageName in hiddenApps) "${tvApp.label} hidden from Home" else "${tvApp.label} shown on Home")
    }

    // ---------------- Problem log ----------------

    fun openLog() {
        navigate(Screen.Log)
        loadLog()
    }

    /** Settings › Problem log › Copy: the log on the clipboard, to paste into a message. */
    fun copyLog() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { AppLog.readInOrder() }
            if (text.isBlank()) {
                showNotice("Nothing to copy – no problems logged")
                return@launch
            }
            val header = "Flicker ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n\n"
            val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (cm == null) {
                showNotice("Couldn't copy on this device")
                return@launch
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Flicker problem log", header + text))
            showNotice(if (isPhone) "Problem log copied – paste it into a message" else "Copied – paste it on this TV, or use the phone remote's …/log.txt")
        }
    }

    fun loadLog() {
        viewModelScope.launch {
            logText = withContext(Dispatchers.IO) { AppLog.read() }.ifEmpty { "No problems logged." }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppLog.clear() }
            logText = "No problems logged."
            showNotice("Problem log cleared")
        }
    }

    // ---------------- Series link ----------------

    fun seriesLinkFor(channel: Channel, p: Programme): SeriesLink? =
        seriesLinks.firstOrNull { SeriesLinks.matches(it, channel.id, p) }

    /** Record every episode of this programme on this channel (or stop doing so). */
    fun toggleSeriesLink(channel: Channel, p: Programme) {
        val existing = seriesLinkFor(channel, p)
        if (existing != null) {
            removeSeriesLink(existing)
            return
        }
        seriesLinks = seriesLinks + SeriesLink(channel.id, channel.name, p.title, System.currentTimeMillis())
        store.seriesLinks = seriesLinks
        applySeriesLinks(announce = true, title = p.title)
    }

    fun removeSeriesLink(link: SeriesLink) {
        seriesLinks = seriesLinks.filterNot { it.channelId == link.channelId && it.title == link.title }
        store.seriesLinks = seriesLinks
        showNotice("Series link removed – already-set recordings are kept")
    }

    private fun applySeriesLinks(announce: Boolean = false, title: String? = null) {
        if (seriesLinks.isEmpty()) return
        val chans = allChannels
        val guide = epg
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { SeriesLinks.apply(app, store, chans, guide) }.getOrDefault(0)
            }
            recordings = store.recordings
            if (announce) {
                showNotice(
                    if (added > 0) "Series linked – recording $added episode${if (added == 1) "" else "s"} of $title so far"
                    else "Series linked – new episodes of $title will be recorded"
                )
            }
        }
    }

    // ---------------- Reminders ----------------

    fun hasReminder(channel: Channel, p: Programme): Boolean =
        reminders.any { it.channelId == channel.id && it.start == p.start }

    fun toggleReminder(channel: Channel, p: Programme) {
        reminders = if (hasReminder(channel, p)) {
            showNotice("Reminder cancelled")
            reminders.filter { it.channelId == channel.id && it.start == p.start }.forEach { ReminderAlarms.cancel(app, it) }
            reminders.filterNot { it.channelId == channel.id && it.start == p.start }
        } else {
            showNotice("I'll remind you at ${hhmm(p.start)} – ${p.title}")
            val r = Reminder(channel.id, channel.name, p.title, p.start, p.stop)
            ReminderAlarms.schedule(app, r)
            reminders + r
        }
        store.reminders = reminders
    }

    private fun checkReminders() {
        if (dueReminder != null) return
        val t = System.currentTimeMillis()
        val live = reminders.filter { it.stop > t }
        // Followed teams' matches pop up 5 minutes early, so there's time to get settled.
        val teams = football.teams
        val due = live.firstOrNull { r ->
            val lead = if (teams.any { SportFollow.mentions(r.title, it) }) 5 * 60_000L else 60_000L
            t >= r.start - lead
        }
        val remaining = if (due != null) live - due else live
        if (remaining.size != reminders.size) {
            reminders = remaining
            store.reminders = remaining
        }
        if (due != null) {
            dueReminder = due
            sendHa("reminder", ReminderAlarms.payload(due) + ("app_open" to true))
        }
    }

    // ---------------- Recommendations ----------------

    /** 👍 / 👎 on a programme or film: more or fewer like it. */
    fun rate(title: String, genre: String?, like: Boolean) {
        if (like) taste.like(title, genre) else taste.dislike(title, genre)
        store.setTaste(activeProfile.id, taste.toJson())
        tasteVersion++
        showNotice(if (like) "👍 Got it – more like \"$title\"" else "👎 Got it – fewer like \"$title\"")
        refreshHomeFeed()
    }

    fun rateProgramme(channel: Channel, p: Programme, like: Boolean) = rate(p.title, Genres.of(p.category, p.title, channel.group), like)

    /** What Tuner has learned, for Settings › Recommendations. */
    fun tasteSummary(): Triple<List<Pair<String, Double>>, List<String>, Int> =
        Triple(taste.genreShares().take(8), taste.topShows(10), taste.liked.size + taste.disliked.size)

    fun resetTaste() {
        taste = Taste()
        store.setTaste(activeProfile.id, taste.toJson())
        tasteVersion++
        refreshHomeFeed()
        showNotice("Recommendations reset – Flicker will learn again from what you watch")
    }

    fun toggleLearnTaste() {
        learnTaste = !learnTaste
        store.learnTaste = learnTaste
    }

    /** Remembers what was just watched, for "Because you watched…". */
    private fun rememberWatched(kind: String, id: String, title: String, extra: String = "") {
        store.setLastWatchedFor(activeProfile.id, org.json.JSONObject().put("kind", kind).put("id", id).put("title", title).put("extra", extra).toString())
    }

    /** Fills "Because you watched…": Plex's own "more like this", else films from your provider in the same category. */
    fun loadBecause() {
        val o = runCatching { org.json.JSONObject(store.lastWatchedFor(activeProfile.id) ?: return) }.getOrNull() ?: return
        val title = o.optString("title")
        when (o.optString("kind")) {
            "plex" -> if (plexLinked) plexFeature.loadSimilar(o.optString("extra"), o.optString("id"), title)
            "film" -> {
                val list = movies ?: run {
                    if (hasVod) loadMovies()
                    return
                }
                val category = list.firstOrNull { it.id == o.optString("id") }?.category ?: o.optString("extra")
                becauseFilms = list.filter { it.category == category && it.id != o.optString("id") && it.id !in resume.keys }
                    .sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
                    .take(20)
                becauseFilmOf = title
            }
        }
    }

    // ---------------- VPN ----------------

    /** Kill switch: with the VPN on but not connected, your provider's streams don't play. */
    private fun vpnBlocks(): Boolean {
        if (!vpn.enabled || !vpn.killSwitch || vpn.connected) return false
        showNotice("🔒 The VPN isn't connected, so streams are paused (kill switch) – Settings › VPN")
        if (!vpn.connected) vpn.connect()
        return true
    }

    // ---------------- Trending ----------------

    /** This week's trending list from TMDB, matched against your Plex and your provider's films and series (every 6 hours). */
    fun loadTrending(force: Boolean = false) {
        val key = tmdbKey ?: return
        val t = System.currentTimeMillis()
        if (!force && t - trendingAt < 6 * 3_600_000L && trending.isNotEmpty()) return
        trendingAt = t
        val films = movies.orEmpty()
        val shows = series.orEmpty()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { com.titanejstudios.tuner.integrations.Tmdb.trending(key) }.getOrDefault(emptyList()) }
            if (list.isEmpty()) return@launch
            val plain = { s: String -> s.lowercase().replace(Regex("^(the|a|an) "), "").replace(Regex("[^\\p{L}\\p{N}]"), "") }
            val filmIndex = withContext(Dispatchers.Default) {
                films.groupBy { m -> FilmName.parse(m.name).let { plain(it.first) } }
            }
            val seriesIndex = withContext(Dispatchers.Default) {
                shows.groupBy { s -> FilmName.parse(s.name).let { plain(it.first) } }
            }
            val out = list.take(20).map { tr ->
                val p = plain(tr.title)
                val plex = if (!plexLinked) null
                else if (tr.isTv) withTimeoutOrNull(4_000) { plexFeature.showNamed(tr.title) }
                else withTimeoutOrNull(4_000) { plexFeature.copiesOf(tr.title, tr.year) }?.firstOrNull()
                val film = if (tr.isTv) null else filmIndex[p]?.let { c ->
                    c.firstOrNull { FilmName.parse(it.name).second == tr.year } ?: c.firstOrNull { FilmName.parse(it.name).second == null }
                }
                val show = if (tr.isTv) seriesIndex[p]?.firstOrNull() else null
                TrendingItem(tr, plex?.takeIf { !activeProfile.kids || plexFeature.isKidsEntry(it) }, film, show)
            }
            trending = out
            // IMDb's score for each (cached a month), so the tiles match IMDb rather than TMDB's own.
            requestRatings(out.filter { !it.trend.isTv }.map { trendName(it.trend) }, false)
            requestRatings(out.filter { it.trend.isTv }.map { trendName(it.trend) }, true)
        }
    }

    /** The name a trending title is looked up (and cached) by. */
    fun trendName(t: com.titanejstudios.tuner.integrations.Tmdb.Trend): String = t.title + (t.year?.let { " ($it)" } ?: "")

    /**
     * "IMDb 7.9" when Flicker has IMDb's score (looked up, or from Plex), otherwise TMDB's labelled
     * ("TMDB 8.5") – never a bare star that could be either.
     */
    fun trendScore(item: TrendingItem): String? {
        val t = item.trend
        vodRatings[ratingKey(trendName(t), t.isTv)]?.takeIf { it.value > 0.0 }?.let { r ->
            return (if (r.source == "IMDb") "IMDb " else "TMDB ") + String.format(Locale.UK, "%.1f", r.value)
        }
        item.plex?.rating?.takeIf { it.startsWith("IMDb") }?.let { return it }
        return com.titanejstudios.tuner.integrations.Tmdb.ratingText(t.rating)
    }

    /** OK on a trending tile: play it where you have it, otherwise show its info. */
    fun openTrending(item: TrendingItem) {
        when {
            item.plex != null -> if (item.plex.isShow) openPlexShow(item.plex) else playPlexEntry(item.plex)
            item.film != null -> playMovie(item.film, fromStart = false)
            item.series != null -> openSeriesDetail(item.series)
            else -> openFilmInfo(item.trend.title + (item.trend.year?.let { " ($it)" } ?: ""), isSeries = item.trend.isTv)
        }
    }

    // ---------------- Languages & countries ----------------

    private fun languageShown(code: String?, hidden: Set<String>): Boolean = (code ?: Languages.NONE) !in hidden

    /** Films/series without the hidden languages ([text] = category and name, where the tags are). */
    private fun <T> langFilter(list: List<T>, text: (T) -> Pair<String, String>): List<T> {
        val hidden = hiddenLanguages
        if (hidden.isEmpty()) return list
        return list.filter { val (cat, name) = text(it); languageShown(Languages.tagOfItem(cat, name)?.code, hidden) }
    }

    /** Every country/language found in your channels (and films/series, if loaded), most first. */
    fun languageCounts(): List<Pair<Languages.Tag?, Int>> {
        val counts = HashMap<String, Pair<Languages.Tag?, Int>>()
        fun add(tag: Languages.Tag?) {
            val k = tag?.code ?: Languages.NONE
            counts[k] = tag to ((counts[k]?.second ?: 0) + 1)
        }
        allChannels.forEach { add(Languages.tagOf(it)) }
        return counts.values.sortedByDescending { it.second }
    }

    fun changeHiddenLanguages(hidden: Set<String>) {
        hiddenLanguages = hidden
        store.hiddenLanguages = hidden
        recomputeVisible()
        // Films and series are filtered as they load: load them again from the saved lists.
        movies = null
        series = null
    }

    /** Hides every country/language that isn't English (things with no country tag stay). */
    fun englishOnly() {
        // Every non-English tag, including ones only used by films and series.
        changeHiddenLanguages(Languages.nonEnglish)
        showNotice("Showing English channels only – change it in Settings › Languages & countries")
    }

    // ---------------- Home rows (5.5) ----------------

    /** Each profile has its own Home rows (the main profile's are also everyone's starting point). */
    fun saveHomeRows(order: List<String>, hidden: Set<String>) {
        homeRowOrder = order
        homeRowsHidden = hidden
        store.setProfileHomeRows(activeProfile.id, order, hidden)
        if (activeProfile.id == Store.MAIN_PROFILE) {
            store.homeRowOrder = order
            store.homeRowsHidden = hidden
        }
    }

    private fun loadHomeRows() {
        val mine = store.profileHomeRows(activeProfile.id)
        homeRowOrder = mine?.first ?: store.homeRowOrder
        homeRowsHidden = mine?.second ?: store.homeRowsHidden
    }

    fun toggleHomeSmart() {
        homeSmart = !homeSmart
        store.homeSmart = homeSmart
    }

    fun allChannelsById(id: String): Channel? = allChannels.firstOrNull { it.id == id }

    /** Home's 🎤 button: opens Search and starts voice typing straight away. */
    fun startVoiceSearch() {
        voiceSearchPending = true
        navigate(Screen.Search)
    }

    /** The channel watched last (Home's ▶ button). */
    val lastChannel: Channel? get() = recents.firstNotNullOfOrNull { id -> allChannels.firstOrNull { it.id == id } }

    // ---------------- Start from the beginning (joined late) ----------------


    /** After a few seconds on a programme that started a while ago, mentions it can be watched from the start. */
    private fun offerStartOver() {
        val m = mode as? PlayMode.Live
        if (m == null || screen != Screen.Player || inPip) {
            liveSinceId = null
            return
        }
        val t = System.currentTimeMillis()
        if (liveSinceId != m.channel.id) {
            liveSinceId = m.channel.id
            liveSince = t
            return
        }
        if (t - liveSince < 10_000) return
        val cur = nowNext(m.channel.id, t).first ?: return
        if (t - cur.start < 5 * 60_000L || cur.stop - t < 10 * 60_000L) return
        if (!startOverOffered.add(m.channel.id + "@" + cur.start)) return
        if (!canCatchup(m.channel, cur)) return
        showNotice("Joined \"${cur.title}\" late? Press ⏪ (or OK › Watch from the start) to see it from the beginning")
    }

    /** ⏪ on live TV: start the current programme from the beginning, if it has catch-up. */
    fun startOverCurrent(): Boolean {
        val m = mode as? PlayMode.Live ?: return false
        val cur = nowNext(m.channel.id, System.currentTimeMillis()).first ?: return false
        if (!canCatchup(m.channel, cur)) return false
        playCatchup(m.channel, cur, zapChannels)
        return true
    }

    fun acceptReminder() {
        val r = dueReminder ?: return
        dueReminder = null
        val ch = allChannels.firstOrNull { it.id == r.channelId }
        if (ch == null) {
            showNotice("${r.channelName} isn't in the current playlist")
            return
        }
        if (screen != Screen.Player) play(ch, channelsFor(ALL)) else playFromOverlay(ch, channelsFor(ALL))
    }

    fun dismissReminder() {
        dueReminder = null
    }

    // ---------------- Search ----------------


    // ---------------- Fix guide matches ----------------



    // ---------------- Films & series ----------------

    /**
     * Films: shows the saved list straight away, then downloads a fresh one in the background if
     * it's over 12 hours old (or you pressed Reload). Only downloads with a wait the very first time.
     */
    fun loadMovies(force: Boolean = false) {
        if (movies != null && !force) return
        val srcs = activeSources().filterIsInstance<Source.Xtream>()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { srcs.map { it to vodCache.loadMovies(it.id) } }
            val complete = cached.all { it.second != null }
            if (complete && movies == null) movies = langFilter(cached.flatMap { it.second!!.items }) { it.category to it.name }
            val stale = cached.any { it.second == null || System.currentTimeMillis() - it.second!!.savedAt > VodCache.MAX_AGE_MS }
            if (!force && complete && !stale) return@launch
            if (complete) vodUpdating = true else vodStatus = "Loading films…"
            try {
                movies = withContext(Dispatchers.IO) {
                    langFilter(srcs.flatMap { src -> XtreamClient.loadMovies(src).also { runCatching { vodCache.saveMovies(src.id, it) } } }) { it.category to it.name }
                }
            } catch (e: Exception) {
                if (!complete) movies = emptyList()
                showNotice("Couldn't load films: ${e.message}")
            }
            vodStatus = null
            vodUpdating = false
        }
    }

    /** Series: same saved-list approach as [loadMovies]. */
    fun loadSeries(force: Boolean = false) {
        if (series != null && !force) return
        val srcs = activeSources().filterIsInstance<Source.Xtream>()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { srcs.map { it to vodCache.loadSeries(it.id) } }
            val complete = cached.all { it.second != null }
            if (complete && series == null) series = langFilter(cached.flatMap { it.second!!.items }) { it.category to it.name }
            val stale = cached.any { it.second == null || System.currentTimeMillis() - it.second!!.savedAt > VodCache.MAX_AGE_MS }
            if (!force && complete && !stale) return@launch
            if (complete) vodUpdating = true else vodStatus = "Loading series…"
            try {
                series = withContext(Dispatchers.IO) {
                    langFilter(srcs.flatMap { src -> XtreamClient.loadSeries(src).also { runCatching { vodCache.saveSeries(src.id, it) } } }) { it.category to it.name }
                }
            } catch (e: Exception) {
                if (!complete) series = emptyList()
                showNotice("Couldn't load series: ${e.message}")
            }
            vodStatus = null
            vodUpdating = false
        }
    }

    // ---------------- My list & new episodes ----------------

    fun inWatchlist(id: String): Boolean = watchlist.any { it.id == id }

    fun toggleWatchlist(m: VodItem) {
        toggleWatchItem(WatchItem.of(m, System.currentTimeMillis()))
    }

    fun toggleWatchlist(s: SeriesItem) {
        toggleWatchItem(WatchItem.of(s, System.currentTimeMillis()))
    }

    private fun toggleWatchItem(item: WatchItem) {
        val adding = !inWatchlist(item.id)
        watchlist = Watchlist.toggle(watchlist, item)
        store.watchlistJson = Watchlist.encode(watchlist)
        markChanged(TvSync.WATCHLIST)
        showNotice(if (adding) "Added ${item.name} to My list" else "Removed ${item.name} from My list")
    }

    /** Plays a film or opens a series from My list / New episodes. */
    fun openWatchItem(item: WatchItem) {
        if (item.isSeries) {
            val s = item.toSeries() ?: return
            if (sources.none { it.id == s.sourceId }) {
                showNotice("That series' playlist has been removed")
                return
            }
            openSeriesDetail(s)
        } else {
            val m = item.toVod() ?: return
            playMovie(m, fromStart = (resume[m.id]?.positionMs ?: 0L) == 0L)
        }
    }

    /** Series you watch that have episodes you haven't seen in the list yet. */
    val newEpisodeSeries: List<FollowedSeries>
        get() = followedSeries.filter { it.newEpisodes > 0 }

    private fun saveFollowed(list: List<FollowedSeries>) {
        followedSeries = list
        store.followedSeriesJson = Watchlist.encodeFollowed(list)
        markChanged(TvSync.FOLLOWED)
    }

    /**
     * Once a day (a few seconds after start-up) counts the episodes of the series you've been
     * watching, one gentle request at a time. More than last time = "New episodes" on Home.
     */
    fun checkNewEpisodes(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Every 6 hours (Flicker can stay open for days – the guide check calls this too).
        if (!force && now - store.newEpisodesCheckedAt < 6 * 3600_000L) return
        refreshComingSoon()
        val follow = followedSeries
        if (follow.isEmpty() || activeProfile.kids) return
        val xtream = sources.filterIsInstance<Source.Xtream>().associateBy { it.id }
        store.newEpisodesCheckedAt = now
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                follow.mapNotNull { f ->
                    val srcId = f.series.sourceId ?: return@mapNotNull null
                    val src = xtream[srcId] ?: return@mapNotNull null
                    val sid = f.series.seriesId ?: return@mapNotNull null
                    runCatching { f.series.id to XtreamClient.loadEpisodes(src, sid).size }.getOrNull()
                        .also { Thread.sleep(300) }
                }.toMap()
            }
            if (counts.isEmpty()) return@launch
            // Merge with whatever changed while we were checking.
            val updated = followedSeries.map { f -> counts[f.series.id]?.let { Watchlist.withCount(f, it) } ?: f }
            saveFollowed(updated)
            val fresh = updated.count { it.newEpisodes > 0 }
            if (fresh > 0) AppLog.info("New episodes found for $fresh series")
        }
    }

    fun openSeriesDetail(s: SeriesItem) {
        if (activeProfile.kids && activeProfile.maxCertificate.isNotBlank()) {
            checkAgeRating(s.name, isSeries = true) { openSeriesDetailChecked(s) }
            return
        }
        openSeriesDetailChecked(s)
    }

    private fun openSeriesDetailChecked(s: SeriesItem) {
        openSeries = s
        episodes = null
        navigate(Screen.SeriesDetail)
        val src = sources.firstOrNull { it.id == s.sourceId } as? Source.Xtream ?: return
        viewModelScope.launch {
            episodes = try {
                withContext(Dispatchers.IO) { XtreamClient.loadEpisodes(src, s.seriesId) }
            } catch (e: Exception) {
                showNotice("Couldn't load episodes: ${e.message}")
                emptyList()
            }
            // Opening a series you follow counts as having seen what's new.
            val count = episodes?.size ?: 0
            val f = followedSeries.firstOrNull { it.series.id == s.id }
            if (f != null && count > 0 && (f.newEpisodes > 0 || f.episodeCount != count)) {
                saveFollowed(followedSeries.map { if (it.series.id == s.id) it.copy(episodeCount = count, newEpisodes = 0) else it })
            }
        }
    }

    fun playMovie(m: VodItem, fromStart: Boolean, viaPlex: Boolean = true, ageChecked: Boolean = false) {
        // Kids with an age limit: check the film's certificate first.
        if (!ageChecked && activeProfile.kids && activeProfile.maxCertificate.isNotBlank()) {
            checkAgeRating(m.name, isSeries = false) { playMovie(m, fromStart, viaPlex, ageChecked = true) }
            return
        }
        rememberWatched("film", m.id, m.name, m.category)
        // Also on Plex? Plex's copy is usually better (and its progress is shared with the Plex app).
        // "☆ Always use your IPTV provider": straight there, no Plex lookup.
        if (viaPlex && plexLinked && preferPlexFilms && preferredCopy != "iptv") {
            viewModelScope.launch {
                val (title, year) = FilmName.parse(m.name)
                showNotice("Starting ${m.name}…")
                // The lookup runs on its own, so a slow Plex server can't hold the film up for more than 5 seconds.
                val lookup = viewModelScope.async { plexFeature.copiesOf(title, year) }
                val copies = withTimeoutOrNull(5_000) { lookup.await() }.orEmpty()
                    .filter { !activeProfile.kids || plexFeature.isKidsEntry(it) }
                when {
                    copies.isEmpty() -> playMovie(m, fromStart, viaPlex = false, ageChecked = true)
                    copies.size == 1 -> {
                        showNotice("Playing from Plex" + (copies[0].library?.let { " ($it)" } ?: "") + " – hold OK on the film for the IPTV copy")
                        playPlexEntry(copies[0], fromStart, chosen = true, fallback = { playMovie(m, fromStart, viaPlex = false) })
                    }
                    else -> {
                        // You've said which you prefer: no need to ask.
                        val pick = preferredPick(copies, hasIptv = true)
                        when {
                            pick == "iptv" -> playMovie(m, fromStart, viaPlex = false)
                            pick != null -> copies.first { copyId(it) == pick }.let { c ->
                                showNotice("Playing from Plex" + (c.library?.let { " ($it)" } ?: "") + " – your usual choice")
                                playPlexEntry(c, fromStart, chosen = true, fallback = { playMovie(m, fromStart, viaPlex = false) })
                            }
                            else -> plexCopyChoice = PlexCopyChoice(m.name, copies, fromStart, m)
                        }
                    }
                }
            }
            return
        }
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(m.id, m.name, m.category, m.poster, m.url, null), fromStart)
    }

    fun playEpisode(e: Episode, all: List<Episode>, fromStart: Boolean) {
        rememberUnderPlayer("ep|${e.id}")
        val s = openSeries
        if (s != null) {
            saveFollowed(Watchlist.watched(followedSeries, WatchItem.of(s, System.currentTimeMillis()), all.size.takeIf { it > 0 }, System.currentTimeMillis()))
        }
        episodeQueue = all.dropWhile { it.id != e.id }.drop(1)
        startVod(
            PlayMode.Vod(
                contentId = e.id,
                title = s?.name ?: e.title,
                subtitle = "S${e.season} E${e.number}  ${e.title}",
                poster = e.image ?: s?.cover,
                url = e.url,
                seriesId = s?.id,
            ),
            fromStart,
        )
    }

    fun playResume(entry: ResumeEntry) {
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(entry.contentId, entry.title, entry.subtitle, entry.poster, entry.url, entry.seriesId), false)
    }

    private fun startVod(m: PlayMode.Vod, fromStart: Boolean, startAt: Long? = null) {
        com.titanejstudios.tuner.data.SafeMode.doing = "Watching ${m.title}" + (m.subtitle?.let { " $it" } ?: "")
        // Downloads play from the phone itself, so the VPN kill switch doesn't apply.
        if (!m.url.startsWith("file:") && vpnBlocks()) {
            AppLog.info("Didn't play ${m.title}: the VPN kill switch is on and the VPN isn't connected")
            return
        }
        AppLog.info("Playing ${m.title}" + (m.subtitle?.let { " ($it)" } ?: ""))
        if (!m.contentId.startsWith("plex:")) markStarted("v:" + m.contentId, m.seriesId?.let { "s:$it" })
        if (mode is PlayMode.Vod) saveResume(syncHome = false)
        mode = m
        val start = if (fromStart) 0L else startAt ?: resume[m.contentId]?.positionMs ?: 0L
        val meta = MediaMetadata.Builder().setTitle(m.title).setArtist(m.subtitle)
        m.poster?.let { meta.setArtworkUri(Uri.parse(it)) }
        playback.play(m.url, live = false, startPositionMs = start, metadata = meta.build())
        maybeRecap(m, start)
        sendHa("playing", mapOf("kind" to if (m.seriesId != null) "episode" else "film", "title" to m.title, "subtitle" to m.subtitle, "poster" to m.poster))
        navigate(Screen.Player)
    }

    internal fun saveResume(syncHome: Boolean) {
        val m = mode as? PlayMode.Vod ?: return
        if (m.contentId.startsWith("plex:")) {
            // Plex keeps its own place – tell it, rather than Tuner's list.
            val item = plexPlaying ?: return
            plexFeature.reportProgress(item, stopped = syncHome, positionMs = playback.positionMs, durationMs = playback.durationMs)
            return
        }
        val pos = playback.positionMs
        val dur = playback.durationMs
        if (dur <= 0) return
        val updated = resume.toMutableMap()
        if (pos > dur * 95 / 100) {
            updated.remove(m.contentId)
            com.titanejstudios.tuner.integrations.LinkSync.markDone(store, m.contentId)
            logToTrakt(m)
        }
        else if (pos > 30_000) {
            updated[m.contentId] = ResumeEntry(
                m.contentId, m.title, m.subtitle, m.poster, m.url, pos, dur, System.currentTimeMillis(), m.seriesId,
            )
        }
        resume = updated
        store.resume = updated
        if (syncEnabled) scheduleSync(if (syncHome) 5_000L else 120_000L)
        if (syncHome) {
            val entries = updated.values.toList()
            viewModelScope.launch(Dispatchers.IO) { HomeScreen.syncContinueWatching(app, store, entries) }
            link.tellOthers()
            cloud.syncSoon()
        }
    }

    private fun onPlaybackEnded() {
        when (val m = mode) {
            is PlayMode.Vod -> {
                resume = resume - m.contentId
                store.resume = resume
                com.titanejstudios.tuner.integrations.LinkSync.markDone(store, m.contentId)
                logToTrakt(m)
                link.tellOthers()
            cloud.syncSoon()
                if (episodeQueue.isNotEmpty()) {
                    showNotice("Playing next episode")
                    startNextEpisode(m)
                } else if (m.contentId.startsWith("plex:") && plexEpisodeQueue.isNotEmpty()) {
                    val next = plexEpisodeQueue.first()
                    val rest = plexEpisodeQueue.drop(1)
                    showNotice("Playing next episode")
                    playPlexEntry(next, fromStart = true)
                    plexEpisodeQueue = rest
                } else if (m.contentId.startsWith("plex:") && plexPlaying?.ratingKey == m.contentId.removePrefix("plex:")) {
                    // Started from Home (Continue watching, Recently added): ask Plex what's next.
                    val item = plexPlaying!!
                    showNotice("Finding the next episode…")
                    viewModelScope.launch {
                        val next = withTimeoutOrNull(10_000) { plexFeature.nextEpisode(item) }
                        if (mode !== m) return@launch // something else was started meanwhile
                        if (next == null) {
                            if (screen == Screen.Player) back()
                            return@launch
                        }
                        val (entry, show) = next
                        showNotice("Playing next episode")
                        playPlexEntry(entry, fromStart = true, showTitle = show.ifBlank { null })
                    }
                } else {
                    if (screen == Screen.Player) back()
                    offerNextInSeries(m)
                }
            }
            is PlayMode.CatchupPlay -> showNotice("End of programme – press BACK, or choose Live from the menu")
            is PlayMode.RecordingPlay -> {
                if (screen == Screen.Player) back()
            }
            else -> Unit
        }
    }

    // ---------------- Recordings ----------------


    val recordingDir: String
        get() = RecordingPaths.dir(app, store)










    // ---------------- Recording tidy-up and network drive ----------------







    val nasUser: String? get() = store.nasUser


    // ---------------- Backup ----------------





    // ---------------- Phone setup & phone remote (one small web server) ----------------





    override fun channelsJson(): String {
        val arr = org.json.JSONArray()
        val t = System.currentTimeMillis()
        channelsFor(ALL).take(4000).forEachIndexed { i, c ->
            val (cur, _) = nowNext(c.id, t)
            arr.put(
                org.json.JSONObject().put("id", c.id).put("n", displayNumber(c, i)).put("name", c.name)
                    .put("group", c.group).put("logo", c.logo).put("now", cur?.title)
            )
        }
        return arr.toString()
    }

    override fun stateJson(): String {
        val o = org.json.JSONObject()
        when (val m = mode) {
            is PlayMode.Live -> {
                val p = nowNext(m.channel.id, System.currentTimeMillis()).first
                o.put("title", m.channel.name).put("detail", (p?.title ?: "Live") + (if (playback.isPaused) " · paused" else ""))
                // For the phone's "About this": what's on, and whether it's a film.
                if (p != null) o.put("what", p.title).put("desc", p.desc ?: "")
                    .put("series", Genres.of(p.category, p.title, m.channel.group) != "Film")
            }
            is PlayMode.CatchupPlay -> o.put("title", m.programme.title).put("detail", "Catch-up · ${m.channel.name}")
                .put("what", m.programme.title).put("desc", m.programme.desc ?: "")
                .put("series", Genres.of(m.programme.category, m.programme.title, m.channel.group) != "Film")
            is PlayMode.Vod -> o.put("title", m.title).put("detail", m.subtitle ?: "Film")
                .put("what", m.title).put("series", m.seriesId != null)
            is PlayMode.RecordingPlay -> o.put("title", m.recording.title).put("detail", "Recording · ${m.recording.channelName}")
            null -> o.put("title", "Flicker").put("detail", "Nothing playing · ${screen.name}")
        }
        return o.toString()
    }

    override fun key(code: Int) {
        // -1 = "Home" from the phone remote (Android doesn't let an app press the real Home button).
        if (code == -1) {
            resetTo(Screen.Home)
            return
        }
        remoteKeys.tryEmit(code)
    }

    override fun play(channelId: String) {
        val all = channelsFor(ALL)
        val ch = all.firstOrNull { it.id == channelId } ?: return
        if (screen == Screen.Player && mode is PlayMode.Live) playFromOverlay(ch, all) else {
            resetTo(Screen.Home)
            play(ch, all)
        }
    }

    override fun searchJson(query: String): String {
        val q = query.trim().lowercase()
        val arr = org.json.JSONArray()
        if (q.length < 2) return arr.toString()
        val chans = allowedAll
        val guide = epg
        val recs = recordings
        val t = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.UK)
        val found = ArrayList<Pair<Channel, Programme>>()
        for (c in chans) {
            for (p in guide[c.id].orEmpty()) {
                if (p.stop > t && p.title.lowercase().contains(q)) found += c to p
            }
            if (found.size > 400) break
        }
        found.sortBy { it.second.start }
        found.distinctBy { it.second.title.lowercase() + "|" + it.second.start }.take(60).forEach { (c, p) ->
            arr.put(
                org.json.JSONObject().put("id", c.id).put("ch", c.name).put("title", p.title).put("start", p.start)
                    .put("when", fmt.format(java.util.Date(p.start)))
                    .put("rec", recs.any { it.channelId == c.id && it.start < p.stop && it.stop > p.start && it.status != RecStatus.FAILED })
            )
        }
        return arr.toString()
    }

    override fun record(channelId: String, start: Long, series: Boolean): String {
        if (activeProfile.kids) return "Recording is off for kids profiles"
        val ch = byId[channelId] ?: return "Channel not found"
        val p = epg[channelId]?.firstOrNull { it.start == start } ?: return "Programme not found"
        return if (series) {
            if (seriesLinkFor(ch, p) == null) toggleSeriesLink(ch, p)
            "Series link set: every ${p.title} on ${ch.name}"
        } else {
            if (recordingFor(ch, p) == null) recordProgramme(ch, p)
            "Recording ${p.title} on ${ch.name}"
        }
    }

    override fun installApk(file: File) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { com.titanejstudios.tuner.apps.Updater.install(app, file) }.exceptionOrNull() }
            if (error != null) showNotice("Update failed: ${error.message}")
        }
    }

    // ---------------- Fast switching, timeshift, health settings ----------------

    private fun scheduleStandby(current: Channel) {
        standbyJob?.cancel()
        if (!fastSwitch) return
        standbyJob = viewModelScope.launch {
            delay(4_000) // let the current channel settle first
            if (zapList.size < 2) return@launch
            val idx = zapList.indexOfFirst { it.id == current.id }
            if (idx < 0) return@launch
            val next = zapList[(idx + lastZapDirection).mod(zapList.size)]
            playback.prepareStandby(next.url)
        }
    }

    fun toggleFastSwitch() {
        fastSwitch = !fastSwitch
        store.fastSwitch = fastSwitch
        playback.fastSwitch = fastSwitch
        playing?.let { scheduleStandby(it) }
    }

    // ---------------- Sleep timer ----------------

    /** [minutes] > 0 sets a timer, 0 turns it off, -1 = at the end of what's on now. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val t = System.currentTimeMillis()
        sleepAt = when {
            minutes == 0 -> 0L
            minutes < 0 -> playing?.let { nowNext(it.id, t).first?.stop } ?: (t + 60 * 60_000L)
            else -> t + minutes * 60_000L
        }
        if (sleepAt == 0L) {
            showNotice("Sleep timer off")
            return
        }
        showNotice("Flicker will stop at ${hhmm(sleepAt)}")
        sleepJob = viewModelScope.launch {
            val warnAt = sleepAt - 60_000L
            if (warnAt > System.currentTimeMillis()) {
                delay(warnAt - System.currentTimeMillis())
                showNotice("Sleep timer: stopping in 1 minute (set it again to keep watching)")
            }
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            sleepAt = 0L
            if (mode is PlayMode.Vod) saveResume(syncHome = true)
            stopPlayback()
            goHome()
            showNotice("Sleep timer – stopped")
            // Lets Home Assistant turn the TV off too.
            sendHa("sleep_timer")
        }
    }

    val sleepLabel: String
        get() = if (sleepAt == 0L) "Off" else "Stops at ${hhmm(sleepAt)}"

    // ---------------- Picture size, lip sync, languages ----------------

    /** For the video surface: 0 = fit, 4 = zoom (fill, crop edges), 3 = stretch. */
    val pictureMode: Int
        get() = (playing?.let { pictureModes[it.id] } ?: pictureModes["*"])?.toIntOrNull() ?: 0

    val pictureModeLabel: String
        get() = when (pictureMode) {
            4 -> "Zoom"
            3 -> "Stretch"
            else -> "Normal"
        }

    /** Cycles Normal → Zoom → Stretch for this channel (or for films/recordings when no channel). */
    fun cyclePictureMode() {
        val next = when (pictureMode) {
            0 -> 4
            4 -> 3
            else -> 0
        }
        val key = playing?.id ?: "*"
        pictureModes = if (next == 0) pictureModes - key else pictureModes + (key to next.toString())
        store.pictureModes = pictureModes
        showNotice("Picture: $pictureModeLabel" + if (playing != null) " (remembered for ${playing?.name})" else "")
    }

    val lipSyncMs: Long
        get() = (playing?.let { lipSyncMap[it.id] } ?: lipSyncMap["*"])?.toLongOrNull() ?: 0L

    fun lipSyncLabel(ms: Long = lipSyncMs): String = when {
        ms == 0L -> "In sync"
        ms > 0 -> "Sound ${ms} ms later"
        else -> "Sound ${-ms} ms earlier"
    }

    /** Sets the sound offset for the channel being watched (or every channel). */
    fun setLipSync(ms: Long, allChannels: Boolean = false) {
        val key = if (allChannels) "*" else playing?.id ?: "*"
        lipSyncMap = if (ms == 0L) lipSyncMap - key else lipSyncMap + (key to ms.toString())
        store.lipSync = lipSyncMap
        showNotice(lipSyncLabel(ms) + if (key == "*") " (all channels)" else "")
    }

    val languages = listOf(
        null to "Stream's default", "en" to "English", "fr" to "French", "de" to "German", "es" to "Spanish",
        "it" to "Italian", "pt" to "Portuguese", "pl" to "Polish", "nl" to "Dutch", "ar" to "Arabic", "tr" to "Turkish",
    )

    fun languageName(code: String?) = languages.firstOrNull { it.first == code }?.second ?: code ?: "Stream's default"

    // Each profile has its own sound and subtitle language, and audio description.

    private fun loadLanguages() {
        val pid = activeProfile.id
        audioLanguage = store.audioLanguageOf(pid)
        subtitleLanguage = store.subtitleLanguageOf(pid)
        audioDescription = store.audioDescriptionOf(pid)
        playback.setLanguages(audioLanguage, subtitleLanguage, audioDescription)
    }

    fun cycleAudioLanguage() {
        val i = languages.indexOfFirst { it.first == audioLanguage }
        audioLanguage = languages[(i + 1).mod(languages.size)].first
        store.saveAudioLanguage(activeProfile.id, audioLanguage)
        playback.setLanguages(audioLanguage, subtitleLanguage, audioDescription)
    }

    fun cycleSubtitleLanguage() {
        val i = languages.indexOfFirst { it.first == subtitleLanguage }
        subtitleLanguage = languages[(i + 1).mod(languages.size)].first
        store.saveSubtitleLanguage(activeProfile.id, subtitleLanguage)
        playback.setLanguages(audioLanguage, subtitleLanguage, audioDescription)
    }

    fun toggleAudioDescription() {
        audioDescription = !audioDescription
        store.saveAudioDescription(activeProfile.id, audioDescription)
        playback.setLanguages(audioLanguage, subtitleLanguage, audioDescription)
        showNotice(if (audioDescription) "Audio description will be picked when there is one" else "Audio description off")
    }

    // ---------------- Catch-up page ----------------

    /**
     * Channels with catch-up. Looks at every copy (the 720p copy often has catch-up when the 1080p
     * one doesn't), then combines copies as the TV Guide does.
     */
    /** Any channel with catch-up (for the Catch-up tab along the top). */
    val hasCatchupChannels: Boolean get() = channels.any { it.hasCatchup }

    val catchupChannels: List<Channel>
        get() {
            val withCatchup = channels.filter { it.hasCatchup && isAllowed(it) }
            return if (mergeVersions) ChannelMerge.merge(withCatchup, preferredVersions).channels else withCatchup
        }

    /** Programmes on [channel] that can be watched again, newest first, grouped by day label. */
    fun catchupProgrammes(channel: Channel): List<Programme> {
        val t = System.currentTimeMillis()
        return epg[channel.id].orEmpty().filter { Catchup.isAvailable(channel, it, t) }.sortedByDescending { it.start }
    }

    // ---------------- Look & sound ----------------

    /** Changes this profile's colours (each profile keeps its own). */
    fun setTheme(theme: String = themeName, accent: String = themeAccent) {
        themeName = theme
        themeAccent = accent
        store.setProfileTheme(activeProfile.id, theme, accent)
        TunerColors.apply(theme, accent)
    }

    /** The active profile's colours: its own, else the main profile's, else what older versions saved. */
    private fun loadTheme() {
        val saved = store.profileTheme(activeProfile.id) ?: store.profileTheme(Store.MAIN_PROFILE)
        val (theme, accent) = saved ?: (TunerColors.themeForOldBackground(store.themeBackground) to store.themeAccent.let { if (it == "sky") "" else it })
        themeName = theme
        themeAccent = accent
        TunerColors.apply(theme, accent)
    }

    fun cycleSubtitleSize() {
        val sizes = listOf(0.8f, 1f, 1.25f, 1.5f, 1.8f)
        val i = sizes.indexOfFirst { kotlin.math.abs(it - subtitleScale) < 0.01f }
        subtitleScale = sizes[(i + 1).mod(sizes.size)]
        store.subtitleScale = subtitleScale
        SubtitleLook.scale = subtitleScale
    }

    val subtitleSizeLabel: String
        get() = when {
            subtitleScale < 0.9f -> "Small"
            subtitleScale < 1.1f -> "Normal"
            subtitleScale < 1.4f -> "Large"
            subtitleScale < 1.6f -> "Extra large"
            else -> "Huge"
        }

    fun toggleSubtitleBox() {
        subtitleBox = !subtitleBox
        store.subtitleBox = subtitleBox
        SubtitleLook.background = subtitleBox
    }

    fun toggleClearSpeech() {
        clearSpeech = !clearSpeech
        store.clearSpeech = clearSpeech
        playback.speechOn = clearSpeech
        showNotice(
            when {
                !clearSpeech -> "Clearer speech off"
                !playback.nightModeSupported -> "Clearer speech isn't supported on this device"
                audioPassthrough -> "Clearer speech on – it needs Audio passthrough off to work"
                else -> "Clearer speech on – voices lifted over music and effects"
            }
        )
    }

    /** Normal → Large → Larger text. */
    fun cycleTextScale() {
        textScale = when (textScale) {
            100 -> 115
            115 -> 130
            else -> 100
        }
        store.textScale = textScale
    }

    val textScaleLabel: String
        get() = when (textScale) {
            115 -> "Large"
            130 -> "Larger"
            else -> "Normal"
        }

    fun toggleNightMode() {
        nightMode = !nightMode
        store.nightMode = nightMode
        playback.nightModeOn = nightMode
        showNotice(
            when {
                !nightMode -> "Night mode off"
                !playback.nightModeSupported -> "Night mode isn't supported on this TV"
                audioPassthrough -> "Night mode on – it needs Audio passthrough off to work"
                else -> "Night mode on – quieter loud bits, clearer speech"
            }
        )
    }

    fun toggleFrameRateMatch() {
        frameRateMatch = !frameRateMatch
        store.frameRateMatch = frameRateMatch
        playback.setSeamlessFrameRate(frameRateMatch)
        showNotice(if (frameRateMatch) "The TV will switch to the video's frame rate (the screen may blank for a second)" else "Frame-rate matching off")
    }

    // ---------------- What's on ----------------

    fun openWhatsOn(genre: String? = null) {
        whatsOnGenre = genre
        navigate(Screen.WhatsOn)
        updateWhatsOn()
    }

    /** The time What's On is showing. */
    val whatsOnAt: Long
        get() = whatsOnMinute?.let { WhatsOn.timeOf(whatsOnDay, it, now) } ?: now

    fun chooseWhatsOnDay(days: Int) {
        whatsOnDay = days.coerceIn(0, 6)
        // Another day: start at 8pm. Today: now.
        whatsOnMinute = if (whatsOnDay == 0) null else (whatsOnMinute ?: 20 * 60)
        updateWhatsOn()
    }

    fun whatsOnNow() {
        whatsOnDay = 0
        whatsOnMinute = null
        updateWhatsOn()
    }

    fun chooseWhatsOnTime(minuteOfDay: Int) {
        whatsOnMinute = minuteOfDay
        updateWhatsOn()
    }

    /** ◀ ▶ on the time: half an hour at a time, across midnight into the next or previous day. */
    fun stepWhatsOnTime(halfHours: Int) {
        val t = System.currentTimeMillis()
        val nowMinute = ((t - WhatsOn.timeOf(0, 0, t)) / 60_000L).toInt()
        val current = whatsOnMinute ?: (nowMinute / 30 * 30)
        var day = whatsOnDay
        var m = current + halfHours * 30
        while (m < 0) { m += 24 * 60; day-- }
        while (m >= 24 * 60) { m -= 24 * 60; day++ }
        if (day < 0 || (day == 0 && m + 30 <= nowMinute)) {
            whatsOnNow()
            return
        }
        if (day > 6) return
        whatsOnDay = day
        whatsOnMinute = m
        updateWhatsOn()
    }

    fun toggleWhatsOnTidy() {
        whatsOnTidy = !whatsOnTidy
        store.whatsOnTidy = whatsOnTidy
        updateWhatsOn()
    }

    fun toggleWhatsOnPreview() {
        whatsOnPreview = !whatsOnPreview
        store.whatsOnPreview = whatsOnPreview
        if (!whatsOnPreview) {
            whatsOnPreviewJob?.cancel()
            if (screen == Screen.WhatsOn && mode is PlayMode.Live) stopPlayback()
        }
    }

    fun updateWhatsOn() {
        val t = System.currentTimeMillis()
        val isNow = whatsOnMinute == null
        val at = if (isNow) t else WhatsOn.timeOf(whatsOnDay, whatsOnMinute ?: 0, t)
        val chans = allowedAll
        val guide = epg
        val favs = favouriteSet
        val genre = whatsOnGenre
        val favOnly = whatsOnFavouritesOnly
        val tidy = whatsOnTidy
        // A copy: viewing keeps updating the real one while this runs.
        val tasteNow = Taste.fromJson(taste.toJson())
        val teams = football.teams
        whatsOnJob?.cancel()
        whatsOnJob = viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { WhatsOn.build(chans, guide, at, isNow, genre, favs, favOnly, tidy, tasteNow, teams) }
            whatsOnResult = r
            val all = r.soon + r.main
            lookUpFilms(all.filter { Genres.of(it.programme.category, it.programme.title, it.channel.group) == "Film" }.take(24).map { it.programme.title })
            lookUpShowPictures(
                all.filter { it.programme.image == null && Genres.of(it.programme.category, it.programme.title, it.channel.group).let { g -> g != "Film" && g != "News" && g != "Sport" } }
                    .map { it.programme.title }
            )
        }
    }

    /** Posters, ratings and running times for the films on What's On (TMDB, if there's a key). */
    fun lookUpFilms(titles: List<String>) {
        val key = tmdbKey ?: return
        val todo = titles.map { it.trim() }.filter { it.isNotEmpty() && filmLookupsAsked.add(it) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            for (title in todo) {
                val info = withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, title, false) }.getOrNull() }
                filmLookups[title] = info
            }
        }
    }

    /** Pictures for TV shows the guide has none for (TMDB, if there's a key): title → wide picture. */
    val showPictures = androidx.compose.runtime.mutableStateMapOf<String, String?>()
    private val showPicturesAsked = HashSet<String>()

    fun lookUpShowPictures(titles: List<String>) {
        val key = tmdbKey ?: return
        val todo = titles.map { it.trim() }.filter { it.isNotEmpty() && showPicturesAsked.add(it) }.take(40)
        if (todo.isEmpty()) return
        viewModelScope.launch {
            for (title in todo) {
                val info = withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, title, true) }.getOrNull() }
                showPictures[title] = info?.backdrop ?: info?.poster
            }
        }
    }

    /** ▶ on a What's On tile: record it (or stop recording). Grown-ups only. */
    fun toggleRecordFromWhatsOn(c: Channel, p: Programme) {
        if (activeProfile.kids) return
        if (p.stop <= System.currentTimeMillis()) return
        val rec = recordingFor(c, p)
        if (rec != null) {
            stopRecording(rec)
            showNotice("Recording cancelled: ${p.title}")
        } else recordProgramme(c, p)
    }

    /**
     * The highlighted show on What's On: after a moment on it, its channel plays in the corner
     * (if it's on now, and the preview is turned on).
     */
    fun whatsOnFocused(c: Channel, p: Programme) {
        whatsOnPreviewJob?.cancel()
        if (!whatsOnPreview || screen != Screen.WhatsOn) return
        val t = System.currentTimeMillis()
        if (p.start > t || p.stop <= t) return
        if (!isAllowed(c) || timeLeftSeconds() <= 0 || isBedtimeNow()) return
        val current = mode
        if (current is PlayMode.Live && current.channel.id == c.id) return
        if (current != null && current !is PlayMode.Live) return
        whatsOnPreviewJob = viewModelScope.launch {
            delay(1_200)
            if (screen == Screen.WhatsOn) startLive(c, preview = true)
        }
    }

    /** OK on a show that's on now: full screen (straight away if it's already in the preview). */
    fun watchFromWhatsOn(c: Channel, list: List<Channel>) {
        whatsOnPreviewJob?.cancel()
        val current = mode
        if (current is PlayMode.Live && current.channel.id == c.id && isAllowed(c)) {
            zapList = list.ifEmpty { listOf(c) }
            addRecent(c.id)
            store.setLastChannel(activeProfile.id, c.id)
            navigate(Screen.Player)
        } else play(c, list)
    }

    // ---------------- TV's Live tab ----------------

    fun cycleLiveTab() {
        liveTabScope = when (liveTabScope) {
            "off" -> "favourites"
            "favourites" -> "all"
            else -> "off"
        }
        store.liveTabScope = liveTabScope
        scheduleLiveSync(0)
    }

    val liveTabLabel: String
        get() = when (liveTabScope) {
            "favourites" -> "Favourites"
            "all" -> "All channels (up to 600)"
            else -> "Off"
        }

    /** Re-copies channels to the TV a little after things change (favourites, a guide update…). */
    fun scheduleLiveSync(delayMs: Long = 20_000L) {
        if (liveTabScope == "off" && delayMs > 0) return
        if (SafeMode.active && delayMs > 0) return
        liveSyncJob?.cancel()
        liveSyncJob = viewModelScope.launch {
            delay(delayMs)
            liveTabStatus = "Updating the TV's channel list…"
            val n = withContext(Dispatchers.IO) {
                inBackgroundPriority { runCatching { LiveChannelSync.sync(app) }.onFailure { AppLog.warn("Live tab update failed", it) }.getOrNull() }
            }
            liveTabStatus = when {
                n == null -> "Couldn't update the TV's channel list – see the problem log"
                liveTabScope == "off" -> null
                else -> "$n channels in the TV's channel list"
            }
        }
    }

    // ---------------- Plex (see features/PlexFeature) ----------------

    /** Starts linking: shows a code to type in at plex.tv/link, then waits for it. */
    fun linkPlex() {
        if (screen != Screen.Plex) navigate(Screen.Plex)
        plexFeature.link()
    }

    fun unlinkPlex() = plexFeature.unlink()

    /** Re-reads Continue watching. */
    fun refreshPlex(force: Boolean = false) {
        if (SafeMode.active && !force) return
        plexFeature.refresh(force)
    }

    fun playPlex(item: Plex.Item, fromStart: Boolean = false, fallback: (() -> Unit)? = null) {
        val url = item.playUrl
        if (url == null) {
            // Search results can leave out the file's address: ask Plex for it.
            viewModelScope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching { Plex(store.plexClientId).playUrlFor(item.server, item.ratingKey) }
                        .onFailure { AppLog.warn("Plex didn't give the file for ${item.title}", it) }
                        .getOrNull()
                }
                when {
                    found != null -> playPlex(
                        Plex.Item(item.ratingKey, item.title, item.subtitle, item.image, found, item.viewOffsetMs, item.durationMs, item.server),
                        fromStart,
                    )
                    fallback != null -> {
                        showNotice("Plex wouldn't play ${item.title} – playing your provider's copy")
                        fallback()
                    }
                    else -> showNotice("Plex wouldn't play ${item.title} – is the Plex server on?")
                }
            }
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = false)
        plexPlaying = item
        markStarted(plexKey(item.title), plexFeature.show?.title?.let { plexKey(it) })
        episodeQueue = emptyList()
        plexEpisodeQueue = emptyList()
        mode = PlayMode.Vod("plex:" + item.ratingKey, item.title, item.subtitle, item.image, url, null)
        val meta = MediaMetadata.Builder().setTitle(item.title).setArtist(item.subtitle)
        playback.play(url, live = false, startPositionMs = if (fromStart) 0L else item.viewOffsetMs, metadata = meta.build())
        (mode as? PlayMode.Vod)?.let { maybeRecap(it, if (fromStart) 0L else item.viewOffsetMs) }
        com.titanejstudios.tuner.data.SafeMode.doing = "Watching ${item.title}" + (item.subtitle?.let { " $it" } ?: "") + " on Plex"
        lookUpPlexPlaying(item)
        sendHa("playing", mapOf("kind" to "plex", "title" to item.title, "subtitle" to item.subtitle))
        navigate(Screen.Player)
    }

    /** Films & TV › Plex: browse your Plex libraries. */
    fun openPlexLibrary() {
        if (!plexLinked) {
            linkPlex()
            return
        }
        if (activeProfile.kids && !kidsHavePlex) {
            showNotice("No Plex libraries are set for kids yet – a grown-up can hold OK on one in Plex to allow it")
            return
        }
        navigate(Screen.PlexLibrary)
        plexFeature.loadSections()
    }

    /** Plays a Plex film or episode. Episodes queue up the rest of the show after them. */
    fun playPlexEntry(
        entry: com.titanejstudios.tuner.integrations.Plex.Entry,
        fromStart: Boolean = false,
        chosen: Boolean = false,
        /** What to play instead if Plex won't give the file (the IPTV copy). */
        fallback: (() -> Unit)? = null,
        ageChecked: Boolean = false,
        /** The show's name for an episode (when the show's page isn't open). */
        showTitle: String? = null,
    ) {
        if (entry.type == "episode") rememberUnderPlayer("pep|${entry.ratingKey}")
        // Kids with an age limit: Plex's own rating for it (else TMDB's).
        if (!ageChecked && activeProfile.kids && activeProfile.maxCertificate.isNotBlank()) {
            val plexCert = com.titanejstudios.tuner.integrations.Tmdb.ukCertificate(entry.contentRating)
            val retry = { playPlexEntry(entry, fromStart, chosen, fallback, ageChecked = true, showTitle = showTitle) }
            if (plexCert != null) checkAgeRatingKnown(entry.title, plexCert, retry)
            else if (entry.type == "movie") checkAgeRating(entry.title + (entry.year?.let { " ($it)" } ?: ""), isSeries = false, then = retry)
            else retry()
            return
        }
        // "Because you watched…": the film, or the show an episode belongs to.
        // The open show page is only this episode's show if the episode is in it (Home can start any).
        val ownShow = plexFeature.show?.takeIf { entry.type == "episode" && plexFeature.showEpisodes.orEmpty().any { it.ratingKey == entry.ratingKey } }
        (if (entry.type == "episode") ownShow else entry)?.let { rememberWatched("plex", it.ratingKey, it.title, it.server.machineId) }
        if (entry.type == "movie" && !chosen) {
            // In Films and Films 4K? Ask which copy to play.
            viewModelScope.launch {
                val lookup = viewModelScope.async { plexFeature.copiesOf(entry.title, entry.year) }
                val copies = withTimeoutOrNull(5_000) { lookup.await() }.orEmpty()
                    .filter { !activeProfile.kids || plexFeature.isKidsEntry(it) }
                val pick = if (copies.size > 1) preferredPick(copies, hasIptv = false) else null
                when {
                    pick != null -> playPlexEntry(copies.first { copyId(it) == pick }, fromStart, chosen = true, fallback = fallback, ageChecked = ageChecked)
                    copies.size > 1 -> plexCopyChoice = PlexCopyChoice(entry.title, copies, fromStart, null)
                    else -> playPlexEntry(entry, fromStart, chosen = true, fallback = fallback, ageChecked = ageChecked)
                }
            }
            return
        }
        val item = entry.toItem(if (entry.type == "episode") showTitle ?: ownShow?.title else null)
        plexShowPlaying = if (entry.type == "episode") ownShow ?: entry.showEntry() else null
        plexShowFor = entry.ratingKey
        playPlex(item, fromStart, fallback)
        if (entry.type == "episode") {
            plexEpisodeQueue = plexFeature.showEpisodes.orEmpty().dropWhile { it.ratingKey != entry.ratingKey }.drop(1)
        }
    }


    /**
     * Plays the copy picked ([copy] null = the IPTV copy). The choice is passed in, because the
     * menu closes (clearing [plexCopyChoice]) before it says which item was picked.
     */
    fun choosePlexCopy(c: PlexCopyChoice, copy: com.titanejstudios.tuner.integrations.Plex.Entry?) {
        plexCopyChoice = null
        if (copy != null) playPlexEntry(copy, c.fromStart, chosen = true, fallback = c.iptv?.let { m -> { playMovie(m, c.fromStart, viaPlex = false) } })
        else c.iptv?.let { playMovie(it, c.fromStart, viaPlex = false) }
    }

    fun cancelPlexCopyChoice() {
        plexCopyChoice = null
    }

    // ---------------- Remember which copy you prefer ----------------

    /** "plex:Films 4K", say – a copy's library (the same film's copies are told apart by library). */
    fun copyId(e: com.titanejstudios.tuner.integrations.Plex.Entry): String = "plex:" + (e.library ?: e.server.name)

    var preferredCopy by mutableStateOf(store.preferredCopy); private set

    /** The copy you always use, if it's one of these ("iptv" or a copyId), else null (ask). */
    private fun preferredPick(copies: List<com.titanejstudios.tuner.integrations.Plex.Entry>, hasIptv: Boolean): String? {
        val p = preferredCopy ?: return null
        if (p == "iptv") return if (hasIptv) p else null
        return copies.firstOrNull { copyId(it) == p }?.let { p }
    }

    /** "Always use this": plays it and remembers. [copy] null = the IPTV copy. */
    fun choosePlexCopyAlways(c: PlexCopyChoice, copy: com.titanejstudios.tuner.integrations.Plex.Entry?) {
        choosePreferredCopy(copy?.let { copyId(it) } ?: "iptv")
        choosePlexCopy(c, copy)
    }

    fun choosePreferredCopy(id: String?) {
        preferredCopy = id
        store.preferredCopy = id
        showNotice(
            when {
                id == null -> "Flicker will ask which copy to play again"
                id == "iptv" -> "Films will play from your IPTV provider without asking (Settings to change)"
                else -> "Films will play from ${id.removePrefix("plex:")} without asking, when it has them (Settings to change)"
            }
        )
    }

    /** A label for Settings. */
    val preferredCopyLabel: String
        get() = when (val p = preferredCopy) {
            null -> "Ask each time"
            "iptv" -> "Your IPTV provider"
            else -> "Plex – " + p.removePrefix("plex:")
        }

    // ---------------- Joined a film part way? Watch it from the start ----------------

    /** "Watch from the beginning?" showing over live TV. */
    var startOver by mutableStateOf<StartOverOffer?>(null); private set
    /** …and they said yes: where to watch it. */
    var startOverChoosing by mutableStateOf(false); private set
    private val startOverAsked = HashSet<String>()
    private var startOverJob: Job? = null

    /**
     * Watching a film on live TV that started a while ago: if it's on your Plex or in your
     * provider's films too, offer to watch it from the beginning (once per showing).
     */
    fun checkStartOver() {
        val m = mode as? PlayMode.Live ?: return
        if (screen != Screen.Player) return
        val t = System.currentTimeMillis()
        val p = nowNext(m.channel.id, t).first ?: return
        if (Genres.of(p.category, p.title, m.channel.group) != "Film") return
        // Only worth asking if a good part's been missed and a good part is left.
        if (t - p.start < 4 * 60_000L || p.stop - t < 20 * 60_000L) return
        if (!startOverAsked.add(m.channel.id + "|" + p.start)) return
        startOverJob?.cancel()
        startOverJob = viewModelScope.launch {
            delay(3_000)
            val (title, year) = FilmName.parse(p.title)
            val kids = activeProfile.kids
            val plex = if (!plexLinked) emptyList() else withTimeoutOrNull(6_000) { plexFeature.copiesOf(title, year) }.orEmpty()
                .filter { !kids || plexFeature.isKidsEntry(it) }
            val plain = { s: String -> s.lowercase().replace(Regex("^(the|a|an) "), "").replace(Regex("[^\\p{L}\\p{N}]"), "") }
            val want = plain(title)
            val film = if (kids || want.isEmpty()) null else withContext(Dispatchers.Default) {
                val matches = movies.orEmpty().filter { plain(FilmName.parse(it.name).first) == want }
                matches.firstOrNull { year != null && FilmName.parse(it.name).second == year } ?: matches.firstOrNull()
            }
            if (plex.isEmpty() && film == null) return@launch
            // Still watching it?
            if ((mode as? PlayMode.Live)?.channel?.id != m.channel.id || screen != Screen.Player) return@launch
            startOverChoosing = false
            startOver = StartOverOffer(m.channel.id, p, title, plex, film)
            AppLog.info("Joined ${p.title} part way – it's on " + listOfNotNull(if (plex.isNotEmpty()) "Plex" else null, film?.let { "the provider's films" }).joinToString(" and "))
            // Gone again after half a minute if it's ignored.
            delay(30_000)
            if (!startOverChoosing && startOver?.programme === p) startOver = null
        }
    }

    fun acceptStartOver() {
        val o = startOver ?: return
        // Your usual copy is there: straight to it.
        when (val pick = preferredPick(o.plex, hasIptv = o.film != null)) {
            null -> Unit
            "iptv" -> {
                playStartOver(o, null)
                return
            }
            else -> {
                playStartOver(o, o.plex.first { copyId(it) == pick })
                return
            }
        }
        // Only one place: straight there.
        if (o.plex.size + (if (o.film != null) 1 else 0) == 1) {
            playStartOver(o, o.plex.firstOrNull())
            return
        }
        startOverChoosing = true
    }

    fun dismissStartOver() {
        startOver = null
        startOverChoosing = false
    }

    /** Plays the film from the start: the Plex copy picked, or ([plex] null) the provider's. */
    fun playStartOver(o: StartOverOffer, plex: com.titanejstudios.tuner.integrations.Plex.Entry?) {
        dismissStartOver()
        if (plex != null) playPlexEntry(plex, fromStart = true, chosen = true, fallback = o.film?.let { f -> { playMovie(f, true, viaPlex = false) } })
        else o.film?.let { playMovie(it, true, viaPlex = false) }
    }


    fun togglePreferPlexFilms() {
        preferPlexFilms = !preferPlexFilms
        store.preferPlexFilms = preferPlexFilms
        showNotice(if (preferPlexFilms) "Films you also have on Plex will play from Plex" else "Films play from your IPTV provider")
    }

    val plexServerCount: Int get() = plexFeature.servers.size

    /** Kids' profiles only see the Plex libraries picked for them (hold OK on a library). */
    val kidsHavePlex: Boolean get() = plexLinked && plexFeature.kidsSections.isNotEmpty()

    fun plexSectionAllowed(s: Plex.Section): Boolean = !activeProfile.kids || s.id in plexFeature.kidsSections

    /** Marks a Plex film, show or episode watched/unwatched, then reloads what's on screen. */
    fun setPlexWatched(entry: Plex.Entry, watched: Boolean) {
        plexFeature.setWatched(entry, watched, { showNotice(it) }) {
            if (screen == Screen.PlexShow) plexFeature.show?.let { plexFeature.openShow(it) }
        }
    }

    fun openPlexShow(entry: com.titanejstudios.tuner.integrations.Plex.Entry) {
        plexFeature.openShow(entry)
        navigate(Screen.PlexShow)
    }

    // ---------------- "Go to…" from the player ----------------

    /** The Plex show the playing episode belongs to (when it was started from the show's page). */
    private var plexShowPlaying: com.titanejstudios.tuner.integrations.Plex.Entry? = null
    private var plexShowFor: String? = null

    /** The IPTV series that's playing. */
    val playingSeries: SeriesItem?
        get() {
            val sid = (mode as? PlayMode.Vod)?.seriesId ?: return null
            return openSeries?.takeIf { it.id == sid } ?: series?.firstOrNull { it.id == sid }
        }

    /** The IPTV film that's playing. */
    val playingFilm: VodItem?
        get() {
            val m = mode as? PlayMode.Vod ?: return null
            if (m.seriesId != null || m.contentId.startsWith("plex:")) return null
            return movies?.firstOrNull { it.id == m.contentId }
        }

    val playingPlexShow: com.titanejstudios.tuner.integrations.Plex.Entry?
        get() = plexShowPlaying?.takeIf { plexShowFor != null && (mode as? PlayMode.Vod)?.contentId == "plex:$plexShowFor" }

    /** What the player shows the name of (the series or show for episodes, the programme on live TV). */
    val playingShowName: String?
        get() = when (val m = mode) {
            is PlayMode.Vod -> playingPlexShow?.title ?: playingPlexEntry?.showName ?: m.title
            is PlayMode.CatchupPlay -> m.programme.title
            is PlayMode.Live -> nowNext(m.channel.id, now).first?.title
            else -> null
        }

    /** Leaves the player (on phones it carries on in the mini player). */
    private fun leavePlayer() {
        if (screen == Screen.Player) back()
    }

    fun goToSeries() {
        val s = playingSeries ?: return
        leavePlayer()
        openSeriesDetail(s)
    }

    fun goToPlexShow(season: Int? = null) {
        val s = playingPlexShow ?: return
        leavePlayer()
        plexFeature.openAtSeason = season
        openPlexShow(s)
    }

    /** The Plex film or episode that's playing, as Plex has it (looked up when it starts). */
    val playingPlexSeason: Int? get() = playingPlexEntry?.takeIf { it.type == "episode" && it.season > 0 }?.season

    /** Looks up what's playing on Plex: its show and season, and whether it's watched. */
    private fun lookUpPlexPlaying(item: Plex.Item) {
        playingPlexEntry = null
        viewModelScope.launch {
            val e = runCatching { plexFeature.entryFor(item.server, item.ratingKey) }.getOrNull() ?: return@launch
            if ((mode as? PlayMode.Vod)?.contentId != "plex:" + item.ratingKey) return@launch
            playingPlexEntry = e
            if (e.type == "episode" && (plexShowPlaying == null || plexShowFor != item.ratingKey)) {
                plexShowPlaying = e.showEntry()
                plexShowFor = item.ratingKey
            }
        }
    }

    fun togglePlexWatchedPlaying() {
        val e = playingPlexEntry ?: return
        val now = !e.watched
        plexFeature.setWatched(e, now, { showNotice(it) }) {}
        playingPlexEntry = com.titanejstudios.tuner.integrations.Plex.Entry(
            e.ratingKey, e.title, e.year, e.image, e.type, e.playUrl, e.viewOffsetMs, e.durationMs, now, e.summary, e.rating,
            e.season, e.episode, e.leafCount, e.viewedLeafCount, e.server, e.library, e.sectionKey, e.art, e.contentRating, e.showKey, e.showName,
        )
        showNotice(if (now) "✓ Marked as watched on Plex" else "Marked as unwatched on Plex")
    }

    fun goToFilmPage() {
        val m = mode as? PlayMode.Vod ?: return
        val isSeries = m.seriesId != null || playingPlexShow != null || playingPlexEntry?.type == "episode" || (m.contentId.startsWith("plex:") && m.subtitle?.matches(Regex("S\\d+ E\\d+.*")) == true)
        val name = playingPlexShow?.title ?: playingPlexEntry?.showName ?: m.title
        leavePlayer()
        openFilmInfo(name, isSeries)
    }

    /** Search everything (guide, films, series, Plex, YouTube) for the show. */
    fun findMoreOf(name: String) {
        leavePlayer()
        navigate(Screen.Search)
        updateSearch(Tmdb.cleanTitle(name).first)
    }

    fun goToChannelGuide() {
        leavePlayer()
        openGuide()
    }

    // ---------------- Your own collections ("Christmas films") ----------------

    /** The film or series being added to a collection (shows the picker). */

    private fun saveCollections(map: Map<String, List<WatchItem>>) {
        collections = map
        store.collectionsJson = Watchlist.encodeCollections(map)
        markChanged(com.titanejstudios.tuner.integrations.LinkSync.COLLECTIONS)
        link.tellOthers()
    }

    fun addToCollection(name: String, item: WatchItem) {
        val n = name.trim().take(40).ifEmpty { return }
        val list = collections[n].orEmpty()
        if (list.any { it.id == item.id }) return showNotice("Already in $n")
        saveCollections(collections + (n to list + item))
        showNotice("Added ${item.name} to $n")
    }

    fun removeFromCollection(name: String, id: String) {
        val list = collections[name].orEmpty().filter { it.id != id }
        saveCollections(if (list.isEmpty()) collections - name else collections + (name to list))
    }

    fun deleteCollection(name: String) {
        saveCollections(collections - name)
        showNotice("Deleted the collection $name")
        if (screen == Screen.Collections) back()
    }

    fun showCollection(name: String) {
        openCollection = name
        navigate(Screen.Collections)
    }

    // ---------------- Bookmarks ----------------


    /** 🔖 Bookmark this moment (films and episodes). */
    fun bookmarkHere() {
        val m = mode as? PlayMode.Vod ?: return
        val pos = playback.positionMs
        val b = Watchlist.Bookmark(java.util.UUID.randomUUID().toString(), m.contentId, m.title, m.subtitle, m.poster, m.url, m.seriesId, pos, System.currentTimeMillis())
        bookmarks = (listOf(b) + bookmarks).take(50)
        store.bookmarksJson = Watchlist.encodeBookmarks(bookmarks)
        showNotice("🔖 Bookmarked ${m.title} at ${pos / 60_000}:${"%02d".format((pos / 1000) % 60)}")
    }

    fun playBookmark(b: Watchlist.Bookmark) {
        if (b.contentId.startsWith("plex:")) return showNotice("Bookmarks of Plex films play from Plex itself")
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(b.contentId, b.title, b.subtitle, b.poster, b.url, b.seriesId), fromStart = false, startAt = b.posMs)
    }

    fun deleteBookmark(id: String) {
        bookmarks = bookmarks.filter { it.id != id }
        store.bookmarksJson = Watchlist.encodeBookmarks(bookmarks)
    }

    // ---------------- "Flicker closed unexpectedly" ----------------

    /** Shown once after a crash, with what went wrong. */

    fun dismissCrashNotice(showLog: Boolean) {
        store.crashShownAt = SafeMode.lastCrashAt
        crashNotice = null
        if (showLog) navigate(Screen.Log)
    }

    // ---------------- Family picks ----------------

    /** "👪 Recommend to the family": shared with every profile and linked device. */

    /** Picks for this profile (not its own). */
    val familyPicksForMe: List<FamilyPick>
        get() = familyPicks.filter { !activeProfile.kids && !it.from.equals(activeProfile.name, ignoreCase = true) }

    fun recommendToFamily(item: WatchItem) {
        if (activeProfile.kids) return showNotice("Only grown-ups' profiles can recommend things")
        familyPicks = Watchlist.addPick(familyPicks, FamilyPick(item, activeProfile.name, System.currentTimeMillis()), System.currentTimeMillis())
        store.familyPicksJson = Watchlist.encodePicks(familyPicks)
        markChanged(com.titanejstudios.tuner.integrations.LinkSync.PICKS)
        link.tellOthers()
        showNotice("👪 ${item.name} is on everyone's Home under Family picks")
    }

    fun removeFamilyPick(id: String) {
        familyPicks = familyPicks.filter { it.item.id != id }
        store.familyPicksJson = Watchlist.encodePicks(familyPicks)
        markChanged(com.titanejstudios.tuner.integrations.LinkSync.PICKS)
        link.tellOthers()
    }

    /** What's playing, as something to recommend or rate (IPTV films and series). */
    val playingWatchItem: WatchItem?
        get() = playingSeries?.let { WatchItem.of(it, System.currentTimeMillis()) } ?: playingFilm?.let { WatchItem.of(it, System.currentTimeMillis()) }

    // ---------------- 👍 / 👎 on films, series and what's playing ----------------

    /** 1 = 👍, -1 = 👎, 0 = not rated. */
    fun thumbOf(title: String): Int {
        val k = Taste.key(FilmName.parse(title).first)
        return when (k) {
            in taste.liked -> 1
            in taste.disliked -> -1
            else -> 0
        }
    }

    fun thumb(title: String, genre: String?, like: Boolean) = rate(FilmName.parse(title).first, genre, like)

    /** The title and genre of what's playing (the programme on live TV). */
    val playingRateable: Pair<String, String?>?
        get() = when (val m = mode) {
            is PlayMode.Vod -> m.title to (playingFilm?.category ?: playingSeries?.category)
            is PlayMode.CatchupPlay -> m.programme.title to Genres.of(m.programme.category, m.programme.title, m.channel.group)
            is PlayMode.Live -> nowNext(m.channel.id, now).first?.let { p -> p.title to Genres.of(p.category, p.title, m.channel.group) }
            else -> null
        }

    // ---------------- Coming soon: new episodes of your series ----------------


    private fun decodeComing(json: String?): List<ComingEpisode> = runCatching {
        val a = org.json.JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val w = Watchlist.decode(org.json.JSONArray().put(o.getJSONObject("w")).toString()).firstOrNull() ?: return@mapNotNull null
            ComingEpisode(w, o.optString("date"), o.optInt("s"), o.optInt("e"), o.optString("name").takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    private fun encodeComing(list: List<ComingEpisode>): String {
        val a = org.json.JSONArray()
        for (c in list) {
            val w = org.json.JSONArray(Watchlist.encode(listOf(c.item))).getJSONObject(0)
            a.put(org.json.JSONObject().put("w", w).put("date", c.date).put("s", c.season).put("e", c.episode).put("name", c.name ?: ""))
        }
        return a.toString()
    }

    /** Upcoming episodes still to come (or today). */
    val comingSoonNow: List<ComingEpisode>
        get() {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
            return comingSoon.filter { it.date >= today }
        }

    /** Asks TMDB, twice a day, when the series you follow and save have their next episode. */
    fun refreshComingSoon(force: Boolean = false) {
        val key = tmdbKey ?: return
        if (activeProfile.kids) return
        val now = System.currentTimeMillis()
        if (!force && now - store.comingSoonAt < 12 * 3600_000L) {
            tellComingToday()
            return
        }
        store.comingSoonAt = now
        val shows = (followedSeries.map { it.series } + watchlist.filter { it.isSeries })
            .distinctBy { FilmName.parse(it.name).first.lowercase() }.take(40)
        if (shows.isEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                shows.mapNotNull { w ->
                    runCatching { Tmdb.upcoming(key, w.name) }.getOrNull()?.let { (_, up) ->
                        ComingEpisode(w, up.date, up.season, up.episode, up.name)
                    }.also { Thread.sleep(150) }
                }
            }.sortedBy { it.date }
            comingSoon = found
            store.comingSoonJson = encodeComing(found)
            tellComingToday()
        }
    }

    /** "New today: …" once a day. */
    private fun tellComingToday() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
        if (store.comingSoonTold == today) return
        val due = comingSoon.filter { it.date == today }
        if (due.isEmpty()) return
        store.comingSoonTold = today
        showNotice("📅 New today: " + due.joinToString(", ") { "${FilmName.parse(it.item.name).first} S${it.season} E${it.episode}" })
    }

    // ---------------- One library: Plex and your provider together ----------------

    class LibraryTitle(
        val key: String,
        val title: String,
        val year: String?,
        val poster: String?,
        val rating: String?,
        val film: VodItem?,
        val series: SeriesItem?,
        val plex: Plex.Entry?,
    ) {
        val where: String
            get() = when {
                plex != null && (film != null || series != null) -> "On Plex and your provider"
                plex != null -> "Plex only"
                else -> "Your provider only"
            }
    }

    private var libraryJob: Job? = null

    fun openLibrary(shows: Boolean) {
        if (activeProfile.kids) return showNotice("Ask a grown-up – kids have their own films")
        libraryShows = shows
        libraryMood = null
        library = null
        navigate(Screen.Library)
    }

    fun switchLibrary() {
        libraryShows = !libraryShows
        libraryMood = null
        library = null
        loadLibrary()
    }

    fun loadLibrary(force: Boolean = false) {
        if (library != null && !force) return
        val shows = libraryShows
        if (hasVod) if (shows) loadSeries() else loadMovies()
        libraryJob?.cancel()
        libraryStatus = "Putting Plex and your provider's ${if (shows) "series" else "films"} together…"
        libraryJob = viewModelScope.launch {
            withTimeoutOrNull(40_000) { while (hasVod && (if (shows) series else movies) == null) delay(300) }
            val plexList = if (plexLinked) plexFeature.allOf(shows) else emptyList()
            val films = if (shows) emptyList() else movies.orEmpty()
            val sers = if (shows) series.orEmpty() else emptyList()
            val merged = withContext(Dispatchers.Default) {
                fun norm(n: String): Pair<String, String?> = FilmName.parse(n).let { (t, y) -> t.lowercase().filter { it.isLetterOrDigit() } to y }
                val plexBy = plexList.groupBy { norm(it.title).first }
                val used = HashSet<String>()
                val out = ArrayList<LibraryTitle>()
                val seen = HashSet<String>()
                val provider: List<Triple<String, VodItem?, SeriesItem?>> =
                    films.map { Triple(it.name, it, null) } + sers.map { Triple(it.name, null, it) }
                for ((name, f, s) in provider) {
                    val (t, y) = norm(name)
                    if (t.isEmpty() || !seen.add(t + "|" + (y ?: ""))) continue
                    val p = plexBy[t]?.firstOrNull { it.ratingKey !in used && (y == null || it.year == null || it.year == y) }
                    if (p != null) used += p.ratingKey
                    out += LibraryTitle(
                        "i:" + (f?.id ?: s?.id), FilmName.parse(name).first, y ?: p?.year, p?.image ?: f?.poster ?: s?.cover,
                        p?.rating ?: ratingLabelFor(name, s != null, f?.rating ?: s?.rating), f, s, p,
                    )
                }
                for (p in plexList) {
                    if (p.ratingKey in used) continue
                    val (t, _) = norm(p.title)
                    if (!seen.add(t + "|" + (p.year ?: ""))) continue
                    out += LibraryTitle("p:" + p.server.machineId + p.ratingKey, p.title, p.year, p.image, p.rating, null, null, p)
                }
                out.sortedBy { it.title.lowercase() }
            }
            // A mood: only the films that fit it.
            val mood = libraryMood?.let { com.titanejstudios.tuner.data.Moods.byKey(it) }
            val shown = if (mood == null || shows) merged else withContext(Dispatchers.Default) { merged.filter { fitsMood(it, mood) } }
            library = shown
            libraryStatus = when {
                merged.isEmpty() -> "Nothing found – is Plex or your provider set up?"
                shown.isEmpty() && (mood?.maxMinutes ?: 0) > 0 -> "Running times come from Plex – none of your Plex films are short enough"
                shown.isEmpty() -> "Nothing found for \"${mood?.label}\" – your provider's categories may not say"
                else -> null
            }
        }
    }

    fun openLibraryTitle(t: LibraryTitle) {
        when {
            t.film != null -> playMovie(t.film, fromStart = (resume[t.film.id]?.positionMs ?: 0L) == 0L)
            t.series != null && (t.plex == null || !preferPlexFilms) -> openSeriesDetail(t.series)
            t.plex != null && t.plex.type == "show" -> openPlexShow(t.plex)
            t.plex != null -> playPlexEntry(t.plex)
        }
    }

    // ---------------- Where can I watch…? ----------------

    class WhereOption(val label: String, val detail: String?, val action: () -> Unit)
    /** [options] is null while still looking. */
    class WhereTo(val title: String, val options: List<WhereOption>?)
    var whereTo by mutableStateOf<WhereTo?>(null); internal set
    internal var whereJob: Job? = null





    // ---------------- Mood picks ----------------

    /** The mood "All my films" is showing (null = everything). */
    var libraryMood by mutableStateOf<String?>(null); internal set




    // ---------------- Voice commands ----------------




    /** "What's on BBC One later": the channel's next few programmes. */
    var channelSchedule by mutableStateOf<Pair<Channel, List<Programme>>?>(null); internal set



    // ---------------- Series: from the beginning, and how far through ----------------

    /** Films and episodes you've finished (here or on a linked device). */
    fun finishedIds(): Set<String> = store.resumeDone.keys

    // ---------------- Profile pictures ----------------

    /** A photo for a profile (from a phone, or the phone's own gallery): squared, shrunk and saved. */
    fun setProfilePhoto(profileId: String, bytes: ByteArray) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth <= 0) return@runCatching false
                    var sample = 1
                    while (minOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 256) sample *= 2
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: return@runCatching false
                    val side = minOf(bmp.width, bmp.height)
                    val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                    val small = android.graphics.Bitmap.createScaledBitmap(square, 256, 256, true)
                    val f = com.titanejstudios.tuner.ui.ProfilePhotos.file(profileId) ?: return@runCatching false
                    f.parentFile?.mkdirs()
                    f.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                    true
                }.onFailure { AppLog.warn("Couldn't save the profile photo", it) }.getOrDefault(false)
            }
            if (ok) {
                store.setProfilePhotoAt(profileId, System.currentTimeMillis())
                com.titanejstudios.tuner.ui.ProfilePhotos.changed()
                showNotice("📷 Profile photo saved for " + (profiles.firstOrNull { it.id == profileId }?.name ?: "the profile"))
            } else showNotice("That picture couldn't be read – try a JPEG or PNG photo")
        }
    }

    fun removeProfilePhoto(profileId: String) {
        com.titanejstudios.tuner.ui.ProfilePhotos.file(profileId)?.delete()
        store.setProfilePhotoAt(profileId, 0L)
        com.titanejstudios.tuner.ui.ProfilePhotos.changed()
        showNotice("Photo removed")
    }

    /** A photo picked on this phone. */
    fun profilePhotoFromUri(profileId: String, uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.size > 30_000_000) showNotice("That picture couldn't be read") else setProfilePhoto(profileId, bytes)
        }
    }

    // ---------------- Kids: ask a grown-up for more time ----------------

    class TimeRequest(val from: String, val profileId: String, val kidName: String)

    /** A child somewhere in the house is asking for more TV (shown on grown-ups' devices). */

    fun askForMoreTime() {
        if (!link.linked) return showNotice("Link a phone first (Settings › Linked devices) – or ask a grown-up for the PIN")
        val kid = activeProfile
        link.search()
        showNotice("📱 Asking a grown-up…")
        viewModelScope.launch {
            delay(3_000)
            val peers = link.peers
            if (peers.isEmpty()) return@launch showNotice("Couldn't find a grown-up's phone – is Flicker open on it?")
            var asked = 0
            peers.forEach { p ->
                link.send(p, JSONObject().put("kind", "askmore").put("profile", kid.id).put("name", kid.name)) { problem ->
                    if (problem == null) asked++
                }
            }
            delay(2_000)
            showNotice(if (asked > 0) "📱 Asked! You'll see when a grown-up answers" else "Couldn't reach a grown-up's phone")
        }
    }

    private fun receiveTimeRequest(from: String, j: JSONObject) {
        if (activeProfile.kids) return
        timeRequest = TimeRequest(from.ifBlank { "The TV" }, j.optString("profile"), j.optString("name").ifBlank { "Your child" })
    }

    fun answerTimeRequest(minutes: Int) {
        val r = timeRequest ?: return
        timeRequest = null
        val kid = profiles.firstOrNull { it.id == r.profileId } ?: profiles.firstOrNull { it.name.equals(r.kidName, true) }
        if (minutes > 0 && kid != null) {
            sendBonusTime(kid, minutes)
            return
        }
        // "Not now": tell the TVs.
        link.tvs.forEach { tv -> link.send(tv, JSONObject().put("kind", "askno").put("profile", r.profileId)) { } }
    }

    /** The rest of a Plex show to play after the current episode. */
    private var plexEpisodeQueue: List<com.titanejstudios.tuner.integrations.Plex.Entry> = emptyList()

    fun openPlexApp() {
        val plexApp = tvApps.orEmpty().firstOrNull { it.packageName == "com.plexapp.android" }
        if (plexApp != null) launchApp(plexApp) else if (!InstalledApps.launch(app, "com.plexapp.android")) showNotice("The Plex app isn't installed")
    }

    // ---------------- Your football teams (see features/FootballFeature) ----------------


    /** Follow a team from ESPN's list, then fetch its fixtures and redraw My teams. */
    fun followEspnTeam(team: com.titanejstudios.tuner.integrations.Espn.Team) {
        football.follow(team, fetch = false)
        refreshTeamFixtures(force = true)
    }

    fun toggleFollowTeam(team: String, sport: String? = null) {
        football.toggleTeam(team, sport)
        refreshTeamFixtures(force = true)
    }


    // ---------------- App updates (see features/UpdateFeature) ----------------

    val installedVersion: String get() = updates.installedVersion

    val updatePageUrl: String?
        get() = phoneBase?.let { "$it/update?t=${store.remoteToken}" }

    fun setUpdateRepo(repo: String?) = updates.changeRepo(repo)

    fun checkForUpdate() = updates.check()

    fun installAvailableUpdate() = updates.install()

    /** Turns the phone remote on (the upload page needs it) and opens the update screen. */
    fun openUpdates() {
        if (!remoteEnabled) toggleRemote()
        navigate(Screen.Update)
        if (updateRepo != null) checkForUpdate()
    }

    // ---------------- Copy settings between TVs ----------------

    fun startShareSend() {
        stopShare()
        shareMode = "send"
        shareCode = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
        shareStatus = null
        updateServer()
        val server = phoneServer
        if (server == null || phoneBase == null) {
            shareStatus = "This TV isn't on a network Flicker can use"
        } else {
            val share = TvShare(app).also { tvShare = it }
            val name = (android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL)
            share.advertise("Flicker on $name", server.port)
        }
        if (screen != Screen.ShareTv) navigate(Screen.ShareTv)
    }

    fun startShareReceive() {
        stopShare()
        shareMode = "receive"
        shareFound = emptyList()
        shareStatus = "Looking for other TVs with Flicker open on \"Copy to another TV\"…"
        val share = TvShare(app).also { tvShare = it }
        share.discover { found ->
            if (shareFound.none { it.host == found.host && it.port == found.port }) shareFound = shareFound + found
        }
        if (screen != Screen.ShareTv) navigate(Screen.ShareTv)
    }

    fun copyFrom(found: TvShare.Found, code: String) {
        shareStatus = "Copying from ${found.name}…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.importBackup(TvShare.fetch(found.host, found.port, code)) }
            }
            result.onSuccess {
                stopShare()
                afterRestore()
                showNotice("Copied everything from ${found.name}")
            }.onFailure {
                shareStatus = it.message ?: "Couldn't copy – try again"
            }
        }
    }

    fun stopShare() {
        tvShare?.stop()
        tvShare = null
        val wasSending = shareMode == "send"
        shareMode = null
        shareCode = null
        if (wasSending) updateServer()
    }

    // ---------------- Watch stats ----------------



    /** The report's "Stopped or blocked" list: (when, what, why). */
    var statsBlocked by mutableStateOf<List<Triple<Long, String, String>>>(emptyList()); internal set







    val healthRunning: Boolean get() = healthJob?.isActive == true



    // ---------------- Match-day channels ----------------







    // ---------------- Settings: playlists ----------------

    fun startAddSource() {
        editingSource = null
        navigate(Screen.SourceEdit)
    }

    fun startEditSource(s: Source) {
        editingSource = s
        navigate(Screen.SourceEdit)
    }

    fun newSourceId(): String = "s" + System.currentTimeMillis().toString(36)

    fun saveSource(s: Source) {
        val list = sources.toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list += s
        sources = list
        store.sources = list
        if (list.size == 1) {
            activeSourceId = s.id
            store.activeSourceId = s.id
        }
        // Saved film/series lists contain the login in their links – start afresh.
        vodCache.clear(s.id)
        movies = null
        series = null
        if (stack.size == 1) resetTo(Screen.Home) else back()
        refreshJob?.cancel()
        refresh(listOf(s))
    }

    fun deleteSource(s: Source) {
        sources = sources.filter { it.id != s.id }
        store.sources = sources
        store.deleteCaches(s.id)
        vodCache.clear(s.id)
        if (activeSourceId == s.id) {
            activeSourceId = ALL_SOURCES
            store.activeSourceId = ALL_SOURCES
        }
        movies = null
        series = null
        if (sources.isEmpty()) {
            stopPlayback()
            allChannels = emptyList()
            epg = emptyMap()
            recomputeVisible()
            editingSource = null
            resetTo(Screen.SourceEdit)
        } else viewModelScope.launch { loadActive() }
    }

    fun selectSource(id: String) {
        activeSourceId = id
        store.activeSourceId = id
        movies = null
        series = null
        viewModelScope.launch {
            loadActive()
            val missing = activeSources().filter { store.lastRefresh(it.id) == 0L || !db.hasChannels(it.id) }
            if (missing.isNotEmpty()) refresh(missing)
        }
    }

    // ---------------- Deep links (home screen row, Home Assistant) ----------------





    // ---------------- Self-test & safe mode ----------------

    fun openSelfTest() {
        navigate(Screen.SelfTest)
        if (selfTestResults.isEmpty() && !selfTestRunning) runSelfTest()
    }

    /** Runs every check one after another; results appear as they finish. */
    fun runSelfTest() {
        selfTestJob?.cancel()
        selfTestResults = emptyList()
        selfTestRunning = true
        val snap = SelfTest.Snapshot(
            sources = sources,
            channels = channels,
            epg = epg,
            favourites = favourites,
            recordingDir = recordingDir,
            webServerRunning = phoneServer != null,
            remoteUrl = remoteUrl,
            webhookUrl = webhookUrl,
            liveTabScope = liveTabScope,
            nightModeOn = nightMode,
            frameRateMatch = frameRateMatch,
            lipSyncSet = lipSyncMap.isNotEmpty(),
        )
        val test = SelfTest(app, store)
        selfTestJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                test.runAll(snap) { r ->
                    viewModelScope.launch { selfTestResults = selfTestResults + r }
                }
            }
            // Let the last result land before saying we're done.
            delay(50)
            selfTestRunning = false
            val bad = selfTestResults.count { it.state == SelfTest.State.Fail }
            AppLog.info("Self-test finished: $bad problem" + (if (bad == 1) "" else "s") + " – " +
                selfTestResults.filter { it.state == SelfTest.State.Fail }.joinToString { it.name })
        }
    }

    /** Turns safe mode off now (as far as possible) and for future starts. */
    fun leaveSafeMode() {
        SafeMode.leave()
        loadSettingsFromStore()
        extrasStarted = false
        startExtras()
        scheduleLiveSync()
        safeModeBannerHidden = true
        showNotice("Safe mode off. Audio passthrough and stream compatibility (if you use them) return next time Flicker starts.")
    }

    /** For "something's not right but it doesn't crash": start with the extras off next time. */
    fun startInSafeModeNextTime() {
        SafeMode.forceNextStart()
        showNotice("Next time Flicker starts it will be in safe mode")
    }

    fun clearLastCrash() {
        SafeMode.clearCrash()
        showNotice("Crash note cleared")
    }

    // ---------------- Smarter buffering ----------------

    /** Half an hour of trouble-free watching lets a channel earn back its quick start. */
    private fun trackCleanPlay() {
        val ch = (mode as? PlayMode.Live)?.channel ?: return
        if (!playback.isPlaying || playback.hadTrouble) return
        cleanPlaySeconds += 15
        if (cleanPlaySeconds >= 30 * 60) {
            cleanPlaySeconds = 0
            if (playback.cushionMs > 0) cushion.healthy(ch.id)
        }
    }

    // ---------------- Recordings planner ----------------

    /** Asks the provider (once) how many streams the account allows, for the planner's clash note. */
    fun loadAccountStreams() {
        if (accountStreamsLoaded) return
        accountStreamsLoaded = true
        val src = sources.filterIsInstance<Source.Xtream>().firstOrNull() ?: return
        viewModelScope.launch {
            accountStreams = withContext(Dispatchers.IO) { XtreamClient.maxConnections(src) }
        }
    }

    // ---------------- Home Assistant: paused / resumed and cinema lights ----------------

    fun cycleCinemaLights() {
        cinemaLights = CinemaLights.next(cinemaLights)
        store.cinemaLights = cinemaLights
        showNotice(
            if (cinemaLights == "off") "Cinema lights off"
            else "Cinema lights: ${CinemaLights.label(cinemaLights)} – Home Assistant gets a \"lights\" event"
        )
    }

    internal fun playKind(m: PlayMode?): String? = when (m) {
        null -> null
        is PlayMode.Live -> "live"
        is PlayMode.CatchupPlay -> "catchup"
        is PlayMode.RecordingPlay -> "recording"
        is PlayMode.Vod -> when {
            m.contentId.startsWith("plex:") -> "plex"
            m.seriesId != null -> "episode"
            else -> "film"
        }
    }

    /**
     * Sends "paused" / "resumed", and "lights" (dim / restore) for the cinema-lights setting.
     * Waits a moment before acting so a quick pause or a channel change doesn't flash the lights.
     */
    private fun watchPlaybackForHomeAssistant() {
        viewModelScope.launch {
            var wasPaused: Boolean? = null
            snapshotFlow { Triple(playKind(mode), screen == Screen.Player && !inPip, playback.isPaused) }
                .distinctUntilChanged()
                .collectLatest { (kind, fullScreen, paused) ->
                    delay(1_500)
                    if (kind != null && webhookUrl != null && wasPaused != null && wasPaused != paused) {
                        sendHa(if (paused) "paused" else "resumed", mapOf("kind" to kind))
                    }
                    wasPaused = if (kind == null) null else paused
                    val dim = CinemaLights.shouldDim(cinemaLights, kind, fullScreen, paused)
                    if (dim != lightsDimmed) {
                        lightsDimmed = dim
                        sendHa("lights", mapOf("state" to (if (dim) "dim" else "restore"), "kind" to kind))
                    }
                }
        }
    }
    // ---------------- Crash alerts (5.4) ----------------

    /** A crash since last time? Tell Home Assistant (event "crash"), once. */
    private fun reportCrashToHomeAssistant() {
        val at = SafeMode.lastCrashAt
        val summary = SafeMode.lastCrash ?: return
        if (at <= store.crashReportedAt || webhookUrl == null) return
        store.crashReportedAt = at
        val log = AppLog.read().lines().filter { it.isNotBlank() }.take(12).joinToString("\n")
        sendHa(
            "crash",
            mapOf(
                "summary" to summary, "at" to at / 1000, "safe_mode" to SafeMode.active,
                "version" to installedVersion, "log" to log,
            ),
        )
    }

    // ---------------- "Are you still watching?" (5.4) ----------------

    /** Any button on the remote (or the phone remote, or Home Assistant) counts as someone watching. */
    /** When a button was last pressed (the player shows its notes for a few seconds after). */
    var buttonPressedAt by androidx.compose.runtime.mutableLongStateOf(0L); internal set


    /** The TV's own screensaver (or the screen going off) came on: ask who's watching when it ends. */
    internal var askProfileOnReturn = false






    /** One picture in the screensaver. */
    class SaverSlide(val title: String, val details: String, val image: String, val play: () -> Unit)

    /** Wide artwork for the screensaver: new on Plex, Continue on Plex, and pictures from the guide. */
    val saverSlides: List<SaverSlide>
        get() = buildList {
            plexRecent.forEach { e -> e.art?.let { add(SaverSlide(e.title, listOfNotNull(e.year, if (e.isShow) "TV" else "Film", e.library).joinToString(" · "), it) { if (e.isShow) openPlexShow(e) else playPlexEntry(e) }) } }
            myPlexItems.forEach { i -> i.image?.let { add(SaverSlide(i.title, listOfNotNull(i.subtitle, "Continue on Plex").joinToString(" · "), it) { playPlex(i) }) } }
            topPicks.forEach { p -> p.programme.image?.let { add(SaverSlide(p.programme.title, p.channel.name, it) { play(p.channel, channelsFor(ALL)) }) } }
        }.distinctBy { it.image }

    internal var saverCurrent: SaverSlide? = null








    val stillWatchingLabel: String
        get() = if (stillWatchingHours <= 0) "Never" else "After $stillWatchingHours hours"

    // ---------------- Volume levelling & picture quality (5.4) ----------------

    val volumeDb: Int get() = playing?.let { volumeLevels[it.id]?.toIntOrNull() } ?: 0
    val volumeLabel: String get() = VolumeLevels.label(volumeDb)

    /** Steps the channel's volume level: Normal → +3 → +6 → +9 → −9 → −6 → −3 → Normal. */
    fun cycleVolumeLevel() {
        val ch = playing ?: return
        val next = VolumeLevels.next(volumeDb)
        volumeLevels = if (next == 0) volumeLevels - ch.id else volumeLevels + (ch.id to next.toString())
        store.volumeLevels = volumeLevels
        showNotice("${ch.name}: ${VolumeLevels.label(next)}" + (if (next > 0 && audioPassthrough) " (needs Audio passthrough off)" else ""))
    }

    val qualityMode: String get() = playing?.let { qualityModes[it.id] } ?: "auto"

    fun qualityLabel(q: String = qualityMode): String = when (q) {
        "best" -> "Best"
        "sd" -> "Data saver (SD)"
        else -> "Auto"
    }

    fun cycleQuality() {
        val ch = playing ?: return
        val next = when (qualityMode) {
            "auto" -> "best"
            "best" -> "sd"
            else -> "auto"
        }
        qualityModes = if (next == "auto") qualityModes - ch.id else qualityModes + (ch.id to next)
        store.qualityModes = qualityModes
        showNotice("${ch.name}: picture quality ${qualityLabel(next)} – only changes channels that offer more than one")
    }

    // ---------------- Followed teams in the guide (5.4) ----------------

    fun cycleSportFollow() {
        sportFollowMode = when (sportFollowMode) {
            "off" -> "remind"
            "remind" -> "record"
            else -> "off"
        }
        store.sportFollowMode = sportFollowMode
        showNotice(
            when (sportFollowMode) {
                "remind" -> "Followed teams: a reminder for each match"
                "record" -> "Followed teams: every match is recorded"
                else -> "Followed teams: nothing set automatically"
            }
        )
        applySportFollow()
    }

    val sportFollowLabel: String
        get() = when (sportFollowMode) {
            "remind" -> "Remind me"
            "record" -> "Record"
            else -> "Off"
        }

    /** Finds followed teams' matches in the guide and sets a reminder or recording (once per match). */
    private fun applySportFollow() {
        val teams = football.teams
        val modeNow = sportFollowMode
        if (teams.isEmpty() || modeNow == "off" || activeProfile.kids) return
        val chans = allowedAll
        val guide = epg
        val favs = favouriteSet
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { SportFollow.find(teams, chans, guide, favs, System.currentTimeMillis()) }
            if (found.isEmpty()) return@launch
            val done = store.sportAutoDone.toHashSet()
            val added = ArrayList<String>()
            for (m in found) {
                if (m.key in done) continue
                if (modeNow == "record") {
                    if (recordingFor(m.channel, m.programme) == null) recordProgramme(m.channel, m.programme)
                } else if (!hasReminder(m.channel, m.programme)) {
                    toggleReminder(m.channel, m.programme)
                }
                done += m.key
                added += m.key
            }
            if (added.isNotEmpty()) {
                store.sportAutoDone = store.sportAutoDone + added
                showNotice((if (modeNow == "record") "Recording " else "Reminders set for ") + "${added.size} match" + (if (added.size == 1) "" else "es") + " with your teams")
            }
        }
    }

    // ---------------- Film info (TMDB) & subtitles (OpenSubtitles) (5.4) ----------------

    fun saveTmdbKey(key: String?) {
        tmdbKey = key?.trim()?.takeIf { it.isNotEmpty() }
        store.tmdbKey = tmdbKey
        showNotice(if (tmdbKey != null) "TMDB key saved – hold OK on a film for its info" else "TMDB key removed")
    }

    fun saveOpenSubtitlesKey(key: String?) {
        openSubtitlesKey = key?.trim()?.takeIf { it.isNotEmpty() }
        store.openSubtitlesKey = openSubtitlesKey
        showNotice(if (openSubtitlesKey != null) "OpenSubtitles key saved – find subtitles from the player menu" else "OpenSubtitles key removed")
    }

    /** Opens the info card for a film or series (looked up on TMDB by name). */
    fun openFilmInfo(name: String, isSeries: Boolean) {
        val key = tmdbKey
        if (key == null) {
            showNotice("Add your free TMDB key in Settings › Films & series first")
            return
        }
        filmInfo = null
        filmInfoStatus = "Looking up $name…"
        filmInfoOpen = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, name, isSeries) } }
            r.onSuccess { info ->
                filmInfo = info
                filmInfoImdb = null
                val omdb = BuildConfig.OMDB_KEY.takeIf { it.isNotBlank() }
                if (info?.imdbId != null && omdb != null) launch {
                    filmInfoImdb = withContext(Dispatchers.IO) { runCatching { Omdb.imdbRating(omdb, info.imdbId) }.getOrNull() }?.takeIf { it > 0.0 }
                }
                filmCollection = null
                if (info != null && !isSeries) filmCollection = loadCollection(info)
                filmInfoStatus = if (info == null) "Couldn't find \"${Tmdb.cleanTitle(name).first}\" on TMDB" else null
            }.onFailure { filmInfoStatus = it.message ?: "Couldn't reach TMDB" }
        }
    }

    fun closeFilmInfo() {
        filmInfoOpen = false
    }

    // ---------------- 📱 Second screen: about what the TV is showing ----------------

    /** TMDB's details for what the TV is playing (looked up on the phone). */
    suspend fun aboutTv(what: String, series: Boolean): Tmdb.Info? {
        val key = tmdbKey ?: return null
        return withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, what, series) }.getOrNull() }
    }

    /** An actor's films, each with your copy if you have one. */
    suspend fun actorFilms(personId: Long): List<SeriesPart> {
        val key = tmdbKey ?: return emptyList()
        if (movies == null && hasVod) loadMovies()
        val parts = withContext(Dispatchers.IO) { runCatching { Tmdb.personFilms(key, personId) }.getOrDefault(emptyList()) }
        return withContext(Dispatchers.Default) { parts.map { SeriesPart(it, findFilm(it.title, it.year)) } }
    }

    /** From the phone: play one of your films on the TV. */
    fun playFilmOnTv(tv: com.titanejstudios.tuner.features.LinkFeature.Peer, m: VodItem) {
        showNotice("Sending ${m.name} to ${tv.name}…")
        link.send(tv, JSONObject().put("kind", "vod").put("id", m.id).put("title", m.name).put("sub", m.category)
            .put("poster", m.poster).put("url", m.url).put("pos", 0L)) { problem ->
            showNotice(problem ?: "▶ Playing on ${tv.name}")
        }
    }

    // ---------------- Film series (collections) ----------------

    /** A film in a series and, if you have it, your copy. */
    class SeriesPart(val part: Tmdb.Part, val film: VodItem?)

    /** The series the film on the info card belongs to ("Harry Potter Collection" → its films in order). */
    var filmCollection by mutableStateOf<Pair<String, List<SeriesPart>>?>(null); private set

    internal fun norm(t: String) = t.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]"), "")

    /** Your copy of a film called [title] from [year] (a year either side, as releases differ). */
    private fun findFilm(title: String, year: String?): VodItem? {
        val y = year?.toIntOrNull()
        return filmIndex()[norm(title)]?.firstOrNull { (_, my) -> y == null || my == null || kotlin.math.abs(my - y) <= 1 }?.first
    }

    /** Your films by tidied-up title (built once per film list – thousands of names). */
    @Volatile private var filmIndexCache: Pair<List<VodItem>, Map<String, List<Pair<VodItem, Int?>>>>? = null

    private fun filmIndex(): Map<String, List<Pair<VodItem, Int?>>> {
        val list = movies.orEmpty()
        filmIndexCache?.let { (l, map) -> if (l === list) return map }
        val map = HashMap<String, MutableList<Pair<VodItem, Int?>>>()
        for (m in list) {
            val (t, y) = FilmName.parse(m.name)
            map.getOrPut(norm(t)) { ArrayList() } += m to y?.toIntOrNull()
        }
        filmIndexCache = list to map
        return map
    }

    private suspend fun loadCollection(info: Tmdb.Info): Pair<String, List<SeriesPart>>? {
        val id = info.collectionId ?: return null
        val key = tmdbKey ?: return null
        val parts = withContext(Dispatchers.IO) { runCatching { Tmdb.collection(key, id) }.getOrDefault(emptyList()) }
        if (parts.size < 2) return null
        val list = withContext(Dispatchers.Default) { parts.map { SeriesPart(it, findFilm(it.title, it.year)) } }
        return (info.collectionName ?: "The series") to list
    }

    fun playSeriesPart(p: SeriesPart) {
        val f = p.film
        if (f == null) {
            showNotice("${p.part.title} isn't in your films")
            return
        }
        closeFilmInfo()
        playMovie(f, fromStart = false)
    }

    /** "Next in the series" when a film ends: the next film you have, offered for a few seconds. */
    var nextInSeries by mutableStateOf<VodItem?>(null); private set
    private var nextInSeriesJob: Job? = null

    private fun offerNextInSeries(m: PlayMode.Vod) {
        val key = tmdbKey ?: return
        if (m.seriesId != null || m.contentId.startsWith("plex:")) return
        nextInSeriesJob?.cancel()
        nextInSeriesJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Tmdb.lookup(key, m.title, false) }.getOrNull() } ?: return@launch
            val (_, parts) = loadCollection(info) ?: return@launch
            val here = parts.indexOfFirst { norm(it.part.title) == norm(info.title) }
            val next = parts.drop(here + 1).firstOrNull { it.film != null }?.film ?: return@launch
            if (here < 0) return@launch
            nextInSeries = next
            delay(30_000)
            nextInSeries = null
        }
    }

    var trailerAutoplay by mutableStateOf(store.trailerAutoplay && !com.titanejstudios.tuner.data.Lite.on); private set

    fun toggleTrailerAutoplay() {
        trailerAutoplay = !trailerAutoplay
        store.trailerAutoplay = trailerAutoplay
    }

    // ---------------- Trakt ----------------

    private val trakt: com.titanejstudios.tuner.integrations.Trakt? =
        if (BuildConfig.TRAKT_ID.isNotBlank() && BuildConfig.TRAKT_SECRET.isNotBlank())
            com.titanejstudios.tuner.integrations.Trakt(BuildConfig.TRAKT_ID, BuildConfig.TRAKT_SECRET) else null
    val traktAvailable: Boolean get() = trakt != null
    var traktUser by mutableStateOf(store.traktAuth?.split('\t')?.getOrNull(3)?.ifBlank { "your account" }); private set
    var traktCode by mutableStateOf<String?>(null); private set
    var traktStatus by mutableStateOf<String?>(null); private set
    private var traktJob: Job? = null
    private val traktLogged = HashSet<String>()

    /** Shows a code to enter at trakt.tv/activate, then waits for it. */
    fun linkTrakt() {
        val t = trakt ?: return showNotice("This copy of Flicker was built without Trakt – see the README")
        traktJob?.cancel()
        traktStatus = "Getting a code from Trakt…"
        traktJob = viewModelScope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { t.deviceCode() } }.getOrElse {
                traktStatus = "Couldn't reach Trakt: ${it.message}"
                return@launch
            }
            traktCode = code.userCode
            traktStatus = "Go to trakt.tv/activate and enter ${code.userCode}"
            val until = System.currentTimeMillis() + code.expiresIn * 1000L
            while (System.currentTimeMillis() < until) {
                delay(code.interval.coerceAtLeast(5) * 1000L)
                val tokens = withContext(Dispatchers.IO) { runCatching { t.checkCode(code) }.getOrNull() } ?: continue
                val name = withContext(Dispatchers.IO) { t.username(tokens.access) } ?: ""
                store.traktAuth = listOf(tokens.access, tokens.refresh, tokens.expiresAt.toString(), name).joinToString("\t")
                traktUser = name.ifBlank { "your account" }
                traktCode = null
                traktStatus = null
                showNotice("Trakt linked ✓ – films and episodes you finish will be logged")
                importTraktWatched(force = true)
                return@launch
            }
            traktCode = null
            traktStatus = "The code expired – try again"
        }
    }

    fun unlinkTrakt() {
        traktJob?.cancel()
        store.traktAuth = null
        traktUser = null
        traktCode = null
        traktStatus = null
        showNotice("Trakt unlinked")
    }

    /** A current access token (refreshed when it's near expiry). Runs on IO. */
    private fun traktAccess(): String? {
        val t = trakt ?: return null
        val parts = store.traktAuth?.split('\t') ?: return null
        if (parts.size < 3) return null
        val expires = parts[2].toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < expires - 86_400_000L) return parts[0]
        return runCatching { t.refresh(parts[1]) }.getOrNull()?.let { n ->
            store.traktAuth = listOf(n.access, n.refresh, n.expiresAt.toString(), parts.getOrElse(3) { "" }).joinToString("\t")
            n.access
        }
    }

    /** A film or episode was finished: log it on Trakt (grown-ups' profiles only). */
    private fun logToTrakt(m: PlayMode.Vod) {
        if (trakt == null || store.traktAuth == null || activeProfile.kids) return
        if (m.contentId.startsWith("plex:") || !traktLogged.add(m.contentId)) return
        // Episodes play with the series as the title and "S1 E2  Name" underneath.
        val seriesName = m.seriesId?.let { id -> series.orEmpty().firstOrNull { it.id == id }?.name ?: m.title }
        val se = Regex("[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})").find(m.subtitle.orEmpty() + " " + m.title)
        viewModelScope.launch(Dispatchers.IO) {
            val access = traktAccess() ?: return@launch
            val ok = runCatching {
                if (m.seriesId != null) {
                    if (seriesName == null || se == null) return@launch
                    trakt.addEpisode(access, Tmdb.cleanTitle(seriesName).first, se.groupValues[1].toInt(), se.groupValues[2].toInt())
                } else {
                    val (title, year) = FilmName.parse(m.title)
                    trakt.addFilm(access, title, year)
                }
            }.getOrDefault(false)
            if (!ok) AppLog.warn("Trakt: couldn't log ${m.title}")
        }
    }

    /** Films Trakt says you've watched count as watched here (Surprise me skips them). Once a day. */
    fun importTraktWatched(force: Boolean = false) {
        val t = trakt ?: return
        if (store.traktAuth == null || activeProfile.kids) return
        if (!force && System.currentTimeMillis() - store.traktImportedAt < 86_400_000L) return
        val list = movies ?: return
        store.traktImportedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val watched = withContext(Dispatchers.IO) { traktAccess()?.let { a -> runCatching { t.watchedFilms(a) }.getOrNull() } } ?: return@launch
            if (list.isEmpty()) return@launch
            val found = withContext(Dispatchers.Default) { watched.mapNotNull { findFilm(it.title, it.year) } }
            val done = store.resumeDone
            var added = 0
            for (f in found) if (f.id !in done && f.id !in resume) {
                com.titanejstudios.tuner.integrations.LinkSync.markDone(store, f.id)
                added++
            }
            if (added > 0) {
                AppLog.info("Trakt: $added watched films matched")
                if (force) showNotice("Trakt: $added films you've seen are marked as watched")
            }
        }
    }

    // ---------------- 🎲 Surprise me ----------------

    /** The film Surprise me picked (shown in a little card: Play / Another / Info). */
    var surprise by mutableStateOf<VodItem?>(null); private set
    private val surpriseShown = HashSet<String>()

    /** A well-rated film you haven't watched, leaning towards what you like. */
    fun surpriseMe() {
        val list = movies
        if (list == null) {
            if (hasVod) loadMovies()
            showNotice("Getting your films ready – try again in a moment")
            return
        }
        if (surpriseJob?.isActive == true) return
        val seen = resume.keys + store.resumeDone.keys
        val learned = Taste.fromJson(taste.toJson())
        val shown = surpriseShown.toSet()
        val ratings = vodRatings.toMap()
        val noKey = tmdbKey == null
        val t = System.currentTimeMillis()
        // Worked out away from the screen: a big film list on the main thread froze the TV (and Android closed Flicker).
        surpriseJob = viewModelScope.launch {
            val pool = runCatching {
                withContext(Dispatchers.Default) {
                    // Each film scored once (rating, nudged by your taste), then a random one of the best 60.
                    list.asSequence()
                        .filter { it.id !in seen && it.id !in shown }
                        .map { it to (ratings[ratingKey(it.name, false)]?.value ?: if (noKey) it.rating?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 else 0.0) }
                        .filter { it.second >= 6.8 }
                        .filter { !com.titanejstudios.tuner.ui.isAdultTitle(it.first.name, it.first.category) }
                        .mapNotNull { (m, r) ->
                            val title = FilmName.parse(m.name).first
                            // 👎 films are left out.
                            if (Taste.key(title) in learned.disliked) null else m to r + learned.score(title, null, t).first / 20.0
                        }
                        .toList()
                        .sortedByDescending { it.second }
                        .take(60)
                }
            }.onFailure { AppLog.warn("Surprise me failed", it) }.getOrDefault(emptyList())
            if (pool.isEmpty()) {
                showNotice(if (tmdbKey == null) "Surprise me needs film ratings – add a TMDB key in Settings" else "Browse Films & TV a little first so Flicker can find the ratings")
                requestRatings(list.take(200).map { it.name }, false)
                return@launch
            }
            val pick = pool.random().first
            surpriseShown += pick.id
            surprise = pick
            requestRatings(listOf(pick.name), false)
        }
    }
    private var surpriseJob: Job? = null

    fun closeSurprise() {
        surprise = null
    }

    fun playNextInSeries() {
        val f = nextInSeries ?: return
        nextInSeries = null
        playMovie(f, fromStart = true)
    }

    fun dismissNextInSeries() {
        nextInSeries = null
    }

    /** Opens the trailer in the YouTube app. */
    /** Trailers play inside Flicker, in YouTube's own player (the YouTube app if the video won't allow that). */
    fun playTrailer(youtubeKey: String, title: String? = null) {
        closeFilmInfo()
        playYouTube(com.titanejstudios.tuner.integrations.YouTube.Video(youtubeKey, title?.let { "$it – trailer" } ?: "Trailer", "", null, 0L))
    }

    /** YouTube subtitles (off unless you turn them on in the player's options). */
    var ytCaptions by mutableStateOf(store.ytCaptions); private set

    fun toggleYouTubeCaptions() {
        ytCaptions = !ytCaptions
        store.ytCaptions = ytCaptions
    }

    /** The list the playing YouTube video came from (for ⏭ Next and playing on at the end). */
    private var ytQueue: List<com.titanejstudios.tuner.integrations.YouTube.Video> = emptyList()

    val ytNext: com.titanejstudios.tuner.integrations.YouTube.Video?
        get() {
            val cur = ytVideo ?: return null
            val i = ytQueue.indexOfFirst { it.id == cur.id }
            return if (i >= 0) ytQueue.getOrNull(i + 1) else null
        }

    /** On to the next video in the list (false at the end, or when TV time's up). */
    fun playNextYouTube(): Boolean {
        val n = ytNext ?: return false
        if (timeLeftSeconds() <= 0 || isBedtimeNow()) {
            timesUp = true
            return false
        }
        ytVideo = n
        return true
    }

    /** "More from this channel": back to YouTube, searching for it. */
    fun moreFromYouTubeChannel(v: com.titanejstudios.tuner.integrations.YouTube.Video) {
        if (screen == Screen.YouTubePlayer) back()
        if (screen != Screen.YouTube) navigate(Screen.YouTube)
        youtube.search(v.channel, activeProfile.kids)
    }

    /** Plays a YouTube video full screen inside Flicker. */
    fun playYouTube(
        v: com.titanejstudios.tuner.integrations.YouTube.Video,
        queue: List<com.titanejstudios.tuner.integrations.YouTube.Video> = emptyList(),
    ) {
        ytQueue = queue
        if (!youTubeAllowed) return
        if (timeLeftSeconds() <= 0 || isBedtimeNow()) {
            timesUp = true
            return
        }
        // Whatever Flicker was playing pauses (films keep their place).
        when (mode) {
            is PlayMode.Vod -> {
                saveResume(syncHome = false)
                playback.pause()
            }
            null -> Unit
            else -> playback.pause()
        }
        ytVideo = v
        navigate(Screen.YouTubePlayer)
    }

    /** The video won't play inside other apps (its owner's choice): open it in the YouTube app instead. */
    fun youTubeFailed(id: String, reason: String) {
        AppLog.warn("YouTube video $id wouldn't play here ($reason)")
    }

    /** Only when you ask: the YouTube app has its own "Who's watching?" and sign-in. */
    fun watchInYouTubeApp(id: String) {
        if (screen == Screen.YouTubePlayer) back()
        openInYouTubeApp(id)
    }

    /**
     * Opens a video in YouTube. Each YouTube app is asked by name, with the full watch address:
     * the Google TV app ignores a general "vnd.youtube:" link (it opens without playing).
     */
    fun openInYouTubeApp(youtubeKey: String) {
        val watch = Uri.parse("https://www.youtube.com/watch?v=$youtubeKey")
        val intents = listOf(
            // YouTube for Google TV / Android TV (and the Fire TV version).
            Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube.tv"),
            Intent(Intent.ACTION_VIEW, watch).setPackage("com.amazon.firetv.youtube"),
            // SmartTube, a popular YouTube app for TVs.
            Intent(Intent.ACTION_VIEW, watch).setPackage("com.teamsmart.videomanager.tv"),
            Intent(Intent.ACTION_VIEW, watch).setPackage("com.liskovsoft.smarttubetv.beta"),
            // YouTube on phones.
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey")).setPackage("com.google.android.youtube"),
            Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube"),
            // Anything else that can open YouTube links (a browser, at worst).
            Intent(Intent.ACTION_VIEW, watch),
        )
        for (i in intents) {
            try {
                app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("force_fullscreen", true))
                AppLog.info("Trailer opened with " + (i.`package` ?: "the default app"))
                return
            } catch (_: Exception) {
            }
        }
        showNotice("Couldn't open YouTube on this device")
    }

    /** Player menu › Find subtitles online: searches OpenSubtitles for the film/episode on screen. */
    fun findSubtitles() {
        val key = openSubtitlesKey
        val m = mode as? PlayMode.Vod
        if (key == null || m == null) {
            showNotice("Add your free OpenSubtitles key in Settings › Films & series first")
            return
        }
        val (title, year) = Tmdb.cleanTitle(m.title)
        val se = if (m.seriesId != null) OpenSubtitles.seasonEpisode(m.subtitle) else null
        val lang = subtitleLanguage ?: "en"
        subtitleStatus = "Searching for $lang subtitles…"
        subtitleHits = emptyList()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { OpenSubtitles.search(key, title, lang, if (se == null) year else null, se?.first, se?.second) }
            }
            r.onSuccess { hits ->
                subtitleHits = hits
                subtitleStatus = if (hits.isEmpty()) "No $lang subtitles found for $title" else null
            }.onFailure {
                subtitleHits = null
                subtitleStatus = null
                showNotice(it.message ?: "Couldn't reach OpenSubtitles")
            }
        }
    }

    fun useSubtitle(hit: OpenSubtitles.Hit) {
        val key = openSubtitlesKey ?: return
        subtitleHits = null
        subtitleStatus = null
        showNotice("Downloading subtitles…")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { OpenSubtitles.download(key, hit, File(app.cacheDir, "subs")) } }
            r.onSuccess { f ->
                playback.addSubtitleFile(f, hit.language)
                showNotice("Subtitles on")
            }.onFailure { showNotice(it.message ?: "Couldn't download the subtitles") }
        }
    }

    fun closeSubtitleHits() {
        subtitleHits = null
        subtitleStatus = null
    }

    // ---------------- Up next & skip intro (5.4) ----------------

    /** The episode that plays next (null for films, or the last episode). */
    val nextEpisode: Episode? get() = if (mode is PlayMode.Vod) episodeQueue.firstOrNull() else null

    private fun startNextEpisode(m: PlayMode.Vod) {
        val next = episodeQueue.firstOrNull() ?: return
        episodeQueue = episodeQueue.drop(1)
        rememberUnderPlayer("ep|${next.id}")
        startVod(
            PlayMode.Vod(next.id, m.title, "S${next.season} E${next.number}  ${next.title}", next.image ?: m.poster, next.url, m.seriesId),
            fromStart = true,
        )
    }

    /** "Play now" on the Up next card. */
    fun playNextEpisodeNow() {
        val m = mode as? PlayMode.Vod ?: return
        // Skipped from well before the end: that's probably where the credits start.
        val left = playback.durationMs - playback.positionMs
        if (m.seriesId != null && creditsFor() == null && playback.durationMs > 0 && left in 25_000L..600_000L) {
            introEnds = introEnds + ("credits:${m.seriesId}" to left.toString())
            store.introEnds = introEnds
        }
        // This one's as good as finished – don't keep a resume point for it.
        resume = resume - m.contentId
        store.resume = resume
        startNextEpisode(m)
    }

    fun cancelAutoNext() {
        episodeQueue = emptyList()
        showNotice("Won't play the next episode")
    }

    /** Where this series' intro ends, if you've skipped it before. */
    fun introEndFor(): Long? {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return null
        return introEnds[sid]?.toLongOrNull()
    }

    // ---------------- Skip credits ----------------
    // Kept with the intros (as "credits:<series>" = how long before the end they start), so they sync too.

    /** How long before the end this series' credits start, if known. */
    fun creditsFor(): Long? {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return null
        return introEnds["credits:$sid"]?.toLongOrNull()
    }

    fun setCreditsHere(posMs: Long, durMs: Long) {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        val fromEnd = durMs - posMs
        if (durMs <= 0 || fromEnd !in 15_000L..900_000L) return showNotice("That's not near enough the end")
        introEnds = introEnds + ("credits:$sid" to fromEnd.toString())
        store.introEnds = introEnds
        showNotice("Got it – Up next will start when the credits do")
    }

    fun forgetCredits() {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        introEnds = introEnds - "credits:$sid"
        store.introEnds = introEnds
        showNotice("Credits forgotten")
    }

    // ---------------- Previously on… ----------------


    fun toggleRecaps() {
        recapsOn = !recapsOn
        store.recaps = recapsOn
    }

    fun dismissRecap() {
        previously = null
    }

    private fun maybeRecap(m: PlayMode.Vod, startMs: Long) {
        previously = null
        val match = Regex("S(\\d+) ?E(\\d+)").find(m.subtitle ?: "") ?: return
        val season = match.groupValues[1].toInt()
        val ep = match.groupValues[2].toInt()
        val now = System.currentTimeMillis()
        val last = lastEpisodeStart.put(m.title, now)
        val key = tmdbKey
        if (!recapsOn || key == null || (season <= 1 && ep <= 1) || startMs > 60_000L) return
        if (last != null && now - last < 4 * 3600_000L) return
        viewModelScope.launch {
            val recap = withContext(Dispatchers.IO) {
                runCatching {
                    val info = Tmdb.lookup(key, m.title, isSeries = true) ?: return@runCatching null
                    val (ps, pe) = if (ep > 1) season to ep - 1 else (season - 1) to Tmdb.seasonLength(key, info.id, season - 1)
                    if (pe < 1 || ps < 1) return@runCatching null
                    val (name, plot) = Tmdb.episode(key, info.id, ps, pe) ?: return@runCatching null
                    plot?.let { "Previously on ${m.title} – S$ps E$pe ${name}".trim() to it }
                }.getOrNull()
            } ?: return@launch
            if (mode !== m) return@launch
            previously = recap
            delay(15_000)
            if (previously === recap) previously = null
        }
    }

    /** "The intro ends here" from the player's menu. */
    fun setIntroEndHere(posMs: Long) {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        introEnds = introEnds + (sid to posMs.toString())
        store.introEnds = introEnds
        showNotice("Got it – every episode will offer to skip to here")
    }

    fun forgetIntro() {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        introEnds = introEnds - sid
        store.introEnds = introEnds
        showNotice("Intro forgotten – skip it once to teach Flicker again")
    }

    /** You skipped forward near the start of an episode and stayed there: remember it as the intro's end. */
    fun learnIntro(endMs: Long) {
        val sid = (mode as? PlayMode.Vod)?.seriesId ?: return
        if (endMs !in 20_000L..300_000L) return
        val old = introEnds[sid]?.toLongOrNull()
        if (old != null && kotlin.math.abs(old - endMs) < 5_000L) return
        introEnds = introEnds + (sid to endMs.toString())
        store.introEnds = introEnds
        showNotice("Next episode: press ▶ to skip the intro")
    }

    // ---------------- Linked devices: send to TV, carry on where you left off ----------------

    /** What's playing here, ready to send to another device (null if it can't be sent). */
    private fun sendable(): JSONObject? {
        val pos = playback.positionMs
        return when (val m = mode) {
            is PlayMode.Live -> liveJson(m.channel)
            is PlayMode.CatchupPlay -> JSONObject().put("kind", "catchup").put("channel", m.channel.id)
                .put("name", m.channel.name).put("url", m.channel.url).put("number", m.channel.number)
                .put("start", m.programme.start).put("stop", m.programme.stop).put("title", m.programme.title)
                .put("category", m.programme.category).put("pos", pos)
            is PlayMode.Vod -> {
                // A download plays from this phone's storage: send the original instead.
                val url = if (m.url.startsWith("file:")) downloads.firstOrNull { it.contentId == m.contentId }?.sourceUrl ?: return null else m.url
                JSONObject().put("kind", "vod").put("id", m.contentId).put("title", m.title).put("sub", m.subtitle)
                    .put("poster", m.poster).put("url", url).put("series", m.seriesId).put("pos", pos)
            }
            is PlayMode.RecordingPlay, null -> null
        }
    }

    /** Sends what's playing to [peer], then stops it here. */
    fun sendTo(peer: com.titanejstudios.tuner.features.LinkFeature.Peer) {
        val what = sendable()
        if (what == null) {
            showNotice(if (mode is PlayMode.RecordingPlay) "Recordings stay on this device – they can't be sent" else "Nothing to send")
            return
        }
        if (mode is PlayMode.Vod) saveResume(syncHome = true)
        showNotice("Sending to ${peer.name}…")
        link.send(peer, what) { problem ->
            if (problem == null) {
                stopPlayback()
                if (screen == Screen.Player) back()
                showNotice("▶ Now playing on ${peer.name}")
            } else showNotice(problem)
        }
    }

    // ---------------- 👫 Watch together (linked devices in the same house) ----------------

    /** The linked device we're watching along with (pause and skips mirror both ways). */
    var togetherWith by mutableStateOf<com.titanejstudios.tuner.features.LinkFeature.Peer?>(null); private set
    private var togetherJob: Job? = null
    /** The device that started watching together with us (by name, before it's found on the network). */
    private var togetherFrom: String? = null
    private var togetherPaused = false
    private var togetherPos = 0L
    private var togetherAt = 0L

    /** Starts what's playing on [peer] too, at the same point, and keeps the two in step. */
    fun watchTogether(peer: com.titanejstudios.tuner.features.LinkFeature.Peer) {
        val what = sendable() ?: run {
            showNotice("This can't be watched together")
            return
        }
        what.put("together", true)
        showNotice("Starting it on ${peer.name}…")
        link.send(peer, what) { problem ->
            if (problem == null) {
                startTogether(peer)
                showNotice("👫 Watching together with ${peer.name} – pause or skip on either")
            } else showNotice(problem)
        }
    }

    fun stopTogether(tell: Boolean = true) {
        togetherFrom = null
        val p = togetherWith ?: return
        togetherJob?.cancel()
        togetherWith = null
        if (tell) sendTogether(p, "leave")
        showNotice("Stopped watching together")
    }

    private fun togetherBaseline() {
        togetherPaused = playback.isPaused
        togetherPos = playback.positionMs
        togetherAt = System.currentTimeMillis()
    }

    /** Watches for pauses and jumps here and tells the other device. */
    private fun startTogether(peer: com.titanejstudios.tuner.features.LinkFeature.Peer) {
        togetherJob?.cancel()
        togetherWith = peer
        togetherJob = viewModelScope.launch {
            delay(3_000)
            togetherBaseline()
            while (togetherWith === peer) {
                delay(700)
                if (mode == null) {
                    stopTogether()
                    break
                }
                val paused = playback.isPaused
                val pos = playback.positionMs
                val now = System.currentTimeMillis()
                val expected = if (togetherPaused) togetherPos else togetherPos + (now - togetherAt)
                when {
                    paused != togetherPaused -> sendTogether(peer, if (paused) "pause" else "play", pos)
                    mode !is PlayMode.Live && kotlin.math.abs(pos - expected) > 6_000 && !playback.isBuffering ->
                        sendTogether(peer, "seek", pos, paused)
                }
                togetherPaused = paused
                togetherPos = pos
                togetherAt = now
            }
        }
    }

    private fun sendTogether(peer: com.titanejstudios.tuner.features.LinkFeature.Peer, act: String, pos: Long = 0L, paused: Boolean = false) {
        link.send(peer, JSONObject().put("kind", "together").put("act", act).put("pos", pos).put("paused", paused)) { problem ->
            if (problem != null && act != "leave" && togetherWith === peer) {
                togetherJob?.cancel()
                togetherWith = null
                showNotice("Lost ${peer.name} – stopped watching together")
            }
        }
    }

    /** The other device paused, played, skipped or left. */
    private fun handleTogether(from: String, j: JSONObject) {
        if (togetherWith == null && togetherFrom != from) return
        when (j.optString("act")) {
            "pause" -> {
                playback.pause()
                if (mode !is PlayMode.Live) playback.seekTo(j.optLong("pos"))
                showNotice("⏸ Paused on $from")
            }
            "play" -> {
                if (mode !is PlayMode.Live) playback.seekTo(j.optLong("pos"))
                playback.resume()
            }
            "seek" -> {
                playback.seekTo(j.optLong("pos"))
                if (j.optBoolean("paused")) playback.pause() else if (playback.isPaused) playback.resume()
            }
            "leave" -> {
                togetherJob?.cancel()
                togetherWith = null
                togetherFrom = null
                showNotice("$from stopped watching together")
                return
            }
        }
        // What we just did isn't news to send back.
        viewModelScope.launch {
            delay(400)
            togetherBaseline()
        }
    }

    /** Another linked device sent something to play here. */
    private fun handleLinkPlay(from: String, j: JSONObject): Boolean {
        if (j.optString("kind") == "together") {
            handleTogether(from.ifBlank { "the other device" }, j)
            return true
        }
        if (j.optString("kind") == "bonus") {
            receiveBonus(from, j)
            return true
        }
        if (j.optString("kind") == "askmore") {
            receiveTimeRequest(from, j)
            return true
        }
        if (j.optString("kind") == "askno") {
            if (activeProfile.id == j.optString("profile")) showNotice("A grown-up said not now – sorry!")
            return true
        }
        if (allChannels.isEmpty() && j.optString("kind") != "vod" && j.optString("kind") != "youtube") return false
        val ok = when (j.optString("kind")) {
            "youtube" -> {
                fun str(k: String) = j.optString(k).takeIf { it.isNotBlank() && it != "null" }
                val id = str("id") ?: return false
                val v = com.titanejstudios.tuner.integrations.YouTube.Video(id, str("title") ?: "YouTube video", str("channel") ?: "", str("thumb"), 0L, str("cid"))
                if (!youTubeAllowed) return false
                if (screen == Screen.YouTubePlayer) back()
                playYouTube(v)
                true
            }
            "live" -> {
                val ch = linkedChannel(j) ?: return false
                resetTo(Screen.Home)
                play(ch, channelsFor(ALL))
                true
            }
            "catchup" -> {
                val ch = linkedChannel(j) ?: return false
                val start = j.optLong("start")
                val p = programmesFor(ch.id).firstOrNull { it.start == start } ?: Programme(
                    start = start, stop = j.optLong("stop"), title = j.optString("title"), desc = null,
                    category = j.optString("category").takeIf { it.isNotBlank() && it != "null" },
                )
                resetTo(Screen.Home)
                playCatchup(ch, p, channelsFor(ALL), startAt = j.optLong("pos"))
                true
            }
            "vod" -> {
                fun str(k: String) = j.optString(k).takeIf { it.isNotBlank() && it != "null" }
                val id = str("id") ?: return false
                val url = str("url") ?: return false
                episodeQueue = emptyList()
                resetTo(Screen.Home)
                startVod(PlayMode.Vod(id, j.optString("title"), str("sub"), str("poster"), url, str("series")), fromStart = false, startAt = j.optLong("pos"))
                true
            }
            else -> false
        }
        if (ok && j.optBoolean("together")) {
            // Keep in step with whoever started it (found by name among the linked devices).
            togetherFrom = from
            val found = link.peers.firstOrNull { it.name == from }
            if (found != null) startTogether(found)
            else link.search { link.peers.firstOrNull { it.name == from }?.let { startTogether(it) } }
        }
        if (ok) {
            showNotice((if (j.optBoolean("together")) "👫 Watching together with " else "▶ Sent from ") + from.ifBlank { "another device" })
            if (!inForeground) {
                // Bring Flicker to the front (allowed because it may draw over other apps).
                runCatching {
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }
        return ok
    }

    /**
     * The channel another device means: the same id (playlists copied between them), else the
     * same stream address, else the same name (ignoring HD / FHD / 1080p labels).
     */
    private fun linkedChannel(j: JSONObject): Channel? {
        allChannelsById(j.optString("channel"))?.let { return it }
        val url = j.optString("url").takeIf { it.isNotBlank() && it != "null" }
        if (url != null) allChannels.firstOrNull { it.url == url }?.let { return it }
        val name = j.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return null
        allChannels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val key = com.titanejstudios.tuner.data.ChannelMerge.key(name)
        return allChannels.firstOrNull { com.titanejstudios.tuner.data.ChannelMerge.key(it.name) == key }
    }

    /** Grown-ups: let kids' profiles see a Plex library (or not) – on every device. */
    fun toggleKidsPlexLibrary(s: Plex.Section) {
        plexFeature.toggleKids(s)
        profilesChanged()
    }

    /** The offline list changed here (a check, a recheck, cleared by hand): share it with the house. */
    internal fun offlineChanged() {
        link.tellOthers()
        cloud.syncSoon()
    }

    /** Profiles were added, edited or deleted here: tell the other devices and houses. */
    private fun profilesChanged() {
        store.setSyncStamp(com.titanejstudios.tuner.integrations.LinkSync.PROFILES, System.currentTimeMillis())
        link.tellOthers()
        cloud.syncSoon()
    }

    private fun applyLinkChanges(changed: Set<String>) {
        if ("pin" in changed) pin = store.pin
        // Playlists from your Google account (a new device just signed in): load their channels.
        if (com.titanejstudios.tuner.data.Store.PLAYLISTS in changed) {
            sources = store.sources
            viewModelScope.launch {
                val empty = withContext(Dispatchers.IO) { activeSources().filter { !db.hasChannels(it.id) } }
                loadActive()
                if (empty.isNotEmpty()) {
                    showNotice("📺 Your playlist is here from your Google account – loading channels…")
                    refresh(empty)
                }
            }
        }
        if (com.titanejstudios.tuner.data.Store.GUIDE_SETTINGS in changed) AppLog.info("TV guide settings synced from another device")
        if (com.titanejstudios.tuner.integrations.LinkSync.COLLECTIONS in changed) collections = Watchlist.decodeCollections(store.collectionsJson)
        if (com.titanejstudios.tuner.integrations.LinkSync.PROFILES in changed) {
            plexFeature.reloadKids()
            profiles = store.profiles
            val now = profiles.firstOrNull { it.id == activeProfile.id }
            when {
                now == null -> switchProfile(profiles.firstOrNull { !it.kids } ?: profiles.first())
                now != activeProfile -> {
                    activeProfile = now
                    recomputeVisible()
                    refreshYouTube()
                }
            }
            AppLog.info("Profiles synced from another device: " + profiles.joinToString { it.name })
        }
        if (com.titanejstudios.tuner.integrations.LinkSync.INTROS in changed) introEnds = store.introEnds
        if (com.titanejstudios.tuner.integrations.LinkSync.OFFLINE in changed) {
            deadChannels = store.deadChannels
            healthCheckedAt = store.healthCheckedAt
            recomputeVisible()
            AppLog.info("Offline list from another device in the house: ${deadChannels.size} channel(s)")
        }
        val lists = changed - "pin" - com.titanejstudios.tuner.integrations.LinkSync.COLLECTIONS - com.titanejstudios.tuner.integrations.LinkSync.INTROS - com.titanejstudios.tuner.integrations.LinkSync.TEAMS -
            com.titanejstudios.tuner.integrations.LinkSync.STARTED - com.titanejstudios.tuner.integrations.LinkSync.PROFILES -
            com.titanejstudios.tuner.integrations.LinkSync.YOUTUBE
        if (com.titanejstudios.tuner.integrations.LinkSync.YOUTUBE in changed) youtube.reload()
        if (lists.isNotEmpty()) applySyncedChanges(lists)
        if (com.titanejstudios.tuner.integrations.LinkSync.STARTED in changed) started = store.started(activeProfile.id)
        if (com.titanejstudios.tuner.integrations.LinkSync.TEAMS in changed) {
            football.reload()
            refreshTeamFixtures(force = true)
        }
        if (TvSync.RESUME in changed) {
            val entries = resume.values.toList()
            viewModelScope.launch(Dispatchers.IO) { HomeScreen.syncContinueWatching(app, store, entries) }
        }
    }

    // ---------------- Backup links: say which, and watch for the better one ----------------

    /** The player switched to a backup link: name it, and if it's a lower quality, keep checking the main one. */
    private fun onBackupLink(url: String) {
        val m = mode as? PlayMode.Live ?: return showNotice("Trying a backup link")
        val original = m.channel
        val alt = altIndex[StreamAlternates.nameKey(original.name)]?.firstOrNull { it.url == url }
        if (alt == null) {
            // The same stream in its other format: same quality, nothing to watch for.
            showNotice("${original.name} wasn't working – trying it another way")
            return
        }
        val label = ChannelMerge.versionLabel(alt).ifBlank { alt.name }
        showNotice("${original.name} isn't working – switched to $label")
        if (ChannelMerge.quality(alt.name) < ChannelMerge.quality(original.name)) watchForUpgrade(original)
    }

    /**
     * While you watch the lower-quality backup, checks every 2 minutes (for up to 3 hours) whether
     * the channel's own stream works again, and says so once.
     */
    private fun watchForUpgrade(original: Channel) {
        upgradeJob?.cancel()
        if (original.id in upgradeTold) return
        upgradeJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 3 * 3_600_000L) {
                delay(2 * 60_000L)
                val m = mode as? PlayMode.Live ?: return@launch
                if (m.channel.id != original.id || playback.currentUrl == original.url) return@launch
                if (!inForeground) continue
                if (withContext(Dispatchers.IO) { streamWorks(original.url) }) {
                    upgradeTold += original.id
                    qualityBack = original
                    AppLog.info("Main stream working again: ${original.name}")
                    delay(20_000)
                    if (qualityBack?.id == original.id) qualityBack = null
                    return@launch
                }
            }
        }
    }

    /** A quick look at a stream: does the server send video back? (Opens and closes a connection.) */
    private fun streamWorks(url: String): Boolean = runCatching {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", com.titanejstudios.tuner.data.Http.USER_AGENT)
        }
        try {
            c.responseCode in 200..299 && c.inputStream.use { it.read(ByteArray(2048)) > 0 }
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** "▲ BBC One FHD is working again" – switch back to it. */
    fun switchToBetterQuality() {
        val c = qualityBack ?: return
        qualityBack = null
        val m = mode as? PlayMode.Live ?: return
        if (m.channel.id == c.id) startLive(c, force = true)
    }

    fun dismissQualityBack() {
        qualityBack = null
    }

    // ---------------- Christmas TV planner ----------------

    /** From late November to New Year: Home offers the planner. */
    val christmasSeason: Boolean
        get() {
            val c = java.util.Calendar.getInstance()
            val m = c.get(java.util.Calendar.MONTH)
            val d = c.get(java.util.Calendar.DAY_OF_MONTH)
            return (m == java.util.Calendar.NOVEMBER && d >= 20) || m == java.util.Calendar.DECEMBER || (m == java.util.Calendar.JANUARY && d <= 2)
        }

    /**
     * Festive specials, Christmas films and the big sport (the darts, and your teams) from now to
     * 2 January, from the stored guide. Guides only reach about a week ahead, so it fills up as
     * Christmas gets nearer.
     */
    fun loadChristmas() {
        val ids = activeSources().map { it.id }
        val chans = allowedAll
        val favs = favouriteSet
        val teams = football.teams.toList()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val c = java.util.Calendar.getInstance()
            val year = if (c.get(java.util.Calendar.MONTH) == java.util.Calendar.JANUARY) c.get(java.util.Calendar.YEAR) - 1 else c.get(java.util.Calendar.YEAR)
            c.clear()
            c.set(year + 1, java.util.Calendar.JANUARY, 3, 0, 0, 0)
            val to = c.timeInMillis
            val from = now - 3 * 3_600_000L
            val byId = chans.associateBy { it.id }
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    val festiveWords = listOf("christmas", "xmas", "festive", "new year", "hogmanay", "santa", "snowman", "nativity", "carol", "boxing day")
                    val specials = db.findInWindow(ids, from, to, festiveWords, limit = 1500)
                    val films = db.findInWindow(
                        ids, from, to,
                        titleWords = festiveWords + listOf("elf", "grinch", "scrooge", "home alone", "polar express", "nutcracker", "reindeer", "jingle", "noel", "holiday"),
                        descWords = listOf("christmas", "santa", "festive", "xmas"),
                        categoryWords = listOf("film", "movie"),
                        limit = 1500,
                    )
                    val sport = db.findInWindow(ids, from, to, listOf("darts") + teams, limit = 1500)
                    val out = LinkedHashMap<String, FestiveItem>()
                    fun add(list: List<Pair<String, Programme>>, section: String, accept: (Programme) -> Boolean) {
                        // Favourite channels first, so the copy kept of a programme is on one you use.
                        for ((id, p) in list.sortedBy { if (it.first in favs) 0 else 1 }) {
                            val ch = byId[id] ?: continue
                            if (!accept(p)) continue
                            val key = p.title.lowercase().trim() + "|" + p.start
                            if (key !in out) out[key] = FestiveItem(ch, p, section)
                        }
                    }
                    val isFilm = { p: Programme -> (p.category ?: "").lowercase().let { "film" in it || "movie" in it } }
                    add(films, "films") { true }
                    add(specials, "specials") { !isFilm(it) && it.stop - it.start >= 20 * 60_000L && Genres.of(it.category, it.title, null) != "News" }
                    add(sport, "sport") { Sports.classify(it) != null && Sports.looksLive(it) }
                    out.values.sortedBy { it.programme.start }
                }.getOrDefault(emptyList())
            }
            festive = items
        }
    }

    // ---------------- Each profile's own Continue watching ----------------

    /**
     * Profiles other than "Everyone" start with an empty Continue watching: only films and
     * episodes they've started (here or on another of your devices) show there, with Plex's own
     * progress. "Everyone" sees the lot.
     */
    val ownContinue: Boolean get() = activeProfile.id != Store.MAIN_PROFILE

    private fun plexKey(title: String) = "p:" + title.lowercase().trim()

    val myPlexItems: List<Plex.Item>
        get() = if (!ownContinue) plexItems else started.let { s -> plexItems.filter { plexKey(it.title) in s } }

    val myResume: Map<String, ResumeEntry>
        get() = if (!ownContinue) resume else started.let { s -> resume.filter { (id, e) -> "v:$id" in s || (e.seriesId != null && "s:${e.seriesId}" in s) } }

    /** Remember that this profile started [keys] (newest 300 kept). */
    private fun markStarted(vararg keys: String?) {
        val add = keys.filterNotNull().filter { it.length > 2 }
        if (add.isEmpty() || add.all { it in started }) return
        val next = (started - add.toSet()).toList().takeLast(300 - add.size).toSet() + add
        started = next
        store.setStarted(activeProfile.id, next)
        store.setSyncStamp(com.titanejstudios.tuner.integrations.LinkSync.STARTED, System.currentTimeMillis())
    }

    /** "Remove from my Continue watching". */
    fun forgetStarted(plexTitle: String? = null, contentId: String? = null) {
        val drop = setOfNotNull(plexTitle?.let { plexKey(it) }, contentId?.let { "v:$it" })
        started = started - drop
        store.setStarted(activeProfile.id, started)
        if (!ownContinue) contentId?.let { id ->
            // "Everyone" sees all resume points, so remove the point itself.
            resume = resume - id
            store.resume = resume
        }
        showNotice("Removed from Continue watching")
    }

    // ---------------- Missed it? & finish what you started ----------------

    /** Ended programmes from the last week on catch-up channels that suit your taste, best first. */
    private fun missedPicks(chans: List<Channel>, guide: Map<String, List<Programme>>, learned: Taste, t: Long, slots: Set<Long> = emptySet()): List<HomeFeed.Pick> {
        val best = HashMap<String, Pair<Double, HomeFeed.Pick>>()
        for (c in chans) {
            if (!c.hasCatchup) continue
            val from = t - c.catchupDays.coerceIn(1, 7) * 86_400_000L
            for (p in guide[c.id].orEmpty()) {
                if (p.stop > t || p.start < from || p.stop - p.start < 20 * 60_000L) continue
                val genre = Genres.of(p.category, p.title, c.group)
                if (genre == "News") continue
                var (score, reason) = learned.score(p.title, genre, t)
                if (score < 5.0) continue
                // One of your shows that went out while nothing was on in Flicker: top of the row.
                if (slots.isNotEmpty() && learned.isYourShow(p.title) && wereAway(p, slots)) {
                    score += 100.0
                    reason = "Your show · on while you were away"
                }
                val key = p.title.lowercase().trim()
                val old = best[key]
                // The best-suited, and of equals the most recent showing.
                if (old == null || score > old.first || (score == old.first && p.start > old.second.programme.start)) {
                    best[key] = score to HomeFeed.Pick(c, p, reason ?: "On catch-up")
                }
            }
        }
        return best.values.sortedByDescending { it.first }.take(20).map { it.second }
    }

    private fun loadUnfinished(): List<Unfinished> = runCatching {
        val arr = org.json.JSONArray(store.unfinishedJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Unfinished(
                o.getString("ch"),
                Programme(o.getLong("s"), o.getLong("e"), o.getString("t"), null, o.optString("c").takeIf { it.isNotBlank() && it != "null" }),
                o.getLong("pos"), o.getLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveUnfinished(list: List<Unfinished>) {
        // Keep the newest 10, and only while catch-up still has them (a week at most).
        val t = System.currentTimeMillis()
        val keep = list.filter { it.programme.start > t - 7 * 86_400_000L }.sortedByDescending { it.at }.take(10)
        unfinished = keep
        val arr = org.json.JSONArray()
        keep.forEach {
            arr.put(JSONObject().put("ch", it.channelId).put("s", it.programme.start).put("e", it.programme.stop)
                .put("t", it.programme.title).put("c", it.programme.category).put("pos", it.positionMs).put("at", it.at))
        }
        store.unfinishedJson = arr.toString()
    }

    /** The live programme you were watching changed or stopped: switched off half-way? */
    internal fun finishLiveWatch() {
        val w = liveWatch ?: return
        liveWatch = null
        if (w.seconds < 10 * 60) return
        val p = programmesFor(w.channelId).firstOrNull { it.start == w.start } ?: return
        // Watched to (nearly) the end, or too short a programme to bother.
        if (w.lastSeen >= p.stop - 5 * 60_000L || p.stop - p.start < 25 * 60_000L) {
            if (unfinished.any { it.channelId == w.channelId && it.programme.start == w.start }) {
                saveUnfinished(unfinished.filterNot { it.channelId == w.channelId && it.programme.start == w.start })
            }
            return
        }
        val ch = allChannelsById(w.channelId) ?: return
        if (catchupVersion(ch) == null) return
        val pos = (w.lastSeen - p.start - 30_000L).coerceAtLeast(0L)
        saveUnfinished(listOf(Unfinished(w.channelId, p, pos, System.currentTimeMillis())) +
            unfinished.filterNot { it.channelId == w.channelId && it.programme.start == w.start })
    }

    /** Programmes you can still finish (catch-up has them). */
    val finishable: List<Unfinished>
        get() = unfinished.filter { u -> allChannelsById(u.channelId)?.let { canCatchup(it, u.programme) } == true }

    fun finishUnfinished(u: Unfinished) {
        val ch = allChannelsById(u.channelId) ?: return
        saveUnfinished(unfinished.filterNot { it === u })
        playCatchup(ch, u.programme, channelsFor(ALL), startAt = u.positionMs)
    }

    fun forgetUnfinished(u: Unfinished) {
        saveUnfinished(unfinished.filterNot { it === u })
    }

    fun playMissed(pick: HomeFeed.Pick) {
        playCatchup(pick.channel, pick.programme, channelsFor(ALL))
    }

    // ---------------- My teams: fixtures (ESPN) and which channel ----------------


    /** The next match in the coming week (for Home). */
    val nextTeamFixture: TeamFixture?
        get() {
            val t = System.currentTimeMillis()
            return teamFixtures.firstOrNull { it.match.kickoff > t - 2 * 3_600_000L && it.match.kickoff < t + 7 * 86_400_000L }
        }

    /** Match day: one of your teams plays within the next 12 hours (or is playing now). */
    val matchDay: TeamFixture?
        get() {
            val t = now
            return teamFixtures.firstOrNull { it.match.live || it.match.kickoff in (t - 2 * 3_600_000L)..(t + 12 * 3_600_000L) }
                ?.takeIf { !it.match.finished }
        }

    /** Watch the match if it's about to start (or on); otherwise open My teams. */
    fun openMatchDay(f: TeamFixture) {
        if (f.channel != null && (f.match.live || f.match.kickoff - now <= 15 * 60_000L)) watchFixture(f) else navigate(Screen.Teams)
    }

    /** On more than one channel: you choose which (the best match is first). */
    fun watchFixture(f: TeamFixture) {
        val ch = f.channel ?: return
        if (f.channels.size > 1) fixtureChoice = f else play(ch, channelsFor(ALL))
    }

    /** The match whose channels are being chosen from. */
    var fixtureChoice by mutableStateOf<TeamFixture?>(null)
    /** "Ipswich v Aston Villa" while its channels are being looked for (a card says so). */
    var findingFixture by mutableStateOf<String?>(null)

    fun watchFixtureOn(ch: Channel) {
        fixtureChoice = null
        play(ch, channelsFor(ALL))
    }

    /** The teams to pick from on My teams (from ESPN). */
    fun loadTeamList() {
        viewModelScope.launch { football.loadTeamList() }
    }

    /** Fetches fixtures (every 6 hours, or now with [force]) and finds their channels in the guide. */
    fun refreshTeamFixtures(force: Boolean = false) {
        if (football.teams.isEmpty() || activeProfile.kids) {
            teamFixtures = emptyList()
            teamCatchup = emptyList()
            return
        }
        if (teamFixturesJob?.isActive == true && !force) return
        teamFixturesJob?.cancel()
        teamFixturesJob = viewModelScope.launch {
            football.loadFixtures(force)
            matchFixtureChannels()
        }
    }

    /**
     * Looks in the stored guide (about a week) around each kick-off for a channel showing it. With
     * no ESPN fixtures (teams added by name, or ESPN unreachable), your teams' games are found in
     * the guide itself.
     */
    private suspend fun matchFixtureChannels() {
        val t = System.currentTimeMillis()
        val ids = activeSources().map { it.id }
        val chans = allowedAll
        val favs = favouriteSet
        val teams = football.teams.toList()
        val espnTeams = football.espnTeams
        val list = football.fixtures.filter { !it.finished && it.kickoff > t - 3 * 3_600_000L }.take(12)
        // Each team's sport, so a Newcastle rugby fan isn't shown Newcastle United.
        val sportOf = teams.associateWith { football.sportOf(it).guideType }
        var pastGames: List<Pair<Channel, Programme>> = emptyList()
        val result = withContext(Dispatchers.IO) {
            val byId = chans.associateBy { it.id }
            fun names(n: String): List<String> = listOfNotNull(n, espnTeams.values.firstOrNull { it.shortName == n || it.name == n }?.name)
                .flatMap { listOf(it, it.removeSuffix(" FC").removeSuffix(" AFC").removePrefix("AFC ")) }.distinct()
            // Only the programmes that mention your teams, straight from the saved guide: reading
            // every channel's whole week into memory ran big playlists out of memory.
            val week: Map<String, List<Programme>> = runCatching {
                val terms = teams.flatMap { names(it) }.map { it.trim() }.filter { it.length >= 3 }.distinct()
                val hits = HashMap<String, MutableList<Programme>>()
                val seen = HashSet<String>()
                for (term in terms) {
                    val found = db.searchProgrammes(ids, term, t - 7 * 86_400_000L, 150) +
                        db.searchProgrammes(ids, term, t - 3 * 3_600_000L, 300)
                    for ((cid, p) in found) {
                        if (cid !in byId || p.start > t + 8 * 86_400_000L) continue
                        if (seen.add(cid + "@" + p.start)) hits.getOrPut(cid) { ArrayList() } += p
                    }
                }
                shiftGuide(chans, hits.mapValues { (_, l) -> l.sortedBy { it.start } })
            }.onFailure { AppLog.warn("My teams: couldn't search the guide", it) }.getOrDefault(emptyMap())
            val guideEnd = runCatching { db.guideEnd(ids) }.getOrDefault(0L)
            fun mentions(title: String, team: String) = names(team).any { com.titanejstudios.tuner.data.SportFollow.mentions(title, it) }
            fun sportOk(team: String, p: Programme, c: Channel): Boolean {
                val type = com.titanejstudios.tuner.data.Sports.classify(p, c.group) ?: return false
                return sportOf[team]?.let { it == type } ?: true
            }
            pastGames = week.flatMap { (cid, progs) ->
                val c = byId[cid] ?: return@flatMap emptyList()
                progs.filter { p ->
                    p.stop <= t && p.start > t - 7 * 86_400_000L && teams.any { n -> mentions(p.title, n) && sportOk(n, p, c) } &&
                        com.titanejstudios.tuner.data.Catchup.isAvailable(c, p, t)
                }.map { c to it }
            }.sortedWith(compareByDescending<Pair<Channel, Programme>> { it.second.start }.thenBy { if (it.first.id in favs) 0 else 1 })
                .distinctBy { it.second.title.lowercase() + "@" + it.second.start / 600_000L }
                .take(15)
            // ESPN's fixtures, each with the channel showing it (both teams named, near kick-off).
            val fromEspn = list.map { m ->
                val found = ArrayList<Triple<Channel, Programme, Int>>()
                for ((cid, progs) in week) {
                    val c = byId[cid] ?: continue
                    for (p in progs) {
                        if (p.stop < m.kickoff - 30 * 60_000L || p.start > m.kickoff + 30 * 60_000L) continue
                        val both = mentions(p.title, m.home) && mentions(p.title, m.away)
                        val one = !both && (mentions(p.title, m.home) || mentions(p.title, m.away)) && sportOk(m.team, p, c)
                        if (!both && !one) continue
                        var score = if (both) 100 else 50
                        if (c.id in favs) score += 20
                        if (com.titanejstudios.tuner.data.Sports.looksLive(p)) score += 10
                        score -= (kotlin.math.abs(p.start - (m.kickoff - 30 * 60_000L)) / 60_000L).toInt().coerceAtMost(60) / 10
                        found += Triple(c, p, score)
                    }
                }
                // Every channel showing it (one entry per channel), best first – you choose which to watch.
                val all = found.sortedByDescending { it.third }.distinctBy { it.first.id }.map { it.first to it.second }
                val best = all.firstOrNull()
                TeamFixture(m, best?.first, best?.second, m.kickoff < guideEnd, all)
            }
            // Games in the guide for teams ESPN doesn't cover (or when it didn't answer).
            val guideOnly = teams.filter { n -> espnTeams[n] == null || fromEspn.none { it.match.team == n } }.flatMap { n ->
                week.flatMap { (cid, progs) ->
                    val c = byId[cid] ?: return@flatMap emptyList()
                    progs.filter { p ->
                        p.stop > t && mentions(p.title, n) && sportOk(n, p, c) &&
                            com.titanejstudios.tuner.data.Sports.looksLive(p)
                    }.map { p ->
                        TeamFixture(
                            com.titanejstudios.tuner.integrations.Espn.Fixture(
                                "guide:$cid@${p.start}", p.title, "", p.start, null,
                                if (p.start <= t) "in" else "pre", true, n,
                            ),
                            c, p, true,
                        )
                    }
                }
            }.sortedBy { it.match.kickoff }
                // The same game on several channels: one entry, with all of them to choose from (favourites first).
                .groupBy { it.match.home.lowercase() + "@" + it.match.kickoff / 3_600_000L }.values
                .map { same ->
                    val ordered = same.sortedBy { if (it.channel?.id in favs) 0 else 1 }
                    val first = ordered.first()
                    TeamFixture(first.match, first.channel, first.programme, true, ordered.mapNotNull { f -> f.channel?.let { c -> f.programme?.let { c to it } } })
                }
                // A game already in ESPN's list isn't listed twice.
                .filter { g -> fromEspn.none { it.programme?.start == g.match.kickoff && it.programme?.title == g.match.home } }
            (fromEspn + guideOnly).sortedBy { it.match.kickoff }.take(15)
        }
        teamFixtures = result
        teamCatchup = pastGames
        // "Newcastle v Arsenal today at 17:30 on Sky Sports Main Event" – once per match.
        val today = result.firstOrNull { it.channel != null && it.match.kickoff in t..(t + 14 * 3_600_000L) }
        if (today != null && today.match.id !in store.teamAnnounced) {
            store.teamAnnounced = (store.teamAnnounced + today.match.id).toList().takeLast(50).toSet()
            showNotice("⚽ ${today.title} today at ${hhmm(today.match.kickoff)} on ${today.channel?.name}")
        }
    }

    // ---------------- Your usual channel ----------------

    /** Is there a channel you normally watch around now? Offered on Home when Flicker opens. */
    fun checkUsual() {
        usualOffer = null
        if (!offerUsual || mode != null || allChannels.isEmpty()) return
        val id = com.titanejstudios.tuner.data.Habits.usual(store.habits(activeProfile.id), System.currentTimeMillis()) ?: return
        val ch = allChannelsById(id) ?: return
        if (!isAllowed(ch)) return
        usualOffer = ch
    }

    fun playUsual() {
        val ch = usualOffer ?: return
        usualOffer = null
        play(ch, channelsFor(ALL))
    }

    fun toggleScrubPreviews() {
        scrubPreviewsIptv = !scrubPreviewsIptv
        store.scrubPreviewsIptv = scrubPreviewsIptv
    }

    fun toggleOfferUsual() {
        offerUsual = !offerUsual
        store.offerUsual = offerUsual
        if (offerUsual) checkUsual() else usualOffer = null
    }

    // ---------------- Home-screen widget (phones) ----------------

    /** Saves your first four favourites with the next few hours of their guide for the "On now" widget. */
    private fun updateWidget() {
        if (!isPhone || allChannels.isEmpty()) return
        val now = System.currentTimeMillis()
        val chs = favourites.mapNotNull { allChannelsById(it) }.take(4)
        val snap = com.titanejstudios.tuner.tv.NowWidget.snapshot(
            chs.map { c ->
                Triple(c.id, c.name, programmesFor(c.id).filter { it.stop > now && it.start < now + 12 * 3600_000L }.take(12).map { Triple(it.start, it.stop, it.title) })
            }
        )
        if (snap == store.widgetSnapshot) return
        store.widgetSnapshot = snap
        com.titanejstudios.tuner.tv.NowWidget.refresh(app)
    }

    // ---------------- Downloads (phones) ----------------

    fun downloadMovie(m: VodItem) {
        showNotice(downloader.start(m.id, m.name, null, m.poster, null, m.url))
    }

    fun downloadEpisode(e: Episode) {
        val s = openSeries
        showNotice(downloader.start(e.id, s?.name ?: e.title, "S${e.season} E${e.number}  ${e.title}", e.image ?: s?.cover, s?.id, e.url))
    }

    fun playDownload(d: com.titanejstudios.tuner.features.DownloadEntry) {
        if (!java.io.File(d.file).exists()) {
            showNotice("That download is missing – it may have been deleted")
            return
        }
        episodeQueue = emptyList()
        startVod(PlayMode.Vod(d.contentId, d.title, d.subtitle, d.poster, Uri.fromFile(java.io.File(d.file)).toString(), d.seriesId), fromStart = false)
    }

    fun removeDownload(contentId: String) {
        downloader.remove(contentId)
        showNotice("Download deleted")
    }

    // ---- Pick on the phone, watch on the TV ----

    /** Something waiting for you to choose which TV it goes to (more than one found). */
    var tvChoice by mutableStateOf<JSONObject?>(null); private set

    // A channel is sent with its id, stream address and name: devices that added the playlist
    // separately give the same channel different ids, so the other device matches on the rest.
    fun liveJson(ch: Channel): JSONObject = JSONObject().put("kind", "live").put("channel", ch.id)
        .put("name", ch.name).put("url", ch.url).put("number", ch.number)

    fun filmJson(m: VodItem): JSONObject = JSONObject().put("kind", "vod").put("id", m.id).put("title", m.name)
        .put("sub", m.category).put("poster", m.poster).put("url", m.url).put("pos", resume[m.id]?.positionMs ?: 0L)

    fun episodeJson(e: Episode): JSONObject {
        val s = openSeries
        return JSONObject().put("kind", "vod").put("id", e.id).put("title", s?.name ?: e.title)
            .put("sub", "S${e.season} E${e.number}  ${e.title}").put("poster", e.image ?: s?.cover).put("url", e.url)
            .put("series", s?.id).put("pos", resume[e.id]?.positionMs ?: 0L)
    }

    fun catchupJson(ch: Channel, p: Programme): JSONObject = JSONObject().put("kind", "catchup").put("channel", ch.id)
        .put("name", ch.name).put("url", ch.url).put("number", ch.number)
        .put("start", p.start).put("stop", p.stop).put("title", p.title).put("category", p.category).put("pos", 0L)

    /** Plays [what] on a linked TV (asks which, if there are several). */
    fun watchOnTv(what: JSONObject) {
        if (!link.linked) {
            showNotice("Link your TV first: Settings › Linked devices")
            return
        }
        fun go() {
            val tvs = link.tvs
            when {
                tvs.isEmpty() -> showNotice("No TV found – is Flicker open on it?")
                tvs.size == 1 -> sendToTv(tvs[0], what)
                else -> tvChoice = what
            }
        }
        if (link.tvs.isEmpty()) {
            showNotice("Looking for your TV…")
            link.search { go() }
        } else go()
    }

    fun sendToTv(tv: com.titanejstudios.tuner.features.LinkFeature.Peer, what: JSONObject) {
        tvChoice = null
        link.send(tv, what) { problem -> showNotice(problem ?: "▶ Playing on ${tv.name}") }
    }

    fun cancelTvChoice() {
        tvChoice = null
    }

    fun acceptCloudPrompt() {
        cloudPrompt = false
        store.cloudPrompted = true
        navigate(Screen.Cloud)
        cloud.signIn()
    }

    fun dismissCloudPrompt() {
        cloudPrompt = false
        store.cloudPrompted = true
    }

    fun linkShowCode() = link.showCode()
    fun linkFindCodes() = link.findCodes()

    // ---------------- Keeping TVs in sync & NAS backups (5.4) ----------------

    fun toggleSync() {
        if (!syncEnabled && nasUrl == null) {
            showNotice("Set up a network drive first (Settings › Recordings › Network drive)")
            return
        }
        syncEnabled = !syncEnabled
        store.syncEnabled = syncEnabled
        if (syncEnabled) {
            syncStatus = "Syncing…"
            startSync()
        } else {
            syncJob?.cancel()
            syncStatus = null
        }
    }

    /** Remember that a list changed here, and sync it soon. */
    private fun markChanged(section: String) {
        // Stamped even without the network drive: linked devices use it to see which list is newest.
        store.setSyncStamp(section, System.currentTimeMillis())
        cloud.syncSoon()
        if (!syncEnabled) return
        scheduleSync(15_000L)
    }

    private fun scheduleSync(delayMs: Long) {
        if (!syncEnabled || SafeMode.active) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(delayMs)
            syncNow()
            // Then every 10 minutes while Tuner is open.
            while (true) {
                delay(10 * 60_000L)
                if (inForeground) syncNow()
            }
        }
    }

    private fun startSync() = scheduleSync(2_000L)

    private suspend fun syncNow() {
        val r = withContext(Dispatchers.IO) { runCatching { TvSync(store).sync() } }
        r.onSuccess { out ->
            syncStatus = "Synced at ${hhmm(System.currentTimeMillis())}"
            if (out.changedHere.isNotEmpty()) applySyncedChanges(out.changedHere)
        }.onFailure {
            syncStatus = "Couldn't reach the network drive: ${it.message ?: it.javaClass.simpleName}"
            AppLog.warn("TV sync failed", it)
        }
    }

    private fun applySyncedChanges(changed: Set<String>) {
        if (TvSync.FAVOURITES in changed) {
            favourites = store.favourites(activeProfile.id)
            favouriteSet = favourites.toSet()
            publishFavouritesRow()
        }
        if (TvSync.WATCHLIST in changed) watchlist = Watchlist.decode(store.watchlistJson)
        if (TvSync.FOLLOWED in changed) followedSeries = Watchlist.decodeFollowed(store.followedSeriesJson)
        if (TvSync.RESUME in changed) resume = store.resume
        if (com.titanejstudios.tuner.integrations.LinkSync.PICKS in changed) familyPicks = Watchlist.decodePicks(store.familyPicksJson)
        AppLog.info("Synced from another TV: " + changed.joinToString())
    }

    fun toggleNasBackup() {
        nasBackupOn = !nasBackupOn
        store.nasBackup = nasBackupOn
    }

    fun backupToNasNow() {
        if (nasUrl == null) {
            showNotice("Set up a network drive first (Settings › Recordings › Network drive)")
            return
        }
        showNotice("Backing up to the network drive…")
        viewModelScope.launch {
            val name = android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL
            val r = withContext(Dispatchers.IO) { runCatching { NasBackup.save(store, name) } }
            r.onSuccess { showNotice("Backed up as ${it ?: "?"} in \"Flicker backups\"") }
                .onFailure { showNotice("Backup failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    val nasBackupLabel: String
        get() = when {
            nasUrl == null -> "Needs a network drive"
            !nasBackupOn -> "Off"
            store.nasBackupAt == 0L -> "Nightly"
            else -> "Nightly · last ${dayLabel(store.nasBackupAt)}"
        }

    // ---------------- Home Assistant: "what's on?" (5.4) ----------------

    override fun whatsOnJson(kind: String, time: String): String {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        fun at(h: Int, dayOffset: Int = 0): Long {
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        val w = time.trim().lowercase(Locale.UK)
        val (whenAt, whenLabel) = when {
            w.isEmpty() || w == "now" -> now to "now"
            w == "tonight" -> (if (now < at(21)) at(21) else now) to "tonight"
            w == "tomorrow" -> at(20, 1) to "tomorrow night"
            Regex("^(\\d{1,2})\\s*pm$").matches(w) -> {
                val h = w.filter { it.isDigit() }.toInt() % 12 + 12
                (if (at(h) < now) at(h, 1) else at(h)) to "at ${h - 12}pm"
            }
            else -> now to "now"
        }
        val k = kind.trim().lowercase(Locale.UK)
        val genre = when {
            k.isEmpty() || k == "anything" -> null
            k.startsWith("sport") || k == "football" || k == "rugby" || k == "cricket" -> "Sport"
            k.startsWith("film") || k.startsWith("movie") -> "Film"
            k.startsWith("news") -> "News"
            k.startsWith("kid") || k.startsWith("child") || k.startsWith("cartoon") -> "Kids"
            k.startsWith("doc") || k.startsWith("factual") -> "Factual"
            k.startsWith("drama") -> "Drama"
            k.startsWith("music") -> "Music"
            else -> "Entertainment"
        }
        val chans = allowedAll
        val guide = epg
        var picks = WhatsOn.list(chans, guide, whenAt, genre, favouriteSet, favouritesOnly = false, max = 40)
        // "What football's on?" – narrow sport down to that sport when the guide says.
        if (k == "football" || k == "rugby" || k == "cricket") {
            picks = picks.filter { (Sports.classify(it.programme) ?: "").lowercase(Locale.UK).contains(k) }
        }
        val items = org.json.JSONArray()
        picks.take(20).forEach { pk ->
            items.put(
                org.json.JSONObject().put("channel", pk.channel.name).put("channel_id", pk.channel.id)
                    .put("number", channelNumberFor(pk.channel)).put("title", pk.programme.title)
                    .put("start", pk.programme.start / 1000).put("stop", pk.programme.stop / 1000)
                    .put("category", pk.programme.category)
            )
        }
        val top = picks.take(4)
        val what = if (k.isEmpty()) "" else "$k "
        val speech = if (top.isEmpty()) "I couldn't find any ${what}on $whenLabel."
        else "On $whenLabel: " + top.joinToString("; ") { "${it.programme.title} on ${it.channel.name}" } +
            (if (picks.size > top.size) ", and ${picks.size - top.size} more." else ".")
        return org.json.JSONObject().put("when", whenLabel).put("kind", k).put("items", items).put("speech", speech).toString()
    }

    private fun channelNumberFor(c: Channel): Int =
        if (c.number > 0) c.number else allowedAll.indexOfFirst { it.id == c.id } + 1

    // ---------------- Update pop-up ----------------

    /** The update to offer now – never over full-screen video. */
    val updateOffer: com.titanejstudios.tuner.apps.Updater.Release?
        get() = if (screen == Screen.Player || screen == Screen.MultiView || inPip) null else updates.offered

    fun installUpdateNow() {
        showNotice("Downloading the update…")
        updates.installOffered()
    }

    fun updateLater() = updates.later()

    // ---------------- Open on start-up ----------------

    var openOnBoot by mutableStateOf(store.openOnBoot); private set

    val canOpenOnBoot: Boolean
        get() = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(app)

    /** Settings › Open Tuner when the TV turns on. Asks for "Display over other apps" if needed. */
    fun toggleOpenOnBoot() {
        openOnBoot = !openOnBoot
        store.openOnBoot = openOnBoot
        if (!openOnBoot) {
            showNotice("Flicker won't open by itself any more")
            return
        }
        if (canOpenOnBoot) {
            showNotice("Flicker will open when the TV starts up")
            return
        }
        // Android only lets apps open themselves at start-up with this permission.
        val asked = runCatching {
            app.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        showNotice(
            if (asked) "Turn on \"Allow display over other apps\" for Flicker, then press BACK"
            else "This TV hides that setting – see the README (Open on start-up) for the one-line fix"
        )
    }
}
