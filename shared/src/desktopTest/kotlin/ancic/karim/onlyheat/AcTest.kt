package ancic.karim.onlyheat

import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.PreferencesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AcTest {
    @Test
    fun greeCryptoMatchesOpenssl() {
        val bind = """{"mac":"f4911ed454bb","t":"bind","uid":0}"""
        val packed = "2NWZzr9LM0ab/phQ6xTVqiKNAmVSNpDLb94c3S4U0P+j9mrs/0p7GUTdWxmNaBUt" // openssl enc -aes-128-ecb
        assertEquals(packed, greeEncrypt(bind, "a3K8Bx%2r8Y7#xDh"))
        assertEquals(bind, greeDecrypt(packed, "a3K8Bx%2r8Y7#xDh"))
    }

    @Test
    fun notificationPairingDoesNotSurviveReplacementOrLogout() = runTest {
        withSettings { s ->
            s.putString("ac.room.legacy", "gree|${ac.mac}|${ac.host}|${ac.secret}")
            s.linkAc("first", "r1", ac)
            assertEquals(mapOf("r1" to ac), s.linkedAcs("first"))

            val replacement = LinkedAc(ac.protocol, ac.mac, ac.host, ac.secret)
            s.linkAc("first", "r1", replacement)
            assertFalse(coolRooms(s, listOf(ac.id)))

            savePairing(s, Pairing("second", null))
            assertNull(s.linkedAc("second", "r1"))
            assertFalse(coolRooms(s, listOf(replacement.id)))

            savePairing(s, Pairing("first", null))
            assertTrue(clearSession(s, "first"))
            savePairing(s, Pairing("first", null))
            assertNull(s.linkedAc("first", "r1"))
            assertFalse(coolRooms(s, listOf(replacement.id)))
        }
    }

    @Test
    fun addressRecoveryKeepsThePairingIdAndDoesNotRestoreAnUnlinkedUnit() = runTest {
        withSettings { s ->
            val moved = ac.asFound().copy(host = "192.168.100.54")
            s.linkAc("first", "r1", ac)
            val recovered = s.withAc("first", "r1", ac.id, scan = { listOf(moved) }) {
                if (it.host != moved.host) throw AcUnreachable()
                it
            }
            assertEquals(ac.copy(host = moved.host), recovered)

            s.linkAc("first", "r1", ac)
            assertFailsWith<AcUnreachable> {
                s.withAc("first", "r1", ac.id, scan = {
                    s.linkAc("first", "r1", null)
                    listOf(moved)
                }) { throw AcUnreachable() }
            }
            assertNull(s.linkedAc("first", "r1"))
        }
    }

    @Test
    fun commandSendsOnlyChangedValuesAndChecksTheResult() {
        val before = AcState(false, AcMode.Cool, 24, AcFan.Low)
        val change = before.changeTo(before.copy(power = true)) + AcChange(target = 25)
        val sent = Gree.commandValues(change)
        assertEquals(mapOf("Pow" to 1, "SetTem" to 25), sent)
        assertTrue(Gree.confirms(reply("""{"t":"res","r":200,"opt":["SetTem","Pow"],"p":[25,1]}"""), sent))
        assertFalse(Gree.confirms(reply("""{"t":"res","r":400,"opt":["Pow","SetTem"],"val":[1,25]}"""), sent))
        assertFalse(Gree.confirms(reply("""{"t":"res","r":200,"opt":["Pow","SetTem"],"p":[1,25],"val":[1,26]}"""), sent))
        assertFalse(Gree.confirms(reply("""{"t":"res","r":200,"opt":["Pow"],"val":[1]}"""), sent))
    }

    @Test
    fun unconfirmedFanChangeRequiresMatchingRoundedReadBack() = runTest {
        val state = AcState(true, AcMode.Fan, 24, AcFan.Low)
        var reported = state
        val protocol = object : AcProtocol {
            override val id = "test"
            override suspend fun scan(hosts: List<String>) = emptyList<FoundAc>()
            override suspend fun bind(found: FoundAc) = ac
            override suspend fun status(ac: LinkedAc) = reported
            override suspend fun apply(ac: LinkedAc, change: AcChange, isCurrent: () -> Boolean) = throw AcUnreachable()
        }
        val change = AcChange(fan = AcFan.Medium)
        assertEquals(state, assertFailsWith<AcUnreachable> { protocol.applyConfirmed(ac, change) { true } }.actual)
        reported = state.copy(fan = Gree.fanSpeed(4), target = 20)
        assertEquals(reported, protocol.applyConfirmed(ac, change) { true })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pollStartedBeforeACompletedChangeCannotOverwriteRoomState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            withSettings { s ->
                s.linkAc("first", "r1", ac)
                val model = AcModel(s, "first")
                val state = AcState(false, AcMode.Cool, 24, AcFan.Low)
                model.refresh(canScan = false) { state }
                val slowPoll = launch {
                    model.refresh(canScan = false) {
                        delay(1_500)
                        error("no status reply")
                    }
                }
                runCurrent()
                // Toggle back during the debounce so the write completes locally without a UDP peer.
                model.set("r1", AcChange(power = true))
                model.set("r1", AcChange(power = false))
                slowPoll.join()
                assertEquals(state, model.room("r1")?.state)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun awayAndReturnReadLivePowerAndRestoreOnlyTheSamePairing() = runTest {
        withSettings { s ->
            val other = ac.copy(mac = "0011aabbccdd", host = "192.168.100.54", id = "other")
            val off = ac.copy(mac = "0011aabbccee", host = "192.168.100.55", id = "off")
            s.linkAc("first", "r1", ac)
            s.linkAc("first", "r2", other)
            s.linkAc("first", "r3", off)
            val model = AcModel(s, "first")
            val on = AcState(true, AcMode.Cool, 24, AcFan.Low)
            val live = mutableMapOf(ac.id to on, other.id to on, off.id to on.copy(power = false))
            val writes = mutableListOf<Pair<String, Boolean>>()
            suspend fun read(unit: LinkedAc) = live.getValue(unit.id)
            suspend fun write(unit: LinkedAc, change: AcChange, current: () -> Boolean): AcState {
                assertTrue(current())
                val state = change.on(live.getValue(unit.id))
                writes += unit.id to state.power
                live[unit.id] = state
                return state
            }

            model.refresh(canScan = false) { on.copy(power = false) }
            model.setAway(true, ::read, ::write)
            assertEquals(mapOf(ac.id to false, other.id to false), writes.toMap())
            assertEquals(false, model.room("r1")?.state?.power)

            model.refresh(canScan = false) { error("no status reply") }
            assertNull(model.room("r1")?.state)
            s.linkAc("first", "r2", other.copy(id = "replacement"))
            s.putString("away.ac", "r3")
            writes.clear()
            model.setAway(false, ::read, ::write)
            assertEquals(listOf(ac.id to true), writes)
            assertEquals(true, model.room("r1")?.state?.power)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun awayWritesContinueWhenTheAcViewModelCloses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = HttpClient()
        try {
            withSettings { s ->
                s.linkAc("first", "r1", ac)
                val acs = AcModel(s, "first")
                val history = object : ReadingDao {
                    override suspend fun insert(readings: List<Reading>) = error("unexpected history write")
                    override suspend fun averaged(roomId: String, from: Long, bucket: Long) = error("unexpected history read")
                    override suspend fun oldest() = error("unexpected history read")
                }
                val floor = HomeModel(HeatApi(client), history, "first", s)
                floor.home = Home(emptyList(), null, null, null, null)
                var reading = false
                var sent = false
                val job = floor.goAway {
                    acs.setAway(true, read = {
                        reading = true
                        delay(1)
                        AcState(true, AcMode.Cool, 24, AcFan.Low)
                    }, write = { _, change, _ ->
                        sent = change.power == false
                        null
                    })
                }
                try {
                    runCurrent()
                    assertTrue(reading)
                    assertTrue(floor.awayBusy)
                    acs.viewModelScope.cancel()
                    testScheduler.advanceTimeBy(2)
                    assertTrue(sent)
                    assertNull(floor.snackbar.currentSnackbarData)
                } finally {
                    job.cancel()
                }
            }
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aWorkingFloorInCoolingModeDoesNotCountAsHeating() {
        val room = Room("r1", "Room", 24.0, 22.0, null, heating = true, smart = false, schedule = false, cooling = true)
        val ac = AcView(AcState(true, AcMode.Cool, 21, AcFan.Auto), loaded = true)
        assertTrue(RoomActivity(room, ac).acCooling)
        assertFalse(RoomActivity(room, ac).floor)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newerRoomEditWinsDuringReadbackAndBulkSetClearsOnlyConfirmedWarnings() = runTest(timeout = 15.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val client = HttpClient(OkHttp) {
            engine {
                config {
                    addInterceptor { chain ->
                        val request = chain.request()
                        val body = if (request.url.pathSegments.last() == "getRooms") {
                            val read = reads.incrementAndGet()
                            if (read == 1) {
                                reading.complete(Unit)
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                            val target = if (read == 1) 20 else 22
                            """{"status":"OK","globalDeviceName":"read-$read","rooms":[{"id":"a","title":"Bedroom","targetTemp":"$target"},{"id":"b","title":"Kitchen","targetTemp":"19"}]}"""
                        } else """{"status":"OK"}"""
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").body(body.toResponseBody()).build()
                    }
                }
            }
        }
        try {
            withSettings { settings ->
                val history = object : ReadingDao {
                    override suspend fun insert(readings: List<Reading>) = error("unexpected history write")
                    override suspend fun averaged(roomId: String, from: Long, bucket: Long) = error("unexpected history read")
                    override suspend fun oldest() = error("unexpected history read")
                }
                val model = HomeModel(HeatApi(client), history, "first", settings)
                val room = Room("a", "Bedroom", null, 20.0, null, heating = false, smart = false, schedule = false)
                val first = checkNotNull(model.setTarget(room, 21.0))
                reading.await()
                val second = checkNotNull(model.setTarget(room, 22.0))
                release.countDown()
                first.join()
                second.join()
                assertEquals(22.0, model.home?.rooms?.first()?.target)
                assertTrue(model.writeNotices.isEmpty())

                saveWriteNotice(settings, "a", "Bedroom warning")
                saveWriteNotice(settings, "b", "Kitchen warning")
                val bulk = model.setAll(22.0)
                try {
                    while (model.snackbar.currentSnackbarData == null) withContext(Dispatchers.Default) { delay(10) }
                    assertEquals(mapOf("b" to "Kitchen warning"), loadWriteNotices(settings))
                } finally {
                    bulk.cancel()
                }
            }
        } finally {
            release.countDown()
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun roomsPollingRefreshesFallbackWeatherAndDiscardsItOnFailure() = runTest {
        val clock = TestTimeSource()
        var weatherRequests = 0
        var outdoorSensor: Int? = null
        var forecast = """{"status":"OK","dasboardForecast":[{"id":"daily","temperature":13}]}"""
        val client = HttpClient(OkHttp) {
            engine {
                config {
                    addInterceptor { chain ->
                        val request = chain.request()
                        val body = when (request.url.pathSegments.last()) {
                            "getRooms" -> """{"status":"OK","s7Temp":$outdoorSensor,"rooms":[]}"""
                            "getSmartForecast" -> { weatherRequests++; forecast }
                            else -> error("Unexpected request path")
                        }
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").body(body.toResponseBody()).build()
                    }
                }
            }
        }
        try {
            withSettings { s ->
                val history = object : ReadingDao {
                    override suspend fun insert(readings: List<Reading>) = error("unexpected history write")
                    override suspend fun averaged(roomId: String, from: Long, bucket: Long) = error("unexpected history read")
                    override suspend fun oldest() = error("unexpected history read")
                }
                val model = HomeModel(HeatApi(client), history, "first", s, clock)
                val room = Room("r1", "Room", 24.0, 27.0, null, heating = true, smart = false, schedule = false)
                model.refreshOnResume()
                assertEquals(13.0, model.outside)
                assertTrue(acIsWrong(room, model.outside, month = 7))

                clock += 2.minutes
                model.pollRooms()
                assertEquals(1, weatherRequests)

                forecast = """{"status":"OK","dasboardForecast":[{"id":"daily","temperature":25}]}"""
                clock += 13.minutes
                model.pollRooms()
                assertEquals(2, weatherRequests)
                assertEquals(25.0, model.outside)
                assertFalse(acIsWrong(room, model.outside, month = 7))

                outdoorSensor = 21
                model.pollRooms()
                assertEquals(21.0, model.outside)
                assertEquals(2, weatherRequests)

                outdoorSensor = null
                forecast = """{"status":"ERROR","message":"No weather"}"""
                clock += 15.minutes
                model.pollRooms()
                assertEquals(3, weatherRequests)
                assertNull(model.outside)
            }
        } finally {
            client.close()
        }
    }

    private val ac = LinkedAc("gree", "f4911ed454bb", "192.168.100.53", "w7Yz|0Bc3")
    private fun reply(json: String) = Json.parseToJsonElement(json).jsonObject

    private inline fun withSettings(block: (PreferencesSettings) -> Unit) {
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            block(PreferencesSettings(node).also { savePairing(it, Pairing("first", null)) })
        } finally {
            node.removeNode()
        }
    }
}

class AcIsWrongTest {
    private val room = Room("1", "R", current = 24.0, target = 27.0, humidity = null, heating = true, smart = false, schedule = false)

    @Test
    fun picksBySeason() {
        assertTrue(acIsWrong(room, outside = 1.0)) // winter: the AC goes
        assertFalse(acIsWrong(room, outside = 30.0)) // summer: the heating goes
        assertTrue(acIsWrong(room, outside = null, month = 1)) // no weather in winter: the AC
        assertFalse(acIsWrong(room, outside = null, month = 7)) // no weather in summer: the heating
        assertFalse(acIsWrong(room, outside = 21.0, month = 7)) // summer night, cooler than the room: still the heating
        assertTrue(acIsWrong(room, outside = 20.0, month = 11)) // mild autumn day: the AC
    }
}
