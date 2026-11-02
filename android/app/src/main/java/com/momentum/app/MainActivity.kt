package com.momentum.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.momentum.app.data.Notifier
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.momentum.app.ui.MomentumApp
import com.momentum.app.ui.MomentumTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dark UI everywhere -> light status/navigation bar icons
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        handleOpen(intent)
        setContent {
            MomentumTheme { MomentumApp(vm) }
        }
        // Android 13+: notifications need the user's OK (asked once)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this)) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> com.momentum.app.ui.Videos.soundOn = true
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val audio = getSystemService(android.media.AudioManager::class.java)
                    if (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) com.momentum.app.ui.Videos.soundOn = false
                }
            }
        }
        return handled
    }

    override fun onResume() {
        super.onResume()
        vm.onForegroundChanged(true)
    }

    override fun onPause() {
        super.onPause()
        vm.onForegroundChanged(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Tapped a notification: open that chat or the activity list. */
    private fun handleOpen(intent: Intent?) {
        intent ?: return
        val chat = intent.getIntExtra(Notifier.EXTRA_CHAT, 0)
        when {
            chat > 0 -> vm.openRequest = chat
            intent.getBooleanExtra(Notifier.EXTRA_ACTIVITY, false) -> vm.openRequest = -1
        }
    }
}
