package ancic.karim.onlyheat

import androidx.compose.ui.graphics.luminance
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.delay

/** Each room keeps its own tonal color everywhere it appears (Rooms tab, History): container to text and icons. */
@Composable
internal fun roomColors(id: String): Pair<Color, Color> {
    val s = MaterialTheme.colorScheme
    return when ((id.hashCode() and Int.MAX_VALUE) % 3) {
        0 -> s.primaryContainer to s.onPrimaryContainer
        1 -> s.tertiaryContainer to s.onTertiaryContainer
        // Not secondary: its low chroma reads as a neutral, "nothing going on" card. Teal is clearly a color in the
        // theme's tone, far from the asleep NightSky and the heat orange.
        else -> hueContainer(TEAL) to hueContent(TEAL)
    }
}

/** A container in [hue] at the primary container's saturation and lightness, so it sits in the theme's tone.
 *  Darkened on dark containers; a little on pale light ones too, so it doesn't fade into the background. */
@Composable
internal fun hueContainer(hue: Float): Color {
    val s = MaterialTheme.colorScheme
    return if (isDark()) withHue(s.primaryContainer, hue, satScale = 0.5f, lightScale = 0.8f)
    else withHue(s.primaryContainer, hue, satScale = 0.55f, lightScale = 0.92f)
}

/** Text and icons on [hueContainer]. Darker on light themes: teal or amber look lighter than the dark blue they
 *  replace at the same lightness. */
@Composable
internal fun hueContent(hue: Float): Color {
    val s = MaterialTheme.colorScheme
    return withHue(s.onPrimaryContainer, hue, lightScale = if (isDark()) 1f else 0.6f)
}

private const val TEAL = 170f
/** Warnings: not tertiary, which is also a room color, and not the heat orange. */
internal const val AMBER = 34f

/** [c] with its hue replaced, saturation and lightness kept (scaled: green looks lighter than blue at the same values).
 *  ponytail: a fixed teal; a wallpaper whose primary is teal itself would make two rooms alike. Pick the hue
 *  farthest from primary and tertiary if that shows up. */
internal fun withHue(c: Color, hue: Float, satScale: Float = 1f, lightScale: Float = 1f): Color {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val l = (max + min) / 2
    val d = max - min
    val sat = if (d == 0f) 0f else d / (1 - kotlin.math.abs(2 * l - 1))
    // A floor, so a grey primary (monochrome wallpaper) still gives a color, not a neutral card.
    return Color.hsl(hue, (sat * satScale).coerceIn(0.3f, 1f), (l * lightScale).coerceIn(0f, 1f), c.alpha)
}

/** Clickable card that squishes and tightens its corners while pressed, then springs back. */
@Composable
internal fun PressCard(
    onClick: () -> Unit, modifier: Modifier = Modifier, colors: CardColors,
    shape: Shape? = null, // fixed corners (a card inside a group); null: the corners tighten while pressed
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme
    val corner by animateDpAsState(if (pressed) 16.dp else 28.dp, spec.fastSpatialSpec())
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spec.fastSpatialSpec())
    Card(
        onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = shape ?: RoundedCornerShape(corner), colors = colors, interactionSource = source, content = content,
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

/** Dark theme, read from the colors themselves, so it matches whatever theme is applied. */
@Composable
internal fun isDark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
internal fun SectionHeading(title: String, text: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.primary)
        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card's corners: a lone card's big ones, or its place in a [Group]. Cards take it as their default shape. */
internal val LocalCardShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(28.dp) }

class GroupScope internal constructor() {
    internal val items = mutableListOf<Pair<Any, @Composable () -> Unit>>()
    /** One card; [key] keeps its state when cards before it come and go, like a lazy list's. */
    fun item(key: Any, content: @Composable () -> Unit) { items += key to content }
}

/** A section's cards joined into one block, Android 16 Settings style: thin gaps, big corners on the block's outer
 *  edges, small ones where cards meet, handed to each card through [LocalCardShape]. Used on every tab. */
@Composable
internal fun Group(content: GroupScope.() -> Unit) {
    val items = GroupScope().apply(content).items
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { i, (k, item) ->
            val top = if (i == 0) 28.dp else 6.dp
            val bottom = if (i == items.lastIndex) 28.dp else 6.dp
            key(k) { CompositionLocalProvider(LocalCardShape provides RoundedCornerShape(top, top, bottom, bottom), content = item) }
        }
    }
}

/** A settings card's icon in its [shape]: colorful by [accent] (0 primary, 1 tertiary, 2 secondary; neighbours
 *  differ) on a neutral card, in the card's own [ink] on one tinted for its state. */
@Composable
internal fun Badge(icon: ImageVector, shape: RoundedPolygon, accent: Int = 0, ink: Color? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (fill, onFill) = when {
        ink != null -> ink.copy(alpha = 0.12f) to ink
        accent % 3 == 0 -> scheme.primary to scheme.onPrimary
        accent % 3 == 1 -> scheme.tertiary to scheme.onTertiary
        else -> scheme.secondary to scheme.onSecondary
    }
    Box(modifier.size(40.dp).background(fill, shape.toShape()), Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = onFill)
    }
}

/**
 * A settings card. Color means state, never identity: neutral by default, [container] only while the card has
 * something to say (a check's result; a switch shows its own state). Identity is the [icon] in its [badge] shape.
 * Also the notices' card (Notices.kt), which may lack a title or an icon.
 */
@Composable
internal fun Section(
    title: String?, text: String?, icon: ImageVector?, badge: RoundedPolygon, // text null: [below] says it
    accent: Int = 0, // the badge's color: 0 primary, 1 tertiary, 2 secondary; neighbours differ
    onClick: (() -> Unit)? = null, modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh, ink: Color = contentColorFor(container),
    shape: Shape = LocalCardShape.current,
    below: (@Composable ColumnScope.() -> Unit)? = null, // extra content under the header row
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val tinted = container != MaterialTheme.colorScheme.surfaceContainerHigh
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val colors = CardDefaults.cardColors(
        containerColor = animateColorAsState(container, spec).value, contentColor = animateColorAsState(ink, spec).value,
    )
    val content: @Composable ColumnScope.() -> Unit = {
        // A card nested in this one is a lone card again, not this one's place in a Group.
        CompositionLocalProvider(LocalCardShape provides RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Badge(icon, badge, accent, if (tinted) ink else null, Modifier.align(Alignment.Top)) // beside the title, also on long cards
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                }
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            below?.invoke(this)
        }
        }
    }
    if (onClick != null) PressCard(onClick, modifier.fillMaxWidth(), colors = colors, shape = shape, content = content)
    else Card(modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
}
