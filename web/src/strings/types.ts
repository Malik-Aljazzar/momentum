import type { ComponentChildren } from "preact"

export type { Rule } from "../model"

export type Lang = "de" | "en"

/** Builder for cloze sentences: plain words and interactive gaps (rule / decay editor). */
export interface ClozeBuilder {
  text(s: string): void
  gap(id: string, content: () => ComponentChildren): void
}

/** The interactive gaps of the rule editor; each sentence places them differently. */
export interface RuleGaps {
  icon: () => ComponentChildren; name: () => ComponentChildren; base: () => ComponentChildren
  mode: () => ComponentChildren; param: () => ComponentChildren; pct: () => ComponentChildren
  min: () => ComponentChildren; reset: () => ComponentChildren
}

export interface DecayGaps {
  icon: () => ComponentChildren; base: () => ComponentChildren; threshold: () => ComponentChildren
  mode: () => ComponentChildren; a: () => ComponentChildren; b: () => ComponentChildren
  pct: () => ComponentChildren
}
