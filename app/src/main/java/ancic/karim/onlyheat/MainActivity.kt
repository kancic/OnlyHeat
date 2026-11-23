package ancic.karim.onlyheat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import com.russhwolf.settings.Settings

class MainActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        scheduleHistorySync()
        scheduleAlerts(alertsEnabled(get<Settings>()))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Notifications tell the user when the controller ignored a change made just before leaving the app
        // or from the widget. Asked once; Settings is the place to change it later.
        val prefs = getPreferences(MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            App(
                ::requestLocalNetwork,
                notify = { notifyHeat(it) },
                wakeAt = { wakeAt(it) },
                backgroundAlerts = { on ->
                    scheduleAlerts(on)
                    // Alerts are useless without notifications: ask again if they were declined at first launch.
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            )
        }
    }

    // Leaving the app: the widget should show what was just changed (or that we logged out).
    // GlobalScope: a short write that must survive the activity finishing right after onStop.
    @OptIn(DelicateCoroutinesApi::class)
    override fun onStop() {
        super.onStop()
        GlobalScope.launch { refreshWidgets(applicationContext) }
    }
}
