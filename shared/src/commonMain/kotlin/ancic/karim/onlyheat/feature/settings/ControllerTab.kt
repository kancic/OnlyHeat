package ancic.karim.onlyheat.feature.settings

import ancic.karim.onlyheat.data.DashboardTarget
import ancic.karim.onlyheat.data.FIRMWARE
import ancic.karim.onlyheat.data.LocalApi
import ancic.karim.onlyheat.data.ServerUnreachable
import ancic.karim.onlyheat.data.WrongDashboardPin
import ancic.karim.onlyheat.data.alertsEnabled
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.coldSetting
import ancic.karim.onlyheat.data.dashboardProbeClient
import ancic.karim.onlyheat.data.findLanDashboard
import ancic.karim.onlyheat.data.firmwareOf
import ancic.karim.onlyheat.data.firmwareRevision
import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.noteFirmware
import ancic.karim.onlyheat.data.rememberLanTarget
import ancic.karim.onlyheat.data.resolveDashboard
import ancic.karim.onlyheat.data.savePin
import ancic.karim.onlyheat.data.savedLanTarget
import ancic.karim.onlyheat.data.savedPin
import ancic.karim.onlyheat.data.warmSetting
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.BellIcon
import ancic.karim.onlyheat.ui.ChevronRightIcon
import ancic.karim.onlyheat.ui.DownloadIcon
import ancic.karim.onlyheat.ui.Group
import ancic.karim.onlyheat.ui.LanControllerPicker
import ancic.karim.onlyheat.ui.LightbulbIcon
import ancic.karim.onlyheat.ui.LocalCardShape
import ancic.karim.onlyheat.ui.LockIcon
import ancic.karim.onlyheat.ui.PersonIcon
import ancic.karim.onlyheat.ui.PinField
import ancic.karim.onlyheat.ui.RefreshIcon
import ancic.karim.onlyheat.ui.ScheduleIcon
import ancic.karim.onlyheat.ui.Section
import ancic.karim.onlyheat.ui.SectionHeading
import ancic.karim.onlyheat.ui.TabInsets
import ancic.karim.onlyheat.ui.TempStepper
import ancic.karim.onlyheat.ui.ThermostatIcon
import ancic.karim.onlyheat.ui.WifiIcon
import ancic.karim.onlyheat.ui.collapsing
import ancic.karim.onlyheat.ui.collapsingBar
import ancic.karim.onlyheat.ui.errorText
import ancic.karim.onlyheat.ui.isDark
import ancic.karim.onlyheat.ui.onCardTonal
import ancic.karim.onlyheat.ui.pinComplete
import ancic.karim.onlyheat.ui.resetTips
import ancic.karim.onlyheat.ui.shared
import ancic.karim.onlyheat.ui.springIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private data class PinPrompt(
    val target: DashboardTarget? = null,
    val restartAfterSave: Boolean = false,
    val onSaved: () -> Unit = {},
)

