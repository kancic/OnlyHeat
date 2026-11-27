package ancic.karim.onlyheat.ac

import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.str
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// A Gree+ / EWPE Smart account reaches a paired AC away from home: the same "pack" messages as on the LAN, relayed
// by Gree's cloud. Protocol: docs/gree-cloud-api-1.27.3.3.md. Only the uid and token are kept, never the password.

internal expect fun md5(data: ByteArray): ByteArray

/** A blocking byte stream to a cloud server; reads time out after a few seconds. Use on Dispatchers.IO. */
internal interface CloudSocket {
    fun write(bytes: ByteArray)
    /** The next byte, or -1 at the end of the stream. */
    fun read(): Int
    fun close()
}

/** TLS verifies the certificate and host name like HTTPS. Null where the platform has no sockets for it (iOS). */
internal expect fun openCloudSocket(host: String, port: Int, tls: Boolean): CloudSocket?

/** ponytail: iOS has no [openCloudSocket] yet (it'd need NWConnection); the account is hidden there. */
internal expect val cloudSockets: Boolean

private const val APP_ID = "4920681951525131286"
private const val APP_HASH = "0fa513124aa97781d1f3f40d61ca1a89"
private const val HTTP_KEY = "#G\$&^jgfujy6ujxt"
private const val GREE_CLOUD = "gree.cloud"

/** One AC on the account. [module]: its Wi-Fi module's MAC, which the cloud addresses. [ver]: the module's protocol. */
internal data class CloudAc(val mac: String, val module: String, val key: String, val ver: String?)

internal data class GreeAccount(val region: String, val user: String, val uid: JsonPrimitive, val token: String, val acs: List<CloudAc>)

class GreeCloudError(message: String) : Exception(message)

@OptIn(ExperimentalStdlibApi::class)
private fun md5Hex(s: String) = md5(s.encodeToByteArray()).toHexString()

internal fun loginHash(password: String, t: String) = md5Hex(md5Hex(md5Hex(password) + password) + t)

/** The account API's request time: UTC "yyyy-MM-dd HH:mm:ss". */
internal fun apiTime(epochSeconds: Long): String {
    val d = kotlin.time.Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${d.year}-${two(d.month.number)}-${two(d.day)} ${two(d.hour)}:${two(d.minute)}:${two(d.second)}"
}

/** The request body before encryption: [fields] plus the "api" block and the "datVc" digest over [signed]. */
internal fun accountBody(epochSeconds: Long, fields: (t: String) -> Map<String, JsonElement>, signed: List<String>): JsonObject {
    val t = apiTime(epochSeconds)
    val f = fields(t)
    fun text(e: JsonElement) = if (e is JsonArray) e.joinToString("_") { it.jsonPrimitive.content } else e.jsonPrimitive.content
    return buildJsonObject {
        putJsonObject("api") {
            put("appId", APP_ID); put("r", epochSeconds); put("t", t)
            put("vc", md5Hex("${APP_ID}_${APP_HASH}_${t}_$epochSeconds"))
        }
        f.forEach { (k, v) -> put(k, v) }
        put("datVc", md5Hex(APP_HASH + "_" + signed.joinToString("_") { text(f.getValue(it)) }))
    }
}

private suspend fun accountPost(region: String, path: String, signed: List<String>, fields: (t: String) -> Map<String, JsonElement>): JsonObject {
    val body = accountBody(Clock.System.now().epochSeconds, fields, signed)
    val reply = HttpClient().use { http ->
        http.post("https://${region}grih.gree.com$path") {
            header("Gaen1", "5ac2bdf935bcca70")
            header("Charset", "utf-8")
            setBody(TextContent(greeEncrypt(body.toString(), HTTP_KEY), ContentType.Application.FormUrlEncoded))
        }.bodyAsText()
    }
    val pack = Json.parseToJsonElement(reply).jsonObject.str("enRes") ?: throw GreeCloudError("$path: no reply")
    val r = Json.parseToJsonElement(greeDecrypt(pack, HTTP_KEY)).jsonObject
    val code = (r["r"] as? JsonPrimitive)?.intOrNull
    if (code != null && code != 200) throw GreeCloudError("$path: $code ${r.str("msg").orEmpty()}")
    return r
}

