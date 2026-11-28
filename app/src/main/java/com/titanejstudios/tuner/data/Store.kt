package com.titanejstudios.tuner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings, playlists, favourites, reminders, resume points and the cached playlists + guides. */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tuner", Context.MODE_PRIVATE)
    private val dir = appContext.filesDir

    init {
        migrateFromV1()
        migrateToV3()
        migrateToV4()
    }

    // ---------------- Playlists ----------------

    var sources: List<Source>
        get() = try {
            val raw = prefs.getString("sources", "[]") ?: "[]"
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).mapNotNull { sourceFromJson(arr.getJSONObject(it)) }
            // Older versions kept passwords as they were typed: encrypt them now (once).
            val plain = (0 until arr.length()).any { i ->
                arr.getJSONObject(i).optString("pass").let { it.isNotEmpty() && !it.startsWith("ks1:") }
            }
            if (plain && list.isNotEmpty()) writeSources(list)
            list
        } catch (e: Exception) {
            emptyList()
        }
        set(value) {
            writeSources(value)
            // Other devices signed in to your Google account pick up the change.
            setSyncStamp(PLAYLISTS, System.currentTimeMillis())
        }

    private fun writeSources(value: List<Source>) {
        val arr = JSONArray()
        value.forEach { arr.put(sourceToJson(it, seal = true)) }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    /** Playlists that came from another device: saved without counting as a change here. */
    fun setSourcesFromSync(value: List<Source>, at: Long) {
        writeSources(value)
        setSyncStamp(PLAYLISTS, at)
    }

    /** The playlists with readable passwords (backups, which may be restored on another device). */
    fun plainSourcesJson(): JSONArray = JSONArray().apply { sources.forEach { put(sourceToJson(it, seal = false)) } }

    /** The playlists for your Google Drive sync file: passwords encrypted with the Google account. */
    fun cloudSourcesJson(account: String): JSONArray = JSONArray().apply {
        sources.forEach { s ->
            val o = sourceToJson(s, seal = false)
            if (s is Source.Xtream) o.put("pass", SyncCrypto.encrypt(s.password, account))
            put(o)
        }
    }

    /** Playlists from the sync file (passwords decrypted with the account), or empty if they can't be read. */
    fun sourcesFromCloud(arr: JSONArray, account: String?): List<Source> = (0 until arr.length()).mapNotNull { i ->
        val o = JSONObject(arr.getJSONObject(i).toString())
        val pass = o.optString("pass")
        // Encrypted with the account's email (or with none, if the email wasn't known when it was sent).
        if (pass.isNotEmpty()) o.put("pass", SyncCrypto.decrypt(pass, account) ?: SyncCrypto.decrypt(pass, "") ?: return@mapNotNull null)
        sourceFromJson(o)
    }

    /** A playlist id, or "all" to combine every playlist. */
    var activeSourceId: String
        get() = prefs.getString("active_source", "all") ?: "all"
        set(value) = prefs.edit().putString("active_source", value).apply()

    fun lastRefresh(sourceId: String): Long = prefs.getLong("last_refresh_$sourceId", 0L)
    fun setLastRefresh(sourceId: String, t: Long) = prefs.edit().putLong("last_refresh_$sourceId", t).apply()

    /** When each part last finished: the channel list and the TV guide count separately, so an update cut short carries on. */
    fun lastChannels(sourceId: String): Long = prefs.getLong("last_channels_$sourceId", lastRefresh(sourceId))
    fun setLastChannels(sourceId: String, t: Long) = prefs.edit().putLong("last_channels_$sourceId", t).apply()
    fun lastGuide(sourceId: String): Long = prefs.getLong("last_guide_$sourceId", lastRefresh(sourceId))
    fun setLastGuide(sourceId: String, t: Long) = prefs.edit().putLong("last_guide_$sourceId", t).apply()

    /** Extra TV guide addresses (XMLTV) that fill gaps and add more days to your provider's guide. */
    var extraGuides: List<String>
        get() = getList("extra_guides")
        set(value) = putList("extra_guides", value)

    private fun sourceToJson(s: Source, seal: Boolean): JSONObject = when (s) {
        is Source.M3u -> JSONObject().put("type", "m3u").put("id", s.id).put("name", s.name)
            .put("url", s.playlistUrl).put("epg", s.epgUrl)
        is Source.Xtream -> JSONObject().put("type", "xtream").put("id", s.id).put("name", s.name)
            .put("server", s.server).put("user", s.username).put("pass", if (seal) LocalCrypto.seal(s.password) else s.password)
            .put("hls", s.useHls).put("epg", s.epgUrl)
    }

    private fun sourceFromJson(o: JSONObject): Source? {
        val epg = o.optString("epg").takeIf { it.isNotBlank() }
        return when (o.optString("type")) {
            "m3u" -> Source.M3u(o.getString("id"), o.optString("name"), o.optString("url"), epg)
            "xtream" -> Source.Xtream(
                o.getString("id"), o.optString("name"), o.optString("server"),
                o.optString("user"), LocalCrypto.open(o.optString("pass")), o.optBoolean("hls"), epg,
            )
            else -> null
        }
    }

    /** Version 1 stored a single playlist in flat keys. */
    private fun migrateFromV1() {
        val type = prefs.getString("type", null) ?: return
        val epg = prefs.getString("epg_url", null)
        val src: Source? = when (type) {
            "m3u" -> Source.M3u("s1", "My playlist", prefs.getString("m3u_url", "") ?: "", epg)
            "xtream" -> Source.Xtream(
                "s1", "My provider",
                prefs.getString("server", "") ?: "", prefs.getString("user", "") ?: "",
                prefs.getString("pass", "") ?: "", prefs.getBoolean("hls", false), epg,
            )
            else -> null
        }
        prefs.edit()
            .remove("type").remove("m3u_url").remove("epg_url").remove("server").remove("user")
            .remove("pass").remove("hls").remove("last_refresh").remove("favourites").remove("recents")
            .apply()
        File(dir, "channels.json").delete()
        File(dir, "epg.json").delete()
        if (src != null) {
            sources = listOf(src)
            activeSourceId = src.id
        }
    }

    // ---------------- Simple lists & sets ----------------

    private fun getList(key: String): List<String> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun putList(key: String, value: Collection<String>) =
        prefs.edit().putString(key, JSONArray(value.toList()).toString()).apply()

    // Favourites and recents belong to a profile ("main" keeps the 2.0 keys).
    private fun profileKey(base: String, profileId: String) = if (profileId == MAIN_PROFILE) base else "${base}_$profileId"

    fun favourites(profileId: String): List<String> = getList(profileKey("favs_v2", profileId))
    fun setFavourites(profileId: String, value: List<String>) = putList(profileKey("favs_v2", profileId), value)

    fun recents(profileId: String): List<String> = getList(profileKey("recents_v2", profileId))
    fun setRecents(profileId: String, value: List<String>) = putList(profileKey("recents_v2", profileId), value)

    fun lastChannel(profileId: String): String? = prefs.getString(profileKey("last_channel", profileId), null)
    fun setLastChannel(profileId: String, channelId: String) =
        prefs.edit().putString(profileKey("last_channel", profileId), channelId).apply()

    var hiddenGroups: Set<String>
        get() = getList("hidden_groups").toSet()
        set(value) = putList("hidden_groups", value)

    var hiddenChannels: Set<String>
        get() = getList("hidden_channels").toSet()
        set(value) = putList("hidden_channels", value)

    var lockedGroups: Set<String>
        get() = getList("locked_groups").toSet()
        set(value) = putList("locked_groups", value)

    var groupOrder: List<String>
        get() = getList("group_order")
        set(value) = putList("group_order", value)

    var pin: String?
        get() = prefs.getString("pin", null)
        set(value) = prefs.edit().putString("pin", value).apply()
    /** When the PIN was last set or removed here (the newest one wins between devices). */
    var pinAt: Long
        get() = prefs.getLong("pin_at", 0L)
        set(value) = prefs.edit().putLong("pin_at", value).apply()
    /** Bookmarks: moments in films and episodes to go back to (JSON). */
    var bookmarksJson: String
        get() = prefs.getString("bookmarks", null) ?: "[]"
        set(value) = prefs.edit().putString("bookmarks", value).apply()
    /** Your own collections ("Christmas films"): name → items (JSON). */
    var collectionsJson: String
        get() = prefs.getString("collections", null) ?: "{}"
        set(value) = prefs.edit().putString("collections", value).apply()
    var crashShownAt: Long
        get() = prefs.getLong("crash_shown_at", 0L)
        set(value) = prefs.edit().putLong("crash_shown_at", value).apply()

    // ---------------- Reminders ----------------

    var reminders: List<Reminder>
        get() = try {
            val arr = JSONArray(prefs.getString("reminders", "[]"))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Reminder(o.getString("c"), o.optString("n"), o.optString("t"), o.getLong("s"), o.getLong("e"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) {
            val arr = JSONArray()
            value.forEach {
                arr.put(JSONObject().put("c", it.channelId).put("n", it.channelName).put("t", it.title).put("s", it.start).put("e", it.stop))
            }
            prefs.edit().putString("reminders", arr.toString()).apply()
        }

    // ---------------- Resume points ----------------

    var resume: Map<String, ResumeEntry>
        get() = try {
            val arr = JSONArray(prefs.getString("resume", "[]"))
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val e = ResumeEntry(
                    contentId = o.getString("id"),
                    title = o.optString("title"),
                    subtitle = o.optString("sub").takeIf { it.isNotEmpty() },
                    poster = o.optString("poster").takeIf { it.isNotEmpty() },
                    url = o.getString("url"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                    updatedAt = o.optLong("at"),
                    seriesId = o.optString("series").takeIf { it.isNotEmpty() },
                )
                e.contentId to e
            }
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) {
            val arr = JSONArray()
            value.values.sortedByDescending { it.updatedAt }.take(50).forEach {
                arr.put(
                    JSONObject().put("id", it.contentId).put("title", it.title).put("sub", it.subtitle)
                        .put("poster", it.poster).put("url", it.url).put("pos", it.positionMs)
                        .put("dur", it.durationMs).put("at", it.updatedAt).put("series", it.seriesId)
                )
            }
            prefs.edit().putString("resume", arr.toString()).apply()
        }

    // ---------------- Home screen bookkeeping ----------------

    var previewChannelId: Long
        get() = prefs.getLong("preview_channel_id", -1L)
        set(value) = prefs.edit().putLong("preview_channel_id", value).apply()

    var watchNextIds: Map<String, Long>
        get() = try {
            val o = JSONObject(prefs.getString("watch_next_ids", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) = prefs.edit().putString("watch_next_ids", JSONObject(value as Map<*, *>).toString()).apply()

    // ---------------- Cached playlists & guides (now in SQLite) ----------------

    fun deleteCaches(sourceId: String) {
        Database.get(appContext).deleteSource(sourceId)
        prefs.edit().remove("last_refresh_$sourceId").apply()
    }

    // ---------------- Profiles ----------------

    var profiles: List<Profile>
        get() = (try {
            profilesFromJson(JSONArray(prefs.getString("profiles", "[]")))
        } catch (e: Exception) {
            emptyList()
        }).ifEmpty { listOf(Profile(MAIN_PROFILE, "Everyone")) }
        set(value) {
            prefs.edit().putString("profiles", profilesToJson(value).toString()).apply()
        }

    /** Profiles deleted here (lower-case name → when), so other devices delete them too. */
    var profilesGone: Map<String, Long>
        get() = try {
            val o = JSONObject(prefs.getString("profiles_gone", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) = prefs.edit().putString("profiles_gone", JSONObject(value).toString()).apply()

    var activeProfileId: String
        get() = prefs.getString("active_profile", MAIN_PROFILE) ?: MAIN_PROFILE
        set(value) = prefs.edit().putString("active_profile", value).apply()

    /** Seconds watched today by a profile (for kids' daily limits), plus any extra time granted with the PIN. */
    fun usageSeconds(profileId: String, day: String): Long = prefs.getLong("usage_${profileId}_$day", 0L)
    fun addUsage(profileId: String, day: String, seconds: Long) =
        prefs.edit().putLong("usage_${profileId}_$day", usageSeconds(profileId, day) + seconds).apply()
    fun bonusMinutes(profileId: String, day: String): Int = prefs.getInt("bonus_${profileId}_$day", 0)
    fun addBonus(profileId: String, day: String, minutes: Int) =
        prefs.edit().putInt("bonus_${profileId}_$day", bonusMinutes(profileId, day) + minutes).apply()

    // ---------------- Other settings ----------------

    var openLastChannel: Boolean
        get() = prefs.getBoolean("open_last_channel", false)
        set(value) = prefs.edit().putBoolean("open_last_channel", value).apply()

    /** What Tuner opens on: "home", "guide" (TV Guide where you left it) or "channel" (last channel playing). */
    var startScreen: String
        get() = prefs.getString("start_screen", null) ?: if (openLastChannel) "channel" else "home"
        set(value) = prefs.edit().putString("start_screen", value).apply()

    /** Update playlists on start-up when the saved copy is over 12 hours old (otherwise overnight only). */
    var refreshOnStart: Boolean
        get() = prefs.getBoolean("refresh_on_start", true)
        set(value) = prefs.edit().putBoolean("refresh_on_start", value).apply()

    fun lastGroup(profileId: String): String? = prefs.getString(profileKey("last_group", profileId), null)
    fun setLastGroup(profileId: String, group: String) = prefs.edit().putString(profileKey("last_group", profileId), group).apply()

    var hiddenApps: Set<String>
        get() = getList("hidden_apps").toSet()
        set(value) = putList("hidden_apps", value)

    var seriesLinks: List<SeriesLink>
        get() = try {
            val arr = JSONArray(prefs.getString("series_links", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SeriesLink(o.getString("c"), o.optString("n"), o.getString("t"), o.optLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(JSONObject().put("c", it.channelId).put("n", it.channelName).put("t", it.title).put("at", it.created)) }
            prefs.edit().putString("series_links", arr.toString()).apply()
        }

    var webhookUrl: String?
        get() = prefs.getString("webhook_url", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("webhook_url", value).apply()

    /** Manual guide matches: playlist channel id -> XMLTV channel id. */
    var epgOverrides: Map<String, String>
        get() = try {
            val o = JSONObject(prefs.getString("epg_overrides", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) {
            prefs.edit().putString("epg_overrides", JSONObject(value as Map<*, *>).toString()).apply()
            setSyncStamp(GUIDE_SETTINGS, System.currentTimeMillis())
        }

    /** TV guide matches that came from another device (not counted as a change here). */
    fun setEpgOverridesFromSync(value: Map<String, String>, at: Long) {
        prefs.edit().putString("epg_overrides", JSONObject(value as Map<*, *>).toString()).apply()
        setSyncStamp(GUIDE_SETTINGS, at)
    }

    var recordingDir: String?
        get() = prefs.getString("recording_dir", null)
        set(value) = prefs.edit().putString("recording_dir", value).apply()

    // ---------------- Recordings ----------------

    var recordings: List<Recording>
        get() = synchronized(RECORDING_LOCK) { readRecordings() }
        set(value) = synchronized(RECORDING_LOCK) { writeRecordings(value) }

    /** Atomic read-modify-write (the recording service and the app both change this list). */
    fun updateRecordings(change: (List<Recording>) -> List<Recording>) = synchronized(RECORDING_LOCK) {
        writeRecordings(change(readRecordings()))
    }

    private fun readRecordings(): List<Recording> = try {
        val arr = JSONArray(prefs.getString("recordings", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Recording(
                id = o.getString("id"),
                channelId = o.optString("ch"),
                channelName = o.optString("chName"),
                title = o.optString("title"),
                start = o.getLong("start"),
                stop = o.getLong("stop"),
                url = o.optString("url"),
                path = o.optString("path"),
                status = o.optString("status", RecStatus.SCHEDULED),
                error = o.optString("error").takeIf { it.isNotEmpty() },
                category = o.optString("cat").takeIf { it.isNotEmpty() },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeRecordings(list: List<Recording>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("ch", it.channelId).put("chName", it.channelName)
                    .put("title", it.title).put("start", it.start).put("stop", it.stop).put("url", it.url)
                    .put("path", it.path).put("status", it.status).put("error", it.error).put("cat", it.category)
            )
        }
        // commit() so the service and app always see each other's changes straight away
        prefs.edit().putString("recordings", arr.toString()).commit()
    }

    // ---------------- Channel health, fast switching, phone remote ----------------

    /** Changing it stamps the time, so the newest list is shared with the other devices in the house. */
    var deadChannels: Set<String>
        get() = getList("dead_channels").toSet()
        set(value) {
            if (value != getList("dead_channels").toSet()) setSyncStamp(com.titanejstudios.tuner.integrations.LinkSync.OFFLINE, System.currentTimeMillis())
            putList("dead_channels", value)
        }

    /** Channels that only show something on match days: never hidden, just "off air" in between. */
    var matchDayChannels: Set<String>
        get() = getList("match_day_channels").toSet()
        set(value) = putList("match_day_channels", value)

    var healthCheckedAt: Long
        get() = prefs.getLong("health_checked_at", 0L)
        set(value) = prefs.edit().putLong("health_checked_at", value).apply()

    var hideDead: Boolean
        get() = prefs.getBoolean("hide_dead", false)
        set(value) = prefs.edit().putBoolean("hide_dead", value).apply()

    var healthNightly: Boolean
        get() = prefs.getBoolean("health_nightly", false)
        set(value) = prefs.edit().putBoolean("health_nightly", value).apply()

    /** "favourites" or "all" */
    var healthScope: String
        get() = prefs.getString("health_scope", "favourites") ?: "favourites"
        set(value) = prefs.edit().putString("health_scope", value).apply()

    var fastSwitch: Boolean
        get() = prefs.getBoolean("fast_switch", false)
        set(value) = prefs.edit().putBoolean("fast_switch", value).apply()

    var timeshiftEnabled: Boolean
        get() = prefs.getBoolean("timeshift", true)
        set(value) = prefs.edit().putBoolean("timeshift", value).apply()

    /** Send Dolby/DTS untouched to the TV/soundbar. Off = decode to PCM (avoids frozen video on some setups). */
    // ---- 5.2 ----
    private fun stringMap(key: String): Map<String, String> = try {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun setStringMap(key: String, value: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in value) o.put(k, v)
        prefs.edit().putString(key, o.toString()).apply()
    }

    /** Per channel: AspectRatioFrameLayout resize mode (0 fit, 4 zoom, 3 stretch). */
    var pictureModes: Map<String, String>
        get() = stringMap("picture_modes")
        set(value) = setStringMap("picture_modes", value)
    /** Per channel: sound offset in ms (+ later, − earlier). Key "*" = every channel. */
    var lipSync: Map<String, String>
        get() = stringMap("lip_sync")
        set(value) = setStringMap("lip_sync", value)
    var audioLanguage: String?
        get() = prefs.getString("audio_lang", null)
        set(value) = prefs.edit().putString("audio_lang", value).apply()
    var subtitleLanguage: String?
        get() = prefs.getString("sub_lang", null)
        set(value) = prefs.edit().putString("sub_lang", value).apply()

    /** Each profile's own sound/subtitle language ("-" = the stream's default / off); the shared one until set. */
    fun audioLanguageOf(profileId: String): String? =
        if (prefs.contains("audio_lang_$profileId")) prefs.getString("audio_lang_$profileId", null)?.takeIf { it != "-" } else audioLanguage
    fun saveAudioLanguage(profileId: String, value: String?) = prefs.edit().putString("audio_lang_$profileId", value ?: "-").apply()
    fun subtitleLanguageOf(profileId: String): String? =
        if (prefs.contains("sub_lang_$profileId")) prefs.getString("sub_lang_$profileId", null)?.takeIf { it != "-" } else subtitleLanguage
    fun saveSubtitleLanguage(profileId: String, value: String?) = prefs.edit().putString("sub_lang_$profileId", value ?: "-").apply()
    /** Audio description (the soundtrack that describes what's on screen) picked when there is one. */
    fun audioDescriptionOf(profileId: String): Boolean = prefs.getBoolean("audio_desc_$profileId", false)
    fun saveAudioDescription(profileId: String, on: Boolean) = prefs.edit().putBoolean("audio_desc_$profileId", on).apply()

    /** Category editor: original name → new name (two renamed to the same name are merged). */
    var groupRenames: Map<String, String>
        get() = stringMap("group_renames")
        set(value) = setStringMap("group_renames", value)
    /** Your own categories: name → channel ids. */
    var customGroups: Map<String, List<String>>
        get() = stringMap("custom_groups").mapValues { (_, v) -> v.split('\n').filter { it.isNotEmpty() } }
        set(value) = setStringMap("custom_groups", value.mapValues { it.value.joinToString("\n") })

    /** Per playlist: shift the guide by this many minutes (for guides that are an hour out). */
    var epgShift: Map<String, String>
        get() = stringMap("epg_shift")
        set(value) = setStringMap("epg_shift", value)

    /** Delete finished recordings after this many days (0 = keep). Kept ones are never deleted. */
    var recordingAutoDelete: Int
        get() = prefs.getInt("rec_auto_delete", 0)
        set(value) = prefs.edit().putInt("rec_auto_delete", value).apply()
    var keptRecordings: Set<String>
        get() = getList("rec_kept").toSet()
        set(value) = putList("rec_kept", value)
    /** Network drive for finished recordings, e.g. smb://192.168.1.20/media/TV */
    var nasUrl: String?
        get() = prefs.getString("nas_url", null)
        set(value) = prefs.edit().putString("nas_url", value).apply()
    var nasUser: String?
        get() = prefs.getString("nas_user", null)
        set(value) = prefs.edit().putString("nas_user", value).apply()
    var nasPassword: String?
        get() = prefs.getString("nas_pass", null)
        set(value) = prefs.edit().putString("nas_pass", value).apply()
    var nasKeepLocal: Boolean
        get() = prefs.getBoolean("nas_keep_local", true)
        set(value) = prefs.edit().putBoolean("nas_keep_local", value).apply()

    // Plex
    val plexClientId: String
        get() = prefs.getString("plex_client_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("plex_client_id", it).apply()
        }
    var plexToken: String?
        get() = prefs.getString("plex_token", null)
        set(value) = prefs.edit().putString("plex_token", value).apply()

    /** A profile's Plex Home user: "uuid\tname\ttoken" (null = the main account). */
    fun plexUserOf(profileId: String): Triple<String, String, String>? =
        prefs.getString("plex_user_$profileId", null)?.split('\t')?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) }
    fun savePlexUser(profileId: String, user: Triple<String, String, String>?) =
        prefs.edit().putString("plex_user_$profileId", user?.let { "${it.first}\t${it.second}\t${it.third}" }).apply()

    var whatsOnTidy: Boolean
        get() = prefs.getBoolean("whats_on_tidy", true)
        set(value) = prefs.edit().putBoolean("whats_on_tidy", value).apply()
    var whatsOnPreview: Boolean
        get() = prefs.getBoolean("whats_on_preview", true)
        set(value) = prefs.edit().putBoolean("whats_on_preview", value).apply()
    var footballTeams: Set<String>
        get() = getList("football_teams").toSet()
        set(value) = putList("football_teams", value)
    /** Followed team name → its ESPN team (JSON), for fixtures. */
    var espnTeams: String?
        get() = prefs.getString("espn_teams", null)
        set(value) = prefs.edit().putString("espn_teams", value).apply()
    /** Teams you can pick from (cached for a fortnight). */
    /** The sport of each team you follow by name ("Newcastle" → "rugby"), as JSON. */
    var teamSports: String?
        get() = prefs.getString("team_sports", null)
        set(value) = prefs.edit().putString("team_sports", value).apply()

    var espnTeamList: String?
        get() = prefs.getString("espn_team_list", null)
        set(value) = prefs.edit().putString("espn_team_list", value).putLong("espn_team_list_at", System.currentTimeMillis()).apply()
    val espnTeamListAt: Long get() = prefs.getLong("espn_team_list_at", 0L)
    /** Your teams' coming fixtures (cached for a few hours). */
    var espnFixtures: String?
        get() = prefs.getString("espn_fixtures", null)
        set(value) = prefs.edit().putString("espn_fixtures", value).putLong("espn_fixtures_at", System.currentTimeMillis()).apply()
    val espnFixturesAt: Long get() = prefs.getLong("espn_fixtures_at", 0L)
    /** Fixtures already announced ("Newcastle play today at 5:30 on…"). */
    var teamAnnounced: Set<String>
        get() = prefs.getStringSet("team_announced", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("team_announced", value).apply()

    /** Clears what the old football scores service left behind (key, fixtures, team lists). */
    fun forgetFootballScores() {
        prefs.edit().remove("football_key").remove("football_team_ids").remove("football_team_list").remove("football_team_list_at")
            .remove("football_fixtures").remove("football_fixtures_at").apply()
    }

    /** App updates from a GitHub repo's releases ("owner/repo"). */
    /** GitHub repo checked for updates ("owner/repo"). Defaults to Tuner's own repo; "" = turned off. */
    var updateRepo: String?
        get() = prefs.getString("update_repo", null).let {
            when {
                it == null -> DEFAULT_UPDATE_REPO
                // The repo moved (GitHub redirects, but don't rely on it).
                it.equals("markbrown3000/Tuner", ignoreCase = true) -> DEFAULT_UPDATE_REPO
                else -> it.takeIf { r -> r.isNotBlank() }
            }
        }
        set(value) = prefs.edit().putString("update_repo", value ?: "").apply()
    /** When Tuner last looked for an update by itself, and a version you said "Later" to. */
    var updateCheckedAt: Long
        get() = prefs.getLong("update_checked_at", 0L)
        set(value) = prefs.edit().putLong("update_checked_at", value).apply()
    /** The version that last showed "What's new" (null on a fresh install). */
    var lastSeenVersion: String?
        get() = prefs.getString("last_seen_version", null)
        set(value) = prefs.edit().putString("last_seen_version", value).apply()

    var updateDismissed: String?
        get() = prefs.getString("update_dismissed", null)
        set(value) = prefs.edit().putString("update_dismissed", value).apply()

    // ---- Look & sound (5.1) ----
    var themeAccent: String
        get() = prefs.getString("theme_accent", "sky") ?: "sky"
        set(value) = prefs.edit().putString("theme_accent", value).apply()
    var themeBackground: String
        get() = prefs.getString("theme_bg", "ink") ?: "ink"
        set(value) = prefs.edit().putString("theme_bg", value).apply()

    /** Each profile's colour theme and highlight colour ("evening|" = Evening with its own highlight); null = not chosen. */
    fun profileTheme(profileId: String): Pair<String, String>? =
        prefs.getString(profileKey("prof_theme", profileId), null)?.let { it.substringBefore('|') to it.substringAfter('|', "") }
    fun setProfileTheme(profileId: String, theme: String, accent: String) =
        prefs.edit().putString(profileKey("prof_theme", profileId), "$theme|$accent").apply()

    /** Each profile's own Home rows (order, and the ones switched off); null = the shared choice. */
    fun profileHomeRows(profileId: String): Pair<List<String>, Set<String>>? {
        val order = prefs.getString(profileKey("prof_rows_order", profileId), null) ?: return null
        val hidden = prefs.getString(profileKey("prof_rows_hidden", profileId), "") ?: ""
        return order.split(',').filter { it.isNotBlank() } to hidden.split(',').filter { it.isNotBlank() }.toSet()
    }
    fun setProfileHomeRows(profileId: String, order: List<String>, hidden: Set<String>) = prefs.edit()
        .putString(profileKey("prof_rows_order", profileId), order.joinToString(","))
        .putString(profileKey("prof_rows_hidden", profileId), hidden.joinToString(","))
        .apply()

    /** How film and series pages are sorted (VOD_SORTS). */
    var vodSort: String
        get() = prefs.getString("vod_sort", "default") ?: "default"
        set(value) = prefs.edit().putString("vod_sort", value).apply()

    /** Whether the TV guide plays the highlighted channel in its corner. */
    var guidePreview: Boolean
        get() = prefs.getBoolean("guide_preview", true)
        set(value) = prefs.edit().putBoolean("guide_preview", value).apply()

    /** A profile picture's file (chosen on a phone), or null for the colour and emoji. */
    fun profilePhotoAt(profileId: String): Long = prefs.getLong(profileKey("photo_at", profileId), 0L)
    fun setProfilePhotoAt(profileId: String, at: Long) = prefs.edit().putLong(profileKey("photo_at", profileId), at).apply()
    var subtitleScale: Float
        get() = prefs.getFloat("subtitle_scale", 1f)
        set(value) = prefs.edit().putFloat("subtitle_scale", value).apply()
    var subtitleBox: Boolean
        get() = prefs.getBoolean("subtitle_box", false)
        set(value) = prefs.edit().putBoolean("subtitle_box", value).apply()
    var nightMode: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(value) = prefs.edit().putBoolean("night_mode", value).apply()
    /** Clearer speech: voices lifted over music and effects. */
    var clearSpeech: Boolean
        get() = prefs.getBoolean("clear_speech", false)
        set(value) = prefs.edit().putBoolean("clear_speech", value).apply()
    /** Text size across Flicker: 100, 115 or 130 (%). Kept per device (a phone and a TV differ). */
    var textScale: Int
        get() = prefs.getInt("text_scale", 100)
        set(value) = prefs.edit().putInt("text_scale", value).apply()
    var frameRateMatch: Boolean
        get() = prefs.getBoolean("frame_rate_match", false)
        set(value) = prefs.edit().putBoolean("frame_rate_match", value).apply()

    // ---- Google TV integration ----
    /** Put channels into the TV's own Live tab / channel list: "off", "favourites" or "all". */
    var liveTabScope: String
        get() = prefs.getString("live_tab_scope", "off") ?: "off"
        set(value) = prefs.edit().putString("live_tab_scope", value).apply()

    /** Live tab bookkeeping: channel id → logo URL already copied to the TV. */
    var liveTabRows: String
        get() = prefs.getString("live_tab_rows", null) ?: "{}"
        set(value) = prefs.edit().putString("live_tab_rows", value).apply()

    // ---- Watch-time stats (per profile, JSON – see WatchStats) ----
    /** Things a kids' profile tried and couldn't watch: "time\twhat\twhy", newest last (two weeks). */
    fun blockedLog(profileId: String): List<String> =
        prefs.getString("blocked_log_$profileId", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    fun addBlocked(profileId: String, what: String, why: String) {
        val cutoff = System.currentTimeMillis() - 14 * 86_400_000L
        val kept = blockedLog(profileId).filter { (it.substringBefore('\t').toLongOrNull() ?: 0L) > cutoff }.takeLast(99)
        val line = "${System.currentTimeMillis()}\t${what.replace('\t', ' ').replace('\n', ' ')}\t$why"
        prefs.edit().putString("blocked_log_$profileId", (kept + line).joinToString("\n")).apply()
    }

    /** Quarter-hours (time / 15 min) something was playing this past week – "were you watching?". */
    var watchingSlots: String
        get() = prefs.getString("watching_slots", null) ?: ""
        set(value) = prefs.edit().putString("watching_slots", value).apply()

    /** Film trailers play (muted) on the info card. */
    var familyPicksJson: String
        get() = prefs.getString("family_picks", null) ?: "[]"
        set(value) = prefs.edit().putString("family_picks", value).apply()
    var comingSoonJson: String?
        get() = prefs.getString("coming_soon", null)
        set(value) = prefs.edit().putString("coming_soon", value).apply()
    var comingSoonAt: Long
        get() = prefs.getLong("coming_soon_at", 0L)
        set(value) = prefs.edit().putLong("coming_soon_at", value).apply()
    var comingSoonTold: String
        get() = prefs.getString("coming_soon_told", null) ?: ""
        set(value) = prefs.edit().putString("coming_soon_told", value).apply()

    var recaps: Boolean
        get() = prefs.getBoolean("recaps", true)
        set(value) = prefs.edit().putBoolean("recaps", value).apply()

    var trailerAutoplay: Boolean
        get() = prefs.getBoolean("trailer_autoplay", true)
        set(value) = prefs.edit().putBoolean("trailer_autoplay", value).apply()

    /** Trakt sign-in: "access\trefresh\texpiresAt\tusername" (null = not linked). */
    var traktAuth: String?
        get() = prefs.getString("trakt_auth", null)
        set(value) = prefs.edit().putString("trakt_auth", value).apply()
    var traktImportedAt: Long
        get() = prefs.getLong("trakt_imported_at", 0L)
        set(value) = prefs.edit().putLong("trakt_imported_at", value).apply()

    fun stats(profileId: String): String = prefs.getString(profileKey("watch_stats", profileId), null) ?: "{}"
    fun setStats(profileId: String, json: String) = prefs.edit().putString(profileKey("watch_stats", profileId), json).apply()

    var audioPassthrough: Boolean
        get() = prefs.getBoolean("audio_passthrough", false)
        set(value) = prefs.edit().putBoolean("audio_passthrough", value).apply()

    /** Looser MPEG-TS parsing for streams that won't start. */
    var streamCompatibility: Boolean
        get() = prefs.getBoolean("stream_compat", false)
        set(value) = prefs.edit().putBoolean("stream_compat", value).apply()

    var remoteEnabled: Boolean
        get() = prefs.getBoolean("remote_enabled", false)
        set(value) = prefs.edit().putBoolean("remote_enabled", value).apply()

    /** Permanent token for the phone remote link, so it can be bookmarked. */
    val remoteToken: String
        get() = prefs.getString("remote_token", null) ?: run {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            val t = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("remote_token", t).apply()
            t
        }

    // ---- 5.3 ----
    /** Films and series saved to "My list" (JSON – see [Watchlist]). */
    var watchlistJson: String
        get() = prefs.getString("watchlist", null) ?: "[]"
        set(value) = prefs.edit().putString("watchlist", value).apply()
    /** Series you've watched, with how many episodes they had (to spot new ones). */
    var followedSeriesJson: String
        get() = prefs.getString("followed_series", null) ?: "[]"
        set(value) = prefs.edit().putString("followed_series", value).apply()
    var newEpisodesCheckedAt: Long
        get() = prefs.getLong("new_eps_checked_at", 0L)
        set(value) = prefs.edit().putLong("new_eps_checked_at", value).apply()
    /** Home Assistant "lights" events: "off", "films", "films_series" or "everything". */
    var cinemaLights: String
        get() = prefs.getString("cinema_lights", "off") ?: "off"
        set(value) = prefs.edit().putString("cinema_lights", value).apply()
    /** Per channel: how often its stream has stalled lately (for a bigger start-up cushion). */
    var flakyStreams: Map<String, String>
        get() = stringMap("flaky_streams")
        set(value) = setStringMap("flaky_streams", value)
    /** When old guide data was last cleared out of the database. */
    var dbTidyAt: Long
        get() = prefs.getLong("db_tidy_at", 0L)
        set(value) = prefs.edit().putLong("db_tidy_at", value).apply()

    // ---- 5.4 ----
    /** Last crash already sent to Home Assistant (time it happened). */
    var crashReportedAt: Long
        get() = prefs.getLong("crash_reported_at", 0L)
        set(value) = prefs.edit().putLong("crash_reported_at", value).apply()
    /** "Are you still watching?" after this many hours without a button press (0 = never). */
    var stillWatchingHours: Int
        get() = prefs.getInt("still_watching_hours", 4)
        set(value) = prefs.edit().putInt("still_watching_hours", value).apply()
    /** Per channel: volume level in dB (−9 … +9). */
    var volumeLevels: Map<String, String>
        get() = stringMap("volume_levels")
        set(value) = setStringMap("volume_levels", value)
    /** Per channel: "auto", "best" or "sd". */
    var qualityModes: Map<String, String>
        get() = stringMap("quality_modes")
        set(value) = setStringMap("quality_modes", value)
    /** Followed teams' matches: "off", "remind" or "record". */
    var sportFollowMode: String
        get() = prefs.getString("sport_follow_mode", "remind") ?: "remind"
        set(value) = prefs.edit().putString("sport_follow_mode", value).apply()
    /** Matches already handled automatically (so a cancelled one isn't set again). */
    var sportAutoDone: List<String>
        get() = getList("sport_auto_done")
        set(value) = putList("sport_auto_done", value.takeLast(200))
    /** Your own free keys for film info and subtitles. */
    /** Your own key from Settings, or else the one built in (keys.properties). */
    var tmdbKey: String?
        get() = prefs.getString("tmdb_key", null)?.takeIf { it.isNotBlank() }
            ?: com.titanejstudios.tuner.BuildConfig.TMDB_KEY.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("tmdb_key", value).apply()
    var openSubtitlesKey: String?
        get() = prefs.getString("opensubtitles_key", null)
        set(value) = prefs.edit().putString("opensubtitles_key", value).apply()
    /** Per series: where its intro ends (ms), learned from you skipping it. */
    var introEnds: Map<String, String>
        get() = stringMap("intro_ends")
        set(value) = setStringMap("intro_ends", value)
    /** Keep favourites, My list and resume points in step with other TVs via the network drive. */
    var syncEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) = prefs.edit().putBoolean("sync_enabled", value).apply()
    fun syncStamp(section: String): Long = prefs.getLong("sync_stamp_$section", 0L)
    fun setSyncStamp(section: String, t: Long) = prefs.edit().putLong("sync_stamp_$section", t).apply()
    var syncedAt: Long
        get() = prefs.getLong("synced_at", 0L)
        set(value) = prefs.edit().putLong("synced_at", value).apply()
    /** Nightly copy of the settings to the network drive. */
    var nasBackup: Boolean
        get() = prefs.getBoolean("nas_backup", true)
        set(value) = prefs.edit().putBoolean("nas_backup", value).apply()
    var nasBackupAt: Long
        get() = prefs.getLong("nas_backup_at", 0L)
        set(value) = prefs.edit().putLong("nas_backup_at", value).apply()

    /** Open Tuner by itself when the TV starts up. */
    /** Home's rows in the order chosen (names of HomeRow); empty = the standard order. */
    var homeRowOrder: List<String>
        get() = prefs.getString("home_row_order", "")!!.split(',').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("home_row_order", value.joinToString(",")).apply()

    /** Home rows switched off. */
    var homeRowsHidden: Set<String>
        // "New on Plex" is replaced by "Trending this week" unless you switch it back on.
        get() = prefs.getStringSet("home_rows_hidden", setOf("PlexNew"))?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("home_rows_hidden", value).apply()

    /** Home moves sport up when it's on, and films/Plex up in the evening. */
    var homeSmart: Boolean
        get() = prefs.getBoolean("home_smart", true)
        set(value) = prefs.edit().putBoolean("home_smart", value).apply()

    /** Country/language tags hidden everywhere (see Languages); "-" = things with no tag. */
    var hiddenLanguages: Set<String>
        get() = prefs.getStringSet("hidden_languages", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_languages", value).apply()

    /** "tv" or "phone" – asked the first time Tuner opens; "" = not asked yet. */
    var deviceMode: String
        get() = prefs.getString("device_mode", "") ?: ""
        set(value) = prefs.edit().putString("device_mode", value).apply()

    // ---- Linked devices (send to TV, carry on where you left off) ----
    /** Shared by every linked device in the house; copying settings to another TV links it too. */
    var linkKey: String?
        get() = prefs.getString("link_key", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("link_key", value).apply()
    /** This device's own id (never copied, so two devices never share one). */
    val linkDeviceId: String
        get() = prefs.getString("link_device_id", null) ?: java.util.UUID.randomUUID().toString().take(12)
            .also { prefs.edit().putString("link_device_id", it).apply() }
    /** What other devices call this one ("Living room TV", "Mark's phone"); "" = the device's own name. */
    var linkName: String
        get() = prefs.getString("link_name", "") ?: ""
        set(value) = prefs.edit().putString("link_name", value).apply()

    /** Films and episodes finished (content id → when), so a linked device's older resume point doesn't bring them back. */
    var resumeDone: Map<String, String>
        get() = stringMap("resume_done")
        set(value) = setStringMap("resume_done", value)

    // ---- Sync between houses (Google Drive) – kept on each device, never in backups ----
    var gdriveRefresh: String?
        get() = prefs.getString("gdrive_refresh", null)
        set(value) = prefs.edit().putString("gdrive_refresh", value).apply()
    var gdriveEmail: String?
        get() = prefs.getString("gdrive_email", null)
        set(value) = prefs.edit().putString("gdrive_email", value).apply()
    var gdriveFileId: String?
        get() = prefs.getString("gdrive_file", null)
        set(value) = prefs.edit().putString("gdrive_file", value).apply()
    /** Which house this device is in ("Home", "Mum's"). */
    var cloudHouse: String
        get() = prefs.getString("cloud_house", "Home") ?: "Home"
        set(value) = prefs.edit().putString("cloud_house", value).apply()
    /** Take favourites from devices in other houses too (off: each house keeps its own). */
    var cloudShareFavourites: Boolean
        get() = prefs.getBoolean("cloud_share_favs", false)
        set(value) = prefs.edit().putBoolean("cloud_share_favs", value).apply()
    /** The "Set up sync between houses?" question has been asked on this device. */
    var cloudPrompted: Boolean
        get() = prefs.getBoolean("cloud_prompted", false)
        set(value) = prefs.edit().putBoolean("cloud_prompted", value).apply()
    var cloudSyncedAt: Long
        get() = prefs.getLong("cloud_synced_at", 0L)
        set(value) = prefs.edit().putLong("cloud_synced_at", value).apply()

    // ---- YouTube (sign-in kept on each device, never in backups) ----
    // YouTube sign-ins are per profile. (The first, from before that, belongs to the main profile.)
    private fun ytKey(name: String, profileId: String): String {
        if (profileId == MAIN_PROFILE && !prefs.contains("${name}_$profileId") && prefs.contains(name)) {
            prefs.edit().putString("${name}_$profileId", prefs.getString(name, null)).remove(name).apply()
        }
        return "${name}_$profileId"
    }
    fun ytRefreshOf(profileId: String): String? = prefs.getString(ytKey("yt_refresh", profileId), null)
    fun saveYtRefresh(profileId: String, value: String?) = prefs.edit().putString(ytKey("yt_refresh", profileId), value).apply()
    fun ytEmailOf(profileId: String): String? = prefs.getString(ytKey("yt_email", profileId), null)
    fun saveYtEmail(profileId: String, value: String?) = prefs.edit().putString(ytKey("yt_email", profileId), value).apply()
    // ("yt_feed2": the subscriptions row without Shorts – older saved rows had them.)
    fun ytFeedOf(profileId: String): String? = prefs.getString("yt_feed2_$profileId", null)
    fun saveYtFeed(profileId: String, value: String?) {
        prefs.edit().putString("yt_feed2_$profileId", value).putLong("yt_feed2_at_$profileId", System.currentTimeMillis()).apply()
    }
    /** What this profile watched on YouTube in Flicker (newest first), and its recommendations. */
    /** Other per-profile YouTube lists in Flicker: "later", "hide", "block", "recent". */
    fun ytData(kind: String, profileId: String): String? = prefs.getString("ytd_${kind}_$profileId", null)
    fun saveYtData(kind: String, profileId: String, value: String?) = prefs.edit().putString("ytd_${kind}_$profileId", value).apply()
    var ytSkipSponsors: Boolean
        get() = prefs.getBoolean("youtube_skip_sponsors", true)
        set(value) = prefs.edit().putBoolean("youtube_skip_sponsors", value).apply()
    /** Film and series ratings looked up (IMDb via OMDb, or TMDB): JSON {key: [rating, source, when]}. */
    var vodRatings: String?
        get() = prefs.getString("vod_ratings", null)
        set(value) = prefs.edit().putString("vod_ratings", value).apply()
    /** Which copy of a film to play without asking: "iptv", or "plex:<library name>" (null = ask). */
    var preferredCopy: String?
        get() = prefs.getString("preferred_copy", null)
        set(value) = prefs.edit().putString("preferred_copy", value).apply()
    var hideAdult: Boolean
        get() = prefs.getBoolean("hide_adult", true)
        set(value) = prefs.edit().putBoolean("hide_adult", value).apply()
    var ytCaptions: Boolean
        get() = prefs.getBoolean("youtube_captions", false)
        set(value) = prefs.edit().putBoolean("youtube_captions", value).apply()
    fun ytHistoryOf(profileId: String): String? = prefs.getString("ythist_$profileId", null)
    fun saveYtHistory(profileId: String, value: String) = prefs.edit().putString("ythist_$profileId", value).apply()
    fun ytRecsOf(profileId: String): String? = prefs.getString("ytrec_$profileId", null)
    fun saveYtRecs(profileId: String, value: String?, at: Long = System.currentTimeMillis()) =
        prefs.edit().putString("ytrec_$profileId", value).putLong("ytrec_at_$profileId", at).apply()
    fun ytRecsAtOf(profileId: String): Long = prefs.getLong("ytrec_at_$profileId", 0L)
    fun ytFeedAtOf(profileId: String): Long = prefs.getLong("yt_feed2_at_$profileId", 0L)

    // ---- Downloads (phones) ----
    var downloadsJson: String
        get() = prefs.getString("downloads", "[]") ?: "[]"
        set(value) = prefs.edit().putString("downloads", value).apply()
    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean("download_wifi_only", true)
        set(value) = prefs.edit().putBoolean("download_wifi_only", value).apply()

    // ---- Your usual channel ----
    /** Watch time by day type and hour, per profile (see Habits). */
    fun habits(profileId: String): String = prefs.getString("habits_$profileId", "{}") ?: "{}"
    fun setHabits(profileId: String, json: String) = prefs.edit().putString("habits_$profileId", json).apply()
    var offerUsual: Boolean
        get() = prefs.getBoolean("offer_usual", true)
        set(value) = prefs.edit().putBoolean("offer_usual", value).apply()

    /** Little pictures while scrubbing IPTV films and catch-up (needs a second connection to the provider). */
    var scrubPreviewsIptv: Boolean
        get() = prefs.getBoolean("scrub_previews_iptv", false)
        set(value) = prefs.edit().putBoolean("scrub_previews_iptv", value).apply()

    /** Programmes you switched off half-way (offered on catch-up to finish). JSON list. */
    var unfinishedJson: String
        get() = prefs.getString("unfinished", "[]") ?: "[]"
        set(value) = prefs.edit().putString("unfinished", value).apply()

    /** What a profile has started watching ("p:<plex title>", "v:<film or episode id>") – its own Continue watching. */
    fun started(profileId: String): Set<String> = prefs.getStringSet("started_$profileId", emptySet())?.toSet() ?: emptySet()
    fun setStarted(profileId: String, keys: Set<String>) = prefs.edit().putStringSet("started_$profileId", keys).apply()

    /** What the home-screen widget shows: favourites with now and next. */
    var widgetSnapshot: String
        get() = prefs.getString("widget_now", "[]") ?: "[]"
        set(value) = prefs.edit().putString("widget_now", value).apply()

    // ---- Built-in VPN (kept on this TV only: the files hold your VPN key) ----
    var vpnServers: String?
        get() = prefs.getString("vpn_servers", null)
        set(value) = prefs.edit().putString("vpn_servers", value).apply()
    var vpnActive: String?
        get() = prefs.getString("vpn_active", null)
        set(value) = prefs.edit().putString("vpn_active", value).apply()
    var vpnEnabled: Boolean
        get() = prefs.getBoolean("vpn_enabled", false)
        set(value) = prefs.edit().putBoolean("vpn_enabled", value).apply()
    var vpnKillSwitch: Boolean
        get() = prefs.getBoolean("vpn_kill_switch", false)
        set(value) = prefs.edit().putBoolean("vpn_kill_switch", value).apply()

    /** What a profile likes (see Taste). */
    fun taste(profileId: String): String? = prefs.getString(profileKey("taste", profileId), null)
    fun setTaste(profileId: String, json: String) = prefs.edit().putString(profileKey("taste", profileId), json).apply()

    /** Learn from what's watched (Settings › Recommendations). */
    var learnTaste: Boolean
        get() = prefs.getBoolean("learn_taste", true)
        set(value) = prefs.edit().putBoolean("learn_taste", value).apply()

    /** The last Plex film/show and the last film watched, for "Because you watched…" (JSON). */
    fun lastWatchedFor(profileId: String): String? = prefs.getString(profileKey("last_watched_for", profileId), null)
    fun setLastWatchedFor(profileId: String, json: String) = prefs.edit().putString(profileKey("last_watched_for", profileId), json).apply()

    /** Home's background tint follows the highlighted picture's colours. */
    var tintFromPicture: Boolean
        get() = prefs.getBoolean("tint_from_picture", true)
        set(value) = prefs.edit().putBoolean("tint_from_picture", value).apply()

    /** Tuner's own artwork screensaver after a few idle minutes (0 = off). */
    var artSaverMinutes: Int
        get() = prefs.getInt("art_saver_minutes", 5)
        set(value) = prefs.edit().putInt("art_saver_minutes", value).apply()

    /** Plex libraries kids' profiles may see ("serverId/sectionKey"). */
    var plexKidsSections: Set<String>
        get() = prefs.getStringSet("plex_kids_sections", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("plex_kids_sections", value).apply()

    /** Play an IPTV film from Plex instead when Plex has it. */
    var preferPlexFilms: Boolean
        get() = prefs.getBoolean("prefer_plex_films", true)
        set(value) = prefs.edit().putBoolean("prefer_plex_films", value).apply()

    /** Plex library order, e.g. "added" – see PlexFeature.SORTS. */
    var plexSort: String
        get() = prefs.getString("plex_sort", "title") ?: "title"
        set(value) = prefs.edit().putString("plex_sort", value).apply()

    var openOnBoot: Boolean
        get() = prefs.getBoolean("open_on_boot", false)
        set(value) = prefs.edit().putBoolean("open_on_boot", value).apply()

    /** Show one entry for HD/SD copies of the same channel. */
    var mergeVersions: Boolean
        get() = prefs.getBoolean("merge_versions", true)
        set(value) = prefs.edit().putBoolean("merge_versions", value).apply()
    /** Merged channel (ChannelMerge.key) → the version you chose to watch. */
    var preferredVersions: Map<String, String>
        get() = stringMap("preferred_versions")
        set(value) = setStringMap("preferred_versions", value)

    /** Every favourite from every profile (for the nightly health check). */
    fun allFavourites(): Set<String> = profiles.flatMap { favourites(it.id) }.toSet()

    // ---------------- Backup & restore ----------------

    private val notBackedUp = listOf("preview_channel_id", "watch_next_ids", "last_refresh_", "usage_", "bonus_", "schema", "dead_channels", "health_checked_at", "recordings", "watch_stats", "live_tab_rows", "plex_client_id", "flaky_streams", "db_tidy_at", "new_eps_checked_at", "crash_reported_at", "sport_auto_done", "sync_stamp_", "synced_at", "nas_backup_at", "update_checked_at", "update_dismissed", "vpn_", "device_mode", "link_device_id", "link_name", "downloads", "habits_", "widget_now", "football_team_list", "football_fixtures", "team_announced", "text_scale", "gdrive_", "cloud_", "yt_", "plex_user_", "watching_slots", "blocked_log_", "trakt_")

    /** Every setting as JSON (playlists, profiles, favourites, PIN, reminders, guide fixes…). */
    fun exportBackup(): String {
        val root = JSONObject().put("app", "Tuner").put("version", 3)
        val values = JSONObject()
        for ((key, value) in prefs.all) {
            if (notBackedUp.any { key.startsWith(it) }) continue
            // Passwords here are encrypted with this device's own key: the backup gets them readable,
            // so it can be restored on another device (as before).
            if (key == "sources") {
                values.put(key, JSONObject().put("t", "s").put("v", plainSourcesJson().toString()))
                continue
            }
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            } ?: continue
            values.put(key, entry)
        }
        root.put("settings", values)
        return root.toString(2)
    }

    /** Restores a backup made by [exportBackup]. Throws if it isn't one. */
    fun importBackup(json: String) {
        val root = JSONObject(json)
        // Backups still say "Tuner" inside (the app's old name) so older versions can read them too.
        if (root.optString("app") !in setOf("Tuner", "Flicker")) throw IllegalArgumentException("That isn't a Flicker backup")
        val values = root.getJSONObject("settings")
        val e = prefs.edit()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = values.getJSONObject(key)
            when (entry.optString("t")) {
                "s" -> e.putString(key, entry.getString("v"))
                "b" -> e.putBoolean(key, entry.getBoolean("v"))
                "i" -> e.putInt(key, entry.getInt("v"))
                "l" -> e.putLong(key, entry.getLong("v"))
                "f" -> e.putFloat(key, entry.getDouble("v").toFloat())
                "set" -> {
                    val arr = entry.getJSONArray("v")
                    e.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        e.commit()
        // Force a fresh download of every playlist and guide
        sources.forEach { prefs.edit().putLong("last_refresh_${it.id}", 0L).apply() }
    }

    // ---------------- Migration 2.0 -> 3.0 ----------------

    /** 2.0 cached playlists as JSON files; 3.0 uses SQLite, so drop them and re-download. */
    private fun migrateToV3() {
        if (prefs.getInt("schema", 2) >= 3) return
        dir.listFiles()?.forEach { f ->
            if ((f.name.startsWith("channels_") || f.name.startsWith("epg_")) && f.name.endsWith(".json")) f.delete()
        }
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith("last_refresh_")) e.putLong(k, 0L)
        e.putInt("schema", 3).apply()
    }

    /** 4.2 stores provider channel numbers and category order – re-download playlists once. */
    private fun migrateToV4() {
        if (prefs.getInt("schema", 3) >= 4) return
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith("last_refresh_")) e.putLong(k, 0L)
        e.putInt("schema", 4).apply()
    }

    companion object {
        /** Sync sections kept by Store: your playlists, and TV guide settings. */
        const val PLAYLISTS = "playlists"
        const val GUIDE_SETTINGS = "guide_settings"

        const val MAIN_PROFILE = "main"

        fun profilesToJson(list: List<Profile>): JSONArray {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("kids", it.kids)
                        .put("groups", JSONArray(it.allowedGroups.toList())).put("limit", it.dailyLimitMinutes)
                        .put("bedtime", it.bedtimeMinutes).put("colour", it.colour).put("icon", it.icon).put("yt", it.youtube)
                        .put("yt_only", it.ytApprovedOnly).put("yt_ch", JSONArray(it.ytChannels)).put("cert", it.maxCertificate)
                )
            }
            return arr
        }

        fun profilesFromJson(arr: JSONArray): List<Profile> = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val groups = o.optJSONArray("groups")
            Profile(
                id = o.optString("id").ifBlank { return@mapNotNull null },
                name = o.optString("name"),
                kids = o.optBoolean("kids"),
                allowedGroups = if (groups == null) emptySet() else (0 until groups.length()).map { groups.getString(it) }.toSet(),
                dailyLimitMinutes = o.optInt("limit"),
                bedtimeMinutes = o.optInt("bedtime", -1),
                colour = o.optInt("colour", -1),
                icon = o.optString("icon"),
                youtube = o.optBoolean("yt", true),
                ytApprovedOnly = o.optBoolean("yt_only"),
                ytChannels = o.optJSONArray("yt_ch")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                maxCertificate = o.optString("cert"),
            )
        }
        const val DEFAULT_UPDATE_REPO = "titanejstudios/Flicker"
        private val RECORDING_LOCK = Any()
    }

}
