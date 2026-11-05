// The daily limit changed since the day before (android/.../data/LimitNews.kt): the first state from the server
// on a new day brings a sheet "higher / lower than yesterday" - once per day and device, for players in the
// competition (daily limit on). It waits until the what's-new sheet is closed (app.tsx): one after the other.
import type { AppState } from "./model"
import { local } from "./storage"

export interface LimitNews { day: string; cap: number; prev: number; average: number }

const KEY = "limit_seen_day"

/** What to show for [st] when this device last saw the server day [seen]. Nothing on a fresh install (seen null),
 *  for a day already seen, a stand-in limit (offline over midnight), players without the limit, or no change. */
export function limitChange(st: AppState, seen: string | null): LimitNews | null {
  const l = st.limit
  if (!l.day || l.provisional || seen == null || seen === l.day) return null
  if (st.settings.daily_limit === false || l.prev_cap == null || l.prev_cap === l.cap) return null
  return { day: l.day, cap: l.cap, prev: l.prev_cap, average: l.average }
}

/** A state from the server: its day counts as seen on this device; returns what to show (null = nothing). */
export function checkLimitNews(st: AppState): LimitNews | null {
  const l = st.limit
  if (!l.day || l.provisional) return null
  const seen = local.get<string | null>(KEY, null)
  if (seen === l.day) return null
  local.set(KEY, l.day)
  return limitChange(st, seen)
}
