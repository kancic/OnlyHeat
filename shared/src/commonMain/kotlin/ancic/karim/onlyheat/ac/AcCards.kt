package ancic.karim.onlyheat.ac

import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.deg
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.AcIcon
import ancic.karim.onlyheat.ui.AirIcon
import ancic.karim.onlyheat.ui.ArrowBack
import ancic.karim.onlyheat.ui.AutoIcon
import ancic.karim.onlyheat.ui.Badge
import ancic.karim.onlyheat.ui.CardSwitch
import ancic.karim.onlyheat.ui.ChevronRightIcon
import ancic.karim.onlyheat.ui.CloseIcon
import ancic.karim.onlyheat.ui.DropIcon
import ancic.karim.onlyheat.ui.ExpandLessIcon
import ancic.karim.onlyheat.ui.FlameIcon
import ancic.karim.onlyheat.ui.Heat
import ancic.karim.onlyheat.ui.LocalCardShape
import ancic.karim.onlyheat.ui.Section
import ancic.karim.onlyheat.ui.SignalIcons
import ancic.karim.onlyheat.ui.SunIcon
import ancic.karim.onlyheat.ui.TempStepper
import ancic.karim.onlyheat.ui.ThermostatIcon
import ancic.karim.onlyheat.ui.VisibilityIcon
import ancic.karim.onlyheat.ui.VisibilityOffIcon
import ancic.karim.onlyheat.ui.WarningIcon
import ancic.karim.onlyheat.ui.onCardTonal
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AcDiscoveryCard(
    discovering: Boolean, enabled: Boolean, modifier: Modifier, onClose: (() -> Unit)?, onDiscover: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onDiscover,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = LocalCardShape.current,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(AcIcon, MaterialShapes.Cookie9Sided, accent = 1)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (discovering) Res.string.ac_discovering else Res.string.ac_discover),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    stringResource(Res.string.ac_discovery_wifi),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (discovering) LoadingIndicator(Modifier.size(24.dp))
            else if (onClose != null) IconButton(onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(CloseIcon, stringResource(Res.string.close), tint = scheme.onSurfaceVariant)
            }
            else Icon(ArrowBack, contentDescription = null, Modifier.size(24.dp).rotate(180f), tint = scheme.onSurfaceVariant)
        }
    }
}

/** Card on the Rooms tab when an unassigned AC answers on the Wi-Fi: one tap on a room pairs it.
 *  [minimized]: one row as tall as the All rooms / Away cards, with a button to open it again. */
@Composable
internal fun AcFoundCard(
    rooms: List<Room>, busy: Boolean, minimized: Boolean, modifier: Modifier,
    onRoom: (Room) -> Unit, onMinimize: (Boolean) -> Unit,
    /** Non-null while the AC is on its own Wi-Fi: asks for the home Wi-Fi (from [networks]) instead of a room. */
    onJoinWifi: ((ssid: String, password: String) -> Unit)? = null, networks: List<NearbyWifi> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    var askWifi by remember { mutableStateOf(false) }
    if (askWifi && onJoinWifi != null) WifiDialog(networks, { askWifi = false }) { ssid, password -> askWifi = false; onJoinWifi(ssid, password) }
    Card(
        modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = LocalCardShape.current,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
    ) {
        // On its own Wi-Fi: one row like the minimized card; the home Wi-Fi is asked in a dialog.
        val wifi = onJoinWifi != null
        val oneRow = minimized || wifi
        Column(if (oneRow) Modifier.padding(horizontal = 16.dp, vertical = 10.dp) else Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { // QuickCard's stepper height
                Badge(AcIcon, MaterialShapes.Cookie9Sided)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (oneRow) Res.string.ac_title else Res.string.ac_found_title),
                        style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (wifi) Text(stringResource(Res.string.ac_wifi_question), style = MaterialTheme.typography.bodyMedium)
                    else if (!minimized) Text(stringResource(Res.string.ac_found_question), style = MaterialTheme.typography.bodyMedium)
                }
                if (wifi) {
                    if (busy) LoadingIndicator(Modifier.size(40.dp))
                    else FilledTonalButton({ askWifi = true }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
                        Text(stringResource(Res.string.ac_wifi_connect))
                    }
                }
                else if (minimized) FilledTonalButton({ onMinimize(false) }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
                    Text(stringResource(Res.string.ac_choose_room))
                }
                else IconButton({ onMinimize(true) }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(ExpandLessIcon, stringResource(Res.string.ac_minimize))
                }
            }
            if (!oneRow) {
                Spacer(Modifier.height(4.dp))
                if (busy) LoadingIndicator(Modifier.padding(start = 4.dp).size(40.dp))
                else FlowRow(
                    Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), // rows are spaced by each button's 48dp touch target
                ) {
                    val small = ButtonDefaults.ExtraSmallContainerHeight
                    rooms.forEach { r ->
                        FilledTonalButton( // like Set all / Go away
                            onClick = { onRoom(r) }, modifier = Modifier.heightIn(small), shapes = ButtonDefaults.shapesFor(small),
                            colors = onCardTonal(),
                            contentPadding = ButtonDefaults.contentPaddingFor(small),
                        ) { Text(r.name, style = ButtonDefaults.textStyleFor(small)) }
                    }
                }
            }
        }
    }
}

