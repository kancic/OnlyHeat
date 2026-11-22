package ancic.karim.onlyheat

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

private data class MacWifi(val interfaceName: String, val address: Inet4Address, val id: ULong)

private val macBootSession by lazy {
    macNetworkCommand("/usr/sbin/sysctl", "-n", "kern.bootsessionuuid")
        ?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}

internal actual suspend fun currentLanConnection(): LanConnection? {
    if (!System.getProperty("os.name").startsWith("Mac")) return null
    return withContext(Dispatchers.IO) {
        val boot = macBootSession ?: return@withContext null
        val wifi = macWifi() ?: return@withContext null
        LanConnection("mac:$boot:${wifi.interfaceName}:${wifi.id}:${wifi.address.hostAddress}") { configure ->
            HttpClient(OkHttp) {
                engine {
                    config {
                        proxy(Proxy.NO_PROXY)
                        addNetworkInterceptor { chain ->
                            val request = chain.request()
                            if (chain.connection()?.socket()?.localAddress != wifi.address ||
                                (request.url.encodedPath != "/dashboard" && macWifi() != wifi)
                            ) throw IOException("The selected Wi-Fi connection changed")
                            chain.proceed(request)
                        }
                    }
                }
                configure()
            }
        }
    }
}

private fun macWifi(): MacWifi? =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { intf ->
        if (!intf.name.startsWith("en") || !intf.isUp) return@mapNotNull null
        val address = intf.inetAddresses.toList().filterIsInstance<Inet4Address>()
            .singleOrNull { isPrivateIpv4(it.hostAddress) } ?: return@mapNotNull null
        val summary = macNetworkCommand("/usr/sbin/ipconfig", "getsummary", intf.name) ?: return@mapNotNull null
        val id = macWifiConnectionId(summary) ?: return@mapNotNull null
        MacWifi(intf.name, address, id)
    }.singleOrNull()

internal fun macWifiConnectionId(summary: String): ULong? {
    val fields = summary.lineSequence()
        .filter { it.startsWith("  ") && !it.startsWith("   ") }
        .mapNotNull { line ->
            val parts = line.trim().split(" : ", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    if (fields["InterfaceType"] != "WiFi" || fields["LinkStatusActive"] != "TRUE") return null
    return fields["ConnectionID"]?.toULongOrNull()?.takeIf { it != 0uL }
}

private fun macNetworkCommand(vararg args: String): String? = try {
    val process = ProcessBuilder(*args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) {
            process.inputStream.bufferedReader().use { it.readText() }
        } else null
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
    }
} catch (_: IOException) {
    null
}

// Wi-Fi/Ethernet only, by name (Android wlan0/eth0, macOS en0, Linux wlp*/enp*/eth*): mobile data (rmnet, ccmni)
// and VPNs (tun, utun) are skipped so the scan never probes a carrier's or company's network.
internal actual fun localIpv4s(): List<Pair<String, Int>> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback && listOf("wl", "en", "eth").any(it.name::startsWith) }
    .flatMap { it.interfaceAddresses }
    .filter { it.address is Inet4Address }
    .map { it.address.hostAddress to it.networkPrefixLength.toInt() }
