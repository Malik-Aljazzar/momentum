// The 8-bit call tones (src/calltones.ts): iOS lets a page sound only after a tap - before that nothing is queued
// (a stopped audio clock would play every round that came meanwhile at once, after the next tap).
import { afterEach, describe, expect, it, vi } from "vitest"

class FakeContext {
  static made: FakeContext[] = []
  state = "suspended"
  currentTime = 0
  destination = {}
  oscillators = 0
  constructor() { FakeContext.made.push(this) }
  resume() { return Promise.resolve() }
  createOscillator() {
    this.oscillators++
    return { type: "", frequency: { value: 0 }, connect: (g: unknown) => g, start() {}, stop() {}, onended: null }
  }
  createGain() { return { gain: { setValueAtTime() {} }, connect: (d: unknown) => d } }
}

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe("call tones", () => {
  it("queue nothing before they may sound, then ring round by round", async () => {
    vi.stubGlobal("AudioContext", FakeContext)
    vi.useFakeTimers()
    const { tones } = await import("../../src/calltones")
    tones.ring()
    vi.advanceTimersByTime(2400 * 3)
    const c = FakeContext.made[0]
    expect(c.oscillators).toBe(0)
    c.state = "running"                              // tapped (main.tsx unlocks on every tap)
    vi.advanceTimersByTime(2400)
    expect(c.oscillators).toBe(8)                    // one round of the ringing, not the three from before
    tones.stop()
    vi.advanceTimersByTime(2400 * 2)
    expect(c.oscillators).toBe(8)
  })
})
