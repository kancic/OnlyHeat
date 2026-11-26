package ancic.karim.onlyheat.data

import com.russhwolf.settings.PreferencesSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class LoginDashboardTest {
    @Test fun logoutClearsOnlyTheCurrentSessionsControllerState() {
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val settings = PreferencesSettings(node)
            savePairing(settings, Pairing("current", "controller"))
            settings.putDouble("allTarget", 24.0)
            settings.putDouble("lastTarget.1", 23.0)
            saveAway(settings, mapOf("1" to 23.0))
            putBoost(settings, Boost("1", 1L, 24.0, 22.0))
            settings.putString("theme", "dark")

            assertFalse(clearSession(settings, "old"))
            assertEquals(mapOf("1" to 23.0), awaySaved(settings))
            assertTrue(clearSession(settings, "current"))
            assertEquals(setOf("theme"), settings.keys)
        } finally {
            node.removeNode()
        }
    }

    @Test fun localCredentialsAreSavedOnlyForTheAcceptedApiPin() {
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val settings = PreferencesSettings(node)
            val selected = DashboardTarget("192.168.100.49", "TTO Advance-A1B2C3")
            val pending = VerifiedDashboard(selected, "1234", "apiA")
            assertNull(settings.savedLanTarget())
            assertNull(savedPin(settings))

            settings.saveLoginDashboard("apiA", pending)
            assertEquals(selected, settings.savedLanTarget())
            assertEquals("1234", savedPin(settings))

            settings.saveLoginDashboard("apiB", pending)
            assertNull(settings.savedLanTarget())
            assertNull(savedPin(settings))
        } finally {
            node.removeNode()
        }
    }

    @Test fun remembersTheHotspotIdentityAndKeepsTheSameControllersHomeAddress() {
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val settings = PreferencesSettings(node)
            val home = DashboardTarget("192.168.100.49", "TTO Advance-A1B2C3")
            settings.rememberLanTarget(home, HOTSPOT)
            settings.rememberLanTarget(home.copy(host = HOTSPOT), HOTSPOT)
            assertEquals(home, settings.savedLanTarget())

            val other = DashboardTarget(HOTSPOT, "TTO Advance-ABC123")
            settings.rememberLanTarget(other, HOTSPOT)
            assertEquals(other, settings.savedLanTarget())
        } finally {
            node.removeNode()
        }
    }

    @Test fun firmwareChangeIsReportedOnce() {
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val settings = PreferencesSettings(node)
            settings.noteFirmware("1.0.7.0.8")
            assertNull(firmwareChange(settings)) // first sighting is not a change
            settings.noteFirmware("1.0.9.2.0")
            assertEquals("1.0.7.0.8" to "1.0.9.2.0", unnotifiedFirmwareChange(settings))
            markFirmwareNotified(settings, "1.0.9.2.0")
            assertNull(unnotifiedFirmwareChange(settings)) // the next check doesn't notify again
            settings.noteFirmware("1.0.9.3.0") // a second update before the notice is closed
            assertEquals("1.0.7.0.8" to "1.0.9.3.0", unnotifiedFirmwareChange(settings))
            settings.noteFirmware("1.0.7.0.8") // rolled back to what the user last saw: nothing changed
            assertNull(firmwareChange(settings))
            settings.noteFirmware("1.0.9.2.0")
            settings.closeFirmwareChange()
            assertNull(firmwareChange(settings))
        } finally {
            node.removeNode()
        }
    }
}
