package com.momentum.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors the JSON of the Quart API (server/service.py: state()).

@Serializable
data class Meta(
    val vehicles: List<Vehicle>, val icons: List<String>, val timezones: List<String>,
    /** newest app version on the server */
    val app: AppRelease = AppRelease(),
    /** sign in with Google (server/google.py): the web client id = the Credential Manager's serverClientId */
    @SerialName("google_client_id") val googleClientId: String? = null,
    /** e-mails with codes work: "forgot password" is offered (server/mail.py) */
    val mail: Boolean = false,
)

@Serializable
data class AppRelease(
    @SerialName("version_code") val versionCode: Int? = null,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("update_url") val updateUrl: String = "/",
)

@Serializable
data class Vehicle(val key: String, val label: String, val category: String)

@Serializable
data class AppState(
    /** challenge rewards not paid out yet (each new day fills the room left under the limit) */
    @SerialName("challenge_credit") val challengeCredit: Double = 0.0,
    val unread: Unread = Unread(),
    val user: UserInfo,
    /** The value the player plays with: ranked momentum, or the free score without the daily limit. */
    val momentum: Double,
    @SerialName("ranked_momentum") val rankedMomentum: Double = momentum,
    @SerialName("free_score") val freeScore: Double = momentum,
    val vehicle: VehicleInfo,
    val settings: UserSettings,
    val decay: Decay,
    val rules: List<Rule>,
    val events: List<Event>,
    val limit: DailyLimit = DailyLimit(),
)

/** Daily momentum limit: yesterday's average of all active players + bias. */
@Serializable
data class DailyLimit(
    val cap: Double = 1e9,
    val gained: Double = 0.0,
    val remaining: Double = 1e9,
    val average: Double = 0.0,
    val bias: Double = 100.0,
    val players: Int = 0,
    /** today's average growth so far and the rate it would give tomorrow (report #46) */
    @kotlinx.serialization.SerialName("today_average") val todayAverage: Double = 0.0,
    @kotlinx.serialization.SerialName("today_players") val todayPlayers: Int = 0,
    @kotlinx.serialization.SerialName("next_cap") val nextCap: Double? = null,
    /** the day before's limit (null on the very first day): a day's first start says when it changed (LimitNews) */
    @kotlinx.serialization.SerialName("prev_cap") val prevCap: Double? = null,
    /** server day the values belong to, and the server's time zone */
    val day: String? = null,
    val tz: String = "Europe/Berlin",
    /** offline after midnight: today's limit isn't known yet, yesterday's is used until the next sync */
    val provisional: Boolean = false,
) {
    /**
     * Offline over midnight: yesterday's count no longer applies. Today starts at 0 with
     * yesterday's limit as a stand-in (the server corrects it when the taps are synced).
     */
    fun forToday(): DailyLimit {
        val d = day ?: return this
        val today = runCatching { java.time.LocalDate.now(java.time.ZoneId.of(tz)).toString() }.getOrNull() ?: return this
        return if (today == d) this else copy(day = today, gained = 0.0, remaining = cap, provisional = true)
    }

    /** Same limit with a new count of today's net momentum. */
    fun counted(newGained: Double): DailyLimit {
        val g = kotlin.math.max(0.0, newGained)
        return copy(gained = g, remaining = kotlin.math.max(0.0, cap - g))
    }
}

@Serializable
data class UserInfo(
    val id: Int? = null,
    val username: String,
    @SerialName("display_name") val displayName: String,
    val bio: String = "",
    val avatar: String? = null,
    val email: String? = null,
    /** signed in with Google: its address (shown only; never a login) */
    @SerialName("google_email") val googleEmail: String? = null,
    /** false for Google-only accounts: no password to change */
    @SerialName("has_password") val hasPassword: Boolean = true,
    @SerialName("needs_username") val needsUsername: Boolean = false,
    /** the address still to confirm: when it's due, and whether the app waits for it now (server/mail.py) */
    @SerialName("email_confirm") val emailConfirm: EmailConfirm? = null,
    /** the Larper election is running (server/nine.py): the home tab points to it */
    val election: Boolean = false,
    val private: Boolean = false,
    /** banner behind the current vehicle */
    val banner: String? = null,
    /** chosen banner per vehicle type */
    val banners: OwnBanners = OwnBanners(),
    /** hyperspace banner unlocked (1 million momentum) */
    val hyperspace: Boolean = false,
    /** the secret golden hyperspace banner unlocked (16 million) */
    val ultra: Boolean = false,
    val followers: Int = 0,
    val following: Int = 0,
)

@Serializable
data class OwnBanners(val land: String? = null, val air: String? = null, val space: String? = null) {
    fun of(kind: String) = when (kind) { "air" -> air; "space" -> space; else -> land }
}