/** Settings tab: the controller (cloud check and its local dashboard), then app settings and the account. */
@Composable
fun ControllerTab(
    device: String?, host: String, requestLocalNetwork: suspend () -> Boolean,
    hasLocalNetwork: () -> Boolean?, // without asking; null when the platform can't tell
    cloudStatus: suspend () -> Throwable?, // null when the cloud can reach the controller right now
    snackbar: SnackbarHostState, backgroundAlerts: ((Boolean) -> Unit)?, onRestarted: () -> Unit,
    onLogout: () -> Unit, openSchedule: () -> Unit,
) {
    val settings = koinInject<Settings>()
    var pin by remember { mutableStateOf(savedPin(settings)) }
    var pinPrompt by remember { mutableStateOf<PinPrompt?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val controllerPicker = remember { LanControllerPicker(settings) }
    var savedController by remember { mutableStateOf(settings.savedLanTarget()?.name) }
    var choosing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firmware = remember(firmwareRevision) { settings.getStringOrNull(FIRMWARE) }

    /** Stages the verified controller for PIN entry. null: the user cancelled discovery. */
    suspend fun chooseController(onSaved: () -> Unit = {}, onEditPin: (() -> Unit)? = null): Boolean? {
        choosing = true
        return try {
            if (!requestLocalNetwork()) {
                if (onEditPin != null) onEditPin()
                else snackbar.showSnackbar(getString(Res.string.local_permission_denied))
                null
            } else {
                attempt { controllerPicker.find(host, change = true, save = false, onEditPin = onEditPin) }.fold(
                    { target -> target?.let { pinPrompt = PinPrompt(it, onSaved = onSaved); true } },
                    { false },
                )
            }
        } finally {
            choosing = false
        }
    }

    val localAccessBusy = choosing || restarting || pinPrompt != null

    fun restart() {
        val p = pin ?: run {
            // The controller first, then its PIN.
            restarting = true
            scope.launch {
                try {
                    if (!requestLocalNetwork()) snackbar.showSnackbar(getString(Res.string.local_permission_denied))
                    else attempt { controllerPicker.find(host, save = false) }
                        .onSuccess { if (it != null) pinPrompt = PinPrompt(it, restartAfterSave = true) }
                        .onFailure { snackbar.showSnackbar(errorText(it)) }
                } finally {
                    restarting = false
                }
            }
            return
        }
        restarting = true
        scope.launch {
            try {
                if (!requestLocalNetwork()) {
                    snackbar.showSnackbar(getString(Res.string.local_permission_denied)); return@launch
                }
                val back = LocalApi(host, p, settings) { controllerPicker.ask() }.rebootAndWait { cloudStatus() == null }
                if (back) onRestarted()
                snackbar.showSnackbar(getString(if (back) Res.string.restart_done else Res.string.restart_timeout))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is WrongDashboardPin) { savePin(settings, null); pin = null }
                snackbar.showSnackbar(errorText(e))
            } finally {
                restarting = false
            }
        }
    }

    val scroll = collapsingBar()
    Scaffold(
        modifier = Modifier.collapsing(scroll),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(Res.string.tab_settings)) },
                subtitle = { if (device != null) Text(device) },
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = TabInsets,
    ) { pad ->
          Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 640.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            SectionHeading(stringResource(Res.string.tab_controller), stringResource(Res.string.controller_local_only))

            Group {
            item("diagnostics") {
            DiagnosticsCard(
                Modifier.springIn(0), host, settings, { chooseController(it) }, requestLocalNetwork, cloudStatus, localAccessBusy,
            ) {
                confirmRestart = true
            }
            }

            item("schedule") {
            Section(stringResource(Res.string.schedule_title), stringResource(Res.string.schedule_card_desc), ScheduleIcon,
                MaterialShapes.Clover4Leaf, accent = 1, onClick = openSchedule, modifier = Modifier.springIn(1).shared("schedule", LocalCardShape.current)) {
                Icon(ChevronRightIcon, null)
            }
            }

            // One card for the controller and its dashboard PIN: picking another controller asks for its PIN too.
            item("local") {
            Section(
                stringResource(Res.string.local_access_title),
                listOfNotNull(
                    savedController ?: stringResource(Res.string.pin_not_set),
                    firmware?.let { stringResource(Res.string.firmware_version, it) },
                    stringResource(Res.string.dashboard_pin) + ": " +
                        stringResource(if (pin != null) Res.string.pin_saved else Res.string.pin_not_set),
                ).joinToString("\n"),
                LockIcon, MaterialShapes.Cookie6Sided, accent = 2,
                modifier = Modifier.springIn(2),
            ) {
                FilledTonalButton({
                    scope.launch {
                        val editPin: (() -> Unit)? = if (savedController != null || pin != null) {
                            { pinPrompt = PinPrompt() }
                        } else null
                        if (chooseController(onEditPin = editPin) == false) {
                            snackbar.showSnackbar(getString(Res.string.schedule_unreachable))
                        }
                    }
                }, enabled = !localAccessBusy, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
                    Text(stringResource(Res.string.change))
                }
            }
            }

            val p = pin
            // Hides itself while unknown, so it must never be the block's first or last card.
            if (p != null && savedController != null) item(listOf(savedController, p)) {
                AutoUpdateCard(Modifier.springIn(3), host, p, settings, snackbar, requestLocalNetwork, hasLocalNetwork,
                    wrongPin = { savePin(settings, null); pin = null }) { controllerPicker.ask() }
            }

            item("restart") {
            Section(stringResource(Res.string.restart_card_title), stringResource(Res.string.restart_card_desc), RefreshIcon,
                MaterialShapes.Cookie4Sided, accent = 1, modifier = Modifier.springIn(4)) {
                FilledTonalButton({ confirmRestart = true }, enabled = !localAccessBusy, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
                    Text(stringResource(Res.string.restart))
                }
            }
            }
            }

            SectionHeading(stringResource(Res.string.settings_app), modifier = Modifier.padding(top = 12.dp))

            Group {
            if (backgroundAlerts != null) item("alerts") { AlertsCard(Modifier.springIn(5), settings, backgroundAlerts) }

            item("limits") { LimitsCard(Modifier.springIn(6), settings, alerts = backgroundAlerts != null) }

            item("tips") {
            val tipsAgain = stringResource(Res.string.tips_again_done)
            Section(stringResource(Res.string.tips_title), stringResource(Res.string.tips_desc), LightbulbIcon,
                MaterialShapes.Sunny, accent = 2, modifier = Modifier.springIn(7)) {
                FilledTonalButton({ resetTips(settings); scope.launch { snackbar.showSnackbar(tipsAgain) } }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
                    Text(stringResource(Res.string.tips_again))
                }
            }
            }

            item("account") {
            Section(stringResource(Res.string.account), stringResource(Res.string.account_desc), PersonIcon,
                MaterialShapes.Circle, modifier = Modifier.springIn(8)) {
                FilledTonalButton(onLogout, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(stringResource(Res.string.log_out)) }
            }
            }
            }
          }
    }

    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text(stringResource(Res.string.restart_title)) },
        text = { Text(stringResource(Res.string.restart_text)) },
        confirmButton = {
            Button({ confirmRestart = false; restart() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.restart))
            }
        },
        dismissButton = { TextButton({ confirmRestart = false }) { Text(stringResource(Res.string.cancel)) } },
    )
    if (restarting && !controllerPicker.isOpen) AlertDialog(
        onDismissRequest = {}, // not dismissable: wait for the controller to come back
        confirmButton = {},
        icon = { LoadingIndicator() },
        title = { Text(stringResource(Res.string.restarting)) },
        text = { Text(stringResource(Res.string.restarting_hint)) },
    )
    pinPrompt?.let { prompt ->
        PinDialog(
            onDismiss = { pinPrompt = null },
            onPin = {
                prompt.target?.let { target ->
                    settings.rememberLanTarget(target, host)
                    savedController = settings.savedLanTarget()?.name
                }
                savePin(settings, it)
                pin = it
                pinPrompt = null
                prompt.onSaved()
                if (prompt.restartAfterSave) restart()
            },
        )
    }
    controllerPicker.Dialog(host)
}

