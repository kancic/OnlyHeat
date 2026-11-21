package ancic.karim.onlyheat

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private fun p(min: Int, cur: Double, tgt: Double?) = Point(1_000_000L + min * 60, cur, tgt)

    @Test fun belowTargetAndHeatUp() {
        val pts = listOf(
            p(0, 22.0, 22.0), p(5, 22.0, 22.0),
            p(10, 22.0, 23.0), p(15, 22.4, 23.0), p(20, 22.9, 23.0), // raised to 23, reached (within 0.2) at 20
            p(25, 23.0, 23.0),
        )
        assertEquals(ChartStats(belowSeconds = 10 * 60, heatUpSeconds = 10 * 60), chartStats(pts))
    }

    @Test fun noiseIsNotBelowAndUnreachedRaiseHasNoHeatUp() {
        val pts = listOf(p(0, 22.9, 23.0), p(5, 22.85, 23.0), p(10, 22.0, 24.0), p(15, 22.5, 24.0))
        assertEquals(ChartStats(belowSeconds = 5 * 60, heatUpSeconds = null), chartStats(pts))
    }

    @Test fun holeInTheDataIsNotCountedAsBelow() {
        // 5-min readings, then the app wasn't run for two days.
        val pts = listOf(p(0, 20.0, 23.0), p(5, 20.0, 23.0), p(10, 20.0, 23.0), p(2890, 23.0, 23.0), p(2895, 23.0, 23.0))
        assertEquals(10 * 60L, chartStats(pts).belowSeconds)
    }
}
