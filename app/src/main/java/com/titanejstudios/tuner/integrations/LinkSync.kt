package com.titanejstudios.tuner.integrations

import com.titanejstudios.tuner.data.ResumeEntry
import com.titanejstudios.tuner.data.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Carry on where you left off between linked devices (TV ↔ phone) over the Wi-Fi.
 * The same lists as the network-drive sync: favourites, My list, followed series and resume
 * points, plus where each series' intro ends. Lists: the newest change wins. Resume points and
 * intros: merged item by item. Each device pulls from the others, so nothing is ever pushed.
 */
object LinkSync {
    const val INTROS = "intros"
    const val TEAMS = "teams"
    const val STARTED = "started"
    const val PROFILES = "profiles"
    const val YOUTUBE = "youtube"
    /** Family picks (shared by every profile). */
    const val PICKS = "picks"
    /** Your own named collections. */
    const val COLLECTIONS = "lists"
    const val TASTE = "taste"
    /** Which channels are offline (shared only within one house: same playlist, same provider). */
    const val OFFLINE = "offline"
    private const val DONE = "done"

    /** Remembers that [contentId] was finished (keeps the newest 200). */
    fun markDone(store: Store, contentId: String, at: Long = System.currentTimeMillis()) {
        val all = store.resumeDone + (contentId to at.toString())
        store.resumeDone = all.entries.sortedByDescending { it.value.toLongOrNull() ?: 0L }.take(200).associate { it.key to it.value }
    }

    /** This device's lists, for another device to pull. */
    fun snapshot(store: Store): JSONObject {
        val sections = JSONObject()
        sections.put(
            PROFILES,
            JSONObject().put("at", store.syncStamp(PROFILES)).put("list", Store.profilesToJson(store.profiles))
                .put("gone", JSONObject(store.profilesGone))
                // Plex libraries kids may see (the same server gives the same ids on every device).
                .put("plex_kids", JSONArray(store.plexKidsSections.toList())),
        )
        val favs = JSONObject()
        for (p in store.profiles) favs.put(p.id, JSONArray(store.favourites(p.id)))
        sections.put(TvSync.FAVOURITES, JSONObject().put("at", store.syncStamp(TvSync.FAVOURITES)).put("data", favs))
        sections.put(TvSync.WATCHLIST, JSONObject().put("at", store.syncStamp(TvSync.WATCHLIST)).put("data", store.watchlistJson))
        sections.put(TvSync.FOLLOWED, JSONObject().put("at", store.syncStamp(TvSync.FOLLOWED)).put("data", store.followedSeriesJson))
        sections.put(PICKS, JSONObject().put("at", store.syncStamp(PICKS)).put("data", store.familyPicksJson))
        sections.put(COLLECTIONS, JSONObject().put("at", store.syncStamp(COLLECTIONS)).put("data", store.collectionsJson))
        // What each profile likes, by name (so a new PC's recommendations are good straight away).
        val taste = JSONObject()
        for (p in store.profiles) store.taste(p.id)?.let { taste.put(p.name.lowercase().trim(), it) }
        sections.put(TASTE, taste)
        val resume = JSONArray()
        store.resume.values.forEach { resume.put(TvSync.resumeToJson(it)) }
        sections.put(TvSync.RESUME, JSONObject().put("data", resume))
        sections.put(INTROS, JSONObject(store.introEnds))
        // Each profile's own Continue watching, by profile name (ids differ between devices).
        val started = JSONObject()
        for (p in store.profiles) {
            val keys = store.started(p.id)
            if (keys.isNotEmpty()) started.put(p.name.lowercase().trim(), JSONArray(keys.toList()))
        }
        sections.put(STARTED, started)
        // Each profile's YouTube in Flicker: what it watched (newest 60) and its Watch later list.
        val yt = JSONObject()
        for (p in store.profiles) {
            val h = runCatching { JSONArray(store.ytHistoryOf(p.id) ?: "[]") }.getOrDefault(JSONArray())
            val newest = JSONArray()
            for (i in 0 until minOf(60, h.length())) newest.put(h.get(i))
            val l = runCatching { JSONArray(store.ytData("later", p.id) ?: "[]") }.getOrDefault(JSONArray())
            val tokAt = store.ytData("tok_at", p.id)?.toLongOrNull() ?: 0L
            if (newest.length() == 0 && l.length() == 0 && tokAt == 0L) continue
            yt.put(
                p.name.lowercase().trim(),
                JSONObject().put("h", newest).put("l", l).put("lat", store.ytData("later_at", p.id)?.toLongOrNull() ?: 0L)
                    // The profile's YouTube sign-in (read-only), so every device has it without signing in again.
                    .put("tok", store.ytRefreshOf(p.id)).put("em", store.ytEmailOf(p.id)).put("tat", tokAt)
                    // Its recommendations, so a device that's just started has them straight away.
                    .put("rec", store.ytRecsOf(p.id)).put("rat", store.ytRecsAtOf(p.id)),
            )
        }
        sections.put(YOUTUBE, yt)
        sections.put(
            TEAMS,
            JSONObject().put("at", store.syncStamp(TEAMS)).put("names", JSONArray(store.footballTeams.toList()))
                .put("espn", runCatching { JSONObject(store.espnTeams ?: "{}") }.getOrDefault(JSONObject())),
        )
        sections.put(DONE, JSONObject(store.resumeDone))
        // The offline list, so one device's channel check saves the others running it.
        sections.put(
            OFFLINE,
            JSONObject().put("at", store.syncStamp(OFFLINE)).put("checked", store.healthCheckedAt)
                .put("ids", JSONArray(store.deadChannels.toList())),
        )
        // Kids' weeks (watch time and anything blocked), read by a grown-up's phone for the report.
        val kids = JSONObject()
        for (p in store.profiles) if (p.kids) {
            kids.put(p.id, JSONObject().put("stats", store.stats(p.id)).put("blocked", JSONArray(store.blockedLog(p.id))))
        }
        return JSONObject().put("app", "Flicker").put("v", 1).put("sections", sections).put("kidsweek", kids)
    }

