// Vehicle effects and banner extras: the tables cover every vehicle / banner, the sheets have FRAMES frames
// (the same checks as android/.../VehiclesTest.kt).
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
import { BANNERS, bannerKinds, bannerNight, EXTRAS, HYPERSPACE, isBanner, ULTRA_HYPERSPACE } from "../../src/gfx/banners"
import { FRAME_MS, FRAMES, PAD, VEHICLE_FX, vehicleSpeed } from "../../src/gfx/vehicles"

/** server/service.py VEHICLES */
const VEHICLES = ["fahrrad", "motorroller", "kleinwagen", "pkw", "suv", "lkw", "kleinflugzeug", "passagierflugzeug", "shuttle",
  "raumschiff", "panzer", "kampfjet", "kreuzer"]

const pngSize = (path: string) => {
  const b = readFileSync(path)
  return [b.readUInt32BE(16), b.readUInt32BE(20)]
}

describe("vehicle effects", () => {
  it("knows every vehicle, with a speed and a hull", () => {
    for (const v of VEHICLES) {
      const fx = VEHICLE_FX[v]
      expect(fx, v).toBeDefined()
      expect(fx.speed).toBeGreaterThan(0)
      expect(fx.speed).toBeLessThanOrEqual(3)
      expect(fx.body[0]).toBeLessThan(fx.body[1])
      for (const [x, y] of [fx.dust, fx.headlight, fx.taillight, ...(fx.exhaust ?? []), ...(fx.contrails ?? [])].filter(Boolean) as [number, number][]) {
        expect(x).toBeGreaterThanOrEqual(0); expect(x).toBeLessThan(64); expect(y).toBeGreaterThanOrEqual(0); expect(y).toBeLessThan(32)
      }
      for (const e of fx.engines ?? []) expect(e.y0).toBeLessThanOrEqual(e.y1)
      for (const st of fx.strobes ?? []) for (const f of st.frames) expect(f).toBeLessThan(FRAMES)
    }
    expect(Object.keys(VEHICLE_FX).sort()).toEqual([...VEHICLES].sort())
  })
  it("kinds match the vehicles' categories", () => {
    expect(VEHICLES.filter((v) => VEHICLE_FX[v].kind === "air")).toEqual(["kleinflugzeug", "passagierflugzeug", "kampfjet"])
    expect(VEHICLES.filter((v) => VEHICLE_FX[v].kind === "space")).toEqual(["shuttle", "raumschiff", "kreuzer"])
    expect(VEHICLES.filter((v) => VEHICLE_FX[v].kind === "land")).toHaveLength(7)
  })
  it("the bicycle is slow, the jet fast, unknown sheets drive like a car", () => {
    expect(vehicleSpeed("fahrrad")).toBeLessThan(vehicleSpeed("pkw"))
    expect(vehicleSpeed("kampfjet")).toBeGreaterThan(vehicleSpeed("passagierflugzeug"))
    expect(vehicleSpeed("gauge")).toBe(1)
  })
  it("every sheet has FRAMES frames of 64x32 (pixelart/generate.py)", () => {
    expect(FRAMES * FRAME_MS).toBe(640)
    for (const v of [...VEHICLES, "gauge", "friends"]) {
      expect(pngSize(join(__dirname, "..", "..", "..", "pixelart", "out", v, "sheet.png")), v).toEqual([64 * FRAMES, 32])
    }
    expect(PAD.left).toBeGreaterThan(PAD.right)
  })
})

describe("banner extras", () => {
  it("only name banners that exist", () => {
    for (const k of Object.keys(EXTRAS)) expect(isBanner(k), k).toBe(true)
  })
  it("water rows lie inside the far strip, rays inside the card", () => {
    for (const [k, e] of Object.entries(EXTRAS)) {
      if (e.water) { expect(e.water[0], k).toBeGreaterThanOrEqual(0); expect(e.water[1], k).toBeLessThanOrEqual(40); expect(e.water[0]).toBeLessThan(e.water[1]) }
      if (e.rays) { expect(e.rays[0]).toBeGreaterThanOrEqual(0); expect(e.rays[0]).toBeLessThanOrEqual(1); expect(e.rays[1]).toBeLessThan(1) }
      if (e.disc || e.galaxy) expect(bannerKinds(k).has("space"), k).toBe(true)
    }
  })
  it("dark banners switch the lights on", () => {
    expect(bannerNight("city")).toBe(true)
    expect(bannerNight("moon")).toBe(true)
    expect(bannerNight(HYPERSPACE)).toBe(true)
    expect(bannerNight(ULTRA_HYPERSPACE)).toBe(true)
    expect(bannerNight("desert")).toBe(false)
    expect(bannerNight("sakura")).toBe(false)
    expect(bannerNight(null)).toBe(true)           // dusk
    expect(bannerNight("nope")).toBe(true)
    expect(BANNERS.filter(bannerNight).length).toBeGreaterThan(10)
  })
})
