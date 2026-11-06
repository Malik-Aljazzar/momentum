package com.momentum.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The home tab's card while notifications are off (calls ring only in the open app); = web/tests/unit/push.test.ts. */
class NotifyHintTest {
    private fun hint(wanted: Boolean = true, enabled: Boolean = false, callsOn: Boolean = true, canAsk: Boolean = true,
                     hiddenUntil: Long = 0, now: Long = 1_000) = notifyHint(wanted, enabled, callsOn, canAsk, hiddenUntil, now)

    @Test
    fun asksWhileAndroidCanStillAskElseTheSystemSettings() {
        assertEquals(NotifyHint.Ask, hint())
        assertEquals(NotifyHint.Settings, hint(canAsk = false))
        assertNull(hint(enabled = true))
    }

    @Test
    fun theCallsChannelSwitchedOff() {
        assertEquals(NotifyHint.Calls, hint(enabled = true, callsOn = false))
        assertEquals(NotifyHint.Ask, hint(callsOn = false))              // all of them off first
    }

    @Test
    fun staysAwayWhenSwitchedOffInTheAppAndAWeekAfterNotNow() {
        assertNull(hint(wanted = false))
        val until = 1_000 + NOTIFY_HINT_DAYS * 86_400_000L
        assertNull(hint(hiddenUntil = until))
        assertEquals(NotifyHint.Ask, hint(hiddenUntil = until, now = until))
    }
}
