package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import org.koin.compose.koinInject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Controller tab: things that talk to the controller's local dashboard, plus the account. */
@Composable
fun ControllerTab(
    device: String?, host: String, requestLocalNetwork: suspend () -> Boolean,
    cloudStatus: suspend () -> Throwable?, // null when the cloud can reach the controller right now
    snackbar: SnackbarHostState, backgroundAlerts: ((Boolean) -> Unit)?, onLogout: () -> Unit, openSchedule: () -> Unit,
) {
    val settings = koinInject<Settings>()
    var pin by remember { mutableStateOf(savedPin(settings)) }
    var askPin by remember { mutableStateOf(false) }
    var restartAfterPin by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val controllerPicker = remember { LanControllerPicker(settings) }
    var savedController by remember { mutableStateOf(settings.savedLanTarget()?.name) }
    var choosing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var firmwareSeen by remember { mutableIntStateOf(0) } // bumped after anything that may have read the version
    val firmware = remember(firmwareSeen) { settings.getStringOrNull(FIRMWARE) }
    val firmwareBefore = remember(firmwareSeen) { settings.getStringOrNull(FIRMWARE_BEFORE) }

    /** Saves the picked controller once its page shows the picked name. null: the user cancelled. */
    suspend fun chooseController(): Boolean? = attempt { controllerPicker.find(host, change = true) }
        .also { savedController = settings.savedLanTarget()?.name }
        .fold({ if (it == null) null else true }, { false })

    fun restart() {
        val p = pin ?: run {
            // The controller first, then its PIN.
            restarting = true
            scope.launch {
                try {
                    if (!requestLocalNetwork()) snackbar.showSnackbar(getString(Res.string.local_permission_denied))
                    else attempt { controllerPicker.find(host) }
                        .onSuccess { if (it != null) { savedController = settings.savedLanTarget()?.name; restartAfterPin = true; askPin = true } }
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
                title = { Text(stringResource(Res.string.tab_controller)) },
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
            Text(
                stringResource(Res.string.controller_local_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (firmwareBefore != null && firmware != null) Section(
                stringResource(Res.string.firmware_changed_title),
                stringResource(Res.string.firmware_changed_desc, firmwareBefore, firmware),
                modifier = Modifier.springIn(0), container = MaterialTheme.colorScheme.errorContainer,
            ) {
                TextButton({ settings.remove(FIRMWARE_BEFORE); firmwareSeen++ }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.ok))
                }
            }

            DiagnosticsCard(
                Modifier.springIn(0), host, settings, ::chooseController, requestLocalNetwork, cloudStatus, restarting,
                onChecked = { firmwareSeen++ },
            ) {
                confirmRestart = true
            }

            if (backgroundAlerts != null) AlertsCard(Modifier.springIn(1), settings, backgroundAlerts)

            Section(stringResource(Res.string.schedule_title), stringResource(Res.string.schedule_card_desc), onClick = openSchedule,
                modifier = Modifier.springIn(2).shared("schedule"), container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("›", style = MaterialTheme.typography.headlineMedium)
            }

            Section(stringResource(Res.string.restart_card_title), stringResource(Res.string.restart_card_desc), modifier = Modifier.springIn(3), container = MaterialTheme.colorScheme.primaryContainer) {
                FilledTonalButton({ confirmRestart = true }, enabled = !restarting, shapes = ButtonDefaults.shapes(),
                    colors = onCardTonal()) {
                    Text(stringResource(Res.string.restart))
                }
            }

            Section(
                stringResource(Res.string.dashboard_pin),
                stringResource(if (pin != null) Res.string.pin_saved else Res.string.pin_not_set),
                modifier = Modifier.springIn(4),
            ) {
                TextButton({ restartAfterPin = false; askPin = true }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.change))
                }
            }

            Section(
                stringResource(Res.string.lan_controller_title),
                listOfNotNull(
                    savedController ?: stringResource(Res.string.pin_not_set),
                    firmware?.let { stringResource(Res.string.firmware_version, it) },
                ).joinToString("\n"),
                modifier = Modifier.springIn(5),
            ) {
                TextButton({
                    choosing = true
                    scope.launch {
                        try {
                            when {
                                !requestLocalNetwork() -> snackbar.showSnackbar(getString(Res.string.local_permission_denied))
                                chooseController() == false -> snackbar.showSnackbar(getString(Res.string.schedule_unreachable))
                            }
                        } finally {
                            choosing = false
                        }
                    }
                }, enabled = !choosing && !restarting, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.change))
                }
            }

            Section(stringResource(Res.string.account), stringResource(Res.string.account_desc), modifier = Modifier.springIn(6)) {
                OutlinedButton(onLogout, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.log_out)) }
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
    if (askPin) PinDialog(
        onDismiss = { askPin = false },
        onPin = {
            savePin(settings, it); pin = it; askPin = false
            if (restartAfterPin) restart()
        },
    )
    controllerPicker.Dialog(host)
}

