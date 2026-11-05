package com.momentum.app.data

import android.content.Context
import com.momentum.app.BuildConfig
import java.util.Locale

/** Locally stored: auth token and backend address. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("momentum", Context.MODE_PRIVATE)

    var token: String?
        get() = sp.getString("token", null)
        set(value) = sp.edit().putString("token", value).apply()

    /** A custom server only applies in developer mode; otherwise always the built-in address. */
    var serverUrl: String
        get() = (if (devMode) sp.getString("server", null) else null) ?: BuildConfig.DEFAULT_SERVER
        set(value) = sp.edit().putString("server", value).apply()

    /** Messages, comments, captions and bios in the phone's own font instead of the pixel font. */
    var systemFont: Boolean
        get() = sp.getBoolean("system_font", false)
        set(value) = sp.edit().putBoolean("system_font", value).apply()

    /** System notifications for new messages and activity (background check). */
    var notifications: Boolean
        get() = sp.getBoolean("notifications", true)
        set(value) = sp.edit().putBoolean("notifications", value).apply()

    /** Newest message / notification already shown in the app or notified (no second notification). */
    var lastMessageSeen: Int
        get() = sp.getInt("last_message_seen", 0)
        set(value) = sp.edit().putInt("last_message_seen", value).apply()
    var lastNotificationSeen: Int
        get() = sp.getInt("last_notification_seen", 0)
        set(value) = sp.edit().putInt("last_notification_seen", value).apply()

    /** Developer mode: shows the server address (tap the logo / version 7 times). */
    var devMode: Boolean
        get() = sp.getBoolean("dev", false)
        set(value) = sp.edit().putBoolean("dev", value).apply()

    /** The newest changelog version shown (data/Changelog.kt); null = never, "" = show everything once. */
    var changelogSeen: String?
        get() = sp.getString("changelog_seen", null)
        set(value) = sp.edit().putString("changelog_seen", value).apply()

    /** The last server day whose limit this device saw (data/LimitNews.kt); null = fresh install. */
    var limitSeenDay: String?
        get() = sp.getString("limit_seen_day", null)
        set(value) = sp.edit().putString("limit_seen_day", value).apply()

    /** "de" or "en"; defaults to the phone language. */
    var language: String
        get() = sp.getString("language", null) ?: if (Locale.getDefault().language == "de") "de" else "en"
        set(value) = sp.edit().putString("language", value).apply()
}
