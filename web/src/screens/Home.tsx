// Start screen: momentum hero, next milestone, garage, history (android/.../ui/Home.kt).
import { useEffect, useRef, useState } from "preact/hooks"
import { ConfirmReminder } from "./Login"
import { fmt, fmtShort, secondsSince, signed } from "../format"
import { bannerNight, defaultBanner } from "../gfx/banners"
import { vehicleSpeed } from "../gfx/vehicles"
import { type AppState, type Event, vehicleProgress } from "../model"
import { meta, undo } from "../store"
import { S } from "../strings"
import {
  Avatar, BannerBg, FitText, Label, PixelIcon, PxButton, PxCard, ScoreLabel, SegBar, Sheet, VehicleSprite,
} from "../ui/components"
import { TabScroll } from "../ui/shell"

/** A number that counts towards its target (like animateFloatAsState). */
export function useCountUp(target: number, ms = 700): number {
  const [shown, setShown] = useState(target)
  const from = useRef(target)
  useEffect(() => {
    const start = performance.now(), a = from.current
    let raf = 0
    const step = (now: number) => {
      const k = Math.min(1, (now - start) / ms)
      const v = a + (target - a) * (1 - (1 - k) ** 3)
      from.current = v
      setShown(v)
      if (k < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [target])
  return shown
}

export function HomeScreen({ st }: { st: AppState }) {
  const s = S.value
  const m = meta.value
  const [undoing, setUndoing] = useState<Event | null>(null)
  if (!m) return null
  const vs = m.vehicles
  const idx = Math.max(0, Math.min(vs.length - 1, st.vehicle.index))
  const v = vs[idx]
  const next = vs[idx + 1]
  const d = st.decay
  return <TabScroll tab="home">
    <div class="col" style={{ gap: 14 }}>
      <div class="row" style={{ padding: 4, gap: 10 }}>
        <Avatar name={st.user.display_name} url={st.user.avatar} size={36} />
        <div class="col grow">
          <span class="t-small" style={{ fontSize: 12 }}>{s.hello}</span>
          <span class="t-strong ellipsis">{st.user.display_name}</span>
        </div>
        <div class="row" style={{ borderRadius: 999, background: d.streak > 0 ? "rgba(239,125,87,0.2)" : "var(--faint)", padding: "6px 12px 6px 8px", gap: 6 }}>
          <PixelIcon name="storm" size={16} />
          <Label text={s.days(d.streak)} color={d.streak > 0 ? "var(--yellow)" : "var(--text)"} />
        </div>
      </div>

      <ConfirmReminder />
      <Hero st={st} vkey={v.key} label={s.vehicle(v.key, v.label)} category={v.category} idx={idx} count={vs.length} />

      <PxCard>
        {next ? <>
          <div class="row">
            <div class="col grow">
              <Label text={s.nextMilestone} />
              <span class="t-strong" style={{ fontSize: 20, lineHeight: "24px" }}>{s.vehicle(next.key, next.label)}</span>
              <span class="t-small">{s.toGo(fmt((st.vehicle.next_at ?? 0) - st.momentum))}</span>
            </div>
            <VehicleSprite vkey={next.key} dpPerPixel={2} animate={false} tint="white" alpha={.25} />
          </div>
          <div style={{ height: 14 }} />
          <SegBar fraction={vehicleProgress(st.vehicle, st.momentum)} segments={24} />
        </> : <>
          <Label text={s.allDone} />
          <span class="t-strong" style={{ fontSize: 20 }}>{s.allDoneSub}</span>
        </>}
      </PxCard>

      <PxCard padding={0}>
        <Label text={s.garage} style={{ padding: "18px 20px 0" }} />
        <div class="row scroll" style={{ overflowX: "auto", overflowY: "hidden", gap: 10, padding: "12px 20px 18px" }}>
          {vs.map((g, i) => {
            const locked = i > idx
            return <div key={g.key} class="col" style={{
              borderRadius: 18, background: "var(--faint)", padding: 10, alignItems: "center", flex: "none",
              boxShadow: i === idx ? "inset 0 0 0 2px var(--yellow)" : undefined,
            }}>
              <VehicleSprite vkey={g.key} dpPerPixel={2} animate={false} tint={locked ? "black" : undefined} alpha={locked ? .55 : 1} />
              <div style={{ height: 6 }} />
              <Label text={locked ? fmtShort(st.vehicle.milestones?.[i] ?? 0) : s.vehicle(g.key, g.label)}
                color={i === idx ? "var(--yellow)" : "var(--muted)"} />
            </div>
          })}
        </div>
      </PxCard>

      {st.settings.show_history && st.events.length > 0 && <PxCard>
        <Label text={s.history} style={{ paddingBottom: 8 }} />
        {st.events.slice(0, 12).map((e, i) => <div key={e.id}>
          {i > 0 && <div style={{ height: 1, background: "var(--line)" }} />}
          <HistoryEntry e={e} onUndo={() => setUndoing(e)} />
        </div>)}
      </PxCard>}
    </div>
    {undoing && <UndoSheet e={undoing} onClose={() => setUndoing(null)} />}
  </TabScroll>
}

/** One history line. Milestones show their vehicle and no points; taps can be undone. */
function HistoryEntry({ e, onUndo }: { e: Event; onUndo: () => void }) {
  const s = S.value
  const vehicle = e.kind === "milestone" ? meta.value?.vehicles.find((x) => x.key === e.label) : undefined
  const label = vehicle ? s.milestoneEvent(s.vehicle(vehicle.key, vehicle.label))
    : e.label ? e.label : e.kind === "daily" ? s.dailyDecay : e.kind === "report" ? s.reportBonus : s.resetDone
  const sec = secondsSince(e.at)
  const extra = e.id < 0 ? ` · ${s.pendingSync}` : e.auto_from ? ` · ${s.autoTapped(e.auto_from)}` : ""
  return <div class="row" style={{ padding: "8px 0", gap: 12, cursor: e.undoable ? "pointer" : undefined }} onClick={e.undoable ? onUndo : undefined}>
    {vehicle ? <div class="center" style={{ width: 32 }}><VehicleSprite vkey={vehicle.key} dpPerPixel={.5} animate={false} /></div>
      : e.icon ? <PixelIcon name={e.icon} size={24} /> : <div style={{ width: 24, height: 24, borderRadius: 6, background: "var(--faint)" }} />}
    <div class="col grow">
      <span class="t-body ellipsis" style={{ fontWeight: 500, color: vehicle ? "var(--yellow)" : undefined }}>{label}</span>
      <span class="t-small ellipsis">{(sec != null ? s.timeAgo(sec) : "") + extra}</span>
    </div>
    {e.kind !== "milestone" && <span class="t-block" style={{ fontSize: 14, fontWeight: 400, color: e.delta >= 0 ? "var(--lime)" : "var(--orange)" }}>
      {signed(e.delta, fmt)}
    </span>}
  </div>
}

function UndoSheet({ e, onClose }: { e: Event; onClose: () => void }) {
  const s = S.value
  const sec = secondsSince(e.at)
  return <Sheet onClose={onClose} label={s.undoTitle}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.undoTitle}</div>
    <div class="row" style={{ gap: 12 }}>
      {e.icon && <PixelIcon name={e.icon} size={32} />}
      <div class="col grow">
        <span class="t-strong">{e.label}</span>
        <span class="t-small">{sec != null ? s.timeAgo(sec) : ""}</span>
      </div>
      <span class="t-block" style={{ fontSize: 18, color: e.delta >= 0 ? "var(--lime)" : "var(--orange)" }}>{signed(e.delta, fmt)}</span>
    </div>
    <div class="row" style={{ gap: 10 }}>
      <PxButton text={s.cancel} kind="ghost" onClick={onClose} class="grow" />
      <PxButton text={s.undo} kind="danger" onClick={() => { void undo(e); onClose() }} class="grow" />
    </div>
  </Sheet>
}

function Hero({ st, vkey, label, category, idx, count }: { st: AppState; vkey: string; label: string; category: string; idx: number; count: number }) {
  const s = S.value
  const land = category !== "air" && category !== "space"
  const shown = useCountUp(st.momentum)
  const d = st.decay
  const competing = st.settings.daily_limit !== false
  const bkey = st.user.banner ?? defaultBanner(category)
  return <div class={competing ? "banner-card glow" : "banner-card"} style={{ position: "relative", height: 440, borderRadius: "var(--card-radius)", overflow: "hidden", flex: "none" }}>
    <BannerBg bkey={bkey} groundDp={land ? 120 : 0} speed={vehicleSpeed(vkey)} />
    <div class="col" style={{ position: "relative", padding: "22px 22px 0" }}>
      <ScoreLabel competing={competing} />
      <div style={{ height: 4 }} />
      {/* sized for the final value so the count-up doesn't jitter */}
      <FitText text={fmt(shown)} fitFor={fmt(st.momentum).padStart(4, "0")} max={76} class="t-block" />
      <div style={{ height: 8 }} />
      <div class="row" style={{ gap: 8 }}>
        <PixelIcon name="star" size={16} />
        <span class="t-body" style={{ fontWeight: 500 }}>{label}</span>
        <span class="t-body" style={{ color: "var(--muted)" }}>· {s.stage(idx + 1, count)}</span>
      </div>
    </div>
    <div style={{ position: "absolute", left: 0, right: 0, bottom: land ? 112 : 150, display: "flex", justifyContent: "center" }}>
      <VehicleSprite vkey={vkey} dpPerPixel={4} night={bannerNight(bkey)} />
    </div>
    <div class="row" style={{
      position: "absolute", left: 0, right: 0, bottom: 0, padding: `${land ? 0 : 40}px 22px 18px`,
      background: land ? undefined : "linear-gradient(180deg, transparent, rgba(0,0,0,0.35))", alignItems: "flex-start",
    }}>
      {([[s.today, signed(d.today_gain, fmt)], [s.goalToday, fmt(d.threshold)], [s.tonight, "−" + fmt(d.tonight)]] as const).map(([k, value]) =>
        <div key={k} class="col grow"><Label text={k} /><span class="t-block" style={{ fontSize: 17 }}>{value}</span></div>)}
    </div>
  </div>
}
