package ancic.karim.onlyheat

import com.russhwolf.settings.PreferencesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchWeatherTest {
    @Test
    fun backgroundCheckUsesWeatherOnlyForEligibleRoomsWithoutAnOutdoorSensor() = runTest {
        var roomTemp = 24
        var sensorTemp: Int? = null
        var weatherAvailable = true
        var weatherRequests = 0
        val client = HttpClient(OkHttp) {
            engine {
                config {
                    addInterceptor { chain ->
                        val request = chain.request()
                        val reply = when (request.url.pathSegments.last()) {
                            "getRooms" -> """{"status":"OK","s7Temp":$sensorTemp,"rooms":[{"id":"1","currentTemp":$roomTemp}]}"""
                            "getSmartForecast" -> {
                                weatherRequests++
                                if (weatherAvailable) """{"status":"OK","dasboardForecast":[{"id":"daily","temperature":31}]}"""
                                else """{"status":"ERROR","message":"No weather"}"""
                            }
                            else -> error("Unexpected request path")
                        }
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").body(reply.toResponseBody()).build()
                    }
                }
            }
        }
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val settings = PreferencesSettings(node)
            savePairing(settings, Pairing("test-key", null))
            val api = HeatApi(client)

            assertEquals(emptyList(), checkAlerts(api, settings))
            roomTemp = 27
            sensorTemp = 22
            assertEquals(emptyList(), checkAlerts(api, settings))
            assertEquals(0, weatherRequests)

            sensorTemp = null
            assertEquals(1, checkAlerts(api, settings).size)
            assertEquals(setOf("1"), loadWatch(settings).warm)
            assertEquals(1, weatherRequests)

            weatherAvailable = false
            assertEquals(emptyList(), checkAlerts(api, settings))
            weatherAvailable = true
            assertEquals(emptyList(), checkAlerts(api, settings))

            val ac = LinkedAc("gree", "f4911ed454bb", "192.168.100.53", "test")
            val cooling = AcState(true, AcMode.Cool, 24, AcFan.Auto)
            settings.linkAc("test-key", "1", ac)
            sensorTemp = 31
            roomTemp = 24
            assertEquals(emptyList(), checkAlerts(api, settings) { cooling })
            roomTemp = 27
            assertEquals(emptyList(), checkAlerts(api, settings) { cooling })
            assertEquals(emptySet(), loadWatch(settings).warm)

            val tip = checkAlerts(api, settings) { cooling.copy(power = false) }.single()
            assertEquals(listOf(ac.id), tip.acLinks)
            assertEquals(emptyList(), checkAlerts(api, settings) { cooling.copy(power = false) })

            roomTemp = 24
            assertEquals(emptyList(), checkAlerts(api, settings) { cooling })
            roomTemp = 27
            val highTargetTip = checkAlerts(api, settings) { cooling.copy(target = 30) }.single()
            assertEquals(listOf(ac.id), highTargetTip.acLinks)
        } finally {
            client.close()
            node.removeNode()
        }
    }
}
