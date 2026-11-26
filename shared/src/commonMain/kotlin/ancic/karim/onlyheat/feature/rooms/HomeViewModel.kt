package ancic.karim.onlyheat.feature.rooms

import ancic.karim.onlyheat.data.AuthExpired
import ancic.karim.onlyheat.data.Boost
import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.Home
import ancic.karim.onlyheat.data.OperationNotice
import ancic.karim.onlyheat.data.ReadingDao
import ancic.karim.onlyheat.data.Room
import ancic.karim.onlyheat.data.WINDOW_MAX_GAP
import ancic.karim.onlyheat.data.WatchState
import ancic.karim.onlyheat.data.Weather
import ancic.karim.onlyheat.data.alertsEnabled
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.awaySaved
import ancic.karim.onlyheat.data.closeFirmwareChange
import ancic.karim.onlyheat.data.decodeSet
import ancic.karim.onlyheat.data.endExpiredBoosts
import ancic.karim.onlyheat.data.firmwareChange
import ancic.karim.onlyheat.data.firmwareRevision
import ancic.karim.onlyheat.data.fmt1
import ancic.karim.onlyheat.data.loadBoosts
import ancic.karim.onlyheat.data.loadOperationNotices
import ancic.karim.onlyheat.data.loadWatch
import ancic.karim.onlyheat.data.loadWriteNotices
import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.noticeSession
import ancic.karim.onlyheat.data.nowSeconds
import ancic.karim.onlyheat.data.putBoost
import ancic.karim.onlyheat.data.removeBoost
import ancic.karim.onlyheat.data.saveAway
import ancic.karim.onlyheat.data.saveOperationNotice
import ancic.karim.onlyheat.data.saveWriteNotice
import ancic.karim.onlyheat.data.savedAuthKey
import ancic.karim.onlyheat.data.syncHistory
import ancic.karim.onlyheat.data.warmSetting
import ancic.karim.onlyheat.res.*
import ancic.karim.onlyheat.ui.MAX_T
import ancic.karim.onlyheat.ui.errorText
import ancic.karim.onlyheat.ui.keptMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Room state and writes; outlives tab switches, pushed screens and activity recreation.
 * The callbacks come from the current composition (they hold the activity), so [Main] re-assigns them
 * on every composition instead of the model keeping the first activity's.
 */
