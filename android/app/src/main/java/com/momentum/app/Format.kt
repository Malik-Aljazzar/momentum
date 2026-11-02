package com.momentum.app

import java.text.NumberFormat
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

private val intFmt = NumberFormat.getIntegerInstance(Locale.GERMANY)


/** Momentum totals: whole numbers with German grouping. */
fun fmt(n: Double): String = intFmt.format(n.roundToLong())

/** Whole numbers only: accelerators round up, decelerators round down (same as the server). */
fun applied(kind: String, value: Double): Double {
    val v = Math.round(value * 1e6) / 1e6          // float noise is not a fraction
    return if (kind == "acc") kotlin.math.ceil(v) else kotlin.math.floor(v)
}

/** Rule values are whole numbers (already rounded by the server or via [applied]). */
fun fmtVal(n: Double): String = intFmt.format(n.roundToLong())

fun signed(n: Double, f: (Double) -> String = ::fmtVal) = (if (n >= 0) "+" else "−") + f(abs(n))

/** Seconds since an ISO timestamp from the server (null if unparsable). */
fun secondsSince(iso: String): Long? = try {
    Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).seconds
} catch (e: Exception) {
    null
}

/** Same formulas as server/momentum.py, for the editor previews. */
fun catalyst(mode: String, a: Double, b: Double, streak: Int): Double = when {
    streak <= 0 || mode == "none" -> 0.0
    mode == "linear" -> a * streak
    mode == "exponential" -> a * b.pow(streak - 1)
    else -> a * (1 - b.pow(streak))
}

fun nextValue(mode: String, value: Double, param: Double, min: Double): Double = when (mode) {
    "linear" -> maxOf(min, value - param)
    "exponential" -> maxOf(min, value * param)
    else -> value
}

fun parseNum(s: String): Double? = s.replace(',', '.').toDoubleOrNull()


/** Big numbers shortened: 950 · 12,3K · 1,2M (whole numbers below 10,000 stay as they are). */
fun fmtShort(n: Double): String {
    val a = kotlin.math.abs(n)
    fun one(v: Double) = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(v)
    return when {
        a >= 1e9 -> one(n / 1e9) + "B"
        a >= 1e6 -> one(n / 1e6) + "M"
        a >= 1e4 -> one(n / 1e3) + "K"
        else -> fmt(n)
    }
}