internal class LanControllerPicker(private val settings: Settings) {
    private data class Prompt(val answer: CompletableDeferred<DashboardTarget?>, val homeNetworkFirst: Boolean)

    private val requests = Mutex()
    private var pending by mutableStateOf<Prompt?>(null)
    val isOpen get() = pending != null

    suspend fun ask(homeNetworkFirst: Boolean = false): DashboardTarget? = requests.withLock {
        val answer = CompletableDeferred<DashboardTarget?>()
        pending = Prompt(answer, homeNetworkFirst)
        try {
            answer.await()
        } finally {
            pending = null
        }
    }

    /**
     * The controller to use, found before any PIN is asked for: the saved one or the hotspot when either answers,
     * otherwise the one the user picks by name ([change] always asks). Saved when [save], so the dashboard login goes
     * there. null: the user cancelled. Throws [ControllerUnreachable] when the picked one doesn't answer as itself.
     */
    suspend fun find(hotspot: String, change: Boolean = false, save: Boolean = true): DashboardTarget? {
        val found = (if (change) null else resolveDashboard(hotspot, settings)?.first) ?: run {
            val chosen = ask(homeNetworkFirst = true) ?: return null
            resolveDashboard(hotspot, settings, selected = chosen)?.first ?: throw ControllerUnreachable(null)
        }
        if (save) settings.rememberLanTarget(found, hotspot)
        return found
    }