internal class HomeViewModel(
    private val api: HeatApi,
    private val history: ReadingDao,
    private val key: String,
    private val settings: Settings,
    private val weatherClock: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    // Writes must finish even if the user leaves right after tapping (Back within the 700 ms debounce),
    // so not viewModelScope, which is cancelled when the activity finishes.
    // ponytail: never cancelled, lives as long as the process; fine for a few short writes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val snackbar = SnackbarHostState()
    var visible: () -> Boolean = { true }
    var notify: (suspend (String) -> Unit)? = null
    var wakeAt: ((Long) -> Unit)? = null
    var notificationsAllowed by mutableStateOf(true) // re-checked on resume
    var openNotificationSettings: () -> Unit = {}
    val alertsOn get() = alertsEnabled(settings) // read on every composition: the switch is on another tab
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
    var weather by mutableStateOf<Weather?>(null) // AccuWeather from the cloud; a TS7 reading wins for the temperature
        private set
    val outside get() = home?.outdoorTemp ?: weather?.temp
    private val weatherLock = Mutex()
    private var weatherAt: TimeMark? = null
    private val pending = mutableMapOf<String, Job>() // debounced writes per room id
    private var allRevision = 0
    private val session = noticeSession(settings)
    private fun sessionActive() = savedAuthKey(settings) == key && noticeSession(settings) == session
    /** The background check's state, for the window and not-warming notices; null when alerts are off or the
     *  last check is older than two of its runs (the phone may have kept the worker from running). */
    var watched by mutableStateOf<WatchState?>(null)
        private set
    var warmAbove by mutableStateOf(warmSetting(settings)) // re-read on every load, so a changed setting shows on return
        private set
    /** Room notice keys shown last time, so they clear half a degree past where they appeared (no flapping). */
    var shownIssues by mutableStateOf(emptySet<String>())
    /** Notices the user closed (Notices.kt); forgotten once their condition is gone. */
    var closed by mutableStateOf(decodeSet(settings.getString("notices.closed", "")))
        private set

    /** Rejected or failed temperature changes per room id (Notices.kt); the widget adds them too. */
    var writeNotices by mutableStateOf(loadWriteNotices(settings))
        private set
    private fun writeNotice(roomId: String, text: String?) {
        if (!sessionActive()) return
        saveWriteNotice(settings, roomId, text)
        writeNotices = loadWriteNotices(settings)
    }

    var operationNotices by mutableStateOf(loadOperationNotices(settings))
        private set

    private fun operationNotice(notice: OperationNotice, text: String?) {
        if (!sessionActive()) return
        saveOperationNotice(settings, notice, text)
        operationNotices = loadOperationNotices(settings)
    }

    /** The controller's firmware changed (from, to), until closed here. */
    val firmwareChange get() = firmwareRevision.let { firmwareChange(settings) }

    fun close(key: String) = when {
        key == "firmware" -> settings.closeFirmwareChange()
        key.startsWith("write-") -> writeNotice(key.removePrefix("write-"), null)
        key.startsWith("operation-") -> operationNotice(OperationNotice.valueOf(key.removePrefix("operation-")), null)
        else -> saveClosed(closed + key)
    }
    fun forgetClosed(active: Set<String>) = (closed intersect active).let { if (it != closed) saveClosed(it) }
    private fun saveClosed(keys: Set<String>) {
        closed = keys
        settings.putString("notices.closed", keys.joinToString(","))
    }

    suspend fun load(readBackRoom: String? = null): Home? {
        if (!sessionActive()) return null
        // Also on failure: a phone offline for hours mustn't keep showing an old window warning.
        watched = if (alertsEnabled(settings)) loadWatch(settings).takeIf { nowSeconds() - it.lastAt <= WINDOW_MAX_GAP } else null
        warmAbove = warmSetting(settings)
        writeNotices = loadWriteNotices(settings) // the widget may have added some
        operationNotices = loadOperationNotices(settings)
        val result = attempt { api.home(key) }
        coroutineContext.ensureActive()
        if (!sessionActive()) return null
        val fresh = result.getOrElse {
            if (it is AuthExpired) onLoggedOut()
            error = errorText(it)
            return null
        }
        // Don't let a refresh overwrite a value the user is still adjusting.
        val local = home?.rooms?.associateBy { it.id }.orEmpty()
        home = fresh.copy(rooms = fresh.rooms.map { r ->
            if (r.id != readBackRoom && pending[r.id]?.isActive == true) r.copy(target = local[r.id]?.target) else r
        })
        error = null
        // Away and boost temperatures are temporary: don't make them what the switch restores.
        if (!away && !awayBusy) fresh.rooms.filter { r -> boosts.none { it.room == r.id } }
            .forEach { r -> r.target?.let { rememberTarget(r.id, it) } }
        return fresh
    }

    suspend fun refreshOnResume() {
        restoreExpiredBoosts()
        if (!awayBusy) away =
            awaySaved(settings) != null // goAway saves first, but shows away only once done
        pollRooms()
    }

    suspend fun pollRooms() {
        load()
        loadWeather(force = false)
    }

    private val restoreLock = Mutex()

    /** True when there is nothing left to retry: restored, or logged out. */
    private suspend fun restoreExpiredBoosts(): Boolean = restoreLock.withLock {
        val result = attempt { endExpiredBoosts(api, key, settings) }
        boosts = loadBoosts(settings)
        val expired = result.exceptionOrNull() is AuthExpired
        if (expired) onLoggedOut()
        result.isSuccess || expired
    }

    suspend fun watchBoostExpiry() {
        snapshotFlow { boosts.minOfOrNull { it.until } }.collectLatest { until ->
            if (until == null) return@collectLatest
            delay((until - nowSeconds()).coerceAtLeast(0).seconds)
            // Retry a few times, then wait for the next resume or worker run
            var tries = 0
            while (!restoreExpiredBoosts() && ++tries < 5) delay(1.minutes)
            refreshRooms()
        }
    }

    suspend fun syncHistoryWhileStarted() {
        // Give the initial screen loads time to finish on the slow controller.
        delay(30.seconds)
        while (true) {
            val result = attempt { syncHistory(api, history, key, settings) }
            delay(if (result.isSuccess) 6.hours else 1.hours)
        }
    }

    // Share the weather limit across polling and resume, and discard an expired reading before retrying.
    private suspend fun loadWeather(force: Boolean) = weatherLock.withLock {
        val recent = weatherAt?.elapsedNow()?.let { it < 15.minutes } == true
        if (!force && recent) return@withLock
        if (!recent) weather = null
        weather = attempt { api.weather(key) }
            .onFailure { log("weather failed", it) }
            .getOrNull()
        weatherAt = weatherClock.markNow()
    }

    fun retryAway(updateAc: suspend () -> Set<String>) =
        goAway(settings.getDouble("away.temp", awayTemp), updateAc)

    /** Lowers every room that is on to [target] and remembers what each one had. */
    fun goAway(target: Double = awayTemp, updateAc: suspend () -> Set<String>) = scope.launch {
        if (awayBusy || !sessionActive()) return@launch
        awayBusy = true
        val h = home ?: load()
        if (h == null) {
            awayBusy = false
            return@launch
        }
        operationNotice(OperationNotice.AwayFailed, null)
        settings.putDouble("away.temp", target)
        val boosted = boosts.associateBy { it.room }
        val saved = h.rooms.associate {
            it.id to (boosted[it.id]?.restore ?: it.target)
        } // a boost isn't "theirs"
        saveAway(settings, saved) // first, so a crash mid-way can still come back
        boosted.keys.forEach { removeBoost(settings, it) }; boosts = loadBoosts(settings)
        val heated = h.rooms.filter { saved[it.id] != null } // rooms that are off stay off
        val lowered = mutableListOf<Room>()
        try {
            heated.forEach { api.setTemp(key, it.id, target); lowered += it }
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
            if (sessionActive()) saveAway(settings, null)
            operationNotice(OperationNotice.AwayFailed, errorText(e))
            load()
            awayBusy = false
            return@launch
        }
        operationNotice(OperationNotice.AcOn, null)
        reportAwayAc(updateAc(), h, goingAway = true)
        delay(4000) // read back, like single rooms
        val fresh = load()
        if (!sessionActive()) return@launch
        val kept = fresh?.rooms.orEmpty()
            .filter { r -> heated.any { it.id == r.id } && r.target != target }
        if (kept.isNotEmpty()) {
            operationNotice(OperationNotice.AwayKept, getString(Res.string.away_kept, kept.joinToString { it.name }))
        }
        awayTemp = target
        away = true; awayBusy = false
        if (fresh != null && kept.isEmpty()) snackbar.showSnackbar(getString(Res.string.away_set, fmt1(target)), duration = SnackbarDuration.Long)
    }

    /** "I'm back": puts every room back to what it had before [goAway]. */
    fun comeBack(updateAc: suspend () -> Set<String>) = scope.launch {
        val saved = awaySaved(settings) ?: return@launch
        if (awayBusy || !sessionActive()) return@launch
        awayBusy = true
        operationNotice(OperationNotice.BackFailed, null)
        try {
            saved.forEach { (id, t) -> if (t != null) api.setTemp(key, id, t) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            operationNotice(OperationNotice.BackFailed, errorText(e))
            load()
            awayBusy = false
            return@launch
        }
        reportAwayAc(updateAc(), home, goingAway = false)
        delay(4000) // read back, like goAway
        val fresh = load()
        if (!sessionActive()) return@launch
        val kept = fresh?.rooms.orEmpty().filter { r -> saved[r.id]?.let { it != r.target } == true }
        if (fresh == null || kept.isNotEmpty()) {
            val text = if (kept.isNotEmpty()) getString(Res.string.back_kept, kept.joinToString { it.name })
                else error ?: getString(Res.string.failed)
            operationNotice(OperationNotice.BackFailed, text)
            awayBusy = false
            return@launch
        }
        saveAway(settings, null)
        operationNotice(OperationNotice.AwayKept, null)
        operationNotice(OperationNotice.AcOff, null)
        away = false; awayBusy = false
        snackbar.showSnackbar(getString(Res.string.back_done))
    }

    private suspend fun reportAwayAc(failed: Set<String>, home: Home?, goingAway: Boolean) {
        if (failed.isEmpty()) return
        val names = home?.rooms.orEmpty().associate { it.id to it.name }
        val rooms = failed.joinToString { names[it] ?: it }
        val notice = if (goingAway) OperationNotice.AcOff else OperationNotice.AcOn
        val message = if (goingAway) Res.string.away_ac_off_failed else Res.string.back_ac_on_failed
        operationNotice(notice, getString(message, rooms))
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

    fun refreshRooms() {
        // Give the cloud a moment to apply the change, or the reload brings back the old state.
        scope.launch { delay(2.seconds); load() }
    }

    fun setTarget(room: Room, target: Double?): Job? {
        if (!sessionActive()) return null
        home =
            home?.let { h -> h.copy(rooms = h.rooms.map { if (it.id == room.id) it.copy(target = target) else it }) }
        if (!away && boosts.none { it.room == room.id }) target?.let { rememberTarget(room.id, it) }
        writeNotice(room.id, null) // a new try replaces what the last one left
        pending[room.id]?.cancel()
        val write = scope.launch {
            val job = coroutineContext.job
            try {
                delay(700) // wait for the user to stop tapping
                if (!sessionActive()) return@launch
                val result = attempt { api.setTemp(key, room.id, target) }
                // The server says OK even when the controller ignores the value, so read it back.
                if (result.isSuccess) delay(4000)
                val kept = load(readBackRoom = room.id)?.rooms?.find { it.id == room.id }
                val failure = result.exceptionOrNull()
                val text = when {
                    failure != null -> "${room.name}: ${errorText(failure)}"
                    kept != null && kept.target != target -> keptMessage(kept)
                    else -> return@launch
                }
                if (pending[room.id] == job && sessionActive()) {
                    writeNotice(room.id, text)
                    if (!visible()) notify?.invoke(text)
                }
            } finally {
                if (pending[room.id] == job) pending.remove(room.id)
            }
        }
        pending[room.id] = write
        return write
    }

    fun setAll(t: Double) = scope.launch {
        if (!sessionActive()) return@launch
        val revision = ++allRevision
        val previousWarnings = loadWriteNotices(settings)
        operationNotice(OperationNotice.AllFailed, null)
        settings.putDouble("allTarget", t)
        val result = attempt { api.setAll(key, t) }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            val text = errorText(failure)
            if (revision == allRevision) operationNotice(OperationNotice.AllFailed, text)
        } else delay(4000) // give the controller time to apply the bulk set before clearing warnings
        val fresh = load()
        if (!sessionActive() || revision != allRevision || failure != null) return@launch
        val warnings = loadWriteNotices(settings)
        fresh?.rooms.orEmpty().filter { r ->
            r.target == t && r.id !in pending && r.id in previousWarnings && previousWarnings[r.id] == warnings[r.id]
        }.forEach { saveWriteNotice(settings, it.id, null) }
        writeNotices = loadWriteNotices(settings)
        snackbar.showSnackbar(getString(Res.string.all_set, fmt1(t)))
    }
}

/** The AC card's "Turn heating off": a scheduled room can't be switched by hand, so explain why ([onLocked]). */
internal fun HomeViewModel.heatingOff(room: Room, onLocked: () -> Unit) {
    if (room.schedule) onLocked() else setTarget(room, null)
}
