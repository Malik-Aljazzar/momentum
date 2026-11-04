package com.momentum.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.momentum.app.AppViewModel
import com.momentum.app.data.CallPeer
import com.momentum.app.data.CallPhase
import com.momentum.app.data.CallView
import com.momentum.app.data.Calls
import com.momentum.app.data.Toasts
import com.momentum.app.data.inCall
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// The call screen (= web/src/screens/Call.tsx): ringing, the others' pictures / avatars, the buttons; minimized it is
// a small window over the app (the call goes on). An overlay layer, not a Dialog (Android 15 cuts a Dialog's bottom).

private fun granted(ctx: Context, permission: String) =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

/** Asks for the microphone (and the camera for video) before a call; [then] gets whether the camera may be used. */
@Composable
fun rememberCallPermissions(): (video: Boolean, then: (camera: Boolean) -> Unit) -> Unit {
    val ctx = LocalContext.current
    val S = LocalStrings.current
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val then = pending
        pending = null
        if (granted(ctx, Manifest.permission.RECORD_AUDIO)) then?.invoke(granted(ctx, Manifest.permission.CAMERA))
        else Toasts.show(S.callNoMic, error = true)
    }
    return { video, then ->
        val need = buildList {
            if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (video && !granted(ctx, Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) then(granted(ctx, Manifest.permission.CAMERA))
        else { pending = then; launcher.launch(need.toTypedArray()) }
    }
}

/** The status line (= Call.tsx statusText). */
fun callStatus(S: Strings, v: CallView, seconds: Int?, me: Int): String {
    if (v.phase == CallPhase.Ended) return v.endText ?: S.callEnded
    if (v.phase == CallPhase.Incoming) return if (v.info.video) S.callIncomingVideo else S.callIncomingVoice
    if (v.phase == CallPhase.Outgoing) return if (v.info.id > 0 && v.info.participants.any { it.state == "ringing" }) S.callRinging else S.callCalling
    val others = inCall(v.info, me)
    if (others.isEmpty()) return if (v.info.group) S.callWaiting else S.callConnecting
    if (v.phase == CallPhase.Connecting) return S.callConnecting
    if (others.any { v.linked[it.user.id] == false }) return S.callReconnecting
    return seconds?.let(::callDuration) ?: S.callConnecting
}

@Composable
private fun clock(since: Long?): Int? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since != null) { now = System.currentTimeMillis(); delay(1000) }
    }
    return since?.let { ((now - it) / 1000).toInt() }
}

/** Over everything while a call is on (MomentumApp). */
@Composable
fun CallHost(vm: AppViewModel) {
    val v = Calls.view ?: return
    if (v.minimized && v.phase != CallPhase.Incoming) CallBubble(vm, v)
    else CoverLayer { CallScreen(vm, v) }        // the feed's videos pause under it
}

/** A camera picture (WebRTC draws it into its own surface). */
@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier, onTop: Boolean = false) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(factory = { c ->
        SurfaceViewRenderer(c).apply {
            init(Calls.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            if (onTop) setZOrderMediaOverlay(true)
            renderer = this
        }
    }, update = { it.setMirror(mirror) }, onRelease = { it.release() }, modifier = modifier)
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) runCatching { track.addSink(r) }
        onDispose { if (r != null) runCatching { track.removeSink(r) } }
    }
}

