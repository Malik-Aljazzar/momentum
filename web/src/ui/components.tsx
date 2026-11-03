// Shared building blocks (android/.../ui/Components.kt, Nav.kt, Pixel.kt, PixelControls.kt, Hyper.kt).
import type { ComponentChildren, JSX } from "preact"
import { createPortal } from "preact/compat"
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks"
import { attach, pixelScale } from "../gfx/engine"
import { BANNER_GROUPS, bannerKinds, drawBanner, HYPERSPACE, ULTRA_HYPERSPACE } from "../gfx/banners"
import { drawScene, type Scene } from "../gfx/scenes"
import { drawVehicle, FRAME_MS, FRAMES, isVehicle, PAD } from "../gfx/vehicles"
import { textParts } from "../links"
import type { UserCard } from "../model"
import { nav } from "../nav"
import { hyperLore, imageRetry, online, req, state, systemFont, toast } from "../store"
import { S } from "../strings"
import { deleteBefore, insertEmoji, isEmojiIcon } from "../emoji"
import { EmojiButton } from "./emoji"

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ")

/** Resolves when the app's pixel fonts are loaded (they're precached, so this is quick after the first start). */
export const fontsLoaded: Promise<unknown> = typeof document === "undefined" || !document.fonts ? Promise.resolve()
  : Promise.all(["400 16px Pixelify", "500 16px Pixelify", "600 16px Pixelify", "400 16px Silkscreen", "700 16px Silkscreen"]
    .map((f) => document.fonts.load(f).catch(() => null)))

// ---------------------------------------------------------------- pixel art

/** 16x16 icon (rule icons and ui_* glyphs), scaled in whole steps so it stays crisp. */
export function PixelIcon({ name, size = 32, alpha = 1, tint, color, class: klass, style }: {
  name: string; size?: number; alpha?: number; tint?: "black" | "white"; class?: string; style?: JSX.CSSProperties
  /** draw the icon's shape in this colour (like ColorFilter.tint) */
  color?: string
}) {
  // a rule icon may be an emoji: the pixel emoji font draws it (1.125 em square, 0.875 em above the baseline)
  if (isEmojiIcon(name)) {
    const filter = tint === "black" ? "brightness(0)" : tint === "white" ? "brightness(0) invert(1)" : undefined
    return <span class={cx("emoji-icon", klass)} aria-hidden style={{ width: size, height: size, fontSize: size / 1.125, lineHeight: `${size}px`,
      opacity: alpha, filter, color: color ?? undefined, ...style }}>{name}</span>
  }
  if (color) {
    const mask = `url(/app/sprites/icons/${name}.png) center / contain no-repeat`
    return <span class={cx("pixel", klass)} style={{ display: "inline-block", width: size, height: size, flex: "none", opacity: alpha,
      background: color, WebkitMask: mask, mask, imageRendering: "pixelated", ...style }} />
  }
  const filter = tint === "black" ? "brightness(0)" : tint === "white" ? "brightness(0) invert(1)" : undefined
  return <img src={`/app/sprites/icons/${name}.png`} width={size} height={size} alt="" draggable={false}
    class={cx("pixel", klass)} style={{ opacity: alpha, filter, flex: "none", ...style }} />
}

/**
 * Animated vehicle (8-frame sheet, 64x32 per frame). A vehicle animates on a canvas with its effects around it
 * (gfx/vehicles.ts: dust, exhaust, contrails, engine trails ...; [night]: lights on); tinted / still ones and the
 * other sheets (gauge, friends) stay a plain sprite.
 */
export function VehicleSprite({ vkey, dpPerPixel, animate = true, tint, alpha = 1, night = false, style }: {
  vkey: string; dpPerPixel: number; animate?: boolean; tint?: "black" | "white"; alpha?: number; night?: boolean; style?: JSX.CSSProperties
}) {
  const dpr = window.devicePixelRatio || 1
  const sd = pixelScale(dpPerPixel)
  const s = sd / dpr
  const w = 64 * s, h = 32 * s
  if (animate && !tint && isVehicle(vkey)) {
    return <div class="pixel" style={{ width: w, height: h, flex: "none", position: "relative", opacity: alpha, ...style }}>
      <LiveCanvas class="vehicle-fx" draw={(c) => drawVehicle(c, vkey, sd, PAD.left * sd, PAD.top * sd, night)} style={{
        position: "absolute", left: -PAD.left * s, top: -PAD.top * s, width: (64 + PAD.left + PAD.right) * s, height: (32 + PAD.top + PAD.bottom) * s,
      }} />
    </div>
  }
  const filter = tint === "black" ? "brightness(0)" : tint === "white" ? "brightness(0) invert(1)" : undefined
  return <div class="pixel" style={{
    width: w, height: h, flex: "none", opacity: alpha, filter,
    backgroundImage: `url(/app/sprites/${vkey}/sheet.png)`, backgroundSize: `${w * FRAMES}px ${h}px`, backgroundRepeat: "no-repeat",
    animation: animate ? `vehicle ${FRAMES * FRAME_MS}ms steps(${FRAMES}) infinite` : undefined, ["--vw" as string]: `-${w * FRAMES}px`, ...style,
  }} />
}