    @Composable
    fun Dialog(hotspot: String) {
        val (answer, homeNetworkFirst) = pending ?: return
        val savedName = remember(answer) { settings.savedLanTarget()?.name }
        var useHomeNetwork by remember(answer) { mutableStateOf(homeNetworkFirst || savedName != null) }
        var discovered by remember(answer, hotspot) { mutableStateOf(emptyList<DashboardTarget>()) }
        var wifiNames by remember(answer, hotspot) { mutableStateOf(emptyMap<String, String>()) } // by host
        var searching by remember(answer, hotspot) { mutableStateOf(false) }
        // Leaving the home-network page cancels the scan; coming back scans again (e.g. the controller finished booting).
        if (useHomeNetwork) LaunchedEffect(answer, hotspot) {
            discovered = emptyList()
            searching = true
            val connection = currentLanConnection()
            dashboardProbeClient(connection).use { client ->
                client.findNamedDashboards(hotspot) { found, wifi ->
                    (wifi ?: settings.knownWifiName(found.name))?.let { wifiNames += found.host to it }
                    discovered = (discovered + found.copy(connectionId = connection?.id)).sortedBy { t -> t.name }
                }
            }
            searching = false
        }
        val canGoBack = useHomeNetwork && !homeNetworkFirst
        val goBack: () -> Unit = {
            if (canGoBack) {
                useHomeNetwork = false
            } else {
                answer.complete(null)
            }
        }
        AlertDialog(
            onDismissRequest = goBack,
            title = {
                Text(stringResource(if (useHomeNetwork) Res.string.lan_devices_title else Res.string.controller_wifi_title))
            },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (useHomeNetwork) {
                        val matches = discovered.groupBy { it.name }.values.sortedBy { it.first().name != savedName }
                        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when {
                            matches.isEmpty() && searching -> Text(stringResource(Res.string.lan_address_searching))
                            matches.isEmpty() -> Text(stringResource(Res.string.lan_no_named_devices))
                            else -> {
                                Text(stringResource(Res.string.lan_match_wifi_name))
                                matches.forEach { sameName ->
                                    val target = sameName.first()
                                    val label = wifiNames[target.host]?.let { "$it (${target.name})" } ?: target.name.orEmpty()
                                    when {
                                        sameName.size > 1 -> Text(stringResource(Res.string.lan_duplicate_name, target.name.orEmpty()))
                                        target.name == savedName -> Button({ answer.complete(target) }) { Text(label) }
                                        else -> OutlinedButton({ answer.complete(target) }) { Text(label) }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(stringResource(Res.string.controller_wifi_desc))
                        TextButton({ useHomeNetwork = true }) {
                            Text(stringResource(Res.string.use_home_wifi))
                        }
                    }
                }
            },
            confirmButton = {
                if (!useHomeNetwork) {
                    Button({ answer.complete(DashboardTarget(hotspot, savedName)) }) { Text(stringResource(Res.string.retry)) }
                }
            },
            dismissButton = {
                TextButton(goBack) {
                    Text(stringResource(if (canGoBack) Res.string.back else Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Section(
    title: String, text: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    below: (@Composable ColumnScope.() -> Unit)? = null, // extra content under the header row
    trailing: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                }
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            below?.invoke(this)
        }
    }
    if (onClick != null) PressCard(onClick, modifier.fillMaxWidth(), colors = colors, content = content)
    else Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = colors, content = content)
}

/** Background checks: controller offline, room too cold, open window. The check itself is in Watch.kt. */
@Composable
private fun AlertsCard(modifier: Modifier, settings: Settings, enable: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(alertsEnabled(settings)) }
    var cold by remember { mutableStateOf(coldSetting(settings)) }
    Section(
        stringResource(Res.string.alerts_title), stringResource(Res.string.alerts_desc), modifier = modifier,
        below = if (on) {{
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.alerts_cold), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TempStepper(cold, { cold = it; settings.putDouble("alerts.cold", it) }, buttonSize = 44.dp)
            }
        }} else null,
    ) {
        Switch(on, { on = it; settings.putBoolean("alerts.on", it); enable(it) })
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
    modifier: Modifier, host: String, settings: Settings, chooseController: suspend () -> Boolean?,
    requestLocalNetwork: suspend () -> Boolean, cloudStatus: suspend () -> Throwable?,
    restarting: Boolean, onChecked: () -> Unit, onRestart: () -> Unit,
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
            onChecked()
        }
    }

    fun pickController() {
        running = true
        scope.launch {
            try {
                if (!requestLocalNetwork()) {
                    local = Link.Down
                    return@launch
                }
                val found = chooseController() ?: return@launch
                local = if (found) Link.Ok else Link.Down
            } finally {
                running = false
            }
        }
    }

    Section(
        stringResource(Res.string.diag_title), stringResource(Res.string.diag_desc), modifier = modifier,
        container = MaterialTheme.colorScheme.secondaryContainer,
        below = {
            if (cloud != Link.Unknown) {
                LinkRow(stringResource(Res.string.diag_cloud), cloud)
                LinkRow(
                    stringResource(Res.string.diag_local), local,
                    onClick = if (local == Link.Unverified && !running && !restarting) ::pickController else null,
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
                    onRestart, enabled = !restarting && !running, shapes = ButtonDefaults.shapes(),
                    colors = onCardTonal(),
                ) { Text(stringResource(Res.string.restart)) }
            }
        },
    ) {
        if (running) LoadingIndicator(Modifier.size(40.dp))
        else Button(::check, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.diag_run)) }
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

private const val PIN_LENGTH = 4

/** The controller dashboard's 4-digit PIN, remembered for the Controller tab and the schedule. */
internal fun savedPin(s: Settings) = s.getStringOrNull("localPin")
internal fun savePin(s: Settings, pin: String?) = if (pin == null) s.remove("localPin") else s.putString("localPin", pin)

/** Masked, digits-only field for the dashboard PIN. */
@Composable
internal fun PinField(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null, label: String? = null,
    onDone: (() -> Unit)? = null, // the keyboard's done key
) {
    // ponytail: the secure field owns a TextFieldState; mirror it both ways so callers keep a plain String.
    val state = rememberTextFieldState(value)
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != latestValue) latestOnChange(it) }
    }
    OutlinedSecureTextField(
        state,
        label = label?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        inputTransformation = InputTransformation.maxLengthTrim(PIN_LENGTH).then {
            if (!asCharSequence().all(Char::isDigit)) revertAllChanges()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        onKeyboardAction = { default -> onDone?.invoke() ?: default() },
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    )
}

internal fun pinComplete(pin: String) = pin.length == PIN_LENGTH
