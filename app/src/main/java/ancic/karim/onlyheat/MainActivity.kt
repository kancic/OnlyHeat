package ancic.karim.onlyheat

import ancic.karim.onlyheat.data.alertsEnabled
import ancic.karim.onlyheat.data.savedAuthKey
import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import android.provider.Settings.EXTRA_APP_PACKAGE
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.core.view.WindowCompat
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get


internal const val SCHEDULE_TIP_EXTRA = "ancic.karim.onlyheat.SCHEDULE_TIP"

class MainActivity : ComponentActivity() {
    private val scheduleTipRequests = Channel<Unit>(Channel.CONFLATED)
    private val scheduleTips = scheduleTipRequests.receiveAsFlow()
    private var pending: CompletableDeferred<Boolean>? = null
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.complete(it)
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Android 17 (API 37) gates LAN access behind a runtime permission; the schedule editor needs it.
    private suspend fun requestLocalNetwork(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        // Concurrent callers share one dialog. After a recreation the old callers' scopes are cancelled anyway.
        val ask = pending?.takeIf { it.isActive }
            ?: CompletableDeferred<Boolean>().also { pending = it; askLocalNetwork.launch(permission) }
        return ask.await()
    }

    private fun hasLocalNetwork() = Build.VERSION.SDK_INT < 37 ||
        checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(ALERTS_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ponytail: leftover of the old one-time prompt; drop this line once everyone has updated.
        getSharedPreferences(MainActivity::class.java.simpleName, MODE_PRIVATE).edit().remove("askedNotifications").apply()
        scheduleHistorySync()
        scheduleAlerts(alertsEnabled(get<Settings>()))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestScheduleTip(intent)
        setContent {
            App(
                ::requestLocalNetwork,
                notify = { notifyHeat(it) },
                wakeAt = { wakeAt(it) },
                backgroundAlerts = { on ->
                    scheduleAlerts(on)
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                // Denied, revoked or no longer asked: the Rooms tab shows a notice that leads here.
                notificationsAllowed = ::notificationsAllowed,
                openNotificationSettings = {
                    startActivity(Intent(ACTION_APP_NOTIFICATION_SETTINGS).putExtra(EXTRA_APP_PACKAGE, packageName))
                },
                dynamicScheme = { dark ->
                    if (Build.VERSION.SDK_INT < 31) null
                    else if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                },
                statusBarOnDark = { onDark ->
                    // false restores what enableEdgeToEdge picked: dark icons in light mode, light in dark mode.
                    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                        !onDark && !resources.configuration.isNightModeActive
                },
                scheduleTipRequests = scheduleTips,
                hasLocalNetwork = ::hasLocalNetwork,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestScheduleTip(intent)
    }

    // Recents relaunches with the widget's intent, and a request while logged out would wait for the next login.
    private fun requestScheduleTip(intent: Intent) {
        if (intent.getBooleanExtra(SCHEDULE_TIP_EXTRA, false) &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
            savedAuthKey(get<Settings>()) != null
        ) scheduleTipRequests.trySend(Unit)
    }

    // Leaving the app: the widget should show what was just changed (or that we logged out).
    // GlobalScope: a short write that must survive the activity finishing right after onStop.
    @OptIn(DelicateCoroutinesApi::class)
    override fun onStop() {
        super.onStop()
        GlobalScope.launch { refreshWidgets(applicationContext) }
    }
}
