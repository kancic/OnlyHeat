package ancic.karim.onlyheat

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** This device's IPv4 addresses on Wi-Fi/Ethernet (never mobile data or VPN), with their prefix length. */
internal expect fun localIpv4s(): List<Pair<String, Int>>
internal expect suspend fun currentLanConnection(): LanConnection?

internal class LanConnection(
    val id: String,
    val openClient: (HttpClientConfig<*>.() -> Unit) -> HttpClient,
)

internal fun LanConnection?.httpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    if (this == null) HttpClient(block = configure) else openClient(configure)

internal fun dashboardProbeClient(connection: LanConnection? = null) = connection.httpClient {
    followRedirects = false
    install(HttpTimeout) { connectTimeoutMillis = 1_500 }
}

private val dashboardTitle = Regex("""<title>\s*Dashboard\s*\|\s*TermoTehnik PI\s*</title>""", RegexOption.IGNORE_CASE)
private val dashboardDevice = Regex("""<option\b[^>]*>\s*(TTO Advance-[0-9A-F]{6})\s*\(\*\)\s*</option>""", RegexOption.IGNORE_CASE)

/** Recognizes the controller's login page. Its markup doesn't identify an individual unit. */
internal fun looksLikeDashboard(html: String): Boolean =
    dashboardTitle.containsMatchIn(html) && html.contains("id=\"loginForm\"") &&
        (1..4).all { html.contains("name=\"loginInput$it\"") }

internal fun dashboardName(html: String): String? =
    dashboardDevice.findAll(html).singleOrNull()?.groupValues?.get(1)

data class DashboardTarget(val host: String, val name: String? = null, val connectionId: String? = null)

internal const val SAVED_LAN_TARGET = "savedLanController"

internal fun DashboardTarget.rememberedValue(): String? {
    val knownName = name ?: return null
    return "${connectionId.orEmpty()}|$host|$knownName"
}

internal fun savedLanTarget(value: String?): DashboardTarget? {
    val fields = value?.split('|')?.takeIf { it.size == 3 } ?: return null
    val (id, host, name) = fields
    if (!isPrivateIpv4(host) || name.isBlank()) return null
    return DashboardTarget(host, name, id.ifEmpty { null })
}

internal fun Settings.savedLanTarget() = savedLanTarget(getStringOrNull(SAVED_LAN_TARGET))

/** The hotspot needs no saving: it is always tried. */
internal fun Settings.rememberLanTarget(target: DashboardTarget, hotspot: String) {
    if (target.host != hotspot) target.rememberedValue()?.let { putString(SAVED_LAN_TARGET, it) }
}

private suspend fun HttpClient.dashboardPage(host: String): String? {
    if (!isPrivateIpv4(host)) return null
    return attempt {
        get("http://$host/dashboard") { timeout { requestTimeoutMillis = 3_000 } }
            .let { if (it.status.isSuccess()) it.bodyAsText().takeIf(::looksLikeDashboard) else null }
    }.getOrNull()
}

internal suspend fun HttpClient.hasDashboard(host: String, expectedName: String? = null): Boolean =
    dashboardPage(host)?.let { expectedName == null || dashboardName(it) == expectedName } == true

private suspend fun lanHosts(exclude: String): List<String> {
    val addrs = attempt { localIpv4s() }.getOrElse { log("cannot list network interfaces", it); emptyList() }
    return scanHosts(addrs) - exclude
}

/** A matching page is a discovery hint. The user must identify their own controller. */
internal suspend fun HttpClient.findLanDashboard(exclude: String, expectedName: String? = null): String? =
    firstDashboard(lanHosts(exclude)) { hasDashboard(it, expectedName) }

/** Reports each controller, with its own Wi-Fi name when it gives one, as it answers: live hosts reply in well
 *  under a second, the dead ones only time out. */
internal suspend fun HttpClient.findNamedDashboards(exclude: String, found: (DashboardTarget, String?) -> Unit) {
    probeHosts(lanHosts(exclude)) { host ->
        dashboardPage(host)?.let(::dashboardName)?.let { found(DashboardTarget(host, it), wifiName(host)) }
    }
}

