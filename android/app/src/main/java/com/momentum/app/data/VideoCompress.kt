package com.momentum.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private const val SHORT_SIDE = 720
private const val BITRATE = 2_000_000
private const val MAX_MS = 30_000L

/**
 * A video re-encoded on the phone before uploading: 720p on the short side, ~2 Mbit/s, at most
 * 30 s (what the server keeps anyway) - a phone video of 50 MB becomes a few MB and uploads fast.
 * Null when it can't be converted (the original is sent then).
 */
@OptIn(UnstableApi::class)
suspend fun compressVideo(ctx: Context, uri: Uri): File? {
    val (w, h) = withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
            val rw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r.release()
            if (rot % 180 != 0) rh to rw else rw to rh
        }.getOrDefault(0 to 0)
    }
    if (w <= 0 || h <= 0) return null
    val scale = minOf(1f, SHORT_SIDE.toFloat() / minOf(w, h))
    val (tw, th) = ((w * scale).toInt() / 2 * 2) to ((h * scale).toInt() / 2 * 2)
    val out = File(File(ctx.cacheDir, "upload").apply { mkdirs() }, "video_${System.currentTimeMillis()}.mp4")
    val item = EditedMediaItem.Builder(
        MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(MAX_MS).build()).build(),
    ).setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(tw, th, Presentation.LAYOUT_SCALE_TO_FIT))))
        .build()
    return withContext(Dispatchers.Main) {           // the Transformer needs a looper thread
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build()).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete()
                        if (cont.isActive) cont.resume(null)
                    }
                })
                .build()
            transformer.start(item, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel(); out.delete() }
        }
    }
}
