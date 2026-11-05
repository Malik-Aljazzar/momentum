package com.momentum.app.data

/**
 * The daily limit changed since the day before (web: src/limitnews.ts): the first state from the server on a
 * new day brings a sheet "higher / lower than yesterday" - once per day and device, for players in the
 * competition (daily limit on). It waits until the what's-new sheet is closed (ui/App.kt): one after the other.
 */
data class LimitNews(val day: String, val cap: Double, val prev: Double, val average: Double) {
    val up get() = cap > prev

    companion object {
        /** What to show for [limit] when this device last saw the server day [seen]. Nothing on a fresh install
         *  (seen null), for a day already seen, a stand-in limit (offline over midnight), players without the
         *  limit, or no change. */
        fun change(limit: DailyLimit, competing: Boolean, seen: String?): LimitNews? {
            val day = limit.day ?: return null
            if (limit.provisional || seen == null || seen == day || !competing) return null
            val prev = limit.prevCap ?: return null
            return if (prev == limit.cap) null else LimitNews(day, limit.cap, prev, limit.average)
        }

        /** A state from the server: its day counts as seen on this device; returns what to show (null = nothing). */
        fun check(prefs: Prefs, state: AppState): LimitNews? {
            val day = state.limit.day ?: return null
            if (state.limit.provisional) return null
            val seen = prefs.limitSeenDay
            if (seen == day) return null
            prefs.limitSeenDay = day
            return change(state.limit, state.settings.dailyLimit, seen)
        }
    }
}
