package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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

/** An AC that answered a scan, not yet paired. [protocol] is its [AcProtocol.id]. */
data class FoundAc(val protocol: String, val mac: String, val host: String, val name: String)

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
}

internal val acProtocols: List<AcProtocol> = listOf(Gree)

private fun protocolOf(id: String) = acProtocols.firstOrNull { it.id == id } ?: throw AcUnreachable()
internal suspend fun FoundAc.bind() = protocolOf(protocol).bind(this)
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

/** Every AC on this device's Wi-Fi, all brands at once. */
private suspend fun scanAcs(force: Boolean = false): List<FoundAc> = scanLock.withLock {
    if (!force && nowSeconds() - lastScan < 5 * 60) return@withLock lastFound
    val hosts = lanHosts()
    lastFound = if (hosts.isEmpty()) emptyList() else coroutineScope {
        acProtocols.map { p -> async { attempt { p.scan(hosts) }.onFailure { log("${p.id} scan failed", it) }.getOrElse { emptyList() } } }
            .awaitAll().flatten().distinctBy { it.protocol to it.mac }
    }
    lastScan = nowSeconds()
    lastFound
}

private const val AC_ROOM = "ac.room."
private const val AC_MINIMIZED = "ac.minimized"
private const val AC_AWAY = "away.acPairings" // under "away.": logout clears it with the rest of Away

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

/** AC state for the Rooms tab. Outlives tab switches like [HomeModel]. */
internal class AcModel(private val settings: Settings, private val key: String) : ViewModel() {
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
    private var discoveryEnabled = false
    /** The found card shrunk to one row. It can't be hidden: an unpaired AC stays on offer. */
    var minimized by mutableStateOf(settings.getBoolean(AC_MINIMIZED, false))
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
    /** Away or I'm back is switching this room's AC, so [set] ignores changes until it's done. */
    fun busy(roomId: String) = roomId in awayRooms

    private fun bump(id: String) {
        revisions[id] = (revisions[id] ?: 0L) + 1
    }

    private suspend fun updateFound(force: Boolean = false) {
        val nearby = scanAcs(force)
        if (nearby.isNotEmpty()) lanScanSucceeded = true
        val taken = linked.values.map { it.protocol to it.mac }.toSet()
        found = nearby.filter { (it.protocol to it.mac) !in taken }
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

    /** [canScan] is null where access can't be checked. There, resume automatic scans after a user starts discovery. */
    suspend fun refresh(canScan: Boolean?, read: suspend (LinkedAc) -> AcState = { it.status() }) {
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
            val bound = attempt { ac.bind() }.getOrElse {
                log("AC bind failed", it)
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

@Composable
internal fun AcDiscoveryCard(
    discovering: Boolean, enabled: Boolean, modifier: Modifier, onClose: (() -> Unit)?, onDiscover: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onDiscover,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = LocalCardShape.current,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(AcIcon, MaterialShapes.Cookie9Sided, accent = 1)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (discovering) Res.string.ac_discovering else Res.string.ac_discover),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    stringResource(Res.string.ac_discovery_wifi),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (discovering) LoadingIndicator(Modifier.size(24.dp))
            else if (onClose != null) IconButton(onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(CloseIcon, stringResource(Res.string.close), tint = scheme.onSurfaceVariant)
            }
            else Icon(ArrowBack, contentDescription = null, Modifier.size(24.dp).rotate(180f), tint = scheme.onSurfaceVariant)
        }
    }
}

/** Card on the Rooms tab when an unassigned AC answers on the Wi-Fi: one tap on a room pairs it.
 *  [minimized]: one row as tall as the All rooms / Away cards, with a button to open it again. */
@Composable
internal fun AcFoundCard(
    rooms: List<Room>, busy: Boolean, minimized: Boolean, modifier: Modifier,
    onRoom: (Room) -> Unit, onMinimize: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = LocalCardShape.current,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
    ) {
        Column(if (minimized) Modifier.padding(horizontal = 16.dp, vertical = 10.dp) else Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { // QuickCard's stepper height
                Badge(AcIcon, MaterialShapes.Cookie9Sided)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (minimized) Res.string.ac_title else Res.string.ac_found_title),
                        style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!minimized) Text(stringResource(Res.string.ac_found_question), style = MaterialTheme.typography.bodyMedium)
                }
                if (minimized) FilledTonalButton({ onMinimize(false) }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
                    Text(stringResource(Res.string.ac_choose_room))
                }
                else IconButton({ onMinimize(true) }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(ExpandLessIcon, stringResource(Res.string.ac_minimize))
                }
            }
            if (!minimized) {
                Spacer(Modifier.height(4.dp))
                if (busy) LoadingIndicator(Modifier.padding(start = 4.dp).size(40.dp))
                else FlowRow(
                    Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), // rows are spaced by each button's 48dp touch target
                ) {
                    val small = ButtonDefaults.ExtraSmallContainerHeight
                    rooms.forEach { r ->
                        FilledTonalButton( // like Set all / Go away
                            onClick = { onRoom(r) }, modifier = Modifier.heightIn(small), shapes = ButtonDefaults.shapesFor(small),
                            colors = onCardTonal(),
                            contentPadding = ButtonDefaults.contentPaddingFor(small),
                        ) { Text(r.name, style = ButtonDefaults.textStyleFor(small)) }
                    }
                }
            }
        }
    }
}

