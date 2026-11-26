package ancic.karim.onlyheat.feature.rooms

import ancic.karim.onlyheat.ac.AcChange
import ancic.karim.onlyheat.ac.AcDiscoveryCard
import ancic.karim.onlyheat.ac.AcFoundCard
import ancic.karim.onlyheat.ac.AcViewModel
import ancic.karim.onlyheat.ac.LocalAcWifi
import ancic.karim.onlyheat.ac.AcState
import ancic.karim.onlyheat.ac.acCoolCap
import ancic.karim.onlyheat.ac.acIsWrong
import ancic.karim.onlyheat.ac.fighting
import ancic.karim.onlyheat.data.MIN_RISE
import ancic.karim.onlyheat.data.OperationNotice
import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.WatchState
import ancic.karim.onlyheat.data.fmt1
import ancic.karim.onlyheat.data.isAcCandidate
import ancic.karim.onlyheat.data.isTooHot
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.AMBER
import ancic.karim.onlyheat.ui.LuggageIcon
import ancic.karim.onlyheat.ui.HomeIcon
import ancic.karim.onlyheat.ui.BellIcon
import ancic.karim.onlyheat.ui.AcIcon
import ancic.karim.onlyheat.ui.AirIcon
import ancic.karim.onlyheat.ui.CloudOffIcon
import ancic.karim.onlyheat.ui.Badge
import ancic.karim.onlyheat.ui.DownloadIcon
import ancic.karim.onlyheat.ui.ExpandLessIcon
import ancic.karim.onlyheat.ui.LocalCardShape
import ancic.karim.onlyheat.ui.FlameIcon
import ancic.karim.onlyheat.ui.Section
import ancic.karim.onlyheat.ui.ThermostatIcon
import ancic.karim.onlyheat.ui.TuneIcon
import ancic.karim.onlyheat.ui.WarningIcon
import ancic.karim.onlyheat.ui.hueContent
import ancic.karim.onlyheat.ui.onCardTonal
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// The Rooms tab's notification area, between the Away card and the rooms: everything that needs attention
// right now, most severe first. Built from the current state on every composition; only closed keys are stored.

/** Order = sort order. Any notice can be closed: one that reflects a live problem stays hidden until it clears. */
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
    val closable: Boolean = true,
    val progress: Float? = null, // shown as a bar in place of the actions
)

/** The Rooms tab's notice card: errors tinted red; warnings neutral with an amber badge, so the red ones stand out;
 *  suggestions and info neutral with a colorful badge (suggestions primary, info tertiary, so neighbours differ).
 *  A swipe closes it when [onClose] is set. */
@Composable
internal fun NoticeCard(n: Notice, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    n.custom?.let { return it(modifier, onClose) }
    if (onClose == null) CompactNotice(n, modifier)
    else SwipeToClose(onClose, modifier) { CompactNotice(n, it) }
}

/** Nothing behind it: the content fades as it slides away. Past the point where letting go closes it, a tick and a
 *  springy shrink; back under it, it springs back to full size. Screen readers get Close as an action instead. */
@Composable
private fun SwipeToClose(onClose: () -> Unit, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val closeLabel = stringResource(Res.string.close)
    val state = rememberSwipeToDismissBoxState()
    val haptics = LocalHapticFeedback.current
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    var wasArmed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed != wasArmed) haptics.performHapticFeedback(if (armed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
        wasArmed = armed
    }
    val scale by animateFloatAsState(if (armed) 0.95f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    // Once it's off screen its height springs away, so the cards below slide up; only then is it closed.
    // Not clipped, or the card would be cut at the screen's padding while it slides.
    val shown = remember { MutableTransitionState(true) }
    if (!shown.targetState && shown.isIdle) LaunchedEffect(Unit) { onClose() }
    AnimatedVisibility(shown, exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), clip = false)) {
        SwipeToDismissBox(
            state, backgroundContent = {},
            modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction(closeLabel) { onClose(); true }) },
            onDismiss = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); shown.targetState = false },
        ) {
            content(Modifier.graphicsLayer {
                val moved = runCatching { state.requireOffset() }.getOrDefault(0f)
                alpha = 1f - (abs(moved) / size.width).coerceIn(0f, 1f)
                scaleX = scale; scaleY = scale
            })
        }
    }
}

/** One card that grows in place. Collapsed it shows two lines at most, so every notice is the same height, with a
 *  lone button in the row; expanded, the rest of the text and the buttons below. A download in progress stays open
 *  for its bar. */