/** A canvas drawn by the shared animation loop (only while it's on screen). */
export function LiveCanvas({ draw, animated = true, class: klass, style }: {
  draw: Parameters<typeof attach>[1]; animated?: boolean; class?: string; style?: JSX.CSSProperties
}) {
  const ref = useRef<HTMLCanvasElement>(null)
  const drawRef = useRef(draw)
  drawRef.current = draw
  useEffect(() => attach(ref.current!, (c) => drawRef.current(c), animated), [animated])
  return <canvas ref={ref} class={klass} style={{ display: "block", width: "100%", height: "100%", ...style }} />
}

export function SceneBg({ scene, groundDp = 120, sun = true, speed = 1, class: klass }: {
  scene: Scene; groundDp?: number; sun?: boolean; speed?: number; class?: string
}) {
  return <LiveCanvas class={cx("fill banner-bg", klass)} draw={(c) => drawScene(c, scene, groundDp, sun, speed)} />
}

/** Banner background; [still] for thumbnails (no animation); [speed] = the vehicle's (the world passes by that fast). */
export function BannerBg({ bkey, groundDp = 0, still = false, speed = 1, class: klass }: {
  bkey?: string | null; groundDp?: number; still?: boolean; speed?: number; class?: string
}) {
  // "banner-bg": explicitly underneath what follows (iOS Safari drew the animated canvas over the whole card -
  // momentum, vehicle, values - when the card had no glow frame: report #47)
  return <LiveCanvas class={cx("fill banner-bg", klass)} animated={!still} draw={(c) => drawBanner(c, bkey, groundDp, speed)} />
}

// ---------------------------------------------------------------- pixel controls

const PIXEL_CHECK = [[2, 5], [3, 6], [4, 7], [5, 6], [6, 5], [7, 4], [8, 3]]

/** Draws on a grid of square cells, snapped to whole pixels (crisp like the sprites). */
function gridSvg(cols: number, rows: number, cells: [number, number, number, number, string][], size: [number, number]) {
  return <svg width={size[0]} height={size[1]} viewBox={`0 0 ${cols} ${rows}`} shape-rendering="crispEdges" style={{ flex: "none", display: "block" }}>
    {cells.map(([x, y, w, h, c], i) => <rect key={i} x={x} y={y} width={w} height={h} fill={c} />)}
  </svg>
}

/** A block with stepped corners: outline, fill, a light top edge and a shaded bottom edge. */
function block(cols: number, rows: number, fill: string, outline: string, light?: string, shade?: string) {
  const cells: [number, number, number, number, string][] = [
    [1, 0, cols - 2, 1, outline], [1, rows - 1, cols - 2, 1, outline], [0, 1, 1, rows - 2, outline], [cols - 1, 1, 1, rows - 2, outline],
    [1, 1, cols - 2, rows - 2, fill],
  ]
  if (light) cells.push([1, 1, cols - 2, 1, light])
  if (shade) cells.push([1, rows - 2, cols - 2, 1, shade])
  return cells
}

/** The selection box: an empty pixel frame, or filled yellow with a pixel tick. */
export function PixelCheckbox({ checked, size = 22 }: { checked: boolean; size?: number }) {
  const cells = checked
    ? [...block(11, 11, "#ffcd75", "#8a5a2b", "#ffe3a8", "#e0a84e"),
      ...PIXEL_CHECK.map(([x, y]) => [x, y, 1, 2, "#1a1c2c"] as [number, number, number, number, string])]
    : block(11, 11, "rgba(255,255,255,0.08)", "rgba(255,255,255,0.4)")
  return gridSvg(11, 11, cells, [size, size])
}

const INFO_I = [[5, 2], [5, 3], [4, 5], [5, 5], [5, 6], [5, 7], [5, 8], [4, 9], [5, 9], [6, 9]]

/** The info button's face: a pixel frame with an "i" (rule cards: details / edit). */
export function PixelInfoIcon({ size = 30 }: { size?: number }) {
  const cells = [...block(11, 12, "rgba(255,255,255,0.08)", "rgba(255,255,255,0.4)", "rgba(255,255,255,0.13)"),
    ...INFO_I.map(([x, y]) => [x, y, 1, 1, "rgba(244,244,244,0.8)"] as [number, number, number, number, string])]
  return gridSvg(11, 12, cells, [size, Math.round(size * 12 / 11)])
}

/** A dotted pixel line (top to bottom) between two parts of a card. */
export const PixelDivider = () => <div style={{
  width: 2, alignSelf: "stretch", margin: "10px 0", flex: "none",
  backgroundImage: "linear-gradient(rgba(255,255,255,0.2) 50%, transparent 0)", backgroundSize: "2px 4px",
}} />

