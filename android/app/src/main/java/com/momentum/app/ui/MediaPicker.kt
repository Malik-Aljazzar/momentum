package com.momentum.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/** Opens the source choice (camera / video / gallery); the result goes to the callback given at creation. */
class MediaPicker internal constructor(val open: () -> Unit)

/** A new file for the camera app to write into (served to it through our FileProvider). */
private fun captureUri(ctx: Context, ext: String): Uri {
    val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(dir, "capture_${System.currentTimeMillis()}.$ext"))
}

/**
 * Photos (and optionally videos) from the camera or the gallery, everywhere images can be added.
 * [multiple] lets the gallery return several items.
 */
@Composable
fun rememberMediaPicker(video: Boolean = false, multiple: Boolean = false, onPicked: (List<Uri>) -> Unit): MediaPicker {
    val S = LocalStrings.current
    val ctx = LocalContext.current
    var sheet by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Uri?>(null) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onPicked(listOf(u)) } }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { if (it.isNotEmpty()) onPicked(it) }

    if (sheet) PxSheet({ sheet = false }) {
        @Composable
        fun option(icon: String, label: String, action: () -> Unit) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { sheet = false; action() }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelIcon(icon, 28.dp)
                Spacer(Modifier.width(14.dp))
                Text(label, style = T.body.copy(fontSize = 18.sp))
            }
        }
        // the app's own camera: the result looks like the preview (also with the front camera)
        option("camera", if (video) S.takePhotoOrVideo else S.takePhoto) { CameraOverlay.open(video) { onPicked(listOf(it)) } }
        option("ui_image", S.fromGallery) {
            val type = if (video) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly
            if (multiple) many.launch(PickVisualMediaRequest(type)) else one.launch(PickVisualMediaRequest(type))
        }
    }
    return remember { MediaPicker { sheet = true } }
}
