package com.momentum.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

/** The biggest chat video the server takes in an encrypted chat (server e2e.MAX_VIDEO). */
internal const val SECURE_VIDEO_MAX_BYTES = 100L * 1024 * 1024
/** What a video keeps at most (server media.VIDEO_MAX_SECONDS, = compressVideo). */
internal const val VIDEO_MAX_SECONDS = 60.0

/** A video for an encrypted chat: the file to seal, its 480 px still, its display size and length. */
class SecureVideo(val bytes: ByteArray, val still: ByteArray?, val width: Int, val height: Int, val duration: Double)

/** A video that couldn't be shrunk goes as it is only within what a chat video may be (unknown length: by its size). */
internal fun sendsAsItIs(bytes: Long, seconds: Double) = bytes in 1..SECURE_VIDEO_MAX_BYTES && seconds <= VIDEO_MAX_SECONDS + .5

/**
 * A video for an encrypted chat (the server can't re-encode it): shrunk here like every upload ([compressVideo]:
 * 720p on the short side, ~2 Mbit/s, at most 60 s) with a 480 px still for the chat bubble. One that can't be
 * shrunk goes as it is when it's short and small enough ([sendsAsItIs]; = web/src/chatvideo.ts). Null: it can't
 * be sent. [onProgress]: how far the shrinking is (0..1).
 */
suspend fun secureVideo(ctx: Context, uri: Uri, onProgress: (Float) -> Unit = {}): SecureVideo? {
    val shrunk = compressVideo(ctx, uri, onProgress)
    try {
        val src = shrunk?.let { Uri.fromFile(it) } ?: uri
        return withContext(Dispatchers.IO) {
            val info = videoInfo(ctx, src)
            val bytes = ctx.contentResolver.openInputStream(src)?.use { readCapped(it, SECURE_VIDEO_MAX_BYTES) } ?: return@withContext null
            if (shrunk == null && !sendsAsItIs(bytes.size.toLong(), info.duration)) return@withContext null
            SecureVideo(bytes, info.still, info.width, info.height, minOf(info.duration, VIDEO_MAX_SECONDS))
        }
    } finally {
        shrunk?.delete()
    }
}

private class VideoInfo(val width: Int, val height: Int, val duration: Double, val still: ByteArray?)

/** Display size (rotation applied), length and a 480 px JPEG still (a frame at ~0.3 s) of a video. */
private fun videoInfo(ctx: Context, uri: Uri): VideoInfo = runCatching {
    val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
    try {
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        // the frame comes upright (the retriever applies the rotation)
        val frame = runCatching { r.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime }.getOrNull()
        val (dw, dh) = if (rot % 180 != 0) h to w else w to h
        VideoInfo(dw, dh, ms / 1000.0, frame?.let { stillJpeg(it) })
    } finally {
        r.release()
    }
}.getOrDefault(VideoInfo(0, 0, 0.0, null))

private fun stillJpeg(frame: Bitmap): ByteArray {
    val k = minOf(1f, 480f / max(frame.width, frame.height))
    val b = if (k < 1f) Bitmap.createScaledBitmap(frame, (frame.width * k).toInt().coerceAtLeast(1), (frame.height * k).toInt().coerceAtLeast(1), true) else frame
    return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}

/** The whole stream, or null when it's longer than [cap] (a big file isn't read into memory first). */
internal fun readCapped(input: InputStream, cap: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > cap) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** A video's first frame for small previews (the photo queue's strip); null when it can't be read. */
fun videoFrame(ctx: Context, uri: Uri, side: Int = 160): Bitmap? = runCatching {
    val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
    try {
        val f = r.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime ?: return null
        val k = minOf(1f, side.toFloat() / max(f.width, f.height))
        if (k < 1f) Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true) else f
    } finally {
        r.release()
    }
}.getOrNull()
