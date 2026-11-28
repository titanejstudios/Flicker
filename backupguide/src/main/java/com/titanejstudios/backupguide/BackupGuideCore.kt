package com.titanejstudios.backupguide

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Backup TV guide – personal use only. The part with no Android in it, shared by the TV app (via
 * [BackupGuide]) and the PC app: the free sources, matching channels, and filling gaps.
 * To remove it, see README.md in this module.
 */
object BackupGuideCore {
    /**
     * One free guide. [urls] are tried in order until one answers. [country] ("UK", "US", "CH"…):
     * it's only read if you have channels from there, and only matched to those (another country's
     * schedule for "History Channel" is a different schedule). null = any channel.
     */
    class Source(val key: String, val name: String, val about: String, val urls: List<String>, val country: String? = null)

    val SOURCES = listOf(
        // UK
        Source("epgtalk_uk", "EPGTalk UK", "UK channels, including Sky", listOf("https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/UK_guide.xml.gz"), "UK"),
        Source("freeview", "Freeview-EPG", "UK free-to-air channels", listOf("https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml"), "UK"),
        Source("epgpw_uk", "epg.pw UK", "more UK channels", listOf("https://epg.pw/xmltv/epg_GB.xml.gz", "https://epg.pw/xmltv/epg_GB.xml"), "UK"),
        Source("epgshare_uk", "epgshare01 UK", "more UK channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"), "UK"),
        // US
        Source("epgshare_us_locals", "epgshare01 US local stations", "ABC, CBS, NBC, FOX, PBS… locals, matched by call sign", listOf("https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz"), "US"),
        Source("epgtalk_us", "EPGTalk US", "US channels", listOf("https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz"), "US"),
        Source("epgpw_us", "epg.pw US", "more US channels", listOf("https://epg.pw/xmltv/epg_US.xml.gz", "https://epg.pw/xmltv/epg_US.xml"), "US"),
        Source("epgshare_us2", "epgshare01 US", "more US channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"), "US"),
        // The rest of the world (each only used if you have channels from that country)
        Source("epgshare_ch", "epgshare01 Switzerland", "Swiss channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz"), "CH"),
        Source("epgshare_pl", "epgshare01 Poland", "Polish channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_PL1.xml.gz"), "PL"),
        Source("epgshare_bg", "epgshare01 Bulgaria", "Bulgarian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_BG1.xml.gz"), "BG"),
        Source("epgshare_nl", "epgshare01 Netherlands", "Dutch channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_NL1.xml.gz"), "NL"),
        Source("epgshare_pt", "epgshare01 Portugal", "Portuguese channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_PT1.xml.gz"), "PT"),
        Source("epgshare_tr", "epgshare01 Turkey", "Turkish channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_TR3.xml.gz"), "TR"),
        Source("epgshare_tr1", "epgshare01 Turkey (more)", "more Turkish channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz"), "TR"),
        Source("epgshare_ro", "epgshare01 Romania", "Romanian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_RO1.xml.gz"), "RO"),
        Source("epgshare_ro2", "epgshare01 Romania (more)", "more Romanian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_RO2.xml.gz"), "RO"),
        Source("epgshare_it", "epgshare01 Italy", "Italian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"), "IT"),
        Source("epgshare_es", "epgshare01 Spain", "Spanish channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz"), "ES"),
        Source("epgshare_al", "epgshare01 Albania", "Albanian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_AL1.xml.gz"), "AL"),
        Source("epgshare_ca", "epgshare01 Canada", "Canadian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz"), "CA"),
        Source("epgpw_ca", "epg.pw Canada", "more Canadian channels", listOf("https://epg.pw/xmltv/epg_CA.xml.gz", "https://epg.pw/xmltv/epg_CA.xml"), "CA"),
        Source("epgshare_br", "epgshare01 Brazil", "Brazilian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_BR2.xml.gz"), "BR"),
        Source("epgpw_br", "epg.pw Brazil", "more Brazilian channels", listOf("https://epg.pw/xmltv/epg_BR.xml.gz", "https://epg.pw/xmltv/epg_BR.xml"), "BR"),
        Source("epgshare_ba", "epgshare01 Bosnia", "Bosnian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_BA1.xml.gz"), "BA"),
        Source("epgshare_be", "epgshare01 Belgium", "Belgian channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_BE2.xml.gz"), "BE"),
        Source("epgshare_ae", "epgshare01 Arabic", "Arabic channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz"), "AE"),
        Source("epgshare_de", "epgshare01 Germany", "German channels", listOf("https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz"), "DE"),
        Source("epgpw_de", "epg.pw Germany", "more German channels", listOf("https://epg.pw/xmltv/epg_DE.xml.gz", "https://epg.pw/xmltv/epg_DE.xml"), "DE"),
        Source("epgpw_au", "epg.pw Australia", "Australian channels", listOf("https://epg.pw/xmltv/epg_AU.xml.gz", "https://epg.pw/xmltv/epg_AU.xml"), "AU"),
        Source("epgpw_hk", "epg.pw Hong Kong", "Hong Kong channels, including Now Sports", listOf("https://epg.pw/xmltv/epg_HK.xml.gz", "https://epg.pw/xmltv/epg_HK.xml"), "HK"),
    )

    /** A channel of yours: its id, the guide id from the playlist (tvg-id), and its name. */
    class Channel(val id: String, val epgId: String?, val name: String, val group: String = "") {
        /** "UK", "US", "CH"… from the category or a prefix on the name; null if it doesn't say. */
        val country: String? by lazy { countryOf(name, group) }
    }

    class Programme(val start: Long, val stop: Long, val title: String, val desc: String?, val category: String?, val image: String?)

    /** A channel in one of the sources (for picking a match by hand). */
    class SourceChannel(val id: String, val name: String)

    /** The settings a fill run uses. [remembered] is updated with what worked. */
    class Settings(val custom: List<String>, val overrides: Map<String, String>, val remembered: MutableMap<String, String>)

    /** The name of a source, for "BBC One · from Freeview-EPG". */
    fun sourceName(key: String): String = SOURCES.firstOrNull { it.key == key }?.name ?: if (key.startsWith("custom")) "your own guide" else key

    /** The sources in use, built-in then your own. */
    fun sources(custom: List<String>): List<Source> = SOURCES + custom.mapIndexed { i, url ->
        Source("custom$i", "Your guide ${i + 1}", url.substringAfter("://").substringBefore('/'), listOf(url))
    }

    // ---------------- Matching ----------------

    private val countryWords = listOf(
        "UK" to listOf("uk", "gb", "united kingdom", "britain"), "IE" to listOf("ie", "ireland"),
        "US" to listOf("us", "usa", "united states", "hulu", "directtv", "directv"), "CA" to listOf("ca", "canada"),
        "CH" to listOf("ch", "switzerland", "swiss"), "PL" to listOf("pl", "poland"), "BG" to listOf("bg", "bulgaria"),
        "NL" to listOf("nl", "netherlands", "holland"), "PT" to listOf("pt", "portugal"), "TR" to listOf("tr", "turkey", "turkiye"),
        "RO" to listOf("ro", "romania"), "IT" to listOf("it", "italy"), "ES" to listOf("es", "spain"), "AL" to listOf("al", "albania"),
        "BR" to listOf("br", "brazil", "brasil"), "BA" to listOf("ba", "bosnia"), "BE" to listOf("be", "belgium"),
        "AE" to listOf("ar", "arabic", "ae"), "DE" to listOf("de", "germany", "deutschland"), "AU" to listOf("au", "australia"),
        "HK" to listOf("hk", "now hk", "hong kong"),
    )

    /** "CH: 7+" → CH, "UK | Sports*" → UK, "Bulgaria Locals" → BG, "[Hulu Arizona] KNXV" (group "US | Locals") → US. */
    fun countryOf(name: String, group: String): String? {
        fun find(text: String, prefixOnly: Boolean): String? {
            val t = text.lowercase(Locale.ROOT).trim()
            // A code or name at the start ("CH:", "PL |", "UK |", "Bulgaria Locals").
            val head = Regex("^[\\[(]?\\s*([a-z ]{2,20}?)\\s*([:|\\])\\-]|\\s|$)").find(t)?.groupValues?.get(1)?.trim()
            for ((cc, words) in countryWords) {
                if (head != null && words.any { it == head }) return cc
                if (!prefixOnly && words.any { it.length > 3 && Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(t) }) return cc
            }
            return null
        }
        return find(group, false) ?: find(name, true)
    }

    private val callSign = Regex("\\b([KW][A-Z]{2,3})(?:-?(?:DT|TV|LD|CD|LP|CA|D)(\\d)?)?\\s*$")

    /** A US station's call sign ("ABC-WALB-D2" → "WALB:2", "[Hulu Arizona] KNXV" → "KNXV:"). */
    fun callKey(name: String): String? {
        val m = callSign.find(name.trim().uppercase(Locale.ROOT)) ?: return null
        val sub = m.groupValues[2]
        return m.groupValues[1] + ":" + (if (sub == "1") "" else sub)
    }

    private val wordNumbers = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )
    private val countryPrefix = Regex("^\\s*(uk|gb|us|usa|hk|ie|ca|au)\\s*[:|\\-]+\\s*", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val quality = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "576p", "50fps", "backup", "vip", "raw")

    /**
     * A name cleaned up for matching: "UK: BBC One HD (1080p)" and "BBC 1" both become "bbc1";
     * "+1" channels stay different ("itv1+1" → "itv1plus1").
     */
    fun cleanName(name: String): String {
        var n = name.lowercase(Locale.ROOT)
            .replace(countryPrefix, "")
            .replace(bracketed, " ")
            .replace("&", " and ")
            .replace("+", " plus ")
        val words = n.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            .filter { it !in quality }
            .map { wordNumbers[it] ?: it }
        n = words.joinToString("")
        return n
    }

    // ---------------- Filling the guide ----------------

    /**
     * Reads the sources and hands back programmes for your channels where your provider's guide
     * has none. [covered] gives a channel's saved programmes as start/stop pairs (sorted by start);
     * [open] downloads an address; [add] saves a programme. Returns how many were added.
     */
    fun fill(
        settings: Settings,
        channels: List<Channel>,
        from: Long,
        to: Long,
        open: (String) -> InputStream,
        covered: (String) -> LongArray?,
        add: (String, Programme) -> Unit,
        log: (String) -> Unit,
        newParser: () -> XmlPullParser,
        saveChannels: (String, List<SourceChannel>) -> Unit,
    ): Int {
        if (channels.isEmpty()) return 0
        val overrides = settings.overrides
        val remembered = settings.remembered
        // Which source each channel takes its backup guide from (by hand, else what worked before).
        val owner = HashMap<String, String>()
        for (c in channels) {
            val o = overrides[c.id]
            if (o == "off") continue
            (o ?: remembered[c.id])?.substringBefore('|')?.let { owner[c.id] = it }
        }
        /** Your channels a source may fill: from its country (or any, for a general source). */
        fun indexes(src: Source): Triple<Map<String, List<Channel>>, Map<String, List<Channel>>, Map<String, List<Channel>>> {
            val byEpgId = HashMap<String, MutableList<Channel>>()
            val byName = HashMap<String, MutableList<Channel>>()
            val byCall = HashMap<String, MutableList<Channel>>()
            for (c in channels) {
                if (overrides[c.id] == "off") continue
                if (src.country != null && c.country != src.country) continue
                c.epgId?.takeIf { it.isNotBlank() }?.let { byEpgId.getOrPut(it.trim().lowercase()) { ArrayList() } += c }
                cleanName(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() } += c }
                if (src.country == "US") callKey(c.name)?.let { byCall.getOrPut(it) { ArrayList() } += c }
            }
            return Triple(byEpgId, byName, byCall)
        }
        val coveredCache = HashMap<String, LongArray>()
        fun free(channelId: String, start: Long, stop: Long): Boolean {
            val iv = coveredCache.getOrPut(channelId) { covered(channelId) ?: LongArray(0) }
            // Binary search for the last saved programme starting before this one ends.
            var lo = 0
            var hi = iv.size / 2 - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (iv[mid * 2] < stop) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            // Any saved programme overlapping [start, stop) means it's not a gap. Look back a few.
            var i = found
            while (i >= 0 && i >= found - 3) {
                if (iv[i * 2 + 1] > start) return false
                i--
            }
            return true
        }
        val added = HashMap<String, MutableList<LongArray>>() // what this run added, so sources don't overlap
        var total = 0
        for (src in sources(settings.custom)) {
            // A country's guide is only downloaded if you have channels from there (or chose it by hand).
            val (byEpgId, byName, byCall) = indexes(src)
            val chosen = channels.any { c -> (overrides[c.id] ?: remembered[c.id])?.substringBefore('|') == src.key }
            if (byEpgId.isEmpty() && byName.isEmpty() && byCall.isEmpty() && !chosen) continue
            val started = System.currentTimeMillis()
            val seenChannels = ArrayList<SourceChannel>()
            var n = 0
            val ok = src.urls.any { url ->
                runCatching {
                    open(url).use { input ->
                        n = readSource(newParser(), input, src, channels, byEpgId, byName, byCall, overrides, remembered, owner, from, to, seenChannels) { cid, p ->
                            val mine = added.getOrPut(cid) { ArrayList() }
                            if (!free(cid, p.start, p.stop) || mine.any { it[0] < p.stop && it[1] > p.start }) false
                            else {
                                mine += longArrayOf(p.start, p.stop)
                                add(cid, p)
                                total++
                                true
                            }
                        }
                    }
                    true
                }.onFailure { log("Backup guide: ${src.name} couldn't be read from $url (${it.message ?: it.javaClass.simpleName})") }.getOrDefault(false)
            }
            if (ok) {
                saveChannels(src.key, seenChannels)
                log("Backup guide: ${src.name} – $n programmes added in ${(System.currentTimeMillis() - started) / 1000}s")
            }
        }
        return total
    }

    private val withOffset = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val noOffset = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return runCatching {
            synchronized(this) { if (t.length > 14 && t.contains(' ')) withOffset.parse(t)!!.time else noOffset.parse(t.take(14))!!.time }
        }.getOrNull()
    }

    /** Reads one XMLTV file: matches its channels to yours, then passes on their programmes. */
    private fun readSource(
        parser: XmlPullParser,
        input: InputStream,
        src: Source,
        channels: List<Channel>,
        byEpgId: Map<String, List<Channel>>,
        byName: Map<String, List<Channel>>,
        byCall: Map<String, List<Channel>>,
        overrides: Map<String, String>,
        remembered: MutableMap<String, String>,
        owner: HashMap<String, String>,
        from: Long,
        to: Long,
        seen: MutableList<SourceChannel>,
        /** Returns whether it was used (only gaps are filled). */
        onProgramme: (String, Programme) -> Boolean,
    ): Int {
        val stream = java.io.BufferedInputStream(input, 64 * 1024)
        // .gz files (and servers that gzip without saying so).
        stream.mark(2)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        val body: InputStream = if (b0 == 0x1f && b1 == 0x8b) java.util.zip.GZIPInputStream(stream, 64 * 1024) else stream
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(body, null)

        // Guide channel id → which of your channels it fills.
        var targets: HashMap<String, List<Channel>>? = null
        val seenIds = HashSet<String>()
        val xmlNames = HashMap<String, MutableList<String>>()
        fun buildTargets(): HashMap<String, List<Channel>> {
            val t = HashMap<String, MutableList<Channel>>()
            // By hand first, then what worked before, then guide id, then cleaned name.
            for (c in channels) {
                val o = overrides[c.id] ?: continue
                if (o == "off" || o.substringBefore('|') != src.key) continue
                t.getOrPut(o.substringAfter('|').lowercase()) { ArrayList() } += c
            }
            val placed = t.values.flatten().map { it.id }.toHashSet()
            for (c in channels) {
                if (c.id in placed || overrides[c.id] != null) continue
                val r = remembered[c.id] ?: continue
                if (r.substringBefore('|') != src.key) continue
                val xid = r.substringAfter('|').lowercase()
                if (xmlNames.containsKey(xid)) {
                    t.getOrPut(xid) { ArrayList() } += c
                    placed += c.id
                }
            }
            for ((xid, names) in xmlNames) {
                val hits = LinkedHashSet<Channel>()
                byEpgId[xid]?.let { hits += it }
                if (hits.isEmpty()) for (nm in names) byName[cleanName(nm)]?.let { hits += it }
                // US locals: the station's call sign ("WALB-DT2" ↔ "ABC-WALB-D2").
                if (hits.isEmpty() && byCall.isNotEmpty()) {
                    for (k in (listOf(xid.substringBefore('.')) + names).mapNotNull { callKey(it) }) byCall[k]?.let { hits += it }
                }
                for (c in hits) {
                    if (c.id in placed) continue
                    // A channel already filled from another source stays with that one.
                    val own = owner[c.id]
                    if (own != null && own != src.key) continue
                    t.getOrPut(xid) { ArrayList() } += c
                    placed += c.id
                }
            }
            return HashMap(t)
        }

        var n = 0
        var chanId: String? = null
        var p: Array<Any?>? = null // start, stop, channel, title, desc, category, image
        var field: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        chanId = parser.getAttributeValue(null, "id")?.trim()
                        chanId?.let { xmlNames.getOrPut(it.lowercase()) { ArrayList() } }
                    }
                    "display-name" -> if (chanId != null && p == null) { field = "name"; text.setLength(0) }
                    "programme" -> {
                        if (targets == null) targets = buildTargets()
                        val cid = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                        val start = time(parser.getAttributeValue(null, "start"))
                        val stop = time(parser.getAttributeValue(null, "stop"))
                        p = if (cid != null && start != null && stop != null && stop > from && start < to && targets!!.containsKey(cid)) {
                            arrayOf(start, stop, cid, null, null, null, null)
                        } else null
                    }
                    "title", "desc", "category" -> if (p != null && p!![when (parser.name) { "title" -> 3; "desc" -> 4; else -> 5 }] == null) {
                        field = parser.name; text.setLength(0)
                    }
                    "icon" -> if (p != null && p!![6] == null) p!![6] = parser.getAttributeValue(null, "src")
                }
                XmlPullParser.TEXT -> if (field != null) text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> if (field == "name") {
                        chanId?.let { id ->
                            val nm = text.toString().trim()
                            if (nm.isNotEmpty()) {
                                xmlNames.getOrPut(id.lowercase()) { ArrayList() } += nm
                                if (seen.size < 8000 && seenIds.add(id)) seen += SourceChannel(id, nm)
                            }
                        }
                        field = null
                    }
                    "channel" -> chanId = null
                    "title" -> if (field == "title") { p?.set(3, text.toString().trim()); field = null }
                    "desc" -> if (field == "desc") { p?.set(4, text.toString().trim()); field = null }
                    "category" -> if (field == "category") { p?.set(5, text.toString().trim()); field = null }
                    "programme" -> {
                        val q = p
                        if (q != null && !(q[3] as String?).isNullOrBlank()) {
                            val prog = Programme(q[0] as Long, q[1] as Long, q[3] as String, q[4] as String?, q[5] as String?, q[6] as String?)
                            for (c in targets!![q[2] as String].orEmpty()) {
                                if (onProgramme(c.id, prog)) {
                                    n++
                                    // This source worked for this channel: it keeps it from now on.
                                    owner[c.id] = src.key
                                    remembered[c.id] = src.key + "|" + q[2]
                                }
                            }
                        }
                        p = null
                    }
                }
            }
            event = parser.next()
        }
        return n
    }

    fun readList(s: String?): List<String> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun readMap(s: String?): Map<String, String> = runCatching {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** A source's channels saved as JSON (for choosing a match by hand). */
    fun channelsJson(list: List<SourceChannel>): String {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.id).put(it.name)) }
        return a.toString()
    }

    fun channelsFromJson(s: String): List<SourceChannel> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i -> a.getJSONArray(i).let { SourceChannel(it.getString(0), it.getString(1)) } }
    }.getOrDefault(emptyList())
}
