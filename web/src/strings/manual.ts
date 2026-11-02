// Hand-written members of Strings (the ones scripts/strings.mjs can't translate). Keep them in
// sync with android/.../ui/Strings.kt - same wording, both languages.
import type { Strings } from "./strings.gen"
import type { ClozeBuilder, DecayGaps, Rule, RuleGaps } from "./types"
import { fmtVal } from "../format"

type Manual = Pick<Strings, "challengeResult" | "decaySentence" | "idleSentence" | "modeHint" | "rewardsLine" |
  "ruleSentence" | "vehicle">

const rewardsLine = (rewards: string[]) => rewards.map((r, i) => `${i + 1}. +${r}`).join(" · ")

export const manualDe: Manual = {
  vehicle: (_key: string, fallback: string) => fallback,
  modeHint: (r: Rule) => r.mode === "linear" ? `linear · −${fmtVal(r.param)} pro Klick`
    : r.mode === "exponential" ? `exponentiell · ${Math.round(r.param * 100)}% bleiben` : "konstant",
  challengeResult: (title: string, rank: number | null, reward: string) =>
    `„${title}“ ist vorbei` + (rank != null ? ` – Platz ${rank}` : "") + (reward !== "0" ? `, +${reward} Momentum.` : "."),
  rewardsLine,
  ruleSentence(c: ClozeBuilder, acc: boolean, mode: string, resetDays: number, g: RuleGaps) {
    c.gap("icon", g.icon); c.text("Für"); c.gap("name", g.name)
    c.text(acc ? "bekomme ich" : "verliere ich")
    if (mode === "constant") {
      c.text("jedes Mal"); c.gap("base", g.base); c.text("Momentum.")
      c.text(acc ? "Das Wachstum ist" : "Die Strafe ist"); c.gap("mode", g.mode)
      c.text(acc ? "— jeder Klick zählt gleich viel." : "— jedes Mal gleich hart.")
      return
    }
    c.text("beim ersten Mal"); c.gap("base", g.base); c.text("Momentum.")
    c.text(acc ? "Das Wachstum ist" : "Die Strafe ist"); c.gap("mode", g.mode)
    if (mode === "linear") {
      c.text(acc ? ": mit jedem weiteren Klick gibt es" : ": jedes weitere Mal kostet")
      c.gap("param", g.param)
      c.text(acc ? "weniger, aber nie weniger als" : "weniger, aber immer mindestens")
    } else {
      c.text(acc ? ": bei jedem Klick bleiben nur" : ": jedes Mal bleiben nur")
      c.gap("pct", g.pct)
      c.text("% davon übrig — erst geht es schnell runter, dann immer langsamer, aber nie unter")
    }
    c.gap("min", g.min); c.text(".")
    if (resetDays === 0) {
      c.text("Zurückgesetzt wird"); c.gap("reset", g.reset); c.text("— einmal verbraucht, bleibt es so.")
    } else {
      c.text("Nach"); c.gap("reset", g.reset)
      c.text(acc ? "ohne Klick starte ich wieder bei voller Stärke." : "ohne Rückfall ist die Strafe wieder voll.")
    }
  },
  decaySentence(c: ClozeBuilder, mode: string, g: DecayGaps) {
    c.gap("icon", g.icon); c.text("Jede Nacht verliere ich"); c.gap("base", g.base); c.text("Momentum.")
    if (mode === "none") {
      c.text("Verpasse ich mein Tagesziel, kommt"); c.gap("mode", g.mode); c.text("eine Zusatzstrafe dazu.")
      return
    }
    c.text("Schaffe ich an einem Tag weniger als"); c.gap("threshold", g.threshold)
    c.text(", kommt eine Zusatzstrafe dazu, die mit jedem verpassten Tag in Folge"); c.gap("mode", g.mode)
    c.text("wächst:")
    if (mode === "linear") { c.text("+"); c.gap("a", g.a); c.text("pro Tag.") }
    else if (mode === "exponential") {
      c.text("sie beginnt bei"); c.gap("a", g.a); c.text("und wird jeden Tag ×"); c.gap("b", g.b); c.text("größer.")
    } else {
      c.text("bis höchstens"); c.gap("a", g.a)
      c.text(". Jeder verpasste Tag schließt die Lücke zum Maximum, es bleiben nur"); c.gap("pct", g.pct); c.text("% übrig.")
    }
    c.text("Sobald ich das Ziel wieder schaffe, fällt sie weg.")
  },
  idleSentence(b: ClozeBuilder, on: boolean, days: () => any, rule: () => any) {
    if (!on) { b.text("Automatisch tippen:"); b.gap("idle", days) }
    else {
      b.text("Nach"); b.gap("idle", days); b.text("ohne Tippen wird automatisch")
      b.gap("idlerule", rule); b.text("getippt – danach wieder nach jeweils dieser Zeit.")
    }
  },
}

