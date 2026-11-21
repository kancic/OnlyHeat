package ancic.karim.onlyheat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.delay

/** Each room keeps its own tonal color everywhere it appears (Rooms tab, History). */
@Composable
internal fun roomContainer(id: String): Color {
    val s = MaterialTheme.colorScheme
    return when ((id.hashCode() and Int.MAX_VALUE) % 3) {
        0 -> s.primaryContainer
        1 -> s.tertiaryContainer
        else -> s.secondaryContainer
    }
}

/** Clickable card that squishes and tightens its corners while pressed, then springs back. */
@Composable
internal fun PressCard(
    onClick: () -> Unit, modifier: Modifier = Modifier, colors: CardColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme
    val corner by animateDpAsState(if (pressed) 16.dp else 28.dp, spec.fastSpatialSpec())
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spec.fastSpatialSpec())
    Card(
        onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(corner), colors = colors, interactionSource = source, content = content,
    )
}

/**
 * Springs the element up into place the first time it appears, staggered by [index].
 * Remembered per item, so scrolling a lazy list back doesn't replay it.
 */
@Composable
internal fun Modifier.springIn(index: Int): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (shown) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (shown) return@LaunchedEffect
        delay(index.coerceAtMost(8) * 45L)
        shown = true
        p.animateTo(1f, spec)
    }
    return graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1 - p.value) * 48.dp.toPx()
    }
}

/**
 * A round blob that morphs into a scalloped cookie and slowly turns while [active],
 * and relaxes back into a circle when not. Content sits still in the middle.
 */
@Composable
internal fun MorphBlob(
    active: Boolean, color: Color, modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val circle = MaterialShapes.Circle
    val cookie = MaterialShapes.Cookie9Sided
    val morph = remember(circle, cookie) { Morph(circle, cookie) }
    val path = remember { Path() }
    val progress by animateFloatAsState(if (active) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec())
    val turn = if (progress > 0.001f) rememberInfiniteTransition().animateFloat(
        0f, 360f, infiniteRepeatable(tween(20_000, easing = LinearEasing)),
    ) else null
    Box(
        modifier.drawWithContent {
            morph.toPath(progress.coerceIn(0f, 1f), path) // unit square; spring overshoot clamped
            val t = turn?.value ?: 0f
            val (w, h) = size
            rotate(t) {
                scale(w, h, pivot = Offset.Zero) {
                    drawPath(path, color)
                    // Content is clipped to the blob, then drawn unrotated and unscaled.
                    clipPath(path) { scale(1 / w, 1 / h, pivot = Offset.Zero) { rotate(-t) { this@drawWithContent.drawContent() } } }
                }
            }
        },
        contentAlignment = Alignment.Center, content = content,
    )
}