/** Green "all good" card, like the status dots in [LinkRow]. */
@Composable
private fun goodColors(): Pair<Color, Color> =
    if (isDark()) Color(0xFF1F3A21) to Color(0xFFB9E6BC) else Color(0xFFCDEBCF) to Color(0xFF0B2E10)

/** Background checks: controller offline, room too cold, open window, not warming up, too hot, AC tip. The check itself is in Watch.kt. */
@Composable
private fun AlertsCard(modifier: Modifier, settings: Settings, enable: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(alertsEnabled(settings)) }
    Section(stringResource(Res.string.alerts_title), stringResource(Res.string.alerts_desc), BellIcon, MaterialShapes.Clover8Leaf,
        modifier = modifier) {
        Switch(on, { on = it; settings.putBoolean("alerts.on", it); enable(it) })
    }
}

/** Always shown: Too warm also drives the AC tip and caps the AC's cooling, with alerts on or off. */
@Composable
private fun LimitsCard(modifier: Modifier, settings: Settings, alerts: Boolean) {
    var cold by remember { mutableStateOf(coldSetting(settings)) }
    var warm by remember { mutableStateOf(warmSetting(settings)) }
    Section(
        stringResource(Res.string.limits_title), stringResource(if (alerts) Res.string.limits_desc else Res.string.limits_desc_no_alerts),
        ThermostatIcon, MaterialShapes.Pill, accent = 1, modifier = modifier,
        below = {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.alerts_cold), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TempStepper(cold, { cold = it; settings.putDouble("alerts.cold", it) }, buttonSize = 44.dp)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.alerts_warm), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TempStepper(warm, { warm = it; settings.putDouble("alerts.warm", it) }, buttonSize = 44.dp)
            }
        },
    )
}

