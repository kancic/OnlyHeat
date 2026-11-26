package ancic.karim.onlyheat.ac

internal actual fun currentWifi(): Pair<String, String>? = null

internal actual fun nearbyWifi(): List<NearbyWifi> = emptyList()

internal actual suspend fun exchangeOnWifi(ap: FoundAc, password: String, port: Int, message: String): String? =
    throw AcUnreachable()
