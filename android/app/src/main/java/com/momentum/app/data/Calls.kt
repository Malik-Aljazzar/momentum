package com.momentum.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.momentum.app.ui.Lang
import com.momentum.app.ui.stringsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------- what the server sends (server/calls.py call_json)

@Serializable
data class CallUser(val id: Int, val username: String = "", @SerialName("display_name") val displayName: String = "",
                    val avatar: String? = null)

@Serializable
data class CallPeer(val user: CallUser, val state: String = "", val seq: Int = 0, val mic: Boolean = true, val cam: Boolean = false)

@Serializable
data class CallMe(val state: String = "", val device: String? = null)

@Serializable
data class CallInfo(
    val id: Int, @SerialName("conversation_id") val conversationId: Int, val group: Boolean = false, val title: String = "",
    val video: Boolean = false, val status: String = "ringing", val reason: String? = null,
    @SerialName("started_by") val startedBy: CallUser? = null, @SerialName("answered_at") val answeredAt: String? = null,
    val max: Int = 8, val me: CallMe? = null, val participants: List<CallPeer> = emptyList(),
)

/** A call as its chat line tells it (server/calls.py call_lines): duration null = nobody answered. */
@Serializable
data class CallLine(val video: Boolean = false, val group: Boolean = false, val duration: Int? = null, val reason: String? = null,
                    val outgoing: Boolean = false, val joined: Boolean = false)

/** A call running in a chat (its "join" bar): me = my state in it, null = not rung / not in it. */
@Serializable
data class CallBrief(val id: Int, val video: Boolean = false, val joined: Int = 0, val me: String? = null, val status: String = "")

@Serializable
data class IceServerJson(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)

@Serializable
data class CallResponse(val call: CallInfo? = null, @SerialName("ice_servers") val iceServers: List<IceServerJson> = emptyList())

enum class CallPhase { Incoming, Outgoing, Connecting, Active, Ended }

/** What the call screen shows (ui/Call.kt). */
data class CallView(
    val info: CallInfo, val phase: CallPhase, val title: String, val avatar: String?,
    /** the others' pictures, by user id (their sound plays by itself) */
    val remoteVideo: Map<Int, VideoTrack> = emptyMap(),
    /** the connection to this participant is up */
    val linked: Map<Int, Boolean> = emptyMap(),
    val mic: Boolean = true, val cam: Boolean = false, val front: Boolean = true, val speaker: Boolean = false,
    val minimized: Boolean = false,
    /** when the talking started (this phone's clock), for the timer */
    val since: Long? = null,
    val endText: String? = null,
)

/** Who sends the offer to whom: the later one (higher seq) to the earlier one (= web/src/calls.ts offersTo). */
fun offersTo(mySeq: Int, otherSeq: Int) = mySeq > otherSeq

fun inCall(info: CallInfo, me: Int) = info.participants.filter { it.user.id != me && it.state == "joined" }

/**
 * Voice and video calls (server/calls.py = web/src/calls.ts): a socket to the server brings the ringing and the
 * WebRTC signals; one PeerConnection to every other participant (mesh, a group call holds up to info.max). Every
 * connection carries an audio and a video line from the start, so the camera can go on and off without a new offer.
 * Who offers: the one who joined later (higher seq) to everyone already in. While the app is in the background the
 * ringing comes as an FCM push (data/Push.kt -> Notifier.incomingCall), during a call CallService keeps it alive.
 */
object Calls {
    var view by mutableStateOf<CallView?>(null); private set
    /** my own camera picture (for the small preview) */
    var localVideo by mutableStateOf<VideoTrack?>(null); private set

    /** this app start: the device that answered gets the call */
    val device: String = UUID.randomUUID().toString()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ctx: Context
    private var api: Api? = null
    private var me = -1
    private var appVisible = false
    private var accepting = false
    private var iceServers: List<IceServerJson> = listOf(IceServerJson(listOf("stun:stun.l.google.com:19302")))

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null

