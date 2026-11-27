package ancic.karim.onlyheat

import ancic.karim.onlyheat.ac.AcCard
import ancic.karim.onlyheat.ac.AcCloudAccount
import ancic.karim.onlyheat.ac.AcViewModel
import ancic.karim.onlyheat.ac.AcWifiAccess
import ancic.karim.onlyheat.ac.LocalAcWifi
import ancic.karim.onlyheat.ac.fighting
import ancic.karim.onlyheat.data.HOTSPOT
import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.Update
import ancic.karim.onlyheat.data.checkUpdate
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.clearSession
import ancic.karim.onlyheat.data.noticeSession
import ancic.karim.onlyheat.data.savePairing
import ancic.karim.onlyheat.data.savedAuthKey
import ancic.karim.onlyheat.feature.history.HistoryViewModel
import ancic.karim.onlyheat.feature.history.HistoryTab
import ancic.karim.onlyheat.feature.login.LoginScreen
import ancic.karim.onlyheat.feature.room.RoomScreen
import ancic.karim.onlyheat.feature.rooms.AcFightCard
import ancic.karim.onlyheat.feature.rooms.HomeViewModel
import ancic.karim.onlyheat.feature.rooms.RoomsTab
import ancic.karim.onlyheat.feature.rooms.heatingOff
import ancic.karim.onlyheat.feature.schedule.ScheduleScreen
import ancic.karim.onlyheat.feature.settings.ControllerTab
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.ChartIcon
import ancic.karim.onlyheat.ui.HomeIcon
import ancic.karim.onlyheat.ui.LocalSharedScope
import ancic.karim.onlyheat.ui.OnlyHeatTheme
import ancic.karim.onlyheat.ui.RoomsTip
import ancic.karim.onlyheat.ui.ScheduleTip
import ancic.karim.onlyheat.ui.TuneIcon
import ancic.karim.onlyheat.ui.expanded
import ancic.karim.onlyheat.ui.fadeThrough
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.russhwolf.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.minutes

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
    Controller(Res.string.tab_settings, TuneIcon),
}

/**
 * [requestLocalNetwork] asks for local-network access where the platform requires it (Android 17+).
 * [dynamicScheme] supplies a wallpaper-based scheme (Android 12+); null falls back to the orange palette.
 * [notify] shows a system notification when a change is rejected after the user left the app; the notice stays too.
 * [wakeAt] runs [endExpiredBoosts] at that time (epoch ms) even if the app is closed; without it, boosts end
 * while the app is visible or when it next starts.
 * [backgroundAlerts] turns periodic alert checks on/off; null hides the Alerts setting.
 * [notificationsAllowed] says whether alerts can be posted; while Alerts is on and they can't, a notice offers
 * [openNotificationSettings]. Checked whenever the app resumes (the permission dialog and settings pause it).
 * [statusBarOnDark] switches the status bar icons to light while a screen draws something dark behind them.
 * [hasLocalNetwork] reports local-network access for AC discovery, or null if the platform can't tell.
 * [acWifi] spots reset ACs by their own Wi-Fi where the platform can scan Wi-Fi (Android); rechecked on resume.
 * [appVersion]: when set (release builds), a newer GitHub release is offered as a Rooms notice that runs [installUpdate].
 */
@Composable
fun App(
    requestLocalNetwork: suspend () -> Boolean = { true },
    notify: (suspend (String) -> Unit)? = null,
    wakeAt: ((Long) -> Unit)? = null,
    backgroundAlerts: ((Boolean) -> Unit)? = null,
    notificationsAllowed: () -> Boolean = { true },
    openNotificationSettings: () -> Unit = {},
    dynamicScheme: (@Composable (dark: Boolean) -> ColorScheme?)? = null,
    statusBarOnDark: (Boolean) -> Unit = {},
    scheduleTipRequests: Flow<Unit> = emptyFlow(),
    hasLocalNetwork: () -> Boolean? = { true },
    acWifi: AcWifiAccess? = null,
    appVersion: String? = null,
    installUpdate: suspend (Update, onProgress: (Float) -> Unit) -> Unit = { _, _ -> },
) {
    if (acWifi != null) LifecycleResumeEffect(acWifi) { acWifi.recheck(); onPauseOrDispose {} }
    OnlyHeatTheme(dynamicScheme) { CompositionLocalProvider(LocalAcWifi provides acWifi) {
        Surface(Modifier.fillMaxSize()) {
            val settings = koinInject<Settings>()
            var key by remember { mutableStateOf(savedAuthKey(settings)) }
            val api = koinInject<HeatApi>()
            val k = key
            if (k == null) LoginScreen(
                api,
                requestLocalNetwork,
                statusBarOnDark
            ) { pairing -> savePairing(settings, pairing); key = pairing.key }
            else Main(api, k, requestLocalNetwork, hasLocalNetwork, notify, wakeAt, backgroundAlerts, notificationsAllowed, openNotificationSettings, scheduleTipRequests, appVersion, installUpdate) {
                if (clearSession(settings, k)) key = null
            }
        }
    } }
}

