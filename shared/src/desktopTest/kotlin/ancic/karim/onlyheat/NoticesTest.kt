package ancic.karim.onlyheat

import kotlin.test.Test
import kotlin.test.assertEquals

class NoticesTest {
    private fun room(id: String, current: Double?, target: Double?, heating: Boolean = false, cooling: Boolean = false) =
        Room(id, id, current, target, null, heating, smart = false, schedule = false, cooling = cooling)

    private fun keys(
        rooms: List<Room>, watched: WatchState? = null, outside: Double? = null, acs: Map<String, AcState> = emptyMap(),
        shown: Set<String> = emptySet(),
    ) = roomIssues(rooms, watched, outside, AC_ABOVE, shown) { acs[it] }.map { it.key }

    private val cooling = AcState(power = true, mode = AcMode.Cool, target = 22, fan = AcFan.Auto)

    @Test fun windowAndNotWarmingNeedTheBackgroundCheckAndStillBeingCold() {
        val watched = WatchState(window = setOf("a", "b"), stuck = setOf("a", "c"))
        val rooms = listOf(
            room("a", 18.0, 21.0), // still cold: both
            room("b", 21.5, 21.0), // warm again: the window notice is gone before the next check
            room("c", 20.7, 21.0), // within half a degree: not "not warming" any more
        )
        assertEquals(listOf("window-a", "stuck-a"), keys(rooms, watched))
        assertEquals(emptyList(), keys(rooms, watched = null)) // alerts off or stale
        assertEquals(emptyList(), keys(listOf(room("a", 18.0, null)), watched)) // switched off
    }

    @Test fun tooHotOnlyWhileTheValveIsOpen() {
        assertEquals(listOf("hot-a"), keys(listOf(room("a", 23.0, 21.0, heating = true))))
        assertEquals(emptyList(), keys(listOf(room("a", 23.0, 21.0, heating = false)))) // sun, not a stuck valve
        assertEquals(emptyList(), keys(listOf(room("a", 22.9, 21.0, heating = true))))
        assertEquals(emptyList(), keys(listOf(room("a", 23.0, 21.0, heating = true, cooling = true)))) // as the background check
    }

    @Test fun liveRecoveryClearsWarningsWhileStillBelowTarget() {
        val watched = WatchState(
            window = setOf("a"), stuck = setOf("a"),
            last = mapOf("a" to 18.1), warmFrom = mapOf("a" to (18.0 to 1L)),
        )
        assertEquals(listOf("window-a", "stuck-a"), keys(listOf(room("a", 18.1, 21.0)), watched))
        assertEquals(listOf("stuck-a"), keys(listOf(room("a", 18.2, 21.0)), watched))
        assertEquals(emptyList(), keys(listOf(room("a", 18.3, 21.0)), watched))
    }

    @Test fun acTipWhenTooWarmAndAtLeastAsWarmOutside() {
        val warm = listOf(room("a", 27.0, null))
        assertEquals(listOf("use-ac-a"), keys(warm, outside = 30.0))
        assertEquals(emptyList(), keys(warm, outside = 24.0)) // a window helps
        assertEquals(emptyList(), keys(warm, outside = null))
        assertEquals(emptyList(), keys(listOf(room("a", 25.5, null)), outside = 30.0)) // under Too warm
        assertEquals(emptyList(), keys(warm, outside = 30.0, acs = mapOf("a" to cooling))) // already cooling
        val off = cooling.copy(power = false)
        val tip = roomIssues(warm, null, 30.0, AC_ABOVE) { off }.single() as RoomIssue.UseAc
        assertEquals(22, tip.coolingTarget)
    }

    @Test fun acTipChecksTheSetpointItWouldSend() {
        val off = cooling.copy(power = false, target = 28)
        val acs = mapOf("a" to off)
        assertEquals(emptyList(), keys(listOf(room("a", 27.0, 26.0)), outside = 30.0, acs = acs))
        assertEquals(emptyList(), keys(listOf(room("a", 27.0, 23.0)), outside = 30.0, acs = mapOf("a" to cooling.copy(power = false))))
        val tip = roomIssues(listOf(room("a", 27.0, 25.0)), null, 30.0, AC_ABOVE) { off }.single() as RoomIssue.UseAc
        assertEquals(25, tip.coolingTarget)
    }

    @Test fun missingWeatherKeepsOnlyTipsThatStillQualifyIndoors() {
        val shown = setOf("use-ac-a", "use-ac-b", "use-ac-c", "use-ac-d")
        val rooms = listOf(
            room("a", 25.6, null),
            room("b", 25.4, null),
            room("c", 27.0, 26.0),
            room("d", 27.0, null),
            room("e", 27.0, null),
        )
        val acs = mapOf("c" to cooling.copy(power = false), "d" to cooling)
        val pending = roomIssues(rooms, null, null, AC_ABOVE, shown) { acs[it] }.single() as RoomIssue.UseAc
        assertEquals("use-ac-a", pending.key)
        assertEquals(null, pending.outside)
        assertEquals(listOf("use-ac-b"), keys(listOf(room("b", 27.0, null)), outside = 30.0, shown = setOf(pending.key)))
    }

    @Test fun fightNeedsALinkedAcCooling() {
        val heated = listOf(room("a", 22.0, 23.0, heating = true))
        assertEquals(listOf("fight-a"), keys(heated, acs = mapOf("a" to cooling)))
        assertEquals(emptyList(), keys(heated))
    }

    @Test fun shownNoticesClearHalfADegreeLater() {
        val hot = listOf(room("a", 22.6, 21.0, heating = true))
        assertEquals(emptyList(), keys(hot))
        assertEquals(listOf("hot-a"), keys(hot, shown = setOf("hot-a")))
        val warm = listOf(room("a", 25.6, null))
        assertEquals(emptyList(), keys(warm, outside = 25.6))
        assertEquals(listOf("use-ac-a"), keys(warm, outside = 25.6, shown = setOf("use-ac-a")))
        assertEquals(listOf("use-ac-a"), keys(listOf(room("a", 26.0, null)), outside = 25.6, shown = setOf("use-ac-a")))
    }

    @Test fun fightingRoomIsNotAlsoBlamedOnTheValve() {
        assertEquals(listOf("fight-a"), keys(listOf(room("a", 25.0, 23.0, heating = true)), acs = mapOf("a" to cooling)))
    }
}
