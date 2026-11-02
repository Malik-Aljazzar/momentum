// Both clients must say the same: every member of the Android interface (Strings.kt) exists in
// both languages on the web, and some samples are checked word for word.
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
import { DE, EN } from "../../src/strings"

const kt = readFileSync(join(__dirname, "../../../android/app/src/main/java/com/momentum/app/ui/Strings.kt"), "utf8")
const iface = kt.slice(kt.indexOf("interface Strings {"), kt.indexOf("\n}\n", kt.indexOf("interface Strings {")))
const members = [...iface.matchAll(/^\s{4}(?:val|fun) (\w+)/gm)].map((m) => m[1]).filter((n) => n !== "lang")

describe("texts", () => {
  it("has every member of Strings.kt in German and English", () => {
    expect(members.length).toBeGreaterThan(300)
    for (const name of members) {
      expect(DE, name).toHaveProperty(name)
      expect(EN, name).toHaveProperty(name)
      expect((DE as any)[name], name).not.toBeUndefined()
      expect((EN as any)[name], name).not.toBeUndefined()
    }
  })

  it("translates templates, plurals and branches like Kotlin", () => {
    expect(DE.removeN(3)).toBe("Entfernen (3)")
    expect(EN.makePublicN(2)).toBe("Public (2)")
    expect(DE.likes(1)).toBe("1 Gefällt mir")
    expect(DE.likes(4)).toBe("4 Gefällt mir")
    expect(DE.timeAgo(125)).toBe("vor 2 min")
    expect(EN.timeAgo(7300)).toBe("2 h ago")
    expect(DE.origin("sub", "lena")).toBe("abonniert · lena")
    expect(DE.empty("chats")[0]).toBe("Noch keine Unterhaltungen")
    expect(DE.empty("other")).toEqual([DE.noContacts, DE.noContactsSub])
    expect(DE.bannerName("hyperspace")).toBe("Hyperraum")
    expect(DE.bannerName("unknown")).toBe("unknown")
    expect(DE.confirmBulkRemove(2, 1)).toBe("2 löschen und 1 abbestellen?")
    expect(EN.vehicle("kreuzer", "Raumkreuzer")).toBe("Space cruiser")
    expect(DE.vehicle("kreuzer", "Raumkreuzer")).toBe("Raumkreuzer")
    expect(DE.challengeResult("Sprint", 2, "300")).toBe("„Sprint“ ist vorbei – Platz 2, +300 Momentum.")
    expect(DE.rewardsLine(["300", "200"])).toBe("1. +300 · 2. +200")
    expect(DE.limitInfo("1", "2", "3", "4", "5")).toHaveLength(4)
  })

  it("builds the cloze sentences with the same gaps", () => {
    const parts: string[] = []
    const b = { text: (s: string) => parts.push(s), gap: (id: string) => parts.push(`[${id}]`) }
    const g = Object.fromEntries(["icon", "name", "base", "mode", "param", "pct", "min", "reset"].map((k) => [k, () => null])) as any
    DE.ruleSentence(b, true, "linear", 3, g)
    expect(parts.join(" ")).toContain("[icon] Für [name] bekomme ich beim ersten Mal [base] Momentum.")
    expect(parts).toContain("[param]")
    expect(parts).toContain("[reset]")
  })
})
