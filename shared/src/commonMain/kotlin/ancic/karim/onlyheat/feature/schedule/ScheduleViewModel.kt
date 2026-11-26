package ancic.karim.onlyheat.feature.schedule

import ancic.karim.onlyheat.data.Schedule
import ancic.karim.onlyheat.data.ScheduleEntry
import ancic.karim.onlyheat.data.attempt
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel

internal sealed interface Load {
    data object Busy : Load
    data object NeedPin : Load
    data class Failed(val message: String) : Load
    data object Ready : Load
}

/** What the schedule editor shows, including unsaved edits, so a theme or language change keeps them. */
internal class ScheduleViewModel : ViewModel() {
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
