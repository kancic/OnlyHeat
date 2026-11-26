package ancic.karim.onlyheat

import ancic.karim.onlyheat.data.Update
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL


/** The installed version for the update check; null in debug builds, which never update themselves. */
fun Context.updatableVersion(): String? =
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) null
    else packageManager.getPackageInfo(packageName, 0).versionName

/**
 * Downloads [update]'s APK and hands it to the system installer; [UpdateReceiver] gets the outcome.
 * Android 12+ installs without asking once OnlyHeat installed itself the first time; until then it asks.
 */
suspend fun Context.installUpdate(update: Update, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
    val apk = update.urls.find { it.endsWith(".apk") } ?: return@withContext
    val installer = packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            // GitHub redirects https -> https, which HttpURLConnection follows.
            val download = URL(apk).openConnection()
            download.getInputStream().use { input ->
                val total = download.contentLengthLong // of the final response, after the redirect
                session.openWrite("OnlyHeat.apk", 0, total).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer).takeIf { it >= 0 } ?: break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) withContext(Dispatchers.Main) { onProgress(done.toFloat() / total) }
                    }
                    session.fsync(out)
                }
            }
            // Mutable: the installer adds the status extras.
            val status = PendingIntent.getBroadcast(
                this@installUpdate, id, Intent(this@installUpdate, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    } catch (e: Exception) {
        installer.abandonSession(id)
        throw e
    }
}

/** Installer results. When Android wants the user to confirm: the prompt opens if the app is on screen, else a notification leads to it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") // the typed overload needs API 33
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            if (me.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) context.startActivity(confirm)
            else context.notifyHeat(context.getString(R.string.update_ready), open = confirm)
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            // e.g. STATUS_FAILURE_CONFLICT: the installed app is signed with another key (an old debug install).
            android.util.Log.w("OnlyHeat", "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
