package ancic.karim.onlyheat.ui

import ancic.karim.onlyheat.ac.AcView
import ancic.karim.onlyheat.data.Room
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random


private const val LOOP_MS = 6000
private const val TAU = (2 * PI).toFloat()

/** The fade and the endless clock the animations here share. [a]: the fade, 0..1 ([raw] may overshoot with a spring);
 *  [t]: 0..1 through the loop, offset per [seed] so neighbouring cards don't move in lockstep. */
private class Loop(private val fade: State<Float>, private val clock: State<Float>, private val offset: Float) {
    val raw get() = fade.value
    val a get() = fade.value.coerceIn(0f, 1f)
    val t get() = (clock.value + offset) % 1f
}

/** Null once faded out, so nothing runs while off. Only whether it shows is read here; the fade and the clock are read
 *  while drawing, so a fade redraws the card instead of recomposing it on every frame. */
@Composable
private fun loop(on: Boolean, seed: Any?, loopMs: Int = LOOP_MS, spec: AnimationSpec<Float> = MaterialTheme.motionScheme.slowEffectsSpec()): Loop? {
    val fade = animateFloatAsState(if (on) 1f else 0f, spec)
    val shown by remember(fade) { derivedStateOf { fade.value > 0.001f } }
    if (!shown) return null
    val clock = rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(loopMs, easing = LinearEasing)))
    return Loop(fade, clock, (seed.hashCode() * 0.618034f % 1f + 1f) % 1f) // golden ratio spreads close ids (1, 2, 3) far apart
}

// Back to front: deep orange, orange, amber. Flat tonal layers, no blur: the expressive look.
private val Layers = listOf(
    Triple(Color(0xFFE64A00), 0.30f, 0.46f), // color, alpha, max height (fraction of card)
    Triple(Color(0xFFFF8A00), 0.32f, 0.30f),
    Triple(Color(0xFFFFC14D), 0.36f, 0.15f),
)
private val Spark = Color(0xFFFFD27A)

/**
 * Fire rising from the bottom edge while [on]: layered flame tongues plus sparks shaped like
 * M3 expressive shapes. It springs up with the theme's expressive motion and sinks away when off.
 * Apply inside a card (not on it) so the card's shape clips it.
 * [seed] offsets the timing so neighbouring cards don't flicker in lockstep.
 * The frame loop only runs while visible, so idle cards cost nothing.
 */
@Composable
internal fun Modifier.flames(on: Boolean, seed: Any? = null, boost: Boolean = false): Modifier {
    val lift = animateFloatAsState(if (boost) 1.4f else 1f, MaterialTheme.motionScheme.slowSpatialSpec()) // boost: taller fire
    val loop = loop(on, seed, spec = MaterialTheme.motionScheme.slowSpatialSpec()) ?: return this
    val sparks = listOf( // remembered unit-square paths
        MaterialShapes.Sunny.toPath(), MaterialShapes.Cookie4Sided.toPath(),
        MaterialShapes.SoftBurst.toPath(), MaterialShapes.Flower.toPath(),
    )
    return drawBehind { // read in draw: redraws, no recomposition
        scale(1f, lift.value, pivot = Offset(size.width / 2, size.height)) { drawFire(loop.t, loop.raw, sparks) }
    }
}

/** What a room is doing, read alike by its card, its detail hero and [roomAnimations]. */
internal class RoomActivity(room: Room, ac: AcView?) {
    val floor = room.heating && !room.cooling && room.target != null
    val acOn = ac?.state?.power == true
    val acHeating = ac?.state?.heating == true
    val acCooling = ac?.state?.cooling == true
    val awake = room.target != null || acOn
    /** The AC hasn't answered yet: hold the animations so they don't jump once it does. */
    val waiting = ac?.loaded == false
}

/**
 * A room's animations, the same on its card and in its detail hero: fire while the floor or the AC heats, snow while
 * the AC cools, the tug of war while they fight, bubbles while on but resting, night when everything is off, and frost
 * when it's cold.
 */