@Composable
private fun CallScreen(vm: AppViewModel, v: CallView) {
    val S = LocalStrings.current
    val me = vm.state?.user
    val myId = me?.id ?: -1
    val others = inCall(v.info, myId)
    val seconds = clock(v.since)
    val ask = rememberCallPermissions()
    // shown over the lock screen while it's on, and the screen stays on
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 27) { activity?.setShowWhenLocked(true); activity?.setTurnScreenOn(true) }
        onDispose { if (Build.VERSION.SDK_INT >= 27) { activity?.setShowWhenLocked(false); activity?.setTurnScreenOn(false) } }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = v.phase != CallPhase.Incoming && v.phase != CallPhase.Ended) { Calls.minimize(true) }
    val talking = v.phase == CallPhase.Active || v.phase == CallPhase.Connecting
    val single = if (!v.info.group && others.size == 1) others[0] else null
    val bigVideo = single?.takeIf { it.cam }?.let { v.remoteVideo[it.user.id] }
    val grid = talking && v.info.group && others.isNotEmpty()
    val local = Calls.localVideo
    Box(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0xFF29366F), Color(0xFF10131F), Color.Black)))
        .pointerInput(Unit) {}) {                                     // nothing below gets the taps
        if (bigVideo != null) VideoView(bigVideo, mirror = false, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (v.phase != CallPhase.Incoming && v.phase != CallPhase.Ended) IconBtn("ui_down") { Calls.minimize(true) }
                else Spacer(Modifier.width(44.dp))
                Text("🔒 " + if (v.info.video) S.videoCall else S.voiceCall, style = T.small.copy(color = Px.Text, textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(44.dp))
            }
            if (grid) {
                Tiles(v, others, me?.displayName.orEmpty(), me?.avatar, Modifier.weight(1f), S)
                Text(callStatus(S, v, seconds, myId), style = T.small.copy(color = Px.Text, fontSize = 15.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (bigVideo != null) Arrangement.Top else Arrangement.Center) {
                    if (bigVideo == null) {
                        Box(Modifier.padding(bottom = 18.dp)) { Avatar(single?.user?.displayName ?: v.title, single?.user?.avatar ?: v.avatar, 112.dp) }
                    }
                    Text(single?.user?.displayName ?: v.title, style = T.title.copy(fontSize = 24.sp, textAlign = TextAlign.Center),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v.info.group && v.phase == CallPhase.Incoming) Text(v.info.startedBy?.displayName.orEmpty(), style = T.small)
                    Spacer(Modifier.height(8.dp))
                    Text(callStatus(S, v, seconds, myId), style = T.small.copy(color = Px.Text, fontSize = 15.sp))
                    if (single != null && !single.mic && talking) {
                        Spacer(Modifier.height(6.dp))
                        PixelIcon("ui_micoff", 18.dp, tint = Px.Orange)
                    }
                }
            }
            Controls(v, S, ask)
        }
        // my own picture: small in the corner (in a group it is one of the tiles)
        if (!grid && v.cam && local != null && v.phase != CallPhase.Incoming) {
            VideoView(local, mirror = v.front, onTop = true, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                .padding(top = 64.dp, end = 14.dp).size(96.dp, 128.dp).border(2.dp, Color.White.copy(alpha = .7f)))
        }
    }
}

@Composable
private fun Tiles(v: CallView, others: List<CallPeer>, myName: String, myAvatar: String?, modifier: Modifier, S: Strings) {
    data class Tile(val key: Int, val name: String, val avatar: String?, val track: VideoTrack?, val cam: Boolean, val mic: Boolean,
                    val self: Boolean, val linked: Boolean)
    val tiles = others.map { p ->
        Tile(p.user.id, p.user.displayName, p.user.avatar, v.remoteVideo[p.user.id], p.cam, p.mic, false, v.linked[p.user.id] != false)
    } + Tile(-1, myName, myAvatar, Calls.localVideo, v.cam, v.mic, true, true)
    val cols = if (tiles.size <= 2) 1 else 2
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(cols).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Px.Card2)) {
                        if (t.cam && t.track != null) VideoView(t.track, mirror = t.self && v.front, modifier = Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(t.name, t.avatar, 64.dp) }
                        Row(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!t.mic) { PixelIcon("ui_micoff", 12.dp, tint = Px.Orange); Spacer(Modifier.width(4.dp)) }
                            Text(if (t.self) S.you else t.name, style = T.small.copy(color = Px.Text, fontSize = 12.sp), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (!t.linked) Text(" …", style = T.small.copy(fontSize = 12.sp))
                        }
                    }
                }
                if (row.size < cols) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private enum class BtnKind { Plain, Red, Green }

