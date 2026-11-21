package ancic.karim.onlyheat

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
import java.util.concurrent.TimeUnit

/** One client for everything that runs without the UI: widget, its buttons and this worker. */
internal val backgroundApi by lazy { HeatApi() }

/** Ends due boosts and, when enabled, checks for alerts. Runs periodically and at each boost's end. */
class HeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        initHistory(applicationContext)
        savedAuthKey()?.let { key ->
            attempt { syncHistory(backgroundApi, key) } // no-op unless 6 h have passed
                .onFailure { android.util.Log.w("OnlyHeat", "history sync failed", it) }
        }
        if (alertsEnabled()) checkAlerts(backgroundApi).forEach { applicationContext.notifyHeat(it, alert = true) }
        val boostsDone = attempt { endExpiredBoosts(backgroundApi) }
            .onFailure { android.util.Log.w("OnlyHeat", "ending boosts failed", it) }.isSuccess
        refreshWidgets(applicationContext)
        return if (boostsDone || runAttemptCount >= 5) Result.success() else Result.retry()
    }
}

private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Every ~15 minutes (the system minimum) while alerts are on. */
fun Context.scheduleAlerts(on: Boolean) {
    val wm = WorkManager.getInstance(this)
    if (on) wm.enqueueUniquePeriodicWork(
        "alerts", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<HeatWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
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
