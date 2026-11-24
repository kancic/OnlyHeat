package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.koin.compose.koinInject

@Composable
private fun SubScreen(
    title: String, onBack: (() -> Unit)?, subtitle: String? = null, // no back arrow when shown as a tab
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = collapsingBar()
    Scaffold(
        modifier = modifier.collapsing(scroll),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title, maxLines = 1) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = {
                    if (onBack != null) IconButton(onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = if (onBack == null) TabInsets else ScaffoldDefaults.contentWindowInsets,
        content = content,
    )
}

/** Room detail: 24 h chart from /getRoomLog, boost and the smart-heating switch. */
@Composable
fun RoomScreen(
    api: HeatApi, key: String, room: Room, boost: Boost?,
    onBoost: (minutes: Int) -> Unit, onStopBoost: () -> Unit, onSmartChanged: () -> Unit,
    ac: AcView? = null, // the room's AC: the hero then shows the floor and the AC as two equal lines and animates for both
    acCard: @Composable (AcView) -> Unit = {}, // its controls, under the hero
    onBack: () -> Unit,
) {
    val settings = koinInject<Settings>()
    val history = koinInject<ReadingDao>()
    var log by remember { mutableStateOf<List<Point>?>(null) }
    var smart by remember { mutableStateOf<Smart?>(null) }
    var logError by remember { mutableStateOf<String?>(null) }
    var smartError by remember { mutableStateOf<String?>(null) }

    // The log has 5-minute points, so reload at that pace while the screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(room.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                attempt { api.roomLog(key, room.id).also { history.store(room.id, it) } }
                    .onSuccess { log = it; logError = null }.onFailure { logError = errorText(it) }
                attempt { api.smart(key, room.id) }
                    .onSuccess { smart = it; smartError = null }.onFailure { smartError = errorText(it) }
                delay(5.minutes)
            }
        }
    }

    SubScreen(
        room.name, onBack, subtitle = if (room.schedule) stringResource(Res.string.follows_schedule) else null,
        modifier = Modifier.shared("room-${room.id}"),
    ) { pad ->
          Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 720.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            val activity = RoomActivity(room, ac)
            val heating = activity.floor
            val on = room.target != null
            val cold = isCold(room, settings)
            val asleep = !activity.awake && !cold
            val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
            val blob by animateColorAsState(if (asleep) NightSky else roomContainer(room.id), spec)
            val ink by animateColorAsState(if (asleep) NightInk else contentColorFor(roomContainer(room.id)), spec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hero: the temperature sits in a blob that turns into a spinning cookie while heating,
                // with the list card's animations inside, clipped to the blob.
                MorphBlob(heating || activity.acOn, blob, Modifier.size(184.dp)) {
                    Box(
                        Modifier.matchParentSize().roomAnimations(
                            room, ac, cold, ink, bubbles = 0.12f,
                            moon = Offset(0.42f, 0.2f), zTo = Offset(0.72f, 0.12f), // above the number
                        ),
                    )
                    RollingTemp(room.current, MaterialTheme.typography.displayMediumEmphasized, color = ink)
                }
                Spacer(Modifier.width(20.dp))
                if (ac != null) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Like the card's rows: the icon tells what each one does, the number what it aims for.
                    HeroDevice(
                        floorIcon(on, heating), room.target?.let { "${fmt1(it)}°" } ?: stringResource(Res.string.off_lower),
                        stringResource(Res.string.heating_title), on,
                    )
                    HeroDevice(
                        acIcon(ac.state), acValue(ac.state, ac.loaded), stringResource(Res.string.ac_short), activity.acOn,
                    )
                }
                else Column {
                    Text(
                        room.target?.let { stringResource(Res.string.target_value, fmt1(it)) } ?: stringResource(Res.string.off_lower),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (heating) Text(
                        stringResource(Res.string.status_heating), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = containerHigh()) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(Res.string.last_24h), style = MaterialTheme.typography.titleMediumEmphasized)
                    Spacer(Modifier.height(12.dp))
                    val l = log
                    if (l == null) Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) {
                        logError?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: LoadingIndicator()
                    }
                    else {
                        StatsLine(l, MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        TempChart(l, Modifier.fillMaxWidth().height(220.dp), interactive = true)
                        Spacer(Modifier.height(8.dp))
                        Legend()
                        logError?.let { Text(it, color = MaterialTheme.colorScheme.error) } // stale chart, refresh failed
                        val temps = l.mapNotNull { it.current }
                        if (temps.isNotEmpty()) Text(
                            stringResource(Res.string.min_max, fmt1(temps.min()), fmt1(temps.max())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ac?.let { acCard(it) }
            BoostCard(boost, onBoost, onStopBoost)
            val supported = remember { hasThermalIntelligence(settings.getStringOrNull(FIRMWARE)) }
            // Old firmware: only show it while it's on, so it can be turned off.
            if (supported || smart?.on == true) SmartCard(api, key, room, smart, smartError, supported) {
                smart = it
                onSmartChanged()
            }
          }
    }
}

/** One device beside the room's hero temperature: its icon (faded when off), its target, its name under it. */
@Composable
private fun HeroDevice(icon: ImageVector, value: String, name: String, on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp), tint = if (on) primary else primary.copy(alpha = 0.5f))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, style = MaterialTheme.typography.headlineSmallEmphasized, color = primary, maxLines = 1)
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoostCard(boost: Boost?, onBoost: (Int) -> Unit, onStop: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (boost != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.boost_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        if (boost != null) stringResource(Res.string.boost_active, fmt1(boost.target), hhmm(boost.until))
                        else stringResource(Res.string.boost_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (boost != null) TextButton(onStop, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.boost_stop)) }
            }
            Spacer(Modifier.height(12.dp))
            // Tapping again while boosted restarts the timer from now.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to Res.string.boost_30, 60 to Res.string.boost_60, 120 to Res.string.boost_120).forEach { (min, label) ->
                    FilledTonalButton({ onBoost(min) }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(stringResource(label)) }
                }
            }
        }
    }
}