/** Up / down button for sorting: a yellow pixel block with a pixel arrow. */
export function PixelArrowButton({ up, enabled, onClick, label }: { up: boolean; enabled: boolean; onClick: () => void; label?: string }) {
  const ink = enabled ? "#1a1c2c" : "rgba(255,255,255,0.33)"
  const cells = enabled ? block(17, 14, "#ffcd75", "#8a5a2b", "#ffe3a8", "#e0a84e") : block(17, 14, "rgba(255,255,255,0.08)", "rgba(255,255,255,0.2)")
  const shape: [number, number, number][] = [[3, 7, 9], [4, 6, 10], [5, 5, 11], [6, 4, 12], [7, 7, 9], [8, 7, 9], [9, 7, 9]]
  for (const [y, a, b] of shape) cells.push([a, up ? y : 13 - y, b - a + 1, 1, ink])
  return <button aria-label={label ?? (up ? "▲" : "▼")} disabled={!enabled} onClick={(e) => { e.stopPropagation(); onClick() }}>
    {gridSvg(17, 14, cells, [34, 28])}
  </button>
}

// ---------------------------------------------------------------- text

const HYPER = /(?<![\p{L}\p{N}_@.])(ultra[- ]?)?(hyperspace|hyperraum)(?![\p{L}\p{N}_])/giu

/**
 * Text in which "Hyperraum / Hyperspace" as a word of its own shines (never inside @usernames);
 * "Ultra-Hyperspace" shines golden, but only for people who reached hyperspace (plain for the rest).
 * Tapping the word opens the lore screen.
 */
// the native app's sparkles (Hyper.kt): pixel crosses at fixed spots of the word, each on its own beat
const SPARK_SPOTS = [[-.04, .1], [.22, -.18], [.5, .05], [.74, -.2], [1.02, .2], [.36, .95], [.86, .9]]

function sparkShadow(arm: number, rgb: string) {
  const p = "var(--spark)"
  const out: string[] = []
  for (let k = 1; k <= arm; k++) {
    const c = `rgba(${rgb},${1 - k * .3})`
    out.push(`calc(${p} * ${k}) 0 ${c}`, `calc(${p} * -${k}) 0 ${c}`, `0 calc(${p} * ${k}) ${c}`, `0 calc(${p} * -${k}) ${c}`)
  }
  return out.join(", ")
}

function Sparkles({ seed, gold }: { seed: number; gold: boolean }) {
  const tint = gold ? "255,240,176" : "191,246,255"
  return <>{SPARK_SPOTS.map(([x, y], i) => {
    const rgb = i % 3 === 0 ? tint : "255,255,255"
    return <i key={i} class="spark" aria-hidden="true" style={{
      left: `${x * 100}%`, top: `${y * 100}%`, background: `rgb(${rgb})`, boxShadow: sparkShadow(i % 2 === 0 ? 2 : 1, rgb),
      animationDelay: `-${(((i * .37 + seed * .21) % 1) * 1.3).toFixed(2)}s`,
    }} />
  })}</>
}

export function HyperText({ text, class: klass, style }: { text: string; class?: string; style?: JSX.CSSProperties }) {
  const golden = !!state.value?.user.hyperspace
  const parts = useMemo(() => {
    const out: (string | { h: string; ultra: boolean })[] = []
    let last = 0
    for (const m of text.matchAll(HYPER)) {
      if (m[1] && !golden) continue
      out.push(text.slice(last, m.index), { h: m[0], ultra: !!m[1] })
      last = (m.index ?? 0) + m[0].length
    }
    out.push(text.slice(last))
    return out
  }, [text, golden])
  return <span class={klass} style={style}>
    {parts.map((p, i) => typeof p === "string" ? p : <span key={i} class={cx("hyper", p.ultra && "gold")} role="button"
      onClick={(e) => { e.stopPropagation(); e.preventDefault(); hyperLore.value = p.ultra ? ULTRA_HYPERSPACE : HYPERSPACE }}>
      {p.h}<Sparkles seed={i >> 1} gold={p.ultra} /></span>)}
  </span>
}

/**
 * One line of text; when it doesn't fit, it slides to the end and back (pausing at both ends) so the
 * whole text can be read - instead of wrapping.
 */
export function SlidingText({ text, class: klass, style }: { text: string; class?: string; style?: JSX.CSSProperties }) {
  const box = useRef<HTMLSpanElement>(null)
  const inner = useRef<HTMLSpanElement>(null)
  const [over, setOver] = useState(0)
  useLayoutEffect(() => {
    const measure = () => setOver(Math.max(0, (inner.current?.scrollWidth ?? 0) - (box.current?.clientWidth ?? 0)))
    measure()
    let raf = 0
    const ro = new ResizeObserver(() => { cancelAnimationFrame(raf); raf = requestAnimationFrame(measure) })
    ro.observe(box.current!)
    void fontsLoaded.then(measure)
    return () => { ro.disconnect(); cancelAnimationFrame(raf) }
  }, [text])
  const slide = Math.max(.6, over / 40)                    // ~40 px per second
  const total = 2 * slide + 2.4
  const kf = `slide-${Math.round(over)}-${Math.round(total * 10)}`
  return <span ref={box} class={klass} style={{ display: "block", overflow: "hidden", whiteSpace: "nowrap", ...style }}>
    {over > 0 && <style>{`@keyframes ${kf} { 0%, ${1.2 / total * 100}% { transform: none }
      ${(1.2 + slide) / total * 100}%, ${(2.4 + slide) / total * 100}% { transform: translateX(-${over}px) } 100% { transform: none } }`}</style>}
    <span ref={inner} style={{ display: "inline-block", animation: over > 0 ? `${kf} ${total}s linear infinite` : undefined }}>{text}</span>
  </span>
}

