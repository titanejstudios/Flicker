package com.titanejstudios.tuner.integrations

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Fixtures from ESPN's public site data (no key or account): football, rugby and US sports. It isn't an official API,
 * so everything here fails quietly – Flicker then falls back to finding games in the TV guide.
 */
object Espn {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"

    /** ESPN's path for a league: "eng.1" (saved by older versions) is football; others say their sport ("rugby/267979"). */
    fun path(league: String): String = if ('/' in league) league else "soccer/$league"

    /**
     * Sports you can follow. Those with leagues get fixtures from ESPN; the rest (and any team ESPN
     * doesn't list) are found by name in the TV guide. [guideType] is the guide's sport (Sports.TYPES),
     * so a Newcastle rugby fan isn't shown Newcastle United.
     */
    class SportChoice(val key: String, val label: String, val emoji: String, val guideType: String, val leagues: List<Pair<String, String>>, val hint: String)

    val SPORTS: List<SportChoice> by lazy {
        listOf(
            SportChoice("football", "Football", "⚽", "Football", LEAGUES, "Part of the name, e.g. Newcastle"),
            SportChoice(
                "rugby", "Rugby union", "🏉", "Rugby",
                listOf(
                    "rugby/267979" to "Premiership Rugby", "rugby/270557" to "United Rugby Championship",
                    "rugby/270559" to "Top 14", "rugby/180659" to "Six Nations", "rugby/242041" to "Super Rugby",
                ),
                "A club or country, e.g. Newcastle Red Bulls or England",
            ),
            SportChoice("rugbyleague", "Rugby league", "🏉", "Rugby", listOf("rugby-league/3" to "Super League"), "e.g. Wigan Warriors"),
            SportChoice("nfl", "American football", "🏈", "US sports", listOf("football/nfl" to "NFL"), "e.g. Patriots"),
            SportChoice("nba", "Basketball", "🏀", "US sports", listOf("basketball/nba" to "NBA"), "e.g. Lakers"),
            SportChoice("nhl", "Ice hockey", "🏒", "US sports", listOf("hockey/nhl" to "NHL"), "e.g. Maple Leafs"),
            SportChoice("mlb", "Baseball", "⚾", "US sports", listOf("baseball/mlb" to "MLB"), "e.g. Yankees"),
            SportChoice("cricket", "Cricket", "🏏", "Cricket", emptyList(), "A team or competition, e.g. England or The Hundred"),
            SportChoice("motorsport", "F1 & motorsport", "🏎", "Motorsport", emptyList(), "As the guide says it, e.g. Formula 1 or MotoGP"),
            SportChoice("darts", "Darts & snooker", "🎯", "Darts & Snooker", emptyList(), "A player or event, e.g. Luke Littler or Premier League Darts"),
            SportChoice("tennis", "Tennis", "🎾", "Tennis", emptyList(), "A player or event, e.g. Draper or Wimbledon"),
            SportChoice("golf", "Golf", "⛳", "Golf", emptyList(), "A player or event, e.g. Rory McIlroy or The Open"),
            SportChoice("boxing", "Boxing & UFC", "🥊", "Boxing & UFC", emptyList(), "A fighter, e.g. Anthony Joshua"),
            SportChoice("racing", "Horse racing", "🏇", "Racing", emptyList(), "A meeting, e.g. Cheltenham"),
        )
    }

    fun sport(key: String?): SportChoice? = SPORTS.firstOrNull { it.key == key }

    /** Which sport a saved ESPN league belongs to. */
    fun sportOfLeague(league: String): SportChoice? = SPORTS.firstOrNull { s -> s.leagues.any { it.first == league } }
        ?: if ('/' !in league) SPORTS.first() else null

    /** Leagues whose teams you can pick from (ESPN's codes). */
    val LEAGUES = listOf(
        "eng.1" to "Premier League",
        "eng.2" to "Championship",
        "eng.3" to "League One",
        "eng.4" to "League Two",
        "eng.5" to "National League",
        "sco.1" to "Scottish Premiership",
        "sco.2" to "Scottish Championship",
        "wal.1" to "Cymru Premier",
        "irl.1" to "League of Ireland",
        "esp.1" to "LaLiga",
        "ita.1" to "Serie A",
        "ger.1" to "Bundesliga",
        "fra.1" to "Ligue 1",
        "ned.1" to "Eredivisie",
        "por.1" to "Primeira Liga",
        "usa.1" to "MLS",
        "eng.w.1" to "Women's Super League",
    )

    /** A team you can follow: ESPN's id and league, and its names. */
    class Team(val id: String, val league: String, val leagueName: String, val name: String, val shortName: String) {
        /** How it's saved: "eng.1:361". */
        val key: String get() = "$league:$id"

        fun toJson(): JSONObject = JSONObject().put("id", id).put("lg", league).put("lgn", leagueName).put("n", name).put("s", shortName)

        companion object {
            fun fromJson(o: JSONObject) = Team(o.optString("id"), o.optString("lg"), o.optString("lgn"), o.optString("n"), o.optString("s"))
        }
    }

