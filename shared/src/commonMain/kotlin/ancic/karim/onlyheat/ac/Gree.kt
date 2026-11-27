package ancic.karim.onlyheat.ac

import ancic.karim.onlyheat.data.log
import ancic.karim.onlyheat.data.str
import com.russhwolf.settings.Settings
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.core.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** AES-128-ECB without padding; [data] is a multiple of 16 bytes. */
internal expect fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray

private const val GENERIC_KEY = "a3K8Bx%2r8Y7#xDh"

@OptIn(ExperimentalEncodingApi::class)
internal fun greeEncrypt(json: String, key: String): String {
    val bytes = json.encodeToByteArray()
    val pad = 16 - bytes.size % 16 // PKCS#7
    return Base64.encode(aesEcb(key.encodeToByteArray(), bytes + ByteArray(pad) { pad.toByte() }, encrypt = true))
}

@OptIn(ExperimentalEncodingApi::class)
internal fun greeDecrypt(pack: String, key: String): String =
    aesEcb(key.encodeToByteArray(), Base64.decode(pack), encrypt = false).decodeToString().trimEnd { it < ' ' } // padding

private val selector by lazy { SelectorManager(Dispatchers.IO) }

/** Sends one datagram to each host on [port] and collects the replies that arrive within [wait]. */
private suspend fun udpExchange(
    hosts: List<String>, port: Int, message: String, wait: Duration, first: Boolean = false,
    isCurrent: () -> Boolean = { true },
): List<Pair<String, String>> = aSocket(selector).udp().bind().use { socket ->
    if (!isCurrent()) throw AcUnreachable()
    hosts.forEach { socket.send(Datagram(buildPacket { writeText(message) }, InetSocketAddress(it, port))) }
    val replies = mutableListOf<Pair<String, String>>()
    withTimeoutOrNull(wait) {
        for (d in socket.incoming) {
            // resolveAddress: the raw IP, never a reverse DNS lookup.
            val from = (d.address as? InetSocketAddress)?.resolveAddress()?.joinToString(".") { (it.toInt() and 0xFF).toString() }
            if (from != null) replies += from to d.packet.readText()
            if (first && replies.isNotEmpty()) break
        }
    }
    replies
}

/**
 * Gree Wi-Fi modules (Gree, Sinclair, … — what the EWPE Smart app talks to at home): JSON over UDP 7000, AES-128-ECB.
 * Scan and bind use a key every unit shares; binding returns the unit's own key.
 * ponytail: ECB only (module firmware before ~1.21). Newer modules use AES-GCM and just won't bind; add it when one shows up.
 */
internal object Gree : AcProtocol {
    override val id = "gree"
    private const val PORT = 7000
    private val modes = mapOf(AcMode.Auto to 0, AcMode.Cool to 1, AcMode.Dry to 2, AcMode.Fan to 3, AcMode.Heat to 4)
    private val fans = mapOf(AcFan.Auto to 0, AcFan.Low to 1, AcFan.Medium to 3, AcFan.High to 5) // Gree has 5 speeds

    private fun packFor(mac: String, pack: JsonObject, key: String) = buildJsonObject {
        put("cid", "app"); put("i", if (key == GENERIC_KEY) 1 else 0); put("t", "pack"); put("uid", 0); put("tcid", mac)
        put("pack", greeEncrypt(pack.toString(), key))
    }.toString()

    private fun unpack(reply: String, key: String): JsonObject? = runCatching {
        Json.parseToJsonElement(greeDecrypt(Json.parseToJsonElement(reply).jsonObject.str("pack")!!, key)).jsonObject
    }.getOrNull()

    // UDP: a request or its reply can get lost. Every message here is safe to repeat (commands set absolute values).
    private suspend fun request(
        host: String, mac: String, key: String, pack: JsonObject, isCurrent: () -> Boolean = { true },
    ): JsonObject {
        val message = packFor(mac, pack, key)
        repeat(2) {
            val reply = try {
                udpExchange(listOf(host), PORT, message, 2.seconds, first = true, isCurrent = isCurrent).firstOrNull()?.second
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw AcUnreachable(cause = e)
            }
            reply?.let { unpack(it, key) }?.let { return it }
        }
        throw AcUnreachable()
    }

    /** [ac] on the home network, or through the Gree+ account once [withAc] gave up on that. */
    private suspend fun request(ac: LinkedAc, pack: JsonObject, isCurrent: () -> Boolean = { true }): JsonObject =
        if (ac.viaCloud) greeCloudRequest(cloudSettings, ac.mac, pack, isCurrent) else request(ac.host, ac.mac, ac.secret, pack, isCurrent)

    private val cloudSettings by lazy { Settings() }

    override fun remote(ac: LinkedAc) = cloudSockets && greeAccount(cloudSettings)?.ac(ac.mac) != null

    private val logged = mutableSetOf<String>()