/** Single-line text that shrinks to fit instead of wrapping. */
export function FitText({ text, max, class: klass = "t-title", fitFor, style }: {
  text: string; max: number; class?: string; fitFor?: string; style?: JSX.CSSProperties
}) {
  const box = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState(max)
  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const fit = () => {
      const probe = document.createElement("span")
      probe.className = klass
      probe.style.cssText = `position:absolute;visibility:hidden;white-space:nowrap;font-size:${max}px`
      probe.textContent = fitFor ?? text
      document.body.appendChild(probe)
      const w = probe.getBoundingClientRect().width
      probe.remove()
      const avail = el.clientWidth
      setSize(w > avail && avail > 0 ? Math.max(12, Math.floor(max * avail / w)) : max)
    }
    fit()
    let raf = 0
    const ro = new ResizeObserver(() => { cancelAnimationFrame(raf); raf = requestAnimationFrame(fit) })
    ro.observe(el)
    // measure again once the pixel fonts are there (before that, a fallback font is measured)
    void fontsLoaded.then(fit)
    return () => { ro.disconnect(); cancelAnimationFrame(raf) }
  }, [text, fitFor, max, klass])
  return <div ref={box} style={{ width: "100%", overflow: "hidden", ...style }}>
    <HyperText text={text} class={klass} style={{ fontSize: size, lineHeight: `${Math.round(size * 1.05)}px`, whiteSpace: "nowrap", display: "block" }} />
  </div>
}

export const Label = ({ text, color, class: klass, style }: { text: string; color?: string; class?: string; style?: JSX.CSSProperties }) =>
  <span class={cx("t-label", klass)} style={{ color, ...style }}>{text}</span>

/** User-written text (chat, comments, captions, bios): pixel font or the system font (setting). */
export const UserText = ({ text, class: klass = "t-body", style }: { text: string; class?: string; style?: JSX.CSSProperties }) =>
  <HyperText text={text} class={cx(klass, "user-text", systemFont.value && "system")} style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", ...style }} />

// ---------------------------------------------------------------- controls

export type BtnStyle = "primary" | "ghost" | "danger"

export function PxButton({ text, onClick, kind = "primary", class: klass, style, disabled }: {
  text: string; onClick: () => void; kind?: BtnStyle; class?: string; style?: JSX.CSSProperties; disabled?: boolean
}) {
  return <button class={cx("btn t-button", kind, klass)} style={style} disabled={disabled} onClick={onClick}>{text}</button>
}

export function PxCard({ children, onClick, class: klass, style, padding = 20, bg }: {
  children: ComponentChildren; onClick?: () => void; class?: string; style?: JSX.CSSProperties; padding?: number; bg?: string
}) {
  return <div class={cx("card col", onClick && "clickable", klass)} onClick={onClick}
    style={{ padding, background: bg, cursor: onClick ? "pointer" : undefined, ...style }}>{children}</div>
}

/** Blocky segmented progress bar. */
export function SegBar({ fraction, segments = 20, color = "var(--yellow)" }: { fraction: number; segments?: number; color?: string }) {
  const on = Math.max(0, Math.min(segments, Math.floor(fraction * segments)))
  return <div class="segbar">{Array.from({ length: segments }, (_, i) => <i key={i} style={{ background: i < on ? color : undefined }} />)}</div>
}

export function PxTextField({ value, onInput, placeholder = "", password, type = "text", onDone, user, autoFocus, maxLength, class: klass, inputMode, emoji }: {
  value: string; onInput: (v: string) => void; placeholder?: string; password?: boolean; type?: string; onDone?: () => void
  user?: boolean; autoFocus?: boolean; maxLength?: number; class?: string; inputMode?: JSX.HTMLAttributes["inputMode"]
  /** a smiley button next to the field: the emoji picker puts its pick at the caret */
  emoji?: boolean
}) {
  const ref = useRef<HTMLInputElement>(null)
  const input = <input ref={ref} class={cx("field", user && "user-text", user && systemFont.value && "system", klass)} value={value}
    type={password ? "password" : type} placeholder={placeholder} autoFocus={autoFocus} maxLength={maxLength} inputMode={inputMode}
    autoCapitalize="off" autoCorrect="off" spellcheck={false}
    onInput={(e) => onInput((e.target as HTMLInputElement).value)}
    onKeyDown={(e) => { if (e.key === "Enter") onDone?.() }} />
  if (!emoji) return input
  return <div class="row" style={{ gap: 2 }}>
    <div class="grow">{input}</div>
    <FieldEmoji field={ref} value={value} onInput={onInput} maxLength={maxLength} />
  </div>
}