@Serializable
data class Unread(
    val messages: Int = 0, val notifications: Int = 0,
    @SerialName("latest_message_id") val latestMessageId: Int = 0,
    @SerialName("latest_notification_id") val latestNotificationId: Int = 0,
)

@Serializable
data class VehicleInfo(
    val index: Int, @SerialName("next_at") val nextAt: Double? = null,
    @SerialName("reached_at") val reachedAt: Double = 0.0,
    val milestones: List<Double> = emptyList(),                       // momentum needed per vehicle (doubling)
    @SerialName("hyperspace_at") val hyperspaceAt: Double = 4_000_000.0,
) {
    /** How far it is from this vehicle to the next one (0..1). */
    fun progress(momentum: Double) = nextAt?.let { if (it > reachedAt) (momentum - reachedAt) / (it - reachedAt) else 1.0 } ?: 1.0
}

@Serializable
data class UserSettings(
    val timezone: String,
    @SerialName("milestone_step") val milestoneStep: Int,
    val haptics: Boolean,
    @SerialName("show_history") val showHistory: Boolean,
    /** on: takes part in the community competition (ranked momentum with the daily limit) */
    @SerialName("daily_limit") val dailyLimit: Boolean = true,
)

@Serializable
data class Decay(
    val base: Double,
    val threshold: Double,
    @SerialName("catalyst_mode") val catalystMode: String,
    @SerialName("catalyst_a") val catalystA: Double,
    @SerialName("catalyst_b") val catalystB: Double,
    val streak: Int,
    @SerialName("today_gain") val todayGain: Double,
    val tonight: Double,
    /** others may follow my own decay */
    val public: Boolean = false,
    /** null: my own decay; else the followed one (it replaces mine) */
    val follow: DecayFollow? = null,
    /** a running challenge sets my decay (title) */
    @SerialName("locked_by") val lockedBy: String? = null,
)

@Serializable
data class DecayFollow(
    val kind: String, val label: String = "", val username: String? = null,
    @SerialName("decay_id") val decayId: Int? = null, @SerialName("group_id") val groupId: Int? = null, val name: String? = null,
)

@Serializable
data class Rule(
    val id: Int,
    val kind: String,
    val name: String,
    val icon: String,
    val mode: String,
    val base: Double,
    val param: Double,
    @SerialName("min_value") val minValue: Double,
    @SerialName("reset_days") val resetDays: Int,
    val value: Double,
    val public: Boolean = false,
    @SerialName("client_id") val clientId: String? = null,
    /** own | sub (subscribed from a profile/chat) | group | challenge — only "own" is editable. */
    val origin: String = "own",
    @SerialName("origin_label") val originLabel: String = "",
    @SerialName("root_id") val rootId: Int? = null,
    @SerialName("challenge_id") val challengeId: Int? = null,
    /** not tapped for idleDays -> the rule idleRuleId (other kind) is tapped automatically */
    @SerialName("idle_days") val idleDays: Int = 0,
    @SerialName("idle_rule_id") val idleRuleId: Int? = null,
    /** a running challenge I take part in plays with this subscription: it stays until I leave */
    @SerialName("locked_by") val lockedBy: String? = null,
    /** the automatic tap is paused (possible for subscribed rules too) */
    @SerialName("idle_paused") val idlePaused: Boolean = false,
    /** false: a tap on the card doesn't count (only the automatic tap); copies keep it, the owner decides */
    val tappable: Boolean = true,
    /** unit of resetDays / idleDays: "d" days or "h" hours */
    @SerialName("reset_unit") val resetUnit: String = "d",
    @SerialName("idle_unit") val idleUnit: String = "d",
) {
    val isAcc get() = kind == "acc"
    val isOwn get() = origin == "own"
}

@Serializable
data class Event(
    val id: Int = 0,                 // negative: tapped offline, not synced yet
    val kind: String,
    val label: String,
    val icon: String,
    val delta: Double,
    val at: String,
    val undoable: Boolean = false,
    /** automatic tap: the name of the rule that was left untapped */
    @SerialName("auto_from") val autoFrom: String? = null,
)

@Serializable
data class StateResponse(
    val state: AppState,
    val delta: Double? = null,
    val milestone: Boolean = false,
    val capped: Boolean = false,
    /** this tap crossed 1 million: the spaceship jumps into hyperspace */
    val hyperspace: Boolean = false,
)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class EmailConfirm(val due: String, val required: Boolean)

/** A confirm code went out: the state (maybe with a new address), where to, when another may be sent. */
@Serializable
data class ConfirmSentResponse(val state: AppState, @SerialName("sent_to") val sentTo: String, @SerialName("resend_in") val resendIn: Int = 60)
