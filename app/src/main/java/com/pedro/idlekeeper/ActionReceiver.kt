package com.pedro.idlekeeper

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

/**
 * Receiver responsável por gerenciar a notificação fixa:
 * 1. Encerra o app/serviço ao clicar em "Encerrar".
 * 2. Recria a notificação imediatamente se ela for descartada.
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "ACTION_STOP_APP" -> {
                context?.let { ctx ->
                    // 1. Encerra o KeepAliveService
                    val serviceIntent = Intent(ctx, KeepAliveService::class.java)
                    ctx.stopService(serviceIntent)

                    // 2. Cancela a notificação fixa
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(KeepAliveService.NOTIF_ID)

                    // 3. Mata o processo do aplicativo
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            }
            "ACTION_RESTORE_NOTIFICATION" -> {
                // Se o usuário tentar remover a notificação deslizando, ela reaparece na hora
                context?.let { ctx ->
                    val serviceIntent = Intent(ctx, KeepAliveService::class.java)
                    ctx.startService(serviceIntent)
                }
            }
        }
    }
}
