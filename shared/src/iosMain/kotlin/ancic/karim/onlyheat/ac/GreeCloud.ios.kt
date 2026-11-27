package ancic.karim.onlyheat.ac

import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun md5(data: ByteArray): ByteArray {
    val out = UByteArray(16)
    data.usePinned { d -> out.usePinned { o -> platform.CoreCrypto.CC_MD5(if (data.isEmpty()) null else d.addressOf(0), data.size.convert(), o.addressOf(0)) } }
    return out.asByteArray()
}

internal actual val cloudSockets = false

internal actual suspend fun <T> withCloudSocket(
    host: String, port: Int, tls: Boolean, exchange: (CloudSocket) -> T,
): T? = null
