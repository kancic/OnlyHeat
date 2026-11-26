package ancic.karim.onlyheat.feature.rooms

import ancic.karim.onlyheat.ac.AcChange
import ancic.karim.onlyheat.ac.AcViewModel
import ancic.karim.onlyheat.ac.AcRow
import ancic.karim.onlyheat.ac.AcSheet
import ancic.karim.onlyheat.ac.AcState
import ancic.karim.onlyheat.ac.AcView
import ancic.karim.onlyheat.ac.DeviceRow
import ancic.karim.onlyheat.ac.acSummary
import ancic.karim.onlyheat.ac.fighting
import ancic.karim.onlyheat.ac.floorIcon
import ancic.karim.onlyheat.data.Home
import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.deg
import ancic.karim.onlyheat.data.fmt1
import ancic.karim.onlyheat.data.isCold
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.Badge
import ancic.karim.onlyheat.ui.Group
import ancic.karim.onlyheat.ui.HomeIcon
import ancic.karim.onlyheat.ui.LocalCardShape
import ancic.karim.onlyheat.ui.LuggageIcon
import ancic.karim.onlyheat.ui.NightInk
import ancic.karim.onlyheat.ui.NightSky
import ancic.karim.onlyheat.ui.PressCard
import ancic.karim.onlyheat.ui.RollingTemp
import ancic.karim.onlyheat.ui.RoomActivity
import ancic.karim.onlyheat.ui.Section
import ancic.karim.onlyheat.ui.SectionHeading
import ancic.karim.onlyheat.ui.TabInsets
import ancic.karim.onlyheat.ui.TempStepper
import ancic.karim.onlyheat.ui.cappedPadding
import ancic.karim.onlyheat.ui.collapsing
import ancic.karim.onlyheat.ui.collapsingBar
import ancic.karim.onlyheat.ui.expanded
import ancic.karim.onlyheat.ui.onCardTonal
import ancic.karim.onlyheat.ui.roomAnimations
import ancic.karim.onlyheat.ui.roomColors
import ancic.karim.onlyheat.ui.shared
import ancic.karim.onlyheat.ui.springIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Outside temperature with the weather icon; the full "Outside …" text is for screen readers. */
@Composable
private fun WeatherPill(emoji: String?, outside: Double, big: Boolean) {
    val label = stringResource(Res.string.outside, fmt1(outside))
    Surface(
        Modifier.padding(start = 12.dp, end = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            emoji?.let {
                Text(
                    it,
                    style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                deg(outside),
                style = if (big) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized
            )
        }
    }
}

@Composable
private fun roomsSubtitle(h: Home, acs: List<AcState?>): String {
    val heating = h.rooms.count { it.heating && it.target != null }
    return listOfNotNull(
        pluralStringResource(Res.plurals.rooms, h.rooms.size, h.rooms.size),
        if (heating > 0) stringResource(Res.string.heating_count, heating) else stringResource(Res.string.idle),
        acSummary(acs),
    ).joinToString(" · ")
}

private const val NOTICES_SHOWN = 3 // past this, the rest wait behind an "N more" row

@Composable
internal fun RoomsTab(
    model: HomeViewModel, acs: AcViewModel, discoverAc: (() -> Unit)?, onScheduleLocked: () -> Unit, openRoom: (String) -> Unit,
) {
    var acSheet by rememberSaveable { mutableStateOf<String?>(null) } // room id
    val notices = roomNotices(model, acs, discoverAc)
    val issues = roomIssuesById(model, acs)
    var allNotices by rememberSaveable { mutableStateOf(false) }
    val scroll = collapsingBar()
    val pull = rememberPullToRefreshState()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                // Title, subtitle and weather in one row, so the weather sits centred beside both, expanded or collapsed.
                // The bar composes this slot twice with different text styles; the subtitle follows the size it gets.
                title = {
                    val h = model.home
                    val expanded =
                        LocalTextStyle.current.fontSize > MaterialTheme.typography.titleLarge.fontSize
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h?.device ?: "OnlyHeat")
                            if (h != null) {
                                Text(
                                    roomsSubtitle(h, acs.linkedStates),
                                    style = if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        val outside = model.outside
                        if (outside != null) WeatherPill(
                            model.weather?.emoji,
                            outside,
                            big = expanded
                        )
                    }
                },
                // The subtitle lives in the title slot, so keep the height the bar gives a real subtitle.
                expandedHeight = TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight,
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = TabInsets,
    ) { pad ->
        PullToRefreshBox(
            model.refreshing,
            { model.refresh() },
            Modifier.padding(pad).fillMaxSize(),
            state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    pull,
                    model.refreshing,
                    Modifier.align(Alignment.TopCenter)
                )
            },
        ) {
            when (val h = model.home) {
                null if notices.isEmpty() -> RoomsSkeleton()
                // Adaptive: one column on a phone, two or more on wider windows.
                // The app bar listens inside the refresh box, so a downward drag first expands the
                // collapsed bar and only then pulls to refresh.
                else -> BoxWithConstraints(Modifier.fillMaxSize().collapsing(scroll)) {
                    // Staggered: a room with an AC is taller (two rows), and each column stacks on its own,
                    // so the card beside it doesn't leave a gap under itself.
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                        contentPadding = cappedPadding(1200.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalItemSpacing = 12.dp,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val span = StaggeredGridItemSpan.FullLine
                        if (h != null && model.away) item(span = span) {
                            AwayOnCard(
                                model.awayTemp,
                                model.awayBusy,
                                Modifier.springIn(0),
                            ) { model.comeBack { acs.setAway(false) } }
                        }
                        // One block like the Settings tab's sections: All rooms (with more than one room) above Away.
                        if (h != null && !model.away) item(span = span) {
                            Group {
                                if (h.rooms.size > 1) item("all") { QuickCard(
                                    stringResource(Res.string.all_rooms), HomeIcon, MaterialShapes.Cookie9Sided, 0,
                                    model.allTarget,
                                    { model.allTarget = it },
                                    stringResource(Res.string.set_all),
                                    Modifier.springIn(0),
                                ) { model.setAll(model.allTarget) } }
                                item("away") { QuickCard(
                                    stringResource(Res.string.away_title), LuggageIcon, MaterialShapes.Cookie6Sided, 1,
                                    model.awayTemp,
                                    { model.awayTemp = it },
                                    stringResource(Res.string.away_go),
                                    Modifier.springIn(1),
                                    model.awayBusy,
                                ) { model.goAway { acs.setAway(true) } } }
                            }
                        }
                        // The notification area: errors, warnings, suggestions, most severe first (Notices.kt).
                        // One block like a Settings section, so several notices read as one area.
                        if (notices.isNotEmpty()) item(span = span, key = "notices") {
                            Column {
                                SectionHeading(stringResource(Res.string.notices_title), modifier = Modifier.padding(vertical = 12.dp))
                                // The most severe three; the rest behind a row that says how many.
                                val folded = notices.size > NOTICES_SHOWN + 1 // never hide just one
                                val shown = if (allNotices || !folded) notices else notices.take(NOTICES_SHOWN)
                                Group {
                                    shown.forEach { n ->
                                        item(n.key) { NoticeCard(n, Modifier.springIn(2), if (n.closable) ({ model.close(n.key) }) else null) }
                                    }
                                    if (folded) item("more") {
                                        PressCard(
                                            { allNotices = !allNotices }, Modifier.fillMaxWidth(), shape = LocalCardShape.current,
                                            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    if (allNotices) stringResource(Res.string.notices_less) else stringResource(Res.string.notices_more, notices.size - NOTICES_SHOWN),
                                                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                                )
                                                ExpandChevron(allNotices, null)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (discoverAc != null && h != null && h.rooms.isNotEmpty() && acs.found.isEmpty() && "ac-discovery" in model.closed) {
                            item(span = span) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                    TextButton(discoverAc, enabled = !acs.discovering && !acs.binding) {
                                        Text(stringResource(if (acs.discovering) Res.string.ac_discovering else Res.string.ac_discover))
                                    }
                                }
                            }
                        }
                        // Like a Settings section heading: tells the rooms apart from the controls and notices above.
                        if (h != null && h.rooms.isNotEmpty()) item(span = span, key = "rooms-heading") {
                            SectionHeading(stringResource(Res.string.tab_rooms), modifier = Modifier.padding(top = 12.dp))
                        }
                        itemsIndexed(h?.rooms.orEmpty(), key = { _, r -> "room-${r.id}" }) { i, r ->
                            RoomCard(
                                r,
                                model.boosts.any { it.room == r.id },
                                { model.lastTarget(r.id) },
                                Modifier.springIn(i + 2),
                                onScheduleLocked = onScheduleLocked,
                                ac = acs.room(r.id),
                                onAcChange = { acs.set(r.id, it) },
                                onAcMore = { acSheet = r.id },
                                issues = issues[r.id].orEmpty(),
                                onOpen = { openRoom(r.id) }) { t -> model.setTarget(r, t) }
                        }
                        if (h != null && h.rooms.isEmpty()) item(span = span) { Text(stringResource(Res.string.no_rooms)) }
                    }
                }
            }
        }
    }
    val sheetRoom = model.home?.rooms?.find { it.id == acSheet }
    val sheetAc = acSheet?.let { acs.room(it) }
    if (sheetRoom != null && sheetAc != null) AcSheet(
        sheetRoom, sheetAc, { acs.set(sheetRoom.id, it) },
        onRemove = { acs.unlink(sheetRoom.id); acSheet = null },
        onDismiss = { acSheet = null },
    ) {
        if (fighting(sheetRoom, sheetAc.state)) AcFightCard(sheetRoom, model.outside, { acs.set(sheetRoom.id, it) }, Modifier.padding(bottom = 8.dp), acs.busy(sheetRoom.id)) {
            model.heatingOff(sheetRoom, onScheduleLocked)
        }
    }
}