@Composable
private fun SmartCard(api: HeatApi, key: String, room: Room, smart: Smart?, loadError: String?, supported: Boolean, onSmart: (Smart) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var target by remember(smart) { mutableStateOf(smart?.target ?: room.target ?: 22.0) }

    fun apply(on: Boolean) {
        busy = true; msg = null
        scope.launch {
            attempt {
                api.setSmart(key, room.id, on, target)
                val now = api.smart(key, room.id) // read back, like temperatures
                onSmart(now)
                if (now.on != on) msg = getString(
                    if (room.schedule) Res.string.smart_rejected_schedule else Res.string.smart_rejected
                )
            }.onFailure { msg = errorText(it) }
            busy = false
        }
    }

    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (smart?.on == true) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.smart_heating), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        when {
                            smart == null -> loadError ?: stringResource(Res.string.loading)
                            smart.on -> stringResource(Res.string.smart_on, smart.target?.let(::fmt1) ?: "–")
                            else -> stringResource(Res.string.off)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) LoadingIndicator(Modifier.size(36.dp))
                else if (smart?.on == true) TextButton({ apply(false) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.turn_off)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (supported) Res.string.smart_desc else Res.string.smart_unsupported),
                style = MaterialTheme.typography.bodySmall,
            )
            if (smart != null && supported) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.smart_target), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TempStepper(target, { target = it }, buttonSize = 44.dp)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    { apply(true) }, enabled = !busy, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(stringResource(if (smart.on) Res.string.smart_update else Res.string.smart_turn_on))
                }
            }
            msg?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** How far back the History tab looks. The day comes from the server, longer spans from the local [history]. */
private enum class Period(val seconds: Long, val bucket: Long, val label: StringResource) {
    Day(24 * 3600L, 0, Res.string.range_day),
    Week(7 * 24 * 3600L, 30 * 60L, Res.string.range_week), // 30-min averages: ~340 points
    Month(30 * 24 * 3600L, 2 * 3600L, Res.string.range_month), // 2-h averages: ~360 points
}

