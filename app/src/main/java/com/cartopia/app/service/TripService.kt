package com.cartopia.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cartopia.app.CartopiaApp
import com.cartopia.app.MainActivity
import com.cartopia.app.R
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while the app is off screen, so [com.cartopia.app.core.AppCore] goes on watching the gear and
 * recording the drive when the driver switches to the navigation app. It does no work of its own.
 *
 * It is started while the app is on screen (Android does not let a background app start one), and then stays for
 * as long as the process does; the notification shows what is being recorded.
 */
class TripService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val core = (application as CartopiaApp).core
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "주행 기록", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID, notification(null), type)
        scope.launch {
            core.state
                .map { s -> if (s.driving) s.trip?.let { "%.1f".format(it.distanceKm) } ?: "0.0" else null }
                .distinctUntilChanged()
                .collect { km -> manager.notify(ID, notification(km)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** [km] is the distance of the drive being recorded, or null while parked. */
    private fun notification(km: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (km != null) "주행 기록 중 · $km km" else "카토피아")
            .setContentText(if (km != null) "지나간 길을 따라 지도가 열리고 있어요" else "출발하면 주행을 기록해요")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val ID = 1

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TripService::class.java)) }
                .onFailure { Log.w("TripService", "not started", it) }
        }
    }
}
