package ancic.karim.onlyheat.ac

import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.currentLanConnection
import ancic.karim.onlyheat.data.fingerprint
import ancic.karim.onlyheat.data.isPrivateIpv4
import ancic.karim.onlyheat.data.lanHosts
import ancic.karim.onlyheat.data.localIpv4s
import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.nowSeconds
import ancic.karim.onlyheat.data.savedAuthKey
import ancic.karim.onlyheat.data.warmSetting
import ancic.karim.onlyheat.ui.Heat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.russhwolf.settings.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Air conditioners on the home network. Each brand speaks its own local protocol ([AcProtocol]); everything else here
// (storage, the Rooms tab, the AC tip's "Turn on") only sees the brand-neutral types below.

enum class AcMode { Auto, Cool, Dry, Fan, Heat }
enum class AcFan { Auto, Low, Medium, High }

const val AC_MIN_T = 16
const val AC_MAX_T = 30

data class AcState(val power: Boolean, val mode: AcMode, val target: Int, val fan: AcFan) {
    val heating get() = power && mode == AcMode.Heat
    val cooling get() = power && mode == AcMode.Cool
    /** Already doing something about a warm room: the AC tip has nothing to offer. */
    fun cools(roomTemp: Double) =
        power && (mode == AcMode.Dry || ((mode == AcMode.Cool || mode == AcMode.Auto) && target < roomTemp))
}

internal data class AcChange(
    val power: Boolean? = null, val mode: AcMode? = null, val target: Int? = null, val fan: AcFan? = null,
) {
    fun on(state: AcState) = AcState(power ?: state.power, mode ?: state.mode, target ?: state.target, fan ?: state.fan)
    operator fun plus(newer: AcChange) =
        AcChange(newer.power ?: power, newer.mode ?: mode, newer.target ?: target, newer.fan ?: fan)
}

internal fun AcState.changeTo(wanted: AcState) = AcChange(
    wanted.power.takeIf { it != power }, wanted.mode.takeIf { it != mode },
    wanted.target.takeIf { it != target }, wanted.fan.takeIf { it != fan },
)

/** A room's AC as the screens see it: [state] null = it didn't answer (away from home); [loaded] false = still asking. */
data class AcView(val state: AcState?, val loaded: Boolean)

/** An unpaired AC. [ssid] and [bssid] identify its setup hotspot when [needsWifi] is true. */
data class FoundAc(
    val protocol: String, val mac: String, val host: String, val name: String, val ssid: String? = null,
    val needsWifi: Boolean = false, val bssid: String? = null,
)

/** The Wi-Fi this device is on, as SSID to BSSID; null when not on Wi-Fi or the platform won't say. */
internal expect fun currentWifi(): Pair<String, String>?

/** A Wi-Fi network in range; [mhz] tells its band (an AC joins only 2.4 GHz, below 2500); [dbm]: signal, higher is
 *  nearer; [seenAt]: when the scan last saw it, in epoch seconds. */
internal data class NearbyWifi(val ssid: String, val bssid: String, val mhz: Int, val dbm: Int, val seenAt: Long)

/** Nearby Wi-Fi networks seen within 3 minutes of the latest scan, without joining them; empty where the platform can't scan. */
internal expect fun nearbyWifi(): List<NearbyWifi>

/** Spotting a reset AC by its own Wi-Fi needs the platform's permission (Android: location, since Wi-Fi scan results
 *  need it). [ask] shows the permission dialog, or the app's system settings once the dialog no longer shows.
 *  [on] is the Settings switch (on by default, though the permission is asked only from the notice); one instance
 *  for the app, so the notice, the switch and the scans see a change at once. */
class AcWifiAccess(private val settings: Settings, private val check: () -> Boolean, private val ask: suspend () -> Unit) {
    var granted by mutableStateOf(check())
        private set
    var on by mutableStateOf(settings.getBoolean(AC_WIFI_SETUP, true))
        private set
    /** Wi-Fi scans for reset ACs run only while this holds. */
    val active get() = on && granted
    fun recheck() { granted = check() }
    suspend fun request() { ask(); recheck() }
    fun switch(on: Boolean) { this.on = on; settings.putBoolean(AC_WIFI_SETUP, on) }
}

private const val AC_WIFI_SETUP = "ac.wifiSetup"

/** Null where the app can't scan Wi-Fi (iOS, desktop). */
internal val LocalAcWifi = staticCompositionLocalOf<AcWifiAccess?> { null }

/** Sends only over [ap]'s verified Wi-Fi, joining it if needed. Null means the datagram was sent but got no reply. */
internal expect suspend fun exchangeOnWifi(ap: FoundAc, password: String, port: Int, message: String): String?

