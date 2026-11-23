package ancic.karim.onlyheat

import ancic.karim.onlyheat.res.*
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock

// Away mode, room boosts and background alerts. State lives in Settings so the app, the widget and the
// Android background worker all see the same thing.

internal fun nowSeconds() = Clock.System.now().epochSeconds

private fun Double?.enc() = this?.let(::fmt) ?: "off"
private fun String.dec(): Double? = if (this == "off") null else toDoubleOrNull()

// Settings only hold strings: maps as "k=v,k=v", sets as "a,b".
private fun <V> encodeMap(m: Map<String, V>, enc: (V) -> String) = m.entries.joinToString(",") { "${it.key}=${enc(it.value)}" }
private fun decodeMap(v: String) = v.split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
private fun decodeSet(v: String) = v.split(',').filter { it.isNotEmpty() }.toSet()

// ---------- Away ----------

/** Targets to restore on "I'm back", or null when not away. */
internal fun awaySaved(s: Settings): Map<String, Double?>? = s.getStringOrNull("away.saved")?.let { v ->
    decodeMap(v).mapValues { it.value.dec() }
}

internal fun saveAway(s: Settings, saved: Map<String, Double?>?) {
    if (saved == null) s.remove("away.saved")
    else s.putString("away.saved", encodeMap(saved) { it.enc() })
}

// ---------- Boost ----------

/** [room] runs at [target] until [until] (epoch seconds), then goes back to [restore] (null = off). */
data class Boost(val room: String, val until: Long, val target: Double, val restore: Double?)

internal fun loadBoosts(s: Settings): List<Boost> = s.keys.filter { it.startsWith("boost.") }.mapNotNull { k ->
    val p = s.getStringOrNull(k)?.split('|') ?: return@mapNotNull null
    if (p.size != 3) return@mapNotNull null
    Boost(k.removePrefix("boost."), p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].dec())
}

internal fun putBoost(s: Settings, b: Boost) = s.putString("boost.${b.room}", "${b.until}|${fmt(b.target)}|${b.restore.enc()}")
internal fun removeBoost(s: Settings, room: String) = s.remove("boost.$room")

/**
 * Ends boosts whose time is up: restores the old target, unless the user changed the target meanwhile.
 * Safe to call from anywhere (app poll, Android worker). Throws if the cloud can't be reached, so callers retry.
 */
suspend fun endExpiredBoosts(api: HeatApi, key: String?, s: Settings) {
    key ?: return
    val due = loadBoosts(s).filter { it.until <= nowSeconds() }
    if (due.isEmpty()) return
    val rooms = api.home(key).rooms.associateBy { it.id }
    // The app may extend or stop a boost while this runs: only touch the one that was loaded.
    fun unchanged(b: Boost) = loadBoosts(s).find { it.room == b.room } == b
    for (b in due) {
        if (!unchanged(b)) continue
        if (rooms[b.room]?.target == b.target) api.setTemp(key, b.room, b.restore)
        if (unchanged(b)) removeBoost(s, b.room)
    }
}

/** Clear controller state before allowing the next login. Ignore logout callbacks from an old session. */
internal fun clearSession(s: Settings, key: String): Boolean {
    if (savedAuthKey(s) != key) return false
    s.remove(SAVED_LAN_TARGET)
    savePin(s, null) // the dashboard PIN belongs to the controller just logged out of
    s.remove("allTarget")
    s.remove(controllerSerialSetting(key))
    s.keys.filter { k -> listOf("away.", "boost.", "watch.", "lastTarget.").any(k::startsWith) }.forEach(s::remove)
    s.remove("authKey")
    return true
}

// ---------- Alerts ----------

/** What the last check saw, so each problem is reported once, not every check. */
data class WatchState(
    val offline: Boolean = false,
    val cold: Set<String> = emptySet(),
    val window: Set<String> = emptySet(),
    val stuck: Set<String> = emptySet(), // wants heat but hasn't warmed up
    val hot: Set<String> = emptySet(),
    val warm: Set<String> = emptySet(), // too warm for comfort on a hot day (AC tip)
    val warmFrom: Map<String, Pair<Double, Long>> = emptyMap(), // room id -> low reading and start of the no-rise timer
    val last: Map<String, Double> = emptyMap(), // room id -> temperature at [lastAt]
    val lastAt: Long = 0,
)

