package com.jacklugano.termvault.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jacklugano.termvault.MainActivity
import com.jacklugano.termvault.R
import com.jacklugano.termvault.ssh.SshSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service che tiene vivo il processo (e quindi le connessioni SSH)
 * mentre ci sono schede aperte, anche con l'app in background.
 */
@AndroidEntryPoint
class SshSessionService : Service() {

    @Inject lateinit var manager: SshSessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            manager.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE esiste solo da API 34; sotto,
        // 0 = usa i tipi dichiarati nel manifest (o nessuno su API < 29).
        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(manager.tabs.value.size),
            fgsType,
        )

        if (watchJob == null) {
            watchJob = scope.launch {
                manager.tabs.collectLatest { tabs ->
                    if (tabs.isEmpty()) {
                        stopSelf()
                    } else {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification(tabs.size))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sessioni SSH attive",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SshSessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TermVault")
            .setContentText(if (count == 1) "1 sessione SSH attiva" else "$count sessioni SSH attive")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnetti tutto", disconnectIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ssh_sessions"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT_ALL = "com.jacklugano.termvault.DISCONNECT_ALL"
    }
}
