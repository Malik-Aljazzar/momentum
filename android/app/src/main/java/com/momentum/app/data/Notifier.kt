package com.momentum.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.momentum.app.MainActivity
import com.momentum.app.R
import com.momentum.app.fmt
import com.momentum.app.ui.Lang
import com.momentum.app.ui.stringsFor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/**
 * System notifications for new messages and activity, without a push service: a background job
 * asks the server every ~15 minutes what's new (GET /api/updates, nothing is marked as read)
 * and remembers the newest ids. What the user already saw in the open app isn't notified.
 */
object Notifier {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_ACTIVITY = "activity"
    const val CHANNEL_MENTIONS = "mentions"
    /** calls (server/calls.py): ringing with the phone's ringtone; the ongoing call quietly */
    const val CHANNEL_CALLS = "calls"
    const val CHANNEL_CALL_ONGOING = "call_ongoing"
    const val EXTRA_CALL = "open_call"
    const val EXTRA_ANSWER = "answer_call"
    const val EXTRA_CHAT = "open_chat"
    const val EXTRA_ACTIVITY = "open_activity"
    private const val WORK = "momentum-updates"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val de = Prefs(ctx).language == "de"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, if (de) "Nachrichten" else "Messages",
            NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ACTIVITY, if (de) "Aktivität" else "Activity",
            NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MENTIONS, if (de) "Erwähnungen" else "Mentions",
            NotificationManager.IMPORTANCE_HIGH))
        val S = stringsFor(if (de) Lang.DE else Lang.EN)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, S.callsChannel, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 700, 500, 700, 500, 700)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CALL_ONGOING, S.callOngoing, NotificationManager.IMPORTANCE_LOW))
    }

    private fun callTag(id: Int) = "call:$id"
    const val ONGOING_ID = 4711

    /** Someone calls while the app isn't on screen (FCM, data/Push.kt): the phone rings, full screen when locked. */
    @android.annotation.SuppressLint("MissingPermission")
    fun incomingCall(ctx: Context, id: Int, name: String, group: String, video: Boolean) {
        ensureChannels(ctx)
        if (!allowed(ctx)) return
        val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
        val title = if (group.isNotBlank()) S.callFromIn(name, group) else name
        val person = androidx.core.app.Person.Builder().setName(title.ifBlank { "Momentum" }).setImportant(true).build()
        val open = open(ctx, 50_000 + id) { putExtra(EXTRA_CALL, id) }
        val answer = open(ctx, 60_000 + id) { putExtra(EXTRA_CALL, id); putExtra(EXTRA_ANSWER, true) }
        val decline = PendingIntent.getBroadcast(ctx, 70_000 + id,
            Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE).putExtra(EXTRA_CALL, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_momentum)
            .setContentTitle(title)
            .setContentText(if (video) S.callIncomingVideo else S.callIncomingVoice)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .setGroup(CHANNEL_CALLS)                      // not bundled with the chat / activity notifications
            .setTimeoutAfter(45_000)
            .build()
        // the channel's ringtone and vibration over and over (else once, ~4 s of buzzing - easily missed in a pocket)
        // until it's answered, declined, opened or over (cancelCall); the app's own ringing takes over when it opens
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(ctx).notify(callTag(id), 1, n)
    }

    /** It rang here and nobody answered: "missed call" (a tap opens the chat). */
    @android.annotation.SuppressLint("MissingPermission")
    fun missedCall(ctx: Context, id: Int, convId: Int, name: String, group: String, video: Boolean) {
        ensureChannels(ctx)
        if (!allowed(ctx)) return
        val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
        val what = if (group.isNotBlank()) S.missedGroupCall else if (video) S.missedVideoCall else S.missedVoiceCall
        val n = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_momentum)
            .setContentTitle(if (group.isNotBlank()) group else name)
            .setContentText(if (group.isNotBlank()) "$name: $what" else what)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(open(ctx, 80_000 + id) { putExtra(EXTRA_CHAT, convId) })
            .build()
        NotificationManagerCompat.from(ctx).notify(missedTag(convId), id, n)
    }

    private fun missedTag(convId: Int) = "missed:$convId"

    fun cancelCall(ctx: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(callTag(id), 1) }
    }

    /** The call that's on (CallService): who, since when; a tap opens it, "hang up" ends it. */
    fun ongoingCall(ctx: Context, name: String, video: Boolean): android.app.Notification {
        ensureChannels(ctx)
        val S = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)
        val person = androidx.core.app.Person.Builder().setName(name.ifBlank { "Momentum" }).build()
        val hangUp = PendingIntent.getBroadcast(ctx, 90_000,
            Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(ctx, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_momentum)
            .setContentTitle(name)
            .setContentText(S.callOngoing)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp).setIsVideo(video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setGroup(CHANNEL_CALLS)
            .setUsesChronometer(true)
            .setContentIntent(open(ctx, 91_000) { putExtra(EXTRA_CALL, -1) })
            .build()
    }

    /** Start (or keep) the background check; called when logged in with notifications on. */
    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<UpdatesWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        Push.register(ctx)                 // real time; the periodic check stays as the fallback
    }

    fun cancel(ctx: Context) {
        Push.unregister(ctx)
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(ctx).cancelAll()
    }

    /** A notification right now, to check that they arrive. */
    @android.annotation.SuppressLint("MissingPermission")
    fun test(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        if (!allowed(ctx)) return
        NotificationManagerCompat.from(ctx).notify(9999, NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_momentum).setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(open(ctx, 9999) {}).build())
    }

    private const val TAG_ACTIVITY = "activity"
    internal fun chatTag(convId: Int) = "chat:$convId"

    /** Read in the app: the chat's notifications (lines and mentions) go away. */
    fun clearChat(ctx: Context, convId: Int) = clearWhere(ctx) { tag, _ -> tag == chatTag(convId) || tag == missedTag(convId) }

    /** The activity list was looked at: its notifications go away. */
    fun clearActivity(ctx: Context) = clearWhere(ctx) { tag, _ -> tag == TAG_ACTIVITY }

    /** Everything the server no longer counts as unread (read here or on another device) goes away. */
    internal fun clearRead(ctx: Context, unreadChats: Set<Int>, unreadNotes: Set<Int>) = clearWhere(ctx) { tag, id ->
        when {
            tag == TAG_ACTIVITY -> id - 20_000 !in unreadNotes
            tag?.startsWith("chat:") == true -> tag.removePrefix("chat:").toIntOrNull() !in unreadChats
            else -> false
        }
    }

    private fun clearWhere(ctx: Context, gone: (String?, Int) -> Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications }.getOrNull()?.forEach { sb ->
            if (gone(sb.tag, sb.id)) nm.cancel(sb.tag, sb.id)
        }
    }

    internal const val ACTIVITY_TAG = TAG_ACTIVITY

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun open(ctx: Context, request: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(extras)
        return PendingIntent.getActivity(ctx, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

@Serializable
private data class UpdateMessage(
    val id: Int, @SerialName("conversation_id") val conversationId: Int, val group: Boolean = false,
    val title: String = "", val sender: String = "", val text: String = "", val media: Boolean = false,
    val share: String? = null,
    @SerialName("media_kind") val mediaKind: String? = null,
    /** the message tags me */
    val mention: Boolean = false,
    /** encrypted: the preview for me, opened on the phone */
    val e2e: ReaderEnvelope? = null,
)

@Serializable
private data class UpdateChallenge(val title: String = "", val rank: Int? = null, val reward: Double = 0.0)

@Serializable
private data class UpdateNotification(
    val id: Int, val kind: String, val actor: String = "", val challenge: UpdateChallenge? = null,
    val rule: com.momentum.app.data.NamedRef? = null, val group: com.momentum.app.data.NamedRef? = null,
)

@Serializable
private data class Updates(
    val messages: List<UpdateMessage> = emptyList(),
    val notifications: List<UpdateNotification> = emptyList(),
    @SerialName("latest_message_id") val latestMessageId: Int = 0,
    @SerialName("latest_notification_id") val latestNotificationId: Int = 0,
    /** still unread (null from an older server: don't clear anything) */
    @SerialName("unread_chats") val unreadChats: List<Int>? = null,
    @SerialName("unread_notifications") val unreadNotifications: List<Int>? = null,
)

/**
 * Asks the server what's new since the last seen ids and posts system notifications for it.
 * Used by the periodic background job and, while the app is in the background but still alive,
 * by a quicker loop in the app. Returns false when the server couldn't be reached.
 */
suspend fun checkUpdates(ctx: Context): Boolean {
    val prefs = Prefs(ctx)
    val token = prefs.token ?: return true
    if (!prefs.notifications) return true
    val api = Api(prefs.serverUrl, token, prefs.language)
    val text = try {
        api.raw("GET", "/updates?after_message=${prefs.lastMessageSeen}&after_notification=${prefs.lastNotificationSeen}")
    } catch (e: ApiException) {
        return e.status != 0                     // offline: try later; logged out/outdated: quiet
    }
    val up = api.json.decodeFromString(Updates.serializer(), text)
    prefs.lastMessageSeen = maxOf(prefs.lastMessageSeen, up.latestMessageId)
    prefs.lastNotificationSeen = maxOf(prefs.lastNotificationSeen, up.latestNotificationId)
    if (Notifier.allowed(ctx)) postUpdates(ctx, prefs, up)
    if (up.unreadChats != null && up.unreadNotifications != null) {
        Notifier.clearRead(ctx, up.unreadChats.toSet(), up.unreadNotifications.toSet())
    }
    return true
}

/**
 * Quick checks after leaving the app: Android blocks the network of apps in the background, only
 * scheduled jobs get it. So for a while after the app was left, a chain of short jobs checks about
 * every minute; afterwards the periodic job (about every 15 minutes) takes over.
 */
class QuickCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        checkUpdates(ctx)
        val since = inputData.getLong("since", System.currentTimeMillis())
        if (System.currentTimeMillis() - since < QUICK_FOR_MS) enqueueQuick(ctx, since, append = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "momentum-quick"
        private const val QUICK_FOR_MS = 2 * 60 * 60 * 1000L
        private const val EVERY_S = 60L

        fun enqueueQuick(ctx: Context, since: Long, append: Boolean = false) {
            val req = androidx.work.OneTimeWorkRequestBuilder<QuickCheckWorker>()
                .setInitialDelay(if (append) EVERY_S else 20, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf("since" to since))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME,
                if (append) androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE else androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        fun start(ctx: Context) = enqueueQuick(ctx, System.currentTimeMillis())
        fun stop(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }
    }
}

class UpdatesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!checkUpdates(ctx)) return Result.retry()
        val prefs = Prefs(ctx)
        val token = prefs.token ?: return Result.success()
        val api = Api(prefs.serverUrl, token, prefs.language)
        // keep the home screen widgets current while the app is closed (e.g. after the nightly decay)
        runCatching {
            val st = api.json.decodeFromString(StateResponse.serializer(), api.raw("GET", "/state")).state
            LocalStore(ctx, api.json).write("state", api.json.encodeToString(AppState.serializer(), st))
            com.momentum.app.widget.Widgets.updateAll(ctx)
        }
        runCatching { com.momentum.app.widget.refreshRankings(ctx) }     // ranking widgets
        return Result.success()
    }
}