/** Holds the logged-in session's ViewModels; see [Main]. */
private class SessionStore : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    override fun onCleared() = viewModelStore.clear()
}

@Composable
private fun Main(
    api: HeatApi,
    key: String,
    requestLocalNetwork: suspend () -> Boolean,
    hasLocalNetwork: () -> Boolean?,
    notify: (suspend (String) -> Unit)?,
    wakeAt: ((Long) -> Unit)?,
    backgroundAlerts: ((Boolean) -> Unit)?,
    notificationsAllowed: () -> Boolean,
    openNotificationSettings: () -> Unit,
    scheduleTipRequests: Flow<Unit>,
    appVersion: String?,
    installUpdate: suspend (Update, onProgress: (Float) -> Unit) -> Unit,
    onLogout: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings = koinInject<Settings>()
    val acWifi = LocalAcWifi.current
    val session = noticeSession(settings)
    // This session's ViewModels live in their own store, cleared on logout (Main leaves while no key is saved),
    // so old sessions' models don't pile up in the activity's store; a recreation keeps them.
    val store = viewModel { SessionStore() }
    DisposableEffect(store) { onDispose { if (savedAuthKey(settings) != key) store.viewModelStore.clear() } }
    val model = koinViewModel<HomeViewModel>(viewModelStoreOwner = store, key = "home:$session:$key") { parametersOf(key) }
    model.visible = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    model.notify = notify
    model.wakeAt = wakeAt
    model.openNotificationSettings = openNotificationSettings
    LifecycleResumeEffect(model) { model.notificationsAllowed = notificationsAllowed(); onPauseOrDispose {} }
    val acs = koinViewModel<AcViewModel>(viewModelStoreOwner = store, key = "ac:$session:$key") { parametersOf(key) }
    val historyModel = koinViewModel<HistoryViewModel>(viewModelStoreOwner = store, key = "history:$session:$key") { parametersOf(key) }
    val logout = { acs.endSession(); onLogout() }
    model.onLoggedOut = logout
    acs.snackbar = model.snackbar
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.watchBoostExpiry() }
            model.syncHistoryWhileStarted()
        }
    }

    // Saveable so a recreation (theme change, process death) reopens the same screen instead of Rooms.
    val backStack =
        rememberSaveable(saver = backStackSaver) { mutableStateListOf<Screen>(Screen.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.Rooms) }
    var lanAvailable by remember { mutableStateOf(hasLocalNetwork()) }
    LaunchedEffect(model, lifecycle, tab) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (tab == Tab.Rooms) {
                launch {
                    while (true) {
                        lanAvailable = hasLocalNetwork()
                        acs.refresh(lanAvailable, scanWifi = acWifi?.active == true)
                        delay(2.minutes)
                    }
                }
                model.refreshOnResume()
                // Slow poll, only while the Rooms tab is on screen
                while (true) { delay(2.minutes); model.pollRooms() }
            }
        }
    }
    var showScheduleTip by rememberSaveable { mutableStateOf(false) }
    val host = model.home?.ip ?: HOTSPOT
    model.installUpdate = installUpdate
    LaunchedEffect(model) {
        if (model.update == null) model.update = appVersion?.let { v -> attempt { checkUpdate(v) }.getOrNull() }
    }
    LaunchedEffect(scheduleTipRequests) {
        scheduleTipRequests.collect { showScheduleTip = true }
    }

    // Back from another tab returns to Rooms before leaving the app. Lives here, not in MainTabs, because
    // MainTabs stays composed during a push transition and would steal the back press from NavDisplay.
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = tab != Tab.Rooms && backStack.size == 1,
        onBackCompleted = { tab = Tab.Rooms }
    )

    val backTransition = defaultPopTransitionSpec<Screen>()
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                // Gives each screen its own ViewModels, cleared when it's popped (HomeViewModel above is the activity's).
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                sharedTransitionScope = this,
                popTransitionSpec = backTransition,
                predictivePopTransitionSpec = { backTransition(this) },
                entryProvider = { screen ->
                    when (screen) {
                        Screen.Main -> NavEntry(screen) {
                            MainTabs(
                                tab,
                                { tab = it },
                                model,
                                acs,
                                historyModel,
                                api,
                                key,
                                host,
                                requestLocalNetwork,
                                hasLocalNetwork,
                                discoverAc = if (lanAvailable ?: acs.lanScanSucceeded) null else {
                                    { acs.discover { requestLocalNetwork().also { lanAvailable = hasLocalNetwork() } } }
                                },
                                backgroundAlerts = backgroundAlerts,
                                onLogout = logout,
                                onScheduleLocked = { showScheduleTip = true },
                                scheduleTipOpen = showScheduleTip,
                            ) {
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
                                onSmartChanged = model::refreshRooms,
                                ac = acs.room(room.id),
                                acCard = { ac ->
                                    AcCard(ac, { acs.set(room.id, it) }, onRemove = { acs.unlink(room.id) }) { AcCloudAccount(acs, ac) }
                                },
                                acWarning = { ac ->
                                    if (fighting(room, ac.state)) AcFightCard(room, model.outside, { acs.set(room.id, it) }, busy = acs.busy(room.id)) {
                                        model.heatingOff(room) { showScheduleTip = true }
                                    }
                                },
                            ) { backStack.removeLastOrNull() }
                        }

                        Screen.ScheduleEditor -> NavEntry(screen) {
                            ScheduleScreen(
                                host,
                                requestLocalNetwork,
                                onSaved = model::refreshRooms,
                            ) { backStack.removeLastOrNull() }
                        }
                    }
                },
            )
        }
    }
    if (showScheduleTip) ScheduleTip(
        onDismiss = { showScheduleTip = false },
        onOpenSchedule = {
            showScheduleTip = false
            if (backStack.lastOrNull() != Screen.ScheduleEditor) backStack += Screen.ScheduleEditor
        },
    )
}

