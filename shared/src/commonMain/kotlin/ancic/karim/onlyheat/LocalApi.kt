package ancic.karim.onlyheat

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// The controller's own web dashboard (PHP on a Raspberry Pi), reachable from its Wi-Fi or the home Wi-Fi it is on.
// There is no JSON read endpoint for the schedule, so it is parsed from the /scheduler page.

/** [day] is "0" for every day, otherwise "Mon".."Sun". Times are "HH:mm". */
data class ScheduleEntry(val roomId: String, val day: String, val start: String, val end: String, val target: Double)

data class Schedule(val rooms: List<Pair<String, String>>, val entries: List<ScheduleEntry>) // rooms: id to name

/** The controller did not answer: the phone is most likely on neither its Wi-Fi nor the home Wi-Fi. */
class ControllerUnreachable(cause: Throwable?) : Exception(cause)

class WrongDashboardPin : Exception()
class NoApiPin : Exception()

internal class VerifiedDashboard(val target: DashboardTarget, val dashboardPin: String, val apiPin: String)

internal fun Settings.saveLoginDashboard(submittedApiPin: String, pending: VerifiedDashboard?) {
    val verified = pending?.takeIf { it.apiPin == submittedApiPin }
    remove(SAVED_LAN_TARGET)
    verified?.let { rememberLanTarget(it.target, HOTSPOT) }
    savePin(this, verified?.dashboardPin)
}

