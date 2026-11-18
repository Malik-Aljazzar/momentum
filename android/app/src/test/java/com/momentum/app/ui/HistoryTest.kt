package com.momentum.app.ui

import com.momentum.app.data.Event
import org.junit.Assert.assertEquals
import org.junit.Test

/** Undo from the own history's older pages; the same cases as web/tests/unit/logic.test.ts (dropUndone). */
class HistoryTest {
    private fun ev(id: Int, kind: String, label: String = "Sport") =
        Event(id = id, kind = kind, label = label, icon = "", delta = if (kind == "acc") 100.0 else 0.0, at = "")

    private val older = listOf(ev(9, "acc"), ev(8, "milestone", "motorroller"), ev(7, "acc"), ev(6, "milestone", "fahrrad"), ev(5, "acc"))
    private val keys = listOf("fahrrad", "motorroller", "kleinwagen")

    @Test
    fun theTapAndTheMilestonesItTookBackGo() {
        // back on the first vehicle: the scooter's milestone went with the tap
        assertEquals(listOf(7, 6, 5), dropUndone(older, older[0], keys, 0).map { it.id })
        // still on the scooter: only the tap goes
        assertEquals(listOf(9, 8, 6, 5), dropUndone(older, older[2], keys, 1).map { it.id })
        // a tap that isn't in these pages: nothing changes
        assertEquals(5, dropUndone(older, ev(20, "acc"), keys, 1).size)
    }
}
