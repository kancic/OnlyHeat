package ancic.karim.onlyheat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

internal const val ALERTS_CHANNEL = "alerts"

/** Posts a notification that opens the app; returns false (posting nothing) without the notification permission.
 *  [acLinks]: the pairings a "Turn on AC" button cools (the AC tip). [open]: what a tap opens instead of the app. */
fun Context.notifyHeat(text: String, alert: Boolean = false, acLinks: List<String> = emptyList(), open: Intent? = null): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    val nm = getSystemService(NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) return false
    // Two channels so alerts (offline, cold, window, not warming, too hot, AC) can stay loud while setpoint notes are muted, or vice versa.
    val channel = if (alert) ALERTS_CHANNEL else "setpoints"
    nm.createNotificationChannel(
        if (alert) NotificationChannel(channel, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        else NotificationChannel(channel, getString(R.string.channel_setpoints), NotificationManager.IMPORTANCE_DEFAULT)
    )
    if (nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false // turned off by the user
    // UPDATE_CURRENT: a newer update prompt replaces the extras of the previous one.
    val tap = PendingIntent.getActivity(
        this, 0, open ?: Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val id = text.hashCode() // ponytail: same text replaces itself; a per-room id if rooms ever need separate entries
    nm.notify(
        id,
        Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .apply {
                if (acLinks.isNotEmpty()) {
                    val turnOn = Intent(this@notifyHeat, AcReceiver::class.java)
                        .setData(Uri.fromParts("onlyheat-ac", acLinks.joinToString(","), null))
                        .putExtra(AcReceiver.LINKS, acLinks.toTypedArray()).putExtra(AcReceiver.NOTIFICATION, id)
                    val action = PendingIntent.getBroadcast(
                        this@notifyHeat, id, turnOn, PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(Notification.Action.Builder(null, getString(R.string.ac_turn_on), action).build())
                }
            }
            .build(),
    )
    return true
}