class LocalApi(
    private val host: String,
    private val pin: String,
    private val settings: Settings,
    private val confirmedLanAddress: suspend () -> DashboardTarget?,
) {
    private var lastTarget: DashboardTarget? = null

    private suspend fun <T> withController(
        selected: DashboardTarget? = null,
        block: suspend HttpClient.(DashboardTarget) -> T,
    ): T {
        val (target, connection) = resolveDashboard(host, settings, confirmedLanAddress, lastTarget, selected)
            ?: throw ControllerUnreachable(null)

        return connection.httpClient {
            followRedirects = false
            install(HttpCookies) // PHP session + the "ttodevice" cookie
            // Firmware 1.0.9.2.0 answers AJAX only with this header (what jQuery sends); older firmware ignores it.
            install(DefaultRequest) { header("X-Requested-With", "XMLHttpRequest") }
            install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 4_000 }
            logHttp()
        }.use { http ->
            val name = reach(anyFailure = true) { http.login(target) } // a failed check() means it isn't the dashboard
            // Logged in, so the Wi-Fi name is readable on any firmware: keep it for the controller picker.
            name?.let { device -> http.wifiName(target.host)?.let { settings.rememberWifiName(device, it) } }
            if (selected == null) {
                lastTarget = target
                settings.rememberLanTarget(target, host)
            }
            reach { http.block(target) }
        }
    }

    /** Network failures become [ControllerUnreachable]; the controller's own answers pass through. */
    private inline fun <T> reach(anyFailure: Boolean = false, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException || e is WrongDashboardPin || e is ApiException) throw e
        if (anyFailure || e is IOException) throw ControllerUnreachable(e)
        throw e
    }

    /** Returns the controller's name from its login page. */
    private suspend fun HttpClient.login(target: DashboardTarget): String? {
        val base = "http://${target.host}"
        val page = get("$base/dashboard") // sets the device cookie the login expects
        check(page.status.isSuccess())
        val html = page.bodyAsText()
        val name = dashboardName(html)
        target.name?.let { check(name == it) }
        dashboardFirmware(html)?.let { settings.noteFirmware(it) }
        val response = submitForm("$base/include/ajax/login-check.php", parameters {
            pin.forEachIndexed { i, c -> append("loginInput${i + 1}", c.toString()) }
        })
        check(response.status.isSuccess())
        if (Json.parseToJsonElement(response.bodyAsText()).jsonObject.str("type") != "success") throw WrongDashboardPin()
        return name
    }

    private suspend fun HttpClient.readSchedule(base: String) = parseSchedule(get("$base/scheduler").bodyAsText())

    suspend fun schedule(): Schedule = withController { readSchedule("http://" + it.host) }

    /** AccuWeather's "Current Conditions" from the dashboard (firmware 1.0.9.2.0+). */
    suspend fun weather(): Weather = withController { parseWeather(get("http://${it.host}/dashboard").bodyAsText()) }

    /** Reads the selected controller's Setup page without saving credentials before cloud login. */
    internal suspend fun apiPin(selected: DashboardTarget): VerifiedDashboard = withController(selected) {
        val apiPin = parseApiPin(get("http://${it.host}/setup").bodyAsText()) ?: throw NoApiPin()
        VerifiedDashboard(it, pin, apiPin)
    }

    /** Replaces the whole schedule, then reads back what the controller stored (null if only the read-back failed). */
    suspend fun save(entries: List<ScheduleEntry>): Schedule? = withController { target ->
        val base = "http://" + target.host
        val response = submitForm("$base/include/ajax/save-schedule.php", parameters {
            // An empty schedule is sent the way the web form sends it: one blank row with room 0.
            val rows = entries.ifEmpty { listOf(ScheduleEntry("0", "0", "", "", 0.0)) }
            rows.forEach {
                append("entity_id[]", it.roomId)
                append("scheduleDay[]", it.day)
                append("scheduleStart[]", it.start)
                append("scheduleEnd[]", it.end)
                append("scheduleTarget[]", if (it.roomId == "0") "" else fmt(it.target))
            }
        })
        // A non-JSON answer (HTML error or login page) is a failed save too.
        val result = if (response.status.isSuccess()) attempt { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() else null
        // Clearing the schedule is answered {"type":"warning","message":"No data sent, schedule disabled."}.
        val cleared = entries.isEmpty() && result?.str("type") == "warning"
        if (result?.str("type") != "success" && !cleared) throw ApiException(result?.str("message") ?: "Save failed")
        // Stored. A failed read-back must not report the save as failed.
        attempt { readSchedule(base) }.onFailure { log("schedule read-back failed", it) }.getOrNull()
    }

    /** Sends one reboot. Either path can confirm recovery once it has observed a loss. */
    suspend fun rebootAndWait(cloudUp: suspend () -> Boolean): Boolean {
        val target = withController { target ->
            // The controller may go down before it answers.
            attempt { submitForm("http://${target.host}/include/ajax/device-restart.php", parameters { }) }
                .onFailure { log("reboot request got no answer", it) }
            target
        }
        delay(10_000) // the dashboard waits this long before polling too
        return dashboardProbeClient().use { probe ->
            var verification: Deferred<DashboardTarget?>? = null
            waitForRecovery(
                localUp = { probe.hasDashboard(target.host, target.name) },
                rediscovered = {
                    probe.hasDashboard(host, target.name) || if (target.name != null) {
                        probe.findLanDashboard(host, target.name) != null
                    } else {
                        // A matching LAN page only prompts for verification. It never confirms recovery.
                        if (verification == null && (probe.findLanDashboard(host) != null || target.host != host)) {
                            verification = async { confirmedLanAddress() }
                        }
                        val done = verification?.takeIf { it.isCompleted } ?: return@waitForRecovery false
                        // Dismissing the picker stops the wait instead of leaving it to time out.
                        val confirmed = done.await() ?: return@waitForRecovery null
                        probe.hasDashboard(confirmed.host, confirmed.name)
                    }
                },
                cloudUp = cloudUp,
            )
        }
    }
}

