package ancic.karim.onlyheat.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class MacDiscoveryTest {
    @Test fun onlyAnActiveWifiConnectionCanBeRemembered() {
        val summary = """
            <dictionary> {
              ConnectionID : 42
              InterfaceType : WiFi
              LinkStatusActive : TRUE
            }
        """.trimIndent()
        assertEquals(42uL, macWifiConnectionId(summary))
        assertNull(macWifiConnectionId(summary.replace("TRUE", "FALSE")))
        assertNull(macWifiConnectionId(summary.replace("WiFi", "Ethernet")))
        assertNull(macWifiConnectionId(summary.replace("42", "<redacted>")))
    }
}