/** The controller's own "install new versions automatically at night". Read on opening (it logs in, which also
 *  notes the firmware); hidden while unknown or on firmware without the setting. The cache is per controller
 *  (callers key the card by it), since another controller's value would be wrong. */
@Composable
private fun AutoUpdateCard(
    modifier: Modifier, host: String, pin: String, settings: Settings, snackbar: SnackbarHostState,
    requestLocalNetwork: suspend () -> Boolean, hasLocalNetwork: () -> Boolean?, wrongPin: () -> Unit, ask: suspend () -> DashboardTarget?,
) {
    val cacheKey = "controllerAutoUpdate." + settings.savedLanTarget()?.name
    var on by remember { mutableStateOf(if (settings.hasKey(cacheKey)) settings.getBoolean(cacheKey, true) else null) }
    var busy by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) } // flipped here: a read that started earlier is stale
    val scope = rememberCoroutineScope()
    suspend fun failed(e: Throwable) {
        if (e is WrongDashboardPin) wrongPin()
        snackbar.showSnackbar(errorText(e))
    }
    LaunchedEffect(Unit) {
        // Opening the tab must not ask for anything: no permission dialog, no controller picker.
        if (hasLocalNetwork() != true) return@LaunchedEffect
        attempt { LocalApi(host, pin, settings) { null }.autoUpdate() }.onSuccess { value ->
            if (touched) return@onSuccess
            on = value
            if (value == null) settings.remove(cacheKey) else settings.putBoolean(cacheKey, value)
        }.onFailure { if (it is WrongDashboardPin) failed(it) } // unreachable: keep the last known value quietly
    }
    val value = on ?: return
    Section(stringResource(Res.string.auto_update_title), stringResource(Res.string.auto_update_desc), DownloadIcon,
        MaterialShapes.Sunny, modifier = modifier) {
        Switch(value, { wanted ->
            touched = true
            busy = true
            on = wanted
            scope.launch {
                try {
                    if (!requestLocalNetwork()) {
                        on = !wanted; snackbar.showSnackbar(getString(Res.string.local_permission_denied)); return@launch
                    }
                    attempt { LocalApi(host, pin, settings, ask).setAutoUpdate(wanted) }
                        .onSuccess { settings.putBoolean(cacheKey, wanted) }
                        .onFailure { on = !wanted; failed(it) }
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy)
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dashboard_pin)) },
        text = {
            Column {
                Text(stringResource(Res.string.dashboard_pin_desc))
                Spacer(Modifier.height(12.dp))
                PinField(value, { value = it })
            }
        },
        confirmButton = { TextButton({ onPin(value) }, enabled = pinComplete(value)) { Text(stringResource(Res.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

private enum class Link { Unknown, Ok, Unverified, Down, NoInternet }
@Composable
private fun DiagnosticsCard(
    modifier: Modifier, host: String, settings: Settings, chooseController: suspend (() -> Unit) -> Boolean?,
    requestLocalNetwork: suspend () -> Boolean, cloudStatus: suspend () -> Throwable?,
    localAccessBusy: Boolean, onRestart: () -> Unit,
) {
    var cloud by remember { mutableStateOf(Link.Unknown) }
    var local by remember { mutableStateOf(Link.Unknown) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun check() {
        running = true
        scope.launch {
            // Different hosts, so both run at once: the cloud check alone can take ~50 s with retries.
            coroutineScope {
                launch {
                    cloud = when (cloudStatus()) {
                        null -> Link.Ok
                        is ServerUnreachable -> Link.NoInternet // the phone cannot reach the TTO cloud at all
                        else -> Link.Down
                    }
                }
                launch {
                    local = when {
                        !requestLocalNetwork() -> Link.Down
                        resolveDashboard(host, settings)?.also { (target, connection) ->
                            dashboardProbeClient(connection).use { it.firmwareOf(target.host) }?.let { settings.noteFirmware(it) }
                        } != null -> Link.Ok
                        dashboardProbeClient().use { it.findLanDashboard(host) } != null -> Link.Unverified
                        else -> Link.Down
                    }
                }
            }
            log("diagnostics: cloud=$cloud local=$local ($host)")
            running = false
        }
    }

    fun pickController() {
        running = true
        scope.launch {
            try {
                if (chooseController { local = Link.Ok } == false) local = Link.Down
            } finally {
                running = false
            }
        }
    }

    // Tinted only once checked: green while the cloud works (away from home the local check failing is normal),
    // red when nothing or only the local dashboard answers; an unverified dashboard is a question, not a result.
    val (good, onGood) = goodColors()
    val (container, ink) = when {
        cloud == Link.Unknown || local == Link.Unverified -> MaterialTheme.colorScheme.surfaceContainerHigh.let { it to contentColorFor(it) }
        cloud == Link.Ok -> good to onGood
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Section(
        stringResource(Res.string.diag_title), stringResource(Res.string.diag_desc), WifiIcon, MaterialShapes.Cookie9Sided,
        modifier = modifier, container = container, ink = ink,
        below = {
            if (cloud != Link.Unknown) {
                LinkRow(stringResource(Res.string.diag_cloud), cloud)
                LinkRow(
                    stringResource(Res.string.diag_local), local,
                    onClick = if (local == Link.Unverified && !running && !localAccessBusy) ::pickController else null,
                )
                val verdict = when {
                    cloud == Link.NoInternet -> Res.string.diag_verdict_no_internet
                    local == Link.Unverified -> Res.string.diag_verdict_unverified
                    cloud == Link.Ok && local == Link.Ok -> Res.string.diag_verdict_ok
                    cloud == Link.Ok -> Res.string.diag_verdict_ok_remote
                    local == Link.Ok -> Res.string.diag_verdict_cloud_link
                    else -> Res.string.diag_verdict_nothing
                }
                Text(stringResource(verdict), style = MaterialTheme.typography.bodyLarge)
                // Unverified too: the restart asks which controller it is.
                if (cloud == Link.Down && local in setOf(Link.Ok, Link.Unverified)) FilledTonalButton(
                    onRestart, enabled = !localAccessBusy && !running, shapes = ButtonDefaults.shapes(), colors = onCardTonal(),
                ) { Text(stringResource(Res.string.restart)) }
            }
        },
    ) {
        if (running) LoadingIndicator(Modifier.size(40.dp))
        else FilledTonalButton(::check, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) { Text(stringResource(Res.string.diag_run)) }
    }
}

@Composable
private fun LinkRow(label: String, state: Link, onClick: (() -> Unit)? = null) {
    val ok = state == Link.Ok
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Expressive status shapes: a soft cookie when fine, a spiky burst when not.
        Box(
            Modifier.size(16.dp).background(
                if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                (if (ok) MaterialShapes.Cookie4Sided else MaterialShapes.SoftBurst).toShape(),
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        val status = stringResource(when (state) {
            Link.Ok -> Res.string.diag_ok
            Link.Unverified -> Res.string.diag_unverified
            else -> Res.string.diag_fail
        })
        if (onClick != null) {
            TextButton(onClick) { Text(status, textDecoration = TextDecoration.Underline) }
        } else {
            Text(status, style = MaterialTheme.typography.labelLarge)
        }
    }
}

