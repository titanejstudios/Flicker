package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * Combines copies of the same channel in one category ("BBC One FHD (1080p)", "BBC One HD (720p)",
 * "BBC One SD (576p)") into one entry. The best quality is used unless you've picked another
 * version for that channel; the others stay available from the player menu and as backup links.
 */
object ChannelMerge {
    private val qualityTag = Regex(
        "[(\\[]?\\b(\\d{3,4}\\s*[pi]|fhd|uhd|qhd|4k|8k|hd|sd|hevc|h\\.?26[45]|x26[45]|50\\s*fps|60\\s*fps|hdr|raw|backup|alt)\\b[)\\]]?",
        RegexOption.IGNORE_CASE,
    )
    private val nonAlnum = Regex("[^a-z0-9]")

    // Worked out once per name: big playlists have tens of thousands of channels, and doing these
    // regexes for every one each time the list changes froze the screen.
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val qualities = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** "BBC One FHD (1080p)" and "BBC One SD" give the same key. */
    fun key(name: String): String = keys.getOrPut(name) {
        name.lowercase(Locale.ROOT).replace(qualityTag, " ").replace(nonAlnum, "")
    }

    /** Works out every name's key and quality ahead (call off the main thread). */
    fun warm(names: Collection<String>) {
        for (n in names) {
            key(n)
            quality(n)
        }
    }

    /** Higher is better: 4K 5, 1080p 4, 720p 3, unknown 2, SD 1. */
    private val q5 = Regex("\\b(2160\\s*p|4k|uhd|8k)\\b")
    private val q4 = Regex("\\b(1080\\s*[pi]|fhd)\\b")
    private val q3 = Regex("\\b(720\\s*p|hd)\\b")
    private val q1 = Regex("\\b(576\\s*[pi]|480\\s*p|sd)\\b")

    fun quality(name: String): Int = qualities.getOrPut(name) { qualityOf(name) }

    private fun qualityOf(name: String): Int {
        val n = name.lowercase(Locale.ROOT)
        return when {
            q5.containsMatchIn(n) -> 5
            q4.containsMatchIn(n) -> 4
            q3.containsMatchIn(n) -> 3
            q1.containsMatchIn(n) -> 1
            else -> 2
        }
    }

    /** "FHD (1080p)" – what's different about this version, for the menu. */
    fun versionLabel(c: Channel): String {
        val tags = qualityTag.findAll(c.name).map { it.value.trim('(', ')', '[', ']', ' ') }.toList()
        return tags.joinToString(" ").ifEmpty { c.name }
    }

    class Result(
        /** One entry per channel, in the order the first copy appeared. */
        val channels: List<Channel>,
        /** Shown channel id → every version of it, best first (only for channels with more than one). */
        val versions: Map<String, List<Channel>>,
    )

    /**
     * Merges copies within each category. [preferred] maps a merge key to the channel id you chose
     * (from the player menu); otherwise the best quality wins.
     */
    fun merge(list: List<Channel>, preferred: Map<String, String>): Result {
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (c in list) {
            val k = key(c.name)
            // Names that are nothing but quality tags (or empty) are never merged.
            val gk = if (k.length < 2) "\u0000" + c.id else c.group + "\u0001" + k
            groups.getOrPut(gk) { ArrayList() }.add(c)
        }
        val out = ArrayList<Channel>(groups.size)
        val versions = HashMap<String, List<Channel>>()
        for (copies in groups.values) {
            if (copies.size == 1) {
                out += copies[0]
                continue
            }
            val sorted = copies.map { it to quality(it.name) }.sortedByDescending { it.second }.map { it.first }
            val chosenId = preferred[key(copies[0].name)]
            val shown = sorted.firstOrNull { it.id == chosenId } ?: sorted[0]
            // Keep the first copy's place and number, so the list order matches your provider.
            val first = copies[0]
            val entry = if (shown.id == first.id || first.number <= 0) shown else shown.copy(number = first.number)
            out += entry
            versions[entry.id] = listOf(shown) + sorted.filter { it.id != shown.id }
        }
        return Result(out, versions)
    }
}