    /**
     * "Copy profiles from this device": this device's profiles (and their favourites) become an
     * exact copy of [remote]'s, and count as the newest, so every other device follows.
     */
    fun copyProfiles(store: Store, remote: JSONObject): Boolean {
        val sections = remote.optJSONObject("sections") ?: return false
        val theirs = sections.optJSONObject(PROFILES)?.optJSONArray("list")?.let { Store.profilesFromJson(it) }.orEmpty()
        if (theirs.isEmpty() || theirs.none { !it.kids }) return false
        val mine = store.profiles
        val out = ArrayList<com.titanejstudios.tuner.data.Profile>()
        val used = HashSet<String>()
        for (t in theirs) {
            val local = mine.firstOrNull { it.id == t.id } ?: mine.firstOrNull { it.name.trim().equals(t.name.trim(), ignoreCase = true) }
            val id = local?.id ?: t.id
            if (used.add(id)) out += t.copy(id = id)
        }
        val now = System.currentTimeMillis()
        val gone = HashMap(store.profilesGone)
        for (p in mine) if (p.id !in used) gone[p.name.trim().lowercase()] = now
        store.profiles = out
        store.profilesGone = gone
        store.setSyncStamp(PROFILES, now)
        sections.optJSONObject(PROFILES)?.optJSONArray("plex_kids")?.let { a ->
            if (a.length() > 0) store.plexKidsSections = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        val favs = sections.optJSONObject(TvSync.FAVOURITES)?.optJSONObject("data")
        if (favs != null) {
            for (theirPid in favs.keys()) {
                val name = theirs.firstOrNull { it.id == theirPid }?.name?.trim() ?: continue
                val pid = out.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.id ?: continue
                val arr = favs.optJSONArray(theirPid) ?: continue
                store.setFavourites(pid, (0 until arr.length()).map { arr.getString(it) })
            }
            store.setSyncStamp(TvSync.FAVOURITES, now)
        }
        return true
    }

    /** Takes whatever is newer from another device's [remote] snapshot. Returns the sections that changed here. */
    fun apply(store: Store, remote: JSONObject): Set<String> {
        val sections = remote.optJSONObject("sections") ?: return emptySet()
        val changed = HashSet<String>()

        // Profiles (first, so favourites below can find them). Matched by id, else by name.
        val theirProfiles = sections.optJSONObject(PROFILES)?.optJSONArray("list")?.let { Store.profilesFromJson(it) }.orEmpty()
        sections.optJSONObject(PROFILES)?.let { r ->
            val theirStamp = r.optLong("at")
            val mineStamp = store.syncStamp(PROFILES)
            // Kids' Plex libraries: the newer device's choice, or theirs if none are chosen here.
            r.optJSONArray("plex_kids")?.let { a ->
                val theirKids = (0 until a.length()).map { a.getString(it) }.toSet()
                val mineKids = store.plexKidsSections
                if (theirKids.isNotEmpty() && theirKids != mineKids && (theirStamp > mineStamp || mineKids.isEmpty())) {
                    store.plexKidsSections = theirKids
                    changed += PROFILES
                }
            }
            val mine = store.profiles
            // A device that's never changed its profiles (just the starting "Everyone") takes the other's.
            val untouched = mineStamp == 0L && mine.size <= 1
            if (theirStamp > mineStamp || (untouched && theirProfiles.isNotEmpty())) {
                val gone = HashMap(store.profilesGone)
                r.optJSONObject("gone")?.let { o -> for (k in o.keys()) if (o.optLong(k) > (gone[k] ?: 0L)) gone[k] = o.optLong(k) }
                val out = ArrayList<com.titanejstudios.tuner.data.Profile>()
                val used = HashSet<String>()
                for (t in theirProfiles) {
                    val local = mine.firstOrNull { it.id == t.id } ?: mine.firstOrNull { it.name.trim().equals(t.name.trim(), ignoreCase = true) }
                    val id = local?.id ?: t.id
                    if (!used.add(id)) continue
                    out += t.copy(id = id)
                }
                // Profiles only here: kept, unless the other device deleted one of that name since.
                for (p in mine) {
                    if (p.id in used) continue
                    if (out.any { it.name.trim().equals(p.name.trim(), ignoreCase = true) }) continue
                    val deletedAt = gone[p.name.trim().lowercase()] ?: 0L
                    if (deletedAt > mineStamp) continue
                    out += p
                }
                // Always at least one grown-up profile.
                if (out.isNotEmpty() && out.any { !it.kids } && out != mine) {
                    store.profiles = out
                    changed += PROFILES
                }
                store.profilesGone = gone
                store.setSyncStamp(PROFILES, maxOf(theirStamp, mineStamp))
            }
        }

        // Favourites: only for profiles this device has (profile ids match after "Copy to another TV").
        sections.optJSONObject(TvSync.FAVOURITES)?.let { r ->
            val data = r.optJSONObject("data") ?: JSONObject()
            val theirStamp = r.optLong("at")
            val mine = store.syncStamp(TvSync.FAVOURITES)
            val hereEmpty = store.profiles.all { store.favourites(it.id).isEmpty() }
            val newer = theirStamp > mine || (mine == 0L && hereEmpty && data.length() > 0)
            run {
                val here = store.profiles
                val ids = here.map { it.id }.toSet()
                var any = false
                for (theirPid in data.keys()) {
                    // Profile ids differ between devices that made their profiles separately: match by name.
                    val pid = if (theirPid in ids) theirPid else {
                        val name = theirProfiles.firstOrNull { it.id == theirPid }?.name?.trim() ?: continue
                        here.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.id ?: continue
                    }
                    // Older lists only fill a profile that has none here yet (one just imported).
                    if (!newer && store.favourites(pid).isNotEmpty()) continue
                    val arr = data.optJSONArray(theirPid) ?: continue
                    if (!newer && arr.length() == 0) continue
                    store.setFavourites(pid, (0 until arr.length()).map { arr.getString(it) })
                    any = true
                }
                if (any) {
                    if (newer) store.setSyncStamp(TvSync.FAVOURITES, theirStamp)
                    changed += TvSync.FAVOURITES
                }
            }
        }

        // My list and followed series (JSON text).
        for ((section, read, write) in listOf(
            Triple(TvSync.WATCHLIST, { store.watchlistJson }, { v: String -> store.watchlistJson = v }),
            Triple(TvSync.FOLLOWED, { store.followedSeriesJson }, { v: String -> store.followedSeriesJson = v }),
            Triple(PICKS, { store.familyPicksJson }, { v: String -> store.familyPicksJson = v }),
            Triple(COLLECTIONS, { store.collectionsJson }, { v: String -> store.collectionsJson = v }),
        )) {
            val r = sections.optJSONObject(section) ?: continue
            val theirs = r.optString("data", "[]")
            val theirStamp = r.optLong("at")
            val mine = store.syncStamp(section)
            val hereEmpty = read().let { it.isBlank() || it == "[]" }
            if ((theirStamp > mine || (mine == 0L && hereEmpty)) && theirs != read() && theirs != "[]") {
                write(theirs)
                store.setSyncStamp(section, theirStamp)
                changed += section
            }
        }

        // Finished on the other device: drop it here too.
        sections.optJSONObject(DONE)?.let { o ->
            val mineDone = store.resumeDone
            val add = HashMap<String, String>()
            for (k in o.keys()) {
                val t = o.optString(k).toLongOrNull() ?: continue
                if ((mineDone[k]?.toLongOrNull() ?: 0L) < t) add[k] = t.toString()
            }
            if (add.isNotEmpty()) store.resumeDone = mineDone + add
        }
        val done = store.resumeDone

        // Resume points: the newest entry for each film or episode (unless it's been finished since).
        sections.optJSONObject(TvSync.RESUME)?.optJSONArray("data")?.let { a ->
            val theirs = HashMap<String, ResumeEntry>()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { TvSync.resumeFromJson(it) }?.let { theirs[it.contentId] = it }
            val mine = store.resume.filter { (id, e) -> (done[id]?.toLongOrNull() ?: 0L) < e.updatedAt }
            theirs.entries.removeAll { (id, e) -> (done[id]?.toLongOrNull() ?: 0L) >= e.updatedAt }
            val merged = TvSync.mergeResume(mine, theirs)
            if (merged != store.resume) {
                store.resume = merged
                changed += TvSync.RESUME
            }
        }

        // Your teams (My teams): the newest choice wins.
        sections.optJSONObject(TEAMS)?.let { r ->
            val theirStamp = r.optLong("at")
            val mine = store.syncStamp(TEAMS)
            val names = r.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            if ((theirStamp > mine || (mine == 0L && store.footballTeams.isEmpty())) && names.isNotEmpty() && names != store.footballTeams) {
                store.footballTeams = names
                // Their fixtures' ESPN teams come along too.
                r.optJSONObject("espn")?.let { theirs ->
                    val mine = runCatching { JSONObject(store.espnTeams ?: "{}") }.getOrDefault(JSONObject())
                    val out = JSONObject()
                    for (k in mine.keys()) if (k in names) out.put(k, mine.get(k))
                    for (k in theirs.keys()) if (k in names) out.put(k, theirs.get(k))
                    store.espnTeams = out.toString()
                }
                store.setSyncStamp(TEAMS, theirStamp)
                changed += TEAMS
            }
        }

        // What each profile has started: added to the profile of the same name here.
        sections.optJSONObject(STARTED)?.let { o ->
            for (p in store.profiles) {
                val arr = o.optJSONArray(p.name.lowercase().trim()) ?: continue
                val theirs = (0 until arr.length()).map { arr.getString(it) }.toSet()
                val mine = store.started(p.id)
                if (!mine.containsAll(theirs)) {
                    store.setStarted(p.id, (mine + theirs).toList().takeLast(300).toSet())
                    changed += STARTED
                }
            }
        }

        // YouTube: history merged video by video (the newer viewing wins); Watch later: the newer list.
        sections.optJSONObject(YOUTUBE)?.let { o ->
            for (p in store.profiles) {
                val theirs = o.optJSONObject(p.name.lowercase().trim()) ?: continue
                val mineH = runCatching { JSONArray(store.ytHistoryOf(p.id) ?: "[]") }.getOrDefault(JSONArray())
                val byId = LinkedHashMap<String, JSONObject>()
                fun add(a: JSONArray?) {
                    a ?: return
                    for (i in 0 until a.length()) {
                        val e = a.optJSONObject(i) ?: continue
                        val id = e.optJSONObject("v")?.optString("id") ?: continue
                        val have = byId[id]
                        if (have == null || e.optLong("at") > have.optLong("at")) byId[id] = e
                    }
                }
                add(mineH)
                val before = byId.mapValues { it.value.optLong("at") }
                add(theirs.optJSONArray("h"))
                if (byId.mapValues { it.value.optLong("at") } != before) {
                    val merged = JSONArray()
                    byId.values.sortedByDescending { it.optLong("at") }.take(300).forEach { merged.put(it) }
                    store.saveYtHistory(p.id, merged.toString())
                    changed += YOUTUBE
                }
                // Sign-in: the newer change wins (signing out on one device signs out everywhere).
                val theirTokAt = theirs.optLong("tat")
                val mineTokAt = store.ytData("tok_at", p.id)?.toLongOrNull() ?: 0L
                val theirTok = theirs.optString("tok").takeIf { it.isNotBlank() && it != "null" }
                if (theirTokAt > mineTokAt || (mineTokAt == 0L && store.ytRefreshOf(p.id) == null && theirTok != null)) {
                    if (theirTok != store.ytRefreshOf(p.id)) {
                        store.saveYtRefresh(p.id, theirTok)
                        store.saveYtEmail(p.id, theirs.optString("em").takeIf { it.isNotBlank() && it != "null" })
                        changed += YOUTUBE
                    }
                    store.saveYtData("tok_at", p.id, theirTokAt.toString())
                }
                // Recommendations: the newer list.
                val theirRecs = theirs.optString("rec").takeIf { it.isNotBlank() && it != "null" }
                if (theirRecs != null && theirs.optLong("rat") > store.ytRecsAtOf(p.id)) {
                    store.saveYtRecs(p.id, theirRecs, theirs.optLong("rat"))
                    changed += YOUTUBE
                }
                val theirLaterAt = theirs.optLong("lat")
                val mineLaterAt = store.ytData("later_at", p.id)?.toLongOrNull() ?: 0L
                val theirLater = theirs.optJSONArray("l")
                if (theirLater != null && theirLaterAt > mineLaterAt) {
                    store.saveYtData("later", p.id, theirLater.toString())
                    store.saveYtData("later_at", p.id, theirLaterAt.toString())
                    changed += YOUTUBE
                }
            }
        }

        // Intros: fill in series this device hasn't learned yet.
        sections.optJSONObject(INTROS)?.let { o ->
            val mine = store.introEnds
            val add = HashMap<String, String>()
            for (k in o.keys()) if (k !in mine) add[k] = o.optString(k)
            if (add.isNotEmpty()) {
                store.introEnds = mine + add
                changed += INTROS
            }
        }
        // Offline channels: the newest list wins (the result of a check, a recheck, or clearing by hand).
        sections.optJSONObject(OFFLINE)?.let { o ->
            val at = o.optLong("at")
            val ids = o.optJSONArray("ids")
            if (ids != null && at > store.syncStamp(OFFLINE)) {
                store.deadChannels = (0 until ids.length()).map { ids.getString(it) }.toSet()
                store.setSyncStamp(OFFLINE, at)
                val checked = o.optLong("checked")
                if (checked > store.healthCheckedAt) store.healthCheckedAt = checked
                changed += OFFLINE
            }
        }
        return changed
    }
}
