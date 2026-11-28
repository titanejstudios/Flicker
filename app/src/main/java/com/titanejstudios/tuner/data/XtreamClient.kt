package com.titanejstudios.tuner.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

object XtreamClient {

    fun normaliseServer(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return s
    }

    private fun apiBase(src: Source.Xtream): String {
        val u = URLEncoder.encode(src.username, "UTF-8")
        val p = URLEncoder.encode(src.password, "UTF-8")
        return "${normaliseServer(src.server)}/player_api.php?username=$u&password=$p"
    }

    fun epgUrl(src: Source.Xtream): String {
        val u = URLEncoder.encode(src.username, "UTF-8")
        val p = URLEncoder.encode(src.password, "UTF-8")
        return "${normaliseServer(src.server)}/xmltv.php?username=$u&password=$p"
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("The server didn't reply like an Xtream Codes panel")
    }

    private fun categories(api: String, action: String): Map<String, String> =
        categoriesInOrder(api, action).mapValues { it.value.first }

    /** category id -> (name, position in the provider's list) */
    private fun categoriesInOrder(api: String, action: String): Map<String, Pair<String, Int>> {
        val arr = JSONArray(Http.readText("$api&action=$action"))
        val out = HashMap<String, Pair<String, Int>>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out[c.optString("category_id")] = c.optString("category_name").trim() to i
        }
        return out
    }

    private fun JSONObject.str(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    /** Account details for the self-test: status, expiry and connections. Throws if the login fails. */
    fun accountSummary(src: Source.Xtream): String = wrap {
        val info = JSONObject(Http.readText(apiBase(src)))
        val user = info.optJSONObject("user_info")
        if (user == null || user.optInt("auth", 0) != 1) throw IOException("Login failed – check the server, username and password")
        val parts = ArrayList<String>()
        user.str("status")?.let { parts += it }
        user.str("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.let { secs ->
            parts += "expires " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(secs * 1000))
        }
        val max = user.str("max_connections")
        if (max != null) parts += "${user.str("active_cons") ?: "0"} of $max streams in use"
        parts.joinToString(" · ").ifEmpty { "Logged in" }
    }

    /**
     * An Xtream-style M3U link (…/get.php?username=…&password=…) → that panel's player_api address,
     * or null for any other kind of playlist.
     */
    fun apiFromM3u(playlistUrl: String): String? = runCatching {
        val uri = java.net.URI(playlistUrl.trim())
        if (!uri.path.orEmpty().endsWith("/get.php", ignoreCase = true)) return@runCatching null
        val q = uri.rawQuery ?: return@runCatching null
        val params = q.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val u = params["username"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val pw = params["password"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port/player_api.php?username=$u&password=$pw"
    }.getOrNull()

    /** The server a playlist streams from (to tell whether all your playlists share one account). */
    fun hostOf(src: Source): String? = runCatching {
        when (src) {
            is Source.Xtream -> java.net.URI(normaliseServer(src.server)).host
            is Source.M3u -> java.net.URI(src.playlistUrl.trim()).host
        }
    }.getOrNull()?.lowercase()

    /** [maxConnections] for an Xtream-style M3U link (null if it isn't one, or the panel doesn't say). */
    fun maxConnectionsM3u(src: Source.M3u): Int? = try {
        apiFromM3u(src.playlistUrl)?.let { JSONObject(Http.readText(it)).optJSONObject("user_info")?.str("max_connections")?.toIntOrNull() }
    } catch (e: Exception) {
        null
    }

    /** How many streams the account allows at once (null if the panel doesn't say). */
    fun maxConnections(src: Source.Xtream): Int? = try {
        JSONObject(Http.readText(apiBase(src))).optJSONObject("user_info")?.str("max_connections")?.toIntOrNull()
    } catch (e: Exception) {
        null
    }

    /** Live channels. Also checks the login and picks up the server time zone for catch-up. */
    fun load(src: Source.Xtream): List<Channel> = wrap {
        val api = apiBase(src)
        val base = normaliseServer(src.server)
        val info = JSONObject(Http.readText(api))
        val user = info.optJSONObject("user_info")
        if (user == null || user.optInt("auth", 0) != 1) {
            throw IOException("Login failed – check the server, username and password")
        }
        val status = user.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) {
            throw IOException("Account status is \"$status\"")
        }
        val timezone = info.optJSONObject("server_info")?.str("timezone")

        val categoryInfo = categoriesInOrder(api, "get_live_categories")
        val streams = JSONArray(Http.readText("$api&action=get_live_streams"))
        val ext = if (src.useHls) "m3u8" else "ts"
        val out = ArrayList<Channel>(streams.length())
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val streamId = s.str("stream_id") ?: continue
            val archive = s.optInt("tv_archive", 0) == 1
            val archiveDays = s.optInt("tv_archive_duration", 0)
            out += Channel(
                id = "${src.id}/x:$streamId",
                name = s.optString("name").trim(),
                group = categoryInfo[s.optString("category_id")]?.first?.takeIf { it.isNotEmpty() } ?: "Uncategorised",
                logo = s.str("stream_icon"),
                url = "$base/live/${src.username}/${src.password}/$streamId.$ext",
                number = s.optInt("num", 0),
                groupPos = categoryInfo[s.optString("category_id")]?.second ?: Int.MAX_VALUE,
                epgId = s.str("epg_channel_id"),
                sourceId = src.id,
                catchupDays = if (archive) archiveDays.coerceAtLeast(1) else 0,
                catchupTemplate = if (archive) Catchup.xtreamTemplate(base, src.username, src.password, streamId) else null,
                catchupTz = timezone,
            )
        }
        // Same order as the provider (and TiviMate): by channel number, server order for ties/none.
        out.sortedWith(compareBy { if (it.number > 0) it.number else Int.MAX_VALUE })
    }

    fun loadMovies(src: Source.Xtream): List<VodItem> = wrap {
        val api = apiBase(src)
        val base = normaliseServer(src.server)
        val cats = categories(api, "get_vod_categories")
        val arr = JSONArray(Http.readText("$api&action=get_vod_streams"))
        val out = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("stream_id") ?: continue
            val ext = o.str("container_extension") ?: "mp4"
            out += VodItem(
                id = "${src.id}/m$id",
                name = o.optString("name").trim(),
                poster = o.str("stream_icon"),
                category = cats[o.optString("category_id")]?.takeIf { it.isNotEmpty() } ?: "Other",
                url = "$base/movie/${src.username}/${src.password}/$id.$ext",
                rating = o.str("rating")?.takeIf { it != "0" },
            )
        }
        out
    }

    fun loadSeries(src: Source.Xtream): List<SeriesItem> = wrap {
        val api = apiBase(src)
        val cats = categories(api, "get_series_categories")
        val arr = JSONArray(Http.readText("$api&action=get_series"))
        val out = ArrayList<SeriesItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("series_id") ?: continue
            out += SeriesItem(
                id = "${src.id}/s$id",
                sourceId = src.id,
                seriesId = id,
                name = o.optString("name").trim(),
                cover = o.str("cover"),
                category = cats[o.optString("category_id")]?.takeIf { it.isNotEmpty() } ?: "Other",
                plot = o.str("plot"),
                rating = o.str("rating")?.takeIf { it != "0" },
            )
        }
        out
    }

    fun loadEpisodes(src: Source.Xtream, seriesId: String): List<Episode> = wrap {
        val api = apiBase(src)
        val base = normaliseServer(src.server)
        val root = JSONObject(Http.readText("$api&action=get_series_info&series_id=$seriesId"))
        val seasons = ArrayList<JSONArray>()
        root.optJSONObject("episodes")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) obj.optJSONArray(keys.next())?.let { seasons += it }
        } ?: root.optJSONArray("episodes")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONArray(i)?.let { seasons += it }
        }
        val out = ArrayList<Episode>()
        for (season in seasons) {
            for (i in 0 until season.length()) {
                val e = season.getJSONObject(i)
                val id = e.str("id") ?: continue
                val ext = e.str("container_extension") ?: "mp4"
                val info = e.optJSONObject("info")
                out += Episode(
                    id = "${src.id}/e$id",
                    season = e.optInt("season", 1),
                    number = e.optInt("episode_num", i + 1),
                    title = e.optString("title").trim(),
                    url = "$base/series/${src.username}/${src.password}/$id.$ext",
                    plot = info?.str("plot"),
                    image = info?.str("movie_image"),
                    durationSecs = info?.optInt("duration_secs", 0) ?: 0,
                )
            }
        }
        out.sortedWith(compareBy({ it.season }, { it.number }))
    }
}