/** The emoji button of a text field. The picker stays open while picking (report #64) and works at the field's caret,
 *  kept here meanwhile (focusing the field after each pick would bring up the keyboard over the picker); when it
 *  closes, the caret goes behind the last pick. */
function FieldEmoji({ field, value, onInput, maxLength }: {
  field: { current: HTMLInputElement | HTMLTextAreaElement | null }; value: string; onInput: (v: string) => void; maxLength?: number
}) {
  const caret = useRef<[number, number] | null>(null)
  const at = (): [number, number] => caret.current ?? [field.current?.selectionStart ?? value.length, field.current?.selectionEnd ?? value.length]
  return <EmojiButton text={value}
    onOpen={() => { caret.current = null; caret.current = at() }}
    onPick={(emoji) => {
      const next = insertEmoji(value, ...at(), emoji)
      if (maxLength != null && next.value.length > maxLength) return
      caret.current = [next.caret, next.caret]
      onInput(next.value)
    }}
    onDelete={() => { const next = deleteBefore(value, ...at()); caret.current = [next.caret, next.caret]; onInput(next.value) }}
    onClose={() => {
      const el = field.current, c = caret.current
      caret.current = null
      if (el && c) requestAnimationFrame(() => { el.focus(); el.setSelectionRange(c[0], c[0]) })
    }} />
}

/** Multi-line text (captions, bios) in the look of PxTextField, with the emoji button below its corner. */
export function PxTextArea({ value, onInput, placeholder = "", rows = 3, user, maxLength, class: klass, style, emoji = true }: {
  value: string; onInput: (v: string) => void; placeholder?: string; rows?: number; user?: boolean; maxLength?: number
  class?: string; style?: JSX.CSSProperties; emoji?: boolean
}) {
  const ref = useRef<HTMLTextAreaElement>(null)
  return <div class="col" style={{ position: "relative" }}>
    <textarea ref={ref} class={cx("field", user && "user-text", user && systemFont.value && "system", klass)} rows={rows} value={value}
      placeholder={placeholder} style={{ resize: "none", paddingRight: emoji ? 44 : undefined, ...style }}
      onInput={(e) => { const v = (e.target as HTMLTextAreaElement).value; if (maxLength == null || v.length <= maxLength) onInput(v) }} />
    {emoji && <div style={{ position: "absolute", right: 2, bottom: 2 }}>
      <FieldEmoji field={ref} value={value} onInput={onInput} maxLength={maxLength} />
    </div>}
  </div>
}

export function IconBtn({ icon, onClick, badge = 0, alpha = 1, label }: { icon: string; onClick: () => void; badge?: number; alpha?: number; label?: string }) {
  return <button class="icon-btn" aria-label={label ?? icon} onClick={(e) => { e.stopPropagation(); onClick() }}>
    <PixelIcon name={icon} size={24} alpha={alpha} />
    {badge > 0 && <span class="badge">{badge > 99 ? "99+" : badge}</span>}
  </button>
}

export function TopBar({ title, onBack = nav.pop, children }: { title: string; onBack?: (() => void) | null; children?: ComponentChildren }) {
  return <div class="topbar">
    {onBack ? <IconBtn icon="ui_back" onClick={onBack} label="back" /> : <span style={{ width: 10 }} />}
    <div class="t-title ellipsis grow" style={{ fontSize: 20, paddingLeft: 4 }}>{text(title)}</div>
    {children}
  </div>
}
const text = (s: string) => <HyperText text={s} />

// ---------------------------------------------------------------- images

/** Network image that doesn't give up: a failed load is retried with growing pauses and after each sync. */
export function RemoteImage({ url, class: klass, style, fallback, fit = "cover", alt = "" }: {
  url?: string | null; class?: string; style?: JSX.CSSProperties; fallback?: ComponentChildren; fit?: "cover" | "contain"; alt?: string
}) {
  // the loading state belongs to the url: resetting it in an effect (after the first frame) hid
  // images from the cache whose load event came before that (own avatar, post media)
  type Img = { url: string | null | undefined; attempt: number; failed: boolean; loaded: boolean }
  const fresh = (): Img => ({ url, attempt: 0, failed: false, loaded: false })
  const [img, setImg] = useState<Img>(fresh)
  const cur = img.url === url ? img : fresh()
  const { attempt, failed, loaded } = cur
  const update = (p: Partial<Img>) => setImg((x) => ({ ...(x.url === url ? x : fresh()), ...p }))
  const retry = imageRetry.value
  useEffect(() => {
    if (!failed) return
    const t = setTimeout(() => update({ failed: false, attempt: attempt + 1 }), attempt === 0 ? 0 : Math.min(60_000, 2000 * 2 ** Math.min(attempt - 1, 5)))
    return () => clearTimeout(t)
  }, [failed, attempt, retry, url])
  // class "fill" (absolute, inset 0) must keep its position: an inline "relative" overrode it and
  // left the box 0 px high - photos in the feed loaded but didn't show
  const fill = klass?.split(" ").includes("fill")
  return <div class={klass} style={{ position: fill ? "absolute" : "relative", overflow: "hidden", ...style }}>
    {!loaded && fallback}
    {url && !failed && <img key={attempt} src={url} alt={alt} loading="lazy" decoding="async"
      style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: fit, opacity: loaded ? 1 : 0 }}
      onLoad={() => update({ loaded: true })} onError={() => update({ failed: true })} />}
  </div>
}