    // Asks each address directly: some units ignore broadcast scans.
    override suspend fun scan(hosts: List<String>): List<FoundAc> {
        val wifi = currentWifi()
        val replies = udpExchange(hosts, PORT, """{"t":"scan"}""", 3.seconds)
        val ap = wifi?.takeIf { it == currentWifi() }?.let { (ssid, bssid) -> hotspot(ssid, bssid) }
        return replies.mapNotNull { (host, reply) ->
            val dev = unpack(reply, GENERIC_KEY) ?: return@mapNotNull null
            val mac = dev.str("mac") ?: return@mapNotNull null
            if (logged.add(mac)) log("Gree scan reply from $host: $dev") // once per AC; no key in it; ver tells the module firmware
            discovered(host, mac, dev.str("name") ?: mac, ap)
        }
    }

    internal fun discovered(host: String, mac: String, name: String, connectedHotspot: FoundAc?): FoundAc =
        connectedHotspot?.takeIf { it.host == host && it.mac.equals(mac, ignoreCase = true) }
            ?: FoundAc(id, mac, host, name)

    /** A reset module (MODE + WiFi on the remote) runs its own Wi-Fi and sits at [AP_HOST] on it. */
    const val AP_HOST = "192.168.1.1"

    override suspend fun joinWifi(found: FoundAc, ssid: String, password: String) {
        if (!found.needsWifi || found.ssid == null || found.bssid == null) throw AcUnreachable()
        // The module may leave its hotspot before its reply goes out.
        val message = buildJsonObject { put("psw", password); put("ssid", ssid); put("t", "wlan") }.toString()
        val reply = exchangeOnWifi(found, AP_PASSWORD, PORT, message)
        if (reply != null && !wifiAccepted(reply)) throw AcUnreachable()
    }

    /** A module's own Wi-Fi is named after the end of its MAC (BSSID f4:91:1e:d4:54:bb: "1ed454bb"); its password is fixed. */
    private const val AP_PASSWORD = "12345678"

    override fun hotspot(ssid: String, bssid: String): FoundAc? {
        val mac = bssid.replace(":", "").lowercase()
        if (ssid.length != 8 || mac.length != 12 || !mac.endsWith(ssid.lowercase())) return null
        return FoundAc(id, mac, AP_HOST, ssid, ssid, needsWifi = true, bssid = bssid.lowercase())
    }

    /** The module answers {"t":"ret","r":200,…}; a reply without "r" counts as accepted. */
    internal fun wifiAccepted(reply: String) = runCatching {
        Json.parseToJsonElement(reply).jsonObject["r"]?.jsonPrimitive?.intOrNull?.let { it == 200 } ?: true
    }.getOrDefault(false)

    override suspend fun bind(found: FoundAc): LinkedAc {
        val r = request(found.host, found.mac, GENERIC_KEY, buildJsonObject { put("mac", found.mac); put("t", "bind"); put("uid", 0) })
        return LinkedAc(id, found.mac, found.host, r.str("key") ?: throw AcUnreachable())
    }

    override suspend fun status(ac: LinkedAc): AcState {
        val r = request(ac, buildJsonObject {
            put("t", "status"); put("mac", ac.mac); putJsonArray("cols") { listOf("Pow", "Mod", "SetTem", "WdSpd").forEach { add(it) } }
        })
        // An odd reply counts as no reply, so withAc and apply's read-back still recover.
        val v = (r["dat"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: throw AcUnreachable() }
            ?.takeIf { it.size >= 4 } ?: throw AcUnreachable()
        if (v[0] !in 0..1 || v[3] !in 0..5) throw AcUnreachable()
        return AcState(
            v[0] == 1,
            modes.entries.firstOrNull { it.value == v[1] }?.key ?: throw AcUnreachable(),
            v[2],
            // 2 and 4 (between the speeds we show) round down.
            fanSpeed(v[3]),
        )
    }

    internal fun fanSpeed(speed: Int) = fans.entries.lastOrNull { it.value <= speed }?.key ?: AcFan.Auto

    internal fun commandValues(change: AcChange) = buildMap {
        change.power?.let { put("Pow", if (it) 1 else 0) }
        change.mode?.let { put("Mod", modes.getValue(it)) }
        change.target?.let { put("SetTem", it) } // SetTem is in Celsius even when the display uses Fahrenheit.
        change.fan?.let { put("WdSpd", fans.getValue(it)) }
    }

    internal fun confirms(reply: JsonObject, sent: Map<String, Int>): Boolean {
        if (reply.str("t") != "res" || (reply["r"] as? JsonPrimitive)?.intOrNull != 200) return false
        val options = reply["opt"] as? JsonArray ?: return false
        val values = (reply["val"] ?: reply["p"]) as? JsonArray ?: return false
        if (options.size != values.size) return false
        val actual = options.zip(values).associate { (option, value) ->
            (option as? JsonPrimitive)?.content to (value as? JsonPrimitive)?.intOrNull
        }
        return sent.all { (key, value) -> actual[key] == value }
    }

    override suspend fun apply(ac: LinkedAc, change: AcChange, isCurrent: () -> Boolean) {
        val values = commandValues(change)
        val reply = request(ac, buildJsonObject {
            put("t", "cmd")
            putJsonArray("opt") { values.keys.forEach { add(it) } }
            putJsonArray("p") { values.values.forEach { add(it) } }
        }, isCurrent)
        if (!confirms(reply, values)) throw AcUnreachable()
    }
}
