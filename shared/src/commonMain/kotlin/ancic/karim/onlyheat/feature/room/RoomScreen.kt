package ancic.karim.onlyheat.feature.room

import ancic.karim.onlyheat.ac.AcView
import ancic.karim.onlyheat.ac.acIcon
import ancic.karim.onlyheat.ac.acValue
import ancic.karim.onlyheat.ac.floorIcon
import ancic.karim.onlyheat.data.Boost
import ancic.karim.onlyheat.data.FIRMWARE
import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.Point
import ancic.karim.onlyheat.data.ReadingDao
import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.Smart
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.deg
import ancic.karim.onlyheat.data.fmt1
import ancic.karim.onlyheat.data.hasThermalIntelligence
import ancic.karim.onlyheat.data.isCold
import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.store
import ancic.karim.onlyheat.feature.history.Legend
import ancic.karim.onlyheat.feature.history.StatsLine
import ancic.karim.onlyheat.feature.history.TempChart
import ancic.karim.onlyheat.feature.history.hhmm
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.AutoIcon
import ancic.karim.onlyheat.ui.ChartIcon
import ancic.karim.onlyheat.ui.FlameIcon
import ancic.karim.onlyheat.ui.Group
import ancic.karim.onlyheat.ui.MorphBlob
import ancic.karim.onlyheat.ui.NightInk
import ancic.karim.onlyheat.ui.NightSky
import ancic.karim.onlyheat.ui.RollingTemp
import ancic.karim.onlyheat.ui.RoomActivity
import ancic.karim.onlyheat.ui.Section
import ancic.karim.onlyheat.ui.SubScreen
import ancic.karim.onlyheat.ui.TempStepper
import ancic.karim.onlyheat.ui.errorText
import ancic.karim.onlyheat.ui.onCardTonal
import ancic.karim.onlyheat.ui.roomAnimations
import ancic.karim.onlyheat.ui.roomColors
import ancic.karim.onlyheat.ui.shared
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.russhwolf.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.floor
import kotlin.time.Duration.Companion.minutes

