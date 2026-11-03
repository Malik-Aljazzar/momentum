// Vehicle effects (android/.../ui/VehicleFx.kt): what a 64x32 sprite sheet can't hold - dust and exhaust,
// speed lines, headlights at night, contrails, afterburners, engine trails, star streaks and a glint sweeping
// over the hull - drawn around the animated sprite. The table's coordinates are art pixels of the sprite
// (0..63 / 0..31); particles are snapped to the art grid so they stay pixel art.
import { type Ctx, glowAt, hash, offscreen, ready, rect, rgba, spread, sprite } from "./engine"

/** Frames per sheet and how long one shows (pixelart/generate.py FRAMES; the sheet is 64 * FRAMES wide). */
export const FRAMES = 8
export const FRAME_MS = 80
/** Art pixels around the sprite the effects may use: behind, in front, above, below. */
export const PAD = { left: 72, right: 48, top: 18, bottom: 6 }

export type Kind = "land" | "air" | "space"
interface Engine { x: number; y0: number; y1: number }
interface Strobe { x: number; y: number; frames: number[]; color: string }
export interface VehicleFx {
  kind: Kind
  /** how fast the world passes by (1 = a car); the banner scrolls with it */
  speed: number
  /** vertical span of the hull (speed lines) */
  body: [number, number]
  /** rear wheel's contact point: dust kicks up here */
  dust?: [number, number]
  /** tailpipes: smoke drifts left from here */
  exhaust?: [number, number][]
  headlight?: [number, number]
  taillight?: [number, number]
  /** x range of the shadow on the road */
  shadow?: [number, number]
  /** wing tips / engines leaving contrails */
  contrails?: [number, number][]
  /** nozzles (x = where the trail starts, y0..y1 = its height) */
  engines?: Engine[]
  /** engine trail colours: head, mid, tail */
  trail?: [string, string, string]
  /** blinking lights that bloom (same frames as in the sheet) */
  strobes?: Strobe[]
  sparks?: boolean
}

const WHITE = "#FFFFFF", YELLOW = "#FFCD75", ORANGE = "#EF7D57", RED = "#FF5C6A", LIME = "#A7F070"

export const VEHICLE_FX: Record<string, VehicleFx> = {
  fahrrad: { kind: "land", speed: .4, body: [8, 24], dust: [17, 30], shadow: [8, 55] },
  motorroller: { kind: "land", speed: .7, body: [5, 25], dust: [17, 30], exhaust: [[7, 24]], headlight: [48, 7], taillight: [7, 17], shadow: [6, 54] },
  kleinwagen: { kind: "land", speed: .9, body: [8, 25], dust: [20, 31], exhaust: [[10, 24]], headlight: [53, 17], taillight: [11, 17], shadow: [10, 54] },
  pkw: { kind: "land", speed: 1.1, body: [9, 25], dust: [15, 31], exhaust: [[4, 24]], headlight: [59, 18], taillight: [5, 18], shadow: [4, 60] },
  suv: { kind: "land", speed: 1, body: [5, 25], dust: [15, 31], exhaust: [[3, 23]], headlight: [59, 16], taillight: [4, 15], shadow: [3, 61] },
  lkw: { kind: "land", speed: .95, body: [3, 25], dust: [10, 31], exhaust: [[1, 23]], headlight: [61, 19], taillight: [2, 20], shadow: [2, 62], sparks: true,
    strobes: [{ x: 6, y: 2, frames: [0, 1, 4, 5], color: ORANGE }, { x: 42, y: 2, frames: [0, 1, 4, 5], color: ORANGE }] },
  panzer: { kind: "land", speed: .7, body: [11, 29], dust: [10, 30], exhaust: [[4, 20]], shadow: [6, 58], sparks: true,
    strobes: [{ x: 17, y: 1, frames: [3, 4], color: RED }] },
  kleinflugzeug: { kind: "air", speed: 1.3, body: [4, 24], contrails: [[24, 10]], strobes: [{ x: 23, y: 9, frames: [0, 4], color: WHITE }] },
  passagierflugzeug: { kind: "air", speed: 1.7, body: [2, 23], contrails: [[28, 22], [22, 19]],
    strobes: [{ x: 6, y: 2, frames: [0, 2], color: WHITE }, { x: 33, y: 20, frames: [4, 5], color: RED }, { x: 22, y: 18, frames: [6, 7], color: LIME }] },
  kampfjet: { kind: "air", speed: 2.5, body: [4, 23], contrails: [[22, 18]], engines: [{ x: 5, y0: 15, y1: 18 }], trail: [WHITE, YELLOW, ORANGE],
    strobes: [{ x: 12, y: 3, frames: [0, 1], color: RED }, { x: 37, y: 20, frames: [4, 5], color: LIME }] },
  shuttle: { kind: "space", speed: 1.6, body: [3, 28], engines: [{ x: 3, y0: 20, y1: 23 }, { x: 9, y0: 4, y1: 7 }], trail: [WHITE, YELLOW, "#C8CCD6"] },
  raumschiff: { kind: "space", speed: 2, body: [4, 28], engines: [{ x: 6, y0: 7, y1: 10 }, { x: 6, y0: 23, y1: 26 }], trail: [WHITE, YELLOW, ORANGE],
    strobes: [{ x: 53, y: 5, frames: [0, 1], color: RED }, { x: 53, y: 28, frames: [0, 1], color: RED }] },
  kreuzer: { kind: "space", speed: 1.4, body: [3, 25], engines: [{ x: 6, y0: 11, y1: 14 }, { x: 6, y0: 16, y1: 19 }, { x: 6, y0: 21, y1: 24 }],
    trail: ["#F4F4F4", "#73EFF7", "#41A6F6"], strobes: [{ x: 14, y: 2, frames: [4, 5], color: WHITE }, { x: 9, y: 3, frames: [4, 5], color: WHITE }] },
}

