package ancic.karim.onlyheat

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.util.sha256
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64

// Reverse-engineered from TTO Advance 1.0.3 (com.thermotechnik.ttoadvanceapp).
// Cloud-only: every call is a multipart POST, every reply is {"status":"OK"|"ERROR","message":...}.
private const val BASE = "https://api.smart-heating.co/api-remote"

data class Room(
    val id: String,
    val name: String,
    val current: Double?,
    val target: Double?, // null = room switched off
    val humidity: Double?,
    val heating: Boolean, // valve open right now
    val smart: Boolean,
    val schedule: Boolean, // controller follows its own schedule and ignores manual setpoints
)

data class Home(
    val rooms: List<Room>, val outdoorTemp: Double?, val outdoorHum: Double?, val device: String?,
    val ip: String?, // the controller on its own Wi-Fi, e.g. 192.168.42.1
)

/** The server answered with status ERROR; [message] is the server's (English-only) text. */
open class ApiException(message: String) : Exception(message)

/** The saved auth key no longer works; the user has to log in again. */
class AuthExpired : ApiException(AUTH_EXPIRED)

private const val AUTH_EXPIRED = "Invalid authentication key" // exact server text (we always send lang=en)

// The key is missing (first) or no longer belongs to a device, e.g. after the API PIN was regenerated (second).
private val LOGGED_OUT = setOf(AUTH_EXPIRED, "No device found for requested authentication key")

internal fun apiError(message: String?) = if (message in LOGGED_OUT) AuthExpired() else ApiException(message ?: "Server error")

/** The saved session, or null when logged out. For callers outside the app UI, e.g. home-screen widgets. */
fun savedAuthKey(s: Settings): String? = s.getStringOrNull("authKey")

data class Pairing(val key: String, val serial: String?)

internal fun fingerprint(value: String) = Base64.UrlSafe.encode(sha256(value.encodeToByteArray()))

internal fun controllerSerialSetting(key: String) = "controllerSerial.${fingerprint(key)}"

internal fun savePairing(s: Settings, pairing: Pairing) {
    val setting = controllerSerialSetting(pairing.key)
    if (pairing.serial == null) s.remove(setting) else s.putString(setting, pairing.serial)
    s.putString("authKey", pairing.key)
}

/** No usable reply after retries (network down, timeouts, empty/garbled bodies). */
class ServerUnreachable(cause: Throwable?) : Exception(cause)

class HeatApi(private val http: HttpClient = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 15_000 }; logHttp() }) {

    // The server relays each call to the controller and sometimes replies with an empty/non-JSON
    // body (seen right after a write). All calls set absolute values, so retrying is safe.
    private suspend fun call(path: String, vararg params: Pair<String, String>): JsonObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            if (attempt > 0) delay(1500L * attempt)
            try {
                val body = http.submitFormWithBinaryData(BASE + path, formData {
                    params.forEach { (k, v) -> append(k, v) }
                    append("lang", "en")
                }).bodyAsText()
                val obj = Json.parseToJsonElement(body).jsonObject
                if (obj.str("status") != "OK") {
                    throw apiError(obj.str("message"))
                }
                return obj
            } catch (e: ApiException) {
                throw e // a real answer from the server, retrying won't change it
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("$path attempt ${attempt + 1}/3 failed", e)
                last = e
            }
        }
        log("$path gave up after 3 attempts")
        throw ServerUnreachable(last)
    }

    /** Exchanges the device PIN for an auth key. */
    suspend fun login(pin: String): Pairing {
        val reply = call("/getKey", "pin" to pin)
        return Pairing(reply.str("authKey") ?: throw ApiException("No auth key in reply"), reply.str("serial"))
    }

    suspend fun home(key: String): Home = parseHome(call("/getRooms", "authKey" to key, "detailed" to "false"))

    suspend fun setTemp(key: String, roomId: String, target: Double?) {
        call("/setRoomTemp", "authKey" to key, "roomID" to roomId, "tempTarget" to fmt(target))
    }

    suspend fun setAll(key: String, target: Double) {
        call("/setRoomTemp4All", "authKey" to key, "tempTarget" to fmt(target))
    }

    /** ~25 h of readings at 5-minute intervals, oldest first. */
    suspend fun roomLog(key: String, roomId: String): List<Point> =
        call("/getRoomLog", "authKey" to key, "roomID" to roomId).objects("singleLogs").map(::parsePoint)

    /** Hourly history plus the controller's prediction for the rest of today, per room. */
    suspend fun last24h(key: String): List<DaySummary> =
        call("/getSmartRoomLogsLast24h", "authKey" to key).objects("smartLogs").map(::parseDay)

    /** AccuWeather's current conditions for the controller's location (works since firmware 1.0.9.2.0). */
    suspend fun weather(key: String): Weather = parseWeather(call("/getSmartForecast", "authKey" to key))

    suspend fun smart(key: String, roomId: String): Smart {
        val o = call("/getRoomSmart", "authKey" to key, "roomID" to roomId)
        return Smart(o.bool("isSmart"), o.num("smartTarget")) // smartTarget is `false` when off
    }

    suspend fun setSmart(key: String, roomId: String, on: Boolean, target: Double) {
        val p = mutableListOf("authKey" to key, "roomID" to roomId, "isSmart" to if (on) "1" else "0")
        if (on) p += "smartTarget" to fmt(target) // the original app only sends it when enabling
        call("/setRoomSmart", *p.toTypedArray())
    }
}