export function Avatar({ name, url, size }: { name: string; url?: string | null; size: number }) {
  const radius = size * 0.28
  const initial = <div class="center t-block" style={{
    width: size, height: size, borderRadius: radius, background: "linear-gradient(135deg, #3b5dc9, #5d275d)",
    fontSize: size * 0.42, fontWeight: 400, flex: "none",
  }}>{(name[0] ?? "?").toUpperCase()}</div>
  if (!url) return initial
  return <RemoteImage url={url} fallback={initial} style={{ width: size, height: size, borderRadius: radius, flex: "none" }} />
}

// ---------------------------------------------------------------- feedback

export function ToastHost({ bottom }: { bottom: string }) {
  const t = toast.value
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    if (!t) return
    setVisible(true)
    const h = setTimeout(() => setVisible(false), 2600)
    return () => clearTimeout(h)
  }, [t?.nonce])
  return <div class="toast" role="status" style={{ bottom, opacity: visible ? 1 : 0, transform: visible ? "none" : "translateY(20px)" }}>
    {t && <div style={{ background: t.error ? "var(--orange)" : "var(--text)" }}>{t.text}</div>}
  </div>
}

/** Bottom sheet (ModalBottomSheet / PxSheet). */
/** A bottom sheet. Rendered into <body>: above the tab bar, never clipped by a card
 *  or scrolled along with the list it was opened from. */
export function Sheet({ onClose, children, label }: { onClose: () => void; children: ComponentChildren; label?: string }) {
  return createPortal(<>
    <div class="sheet-scrim" onClick={onClose} />
    <div class="sheet" role="dialog" aria-label={label}>
      <div class="handle" />
      <div class="body">{children}</div>
    </div>
  </>, document.body)
}

export function EmptyHint({ title, sub, icon = "chat" }: { title: string; sub?: string; icon?: string }) {
  return <div class="empty">
    <PixelIcon name={icon} size={48} alpha={.8} />
    <div class="t-strong" style={{ fontSize: 18, marginTop: 12 }}>{title}</div>
    {sub && <div class="t-small" style={{ marginTop: 4 }}>{sub}</div>}
  </div>
}

/** Whole area needs the internet and nothing is cached. */
export function OfflineState() {
  return <div class="empty">
    <PixelIcon name="storm" size={64} />
    <div class="t-strong" style={{ fontSize: 18, marginTop: 14 }}>{S.value.offlineTitle}</div>
    <div class="t-small" style={{ marginTop: 4 }}>{S.value.offlineSub}</div>
  </div>
}

/** Thin strip under a top bar: the content is cached and not updating. */
export function OfflineStrip() {
  if (online.value) return null
  return <div class="row" style={{ background: "rgba(239,125,87,0.16)", padding: "6px 16px", gap: 8 }}>
    <PixelIcon name="storm" size={16} />
    <span class="t-small" style={{ color: "var(--yellow)" }}>{S.value.offlineStale}</span>
  </div>
}

/** "MOMENTUM · 🏆 COMPETITION" or "FREE MOMENTUM · UNRANKED" above the big number. */
export function ScoreLabel({ competing }: { competing: boolean }) {
  const s = S.value
  return competing
    ? <div class="row" style={{ gap: 8 }}>
      <Label text="MOMENTUM" />
      <span class="row" style={{ borderRadius: 6, background: "rgba(255,205,117,0.2)", padding: "2px 6px", gap: 4 }}>
        <PixelIcon name="trophy" size={12} /><Label text={s.competition} color="var(--yellow)" />
      </span>
    </div>
    : <div class="row" style={{ gap: 8 }}>
      <Label text={s.freeMomentum} />
      <span style={{ borderRadius: 6, background: "rgba(255,255,255,0.12)", padding: "2px 6px" }}><Label text={s.unranked} /></span>
    </div>
}

// ---------------------------------------------------------------- people

/** Person in a list: avatar, name, optional trailing action. */
export function UserRow({ user, onClick, trailing }: { user: UserCard; onClick: () => void; trailing?: ComponentChildren }) {
  return <div class="row list-item" style={{ padding: "10px 16px", gap: 12, cursor: "pointer" }} onClick={onClick}>
    <Avatar name={user.display_name} url={user.avatar} size={44} />
    <div class="row grow" style={{ gap: 6 }}>
      <span class="t-strong ellipsis">{user.username}</span>
      {user.friend && <Label text={S.value.friend} color="var(--lime)" />}
    </div>
    {trailing}
  </div>
}

