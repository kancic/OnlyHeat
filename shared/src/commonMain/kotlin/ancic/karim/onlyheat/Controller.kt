package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Controller tab: things that talk to the controller's local dashboard, plus the account. */
@Composable
fun ControllerTab(
    device: String?, host: String, requestLocalNetwork: suspend () -> Boolean,
    cloudStatus: suspend () -> Throwable?, // null when the cloud can reach the controller right now
    snackbar: SnackbarHostState, backgroundAlerts: ((Boolean) -> Unit)?, onLogout: () -> Unit, openSchedule: () -> Unit,
) {
    val settings = remember { Settings() }
    var pin by remember { mutableStateOf(savedPin(settings)) }
    var askPin by remember { mutableStateOf(false) }
    var restartAfterPin by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun restart() {
        val p = pin ?: run { restartAfterPin = true; askPin = true; return }
        restarting = true
        scope.launch {
            try {
                if (!requestLocalNetwork()) {
                    snackbar.showSnackbar(getString(Res.string.local_permission_denied)); return@launch
                }
                val back = LocalApi(host, p).use { it.rebootAndWait { cloudStatus() == null } }
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
                subtitle = { Text(listOfNotNull(device, host).joinToString(" · ")) },
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

            DiagnosticsCard(Modifier.springIn(0), host, requestLocalNetwork, cloudStatus, restarting) { confirmRestart = true }

            if (backgroundAlerts != null) AlertsCard(Modifier.springIn(1), settings, backgroundAlerts)

            Section(stringResource(Res.string.schedule_title), stringResource(Res.string.schedule_card_desc), onClick = openSchedule,
                modifier = Modifier.springIn(2).shared("schedule"), container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("›", style = MaterialTheme.typography.headlineMedium)
            }

            Section(stringResource(Res.string.restart_card_title), stringResource(Res.string.restart_card_desc), modifier = Modifier.springIn(3), container = MaterialTheme.colorScheme.primaryContainer) {
                FilledTonalButton({ confirmRestart = true }, enabled = !restarting, shapes = ButtonDefaults.shapes()) {
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

            Section(stringResource(Res.string.account), stringResource(Res.string.account_desc), modifier = Modifier.springIn(5)) {
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
    if (restarting) AlertDialog(
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

private enum class Link { Unknown, Ok, Down, NoInternet }

/**
 * Checks both paths to the controller and says what that means:
 * cloud ok → fine; cloud down but local ok → only the cloud link is stuck (restart helps);
 * both down → frozen (breaker) or the phone is simply not on the controller's Wi-Fi.
 */
@Composable
private fun DiagnosticsCard(
    modifier: Modifier, host: String, requestLocalNetwork: suspend () -> Boolean, cloudStatus: suspend () -> Throwable?,
    restarting: Boolean, onRestart: () -> Unit,
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
                launch { local = if (requestLocalNetwork() && controllerAnswers(host)) Link.Ok else Link.Down }
            }
            log("diagnostics: cloud=$cloud local=$local ($host)")
            running = false
        }
    }

    Section(
        stringResource(Res.string.diag_title), stringResource(Res.string.diag_desc), modifier = modifier,
        container = MaterialTheme.colorScheme.secondaryContainer,
        below = {
            if (cloud != Link.Unknown) {
                LinkRow(stringResource(Res.string.diag_cloud), cloud)
                LinkRow(stringResource(Res.string.diag_local), local)
                val verdict = when {
                    cloud == Link.NoInternet -> Res.string.diag_verdict_no_internet
                    cloud == Link.Ok && local == Link.Ok -> Res.string.diag_verdict_ok
                    cloud == Link.Ok -> Res.string.diag_verdict_ok_remote
                    local == Link.Ok -> Res.string.diag_verdict_cloud_link
                    else -> Res.string.diag_verdict_nothing
                }
                Text(stringResource(verdict), style = MaterialTheme.typography.bodyLarge)
                if (cloud == Link.Down && local == Link.Ok) FilledTonalButton(
                    onRestart, enabled = !restarting, shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.restart)) }
            }
        },
    ) {
        if (running) LoadingIndicator(Modifier.size(40.dp))
        else Button(::check, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.diag_run)) }
    }
}

@Composable
private fun LinkRow(label: String, state: Link) {
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
        Text(
            stringResource(if (ok) Res.string.diag_ok else Res.string.diag_fail),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private const val PIN_LENGTH = 4

/** The controller dashboard's 4-digit PIN, remembered for the Controller tab and the schedule. */
internal fun savedPin(s: Settings) = s.getStringOrNull("localPin")
internal fun savePin(s: Settings, pin: String?) = if (pin == null) s.remove("localPin") else s.putString("localPin", pin)

/** Masked, digits-only field for the dashboard PIN. */
@Composable
internal fun PinField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null) {
    OutlinedTextField(
        value, { v -> onChange(v.filter(Char::isDigit).take(PIN_LENGTH)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    )
}

internal fun pinComplete(pin: String) = pin.length == PIN_LENGTH
