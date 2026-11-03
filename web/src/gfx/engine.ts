// Canvas helpers for the pixel art: sprite loading, crisp scaled blits, one shared animation loop
// that only draws canvases that are on screen.

const images = new Map<string, HTMLImageElement>()

/** A sprite from public/sprites (copied from pixelart/out); draws once it has loaded. */
export function sprite(path: string): HTMLImageElement {
  let img = images.get(path)
  if (!img) {
    img = new Image()
    img.src = `/app/sprites/${path}`
    img.onload = () => { for (const s of scenes) s.dirty = true }
    images.set(path, img)
  }
  return img
}
export const ready = (img: HTMLImageElement | null | undefined): img is HTMLImageElement =>
  !!img && img.complete && img.naturalWidth > 0

export interface Ctx {
  g: CanvasRenderingContext2D
  w: number          // device pixels
  h: number
  dp: number         // device pixels per CSS px
  t: number          // seconds since the scene appeared
}

/** Integer device pixels per art pixel, so scaled pixel art stays crisp. */
export const pixelScale = (dpPerPixel: number) => Math.max(1, Math.round(dpPerPixel * (window.devicePixelRatio || 1)))

export function blit(c: Ctx, img: HTMLImageElement, x: number, y: number, s: number, alpha = 1) {
  if (!ready(img)) return
  c.g.globalAlpha = alpha
  c.g.drawImage(img, Math.floor(x), Math.floor(y), img.naturalWidth * s, img.naturalHeight * s)
  c.g.globalAlpha = 1
}

/** Repeat an image horizontally, shifted left by phase (0..1) of one tile. */
export function tileX(c: Ctx, img: HTMLImageElement, s: number, y: number, phase: number, alpha = 1) {
  if (!ready(img)) return
  const w = img.naturalWidth * s
  for (let x = -Math.floor(phase * w); x < c.w; x += w) blit(c, img, x, y, s, alpha)
}

/** Like tileX, but every art row is shifted sideways by [offset](row) device pixels (heat shimmer, water). */
export function tileXWavy(c: Ctx, img: HTMLImageElement, s: number, y: number, phase: number, offset: (row: number) => number) {
  if (!ready(img)) return
  const w = img.naturalWidth * s, iw = img.naturalWidth, ih = img.naturalHeight
  const x0 = -Math.floor(phase * w)
  for (let r = 0; r < ih; r++) {
    const o = Math.round(offset(r))
    for (let x = x0 - w; x < c.w + w; x += w) c.g.drawImage(img, 0, r, iw, 1, Math.floor(x) + o, Math.floor(y) + r * s, w, s)
  }
}

/** Repeat an image over the whole area, shifted vertically by phase (0..1) of one tile. */
export function tileAll(c: Ctx, img: HTMLImageElement, s: number, phase: number, alpha: number) {
  if (!ready(img)) return
  const w = img.naturalWidth * s, h = img.naturalHeight * s
  for (let y = -h + phase * h; y < c.h; y += h) for (let x = -w; x < c.w + w; x += w) blit(c, img, x, y, s, alpha)
}

export function rect(c: Ctx, color: string, x: number, y: number, w: number, h: number) {
  c.g.fillStyle = color
  c.g.fillRect(x, y, w, h)
}

/** A soft round glow, drawn only within its own box (cheap enough for many per frame). */
export function glowAt(c: Ctx, x: number, y: number, r: number, color: string, ry = r) {
  if (r <= 0 || ry <= 0) return
  const g = c.g
  g.save()
  g.translate(x, y)
  g.scale(1, ry / r)
  const grad = g.createRadialGradient(0, 0, 0, 0, 0, r)
  grad.addColorStop(0, color); grad.addColorStop(1, "rgba(0,0,0,0)")
  g.fillStyle = grad
  g.fillRect(-r, -r, 2 * r, 2 * r)
  g.restore()
}

