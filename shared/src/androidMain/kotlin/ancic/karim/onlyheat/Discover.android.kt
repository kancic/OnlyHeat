package ancic.karim.onlyheat

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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