sealed interface Alert {
    data object Offline : Alert
    data object BackOnline : Alert
    data class Cold(val room: String, val temp: Double) : Alert
    data class Window(val room: String, val drop: Double) : Alert
    data class NotWarming(val room: String, val temp: Double, val target: Double) : Alert
    data class TooHot(val room: String, val temp: Double, val target: Double) : Alert
    data class UseAc(val rooms: List<String>, val outside: Double) : Alert
}

const val WINDOW_DROP = 1.5 // °C between two checks while heating = probably an open window
private const val WINDOW_MAX_GAP = 40 * 60 // only compare readings this close together (seconds)
// ponytail: guesses for slow (floor) heating; tune if real radiators flag too late or floors too early.
const val NOT_WARMING_AFTER = 2 * 3600 // seconds below target without warming up before we say so
const val MIN_RISE = 0.3 // °C that counts as "warming up"
const val HOT_MARGIN = 2.0 // °C above target while the valve is still open = probably stuck open
const val AC_ABOVE = 26.0 // default for the "Too warm above" setting

private fun Room.isAcCandidate(warm: Set<String>, warmAbove: Double): Boolean {
    val limit = if (id in warm) warmAbove - 0.5 else warmAbove
    return !heating && current != null && current >= limit
}

/**
 * Pure alert rules. [home] is null when the cloud couldn't reach the controller ([controllerOffline])
 * or when the phone itself is offline (then nothing changes). [coldBelow] null disables cold alerts.
 */
fun watch(prev: WatchState, home: Home?, controllerOffline: Boolean, coldBelow: Double?, now: Long, warmAbove: Double = AC_ABOVE): Pair<WatchState, List<Alert>> {
    if (home == null) {
        return if (controllerOffline && !prev.offline) prev.copy(offline = true) to listOf(Alert.Offline)
        else prev to emptyList()
    }
    val alerts = mutableListOf<Alert>()
    if (prev.offline) alerts += Alert.BackOnline

    val cold = mutableSetOf<String>()
    val window = mutableSetOf<String>()
    val stuck = mutableSetOf<String>()
    val hot = mutableSetOf<String>()
    val warm = mutableSetOf<String>()
    val warmFrom = mutableMapOf<String, Pair<Double, Long>>()
    val recent = now - prev.lastAt <= WINDOW_MAX_GAP
    val outside = home.outdoorTemp
    for (r in home.rooms) {
        val t = r.current ?: continue
        // Cold: report once, clear again half a degree above the limit (no flapping around the threshold).
        if (coldBelow != null && (t < coldBelow || (r.id in prev.cold && t < coldBelow + 0.5))) {
            cold += r.id
            if (r.id !in prev.cold) alerts += Alert.Cold(r.name, t)
        }
        // Window: a sharp drop while the room should be warm. Stays flagged until it stops falling.
        val before = prev.last[r.id]
        val drop = if (recent && before != null) before - t else 0.0
        val heatingWanted = r.target != null && t < r.target
        if (heatingWanted && (drop >= WINDOW_DROP || (r.id in prev.window && drop > 0))) {
            window += r.id
            if (r.id !in prev.window) alerts += Alert.Window(r.name, drop)
        }
        // Too warm on a hot day: when it's at least as warm outside, an open window won't help, AC will.
        if (r.isAcCandidate(prev.warm, warmAbove) && (outside?.let { it >= t } ?: (r.id in prev.warm))) warm += r.id
        if (r.cooling) continue
        val target = r.target ?: continue
        // Not warming up: clearly below target and no rise of MIN_RISE for NOT_WARMING_AFTER. A gap in the
        // checks (phone off) restarts the clock, since we can't tell what happened in between.
        if (t < target - 0.5) {
            val from = prev.warmFrom[r.id]?.takeIf { recent && t < it.first + MIN_RISE } ?: (t to now)
            warmFrom[r.id] = minOf(t, from.first) to from.second
            if (now - from.second >= NOT_WARMING_AFTER) {
                stuck += r.id
                if (r.id !in prev.stuck) alerts += Alert.NotWarming(r.name, t, target)
            }
        }
        // Too hot: well above target and the valve still open. Without the valve check every lowered
        // target or sunny afternoon would trigger it. Clears half a degree lower (no flapping).
        if (r.heating && (t >= target + HOT_MARGIN || (r.id in prev.hot && t >= target + HOT_MARGIN - 0.5))) {
            hot += r.id
            if (r.id !in prev.hot) alerts += Alert.TooHot(r.name, t, target)
        }
    }
    // One notification for all rooms that got too warm, not one per room in a heatwave.
    val newlyWarm = home.rooms.filter { it.id in warm && it.id !in prev.warm }.map { it.name }
    if (outside != null && newlyWarm.isNotEmpty()) alerts += Alert.UseAc(newlyWarm, outside)
    val last = home.rooms.mapNotNull { r -> r.current?.let { r.id to it } }.toMap()
    return WatchState(false, cold, window, stuck, hot, warm, warmFrom, last, now) to alerts
}

