package ancic.karim.onlyheat

import com.russhwolf.settings.PreferencesSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginDashboardTest {
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
}
