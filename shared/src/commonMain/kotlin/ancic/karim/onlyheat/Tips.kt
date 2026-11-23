package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val TIP = "tip."

/**
 * One page of a tip: a short [title] and [text] under a [scene], a small live picture people can play with.
 * [id] is remembered once the page has been shown, so it must be unique and never reused or renamed.
 */
private class TipPage(val id: String, val title: StringResource, val text: StringResource, val scene: @Composable () -> Unit)

/** The [ids] not shown yet, in order. */
internal fun unseenTips(s: Settings, ids: List<String>) = ids.filterNot { s.getBoolean(TIP + it, false) }

/**
 * Explains a screen the first time it opens, showing only the [all] pages not seen yet: a page added
 * later shows by itself on a screen people already know. Closing it in any way marks the shown pages as seen.
 */
@Composable
private fun FirstTimeTip(vararg all: TipPage) {
    val settings = koinInject<Settings>()
    var unseen by remember { mutableStateOf(unseenTips(settings, all.map { it.id })) }
    val pages = all.filter { it.id in unseen }
    var page by rememberSaveable { mutableStateOf(0) }
    if (pages.isEmpty()) return
    fun done() {
        pages.forEach { settings.putBoolean(TIP + it.id, true) }
        page = 0
        unseen = emptyList()
    }
    val current = page.coerceIn(pages.indices)
    val last = current == pages.lastIndex
    BasicAlertDialog(::done) { // Back closes it; a tap outside does too, like any dialog
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(current, modifier = Modifier.weight(1f, fill = false), transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
                }) { p ->
                    Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        pages[p].scene()
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(pages[p].title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(pages[p].text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pages.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pages.indices.forEach { i ->
                            val w by animateDpAsState(if (i == current) 20.dp else 8.dp)
                            Box(Modifier.size(w, 8.dp).clip(CircleShape).background(
                                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!last) TextButton(::done) { Text(stringResource(Res.string.tip_skip)) }
                    Button({ if (last) done() else page = current + 1 }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(if (last) Res.string.tip_done else Res.string.tip_next))
                    }
                }
            }
        }
    }
}

/** Shows the tour again the next time the Rooms tab opens. */
internal fun resetTips(s: Settings) = s.keys.filter { it.startsWith(TIP) }.forEach(s::remove)

/** The picture area of a page: a card-coloured box with the app's own effects drawn by [effects]. */
@Composable
private fun Scene(container: Color, ink: Color, effects: Modifier = Modifier, content: @Composable () -> Unit) {
    val bg by animateColorAsState(container)
    val fg by animateColorAsState(ink)
    Box(
        Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.extraLarge).background(bg).then(effects),
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides fg) { content() } }
}

@Composable
private fun warm() = MaterialTheme.colorScheme.primaryContainer

@Composable
private fun warmInk() = MaterialTheme.colorScheme.onPrimaryContainer

// The room card's moon spot is under a tip's centred text; these corners stay clear of it.
private val MoonTop = Offset(0.13f, 0.24f)
private val ZTop = Offset(0.30f, 0.06f)
private val NoMoon = Offset(2f, 2f) // off the card: stars only, for cards too small for the moon

/** Something that changes every [ms] on its own, so a scene plays without a tap. */
@Composable
private fun cycle(count: Int, ms: Long = 2_200): Int {
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(ms); i = (i + 1) % count } }
    return i
}

/** The welcome tour, on the Rooms tab right after the first login: what isn't obvious from the cards themselves. */
@Composable
internal fun RoomsTip() = FirstTimeTip(
    // The card pictures carry meaning; nobody guesses that frost means "below your cold alert".
    TipPage("room-card-pictures", Res.string.tour_cards_title, Res.string.tour_cards) {
        val state = cycle(4)
        val ink = if (state == 2) NightInk else warmInk()
        Scene(
            if (state == 2) NightSky else if (state == 3) MaterialTheme.colorScheme.surfaceContainerLow else warm(), ink,
            Modifier.flames(state == 0).drift(state == 1, ink.copy(alpha = 0.12f)).night(state == 2, NightInk, moon = MoonTop, zTo = ZTop).frost(state == 3),
        ) {
            Text(
                stringResource(listOf(Res.string.state_heating, Res.string.state_warm, Res.string.state_off, Res.string.state_cold)[state]),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = if (state == 3) MaterialTheme.colorScheme.onSurface else ink,
            )
        }
    },
    // The surprise people call support about: a scheduled room ignores the app.
    TipPage("scheduled-room-locked", Res.string.tour_schedule_title, Res.string.tour_schedule) {
        var t by remember { mutableStateOf(21.0) }
        LaunchedEffect(t) { if (t != 21.0) { delay(700); t = 21.0 } }
        Scene(warm(), warmInk()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.schedule), style = MaterialTheme.typography.labelLarge)
                TempStepper(t, { t = it }, label = stringResource(Res.string.target))
            }
        }
    },
    TipPage("away", Res.string.tour_away_title, Res.string.tour_away) { AwayScene() },
)

/**
 * Three rooms heating, then "Go away": one after the other they fall asleep at 16°, then "I'm back" wakes each
 * at its own temperature. Plays by itself until the button is tapped.
 */
@Composable
private fun AwayScene() {
    val own = listOf(22.0, 20.5, 19.0)
    var away by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf(false) }
    val asleep = remember { mutableStateListOf(false, false, false) }
    LaunchedEffect(away) { own.indices.forEach { delay(260); asleep[it] = away } }
    LaunchedEffect(tapped) { while (!tapped) { delay(3_200); away = !away } }
    Scene(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                own.forEachIndexed { i, t ->
                    val night = asleep[i]
                    val bg by animateColorAsState(if (night) NightSky else warm())
                    val ink by animateColorAsState(if (night) NightInk else warmInk())
                    Box(
                        Modifier.weight(1f).height(72.dp).clip(MaterialTheme.shapes.large).background(bg)
                            .flames(!night, seed = i).night(night, NightInk, seed = i, moon = NoMoon, zTo = NoMoon),
                        contentAlignment = Alignment.Center,
                    ) { RollingTemp(if (night) 16.0 else t, MaterialTheme.typography.headlineSmall, color = ink) }
                }
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton({ tapped = true; away = !away }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (away) Res.string.away_back else Res.string.away_go))
            }
        }
    }
}
