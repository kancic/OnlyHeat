package ancic.karim.onlyheat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.text.TextAlign
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

// Glance builds receivers and callbacks itself, so they look up the shared instances.
private val api: HeatApi get() = KoinPlatform.getKoin().get()

/** Bumped to make the widget fetch fresh room data. */
private val Version = longPreferencesKey("version")
/** Target shown right after a tap, until the write has been confirmed. */
private fun pendingKey(roomId: String) = doublePreferencesKey("pending.$roomId")
private fun pendingAtKey(roomId: String) = longPreferencesKey("pendingAt.$roomId")
/** Longer than a tap's whole write (debounce + retries + read-back); after that the callback must have died. */
private const val PENDING_TTL_MS = 90_000L

private fun Preferences.pendingTarget(roomId: String) = this[pendingKey(roomId)]
    ?.takeIf { System.currentTimeMillis() - (this[pendingAtKey(roomId)] ?: 0) < PENDING_TTL_MS }
private val RoomId = ActionParameters.Key<String>("roomId")
private val RoomName = ActionParameters.Key<String>("roomName")
private val Target = ActionParameters.Key<Double>("target")
private val ScheduleTipRequest = ActionParameters.Key<Boolean>(SCHEDULE_TIP_EXTRA)

/** Home-screen widget: every room's temperature, with −/+ for the target. Tapping elsewhere opens the app. */
class HeatWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact // lay out for the real size, see Content

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fetch before composing: provideGlance runs as a worker the system keeps alive, while an effect
        // inside the composition can die with the process (seen: killed as a cached process mid-fetch).
        val initial = fetch()
        provideContent {
            val prefs = currentState<Preferences>()
            var state by remember { mutableStateOf(initial) }
            val version = prefs[Version]
            val firstVersion = remember { version }
            // A bump while this session is still running (app went to background, a write finished).
            LaunchedEffect(version) { if (version != firstVersion) state = fetch() }
            GlanceTheme { Content(context, state, prefs) }
        }
    }
}

/** [key] null = logged out; [home] null with a key = the fetch failed. */
private class WidgetState(val key: String?, val home: Home?)

private suspend fun fetch(): WidgetState {
    val key = savedAuthKey(KoinPlatform.getKoin().get()) ?: return WidgetState(null, null)
    val home = attempt { api.home(key) }
        .onFailure { android.util.Log.w("OnlyHeat", "widget refresh failed", it) }
        .getOrNull()
    return WidgetState(key, home)
}

class HeatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = HeatWidget()
}

/** Make every placed widget fetch fresh data, e.g. after the app changed something. */
suspend fun refreshWidgets(context: Context) {
    GlanceAppWidgetManager(context).getGlanceIds(HeatWidget::class.java).forEach { bump(context, it) }
}

private suspend fun bump(context: Context, id: GlanceId, change: (MutablePreferences) -> Unit = {}) {
    updateAppWidgetState(context, id) { change(it); it[Version] = (it[Version] ?: 0L) + 1 }
    HeatWidget().update(context, id)
}

private val CardHeight = 56.dp // name line + stepper row
private val CardMinWidth = 150.dp
private val Gap = 6.dp

