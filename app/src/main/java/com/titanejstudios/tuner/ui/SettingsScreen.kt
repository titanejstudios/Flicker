package com.titanejstudios.tuner.ui

import com.titanejstudios.tuner.backupToStorage
import com.titanejstudios.tuner.cancelHealthCheck
import com.titanejstudios.tuner.clearDead
import com.titanejstudios.tuner.cycleArtSaver
import com.titanejstudios.tuner.cycleAutoDelete
import com.titanejstudios.tuner.cycleStillWatching
import com.titanejstudios.tuner.openStats
import com.titanejstudios.tuner.restoreFromStorage
import com.titanejstudios.tuner.runHealthCheck
import com.titanejstudios.tuner.setNas
import com.titanejstudios.tuner.toggleAudioPassthrough
import com.titanejstudios.tuner.toggleHealthNightly
import com.titanejstudios.tuner.toggleHealthScope
import com.titanejstudios.tuner.toggleHideDead
import com.titanejstudios.tuner.toggleNasKeepLocal
import com.titanejstudios.tuner.toggleRemote
import com.titanejstudios.tuner.toggleStreamCompatibility
import com.titanejstudios.tuner.toggleTimeshift
import com.titanejstudios.tuner.toggleTintFromPicture
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import com.titanejstudios.tuner.integrations.PhoneSetupServer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.ALL_SOURCES
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Profile
import com.titanejstudios.tuner.data.Source
import com.titanejstudios.tuner.data.Store
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: TunerViewModel) {
    // Kids' profiles get a small page of their own; the rest needs a grown-up (the parental PIN).
    if (vm.activeProfile.kids && !vm.grownUpSettings) {
        KidsSettingsScreen(vm)
        return
    }
    var menuFor by remember { mutableStateOf<Source?>(null) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    var profileMenu by remember { mutableStateOf<Profile?>(null) }
    /** Network drive set-up: 1 = address, 2 = user name, 3 = password. */
    var nasStep by remember { mutableStateOf(0) }
    /** Typing a key: "tmdb" or "subs". */
    var askKey by remember { mutableStateOf<String?>(null) }

    val restorer = rememberFocusRestorer()
    RestoreFocusWhenClosed(menuFor != null || confirmDelete != null || profileMenu != null, restorer)
    val first = remember { FocusRequester() }
    val groupsFirst = remember { FocusRequester() }
    val narrow = narrowScreen()
    LaunchedEffect(Unit) {
        delay(60)
        // The group menu on TVs; the first row on phones.
        runCatching { if (narrow) first.requestFocus() else groupsFirst.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = if (narrowScreen()) 12.dp else 64.dp, vertical = if (narrowScreen()) 12.dp else 32.dp)
    ) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(16.dp))
        // A menu of groups on the left (chips along the top on phones), that group's settings on the right.
        var group by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsGroups.all.first().key) }
        var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        var searching by remember { mutableStateOf(false) }
        var advancedFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (query.isBlank()) "🔍 Search settings" else "🔍 \"$query\"", { searching = true }, compact = true)
            if (query.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                PillButton("✕ Clear", { query = "" }, compact = true)
            }
        }
        if (searching) {
            TextInputDialog(
                title = "Search settings",
                initial = query,
                hint = "e.g. subtitles, lite, PIN, recordings",
                label = "Search",
                onDismiss = { searching = false },
                onDone = { searching = false; query = it.trim() },
            )
        }
        Spacer(Modifier.height(10.dp))
        if (narrow && query.isBlank()) {
            CategoryChips(SettingsGroups.all.map { it.key }, group, label = { k -> SettingsGroups.all.first { it.key == k }.label.substringAfter("  ") }) { group = it }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxSize()) {
        if (!narrow && query.isBlank()) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(SettingsGroups.all.size, key = { SettingsGroups.all[it].key }) { i ->
                    val g = SettingsGroups.all[i]
                    FocusSurface(
                        memoryKey = "sgrp|${g.key}",
                        onClick = { group = g.key },
                        onFocused = { group = g.key },
                        selected = g.key == group,
                        baseColor = TunerColors.Panel,
                        modifier = Modifier.fillMaxWidth().height(52.dp).then(if (i == 0) Modifier.focusRequester(groupsFirst) else Modifier),
                    ) { f ->
                        Text(
                            g.label, modifier = Modifier.padding(horizontal = 14.dp),
                            color = if (f || g.key == group) Color.White else TunerColors.TextSecondary,
                            fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalSettingsQuery provides query) {
        LazyColumn(Modifier.widthIn(max = 980.dp), verticalArrangement = if (query.isBlank()) Arrangement.spacedBy(6.dp) else Arrangement.Top) {
          with(SettingsScope(this, group, query, advancedFor == group) { advancedFor = group }) {
            section("Playlists")
            if (vm.sources.size > 1) {
                item {
                    SettingRow(
                        title = "Use all playlists together",
                        detail = if (vm.activeSourceId == ALL_SOURCES) "✓ In use" else null,
                        modifier = Modifier.focusRequester(first),
                        onClick = { vm.selectSource(ALL_SOURCES) },
                    )
                }
            }
            items(vm.sources, key = { it.id }) { s ->
                val inUse = vm.activeSourceId == s.id || (vm.activeSourceId == ALL_SOURCES && vm.sources.size == 1)
                SettingRow(
                    title = s.name,
                    subtitle = when (s) {
                        is Source.M3u -> "M3U playlist"
                        is Source.Xtream -> "Xtream Codes · ${s.server}"
                    },
                    detail = if (inUse) "✓ In use" else null,
                    modifier = (if (vm.sources.size == 1) Modifier.focusRequester(first) else Modifier).then(restorer.mod(s.id)),
                    onClick = {
                        restorer.key = s.id
                        menuFor = s
                    },
                )
            }
            item { SettingRow("Add a playlist", onClick = { vm.startAddSource() }) }
            item {
                SettingRow(
                    "Refresh channels and TV guide now",
                    subtitle = vm.status ?: "Also refreshes automatically every night at about 4am",
                    onClick = { vm.refresh() },
                )
            }
            item {
                SettingRow(
                    "Update channels when Flicker starts",
                    subtitle = if (vm.refreshOnStart) "The channel list, if it's over a day old · the TV guide always updates once a day in the background"
                    else "Overnight only – the TV guide updates once a day in the background",
                    detail = if (vm.refreshOnStart) "On" else "Off",
                    onClick = { vm.toggleRefreshOnStart() },
                )
            }
            // Backup guide (remove with the module): "Use extra guide sources" and fixing its matches.
            item { BackupGuideSettings(vm) }

            item {
                SettingRow(
                    if (vm.isPhone) "💻 Set up from a computer" else "📱 Set up from your phone",
                    subtitle = if (vm.isPhone) "Paste long playlist links, add a VPN, back up and restore from a computer's browser"
                    else "Add playlists, Home Assistant webhook, backup and restore – no typing on the TV",
                    onClick = { vm.navigate(Screen.PhoneSetup) },
                )
            }

            section("Profiles")
            items(vm.profiles, key = { "profile-" + it.id }) { p ->
                SettingRow(
                    (if (p.kids) "🧸 " else "👤 ") + p.name,
                    subtitle = if (p.kids) {
                        "Kids · ${p.allowedGroups.size} groups" + (if (p.dailyLimitMinutes > 0) " · ${p.dailyLimitMinutes} min a day" else " · no time limit")
                    } else null,
                    detail = if (p.id == vm.activeProfile.id) "✓ Watching" else null,
                    modifier = restorer.mod("profile-" + p.id),
                    onClick = {
                        restorer.key = "profile-" + p.id
                        profileMenu = p
                    },
                )
            }
            item { SettingRow("Add a profile", subtitle = "Each profile has its own favourites; kids profiles can have limits", onClick = { vm.startAddProfile() }) }
            section("Start-up")
            item {
                SettingRow(
                    "When Flicker opens",
                    subtitle = "Home, the TV Guide on the category and channel you left, or straight into the last channel",
                    detail = vm.startScreenLabel,
                    onClick = { vm.cycleStartScreen() },
                )
            }

            item {
                SettingRow(
                    "This device is a",
                    subtitle = "Phone: touch controls, a menu bar along the bottom, and the video turns sideways to fill the screen",
                    detail = if (vm.isPhone) "📱 Phone" else "📺 TV",
                    onClick = { vm.chooseDevice(if (vm.isPhone) "tv" else "phone") },
                )
            }

            section("Devices & sync")
            if (vm.isPhone) item {
                SettingRow(
                    "📺 TV remote",
                    subtitle = "Control a linked TV from this phone: buttons, and its channels with what's on",
                    detail = if (vm.link.linked) null else "Link first",
                    onClick = { vm.navigate(Screen.Remote) },
                )
            }

            if (vm.isPhone) item {
                SettingRow(
                    "⬇ Downloads",
                    subtitle = "Films and episodes saved on this phone to watch offline",
                    detail = vm.downloads.size.takeIf { it > 0 }?.toString(),
                    onClick = { vm.navigate(Screen.Downloads) },
                )
            }

            item {
                SettingRow(
                    "▶ YouTube",
                    subtitle = "Search YouTube and play it here; sign in for your subscriptions on Home",
                    detail = if (vm.youtube.signedIn) (vm.youtube.email ?: "Signed in") else "Not signed in",
                    onClick = { vm.navigate(Screen.YouTube) },
                )
            }

            item {
                SettingRow(
                    "☁ Sync between houses",
                    subtitle = "Through your own Google Drive: carry on films and episodes, My list and your teams at another house",
                    detail = when {
                        vm.cloud.signedIn -> vm.cloud.house
                        else -> "Off"
                    },
                    onClick = { vm.navigate(Screen.Cloud) },
                )
            }

            item {
                SettingRow(
                    "🔗 Linked devices",
                    subtitle = "Send what's playing to another TV or phone, and carry on where you left off",
                    detail = when {
                        !vm.link.linked -> "Not linked"
                        vm.link.peers.isNotEmpty() -> "${vm.link.peers.size} found"
                        else -> "Linked"
                    },
                    onClick = { vm.navigate(Screen.Link) },
                )
            }

            section("Easier to see and hear")
            item {
                SettingRow(
                    "Text size",
                    subtitle = "Bigger text everywhere in Flicker, on this device",
                    detail = vm.textScaleLabel,
                    onClick = { vm.cycleTextScale() },
                )
            }

            item {
                SettingRow(
                    "🗣 Clearer speech",
                    subtitle = "Lifts voices over music and effects, and turns down rumble – also in the player's ⋮ menu",
                    detail = if (vm.clearSpeech) "On" else "Off",
                    onClick = { vm.toggleClearSpeech() },
                )
            }

            section("Skipping & previews")
            item {
                SettingRow(
                    "Preview pictures when skipping (IPTV)",
                    subtitle = "Little pictures as you scrub through IPTV films and catch-up. Needs a second connection – " +
                        "turn it off if your provider allows only one. Always on for Plex, downloads and recordings.",
                    detail = if (vm.scrubPreviewsIptv) "On" else "Off",
                    onClick = { vm.toggleScrubPreviews() },
                )
            }

            section("Your usual channel")
            item {
                SettingRow(
                    "Offer my usual channel",
                    subtitle = "When you open Flicker at a time you usually watch something (the news at 6, say), Home offers it",
                    detail = if (vm.offerUsual) "On" else "Off",
                    onClick = { vm.toggleOfferUsual() },
                )
            }

            section("VPN")
            item {
                SettingRow(
                    "VPN",
                    subtitle = "Flicker's own VPN (Surfshark and other WireGuard providers) – only Flicker's streams use it",
                    detail = when {
                        vm.vpn.connected -> "🔒 Connected"
                        vm.vpn.enabled -> "On – not connected"
                        else -> "Off"
                    },
                    onClick = { vm.navigate(Screen.Vpn) },
                )
            }

            section("Home & recommendations")
            item {
                SettingRow(
                    "Recommendations",
                    subtitle = "What Flicker has learned you like – and a reset if it gets it wrong",
                    detail = if (vm.learnTaste) "Learning" else "Off",
                    onClick = { vm.navigate(Screen.Recommendations) },
                )
            }

            item {
                SettingRow(
                    "Languages & countries",
                    subtitle = "Hide everything that isn't in English – or pick countries one by one",
                    detail = if (vm.hiddenLanguages.isEmpty()) "Everything" else "${vm.hiddenLanguages.size} hidden",
                    onClick = { vm.navigate(Screen.Languages) },
                )
            }

            item {
                SettingRow(
                    "Home rows",
                    subtitle = "Show, hide and reorder Home's rows for this profile – and whether they change with the time of day",
                    detail = if (vm.homeRowsHidden.isEmpty()) "All shown" else "${vm.homeRowsHidden.size} hidden",
                    onClick = { vm.navigate(Screen.HomeRows) },
                )
            }

            section("When the TV turns on")
            item {
                SettingRow(
                    "Open Flicker when the TV turns on",
                    subtitle = if (vm.openOnBoot && !vm.canOpenOnBoot) "Needs \"Display over other apps\" allowed – press to set it"
                    else "After the TV fully restarts (not when it wakes from standby)",
                    detail = if (vm.openOnBoot) "On" else "Off",
                    onClick = { vm.toggleOpenOnBoot() },
                )
            }

            section("Playback")
            item {
                SettingRow(
                    "Combine HD / SD copies of a channel",
                    subtitle = "Shows BBC One once and plays the best quality – change version from the player menu",
                    detail = if (vm.mergeVersions) "On" else "Off",
                    onClick = { vm.toggleMergeVersions() },
                )
            }
            item {
                SettingRow(
                    "Are you still watching?",
                    subtitle = "Asks after this long with no button pressed, then stops – saves your connection overnight",
                    detail = vm.stillWatchingLabel,
                    onClick = { vm.cycleStillWatching() },
                )
            }
            item {
                SettingRow(
                    "Pause live TV to storage (timeshift)",
                    subtitle = "Pausing keeps recording, so you can resume, rewind or skip – up to 90 minutes",
                    detail = if (vm.timeshiftEnabled) "On" else "Off",
                    onClick = { vm.toggleTimeshift() },
                )
            }
            item {
                SettingRow(
                    "Fast channel switching",
                    subtitle = "Keeps the next channel ready. Uses a 2nd connection – only if your provider allows 2+",
                    detail = if (vm.fastSwitch) "On" else "Off",
                    onClick = { vm.toggleFastSwitch() },
                )
            }

            advanced()
            item {
                SettingRow(
                    "Audio passthrough (Dolby to soundbar/amp)",
                    subtitle = "Leave off if the picture freezes while sound carries on · applies after reopening Flicker",
                    detail = if (vm.audioPassthrough) "On" else "Off",
                    onClick = { vm.toggleAudioPassthrough() },
                )
            }
            item {
                SettingRow(
                    "Stream compatibility mode",
                    subtitle = "Only for channels that never start · applies after reopening Flicker",
                    detail = if (vm.streamCompatibility) "On" else "Off",
                    onClick = { vm.toggleStreamCompatibility() },
                )
            }
            item {
                SettingRow(
                    "Match the TV's frame rate",
                    subtitle = "Switches the TV to 50 Hz for UK channels, 24 Hz for films… smoother motion. The screen may blank for a second on channel change",
                    detail = if (vm.frameRateMatch) "On" else "Off",
                    onClick = { vm.toggleFrameRateMatch() },
                )
            }

            section("Sound & subtitles")
            item {
                SettingRow(
                    "Night mode",
                    subtitle = "Quieter loud bits, clearer speech · needs Audio passthrough off",
                    detail = if (vm.nightMode) "On" else "Off",
                    onClick = { vm.toggleNightMode() },
                )
            }
            item { SettingRow("Subtitle size", detail = vm.subtitleSizeLabel, onClick = { vm.cycleSubtitleSize() }) }
            item { LiveSubtitlesSetting(vm) }
            item {
                SettingRow(
                    "Subtitle background",
                    subtitle = "A dark box behind subtitles – easier to read over bright pictures",
                    detail = if (vm.subtitleBox) "On" else "Off",
                    onClick = { vm.toggleSubtitleBox() },
                )
            }
            item { SettingRow("Sleep timer", subtitle = "Also in the player's menu (hold OK)", detail = vm.sleepLabel, onClick = { vm.setSleepTimer(if (vm.sleepAt == 0L) 60 else 0) }) }

            section("Look")
            item {
                val theme = TunerColors.themeOf(vm.themeName)
                val accent = TunerColors.accents.firstOrNull { it.first == vm.themeAccent && it.first.isNotEmpty() }?.second
                SettingRow(
                    "🎨 Colour theme",
                    subtitle = "Evening, Ocean, Cinema, Festive, High contrast and more – each profile has its own",
                    detail = theme.name + (accent?.let { " · $it" } ?: ""),
                    onClick = { vm.themePickerOpen = true },
                )
            }
            item {
                SettingRow(
                    "🪶 Lite mode",
                    // On a TV the row has one line, so just what it found; phones also get the explanation.
                    subtitle = com.titanejstudios.tuner.data.Lite.summary(vm.liteChoice) + if (vm.isPhone) "\nFor older sticks with little memory: fewer pictures held at once, the guide read a few hours at a time" else "",
                    detail = run {
                        when (vm.liteChoice) {
                            "on" -> "On"
                            "off" -> "Off"
                            else -> if (com.titanejstudios.tuner.data.Lite.lowMemoryDevice) "Auto (on)" else "Auto (off)"
                        }
                    },
                    onClick = { vm.cycleLite() },
                )
            }
            if (!vm.isPhone) item {
                SettingRow(
                    "Live picture in the TV guide",
                    subtitle = if (vm.liteOn) "Off while Lite mode is on" else "Rest on a programme that's on now and it plays in the guide's corner",
                    detail = if (vm.guidePreview && !vm.liteOn) "On" else "Off",
                    onClick = { vm.toggleGuidePreview() },
                )
            }
            item {
                SettingRow(
                    "Colours follow the picture",
                    subtitle = "Home, Films & TV and Plex take on the colours of what's highlighted",
                    detail = if (vm.tintFromPicture) "On" else "Off",
                    onClick = { vm.toggleTintFromPicture() },
                )
            }
            item {
                SettingRow(
                    "Artwork screensaver",
                    subtitle = "When nothing's playing: your Plex and programme pictures with the time. OK watches what's showing.",
                    detail = if (vm.artSaverMinutes == 0) "Off" else "After ${vm.artSaverMinutes} min",
                    onClick = { vm.cycleArtSaver() },
                )
            }

            section("Languages")
            item {
                SettingRow("Sound language", subtitle = "Picked automatically when a channel or film has several (each profile has its own)", detail = vm.languageName(vm.audioLanguage), onClick = { vm.cycleAudioLanguage() })
            }
            item {
                SettingRow(
                    "Subtitles language",
                    subtitle = "Subtitles in this language switch on by themselves when there are any",
                    detail = if (vm.subtitleLanguage == null) "Off" else vm.languageName(vm.subtitleLanguage),
                    onClick = { vm.cycleSubtitleLanguage() },
                )
            }
            item {
                SettingRow(
                    "Audio description",
                    subtitle = "Picks the soundtrack that describes what's happening on screen, when there is one (for this profile)",
                    detail = if (vm.audioDescription) "On" else "Off",
                    onClick = { vm.toggleAudioDescription() },
                )
            }

            section("Films & series")
            item {
                SettingRow(
                    "Film info (TMDB key)",
                    subtitle = "Free from themoviedb.org › Settings › API. Gives posters, plots, cast and trailers",
                    detail = if (vm.tmdbKey != null) "Set" else "Not set",
                    onClick = { askKey = "tmdb" },
                )
            }
            item {
                SettingRow(
                    "Previously on…",
                    subtitle = "Starting an episode after a break shows what happened in the one before (needs the TMDB key)",
                    detail = if (vm.recapsOn) "On" else "Off",
                    onClick = { vm.toggleRecaps() },
                )
            }
            item {
                SettingRow(
                    "Play trailers on film info",
                    subtitle = "The trailer plays silently behind a film's details after a couple of seconds",
                    detail = if (vm.trailerAutoplay) "On" else "Off",
                    onClick = { vm.toggleTrailerAutoplay() },
                )
            }
            advanced()
            item {
                SettingRow(
                    "Subtitles online (OpenSubtitles key)",
                    subtitle = "Free from opensubtitles.com › API consumers. Then: player menu › Find subtitles online",
                    detail = if (vm.openSubtitlesKey != null) "Set" else "Not set",
                    onClick = { askKey = "subs" },
                )
            }

            section("Plex & scores")
            if (vm.traktAvailable) item {
                SettingRow(
                    "Trakt",
                    subtitle = vm.traktStatus ?: if (vm.traktUser != null) "Films and episodes you finish are logged; films you've seen there show as watched here. OK to unlink."
                    else "Log what you watch on trakt.tv, and bring your watched films across",
                    detail = vm.traktCode ?: vm.traktUser?.let { "Linked · $it" } ?: "Not linked",
                    onClick = { if (vm.traktUser != null) vm.unlinkTrakt() else vm.linkTrakt() },
                )
            }
            item {
                SettingRow(
                    "⚽ My teams",
                    subtitle = "Your teams' fixtures and which channel shows each one; their games stand out in the guide",
                    detail = vm.footballTeams.sorted().joinToString(", ").ifEmpty { "Pick" },
                    onClick = { vm.navigate(Screen.Teams) },
                )
            }
            item {
                SettingRow(
                    "Your teams' matches",
                    subtitle = "Flicker finds your teams' matches in the guide and pops up 5 minutes before kick-off (sport recordings run 30 min over)",
                    detail = vm.sportFollowLabel,
                    onClick = { vm.cycleSportFollow() },
                )
            }
            item {
                SettingRow(
                    "Plex",
                    subtitle = if (vm.plexLinked) "Linked – your Continue watching is on Home" else "Link your Plex account to see Continue watching on Home",
                    detail = if (vm.plexLinked) "Linked" else "Link",
                    onClick = { if (vm.plexLinked) vm.navigate(Screen.Plex) else vm.linkPlex() },
                )
            }
            if (vm.plexLinked) {
                item {
                    SettingRow(
                        "Play films from Plex when you have them",
                        subtitle = "A film in Films & TV that's also on your Plex plays from Plex (usually a better copy). Hold OK on a film for the IPTV copy.",
                        detail = if (vm.preferPlexFilms) "On" else "Off",
                        onClick = { vm.togglePreferPlexFilms() },
                    )
                }
                item {
                    SettingRow(
                        "Which copy to play",
                        subtitle = "When a film's in more than one place (Films and Films 4K, or your provider too). Pick \"☆ Always use…\" when asked to stop asking.",
                        detail = vm.preferredCopyLabel,
                        onClick = { if (vm.preferredCopy != null) vm.choosePreferredCopy(null) else vm.showNotice("Next time you're asked which copy, choose \"☆ Always use…\"") },
                    )
                }
            }

            section("Google TV")
            item {
                SettingRow(
                    "Channels in the TV's Live tab",
                    subtitle = vm.liveTabStatus ?: "Adds your channels and guide to the TV's own channel list (Live tab / Live Channels app)",
                    detail = vm.liveTabLabel,
                    onClick = { vm.cycleLiveTab() },
                )
            }
            item {
                SettingRow(
                    "Voice",
                    subtitle = "Say \"Hey Google, play BBC One on Flicker\" – or use tuner://play links from Home Assistant",
                    onClick = { vm.showNotice("Try: \"Hey Google, play Sky Sports Main Event on Flicker\"") },
                )
            }
            if (!vm.isPhone) item {
                SettingRow(
                    "Screensaver",
                    subtitle = "Clock + what's on now on your favourites. Choose \"Flicker – what's on\" in the TV's screensaver settings (see README)",
                    onClick = { vm.showNotice("Pick \"Flicker – what's on\" in the TV's screensaver / ambient settings") },
                )
            }

            // A phone remote for a phone makes no sense, so these rows only show on a TV.
            if (!vm.isPhone) section("Phone remote")
            if (!vm.isPhone) item {
                SettingRow(
                    "Phone remote",
                    subtitle = "Control Flicker from your phone's browser – buttons, channel list, what's on",
                    detail = if (vm.remoteEnabled) "On" else "Off",
                    onClick = { vm.toggleRemote() },
                )
            }
            if (!vm.isPhone) vm.remoteUrl?.let { url ->
                item {
                    val qr = remember(url) { PhoneSetupServer.qrCode(url, 320).asImageBitmap() }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(qr, contentDescription = "Phone remote QR code", modifier = Modifier.size(170.dp))
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text("Scan to open the remote, then add it to your home screen.", color = TunerColors.TextPrimary, fontSize = 16.sp)
                            Text(url, color = TunerColors.Accent, fontSize = 16.sp)
                            Text("Works while Flicker is open, on the same Wi-Fi.", color = TunerColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }

            section("Channel health")
            item {
                LaunchedEffect(Unit) { vm.loadAccountStreams() }
                val progress = vm.healthProgress
                SettingRow(
                    if (progress != null) "Checking channels… (OK to stop)" else "Check channels now",
                    subtitle = progress ?: (
                        "${vm.deadChannels.size} offline" +
                            (if (vm.healthCheckedAt > 0) " · last checked ${dayLabel(vm.healthCheckedAt)} ${hhmm(vm.healthCheckedAt)}" else " · never checked") +
                            (vm.accountStreams?.takeIf { it > 1 }?.let { " · your account allows $it streams, so it checks while you watch" } ?: " · stops playback while checking") +
                            " · shared with the other devices in this house"
                        ),
                    onClick = { if (progress != null) vm.cancelHealthCheck() else vm.runHealthCheck() },
                )
            }
            item {
                SettingRow(
                    "What to check",
                    subtitle = "Checking everything can take hours on big playlists",
                    detail = if (vm.healthScope == "all") "All visible channels" else "Favourites",
                    onClick = { vm.toggleHealthScope() },
                )
            }
            item {
                SettingRow(
                    "Check every night",
                    subtitle = "After the guide refresh (about 4am), for up to 7 minutes",
                    detail = if (vm.healthNightly) "On" else "Off",
                    onClick = { vm.toggleHealthNightly() },
                )
            }
            item {
                SettingRow(
                    "Hide offline channels",
                    subtitle = "Rechecked every few hours, and any with a match on in the guide come back · hold OK on a channel for \"Match-day channel\"",
                    detail = if (vm.hideDead) "On" else "Off",
                    onClick = { vm.toggleHideDead() },
                )
            }
            if (vm.deadChannels.isNotEmpty()) {
                item { SettingRow("Clear the offline list", onClick = { vm.clearDead() }) }
            }

            section("Channels")
            item {
                SettingRow(
                    "Manage groups and channels",
                    subtitle = "Hide, reorder or lock groups · reorder favourites · unhide channels",
                    onClick = { vm.navigate(Screen.Manage) },
                )
            }

            section("Parental controls")
            item {
                SettingRow(
                    if (vm.pin == null) "Set a parental PIN" else "Change PIN",
                    subtitle = "Locked groups need the PIN once each time Flicker is opened",
                    onClick = { vm.changePin() },
                )
            }
            if (vm.pin != null) {
                item { SettingRow("Remove PIN", subtitle = "Unlocks every group", onClick = { vm.removePin() }) }
            }

            section("Recordings")
            item {
                SettingRow(
                    "Recordings",
                    subtitle = "Saved to ${vm.recordingDir}",
                    onClick = { vm.navigate(Screen.Recordings) },
                )
            }
            item {
                SettingRow(
                    "Delete old recordings",
                    subtitle = "Finished recordings older than this are deleted overnight (📌 kept ones stay)",
                    detail = if (vm.autoDeleteDays == 0) "Never" else "After ${vm.autoDeleteDays} days",
                    onClick = { vm.cycleAutoDelete() },
                )
            }
            item {
                SettingRow(
                    "Network drive (NAS / PC share)",
                    subtitle = vm.nasStatus ?: (vm.nasUrl?.let { "Copies finished recordings to $it" } ?: "Copy finished recordings to a shared folder – handy if Plex watches that folder"),
                    detail = if (vm.nasUrl == null) "Set up" else "Change",
                    onClick = { nasStep = 1 },
                )
            }
            if (vm.nasUrl != null) {
                item {
                    SettingRow(
                        "Keep a copy on the TV too",
                        subtitle = "Off saves space on the TV, but those recordings then play from Plex (or another app), not Flicker",
                        detail = if (vm.nasKeepLocal) "On" else "Off",
                        onClick = { vm.toggleNasKeepLocal() },
                    )
                }
            }
            item {
                SettingRow(
                    "Series links",
                    subtitle = if (vm.seriesLinks.isEmpty()) "None yet – hold OK on a programme in the guide and pick Series link"
                    else vm.seriesLinks.joinToString(" · ") { it.title },
                    detail = vm.seriesLinks.size.toString(),
                    onClick = { vm.navigate(Screen.Recordings) },
                )
            }

            section("Apps")
            item {
                SettingRow(
                    "Apps on this TV",
                    subtitle = "Plex, BBC iPlayer, Netflix and the rest – choose which appear on Home",
                    onClick = { vm.navigate(Screen.Apps) },
                )
            }

            section("This TV")
            item { SettingRow("Watch time", subtitle = "What each profile watched this week", onClick = { vm.openStats() }) }
            item { SettingRow(if (vm.isPhone) "Copy to another device" else "Copy to another TV", subtitle = if (vm.isPhone) "Send playlists, favourites and settings to a TV or phone on the same Wi-Fi" else "Send playlists, favourites and settings to a TV on the same Wi-Fi", onClick = { vm.startShareSend() }) }
            item { SettingRow(if (vm.isPhone) "Copy from another device" else "Copy from another TV", subtitle = if (vm.isPhone) "Get everything from a TV or phone running Flicker" else "Get everything from another TV running Flicker", onClick = { vm.startShareReceive() }) }

            section("Help")
            item {
                SettingRow(
                    "Check everything",
                    subtitle = if (vm.isPhone) "Tries each part of Flicker on this phone and says what works and what doesn't" else "Tries each part of Flicker on this TV and says what works and what doesn't",
                    onClick = { vm.openSelfTest() },
                )
            }
            item {
                SettingRow(
                    "🛠 Fix it",
                    subtitle = "Something not working? Checks the internet, VPN and your provider, restarts what's playing and reloads an old guide",
                    detail = if (vm.fixing) "Checking…" else null,
                    onClick = { vm.fixIt() },
                )
            }
            item {
                SettingRow(
                    "Problem log",
                    subtitle = "Stream drops, backup links used, failed updates and crashes – newest first",
                    onClick = { vm.openLog() },
                )
            }
            item {
                if (vm.safeMode) {
                    SettingRow(
                        "Safe mode is ON",
                        subtitle = vm.lastCrash?.let { "After crashes: $it" } ?: "Newer extras are switched off",
                        detail = "Turn off",
                        onClick = { vm.leaveSafeMode() },
                    )
                } else {
                    SettingRow(
                        "Start in safe mode next time",
                        subtitle = "If something misbehaves: opens with night mode, lip sync, Live tab, Plex and other extras off",
                        onClick = { vm.startInSafeModeNextTime() },
                    )
                }
            }

            section("Home Assistant")
            item {
                SettingRow(
                    "Webhook",
                    subtitle = vm.webhookUrl ?: "Not set – add it with \"Set up from your phone\"",
                    detail = if (vm.webhookUrl != null) "On" else null,
                    onClick = { vm.navigate(Screen.PhoneSetup) },
                )
            }
            item {
                SettingRow(
                    "Cinema lights",
                    subtitle = "Sends \"lights\" dim / restore events when you watch and pause – see the README for the automation",
                    detail = com.titanejstudios.tuner.integrations.CinemaLights.label(vm.cinemaLights),
                    onClick = { vm.cycleCinemaLights() },
                )
            }

            section("Backup")
            item {
                SettingRow(
                    "Keep TVs in sync",
                    subtitle = vm.syncStatus ?: "Favourites, My list and where you got to, shared through your network drive",
                    detail = if (vm.syncEnabled) "On" else if (vm.nasUrl == null) "Needs a network drive" else "Off",
                    onClick = { vm.toggleSync() },
                )
            }
            item {
                SettingRow(
                    "Nightly backup to the network drive",
                    subtitle = "Keeps the last 7 in \"Flicker backups\" – press to back up now",
                    detail = vm.nasBackupLabel,
                    onClick = { vm.backupToNasNow() },
                )
            }
            if (vm.nasUrl != null) {
                item {
                    SettingRow(
                        if (vm.nasBackupOn) "Turn nightly backups off" else "Turn nightly backups on",
                        onClick = { vm.toggleNasBackup() },
                    )
                }
            }
            item {
                SettingRow(
                    "Save a backup",
                    subtitle = "Playlists, profiles, favourites, PIN, reminders and guide fixes – also downloadable from your phone",
                    onClick = { vm.backupToStorage() },
                )
            }
            item { SettingRow("Restore the saved backup", onClick = { vm.restoreFromStorage() }) }

            section("Reminders")
            if (vm.reminders.isEmpty()) {
                item { SettingRow("No reminders set", subtitle = "In the TV guide, press OK on an upcoming programme", onClick = {}) }
            } else {
                items(vm.reminders, key = { it.channelId + it.start }) { r ->
                    SettingRow(
                        r.title,
                        subtitle = "${dayLabel(r.start)} ${hhmm(r.start)} · ${r.channelName}",
                        detail = "OK to cancel",
                        onClick = {
                            vm.allChannels.firstOrNull { it.id == r.channelId }?.let { ch ->
                                vm.toggleReminder(ch, com.titanejstudios.tuner.data.Programme(r.start, r.stop, r.title, null))
                            }
                        },
                    )
                }
            }

            section("Updates")
            item {
                SettingRow(
                    "⬆ Update Flicker",
                    subtitle = "Install a new version from your computer's browser, or from GitHub · this is ${vm.installedVersion}",
                    onClick = { vm.openUpdates() },
                )
            }

            section("About")
            item {
                SettingRow(
                    "Flicker ${vm.installedVersion}",
                    subtitle = "Titan EJ Studios · ${vm.allChannels.size} channels loaded" + (if (vm.safeMode) " · safe mode" else ""),
                    onClick = {},
                )
            }
          }
        }
        }
        }
    }

    when (askKey) {
        "tmdb" -> TextInputDialog(
            title = "TMDB key",
            initial = vm.tmdbKey.orEmpty(),
            hint = "Your API key (or the long read access token) – leave empty to turn off",
            label = "Key",
            onDismiss = { askKey = null },
        ) {
            askKey = null
            vm.saveTmdbKey(it)
        }
        "subs" -> TextInputDialog(
            title = "OpenSubtitles key",
            initial = vm.openSubtitlesKey.orEmpty(),
            hint = "Your API consumer key – leave empty to turn off",
            label = "Key",
            onDismiss = { askKey = null },
        ) {
            askKey = null
            vm.saveOpenSubtitlesKey(it)
        }
    }

    when (nasStep) {
        1 -> TextInputDialog(
            title = "Network drive address",
            initial = vm.nasUrl ?: "smb://",
            hint = "e.g. smb://192.168.1.20/Media/TV Recordings – leave empty to turn off",
            label = "Address",
            keyboard = KeyboardType.Uri,
            onDismiss = { if (nasStep == 1) nasStep = 0 },
        ) { url ->
            if (url.isBlank() || url == "smb://") {
                vm.setNas(null, null, null)
                nasStep = 0
            } else {
                vm.setNas(url, null, null)
                nasStep = 2
            }
        }
        2 -> TextInputDialog(
            title = "User name for the drive",
            initial = vm.nasUser.orEmpty(),
            hint = "Leave empty if the folder doesn't need a log-in",
            label = "User name",
            onDismiss = { if (nasStep == 2) nasStep = 0 },
        ) { user ->
            vm.setNas(vm.nasUrl, user, null)
            nasStep = if (user.isBlank()) 0 else 3
        }
        3 -> TextInputDialog(
            title = "Password for the drive",
            label = "Password",
            password = true,
            keyboard = KeyboardType.Password,
            onDismiss = { if (nasStep == 3) nasStep = 0 },
        ) { pass ->
            vm.setNas(vm.nasUrl, null, pass)
            nasStep = 0
        }
    }

    menuFor?.let { s ->
        MenuDialog(
            title = s.name,
            items = listOfNotNull(
                MenuItem("Use this playlist") { vm.selectSource(s.id) },
                MenuItem("Edit") { vm.startEditSource(s) },
                MenuItem("Refresh now") { vm.refresh(listOf(s)) },
                MenuItem("🕑 Guide times", vm.guideShiftLabel(s.id) + " · OK to change (if the guide is an hour out)") { vm.cycleGuideShift(s.id) },
                MenuItem("Delete") { confirmDelete = s },
            ),
            onDismiss = { menuFor = null },
        )
    }
    profileMenu?.let { p ->
        MenuDialog(
            title = p.name,
            items = buildList {
                if (p.id != vm.activeProfile.id) add(MenuItem("Switch to ${p.name}") { vm.switchProfile(p) })
                add(MenuItem("Edit") { vm.startEditProfile(p) })
                if (vm.canDeleteProfile(p)) add(MenuItem("Delete", "its favourites and history go too") { vm.deleteProfile(p) })
            },
            onDismiss = { profileMenu = null },
        )
    }
    confirmDelete?.let { s ->
        MenuDialog(
            title = "Delete \"${s.name}\"?",
            subtitle = "Its channels and guide are removed from Flicker. Your favourites list is kept.",
            items = listOf(
                MenuItem("Cancel") {},
                MenuItem("Delete") { vm.deleteSource(s) },
            ),
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detail: String? = null,
    onClick: () -> Unit,
) {
    // Searching Settings: rows that don't match aren't shown (and space themselves when they are).
    val query = LocalSettingsQuery.current
    if (query.isNotBlank() && !settingsMatch(query, title, subtitle, detail)) return
    FocusSurface(
        onClick = onClick,
        baseColor = TunerColors.Panel,
        memoryKey = "row|$title",
        // Phones: the row grows so long explanations wrap instead of being cut off.
        modifier = modifier.then(if (query.isNotBlank()) Modifier.padding(bottom = 6.dp) else Modifier).fillMaxWidth().then(
            if (narrowScreen()) Modifier.heightIn(min = 58.dp) else Modifier.height(if (subtitle != null) 72.dp else 58.dp)
        ),
    ) { focused ->
        val narrow = narrowScreen()
        Row(Modifier.fillMaxWidth().padding(horizontal = if (narrow) 14.dp else 20.dp, vertical = if (narrow) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = if (narrow) 16.sp else 18.sp, maxLines = if (narrow) 2 else 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, color = if (focused) Color(0xDDFFFFFF) else TunerColors.TextSecondary, fontSize = if (narrow) 13.sp else 14.sp, maxLines = if (narrow) 4 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
            detail?.let {
                Text(it, color = if (focused) Color.White else TunerColors.Catchup, fontSize = 15.sp)
            }
        }
    }
}


/** Settings on a kids' profile: just the safe bits, and a PIN-locked way in for grown-ups. */
@Composable
private fun KidsSettingsScreen(vm: TunerViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .padding(horizontal = if (narrowScreen()) 12.dp else 64.dp, vertical = if (narrowScreen()) 12.dp else 32.dp)
    ) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TunerColors.TextPrimary)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SettingRow(
                    "👤 Change profile", subtitle = "Who's watching?", detail = vm.activeProfile.name,
                    modifier = Modifier.focusRequester(first), onClick = { vm.navigate(Screen.ProfilePicker) },
                )
            }
            item { SettingRow("Subtitle size", detail = vm.subtitleSizeLabel, onClick = { vm.cycleSubtitleSize() }) }
            item { LiveSubtitlesSetting(vm) }
            item { SettingRow("Text size", detail = vm.textScaleLabel, onClick = { vm.cycleTextScale() }) }
            item { SettingRow("Clearer speech", subtitle = "Makes voices easier to hear", detail = if (vm.clearSpeech) "On" else "Off", onClick = { vm.toggleClearSpeech() }) }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                SettingRow(
                    "🔒 Grown-up settings",
                    subtitle = if (vm.pin != null) "Needs the parental PIN" else "Grown-ups: set a PIN on your own profile first (Settings › Parental controls)",
                    onClick = { vm.unlockGrownUpSettings() },
                )
            }
        }
    }
}
