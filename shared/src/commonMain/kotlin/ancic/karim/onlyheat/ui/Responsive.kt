package ancic.karim.onlyheat.ui

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/** Current window width in dp. Works on phone, desktop and tablet (whole-window size). */
@Composable
internal fun windowWidth(): Dp {
    val px = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { px.toDp() }
}

/** Material window-size breakpoint: a wide window (tablet landscape, desktop) gets a side rail. */
internal val expanded: Boolean @Composable get() = windowWidth() >= 840.dp

/**
 * Padding that keeps a full-width list's content centered and at most [max] wide (16 dp gutters on a phone).
 * The scroller itself stays full width, so a mouse wheel or trackpad scrolls it (and the collapsing
 * top bar) from anywhere in the window, side margins included.
 */
internal fun BoxWithConstraintsScope.cappedPadding(max: Dp, top: Dp = 16.dp, bottom: Dp = 16.dp): PaddingValues {
    val side = maxOf(16.dp, (maxWidth - max) / 2 + 16.dp)
    return PaddingValues(side, top, side, bottom)
}
