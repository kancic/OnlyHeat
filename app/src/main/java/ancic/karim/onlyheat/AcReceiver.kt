package ancic.karim.onlyheat

import ancic.karim.onlyheat.ac.coolRooms
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.russhwolf.settings.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


/** "Turn on AC" on the AC tip notification: cools those rooms without opening the app. */
class AcReceiver : BroadcastReceiver(), KoinComponent {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val links = intent.getStringArrayExtra(LINKS)?.toList().orEmpty()
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(NOTIFICATION, 0))
        val done = goAsync()
        GlobalScope.launch(Dispatchers.Main) {
            try {
                if (!coolRooms(get<Settings>(), links)) context.notifyHeat(context.getString(R.string.ac_turn_on_failed), alert = true)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val LINKS = "acLinks"
        const val NOTIFICATION = "notification"
    }
}
