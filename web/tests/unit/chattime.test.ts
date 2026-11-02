import { describe, expect, it } from "vitest"
import { clock, countdown, dayKey, dayLabel } from "../../src/chattime"
import { chainEnds, withDays } from "../../src/screens/Chat"

const NOW = new Date("2026-10-22T10:00:00Z")      // Thursday, 12:00 in Berlin
const label = (iso: string, lang = "de", tz = "Europe/Berlin") => dayLabel(iso, tz, lang, NOW, "Heute", "Gestern")

describe("chat times (report #49)", () => {
  it("shows the time in the player's time zone", () => {
    expect(clock("2026-10-22T10:05:00Z", "Europe/Berlin")).toBe("12:05")
    expect(clock("2026-10-22T10:05:00Z", "America/New_York")).toBe("06:05")
  })

  it("cuts days at local midnight", () => {
    expect(dayKey("2026-10-21T22:30:00Z", "Europe/Berlin")).toBe("2026-10-22")
    expect(dayKey("2026-10-21T22:30:00Z", "UTC")).toBe("2026-10-21")
  })

  it("labels today, yesterday, a weekday within the week, else the date", () => {
    expect(label("2026-10-21T22:30:00Z")).toBe("Heute")
    expect(label("2026-10-21T08:00:00Z")).toBe("Gestern")
    expect(label("2026-10-19T08:00:00Z")).toBe("Montag")
    expect(label("2026-10-19T08:00:00Z", "en")).toBe("Monday")
    expect(label("2026-10-01T08:00:00Z")).toBe("1. Oktober")
    expect(label("2025-12-24T08:00:00Z", "en")).toBe("December 24, 2025")
  })

  it("puts a marker before each new day", () => {
    const m = (id: number, at: string) => ({ m: { id, at }, photos: [] })
    const out = withDays([m(1, "2026-10-21T08:00:00Z"), m(2, "2026-10-21T09:00:00Z"), m(3, "2026-10-22T08:00:00Z")], "Europe/Berlin")
    expect(out.map((x) => ("day" in x ? x.day : x.m.id))).toEqual(["2026-10-21", 1, 2, "2026-10-22", 3])
  })
})

describe("photo countdown (report #48)", () => {
  it("days and hh:mm until the full picture goes", () => {
    expect(countdown("2026-11-05T14:12:30Z", NOW)).toEqual({ days: 14, hm: "04:12" })
    expect(countdown("2026-10-22T10:30:00Z", NOW)).toEqual({ days: 0, hm: "00:30" })
    expect(countdown("2026-10-22T09:00:00Z", NOW)).toBeNull()
  })
})

describe("runs of messages (picture and time once)", () => {
  it("ends a run at another sender, another minute or the end", () => {
    const msg = (id: number, at: string, who: string) => ({ m: { id, at, user: { username: who } as never, mine: who === "me" } })
    const groups = [
      msg(1, "2026-10-22T10:00:05Z", "ann"), msg(2, "2026-10-22T10:00:40Z", "ann"),   // same minute: only 2
      msg(3, "2026-10-22T10:01:10Z", "ann"),                                            // next minute
      msg(4, "2026-10-22T10:01:20Z", "me"), msg(5, "2026-10-22T10:01:30Z", "me"),
    ]
    expect([...chainEnds(groups, "Europe/Berlin")]).toEqual([2, 3, 5])
  })
})