/** All ACs in one phrase for the Rooms header ("AC heating"); null without any, or while none answers. */
@Composable
internal fun acSummary(states: List<AcState?>): String? {
    val known = states.filterNotNull().ifEmpty { return null }
    val modes = known.filter { it.power }.map { it.mode }.toSet()
    return stringResource(
        when (modes) {
            emptySet<AcMode>() -> Res.string.ac_header_off
            setOf(AcMode.Heat) -> Res.string.ac_header_heating
            setOf(AcMode.Cool) -> Res.string.ac_header_cooling
            else -> Res.string.ac_header_on
        }
    )
}

/** What a room's AC is doing, in words. [loaded] false = still asking it; null [state] = it didn't answer. */
@Composable
internal fun acText(state: AcState?, loaded: Boolean): String = when {
    !loaded -> stringResource(Res.string.ac_short)
    state == null -> stringResource(Res.string.ac_at_home_only)
    !state.power -> stringResource(Res.string.ac_off)
    else -> when (state.mode) {
        AcMode.Cool -> stringResource(Res.string.ac_cooling_to, state.target)
        AcMode.Heat -> stringResource(Res.string.ac_heating_to, state.target)
        AcMode.Dry -> stringResource(Res.string.ac_drying)
        AcMode.Fan -> stringResource(Res.string.ac_fan_only)
        AcMode.Auto -> stringResource(Res.string.ac_auto_at, state.target)
    }
}

/** One device on a room card: icon and [label], then the target and the switch. The heating and AC rows share it
 *  so neither looks like the lesser one. The icon fades unless [on]; a null [on] (can't reach it) hides the switch.
 *  Tapping the name runs [onLabel]. [description] tells TalkBack what the icon shows. */
@Composable
internal fun DeviceRow(
    icon: ImageVector, label: String, on: Boolean?, onSwitch: (Boolean) -> Unit,
    onLabel: (() -> Unit)? = null, description: String? = null, trailing: @Composable () -> Unit = {},
) {
    val ink = LocalContentColor.current
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                .then(if (onLabel != null) Modifier.clickable(role = Role.Button, onClick = onLabel) else Modifier)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, description, Modifier.size(22.dp), tint = if (on == true) ink else ink.copy(alpha = 0.5f))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // A tappable name says so: a chevron like a list item's.
            if (onLabel != null) Icon(ChevronRightIcon, null, Modifier.size(20.dp), tint = ink.copy(alpha = 0.6f))
        }
        trailing()
        if (on != null) {
            Spacer(Modifier.width(8.dp))
            CardSwitch(on, onSwitch)
        }
    }
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

/** The AC's mode as an icon: snowflake for cool (and off), sun, drop, wind, or arrows for auto. */
internal fun acIcon(state: AcState?) = when (state?.mode?.takeIf { state.power }) {
    AcMode.Heat -> SunIcon
    AcMode.Dry -> DropIcon
    AcMode.Fan -> AirIcon
    AcMode.Auto -> AutoIcon
    else -> AcIcon
}

/** The floor heating as an icon: a flame while heating, a thermostat while holding its target (and a flame when off). */
internal fun floorIcon(on: Boolean, heating: Boolean) = if (on && !heating) ThermostatIcon else FlameIcon

/** The AC's target in a word or number, for the room details: "27°", "off", "Dry", "Not home". */
@Composable
internal fun acValue(state: AcState?, loaded: Boolean): String = when {
    !loaded -> "–"
    state == null -> stringResource(Res.string.ac_pill_away)
    !state.power -> stringResource(Res.string.off_lower)
    state.mode == AcMode.Dry -> stringResource(Res.string.ac_mode_dry)
    state.mode == AcMode.Fan -> stringResource(Res.string.ac_mode_fan)
    else -> "${fmt1(state.target.toDouble())}°" // like the floor and the stepper
}

/** The AC's row on a room card, the twin of the heating row. The icon shows the mode; tapping the name opens
 *  mode and fan ([onMore]). */
