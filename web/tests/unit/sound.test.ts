import { describe, expect, it } from "vitest"
import { clampRange } from "../../src/screens/Feed"

describe("the used part of a sound", () => {
  it("posts: 5 to 30 s, inside the sound", () => {
    expect(clampRange(30_000, 0, 30_000)).toEqual([0, 30_000])
    expect(clampRange(30_000, 12_000, 13_000)).toEqual([12_000, 17_000])        // at least 5 s
    expect(clampRange(120_000, 10_000, 80_000)).toEqual([10_000, 40_000])       // at most 30 s (the end stops)
    expect(clampRange(30_000, 28_000, 40_000)).toEqual([25_000, 30_000])
    expect(clampRange(3_000, 0, 3_000)).toEqual([0, 3_000])                     // shorter than 5 s: all of it
  })
  it("stories: exactly as long as the story, it only slides", () => {
    expect(clampRange(30_000, 20_000, 30_000, 15_000)).toEqual([15_000, 30_000])
    expect(clampRange(30_000, 4_000, 6_000, 10_000)).toEqual([4_000, 14_000])
    expect(clampRange(8_000, 0, 8_000, 30_000)).toEqual([0, 8_000])             // the sound is shorter
  })
})
