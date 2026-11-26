package ancic.karim.onlyheat.ac

internal actual fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray =
    javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").run {
        init(if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        doFinal(data)
    }
