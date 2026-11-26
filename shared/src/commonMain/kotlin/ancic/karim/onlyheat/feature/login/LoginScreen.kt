package ancic.karim.onlyheat.feature.login

import ancic.karim.onlyheat.data.ApiException
import ancic.karim.onlyheat.data.ControllerUnreachable
import ancic.karim.onlyheat.data.DashboardTarget
import ancic.karim.onlyheat.data.HOTSPOT
import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.LocalApi
import ancic.karim.onlyheat.data.Pairing
import ancic.karim.onlyheat.data.VerifiedDashboard
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.saveLoginDashboard
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.FlameIcon
import ancic.karim.onlyheat.ui.Heat
import ancic.karim.onlyheat.ui.LanControllerPicker
import ancic.karim.onlyheat.ui.PinField
import ancic.karim.onlyheat.ui.errorText
import ancic.karim.onlyheat.ui.expanded
import ancic.karim.onlyheat.ui.flames
import ancic.karim.onlyheat.ui.pinComplete
import ancic.karim.onlyheat.ui.springIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.russhwolf.settings.Settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
internal fun LoginScreen(
    api: HeatApi,
    requestLocalNetwork: suspend () -> Boolean,
    statusBarOnDark: (Boolean) -> Unit,
    onKey: (Pairing) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings = koinInject<Settings>()
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
