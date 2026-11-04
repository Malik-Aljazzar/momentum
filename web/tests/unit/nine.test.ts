// One of the Nine: nine titles with colours, creature sheets and texts in both languages
// (the same checks as android/.../NineTest.kt).
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
import { BANNERS, isBanner, NINE_COLORS, NINE_KEYS } from "../../src/gfx/banners"
import { NINE_FRAMES, NINE_PX } from "../../src/screens/Nine"
import { DE, EN } from "../../src/strings"

const pngSize = (path: string) => {
  const b = readFileSync(path)
  return [b.readUInt32BE(16), b.readUInt32BE(20)]
}

describe("the nine titles", () => {
  it("are nine, each with a colour, a theme and a critter sheet of NINE_FRAMES frames", () => {
    expect(NINE_KEYS).toEqual(["goat", "larper", "hermes", "yapper", "charmeur", "gamemaster", "veteran", "clown", "supercharged"])
    for (const k of NINE_KEYS) {
      expect(NINE_COLORS[k]).toMatch(/^#[0-9A-F]{6}$/)
      expect(isBanner(`nine_${k}`), k).toBe(true)
      expect(BANNERS).not.toContain(`nine_${k}`)                  // never in the picker
      expect(pngSize(join(__dirname, "..", "..", "..", "pixelart", "out", `nine_${k}`, "sheet.png")), k).toEqual([NINE_PX * NINE_FRAMES, NINE_PX])
    }
  })
  it("have a name, a tale, a how-to and a unit in both languages", () => {
    for (const L of [DE, EN]) for (const k of NINE_KEYS) {
      expect(L.nineName(k).length, k).toBeGreaterThan(2)
      expect(L.nineLore(k).length, k).toBeGreaterThan(40)
      expect(L.nineHow(k).length, k).toBeGreaterThan(15)
      expect(L.nineUnit(k, "7")).toContain("7")
    }
    expect(DE.nineName("goat")).toBe("GOAT")
    expect(EN.nineUnit("yapper", "3")).toBe("3 comments")
    expect(DE.nineMine("12 Posts", 4)).toBe("Du: 12 Posts · Platz 4 weltweit")
    expect(EN.electionVotes(3, 10)).toBe("3 of 10 votes needed")
  })
})
