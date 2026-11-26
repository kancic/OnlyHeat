package ancic.karim.onlyheat.ui

import ancic.karim.onlyheat.data.ApiException
import ancic.karim.onlyheat.data.ControllerUnreachable
import ancic.karim.onlyheat.data.NoApiPin
import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.ServerUnreachable
import ancic.karim.onlyheat.data.WrongDashboardPin
import ancic.karim.onlyheat.data.deg
import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.res.*
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal val Heat = Color(0xFFFF5A00)
internal val NightSky = Color(0xFF353D70)
internal val NightInk = Color(0xFFE6E8FF)
const val STEP = 0.5
const val MIN_T = 5.0
const val MAX_T = 30.0

/**
 * M3 fade-through: the old content fades out quickly, then the new one fades and scales in. Tweens, not the
 * MotionScheme's springs: the fade-in and scale must wait for the fade-out, which a spring can't.
 */
internal fun fadeThrough() =
    (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f)) togetherWith
        fadeOut(tween(90))

internal val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Container transform: the element with the same [key] on the next screen grows out of this one. Only valid inside a NavEntry.
 *  [shape] is the card's own outline, so the transition clips it the way it is drawn. */
@Composable
internal fun Modifier.shared(key: String, shape: Shape = MaterialTheme.shapes.extraLarge): Modifier {
    val scope = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedContentScope.current
    val clip = with(scope) { OverlayClip(shape) }
    return with(scope) {
        sharedBounds(
            rememberSharedContentState(key), anim,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = clip,
        )
    }
}

/** Insets for a tab's own Scaffold: the bottom is already taken by the navigation bar. */
internal val TabInsets
    @Composable get() =
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** A failed load or empty state: icon in a cookie, the text, and Retry when there's something to retry. */
@Composable
internal fun Message(
    text: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    icon: ImageVector = CloudOffIcon,
    onRetry: (() -> Unit)?,
) {
    Column(modifier.padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).background(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialShapes.Cookie9Sided.toShape()
            ),
            Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text, Modifier.widthIn(max = 360.dp), style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            Button(
                onRetry,
                shapes = ButtonDefaults.shapes()
            ) { Text(stringResource(Res.string.retry)) }
        }
    }
}

/** Why the controller kept [kept]'s target instead of the one just sent. Also used by the Android widget. */
suspend fun keptMessage(kept: Room): String {
    val shown = kept.target?.let { deg(it) } ?: getString(Res.string.off_lower)
    return if (kept.schedule) getString(Res.string.kept_schedule, kept.name, shown)
    else "${kept.name}: " + getString(Res.string.kept_other, shown)
}

/** User-facing text for a failure. Server texts are English-only; the API ignores `lang`. */
internal suspend fun errorText(e: Throwable): String {
    log("showing error to user", e)
    return when (e) {
        is ApiException -> e.message ?: getString(Res.string.failed)
        is ServerUnreachable -> getString(Res.string.server_unreachable)
        is ControllerUnreachable -> getString(Res.string.schedule_unreachable)
        is WrongDashboardPin -> getString(Res.string.wrong_dashboard_pin)
        is NoApiPin -> getString(Res.string.no_api_pin)
        else -> getString(Res.string.failed)
    }
}

@Composable
internal fun SubScreen(
    title: String, onBack: (() -> Unit)?, subtitle: String? = null, // no back arrow when shown as a tab
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = collapsingBar()
    Scaffold(
        modifier = modifier.collapsing(scroll),
        topBar = {
            // As a tab, the same large bar as Rooms and Settings; a pushed screen gets the medium one with a back arrow.
            if (onBack == null) LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1) },
                subtitle = subtitle?.let { { Text(it) } },
                scrollBehavior = scroll,
            )
            else MediumFlexibleTopAppBar(
                title = { Text(title, maxLines = 1) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = {
                    IconButton(onBack, shapes = IconButtonDefaults.shapes()) {
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