@Composable
private fun MainTabs(
    tab: Tab,
    onTab: (Tab) -> Unit,
    model: HomeViewModel,
    acs: AcViewModel,
    history: HistoryViewModel,
    api: HeatApi,
    key: String,
    host: String,
    requestLocalNetwork: suspend () -> Boolean,
    hasLocalNetwork: () -> Boolean?,
    discoverAc: (() -> Unit)?,
    backgroundAlerts: ((Boolean) -> Unit)?,
    onLogout: () -> Unit,
    onScheduleLocked: () -> Unit,
    scheduleTipOpen: Boolean, // holds the welcome tour back, so two dialogs never stack
    open: (Screen) -> Unit,
) {
    // Each tab keeps its saved state (scroll, app bar, cards already sprung in) while another is shown;
    // without it every switch rebuilt the tab and replayed the cards' spring-in under the fade-through.
    val tabStates = rememberSaveableStateHolder()
    val tabContent = @Composable {
        AnimatedContent(tab, transitionSpec = { fadeThrough() }) { displayedTab ->
            tabStates.SaveableStateProvider(displayedTab.name) {
                when (displayedTab) {
                    Tab.Rooms -> {
                        if (tab == Tab.Rooms && !scheduleTipOpen) {
                            RoomsTip(hasAc = acs.linked.isNotEmpty(), cloudAvailable = acs.cloudAvailable)
                        }
                        RoomsTab(model, acs, discoverAc, onScheduleLocked) { open(Screen.RoomDetail(it)) }
                    }
                    Tab.History -> HistoryTab(history, model.home?.rooms) { open(Screen.RoomDetail(it)) }

                    Tab.Controller -> {
                        ControllerTab(
                            model.home?.device,
                            host,
                            requestLocalNetwork,
                            hasLocalNetwork,
                            cloudStatus = { attempt { api.home(key) }.exceptionOrNull() },
                            snackbar = model.snackbar,
                            backgroundAlerts = backgroundAlerts,
                            onRestarted = model::refreshRooms,
                            onLogout = onLogout,
                        ) { open(Screen.ScheduleEditor) }
                    }
                }
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