    private class Peer(val pc: PeerConnection, val seq: Int, val offerer: Boolean) {
        val early = mutableListOf<IceCandidate>()
        val out = mutableListOf<IceCandidate>()
        var flush: Job? = null
        val lock = Mutex()
    }

    private val peers = mutableMapOf<Int, Peer>()

    private fun strings() = stringsFor(if (Prefs(ctx).language == "de") Lang.DE else Lang.EN)

    /** The app's context (MomentumApplication): pushes may come before the app's screen. */
    fun init(context: Context) {
        if (!::ctx.isInitialized) ctx = context.applicationContext
    }

    /** Logged in (AppViewModel): the account, its session and the socket while the app is on screen. */
    fun attach(context: Context, api: Api, myId: Int) {
        ctx = context.applicationContext
        this.api = api
        me = myId
        if (appVisible || view != null) connect()
    }

    /** Logged out: hang up, socket closed. */
    fun detach() {
        hangUp()
        api = null
        me = -1
        disconnect()
    }

    fun setVisible(on: Boolean) {
        appVisible = on
        if (on) connect() else if (view == null) disconnect()
    }

    fun isRinging(id: Int) = view?.let { it.info.id == id } == true

    /** FCM (data/Push.kt): "call" rings - in the app when it's on screen, else the notification; "call_end" stops
     *  it (missed: "missed call"). */
    fun onPush(context: Context, d: Map<String, String>) {
        init(context)
        val id = d["call"]?.toIntOrNull() ?: return
        val name = d["name"].orEmpty()
        val group = d["group"].orEmpty()
        val video = d["video"] == "1"
        if (d["type"] == "call") {
            if (view?.info?.id == id) return
            val a = api
            if (appVisible && a != null) {
                connect()
                scope.launch { runCatching { a.call<CallResponse>("GET", "/calls/$id").call }.getOrNull()?.let(::applyInfo) }
                return
            }
            Notifier.incomingCall(ctx, id, name, group, video)
        } else {
            Notifier.cancelCall(ctx, id)
            if (d["missed"] == "1" && view?.info?.id != id) Notifier.missedCall(ctx, id, d["conv"]?.toIntOrNull() ?: 0, name, group, video)
        }
    }

    private fun update(f: (CallView) -> CallView) { view?.let { view = f(it) } }

    // ---------------------------------------------------------------- the socket

    private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private var socket: WebSocket? = null
    private var retries = 0
    private var retry: Job? = null

