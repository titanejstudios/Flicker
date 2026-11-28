package com.titanejstudios.tuner.data

/** Picks sport out of the TV guide and sorts it into types. */
object Sports {
    val TYPES = listOf(
        "Football", "Rugby", "Cricket", "Motorsport", "Tennis", "Golf",
        "Boxing & UFC", "Darts & Snooker", "US sports", "Racing", "Other",
    )

    private val patterns: List<Pair<String, Regex>> = listOf(
        "Darts & Snooker" to Regex("\\b(darts|snooker|pdc|world matchplay|grand slam of darts)\\b"),
        "Cricket" to Regex(
            "\\b(cricket|test match|the hundred|ipl|t20i?|odis?|ashes|\\d(st|nd|rd|th) test|test day|asia ?cup|" +
                "big bash|bbl|wbbl|county championship|sheffield shield|ranji trophy|sa20|ilt20|psl|cpl|" +
                "vitality blast|one-day cup|rising stars)\\b"
        ),
        "Rugby" to Regex("\\b(rugby|six nations|premiership rugby|super league|challenge cup|urc)\\b"),
        "Football" to Regex(
            "\\b(football|soccer|premier league|champions league|europa league|conference league|fa cup|" +
                "efl|carabao cup|la liga|serie a|bundesliga|ligue 1|spfl|scottish premiership|mls|world cup|" +
                "euro 20\\d\\d|super sunday|monday night football|match of the day|super 6)\\b"
        ),
        "Motorsport" to Regex("(\\bformula ?1\\b|\\bf1\\b|grand prix|motogp|moto gp|nascar|indycar|\\bwrc\\b|btcc|formula e|superbikes)"),
        "Tennis" to Regex("\\b(tennis|wimbledon|atp|wta|roland garros|davis cup)\\b"),
        "Golf" to Regex("\\b(golf|pga|ryder cup|dp world tour|lpga)\\b"),
        "Boxing & UFC" to Regex("\\b(boxing|ufc|mma|bellator|fight night|wwe)\\b"),
        "US sports" to Regex("\\b(nfl|nba|nhl|mlb|super bowl|ncaa)\\b"),
        "Racing" to Regex("\\b(horse racing|cheltenham|grand national|royal ascot|greyhound racing|itv racing)\\b"),
    )

    /** "Liverpool v Arsenal", "Leeds vs Hull" */
    private val versus = Regex("\\p{L}[\\p{L}.']*\\s+(v|vs\\.?)\\s+\\p{Lu}")

    private const val CODES = "eng|aus|ind|sa|rsa|nz|pak|sl|si|ban|afg|wi|zim|ire|sco|ned|uae|nep|oma|usa|can"
    private val cricketCodes = Regex("\\b($CODES)\\s+(v|vs\\.?)\\s+($CODES)\\b", RegexOption.IGNORE_CASE)