/** All rooms over the last day (from /getSmartRoomLogsLast24h), week or month (stored on this device). */
@Composable
fun OverviewScreen(
    api: HeatApi, key: String,
    rooms: List<Room>?, // from the home poll, so week/month work even when the day call fails
    cache: MutableState<List<DaySummary>?>, // hoisted so coming back from a room doesn't reload from scratch
    onRoom: (String) -> Unit,
) {
    val settings = koinInject<Settings>()
    val history = koinInject<ReadingDao>()
    var days by cache
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(Period.Day) }
    var stored by remember { mutableStateOf<Map<String, List<Point>>?>(null) } // per room, for week/month
    var storedOutside by remember { mutableStateOf<List<Point>>(emptyList()) }
    var since by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(attempt) {
        error = null
        attempt { days = api.last24h(key) }.onFailure { error = errorText(it) }
    }
    val ids = rooms?.map { it.id } // stable across polls, unlike the list itself
    LaunchedEffect(period, ids, key) {
        if (ids == null || period == Period.Day) return@LaunchedEffect
        suspend fun read() {
            val from = nowSeconds() - period.seconds
            stored = ids.associateWith { id -> history.averaged(id, from, period.bucket).map { it.point() } }
            storedOutside = history.averaged(outsideId(key, settings), from, period.bucket).map { it.point() }
            since = history.oldest()
        }
        read() // what we have, right away
        attempt { syncHistory(api, history, key, settings, every = 30.minutes) }.onSuccess { read() } // then top up from the server
    }

    SubScreen(stringResource(Res.string.history), onBack = null) { pad ->
        if (rooms == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }; return@SubScreen }
        val dayFailed = period == Period.Day && days == null && error != null
        val rows = rooms.map { r ->
            r to if (period == Period.Day) days?.find { it.roomId == r.id }?.points.orEmpty().takeIf { days != null }
            else stored?.get(r.id)
        }
        val forecasts = if (period == Period.Day) days.orEmpty().associate { it.roomId to it.forecast } else emptyMap()
        val outside = if (period == Period.Day) days.orEmpty().flatMap { it.points }.outside() else storedOutside
        val outsideAhead = if (period == Period.Day) days.orEmpty().flatMap { it.forecast }.outside() else emptyList()
        val hasOutside = outside.isNotEmpty() || outsideAhead.isNotEmpty()
        // One scale so rooms compare at a glance.
        val shared = yRangeOf(rows.flatMap { it.second.orEmpty() } + forecasts.values.flatten())
        // Under 2 days saved, the week/month view would only repeat the 24 h tab: say so instead.
        val nothingSaved = period != Period.Day && stored != null && nowSeconds() - (since ?: nowSeconds()) < 2 * 24 * 3600L
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) { LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 420.dp),
            contentPadding = cappedPadding(1200.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val span: (LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
            item(span = span) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Period.entries.forEach { p ->
                            SegmentedButton(
                                period == p, { period = p }, SegmentedButtonDefaults.itemShape(p.ordinal, Period.entries.size),
                            ) { Text(stringResource(p.label)) }
                        }
                    }
                    if (!dayFailed && !nothingSaved) Legend(
                        predicted = outsideAhead.isNotEmpty() || forecasts.values.any { it.isNotEmpty() },
                        outside = hasOutside,
                    )
                    if (period != Period.Day && !nothingSaved) since?.let {
                        Text(
                            stringResource(Res.string.stored_since, dayMonth(it)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (dayFailed) item(span = span) { Message(error!!, Modifier.fillMaxWidth().padding(top = 48.dp)) { attempt++ } }
            else if (nothingSaved) item(span = span) {
                Message(stringResource(Res.string.no_history), Modifier.fillMaxWidth().padding(top = 48.dp), ChartIcon, onRetry = null)
            }
            else itemsIndexed(rows, key = { _, it -> it.first.id }) { i, (room, points) ->
                PressCard(
                    { onRoom(room.id) }, Modifier.springIn(i).fillMaxWidth().shared("room-${room.id}"),
                    colors = CardDefaults.cardColors(containerColor = roomContainer(room.id)),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleMediumEmphasized)
                        if (points == null) Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) { LoadingIndicator() }
                        else if (points.size < 2 && !hasOutside) Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) {
                            Text( // one room without data while others have some
                                stringResource(if (period == Period.Day) Res.string.no_data else Res.string.no_history),
                                Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                        else {
                            if (points.size >= 2) StatsLine(points, MaterialTheme.typography.bodyMedium)
                            val temps = points.mapNotNull { it.current }
                            if (temps.isNotEmpty()) Text(
                                "${fmt1(temps.min())}–${fmt1(temps.max())}°", style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            TempChart(
                                points, Modifier.fillMaxWidth().height(if (hasOutside) 180.dp else 140.dp), shared, forecast = forecasts[room.id].orEmpty(),
                                outside = outside, outsideAhead = outsideAhead,
                            )
                        }
                    }
                }
            }
        } }
    }
}

