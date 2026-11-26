package ancic.karim.onlyheat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import ancic.karim.onlyheat.res.Res
import ancic.karim.onlyheat.res.schedule_locked
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.animate
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
internal val WarningIcon = icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
internal val CloudOffIcon = icon(
    "CloudOff",
    "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
)
internal val AcIcon = icon(
    "AcUnit",
    "M22,11h-4.17l3.24,-3.24 -1.41,-1.42L15,11h-2V9l4.66,-4.66 -1.42,-1.41L13,6.17V2h-2v4.17L7.76,2.93 6.34,4.34 11,9v2H9L4.34,6.34 2.93,7.76 6.17,11H2v2h4.17l-3.24,3.24 1.41,1.42L9,13h2v2l-4.66,4.66 1.42,1.41L11,17.83V22h2v-4.17l3.24,3.24 1.42,-1.41L13,15v-2h2l4.66,4.66 1.41,-1.42L17.83,13H22z",
)
internal val ThermostatIcon = icon(
    "Thermostat",
    "M15,13V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v8c-1.21,0.91 -2,2.37 -2,4c0,2.76 2.24,5 5,5s5,-2.24 5,-5C17,15.37 16.21,13.91 15,13zM11,5c0,-0.55 0.45,-1 1,-1s1,0.45 1,1h-1v1h1v2h-1v1h1v2h-2V5z",
)
internal val SunIcon = icon(
    "WbSunny",
    "M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5L1,10.5v2h3v-2zM13,0.55h-2L11,3.5h2L13,0.55zM20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4zM20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM11,22.45h2L13,19.5h-2v2.95zM3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z",
)
internal val DropIcon = icon("WaterDrop", "M12,2c-5.33,4.55 -8,8.48 -8,11.8c0,4.98 3.8,8.2 8,8.2s8,-3.22 8,-8.2C20,10.48 17.33,6.55 12,2z")
internal val AirIcon = icon(
    "Air",
    "M14.5,17c0,1.65 -1.35,3 -3,3s-3,-1.35 -3,-3h2c0,0.55 0.45,1 1,1s1,-0.45 1,-1s-0.45,-1 -1,-1H2v-2h9.5C13.15,14 14.5,15.35 14.5,17zM19,6.5C19,4.57 17.43,3 15.5,3S12,4.57 12,6.5h2C14,5.67 14.67,5 15.5,5S17,5.67 17,6.5S16.33,8 15.5,8H2v2h13.5C17.43,10 19,8.43 19,6.5zM18.5,11H2v2h16.5c0.83,0 1.5,0.67 1.5,1.5S19.33,16 18.5,16v2c1.93,0 3.5,-1.57 3.5,-3.5S20.43,11 18.5,11z",
)
internal val AutoIcon = icon(
    "Autorenew",
    "M12,6v3l4,-4 -4,-4v3c-4.42,0 -8,3.58 -8,8 0,1.57 0.46,3.03 1.24,4.26L6.7,14.8c-0.45,-0.83 -0.7,-1.79 -0.7,-2.8 0,-3.31 2.69,-6 6,-6zM18.76,7.74L17.3,9.2c0.44,0.84 0.7,1.79 0.7,2.8 0,3.31 -2.69,6 -6,6v-3l-4,4 4,4v-3c4.42,0 8,-3.58 8,-8 0,-1.57 -0.46,-3.03 -1.24,-4.26z",
)
internal val ChevronRightIcon = icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
internal val CloseIcon = icon("Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
internal val ExpandLessIcon = icon("ExpandLess", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
internal val HomeIcon = icon("Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")
internal val ChartIcon = icon("ShowChart", "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z")
internal val FlameIcon = icon(
    "Whatshot",
    "M13.5,0.67s0.74,2.65 0.74,4.8c0,2.06 -1.35,3.73 -3.41,3.73 -2.07,0 -3.63,-1.67 -3.63,-3.73l0.03,-0.36C5.21,7.51 4,10.62 4,14c0,4.42 3.58,8 8,8s8,-3.58 8,-8C20,8.61 17.41,3.8 13.5,0.67zM11.71,19c-1.78,0 -3.22,-1.4 -3.22,-3.14 0,-1.62 1.05,-2.76 2.81,-3.12 1.77,-0.36 3.6,-1.21 4.62,-2.58 0.39,1.29 0.59,2.65 0.59,4.04 0,2.65 -2.15,4.8 -4.8,4.8z",
)
internal val WifiIcon = icon(
    "Wifi",
    "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
)
internal val ScheduleIcon = icon(
    "Schedule",
    "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
)
internal val LockIcon = icon(
    "Lock",
    "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
)
internal val DownloadIcon = icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
internal val RefreshIcon = icon(
    "Refresh",
    "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
)
internal val BellIcon = icon(
    "Notifications",
    "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z",
)
internal val PersonIcon = icon(
    "Person",
    "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z",
)
internal val LightbulbIcon = icon(
    "Lightbulb",
    "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9v1zM12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74V17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z",
)
internal val LuggageIcon = icon(
    "Luggage",
    "M17,6h-2V3c0,-0.55 -0.45,-1 -1,-1h-4C9.45,2 9,2.45 9,3v3H7C5.9,6 5,6.9 5,8v11c0,1.1 0.9,2 2,2c0,0.55 0.45,1 1,1s1,-0.45 1,-1h6c0,0.55 0.45,1 1,1s1,-0.45 1,-1c1.1,0 2,-0.9 2,-2V8C19,6.9 18.1,6 17,6zM9.5,18H8V9h1.5V18zM12.75,18h-1.5V9h1.5V18zM16,18h-1.5V9H16V18zM10.5,3.5h3V6h-3V3.5z",
)
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

/** Tonal button tinted like the [TempStepper] − / + buttons, so it stays visible on every card color. */
@Composable
internal fun onCardTonal() = LocalContentColor.current.let {
    ButtonDefaults.filledTonalButtonColors(containerColor = it.copy(alpha = 0.12f), contentColor = it)
}

/** A room card's on/off switch: tinted with the card's own ink instead of the theme's bright primary and drawn a bit
 *  smaller (the touch target stays), so it sits quietly on every room color. */
@Composable
internal fun CardSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val ink = LocalContentColor.current
    Switch(
        checked, onChange, Modifier.scale(0.85f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = ink, checkedTrackColor = ink.copy(alpha = 0.28f), checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = ink.copy(alpha = 0.55f), uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = ink.copy(alpha = 0.35f),
        ),
    )
}

/** − value + control with shape-morphing buttons and a rolling number. */
@Composable
internal fun TempStepper(
    value: Double,
    onChange: (Double) -> Unit,
    compact: Boolean = false, // a room card's device row: small buttons and number, so the row's name still fits
    buttonSize: Dp = if (compact) 36.dp else 52.dp,
    label: String? = null,
    onLocked: (() -> Unit)? = null,
    step: Double = STEP,
    min: Double = MIN_T,
    max: Double = MAX_T,
) {
    // Tint of whatever card we sit on, so the buttons stay visible on every container color.
    val content = LocalContentColor.current
    val locked = stringResource(Res.string.schedule_locked)
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = content.copy(alpha = 0.12f),
        contentColor = if (onLocked != null) content.copy(alpha = 0.5f) else content,
    )
    // Locked buttons still take taps (they explain the schedule), so TalkBack has to hear why they look dimmed.
    val lockedState = if (onLocked != null) Modifier.semantics { stateDescription = locked } else Modifier
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onLocked ?: { onChange((value - step).coerceAtLeast(min)) },
            shapes = IconButtonDefaults.shapes(),
            enabled = onLocked != null || value > min,
            colors = colors,
            modifier = Modifier.size(buttonSize).then(lockedState),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.widthIn(min = if (compact) 60.dp else 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RollingTemp(
                value, if (compact) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.headlineSmallEmphasized,
                color = content,
            )
            label?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        FilledTonalIconButton(
            onClick = onLocked ?: { onChange((value + step).coerceAtMost(max)) },
            shapes = IconButtonDefaults.shapes(),
            enabled = onLocked != null || value < max,
            colors = colors,
            modifier = Modifier.size(buttonSize).then(lockedState),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/** Temperature that rolls up or down like an odometer when it changes; null shows a dash. */
@Composable
internal fun RollingTemp(value: Double?, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val roll = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    AnimatedContent(
        value, modifier,
        transitionSpec = {
            val up = (targetState ?: 0.0) > (initialState ?: 0.0)
            slideInVertically(roll) { if (up) it else -it } togetherWith slideOutVertically(roll) { if (up) -it else it }
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
    // No bounce: a bouncy spring's long tail keeps the glide running, and glide() drops same-way events meanwhile.
    val spec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
    fun glide(open: Boolean) {
        // A trackpad sends a stream of events: let a glide already heading this way run, don't restart it.
        if (g.job?.isActive == true && g.open == open) return
        g.job?.cancel()
        g.open = open
        g.job = scope.launch {
            val end = if (open) 0f else s.heightOffsetLimit
            animate(s.heightOffset, end, animationSpec = spec) { v, _ -> s.heightOffset = v }
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
