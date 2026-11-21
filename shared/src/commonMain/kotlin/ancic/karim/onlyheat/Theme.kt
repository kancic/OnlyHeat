package ancic.karim.onlyheat

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Warm tonal palette generated from the heating orange (#FF5A00).
private val Light = lightColorScheme(
    primary = Color(0xFFA23F00), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC), onPrimaryContainer = Color(0xFF351000),
    secondary = Color(0xFF77574A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF), onSecondaryContainer = Color(0xFF2C160C),
    tertiary = Color(0xFF666014), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEE58C), onTertiaryContainer = Color(0xFF1F1C00),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231915),
    surface = Color(0xFFFFF8F6), onSurface = Color(0xFF231915),
    surfaceVariant = Color(0xFFF5DED5), onSurfaceVariant = Color(0xFF53433D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFCEAE4), surfaceContainerHigh = Color(0xFFF6E5DE),
    surfaceContainerHighest = Color(0xFFF0DFD8),
    outline = Color(0xFF85736C), outlineVariant = Color(0xFFD8C2BA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB595), onPrimary = Color(0xFF571E00),
    primaryContainer = Color(0xFF7C2E00), onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE7BEAE), onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034), onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFD1C973), onTertiary = Color(0xFF353100),
    tertiaryContainer = Color(0xFF4D4800), onTertiaryContainer = Color(0xFFEEE58C),
    background = Color(0xFF1A110E), onBackground = Color(0xFFF1DFD9),
    surface = Color(0xFF1A110E), onSurface = Color(0xFFF1DFD9),
    surfaceVariant = Color(0xFF53433D), onSurfaceVariant = Color(0xFFD8C2BA),
    surfaceContainerLowest = Color(0xFF140C09), surfaceContainerLow = Color(0xFF231915),
    surfaceContainer = Color(0xFF271D19), surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D322E),
    outline = Color(0xFFA08D86), outlineVariant = Color(0xFF53433D),
)

// ponytail: the few Material icons we need, inlined as paths instead of pulling in an icon library.
private fun icon(name: String, path: String, mirror: Boolean = false) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

internal val ArrowBack = icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", mirror = true)
internal val CloudOffIcon = icon(
    "CloudOff",
    "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
)
internal val HomeIcon = icon("Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
internal val ChartIcon = icon("ShowChart", "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z")
internal val TuneIcon = icon(
    "Tune",
    "M3,17v2h6v-2H3zM3,5v2h10V5H3zm10,16v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zm14,4v-2H11v2h10zm-6,-4h2V7h4V5h-4V3h-2v6z",
)

@Composable
fun OnlyHeatTheme(dynamicScheme: (@Composable (dark: Boolean) -> ColorScheme?)? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialExpressiveTheme(
        colorScheme = dynamicScheme?.invoke(dark) ?: if (dark) Dark else Light,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** − value + control with shape-morphing buttons and a rolling number. */
@Composable
internal fun TempStepper(
    value: Double,
    onChange: (Double) -> Unit,
    buttonSize: Dp = 52.dp,
    label: String? = null,
) {
    // Tint of whatever card we sit on, so the buttons stay visible on every container color.
    val content = LocalContentColor.current
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = content.copy(alpha = 0.12f), contentColor = content,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onChange((value - STEP).coerceAtLeast(MIN_T)) },
            shapes = IconButtonDefaults.shapes(),
            enabled = value > MIN_T,
            colors = colors,
            modifier = Modifier.size(buttonSize),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.widthIn(min = 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RollingTemp(value, MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            label?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        FilledTonalIconButton(
            onClick = { onChange((value + STEP).coerceAtMost(MAX_T)) },
            shapes = IconButtonDefaults.shapes(),
            enabled = value < MAX_T,
            colors = colors,
            modifier = Modifier.size(buttonSize),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/** Temperature that rolls up or down like an odometer when it changes; null shows a dash. */
@Composable
internal fun RollingTemp(value: Double?, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    AnimatedContent(
        value, modifier,
        transitionSpec = {
            val up = (targetState ?: 0.0) > (initialState ?: 0.0)
            slideInVertically { if (up) it else -it } togetherWith slideOutVertically { if (up) -it else it }
        },
    ) { v ->
        Text(v?.let { "${fmt1(it)}°" } ?: "—", style = style, color = color, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun collapsingBar(): TopAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

/**
 * Connects [bar] to scrolling, and makes mouse wheels / trackpads drive it properly. On desktop a list
 * already at its top drops wheel-up events, so they never reach the bar and it can't expand; and a
 * wheel never flings, so the bar would stop half-way. So: a wheel-down while the bar is open glides it
 * shut (the list waits, like with touch), and a wheel-up the list didn't use glides it open. Touch input
 * produces no wheel events and keeps the stock behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Modifier.collapsing(bar: TopAppBarScrollBehavior): Modifier {
    val s = bar.state
    val scope = rememberCoroutineScope()
    val g = remember { Glide() }
    fun glide(open: Boolean) {
        // A trackpad sends a stream of events: let a glide already heading this way run, don't restart it.
        if (g.job?.isActive == true && g.open == open) return
        g.job?.cancel()
        g.open = open
        g.job = scope.launch {
            val end = if (open) 0f else s.heightOffsetLimit
            animate(s.heightOffset, end, animationSpec = tween(200, easing = FastOutSlowInEasing)) { v, _ -> s.heightOffset = v }
        }
    }
    return nestedScroll(bar.nestedScrollConnection).pointerInput(bar) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type != PointerEventType.Scroll) continue
                val dy = e.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y } // > 0: scrolling down
                val collapsed = s.heightOffset <= s.heightOffsetLimit
                if (dy > 0 && !collapsed) {
                    glide(open = false)
                    e.changes.forEach { it.consume() }
                } else if (dy < 0 && s.heightOffset < 0f) {
                    // Same event after the list had its turn: unconsumed means the list is at the top.
                    if (awaitPointerEvent(PointerEventPass.Final).changes.none { it.isConsumed }) glide(open = true)
                }
            }
        }
    }
}

private class Glide { var open = false; var job: Job? = null }
