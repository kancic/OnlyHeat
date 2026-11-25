package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.foundation.background
import com.russhwolf.settings.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

// The Rooms tab's notification area, between the Away card and the rooms: everything that needs attention
// right now, most severe first. Built from the current state on every composition; only closed keys are stored.

/** Order = sort order. Only [Suggestion] and [Info] can be closed: errors and warnings clear when fixed. */
internal enum class Level { Error, Warning, Suggestion, Info }

internal class NoticeAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** [custom] replaces the standard card (the AC cards with their own controls); it gets the close callback. */
internal class Notice(
    val key: String,
    val level: Level,
    val text: String = "",
    val title: String? = null,
    val icon: ImageVector? = null,
    val actions: List<NoticeAction> = emptyList(),
    val custom: (@Composable (Modifier, onClose: (() -> Unit)?) -> Unit)? = null,
    val closable: Boolean = level >= Level.Suggestion,
)

// A temperature change the controller ignored or that failed, per room, from the app or the widget. Kept in
// settings so it waits for the user without the notification permission; gone when closed or the room is changed.
private const val WRITE_NOTICE = "notice.write."
private const val OPERATION_NOTICE = "notice.operation."
private const val NOTICE_SESSION = "noticeSession"

internal enum class OperationNotice { AwayFailed, BackFailed, AwayKept, AcOff, AcOn, AllFailed }

fun noticeSession(settings: Settings) = settings.getStringOrNull(NOTICE_SESSION)

internal fun startNoticeSession(settings: Settings) {
    clearSessionNotices(settings)
    settings.putString(NOTICE_SESSION, Uuid.random().toString())
}

internal fun clearSessionNotices(settings: Settings) {
    settings.remove(NOTICE_SESSION)
    settings.keys.filter { it.startsWith(WRITE_NOTICE) || it.startsWith(OPERATION_NOTICE) }.forEach(settings::remove)
}

fun saveWriteNotice(settings: Settings, roomId: String, text: String?) =
    if (text == null) settings.remove(WRITE_NOTICE + roomId) else settings.putString(WRITE_NOTICE + roomId, text)

internal fun loadWriteNotices(settings: Settings): Map<String, String> =
    settings.keys.filter { it.startsWith(WRITE_NOTICE) }.associate { it.removePrefix(WRITE_NOTICE) to settings.getString(it, "") }

internal fun saveOperationNotice(settings: Settings, notice: OperationNotice, text: String?) {
    val key = OPERATION_NOTICE + notice.name
    if (text == null) settings.remove(key) else settings.putString(key, text)
}

internal fun loadOperationNotices(settings: Settings) = OperationNotice.entries.mapNotNull { notice ->
    settings.getStringOrNull(OPERATION_NOTICE + notice.name)?.let { notice to it }
}.toMap()

/** A Settings card ([Section]): errors and warnings tinted with a badge in their own ink, suggestions and info
 *  neutral with a colorful badge (suggestions primary, info tertiary, so neighbours differ). */