/** The home Wi-Fi for an AC on its own Wi-Fi: picked from the nearby 2.4 GHz networks (or typed, for a hidden one).
 *  It starts as the phone's current Wi-Fi if that's one of them, or wherever the platform can't scan. */
@Composable
private fun WifiDialog(networks: List<NearbyWifi>, onDismiss: () -> Unit, onJoin: (ssid: String, password: String) -> Unit) {
    var ssid by rememberSaveable {
        val current = currentWifi()?.takeIf { (s, b) -> hotspotOf(s, b) == null }?.first
        mutableStateOf(current?.takeIf { networks.isEmpty() || networks.any { n -> n.ssid == it } }.orEmpty())
    }
    var picking by remember { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(networks.isEmpty()) } // a hidden network, or nothing listed
    val password = remember { TextFieldState() }
    var showPassword by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AcIcon, contentDescription = null) },
        title = { Text(stringResource(Res.string.ac_wifi_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(picking, { picking = it && !typing }) {
                    // A picker while there are networks to pick (no keyboard); a text field after Other network.
                    OutlinedTextField(
                        ssid, { ssid = it },
                        Modifier.menuAnchor(
                            if (typing) ExposedDropdownMenuAnchorType.PrimaryEditable else ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        ).fillMaxWidth(),
                        readOnly = !typing, singleLine = true,
                        label = { Text(stringResource(Res.string.ac_wifi_name)) },
                        supportingText = { Text(stringResource(Res.string.ac_wifi_band)) },
                        trailingIcon = if (typing) null else {
                            { ExposedDropdownMenuDefaults.TrailingIcon(picking) }
                        },
                    )
                    ExposedDropdownMenu(picking, { picking = false }) {
                        networks.forEach { n ->
                            // Bars like Android's Wi-Fi list: -55 dBm and up is full.
                            val bars = when { n.dbm >= -55 -> 4; n.dbm >= -66 -> 3; n.dbm >= -77 -> 2; else -> 1 }
                            DropdownMenuItem(
                                { Text(n.ssid) }, { ssid = n.ssid; picking = false },
                                trailingIcon = { Icon(SignalIcons[bars - 1], stringResource(Res.string.ac_wifi_signal, bars)) },
                            )
                        }
                        DropdownMenuItem({ Text(stringResource(Res.string.ac_wifi_other)) }, { ssid = ""; typing = true; picking = false })
                    }
                }
                OutlinedSecureTextField( // like the PIN field
                    password, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.ac_wifi_password)) },
                    textObfuscationMode = if (showPassword) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton({ showPassword = !showPassword }, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                if (showPassword) VisibilityOffIcon else VisibilityIcon,
                                stringResource(if (showPassword) Res.string.ac_wifi_hide_password else Res.string.ac_wifi_show_password),
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            // A listed name goes out as listed (SSIDs may end in spaces); a typed one loses stray spaces.
            TextButton({ onJoin(if (typing) ssid.trim() else ssid, password.text.toString()) }, enabled = ssid.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.ac_wifi_connect))
            }
        },
        dismissButton = {
            TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

/** All ACs in one phrase for the Rooms header ("AC heating"); null without any, or while none answers. */
@Composable
internal fun acSummary(states: List<AcState?>): String? {
    val known = states.filterNotNull().ifEmpty { return null }
    val modes = known.filter { it.power }.map { it.mode }.toSet()
    return stringResource(
        when (modes) {
            emptySet<AcMode>() -> Res.string.ac_header_off
            setOf(AcMode.Heat) -> Res.string.ac_header_heating
            setOf(AcMode.Cool) -> Res.string.ac_header_cooling
            else -> Res.string.ac_header_on
        }
    )
}

/** What a room's AC is doing, in words. [loaded] false = still asking it; null [state] = it didn't answer. */
@Composable
internal fun acText(state: AcState?, loaded: Boolean): String = when {
    !loaded -> stringResource(Res.string.ac_short)
    state == null -> stringResource(Res.string.ac_at_home_only)
    !state.power -> stringResource(Res.string.ac_off)
    else -> when (state.mode) {
        AcMode.Cool -> stringResource(Res.string.ac_cooling_to, state.target)
        AcMode.Heat -> stringResource(Res.string.ac_heating_to, state.target)
        AcMode.Dry -> stringResource(Res.string.ac_drying)
        AcMode.Fan -> stringResource(Res.string.ac_fan_only)
        AcMode.Auto -> stringResource(Res.string.ac_auto_at, state.target)
    }
}

/** One device on a room card: icon and [label], then the target and the switch. The heating and AC rows share it
 *  so neither looks like the lesser one. The icon fades unless [on]; a null [on] (can't reach it) hides the switch.
 *  Tapping the name runs [onLabel]. [description] tells TalkBack what the icon shows. */
@Composable
internal fun DeviceRow(
    icon: ImageVector, label: String, on: Boolean?, onSwitch: (Boolean) -> Unit,
    onLabel: (() -> Unit)? = null, description: String? = null, trailing: @Composable () -> Unit = {},
) {
    val ink = LocalContentColor.current
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                .then(if (onLabel != null) Modifier.clickable(role = Role.Button, onClick = onLabel) else Modifier)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, description, Modifier.size(22.dp), tint = if (on == true) ink else ink.copy(alpha = 0.5f))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // A tappable name says so: a chevron like a list item's.
            if (onLabel != null) Icon(ChevronRightIcon, null, Modifier.size(20.dp), tint = ink.copy(alpha = 0.6f))
        }
        trailing()
        if (on != null) {
            Spacer(Modifier.width(8.dp))
            CardSwitch(on, onSwitch)
        }
    }
}

