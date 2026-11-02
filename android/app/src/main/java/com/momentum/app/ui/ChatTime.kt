package com.momentum.app.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Chat times in the player's time zone (report #49): the time on every message and day separators like
// WhatsApp (today, yesterday, a weekday within the last week, else the date); the countdown under a chat
// photo until only its small copy is left (report #48). Same rules as web/src/chattime.ts.

private fun zoneOf(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrDefault(ZoneId.of("Europe/Berlin"))

private fun instant(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

/** The calendar day of a message in the time zone. */
fun chatDay(iso: String, tz: String): LocalDate? = instant(iso)?.atZone(zoneOf(tz))?.toLocalDate()

/** "14:05" in the time zone. */
fun chatClock(iso: String, tz: String): String =
    instant(iso)?.atZone(zoneOf(tz))?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""

/** The minute a message was sent in the time zone. */
fun chatMinute(iso: String, tz: String): String = "${chatDay(iso, tz)} ${chatClock(iso, tz)}"

/** The separator above a day's first message. */
fun chatDayLabel(day: LocalDate, tz: String, S: Strings): String {
    val today = LocalDate.now(zoneOf(tz))
    val ago = ChronoUnit.DAYS.between(day, today)
    val locale = if (S.lang == Lang.DE) Locale.GERMAN else Locale.ENGLISH
    return when {
        ago == 0L -> S.today
        ago == 1L -> S.yesterday
        ago in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> {
            val pattern = when {
                S.lang == Lang.DE -> if (day.year == today.year) "d. MMMM" else "d. MMMM yyyy"
                else -> if (day.year == today.year) "MMMM d" else "MMMM d, yyyy"
            }
            day.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}

/** Time left until [expiresAt]: whole days and "hh:mm" (null when it's over). */
fun photoCountdown(expiresAt: String): Pair<Int, String>? {
    val end = instant(expiresAt) ?: return null
    val left = Duration.between(Instant.now(), end).toMinutes()
    if (left <= 0) return null
    return (left / 1440).toInt() to "%02d:%02d".format((left / 60) % 24, left % 60)
}
