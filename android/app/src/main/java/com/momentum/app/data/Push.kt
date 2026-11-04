package com.momentum.app.data

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Real-time notifications via Firebase Cloud Messaging (server/push.py): the server sends a silent
 * wake-up for every new message / activity, the app then runs its usual update check and shows the
 * notifications itself (same texts, mute rules and "no doubles" as the background check, which
 * stays as the fallback).
 */
object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tell the server where to push (logged in, notifications on). */
    fun register(ctx: Context) {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> send(ctx, token) }
        }
    }

    internal fun send(ctx: Context, token: String) {
        val prefs = Prefs(ctx)
        val auth = prefs.token ?: return
        if (!prefs.notifications) return
        scope.launch {
            runCatching {
                Api(prefs.serverUrl, auth, prefs.language).raw("POST", "/push/register",
                    buildJsonObject { put("platform", "fcm"); put("token", token); put("lang", prefs.language) })
            }
        }
    }

    /** Logout / notifications off: this phone gets no more pushes for the account. */
    fun unregister(ctx: Context) {
        val prefs = Prefs(ctx)
        val auth = prefs.token ?: return
        val api = Api(prefs.serverUrl, auth, prefs.language)       // captured now: the token is cleared right after
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch { runCatching { api.raw("DELETE", "/push/register", buildJsonObject { put("token", token) }) } }
            }
        }
    }
}

class MomentumMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Push.send(applicationContext, token)

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        // a call (server/calls.py): ringing / its end, handled on the main thread (data/Calls.kt)
        if (d["type"] == "call" || d["type"] == "call_end") {
            val ctx = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).post { Calls.onPush(ctx, d) }
            return
        }
        // runs off the main thread; a high-priority message gives the app a few seconds of network
        runBlocking { withTimeoutOrNull(9_000) { checkUpdates(applicationContext) } }
    }
}