@Composable
private fun containerHigh() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

/** Time spent under target, and how long the latest target raise took to reach. */
internal data class ChartStats(val belowSeconds: Long, val heatUpSeconds: Long?)

/** Readings within this of the target count as on target (sensor noise, controller hysteresis). */
internal const val BelowTolerance = 0.2

/** Longest step still treated as continuous; longer ones are holes in the data (app not run, server down). */
internal fun maxStep(pts: List<Point>): Long {
    val steps = pts.zipWithNext { a, b -> b.time - a.time }.sorted()
    return if (steps.isEmpty()) Long.MAX_VALUE else steps[steps.size / 2] * 3
}

internal fun chartStats(points: List<Point>): ChartStats {
    val h = points.filter { it.time > 0 }
    fun below(p: Point) = p.current != null && p.target != null && p.current < p.target - BelowTolerance
    val gap = maxStep(h)
    val belowSeconds = h.zipWithNext().sumOf { (a, b) -> if (below(a) && b.time - a.time <= gap) b.time - a.time else 0L }
    // Latest raise of the target by at least half a degree (or switching on) that left the room below target.
    val raise = (1 until h.size).lastOrNull { i ->
        val t = h[i].target ?: return@lastOrNull false
        (h[i - 1].target?.let { t >= it + 0.5 } ?: true) && below(h[i])
    }
    val heatUp = raise?.let { i ->
        h.drop(i).firstOrNull { it.target != null && it.current != null && it.current >= it.target - BelowTolerance }
            ?.let { it.time - h[i].time }
    }
    return ChartStats(belowSeconds, heatUp)
}

private fun duration(seconds: Long): String {
    val m = (seconds + 30) / 60
    return if (m < 60) "$m min" else "${m / 60} h${if (m % 60 > 0) " ${m % 60} min" else ""}"
}

@Composable
private fun StatsLine(points: List<Point>, style: TextStyle) {
    val s = remember(points) { chartStats(points) }
    Text(
        listOfNotNull(
            if (s.belowSeconds >= 60) stringResource(Res.string.below_for, duration(s.belowSeconds))
            else stringResource(Res.string.never_below),
            s.heatUpSeconds?.let { stringResource(Res.string.reached_in, duration(it)) },
        ).joinToString(" · "),
        style = style,
    )
}

@Composable
private fun Legend(predicted: Boolean = false, outside: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(Heat, stringResource(Res.string.legend_room))
        LegendItem(muted, stringResource(Res.string.legend_target))
        if (outside) LegendItem(Outside, stringResource(Res.string.legend_outside))
        if (predicted) LegendItem(Heat, stringResource(Res.string.legend_predicted), dashed = true)
        LegendItem(Heat.copy(alpha = BelowAlpha), stringResource(Res.string.legend_below), height = 10.dp)
    }
}

