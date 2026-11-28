package com.titanejstudios.tuner.features

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titanejstudios.tuner.BuildConfig
import com.titanejstudios.tuner.data.AppLog
import com.titanejstudios.tuner.data.Store
import com.titanejstudios.tuner.integrations.GoogleDrive
import com.titanejstudios.tuner.integrations.LinkSync
import com.titanejstudios.tuner.integrations.TvSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sync between houses: every device signed in to the same Google account shares one small file
 * in a "Flicker" folder in that account's own Google Drive (Flicker can only see files it made). Each device writes
 * its lists there and takes what's newer from the others – where you got to in films and
 * episodes, My list, followed series, your teams, learned intros, and (if you like) favourites.
 */
class CloudSyncFeature(
    private val store: Store,
    private val scope: CoroutineScope,
    private val deviceId: () -> String,
    private val deviceName: () -> String,
    private val isPhone: () -> Boolean,
    private val isForeground: () -> Boolean,
    private val onSynced: (Set<String>) -> Unit,
    /** Backup guide (remove with the module): its settings, to share with your other devices. */
    private val backupGuideExport: () -> JSONObject? = { null },
    private val backupGuideImport: (JSONObject) -> Boolean = { false },
) {
    /** Another device in the sync file. */
    class Device(val id: String, val name: String, val house: String, val phone: Boolean, val seenAt: Long, val profiles: List<String> = emptyList())

    /** The other devices' latest lists (for "Copy profiles from this device"). */
    private val snapshots = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    /** Makes this device's profiles a copy of [d]'s (and tells the rest to follow). */
    fun copyProfilesFrom(d: Device) {
        val snap = snapshots[d.id]
        if (snap == null || !LinkSync.copyProfiles(store, snap)) {
            status = "${d.name} hasn't shared its profiles yet – update Flicker there and press Sync now on it"
            return
        }
        status = "✓ Profiles copied from ${d.name}"
        onSynced(setOf(LinkSync.PROFILES, TvSync.FAVOURITES))
        syncSoon(1_000L)
    }

    private val drive = GoogleDrive(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET)

    /** This copy of Flicker was built with Google sign-in set up. */
    val available: Boolean get() = drive.configured

    var signedIn by mutableStateOf(store.gdriveRefresh != null); private set
    var email by mutableStateOf(store.gdriveEmail); private set
    /** While signing in: the code to type on google.com/device. */
    var code by mutableStateOf<GoogleDrive.DeviceCode?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var lastSync by mutableStateOf(store.cloudSyncedAt); private set
    var syncing by mutableStateOf(false); private set

    /**
     * A device with no playlists found some in your Google account: offered ("Playlist found –
     * import it?") rather than taken without asking. The playlists, and when they were last changed.
     */
    var foundPlaylists by mutableStateOf<Pair<List<com.titanejstudios.tuner.data.Source>, Long>?>(null); private set

    /** "Import": the found playlists become this device's, and their channels load. */
    fun importFound() {
        val (list, at) = foundPlaylists ?: return
        foundPlaylists = null
        store.setSourcesFromSync(list, at)
        AppLog.info("Playlists imported from your Google account: " + list.joinToString { it.name })
        onSynced(setOf(Store.PLAYLISTS))
    }

    /** "Not now": not asked again until they change on another device. */
    fun declineFound() {
        val (_, at) = foundPlaylists ?: return
        foundPlaylists = null
        store.setSyncStamp(Store.PLAYLISTS, at)
    }
    var house by mutableStateOf(store.cloudHouse); private set
    var shareFavourites by mutableStateOf(store.cloudShareFavourites); private set

    private var access: GoogleDrive.Tokens? = null
    private var signInJob: Job? = null
    private var syncJob: Job? = null
    private var loopJob: Job? = null

    // ---------------- Signing in ----------------

    fun signIn() {
        if (!available) {
            status = "This copy of Flicker hasn't been set up for Google sign-in yet"
            return
        }
        signInJob?.cancel()
        status = "Getting a code…"
        signInJob = scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { drive.startSignIn() } }.getOrElse {
                status = "Couldn't reach Google: ${it.message}"
                return@launch
            }
            code = d
            status = null
            var interval = d.intervalSec.coerceAtLeast(3) * 1000L
            while (System.currentTimeMillis() < d.expiresAt) {
                delay(interval)
                when (val r = withContext(Dispatchers.IO) { runCatching { drive.poll(d) }.getOrElse { GoogleDrive.Poll.Waiting } }) {
                    is GoogleDrive.Poll.Waiting -> Unit
                    is GoogleDrive.Poll.SlowDown -> interval += 5_000L
                    is GoogleDrive.Poll.Failed -> {
                        code = null
                        status = r.reason
                        return@launch
                    }
                    is GoogleDrive.Poll.Done -> {
                        code = null
                        access = r.tokens
                        store.gdriveRefresh = r.tokens.refresh
                        store.gdriveEmail = r.tokens.email
                        email = r.tokens.email
                        signedIn = r.tokens.refresh != null
                        status = "✓ Signed in" + (r.tokens.email?.let { " as $it" } ?: "")
                        AppLog.info("Signed in to Google Drive sync")
                        syncNow()
                        start()
                        return@launch
                    }
                }
            }
            code = null
            status = "The code ran out – start again"
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        code = null
        status = null
    }

    fun signOut() {
        val r = store.gdriveRefresh
        loopJob?.cancel()
        syncJob?.cancel()
        store.gdriveRefresh = null
        store.gdriveEmail = null
        store.gdriveFileId = null
        access = null
        signedIn = false
        email = null
        devices = emptyList()
        status = "Signed out – this device no longer syncs"
        if (r != null) scope.launch(Dispatchers.IO) { drive.revoke(r) }
    }

    fun changeHouse(name: String) {
        val n = name.trim().take(30).ifBlank { "Home" }
        house = n
        store.cloudHouse = n
        syncSoon(1_000L)
    }

    fun toggleShareFavourites() {
        shareFavourites = !shareFavourites
        store.cloudShareFavourites = shareFavourites
        syncSoon(1_000L)
    }

    // ---------------- Syncing ----------------

    /** Every 3 minutes while Flicker is open (and once now). */
    fun start() {
        if (!signedIn || !available) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            delay(3_000)
            while (true) {
                if (isForeground()) syncOnce()
                delay(3 * 60_000L)
            }
        }
    }

    /** Something changed here (stopped a film, changed My list…): sync in a moment. */
    fun syncSoon(delayMs: Long = 8_000L) {
        if (!signedIn || !available) return
        // Never stop a sync half way (that showed "Couldn't sync: … was cancelled"): go again after it.
        if (syncing) {
            syncAgain = true
            return
        }
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            syncOnce()
        }
    }

    fun syncNow() = syncSoon(0L)

    private var syncAgain = false

    private suspend fun syncOnce() {
        if (syncing) {
            syncAgain = true
            return
        }
        syncing = true
        try {
            val (changed, others) = withContext(Dispatchers.IO) { roundTrip() }
            devices = others
            lastSync = System.currentTimeMillis()
            store.cloudSyncedAt = lastSync
            if (status?.startsWith("✓") != true) status = null
            if (changed.isNotEmpty()) {
                AppLog.info("Synced from other houses: " + changed.joinToString())
                onSynced(changed)
            }
        } catch (e: GoogleDrive.SignedOut) {
            signOut()
            status = "Google signed this device out – sign in again"
        } catch (e: GoogleDrive.NoDrivePermission) {
            // Signed in without the Drive box ticked: start again so it can be ticked.
            signOut()
            status = GoogleDrive.NO_DRIVE
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Signed out or closed while syncing – not a problem worth showing.
            throw e
        } catch (e: Exception) {
            status = "Couldn't sync: ${e.message ?: e.javaClass.simpleName}"
            AppLog.warn("Google Drive sync failed", e)
        } finally {
            syncing = false
            if (syncAgain) {
                syncAgain = false
                syncSoon(3_000L)
            }
        }
    }

    /** Read the file, take what's newer from the other devices, write ours back. Blocking. */
    private fun roundTrip(): Pair<Set<String>, List<Device>> {
        val token = accessToken()
        val found: Pair<String, String>? = try {
            drive.read(token)
        } catch (e: GoogleDrive.Unauthorised) {
            access = null
            drive.read(accessToken())
        }
        val fileId: String? = found?.first
        val text: String = found?.second ?: "{}"
        val root = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val all = root.optJSONObject("devices") ?: JSONObject()
        val me = deviceId()
        val now = System.currentTimeMillis()
        val changed = HashSet<String>()
        val seen = ArrayList<Device>()
        // The newest playlists and guide settings from your other devices (see after the loop).
        var bestSources: org.json.JSONArray? = null
        var bestSourcesAt = 0L
        var bestGuide: JSONObject? = null
        for (id in all.keys().asSequence().toList()) {
            val d = all.optJSONObject(id) ?: continue
            val at = d.optLong("at")
            // Devices not heard from in 60 days are dropped from the file.
            if (now - at > 60L * 86_400_000L) {
                all.remove(id)
                continue
            }
            if (id == me) continue
            d.optJSONArray("sources")?.let { arr ->
                // Older versions sent no date: they only fill a device with no playlists yet.
                val sAt = d.optLong("sources_at", 1L)
                if (arr.length() > 0 && sAt > bestSourcesAt) {
                    bestSources = arr
                    bestSourcesAt = sAt
                }
            }
            d.optJSONObject("guide")?.let { g -> if (g.optLong("at") > (bestGuide?.optLong("at") ?: 0L)) bestGuide = g }
            // Backup guide (remove with the module): the newest settings win.
            d.optJSONObject("guide")?.optJSONObject("backup")?.let { b -> if (backupGuideImport(b)) changed += Store.GUIDE_SETTINGS }
            val theirHouse = d.optString("house", "Home")
            val snap0 = d.optJSONObject("snapshot")
            val names = snap0?.optJSONObject("sections")?.optJSONObject(LinkSync.PROFILES)?.optJSONArray("list")
                ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
            seen += Device(id, d.optString("name"), theirHouse, d.optBoolean("phone"), at, names)
            val snap = snap0 ?: continue
            snapshots[id] = JSONObject(snap.toString())
            // Each house keeps its own favourites unless you've chosen to share them.
            if (!theirHouse.equals(house, true) && !shareFavourites) snap.optJSONObject("sections")?.remove(TvSync.FAVOURITES)
            changed += LinkSync.apply(store, snap)
            // A PIN set or removed on another device (a PC) more recently than here.
            val theirPinAt = d.optLong("pin_at")
            if (theirPinAt > store.pinAt) {
                store.pin = d.optString("pin").takeIf { it.isNotBlank() && it != "null" }
                store.pinAt = theirPinAt
                changed += "pin"
            }
        }
        // Playlists: a device with none is offered them ("Playlist found – import it?"); otherwise a
        // newer change from another device is added in (playlists are never removed this way).
        bestSources?.let { arr ->
            val theirs = store.sourcesFromCloud(arr, store.gdriveEmail)
            val mine = store.sources
            val mineAt = store.syncStamp(Store.PLAYLISTS)
            if (theirs.isNotEmpty() && mine.isEmpty()) {
                // Asked once per change: "Not now" isn't asked again until they change somewhere else.
                if (bestSourcesAt > mineAt && foundPlaylists == null) {
                    foundPlaylists = theirs to bestSourcesAt
                    AppLog.info("Found ${theirs.size} playlist(s) in your Google account – asking whether to import")
                }
            } else if (theirs.isNotEmpty() && bestSourcesAt > mineAt) {
                val merged = mine.map { m -> theirs.firstOrNull { it.id == m.id } ?: m } + theirs.filter { t -> mine.none { it.id == t.id } }
                if (merged != mine) {
                    store.setSourcesFromSync(merged, maxOf(bestSourcesAt, mineAt))
                    changed += Store.PLAYLISTS
                    AppLog.info("Playlists from your Google account: " + merged.joinToString { it.name })
                } else store.setSyncStamp(Store.PLAYLISTS, maxOf(bestSourcesAt, mineAt))
            }
        }
        // TV guide settings (matches you fixed by hand, extra guides): the newest change wins.
        bestGuide?.let { g ->
            val at = g.optLong("at")
            if (at > store.syncStamp(Store.GUIDE_SETTINGS)) {
                g.optJSONObject("epg_overrides")?.let { o -> store.setEpgOverridesFromSync(o.keys().asSequence().associateWith { o.optString(it) }, at) }
                store.setSyncStamp(Store.GUIDE_SETTINGS, at)
                changed += Store.GUIDE_SETTINGS
            }
        }
        val email = store.gdriveEmail ?: ""
        all.put(
            me,
            JSONObject().put("name", deviceName()).put("house", house).put("phone", isPhone()).put("at", now)
                .put("snapshot", LinkSync.snapshot(store))
                // Your playlists (passwords encrypted with your Google account), so a new device or
                // Flicker on a PC signs in once and has the same channels (same ids).
                .put("sources", runCatching { store.cloudSourcesJson(email) }.getOrDefault(org.json.JSONArray()))
                .put("sources_at", store.syncStamp(Store.PLAYLISTS))
                .put(
                    "guide",
                    JSONObject().put("at", store.syncStamp(Store.GUIDE_SETTINGS))
                        .put("epg_overrides", JSONObject(store.epgOverrides as Map<*, *>))
                        .put("backup", runCatching { backupGuideExport() }.getOrNull() ?: JSONObject()),
                )
                // The linked-devices key, so Flicker on a PC joins the house (phone remote, Watch on…).
                .put("link_key", store.linkKey ?: "")
                // The parental PIN, so kids' limits work the same on a PC.
                .put("pin", store.pin ?: "").put("pin_at", store.pinAt),
        )
        root.put("app", "Flicker").put("v", 1).put("devices", all)
        val id = drive.write(accessToken(), fileId ?: store.gdriveFileId, root.toString())
        store.gdriveFileId = id
        return changed to seen.sortedWith(compareBy<Device>({ !it.house.equals(house, true) }, { it.house.lowercase() }, { it.name.lowercase() }))
    }

    private fun accessToken(): String {
        access?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.access }
        val refresh = store.gdriveRefresh ?: throw GoogleDrive.SignedOut()
        val t = drive.refresh(refresh)
        access = t
        return t.access
    }

    /** Settings restored from a backup: nothing to do (sign-in is per device), but keep going. */
    fun reload() {
        signedIn = store.gdriveRefresh != null
        email = store.gdriveEmail
        house = store.cloudHouse
        shareFavourites = store.cloudShareFavourites
    }
}
