package ancic.karim.onlyheat

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTest {
    private fun room(id: String, current: Double, target: Double? = 21.0, heating: Boolean = false) =
        Room(id, "R$id", current, target, null, heating, smart = false, schedule = false)
    private fun home(vararg rooms: Room, outside: Double? = null) = Home(rooms.toList(), outside, null, null, null)

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

    @Test
    fun notWarmingAfterTwoHoursWithoutRise() {
        // a check every 15 minutes, room stuck at 18° with 21° set
        var s = WatchState()
        val seen = mutableListOf<Alert>()
        for (i in 0..8) watch(s, home(room("1", 18.0)), false, null, i * 900L).let { (n, a) -> s = n; seen += a }
        assertEquals(listOf<Alert>(Alert.NotWarming("R1", 18.0, 21.0)), seen) // once, at 2 h
        // warming up again clears it; slow but steady rise never triggers
        var w = WatchState()
        for (i in 0..16) watch(w, home(room("1", 15.0 + i * 0.1)), false, null, i * 900L).let { (n, a) -> w = n; assertEquals(emptyList(), a) }
    }

    @Test
    fun notWarmingRestartsAfterGap() {
        val s = watch(WatchState(), home(room("1", 18.0)), false, null, 0).first
        assertEquals(emptyList(), watch(s, home(room("1", 18.0)), false, null, 3 * 3600).second)
    }

    @Test
    fun recoveryFromALowerReadingRestartsTheWarmingTimer() {
        val temps = listOf(19.0, 18.0, 18.2, 18.4, 18.6, 18.8, 19.0, 19.1, 19.2)
        var state = WatchState()
        temps.forEachIndexed { i, temp ->
            val (next, alerts) = watch(state, home(room("1", temp)), false, null, i * 900L)
            state = next
            assertEquals(emptyList(), alerts)
        }
    }

    @Test
    fun coolingSkipsBothHeatingFaultsAndClearsTheirState() {
        val prev = WatchState(warmFrom = mapOf("1" to (18.0 to 0L)), lastAt = 6300)
        val cooling = home(room("1", 18.0).copy(cooling = true), room("2", 23.5, heating = true).copy(cooling = true))
        val (next, alerts) = watch(prev, cooling, false, null, 7200)
        assertEquals(emptyList(), alerts)
        assertEquals(emptyMap(), next.warmFrom)
        assertEquals(emptySet(), next.stuck)
    }

    @Test
    fun tooHotOnlyWhileValveOpen() {
        assertEquals(listOf<Alert>(Alert.TooHot("R1", 23.5, 21.0)), watch(WatchState(), home(room("1", 23.5, heating = true)), false, null, 0).second)
        assertEquals(emptyList(), watch(WatchState(), home(room("1", 23.5)), false, null, 0).second) // valve closed: just cooling down
        val s = watch(WatchState(), home(room("1", 23.5, heating = true)), false, null, 0).first
        assertEquals(emptyList(), watch(s, home(room("1", 22.6, heating = true)), false, null, 900).second) // same incident
    }

    @Test
    fun acTipOnceForAllWarmRoomsWhenItsHotOutside() {
        val hot = home(room("1", 27.0, null), room("2", 26.5, null), room("3", 24.0, null), outside = 31.0)
        val (s, a) = watch(WatchState(), hot, false, null, 0)
        assertEquals(listOf<Alert>(Alert.UseAc(hot.rooms.take(2), 31.0)), a)
        assertEquals(emptyList(), watch(s, hot, false, null, 900).second) // no repeat
        // cooler outside than inside: open a window instead, no AC tip
        assertEquals(emptyList(), watch(WatchState(), home(room("1", 27.0, null), outside = 22.0), false, null, 0).second)
        // a higher personal limit keeps 27° quiet
        assertEquals(emptyList(), watch(WatchState(), hot, false, null, 0, warmAbove = 28.0).second)
    }
}