/** Follow / Following / Follow back. */
export function FollowButton({ user, small = true, requested = false, onToggle }: { user: UserCard; small?: boolean; requested?: boolean; onToggle: () => void }) {
  const s = S.value
  if (user.is_me) return null
  const muted = user.following || requested
  const label = user.following ? s.followingButton : requested ? s.requested : user.follows_me ? s.followBack : s.follow
  return <button class="t-button" onClick={(e) => { e.stopPropagation(); onToggle() }} style={{
    borderRadius: 12, background: muted ? "var(--faint)" : "var(--text)", color: muted ? "var(--text)" : "#000",
    padding: small ? "9px 14px" : "13px 18px", flex: "none",
  }}>{label}</button>
}

// ---------------------------------------------------------------- lists

/** Infinite scroll: calls [load] when this marker comes near the screen. */
export function LoadMore({ hasMore, load }: { hasMore: boolean; load: () => Promise<unknown> | void }) {
  const ref = useRef<HTMLDivElement>(null)
  const busy = useRef(false)
  useEffect(() => {
    if (!hasMore || !ref.current) return
    const io = new IntersectionObserver(async (es) => {
      if (!es.some((e) => e.isIntersecting) || busy.current) return
      busy.current = true
      try { await load() } finally { busy.current = false }
    }, { rootMargin: "600px" })
    io.observe(ref.current)
    return () => io.disconnect()
  }, [hasMore, load])
  return hasMore ? <div ref={ref} style={{ height: 1 }} /> : null
}

/** Pages of users (offset based) for long people lists. */
export function useUserPages(fetch: (offset: number, limit: number) => Promise<UserCard[] | null>, deps: unknown[], size = 30) {
  const [users, setUsers] = useState<UserCard[] | null>(null)
  const [hasMore, setHasMore] = useState(true)
  const loading = useRef(false)
  const more = async (reset = false) => {
    if (loading.current) return
    loading.current = true
    const have = reset ? [] : users ?? []
    const page = await fetch(have.length, size)
    if (page) {
      const seen = new Set(have.map((u) => u.username))
      setUsers([...have, ...page.filter((u) => !seen.has(u.username))])
      setHasMore(page.length >= size)
    } else if (reset || users == null) setUsers([])
    loading.current = false
  }
  useEffect(() => { setUsers(null); setHasMore(true); void more(true) }, deps)
  const update = (u: UserCard) => setUsers((list) => list?.map((x) => (x.username === u.username ? u : x)) ?? null)
  return { users, hasMore, more: () => more(false), update }
}

/** Long press (≈ 500 ms, like Android) vs. tap; moving the finger cancels both. */
export function usePress(onTap: () => void, onLong?: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout>>()
  const start = useRef<{ x: number; y: number } | null>(null)
  const longDone = useRef(false)
  const cancel = () => { clearTimeout(timer.current); start.current = null }
  return {
    onPointerDown: (e: PointerEvent) => {
      start.current = { x: e.clientX, y: e.clientY }
      longDone.current = false
      if (onLong) timer.current = setTimeout(() => { longDone.current = true; navigator.vibrate?.(15); onLong() }, 500)
    },
    onPointerMove: (e: PointerEvent) => {
      if (start.current && Math.hypot(e.clientX - start.current.x, e.clientY - start.current.y) > 10) cancel()
    },
    onPointerUp: () => {
      const wasPress = start.current != null
      cancel()
      if (wasPress && !longDone.current) onTap()
    },
    onPointerCancel: cancel,
    onContextMenu: (e: Event) => e.preventDefault(),
  }
}

/** On/off switch (lime when on, like the app's Material switch). */
export function Switch({ on, onChange, label }: { on: boolean; onChange: (on: boolean) => void; label?: string }) {
  return <button role="switch" aria-checked={on} aria-label={label} onClick={(e) => { e.stopPropagation(); onChange(!on) }} style={{
    width: 52, height: 32, borderRadius: 16, flex: "none", position: "relative", transition: "background .15s",
    background: on ? "rgba(167,240,112,0.3)" : "var(--faint)",
  }}>
    <i style={{
      position: "absolute", top: on ? 4 : 8, left: on ? 24 : 8, width: on ? 24 : 16, height: on ? 24 : 16, borderRadius: 12,
      background: on ? "var(--lime)" : "var(--muted)", transition: "all .15s",
    }} />
  </button>
}

// ---------------------------------------------------------------- mentions, banners

const MENTION_TAIL = /(^|\s)@([a-z0-9_.]{0,30})$/i

/**
 * Suggestions while typing "@name" at the end of [text] (like Instagram): exact match first, then
 * people I follow / who follow me (ranked by the server). Picking completes the name.
 */