    /** Guide categories and channel groups that are plainly not sport ("Alex vs. America" on Food Network). */
    private val notSport = Regex(
        "\\b(food|cook|cookery|lifestyle|entertainment|reality|drama|soap|comedy|film|movie|cinema|documentar|factual|" +
            "kids|children|cartoon|animation|music|quiz|game ?show|talk|travel|home|property|history|nature|science|shopping|religio)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @return the sport type, or null if this isn't sport. [channelGroup] (the channel's group)
     * helps rule out match-ups that aren't sport, like a cookery show called "Alex vs. America".
     */
    fun classify(p: Programme, channelGroup: String? = null): String? {
        val cat = p.category?.lowercase() ?: ""
        if ("news" in cat) return null
        val text = (p.title + " " + cat + " " + (p.desc ?: "").take(160)).lowercase()
        val titleText = (p.title + " " + cat).lowercase()
        for ((name, re) in patterns) if (re.containsMatchIn(titleText)) return name
        val sportCategory = "sport" in cat
        if (versus.containsMatchIn(p.title)) {
            // Match-ups: look at the description for a clue, otherwise football (the usual UK case).
            for ((name, re) in patterns) if (re.containsMatchIn(text)) return name
            // …unless the guide or the channel says it's something else entirely.
            if (!sportCategory) {
                if (cat.isNotBlank() && notSport.containsMatchIn(cat)) return null
                val group = channelGroup.orEmpty()
                if (group.isNotBlank() && !group.contains("sport", ignoreCase = true) && notSport.containsMatchIn(group)) return null
            }
            // "SA v BAN", "IND vs AUS" – short country codes are how cricket is listed.
            if (cricketCodes.containsMatchIn(p.title)) return "Cricket"
            return "Football"
        }
        if (sportCategory) {
            for ((name, re) in patterns) if (re.containsMatchIn(text)) return name
            return "Other"
        }
        return null
    }

    /** Highlights, repeats and shows about the sport, not the sport itself ("Brentford v Everton Hlts"). */
    private val notLive = Regex(
        "\\b(highlights?|h/?lts|hlts|hilights|hl|extended|review|replay|re-?run|repeat|classic|classics|preview|magazine|" +
            "rewind|recap|reloaded|best of|goals|catch-?up|in \\d+ minutes|uncut|story of|retro|archive|vintage|build-?up|reaction|debate|show)\\b|\\(r\\)|\\[r\\]"
    )

    /** Shown live rather than highlights/previews/replays (and a sensible match length). */
    fun looksLive(p: Programme): Boolean {
        val title = p.title.lowercase()
        if (notLive.containsMatchIn(title)) return false
        val t = (title + " " + (p.desc ?: "").take(160)).lowercase()
        // The description gives it away: "Highlights of…", "Another chance to see…", "Action from yesterday's…".
        if (Regex("\\b(highlights|another chance|action from (yesterday|last|saturday|sunday|monday|tuesday|wednesday|thursday|friday)|repeat of|first shown)\\b").containsMatchIn(t)) return false
        if (Regex("\\blive\\b").containsMatchIn(t)) return true
        return versus.containsMatchIn(p.title) && p.stop - p.start <= 4 * HOUR
    }

    // ---------------- 24/7 "event" channels ----------------

    private const val HOUR = 3_600_000L

    /** "Next: …", "Coming up: …", "Upcoming - …" */
    private val nextPrefix = Regex(
        "^\\s*(next( event| match| up)?|coming up|upcoming|up next|later)\\s*[:\\-–—|]\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** Filler used by event channels when nothing is on. */
    private val placeholder = Regex(
        "^\\s*(no (live )?(event|events|programme|program|information|match|stream)|off ?air|" +
            "event (will )?(start|begin)|tba\\b|to be announced|stream (starts|offline)|channel (closed|offline)|" +
            "nothing (on|scheduled)|stay tuned)",
        RegexOption.IGNORE_CASE,
    )

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "Sun 29 Nov 16:30", "Sunday 29th November @ 16:30", "29 Nov 16:30" */
    private val dayMonthTime = Regex(
        "(?:(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\.?,?\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+" +
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?,?\\s*(?:at|@|-|–|—)?\\s*(\\d{1,2})[:.](\\d{2})",
        RegexOption.IGNORE_CASE,
    )

    /** "29/11 16:30", "29.11.2026 16:30" */
    /** "EPL02: Man Utd 15:00 Brentford 28/11": an optional slot code, home, time, away, optional date. */
    private val slot = Regex(
        "^\\s*([A-Za-z]{2,8}\\s?\\d{1,3}\\s*[:\\-–—|]\\s*)?([\\p{L}][\\p{L} .'&-]{1,40}?)\\s+(\\d{1,2})[:.](\\d{2})\\s+" +
            "([\\p{L}][\\p{L} .'&-]{1,40}?)(?:\\s+(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?)?\\s*$",
    )

    private val numericDateTime = Regex(
        "(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\s*(?:at|@|-|–|—)?\\s*(\\d{1,2})[:.](\\d{2})",
        RegexOption.IGNORE_CASE,
    )

    /** "16:30" on its own */
    private val timeOnly = Regex("(?<![\\d/.:])(\\d{1,2})[:.](\\d{2})(?![\\d/.])")

    /**
     * Turns event-channel listings into real events. A 24/7 team channel typically shows one
     * all-day entry like "Next: Arsenal v Manchester City — Sun 29 Nov 16:30"; this returns
     * "Arsenal v Manchester City" starting at that time (2 hours long). Filler such as
     * "No event" returns null. Ordinary programmes come back unchanged.
     */
    fun normalise(p: Programme, now: Long): Programme? {
        var title = p.title.trim()
        if (placeholder.containsMatchIn(title)) return null
        val hadPrefix = nextPrefix.containsMatchIn(title)
        if (hadPrefix) title = nextPrefix.replace(title, "")
        if (placeholder.containsMatchIn(title)) return null

        val long = p.stop - p.start > 4 * HOUR
        // Event slots: "EPL02: Man Utd 15:00 Brentford 28/11" – home, kick-off time, away, date.
        slot.find(title)?.let { m ->
            val coded = m.groupValues[1].isNotBlank()
            if (coded || hadPrefix || long) {
                val home = cleanTitle(m.groupValues[2])
                val away = cleanTitle(m.groupValues[5])
                val h = m.groupValues[3].toInt()
                val min = m.groupValues[4].toInt()
                val cal = java.util.Calendar.getInstance()
                val start = if (m.groupValues[6].isNotBlank()) {
                    val year = m.groupValues[8].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
                    at(cal, now, m.groupValues[6].toInt(), m.groupValues[7].toInt() - 1, year, h, min)
                } else findWhen("$h:${"%02d".format(min)}", now)?.first
                if (start != null && home.length >= 2 && away.length >= 2) return p.copy(title = "$home v $away", start = start, stop = start + 2 * HOUR)
            }
        }
        if (hadPrefix || long) {
            val found = findWhen(title, now)
            if (found != null) {
                val (start, range) = found
                val cleaned = cleanTitle(title.removeRange(range))
                if (cleaned.isEmpty()) return null
                return p.copy(title = cleaned, start = start, stop = start + 2 * HOUR)
            }
            // "Next: …" without a time we can read is never "on now".
            if (hadPrefix) return null
        }
        // An all-day block without "live" in it is a channel filler, not an event.
        if (p.stop - p.start > 6 * HOUR && !Regex("\\blive\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return null
        return if (title == p.title) p else p.copy(title = cleanTitle(title))
    }

    private fun cleanTitle(s: String): String =
        s.replace(Regex("\\(\\s*(uk|gmt|bst|cet|et)?\\s*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('-', '–', '—', '|', '@', ':', ',', ' ')
            .trim()

    /** Finds a date/time in the title. Returns the start time and where it was in the text. */
    private fun findWhen(title: String, now: Long): Pair<Long, IntRange>? {
        val cal = java.util.Calendar.getInstance()
        dayMonthTime.find(title)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = months.indexOf(m.groupValues[2].lowercase().take(3))
            val start = at(cal, now, day, month, null, m.groupValues[3].toInt(), m.groupValues[4].toInt()) ?: return null
            return start to m.range
        }
        numericDateTime.find(title)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt() - 1
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val start = at(cal, now, day, month, year, m.groupValues[4].toInt(), m.groupValues[5].toInt()) ?: return null
            return start to m.range
        }
        timeOnly.find(title)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            cal.timeInMillis = now
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, min)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            // A time already more than 3 hours gone means tomorrow.
            if (cal.timeInMillis < now - 3 * HOUR) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis to m.range
        }
        return null
    }

    private fun at(cal: java.util.Calendar, now: Long, day: Int, month: Int, year: Int?, h: Int, min: Int): Long? {
        if (month !in 0..11 || day !in 1..31 || h > 23 || min > 59) return null
        cal.timeInMillis = now
        val thisYear = cal.get(java.util.Calendar.YEAR)
        cal.set(year ?: thisYear, month, day, h, min, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        // "5 Jan" seen in December means next year.
        if (year == null && cal.timeInMillis < now - 60L * 24 * HOUR) cal.add(java.util.Calendar.YEAR, 1)
        return cal.timeInMillis
    }
}
