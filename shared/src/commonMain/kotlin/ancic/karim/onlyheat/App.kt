package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal val Heat = Color(0xFFFF5A00)
const val STEP = 0.5
const val MIN_T = 5.0
const val MAX_T = 30.0

/** Navigation 3 back stack keys. [Main] holds the bottom-bar tabs; the others are pushed on top. */
private sealed interface Screen {
    data object Main : Screen
    data class RoomDetail(val id: String) : Screen
    data object ScheduleEditor : Screen
}

private enum class Tab(val label: StringResource, val icon: ImageVector) {
    Rooms(Res.string.tab_rooms, HomeIcon),
    History(Res.string.history, ChartIcon),
    Controller(Res.string.tab_controller, TuneIcon),
}

/**
 * [requestLocalNetwork] asks for local-network access where the platform requires it (Android 17+).
 * [dynamicScheme] supplies a wallpaper-based scheme (Android 12+); null falls back to the orange palette.
 * [notify] shows a system notification; used instead of a snackbar when the app is in the background.
 * [wakeAt] runs [endExpiredBoosts] at that time (epoch ms) even if the app is closed; without it, boosts end
 * at the next poll while the app is open.
 * [backgroundAlerts] turns periodic alert checks on/off; null hides the Alerts setting.
 */
@Composable
fun App(
    requestLocalNetwork: suspend () -> Boolean = { true },
    notify: (suspend (String) -> Unit)? = null,
    wakeAt: ((Long) -> Unit)? = null,
    backgroundAlerts: ((Boolean) -> Unit)? = null,
    dynamicScheme: (@Composable (dark: Boolean) -> ColorScheme?)? = null,
) {
    OnlyHeatTheme(dynamicScheme) {
        Surface(Modifier.fillMaxSize()) {
            val settings = remember { Settings() }
            var key by remember { mutableStateOf(settings.getStringOrNull("authKey")) }
            val api = remember { HeatApi() }
            val k = key
            if (k == null) LoginScreen(api) { settings.putString("authKey", it); key = it }
            else Main(api, k, requestLocalNetwork, notify, wakeAt, backgroundAlerts) { settings.remove("authKey"); key = null }
        }
    }
}