@Composable
private fun CallButton(icon: String, label: String, kind: BtnKind = BtnKind.Plain, on: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (face, shadow) = when {
        kind == BtnKind.Red -> Px.Red to Color(0xFF6B1F33)
        kind == BtnKind.Green -> Px.Lime to Color(0xFF4F8A3A)
        on -> Px.Text to Color(0x73000000)
        else -> Color.White.copy(alpha = .14f) to Color(0x73000000)
    }
    val drop = if (pressed) 2.dp else 4.dp
    Column(Modifier.width(82.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(62.dp, 66.dp)) {
            Box(Modifier.offset(y = 4.dp).size(62.dp).clip(RoundedCornerShape(20.dp)).background(shadow))
            Box(Modifier.offset(y = 4.dp - drop).size(62.dp).clip(RoundedCornerShape(20.dp)).background(face)
                .clickable(interactionSource = source, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
                PixelIcon(icon, 28.dp, tint = if (kind == BtnKind.Green || on) Color.Black else Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = T.small.copy(fontSize = 11.sp, color = Px.Text, textAlign = TextAlign.Center), maxLines = 1)
    }
}

@Composable
private fun Controls(v: CallView, S: Strings, ask: (Boolean, (Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xA6000000))))
        .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 18.dp).height(92.dp),
        horizontalArrangement = if (v.phase == CallPhase.Incoming) Arrangement.SpaceAround else Arrangement.SpaceEvenly) {
        when (v.phase) {
            CallPhase.Ended -> {}
            CallPhase.Incoming -> {
                CallButton("ui_hangup", S.callDecline, BtnKind.Red) { Calls.decline() }
                CallButton(if (v.info.video) "ui_cam" else "ui_handset", S.callAccept, BtnKind.Green) {
                    ask(v.info.video) { camera -> Calls.accept(camera) }
                }
            }
            else -> {
                CallButton(if (v.cam) "ui_cam" else "ui_camoff", S.callCamera, on = v.cam) {
                    if (v.cam) Calls.toggleCam(true) else ask(true) { Calls.toggleCam(granted(ctx, Manifest.permission.CAMERA)) }
                }
                if (v.cam) CallButton("ui_flip", S.callFlip) { Calls.flipCam() }
                else CallButton("ui_speaker", S.callSpeaker, on = v.speaker) { Calls.toggleSpeaker() }
                CallButton(if (v.mic) "ui_mic" else "ui_micoff", S.callMute, on = !v.mic) { Calls.toggleMic() }
                CallButton("ui_hangup", S.callHangUp, BtnKind.Red) { Calls.hangUp() }
            }
        }
    }
}

/** Minimized: a small window over the app (the other's picture, or the avatar and the time); a tap opens the call. */
@Composable
private fun CallBubble(vm: AppViewModel, v: CallView) {
    val S = LocalStrings.current
    val seconds = clock(v.since)
    val myId = vm.state?.user?.id ?: -1
    val other = inCall(v.info, myId).firstOrNull()
    val track = other?.takeIf { it.cam }?.let { v.remoteVideo[it.user.id] }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 12.dp, bottom = TAB_BAR_HEIGHT + 14.dp), contentAlignment = Alignment.BottomEnd) {
        Box(Modifier.size(96.dp, 128.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B2A55))
            .border(2.dp, Px.Lime, RoundedCornerShape(16.dp)).clickable { Calls.minimize(false) }, contentAlignment = Alignment.Center) {
            if (track != null) VideoView(track, mirror = false, onTop = true, modifier = Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (track == null) Avatar(other?.user?.displayName ?: v.title, other?.user?.avatar ?: v.avatar, 44.dp)
                Spacer(Modifier.height(8.dp))
                Text(callStatus(S, v, seconds, myId), style = T.small.copy(color = Px.Text, fontSize = 12.sp), maxLines = 1)
            }
        }
    }
}
