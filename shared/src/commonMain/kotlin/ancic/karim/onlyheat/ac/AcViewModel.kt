package ancic.karim.onlyheat.ac

import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.decodeSet
import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.nowSeconds
import ancic.karim.onlyheat.data.savedAuthKey
import ancic.karim.onlyheat.res.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

private const val AC_MINIMIZED = "ac.minimized2" // renamed so everyone starts minimized
private const val AC_AWAY = "away.acPairings" // under "away.": logout clears it with the rest of Away

/** AC state for the Rooms tab. Outlives tab switches like [HomeViewModel]. */
internal class AcViewModel(private val settings: Settings, private val key: String) : ViewModel() {
    var linked by mutableStateOf(settings.linkedAcs(key))
        private set
    /** Room id -> state; null = didn't answer (away from home). No entry yet = still asking. */
    val states = mutableStateMapOf<String, AcState?>()
    var found by mutableStateOf<List<FoundAc>>(emptyList())
        private set
    var binding by mutableStateOf(false)
        private set
    var discovering by mutableStateOf(false)
        private set
    var lanScanSucceeded by mutableStateOf(false)
        private set
    /** The nearby Wi-Fi an AC can join (2.4 GHz, not an AC's own), strongest first; from the last [refresh]'s scan. */
    var homeWifi by mutableStateOf(emptyList<NearbyWifi>())
        private set
    private var discoveryEnabled = false
    private var scanWifi = false // from the last [refresh]
    /** The found card shrunk to one row. It can't be hidden: an unpaired AC stays on offer. */
    var minimized by mutableStateOf(settings.getBoolean(AC_MINIMIZED, true))
        private set
    var snackbar: SnackbarHostState? = null
    /** The room's AC; null without one. */
    fun room(roomId: String) = if (roomId in linked) AcView(states[roomId], roomId in states) else null
    /** Every paired AC's state, for the Rooms header. */
    val linkedStates get() = linked.keys.map { states[it] }
    private val pending = mutableMapOf<String, Job>()
    private val queued = mutableMapOf<String, AcChange>()
    private val revisions = mutableMapOf<String, Long>()
    private val awayRooms = mutableStateSetOf<String>()
    private val wifiSent = mutableMapOf<String, Long>() // MAC -> when it was given the home Wi-Fi (epoch seconds)
    private var lastReport: String? = null
    private var rescan = false // after a Wi-Fi send: the cached LAN scan may still hold the AC at its own Wi-Fi's address
    /** Away or I'm back is switching this room's AC, so [set] ignores changes until it's done. */
    fun busy(roomId: String) = roomId in awayRooms

    private fun bump(id: String) {
        revisions[id] = (revisions[id] ?: 0L) + 1
    }

    private suspend fun updateFound(force: Boolean = false) {
        val fresh = force || rescan
        val nearby = scanAcs(fresh)
        if (fresh) rescan = false
        if (nearby.isNotEmpty()) lanScanSucceeded = true
        val taken = linked.values.map { it.protocol to it.mac }.toSet()
        val wifi = if (scanWifi) attempt { nearbyWifi() }.getOrDefault(emptyList()) else emptyList()
        val now = nowSeconds()
        // ponytail: neighbours' ACs drop into setup mode too, at -80 dBm and weaker; one being set up here is near.
        // Lower the cutoff if a reset AC across a large home gets missed. A paired one is in this home at any signal.
        val seen = wifi.mapNotNull { w ->
            // One sent the home Wi-Fi stays off the list until a scan sees its own Wi-Fi again after that.
            hotspotOf(w.ssid, w.bssid)?.takeIf {
                w.seenAt > (wifiSent[it.mac] ?: 0L) && (w.dbm >= -75 || (it.protocol to it.mac) in taken)
            }?.let { w to it }
        }
        val hotspots = seen.map { it.second }
        // Logged when which ACs are found changes, not every refresh (signal and age vary): why an AC card or the
        // Wi-Fi notice shows up.
        val report = "on the network: " + nearby.joinToString { "${it.mac} at ${it.host}" }.ifEmpty { "none" } +
            "; on its own Wi-Fi: " + seen.joinToString { (w) -> "${w.ssid} (${w.bssid})" }.ifEmpty { "none" }
        if (report != lastReport) {
            lastReport = report
            log("ACs $report" + seen.joinToString("") { (w) -> "; ${w.bssid}: ${w.dbm} dBm, seen ${now - w.seenAt} s ago" })
        }
        homeWifi = wifi.filter { it.mhz < 2500 && it.ssid.isNotBlank() && hotspotOf(it.ssid, it.bssid) == null }
            .sortedByDescending { it.dbm }.distinctBy { it.ssid } // strongest first: the home router, then neighbours
        // A paired AC that was reset needs its Wi-Fi again too. Already on its Wi-Fi: the scan's own entry comes first.
        found = (nearby + hotspots).distinctBy { it.protocol to it.mac }.filter { it.needsWifi || (it.protocol to it.mac) !in taken }
    }

