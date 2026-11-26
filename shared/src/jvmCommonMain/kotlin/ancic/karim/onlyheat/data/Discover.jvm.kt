package ancic.karim.onlyheat.data

import java.net.Inet4Address
import java.net.NetworkInterface


// Wi-Fi/Ethernet only, by name (Android wlan0/eth0, macOS en0, Linux wlp*/enp*/eth*): mobile data (rmnet, ccmni)
// and VPNs (tun, utun) are skipped so the scan never probes a carrier's or company's network.
internal actual fun localIpv4s(): List<Pair<String, Int>> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback && listOf("wl", "en", "eth").any(it.name::startsWith) }
    .flatMap { it.interfaceAddresses }
    .filter { it.address is Inet4Address }
    .map { it.address.hostAddress to it.networkPrefixLength.toInt() }