export const isVehicle = (key: string) => key in VEHICLE_FX
/** How fast the world passes by for this vehicle (1 = a car). */
export const vehicleSpeed = (key: string) => VEHICLE_FX[key]?.speed ?? 1

/**
 * Draws the sprite's current frame at (ox, oy) device pixels, [s] device pixels per art pixel, with its effects
 * around it. [night]: headlights on, lights bloom.
 */
export function drawVehicle(c: Ctx, key: string, s: number, ox: number, oy: number, night: boolean) {
  const def = VEHICLE_FX[key]
  const sheet = sprite(`${key}/sheet.png`)
  const t = c.t
  const frame = Math.floor(t * 1000 / FRAME_MS) % FRAMES
  // aircraft and ships float on top of the sheet's own bob
  const lift = def && def.kind !== "land" ? Math.round(Math.sin(t * 1.1) * 1.2) : 0
  const py = oy + lift * s
  /** a block of art pixels */
  const P = (color: string, x: number, y: number, w = 1, h = 1) =>
    rect(c, color, ox + Math.round(x) * s, py + Math.round(y) * s, Math.max(1, Math.round(w)) * s, Math.max(1, Math.round(h)) * s)
  const cx = (x: number) => ox + x * s, cy = (y: number) => py + y * s

  if (def) {
    const sp = def.speed
    // ---- behind the vehicle
    if (def.kind === "land") {
      if (def.shadow) {
        P("rgba(0,0,0,.22)", def.shadow[0] + 2, 31, def.shadow[1] - def.shadow[0] - 4, 1)
        P("rgba(0,0,0,.16)", def.shadow[0], 32, def.shadow[1] - def.shadow[0], 1)
      }
      if (night && def.headlight) {
        const hx = cx(def.headlight[0] + 1), hy = cy(def.headlight[1]) + s / 2
        const flick = .9 + .1 * Math.sin(t * 23)
        const g = c.g
        const grad = g.createLinearGradient(hx, 0, hx + 46 * s, 0)
        grad.addColorStop(0, `rgba(255,232,150,${.3 * flick})`); grad.addColorStop(1, "rgba(255,232,150,0)")
        g.fillStyle = grad
        g.beginPath(); g.moveTo(hx, hy - s); g.lineTo(hx + 46 * s, hy - 10 * s); g.lineTo(hx + 46 * s, hy + 13 * s); g.lineTo(hx, hy + 2 * s); g.closePath(); g.fill()
        glowAt(c, hx, hy, 6 * s, `rgba(255,232,150,${.6 * flick})`)
      }
      if (night && def.taillight) glowAt(c, cx(def.taillight[0] + .5), cy(def.taillight[1] + .5), 4 * s, "rgba(255,70,90,.55)")
      if (def.dust) {
        const n = Math.round(4 + 6 * sp), life = Math.max(.5, 1.3 - sp * .4)
        for (let i = 0; i < n; i++) {
          const k = (t / life + spread(i)) % 1
          const x = def.dust[0] - 2 - k * (20 + 16 * sp) - (i % 3)
          const y = def.dust[1] + 1 - k * k * (8 + i % 4) - (i % 2)
          const d = 1 + Math.floor(k * 3)
          const a = (1 - k) * .5
          P(`rgba(196,186,166,${a})`, x, y, d, d)
          if (d > 1) P(`rgba(196,186,166,${a * .7})`, x - 1, y + 1, d, d - 1)
        }
      }
      if (def.exhaust) for (const [ex, ey] of def.exhaust) for (let i = 0; i < 5; i++) {
        const k = (t / 1.5 + spread(i + 3)) % 1
        const x = ex - 1 - k * (14 + 8 * sp) - (i % 2)
        const y = ey - k * 8 + Math.sin(t * 2 + i) * 1
        const d = 1 + Math.floor(k * 2.5)
        P(`rgba(165,170,180,${(1 - k) * .45})`, x, y, d, d)
      }
      if (sp >= .6) for (let i = 0; i < 5; i++) {
        const k = (t / (.5 / sp) + spread(i)) % 1
        const y = def.body[0] + ((i * 7 + 3) % (def.body[1] - def.body[0]))
        const x = (def.shadow?.[0] ?? 4) - 2 - k * 44
        const len = 5 + 9 * (1 - k)
        P(`rgba(255,255,255,${.3 * (1 - k) * Math.min(1, sp)})`, x - len, y, len, 1)
      }
      if (def.sparks && def.dust) for (let i = 0; i < 3; i++) {
        if ((t * 1.7 + spread(i)) % 1 < .12) P(YELLOW, def.dust[0] - 3 - i * 2, def.dust[1] - 1 - ((t * 40 + i) % 3))
      }
    } else {
      // wisps of cloud / star streaks rushing past
      const space = def.kind === "space"
      for (let i = 0; i < 8; i++) {
        const k = (t / ((space ? .45 : .7) / sp) + spread(i)) % 1
        const y = -PAD.top + 2 + ((i * 11) % (32 + PAD.top + 2))
        const x = 64 + PAD.right - k * (64 + PAD.left + PAD.right + 24)
        const len = space ? 6 + 10 * (1 - k) : 10 + 12 * k
        P(`rgba(255,255,255,${(space ? .3 : .16) * (1 - k)})`, x, y, len, 1)
      }
      if (def.contrails) for (const [tx, ty] of def.contrails) for (let i = 0; i < 34; i++) {
        const x = tx - 1 - i * 2
        const y = ty + Math.sin(t * 2.6 - i * .32) * (.6 + i * .04) + i * .06
        const d = 1 + Math.floor(i / 12)
        const a = .5 * (1 - i / 34) * (.75 + .25 * Math.sin(t * 5 + i))
        P(`rgba(255,255,255,${a})`, x, y - d / 2, 2, d)
      }
      if (def.engines) def.engines.forEach((e, ei) => {
        const mid = (e.y0 + e.y1 + 1) / 2, hgt = e.y1 - e.y0 + 1
        const [head, body, tail] = def.trail ?? [WHITE, YELLOW, ORANGE]
        const pulse = Math.sin(t * 9 + ei * 2) * .5 + .5
        glowAt(c, cx(e.x), cy(mid), (hgt + 5) * s, rgba(body, .3 + .25 * pulse))
        if (space) {
          for (let i = 0; i < 14; i++) {
            const k = (t / (1.1 / sp) + spread(i + ei * 5)) % 1
            const x = e.x - 2 - k * (56 + 22 * sp)
            const y = mid - .5 + Math.sin(i * 7.3) * (hgt / 2 - .5) + Math.sin(t * 3 + i) * k * 2
            const d = k < .3 ? 2 : 1
            P(rgba(k < .2 ? head : k < .5 ? body : tail, (1 - k) * .85), x, y, d, d)
          }
          if (key === "shuttle") for (let i = 0; i < 8; i++) {
            const k = (t / 1.3 + spread(i + ei * 7)) % 1
            const x = e.x - 2 - k * 36 - (i % 2)
            const y = mid - 1 + Math.sin(i * 3.1) * 2 + k * 3 * (i % 2 ? 1 : -1)
            const d = 2 + Math.floor(k * 4)
            P(rgba(k < .3 ? WHITE : tail, (1 - k) * .5), x, y - d / 2, d, d)
          }
        } else {
          // afterburner: shock diamonds and heat shimmer
          for (let j = 0; j < 4; j++) {
            if ((t * 25 + j) % 2 < 1) P(j % 2 ? body : head, e.x - 3 - j * 3 - (frame % 2), mid - .5, 1, 1)
            P("rgba(255,255,255,.07)", e.x - 5 - j * 4, mid - 2 + Math.sin(t * 17 + j) * 2, 3, 4)
          }
        }
      })
    }
    if (def.strobes) for (const st of def.strobes) if (st.frames.includes(frame)) {
      glowAt(c, cx(st.x + .5), cy(st.y + .5), 4 * s, rgba(st.color, .6))
    }
  }

  // ---- the sprite's frame
  if (ready(sheet)) c.g.drawImage(sheet, frame * 64, 0, 64, 32, ox, py, 64 * s, 32 * s)

  // ---- in front: a glint sweeping over the hull now and then
  if (def && ready(sheet)) {
    const cyc = 4.6
    const k = ((t + (Math.abs(hash(key)) % 100) / 25) % cyc) / .55
    if (k < 1) {
      const og = offscreen(64 * s, 32 * s)
      og.drawImage(sheet, frame * 64, 0, 64, 32, 0, 0, 64 * s, 32 * s)
      og.globalCompositeOperation = "source-in"
      const gx = (-12 + 88 * k) * s
      const grad = og.createLinearGradient(gx - 12 * s, 0, gx + 4 * s, 32 * s)
      grad.addColorStop(0, "rgba(255,255,255,0)"); grad.addColorStop(.5, "rgba(255,255,255,.5)"); grad.addColorStop(1, "rgba(255,255,255,0)")
      og.fillStyle = grad
      og.fillRect(0, 0, 64 * s, 32 * s)
      og.globalCompositeOperation = "source-over"
      c.g.drawImage(og.canvas, 0, 0, 64 * s, 32 * s, ox, py, 64 * s, 32 * s)
    }
  }
}
