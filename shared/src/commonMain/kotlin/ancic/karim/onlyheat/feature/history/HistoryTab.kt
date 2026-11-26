package ancic.karim.onlyheat.feature.history

import ancic.karim.onlyheat.data.Point
import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.deg
import ancic.karim.onlyheat.data.fmt1
import ancic.karim.onlyheat.data.nowSeconds
import ancic.karim.onlyheat.data.outside
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.ChartIcon
import ancic.karim.onlyheat.ui.Heat
import ancic.karim.onlyheat.ui.Message
import ancic.karim.onlyheat.ui.PressCard
import ancic.karim.onlyheat.ui.SubScreen
import ancic.karim.onlyheat.ui.cappedPadding
import ancic.karim.onlyheat.ui.fadeThrough
import ancic.karim.onlyheat.ui.roomColors
import ancic.karim.onlyheat.ui.shared
import ancic.karim.onlyheat.ui.springIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** How far back the History tab looks. The day comes from the server, longer spans from the local [history]. */
private enum class Period(val seconds: Long, val bucket: Long, val label: StringResource) {
    Day(24 * 3600L, 0, Res.string.range_day),
    Week(7 * 24 * 3600L, 30 * 60L, Res.string.range_week), // 30-min averages: ~340 points
    Month(30 * 24 * 3600L, 2 * 3600L, Res.string.range_month), // 2-h averages: ~360 points
}

/** All rooms over the last day (from /getSmartRoomLogsLast24h), week or month (stored on this device). */
@Composable
internal fun HistoryTab(
    model: HistoryViewModel,
    rooms: List<Room>?, // from the home poll, so week/month work even when the day call fails
    onRoom: (String) -> Unit,
) {
    val days = model.days
    val error = model.error
    var attempt by remember { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(Period.Day) }
    // Another period's readings (kept since the last visit) count as not loaded yet, never as this one's.
    val stored = model.stored?.takeIf { it.seconds == period.seconds }
    LaunchedEffect(attempt) { model.loadDay() } // refreshes on every visit; the last day shows meanwhile
    val ids = rooms?.map { it.id } // stable across polls, unlike the list itself
    LaunchedEffect(period, ids) {
        if (ids != null && period != Period.Day) model.loadStored(ids, period.seconds, period.bucket)
    }

    SubScreen(stringResource(Res.string.history), onBack = null) { pad ->
        if (rooms == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }; return@SubScreen }
        val dayFailed = period == Period.Day && days == null && error != null
        val rows = rooms.map { r ->
            r to if (period == Period.Day) days?.find { it.roomId == r.id }?.points.orEmpty().takeIf { days != null }
            else stored?.rooms?.get(r.id)
        }
        val forecasts = if (period == Period.Day) days.orEmpty().associate { it.roomId to it.forecast } else emptyMap()
        val outside = if (period == Period.Day) days.orEmpty().flatMap { it.points }.outside() else stored?.outside.orEmpty()
        val outsideAhead = if (period == Period.Day) days.orEmpty().flatMap { it.forecast }.outside() else emptyList()
        val hasOutside = outside.isNotEmpty() || outsideAhead.isNotEmpty()
        // One scale so rooms compare at a glance.
        val shared = yRangeOf(rows.flatMap { it.second.orEmpty() } + forecasts.values.flatten())
        // Under 2 days saved, the week/month view would only repeat the 24 h tab: say so instead.
        val since = stored?.since
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
                    // The refresh failed but the last day is still shown: say it may be out of date.
                    if (period == Period.Day && days != null) error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (period != Period.Day && !nothingSaved) since?.let {
                        Text(
                            stringResource(Res.string.stored_since, dayMonth(it)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (dayFailed) item(span = span) { Message(error, Modifier.fillMaxWidth().padding(top = 48.dp)) { attempt++ } }
            else if (nothingSaved) item(span = span) {
                Message(stringResource(Res.string.no_history), Modifier.fillMaxWidth().padding(top = 48.dp), ChartIcon, onRetry = null)
            }
            else itemsIndexed(rows, key = { _, it -> it.first.id }) { i, (room, points) ->
                val (container, ink) = roomColors(room.id)
                PressCard(
                    { onRoom(room.id) }, Modifier.springIn(i).fillMaxWidth().shared("room-${room.id}"),
                    colors = CardDefaults.cardColors(containerColor = container, contentColor = ink),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleMediumEmphasized)
                        // The chart's data rides in the state, so the outgoing period fades out with its own readings.
                        val chart = CardChart(period, points, forecasts[room.id].orEmpty(), outside, outsideAhead, shared)
                        AnimatedContent(
                            chart, contentKey = { it.period to (it.points == null) }, transitionSpec = { fadeThrough() },
                        ) { c -> CardChartBody(c) }
                    }
                }
            }
        } }
    }
}

private class CardChart(
    val period: Period, val points: List<Point>?, val forecast: List<Point>,
    val outside: List<Point>, val outsideAhead: List<Point>, val range: ClosedFloatingPointRange<Double>?,
) { val hasOutside get() = outside.isNotEmpty() || outsideAhead.isNotEmpty() }

@Composable
private fun CardChartBody(c: CardChart) {
    val points = c.points
    if (points == null) Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) { LoadingIndicator() }
    else if (points.size < 2 && !c.hasOutside) Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) {
        Text( // one room without data while others have some
            stringResource(if (c.period == Period.Day) Res.string.no_data else Res.string.no_history),
            Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
    }
    else Column {
        if (points.size >= 2) StatsLine(points, MaterialTheme.typography.bodyMedium)
        val temps = points.mapNotNull { it.current }
        if (temps.isNotEmpty()) Text(
            "${fmt1(temps.min())}–${fmt1(temps.max())}°", style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        TempChart(
            points, Modifier.fillMaxWidth().height(if (c.hasOutside) 180.dp else 140.dp), c.range, forecast = c.forecast,
            outside = c.outside, outsideAhead = c.outsideAhead,
        )
    }
}

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
internal fun StatsLine(points: List<Point>, style: TextStyle) {
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
internal fun Legend(predicted: Boolean = false, outside: Boolean = false) {
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
            val text = listOfNotNull(hhmm(p.time), p.current?.let { deg(it) }, p.target?.let { "→ ${deg(it)}" }).joinToString("  ")
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
internal fun hhmm(epochSeconds: Long): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return LocalTime(t.hour, t.minute).toString() // "HH:mm" (seconds are left out when zero)
}

@OptIn(ExperimentalTime::class)
internal fun dayMonth(epochSeconds: Long): String {
    val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.day}.${t.month.ordinal + 1}."
}
