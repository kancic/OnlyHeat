package ancic.karim.onlyheat.feature.history

import ancic.karim.onlyheat.data.DaySummary
import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.Point
import ancic.karim.onlyheat.data.ReadingDao
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.nowSeconds
import ancic.karim.onlyheat.data.outsideId
import ancic.karim.onlyheat.data.point
import ancic.karim.onlyheat.data.syncHistory
import ancic.karim.onlyheat.ui.errorText
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.russhwolf.settings.Settings
import kotlin.time.Duration.Companion.minutes

/** Week/month readings stored on this device: per room, outside, and the oldest saved day. */
internal class Stored(val seconds: Long, val rooms: Map<String, List<Point>>, val outside: List<Point>, val since: Long?)

/** The History tab's data. Outlives tab switches and room screens like [HomeViewModel], so coming back shows what
 *  was there while it refreshes, instead of loading from scratch. */
internal class HistoryViewModel(
    private val api: HeatApi, private val history: ReadingDao, private val settings: Settings, private val key: String,
) : ViewModel() {
    var days by mutableStateOf<List<DaySummary>?>(null) // the last 24 h, from the server
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var stored by mutableStateOf<Stored?>(null) // week/month, set in one go so it's never half set
        private set

    suspend fun loadDay() {
        error = null
        attempt { days = api.last24h(key) }.onFailure { error = errorText(it) }
    }

    /** What this device has for [seconds] back, right away, then again once topped up from the server. */
    suspend fun loadStored(ids: List<String>, seconds: Long, bucket: Long) {
        suspend fun read() {
            val from = nowSeconds() - seconds
            stored = Stored(
                seconds,
                ids.associateWith { id -> history.averaged(id, from, bucket).map { it.point() } },
                history.averaged(outsideId(key, settings), from, bucket).map { it.point() },
                history.oldest(),
            )
        }
        read()
        attempt { syncHistory(api, history, key, settings, every = 30.minutes) }.onSuccess { read() }
    }
}
