package com.momentum.app

import android.app.Application
import android.os.Build
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import com.momentum.app.ui.Fonts

class MomentumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // the pixel emoji font as the fallback of the app's fonts (Android 10+)
        Fonts.init(this)
        // calls: pushes can ring before any screen is open (data/Calls.kt)
        com.momentum.app.data.Calls.init(this)
        // Android 8/9 can't chain fonts: there the downloadable emoji font fills in emojis the phone doesn't know
        // (its automatic start is off in the manifest - it would draw its own emojis over the pixel ones)
        if (Build.VERSION.SDK_INT < 29) DefaultEmojiCompatConfig.create(this)?.let { EmojiCompat.init(it) }
    }
}