/** The Rooms tab's shape while the first load runs: All rooms / Away, the heading and three rooms, breathing. */
@Composable
private fun RoomsSkeleton() {
    val pulse by rememberInfiniteTransition().animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse)
    )
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    @Composable
    fun Bone(height: Dp, index: Int, modifier: Modifier = Modifier, shape: Shape = LocalCardShape.current) =
        Box(modifier.springIn(index).fillMaxWidth().height(height).graphicsLayer { alpha = pulse }.background(color, shape))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(cappedPadding(1200.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group {
                item("all") { Bone(88.dp, 0) }
                item("away") { Bone(88.dp, 1) }
            }
            Bone(28.dp, 2, Modifier.padding(start = 4.dp, top = 20.dp).fillMaxWidth(0.3f), CircleShape)
            repeat(3) { Bone(112.dp, it + 3) }
        }
    }
}

@Composable
private fun RoomCard(
    room: Room,
    boosted: Boolean,
    lastTarget: () -> Double,
    modifier: Modifier,
    onScheduleLocked: () -> Unit,
    ac: AcView? = null, // the room's AC: its row goes under the heating row, and it drives the animations too
    onAcChange: (AcChange) -> Unit = {},
    onAcMore: () -> Unit = {}, // tapping the AC's name: mode and fan
    issues: List<RoomIssue> = emptyList(), // pills under the status line (Notices.kt)
    onOpen: () -> Unit,
    onTarget: (Double?) -> Unit
) {
    val on = room.target != null
    val onSwitch = { it: Boolean -> if (room.schedule) onScheduleLocked() else onTarget(if (it) lastTarget() else null) }
    val activity = RoomActivity(room, ac)
    val active = activity.floor
    val acOn = activity.acOn
    val scheme = MaterialTheme.colorScheme
    val cold = isCold(
        room,
        koinInject()) // read each recomposition, so a changed limit shows up
    val awake = activity.awake
    val asleep = !awake && !cold
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    // Off and asleep: a night-sky card in any theme, like the fire's fixed orange. Heating shows as fire on top.
    val (roomContainer, roomContent) = roomColors(room.id)
    val container by animateColorAsState(
        if (asleep) NightSky else if (awake) roomContainer else scheme.surfaceContainerLow,
        spec
    )
    val content by animateColorAsState(
        if (asleep) NightInk else if (awake) roomContent else contentColorFor(scheme.surfaceContainerLow), spec
    )
    PressCard(
        onOpen, modifier.fillMaxWidth().shared("room-${room.id}"),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        val ink = LocalContentColor.current
        Column(
            Modifier.roomAnimations(
                room, ac, cold, ink, boosted,
                // The moon sleeps in the gap between the rows' names and switches, not on a switch.
                moon = Offset(0.52f, if (ac == null) 0.5f else 0.66f),
                zTo = if (ac == null) Offset(0.66f, 0.3f) else Offset(0.66f, 0.5f),
            ).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val working = active || acOn
                    val status = listOfNotNull(
                        // Always a first word, so every card has the same rhythm: what works, else Idle or Off.
                        if (!working) stringResource(if (on) Res.string.status_idle else Res.string.off) else null,
                        // With an AC say which one works: "Floor heating · AC cooling".
                        if (active) stringResource(if (ac == null) Res.string.status_heating else Res.string.floor_heating) else null,
                        if (acOn) acSummary(listOf(ac?.state)) else null,
                        room.humidity?.let {
                            stringResource(
                                Res.string.humidity,
                                "${it.toInt()}%"
                            )
                        },
                        if (room.smart) stringResource(Res.string.smart) else null,
                        if (room.schedule) stringResource(Res.string.schedule) else null,
                        if (boosted) stringResource(Res.string.boost) else null,
                    ).joinToString(" · ")
                    Text(
                        status, style = MaterialTheme.typography.labelLarge,
                        color = LocalContentColor.current.copy(alpha = if (working) 1f else 0.75f),
                    )
                    if (issues.isNotEmpty()) FlowRow(
                        Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { issues.forEach { RoomIssuePill(it) } }
                }
                // One layout for every room: the temperature beside the name, then a row per device.
                RollingTemp(room.current, MaterialTheme.typography.displaySmall)
            }
            Spacer(Modifier.height(4.dp))
            // Like the AC's mode icon: a flame while heating, a thermostat while holding the target, a faded flame when off.
            DeviceRow(
                floorIcon(on, active), stringResource(Res.string.heating_title), on, onSwitch,
                // Without an AC the status line already says it; TalkBack shouldn't read it twice.
                description = if (ac == null) null else stringResource(if (active) Res.string.status_heating else if (on) Res.string.idle else Res.string.off),
            ) {
                if (on) TempStepper(room.target, onTarget, compact = true, onLocked = onScheduleLocked.takeIf { room.schedule })
            }
            if (ac != null) AcRow(ac, fighting(room, ac.state), onAcChange, onAcMore)
        }
    }
}

