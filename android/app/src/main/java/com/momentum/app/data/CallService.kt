package com.momentum.app.data

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps a call going while the app is in the background or the screen is off (Android stops the microphone and
 * the camera of apps without one): a foreground service with the ongoing call's notification (hang up from there).
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val v = Calls.view
        if (v == null) { stopSelf(); return START_NOT_STICKY }
        val name = v.info.title.ifBlank { v.title }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (v.cam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) else 0
        runCatching { ServiceCompat.startForeground(this, Notifier.ONGOING_ID, Notifier.ongoingCall(this, name, v.info.video || v.cam), type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CallService::class.java)) }
        }
    }
}

/** The buttons in a call's notification: decline the ringing one, hang up the ongoing one. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            HANG_UP -> Calls.hangUp()
            DECLINE -> {
                val id = intent.getIntExtra(Notifier.EXTRA_CALL, 0)
                Notifier.cancelCall(ctx, id)
                if (Calls.isRinging(id)) { Calls.decline(id); return }
                // the app isn't running: tell the server with the saved session
                val prefs = Prefs(ctx)
                val token = prefs.token ?: return
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { Api(prefs.serverUrl, token, prefs.language).raw("POST", "/calls/$id/decline") }
                    done.finish()
                }
            }
        }
    }

    companion object {
        const val DECLINE = "com.momentum.app.CALL_DECLINE"
        const val HANG_UP = "com.momentum.app.CALL_HANG_UP"
    }
}
