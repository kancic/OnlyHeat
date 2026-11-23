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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
internal val NightSky = Color(0xFF353D70)
internal val NightInk = Color(0xFFE6E8FF)
const val STEP = 0.5
const val MIN_T = 5.0
const val MAX_T = 30.0

/** Navigation 3 back stack keys. [Main] holds the bottom-bar tabs; the others are pushed on top. */
private sealed interface Screen {
    data object Main : Screen
    data class RoomDetail(val id: String) : Screen
    data object ScheduleEditor : Screen
}

private val backStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { if (it is Screen.RoomDetail) "room:${it.id}" else it.toString() } },
    restore = { saved ->
        saved.map { s ->
            when {
                s.startsWith("room:") -> Screen.RoomDetail(s.removePrefix("room:")); s == "ScheduleEditor" -> Screen.ScheduleEditor; else -> Screen.Main
            }
        }
            .toMutableStateList()
    },
)

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
 * [statusBarOnDark] switches the status bar icons to light while a screen draws something dark behind them.
 */
@Composable
fun App(
    requestLocalNetwork: suspend () -> Boolean = { true },
    notify: (suspend (String) -> Unit)? = null,
    wakeAt: ((Long) -> Unit)? = null,
    backgroundAlerts: ((Boolean) -> Unit)? = null,
    dynamicScheme: (@Composable (dark: Boolean) -> ColorScheme?)? = null,
    statusBarOnDark: (Boolean) -> Unit = {},
) {
    OnlyHeatTheme(dynamicScheme) {
        Surface(Modifier.fillMaxSize()) {
            val settings = remember { Settings() }
            var key by remember { mutableStateOf(settings.getStringOrNull("authKey")) }
            val api = remember { HeatApi() }
            val k = key
            if (k == null) LoginScreen(
                api,
                requestLocalNetwork,
                statusBarOnDark
            ) { settings.putString("authKey", it); key = it }
            else Main(api, k, requestLocalNetwork, notify, wakeAt, backgroundAlerts) {
                settings.remove("authKey")
                key = null
            }
        }
    }
}

/**
 * Room state and writes; outlives tab switches, pushed screens and activity recreation.
 * The callbacks come from the current composition (they hold the activity), so [Main] re-assigns them
 * on every composition instead of the model keeping the first activity's.
 */