/** One-row card: a stepper labelled [title] and an action button. */
@Composable
private fun QuickCard(
    title: String, icon: ImageVector, badge: RoundedPolygon, accent: Int, t: Double, onChange: (Double) -> Unit, action: String, modifier: Modifier,
    busy: Boolean = false, onAction: () -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = LocalCardShape.current,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // On narrow phones or with big fonts the button wraps below instead of squeezing its label.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(icon, badge, accent)
                Spacer(Modifier.width(12.dp))
                TempStepper(t, onChange, compact = true, label = title) // like the room cards
            }
            FilledTonalButton(
                onAction,
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
                colors = onCardTonal()
            ) {
                if (busy) LoadingIndicator(
                    Modifier.size(24.dp),
                    color = LocalContentColor.current
                ) else Text(action)
            }
        }
    }
}

/** Away is on: a Settings-style card tinted while it lasts, color meaning state. */
@Composable
private fun AwayOnCard(temp: Double, busy: Boolean, modifier: Modifier, onBack: () -> Unit) {
    Section(
        stringResource(Res.string.away_on_title), stringResource(Res.string.away_on_desc, fmt1(temp)), LuggageIcon,
        MaterialShapes.Cookie6Sided, modifier = modifier,
        container = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        FilledTonalButton(onBack, enabled = !busy, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
            if (busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
            else Text(stringResource(Res.string.away_back))
        }
    }
}