/** An AC paired with a room. [secret] is whatever its protocol needs to talk to it (Gree: the unit's own key). */
internal data class LinkedAc(val protocol: String, val mac: String, val host: String, val secret: String, val id: String = Uuid.random().toString()) {
    /** Unpaired again: offered on the found card under its MAC. */
    fun asFound() = FoundAc(protocol, mac, host, mac)
}

/** [actual]: what the AC reported when it was read back after a change it didn't confirm; null = no answer at all. */
class AcUnreachable(val actual: AcState? = null) : Exception("air conditioner did not answer")

/** One brand's local protocol. To add a brand: implement this and list it in [acProtocols]. */
internal interface AcProtocol {
    val id: String
    /** The units of this brand among [hosts] (this device's subnet). */
    suspend fun scan(hosts: List<String>): List<FoundAc>
    suspend fun bind(found: FoundAc): LinkedAc
    suspend fun status(ac: LinkedAc): AcState
    suspend fun apply(ac: LinkedAc, change: AcChange, isCurrent: () -> Boolean)
    /** A reset unit of this brand, seen by its own Wi-Fi in a Wi-Fi scan; null if that Wi-Fi isn't one. */
    fun hotspot(ssid: String, bssid: String): FoundAc? = null
    /** Gives a unit that [FoundAc.needsWifi] the home Wi-Fi, joining its own Wi-Fi first when found by [hotspot]. */
    suspend fun joinWifi(found: FoundAc, ssid: String, password: String): Unit = throw AcUnreachable()
}

internal val acProtocols: List<AcProtocol> = listOf(Gree)

private fun protocolOf(id: String) = acProtocols.firstOrNull { it.id == id } ?: throw AcUnreachable()
internal suspend fun FoundAc.bind() = protocolOf(protocol).bind(this)
internal suspend fun FoundAc.joinWifi(ssid: String, password: String) = protocolOf(protocol).joinWifi(this, ssid, password)
/** The reset AC whose own Wi-Fi this is, of any brand; null for any other Wi-Fi. */
internal fun hotspotOf(ssid: String, bssid: String) = acProtocols.firstNotNullOfOrNull { it.hotspot(ssid, bssid) }
internal suspend fun LinkedAc.status() = protocolOf(protocol).status(this)
internal suspend fun LinkedAc.apply(change: AcChange, isCurrent: () -> Boolean): AcState? =
    protocolOf(protocol).applyConfirmed(this, change, isCurrent)

/** Returns the read-back state if the reply did not confirm the change. */
internal suspend fun AcProtocol.applyConfirmed(ac: LinkedAc, change: AcChange, isCurrent: () -> Boolean): AcState? {
    if (!isCurrent()) throw AcUnreachable()
    if (change == AcChange()) return null
    try {
        apply(ac, change, isCurrent)
        return null
    } catch (e: AcUnreachable) {
        if (!isCurrent()) throw e
        val actual = attempt { status(ac) }.getOrNull()
        if (actual == null || change.on(actual) != actual) throw AcUnreachable(actual)
        return actual
    }
}

private val scanLock = Mutex()
private var lastScan = 0L
private var lastFound = emptyList<FoundAc>()
private var lastNetwork: Any? = null

/** Every AC on this device's Wi-Fi, all brands at once. Another Wi-Fi (an AC's own after a reset) scans afresh:
 *  told by the network's id where the platform gives one (an AC's Wi-Fi may share the home's subnet), else its addresses. */
internal suspend fun scanAcs(force: Boolean = false): List<FoundAc> = scanLock.withLock {
    val network = Triple(currentLanConnection()?.id, attempt { localIpv4s() }.getOrNull(), currentWifi())
    if (!force && network == lastNetwork && nowSeconds() - lastScan < 5 * 60) return@withLock lastFound
    lastNetwork = network
    val hosts = lanHosts()
    lastFound = if (hosts.isEmpty()) emptyList() else coroutineScope {
        acProtocols.map { p -> async { attempt { p.scan(hosts) }.onFailure { log("${p.id} scan failed", it) }.getOrElse { emptyList() } } }
            .awaitAll().flatten().distinctBy { it.protocol to it.mac }
    }
    lastScan = nowSeconds()
    lastFound
}

private const val AC_ROOM = "ac.room."

/** The room's AC, with its owning controller session fingerprint and pairing ID. */
internal fun Settings.linkedAc(key: String, roomId: String): LinkedAc? {
    if (savedAuthKey(this) != key) return null
    val f = getStringOrNull(AC_ROOM + roomId)?.split('|', limit = 6) ?: return null
    if (f.size != 6 || f[0] != fingerprint(key) || !isPrivateIpv4(f[4])) return null
    return LinkedAc(f[2], f[3], f[4], f[5], f[1])
}