/** The AC's mode as an icon: snowflake for cool (and off), sun, drop, wind, or arrows for auto. */
internal fun acIcon(state: AcState?) = when (state?.mode?.takeIf { state.power }) {
    AcMode.Heat -> SunIcon
    AcMode.Dry -> DropIcon
    AcMode.Fan -> AirIcon
    AcMode.Auto -> AutoIcon
    else -> AcIcon
}

/** The floor heating as an icon: a flame while heating, a thermostat while holding its target (and a flame when off). */
internal fun floorIcon(on: Boolean, heating: Boolean) = if (on && !heating) ThermostatIcon else FlameIcon

/** The AC's target in a word or number, for the room details: "27°", "off", "Dry", "Not home". */
@Composable
internal fun acValue(state: AcState?, loaded: Boolean): String = when {
    !loaded -> "–"
    state == null -> stringResource(Res.string.ac_pill_away)
    !state.power -> stringResource(Res.string.off_lower)
    state.mode == AcMode.Dry -> stringResource(Res.string.ac_mode_dry)
    state.mode == AcMode.Fan -> stringResource(Res.string.ac_mode_fan)
    else -> deg(state.target.toDouble()) // like the floor and the stepper
}

/** The AC's row on a room card, the twin of the heating row. The icon shows the mode; tapping the name opens
 *  mode and fan ([onMore]). */
