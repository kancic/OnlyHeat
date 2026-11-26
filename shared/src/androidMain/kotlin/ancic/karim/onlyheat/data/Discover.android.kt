package ancic.karim.onlyheat.data

import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import android.provider.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy


internal actual val requiresLanConnection = true

/** The active network and its capabilities; a VPN's include the transports under it. */
private fun activeNetwork(manager: ConnectivityManager): Pair<Network, NetworkCapabilities>? {
    val network = manager.activeNetwork ?: return null
    return network to (manager.getNetworkCapabilities(network) ?: return null)
}

internal actual suspend fun currentLanConnection(): LanConnection? {
    val context = androidContextOrNull() ?: return null
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val (network, caps) = activeNetwork(manager) ?: return null
    val wifi = lanWifi(manager, network, caps) ?: return null
    val withVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    if (boot < 0) return null
    return LanConnection("$boot:${wifi.networkHandle}") { configure ->
        HttpClient(OkHttp) {
            engine {
                config {
                    // Let Android apply the VPN's split routes and lockdown policy.
                    if (!withVpn) socketFactory(wifi.socketFactory)
                    proxy(Proxy.NO_PROXY)
                    addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val (active, capabilities) = activeNetwork(manager) ?: throw IOException("Wi-Fi is unavailable")
                        if (active != network || lanWifi(manager, active, capabilities) != wifi ||
                            (withVpn && (vpnTakesAddress(manager, active, InetAddresses.parseNumericAddress(request.url.host)) ||
                                manager.getLinkProperties(wifi)?.linkAddresses.orEmpty().none {
                                    it.address == chain.connection()?.socket()?.localAddress
                                }))
                        ) throw IOException("The controller's Wi-Fi route is unavailable")
                        chain.proceed(request)
                    }
                }
            }
            configure()
        }
    }
}

private fun lanWifi(manager: ConnectivityManager, network: Network, caps: NetworkCapabilities): Network? =
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) underWifi(manager) else null
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> network
        // Android can keep cellular as the default while connected to a controller's hotspot.
        else -> wifiNetworks(manager).singleOrNull()
    }

private fun isWifi(manager: ConnectivityManager, n: Network) = manager.getNetworkCapabilities(n)
    ?.run { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true

private fun wifiNetworks(manager: ConnectivityManager): List<Network> {
    @Suppress("DEPRECATION")
    return manager.allNetworks.filter { isWifi(manager, it) }
}

/** Prefer the validated Wi-Fi when a VPN and an AC setup hotspot coexist. */
private fun underWifi(manager: ConnectivityManager): Network? =
    wifiNetworks(manager).maxByOrNull {
        manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

/** Only an absent route or an explicit exclusion allows this destination under a VPN. */
private fun vpnTakesAddress(manager: ConnectivityManager, vpn: Network, address: InetAddress): Boolean {
    val routes = manager.getLinkProperties(vpn)?.routes ?: return true
    val route = routes.filter { it.matches(address) }.maxByOrNull { it.destination.prefixLength } ?: return false
    return Build.VERSION.SDK_INT < 33 || route.type != RouteInfo.RTN_THROW
}
