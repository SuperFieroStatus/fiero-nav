package dev.mike.fieronav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service (type location) that keeps GPS and voice prompts running while the phone is
 * closed or another app is in front. Shows the next instruction in its notification.
 */
class NavService : Service() {

    private lateinit var nm: NotificationManager
    private var lastText = ""

    private val listener: (Nav.Snapshot) -> Unit = { s ->
        val text = when (s.status) {
            Nav.Status.GUIDING -> s.next?.let { "${Units.distance(s.distToNext)} · ${it.text}" } ?: "Guiding"
            Nav.Status.ARRIVED -> "Arrived"
            else -> s.message.ifEmpty { "Navigation" }
        }
        if (text != lastText) {
            lastText = text
            nm.notify(ID, build(s.destination?.name ?: "Fiero Nav", text))
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        startForeground(ID, build("Fiero Nav", "Starting…"))
        Nav.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        Nav.removeListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, GuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "nav"
        private const val ID = 1
    }
}
