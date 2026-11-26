package ancic.karim.onlyheat

import ancic.karim.onlyheat.data.verboseHttpLogs
import android.app.Application
import android.content.pm.ApplicationInfo
import org.koin.android.ext.koin.androidContext


/** Starts Koin before any entry point (activity, widget, worker) can ask for a dependency. */
class OnlyHeatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        verboseHttpLogs = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 // before HeatApi is built
        initKoin { androidContext(this@OnlyHeatApp) }
    }
}