    private fun connect() {
        val a = api ?: return
        val token = a.token ?: return
        if (socket != null) return
        val url = a.baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/calls/ws"
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject { put("token", token); put("device", device) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { runCatching { onEvent(a.json.parseToJsonElement(text).jsonObject, a) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scope.launch { dropped(webSocket) }.let {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scope.launch { dropped(webSocket) }.let {}
        })
        socket = ws
    }

    private fun dropped(ws: WebSocket) {
        if (socket !== ws) return
        socket = null
        retry?.cancel()
        if (api?.token != null && (appVisible || view != null)) retry = scope.launch {
            delay(minOf(15_000L, 1000L shl minOf(retries++, 4)))
            connect()
        }
    }

    private fun disconnect() {
        retry?.cancel()
        val ws = socket
        socket = null
        ws?.close(1000, null)
    }

    private fun send(msg: JsonObject) { socket?.send(msg.toString()) }

    private fun onEvent(e: JsonObject, a: Api) {
        when (e["type"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> {
                retries = 0
                e["ice"]?.let { iceServers = a.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(IceServerJson.serializer()), it) }
            }
            "call" -> e["call"]?.let { applyInfo(a.json.decodeFromJsonElement(CallInfo.serializer(), it)) }
            "signal" -> {
                val id = e["call"]?.jsonPrimitive?.intOrNull ?: return
                val from = e["from"]?.jsonPrimitive?.intOrNull ?: return
                val data = e["data"] as? JsonObject ?: return
                onSignal(id, from, data)
            }
        }
    }

    // ---------------------------------------------------------------- what the server says

    private fun isCurrent(cur: CallView, info: CallInfo) =
        cur.info.id == info.id || (cur.info.id == 0 && cur.info.conversationId == info.conversationId)

    fun applyInfo(info: CallInfo) {
        val cur = view
        val my = info.me
        if (cur == null || !isCurrent(cur, info)) {
            if (info.status == "ended") { Notifier.cancelCall(ctx, info.id); return }
            if (cur != null) return                     // in another call: the server tells them I'm busy
            if (my?.state == "ringing") { ringing(info); return }
            // this app was restarted in the middle of the call: its sound is gone, so it leaves
            if (my?.state == "joined" && my.device == device) scope.launch { runCatching { api?.raw("POST", "/calls/${info.id}/leave") } }
            return
        }
        if (info.status == "ended") { ended(info); return }
        if (cur.phase == CallPhase.Incoming) {
            if (my?.state == "ringing") { view = cur.copy(info = info); return }
            Tones.stop()                                // answered on another device, declined, missed
            Notifier.cancelCall(ctx, info.id)
            view = null
            return
        }
        if (my?.state != "joined" || my.device != device) {
            if (accepting && my?.state == "ringing") { view = cur.copy(info = info); return }
            cleanup()
            view = null
            return
        }
        val phase = if (cur.phase == CallPhase.Outgoing && info.status == "active") CallPhase.Connecting else cur.phase
        view = cur.copy(info = info, phase = phase)
        if (phase == CallPhase.Outgoing) Tones.ringback(ctx) else if (cur.phase == CallPhase.Outgoing) Tones.stop()
        syncPeers(info)
    }

    private fun ringing(info: CallInfo) {
        val s = strings()
        view = CallView(info, CallPhase.Incoming, if (info.group) info.title.ifBlank { s.groupVoiceCall } else info.startedBy?.displayName.orEmpty(),
            info.startedBy?.avatar, cam = info.video)
        Notifier.cancelCall(ctx, info.id)               // the app shows it: no second ringing from the notification
        Tones.ring(ctx)
    }

    private fun ended(info: CallInfo) {
        val cur = view ?: return
        if (cur.phase == CallPhase.Ended) { view = cur.copy(info = info); return }
        val s = strings()
        val text = if (info.startedBy?.id == me && info.answeredAt == null) when (info.reason) {
            "busy" -> s.callBusy
            "declined" -> s.callDeclined
            else -> s.callNoAnswer
        } else s.callEnded
        val wasRinging = cur.phase == CallPhase.Incoming
        cleanup()
        Notifier.cancelCall(ctx, info.id)
        if (wasRinging) { view = null; return }        // missed: the chat line tells it
        Tones.end(ctx)
        view = cur.copy(info = info, phase = CallPhase.Ended, endText = text, remoteVideo = emptyMap(), minimized = false)
        scope.launch {
            delay(1800)
            if (view?.phase == CallPhase.Ended && view?.info?.id == info.id) view = null
        }
    }

    // ---------------------------------------------------------------- what I do

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    private fun openMedia(video: Boolean, hasCamera: Boolean) {
        val f = factory()
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("mic", audioSource).apply { setEnabled(true) }
        if (video && hasCamera) startCamera(true)
        Audio.start(ctx, speaker = video)
        update { it.copy(cam = localVideo != null, speaker = video, mic = true) }
    }

    private fun startCamera(front: Boolean): Boolean {
        val f = factory()
        val cams = Camera2Enumerator(ctx)
        val name = cams.deviceNames.firstOrNull { cams.isFrontFacing(it) == front } ?: cams.deviceNames.firstOrNull() ?: return false
        return runCatching {
            val cap = cams.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
            val source = f.createVideoSource(false)
            cap.initialize(helper, ctx, source.capturerObserver)
            cap.startCapture(640, 480, 24)
            capturer = cap
            textures = helper
            videoSource = source
            localVideo = f.createVideoTrack("cam", source)
            true
        }.getOrElse { false }
    }

    private fun stopCamera() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        localVideo?.dispose()
        localVideo = null
        videoSource?.dispose()
        videoSource = null
        textures?.dispose()
        textures = null
    }

    /** Rings the chat (or joins the call running there). Permissions are asked before (ui/Call.kt). */
    fun start(convId: Int, video: Boolean, title: String, avatar: String?, hasCamera: Boolean) {
        val a = api ?: return
        val s = strings()
        view?.let { cur ->
            if (cur.info.conversationId == convId) view = cur.copy(minimized = false) else Toasts.show(s.callAlready)
            return
        }
        connect()
        view = CallView(CallInfo(0, convId, video = video), CallPhase.Outgoing, title, avatar, cam = video)
        openMedia(video, hasCamera)
        if (video && !hasCamera) Toasts.show(s.callNoCamera)
        scope.launch {
            try {
                val r = a.call<CallResponse>("POST", "/conversations/$convId/call", buildJsonObject { put("video", video); put("device", device) })
                if (r.iceServers.isNotEmpty()) iceServers = r.iceServers
                val info = r.call ?: return@launch
                if (view == null) { runCatching { a.raw("POST", "/calls/${info.id}/leave") }; return@launch }
                CallService.start(ctx)
                applyInfo(info)
            } catch (e: ApiException) {
                cleanup()
                view = null
                Toasts.show(e.message ?: "", error = true)
            }
        }
    }

    fun accept(hasCamera: Boolean) {
        val cur = view ?: return
        val a = api ?: return
        if (cur.phase != CallPhase.Incoming) return
        Tones.stop()
        Notifier.cancelCall(ctx, cur.info.id)
        connect()
        accepting = true
        view = cur.copy(phase = CallPhase.Connecting)
        openMedia(cur.info.video, hasCamera)
        scope.launch {
            try {
                val r = a.call<CallResponse>("POST", "/calls/${cur.info.id}/accept", buildJsonObject { put("device", device) })
                if (r.iceServers.isNotEmpty()) iceServers = r.iceServers
                accepting = false
                CallService.start(ctx)
                r.call?.let(::applyInfo)
            } catch (e: ApiException) {
                cleanup()
                view = null
                Toasts.show(e.message ?: "", error = true)
            } finally {
                accepting = false
            }
        }
    }

    fun decline(id: Int? = view?.info?.id) {
        Tones.stop()
        cleanup()
        view = null
        if (id != null && id > 0) {
            Notifier.cancelCall(ctx, id)
            scope.launch { runCatching { api?.raw("POST", "/calls/$id/decline") } }
        }
    }

    fun hangUp() {
        val cur = view ?: return
        val id = cur.info.id
        cleanup()
        if (cur.phase == CallPhase.Incoming) { view = null; if (id > 0) scope.launch { runCatching { api?.raw("POST", "/calls/$id/decline") } }; return }
        Tones.end(ctx)
        view = cur.copy(phase = CallPhase.Ended, endText = strings().callEnded, remoteVideo = emptyMap())
        scope.launch {
            delay(1200)
            if (view?.phase == CallPhase.Ended && view?.info?.id == id) view = null
        }
        if (id > 0) scope.launch { runCatching { api?.raw("POST", "/calls/$id/leave") } }
    }

    /** Opened from the ringing notification: still ringing -> the incoming call screen (answer: at once). */
    fun openFromNotification(id: Int, answer: Boolean, hasCamera: () -> Boolean, onEnded: (Int) -> Unit) {
        val a = api ?: return
        connect()
        scope.launch {
            val info = runCatching { a.call<CallResponse>("GET", "/calls/$id").call }.getOrNull() ?: return@launch
            applyInfo(info)
            if (info.status == "ended") { onEnded(info.conversationId); return@launch }
            if (answer && view?.phase == CallPhase.Incoming && view?.info?.id == id) accept(hasCamera())
        }
    }

    fun minimize(on: Boolean) = update { it.copy(minimized = on) }

    fun toggleMic() {
        val cur = view ?: return
        val on = !cur.mic
        audioTrack?.setEnabled(on)
        view = cur.copy(mic = on)
        sendMedia()
    }

    fun toggleSpeaker() {
        val cur = view ?: return
        Audio.speaker(ctx, !cur.speaker)
        view = cur.copy(speaker = !cur.speaker)
    }

    fun toggleCam(hasCamera: Boolean) {
        val cur = view ?: return
        if (cur.cam) {
            replaceVideo(null)
            stopCamera()
            view = cur.copy(cam = false)
        } else {
            if (!hasCamera || !startCamera(cur.front)) { Toasts.show(strings().callNoCamera, error = true); return }
            replaceVideo(localVideo)
            tuneVideo(peers.size)
            // a voice call becomes a video call: the speaker comes on like WhatsApp does
            if (!cur.speaker) Audio.speaker(ctx, true)
            view = cur.copy(cam = true, speaker = true)
            CallService.start(ctx)          // its type now includes the camera
        }
        sendMedia()
    }

    fun flipCam() {
        val cap = capturer ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) { scope.launch { update { it.copy(front = isFrontCamera) } } }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    private fun sendMedia() {
        val cur = view ?: return
        if (cur.info.id > 0) send(buildJsonObject {
            put("type", "media"); put("call", cur.info.id); put("mic", cur.mic); put("cam", cur.cam)
        })
    }

    private fun cleanup() {
        Tones.stop()
        peers.keys.toList().forEach(::closePeer)
        stopCamera()
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        Audio.stop(ctx)
        CallService.stop(ctx)
        accepting = false
        if (!appVisible) disconnect()
    }

    // ---------------------------------------------------------------- the connections

    private fun signalTo(uid: Int, data: JsonObject) {
        val id = view?.info?.id ?: return
        if (id > 0) send(buildJsonObject { put("type", "signal"); put("call", id); put("to", uid); put("data", data) })
    }

    private fun syncPeers(info: CallInfo) {
        val mine = info.participants.firstOrNull { it.user.id == me } ?: return
        if (mine.state != "joined") return
        val here = mutableSetOf<Int>()
        for (p in info.participants) {
            if (p.user.id == me) continue
            val peer = peers[p.user.id]
            if (p.state != "joined") { if (peer != null) closePeer(p.user.id); continue }
            here += p.user.id
            if (peer != null && peer.seq != p.seq) closePeer(p.user.id)       // they came again
            if (p.user.id !in peers && offersTo(mine.seq, p.seq)) offer(p.user.id, p.seq)
        }
        peers.keys.filter { it !in here }.forEach(::closePeer)
        tuneVideo(here.size)
    }

    private fun newPeer(uid: Int, seq: Int, offerer: Boolean): Peer? {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let(::setUsername)
                s.credential?.let(::setPassword)
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        var holder: Peer? = null
        val pc = factory().createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { scope.launch { holder?.let { queueCandidate(uid, it, c) } } }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) scope.launch {
                    if (holder != null && peers[uid] === holder) update { it.copy(remoteVideo = it.remoteVideo + (uid to track)) }
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { scope.launch { holder?.let { watch(uid, it, newState) } } }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: return null
        val peer = Peer(pc, seq, offerer)
        holder = peer
        peers[uid] = peer
        return peer
    }

    private fun queueCandidate(uid: Int, peer: Peer, c: IceCandidate) {
        if (peers[uid] !== peer) return
        peer.out += c
        if (peer.flush == null) peer.flush = scope.launch {
            delay(80)
            peer.flush = null
            val batch = peer.out.toList()
            peer.out.clear()
            signalTo(uid, buildJsonObject {
                put("ice", buildJsonArray {
                    batch.forEach { add(buildJsonObject { put("candidate", it.sdp); put("sdpMid", it.sdpMid); put("sdpMLineIndex", it.sdpMLineIndex) }) }
                })
            })
        }
    }

    private fun watch(uid: Int, peer: Peer, state: PeerConnection.PeerConnectionState) {
        val cur = view ?: return
        if (peers[uid] !== peer) return
        val up = state == PeerConnection.PeerConnectionState.CONNECTED
        if (cur.linked[uid] != up) view = cur.copy(linked = cur.linked + (uid to up))
        if (up && (cur.phase == CallPhase.Connecting || cur.phase == CallPhase.Outgoing)) {
            Tones.stop()
            update { it.copy(phase = CallPhase.Active, since = it.since ?: System.currentTimeMillis()) }
            Audio.proximity(ctx, !(view?.speaker ?: false))
        }
        if (state == PeerConnection.PeerConnectionState.FAILED && peer.offerer) scope.launch { restart(uid, peer) }
    }

    private fun closePeer(uid: Int) {
        val peer = peers.remove(uid) ?: return
        peer.flush?.cancel()
        runCatching { peer.pc.close() }
        runCatching { peer.pc.dispose() }
        update { it.copy(remoteVideo = it.remoteVideo - uid, linked = it.linked - uid) }
    }

    private fun initOf() = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("local"))

