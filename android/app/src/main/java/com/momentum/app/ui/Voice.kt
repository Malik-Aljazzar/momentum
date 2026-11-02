package com.momentum.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File

/** Records a voice message as AAC (.m4a) into the cache. */
class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    var startedAt = 0L
        private set

    fun start(): Boolean = runCatching {
        val f = File(File(ctx.cacheDir, "voice").apply { mkdirs() }, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
        true
    }.getOrDefault(false)

    /** Stop; returns the file if it's long enough to send (too short / failed: null). */
    fun stop(keep: Boolean): File? {
        val r = recorder ?: return null
        val ok = runCatching { r.stop() }.isSuccess            // throws if nothing was recorded
        r.release()
        recorder = null
        val f = file
        val long = System.currentTimeMillis() - startedAt >= 800
        return if (keep && ok && long) f else { f?.delete(); null }
    }
}

/**
 * Hold to record, release to send. [onRecording] tells the chat to show the recording state;
 * [onHint] shows a toast: a tap (too short to be a message), the permission just granted or missing.
 */
@Composable
fun MicButton(onRecording: (Long?) -> Unit, onHint: (String) -> Unit, onRecorded: (File, Double) -> Unit) {
    val ctx = LocalContext.current
    val S = LocalStrings.current
    val recorder = remember { VoiceRecorder(ctx) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onHint(if (granted) S.micReady else S.micBlocked)       // the press itself was taken by the dialog
    }
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    return@detectTapGestures
                }
                if (!recorder.start()) { onHint(S.micBlocked); return@detectTapGestures }
                onRecording(recorder.startedAt)
                val released = tryAwaitRelease()                    // false: gesture cancelled -> discard
                onRecording(null)
                val seconds = (System.currentTimeMillis() - recorder.startedAt) / 1000.0
                val file = recorder.stop(keep = released)
                if (file != null) onRecorded(file, seconds) else if (released) onHint(S.holdToRecord)
            })
        }.padding(10.dp),
    ) { PixelIcon("ui_mic", 24.dp) }
}

/** "● 0:07  release to send" while recording. */
@Composable
fun RecordingIndicator(since: Long, hint: String) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(200) } }
    val s = ((now - since) / 1000).toInt()
    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (s % 2 == 0) Px.Orange else Px.Red))
        Spacer(Modifier.width(10.dp))
        Text("%d:%02d".format(s / 60, s % 60), style = T.block.copy(fontSize = 16.sp))
        Spacer(Modifier.width(12.dp))
        Text(hint, style = T.small)
    }
}

/** Voice message in a bubble: play/pause, progress, duration. */
@Composable
fun AudioBubble(url: String, duration: Double) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    DisposableEffect(url) { onDispose { player?.release() } }
    // app in the background / screen off: stop
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { player?.pause(); playing = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playing) {
        while (playing) {
            val p = player ?: break
            val total = p.duration.takeIf { it > 0 } ?: (duration * 1000).toLong().coerceAtLeast(1)
            progress = (p.currentPosition.toFloat() / total).coerceIn(0f, 1f)
            delay(100)
        }
    }
    Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp).width(220.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Px.Faint).clickable {
                val p = player ?: ExoPlayer.Builder(ctx).build().also { np ->
                    np.setMediaItem(MediaItem.fromUri(url))
                    np.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) { playing = false; progress = 0f; np.seekTo(0); np.pause() }
                        }
                    })
                    np.prepare()
                    player = np
                }
                if (playing) p.pause() else p.play()
                playing = !playing
            },
            contentAlignment = Alignment.Center,
        ) { PixelIcon(if (playing) "ui_pause" else "ui_play", 18.dp) }
        Spacer(Modifier.width(10.dp))
        SegBar(progress.toDouble(), 14, Px.Lime, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        val s = duration.toInt()
        Text("%d:%02d".format(s / 60, s % 60), style = T.small.copy(fontSize = 12.sp, color = Px.Text))
    }
}