    fun discover(requestAccess: suspend () -> Boolean) {
        if (discovering) return
        viewModelScope.launch {
            discovering = true
            try {
                if (requestAccess()) {
                    discoveryEnabled = true
                    updateFound(force = true)
                }
            } finally {
                discovering = false
            }
            if (found.isEmpty()) snackbar?.showSnackbar(getString(Res.string.ac_discovery_empty))
        }
    }

    /** [canScan] is null where access can't be checked. There, resume automatic scans after a user starts discovery.
     *  [scanWifi]: also look for reset ACs by their own Wi-Fi ([AcWifiAccess.active]). */
    suspend fun refresh(canScan: Boolean?, scanWifi: Boolean = false, read: suspend (LinkedAc) -> AcState = { it.status() }) {
        this.scanWifi = scanWifi
        coroutineScope {
            if ((canScan ?: discoveryEnabled) && !binding && !discovering) launch { updateFound() }
            linked.forEach { (id, ac) ->
                val revision = revisions[id]
                if (id in awayRooms || pending[id]?.isActive == true) return@forEach
                launch {
                    val state = attempt { settings.withAc(key, id, ac.id, block = read) }.getOrNull()
                    if (revisions[id] == revision) states[id] = state
                }
            }
        }
        linked = settings.linkedAcs(key) // an address may have moved
    }

    fun assign(ac: FoundAc, room: Room) = viewModelScope.launch {
        if (binding) return@launch
        binding = true
        try {
            // A module that just joined the home Wi-Fi can skip a few seconds of replies: ask it again.
            val bound = attempt { ac.bind() }.let { if (it.isFailure && ac.mac in wifiSent) attempt { ac.bind() } else it }.getOrElse {
                log("AC bind to ${ac.mac} at ${ac.host} failed", it)
                updateFound(force = true)
                launch { snackbar?.showSnackbar(getString(Res.string.ac_bind_failed)) }
                return@launch
            }
            if (savedAuthKey(settings) == key) {
                linked[room.id]?.let { old -> found += old.asFound() } // replaced: offer it again
                invalidate(room.id)
                settings.linkAc(key, room.id, bound)
                linked = settings.linkedAcs(key)
                found = found.filterNot { it.protocol == bound.protocol && it.mac == bound.mac }
                val revision = revisions[room.id]
                val state = attempt { bound.status() }.getOrNull()
                if (revisions[room.id] == revision) states[room.id] = state
            }
        } finally {
            binding = false
        }
    }

    /** Gives a reset AC (on its own Wi-Fi) the home Wi-Fi. It shows up as found again once the phone is back home. */
    fun joinWifi(ac: FoundAc, ssid: String, password: String) = viewModelScope.launch {
        if (binding) return@launch
        binding = true
        val sent = try {
            attempt { ac.joinWifi(ssid, password) }.onFailure { log("AC Wi-Fi setup failed", it) }.isSuccess
        } finally {
            binding = false
        }
        if (sent) {
            log("AC ${ac.mac}: sent the home Wi-Fi $ssid")
            wifiSent[ac.mac] = nowSeconds()
            rescan = true
            found -= ac
        }
        snackbar?.showSnackbar(getString(if (sent) Res.string.ac_wifi_sent else Res.string.ac_failed))
    }

    fun minimize(on: Boolean) {
        minimized = on
        settings.putBoolean(AC_MINIMIZED, on)
    }

