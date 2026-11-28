package com.titanejstudios.tuner.data

import android.content.Context
import java.io.IOException

/** Downloads playlists and guides into the database. Shared by the app and the overnight refresh. */
class Repository(private val context: Context, private val store: Store) {
    private val db = Database.get(context)

    class Refreshed(val channels: List<Channel>, val epgError: String?)

    /** Downloads one playlist + its guide and saves both. Throws if the channels fail. */
    fun refreshSource(src: Source): Refreshed {
        val (list, playlistEpg) = refreshChannels(src)
        return Refreshed(list, refreshGuide(src, list, playlistEpg))
    }

    /** Step 1 (quick): download and save the channel list. Returns the channels and any guide URL in the playlist. */
    fun refreshChannels(src: Source): Pair<List<Channel>, String?> {
        val (list, playlistEpg) = loadChannels(src)
        if (list.isEmpty()) throw IOException("the playlist has no channels")
        db.replaceChannels(src.id, list)
        store.setLastChannels(src.id, System.currentTimeMillis())
        return list to playlistEpg
    }

    /** Step 2 (slow): download and save the TV guide. Returns an error message, or null if it worked. */
    fun refreshGuide(src: Source, list: List<Channel>, playlistEpg: String?): String? {
        val epgUrl = when (src) {
            is Source.M3u -> src.epgUrl?.takeIf { it.isNotBlank() } ?: playlistEpg
            is Source.Xtream -> src.epgUrl?.takeIf { it.isNotBlank() } ?: XtreamClient.epgUrl(src)
        }
        var epgError: String? = null
        if (epgUrl != null) {
            val started = System.currentTimeMillis()
            val bytes = Http.received.get()
            try {
                loadEpg(src.id, epgUrl, list)
                val secs = (System.currentTimeMillis() - started) / 1000
                AppLog.info("TV guide updated for ${src.name} in ${secs}s (" + String.format(java.util.Locale.UK, "%.1f MB", (Http.received.get() - bytes) / 1_000_000.0) + ")")
            } catch (e: Exception) {
                epgError = e.message ?: e.javaClass.simpleName
            }
        }
        // Backup guide (remove with the module): free sources fill channels and gaps your provider's guide leaves.
        runCatching { fillFromBackupGuide(src, list) }.onFailure { AppLog.warn("Backup guide failed for ${src.name}", it) }
        val t = System.currentTimeMillis()
        store.setLastGuide(src.id, t)
        store.setLastRefresh(src.id, t)
        return epgError
    }

    /** How far ahead the guide is saved: a week, so it only needs fetching once a day (or less). */
    private val guideDays = 7

    /** Which of your channels each guide id and name belongs to. */
    private fun matchers(list: List<Channel>): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val overrides = store.epgOverrides
        val byId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        for (c in list) {
            val manual = overrides[c.id]
            if (manual != null) byId.getOrPut(manual.lowercase()) { ArrayList() }.add(c.id)
            else {
                c.epgId?.let { byId.getOrPut(it.lowercase()) { ArrayList() }.add(c.id) }
                byName.getOrPut(XmltvParser.normalise(c.name)) { ArrayList() }.add(c.id)
            }
        }
        return byId to byName
    }

    /** Backup guide (remove with the module): fills gaps from free guide sources when it's turned on. */
    private fun fillFromBackupGuide(src: Source, list: List<Channel>) {
        if (!com.titanejstudios.backupguide.BackupGuide.enabled(context)) return
        val now = System.currentTimeMillis()
        val from = now - 3 * 3600_000L
        val to = now + guideDays * 86_400_000L
        val channels = list.map { com.titanejstudios.backupguide.BackupGuideCore.Channel(it.id, it.epgId, it.name, it.group) }
        // Saved a couple of thousand at a time (short writes, little held in memory).
        val batch = ArrayList<Pair<String, Programme>>(2000)
        fun flush() {
            if (batch.isEmpty()) return
            db.addProgrammes(src.id) { add -> for ((id, p) in batch) add(id, p) }
            batch.clear()
        }
        val n = com.titanejstudios.backupguide.BackupGuide.fill(
            context, channels, from, to,
            open = { url -> Http.open(url.trim()) },
            covered = { id -> db.intervals(src.id, id, from, to) },
            add = { id, p ->
                batch += id to Programme(p.start, p.stop, p.title, p.desc, p.category, p.image, null)
                if (batch.size >= 2000) flush()
            },
            log = { AppLog.info(it) },
        )
        flush()
        AppLog.info("Backup guide: $n programmes added for ${src.name}")
    }

    private fun loadChannels(src: Source): Pair<List<Channel>, String?> = when (src) {
        is Source.M3u -> {
            val r = Http.open(src.playlistUrl.trim()).use { M3uParser.parse(it, src.id) }
            r.channels to r.epgUrl
        }
        is Source.Xtream -> XtreamClient.load(src) to null
    }

    private fun loadEpg(sourceId: String, url: String, list: List<Channel>) {
        val overrides = store.epgOverrides
        val byId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        val catchupFrom = HashMap<String, Long>()
        val now = System.currentTimeMillis()
        for (c in list) {
            val manual = overrides[c.id]
            if (manual != null) {
                // A guide match picked by hand wins over tvg-id and name matching.
                byId.getOrPut(manual.lowercase()) { ArrayList() }.add(c.id)
            } else {
                c.epgId?.let { byId.getOrPut(it.lowercase()) { ArrayList() }.add(c.id) }
                byName.getOrPut(XmltvParser.normalise(c.name)) { ArrayList() }.add(c.id)
            }
            if (c.hasCatchup) catchupFrom[c.id] = now - c.catchupDays.coerceAtMost(7) * 86_400_000L
        }
        val missingLogo = list.filter { it.logo.isNullOrBlank() }.map { it.id }.toHashSet()
        val guideLogos = HashMap<String, String>()
        Http.open(url.trim()).use { input ->
            db.replaceEpg(sourceId) { addProgramme, addXmlChannel ->
                XmltvParser.parse(
                    input, byId, byName,
                    from = now - 3 * 3600_000L,
                    to = now + guideDays * 86_400_000L,
                    catchupFrom = catchupFrom,
                    onChannel = addXmlChannel,
                    onChannelLogo = { ids, logo -> for (id in ids) if (id in missingLogo) guideLogos.putIfAbsent(id, logo) },
                    onProgramme = addProgramme,
                )
            }
        }
        // Channels with no logo in the playlist borrow the guide's.
        if (guideLogos.isNotEmpty()) db.fillMissingLogos(sourceId, guideLogos)
    }
}
