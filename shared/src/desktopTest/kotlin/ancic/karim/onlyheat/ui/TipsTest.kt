package ancic.karim.onlyheat.ui

import com.russhwolf.settings.PreferencesSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals


class TipsTest {
    @Test fun resetClearsOnlyTips() {
        val node = Preferences.userRoot().node("onlyheat-test-${UUID.randomUUID()}")
        try {
            val s = PreferencesSettings(node)
            val tour = listOf("room-card-pictures", "away", "ac", "ac-cloud")
            s.putBoolean("tip.room-card-pictures", true)
            s.putBoolean("tip.away", true)
            s.putBoolean("tip.ac", true)
            s.putBoolean("tip.scheduled-room-locked", true) // A tip from an older version.
            s.putString("authKey", "k")
            assertEquals(listOf("ac-cloud"), unseenTips(s, tour))
            resetTips(s)
            assertEquals(setOf("authKey"), s.keys)
            assertEquals(tour, unseenTips(s, tour))
        } finally {
            node.removeNode()
        }
    }
}
