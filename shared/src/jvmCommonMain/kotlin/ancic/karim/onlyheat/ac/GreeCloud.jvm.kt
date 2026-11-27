package ancic.karim.onlyheat.ac

import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal actual fun md5(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("MD5").digest(data)

internal actual val cloudSockets = true

internal actual fun openCloudSocket(host: String, port: Int, tls: Boolean): CloudSocket? {
    val plain = Socket().apply { soTimeout = 10_000; connect(InetSocketAddress(host, port), 10_000) }
    val socket = if (!tls) plain else (SSLSocketFactory.getDefault() as SSLSocketFactory)
        .createSocket(plain, host, port, true).let { it as SSLSocket }
        .apply { sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }; startHandshake() }
    val input = BufferedInputStream(socket.getInputStream())
    val output = socket.getOutputStream()
    return object : CloudSocket {
        override fun write(bytes: ByteArray) = output.run { write(bytes); flush() }
        override fun read() = input.read()
        override fun close() = socket.close()
    }
}