internal fun loadWatch(s: Settings) = WatchState(
    offline = s.getBoolean("watch.offline", false),
    cold = decodeSet(s.getString("watch.cold", "")),
    window = decodeSet(s.getString("watch.window", "")),
    stuck = decodeSet(s.getString("watch.stuck", "")),
    hot = decodeSet(s.getString("watch.hot", "")),
    warm = decodeSet(s.getString("watch.warm", "")),
    warmFrom = decodeMap(s.getString("watch.warmFrom", "")).mapNotNull { (k, v) ->
        val t = v.substringBefore('@').toDoubleOrNull() ?: return@mapNotNull null
        k to (t to (v.substringAfter('@').toLongOrNull() ?: return@mapNotNull null))
    }.toMap(),
    last = decodeMap(s.getString("watch.last", "")).mapValues { it.value.toDoubleOrNull() ?: 0.0 },
    lastAt = s.getLong("watch.lastAt", 0),
)

internal fun saveWatch(s: Settings, w: WatchState) {
    s.putBoolean("watch.offline", w.offline)
    s.putString("watch.cold", w.cold.joinToString(","))
    s.putString("watch.window", w.window.joinToString(","))
    s.putString("watch.stuck", w.stuck.joinToString(","))
    s.putString("watch.hot", w.hot.joinToString(","))
    s.putString("watch.warm", w.warm.joinToString(","))
    s.putString("watch.warmFrom", encodeMap(w.warmFrom) { "${it.first}@${it.second}" })
    s.putString("watch.last", encodeMap(w.last) { it.toString() })
    s.putLong("watch.lastAt", w.lastAt)
}

/** Below this a room triggers a cold alert; null = off. */
internal fun coldSetting(s: Settings) = s.getDouble("alerts.cold", 12.0)
/** At or above this, on a day at least as warm outside, a room triggers the AC tip. */
internal fun warmSetting(s: Settings) = s.getDouble("alerts.warm", AC_ABOVE)
internal fun coldLimit(s: Settings): Double? = coldSetting(s).takeIf { it > 0 }
/** Below the cold-alert temperature right now: the room cards and details show frost. */
internal fun isCold(room: Room, s: Settings): Boolean = coldLimit(s).let { it != null && room.current != null && room.current < it }

fun alertsEnabled(s: Settings) = s.getBoolean("alerts.on", false)

/** One background check: returns the texts to notify. Called by the Android worker every ~20 minutes. */
suspend fun checkAlerts(api: HeatApi, s: Settings): List<String> {
    val key = savedAuthKey(s) ?: return emptyList()
    val result = attempt { api.home(key) }
    val e = result.exceptionOrNull()
    // Same reading as the Connection check: an error answer from the cloud = it lost the controller;
    // no answer at all = this phone is offline, which says nothing about the house.
    var home = result.getOrNull()
    val offline = e is ApiException && e !is AuthExpired
    val warmAbove = warmSetting(s)
    if (home != null && home.outdoorTemp == null) {
        val warm = loadWatch(s).warm
        if (home.rooms.any { it.isAcCandidate(warm, warmAbove) }) {
            val weather = attempt { api.weather(key) }.getOrNull()
            home = home.copy(outdoorTemp = weather?.temp)
        }
    }
    if (savedAuthKey(s) != key) return emptyList()
    val (next, alerts) = watch(loadWatch(s), home, offline, coldLimit(s), nowSeconds(), warmAbove)
    saveWatch(s, next)
    return alerts.map { alertText(it) }
}

private suspend fun alertText(a: Alert) = when (a) {
    Alert.Offline -> getString(Res.string.alert_offline)
    Alert.BackOnline -> getString(Res.string.alert_back_online)
    is Alert.Cold -> getString(Res.string.alert_cold, a.room, fmt1(a.temp))
    is Alert.Window -> getString(Res.string.alert_window, a.room, fmt1(a.drop))
    is Alert.NotWarming -> getString(Res.string.alert_not_warming, a.room, fmt1(a.temp), fmt1(a.target))
    is Alert.TooHot -> getString(Res.string.alert_too_hot, a.room, fmt1(a.temp), fmt1(a.target))
    is Alert.UseAc -> getString(Res.string.alert_use_ac, a.rooms.joinToString(", "), fmt1(a.outside))
}
