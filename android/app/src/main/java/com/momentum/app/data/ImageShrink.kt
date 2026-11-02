package com.momentum.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val MAX_SIDE = 2048

/** A photo for an encrypted chat (the server can't shrink it): JPEG with 1600 px and a 480 px copy for the chat. */
class SecurePhoto(val full: ByteArray, val thumb: ByteArray, val width: Int, val height: Int)

fun securePhoto(ctx: Context, uri: Uri): SecurePhoto? = runCatching {
    val upright = shrinkImage(ctx, uri) ?: return null
    val bmp = BitmapFactory.decodeByteArray(upright, 0, upright.size) ?: return null
    fun scaled(side: Int, quality: Int): Triple<ByteArray, Int, Int> {
        val k = minOf(1f, side.toFloat() / max(bmp.width, bmp.height))
        val b = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Triple(out.toByteArray(), b.width, b.height)
    }
    val (full, w, h) = scaled(1600, 85)
    SecurePhoto(full, scaled(480, 80).first, w, h)
}.getOrNull()

/** A photo as JPEG with at most 2048 px on the long side, upright (EXIF rotation applied). Null: not decodable. */
fun shrinkImage(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
    }
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    }
}.getOrNull()
