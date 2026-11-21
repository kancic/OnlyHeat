package ancic.karim.onlyheat

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTest {
    private fun room(id: String, current: Double, target: Double? = 21.0) =
        Room(id, "R$id", current, target, null, heating = false, smart = false, schedule = false)
    private fun home(vararg rooms: Room) = Home(rooms.toList(), null, null, null, null)

    @Test
    fun offlineReportedOnceThenBackOnline() {
        var (s, a) = watch(WatchState(), null, controllerOffline = true, coldBelow = null, now = 0)
        assertEquals(listOf<Alert>(Alert.Offline), a)
        a = watch(s, null, true, null, 900).second
        assertEquals(emptyList(), a) // still offline: no repeat
        a = watch(s, null, controllerOffline = false, coldBelow = null, now = 900).second
        assertEquals(emptyList(), a) // phone offline says nothing about the house
        a = watch(s, home(room("1", 20.0)), false, null, 1800).second
        assertEquals(listOf<Alert>(Alert.BackOnline), a)
    }

    @Test
    fun coldOnceWithHysteresis() {
        var (s, a) = watch(WatchState(), home(room("1", 11.5)), false, 12.0, 0)
        assertEquals(listOf<Alert>(Alert.Cold("R1", 11.5)), a)
        watch(s, home(room("1", 12.2)), false, 12.0, 900).let { (n, al) -> s = n; a = al }
        assertEquals(emptyList(), a) // above the limit but within 0.5: still the same incident
        watch(s, home(room("1", 12.6)), false, 12.0, 1800).let { (n, al) -> s = n; a = al }
        watch(s, home(room("1", 11.9)), false, 12.0, 2700).let { (n, al) -> s = n; a = al }
        assertEquals(listOf<Alert>(Alert.Cold("R1", 11.9)), a) // recovered, then cold again: new alert
    }

    @Test
    fun windowNeedsSharpDropCloseInTimeWhileHeating() {
        val s0 = watch(WatchState(), home(room("1", 21.0, 22.0)), false, null, 0).first
        assertEquals(listOf<Alert>(Alert.Window("R1", 2.0)), watch(s0, home(room("1", 19.0, 22.0)), false, null, 900).second)
        assertEquals(emptyList(), watch(s0, home(room("1", 20.0, 22.0)), false, null, 900).second) // small drop
        assertEquals(emptyList(), watch(s0, home(room("1", 19.0, 22.0)), false, null, 3 * 3600).second) // readings too far apart
        assertEquals(emptyList(), watch(s0, home(room("1", 19.0, null)), false, null, 900).second) // room is off
    }
}