@Composable
internal fun Modifier.roomAnimations(
    room: Room, ac: AcView?, cold: Boolean, ink: Color, boost: Boolean = false,
    bubbles: Float = 0.10f, moon: Offset = Offset(0.70f, 0.56f), zTo: Offset = Offset(0.86f, 0.48f),
): Modifier {
    with(RoomActivity(room, ac)) {
        return flames((floor || acHeating) && !(floor && acCooling) && !waiting, room.id, boost)
            .chill(acCooling && !floor, room.id) // the AC's cold air: snow from the vent
            .tug(floor && acCooling, room.id) // floor heating against AC cooling: warm and cool pushing at each other
            .drift(awake && !floor && !acHeating && !acCooling && !waiting, ink.copy(alpha = bubbles), room.id) // at target
            .night(!awake && !cold && !waiting, ink, room.id, moon, zTo) // off: moon, stars, z's
            .frost(cold, room.id) // below the cold-alert temperature: ice on glass
    }
}

/**
 * Slow warm bubbles rising while [on], turning and fading in and out.
 * Same cost rules as [flames]: nothing runs while off.
 */
@Composable
internal fun Modifier.drift(on: Boolean, color: Color, seed: Any? = null): Modifier {
    val loop = loop(on, seed, LOOP_MS * 3) ?: return this
    val shapes = listOf(MaterialShapes.Circle.toPath(), MaterialShapes.Cookie4Sided.toPath(), MaterialShapes.Pill.toPath())
    return drawBehind {
        val t = loop.t
        val a = loop.a
        val (w, h) = size
        repeat(6) { i ->
            val p = (t * (1 + i % 2) + i * 0.618f) % 1f // whole-number speeds: loops seamlessly
            val s = (12f + i % 3 * 6f).dp.toPx()
            val x = s + (w - 2 * s) * ((i * 0.381f + 0.08f) % 1f) + sin(p * TAU * 2 + i) * 6.dp.toPx()
            val y = s + (h - 2 * s) * (1 - p) // fades in and out inside the card
            translate(x - s / 2, y - s / 2) {
                rotate(p * 180f * (if (i % 2 == 0) 1 else -1), pivot = Offset(s / 2, s / 2)) {
                    scale(s, s, pivot = Offset.Zero) {
                        drawPath(shapes[i % shapes.size], color.copy(alpha = color.alpha * sin(p * PI.toFloat()) * a))
                    }
                }
            }
        }
    }
}

private val Moon = Color(0xFFE3E8F7)
private val Crater = Color(0xFFB4BCD8)
private val MoonFace = Color(0xFF5A6390)
private val Blush = Color(0xFFF2A7C0)
private val Dusk = Color(0xFF3F51B5)

/**
 * Night while [on] (room off, resting): a dusk tint from the top, a bobbing crescent moon asleep (face in profile),
 * twinkling four-point stars and sleepy "z"s drifting from the moon. [ink] draws the z's.
 * [moon] and [zTo] are fractions of the size: where the moon sits and where the z's fade out.
 */