/** Room id -> its AC. Scans every setting: for when no list of rooms is at hand. */
internal fun Settings.linkedAcs(key: String): Map<String, LinkedAc> =
    keys.filter { it.startsWith(AC_ROOM) }.map { it.removePrefix(AC_ROOM) }
        .mapNotNull { id -> linkedAc(key, id)?.let { id to it } }.toMap()

internal fun Settings.linkAc(key: String, roomId: String, ac: LinkedAc?) {
    if (savedAuthKey(this) != key) return
    if (ac == null) remove(AC_ROOM + roomId)
    else putString(AC_ROOM + roomId, "${fingerprint(key)}|${ac.id}|${ac.protocol}|${ac.mac}|${ac.host}|${ac.secret}")
}

internal fun Settings.clearAcLinks() = keys.filter { it.startsWith(AC_ROOM) }.forEach(::remove)

/** Runs [block] on the room's AC. If it doesn't answer, it may have a new address: look for it (a fresh scan at most
 *  every 5 min; in between, the last scan's results). */
internal suspend fun <T> Settings.withAc(
    key: String, roomId: String, linkId: String, scan: suspend () -> List<FoundAc> = { scanAcs() },
    block: suspend (LinkedAc) -> T,
): T {
    val ac = linkedAc(key, roomId)?.takeIf { it.id == linkId } ?: throw AcUnreachable()
    return try {
        block(ac)
    } catch (e: AcUnreachable) {
        if (e.actual != null || linkedAc(key, roomId) != ac) throw e
        val moved = scan().find { it.protocol == ac.protocol && it.mac == ac.mac && it.host != ac.host } ?: throw e
        if (linkedAc(key, roomId) != ac) throw e
        ac.copy(host = moved.host).also { linkAc(key, roomId, it) }.let { block(it) }
    }
}

/** Null when the AC doesn't answer within a few seconds (not home). No rescan for a moved address: a worker shouldn't
 *  sweep the subnet every 20 minutes. */
internal suspend fun acTipState(ac: LinkedAc): AcState? =
    withTimeoutOrNull(5.seconds) { attempt { ac.status() }.getOrNull() }

/** The AC tip's Turn on cools at most to a degree under "Too warm above". */
internal fun acCoolCap(warmAbove: Double) = (warmAbove - 1).toInt().coerceIn(AC_MIN_T, AC_MAX_T)

/** "Turn on" from the AC tip: cool at the AC's own setting, capped a degree under "Too warm above" so a winter heat
 *  setting doesn't cool to 30°. Rooms run at once and give up after 8 s: a broadcast receiver has about 10 s. */
suspend fun coolRooms(s: Settings, linkIds: List<String>): Boolean {
    val key = savedAuthKey(s) ?: return false
    val cap = acCoolCap(warmSetting(s))
    val rooms = s.linkedAcs(key).entries.associate { (room, ac) -> ac.id to room }
    if (linkIds.isEmpty() || !rooms.keys.containsAll(linkIds)) return false
    return withTimeoutOrNull(8.seconds) {
        linkIds.distinct().map { linkId ->
            async {
                attempt {
                    val room = rooms.getValue(linkId)
                    s.withAc(key, room, linkId) { ac ->
                        val current = ac.status()
                        val wanted = current.copy(power = true, mode = AcMode.Cool, target = current.target.coerceAtMost(cap))
                        ac.apply(current.changeTo(wanted)) { s.linkedAc(key, room) == ac }
                    }
                }.onFailure { log("AC on failed", it) }.isSuccess
            }
        }.awaitAll().all { it }
    } ?: false
}

/** The room's heating works against its AC cooling: it heats, or is set above the AC's target. */
internal fun fighting(room: Room, state: AcState?) =
    state != null && state.cooling && !room.cooling && room.target != null && (room.heating || room.target > state.target)

/** In a fight, the AC is the one to turn off when it's clearly cold outside, and the heating when it's at least
 *  as warm outside as in the room (the AC tip's rule, Watch.kt). In between, as on a summer night that is merely
 *  cooler than the room, or without an outside temperature (no AccuWeather on old firmware, no TS7 sensor),
 *  the season decides: October to April the heating stays. */
internal fun acIsWrong(room: Room, outside: Double?, month: Int = thisMonth()) = when {
    outside != null && outside < COLD_OUTSIDE -> true
    outside != null && room.current != null && outside >= room.current -> false
    else -> month in HEATING_MONTHS
}

// ponytail: a guess at "heating weather"; tune it if the card picks the wrong side in spring or autumn
private const val COLD_OUTSIDE = 15.0

// ponytail: northern hemisphere; use the controller's latitude if one ever ships south of the equator
private val HEATING_MONTHS = setOf(10, 11, 12, 1, 2, 3, 4)

private fun thisMonth() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).month.number
