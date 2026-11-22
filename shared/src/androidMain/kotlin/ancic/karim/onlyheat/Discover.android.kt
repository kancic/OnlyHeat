package ancic.karim.onlyheat

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy

internal actual suspend fun currentLanConnection(): LanConnection? {
    val context = androidContextOrNull() ?: return null
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val caps = manager.getNetworkCapabilities(network) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    if (boot < 0) return null
    return LanConnection("$boot:${network.networkHandle}") { configure ->
        HttpClient(OkHttp) {
            // A Wi-Fi change must fail this request instead of rerouting the saved PIN.
            engine { config { socketFactory(network.socketFactory); proxy(Proxy.NO_PROXY) } }
            configure()
        }
    }
}

// Wi-Fi/Ethernet only, by name (Android wlan0/eth0, macOS en0, Linux wlp*/enp*/eth*): mobile data (rmnet, ccmni)
// and VPNs (tun, utun) are skipped so the scan never probes a carrier's or company's network.
internal actual fun localIpv4s(): List<Pair<String, Int>> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback && listOf("wl", "en", "eth").any(it.name::startsWith) }
    .flatMap { it.interfaceAddresses }
    .filter { it.address is Inet4Address }
    .map { it.address.hostAddress to it.networkPrefixLength.toInt() }