@Composable
internal fun Modifier.night(
    on: Boolean, ink: Color, seed: Any? = null,
    moon: Offset = Offset(0.70f, 0.56f), // room card: below the switch, left of "Off"
    zTo: Offset = Offset(0.86f, 0.48f), // room card: under the switch, above "Off"
): Modifier {
    val loop = loop(on, seed) ?: return this
    return drawWithCache {
        val (w, h) = size
        val dp = 1.dp.toPx()
        val rnd = Random(seed.hashCode())
        val stars = List(10) { Offset(w * rnd.nextFloat(), h * rnd.nextFloat()) to (3f + rnd.nextFloat() * 4f) * dp }
        val r = 22 * dp
        val face = Path() // rebuilt each frame: the face breathes
        val dust = listOf(Offset(-0.62f, -0.45f), Offset(-0.25f, -0.72f), Offset(-0.82f, 0.08f), Offset(-0.6f, 0.58f), Offset(-0.12f, 0.78f)) // tiny stars on the moon
        val halo = Brush.radialGradient(listOf(Moon.copy(alpha = 0.2f), Color.Transparent), Offset.Zero, r * 2.6f)
        val moonAt = Offset(w * moon.x, h * moon.y)
        val dusk = Brush.verticalGradient(listOf(Dusk.copy(alpha = 0.14f), Color.Transparent), endY = h)
        onDrawBehind {
            val a = loop.a
            val t = loop.t
            drawRect(dusk, alpha = a)
            stars.forEachIndexed { i, (p, size) ->
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin((t * (1 + i % 3) + i * 0.37f) * TAU))
                drawPath(star(p, size * (0.7f + 0.3f * tw)), Moon.copy(alpha = 0.55f * tw * a))
            }
            val bob = Offset(0f, sin(t * TAU) * 3 * dp)
            // Two slow breaths per loop, one per z: lips part on the way out, the nose lifts on the way in.
            val out = 0.5f + 0.5f * sin(t * TAU * 2)
            face.rewind()
            face.sleepingCrescent(r, out)
            translate(moonAt.x + bob.x, moonAt.y + bob.y) {
                drawCircle(halo, r * 2.6f, Offset.Zero, alpha = a)
                drawPath(face, Moon.copy(alpha = a))
                clipPath(face) {
                    drawCircle(Crater.copy(alpha = 0.45f * a), r - 1.5f * dp, Offset.Zero, style = Stroke(3 * dp)) // shaded rim
                    dust.forEach { drawPath(star(it * r, 0.09f * r), Crater.copy(alpha = 0.9f * a)) }
                }
                // Closed eye: a downward arc with lashes hanging off it, and a blushing cheek below.
                val eye = Offset(-0.42f * r, 0.02f * r)
                val ew = 0.36f * r
                drawArc(MoonFace.copy(alpha = a), 10f, 160f, false, eye - Offset(ew / 2, ew * 0.35f), Size(ew, ew * 0.7f), style = Stroke(1.5f * dp, cap = StrokeCap.Round))
                for (k in 0..4) {
                    val ang = (30f + k * 30f) * PI.toFloat() / 180f
                    val from = eye + Offset(cos(ang) * ew / 2, sin(ang) * ew * 0.35f)
                    drawLine(MoonFace.copy(alpha = a), from, from + Offset(cos(ang), sin(ang)) * (0.08f * r), 1.1f * dp, StrokeCap.Round)
                }
                drawCircle(Blush.copy(alpha = 0.55f * a), 0.14f * r, Offset(-0.55f * r, 0.42f * r))
            }
            val zFrom = moonAt + Offset(r * 0.5f, -r * 0.5f) // out of the crescent's hollow
            val zEnd = Offset(w * zTo.x, h * zTo.y)
            repeat(3) { i ->
                val p = (t * 2 + i / 3f) % 1f
                val zs = (2.5f + p * 2.5f) * dp
                val c = zFrom + (zEnd - zFrom) * p + Offset(0f, sin(p * TAU) * 2 * dp)
                val z = Path().apply {
                    moveTo(c.x - zs, c.y - zs); lineTo(c.x + zs, c.y - zs); lineTo(c.x - zs, c.y + zs); lineTo(c.x + zs, c.y + zs)
                }
                drawPath(z, ink.copy(alpha = 0.5f * sin(p * PI.toFloat()) * a), style = Stroke(1.6f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * A crescent opening right, centred on 0,0 with radius [r]. Its inner edge is a face in profile:
 * forehead, nose, lips and chin, like a storybook moon.
 */
private fun Path.sleepingCrescent(r: Float, out: Float) {
    val inn = 1 - out
    moveTo(0.45f * r, -0.89f * r) // top tip
    arcTo(Rect(Offset.Zero, r), -63f, -244f, false) // round the back to the bottom tip (0.6, 0.8)
    cubicTo(0.35f * r, 0.62f * r, 0.1f * r, 0.55f * r, 0.06f * r, 0.44f * r) // chin
    quadraticTo((0.15f + 0.03f * out) * r, (0.39f + 0.015f * out) * r, 0.08f * r, (0.335f + 0.015f * out) * r) // lower lip, pouts out
    lineTo((0.08f - 0.06f * out) * r, 0.305f * r) // mouth: a notch that opens on the out-breath
    lineTo(0.08f * r, (0.275f - 0.015f * out) * r)
    quadraticTo((0.16f + 0.03f * out) * r, (0.255f - 0.01f * out) * r, 0.06f * r, 0.21f * r) // upper lip
    quadraticTo(0.05f * r, 0.15f * r, 0.13f * r, 0.08f * r) // under the nose
    quadraticTo((0.19f + 0.025f * inn) * r, (0.02f - 0.015f * inn) * r, 0.06f * r, -0.03f * r) // nose tip, lifts breathing in
    quadraticTo(-0.02f * r, -0.07f * r, -0.04f * r, -0.16f * r) // bridge
    cubicTo(-0.1f * r, -0.45f * r, 0.1f * r, -0.78f * r, 0.45f * r, -0.89f * r) // forehead up to the tip
    close()
}

/** A four-point sparkle: diamond with sides pulled in toward the center. */
private fun star(c: Offset, r: Float) = Path().apply {
    moveTo(c.x, c.y - r)
    quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r)
    quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r)
    close()
}

private val Ice = Color(0xFF8EC5F0)
private val Warm = Color(0xFFFF3030) // coral red: orange turns brown over the grey-blue cards
private val Cool = Color(0xFF4FB4FF)

/**
 * Snow drifting down from the top edge while [on] (the AC cooling), under a band of cool air from the vent.
 * Same cost rules as [flames].
 */
@Composable
internal fun Modifier.chill(on: Boolean, seed: Any? = null): Modifier {
    val loop = loop(on, seed, LOOP_MS * 2) ?: return this
    return drawWithCache {
      val (w, h) = size
      val dp = 1.dp.toPx()
      val band = Brush.verticalGradient(listOf(Ice.copy(alpha = 0.22f), Color.Transparent), endY = h * 0.45f)
      onDrawBehind {
        val t = loop.t
        val a = loop.a
        drawRect(band, alpha = a)
        repeat(10) { i ->
            val p = (t * (1 + i % 2) + i * 0.618f) % 1f // whole-number speeds: loops seamlessly
            val r = (4f + i % 3 * 1.5f) * dp
            val x = w * ((i * 0.381f + 0.03f) % 1f) + sin(p * TAU * 2 + i) * 8 * dp
            val y = -r + (h + 2 * r) * p
            val fade = sin(p * PI.toFloat()).coerceAtLeast(0f) // fades in at the top, out at the bottom
            val flakeA = 0.75f * a * fade
            if (flakeA > 0.01f) repeat(3) { k -> // six arms
                val ang = p * TAU * (if (i % 2 == 0) 1 else -1) + k * PI.toFloat() / 3
                val d = Offset(cos(ang), sin(ang)) * r
                drawLine(IceGlint.copy(alpha = flakeA), Offset(x, y) - d, Offset(x, y) + d, 1.5f * dp, StrokeCap.Round)
            }
        }
      }
    }
}

/**
 * Floor heating and the AC cooling at once, a tug of war: warm red up from the floor, cool blue down from the vent,
 * and the wavy line between them slowly pushed back and forth, with a faint shimmer. No particles: calm on purpose.
 */
@Composable
internal fun Modifier.tug(on: Boolean, seed: Any? = null): Modifier {
    val loop = loop(on, seed, LOOP_MS * 2) ?: return this
    return drawWithCache {
      val (w, h) = size
      val edge = Path()
      val warm = Path()
      // Built once over 0..1 and stretched to the moving line each frame, instead of a new gradient per frame.
      val coolFade = Brush.verticalGradient(listOf(Cool.copy(alpha = 0.34f), Cool.copy(alpha = 0.04f)), startY = 0f, endY = 1f)
      val warmFade = Brush.verticalGradient(listOf(Warm.copy(alpha = 0.08f), Warm.copy(alpha = 0.45f)), startY = 0f, endY = 1f)
      val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
      onDrawBehind {
        if (h <= 0f) return@onDrawBehind
        val t = loop.t
        val a = loop.a
        val line = h * (0.5f + 0.14f * sin(t * TAU) + 0.04f * sin(t * TAU * 3)) // whole-number speeds: loops seamlessly
        edge.rewind()
        repeat(25) { k ->
            val x = w * k / 24
            val y = line + sin(k / 24f * TAU * 2 + t * TAU * 4) * 4.dp.toPx()
            if (k == 0) edge.moveTo(x, y) else edge.lineTo(x, y)
        }
        warm.rewind()
        warm.addPath(edge); warm.lineTo(w, h); warm.lineTo(0f, h); warm.close()
        clipPath(warm, ClipOp.Difference) { // cool: top to the line, then flat down to the wave's lowest dip
            scale(1f, line, Offset.Zero) { drawRect(coolFade, size = Size(w, h / line), alpha = a) }
        }
        clipPath(warm) { // warm: the line to the bottom, flat above it up to the wave's highest crest
            translate(top = line) { scale(1f, h - line, Offset.Zero) { drawRect(warmFade, Offset(0f, -1f), Size(w, 2f), alpha = a) } }
        }
        drawPath(edge, Color.White.copy(alpha = (0.12f + 0.1f * sin(t * TAU * 6)) * a), style = stroke)
      }
    }
}
private val IceGlint = Color(0xFFE8F6FF)

/** One frost crystal line. It starts growing at [birth] (0..1 of the creep-in) and takes [GROW] to finish. */
private class Shard(val a: Offset, val b: Offset, val birth: Float, val width: Float)
private const val GROW = 0.35f

/**
 * Ice on glass while [on] (room below the cold-alert temperature): frost crystals creep in from the corners and edges, then a few glints twinkle.
 * Fixed icy blue on purpose, like the fire's orange. Clipped by the card like [flames].
 */
@Composable
internal fun Modifier.frost(on: Boolean, seed: Any? = null): Modifier {
    val loop = loop(on, null, spec = tween(if (on) 2800 else 700)) ?: return this // the seed shapes the crystals, not the timing
    return drawWithCache {
        val shards = frostShards(size, seed.hashCode(), 1.dp.toPx())
        val tips = shards.filter { it.width > 1.dp.toPx() }.let { l -> List(5) { l[(it * 7 + 3) % l.size].b } }
        val r = size.height * 0.9f
        val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        onDrawBehind {
            val g = loop.a
            // Frosted haze in the corners, like breath on a cold window.
            corners.forEach { c ->
                drawCircle(Brush.radialGradient(listOf(Ice.copy(alpha = 0.22f * g), Color.Transparent), c, r), r, c)
            }
            shards.forEach { s ->
                val local = ((g - s.birth) / GROW).coerceIn(0f, 1f)
                if (local > 0f) drawLine(Ice.copy(alpha = 0.55f), s.a, s.a + (s.b - s.a) * local, s.width, StrokeCap.Round)
            }
            // Glints: four-point twinkles at a few crystal tips, once the frost has formed.
            val t = loop.t
            tips.forEachIndexed { i, p ->
                val a = (sin((t + i * 0.2f) * TAU) * 1.4f - 0.4f).coerceIn(0f, 1f) * ((g - 0.8f) / 0.2f).coerceIn(0f, 1f)
                if (a > 0f) {
                    val l = 5.dp.toPx() * a
                    drawLine(IceGlint.copy(alpha = a), p - Offset(l, 0f), p + Offset(l, 0f), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(IceGlint.copy(alpha = a), p - Offset(0f, l), p + Offset(0f, l), 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * Dendrite crystals: each line sprouts side branches at ±60° (ice's hexagonal habit), shrinking each level.
 * Roots sit in the corners and along the long edges, pointing inward, so the middle stays readable.
 */
private fun frostShards(size: Size, seed: Int, dp: Float): List<Shard> {
    val rnd = Random(seed)
    val out = mutableListOf<Shard>()
    fun grow(from: Offset, angle: Float, len: Float, birth: Float, depth: Int) {
        if (depth > 3 || len < 4 * dp) return
        val to = from + Offset(cos(angle) * len, sin(angle) * len)
        out += Shard(from, to, birth, (2.2f - depth * 0.5f) * dp)
        repeat(if (depth < 2) 4 else 2) { k ->
            val f = (k + 1f) / 5f + rnd.nextFloat() * 0.1f
            val side = if ((k + depth) % 2 == 0) 1 else -1
            grow(from + (to - from) * f, angle + side * PI.toFloat() / 3, len * (0.35f + rnd.nextFloat() * 0.2f), birth + GROW * f * 0.6f, depth + 1)
        }
    }
    val (w, h) = size
    val q = PI.toFloat() / 4
    listOf(Offset(0f, 0f) to q, Offset(w, 0f) to 3 * q, Offset(0f, h) to -q, Offset(w, h) to -3 * q)
        .forEach { (c, a) -> grow(c, a + (rnd.nextFloat() - 0.5f) * 0.4f, h * 0.55f, 0f, 0) }
    repeat(3) { // a few more along the top and bottom edges
        val x = w * (0.25f + it * 0.25f) + (rnd.nextFloat() - 0.5f) * w * 0.1f
        val top = it % 2 == 0
        grow(Offset(x, if (top) 0f else h), (if (top) 1 else -1) * (PI.toFloat() / 2 + (rnd.nextFloat() - 0.5f) * 0.8f), h * 0.3f, 0.2f, 1)
    }
    val end = out.maxOf { it.birth } + GROW
    return out.map { Shard(it.a, it.b, it.birth / end * (1 - GROW), it.width) } // whole creep-in fits in 0..1
}

// All frequencies are whole numbers per loop, so the animation wraps around seamlessly.
private fun DrawScope.drawFire(t: Float, strength: Float, sparks: List<Path>) {
    val w = size.width
    val h = size.height
    // Spring overshoot lifts the fire a touch above its rest height, then it settles.
    translate(top = h * (1 - strength.coerceAtMost(1.2f)) * 0.6f) {
        Layers.forEachIndexed { layer, (color, alpha, maxH) ->
            drawPath(flamePath(w, h, maxH * h, t, layer), color.copy(alpha = alpha * strength.coerceIn(0f, 1f)))
        }
    }

    // Sparks: small expressive shapes that spin, sway and shrink as they rise.
    val a = strength.coerceIn(0f, 1f)
    repeat(8) { i ->
        val p = (t * (2 + i % 3) + i * 0.618f) % 1f // progress 0..1
        val x = w * ((i * 0.381f + 0.05f) % 1f) + sin(p * TAU + i) * 14.dp.toPx()
        val y = h * (1 - p * 0.9f)
        val s = (10f + i % 3 * 4f).dp.toPx() * (1 - p * 0.6f)
        translate(x - s / 2, y - s / 2) {
            rotate(p * 360f * (if (i % 2 == 0) 1 else -1), pivot = Offset(s / 2, s / 2)) {
                scale(s, s, pivot = Offset.Zero) {
                    drawPath(sparks[i % sparks.size], Spark.copy(alpha = (1 - p) * 0.6f * a))
                }
            }
        }
    }
}

/**
 * A row of slim straight-edged flame tongues: narrow base, low shoulders, long taper to a sharp tip.
 * Layers share positions and motion, inner layers narrower, so every tongue reads as
 * nested flames (red outside, yellow core). Tall and short tongues alternate.
 * One path per layer, so overlapping tongues don't darken.
 */
private fun flamePath(w: Float, h: Float, maxH: Float, t: Float, layer: Int): Path {
    val tongues = 9
    val gap = w / tongues
    val half = gap * 0.7f * (1f - layer * 0.25f) // half width at the shoulders; neighbours overlap
    // Solid bed along the bottom so there are no gaps between tongues. Drawn up-right-down,
    // the same winding as the tongues, so the union fills without holes.
    val bed = maxH * 0.18f
    val path = Path().apply { moveTo(0f, h); lineTo(0f, h - bed); lineTo(w, h - bed); lineTo(w, h); close() }
    for (j in 0..tongues) { // one extra so a tongue straddles the right edge
        val phase = j * 2.3f
        val size = if (j % 2 == 0) 1f else 0.65f
        val stretch = 0.8f + 0.2f * sin(t * TAU * (3 + j % 3) + phase)
        val lean = sin(t * TAU * (2 + j % 2) + phase) * gap * 0.25f
        val cx = gap * j + if (j % 2 == 0) gap * 0.1f else -gap * 0.1f
        val top = maxH * size * stretch
        path.moveTo(cx - half * 0.7f, h)
        path.lineTo(cx - half + lean * 0.15f, h - top * 0.22f) // left shoulder
        path.lineTo(cx + lean, h - top) // tip: long straight taper
        path.lineTo(cx + half + lean * 0.15f, h - top * 0.22f) // right shoulder
        path.lineTo(cx + half * 0.7f, h)
        path.close()
    }
    return path
}