data class Point(
    val time: Long, val current: Double?, val target: Double?,
    val outside: Double? = null, // only in the 24 h call: AccuWeather, or the TS7 sensor
)

data class DaySummary(
    val roomId: String, val name: String,
    val points: List<Point>,
    val forecast: List<Point> = emptyList(), // the controller's hourly prediction for the next hours
)

data class Smart(val on: Boolean, val target: Double?)

internal fun parsePoint(o: JsonObject) = Point(
    time = o.num("dateTime")?.toLong() ?: 0,
    current = o.num("currentTemp"),
    target = o.num("targetTemp"),
    outside = o.num("outsideTemp"),
)

internal fun parseDay(o: JsonObject): DaySummary {
    val (predicted, observed) = o.objects("logs").partition {
        "predictedCurrentTemp" in it || it.num("predictedOutsideTemp") != null
    }
    val points = observed.map(::parsePoint)
    // Flat on firmware 1.0.7.0.8; a real prediction since 1.0.9.2.0 (AccuWeather works again).
    val forecast = predicted.map {
        Point(it.num("dateTime")?.toLong() ?: 0, it.num("predictedCurrentTemp"), it.num("predictedTargetTemp"), it.num("predictedOutsideTemp"))
    }
    // ponytail: without AccuWeather or a TS7 (firmware 1.0.7.0.8) every outside value is 0; a real day of exactly
    // 0° all day would be hidden too.
    val noOutside = (points + forecast).all { it.outside == null || it.outside == 0.0 }
    fun List<Point>.clean() = if (noOutside) map { it.copy(outside = null) } else this
    return DaySummary(o.str("roomID") ?: "", o.str("roomName") ?: "Room", points.clean(), forecast.clean())
}

data class Weather(val temp: Double?, val emoji: String?)

// "daily" is Current Conditions; today, tomorrow and three more days follow. "dasboard" is the server's spelling.
internal fun parseWeather(o: JsonObject): Weather {
    val now = o.objects("dasboardForecast").find { it.str("id") == "daily" } ?: return Weather(null, null)
    val icon = Regex("""icons/(\d+)""").find(now.str("image").orEmpty())?.groupValues?.get(1)?.toInt()
    return Weather(now.num("temperature"), icon?.let(::weatherEmoji))
}

private fun JsonObject.objects(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

// Same as JS number -> string: 21.0 -> "21", 21.5 -> "21.5", off -> "".
internal fun fmt(t: Double?) = when {
    t == null -> ""
    t % 1.0 == 0.0 -> t.toLong().toString()
    else -> t.toString()
}

// Lenient on purpose: the server mixes strings, numbers, booleans and nulls for the same kinds of values.
internal fun parseHome(root: JsonObject): Home {
    val rooms = root.objects("rooms").map { r ->
        val id = r.str("id") ?: ""
        Room(
            id = id,
            name = r.str("title") ?: "Room $id",
            current = r.num("currentTemp"),
            target = r.num("targetTemp"),
            humidity = r.num("currentHum"),
            heating = r.bool("isWorking"),
            smart = r.bool("isSmart"),
            schedule = r.bool("isSchedule"),
        )
    }
    return Home(rooms, root.num("s7Temp"), root.num("s7Hum"), root.str("globalDeviceName"), root.str("globalIpAddress"))
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
private fun JsonObject.num(k: String) = str(k)?.toDoubleOrNull()
private fun JsonObject.bool(k: String) = str(k)?.lowercase() in setOf("1", "true")