/** "#RRGGBB" with an alpha -> "rgba(...)". */
export function rgba(hex: string, a: number) {
  const n = parseInt(hex.slice(1), 16)
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${Math.max(0, Math.min(1, a))})`
}

/** Relative luminance (0..1) of "#RRGGBB". */
export function luminance(hex: string) {
  const n = parseInt(hex.slice(1), 16)
  return (.2126 * ((n >> 16) & 255) + .7152 * ((n >> 8) & 255) + .0722 * (n & 255)) / 255
}

/** 0..1 progress of a loop with the given period. */
export const phase = (seconds: number, periodMs: number) => ((seconds * 1000) / periodMs) % 1

/** Evenly spread offsets in 0..1 for particle i (golden ratio: no two alike, no pattern). */
export const spread = (i: number) => (i * .618034) % 1

export type Bands = [number, string][]

export function drawBands(c: Ctx, bands: Bands) {
  bands.forEach(([start, color], i) => {
    const end = i + 1 < bands.length ? bands[i + 1][0] : 1
    rect(c, color, 0, Math.floor(start * c.h), c.w, Math.ceil((end - start) * c.h) + 1)
  })
}

let ditherCache: { dp: number; pattern: CanvasPattern | null } | null = null

/** Soft checkerboard dither (2 CSS px cells) over everything, like the app. */
export function dither(c: Ctx) {
  if (!ditherCache || ditherCache.dp !== c.dp) {
    const cell = Math.max(1, Math.round(2 * c.dp))
    const tile = document.createElement("canvas")
    tile.width = tile.height = cell * 2
    const tg = tile.getContext("2d")!
    tg.fillStyle = "rgba(0,0,0,0.16)"
    tg.fillRect(0, 0, cell, cell)
    tg.fillRect(cell, cell, cell, cell)
    ditherCache = { dp: c.dp, pattern: c.g.createPattern(tile, "repeat") }
  }
  if (ditherCache.pattern) { c.g.fillStyle = ditherCache.pattern; c.g.fillRect(0, 0, c.w, c.h) }
}

let scratch: HTMLCanvasElement | null = null

/** A shared scratch canvas of at least the given size, cleared (masks, glints). */
export function offscreen(w: number, h: number): CanvasRenderingContext2D {
  if (!scratch) scratch = document.createElement("canvas")
  if (scratch.width < w || scratch.height < h) { scratch.width = Math.max(scratch.width, w); scratch.height = Math.max(scratch.height, h) }
  const g = scratch.getContext("2d")!
  g.setTransform(1, 0, 0, 1, 0, 0)
  g.globalCompositeOperation = "source-over"
  g.globalAlpha = 1
  g.clearRect(0, 0, scratch.width, scratch.height)
  g.imageSmoothingEnabled = false
  return g
}

/** Seeded random numbers (same layout every time for the same key). */
export function rng(seed: number) {
  let s = seed >>> 0 || 1
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296)
}
export function hash(text: string) {
  let h = 0
  for (let i = 0; i < text.length; i++) h = (Math.imul(31, h) + text.charCodeAt(i)) | 0
  return h
}

// ---------------------------------------------------------------- the loop

interface Live {
  canvas: HTMLCanvasElement
  draw: (c: Ctx) => void
  start: number
  visible: boolean
  animated: boolean
  dirty: boolean
}

const scenes = new Set<Live>()
let running = false
const observer = typeof IntersectionObserver !== "undefined"
  ? new IntersectionObserver((entries) => {
    for (const e of entries) for (const s of scenes) if (s.canvas === e.target) { s.visible = e.isIntersecting; s.dirty = true }
    kick()
  })
  : null

function frame(now: number) {
  running = false
  let again = false
  for (const s of scenes) {
    if (!s.visible || (!s.animated && !s.dirty)) continue
    const dpr = window.devicePixelRatio || 1
    const w = Math.max(1, Math.round(s.canvas.clientWidth * dpr))
    const h = Math.max(1, Math.round(s.canvas.clientHeight * dpr))
    if (s.canvas.width !== w || s.canvas.height !== h) { s.canvas.width = w; s.canvas.height = h }
    const g = s.canvas.getContext("2d")
    if (!g) continue
    g.imageSmoothingEnabled = false
    g.clearRect(0, 0, w, h)
    s.draw({ g, w, h, dp: dpr, t: s.animated ? (now - s.start) / 1000 : 3 })
    s.dirty = false
    if (s.animated) again = true
  }
  if (again && !document.hidden) kick()
}

function kick() {
  if (running) return
  running = true
  requestAnimationFrame(frame)
}
if (typeof document !== "undefined") document.addEventListener("visibilitychange", () => { if (!document.hidden) kick() })
if (typeof window !== "undefined") window.addEventListener("resize", () => { for (const s of scenes) s.dirty = true; kick() })

/** Keeps [canvas] drawn by [draw]; returns the function that stops it. */
export function attach(canvas: HTMLCanvasElement, draw: (c: Ctx) => void, animated: boolean): () => void {
  const live: Live = { canvas, draw, start: performance.now(), visible: !observer, animated, dirty: true }
  scenes.add(live)
  observer?.observe(canvas)
  kick()
  return () => { scenes.delete(live); observer?.unobserve(canvas) }
}
