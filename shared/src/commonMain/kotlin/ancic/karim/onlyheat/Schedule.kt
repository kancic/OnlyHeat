package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.russhwolf.settings.Settings
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private val DAYS = listOf(
    "0" to Res.string.day_every, "Mon" to Res.string.day_mon, "Tue" to Res.string.day_tue,
    "Wed" to Res.string.day_wed, "Thu" to Res.string.day_thu, "Fri" to Res.string.day_fri,
    "Sat" to Res.string.day_sat, "Sun" to Res.string.day_sun,
)

internal sealed interface Load {
    data object Busy : Load
    data object NeedPin : Load
    data class Failed(val message: String) : Load
    data object Ready : Load
}

/** What the schedule editor shows, including unsaved edits, so a theme or language change keeps them. */
internal class ScheduleModel : ViewModel() {
    val state = mutableStateOf<Load>(Load.Busy)
    val rooms = mutableStateOf(emptyList<Pair<String, String>>())
    val saved = mutableStateOf(emptyList<ScheduleEntry>())
    val entries = mutableStateListOf<ScheduleEntry>()
    val wrongPin = mutableStateOf(false)
    private var needsRefresh = false
    val needsLoad get() = state.value != Load.Ready || needsRefresh

    fun showSchedule(schedule: Schedule, keepEdits: Boolean = needsRefresh || entries.toList() != saved.value) {
        rooms.value = schedule.rooms
        saved.value = schedule.entries
        if (!keepEdits) {
            entries.clear()
            entries.addAll(schedule.entries)
        }
        needsRefresh = false
        state.value = Load.Ready
    }

    suspend fun save(store: suspend (List<ScheduleEntry>) -> Schedule?): Result<Unit> {
        needsRefresh = true
        val sent = entries.toList()
        return attempt {
            val schedule = store(sent)
            if (schedule != null) showSchedule(schedule, keepEdits = false)
            else saved.value = sent // stored, but the read-back failed
        }.also { needsRefresh = false } // attempt rethrows cancellation, leaving the refresh pending
    }
}