@android.annotation.SuppressLint("MissingPermission")     // checked in Notifier.allowed
private fun postUpdates(ctx: Context, prefs: Prefs, up: Updates) {
    run {
        Notifier.ensureChannels(ctx)
        val S = stringsFor(if (prefs.language == "de") Lang.DE else Lang.EN)
        val nm = NotificationManagerCompat.from(ctx)
        // encrypted messages: their text is opened here with the key on the phone (the server can't read it)
        val messages = up.messages.map { m ->
            val env = m.e2e ?: return@map m
            val pv = E2EStore.previewText(ctx, m.conversationId, env)
            m.copy(text = pv?.t ?: S.e2eNewMessage, mediaKind = pv?.k ?: m.mediaKind)
        }
        // tagged in a message: its own, more prominent notification
        val (mentions, plain) = messages.partition { it.mention }
        mentions.takeLast(5).forEach { m ->
            val n = NotificationCompat.Builder(ctx, Notifier.CHANNEL_MENTIONS)
                .setSmallIcon(R.drawable.ic_stat_momentum)
                .setContentTitle(S.mentionedYou(m.sender, if (m.group) m.title else null))
                .setContentText(m.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(Notifier.open(ctx, 30_000 + m.id) { putExtra(Notifier.EXTRA_CHAT, m.conversationId) })
                .build()
            nm.notify(Notifier.chatTag(m.conversationId), 30_000 + m.id, n)
        }
        // one notification per chat, with every new line in it
        plain.groupBy { it.conversationId }.forEach { (convId, msgs) ->
            val lines = msgs.map { m ->
                val body = m.text.ifBlank {
                    when {
                        m.mediaKind == "audio" -> "♪ ${S.voiceMessage}"
                        m.mediaKind == "video" -> "▶ ${S.videoLabel}"
                        m.media -> "▣ ${S.photo}"
                        m.share == "rule" -> "↗ ${S.sharedRule}"
                        m.share == "post" -> "↗ ${S.sharedPost}"
                        m.share == "profile" -> "↗ ${S.sharedProfile}"
                        m.share == "challenge" -> S.challenge
                        else -> ""
                    }
                }
                if (m.group) "${m.sender}: $body" else body
            }
            val n = NotificationCompat.Builder(ctx, Notifier.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_momentum)
                .setContentTitle(msgs.last().title)
                .setContentText(lines.last())
                .setStyle(NotificationCompat.InboxStyle().also { st -> lines.takeLast(6).forEach(st::addLine) })
                .setNumber(msgs.size)
                .setAutoCancel(true)
                .setContentIntent(Notifier.open(ctx, 10_000 + convId) { putExtra(Notifier.EXTRA_CHAT, convId) })
                .build()
            nm.notify(Notifier.chatTag(convId), 10_000 + convId, n)
        }
        // activity: likes, comments, follows, requests, challenge results
        up.notifications.takeLast(5).forEach { a ->
            val ch = a.challenge
            val line = when {
                a.kind == "ch_end" && ch != null -> S.challengeResult(ch.title, ch.rank, fmt(ch.reward))
                a.kind == "ch_cancel" && ch != null -> "${a.actor} ${S.challengeCancelled(ch.title)}"
                else -> "${a.actor} ${com.momentum.app.ui.activityText(S, a.kind, a.rule?.name, a.group?.name)}"
            }
            val n = NotificationCompat.Builder(ctx, Notifier.CHANNEL_ACTIVITY)
                .setSmallIcon(R.drawable.ic_stat_momentum)
                .setContentTitle(S.activity)
                .setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(line))
                .setAutoCancel(true)
                .setContentIntent(Notifier.open(ctx, 20_000 + a.id) { putExtra(Notifier.EXTRA_ACTIVITY, true) })
                .build()
            nm.notify(Notifier.ACTIVITY_TAG, 20_000 + a.id, n)
        }
    }
}