@Composable
private fun LegendItem(color: Color, label: String, height: androidx.compose.ui.unit.Dp = 3.dp, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, height)) {
            if (dashed) drawLine(color, Offset(0f, center.y), Offset(size.width, center.y), size.height, pathEffect = Dashes)
            else drawRect(color)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private const val BelowAlpha = 0.25f
private val Outside = Color(0xFF3D8BD4) // cold blue, apart from the orange room line in both themes
private val Dashes by lazy { PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) } // lazy: unit tests have no Skia

/**
 * The chart's y bands, top first: the [room] range alone, or with [outside] merged into it when they are close, or
 * both separately when at least [MinBreak]° apart, so 2° outside doesn't squash 23° inside into a flat line.
 */
internal fun axisBands(
    room: ClosedFloatingPointRange<Double>, outside: ClosedFloatingPointRange<Double>?,
): List<ClosedFloatingPointRange<Double>> = when {
    outside == null -> listOf(room)
    outside.endInclusive + MinBreak <= room.start -> listOf(room, outside) // winter: outside below
    room.endInclusive + MinBreak <= outside.start -> listOf(outside, room) // a hot summer day: outside above
    else -> listOf(minOf(room.start, outside.start)..maxOf(room.endInclusive, outside.endInclusive))
}

private const val MinBreak = 3.0

/** Padded y range over all points, at least 4° tall so half a degree of sensor noise stays flat. */
internal fun yRangeOf(points: List<Point>): ClosedFloatingPointRange<Double>? {
    val v = points.filter { it.time > 0 }.flatMap { listOfNotNull(it.current, it.target) }
    if (v.isEmpty()) return null
    val lo = floor(v.min()) - 0.5
    val hi = ceil(v.max()) + 0.5
    val pad = ((4.0 - (hi - lo)) / 2).coerceAtLeast(0.0)
    return (lo - pad)..(hi + pad)
}

internal fun forecastLine(history: List<Point>, forecast: List<Point>): List<Point> {
    val last = history.lastOrNull() ?: return forecast
    val future = forecast.filter { it.time > last.time }
    return if (future.isEmpty()) emptyList() else listOf(last) + future
}