private class HomeModel(
    private val api: HeatApi,
    private val key: String,
    private val settings: Settings
) : ViewModel() {
    // Writes must finish even if the user leaves right after tapping (Back within the 700 ms debounce),
    // so not viewModelScope, which is cancelled when the activity finishes.
    // ponytail: never cancelled, lives as long as the process; fine for a few short writes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val snackbar = SnackbarHostState()
    var visible: () -> Boolean = { true }
    var notify: (suspend (String) -> Unit)? = null
    var wakeAt: ((Long) -> Unit)? = null
    var onLoggedOut: () -> Unit = {}
    var away by mutableStateOf(awaySaved(settings) != null)
    var awayBusy by mutableStateOf(false) // Away / I'm back writing; the cards switch once it's done
        private set
    var awayTemp by mutableStateOf(settings.getDouble("away.temp", 16.0))
    var boosts by mutableStateOf(loadBoosts(settings))
        private set
    var home by mutableStateOf<Home?>(null)
    var allTarget by mutableStateOf(settings.getDouble("allTarget", 21.0)) // "All rooms" stepper
    var error by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
    var weather by mutableStateOf<Weather?>(null) // dashboard's AccuWeather, when the cloud has no TS7 value
    private var weatherAt = 0L
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
                if (!away && !awayBusy) fresh.rooms.filter { r -> boosts.none { it.room == r.id } }
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
        if (!awayBusy) away =
            awaySaved(settings) != null // goAway saves first, but shows away only once done
        load()
        loadWeather(force = false)
        // Keeps the local history gap-free while the app runs (a no-op unless 6 h have passed).
        // Not on the first tick: the slow server would make the screen's own first loads time out.
        if (ticks++ > 0) attempt { syncHistory(api, key, settings) } // next tick tries again
    }

    // ponytail: one dashboard login per 15 min (or on pull to refresh), only with a saved PIN.
    // Unreachable controller (off the home network) hides the weather instead of showing a stale one.
    private suspend fun loadWeather(force: Boolean) {
        val h = home ?: return
        if (h.outdoorTemp != null || (!force && nowSeconds() - weatherAt <= 15 * 60)) return
        val pin = savedPin(settings) ?: return
        weatherAt = nowSeconds()
        attempt { LocalApi(h.ip ?: HOTSPOT, pin, settings) { null }.weather() }
            .onSuccess { weather = it }
            .onFailure { weather = null; log("dashboard weather failed", it) }
    }

    /** Lowers every room that is on to [awayTemp] and remembers what each one had. */
    fun goAway() = scope.launch {
        val h = home ?: return@launch
        if (awayBusy) return@launch
        awayBusy = true
        settings.putDouble("away.temp", awayTemp)
        val boosted = boosts.associateBy { it.room }
        val saved = h.rooms.associate {
            it.id to (boosted[it.id]?.restore ?: it.target)
        } // a boost isn't "theirs"
        saveAway(settings, saved) // first, so a crash mid-way can still come back
        boosted.keys.forEach { removeBoost(settings, it) }; boosts = loadBoosts(settings)
        val heated = h.rooms.filter { saved[it.id] != null } // rooms that are off stay off
        val lowered = mutableListOf<Room>()
        try {
            heated.forEach { api.setTemp(key, it.id, awayTemp); lowered += it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Don't leave it half away: undo what was lowered, so Away shows again and can simply be retried.
            attempt {
                lowered.forEach {
                    api.setTemp(
                        key,
                        it.id,
                        saved[it.id]
                    )
                }
            } // else the error below covers it
            saveAway(settings, null); load(); awayBusy = false
            snackbar.showSnackbar(errorText(e)); return@launch
        }
        delay(4000) // read back, like single rooms
        load()
        away = true; awayBusy = false
        val kept = home?.rooms.orEmpty()
            .filter { r -> heated.any { it.id == r.id } && r.target != awayTemp }
        snackbar.showSnackbar(
            if (kept.isEmpty()) getString(Res.string.away_set, fmt1(awayTemp))
            else getString(Res.string.away_kept, kept.joinToString { it.name }),
            duration = SnackbarDuration.Long,
        )
    }

    /** "I'm back": puts every room back to what it had before [goAway]. */
    fun comeBack() = scope.launch {
        val saved = awaySaved(settings) ?: return@launch
        if (awayBusy) return@launch
        awayBusy = true
        try {
            saved.forEach { (id, t) -> if (t != null) api.setTemp(key, id, t) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            load(); awayBusy = false
            snackbar.showSnackbar(errorText(e)); return@launch // still away, so it can be retried
        }
        saveAway(settings, null)
        delay(4000) // read back, like goAway
        load()
        away = false; awayBusy = false
        snackbar.showSnackbar(getString(Res.string.back_done))
    }

    /** +2° on top of the room's own target for [minutes], then back. A second boost just extends it. */
    fun boost(room: Room, minutes: Int) {
        val existing = boosts.find { it.room == room.id }
        val restore =
            if (existing != null) existing.restore else room.target // null restore = was off, keep it
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
        if (settings.getDoubleOrNull("lastTarget.$roomId") != t) settings.putDouble(
            "lastTarget.$roomId",
            t
        )
    }

    fun refresh() =
        scope.launch { refreshing = true; load(); refreshing = false; loadWeather(force = true) }

    fun setTarget(room: Room, target: Double?) {
        home =
            home?.let { h -> h.copy(rooms = h.rooms.map { if (it.id == room.id) it.copy(target = target) else it }) }
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
                val n = notify
                if (n != null && !visible()) n(text) // user already left the app
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
    api: HeatApi,
    key: String,
    requestLocalNetwork: suspend () -> Boolean,
    notify: (suspend (String) -> Unit)?,
    wakeAt: ((Long) -> Unit)?,
    backgroundAlerts: ((Boolean) -> Unit)?,
    onLogout: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val model = viewModel(key = "home:$key") { HomeModel(api, key, Settings()) }
    model.visible = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    model.notify = notify
    model.wakeAt = wakeAt
    model.onLoggedOut = onLogout
    // Poll only while the app is on screen; resuming refreshes right away.
    LaunchedEffect(model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                model.tick(); delay(30_000)
            }
        }
    }

    // Saveable so a recreation (theme change, process death) reopens the same screen instead of Rooms.
    val backStack =
        rememberSaveable(saver = backStackSaver) { mutableStateListOf<Screen>(Screen.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.Rooms) }
    val host = model.home?.ip ?: HOTSPOT

    // Back from another tab returns to Rooms before leaving the app. Lives here, not in MainTabs, because
    // MainTabs stays composed during a push transition and would steal the back press from NavDisplay.
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = tab != Tab.Rooms && backStack.size == 1,
        onBackCompleted = { tab = Tab.Rooms }
    )

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                // Gives each screen its own ViewModels, cleared when it's popped (HomeModel above is the activity's).
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                sharedTransitionScope = this,
                entryProvider = { screen ->
                    when (screen) {
                        Screen.Main -> NavEntry(screen) {
                            MainTabs(
                                tab,
                                { tab = it },
                                model,
                                api,
                                key,
                                host,
                                requestLocalNetwork,
                                backgroundAlerts,
                                { clearAccountState(Settings()); onLogout() }) {
                                backStack += it
                            }
                        }

                        is Screen.RoomDetail -> NavEntry(screen) {
                            val room = model.home?.rooms?.find { it.id == screen.id }
                            if (room != null) RoomScreen(
                                api,
                                key,
                                room,
                                model.boosts.find { it.room == room.id },
                                onBoost = { model.boost(room, it) },
                                onStopBoost = { model.stopBoost(room) },
                            ) { backStack.removeLastOrNull() }
                        }

                        Screen.ScheduleEditor -> NavEntry(screen) {
                            ScheduleScreen(
                                host,
                                requestLocalNetwork
                            ) { backStack.removeLastOrNull() }
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
    tab: Tab,
    onTab: (Tab) -> Unit,
    model: HomeModel,
    api: HeatApi,
    key: String,
    host: String,
    requestLocalNetwork: suspend () -> Boolean,
    backgroundAlerts: ((Boolean) -> Unit)?,
    onLogout: () -> Unit,
    open: (Screen) -> Unit,
) {
    val tabContent = @Composable {
        // M3 fade-through: old tab fades out quickly, new one fades and scales in.
        AnimatedContent(tab, transitionSpec = {
            (fadeIn(tween(210, delayMillis = 90)) + scaleIn(
                tween(210, delayMillis = 90),
                initialScale = 0.92f
            )) togetherWith
                    fadeOut(tween(90))
        }) { tab ->
            when (tab) {
                Tab.Rooms -> RoomsTab(model) { open(Screen.RoomDetail(it)) }
                Tab.History -> OverviewScreen(
                    api,
                    key,
                    model.home?.rooms,
                    model.days
                ) { open(Screen.RoomDetail(it)) }

                Tab.Controller -> ControllerTab(
                    model.home?.device,
                    host,
                    requestLocalNetwork,
                    cloudStatus = { attempt { api.home(key) }.exceptionOrNull() },
                    snackbar = model.snackbar,
                    backgroundAlerts = backgroundAlerts,
                    onLogout = onLogout,
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
internal val TabInsets
    @Composable get() =
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** Outside temperature with the weather icon; the full "Outside …" text is for screen readers. */
@Composable
private fun WeatherPill(emoji: String?, outside: Double, big: Boolean) {
    val label = stringResource(Res.string.outside, fmt1(outside))
    Surface(
        Modifier.padding(start = 12.dp, end = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            emoji?.let {
                Text(
                    it,
                    style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "${fmt1(outside)}°",
                style = if (big) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized
            )
        }
    }
}

@Composable
private fun RoomsTab(model: HomeModel, openRoom: (String) -> Unit) {
    val scroll = collapsingBar()
    val pull = rememberPullToRefreshState()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                // Title, subtitle and weather in one row, so the weather sits centred beside both, expanded or collapsed.
                // The bar composes this slot twice with different text styles; the subtitle follows the size it gets.
                title = {
                    val h = model.home
                    val expanded =
                        LocalTextStyle.current.fontSize > MaterialTheme.typography.titleLarge.fontSize
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h?.device ?: "OnlyHeat")
                            if (h != null) {
                                val heating = h.rooms.count { it.heating && it.target != null }
                                Text(
                                    listOfNotNull(
                                        pluralStringResource(
                                            Res.plurals.rooms,
                                            h.rooms.size,
                                            h.rooms.size
                                        ),
                                        if (heating > 0) stringResource(
                                            Res.string.heating_count,
                                            heating
                                        )
                                        else stringResource(Res.string.idle),
                                    ).joinToString(" · "),
                                    style = if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        val outside = h?.outdoorTemp ?: model.weather?.temp
                        if (outside != null) WeatherPill(
                            model.weather?.emoji,
                            outside,
                            big = expanded
                        )
                    }
                },
                // The subtitle lives in the title slot, so keep the height the bar gives a real subtitle.
                expandedHeight = TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight,
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = TabInsets,
    ) { pad ->
        PullToRefreshBox(
            model.refreshing,
            { model.refresh() },
            Modifier.padding(pad).fillMaxSize(),
            state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    pull,
                    model.refreshing,
                    Modifier.align(Alignment.TopCenter)
                )
            },
        ) {
            when (val h = model.home) {
                null if model.error != null -> Message(model.error!!) { model.refresh() }
                null -> Box(
                    Modifier.fillMaxSize(),
                    Alignment.Center
                ) { LoadingIndicator(Modifier.size(64.dp)) }
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
                        model.error?.let { e ->
                            item(span = span) {
                                Text(
                                    e,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (model.away) item(span = span) {
                            AwayOnCard(
                                model.awayTemp,
                                model.awayBusy,
                                Modifier.springIn(0),
                                model::comeBack
                            )
                        }
                        if (h.rooms.size > 1 && !model.away) item {
                            QuickCard(
                                stringResource(Res.string.all_rooms),
                                model.allTarget,
                                { model.allTarget = it },
                                stringResource(Res.string.set_all),
                                Modifier.springIn(0)
                            ) { model.setAll(model.allTarget) }
                        }
                        if (!model.away) item {
                            QuickCard(
                                stringResource(Res.string.away_title),
                                model.awayTemp,
                                { model.awayTemp = it },
                                stringResource(Res.string.away_go),
                                Modifier.springIn(1),
                                model.awayBusy,
                                model::goAway
                            )
                        }
                        itemsIndexed(h.rooms, key = { _, r -> r.id }) { i, r ->
                            RoomCard(
                                r,
                                model.boosts.any { it.room == r.id },
                                { model.lastTarget(r.id) },
                                Modifier.springIn(i + 2),
                                onOpen = { openRoom(r.id) }) { t -> model.setTarget(r, t) }
                        }
                        if (h.rooms.isEmpty()) item(span = span) { Text(stringResource(Res.string.no_rooms)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    api: HeatApi,
    requestLocalNetwork: suspend () -> Boolean,
    statusBarOnDark: (Boolean) -> Unit,
    onKey: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings = remember { Settings() }
    val controllerPicker = remember { LanControllerPicker(settings) }
    var way by rememberSaveable { mutableStateOf<LoginWay?>(null) }
    var dashboardPin by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf<DashboardTarget?>(null) }
    var pendingDashboard by remember { mutableStateOf<VerifiedDashboard?>(null) }
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = way != null && !busy,
        onBackCompleted = { way = null; error = null }
    )
    suspend fun login(submittedPin: String) {
        attempt { api.login(submittedPin) }
            .onSuccess { settings.saveLoginDashboard(submittedPin, pendingDashboard); onKey(it) }
            .onFailure {
                error =
                    if (it is ApiException && it.message?.contains("PIN") == true) getString(Res.string.pin_wrong)
                    else errorText(it)
            }
    }

    fun connect() {
        if (busy) return
        val submittedPin = pin
        busy = true
        scope.launch { login(submittedPin); busy = false }
    }

    // The other way in: pick the controller (its own Wi-Fi or the home Wi-Fi), then read the API PIN from its dashboard.
    fun onLan(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            if (!requestLocalNetwork()) error = getString(Res.string.local_permission_denied)
            else attempt { block() }.onFailure { error = errorText(it) }
            busy = false
        }
    }

    fun findController(change: Boolean) = onLan {
        controllerPicker.find(HOTSPOT, change, save = false)?.let {
            if (it != controller) dashboardPin = ""
            controller = it
        }
    }

    fun fromController() {
        val selected = controller ?: return
        val submittedPin = dashboardPin
        onLan {
            val verified = try {
                LocalApi(HOTSPOT, submittedPin, settings) { null }.apiPin(selected)
            } catch (e: ControllerUnreachable) {
                controller = null
                dashboardPin = ""
                throw e
            }
            pendingDashboard = verified
            pin = verified.apiPin
            login(verified.apiPin)
            // Show cloud errors beside the API PIN so it can be retried.
            if (error != null) way = LoginWay.Manual
        }
    }

    val form: @Composable (Modifier) -> Unit = { modifier ->
        LoginForm(
            modifier,
            way,
            { way = it; error = null },
            pin,
            { pin = it.trim(); error = null },
            dashboardPin,
            { dashboardPin = it; error = null },
            controller,
            ::findController,
            error,
            busy,
            ::connect,
            ::fromController,
        )
    }
    controllerPicker.Dialog(HOTSPOT)

    // Light status bar icons only while the dark hero is behind them: not once it scrolls away (keyboard up,
    // landscape), and never in the wide layout, where the hero is an inset card clear of the status bar.
    val wide = expanded
    val scroll = rememberScrollState()
    var heroHeight by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density)
    val onDark by remember(wide) { derivedStateOf { !wide && scroll.value < heroHeight - statusBar } }
    DisposableEffect(onDark) { statusBarOnDark(onDark); onDispose { statusBarOnDark(false) } }
    // Only the scrolling form makes room for the keyboard: the hero is sized from the whole screen (maxHeight here),
    // else it shrinks as the keyboard slides in and the form bounces.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide window: fire on the left, form on the right at a readable width.
        if (wide) Row(Modifier.fillMaxSize().imePadding()) {
            LoginHero(
                Modifier.weight(1f).fillMaxHeight().systemBarsPadding().padding(16.dp),
                RoundedCornerShape(48.dp)
            )
            Column(
                Modifier.weight(1f).verticalScroll(scroll)
                    .heightIn(min = this@BoxWithConstraints.maxHeight).safeDrawingPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { form(Modifier.widthIn(max = 480.dp).padding(32.dp)) }
        }
        // Phone: fits one screen, the hero takes whatever height the form leaves (at least 220 dp). Scrolls only when it can't.
        // (Not weight(): with no space left it squeezes the hero to zero, ignoring any minimum.)
        // Measured in one pass (no first frame of hero only). The nav bar counts at its full height even under the
        // keyboard, where its padding drops to 0: else the hero grows by that much and the form jumps.
        else {
            val screen = maxHeight
            val navBar = WindowInsets.navigationBars.getBottom(density)
            Layout(
                listOf(
                    {
                        LoginHero(
                            Modifier.onSizeChanged { heroHeight = it.height },
                            RoundedCornerShape(bottomStart = 48.dp, bottomEnd = 48.dp)
                        )
                    },
                    { form(Modifier.widthIn(max = 560.dp).padding(24.dp)) },
                ),
                Modifier.fillMaxSize().imePadding().verticalScroll(scroll),
            ) { (heroPart, formPart), c ->
                val f = formPart.single().measure(Constraints(maxWidth = c.maxWidth))
                val h = maxOf(220.dp.roundToPx(), screen.roundToPx() - f.height - navBar)
                val top = heroPart.single().measure(Constraints.fixed(c.maxWidth, h))
                layout(c.maxWidth, h + f.height + navBar) {
                    top.place(0, 0)
                    f.place((c.maxWidth - f.width) / 2, h)
                }
            }
        }
        // Once the hero has scrolled away, the form must not run under the status bar icons.
        if (!wide && !onDark) Box(
            Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

/**
 * Embers glowing over a dark hearth, fire licking up from the bottom. Brand orange on purpose,
 * even under a dynamic (wallpaper) scheme: fire isn't blue.
 */
@Composable
private fun LoginHero(modifier: Modifier, shape: Shape) {
    Box(
        modifier.clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1E0A04),
                        Color(0xFF4A1400),
                        Color(0xFF7A2500)
                    )
                )
            )
            .flames(true)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Box(
                Modifier.size(72.dp).springIn(0)
                    .background(Heat, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) { Icon(FlameIcon, null, Modifier.size(36.dp), tint = Color.White) }
            Spacer(Modifier.height(8.dp))
            Text(
                "OnlyHeat", Modifier.springIn(1),
                style = MaterialTheme.typography.displaySmallEmphasized, color = Color.White,
            )
        }
    }
}

private enum class LoginWay { Manual, Controller }

@Composable
private fun LoginForm(
    modifier: Modifier, way: LoginWay?, onWay: (LoginWay?) -> Unit,
    pin: String, onPin: (String) -> Unit, dashboardPin: String, onDashboardPin: (String) -> Unit,
    controller: DashboardTarget?, onFind: (change: Boolean) -> Unit,
    error: String?, busy: Boolean, onConnect: () -> Unit, onFromController: () -> Unit,
) {
    val wide = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight)
    Column(modifier) {
        Text(
            stringResource(Res.string.login_intro), Modifier.springIn(2),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        when (way) {
            null -> {
                Button(
                    onClick = { onWay(LoginWay.Controller) },
                    modifier = wide.springIn(3),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(
                        stringResource(Res.string.pin_from_controller),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onWay(LoginWay.Manual) },
                    modifier = wide.springIn(4),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(
                        stringResource(Res.string.pin_enter),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            LoginWay.Manual -> {
                LoginSteps(
                    Res.string.pin_where,
                    listOf(
                        Res.string.pin_step1,
                        Res.string.pin_step2,
                        Res.string.pin_step3,
                        Res.string.pin_step4
                    )
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    pin, onPin,
                    label = { Text(stringResource(Res.string.pin_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (pin.isNotEmpty() && !busy) onConnect() }),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                ConnectButton(wide, pin.isNotEmpty(), busy, onConnect)
            }

            LoginWay.Controller -> {
                LoginSteps(
                    Res.string.ctrl_how,
                    listOf(Res.string.ctrl_step1, Res.string.ctrl_step2, Res.string.ctrl_step3)
                )
                Spacer(Modifier.height(24.dp))
                if (controller == null) {
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ConnectButton(wide, true, busy, { onFind(false) }, Res.string.ctrl_find)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.tab_controller),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                controller.name ?: stringResource(Res.string.ctrl_on_hotspot),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                        }
                        TextButton(
                            { onFind(true) },
                            enabled = !busy
                        ) { Text(stringResource(Res.string.change)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    PinField(
                        dashboardPin,
                        onDashboardPin,
                        Modifier.fillMaxWidth(),
                        error,
                        label = stringResource(Res.string.dashboard_pin),
                        onDone = { if (pinComplete(dashboardPin) && !busy) onFromController() },
                    )
                    Spacer(Modifier.height(16.dp))
                    ConnectButton(wide, pinComplete(dashboardPin), busy, onFromController)
                }
            }
        }
        if (way != null) TextButton(
            { onWay(null) },
            Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy
        ) {
            Text(stringResource(Res.string.back))
        }
    }
}

@Composable
private fun ConnectButton(
    modifier: Modifier,
    ready: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    label: StringResource = Res.string.connect,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = ready && !busy,
        shapes = ButtonDefaults.shapes()
    ) {
        if (busy) LoadingIndicator(
            Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.onPrimary
        )
        else Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LoginSteps(title: StringResource, steps: List<StringResource>) {
    Card(
        Modifier.fillMaxWidth().springIn(3),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMediumEmphasized)
            val badges = listOf(
                MaterialShapes.Cookie4Sided,
                MaterialShapes.Pill,
                MaterialShapes.Sunny,
                MaterialShapes.Clover4Leaf
            )
            steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(32.dp).background(Heat, badges[i].toShape()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RoomCard(
    room: Room,
    boosted: Boolean,
    lastTarget: () -> Double,
    modifier: Modifier,
    onOpen: () -> Unit,
    onTarget: (Double?) -> Unit
) {
    val on = room.target != null
    val active = room.heating && on
    val scheme = MaterialTheme.colorScheme
    val cold = isCold(
        room,
        remember { Settings() }) // read each recomposition, so a changed limit shows up
    val asleep = !on && !cold
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    // Off and asleep: a night-sky card in any theme, like the fire's fixed orange. Heating shows as fire on top.
    val container by animateColorAsState(
        if (asleep) NightSky else if (on) roomContainer(room.id) else scheme.surfaceContainerLow,
        spec
    )
    val content by animateColorAsState(
        if (asleep) NightInk else contentColorFor(
            if (on) roomContainer(
                room.id
            ) else scheme.surfaceContainerLow
        ), spec
    )
    PressCard(
        onOpen, modifier.fillMaxWidth().shared("room-${room.id}"),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        val ink = LocalContentColor.current
        Column(
            Modifier.flames(active, room.id, boosted)
                .drift(on && !active, ink.copy(alpha = 0.10f), room.id) // at target: warm bubbles
                .night(asleep, ink, room.id) // off: moon, stars, z's
                .frost(cold, room.id) // below the cold-alert temperature: ice on glass
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleLargeEmphasized)
                    val status = listOfNotNull(
                        if (active) stringResource(Res.string.status_heating) else null,
                        room.humidity?.let {
                            stringResource(
                                Res.string.humidity,
                                "${it.toInt()}%"
                            )
                        },
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
                RollingTemp(
                    room.current,
                    MaterialTheme.typography.displayMedium,
                    Modifier.weight(1f)
                )
                if (on) TempStepper(
                    room.target!!,
                    onTarget,
                    label = stringResource(Res.string.target)
                )
                else Text(
                    stringResource(Res.string.off),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalContentColor.current.copy(alpha = 0.75f)
                )
            }
        }
    }
}

/** One-row card: a stepper labelled [title] and an action button. */
@Composable
private fun QuickCard(
    title: String, t: Double, onChange: (Double) -> Unit, action: String, modifier: Modifier,
    busy: Boolean = false, onAction: () -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TempStepper(t, onChange, buttonSize = 40.dp, label = title)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onAction,
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
                colors = onCardTonal()
            ) {
                if (busy) LoadingIndicator(
                    Modifier.size(24.dp),
                    color = LocalContentColor.current
                ) else Text(action)
            }
        }
    }
}

@Composable
private fun AwayOnCard(temp: Double, busy: Boolean, modifier: Modifier, onBack: () -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.away_on_title),
                    style = MaterialTheme.typography.titleMediumEmphasized
                )
                Text(
                    stringResource(Res.string.away_on_desc, fmt1(temp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onBack, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                if (busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                else Text(stringResource(Res.string.away_back))
            }
        }
    }
}

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
    val shown = kept.target?.let { "${fmt1(it)}°" } ?: getString(Res.string.off_lower)
    return if (kept.schedule) getString(Res.string.kept_schedule, kept.name, shown)
    else "${kept.name}: " + getString(Res.string.kept_other, shown)
}

/** The controller's address on its own Wi-Fi. */
internal const val HOTSPOT = "192.168.42.1"

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

fun fmt1(t: Double): String {
    val tenths = kotlin.math.round(t * 10).toLong()
    val a = kotlin.math.abs(tenths)
    return (if (tenths < 0) "-" else "") + "${a / 10}.${a % 10}"
}
