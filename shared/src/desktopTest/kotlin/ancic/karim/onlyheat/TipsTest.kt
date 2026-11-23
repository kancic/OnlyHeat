package ancic.karim.onlyheat

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
            val tour = listOf("room-card-pictures", "scheduled-room-locked", "away")
            s.putBoolean("tip.room-card-pictures", true)
            s.putBoolean("tip.scheduled-room-locked", true)
            s.putBoolean("tip.history-periods", true) // A tip from an older version.
            s.putString("authKey", "k")
            assertEquals(listOf("away"), unseenTips(s, tour))
            resetTips(s)
            assertEquals(setOf("authKey"), s.keys)
            assertEquals(tour, unseenTips(s, tour))
        } finally {
            node.removeNode()
        }
    }
}
