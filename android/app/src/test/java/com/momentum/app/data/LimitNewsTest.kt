package com.momentum.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The limit's change since the day before; the same cases as web/tests/unit/limitnews.test.ts. */
class LimitNewsTest {
    private val limit = DailyLimit(cap = 400.0, average = 312.0, prevCap = 200.0, day = "2026-11-05")

    @Test
    fun aNewDayWithAChangedLimitUpOrDown() {
        assertEquals(LimitNews("2026-11-05", 400.0, 200.0, 312.0), LimitNews.change(limit, true, "2026-11-04"))
        assertTrue(LimitNews.change(limit, true, "2026-11-04")!!.up)
        val down = LimitNews.change(limit.copy(cap = 100.0), true, "2026-11-01")!!
        assertEquals(100.0, down.cap, 0.0)
        assertEquals(false, down.up)
    }

    @Test
    fun nothingOnAFreshInstallASeenDayNoChangeAStandInOrWithoutTheLimit() {
        assertNull(LimitNews.change(limit, true, null))
        assertNull(LimitNews.change(limit, true, "2026-11-05"))
        assertNull(LimitNews.change(limit.copy(prevCap = 400.0), true, "2026-11-04"))
        assertNull(LimitNews.change(limit.copy(prevCap = null), true, "2026-11-04"))
        assertNull(LimitNews.change(limit.copy(provisional = true), true, "2026-11-04"))
        assertNull(LimitNews.change(limit.copy(day = null), true, "2026-11-04"))
        assertNull(LimitNews.change(limit, false, "2026-11-04"))
    }
}