internal suspend fun waitForRecovery(
    localUp: suspend () -> Boolean,
    rediscovered: suspend CoroutineScope.() -> Boolean?, // null: the user gave up
    cloudUp: suspend () -> Boolean,
): Boolean = withTimeoutOrNull(4.minutes) {
    coroutineScope {
        val back = CompletableDeferred<Boolean>()
        val local = launch {
            // Several misses in a row: one slow answer while the Pi handles the restart isn't the reboot.
            var misses = 0
            while (misses < 3) { misses = if (localUp()) 0 else misses + 1; delay(2_000) }
            var found = rediscovered()
            while (found == false) { delay(5_000); found = rediscovered() }
            back.complete(found == true)
        }
        val cloud = launch {
            while (cloudUp()) delay(2_000)
            while (!cloudUp()) delay(2_000)
            back.complete(true)
        }
        back.await().also { local.cancel(); cloud.cancel() }
    }
} ?: false

data class Weather(val temp: Double?, val emoji: String?)

// The first "Temperature:" and icon are Current Conditions; forecast cards follow. Blank (old firmware) → nulls.
internal fun parseWeather(html: String) = Weather(
    Regex("""Temperature:</small>\s*<strong[^>]*>\s*(-?\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)?.toDoubleOrNull(),
    Regex("""accuweather\.com/\S*/icons/(\d+)\.svg""").find(html)?.groupValues?.get(1)?.toInt()?.let(::weatherEmoji),
)

// AccuWeather icon codes: developer.accuweather.com/weather-icons. 0 ("00.svg") means no data.
internal fun weatherEmoji(icon: Int): String? = when (icon) {
    1, 2, 30 -> "☀️"
    3, 4, 5 -> "🌤️"
    6 -> "🌥️"
    7, 8, 38 -> "☁️"
    11 -> "🌫️"
    12, 13, 14, 18, 39, 40 -> "🌧️"
    15, 16, 17, 41, 42 -> "⛈️"
    19, 20, 21, 22, 23, 43, 44 -> "🌨️"
    24, 25, 26, 29 -> "🧊"
    31 -> "🥶"
    32 -> "💨"
    33, 34 -> "🌙"
    35, 36, 37 -> "☁️"
    else -> null
}

internal fun parseApiPin(html: String): String? =
    Regex("""id="apiPin"\s*>\s*(\w+)\s*<""").find(html)?.groupValues?.get(1)

// ponytail: regex over the dashboard's server-rendered form; fine for this one fixed page,
// breaks if the controller firmware changes its markup (parseScheduleFromRealPage test catches that).
internal fun parseSchedule(html: String): Schedule {
    val start = html.indexOf("id=\"formSchedule\"")
    require(start >= 0) { "No schedule form on page" }
    val form = html.substring(start, html.indexOf("</form>", start).takeIf { it >= 0 } ?: html.length)

    fun selects(name: String) = Regex("""name="$name\[\]">(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(form).map { it.groupValues[1] }.toList()
    fun selected(options: String) = Regex("""<option value="([^"]*)"\s+selected>""").find(options)?.groupValues?.get(1)
    fun inputs(name: String) = Regex("""name="$name\[\]".*?value="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
        .findAll(form).map { it.groupValues[1] }.toList()

    val roomSelects = selects("entity_id")
    val rooms = roomSelects.firstOrNull().orEmpty().let { opts ->
        Regex("""<option value="(\d+)"[^>]*>([^<]*)""").findAll(opts)
            .map { it.groupValues[1] to it.groupValues[2].trim() }.filter { it.first != "0" }.toList()
    }
    val days = selects("scheduleDay")
    val starts = inputs("scheduleStart")
    val ends = inputs("scheduleEnd")
    val targets = inputs("scheduleTarget")

    val entries = roomSelects.indices.mapNotNull { i ->
        val room = selected(roomSelects[i]) ?: return@mapNotNull null
        val from = starts.getOrNull(i).orEmpty()
        if (room == "0" || from.isEmpty()) return@mapNotNull null // the hidden blank row
        ScheduleEntry(
            roomId = room,
            day = days.getOrNull(i)?.let(::selected) ?: "0",
            start = from,
            end = ends.getOrNull(i).orEmpty(),
            target = targets.getOrNull(i)?.toDoubleOrNull() ?: 21.0,
        )
    }
    return Schedule(rooms, entries)
}
