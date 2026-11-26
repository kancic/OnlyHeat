package ancic.karim.onlyheat.ac

import ancic.karim.onlyheat.data.androidContextOrNull
import ancic.karim.onlyheat.data.nowSeconds
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.minutes

/** Wi-Fi scan results need precise location even with Nearby devices (and location turned on, or they're empty). */
fun canScanWifi(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal actual fun nearbyWifi(): List<NearbyWifi> {
    val context = androidContextOrNull() ?: return emptyList()
    if (!canScanWifi(context)) return emptyList()
    val wifi = context.getSystemService(WifiManager::class.java) ?: return emptyList()
    @Suppress("DEPRECATION") val results = wifi.scanResults
    // The results read here are the last scan's, which may be another app's or the system's. Android allows 4 scans
    // per 2 minutes, so a new one is asked only once they're a minute old; the next refresh reads it.
    val bootMicros = SystemClock.elapsedRealtime() * 1000
    if (results.none { bootMicros - it.timestamp < 60_000_000 }) @Suppress("DEPRECATION") wifi.startScan()
    val now = nowSeconds()
    return results.map { NearbyWifi(it.SSID, it.BSSID, it.frequency, it.level, now - (bootMicros - it.timestamp) / 1_000_000) }
}

// The SSID needs precise location too; without it Android says "<unknown ssid>".
internal actual fun currentWifi(): Pair<String, String>? {
    val context = androidContextOrNull() ?: return null
    if (!canScanWifi(context)) return null
    @Suppress("DEPRECATION") val info = context.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
    val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID } ?: return null
    return ssid to (info.bssid ?: return null)
}

internal actual suspend fun exchangeOnWifi(ap: FoundAc, password: String, port: Int, message: String): String? {
    val ssid = ap.ssid ?: throw AcUnreachable()
    val bssid = ap.bssid ?: throw AcUnreachable()
    val manager = androidContextOrNull()?.getSystemService(ConnectivityManager::class.java) ?: throw AcUnreachable()
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(WifiNetworkSpecifier.Builder()
            .setSsid(ssid).setBssid(MacAddress.fromString(bssid)).setWpa2Passphrase(password).build())
        .build()
    val joined = CompletableDeferred<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { joined.complete(network) }
        override fun onUnavailable() { joined.completeExceptionally(AcUnreachable()) } // declined or not found
    }
    manager.requestNetwork(request, callback) // Android asks the user to connect
    try {
        // The user may take a while to tap Connect. A timeout fails like a decline: a cancellation would end setup silently.
        val network = withTimeoutOrNull(2.minutes) { joined.await() } ?: throw AcUnreachable()
        return withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.connect(InetAddress.getByName(ap.host), port)
                socket.soTimeout = 3_000
                val bytes = message.encodeToByteArray()
                val reply = DatagramPacket(ByteArray(4096), 4096)
                repeat(2) { attempt ->
                    ensureActive()
                    // A module can leave its hotspot as soon as it receives the first packet.
                    if (attempt > 0 && manager.getNetworkCapabilities(network) == null) return@withContext null
                    socket.send(DatagramPacket(bytes, bytes.size))
                    try {
                        socket.receive(reply)
                        return@withContext reply.data.decodeToString(0, reply.length)
                    } catch (_: SocketTimeoutException) {
                        // UDP can lose the request or the reply.
                    }
                }
                null
            }
        }
    } finally {
        manager.unregisterNetworkCallback(callback)
    }
}
