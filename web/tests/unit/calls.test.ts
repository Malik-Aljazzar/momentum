import { describe, expect, it } from "vitest"
import { inCall, offersTo, type CallView } from "../../src/calls"
import { callDuration, callLine, callMissed } from "../../src/lines"
import type { CallInfo, CallLine } from "../../src/model"
import { statusText } from "../../src/screens/Call"
import { DE as de, EN as en } from "../../src/strings"

const line = (over: Partial<CallLine>): CallLine =>
  ({ video: false, group: false, duration: null, reason: "missed", outgoing: false, joined: false, ...over })

describe("call lines in the chat (= Lines.kt callLine)", () => {
  it("tells the length of a call I was in", () => {
    expect(callLine(de, line({ duration: 204, joined: true, reason: "done" }))).toBe("📞 Sprachanruf · 3:24")
    expect(callLine(de, line({ duration: 42, outgoing: true, joined: true, reason: "done", video: true }))).toBe("📹 Ausgehender Videoanruf · 0:42")
    expect(callLine(en, line({ duration: 3849, joined: true, group: true, reason: "done" }))).toBe("📞 Group call · 1:04:09")
  })
  it("why nobody talked: missed for me, no answer / declined / busy for the caller", () => {
    expect(callLine(de, line({}))).toBe("📞 Verpasster Sprachanruf")
    expect(callLine(de, line({ video: true }))).toBe("📹 Verpasster Videoanruf")
    expect(callLine(de, line({ group: true, duration: 300 }))).toBe("📞 Verpasster Gruppenanruf")     // the others talked
    expect(callLine(de, line({ outgoing: true }))).toBe("📞 Sprachanruf · keine Antwort")
    expect(callLine(de, line({ outgoing: true, reason: "declined" }))).toBe("📞 Sprachanruf · abgelehnt")
    expect(callLine(en, line({ outgoing: true, reason: "busy", video: true }))).toBe("📹 Video call · busy")
    expect(callMissed(line({}))).toBe(true)
    expect(callMissed(line({ outgoing: true }))).toBe(false)
  })
  it("formats durations", () => {
    expect([callDuration(0), callDuration(59), callDuration(60), callDuration(3600)]).toEqual(["0:00", "0:59", "1:00", "1:00:00"])
  })
})

const info = (over: Partial<CallInfo>): CallInfo => ({
  id: 5, conversation_id: 9, group: false, video: false, status: "active", participants: [
    { user: { id: 1, username: "ann", display_name: "Ann" }, state: "joined", seq: 1, mic: true, cam: false },
    { user: { id: 2, username: "ben", display_name: "Ben" }, state: "joined", seq: 2, mic: true, cam: false },
    { user: { id: 3, username: "cat", display_name: "Cat" }, state: "ringing", seq: 0, mic: true, cam: false },
  ], ...over,
})

const view = (over: Partial<CallView>): CallView => ({
  info: info({}), phase: "active", title: "Ann", avatar: null, local: null, remotes: {}, linked: {}, mic: true, cam: false,
  facing: "user", minimized: false, since: null, ...over,
})

describe("the mesh", () => {
  it("the later one offers, only to who is in", () => {
    expect(offersTo(2, 1)).toBe(true)
    expect(offersTo(1, 2)).toBe(false)
    expect(inCall(info({}), 1).map((p) => p.user.username)).toEqual(["ben"])
  })
})

describe("the call screen's status line", () => {
  it("rings, connects, counts", () => {
    expect(statusText(de, view({ phase: "incoming", info: info({ video: true }) }), null)).toBe("Eingehender Videoanruf")
    expect(statusText(de, view({ phase: "outgoing", info: info({ status: "ringing" }) }), null)).toBe("Klingelt …")
    expect(statusText(de, view({ phase: "outgoing", info: info({ id: 0, participants: [] }) }), null)).toBe("Ruft an …")
    expect(statusText(de, view({ phase: "connecting" }), null)).toBe("Verbinden …")
    expect(statusText(de, view({ phase: "active", linked: { 2: true } }), 75)).toBe("1:15")
    expect(statusText(de, view({ phase: "active", linked: { 2: false } }), 75)).toBe("Verbindung wird wiederhergestellt …")
    expect(statusText(en, view({ phase: "ended", endText: en.callBusy }), null)).toBe("Busy")
  })
})