const VEHICLES_EN: Record<string, string> = {
  fahrrad: "Bicycle", motorroller: "Scooter", kleinwagen: "Small car", pkw: "Car", suv: "SUV", lkw: "Truck",
  panzer: "Tank", kleinflugzeug: "Light aircraft", passagierflugzeug: "Airliner", kampfjet: "Fighter jet",
  shuttle: "Space Shuttle", raumschiff: "Starfighter", kreuzer: "Space cruiser",
}

export const manualEn: Manual = {
  vehicle: (key: string, fallback: string) => VEHICLES_EN[key] ?? fallback,
  modeHint: (r: Rule) => r.mode === "linear" ? `linear · −${fmtVal(r.param)} per tap`
    : r.mode === "exponential" ? `exponential · ${Math.round(r.param * 100)}% remain` : "constant",
  challengeResult: (title: string, rank: number | null, reward: string) =>
    `“${title}” is over` + (rank != null ? ` – place ${rank}` : "") + (reward !== "0" ? `, +${reward} momentum.` : "."),
  rewardsLine,
  ruleSentence(c: ClozeBuilder, acc: boolean, mode: string, resetDays: number, g: RuleGaps) {
    c.gap("icon", g.icon); c.text("For"); c.gap("name", g.name)
    c.text(acc ? "I gain" : "I lose")
    if (mode === "constant") {
      c.gap("base", g.base); c.text("momentum every time.")
      c.text(acc ? "The growth is" : "The penalty is"); c.gap("mode", g.mode)
      c.text(acc ? "— every tap counts the same." : "— equally harsh every time.")
      return
    }
    c.gap("base", g.base); c.text("momentum the first time.")
    c.text(acc ? "The growth is" : "The penalty is"); c.gap("mode", g.mode)
    if (mode === "linear") {
      c.text(acc ? ": every further tap gives" : ": every further time costs")
      c.gap("param", g.param)
      c.text(acc ? "less, but never less than" : "less, but always at least")
    } else {
      c.text(acc ? ": with every tap only" : ": every time only")
      c.gap("pct", g.pct)
      c.text("% of it remains — it drops fast at first, then slower and slower, but never below")
    }
    c.gap("min", g.min); c.text(".")
    if (resetDays === 0) {
      c.text("It resets"); c.gap("reset", g.reset); c.text("— once used up, it stays that way.")
    } else {
      c.text("After"); c.gap("reset", g.reset)
      c.text(acc ? "without a tap it starts again at full strength." : "without a relapse the penalty is back to full.")
    }
  },
  decaySentence(c: ClozeBuilder, mode: string, g: DecayGaps) {
    c.gap("icon", g.icon); c.text("Every night I lose"); c.gap("base", g.base); c.text("momentum.")
    if (mode === "none") {
      c.text("Missing my daily goal adds"); c.gap("mode", g.mode); c.text("extra penalty.")
      return
    }
    c.text("If I gather less than"); c.gap("threshold", g.threshold)
    c.text("in a day, an extra penalty is added that grows"); c.gap("mode", g.mode)
    c.text("with every missed day in a row:")
    if (mode === "linear") { c.text("+"); c.gap("a", g.a); c.text("per day.") }
    else if (mode === "exponential") {
      c.text("it starts at"); c.gap("a", g.a); c.text("and grows ×"); c.gap("b", g.b); c.text("every day.")
    } else {
      c.text("up to at most"); c.gap("a", g.a)
      c.text(". Each missed day closes the gap to that maximum, leaving only"); c.gap("pct", g.pct); c.text("% of it.")
    }
    c.text("As soon as I hit the goal again, it's gone.")
  },
  idleSentence(b: ClozeBuilder, on: boolean, days: () => any, rule: () => any) {
    if (!on) { b.text("Tap automatically:"); b.gap("idle", days) }
    else {
      b.text("After"); b.gap("idle", days); b.text("without a tap,")
      b.gap("idlerule", rule); b.text("is tapped automatically – then again after the same time.")
    }
  },
}