@Composable
internal fun NoticeCard(n: Notice, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    n.custom?.let { return it(modifier, onClose) }
    val scheme = MaterialTheme.colorScheme
    val (container, ink) = when (n.level) {
        Level.Error -> scheme.errorContainer to scheme.onErrorContainer
        Level.Warning -> hueContainer(AMBER) to hueContent(AMBER)
        else -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    Section(
        n.title, n.text, n.icon, MaterialShapes.Cookie9Sided, accent = if (n.level == Level.Info) 1 else 0,
        modifier = modifier, container = container, ink = ink,
        below = if (n.actions.isEmpty()) null else ({
            Row(Modifier.align(Alignment.End).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                n.actions.forEach { a ->
                    FilledTonalButton(a.onClick, enabled = a.enabled, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(a.label) }
                }
            }
        }),
    ) {
        if (onClose != null) IconButton(onClose, modifier = Modifier.align(Alignment.Top).offset(y = (-8).dp), shapes = IconButtonDefaults.shapes()) {
            Icon(CloseIcon, stringResource(Res.string.close))
        }
    }
}

/** Heating and AC working against each other (callers check [fighting]); the weather picks which one the button
 *  turns off. A scheduled room's heating can't be switched by hand, so it also gets Turn off AC. */
@Composable
internal fun fightNotice(
    room: Room, outside: Double?, onChange: (AcChange) -> Unit,
    title: String? = null, // the room's name in the rooms list, where "this room" alone doesn't say which
    busy: Boolean = false, // Away or I'm back is switching the AC, which ignores changes meanwhile
    onHeatingOff: () -> Unit,
): Notice {
    val off = acIsWrong(room, outside)
    return Notice(
        "fight-${room.id}", Level.Error,
        stringResource(if (off) Res.string.ac_fighting_cold else Res.string.ac_fighting), title, WarningIcon,
        listOfNotNull(
            if (!off) NoticeAction(stringResource(Res.string.ac_heating_off), onClick = onHeatingOff) else null,
            if (off || room.schedule) NoticeAction(stringResource(Res.string.ac_turn_off), !busy) { onChange(AcChange(power = false)) } else null,
        ),
    )
}

/** What a room needs attention for; [key] identifies its notice (and what was closed). */
internal sealed class RoomIssue(val room: Room, kind: String) {
    val key = "$kind-${room.id}"
    class Fight(room: Room) : RoomIssue(room, "fight")
    class Window(room: Room) : RoomIssue(room, "window")
    class NotWarming(room: Room, val temp: Double, val target: Double) : RoomIssue(room, "stuck")
    class TooHot(room: Room, val temp: Double, val target: Double) : RoomIssue(room, "hot")
    /** A null [outside] keeps an existing tip pending until weather returns. */
    class UseAc(room: Room, val outside: Double?, val coolingTarget: Int?) : RoomIssue(room, "use-ac")
}

/**
 * Pure rules for the room notices. [watched]: the background check's state (null = alerts off or stale);
 * [acState]: a linked AC's last reading, null without one or while it doesn't answer; [shown]: existing issue
 * keys, including dismissed tips, which clear half a degree later than they appear (as in Watch.kt).
 */
internal fun roomIssues(
    rooms: List<Room>, watched: WatchState?, outside: Double?, warmAbove: Double, shown: Set<String> = emptySet(),
    acState: (String) -> AcState?,
): List<RoomIssue> = buildList {
    val cap = acCoolCap(warmAbove)
    for (r in rooms) {
        val ac = acState(r.id)
        val fight = fighting(r, ac)
        if (fight) add(RoomIssue.Fight(r))
        val t = r.current ?: continue
        val target = r.target
        // Window and not warming need readings over time, so they come from the background check; the live
        // guards drop them as soon as the room is fine again, without waiting up to 20 minutes for the worker.
        if (watched != null && target != null) {
            val last = watched.last[r.id]
            if (r.id in watched.window && t < target && (last == null || t <= last)) add(RoomIssue.Window(r))
            val warmFrom = watched.warmFrom[r.id]?.first
            if (r.id in watched.stuck && t < target - 0.5 && (warmFrom == null || t < warmFrom + MIN_RISE)) {
                add(RoomIssue.NotWarming(r, t, target))
            }
        }
        // Too hot and the AC tip only need the current reading (the same rules as Watch.kt). Fighting already
        // says the heating is the problem; blaming the valve as well would contradict it.
        if (!fight && target != null && r.isTooHot(t, target, wasHot = "hot-${r.id}" in shown)) add(RoomIssue.TooHot(r, t, target))
        val wasWarm = "use-ac-${r.id}" in shown
        if (!r.isAcCandidate(if (wasWarm) setOf(r.id) else emptySet(), warmAbove) || ac?.cools(t) == true) continue
        val coolingTarget = ac?.let { minOf(it.target, cap) }
        if (!r.cooling && target != null && coolingTarget != null && target > coolingTarget) continue
        val warmOutside = outside?.let { it >= t - (if (wasWarm) 0.5 else 0.0) }
        if (warmOutside ?: wasWarm) add(RoomIssue.UseAc(r, outside, coolingTarget))
    }
}

/** Everything for the Rooms tab's notification area, sorted, closed ones left out. */
@Composable
internal fun roomNotices(
    model: HomeModel, acs: AcModel, discoverAc: (() -> Unit)?, onScheduleLocked: () -> Unit, openRoom: (String) -> Unit,
): List<Notice> {
    val h = model.home
    val outside = model.outside
    val watched = model.watched // the background check's view; null when alerts are off or it's stale
    val list = mutableListOf<Notice>()
    model.error?.let { e ->
        list += Notice("error", Level.Error, e, icon = CloudOffIcon,
            actions = listOf(NoticeAction(stringResource(Res.string.retry)) { model.refresh() }))
    }
    if (model.alertsOn && !model.notificationsAllowed) list += Notice("notifications", Level.Warning,
        stringResource(Res.string.notice_notifications_off), icon = WarningIcon,
        actions = listOf(NoticeAction(stringResource(Res.string.notice_notifications_allow), onClick = model.openNotificationSettings)))
    model.writeNotices.forEach { (id, text) ->
        list += Notice("write-$id", Level.Warning, text, icon = WarningIcon, closable = true)
    }
    model.operationNotices.forEach { (operation, text) ->
        val retry = when (operation) {
            OperationNotice.AwayFailed -> NoticeAction(stringResource(Res.string.retry), !model.awayBusy) {
                model.retryAway { acs.setAway(true) }
            }
            OperationNotice.BackFailed -> NoticeAction(stringResource(Res.string.retry), !model.awayBusy) {
                model.comeBack { acs.setAway(false) }
            }
            else -> null
        }
        val title = when (operation) {
            OperationNotice.AwayFailed -> stringResource(Res.string.away_failed)
            OperationNotice.BackFailed -> stringResource(Res.string.back_failed)
            OperationNotice.AllFailed -> stringResource(Res.string.all_failed)
            else -> null
        }
        list += Notice("operation-${operation.name}", if (retry == null) Level.Warning else Level.Error,
            text, title, WarningIcon, listOfNotNull(retry), closable = retry == null)
    }
    val issues = roomIssues(h?.rooms.orEmpty(), watched, outside, model.warmAbove, model.shownIssues + model.closed) {
        acs.room(it)?.state
    }
    val issueKeys = issues.map { it.key }.toSet()
    LaunchedEffect(issueKeys) { model.shownIssues = issueKeys }
    for (i in issues) {
        val r = i.room
        list += when (i) {
            is RoomIssue.Fight -> fightNotice(r, outside, { acs.set(r.id, it) }, r.name, acs.busy(r.id)) {
                model.heatingOff(r, onScheduleLocked)
            }
            is RoomIssue.Window -> Notice(i.key, Level.Warning, stringResource(Res.string.notice_window, r.name), icon = AirIcon)
            is RoomIssue.NotWarming -> Notice(i.key, Level.Warning,
                stringResource(Res.string.alert_not_warming, r.name, fmt1(i.temp), fmt1(i.target)), icon = ThermostatIcon,
                actions = listOf(NoticeAction(stringResource(Res.string.notice_open_room)) { openRoom(r.id) }))
            is RoomIssue.TooHot -> Notice(i.key, Level.Warning,
                stringResource(Res.string.alert_too_hot, r.name, fmt1(i.temp), fmt1(i.target)), icon = FlameIcon)
            is RoomIssue.UseAc -> {
                val tipOutside = i.outside ?: continue
                Notice(i.key, Level.Suggestion,
                    stringResource(Res.string.alert_use_ac, r.name, fmt1(tipOutside)), icon = AcIcon,
                    actions = i.coolingTarget?.let { target ->
                        listOf(NoticeAction(stringResource(Res.string.ac_turn_on), !acs.busy(r.id)) {
                            acs.set(r.id, AcChange(power = true, mode = AcMode.Cool, target = target))
                        })
                    }.orEmpty())
            }
        }
    }
    model.firmwareChange?.let { (old, new) ->
        list += Notice("firmware", Level.Info, stringResource(Res.string.firmware_changed_desc, old, new),
            stringResource(Res.string.firmware_changed_title), TuneIcon)
    }
    if (h != null && h.rooms.isNotEmpty()) {
        val found = acs.found.firstOrNull()
        // Found can be minimized but not closed: closing would lose the AC until it drops off the network.
        if (found != null) list += Notice("ac-found", Level.Suggestion, custom = { m, _ ->
            AcFoundCard(h.rooms, acs.binding, acs.minimized, m, { acs.assign(found, it) }, acs::minimize)
        })
        else if (discoverAc != null) list += Notice("ac-discovery", Level.Info, custom = { m, close ->
            AcDiscoveryCard(acs.discovering, enabled = !acs.discovering && !acs.binding, m, close, discoverAc)
        })
    }
    // Include pending AC tips so a weather outage alone never resets a dismissal.
    if (h != null) {
        val keys = issueKeys + list.map { it.key }
        LaunchedEffect(keys) { model.forgetClosed(keys) }
    }
    return list.filter { it.key !in model.closed }.sortedBy { it.level }
}
