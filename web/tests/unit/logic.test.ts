import { describe, expect, it } from "vitest"
import { applied, catalyst, fmt, fmtShort, nextValue } from "../../src/format"
import { applyLocal, applyTap, limitCounted, moveRule, revertLocal, vehicleFor } from "../../src/logic"
import type { AppState, Rule } from "../../src/model"

const LADDER = [0, 1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000, 256000, 512000, 1_000_000, 2_000_000]

function rule(p: Partial<Rule>): Rule {
  return { id: 1, kind: "acc", name: "Sport", icon: "star", mode: "constant", base: 100, param: 0, min_value: 0, reset_days: 0, value: 100, origin: "own", ...p }
}

function state(p: Partial<AppState> = {}): AppState {
  return {
    unread: { messages: 0, notifications: 0 },
    user: { username: "ann", display_name: "ann" },
    momentum: 900, ranked_momentum: 900, free_score: 900,
    vehicle: { index: 0, reached_at: 0, next_at: 1000, milestones: LADDER, hyperspace_at: 4_000_000 },
    settings: { timezone: "Europe/Berlin", milestone_step: 1000, haptics: true, show_history: true, daily_limit: true },
    decay: { base: 50, threshold: 200, catalyst_mode: "none", catalyst_a: 0, catalyst_b: 1, streak: 0, today_gain: 0, tonight: 50 },
    rules: [rule({}), rule({ id: 2, kind: "dec", name: "Doom", value: 40, base: 40 })],
    events: [],
    limit: { cap: 1000, gained: 0, remaining: 1000, average: 0, bias: 100, players: 0 },
    ...p,
  }
}

let n = 0
const tempId = () => -(++n)

describe("format (same as Format.kt)", () => {
  it("rounds like the server: accelerators up, decelerators down", () => {
    expect(applied("acc", 10.2)).toBe(11)
    expect(applied("dec", 10.8)).toBe(10)
    expect(applied("acc", 10.0000001)).toBe(10)        // float noise isn't a fraction
  })
  it("formats momentum with German grouping and short big numbers", () => {
    expect(fmt(1234567)).toBe("1.234.567")
    expect(fmtShort(950)).toBe("950")
    expect(fmtShort(4_000_000)).toMatch(/^4M$/)
  })
  it("rule and decay formulas match server/momentum.py", () => {
    expect(nextValue("linear", 100, 30, 20)).toBe(70)
    expect(nextValue("linear", 30, 30, 20)).toBe(20)
    expect(nextValue("exponential", 100, .5, 10)).toBe(50)
    expect(catalyst("linear", 10, 0, 3)).toBe(30)
    expect(catalyst("exponential", 10, 2, 3)).toBe(40)
    expect(catalyst("none", 10, 2, 3)).toBe(0)
  })
})

describe("milestone ladder", () => {
  it("finds the vehicle on the doubling ladder sent by the server", () => {
    const v = vehicleFor(5000, state().vehicle)
    expect(v.index).toBe(3)
    expect(v.reached_at).toBe(4000)
    expect(v.next_at).toBe(8000)
    expect(vehicleFor(3_000_000, state().vehicle).next_at).toBeNull()
  })
})

