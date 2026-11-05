import { beforeEach, describe, expect, it } from "vitest"
import { checkLimitNews, limitChange } from "../../src/limitnews"
import type { AppState, DailyLimit } from "../../src/model"

function state(limit: Partial<DailyLimit> = {}, dailyLimit = true): AppState {
  return {
    unread: { messages: 0, notifications: 0 },
    user: { username: "ann", display_name: "ann" },
    momentum: 0, ranked_momentum: 0, free_score: 0,
    vehicle: { index: 0, reached_at: 0, next_at: 1000, milestones: [0, 1000], hyperspace_at: 4_000_000 },
    settings: { timezone: "Europe/Berlin", milestone_step: 1000, haptics: true, show_history: true, daily_limit: dailyLimit },
    decay: { base: 50, threshold: 200, catalyst_mode: "none", catalyst_a: 0, catalyst_b: 1, streak: 0, today_gain: 0, tonight: 50 },
    rules: [], events: [],
    limit: { cap: 400, gained: 0, remaining: 400, average: 312, bias: 400, players: 6, prev_cap: 200, day: "2026-11-05", ...limit },
  } as AppState
}

describe("limit news (= LimitNews.kt)", () => {
  beforeEach(() => localStorage.clear())

  it("a new day with a changed limit: up or down, with both numbers", () => {
    expect(limitChange(state(), "2026-11-04")).toEqual({ day: "2026-11-05", cap: 400, prev: 200, average: 312 })
    expect(limitChange(state({ cap: 100, prev_cap: 200 }), "2026-11-01")).toMatchObject({ cap: 100, prev: 200 })
  })

  it("nothing on a fresh install, a day already seen, no change, no limit before, a stand-in, without the limit", () => {
    expect(limitChange(state(), null)).toBeNull()
    expect(limitChange(state(), "2026-11-05")).toBeNull()
    expect(limitChange(state({ prev_cap: 400 }), "2026-11-04")).toBeNull()
    expect(limitChange(state({ prev_cap: null }), "2026-11-04")).toBeNull()
    expect(limitChange(state({ provisional: true }), "2026-11-04")).toBeNull()
    expect(limitChange(state({ day: null }), "2026-11-04")).toBeNull()
    expect(limitChange(state({}, false), "2026-11-04")).toBeNull()
  })

  it("once per day and device: the first state of a day counts it as seen", () => {
    expect(checkLimitNews(state())).toBeNull()                          // fresh install: only remembered
    expect(localStorage.getItem("limit_seen_day")).toBe(JSON.stringify("2026-11-05"))
    localStorage.setItem("limit_seen_day", JSON.stringify("2026-11-04"))
    expect(checkLimitNews(state())).toMatchObject({ cap: 400, prev: 200 })
    expect(checkLimitNews(state())).toBeNull()                          // the next state of the same day
    // offline over midnight: the stand-in doesn't use up the day
    localStorage.setItem("limit_seen_day", JSON.stringify("2026-11-04"))
    expect(checkLimitNews(state({ provisional: true }))).toBeNull()
    expect(localStorage.getItem("limit_seen_day")).toBe(JSON.stringify("2026-11-04"))
  })
})
