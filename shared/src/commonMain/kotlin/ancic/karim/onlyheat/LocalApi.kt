package ancic.karim.onlyheat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// The controller's own web dashboard (PHP on a Raspberry Pi), reachable only from its Wi-Fi.
// There is no JSON read endpoint for the schedule, so it is parsed from the /scheduler page.

/** [day] is "0" for every day, otherwise "Mon".."Sun". Times are "HH:mm". */
data class ScheduleEntry(val roomId: String, val day: String, val start: String, val end: String, val target: Double)

data class Schedule(val rooms: List<Pair<String, String>>, val entries: List<ScheduleEntry>) // rooms: id to name

/** The controller did not answer: the phone is most likely not on its Wi-Fi. */
class ControllerUnreachable(val host: String, cause: Throwable?) : Exception(cause)

class WrongDashboardPin : Exception()

class LocalApi(private val host: String, private val pin: String) : AutoCloseable {
    private val http = HttpClient {
        install(HttpCookies) // PHP session + the "ttodevice" cookie
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 4_000 }
        logHttp()
    }
    private val base = "http://$host"

    override fun close() = http.close()

    private suspend fun <T> reach(block: suspend () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException || e is WrongDashboardPin || e is ApiException) throw e
        log("controller $host unreachable", e)
        throw ControllerUnreachable(host, e)
    }

    private suspend fun login() {
        log("logging in to controller dashboard at $host")
        http.get("$base/dashboard") // sets the device cookie the login expects
        val reply = http.submitForm("$base/include/ajax/login-check.php", parameters {
            pin.forEachIndexed { i, c -> append("loginInput${i + 1}", c.toString()) }
        }).bodyAsText()
        if (Json.parseToJsonElement(reply).jsonObject.str("type") != "success") throw WrongDashboardPin()
    }

    suspend fun schedule(): Schedule = reach {
        var page = http.get("$base/scheduler").bodyAsText()
        if ("formSchedule" !in page) { log("no schedule form, session expired?"); login(); page = http.get("$base/scheduler").bodyAsText() }
        parseSchedule(page)
    }

    internal suspend fun sendReboot() {
        reach { login() } // proves we can reach it and are allowed to, before firing
        try {
            http.submitForm("$base/include/ajax/device-restart.php", parameters { })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The controller may go down before it answers; the status polling decides.
            log("reboot request got no answer (expected if the controller went down first)", e)
        }
    }

    internal suspend fun isUp(): Boolean = try {
        http.submitForm("$base/include/ajax/device-status.php", parameters { }).status.isSuccess()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        log("controller status check failed: $e")
        false
    }

    /** Replaces the whole schedule, exactly like the dashboard's Save button. */
    suspend fun save(entries: List<ScheduleEntry>) = reach {
        suspend fun post() = http.submitForm("$base/include/ajax/save-schedule.php", parameters {
            // An empty schedule is sent the way the web form sends it: one blank row with room 0.
            val rows = entries.ifEmpty { listOf(ScheduleEntry("0", "0", "", "", 0.0)) }
            rows.forEach {
                append("entity_id[]", it.roomId)
                append("scheduleDay[]", it.day)
                append("scheduleStart[]", it.start)
                append("scheduleEnd[]", it.end)
                append("scheduleTarget[]", if (it.roomId == "0") "" else fmt(it.target))
            }
        }).bodyAsText()

        var reply = post()
        if (!reply.trimStart().startsWith("{")) { log("save got a non-JSON reply, logging in again"); login(); reply = post() } // session expired → login page
        val o = Json.parseToJsonElement(reply).jsonObject
        if (o.str("type") != "success") throw ApiException(o.str("message") ?: "Save failed")
    }
}

/**
 * Reboots the controller like the dashboard's Setup → Device reboot; true once it is back.
 * The controller's Wi-Fi drops during the reboot and phones often hop to another network, so
 * [cloudUp] is checked too: the cloud first losing the controller and then reaching it again
 * also counts as "back" (requiring the loss first ignores a stale answer from before the reboot).
 */
suspend fun LocalApi.rebootAndWait(cloudUp: suspend () -> Boolean): Boolean {
    log("rebooting controller")
    sendReboot()
    delay(10_000) // the dashboard waits this long before polling too
    val deadline = TimeSource.Monotonic.markNow() + 4.minutes
    var cloudLostIt = false
    var localLostIt = false // like the cloud: an answer only counts after it was seen down (it may not be yet)
    while (deadline.hasNotPassedNow()) {
        if (!isUp()) localLostIt = true
        else if (localLostIt) { log("controller is back (local)"); return true }
        if (!cloudUp()) { if (!cloudLostIt) log("cloud lost the controller"); cloudLostIt = true }
        else if (cloudLostIt) { log("controller is back (cloud)"); return true }
        delay(2_000)
    }
    log("controller did not come back within 4 minutes")
    return false
}

/** Does the dashboard at [host] answer at all? No login needed: the login page counts. */
suspend fun controllerAnswers(host: String): Boolean {
    val http = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 6_000; connectTimeoutMillis = 4_000 }; logHttp() }
    return try {
        http.get("http://$host/dashboard").status.isSuccess()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    } finally {
        http.close()
    }
}

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