@Composable
internal fun AcRow(ac: AcView, conflict: Boolean, onChange: (AcChange) -> Unit, onMore: () -> Unit) {
    val (state, loaded) = ac
    DeviceRow(
        if (conflict) WarningIcon else acIcon(state), stringResource(Res.string.ac_short), state?.power,
        { onChange(AcChange(power = it)) }, onMore,
        if (conflict) stringResource(Res.string.ac_fighting) else acText(state, loaded),
    ) {
        when {
            !loaded -> LoadingIndicator(Modifier.size(32.dp))
            state == null -> Text(
                stringResource(Res.string.ac_pill_away), style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            !state.power -> {}
            // No target in these modes: name the mode so the row isn't empty.
            state.mode == AcMode.Fan || state.mode == AcMode.Dry -> Text(acValue(state, true), style = MaterialTheme.typography.titleMedium)
            else -> TempStepper(
                state.target.toDouble(), { onChange(AcChange(target = it.toInt())) }, compact = true,
                step = 1.0, min = AC_MIN_T.toDouble(), max = AC_MAX_T.toDouble(),
            )
        }
    }
}

/** Controls for a room's AC, opened from the room card's AC button. */
@Composable
internal fun AcSheet(
    room: Room, ac: AcView, onChange: (AcChange) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
    account: @Composable () -> Unit = {}, // [AcCloudAccount]
    warning: @Composable () -> Unit = {}, // the fight card, so a change made here that starts a fight says so
) {
    ModalBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            warning()
            Row(verticalAlignment = Alignment.CenterVertically) { // the room screen's AC card header, plus the room
                Badge(acIcon(ac.state), MaterialShapes.Cookie9Sided, accent = 2, modifier = Modifier.align(Alignment.Top))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.ac_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    // Room and status on their own lines: joined, a long room name wraps mid-status.
                    listOf(room.name, acText(ac.state, ac.loaded)).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.width(12.dp))
                AcPower(ac, onChange)
            }
            ac.state?.let { AcControls(it, onChange) }
            account()
            AcRemove(onRemove)
        }
    }
}

/** The same controls as a Settings card on the room's detail screen, tinted while the AC is on. */
@Composable
internal fun AcCard(ac: AcView, onChange: (AcChange) -> Unit, onRemove: () -> Unit, account: @Composable () -> Unit = {}) {
    val state = ac.state
    Section(
        stringResource(Res.string.ac_title), acText(state, ac.loaded), acIcon(state), MaterialShapes.Cookie9Sided, accent = 2,
        container = if (state?.power == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        below = {
            state?.let { AcControls(it, onChange) }
            account()
            AcRemove(onRemove) // also while the AC can't be reached
        },
    ) { AcPower(ac, onChange) }
}

@Composable
private fun AcPower(ac: AcView, onChange: (AcChange) -> Unit) {
    if (ac.state != null) Switch(ac.state.power, { onChange(AcChange(power = it)) })
    else if (!ac.loaded) LoadingIndicator(Modifier.size(32.dp))
}

/** Away from home: an offer to sign in to the AC's Gree+ / EWPE Smart account, which reaches it through Gree's cloud.
 *  Signed in: a quiet way out, like [AcRemove]. */
@Composable
internal fun AcCloudAccount(acs: AcViewModel, ac: AcView) {
    if (!acs.cloudAvailable) return
    var asking by remember { mutableStateOf(false) }
    if (asking) CloudDialog({ asking = false }) { user, password -> asking = false; acs.cloudSignIn(user, password) }
    val user = acs.cloudUser
    if (user != null) TextButton(
        acs::cloudSignOut, shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), modifier = Modifier.offset(x = (-16).dp),
    ) { Text(stringResource(Res.string.ac_cloud_sign_out, user)) }
    else if (ac.loaded && ac.state == null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.ac_cloud_offer), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        if (acs.signingIn) LoadingIndicator(Modifier.size(40.dp))
        else FilledTonalButton({ asking = true }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
            Text(stringResource(Res.string.ac_cloud_sign_in))
        }
    }
}

