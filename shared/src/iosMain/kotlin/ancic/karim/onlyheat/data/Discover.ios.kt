package ancic.karim.onlyheat.data

import kotlinx.cinterop.*
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.AF_INET
import platform.posix.IFF_UP
import platform.posix.INET_ADDRSTRLEN
import platform.posix.sockaddr_in

internal actual suspend fun currentLanConnection(): LanConnection? = null

@OptIn(ExperimentalForeignApi::class)
internal actual fun localIpv4s(): List<Pair<String, Int>> = memScoped {
    val interfaces = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(interfaces.ptr) != 0) return@memScoped emptyList()
    val first = interfaces.value ?: return@memScoped emptyList()
    val buffer = allocArray<ByteVar>(INET_ADDRSTRLEN)
    try {
        generateSequence(first) { it.pointed.ifa_next }.mapNotNull { pointer ->
            val entry = pointer.pointed
            val address = entry.ifa_addr ?: return@mapNotNull null
            val mask = entry.ifa_netmask ?: return@mapNotNull null
            // Apple uses en interfaces for Wi-Fi and Ethernet. Exclude cellular and tunnels.
            if (entry.ifa_name?.toKString()?.startsWith("en") != true ||
                entry.ifa_flags and IFF_UP.toUInt() == 0u ||
                address.pointed.sa_family.toInt() != AF_INET || mask.pointed.sa_family.toInt() != AF_INET
            ) return@mapNotNull null
            val ipv4 = address.reinterpret<sockaddr_in>().pointed.sin_addr
            val ip = inet_ntop(AF_INET, ipv4.ptr, buffer, INET_ADDRSTRLEN.toUInt())?.toKString()
                ?: return@mapNotNull null
            ip to mask.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr.countOneBits()
        }.toList()
    } finally {
        freeifaddrs(first)
    }
}
