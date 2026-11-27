package ancic.karim.onlyheat.ac

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal actual fun md5(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("MD5").digest(data)

internal actual val cloudSockets = true

internal actual suspend fun <T> withCloudSocket(
    host: String, port: Int, tls: Boolean, exchange: (CloudSocket) -> T,
): T? = suspendCancellableCoroutine { continuation ->
    val plain = Socket()
    // Closing the underlying socket unblocks connect, TLS handshake and reads.
    continuation.invokeOnCancellation { runCatching { plain.close() } }
    Dispatchers.IO.dispatch(continuation.context) {
        continuation.resumeWith(runCatching {
            plain.use {
                continuation.context.ensureActive()
                plain.soTimeout = 10_000
                plain.connect(InetSocketAddress(host, port), 10_000)
                val socket = if (!tls) plain else (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true) as SSLSocket
                socket.use {
                    if (socket is SSLSocket) {
                        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        socket.startHandshake()
                    }
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    exchange(object : CloudSocket {
                        override fun write(bytes: ByteArray) {
                            continuation.context.ensureActive()
                            output.write(bytes)
                            output.flush()
                        }
                        override fun read() = input.read()
                    })
                }
            }
        })
    }
}