    /** One match. [timeKnown] false = the date's set but not the kick-off time. */
    class Fixture(
        val id: String,
        val home: String,
        val away: String,
        val kickoff: Long,
        val competition: String?,
        /** "pre" (to come), "in" (playing) or "post" (finished). */
        val state: String,
        val timeKnown: Boolean,
        /** Which of your teams this is for. */
        val team: String,
    ) {
        val live: Boolean get() = state == "in"
        val finished: Boolean get() = state == "post"

        fun toJson(): JSONObject = JSONObject().put("id", id).put("h", home).put("a", away).put("k", kickoff)
            .put("c", competition ?: "").put("st", state).put("tk", timeKnown).put("t", team)

        companion object {
            fun fromJson(o: JSONObject) = Fixture(
                o.optString("id"), o.optString("h"), o.optString("a"), o.optLong("k"),
                o.optString("c").ifBlank { null }, o.optString("st", "pre"), o.optBoolean("tk", true), o.optString("t"),
            )
        }
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Flicker")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) throw IllegalStateException("ESPN said ${c.responseCode}")
            return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }

    /** Every team in one league (blocking). */
    fun teams(league: String, leagueName: String): List<Team> {
        val leagues = get("$BASE/${path(league)}/teams").optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues") ?: return emptyList()
        val arr = leagues.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
            val id = t.optString("id").ifBlank { return@mapNotNull null }
            val name = t.optString("displayName").ifBlank { t.optString("name") }
            Team(id, league, leagueName, name, t.optString("shortDisplayName").ifBlank { name })
        }
    }

    /** A team's coming matches (cups too), soonest first. Blocking. */
    fun fixtures(team: Team, followedName: String): List<Fixture> {
        val o = get("$BASE/${path(team.league)}/teams/${team.id}/schedule" + if ('/' in team.league) "" else "?fixture=true")
        val events = o.optJSONArray("events") ?: JSONArray()
        val out = ArrayList<Fixture>()
        for (i in 0 until events.length()) {
            out += parseEvent(events.optJSONObject(i) ?: continue, team.leagueName, followedName) ?: continue
        }
        return out.sortedBy { it.kickoff }
    }

    /** One ESPN event as a fixture, or null if it isn't a two-sided match. */
    private fun parseEvent(e: JSONObject, defaultCompetition: String, followedName: String): Fixture? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val sides = comp.optJSONArray("competitors") ?: return null
        var home: String? = null
        var away: String? = null
        for (j in 0 until sides.length()) {
            val s = sides.optJSONObject(j) ?: continue
            val n = s.optJSONObject("team")?.let { it.optString("shortDisplayName").ifBlank { it.optString("displayName") } } ?: continue
            if (s.optString("homeAway") == "home") home = n else away = n
        }
        if (home == null || away == null) return null
        val kickoff = parseTime(e.optString("date").ifBlank { comp.optString("date") }) ?: return null
        val state = comp.optJSONObject("status")?.optJSONObject("type")?.optString("state")
            ?: e.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"
        val competition = e.optJSONObject("league")?.optString("name")?.ifBlank { null }
            ?: e.optJSONObject("seasonType")?.optString("name")?.ifBlank { null }
            ?: defaultCompetition
        return Fixture(
            e.optString("id").ifBlank { "$home-$away-$kickoff" }, home, away, kickoff, competition,
            state.ifBlank { "pre" }, comp.optBoolean("timeValid", e.optBoolean("timeValid", true)), followedName,
        )
    }

    /** Cups and European competitions, for the Sports page's full fixture list (football). */
    val CUPS = listOf(
        "uefa.champions" to "Champions League",
        "uefa.europa" to "Europa League",
        "uefa.europa.conf" to "Conference League",
        "eng.fa" to "FA Cup",
        "eng.league_cup" to "Carabao Cup",
        "sco.tennents" to "Scottish Cup",
    )

    /** Every match in one competition between two days ("20261128", "20261205"), soonest first. Blocking. */
    fun scoreboard(league: String, leagueName: String, fromDay: String, toDay: String): List<Fixture> =
        scoreboardAt(league, leagueName, "$fromDay-$toDay&limit=300")

    /** One day's matches in a competition ("20261128"), for when a range isn't accepted. Blocking. */
    fun scoreboardDay(league: String, leagueName: String, day: String): List<Fixture> = scoreboardAt(league, leagueName, day)

    private fun scoreboardAt(league: String, leagueName: String, dates: String): List<Fixture> {
        val o = get("$BASE/${path(league)}/scoreboard?dates=$dates")
        val name = o.optJSONArray("leagues")?.optJSONObject(0)?.optString("name")?.ifBlank { null } ?: leagueName
        val events = o.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Fixture>()
        for (i in 0 until events.length()) {
            val f = parseEvent(events.optJSONObject(i) ?: continue, name, "") ?: continue
            // Each competition's own name (the event's "league" can be the season type).
            out += Fixture(f.id, f.home, f.away, f.kickoff, name, f.state, f.timeKnown, "")
        }
        return out.sortedBy { it.kickoff }
    }

    private val formats = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    private fun parseTime(s: String): Long? {
        if (s.isBlank()) return null
        for (f in formats) {
            val p = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            runCatching { return p.parse(s)?.time }
        }
        return null
    }
}