@Composable
private fun Content(context: Context, state: WidgetState, prefs: Preferences) {
    val home = state.home
    val size = LocalSize.current
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp)
            .padding(8.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        val message = when {
            state.key == null -> context.getString(R.string.widget_log_in)
            home == null -> context.getString(R.string.widget_failed)
            home.rooms.isEmpty() -> context.getString(R.string.widget_no_rooms)
            else -> null
        }
        if (home == null || message != null) {
            Header(home)
            Text(message.orEmpty(), GlanceModifier.padding(8.dp), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
            return@Column
        }

        // Fit everything, never scroll: as many columns as the width allows, as many rows as the height allows.
        val w = size.width - 16.dp
        val h = size.height - 16.dp
        val cols = maxOf(1, ((w + Gap) / (CardMinWidth + Gap)).toInt())
        val maxRows = maxOf(1, ((h + Gap) / (CardHeight + Gap)).toInt())
        val rows = minOf(maxRows, (home.rooms.size + cols - 1) / cols)
        val slots = rows * cols
        val overflow = home.rooms.size > slots
        val shown = if (overflow) home.rooms.take(slots - 1) else home.rooms
        if (h - (CardHeight + Gap) * rows >= 22.dp) Header(home)

        val cells: List<Room?> = shown + if (overflow) listOf(null) else emptyList()
        cells.chunked(cols).forEachIndexed { r, line ->
            if (r > 0) Spacer(GlanceModifier.height(Gap))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                line.forEachIndexed { c, room ->
                    if (c > 0) Spacer(GlanceModifier.width(Gap))
                    val cell = GlanceModifier.defaultWeight().fillMaxHeight()
                    if (room == null) More(context, home.rooms.size - shown.size, cell)
                    else RoomCard(room, prefs.pendingTarget(room.id) ?: room.target, cell)
                }
                repeat(cols - line.size) { Spacer(GlanceModifier.width(Gap)); Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun Header(home: Home?) {
    Text(
        listOfNotNull(home?.device ?: "OnlyHeat", home?.outdoorTemp?.let { "${fmt1(it)}°" }).joinToString(" · "),
        GlanceModifier.padding(start = 6.dp, bottom = 4.dp),
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

/** Same per-room colour as the app's cards. */
@Composable
private fun roomColors(id: String): Pair<ColorProvider, ColorProvider> {
    val c = GlanceTheme.colors
    return when ((id.hashCode() and Int.MAX_VALUE) % 3) {
        0 -> c.primaryContainer to c.onPrimaryContainer
        1 -> c.tertiaryContainer to c.onTertiaryContainer
        else -> c.secondaryContainer to c.onSecondaryContainer
    }
}

@Composable
private fun RoomCard(room: Room, target: Double?, modifier: GlanceModifier) {
    val on = target != null
    val (bg, fg) = if (on) roomColors(room.id) else GlanceTheme.colors.surfaceVariant to GlanceTheme.colors.onSurfaceVariant
    Column(
        modifier.cornerRadius(16.dp).background(bg).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                room.name + if (room.heating && on) " 🔥" else "",
                GlanceModifier.defaultWeight(),
                style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            room.current?.let { Text("${fmt1(it)}°", style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
        }
        Spacer(GlanceModifier.height(2.dp))
        if (target == null) Text(
            "–", GlanceModifier.fillMaxWidth().height(28.dp),
            style = TextStyle(color = fg, fontSize = 16.sp, textAlign = TextAlign.Center),
        )
        else Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", room, (target - STEP).coerceAtLeast(MIN_T), enabled = !room.schedule)
            Text(
                "${fmt1(target)}°", GlanceModifier.defaultWeight(),
                style = TextStyle(color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                maxLines = 1,
            )
            StepButton("+", room, (target + STEP).coerceAtMost(MAX_T), enabled = !room.schedule)
        }
    }
}

@Composable
private fun More(context: Context, count: Int, modifier: GlanceModifier) {
    Box(modifier.cornerRadius(16.dp).background(GlanceTheme.colors.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(
            context.getString(R.string.widget_more, count),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun StepButton(label: String, room: Room, target: Double, enabled: Boolean) {
    val colors = GlanceTheme.colors
    val button = GlanceModifier.width(40.dp).height(28.dp).cornerRadius(14.dp)
    val modifier = if (enabled) {
        button.background(colors.surface)
            .clickable(actionRunCallback<StepTarget>(actionParametersOf(RoomId to room.id, RoomName to room.name, Target to target)))
    } else {
        button.background(colors.surfaceVariant)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(ScheduleTipRequest to true)))
    }
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/** Same flow as the app: show at once, debounce taps, write, then read back and tell the user if it was not kept. */
class StepTarget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withContext(Dispatchers.Main.immediate) {
            val roomId = parameters[RoomId] ?: return@withContext
            val target = parameters[Target] ?: return@withContext
            val settings = KoinPlatform.getKoin().get<com.russhwolf.settings.Settings>()
            val key = savedAuthKey(settings) ?: return@withContext
            val session = noticeSession(settings)
            val pending = pendingKey(roomId)
            val pendingAt = pendingAtKey(roomId)
            var revision = 0L
            updateAppWidgetState(context, glanceId) {
                revision = maxOf(System.currentTimeMillis(), (it[pendingAt] ?: 0) + 1)
                it[pending] = target
                it[pendingAt] = revision
            }
            suspend fun stillOurs() =
                getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[pendingAt] == revision &&
                    savedAuthKey(settings) == key && noticeSession(settings) == session

            HeatWidget().update(context, glanceId)
            delay(700) // wait for the user to stop tapping; a newer tap takes over
            if (!stillOurs()) return@withContext
            saveWriteNotice(settings, roomId, null)
            // Also a notice in the app, which waits there when notifications aren't allowed.
            suspend fun report(text: String) {
                if (!stillOurs()) return
                saveWriteNotice(settings, roomId, text)
                context.notifyHeat(text)
            }
            try {
                api.setTemp(key, roomId, target)
                delay(4_000) // the server says OK even when the controller ignores the value
                if (!stillOurs()) return@withContext
                val kept = api.home(key).rooms.find { it.id == roomId }
                if (kept != null && kept.target != target) report(keptMessage(kept))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("OnlyHeat", "widget write failed", e)
                report(listOfNotNull(parameters[RoomName], context.getString(R.string.widget_write_failed)).joinToString(": "))
            }
            if (!stillOurs()) return@withContext
            bump(context, glanceId) {
                if (it[pendingAt] == revision) {
                    it.remove(pending)
                    it.remove(pendingAt)
                }
            }
        }
}