/**
 * Room temperature (orange) and target (grey) over time; time below target is shaded, holes in the data stay empty.
 * [outside] (points carrying the temperature in [Point.current]) is drawn thin and blue behind them on the same scale;
 * when it is far from the room's range, the empty degrees between them are cut out (see [axisBands]).
 * Pass [yRange] to share one scale across several charts. A [forecast] continues both lines dashed after the last reading.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun TempChart(
    points: List<Point>, modifier: Modifier, yRange: ClosedFloatingPointRange<Double>? = null,
    interactive: Boolean = false, // press or drag to read a value; off where the chart sits on a tappable card
    forecast: List<Point> = emptyList(),
    outside: List<Point> = emptyList(), outsideAhead: List<Point> = emptyList(),
) {
    val pts = remember(points) { points.filter { it.time > 0 } } // the History tab recomposes on every poll
    val ahead = remember(pts, forecast) { forecastLine(pts, forecast.filter { it.time > 0 }) }
    val outAhead = remember(outside, outsideAhead) { forecastLine(outside, outsideAhead) }
    val outRange = remember(outside, outAhead) { yRangeOf(outside + outAhead) }
    val roomRange = yRange ?: yRangeOf(pts + ahead)
    val range = roomRange ?: outRange
    if ((pts.size < 2 && outRange == null) || range == null) {
        Box(modifier, Alignment.Center) { Text(stringResource(Res.string.no_data)) }
        return
    }
    val bands = axisBands(range, outRange.takeIf { roomRange != null })

    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // From the card's content colour, so the lines show on every room colour (outlineVariant vanished on the blue ones).
    val grid = LocalContentColor.current.copy(alpha = 0.22f)
    val label = TextStyle(fontSize = 10.sp, color = muted)

    val times = remember(pts, ahead, outside, outAhead) { (pts + ahead + outside + outAhead).map { it.time } }
    val t0 = times.min()
    val t1 = times.max().coerceAtLeast(t0 + 1)
    val pairs = remember(pts) { val gap = maxStep(pts); pts.zipWithNext().filter { (a, b) -> b.time - a.time <= gap } }
    val surface = MaterialTheme.colorScheme.inverseSurface
    val onSurface = MaterialTheme.colorScheme.inverseOnSurface
    var touchX by remember { mutableStateOf<Float?>(null) }
    val touch = if (!interactive) Modifier else Modifier
        .pointerInput(Unit) { detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null }) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { touchX = it.x }, onDragEnd = { touchX = null }, onDragCancel = { touchX = null },
            ) { change, _ -> touchX = change.position.x }
        }

    Canvas(modifier.then(touch)) {
        val left = 32.dp.toPx()
        val right = size.width
        val bottom = size.height - 16.dp.toPx()
        fun x(t: Long) = left + (right - left) * (t - t0) / (t1 - t0).toFloat()
        // Bands top to bottom with a small gap between them. Heights follow each band's degrees (one scale), except the
        // room's band never gets under 55% or over 75%: it is what the card is about.
        val gap = if (bands.size == 2) 12.dp.toPx() else 0f
        val spans = bands.map { it.endInclusive - it.start }
        val roomShare = (spans[bands.indexOf(range).coerceAtLeast(0)] / spans.sum()).coerceIn(0.55, 0.75)
        val topH = if (bands.size == 1) bottom else ((bottom - gap) * if (bands[0] == range) roomShare else 1 - roomShare).toFloat()
        val tops = listOf(0f, topH + gap)
        val bots = listOf(topH, bottom)
        fun inBand(i: Int, v: Double) = bots[i] - ((v - bands[i].start) / spans[i] * (bots[i] - tops[i])).toFloat()
        fun y(v: Double) = when {
            bands.size == 1 || v >= bands[0].start -> inBand(0, v)
            v <= bands[1].endInclusive -> inBand(1, v)
            else -> topH + gap / 2 // inside the cut-out degrees
        }

        // Horizontal grid with whole-degree labels per band, at least 16 dp apart.
        bands.forEachIndexed { i, band ->
            val perDegree = (bots[i] - tops[i]) / spans[i]
            val step = listOf(1, 2, 5, 10, 20).firstOrNull { it * perDegree >= 16.dp.toPx() } ?: 50
            var v = ceil(band.start / step).toInt() * step
            while (v <= band.endInclusive) {
                drawLine(grid, Offset(left, y(v.toDouble())), Offset(right, y(v.toDouble())), 1f)
                drawText(measurer, "$v°", Offset(0f, y(v.toDouble()) - 7.dp.toPx()), label)
                v += step
            }
        }
        // The break: a zigzag across the cut-out degrees, from card edge to card edge. The Canvas doesn't clip, so it
        // reaches past the chart into the card's 20 dp padding; the card's shape cuts it off at its edges.
        if (bands.size == 2) {
            val zig = Path()
            val mid = topH + gap / 2
            val tooth = 6.dp.toPx()
            val bleed = 20.dp.toPx()
            var zx = -bleed
            zig.moveTo(zx, mid)
            while (zx < size.width + bleed) { zx += tooth; zig.lineTo(zx, mid + if (((zx + bleed) / tooth).toInt() % 2 == 1) -gap / 4 else gap / 4) }
            drawPath(zig, grid, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }

        // Time labels on whole local hours (00, 06, 12, 18 for a day) or days, at most 5 of them.
        val hours = (listOf(1, 2, 3, 6, 12, 24, 48, 72, 168).firstOrNull { (t1 - t0) / (it * 3600L) <= 5 } ?: 336) * 3600L
        val off = TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochSeconds(t0)).totalSeconds.toLong()
        var t = ((t0 + off + hours - 1) / hours) * hours - off
        while (t <= t1) {
            val text = if (hours >= 24 * 3600L) dayMonth(t) else hhmm(t)
            val w = measurer.measure(text, label).size.width
            val px = (x(t) - w / 2f).coerceIn(left, right - w)
            drawLine(grid, Offset(x(t), bottom), Offset(x(t), bottom + 2.dp.toPx()), 1f)
            drawText(measurer, text, Offset(px, bottom + 2.dp.toPx()), label)
            t += hours
        }

        // Shade where the room sat below target. ponytail: segment-wise quads, crossings are approximated (fine at 5-min points).
        val below = Path()
        pairs.forEach { (a, b) ->
            val ca = a.current ?: return@forEach; val cb = b.current ?: return@forEach
            val ta = a.target ?: return@forEach; val tb = b.target ?: return@forEach
            if (ca >= ta - BelowTolerance && cb >= tb - BelowTolerance) return@forEach
            below.moveTo(x(a.time), y(minOf(ca, ta)))
            below.lineTo(x(b.time), y(minOf(cb, tb)))
            below.lineTo(x(b.time), y(tb))
            below.lineTo(x(a.time), y(ta))
            below.close()
        }
        drawPath(below, Heat.copy(alpha = BelowAlpha))

        // Outside: thin, behind the room's lines.
        if (outRange != null) {
            val step = maxStep(outside)
            val thin = 1.5.dp.toPx()
            series(outside.zipWithNext().filter { (a, b) -> b.time - a.time <= step }, { it.current }, Outside, ::x, ::y, width = thin)
            series(outAhead.zipWithNext(), { it.current }, Outside, ::x, ::y, Dashes, thin)
            (outAhead.singleOrNull() ?: outside.singleOrNull()?.takeIf { outAhead.isEmpty() })?.let { p ->
                p.current?.let { drawCircle(Outside, thin, Offset(x(p.time), y(it))) }
            }
        }

        series(pairs, { it.target }, muted, ::x, ::y)
        series(pairs, { it.current }, Heat, ::x, ::y)
        val next = ahead.zipWithNext()
        series(next, { it.target }, muted, ::x, ::y, Dashes)
        series(next, { it.current }, Heat, ::x, ::y, Dashes)

        // Crosshair on the reading nearest the finger.
        touchX?.let { tx ->
            val p = pts.minByOrNull { kotlin.math.abs(x(it.time) - tx) } ?: return@let
            val px = x(p.time)
            drawLine(muted, Offset(px, 0f), Offset(px, bottom), 1.dp.toPx())
            p.current?.let { drawCircle(Heat, 4.dp.toPx(), Offset(px, y(it))) }
            val text = listOfNotNull(hhmm(p.time), p.current?.let { "${fmt1(it)}°" }, p.target?.let { "→ ${fmt1(it)}°" }).joinToString("  ")
            val tl = measurer.measure(text, label.copy(color = onSurface, fontWeight = FontWeight.Medium))
            val pad = 6.dp.toPx()
            val bx = (px - tl.size.width / 2f - pad).coerceIn(left, size.width - tl.size.width - 2 * pad)
            drawRoundRect(
                surface, Offset(bx, 0f), Size(tl.size.width + 2 * pad, tl.size.height + pad),
                androidx.compose.ui.geometry.CornerRadius(pad),
            )
            drawText(tl, topLeft = Offset(bx + pad, pad / 2))
        }
    }
}

private fun DrawScope.series(
    pairs: List<Pair<Point, Point>>, value: (Point) -> Double?, color: Color,
    x: (Long) -> Float, y: (Double) -> Float, effect: PathEffect? = null, width: Float = 2.dp.toPx(),
) {
    pairs.forEach { (a, b) ->
        val va = value(a) ?: return@forEach
        val vb = value(b) ?: return@forEach
        drawLine(
            color, Offset(x(a.time), y(va)), Offset(x(b.time), y(vb)),
            strokeWidth = width, pathEffect = effect,
        )
    }
}

@OptIn(ExperimentalTime::class)
private fun hhmm(epochSeconds: Long): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return LocalTime(t.hour, t.minute).toString() // "HH:mm" (seconds are left out when zero)
}

@OptIn(ExperimentalTime::class)
internal fun dayMonth(epochSeconds: Long): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.day}.${t.month.ordinal + 1}."
}
