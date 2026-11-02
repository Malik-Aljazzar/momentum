import { computed, signal } from "@preact/signals"
import { autoDe, autoEn, type Strings } from "./strings.gen"
import { manualDe, manualEn } from "./manual"
import type { Lang } from "./types"

export type { Strings } from "./strings.gen"
export type { Lang } from "./types"

function build(lang: Lang): Strings {
  const L = {} as Strings
  // getters referencing other members (L.x) work because L is filled before anything reads it
  Object.defineProperties(L, Object.getOwnPropertyDescriptors({
    lang, ...(lang === "de" ? manualDe : manualEn),
  }))
  Object.defineProperties(L, Object.getOwnPropertyDescriptors(lang === "de" ? autoDe(L) : autoEn(L)))
  return L
}

export const DE = build("de")
export const EN = build("en")

export const stringsFor = (lang: Lang) => (lang === "de" ? DE : EN)

function deviceLang(): Lang {
  try {
    const saved = localStorage.getItem("language")
    if (saved === "de" || saved === "en") return saved
  } catch { /* storage blocked */ }
  return (navigator.language || "en").toLowerCase().startsWith("de") ? "de" : "en"
}

/** The chosen language (stored on the device, default: the phone's language). */
export const lang = signal<Lang>(deviceLang())
export const S = computed(() => stringsFor(lang.value))

export function setLang(l: Lang) {
  lang.value = l
  try { localStorage.setItem("language", l) } catch { /* storage blocked */ }
}