    private fun offer(uid: Int, seq: Int) {
        val peer = newPeer(uid, seq, true) ?: return
        audioTrack?.let { peer.pc.addTransceiver(it, initOf()) } ?: peer.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, initOf())
        localVideo?.let { peer.pc.addTransceiver(it, initOf()) } ?: peer.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, initOf())
        scope.launch {
            peer.lock.withLock {
                runCatching {
                    val sdp = peer.pc.create(offer = true)
                    peer.pc.setLocal(sdp)
                    signalTo(uid, sdpJson(sdp))
                }.onFailure { closePeer(uid) }
            }
        }
    }

    private suspend fun restart(uid: Int, peer: Peer) = peer.lock.withLock {
        if (peers[uid] !== peer || peer.pc.signalingState() != PeerConnection.SignalingState.STABLE) return@withLock
        runCatching {
            peer.pc.restartIce()
            val sdp = peer.pc.create(offer = true)
            peer.pc.setLocal(sdp)
            signalTo(uid, sdpJson(sdp))
        }
    }

    private fun onSignal(callId: Int, from: Int, data: JsonObject) {
        val cur = view ?: return
        if (cur.info.id != callId) return
        val sdp = data["sdp"] as? JsonObject
        var peer = peers[from]
        if (peer == null && sdp?.get("type")?.jsonPrimitive?.contentOrNull == "offer") {
            peer = newPeer(from, cur.info.participants.firstOrNull { it.user.id == from }?.seq ?: 0, false)
        }
        val p = peer ?: return
        scope.launch { p.lock.withLock { runCatching { handleSignal(from, p, sdp, data["ice"] as? JsonArray) } } }
    }

    private suspend fun handleSignal(uid: Int, peer: Peer, sdp: JsonObject?, ice: JsonArray?) {
        val pc = peer.pc
        if (sdp != null) {
            val type = SessionDescription.Type.fromCanonicalForm(sdp["type"]?.jsonPrimitive?.contentOrNull ?: return)
            pc.setRemote(SessionDescription(type, sdp["sdp"]?.jsonPrimitive?.contentOrNull ?: return))
            if (type == SessionDescription.Type.OFFER) {
                for (t in pc.transceivers) {
                    if (t.isStopped) continue
                    if (t.sender.track() == null) {
                        val track: MediaStreamTrack? = if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) audioTrack else localVideo
                        if (track != null) t.sender.setTrack(track, false)
                    }
                    t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    runCatching { t.sender.setStreams(listOf("local")) }
                }
                val answer = pc.create(offer = false)
                pc.setLocal(answer)
                signalTo(uid, sdpJson(answer))
            }
            peer.early.forEach { pc.addIceCandidate(it) }
            peer.early.clear()
        }
        for (c in ice ?: JsonArray(emptyList())) {
            val o = c.jsonObject
            val cand = IceCandidate(o["sdpMid"]?.jsonPrimitive?.contentOrNull ?: "0",
                o["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0, o["candidate"]?.jsonPrimitive?.contentOrNull ?: continue)
            if (pc.remoteDescription != null) pc.addIceCandidate(cand) else peer.early += cand
        }
    }

    private fun replaceVideo(track: VideoTrack?) {
        for (peer in peers.values) for (t in peer.pc.transceivers) {
            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !t.isStopped) t.sender.setTrack(track, false)
        }
    }

    /** Several pictures to send in a group: each one smaller (mesh: every phone sends to every other). */
    private fun tuneVideo(others: Int) {
        val max = if (others > 2) 300_000 else if (others > 1) 600_000 else 1_200_000
        for (peer in peers.values) for (sender in peer.pc.senders) {
            if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) continue
            val p = sender.parameters
            if (p.encodings.isEmpty()) continue
            p.encodings[0].maxBitrateBps = max
            sender.parameters = p
        }
    }

    private fun sdpJson(sdp: SessionDescription) = buildJsonObject {
        put("sdp", buildJsonObject { put("type", sdp.type.canonicalForm()); put("sdp", sdp.description) })
    }

    private suspend fun PeerConnection.create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }
        if (offer) createOffer(obs, MediaConstraints()) else createAnswer(obs, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = setDescription { setLocalDescription(it, sdp) }
    private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = setDescription { setRemoteDescription(it, sdp) }

    private suspend fun setDescription(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        })
    }
}

