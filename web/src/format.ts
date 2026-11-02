// Number and time formatting, same as android/.../Format.kt.

const intFmt = new Intl.NumberFormat("de-DE", { maximumFractionDigits: 0 })

/** Momentum totals: whole numbers with German grouping. */
export const fmt = (n: number) => intFmt.format(Math.round(n))

/** Rule values are whole numbers (already rounded by the server or via applied()). */
export const fmtVal = fmt

/** Whole numbers only: accelerators round up, decelerators round down (same as the server). */
export function applied(kind: string, value: number): number {
  const v = Math.round(value * 1e6) / 1e6          // float noise is not a fraction
  return kind === "acc" ? Math.ceil(v) : Math.floor(v)
}

export const signed = (n: number, f: (n: number) => string = fmtVal) => (n >= 0 ? "+" : "−") + f(Math.abs(n))

/** Seconds since an ISO timestamp from the server (null if unparsable). */
export function secondsSince(iso: string): number | null {
  const t = Date.parse(iso)
  return Number.isNaN(t) ? null : Math.floor((Date.now() - t) / 1000)
}

/** Same formulas as server/momentum.py, for the editor previews. */
export function catalyst(mode: string, a: number, b: number, streak: number): number {
  if (streak <= 0 || mode === "none") return 0
  if (mode === "linear") return a * streak
  if (mode === "exponential") return a * b ** (streak - 1)
  return a * (1 - b ** streak)
}

export function nextValue(mode: string, value: number, param: number, min: number): number {
  if (mode === "linear") return Math.max(min, value - param)
  if (mode === "exponential") return Math.max(min, value * param)
  return value
}

export function parseNum(s: string): number | null {
  const v = Number(s.trim().replace(",", "."))
  return s.trim() === "" || Number.isNaN(v) ? null : v
}

const oneFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 })

/** Big numbers shortened: 950 · 12,3K · 1,2M (whole numbers below 10,000 stay as they are). */
export function fmtShort(n: number): string {
  const a = Math.abs(n)
  if (a >= 1e9) return oneFmt.format(n / 1e9) + "B"
  if (a >= 1e6) return oneFmt.format(n / 1e6) + "M"
  if (a >= 1e4) return oneFmt.format(n / 1e3) + "K"
  return fmt(n)
}
