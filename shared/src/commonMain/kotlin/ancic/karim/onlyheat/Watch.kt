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
suspend fun endExpiredBoosts(api: HeatApi = HeatApi(), key: String? = savedAuthKey(), s: Settings = Settings()) {
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

/** Forgets away, boosts, alert history and remembered targets, so a logout doesn't leak them into the next account. */
internal fun clearAccountState(s: Settings) =
    s.keys.filter { k -> listOf("away.", "boost.", "watch.", "lastTarget.").any(k::startsWith) }.forEach(s::remove)

// ---------- Alerts ----------

/** What the last check saw, so each problem is reported once, not every 15 minutes. */
data class WatchState(
    val offline: Boolean = false,
    val cold: Set<String> = emptySet(),
    val window: Set<String> = emptySet(),
    val last: Map<String, Double> = emptyMap(), // room id -> temperature at [lastAt]
    val lastAt: Long = 0,
)

sealed interface Alert {
    data object Offline : Alert
    data object BackOnline : Alert
    data class Cold(val room: String, val temp: Double) : Alert
    data class Window(val room: String, val drop: Double) : Alert
}

const val WINDOW_DROP = 1.5 // °C between two checks while heating = probably an open window
private const val WINDOW_MAX_GAP = 40 * 60 // only compare readings this close together (seconds)

/**
 * Pure alert rules. [home] is null when the cloud couldn't reach the controller ([controllerOffline])
 * or when the phone itself is offline (then nothing changes). [coldBelow] null disables cold alerts.
 */
fun watch(prev: WatchState, home: Home?, controllerOffline: Boolean, coldBelow: Double?, now: Long): Pair<WatchState, List<Alert>> {
    if (home == null) {
        return if (controllerOffline && !prev.offline) prev.copy(offline = true) to listOf(Alert.Offline)
        else prev to emptyList()
    }
    val alerts = mutableListOf<Alert>()
    if (prev.offline) alerts += Alert.BackOnline

    val cold = mutableSetOf<String>()
    val window = mutableSetOf<String>()
    val recent = now - prev.lastAt <= WINDOW_MAX_GAP
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
    }
    val last = home.rooms.mapNotNull { r -> r.current?.let { r.id to it } }.toMap()
    return WatchState(false, cold, window, last, now) to alerts
}

internal fun loadWatch(s: Settings) = WatchState(
    offline = s.getBoolean("watch.offline", false),
    cold = decodeSet(s.getString("watch.cold", "")),
    window = decodeSet(s.getString("watch.window", "")),
    last = decodeMap(s.getString("watch.last", "")).mapValues { it.value.toDoubleOrNull() ?: 0.0 },
    lastAt = s.getLong("watch.lastAt", 0),
)

internal fun saveWatch(s: Settings, w: WatchState) {
    s.putBoolean("watch.offline", w.offline)
    s.putString("watch.cold", w.cold.joinToString(","))
    s.putString("watch.window", w.window.joinToString(","))
    s.putString("watch.last", encodeMap(w.last) { it.toString() })
    s.putLong("watch.lastAt", w.lastAt)
}

/** Below this a room triggers a cold alert; null = off. */
internal fun coldSetting(s: Settings) = s.getDouble("alerts.cold", 12.0)
internal fun coldLimit(s: Settings): Double? = coldSetting(s).takeIf { it > 0 }

fun alertsEnabled(s: Settings = Settings()) = s.getBoolean("alerts.on", false)

/** One background check: returns the texts to notify. Called by the Android worker every ~15 minutes. */
suspend fun checkAlerts(api: HeatApi = HeatApi(), s: Settings = Settings()): List<String> {
    val key = savedAuthKey() ?: return emptyList()
    val result = attempt { api.home(key) }
    val e = result.exceptionOrNull()
    // Same reading as the Connection check: an error answer from the cloud = it lost the controller;
    // no answer at all = this phone is offline, which says nothing about the house.
    val (home, offline) = result.getOrNull() to (e is ApiException && e !is AuthExpired)
    val (next, alerts) = watch(loadWatch(s), home, offline, coldLimit(s), nowSeconds())
    saveWatch(s, next)
    return alerts.map { alertText(it) }
}

private suspend fun alertText(a: Alert) = when (a) {
    Alert.Offline -> getString(Res.string.alert_offline)
    Alert.BackOnline -> getString(Res.string.alert_back_online)
    is Alert.Cold -> getString(Res.string.alert_cold, a.room, fmt1(a.temp))
    is Alert.Window -> getString(Res.string.alert_window, a.room, fmt1(a.drop))
}