/** Signs in and keeps the account's ACs. ponytail: Europe only (eugrih); add a region picker for accounts elsewhere. */
internal suspend fun greeSignIn(s: Settings, user: String, password: String, region: String = "eu") {
    val login = accountPost(region, "/App/UserLoginV2", listOf("user", "psw", "t")) { t ->
        mapOf("user" to JsonPrimitive(user), "psw" to JsonPrimitive(loginHash(password, t)), "t" to JsonPrimitive(t))
    }
    val data = if ("uid" in login) login else login["data"] as? JsonObject ?: login
    val uid = data["uid"] as? JsonPrimitive ?: throw GreeCloudError("login: no uid")
    val token = data.str("token") ?: throw GreeCloudError("login: no token")
    val auth = mapOf("token" to JsonPrimitive(token), "uid" to uid)
    val homes = accountPost(region, "/App/GetHomes", listOf("token", "uid")) { auth }["home"] as? JsonArray ?: JsonArray(emptyList())
    val acs = homes.flatMap { home ->
        val homeId = home.jsonObject["id"] as? JsonPrimitive ?: return@flatMap emptyList()
        val rooms = accountPost(region, "/App/GetDevsInRoomsOfHomeV2", listOf("token", "uid", "homeId")) { auth + ("homeId" to homeId) }["rooms"]
        (rooms as? JsonArray).orEmpty().flatMap { (it.jsonObject["devs"] as? JsonArray).orEmpty() }.mapNotNull { d ->
            val dev = d as? JsonObject ?: return@mapNotNull null
            val mac = dev.str("mac") ?: return@mapNotNull null
            CloudAc(mac, dev.str("pmac") ?: mac, dev.str("key") ?: return@mapNotNull null, dev.str("ver"))
        }
    }
    log("Gree account: ${acs.size} ACs, modules " + acs.joinToString { it.ver ?: "?" })
    s.putString(GREE_CLOUD, buildJsonObject {
        put("region", region); put("user", user); put("uid", uid); put("token", token)
        putJsonArray("acs") { acs.forEach { add(buildJsonObject { put("mac", it.mac); put("module", it.module); put("key", it.key); put("ver", it.ver) }) } }
    }.toString())
}

internal fun greeSignOut(s: Settings) = s.remove(GREE_CLOUD)

internal fun greeAccount(s: Settings): GreeAccount? = runCatching {
    val o = Json.parseToJsonElement(s.getStringOrNull(GREE_CLOUD) ?: return null).jsonObject
    GreeAccount(
        o.str("region")!!, o.str("user")!!, o["uid"]!!.jsonPrimitive, o.str("token")!!,
        o["acs"]!!.jsonArray.map { a -> a.jsonObject.let { CloudAc(it.str("mac")!!, it.str("module")!!, it.str("key")!!, it.str("ver")) } },
    )
}.getOrNull()

internal fun GreeAccount.ac(mac: String) = acs.firstOrNull { it.mac.equals(mac, ignoreCase = true) }

/** Modules before protocol 3.2 talk through a per-device gateway; newer ones and unknown versions through MQTT. */
internal fun usesMqtt(ver: String?): Boolean {
    val (major, minor) = Regex("""(\d+)\.(\d+)""").find(ver?.substringAfterLast('V') ?: return true)?.destructured ?: return true
    return major.toInt() > 3 || (major.toInt() == 3 && minor.toInt() >= 2)
}

/** [inner] sent to the AC [mac] through the cloud; its decrypted reply ("dat" for a status, "res" for a command). */
internal suspend fun greeCloudRequest(s: Settings, mac: String, inner: JsonObject): JsonObject {
    val account = greeAccount(s) ?: throw AcUnreachable()
    val ac = account.ac(mac) ?: throw AcUnreachable()
    val want = if (inner.str("t") == "status") "dat" else "res"
    val pack = greeEncrypt(inner.toString(), ac.key)
    fun envelope(cid: String) = buildJsonObject {
        put("t", "pack"); put("i", 0); put("uid", account.uid); put("cid", cid); put("tcid", ac.mac); put("pack", pack)
    }.toString()
    fun open(reply: String?) = reply?.let { runCatching { Json.parseToJsonElement(it).jsonObject.str("pack") }.getOrNull() }
        ?.let { runCatching { Json.parseToJsonElement(greeDecrypt(it, ac.key)).jsonObject }.getOrNull() }
        ?.takeIf { it.str("t") == want && (it.str("mac") ?: ac.mac).equals(ac.mac, ignoreCase = true) }
    val mqtt = suspend { withContext(Dispatchers.IO) { mqttExchange(s, account, ac, envelope(Random.nextLong(1_000_000_000, 10_000_000_000).toString()), ::open) } }
    val gateway = suspend { gatewayServer(account, ac)?.let { (host, port, tls) -> open(withContext(Dispatchers.IO) { lineExchange(host, port, tls, envelope("app")) }) } }
    // The reference client tries the other way too when the first doesn't answer.
    val ways = if (usesMqtt(ac.ver)) listOf("MQTT" to mqtt, "gateway" to gateway) else listOf("gateway" to gateway, "MQTT" to mqtt)
    for ((name, way) in ways) {
        val reply = runCatching { way() }.onFailure { log("Gree cloud $name failed", it) }.getOrNull()
        if (reply != null) return reply
    }
    throw AcUnreachable()
}

