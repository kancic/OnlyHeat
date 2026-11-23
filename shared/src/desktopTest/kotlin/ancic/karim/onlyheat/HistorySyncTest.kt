package ancic.karim.onlyheat

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.russhwolf.settings.PreferencesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class HistorySyncTest {
    @Test fun retriesOutdoorHistoryAndStoresItUnderTheCurrentController() = runTest {
        var roomRequests = 0
        var outdoorRequests = 0
        var temperature = 8.0
        val client = HttpClient(OkHttp) {
            engine {
                config {
                    addInterceptor { chain ->
                        val request = chain.request()
                        val reply = when (request.url.pathSegments.last()) {
                            "getKey" -> """{"status":"OK","authKey":"first","serial":"controller-a"}"""
                            "getRooms" -> """{"status":"OK","rooms":[{"id":"1"}]}"""
                            "getRoomLog" -> {
                                roomRequests++
                                """{"status":"OK","singleLogs":[{"dateTime":3600,"currentTemp":"20"}]}"""
                            }
                            "getSmartRoomLogsLast24h" -> {
                                if (++outdoorRequests <= 3) throw SocketTimeoutException("test timeout")
                                """{"status":"OK","smartLogs":[{"logs":[{"dateTime":3600,"outsideTemp":"$temperature"}]}]}"""
                            }
                            else -> error("Unexpected request path")
                        }
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").body(reply.toResponseBody()).build()
                    }
                }
            }
        }
        val db = Room.inMemoryDatabaseBuilder<HistoryDb>().setDriver(BundledSQLiteDriver()).build()
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val api = HeatApi(client)
            val history = db.readings()
            val settings = PreferencesSettings(node)
            history.insert(listOf(Reading("outside", 3600, 99.0, null)))
            val pairing = api.login("test-pin")
            assertEquals(Pairing("first", "controller-a"), pairing)
            savePairing(settings, pairing)
            val firstOutside = outsideId("first", settings)

            syncHistory(api, history, "first", settings)
            assertEquals(1, roomRequests)
            assertEquals(emptyList(), history.averaged(firstOutside, 1, 3600))

            syncHistory(api, history, "first", settings)
            syncHistory(api, history, "first", settings)
            assertEquals(1, roomRequests)
            assertEquals(4, outdoorRequests)

            clearSession(settings, "first")
            savePairing(settings, Pairing("renewed", "controller-a"))
            assertEquals(firstOutside, outsideId("renewed", settings))

            clearSession(settings, "renewed")
            temperature = -3.0
            // A pairing without a serial still has its own history.
            savePairing(settings, Pairing("second", null))
            syncHistory(api, history, "second", settings)
            assertEquals(5, outdoorRequests)
            assertEquals(8.0, history.averaged(firstOutside, 1, 3600).single().measured)
            assertEquals(-3.0, history.averaged(outsideId("second", settings), 1, 3600).single().measured)
        } finally {
            client.close()
            db.close()
            node.removeNode()
        }
    }
}