@Composable
private fun CompactNotice(n: Notice, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val error = n.level == Level.Error
    val container = if (error) scheme.errorContainer else scheme.surfaceContainerHigh
    val ink = if (error) scheme.onErrorContainer else scheme.onSurface
    val badgeInk = when (n.level) { Level.Error -> ink; Level.Warning -> hueContent(AMBER); else -> null }
    var expanded by rememberSaveable(n.key) { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    val open = expanded || n.progress != null
    val inline = if (open) null else n.actions.singleOrNull()
    val toggles = n.progress == null && (expanded || cut || n.actions.size > 1)
    val fit: (TextLayoutResult) -> Unit = { if (it.hasVisualOverflow) cut = true }
    Card(
        modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = LocalCardShape.current, colors = CardDefaults.cardColors(container, ink),
    ) {
        Column(
            Modifier.then(if (toggles) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (n.icon != null) {
                    Badge(n.icon, MaterialShapes.Cookie9Sided, if (n.level == Level.Info) 1 else 0, badgeInk)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (n.title != null) Text(n.title, style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, onTextLayout = fit)
                    if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = if (n.title != null) 0.8f else 1f),
                        maxLines = if (open) Int.MAX_VALUE else if (n.title != null) 1 else 2,
                        overflow = TextOverflow.Ellipsis, onTextLayout = fit)
                }
                if (inline != null) NoticeButtons(listOf(inline), Modifier.padding(start = 8.dp))
                else if (toggles) ExpandChevron(
                    expanded, stringResource(if (expanded) Res.string.ac_minimize else Res.string.notice_expand),
                    Modifier.padding(start = 8.dp),
                )
            }
            if (n.progress != null) LinearWavyProgressIndicator({ n.progress }, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
            else if (open && n.actions.isNotEmpty()) NoticeButtons(n.actions, Modifier.align(Alignment.End).padding(top = 4.dp))
        }
    }
}

/** Points down while collapsed, turns up when [expanded]. */
@Composable
internal fun ExpandChevron(expanded: Boolean, description: String?, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (expanded) 0f else 180f, MaterialTheme.motionScheme.defaultSpatialSpec())
    Icon(ExpandLessIcon, description, modifier.rotate(turn))
}

@Composable
private fun NoticeButtons(actions: List<NoticeAction>, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { a ->
            FilledTonalButton(a.onClick, enabled = a.enabled, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(a.label) }
        }
    }
}

/** What a room needs attention for; [key] identifies its notice (and what was closed). */
internal sealed class RoomIssue(val room: Room, kind: String) {
    val key = "$kind-${room.id}"
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
    model: HomeViewModel, acs: AcViewModel, discoverAc: (() -> Unit)?,
): List<Notice> {
    val h = model.home
    val list = mutableListOf<Notice>()
    val acWifi = LocalAcWifi.current
    val scope = rememberCoroutineScope()
    model.error?.let { e ->
        list += Notice("error", Level.Error, e, icon = CloudOffIcon,
            actions = listOf(NoticeAction(stringResource(Res.string.retry)) { model.refresh() }))
    }
    if (model.alertsOn && !model.notificationsAllowed) list += Notice("notifications", Level.Warning,
        stringResource(Res.string.notice_notifications_off), icon = BellIcon,
        actions = listOf(NoticeAction(stringResource(Res.string.notice_notifications_allow), onClick = model.openNotificationSettings)))
    model.writeNotices.forEach { (id, text) ->
        // The room's name as the title, so the list scans by room.
        val name = h?.rooms?.find { it.id == id }?.name
        list += Notice("write-$id", Level.Warning, name?.let { text.removePrefix("$it: ") } ?: text, name, ThermostatIcon)
    }
    model.operationNotices.forEach { (operation, text) ->
        val (title, icon, retry) = when (operation) {
            OperationNotice.AwayFailed -> Triple(stringResource(Res.string.away_failed), LuggageIcon,
                NoticeAction(stringResource(Res.string.retry), !model.awayBusy) { model.retryAway { acs.setAway(true) } })
            OperationNotice.BackFailed -> Triple(stringResource(Res.string.back_failed), LuggageIcon,
                NoticeAction(stringResource(Res.string.retry), !model.awayBusy) { model.comeBack { acs.setAway(false) } })
            OperationNotice.AllFailed -> Triple(stringResource(Res.string.all_failed), HomeIcon, null)
            OperationNotice.AcOff, OperationNotice.AcOn -> Triple(null, AcIcon, null)
            OperationNotice.AwayKept -> Triple(null, LuggageIcon, null)
        }
        list += Notice("operation-${operation.name}", if (retry == null) Level.Warning else Level.Error,
            text, title, icon, listOfNotNull(retry))
    }
    model.update?.let { u ->
        // The version in the key: closing hides this release only, the next one shows again.
        val progress = model.updateProgress
        list += Notice("update-${u.version}", Level.Suggestion,
            if (progress == null) stringResource(Res.string.update_available, u.version)
            else stringResource(Res.string.update_downloading, u.version, (progress * 100).toInt()),
            icon = DownloadIcon, actions = listOf(NoticeAction(stringResource(Res.string.update_install)) { model.startUpdate(u) }),
            closable = progress == null, progress = progress)
    }
    model.firmwareChange?.let { (old, new) ->
        list += Notice("firmware", Level.Info, stringResource(Res.string.firmware_changed_desc, old, new),
            stringResource(Res.string.firmware_changed_title), TuneIcon)
    }
    if (h != null && h.rooms.isNotEmpty()) {
        val found = acs.found.firstOrNull()
        // Found can be minimized but not closed: closing would lose the AC until it drops off the network.
        if (found != null) list += Notice("ac-found:${found.protocol}:${found.mac}:${found.bssid}", Level.Suggestion, custom = { m, _ ->
            AcFoundCard(
                h.rooms, acs.binding, acs.minimized, m, { acs.assign(found, it) }, acs::minimize,
                if (found.needsWifi) { ssid, password -> acs.joinWifi(found, ssid, password) } else null, acs.homeWifi,
            )
        })
        else if (discoverAc != null) list += Notice("ac-discovery", Level.Info, custom = { m, close ->
            AcDiscoveryCard(acs.discovering, enabled = !acs.discovering && !acs.binding, m, close, discoverAc)
        })
        // Beside the cards above, not instead of them: LAN discovery works without the permission. Shown whatever else is
        // shown, so a close sticks until the permission or the Settings switch ends it.
        if (acWifi != null && !acWifi.granted && acWifi.on) list += Notice("ac-wifi-access", Level.Suggestion,
            stringResource(Res.string.ac_wifi_access_desc), stringResource(Res.string.ac_wifi_access_title), AcIcon,
            listOf(NoticeAction(stringResource(Res.string.ac_wifi_access_allow)) { scope.launch { acWifi.request() } }))
    }
    if (h != null) {
        val keys = list.map { it.key }.toSet()
        LaunchedEffect(keys) { model.forgetClosed(keys) }
    }
    return list.filter { it.key !in model.closed }.sortedBy { it.level }
}

