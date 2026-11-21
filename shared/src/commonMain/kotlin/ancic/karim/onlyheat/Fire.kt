package ancic.karim.onlyheat

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private const val LOOP_MS = 6000
private const val TAU = (2 * PI).toFloat()

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
internal fun Modifier.flames(on: Boolean, seed: Any? = null): Modifier {
    val strength by animateFloatAsState(if (on) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec())
    if (strength <= 0.001f) return this
    val time = rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)),
    )
    val sparks = listOf( // remembered unit-square paths
        MaterialShapes.Sunny.toPath(), MaterialShapes.Cookie4Sided.toPath(),
        MaterialShapes.SoftBurst.toPath(), MaterialShapes.Flower.toPath(),
    )
    val offset = (seed.hashCode() * 0.618034f % 1f + 1f) % 1f // golden ratio spreads close ids (1, 2, 3) far apart
    return drawBehind { drawFire((time.value + offset) % 1f, strength, sparks) } // read in draw: redraws, no recomposition
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
