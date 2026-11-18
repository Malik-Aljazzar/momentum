import { describe, expect, it } from "vitest"
import { sheetDismisses } from "../../src/ui/components"

describe("swiping a sheet down (report #71)", () => {
  it("closes it when pulled a quarter of its height", () => {
    expect(sheetDismisses(160, 600, 0)).toBe(true)
    expect(sheetDismisses(140, 600, 0)).toBe(false)
  })
  it("closes it with a quick flick, also a short one", () => {
    expect(sheetDismisses(40, 600, 1.2)).toBe(true)
    expect(sheetDismisses(40, 600, .3)).toBe(false)
  })
  it("a twitch isn't a flick", () => {
    expect(sheetDismisses(10, 600, 3)).toBe(false)
  })
})