/** Edits the controller's schedule through its local dashboard (reachable on its Wi-Fi or the home Wi-Fi). */
@Composable
fun ScheduleScreen(
    host: String, requestLocalNetwork: suspend () -> Boolean, onSaved: () -> Unit, onBack: () -> Unit,
) {
    val settings = koinInject<Settings>()
    val controllerPicker = remember { LanControllerPicker(settings) }
    var pin by remember { mutableStateOf(savedPin(settings)) }
    val local = remember(host, pin) { pin?.let { LocalApi(host, it, settings) { controllerPicker.ask() } } }
    val m = viewModel { ScheduleModel() }
    var state by m.state
    val rooms by m.rooms
    val saved by m.saved
    val entries = m.entries
    var saving by remember { mutableStateOf(false) } // not in the model: a recreation cancels the save
    var wrongPin by m.wrongPin
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun clearWrongPin() {
        savePin(settings, null)
        pin = null
        wrongPin = true
        state = Load.NeedPin
    }

    suspend fun load() {
        // The controller was just reached: no need to look for it again.
        if (local == null && wrongPin) { state = Load.NeedPin; return }
        state = Load.Busy
        if (!requestLocalNetwork()) { state = Load.Failed(getString(Res.string.local_permission_denied)); return }
        try {
            val api = local ?: run {
                // The controller first, then its PIN.
                if (controllerPicker.find(host) == null) { onBack(); return } // the user cancelled the picker
                state = Load.NeedPin
                return
            }
            m.showSchedule(api.schedule())
        } catch (e: WrongDashboardPin) {
            clearWrongPin()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            state = Load.Failed(errorText(e))
        }
    }

    LaunchedEffect(local) { if (m.needsLoad) load() }
    controllerPicker.Dialog(host)

    val scroll = collapsingBar()
    Scaffold(
        modifier = Modifier.shared("schedule").collapsing(scroll),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(Res.string.schedule_title)) },
                subtitle = { Text(host) },
                navigationIcon = {
                    IconButton(onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        // Expressive floating toolbar: Save in a vibrant pill, Add as its attached FAB.
        floatingActionButton = {
            if (state == Load.Ready) HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                floatingActionButton = {
                    if (rooms.isNotEmpty()) FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = { entries += ScheduleEntry(rooms.first().first, "0", "06:00", "22:00", 21.0) },
                    ) {
                        val label = stringResource(Res.string.add_entry)
                        Text("+", Modifier.semantics { contentDescription = label }, style = MaterialTheme.typography.headlineSmall)
                    }
                },
            ) {
                TextButton(
                    onClick = {
                        saving = true
                        scope.launch {
                            m.save { local!!.save(it) }.onSuccess {
                                onSaved()
                                snackbar.showSnackbar(getString(Res.string.schedule_saved))
                            }.onFailure {
                                if (it is WrongDashboardPin) clearWrongPin()
                                snackbar.showSnackbar(errorText(it))
                            }
                            saving = false
                        }
                    },
                    enabled = !saving && entries.toList() != saved,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) { Text(stringResource(Res.string.save), style = MaterialTheme.typography.titleMedium) }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            // The PIN prompt and messages scroll when the keyboard or a short screen leaves too little room.
            val formScroll = rememberScrollState()
            val fill = Modifier.verticalScroll(formScroll).heightIn(min = maxHeight)
            when (val s = state) {
                Load.Busy -> LoadingIndicator(Modifier.size(64.dp).align(Alignment.Center))
                Load.NeedPin -> PinPrompt(fill, wrongPin) { savePin(settings, it); pin = it; wrongPin = false }
                is Load.Failed -> Message("${s.message}\n${stringResource(Res.string.schedule_local_only)}", fill.fillMaxWidth()) {
                    scope.launch { load() }
                }
                Load.Ready -> LazyColumn(
                    contentPadding = cappedPadding(720.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            stringResource(Res.string.schedule_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entries.isEmpty()) item { Text(stringResource(Res.string.schedule_empty)) }
                    itemsIndexed(entries) { i, e ->
                        EntryCard(e, rooms, onChange = { entries[i] = it }, onRemove = { entries.removeAt(i) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    e: ScheduleEntry, rooms: List<Pair<String, String>>,
    onChange: (ScheduleEntry) -> Unit, onRemove: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Choice(
                    rooms.find { it.first == e.roomId }?.second ?: e.roomId,
                    rooms, { onChange(e.copy(roomId = it)) }, Modifier.weight(1f),
                )
                TextButton(onRemove, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.remove)) }
            }
            Choice(
                stringResource(DAYS.find { it.first == e.day }?.second ?: Res.string.day_every),
                DAYS.map { (v, label) -> v to stringResource(label) }, { onChange(e.copy(day = it)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton(Res.string.time_from, e.start) { onChange(e.copy(start = it)) }
                TimeButton(Res.string.time_to, e.end) { onChange(e.copy(end = it)) }
            }
            TempStepper(e.target, { onChange(e.copy(target = it)) }, buttonSize = 44.dp)
        }
    }
}

@Composable
private fun Choice(
    current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }, shapes = ButtonDefaults.shapes()) { Text("$current  ▾") }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

@Composable
private fun TimeButton(label: StringResource, time: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton({ open = true }, shapes = ButtonDefaults.shapes(), colors = onCardTonal()) {
        Text("${stringResource(label)} $time")
    }
    if (open) {
        val (h, m) = time.split(":").map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 6 } to it.getOrElse(1) { 0 } }
        val picker = rememberTimePickerState(h, m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    onPick(LocalTime(picker.hour, picker.minute).toString())
                    open = false
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = { TextButton({ open = false }) { Text(stringResource(Res.string.cancel)) } },
            text = { TimePicker(picker) },
        )
    }
}

@Composable
private fun PinPrompt(modifier: Modifier, wrong: Boolean, onPin: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(24.dp), Arrangement.Center) {
        Text(stringResource(Res.string.dashboard_pin), style = MaterialTheme.typography.titleLargeEmphasized)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.dashboard_pin_desc))
        Spacer(Modifier.height(16.dp))
        PinField(
            value, { value = it }, Modifier.fillMaxWidth(),
            error = if (wrong) stringResource(Res.string.wrong_dashboard_pin) else null,
            onDone = { if (pinComplete(value)) onPin(value) },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            { onPin(value) }, enabled = pinComplete(value), shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
        ) { Text(stringResource(Res.string.connect)) }
    }
}
