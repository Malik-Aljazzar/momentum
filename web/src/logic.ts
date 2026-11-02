// Pure game logic the client applies locally (instant taps, offline changes) the way the server
// will - same as AppViewModel.kt (applyLocal, applyTap, vehicleFor) and DailyLimit (Models.kt).
import { applied, catalyst, nextValue } from "./format"
import type { AppState, DailyLimit, Event, Rule, VehicleInfo } from "./model"

/** The vehicle for a momentum, on the server's milestone ladder (sent with the state). */
export function vehicleFor(momentum: number, current: VehicleInfo): VehicleInfo {
  const ladder = current.milestones ?? []
  if (ladder.length === 0) return current
  let idx = 0
  ladder.forEach((m, i) => { if (momentum >= m) idx = i })
  return { ...current, index: idx, reached_at: ladder[idx], next_at: ladder[idx + 1] ?? null }
}

function todayIn(tz: string): string | null {
  try {
    // en-CA formats as YYYY-MM-DD
    return new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date())
  } catch {
    return null
  }
}

/** Offline over midnight: today starts at 0 with yesterday's limit as a stand-in. */
export function limitForToday(l: DailyLimit): DailyLimit {
  if (!l.day) return l
  const today = todayIn(l.tz ?? "Europe/Berlin")
  if (!today || today === l.day) return l
  return { ...l, day: today, gained: 0, remaining: l.cap, provisional: true }
}

/** Same limit with a new count of today's net momentum. */
export function limitCounted(l: DailyLimit, gained: number): DailyLimit {
  const g = Math.max(0, gained)
  return { ...l, gained: g, remaining: Math.max(0, l.cap - g) }
}

export function currentDay(st: AppState): AppState {
  const l = limitForToday(st.limit)
  return l === st.limit ? st : { ...st, limit: l }
}

export const RULE_PATH = /^\/rules\/(-?\d+)$/
export const OFFLINE_SETTINGS = new Set(["timezone", "haptics", "show_history"])

export function offlineCapable(method: string, path: string): boolean {
  return (method === "POST" && path === "/rules") ||
    ((method === "PUT" || method === "DELETE") && RULE_PATH.test(path)) ||
    (method === "PUT" && (path === "/decay" || path === "/settings"))
}

type Body = Record<string, unknown> | undefined
const str = (b: Body, k: string) => (typeof b?.[k] === "string" ? (b[k] as string) : undefined)
const num = (b: Body, k: string) => (typeof b?.[k] === "number" ? (b[k] as number) : undefined)
const bool = (b: Body, k: string) => (typeof b?.[k] === "boolean" ? (b[k] as boolean) : undefined)

/** Apply a queued change to the local state, the way the server will. */
export function applyLocal(st: AppState, method: string, path: string, b: Body, tempId: () => number): AppState {
  const m = path.match(RULE_PATH)
  const ruleId = m ? Number(m[1]) : null
  if (method === "POST" && path === "/rules" && b) {
    const kind = str(b, "kind") ?? "acc"
    const base = num(b, "base") ?? 0
    const rule: Rule = {
      id: tempId(), kind, name: str(b, "name") ?? "", icon: str(b, "icon") ?? "star", mode: str(b, "mode") ?? "constant",
      base, param: num(b, "param") ?? 0, min_value: num(b, "min_value") ?? 0, reset_days: num(b, "reset_days") ?? 0,
      reset_unit: str(b, "reset_unit") ?? "d",
      value: applied(kind, base), public: bool(b, "public") ?? false, client_id: str(b, "client_id") ?? null, origin: "own",
    }
    return { ...st, rules: [...st.rules, rule] }
  }
  if (method === "PUT" && ruleId != null && b) {
    return {
      ...st, rules: st.rules.map((r) => {
        if (r.id !== ruleId) return r
        const base = num(b, "base") ?? r.base
        return {
          ...r, name: str(b, "name") ?? r.name, icon: str(b, "icon") ?? r.icon, mode: str(b, "mode") ?? r.mode, base,
          param: num(b, "param") ?? r.param, min_value: num(b, "min_value") ?? r.min_value,
          reset_days: num(b, "reset_days") ?? r.reset_days, public: bool(b, "public") ?? r.public,
          idle_days: num(b, "idle_days") ?? r.idle_days,
          reset_unit: str(b, "reset_unit") ?? r.reset_unit, idle_unit: str(b, "idle_unit") ?? r.idle_unit,
          idle_rule_id: "idle_rule_id" in b ? (num(b, "idle_rule_id") ?? null) : r.idle_rule_id,
          value: applied(r.kind, base),
        }
      }),
    }
  }
  if (method === "DELETE" && ruleId != null) return { ...st, rules: st.rules.filter((r) => r.id !== ruleId) }
  if (path === "/decay" && b) {
    const d = st.decay
    const mode = str(b, "catalyst_mode") ?? d.catalyst_mode
    const base = num(b, "base") ?? d.base
    const threshold = num(b, "threshold") ?? d.threshold
    const a = num(b, "catalyst_a") ?? d.catalyst_a
    const bb = num(b, "catalyst_b") ?? d.catalyst_b
    const streak = d.today_gain < threshold ? d.streak + 1 : 0
    return {
      ...st, decay: {
        ...d, base, threshold, catalyst_mode: mode, catalyst_a: a, catalyst_b: bb,
        tonight: applied("dec", base + catalyst(mode, a, bb, streak)),
      },
    }
  }
  if (path === "/settings" && b) {
    return {
      ...st, settings: {
        ...st.settings, timezone: str(b, "timezone") ?? st.settings.timezone,
        haptics: bool(b, "haptics") ?? st.settings.haptics, show_history: bool(b, "show_history") ?? st.settings.show_history,
      },
    }
  }
  return st
}

