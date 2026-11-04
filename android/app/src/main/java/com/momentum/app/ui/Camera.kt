package com.momentum.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File

private const val MAX_VIDEO_MS = 60_000L

/**
 * The app's own camera: photos and videos come out the way the preview shows them (the front camera
 * is mirrored like a mirror, on every phone), videos are recorded at 720p with a moderate bitrate so
 * they upload quickly.
 */
@Composable
internal fun CameraCapture(video: Boolean, onDismiss: () -> Unit, onCaptured: (Uri) -> Unit) {
    val S = LocalStrings.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result[Manifest.permission.CAMERA] == true
        if (!granted) onDismiss()
    }
    LaunchedEffect(Unit) {
        if (!granted) ask.launch(if (video) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else arrayOf(Manifest.permission.CAMERA))
    }
    var front by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    // TextureView mode: a SurfaceView would stay black inside a dialog window
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(2_500_000)
            .build()
        VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
    }

    DisposableEffect(granted, front, videoMode) {
        if (!granted) return@DisposableEffect onDispose {}
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                provider.unbindAll()
                if (videoMode) provider.bindToLifecycle(lifecycle, selector, preview, videoCapture)
                else provider.bindToLifecycle(lifecycle, selector, preview, imageCapture)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(recording) {
        while (recording != null) {
            now = System.currentTimeMillis()
            if (now - recordStart >= MAX_VIDEO_MS) { recording?.stop(); recording = null }
            delay(200)
        }
    }

    fun newFile(ext: String) = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "capture_${System.currentTimeMillis()}.$ext")

    fun shoot() {
        if (videoMode) {
            val r = recording
            if (r != null) { r.stop(); recording = null; return }
            val file = newFile("mp4")
            val pending = videoCapture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
                .let { p ->
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        p.withAudioEnabled() else p
                }
            recordStart = System.currentTimeMillis()
            recording = pending.start(ContextCompat.getMainExecutor(ctx)) { e ->
                if (e is VideoRecordEvent.Finalize) {
                    if (!e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) onCaptured(Uri.fromFile(file))
                    else file.delete()
                }
            }
        } else {
            val file = newFile("jpg")
            // the front camera's picture mirrored, like the preview
            val meta = ImageCapture.Metadata().apply { isReversedHorizontal = front }
            imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build(),
                ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCaptured(Uri.fromFile(file))
                    override fun onError(e: ImageCaptureException) { file.delete() }
                })
        }
    }

    androidx.activity.compose.BackHandler { recording?.stop(); onDismiss() }
    run {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
            if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
            Row(Modifier.fillMaxWidth().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn("ui_close") { recording?.stop(); onDismiss() }
                Box(Modifier.weight(1f))
                if (recording != null) {
                    val s = ((now - recordStart) / 1000).toInt()
                    Text("● %d:%02d".format(s / 60, s % 60), style = T.block.copy(fontSize = 16.sp, color = Px.Orange))
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (video && recording == null) Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x66000000)).padding(4.dp)) {
                    listOf(false to S.photo, true to S.videoLabel).forEach { (v, label) ->
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (videoMode == v) Px.Text else Color.Transparent)
                            .clickable { videoMode = v }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(label.uppercase(), style = T.button.copy(color = if (videoMode == v) Color.Black else Px.Text))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Box(Modifier.size(48.dp))
                    // shutter: photo, or start / stop the video
                    Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
                        .background(if (videoMode) Color(0xFFB13E53) else Color.White).clickable { shoot() },
                        contentAlignment = Alignment.Center) {
                        if (recording != null) Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x66000000))
                        .clickable(enabled = recording == null) { front = !front }, contentAlignment = Alignment.Center) {
                        Text("⟲", style = T.block.copy(fontSize = 22.sp, color = Color.White))
                    }
                }
            }
        }
    }
}


/**
 * The camera is shown above everything (not as a dialog window, which doesn't get the right insets
 * in the edge-to-edge app): [request] opens it, the app's root draws [CameraHost].
 */
object CameraOverlay {
    internal var request by mutableStateOf<Pair<Boolean, (Uri) -> Unit>?>(null)
    fun open(video: Boolean, onCaptured: (Uri) -> Unit) { request = video to onCaptured }
}

@Composable
fun CameraHost() {
    val (video, done) = CameraOverlay.request ?: return
    CameraCapture(video, onDismiss = { CameraOverlay.request = null }) { uri -> CameraOverlay.request = null; done(uri) }
}