/** Room problems by room id, shown on the room cards instead of the notification area. The fight shows in the
 *  card's AC row already, with its buttons on the room's page, so it isn't listed again. */
@Composable
internal fun roomIssuesById(model: HomeViewModel, acs: AcViewModel): Map<String, List<RoomIssue>> {
    val issues = roomIssues(model.home?.rooms.orEmpty(), model.watched, model.outside, model.warmAbove, model.shownIssues) {
        acs.room(it)?.state
    }
    val keys = issues.map { it.key }.toSet()
    LaunchedEffect(keys) { model.shownIssues = keys }
    return issues.filter { !(it is RoomIssue.UseAc && it.outside == null) }.groupBy { it.room.id }
}

/** One problem as a short pill on its room card; the card opens the room for the rest. */
@Composable
internal fun RoomIssuePill(i: RoomIssue) {
    val (icon, text) = when (i) {
        is RoomIssue.Window -> AirIcon to stringResource(Res.string.room_issue_window)
        is RoomIssue.NotWarming -> ThermostatIcon to stringResource(Res.string.room_issue_not_warming)
        is RoomIssue.TooHot -> FlameIcon to stringResource(Res.string.room_issue_too_hot)
        is RoomIssue.UseAc -> AcIcon to stringResource(Res.string.room_issue_use_ac, fmt1(i.outside ?: 0.0))
    }
    // In the card's own ink, so it suits every room color.
    val fg = LocalContentColor.current
    val bg = fg.copy(alpha = 0.16f)
    Row(
        Modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Heating and AC working against each other (callers check [fighting]); the weather picks which one the button
 *  turns off. A scheduled room's heating can't be switched by hand, so it also gets Turn off AC. */
@Composable
internal fun AcFightCard(
    room: Room, outside: Double?, onChange: (AcChange) -> Unit, modifier: Modifier = Modifier,
    busy: Boolean = false, // Away or I'm back is switching the AC, which ignores changes meanwhile
    onHeatingOff: () -> Unit,
) {
    val off = acIsWrong(room, outside)
    val actions = listOfNotNull(
        if (!off) NoticeAction(stringResource(Res.string.ac_heating_off), onClick = onHeatingOff) else null,
        if (off || room.schedule) NoticeAction(stringResource(Res.string.ac_turn_off), !busy) { onChange(AcChange(power = false)) } else null,
    )
    Section(
        null, stringResource(if (off) Res.string.ac_fighting_cold else Res.string.ac_fighting), WarningIcon, MaterialShapes.Cookie9Sided,
        modifier = modifier, container = MaterialTheme.colorScheme.errorContainer, ink = MaterialTheme.colorScheme.onErrorContainer,
        below = { NoticeButtons(actions, Modifier.align(Alignment.End)) },
    )
}
