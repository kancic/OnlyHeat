package ancic.karim.onlyheat.ac

import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
    val out = ByteArray(data.size)
    val status = memScoped {
        val moved = alloc<platform.posix.size_tVar>()
        key.usePinned { k -> data.usePinned { d -> out.usePinned { o ->
            platform.CoreCrypto.CCCrypt(
                (if (encrypt) platform.CoreCrypto.kCCEncrypt else platform.CoreCrypto.kCCDecrypt).convert(),
                platform.CoreCrypto.kCCAlgorithmAES.convert(), platform.CoreCrypto.kCCOptionECBMode.convert(),
                k.addressOf(0), key.size.convert(), null,
                d.addressOf(0), data.size.convert(), o.addressOf(0), out.size.convert(), moved.ptr,
            )
        } } }
    }
    check(status == platform.CoreCrypto.kCCSuccess) { "AES failed: $status" }
    return out
}