/** The controller's own Wi-Fi name (the user may have renamed it). The controller serves it without login. */
private suspend fun HttpClient.wifiName(host: String): String? = attempt {
    val reply = post("http://$host/include/ajax/check-wifi-status.php") { timeout { requestTimeoutMillis = 3_000 } }
    Json.parseToJsonElement(reply.bodyAsText()).jsonObject.str("wifiSSID")
}.getOrNull()

private suspend fun <T> probeHosts(hosts: List<String>, probe: suspend (String) -> T): List<T> = coroutineScope {
    // Acquire a slot before Ktor starts the timeout, just below OkHttp's 64-request limit. Most of a /24 never
    // answers and waits out the connect timeout, so the slot count sets how long a full scan takes.
    val slots = Semaphore(60)
    hosts.map { host -> async { slots.withPermit { probe(host) } } }.awaitAll()
}

internal suspend fun firstDashboard(hosts: List<String>, probe: suspend (String) -> Boolean): String? = coroutineScope {
    val found = CompletableDeferred<String?>()
    val probes = launch {
        probeHosts(hosts) { host ->
            if (!found.isCompleted && probe(host)) found.complete(host)
        }
        found.complete(null)
    }
    found.await().also { probes.cancel() }
}

private fun ipv4Octets(ip: String): List<Int>? =
    ip.split('.').takeIf { it.size == 4 }?.map {
        it.toIntOrNull()?.takeIf { n -> n in 0..255 && n.toString() == it } ?: return null
    }

private fun List<Int>.isPrivateIpv4() =
    this[0] == 10 || (this[0] == 172 && this[1] in 16..31) || (this[0] == 192 && this[1] == 168)

internal fun isPrivateIpv4(ip: String) = ipv4Octets(ip)?.isPrivateIpv4() == true

/** Only the hotspot or a previously or currently identified LAN target can receive credentials. */
internal suspend fun dashboardAddress(
    hotspot: String,
    confirmedLanAddress: suspend () -> DashboardTarget?,
    remembered: DashboardTarget? = null,
    hotspotName: String? = null, // the user's controller, once known: nothing else at the hotspot address gets the PIN
    selected: DashboardTarget? = null,
    probe: suspend (DashboardTarget) -> Boolean,
): DashboardTarget? {
    suspend fun DashboardTarget.usable() = isPrivateIpv4(host) && probe(this)
    if (selected != null) return selected.takeIf { it.usable() }
    remembered?.let { if (it.usable()) return it }
    DashboardTarget(hotspot, hotspotName).let { if (it.usable()) return it }
    return confirmedLanAddress()?.takeIf { it.usable() }
}

/** Resolves a controller without credentials. An explicit selection allows no fallback. */
internal suspend fun resolveDashboard(
    hotspot: String,
    settings: Settings,
    confirm: suspend () -> DashboardTarget? = { null },
    recent: DashboardTarget? = null,
    selected: DashboardTarget? = null,
): Pair<DashboardTarget, LanConnection?>? {
    val saved = settings.savedLanTarget()
    var connection: LanConnection? = null
    val target = dashboardAddress(
        hotspot, confirm,
        // The saved controller on whatever Wi-Fi the phone is on now: login still checks it shows the saved name.
        remembered = if (selected == null) recent ?: saved?.copy(connectionId = currentLanConnection()?.id) else null,
        hotspotName = saved?.name,
        selected = selected,
    ) { candidate ->
        // Read again: the user may switch Wi-Fi while the picker is open.
        val candidateConnection = candidate.connectionId?.let { id ->
            currentLanConnection()?.takeIf { it.id == id } ?: return@dashboardAddress false
        }
        connection = candidateConnection
        dashboardProbeClient(candidateConnection).use { it.hasDashboard(candidate.host, candidate.name) }
    } ?: return null
    return target to connection
}

/** Hosts of each private network, once.
 *  ponytail: networks bigger than /24 are only scanned in this device's /24; a controller outside it is not found. */
internal fun scanHosts(addrs: List<Pair<String, Int>>): List<String> = addrs.flatMap { (ip, prefix) ->
    val o = ipv4Octets(ip)?.takeIf { it.isPrivateIpv4() } ?: return@flatMap emptyList()
    val size = 1 shl (32 - prefix.coerceIn(24, 30))
    val first = o[3] / size * size
    (first + 1 until first + size - 1).map { "${o[0]}.${o[1]}.${o[2]}.$it" }
}.distinct()