export function MentionSuggestions({ text, scope = "", onPick }: { text: string; scope?: string; onPick: (t: string) => void }) {
  const query = text.match(MENTION_TAIL)?.[2]
  const [users, setUsers] = useState<UserCard[]>([])
  useEffect(() => {
    if (query == null || !online.value) { setUsers([]); return }
    const h = setTimeout(async () => {          // debounce typing
      setUsers((await req<{ users: UserCard[] }>("GET", "/mentions?q=" + encodeURIComponent(query) + scope))?.users ?? [])
    }, 150)
    return () => clearTimeout(h)
  }, [query, scope])
  if (query == null || users.length === 0) return null
  const s = S.value
  return <div class="col" style={{ borderRadius: 16, background: "var(--card2)", padding: "4px 0" }}>
    {users.slice(0, 5).map((u) => <div key={u.username} class="row" style={{ padding: "7px 12px", gap: 10, cursor: "pointer" }}
      onPointerDown={(e) => e.preventDefault()}
      onClick={() => onPick(text.slice(0, text.length - query.length) + u.username + " ")}>
      <Avatar name={u.display_name} url={u.avatar} size={28} />
      <span class="t-strong ellipsis">{u.username}</span>
      {(u.friend || u.following) && <Label text={u.friend ? s.friend : s.followingButton} color={u.friend ? "var(--lime)" : "var(--muted)"} />}
    </div>)}
  </div>
}

/** Grid of all banners; [allowAuto] adds "automatic" (the vehicle's own scene, key = null). */
export function BannerPicker({ selected, allowAuto, autoKey = "dusk", hyperspace, ultra, kind, onClose, onPick }: {
  selected: string | null | undefined; allowAuto: boolean; autoKey?: string
  /** own vehicle: undefined = not offered, false = locked, true = unlocked */
  hyperspace?: boolean; kind?: string; onClose: () => void; onPick: (key: string | null) => void
  /** the secret golden one: only listed once unlocked */
  ultra?: boolean
}) {
  const s = S.value
  const options: (string | null)[] = [...(ultra ? [ULTRA_HYPERSPACE] : []), ...(hyperspace !== undefined ? [HYPERSPACE] : []), ...(allowAuto ? [null] : [])]
  const groups = BANNER_GROUPS.map(([g, keys]) => [g, kind ? keys.filter((k) => bannerKinds(k).has(kind)) : keys] as const).filter(([, k]) => k.length)
  const tile = (key: string | null) => {
    const locked = key === HYPERSPACE && hyperspace !== true
    return <button key={String(key)} disabled={locked} onClick={() => onPick(key)} style={{
      position: "relative", height: 96, borderRadius: 16, overflow: "hidden", boxShadow: key === selected ? "inset 0 0 0 2px var(--yellow)" : undefined,
    }}>
      <BannerBg bkey={key ?? autoKey} still />
      {locked && <div class="fill center" style={{ background: "rgba(0,0,0,0.7)", gap: 6 }}>
        <PixelIcon name="ui_lock" size={16} /><span class="t-small" style={{ color: "var(--text)" }}>{s.hyperspaceLocked}</span>
      </div>}
      <HyperText text={key == null ? s.bannerAuto : s.bannerName(key)} class="t-small"
        style={{ position: "absolute", left: 0, bottom: 0, background: "rgba(0,0,0,0.6)", borderTopRightRadius: 8, padding: "3px 8px", color: "var(--text)", fontSize: 12 }} />
    </button>
  }
  return <Sheet onClose={onClose}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.chooseBanner}</div>
    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
      {options.map(tile)}
      {groups.map(([g, keys]) => [
        <Label key={g} text={s.bannerGroup(g)} style={{ gridColumn: "1 / -1", padding: "10px 0 2px" }} />,
        ...keys.map(tile),
      ])}
    </div>
  </Sheet>
}

/** Text with tappable @mentions and web links (cyan); usernames and links never get the hyperspace shine. */
export function MentionText({ prefixBold, text, class: klass = "t-body", style, onMention }: {
  prefixBold?: string | null; text: string; class?: string; style?: JSX.CSSProperties; onMention?: () => void
}) {
  const parts = useMemo(() => textParts(text), [text])
  return <span class={cx(klass, "user-text", systemFont.value && "system")} style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", ...style }}>
    {prefixBold && <><b style={{ fontWeight: 600 }}>{prefixBold}</b>{"  "}</>}
    {parts.map((p, i) => "url" in p
      ? <a key={i} class="text-link" href={p.url} target="_blank" rel="noopener noreferrer" style={{ color: "var(--cyan)", textDecoration: "underline" }}
        onClick={(e) => e.stopPropagation()}>{p.text}</a>
      : "user" in p ? <a key={i} style={{ color: "var(--cyan)", cursor: "pointer" }}
        onClick={(e) => { e.stopPropagation(); onMention?.(); nav.push({ r: "user", username: p.user.toLowerCase() }) }}>{p.text}</a>
      : <HyperText key={i} text={p.text} />)}
  </span>
}

/** Empty contact lists: a person waving at the friend that is still missing. */
export function EmptyFriends({ title, sub, showSearch = true }: { title?: string; sub?: string; showSearch?: boolean }) {
  const s = S.value
  return <div class="empty">
    <VehicleSprite vkey="friends" dpPerPixel={3} />
    <div class="t-strong" style={{ fontSize: 18, marginTop: 14 }}>{title ?? s.noContacts}</div>
    <div class="t-small" style={{ marginTop: 4 }}>{sub ?? s.noContactsSub}</div>
    {showSearch && <PxButton text={s.findPeople} kind="ghost" style={{ marginTop: 16 }} onClick={() => nav.push({ r: "search" })} />}
  </div>
}