@Composable
private fun CloudDialog(onDismiss: () -> Unit, onSignIn: (user: String, password: String) -> Unit) {
    var user by rememberSaveable { mutableStateOf("") }
    val password = remember { TextFieldState() }
    var showPassword by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AcIcon, contentDescription = null) },
        title = { Text(stringResource(Res.string.ac_cloud_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.ac_cloud_hint))
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(Res.string.ac_cloud_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
                OutlinedSecureTextField( // like the Wi-Fi password
                    password, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.ac_cloud_password)) },
                    textObfuscationMode = if (showPassword) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton({ showPassword = !showPassword }, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                if (showPassword) VisibilityOffIcon else VisibilityIcon,
                                stringResource(if (showPassword) Res.string.ac_wifi_hide_password else Res.string.ac_wifi_show_password),
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                { onSignIn(user, password.text.toString()) }, enabled = user.isNotBlank() && password.text.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.ac_cloud_sign_in)) }
        },
        dismissButton = {
            TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

/** Rarely used, so a quiet text button in the card's own ink, not a tonal one like the card's actions. */
@Composable
private fun AcRemove(onRemove: () -> Unit) = TextButton(
    onRemove, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
    modifier = Modifier.offset(x = (-16).dp), // text in line with the labels above
) { Text(stringResource(Res.string.ac_remove)) }

/** Mode, temperature and fan. */
@Composable
private fun AcControls(state: AcState, onChange: (AcChange) -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Both rows keep a label: each has an Auto.
        Text(stringResource(Res.string.ac_mode), style = MaterialTheme.typography.labelLarge)
        val modes = listOf(
            AcMode.Cool to Res.string.ac_mode_cool, AcMode.Heat to Res.string.ac_mode_heat, AcMode.Dry to Res.string.ac_mode_dry,
            AcMode.Fan to Res.string.ac_mode_fan, AcMode.Auto to Res.string.ac_mode_auto,
        )
        Segmented(modes.map { it.first to stringResource(it.second) }, state.mode, state.power) { onChange(AcChange(mode = it)) }
        if (state.mode != AcMode.Fan) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Like Thermal Intelligence's target row.
            Text(stringResource(Res.string.smart_target), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TempStepper(
                state.target.toDouble(), { onChange(AcChange(target = it.toInt())) },
                buttonSize = 44.dp, step = 1.0, min = AC_MIN_T.toDouble(), max = AC_MAX_T.toDouble(),
            )
        }
        Text(stringResource(Res.string.ac_fan), style = MaterialTheme.typography.labelLarge)
        val fans = listOf(
            AcFan.Auto to Res.string.ac_fan_auto, AcFan.Low to Res.string.ac_fan_low,
            AcFan.Medium to Res.string.ac_fan_medium, AcFan.High to Res.string.ac_fan_high,
        )
        Segmented(fans.map { it.first to stringResource(it.second) }, state.fan, state.power) { onChange(AcChange(fan = it)) }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    val ink = LocalContentColor.current
    ButtonGroup(
        overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (value, label) ->
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = value == selected, onCheckedChange = { onSelect(value) }, enabled = enabled,
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ink.let { // unchecked in the card's own ink like the other card buttons, not a dark hole
                            ToggleButtonDefaults.colors(containerColor = it.copy(alpha = 0.12f), contentColor = it)
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp), // "Medium" / "Srednja" fit on a phone-width card
                    ) { Text(label, maxLines = 1) }
                },
                menuContent = { menu ->
                    DropdownMenuItem({ Text(label) }, { onSelect(value); menu.dismiss() }, enabled = enabled)
                },
            )
        }
    }
}
