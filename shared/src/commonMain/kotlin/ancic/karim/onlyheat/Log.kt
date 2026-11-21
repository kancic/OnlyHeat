package ancic.karim.onlyheat

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging

// ponytail: println lands in logcat (tag System.out), the Xcode console and the desktop terminal.
internal fun log(msg: String, e: Throwable? = null) {
    println("OnlyHeat: $msg" + (e?.let { "\n" + it.stackTraceToString() } ?: ""))
}

/**
 * Full HTTP logging (headers and bodies, so the auth key, PIN and cookies too). Debug builds only:
 * the Android app turns it on when debuggable; set it before the first screen is shown.
 */
var verboseHttpLogs = false

/** Method, URL and status always; headers and bodies only with [verboseHttpLogs]. */
internal fun HttpClientConfig<*>.logHttp() = install(Logging) {
    level = if (verboseHttpLogs) LogLevel.ALL else LogLevel.INFO
    logger = object : Logger {
        override fun log(message: String) = ancic.karim.onlyheat.log("HTTP $message")
    }
}

/** [runCatching] that lets coroutine cancellation through instead of swallowing it. */
// Not inline: the app module targets an older JVM than :shared and can't inline from it.
suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    runCatching { block() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