private suspend fun gatewayServer(account: GreeAccount, ac: CloudAc): Triple<String, Int, Boolean>? {
    val online = accountPost(account.region, "/App/QueryOnline", listOf("token", "uid", "macs")) {
        mapOf("token" to JsonPrimitive(account.token), "uid" to account.uid, "macs" to JsonArray(listOf(JsonPrimitive(ac.mac))))
    }["online"] as? JsonArray ?: return null
    val entry = online.map { it.jsonObject }.firstOrNull { it.str("mac").equals(ac.mac, ignoreCase = true) } ?: return null
    val svr = entry.str("svr")?.takeIf { ':' in it } ?: return null
    val tls = (entry["isSSL"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull == 1) } ?: false
    return Triple(svr.substringBeforeLast(':'), svr.substringAfterLast(':').toInt(), tls)
}

/** One newline-terminated JSON line out, one back. */
private fun lineExchange(host: String, port: Int, tls: Boolean, message: String): String? {
    val socket = openCloudSocket(host, port, tls) ?: return null
    try {
        socket.write((message + "\n").encodeToByteArray())
        val line = ArrayList<Byte>()
        while (true) {
            val b = socket.read()
            if (b == -1 || b == '\n'.code) break
            line += b.toByte()
        }
        return line.toByteArray().decodeToString().ifBlank { null }
    } finally {
        socket.close()
    }
}

// ---------- MQTT 3.1.1: just enough for one request and its reply ----------

internal fun mqttLength(n: Int): ByteArray {
    val out = ArrayList<Byte>()
    var x = n
    do {
        var b = x % 128
        x /= 128
        if (x > 0) b = b or 0x80
        out += b.toByte()
    } while (x > 0)
    return out.toByteArray()
}

internal fun mqttString(s: String) = s.encodeToByteArray().let { byteArrayOf((it.size shr 8).toByte(), it.size.toByte()) + it }

internal fun mqttPacket(type: Int, body: ByteArray) = byteArrayOf(type.toByte()) + mqttLength(body.size) + body

internal fun mqttConnect(clientId: String, user: String, password: String) = mqttPacket(
    0x10,
    mqttString("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 60) + // level 4, user + password + clean session, keepalive 60 s
        mqttString(clientId) + mqttString(user) + mqttString(password),
)

/** A PUBLISH packet's topic and payload; [body] is everything after the fixed header. */
internal fun mqttPublished(header: Int, body: ByteArray): Pair<String, ByteArray> {
    val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
    val qos = (header shr 1) and 3
    val start = 2 + topicLength + (if (qos > 0) 2 else 0)
    return body.copyOfRange(2, 2 + topicLength).decodeToString() to body.copyOfRange(start, body.size)
}

private fun CloudSocket.readPacket(): Pair<Int, ByteArray> {
    val header = read().takeIf { it >= 0 } ?: throw AcUnreachable()
    var length = 0
    var shift = 0
    do {
        val b = read().takeIf { it >= 0 } ?: throw AcUnreachable()
        length = length or ((b and 0x7F) shl shift)
        shift += 7
    } while (b and 0x80 != 0)
    return header to ByteArray(length) { read().takeIf { b -> b >= 0 }?.toByte() ?: throw AcUnreachable() }
}

/** Publishes [message] for [ac]'s module and returns the first reply on its response topic that [accept]s, within 10 s. */
private fun <T> mqttExchange(s: Settings, account: GreeAccount, ac: CloudAc, message: String, accept: (String) -> T?): T? {
    val socket = openCloudSocket(if (account.region.isEmpty()) "mqtt.gree.com" else "mqtt-${account.region}.gree.com", 1984, tls = true) ?: return null
    try {
        socket.write(mqttConnect("app_" + Random.nextLong().toULong().toString(16).padStart(16, '0'), account.uid.content, account.token))
        val (type, ack) = socket.readPacket()
        if (type != 0x20 || ack.size < 2) throw AcUnreachable()
        if (ack[1].toInt() == 4 || ack[1].toInt() == 5) { // bad user name or password: the token ran out
            log("Gree cloud: token refused, signing out")
            greeSignOut(s)
            throw AcUnreachable()
        }
        if (ack[1].toInt() != 0) throw AcUnreachable()
        socket.write(mqttPacket(0x82, byteArrayOf(0, 1) + mqttString("response/${ac.module}/#") + byteArrayOf(1)))
        socket.write(mqttPacket(0x32, mqttString("request/${ac.module}") + byteArrayOf(0, 2) + message.encodeToByteArray()))
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (deadline.hasNotPassedNow()) {
            val (header, body) = socket.readPacket()
            if (header shr 4 != 3) continue // SUBACK, PUBACK
            if ((header shr 1) and 3 > 0) { // QoS 1: acknowledge it
                val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                socket.write(byteArrayOf(0x40, 2, body[2 + topicLength], body[3 + topicLength]))
            }
            val (topic, payload) = mqttPublished(header, body)
            if (topic.startsWith("response/")) accept(payload.decodeToString())?.let { return it }
        }
        return null
    } finally {
        runCatching { socket.write(byteArrayOf(0xE0.toByte(), 0)) } // DISCONNECT
        socket.close()
    }
}