    fun unlink(roomId: String) {
        val ac = linked[roomId] ?: return
        invalidate(roomId)
        settings.linkAc(key, roomId, null)
        linked = settings.linkedAcs(key)
        found = listOf(ac.asFound()) + found // offer it again right away, first
    }

    private fun invalidate(roomId: String) {
        bump(roomId)
        pending.remove(roomId)?.cancel()
        queued.remove(roomId)
        states.remove(roomId)
    }

    fun endSession() {
        viewModelScope.coroutineContext.cancelChildren()
        linked.keys.forEach(::invalidate)
        linked = emptyMap()
        found = emptyList()
        lastReport = null
        lanScanSucceeded = false
        discoveryEnabled = false
    }

    /** Runs in the floor's write scope so the commands can finish after the activity closes. */
    suspend fun setAway(
        away: Boolean,
        read: suspend (LinkedAc) -> AcState = { it.status() },
        write: suspend (LinkedAc, AcChange, () -> Boolean) -> AcState? = { unit, change, current ->
            unit.apply(change, current)
        },
    ): Set<String> {
        if (savedAuthKey(settings) != key) return emptySet()
        settings.remove("away.ac") // old snapshots stored room IDs, which cannot identify the original AC
        val saved = decodeSet(settings.getString(AC_AWAY, "")).toMutableSet()
        if (away) {
            saved.clear()
            settings.remove(AC_AWAY)
        }
        val failed = mutableSetOf<String>()
        coroutineScope {
            for ((room, ac) in settings.linkedAcs(key)) {
                if (!away && ac.id !in saved) continue
                awayRooms += room
                launch {
                    try {
                        pending[room]?.join()
                        bump(room)
                        val result = attempt {
                            settings.withAc(key, room, ac.id) { unit ->
                                val before = read(unit)
                                val change = AcChange(power = !away)
                                if (before.power == !away) before to false
                                else (write(unit, change) { settings.linkedAc(key, room) == unit }
                                    ?: change.on(before)) to true
                            }
                        }
                        if (settings.linkedAc(key, room)?.id != ac.id) return@launch
                        result.onSuccess { (state, changed) ->
                            states[room] = state
                            if (away && changed) {
                                saved += ac.id
                                settings.putString(AC_AWAY, saved.joinToString(","))
                            }
                        }.onFailure {
                            states[room] = (it as? AcUnreachable)?.actual
                            log("AC change failed", it)
                            failed += room
                        }
                    } finally {
                        awayRooms -= room
                    }
                }
            }
        }
        // shortcut: return tries once, revisit if the UI offers an explicit retry for unreachable units.
        if (!away && savedAuthKey(settings) == key) settings.remove(AC_AWAY)
        linked = settings.linkedAcs(key)
        return failed
    }

    fun set(roomId: String, change: AcChange) {
        if (roomId in awayRooms) return
        val ac = linked[roomId] ?: return
        val previous = states[roomId] ?: return
        val state = change.on(previous)
        if (previous == state) return
        queued[roomId] = queued[roomId]?.plus(change) ?: change
        bump(roomId)
        states[roomId] = state
        if (pending[roomId]?.isActive == true) return
        pending[roomId] = viewModelScope.launch {
            var confirmed: AcState? = previous
            while (roomId in queued) {
                val revision = revisions[roomId]
                delay(500) // wait for the user to stop tapping
                if (revisions[roomId] != revision) continue
                val requested = queued.remove(roomId) ?: break
                val change = confirmed?.let { it.changeTo(requested.on(it)) } ?: requested
                confirmed = attempt {
                    settings.withAc(key, roomId, ac.id) { unit ->
                        unit.apply(change) { settings.linkedAc(key, roomId) == unit }
                            ?: confirmed?.let(change::on) ?: unit.status()
                    }
                }.getOrElse {
                    log("AC change failed", it)
                    viewModelScope.launch { snackbar?.showSnackbar(getString(Res.string.ac_failed)) }
                    if (it is AcUnreachable) it.actual
                    else attempt { settings.withAc(key, roomId, ac.id) { unit -> unit.status() } }.getOrNull()
                }
                if (revisions[roomId] == revision) states[roomId] = confirmed
            }
        }
    }
}
