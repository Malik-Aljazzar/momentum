// Full-screen moments (android/.../ui/App.kt): milestone reached, hyperspace jump, update needed.
import { useEffect, useMemo, useState } from "preact/hooks"
import { sceneFor } from "../gfx/scenes"
import { glow, GOLD_RGB, HYPER_RGB, HYPERSPACE, ULTRA_HYPERSPACE } from "../gfx/banners"
import { phase, rect, type Ctx } from "../gfx/engine"
import { meta, req, save, showToast, state } from "../store"
import { S } from "../strings"
import { BannerBg, FitText, HyperText, Label, LiveCanvas, PixelIcon, PxButton, SceneBg, Sheet, VehicleSprite } from "../ui/components"
import type { Release } from "../changelog"

const CONFETTI = ["#FFCD75", "#EF7D57", "#A7F070", "#73EFF7", "#F4F4F4", "#B13E53", "#41A6F6"]

function useClock(maxMs: number) {
  const [t, setT] = useState(0)
  useEffect(() => {
    const start = performance.now()
    let raf = 0
    const step = (now: number) => { const v = now - start; setT(v); if (v < maxMs) raf = requestAnimationFrame(step) }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [])
  return t / 1000
}

export function MilestoneOverlay({ onClose }: { onClose: () => void }) {
  const s = S.value
  const m = meta.value, st = state.value
  const sec = useClock(4000)
  const confetti = useMemo(() => Array.from({ length: 70 }, (_, i) => ({
    x: Math.random(), delay: Math.random() * .6, speed: .35 + Math.random() * .4, dx: (Math.random() - .5) * 120, color: CONFETTI[i % CONFETTI.length],
  })), [])
  if (!m || !st) return null
  const v = m.vehicles[st.vehicle.index]
  if (!v) return null
  return <div class="fill banner-card" role="dialog" aria-label={s.levelUp} style={{ zIndex: 80, background: "#000" }}>
    <SceneBg scene={sceneFor(v.category)} groundDp={0} />
    <div class="fill col center" style={{ flexDirection: "column", padding: 24, textAlign: "center" }}>
      <Label text={s.milestoneReached} />
      <div class="t-block" style={{ fontSize: 52, lineHeight: "54px" }}>{s.levelUp}</div>
      <div style={{ height: 28 }} />
      <VehicleSprite vkey={v.key} dpPerPixel={5} />
      <div style={{ height: 28 }} />
      <div class="t-strong" style={{ fontSize: 18 }}>{s.nowDriving} {s.vehicle(v.key, v.label)}</div>
      <div style={{ height: 20 }} />
      {/* milestones appear in the feed only if the user decides to share them */}
      <PxButton text={s.shareMilestone} style={{ width: 260 }} onClick={async () => {
        if (await req("POST", "/posts/milestone", { vehicle: v.key })) { showToast(s.shared); onClose() }
      }} />
      <div style={{ height: 10 }} />
      <PxButton text={s.next} kind="ghost" style={{ width: 260 }} onClick={onClose} />
    </div>
    <div class="fill" style={{ pointerEvents: "none", overflow: "hidden" }}>
      {confetti.map((c, i) => {
        const p = (sec - c.delay) * c.speed
        if (p < 0 || p > 1.2) return null
        return <i key={i} style={{ position: "absolute", width: 8, height: 8, background: c.color, top: `calc(${p * 110}% - 12px)`, left: `calc(${c.x * 100}% + ${c.dx * p}px)` }} />
      })}
    </div>
  </div>
}

/** Easter egg at the hyperspace milestone: the ship accelerates and jumps into hyperspace. */
export function HyperspaceOverlay({ onClose }: { onClose: () => void }) {
  const s = S.value
  const sec = useClock(6000)
  const shake = sec < 1.6 ? Math.sin(sec * 60) * sec * 2 : 0
  const scale = sec < 1.6 ? 5 : sec < 2.2 ? 5 - (sec - 1.6) * 6 : 1.4 + Math.sin(sec * 3) * .05
  const flash = sec >= 1.6 && sec <= 2.6 ? 1 - (sec - 1.6) : 0
  return <div class="fill" role="dialog" style={{ zIndex: 81, background: "#000" }}>
    <BannerBg bkey={sec < 1.6 ? "cosmos" : HYPERSPACE} />
    <div class="fill center" style={{ transform: `translateX(${shake}px)` }}>
      <VehicleSprite vkey={meta.value?.vehicles.at(-1)?.key ?? "kreuzer"} dpPerPixel={Math.max(1, scale)} />
    </div>
    {flash > 0 && <div class="fill" style={{ background: `rgba(255,255,255,${Math.min(1, flash)})` }} />}
    {sec > 2.4 && <div class="col" style={{
      position: "absolute", left: 24, right: 24, bottom: "calc(var(--safe-bottom) + 36px)", alignItems: "center", textAlign: "center",
      opacity: Math.min(1, (sec - 2.4) * 2),
    }}>
      <FitText text={s.hyperspaceTitle} max={44} class="t-block" />
      <div style={{ height: 8 }} />
      <HyperText text={s.hyperspaceSub} class="t-body" />
      <div style={{ height: 16 }} />
      <PxButton text={s.useAsBackground} style={{ width: 280 }} onClick={async () => { await save("PUT", "/settings", { banner_space: HYPERSPACE }); onClose() }} />
      <div style={{ height: 10 }} />
      <PxButton text={s.next} kind="ghost" style={{ width: 280 }} onClick={onClose} />
    </div>}
  </div>
}

/**
 * The hyperspace lore (tap on the shining word): a mystical star sky, a slogan in the middle and the
 * hyperspace background below - still and locked until unlocked, animated afterwards. The golden
 * ultra version shows its (secret) background only once it's unlocked. Tap anywhere to close.
 */
export function HyperLoreOverlay({ bkey, onClose }: { bkey: string; onClose: () => void }) {
  const s = S.value
  const u = state.value?.user
  const ultra = bkey === ULTRA_HYPERSPACE
  const unlocked = ultra ? !!u?.ultra : !!u?.hyperspace
  const sec = useClock(3500)
  // the keyboard goes away (tapped in a text being typed, e.g. a comment)
  useEffect(() => { (document.activeElement as HTMLElement | null)?.blur?.() }, [])
  const text = ultra ? s.ultraLore : s.hyperLore
  // the slogan types itself; the rest is already laid out (hidden), so the lines don't jump
  const shown = Math.max(0, Math.min(text.length, Math.floor((sec - .5) / 2.4 * text.length)))
  return <div class="fill lore" role="dialog" aria-label={text} style={{ zIndex: 82, background: "#02030A" }} onClick={onClose}>
    <LiveCanvas class="fill" draw={(c) => drawLoreSky(c, ultra ? GOLD_RGB : HYPER_RGB)} />
    <div class="fill col center" style={{ flexDirection: "column", padding: "0 28px", textAlign: "center" }}>
      <HyperText text={(s.bannerName(bkey)).toUpperCase()} class="t-block" style={{ fontSize: ultra ? 24 : 30 }} />
      <div style={{ height: 28 }} />
      <div class={ultra ? "t-title lore-text gold" : "t-title lore-text"} style={{ fontSize: 22, lineHeight: "30px", minHeight: 96, width: "100%" }}>
        {text.slice(0, shown)}<span style={{ visibility: "hidden" }}>{text.slice(shown)}</span>
      </div>
      {(!ultra || unlocked) && <>
        <div style={{ height: 36 }} />
        <div class={ultra ? "lore-banner gold" : "lore-banner"} style={{ position: "relative", width: "100%", height: 150, borderRadius: 16, overflow: "hidden" }}>
          <BannerBg bkey={bkey} still={!unlocked} />
          {!unlocked && <div class="fill center" style={{ background: "rgba(0,0,0,0.7)", gap: 6 }}>
            <PixelIcon name="ui_lock" size={18} /><span class="t-small" style={{ color: "var(--text)" }}>{s.hyperspaceLocked}</span>
          </div>}
        </div>
      </>}
    </div>
  </div>
}

const LORE_STARS = Array.from({ length: 170 }, (_, i) => {
  const r = (n: number) => (Math.sin(i * 12.9898 + n * 78.233) * 43758.5453) % 1
  const f = (n: number) => Math.abs(r(n))
  return { x: f(1), y: f(2), size: f(3) < .12 ? 2 : 1, speed: .4 + f(4), seed: f(5) * 6.28, color: Math.floor(f(6) * 5) }
})

/** Nebula clouds drifting in the word's colors, twinkling pixel stars, now and then a shooting star. */
function drawLoreSky(c: Ctx, rgb: string[]) {
  const { w, h, dp, t } = c
  rect(c, "#02030A", 0, 0, w, h)
  const nebulae: [number, number, number, number][] = [[2, .25, .3, .55], [1, .8, .55, .6], [0, .4, .82, .45]]
  nebulae.forEach(([k, x, y, r], i) => {
    const breathe = Math.sin(t * .5 + i * 2.1) * .5 + .5
    glow(c, (x + Math.sin(t * .07 + i) * .06) * w, (y + Math.cos(t * .05 + i * 1.7) * .05) * h, r * w * (1 + breathe * .15),
      `rgba(${rgb[k]},${.16 + breathe * .14})`)
  })
  const palette = ["255,255,255", "255,255,255", rgb[0], rgb[1], rgb[2]]
  const px = 2 * dp
  for (const st of LORE_STARS) {
    const tw = Math.sin(t * st.speed * 1.8 + st.seed) * .5 + .5
    const a = .15 + .85 * tw * tw
    // a slow pull towards the center: like looking into hyperspace
    const k = (t * .012 * st.speed + st.seed) % 1
    const x = w / 2 + (st.x - .5) * w * (1.25 - k * .25)
    const y = h / 2 + (st.y - .5) * h * (1.25 - k * .25)
    const d = px * st.size
    rect(c, `rgba(${palette[st.color]},${a})`, x, y, d, d)
    if (st.size === 2 && a > .7) {          // the bright ones get a pixel cross
      const arm = `rgba(${palette[st.color]},${(a - .6) * 1.6})`
      rect(c, arm, x - d, y + d / 4, d * 3, d / 2)
      rect(c, arm, x + d / 4, y - d, d / 2, d * 3)
    }
  }
  // a shooting star every 4.5 s
  const p = phase(t, 4500)
  if (p < .25) {
    const f = p / .25, n = Math.floor(t / 4.5)
    const sx = w * (.2 + (n * .37 % 1) * .7) - f * w * .5
    const sy = h * (.08 + (n * .53 % 1) * .3) + f * h * .18
    const a = Math.sin(f * Math.PI)
    const g = c.g.createLinearGradient(sx, sy, sx + 90 * dp, sy - 32 * dp)
    g.addColorStop(0, `rgba(255,255,255,${a})`); g.addColorStop(.4, `rgba(${rgb[0]},${a * .5})`); g.addColorStop(1, "rgba(0,0,0,0)")
    c.g.strokeStyle = g; c.g.lineWidth = 2 * dp
    c.g.beginPath(); c.g.moveTo(sx, sy); c.g.lineTo(sx + 90 * dp, sy - 32 * dp); c.g.stroke()
  }
}

/** The server asks for a newer app: the PWA reloads itself (new files from the server). */
/** What's new after an update (android: WhatsNewSheet in ui/Settings.kt): the releases' points, newest first. */
export function WhatsNewOverlay({ releases, onClose }: { releases: Release[]; onClose: () => void }) {
  const s = S.value
  return <Sheet onClose={onClose} label={s.whatsNewTitle}>
    <Label text={s.whatsNewTitle} color="var(--yellow)" />
    {releases.map((r) => <div key={r.version} class="col" style={{ gap: 6 }} data-release={r.version}>
      <span class="t-small">{r.version}</span>
      {(s.lang === "de" ? r.de : r.en).map((line, i) => <div key={i} class="row" style={{ gap: 12, alignItems: "flex-start" }}>
        <i style={{ flex: "none", width: 6, height: 6, marginTop: 7, background: "var(--lime)" }} />
        <span class="t-body">{line}</span>
      </div>)}
    </div>)}
    <PxButton text={s.whatsNewOk} onClick={onClose} />
  </Sheet>
}

export function UpdateScreen() {
  const s = S.value
  return <div class="fill" style={{ zIndex: 95, background: "var(--bg)" }}>
    <BannerBg bkey="dusk" groundDp={160} />
    <div style={{ position: "absolute", left: 0, right: 0, bottom: 150, display: "flex", justifyContent: "center" }}><VehicleSprite vkey="motorroller" dpPerPixel={4} /></div>
    <div class="col" style={{ position: "absolute", left: 28, right: 28, top: "calc(var(--safe-top) + 72px)", alignItems: "center", textAlign: "center" }}>
      <Label text={s.updateTag} color="var(--yellow)" />
      <div style={{ height: 10 }} />
      <FitText text={s.updateTitle} max={40} class="t-block" />
      <div style={{ height: 14 }} />
      <span class="t-body">{s.updateText}</span>
      <div style={{ height: 26 }} />
      <PxButton text={s.updateButton} style={{ width: 280 }} onClick={() => location.reload()} />
    </div>
  </div>
}