export interface TapResult {
  state: AppState; eventId: number; amount: number; actual: number; capped: boolean
  milestone: boolean; hyperspace: boolean
}

/** Tapping it keeps an automatic tap away (= server service.guards_idle). */
export const guardsIdle = (rule: Rule) => !!rule.idle_days && rule.idle_rule_id != null && !rule.idle_paused

/** A tap with the server's rounding and limit rules; null = the daily limit is used up. */
export function applyTap(st0: AppState, rule: Rule, at: string, tempId: () => number, vehicleKey: (i: number) => string): TapResult | null {
  if (rule.tappable === false) return null             // only its automatic tap counts it
  const st = currentDay(st0)
  const acc = rule.kind === "acc"
  const full = applied(rule.kind, rule.value)
  let amount = full
  let capped = false
  const ranked = st.settings.daily_limit !== false
  if (acc && ranked) {
    const left = Math.floor(st.limit.remaining)
    // limit used up: still a tap with +0 when it keeps the rule's automatic decelerator away
    if (left <= 0) {
      if (!guardsIdle(rule)) return null
      amount = 0
      capped = true
    } else if (amount > left) { amount = left; capped = true }
  }
  // with the limit: ranked momentum (capped) is the playing value; without: the free score
  const sign = acc ? 1 : -1
  const freeScore = st.free_score ?? st.momentum
  const newFree = Math.max(0, freeScore + sign * full)
  const newMomentum = ranked ? Math.max(0, st.momentum + sign * amount) : newFree
  const actual = newMomentum - st.momentum
  const hyperAt = st.vehicle.hyperspace_at ?? 4_000_000
  const hyperspace = ranked && st.momentum < hyperAt && newMomentum >= hyperAt      // exclusive to the competition
  const eventId = tempId()
  const vehicle = vehicleFor(newMomentum, st.vehicle)
  let events: Event[] = [{ id: eventId, kind: rule.kind, label: rule.name, icon: rule.icon, delta: actual, at, undoable: true }, ...st.events]
  const milestone = vehicle.index > st.vehicle.index
  if (milestone) events = [{ id: tempId(), kind: "milestone", label: vehicleKey(vehicle.index), icon: "", delta: 0, at }, ...events]
  const next = nextValue(rule.mode, rule.value, rule.param, rule.min_value)
  return {
    eventId, amount, actual, capped, milestone, hyperspace,
    state: {
      ...st,
      momentum: newMomentum,
      ranked_momentum: ranked ? newMomentum : st.ranked_momentum,
      free_score: newFree,
      vehicle,
      rules: st.rules.map((r) => r.id === rule.id ? { ...r, value: applied(rule.kind, rule.mode === "constant" ? rule.base : next) } : r),
      events,
      // the limit counts accelerators minus decelerators (never below 0), like the server
      limit: ranked ? limitCounted(st.limit, st.limit.gained + actual) : st.limit,
      decay: acc ? { ...st.decay, today_gain: st.decay.today_gain + actual } : st.decay,
    },
  }
}

/** Undo a tap locally (offline / still queued). */
export function revertLocal(st: AppState, e: Event, vehicleKeys: string[]): AppState {
  const momentum = Math.max(0, st.momentum - e.delta)
  const vehicle = vehicleFor(momentum, st.vehicle)
  const reached = new Set(vehicleKeys.slice(0, vehicle.index + 1))
  return {
    ...st, momentum, vehicle,
    events: st.events.filter((x) => x.id !== e.id && (x.kind !== "milestone" || reached.has(x.label))),
    limit: limitCounted(st.limit, st.limit.gained - e.delta),
    decay: e.kind === "acc" ? { ...st.decay, today_gain: st.decay.today_gain - e.delta } : st.decay,
  }
}

/** One step up (-1) or down (+1) among the rules of the same kind; null when it can't move. */
export function moveRule(rules: Rule[], id: number, step: number): Rule[] | null {
  const r = rules.find((x) => x.id === id)
  if (!r) return null
  const same = rules.filter((x) => x.kind === r.kind)
  const i = same.indexOf(r)
  const j = i + step
  if (j < 0 || j >= same.length) return null
  ;[same[i], same[j]] = [same[j], same[i]]
  return [...same, ...rules.filter((x) => x.kind !== r.kind)]
}