/** Room detail: 24 h chart from /getRoomLog, boost and the smart-heating switch. */
@Composable
fun RoomScreen(
    api: HeatApi, key: String, room: Room, boost: Boost?,
    onBoost: (minutes: Int) -> Unit, onStopBoost: () -> Unit, onSmartChanged: () -> Unit,
    ac: AcView? = null, // the room's AC: the hero then shows the floor and the AC as two equal lines and animates for both
    acCard: @Composable (AcView) -> Unit = {}, // its controls, under the chart
    acWarning: @Composable (AcView) -> Unit = {}, // heating and AC working against each other, under the hero
    onBack: () -> Unit,
) {
    val settings = koinInject<Settings>()
    val history = koinInject<ReadingDao>()
    var log by remember { mutableStateOf<List<Point>?>(null) }
    var smart by remember { mutableStateOf<Smart?>(null) }
    var logError by remember { mutableStateOf<String?>(null) }
    var smartError by remember { mutableStateOf<String?>(null) }

    // The log has 5-minute points, so reload at that pace while the screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(room.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                attempt { api.roomLog(key, room.id).also { history.store(room.id, it) } }
                    .onSuccess { log = it; logError = null }.onFailure { logError = errorText(it) }
                attempt { api.smart(key, room.id) }
                    .onSuccess { smart = it; smartError = null }.onFailure { smartError = errorText(it) }
                delay(5.minutes)
            }
        }
    }

    SubScreen(
        room.name, onBack, subtitle = if (room.schedule) stringResource(Res.string.follows_schedule) else null,
        modifier = Modifier.shared("room-${room.id}"),
    ) { pad ->
          Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 720.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            val activity = RoomActivity(room, ac)
            val heating = activity.floor
            val on = room.target != null
            val cold = isCold(room, settings)
            val asleep = !activity.awake && !cold
            val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
            val (roomContainer, roomContent) = roomColors(room.id)
            val blob by animateColorAsState(if (asleep) NightSky else roomContainer, spec)
            val ink by animateColorAsState(if (asleep) NightInk else roomContent, spec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hero: the temperature sits in a blob that turns into a spinning cookie while heating,
                // with the list card's animations inside, clipped to the blob.
                MorphBlob(heating || activity.acOn, blob, Modifier.size(184.dp)) {
                    Box(
                        Modifier.matchParentSize().roomAnimations(
                            room, ac, cold, ink, bubbles = 0.12f,
                            moon = Offset(0.42f, 0.2f), zTo = Offset(0.72f, 0.12f), // above the number
                        ),
                    )
                    RollingTemp(room.current, MaterialTheme.typography.displayMediumEmphasized, color = ink)
                }
                Spacer(Modifier.width(20.dp))
                if (ac != null) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Like the card's rows: the icon tells what each one does, the number what it aims for.
                    HeroDevice(
                        floorIcon(on, heating), room.target?.let { deg(it) } ?: stringResource(Res.string.off_lower),
                        stringResource(Res.string.heating_title), on,
                    )
                    HeroDevice(
                        acIcon(ac.state), acValue(ac.state, ac.loaded), stringResource(Res.string.ac_short), activity.acOn,
                    )
                }
                else Column {
                    Text(
                        room.target?.let { stringResource(Res.string.target_value, fmt1(it)) } ?: stringResource(Res.string.off_lower),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (heating) Text(
                        stringResource(Res.string.status_heating), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ac?.let { acWarning(it) } // under the hero, still seen without scrolling
            Section(
                stringResource(Res.string.last_24h), null, ChartIcon, MaterialShapes.Cookie9Sided,
                below = {
                    val l = log
                    if (l == null) Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) {
                        logError?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: LoadingIndicator()
                    }
                    else {
                        StatsLine(l, MaterialTheme.typography.titleSmall)
                        TempChart(l, Modifier.fillMaxWidth().height(220.dp), interactive = true)
                        Legend()
                        logError?.let { Text(it, color = MaterialTheme.colorScheme.error) } // stale chart, refresh failed
                        val temps = l.mapNotNull { it.current }
                        if (temps.isNotEmpty()) Text(
                            stringResource(Res.string.min_max, fmt1(temps.min()), fmt1(temps.max())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            ac?.let { acCard(it) }
            val supported = remember { hasThermalIntelligence(settings.getStringOrNull(FIRMWARE)) }
            // Old firmware: only show it while it's on, so it can be turned off.
            val showSmart = supported || smart?.on == true
            Group { // the room's heating controls in one block, like a Settings section
                item("boost") { BoostCard(boost, onBoost, onStopBoost) }
                if (showSmart) item("smart") {
                    SmartCard(api, key, room, smart, smartError, supported) {
                        smart = it
                        onSmartChanged()
                    }
                }
            }
          }
    }
}

/** One device beside the room's hero temperature: its icon (faded when off), its target, its name under it. */
@Composable
private fun HeroDevice(icon: ImageVector, value: String, name: String, on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp), tint = if (on) primary else primary.copy(alpha = 0.5f))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, style = MaterialTheme.typography.headlineSmallEmphasized, color = primary, maxLines = 1)
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoostCard(boost: Boost?, onBoost: (Int) -> Unit, onStop: () -> Unit) {
    Section(
        stringResource(Res.string.boost_title),
        if (boost != null) stringResource(Res.string.boost_active, fmt1(boost.target), hhmm(boost.until))
        else stringResource(Res.string.boost_desc),
        FlameIcon, MaterialShapes.Sunny, accent = 1,
        container = if (boost != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        below = {
            // Tapping again while boosted restarts the timer from now.
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to Res.string.boost_30, 60 to Res.string.boost_60, 120 to Res.string.boost_120).forEach { (min, label) ->
                    FilledTonalButton({ onBoost(min) }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(stringResource(label)) }
                }
            }
        },
    ) {
        if (boost != null) FilledTonalButton(onStop, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(stringResource(Res.string.boost_stop)) }
    }
}

@Composable
private fun SmartCard(api: HeatApi, key: String, room: Room, smart: Smart?, loadError: String?, supported: Boolean, onSmart: (Smart) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var target by remember(smart) { mutableStateOf(smart?.target ?: room.target ?: 22.0) }

    fun apply(on: Boolean) {
        busy = true; msg = null
        scope.launch {
            attempt {
                api.setSmart(key, room.id, on, target)
                val now = api.smart(key, room.id) // read back, like temperatures
                onSmart(now)
                if (now.on != on) msg = getString(
                    if (room.schedule) Res.string.smart_rejected_schedule else Res.string.smart_rejected
                )
            }.onFailure { msg = errorText(it) }
            busy = false
        }
    }

    Section(
        stringResource(Res.string.smart_heating),
        when {
            smart == null -> loadError ?: stringResource(Res.string.loading)
            smart.on -> stringResource(Res.string.smart_on, smart.target?.let(::fmt1) ?: "–")
            else -> stringResource(Res.string.off)
        },
        AutoIcon, MaterialShapes.Clover4Leaf, accent = 2,
        container = if (smart?.on == true) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        below = {
            Text(
                stringResource(if (supported) Res.string.smart_desc else Res.string.smart_unsupported),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (smart != null && supported) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.smart_target), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TempStepper(target, { target = it }, buttonSize = 44.dp)
                }
                Button(
                    { apply(true) }, enabled = !busy, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(stringResource(if (smart.on) Res.string.smart_update else Res.string.smart_turn_on))
                }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        },
    ) {
        if (busy) LoadingIndicator(Modifier.size(36.dp))
        else if (smart?.on == true) FilledTonalButton({ apply(false) }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
            Text(stringResource(Res.string.turn_off))
        }
    }
}