describe("taps (applied locally like the server)", () => {
  it("adds momentum, logs the event, counts the limit and passes a milestone", () => {
    const t = applyTap(state(), rule({}), "2026-10-18T10:00:00Z", tempId, (i) => ["fahrrad", "motorroller"][i])!
    expect(t.state.momentum).toBe(1000)
    expect(t.milestone).toBe(true)
    expect(t.state.vehicle.index).toBe(1)
    expect(t.state.events[0]).toMatchObject({ kind: "milestone", label: "motorroller" })
    expect(t.state.events[1]).toMatchObject({ kind: "acc", delta: 100, undoable: true })
    expect(t.state.limit.gained).toBe(100)
    expect(t.state.decay.today_gain).toBe(100)
  })
  it("caps at the daily limit and refuses when it's used up", () => {
    const st = state({ limit: { ...state().limit, gained: 950, remaining: 50 } })
    const t = applyTap(st, rule({}), "x", tempId, () => "")!
    expect(t.capped).toBe(true)
    expect(t.state.momentum).toBe(950)
    expect(applyTap(state({ limit: { ...state().limit, gained: 1000, remaining: 0 } }), rule({}), "x", tempId, () => "")).toBeNull()
  })
  it("a used-up limit still takes a +0 tap that keeps the automatic decelerator away", () => {
    const full = state({ limit: { ...state().limit, gained: 1000, remaining: 0 } })
    const guard = rule({ idle_days: 2, idle_rule_id: 2 })
    const t = applyTap(full, guard, "x", tempId, () => "")!
    expect(t.capped).toBe(true)
    expect(t.amount).toBe(0)
    expect(t.state.momentum).toBe(900)
    expect(t.state.events[0]).toMatchObject({ kind: "acc", delta: 0 })
    expect(applyTap(full, { ...guard, idle_paused: true }, "x", tempId, () => "")).toBeNull()      // paused: nothing to keep away
  })
  it("decelerators always work and never go below zero", () => {
    const t = applyTap(state({ momentum: 10, limit: { ...state().limit, remaining: 0 } }), rule({ id: 2, kind: "dec", value: 40 }), "x", tempId, () => "")!
    expect(t.state.momentum).toBe(0)
  })
  it("without the daily limit the free score moves and nothing is capped", () => {
    const st = state({ settings: { ...state().settings, daily_limit: false }, limit: { ...state().limit, remaining: 0 } })
    const t = applyTap(st, rule({}), "x", tempId, () => "")!
    expect(t.state.momentum).toBe(1000)
    expect(t.state.ranked_momentum).toBe(900)
  })
  it("a linear rule gets weaker with every tap", () => {
    const t = applyTap(state(), rule({ mode: "linear", param: 30, min_value: 20 }), "x", tempId, () => "")!
    expect(t.state.rules[0].value).toBe(70)
  })
  it("undo takes the tap back", () => {
    const t = applyTap(state(), rule({}), "x", tempId, () => "")!
    const back = revertLocal(t.state, t.state.events.find((e) => e.kind === "acc")!, ["fahrrad", "motorroller"])
    expect(back.momentum).toBe(900)
    expect(back.events).toHaveLength(0)            // the milestone is gone too
    expect(back.limit.gained).toBe(0)
  })
  it("a rule that can't be tapped changes nothing", () => {
    expect(applyTap(state(), rule({ tappable: false }), "x", tempId, () => "")).toBeNull()
  })
  it("the limit counts net momentum, never below zero", () => {
    expect(limitCounted(state().limit, -50)).toMatchObject({ gained: 0, remaining: 1000 })
  })
})

describe("offline changes", () => {
  it("creates, edits and deletes rules locally", () => {
    let st = applyLocal(state(), "POST", "/rules", { kind: "acc", name: "Lesen", base: 33.2, client_id: "c1" }, tempId)
    const created = st.rules.at(-1)!
    expect(created).toMatchObject({ name: "Lesen", value: 34, client_id: "c1" })
    expect(created.id).toBeLessThan(0)
    st = applyLocal(st, "PUT", `/rules/${created.id}`, { name: "Lesen!", base: 50 }, tempId)
    expect(st.rules.at(-1)).toMatchObject({ name: "Lesen!", value: 50 })
    st = applyLocal(st, "DELETE", `/rules/${created.id}`, undefined, tempId)
    expect(st.rules.some((r) => r.name === "Lesen!")).toBe(false)
  })
  it("decay settings update tonight's loss", () => {
    const st = applyLocal(state(), "PUT", "/decay", { base: 80, catalyst_mode: "linear", catalyst_a: 10 }, tempId)
    expect(st.decay.tonight).toBe(90)             // base + catalyst for a missed day (today 0 < threshold)
  })
})

describe("sorting with the arrows", () => {
  it("moves one step within its kind only", () => {
    const rules = [rule({ id: 1 }), rule({ id: 2, kind: "dec" }), rule({ id: 3 }), rule({ id: 4 })]
    expect(moveRule(rules, 3, -1)!.map((r) => r.id)).toEqual([3, 1, 4, 2])
    expect(moveRule(rules, 1, -1)).toBeNull()
    expect(moveRule(rules, 4, 1)).toBeNull()
  })
})