@Composable
internal fun AcRow(ac: AcView, conflict: Boolean, onChange: (AcChange) -> Unit, onMore: () -> Unit) {
    val (state, loaded) = ac
    DeviceRow(
        if (conflict) WarningIcon else acIcon(state), stringResource(Res.string.ac_short), state?.power,
        { onChange(AcChange(power = it)) }, onMore,
        if (conflict) stringResource(Res.string.ac_fighting) else acText(state, loaded),
    ) {
        when {
            !loaded -> LoadingIndicator(Modifier.size(32.dp))
            state == null -> Text(
                stringResource(Res.string.ac_pill_away), style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            !state.power -> {}
            // No target in these modes: name the mode so the row isn't empty.
            state.mode == AcMode.Fan || state.mode == AcMode.Dry -> Text(acValue(state, true), style = MaterialTheme.typography.titleMedium)
            else -> TempStepper(
                state.target.toDouble(), { onChange(AcChange(target = it.toInt())) }, compact = true,
                step = 1.0, min = AC_MIN_T.toDouble(), max = AC_MAX_T.toDouble(),
            )
        }
    }
}

/** Controls for a room's AC, opened from the room card's AC button. */
@Composable
internal fun AcSheet(
    room: Room, ac: AcView, onChange: (AcChange) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
    warning: @Composable () -> Unit = {}, // the fight card, so a change made here that starts a fight says so
) {
    ModalBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            warning()
            Row(verticalAlignment = Alignment.CenterVertically) { // the room screen's AC card header, plus the room
                Badge(acIcon(ac.state), MaterialShapes.Cookie9Sided, accent = 2, modifier = Modifier.align(Alignment.Top))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.ac_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    // Room and status on their own lines: joined, a long room name wraps mid-status.
                    listOf(room.name, acText(ac.state, ac.loaded)).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.width(12.dp))
                AcPower(ac, onChange)
            }
            ac.state?.let { AcControls(it, onChange) }
            AcRemove(onRemove)
        }
    }
}

@Composable
internal fun AcFightCard(
    room: Room, outside: Double?, onChange: (AcChange) -> Unit, modifier: Modifier = Modifier,
    busy: Boolean = false, onHeatingOff: () -> Unit,
) = NoticeCard(fightNotice(room, outside, onChange, busy = busy, onHeatingOff = onHeatingOff), modifier)

/** The same controls as a Settings card on the room's detail screen, tinted while the AC is on. */
@Composable
internal fun AcCard(ac: AcView, onChange: (AcChange) -> Unit, onRemove: () -> Unit) {
    val state = ac.state
    Section(
        stringResource(Res.string.ac_title), acText(state, ac.loaded), acIcon(state), MaterialShapes.Cookie9Sided, accent = 2,
        container = if (state?.power == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        below = {
            state?.let { AcControls(it, onChange) }
            AcRemove(onRemove) // also while the AC can't be reached
        },
    ) { AcPower(ac, onChange) }
}

@Composable
private fun AcPower(ac: AcView, onChange: (AcChange) -> Unit) {
    if (ac.state != null) Switch(ac.state.power, { onChange(AcChange(power = it)) })
    else if (!ac.loaded) LoadingIndicator(Modifier.size(32.dp))
}

/** Rarely used, so a quiet text button in the card's own ink, not a tonal one like the card's actions. */
@Composable
private fun AcRemove(onRemove: () -> Unit) = TextButton(
    onRemove, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
    modifier = Modifier.offset(x = (-16).dp), // text in line with the labels above
) { Text(stringResource(Res.string.ac_remove)) }

/** Mode, temperature and fan. */
@Composable
private fun AcControls(state: AcState, onChange: (AcChange) -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Both rows keep a label: each has an Auto.
        Text(stringResource(Res.string.ac_mode), style = MaterialTheme.typography.labelLarge)
        val modes = listOf(
            AcMode.Cool to Res.string.ac_mode_cool, AcMode.Heat to Res.string.ac_mode_heat, AcMode.Dry to Res.string.ac_mode_dry,
            AcMode.Fan to Res.string.ac_mode_fan, AcMode.Auto to Res.string.ac_mode_auto,
        )
        Segmented(modes.map { it.first to stringResource(it.second) }, state.mode, state.power) { onChange(AcChange(mode = it)) }
        if (state.mode != AcMode.Fan) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Like Thermal Intelligence's target row.
            Text(stringResource(Res.string.smart_target), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TempStepper(
                state.target.toDouble(), { onChange(AcChange(target = it.toInt())) },
                buttonSize = 44.dp, step = 1.0, min = AC_MIN_T.toDouble(), max = AC_MAX_T.toDouble(),
            )
        }
        Text(stringResource(Res.string.ac_fan), style = MaterialTheme.typography.labelLarge)
        val fans = listOf(
            AcFan.Auto to Res.string.ac_fan_auto, AcFan.Low to Res.string.ac_fan_low,
            AcFan.Medium to Res.string.ac_fan_medium, AcFan.High to Res.string.ac_fan_high,
        )
        Segmented(fans.map { it.first to stringResource(it.second) }, state.fan, state.power) { onChange(AcChange(fan = it)) }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    val ink = LocalContentColor.current
    ButtonGroup(
        overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (value, label) ->
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = value == selected, onCheckedChange = { onSelect(value) }, enabled = enabled,
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ink.let { // unchecked in the card's own ink like the other card buttons, not a dark hole
                            ToggleButtonDefaults.colors(containerColor = it.copy(alpha = 0.12f), contentColor = it)
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp), // "Medium" / "Srednja" fit on a phone-width card
                    ) { Text(label, maxLines = 1) }
                },
                menuContent = { menu ->
                    DropdownMenuItem({ Text(label) }, { onSelect(value); menu.dismiss() }, enabled = enabled)
                },
            )
        }
    }
}
