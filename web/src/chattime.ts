// Chat times in the player's time zone (report #49): the time on every message and day separators like
// WhatsApp (today, yesterday, a weekday within the last week, else the date); the countdown under a chat
// photo until only its small copy is left (report #48). Same rules as android/.../ui/ChatTime.kt.

const parts = (d: Date, tz: string) => {
  const f = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" })
  const p = Object.fromEntries(f.formatToParts(d).map((x) => [x.type, x.value]))
  return { y: +p.year, m: +p.month, d: +p.day }
}

/** The calendar day of a moment in a time zone, as "yyyy-mm-dd". */
export function dayKey(iso: string | Date, tz: string): string {
  const { y, m, d } = parts(new Date(iso), tz)
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`
}

/** "14:05" in the time zone. */
export function clock(iso: string, tz: string): string {
  return new Intl.DateTimeFormat("de-DE", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(new Date(iso))
}

const dayNumber = (key: string) => {
  const [y, m, d] = key.split("-").map(Number)
  return Date.UTC(y, m - 1, d) / 86_400_000
}

/** The minute a message was sent in the time zone ("yyyy-mm-dd hh:mm"). */
export const minuteKey = (iso: string, tz: string) => `${dayKey(iso, tz)} ${clock(iso, tz)}`

/** The separator above a day's first message. */
export function dayLabel(iso: string, tz: string, lang: string, now: Date, today: string, yesterday: string): string {
  const key = dayKey(iso, tz), ago = dayNumber(dayKey(now, tz)) - dayNumber(key)
  if (ago === 0) return today
  if (ago === 1) return yesterday
  const locale = lang === "de" ? "de-DE" : "en-US"
  const at = new Date(iso)
  if (ago > 1 && ago < 7) return new Intl.DateTimeFormat(locale, { timeZone: tz, weekday: "long" }).format(at)
  const sameYear = key.slice(0, 4) === dayKey(now, tz).slice(0, 4)
  return new Intl.DateTimeFormat(locale, { timeZone: tz, day: "numeric", month: "long", ...(sameYear ? {} : { year: "numeric" }) }).format(at)
}

/** Time left until [expiresAt]: whole days and "hh:mm" (null when it's over). */
export function countdown(expiresAt: string, now: Date): { days: number; hm: string } | null {
  const left = Math.floor((new Date(expiresAt).getTime() - now.getTime()) / 60_000)
  if (!(left > 0)) return null
  const days = Math.floor(left / 1440), h = Math.floor(left / 60) % 24, m = left % 60
  return { days, hm: `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}` }
}