/** Room state and writes; outlives tab switches and pushed screens. */
private class HomeModel(
    private val api: HeatApi, private val key: String,
    private val scope: CoroutineScope, val snackbar: SnackbarHostState,
    private val settings: Settings,
    private val visible: () -> Boolean, private val notify: (suspend (String) -> Unit)?,
    private val wakeAt: ((Long) -> Unit)?,
    private val onLoggedOut: () -> Unit,
) {
    var away by mutableStateOf(awaySaved(settings) != null)
    var awayTemp by mutableStateOf(settings.getDouble("away.temp", 16.0))
    var boosts by mutableStateOf(loadBoosts(settings))
        private set
    var home by mutableStateOf<Home?>(null)
    var allTarget by mutableStateOf(settings.getDouble("allTarget", 21.0)) // "All rooms" stepper
    var error by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
    val days = mutableStateOf<List<DaySummary>?>(null) // History tab, kept across navigation
    private val pending = mutableMapOf<String, Job>() // debounced writes per room id
    private var ticks = 0

    suspend fun load() {
        attempt { api.home(key) } // a cancel means polling paused, not an error
            .onSuccess { fresh ->
                // Don't let a poll overwrite a value the user is still adjusting.
                val local = home?.rooms?.associateBy { it.id }.orEmpty()
                home = fresh.copy(rooms = fresh.rooms.map { r ->
                    if (pending[r.id]?.isActive == true) r.copy(target = local[r.id]?.target) else r
                })
                error = null
                // Away and boost temperatures are temporary: don't make them what the switch restores.
                if (!away) fresh.rooms.filter { r -> boosts.none { it.room == r.id } }
                    .forEach { r -> r.target?.let { rememberTarget(r.id, it) } }
            }
            .onFailure {
                if (it is AuthExpired) onLoggedOut()
                error = errorText(it)
            }
    }

    /** One poll: end boosts whose time is up (if the background job hasn't), then reload. */
    suspend fun tick() {
        attempt { endExpiredBoosts(api, key, settings) } // load() below reports the error
        boosts = loadBoosts(settings)
        away = awaySaved(settings) != null
        load()
        // Keeps the local history gap-free while the app runs (a no-op unless 6 h have passed).
        // Not on the first tick: the slow server would make the screen's own first loads time out.
        if (ticks++ > 0) attempt { syncHistory(api, key, settings) } // next tick tries again
    }

    /** Lowers every room that is on to [awayTemp] and remembers what each one had. */
    fun goAway() = scope.launch {
        val h = home ?: return@launch
        settings.putDouble("away.temp", awayTemp)
        val boosted = boosts.associateBy { it.room }
        val saved = h.rooms.associate { it.id to (boosted[it.id]?.restore ?: it.target) } // a boost isn't "theirs"
        saveAway(settings, saved); away = true
        boosted.keys.forEach { removeBoost(settings, it) }; boosts = loadBoosts(settings)
        val heated = h.rooms.filter { saved[it.id] != null } // rooms that are off stay off
        val lowered = mutableListOf<Room>()
        try {
            heated.forEach { api.setTemp(key, it.id, awayTemp); lowered += it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Don't leave it half away: undo what was lowered, so Away shows again and can simply be retried.
            attempt { lowered.forEach { api.setTemp(key, it.id, saved[it.id]) } } // else the error below covers it
            saveAway(settings, null); away = false
            snackbar.showSnackbar(errorText(e)); load(); return@launch
        }
        delay(4000) // read back, like single rooms
        load()
        val kept = home?.rooms.orEmpty().filter { r -> heated.any { it.id == r.id } && r.target != awayTemp }
        snackbar.showSnackbar(
            if (kept.isEmpty()) getString(Res.string.away_set, fmt1(awayTemp))
            else getString(Res.string.away_kept, kept.joinToString { it.name }),
            duration = SnackbarDuration.Long,
        )
    }

    /** "I'm back": puts every room back to what it had before [goAway]. */
    fun comeBack() = scope.launch {
        val saved = awaySaved(settings) ?: return@launch
        try {
            saved.forEach { (id, t) -> if (t != null) api.setTemp(key, id, t) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            snackbar.showSnackbar(errorText(e)); load(); return@launch // still away, so it can be retried
        }
        saveAway(settings, null); away = false
        load()
        snackbar.showSnackbar(getString(Res.string.back_done))
    }

    /** +2° on top of the room's own target for [minutes], then back. A second boost just extends it. */
    fun boost(room: Room, minutes: Int) {
        val existing = boosts.find { it.room == room.id }
        val restore = if (existing != null) existing.restore else room.target // null restore = was off, keep it
        val target = ((restore ?: lastTarget(room.id)) + 2).coerceAtMost(MAX_T)
        val b = Boost(room.id, nowSeconds() + minutes * 60L, target, restore)
        putBoost(settings, b); boosts = loadBoosts(settings)
        wakeAt?.invoke(b.until * 1000)
        setTarget(room, target)
    }

    fun stopBoost(room: Room) {
        val b = boosts.find { it.room == room.id } ?: return
        removeBoost(settings, room.id); boosts = loadBoosts(settings)
        setTarget(room, b.restore)
    }

    /** What the room's switch turns it back on to: the last target it had. */
    fun lastTarget(roomId: String) = settings.getDouble("lastTarget.$roomId", 21.0)

    private fun rememberTarget(roomId: String, t: Double) {
        if (settings.getDoubleOrNull("lastTarget.$roomId") != t) settings.putDouble("lastTarget.$roomId", t)
    }

    fun refresh() = scope.launch { refreshing = true; load(); refreshing = false }

    fun setTarget(room: Room, target: Double?) {
        home = home?.let { h -> h.copy(rooms = h.rooms.map { if (it.id == room.id) it.copy(target = target) else it }) }
        if (!away && boosts.none { it.room == room.id }) target?.let { rememberTarget(room.id, it) }
        pending[room.id]?.cancel()
        pending[room.id] = scope.launch {
            delay(700) // wait for the user to stop tapping
            try {
                api.setTemp(key, room.id, target)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                pending.remove(room.id); load()
                snackbar.showSnackbar("${room.name}: ${errorText(e)}")
                return@launch
            }
            // The server says OK even when the controller ignores the value, so read it back.
            delay(4000)
            pending.remove(room.id)
            load()
            val kept = home?.rooms?.find { it.id == room.id } ?: return@launch
            if (kept.target != target) {
                val text = keptMessage(kept)
                if (notify != null && !visible()) notify(text) // user already left the app
                else snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
            }
        }
    }

    fun setAll(t: Double) = scope.launch {
        settings.putDouble("allTarget", t)
        attempt { api.setAll(key, t) }
            .onSuccess { snackbar.showSnackbar(getString(Res.string.all_set, fmt1(t))) }
            .onFailure { snackbar.showSnackbar(errorText(it)) }
        load()
    }
}

@Composable
private fun Main(
    api: HeatApi, key: String, requestLocalNetwork: suspend () -> Boolean,
    notify: (suspend (String) -> Unit)?, wakeAt: ((Long) -> Unit)?, backgroundAlerts: ((Boolean) -> Unit)?,
    onLogout: () -> Unit,
) {
    // Writes must finish even if the user leaves right after tapping (Back within the 700 ms debounce),
    // so the model's scope isn't tied to the composition.
    // ponytail: never cancelled, lives as long as the process; fine for a few short writes.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val model = remember(key) {
        HomeModel(api, key, scope, snackbar, Settings(), { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }, notify, wakeAt, onLogout)
    }
    // Poll only while the app is on screen; resuming refreshes right away.
    LaunchedEffect(model) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { model.tick(); delay(30_000) } } }

    val backStack = remember { mutableStateListOf<Screen>(Screen.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.Rooms) }
    val host = model.home?.ip ?: "192.168.42.1"

    // Back from another tab returns to Rooms before leaving the app. Lives here, not in MainTabs, because
    // MainTabs stays composed during a push transition and would steal the back press from NavDisplay.
    BackHandler(tab != Tab.Rooms && backStack.size == 1) { tab = Tab.Rooms }

    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedScope provides this) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sharedTransitionScope = this,
        entryProvider = { screen ->
            when (screen) {
                Screen.Main -> NavEntry(screen) {
                    MainTabs(tab, { tab = it }, model, api, key, host, requestLocalNetwork, backgroundAlerts, { clearAccountState(Settings()); onLogout() }) {
                        backStack += it
                    }
                }
                is Screen.RoomDetail -> NavEntry(screen) {
                    val room = model.home?.rooms?.find { it.id == screen.id }
                    if (room != null) RoomScreen(
                        api, key, room, model.boosts.find { it.room == room.id },
                        onBoost = { model.boost(room, it) }, onStopBoost = { model.stopBoost(room) },
                    ) { backStack.removeLastOrNull() }
                }
                Screen.ScheduleEditor -> NavEntry(screen) {
                    ScheduleScreen(host, requestLocalNetwork) { backStack.removeLastOrNull() }
                }
            }
        },
    )
    }
    }
}

