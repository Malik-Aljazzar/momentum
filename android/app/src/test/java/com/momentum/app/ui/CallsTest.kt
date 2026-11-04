package com.momentum.app.ui

import com.momentum.app.data.CallInfo
import com.momentum.app.data.CallLine
import com.momentum.app.data.CallPeer
import com.momentum.app.data.CallPhase
import com.momentum.app.data.CallUser
import com.momentum.app.data.CallView
import com.momentum.app.data.inCall
import com.momentum.app.data.offersTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls: the same cases as web/tests/unit/calls.test.ts. */
class CallsTest {
    private val de = De
    private val en = En
    private fun line(video: Boolean = false, group: Boolean = false, duration: Int? = null, reason: String? = "missed",
                     outgoing: Boolean = false, joined: Boolean = false) = CallLine(video, group, duration, reason, outgoing, joined)

    @Test
    fun linesTellTheLengthOfACallIWasIn() {
        assertEquals("📞 Sprachanruf · 3:24", callLine(de, line(duration = 204, joined = true, reason = "done")))
        assertEquals("📹 Ausgehender Videoanruf · 0:42", callLine(de, line(duration = 42, outgoing = true, joined = true, reason = "done", video = true)))
        assertEquals("📞 Group call · 1:04:09", callLine(en, line(duration = 3849, joined = true, group = true, reason = "done")))
        assertEquals("Sprachanruf · 3:24", callLine(de, line(duration = 204, joined = true, reason = "done"), emoji = false))
    }

    @Test
    fun linesTellWhyNobodyTalked() {
        assertEquals("📞 Verpasster Sprachanruf", callLine(de, line()))
        assertEquals("📹 Verpasster Videoanruf", callLine(de, line(video = true)))
        assertEquals("📞 Verpasster Gruppenanruf", callLine(de, line(group = true, duration = 300)))
        assertEquals("📞 Sprachanruf · keine Antwort", callLine(de, line(outgoing = true)))
        assertEquals("📞 Sprachanruf · abgelehnt", callLine(de, line(outgoing = true, reason = "declined")))
        assertEquals("📹 Video call · busy", callLine(en, line(outgoing = true, reason = "busy", video = true)))
        assertTrue(callMissed(line()))
        assertFalse(callMissed(line(outgoing = true)))
    }

    @Test
    fun durations() {
        assertEquals(listOf("0:00", "0:59", "1:00", "1:00:00"), listOf(0, 59, 60, 3600).map(::callDuration))
    }

    private fun info(id: Int = 5, video: Boolean = false, status: String = "active", participants: List<CallPeer>? = null) = CallInfo(
        id = id, conversationId = 9, video = video, status = status, participants = participants ?: listOf(
            CallPeer(CallUser(1, "ann", "Ann"), "joined", 1),
            CallPeer(CallUser(2, "ben", "Ben"), "joined", 2),
            CallPeer(CallUser(3, "cat", "Cat"), "ringing", 0),
        ))

    private fun view(phase: CallPhase, info: CallInfo = info(), linked: Map<Int, Boolean> = emptyMap(), endText: String? = null) =
        CallView(info, phase, "Ann", null, linked = linked, endText = endText)

    @Test
    fun theLaterOneOffersToWhoIsIn() {
        assertTrue(offersTo(2, 1))
        assertFalse(offersTo(1, 2))
        assertEquals(listOf("ben"), inCall(info(), 1).map { it.user.username })
    }

    @Test
    fun theStatusLineRingsConnectsCounts() {
        assertEquals("Eingehender Videoanruf", callStatus(de, view(CallPhase.Incoming, info(video = true)), null, 1))
        assertEquals("Klingelt …", callStatus(de, view(CallPhase.Outgoing, info(status = "ringing")), null, 1))
        assertEquals("Ruft an …", callStatus(de, view(CallPhase.Outgoing, info(id = 0, participants = emptyList())), null, 1))
        assertEquals("Verbinden …", callStatus(de, view(CallPhase.Connecting), null, 1))
        assertEquals("1:15", callStatus(de, view(CallPhase.Active, linked = mapOf(2 to true)), 75, 1))
        assertEquals("Verbindung wird wiederhergestellt …", callStatus(de, view(CallPhase.Active, linked = mapOf(2 to false)), 75, 1))
        assertEquals("Busy", callStatus(en, view(CallPhase.Ended, endText = en.callBusy), null, 1))
    }
}
