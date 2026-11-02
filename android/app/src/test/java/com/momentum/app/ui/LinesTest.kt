package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Group member lines (report #56) and subscription activity (report #54); the same cases as web/tests/unit/lines.test.ts. */
class LinesTest {
    private val de = De

    @Test
    fun memberLines() {
        assertEquals("Ann hat Ben hinzugefügt", chatLine(de, "member_add", "Ann", "Ben"))
        assertEquals("Du hast Ben hinzugefügt", chatLine(de, "member_add", null, "Ben"))
        assertEquals("Ann hat dich hinzugefügt", chatLine(de, "member_add", "Ann", null))
        assertEquals("Ann hat Ben entfernt", chatLine(de, "member_remove", "Ann", "Ben"))
        assertEquals("Dan ist beigetreten", chatLine(de, "member_join", "Dan"))
        assertEquals("Dan hat die Gruppe verlassen", chatLine(de, "member_leave", "Dan"))
        assertEquals("🔒 Du hast die Ende-zu-Ende-Verschlüsselung eingeschaltet", chatLine(de, "e2e_on", null))
    }

    @Test
    fun subscriptionActivity() {
        assertEquals("hat deine Regel „Push-ups“ abonniert.", activityText(de, "sub_rule", "Push-ups", null))
        assertEquals("hat deine Regel „Push-ups“ für die Gruppe Gym abonniert.", activityText(de, "sub_rule", "Push-ups", "Gym"))
        assertEquals("folgt jetzt deinem Decay.", activityText(de, "sub_decay", null, null))
        assertEquals("hat deinen Decay in die Gruppe Gym übernommen.", activityText(de, "sub_decay", null, "Gym"))
        assertEquals("folgt dir jetzt.", activityText(de, "follow", null, null))
    }
}