/** Short messages from the call engine (the view model shows them as toasts). */
object Toasts {
    var sink: (String, Boolean) -> Unit = { _, _ -> }
    fun show(text: String, error: Boolean = false) = sink(text, error)
}

/** The phone's sound during a call: voice mode, earpiece or speaker, the screen off at the ear. */
internal object Audio {
    private var focus: AudioFocusRequest? = null
    private var near: PowerManager.WakeLock? = null

    fun start(ctx: Context, speaker: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            am.requestAudioFocus(req)
            focus = req
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        speaker(ctx, speaker)
    }

    fun speaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (on && dev != null) am.setCommunicationDevice(dev) else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
        proximity(ctx, !on && Calls.view?.phase == CallPhase.Active)
    }

    /** At the ear (earpiece, not the speaker): the screen goes off while something is close to it. */
    fun proximity(ctx: Context, on: Boolean) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (on && near == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            near = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "momentum:call").apply { acquire(4 * 60 * 60 * 1000L) }
        } else if (!on) {
            near?.let { if (it.isHeld) it.release() }
            near = null
        }
    }

    fun stop(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        proximity(ctx, false)
    }
}

/** The phone's ringtone for an incoming call, the ringback while it rings at the other end, a beep at the end
 *  (the PWA plays 8-bit tones instead: web/src/calltones.ts). */
internal object Tones {
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var buzzing = false

    fun ring(ctx: Context) {
        stop()
        ringtone = runCatching {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        val vib = ctx.getSystemService(Vibrator::class.java)
        if (vib?.hasVibrator() == true) {
            buzzing = true
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 700), 0))
        }
        vibrator = vib
    }

    private var vibrator: Vibrator? = null

    fun ringback(ctx: Context) {
        if (tone != null) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun end(ctx: Context) {
        stop()
        runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply {
                startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
                CoroutineScope(Dispatchers.Default).launch { delay(600); release() }
            }
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        tone?.let { runCatching { it.stopTone(); it.release() } }
        tone = null
        if (buzzing) vibrator?.cancel()
        buzzing = false
    }
}
