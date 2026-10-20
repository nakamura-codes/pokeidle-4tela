package com.pedro.idlekeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.Html
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Serviço em primeiro plano: notificação fixa + WakeLock + WifiLock.
 * A notificação permanece travada até o encerramento explícito pelo botão ou fechamento do app.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireLocks()
        return START_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pokeidlekeeper:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "pokeidlekeeper:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Botão "Encerrar" da notificação
        val stopIntent = Intent(this, ActionReceiver::class.java).apply {
            action = "ACTION_STOP_APP"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Se o usuário tentar deslizar/apagar a notificação, este evento a restaura na hora
        val deleteIntent = Intent(this, ActionReceiver::class.java).apply {
            action = "ACTION_RESTORE_NOTIFICATION"
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, 2, deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val titleFormatted = Html.fromHtml(
            "<b><font color='#FF4500'>PokeIdle</font></b> • <font color='#00E676'><b>Farm Ativo ⚡</b></font>",
            Html.FROM_HTML_MODE_LEGACY
        )

        val textFormatted = Html.fromHtml(
            "Status: <font color='#FFD700'><b>Em Execução</b></font>",
            Html.FROM_HTML_MODE_LEGACY
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(titleFormatted)
            .setContentText(textFormatted)
            .setColor(0xFFE53935.toInt())
            .setContentIntent(open)
            .setDeleteIntent(deletePendingIntent) // Restaura automaticamente se for removida
            .setOngoing(true)                      // Torna a notificação fixa
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stopPendingIntent)

        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PokeIdle Farm Ativo", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pokeidle_farm_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.pedro.idlekeeper.STOP"
    }
}