internal val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Container transform: the element with the same [key] on the next screen grows out of this one. Only valid inside a NavEntry. */
@Composable
internal fun Modifier.shared(key: String): Modifier {
    val scope = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedContentScope.current
    val clip = with(scope) { OverlayClip(MaterialTheme.shapes.extraLarge) }
    return with(scope) {
        sharedBounds(
            rememberSharedContentState(key), anim,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = clip,
        )
    }
}

@Composable
private fun MainTabs(
    tab: Tab, onTab: (Tab) -> Unit, model: HomeModel, api: HeatApi, key: String, host: String,
    requestLocalNetwork: suspend () -> Boolean, backgroundAlerts: ((Boolean) -> Unit)?, onLogout: () -> Unit,
    open: (Screen) -> Unit,
) {
    val tabContent = @Composable {
      // M3 fade-through: old tab fades out quickly, new one fades and scales in.
      AnimatedContent(tab, transitionSpec = {
          (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f)) togetherWith
              fadeOut(tween(90))
      }) { tab ->
        when (tab) {
            Tab.Rooms -> RoomsTab(model) { open(Screen.RoomDetail(it)) }
            Tab.History -> OverviewScreen(api, key, model.home?.rooms, model.days) { open(Screen.RoomDetail(it)) }
            Tab.Controller -> ControllerTab(
                model.home?.device, host, requestLocalNetwork,
                cloudStatus = { attempt { api.home(key) }.exceptionOrNull() },
                snackbar = model.snackbar, backgroundAlerts = backgroundAlerts, onLogout = onLogout,
            ) { open(Screen.ScheduleEditor) }
        }
      }
    }

    if (expanded) {
        // Wide window (desktop, tablet landscape): navigation rail on the side.
        Row(Modifier.fillMaxSize()) {
            WideNavigationRail {
                Tab.entries.forEach { t ->
                    WideNavigationRailItem(
                        railExpanded = false, // compact rail: icon with label beneath
                        selected = t == tab,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
            Scaffold(
                snackbarHost = { SnackbarHost(model.snackbar) },
                contentWindowInsets = WindowInsets(0),
                modifier = Modifier.weight(1f),
            ) { pad -> Box(Modifier.padding(pad)) { tabContent() } }
        }
    } else {
        Scaffold(
            bottomBar = {
                ShortNavigationBar {
                    Tab.entries.forEach { t ->
                        ShortNavigationBarItem(
                            selected = t == tab,
                            onClick = { onTab(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(model.snackbar) },
            contentWindowInsets = WindowInsets(0), // each tab's own Scaffold handles the top insets
        ) { pad -> Box(Modifier.padding(pad)) { tabContent() } }
    }
}

/** Insets for a tab's own Scaffold: the bottom is already taken by the navigation bar. */
internal val TabInsets @Composable get() =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@Composable
private fun RoomsTab(model: HomeModel, openRoom: (String) -> Unit) {
    val scroll = collapsingBar()
    val pull = rememberPullToRefreshState()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(model.home?.device ?: "OnlyHeat") },
                subtitle = {
                    val h = model.home ?: return@LargeFlexibleTopAppBar
                    val heating = h.rooms.count { it.heating && it.target != null }
                    Text(
                        listOfNotNull(
                            h.outdoorTemp?.let { stringResource(Res.string.outside, fmt1(it)) },
                            pluralStringResource(Res.plurals.rooms, h.rooms.size, h.rooms.size),
                            if (heating > 0) stringResource(Res.string.heating_count, heating)
                            else stringResource(Res.string.idle),
                        ).joinToString(" · ")
                    )
                },
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = TabInsets,
    ) { pad ->
        PullToRefreshBox(
            model.refreshing, { model.refresh() }, Modifier.padding(pad).fillMaxSize(), state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(pull, model.refreshing, Modifier.align(Alignment.TopCenter))
            },
        ) {
            val h = model.home
            when {
                h == null && model.error != null -> Message(model.error!!) { model.refresh() }
                h == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
                // Adaptive: one column on a phone, two or more on wider windows.
                // The app bar listens inside the refresh box, so a downward drag first expands the
                // collapsed bar and only then pulls to refresh.
                else -> BoxWithConstraints(Modifier.fillMaxSize().collapsing(scroll)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 340.dp),
                        contentPadding = cappedPadding(1200.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val span: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) =
                            { GridItemSpan(maxLineSpan) }
                        model.error?.let { e -> item(span = span) { Text(e, color = MaterialTheme.colorScheme.error) } }
                        if (model.away) item(span = span) { AwayOnCard(model.awayTemp, Modifier.springIn(0), model::comeBack) }
                        if (h.rooms.size > 1 && !model.away) item {
                            QuickCard(stringResource(Res.string.all_rooms), model.allTarget, { model.allTarget = it }, stringResource(Res.string.set_all), Modifier.springIn(0)) { model.setAll(model.allTarget) }
                        }
                        if (!model.away) item {
                            QuickCard(stringResource(Res.string.away_title), model.awayTemp, { model.awayTemp = it }, stringResource(Res.string.away_go), Modifier.springIn(1), model::goAway)
                        }
                        itemsIndexed(h.rooms, key = { _, r -> r.id }) { i, r ->
                            RoomCard(r, model.boosts.any { it.room == r.id }, { model.lastTarget(r.id) }, Modifier.springIn(i + 2), onOpen = { openRoom(r.id) }) { t -> model.setTarget(r, t) }
                        }
                        if (h.rooms.isEmpty()) item(span = span) { Text(stringResource(Res.string.no_rooms)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(api: HeatApi, onKey: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        MorphBlob(true, MaterialTheme.colorScheme.primaryContainer, Modifier.size(96.dp)) {
            Text("°", style = MaterialTheme.typography.displayMediumEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text("OnlyHeat", style = MaterialTheme.typography.displayMediumEmphasized, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.login_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.pin_where), style = MaterialTheme.typography.titleMediumEmphasized)
                listOf(Res.string.pin_step1, Res.string.pin_step2, Res.string.pin_step3, Res.string.pin_step4)
                    .forEach { Text(stringResource(it)) }
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            pin, { pin = it.trim(); error = null },
            label = { Text(stringResource(Res.string.pin_label)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    attempt { api.login(pin) }
                        .onSuccess(onKey)
                        .onFailure {
                            error = if (it is ApiException && it.message?.contains("PIN") == true) getString(Res.string.pin_wrong)
                            else errorText(it)
                        }
                    busy = false
                }
            },
            enabled = pin.isNotEmpty() && !busy,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
        ) {
            if (busy) LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(Res.string.connect), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RoomCard(room: Room, boosted: Boolean, lastTarget: () -> Double, modifier: Modifier, onOpen: () -> Unit, onTarget: (Double?) -> Unit) {
    val on = room.target != null
    val active = room.heating && on
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (on) roomContainer(room.id) else scheme.surfaceContainerLow, // heating shows as fire on top
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    PressCard(
        onOpen, modifier.fillMaxWidth().shared("room-${room.id}"),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.flames(active, room.id).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleLargeEmphasized)
                    val status = listOfNotNull(
                        if (active) stringResource(Res.string.status_heating) else null,
                        room.humidity?.let { stringResource(Res.string.humidity, "${it.toInt()}%") },
                        if (room.smart) stringResource(Res.string.smart) else null,
                        if (room.schedule) stringResource(Res.string.schedule) else null,
                        if (boosted) stringResource(Res.string.boost) else null,
                    ).joinToString(" · ")
                    if (status.isNotEmpty()) Text(
                        status, style = MaterialTheme.typography.labelLarge,
                        color = LocalContentColor.current.copy(alpha = if (active) 1f else 0.75f),
                    )
                }
                Switch(on, { onTarget(if (it) lastTarget() else null) })
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RollingTemp(room.current, MaterialTheme.typography.displayMedium, Modifier.weight(1f))
                if (on) TempStepper(room.target!!, onTarget, label = stringResource(Res.string.target))
                else Text(stringResource(Res.string.off), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** One-row card: a stepper labelled [title] and an action button. */
@Composable
private fun QuickCard(title: String, t: Double, onChange: (Double) -> Unit, action: String, modifier: Modifier, onAction: () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TempStepper(t, onChange, buttonSize = 40.dp, label = title)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
        }
    }
}

@Composable
private fun AwayOnCard(temp: Double, modifier: Modifier, onBack: () -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.away_on_title), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    stringResource(Res.string.away_on_desc, fmt1(temp)),
                    style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.away_back)) }
        }
    }
}

/** A failed load or empty state: icon in a cookie, the text, and Retry when there's something to retry. */
@Composable
internal fun Message(
    text: String, modifier: Modifier = Modifier.fillMaxSize(), icon: ImageVector = CloudOffIcon, onRetry: (() -> Unit)?,
) {
    Column(modifier.padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            Alignment.Center,
        ) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.height(20.dp))
        Text(
            text, Modifier.widthIn(max = 360.dp), style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            Button(onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
        }
    }
}

/** Why the controller kept [kept]'s target instead of the one just sent. Also used by the Android widget. */
suspend fun keptMessage(kept: Room): String {
    val shown = kept.target?.let { "${fmt1(it)}°" } ?: getString(Res.string.off_lower)
    return if (kept.schedule) getString(Res.string.kept_schedule, kept.name, shown)
    else "${kept.name}: " + getString(Res.string.kept_other, shown)
}

/** User-facing text for a failure. Server texts are English-only; the API ignores `lang`. */
internal suspend fun errorText(e: Throwable): String {
    log("showing error to user", e)
    return when (e) {
        is ApiException -> e.message ?: getString(Res.string.failed)
        is ServerUnreachable -> getString(Res.string.server_unreachable)
        is ControllerUnreachable -> getString(Res.string.schedule_unreachable, e.host)
        is WrongDashboardPin -> getString(Res.string.wrong_dashboard_pin)
        else -> getString(Res.string.failed)
    }
}

fun fmt1(t: Double): String {
    val tenths = kotlin.math.round(t * 10).toLong()
    val a = kotlin.math.abs(tenths)
    return (if (tenths < 0) "-" else "") + "${a / 10}.${a % 10}"
}
