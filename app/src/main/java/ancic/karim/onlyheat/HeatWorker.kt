package ancic.karim.onlyheat

import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.ReadingDao
import ancic.karim.onlyheat.data.alertsEnabled
import ancic.karim.onlyheat.data.attempt
import ancic.karim.onlyheat.data.checkAlerts
import ancic.karim.onlyheat.data.endExpiredBoosts
import ancic.karim.onlyheat.data.markFirmwareNotified
import ancic.karim.onlyheat.data.savedAuthKey
import ancic.karim.onlyheat.data.syncHistory
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.russhwolf.settings.Settings
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit


/** Ends due boosts and, when enabled, checks for alerts. Runs periodically and at each boost's end. */
class HeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val api: HeatApi by inject()
    private val history: ReadingDao by inject()
    private val settings: Settings by inject()

    override suspend fun doWork(): Result {
        val key = savedAuthKey(settings)
        key?.let {
            attempt { syncHistory(api, history, it, settings) } // no-op unless 6 h have passed
                .onFailure { android.util.Log.w("OnlyHeat", "history sync failed", it) }
        }
        if (alertsEnabled(settings)) checkAlerts(api, settings).forEach {
            val posted = applicationContext.notifyHeat(it.text, alert = true, acLinks = it.acLinks)
            if (posted) it.firmware?.let { v -> markFirmwareNotified(settings, v) }
        }
        val boostsDone = attempt { endExpiredBoosts(api, savedAuthKey(settings), settings) }
            .onFailure { android.util.Log.w("OnlyHeat", "ending boosts failed", it) }.isSuccess
        refreshWidgets(applicationContext)
        return if (boostsDone || runAttemptCount >= 5) Result.success() else Result.retry()
    }
}

private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Every ~20 minutes while alerts are on. UPDATE so a changed interval reaches phones that already have the job. */
fun Context.scheduleAlerts(on: Boolean) {
    val wm = WorkManager.getInstance(this)
    if (on) wm.enqueueUniquePeriodicWork(
        "alerts", ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<HeatWorker>(20, TimeUnit.MINUTES).setConstraints(online).build(),
    )
    else wm.cancelUniqueWork("alerts")
}

/** Every ~6 h, so the local history has no holes: the server only keeps ~25 h. */
fun Context.scheduleHistorySync() = WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "history", ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<HeatWorker>(6, TimeUnit.HOURS).setConstraints(online).build(),
)

/** Run once at [epochMillis], e.g. when a boost ends. */
fun Context.wakeAt(epochMillis: Long) {
    val delay = (epochMillis - System.currentTimeMillis()).coerceAtLeast(0)
    WorkManager.getInstance(this).enqueueUniqueWork(
        "wake-$epochMillis", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<HeatWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setConstraints(online).build(),
    )
}
