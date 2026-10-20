package com.pedro.idlekeeper

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

/**
 * Receiver responsável por capturar o clique do botão "Encerrar" na notificação,
 * parar o serviço em segundo plano e finalizar o processo do app.
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "ACTION_STOP_APP") {
            context?.let { ctx ->
                // 1. Para o serviço KeepAlive
                val serviceIntent = Intent(ctx, KeepAliveService::class.java)
                ctx.stopService(serviceIntent)

                // 2. Remove a notificação da barra (ID 1001)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(KeepAliveService.NOTIF_ID)

                // 3. Encerra o processo do app por completo
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }
        }
    }
}
