package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    onBoost: (minutes: Int) -> Unit, onStopBoost: () -> Unit, onBack: () -> Unit,
) {
    var log by remember { mutableStateOf<List<Point>?>(null) }
    var smart by remember { mutableStateOf<Smart?>(null) }
    var logError by remember { mutableStateOf<String?>(null) }
    var smartError by remember { mutableStateOf<String?>(null) }

    // The log has 5-minute points, so reload at that pace while the screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(room.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                attempt { api.roomLog(key, room.id).also { storeLog(room.id, it) } }
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
            val heating = room.heating && room.target != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hero: the temperature sits in a blob that turns into a spinning cookie, with fire inside, while heating.
                MorphBlob(
                    heating,
                    roomContainer(room.id),
                    Modifier.size(184.dp),
                ) {
                    Box(Modifier.matchParentSize().flames(heating, room.id)) // same fire as the list card, clipped to the blob
                    RollingTemp(
                        room.current, MaterialTheme.typography.displayMediumEmphasized,
                        color = contentColorFor(roomContainer(room.id)),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column {
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

            BoostCard(boost, onBoost, onStopBoost)
            SmartCard(api, key, room, smart, smartError) { smart = it }
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
                    FilledTonalButton({ onBoost(min) }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Text(stringResource(label)) }
                }
            }
        }
    }
}

@Composable
private fun SmartCard(api: HeatApi, key: String, room: Room, smart: Smart?, loadError: String?, onSmart: (Smart) -> Unit) {
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
                stringResource(Res.string.smart_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            if (smart != null) {
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
    var days by cache
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(Period.Day) }
    var stored by remember { mutableStateOf<Map<String, List<Point>>?>(null) } // per room, for week/month
    var since by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(attempt) {
        error = null
        attempt { days = api.last24h(key) }.onFailure { error = errorText(it) }
    }
    val ids = rooms?.map { it.id } // stable across polls, unlike the list itself
    LaunchedEffect(period, ids) {
        if (ids == null || period == Period.Day) return@LaunchedEffect
        suspend fun read() {
            val from = nowSeconds() - period.seconds
            stored = ids.associateWith { id -> history.averaged(id, from, period.bucket).map { it.point() } }
            since = history.oldest()
        }
        read() // what we have, right away
        attempt { syncHistory(api, key, every = 30.minutes) }.onSuccess { read() } // then top up from the server
    }

    SubScreen(stringResource(Res.string.history), onBack = null) { pad ->
        if (rooms == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }; return@SubScreen }
        val dayFailed = period == Period.Day && days == null && error != null
        val rows = rooms.map { r ->
            r to if (period == Period.Day) days?.find { it.roomId == r.id }?.points.orEmpty().takeIf { days != null }
            else stored?.get(r.id)
        }
        val shared = yRangeOf(rows.flatMap { it.second.orEmpty() }) // one scale so rooms compare at a glance
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
                    if (!dayFailed && !nothingSaved) Legend()
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
                        else if (points.size < 2) Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) {
                            Text( // one room without data while others have some
                                stringResource(if (period == Period.Day) Res.string.no_data else Res.string.no_history),
                                Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                        else {
                            StatsLine(points, MaterialTheme.typography.bodyMedium)
                            val temps = points.mapNotNull { it.current }
                            if (temps.isNotEmpty()) Text(
                                "${fmt1(temps.min())}–${fmt1(temps.max())}°", style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            TempChart(points, Modifier.fillMaxWidth().height(140.dp), shared)
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
private fun Legend() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(Heat, stringResource(Res.string.legend_room))
        LegendItem(muted, stringResource(Res.string.legend_target))
        LegendItem(Heat.copy(alpha = BelowAlpha), stringResource(Res.string.legend_below), height = 10.dp)
    }
}

@Composable
private fun LegendItem(color: Color, label: String, height: androidx.compose.ui.unit.Dp = 3.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, height)) { drawRect(color) }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private const val BelowAlpha = 0.25f

/** Padded y range over all points, at least 4° tall so half a degree of sensor noise stays flat. */
internal fun yRangeOf(points: List<Point>): ClosedFloatingPointRange<Double>? {
    val v = points.filter { it.time > 0 }.flatMap { listOfNotNull(it.current, it.target) }
    if (v.isEmpty()) return null
    val lo = floor(v.min()) - 0.5
    val hi = ceil(v.max()) + 0.5
    val pad = ((4.0 - (hi - lo)) / 2).coerceAtLeast(0.0)
    return (lo - pad)..(hi + pad)
}

/**
 * Room temperature (orange) and target (grey) over time; time below target is shaded, holes in the data stay empty.
 * Pass [yRange] to share one scale across several charts.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun TempChart(
    points: List<Point>, modifier: Modifier, yRange: ClosedFloatingPointRange<Double>? = null,
    interactive: Boolean = false, // press or drag to read a value; off where the chart sits on a tappable card
) {
    val pts = remember(points) { points.filter { it.time > 0 } } // the History tab recomposes on every poll
    val range = yRange ?: yRangeOf(pts)
    if (pts.size < 2 || range == null) { Box(modifier, Alignment.Center) { Text(stringResource(Res.string.no_data)) }; return }

    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = TextStyle(fontSize = 10.sp, color = muted)

    val yMin = range.start
    val yMax = range.endInclusive
    val t0 = pts.first().time // a young history fills the width; the header says how far back it goes
    val t1 = pts.last().time
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
        val bottom = size.height - 16.dp.toPx()
        fun x(t: Long) = left + (size.width - left) * (t - t0) / (t1 - t0).toFloat()
        fun y(v: Double) = (bottom * (1 - (v - yMin) / (yMax - yMin))).toFloat()

        // Horizontal grid with whole-degree labels (at most ~5 lines).
        val span = (yMax - yMin).toInt().coerceAtLeast(1)
        val step = ((span + 3) / 4).coerceAtLeast(1)
        var v = ceil(yMin).toInt()
        while (v <= yMax) {
            drawLine(grid, Offset(left, y(v.toDouble())), Offset(size.width, y(v.toDouble())), 1f)
            drawText(measurer, "$v°", Offset(0f, y(v.toDouble()) - 7.dp.toPx()), label)
            v += step
        }

        // Time labels on whole local hours (00, 06, 12, 18 for a day) or days, at most 5 of them.
        val hours = (listOf(1, 2, 3, 6, 12, 24, 48, 72, 168).firstOrNull { (t1 - t0) / (it * 3600L) <= 5 } ?: 336) * 3600L
        val off = TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochSeconds(t0)).totalSeconds.toLong()
        var t = ((t0 + off + hours - 1) / hours) * hours - off
        while (t <= t1) {
            val text = if (hours >= 24 * 3600L) dayMonth(t) else hhmm(t)
            val w = measurer.measure(text, label).size.width
            val px = (x(t) - w / 2f).coerceIn(left, size.width - w)
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

        series(pairs, { it.target }, muted, ::x, ::y)
        series(pairs, { it.current }, Heat, ::x, ::y)

        // Crosshair on the reading nearest the finger.
        touchX?.let { tx ->
            val p = pts.minBy { kotlin.math.abs(x(it.time) - tx) }
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
    x: (Long) -> Float, y: (Double) -> Float,
) {
    pairs.forEach { (a, b) ->
        val va = value(a) ?: return@forEach
        val vb = value(b) ?: return@forEach
        drawLine(
            color, Offset(x(a.time), y(va)), Offset(x(b.time), y(vb)),
            strokeWidth = 2.dp.toPx(),
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
