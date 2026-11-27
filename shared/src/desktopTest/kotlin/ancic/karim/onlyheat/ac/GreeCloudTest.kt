package ancic.karim.onlyheat.ac

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Expected digests from Python's hashlib, as the reference client computes them. */
class GreeCloudTest {
    private val r = 1764201600L // 2025-11-27 00:00:00 UTC

    @Test
    fun loginIsSignedLikeTheReferenceClient() {
        val body = accountBody(r, { t ->
            mapOf("user" to JsonPrimitive("me@x.hr"), "psw" to JsonPrimitive(loginHash("secret", t)), "t" to JsonPrimitive(t))
        }, listOf("user", "psw", "t"))
        val api = body["api"]!!.jsonObject
        assertEquals("2025-11-27 00:00:00", api["t"]!!.jsonPrimitive.content)
        assertEquals("e4bb676e15236e46fea96d83eca67ab4", api["vc"]!!.jsonPrimitive.content)
        assertEquals("47b0b2d3db7b5b25b80220299a6f97ac", body["psw"]!!.jsonPrimitive.content)
        assertEquals("432ef5919377a3c13ea12dd6e1fbff78", body["datVc"]!!.jsonPrimitive.content)
    }

    @Test
    fun arraysAndNumbersAreSignedAsText() {
        val body = accountBody(r, {
            mapOf("token" to JsonPrimitive("tok"), "uid" to JsonPrimitive(42), "macs" to JsonArray(listOf(JsonPrimitive("f4911ed454bb"), JsonPrimitive("c8f742aa1122"))))
        }, listOf("token", "uid", "macs"))
        assertEquals("4e66b73115849e31ae31c90d1779841e", body["datVc"]!!.jsonPrimitive.content)
        assertEquals("42", body["uid"].toString()) // the uid stays a number on the wire
    }

    @Test
    fun oldModulesUseTheGateway() {
        assertFalse(usesMqtt("V2.0.0"))
        assertFalse(usesMqtt("1.45"))
        assertTrue(usesMqtt("V3.2.1"))
        assertTrue(usesMqtt("V10.0"))
        assertTrue(usesMqtt(null))
        assertTrue(usesMqtt("unknown"))
    }

    @Test
    fun mqttPackets() {
        assertContentEquals(byteArrayOf(0), mqttLength(0))
        assertContentEquals(byteArrayOf(0x7F), mqttLength(127))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), mqttLength(128))
        assertContentEquals(byteArrayOf(0xC1.toByte(), 0x02), mqttLength(321))
        // CONNECT for client "c", user "1", password "p": 10 bytes of header, then three strings.
        assertContentEquals(
            byteArrayOf(0x10, 19, 0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 4, 0xC2.toByte(), 0, 60,
                0, 1, 'c'.code.toByte(), 0, 1, '1'.code.toByte(), 0, 1, 'p'.code.toByte()),
            mqttConnect("c", "1", "p"),
        )
        val body = mqttString("response/ab/x") + byteArrayOf(0, 7) + "{}".encodeToByteArray()
        val (topic, payload) = mqttPublished(0x32, body) // QoS 1: a packet id before the payload
        assertEquals("response/ab/x", topic)
        assertEquals("{}", payload.decodeToString())
        assertEquals("{}", mqttPublished(0x30, mqttString("t") + "{}".encodeToByteArray()).second.decodeToString())
    }

    @Test
    fun cloudRouteDoesNotChangeEquality() {
        val ac = LinkedAc("gree", "f4911ed454bb", "192.168.100.53", "k")
        val cloud = ac.throughCloud()
        assertTrue(cloud.viaCloud && !ac.viaCloud)
        assertEquals(ac, cloud) // the isCurrent checks compare against the stored pairing
    }
}
