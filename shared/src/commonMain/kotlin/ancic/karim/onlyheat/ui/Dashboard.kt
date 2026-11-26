package ancic.karim.onlyheat.ui

import ancic.karim.onlyheat.data.ControllerUnreachable
import ancic.karim.onlyheat.data.DashboardTarget
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.currentLanConnection
import ancic.karim.onlyheat.data.dashboardProbeClient
import ancic.karim.onlyheat.data.findNamedDashboards
import ancic.karim.onlyheat.data.knownWifiName
import ancic.karim.onlyheat.data.nameOf
import ancic.karim.onlyheat.data.rememberLanTarget
import ancic.karim.onlyheat.data.resolveDashboard
import ancic.karim.onlyheat.data.savedLanTarget
import ancic.karim.onlyheat.res.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.stringResource

// Shared by the login, the schedule and the Controller tab: reaching the controller's dashboard.

private const val PIN_LENGTH = 4

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

internal class LanControllerPicker(private val settings: Settings) {
    private data class Prompt(
        val answer: CompletableDeferred<DashboardTarget?>,
        val homeNetworkFirst: Boolean,
        val onEditPin: (() -> Unit)?,
    )

    private val requests = Mutex()
    private var pending by mutableStateOf<Prompt?>(null)
    val isOpen get() = pending != null

    suspend fun ask(homeNetworkFirst: Boolean = false, onEditPin: (() -> Unit)? = null): DashboardTarget? = requests.withLock {
        val answer = CompletableDeferred<DashboardTarget?>()
        pending = Prompt(answer, homeNetworkFirst, onEditPin)
        try {
            answer.await()
        } finally {
            pending = null
        }
    }

    /**
     * The controller to use, found before any PIN is asked for: the saved one or the hotspot when either answers,
     * otherwise the one the user picks by name ([change] asks unless the phone is on a controller's own Wi-Fi). Saved when [save], so the dashboard login goes
     * there. null: the user cancelled. Throws [ControllerUnreachable] when the picked one doesn't answer as itself.
     */
    suspend fun find(
        hotspot: String, change: Boolean = false, save: Boolean = true, onEditPin: (() -> Unit)? = null,
    ): DashboardTarget? {
        val found = (if (change) onHotspot(hotspot) else resolveDashboard(hotspot, settings)?.first) ?: run {
            val chosen = ask(homeNetworkFirst = true, onEditPin = onEditPin) ?: return null
            resolveDashboard(hotspot, settings, selected = chosen)?.first ?: throw ControllerUnreachable(null)
        }
        if (save) settings.rememberLanTarget(found, hotspot)
        return found
    }

    /** On the controller's own Wi-Fi there is nothing to choose: joining it picked the controller. */
    private suspend fun onHotspot(hotspot: String): DashboardTarget? {
        val name = dashboardProbeClient(currentLanConnection()).use { it.nameOf(hotspot) } ?: return null
        return DashboardTarget(hotspot, name)
    }

    @Composable
    fun Dialog(hotspot: String) {
        val (answer, homeNetworkFirst, onEditPin) = pending ?: return
        val savedName = remember(answer) { settings.savedLanTarget()?.name }
        var useHomeNetwork by remember(answer) { mutableStateOf(homeNetworkFirst || savedName != null) }
        var discovered by remember(answer, hotspot) { mutableStateOf(emptyList<DashboardTarget>()) }
        var wifiNames by remember(answer, hotspot) { mutableStateOf(emptyMap<String, String>()) } // by host
        var searching by remember(answer, hotspot) { mutableStateOf(useHomeNetwork) }
        var canEditPin by remember(answer, hotspot) { mutableStateOf(false) }
        // Leaving the home-network page cancels the scan; coming back scans again (e.g. the controller finished booting).
        if (useHomeNetwork) LaunchedEffect(answer, hotspot) {
            discovered = emptyList()
            searching = true
            canEditPin = false
            val connection = currentLanConnection()
            dashboardProbeClient(connection).use { client ->
                client.findNamedDashboards { found, wifi ->
                    (wifi ?: settings.knownWifiName(found.name))?.let { wifiNames += found.host to it }
                    discovered = (discovered + found.copy(connectionId = connection?.id)).sortedBy { t -> t.name }
                }
            }
            if (onEditPin != null && discovered.none { it.name == savedName }) {
                val saved = if (savedName != null) attempt { resolveDashboard(hotspot, settings) }.getOrNull()?.first else null
                if (saved == null) canEditPin = true else discovered += saved
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
                        if (searching) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
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
                } else if (canEditPin && onEditPin != null) {
                    TextButton({
                        if (answer.complete(null)) onEditPin()
                    }) {
                        Text(stringResource(Res.string.edit_pin))
                    }
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
