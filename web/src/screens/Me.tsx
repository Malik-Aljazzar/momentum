// What only my own profile shows (it's the main page; android/.../ui/Me.kt): the notes that want my attention, the
// whole history (every tap, undoable) and today's numbers. Before 2026-11-18 these lived on a home tab.
import { useEffect, useRef, useState } from "preact/hooks"
import { ConfirmReminder } from "./Login"
import { ElectionCard } from "./Nine"
import { fmt, secondsSince, signed } from "../format"
import type { AppState, Event } from "../model"
import { installed, isIos, notifyHint, notifyHintUntil, notifyPermission, postponeNotifyHint, requestNotifications } from "../push"
import { meta, notifications, req, undo } from "../store"
import { lang, S } from "../strings"
import { EmptyHint, LoadMore, PixelIcon, PxButton, Sheet, VehicleSprite } from "../ui/components"

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

/** The cards above my profile: confirm the address, notifications off, the election. */
export function MyNotes() {
  return <div class="col my-notes" style={{ gap: 12, padding: "0 16px" }}>
    <ConfirmReminder />
    <NotifyReminder />
    <ElectionCard />
  </div>
}

/** Notifications off: calls ring only while the app is open, messages come only when you look in - a card that
 *  says so and what helps (push.ts notifyHint = Android NotifyReminder in ui/Me.kt). "Not now": a week's peace. */
function NotifyReminder() {
  const s = S.value
  const [permission, setPermission] = useState(notifyPermission())
  // back from the phone's settings: maybe allowed now
  useEffect(() => {
    const again = () => setPermission(notifyPermission())
    document.addEventListener("visibilitychange", again)
    return () => document.removeEventListener("visibilitychange", again)
  }, [])
  const hint = notifyHint({
    wanted: notifications.value, permission, ios: isIos(), installed: installed(), hiddenUntil: notifyHintUntil.value, now: Date.now(),
  })
  if (!hint) return null
  return <div class="col notify-reminder" role="note" style={{
    gap: 10, padding: 14, borderRadius: 16, background: "var(--card)", border: "1px solid var(--orange)",
  }}>
    <div class="row" style={{ gap: 10 }}>
      <PixelIcon name="ui_bell" size={24} />
      <span class="t-strong">{s.notifyOffTitle}</span>
    </div>
    <span class="t-small" style={{ color: "var(--text)" }}>{s.notifyOffText}</span>
    {hint !== "ask" && <span class="t-small">{hint === "install" ? s.notificationsInstallHint : s.notifyOffDenied}</span>}
    <div class="row" style={{ gap: 8 }}>
      {hint === "ask" && <PxButton text={s.allowNotifications} class="grow"
        onClick={async () => setPermission(await requestNotifications(lang.value))} />}
      <PxButton text={s.notNow} kind="ghost" class="grow" onClick={() => postponeNotifyHint()} />
    </div>
  </div>
}

/** Today's numbers under my competition card: gained, the goal, what the night takes. */
export function todayStats(st: AppState): [string, string][] {
  const s = S.value
  const d = st.decay
  return [[s.today, signed(d.today_gain, fmt)], [s.goalToday, fmt(d.threshold)], [s.tonight, "−" + fmt(d.tonight)]]
}

/** My whole history, newest first: the state's latest entries (live, also offline), older ones page by page
 *  (GET /api/events?before=). Taps can be undone. */
export function MyHistory({ st }: { st: AppState }) {
  const s = S.value
  const [older, setOlder] = useState<Event[]>([])
  const [more, setMore] = useState(st.events.length >= 30)
  const [undoing, setUndoing] = useState<Event | null>(null)
  const head = st.events
  const seen = new Set(head.map((e) => e.id))
  const all = [...head, ...older.filter((e) => !seen.has(e.id))]
  const loadMore = async () => {
    const last = [...all].reverse().find((e) => e.id > 0)
    if (!last) { setMore(false); return }
    const r = await req<{ events: Event[] }>("GET", `/events?before=${last.id}&limit=30`)
    if (!r) return
    setOlder((o) => [...o, ...r.events.filter((e) => !o.some((x) => x.id === e.id))])
    setMore(r.events.length >= 30)
  }
  return <>
    {all.length === 0 ? <EmptyHint title={s.noHistory} icon="ui_clock" />
      : <div class="col my-history">{all.map((e, i) => <div key={e.id}>
        {i > 0 && <div style={{ height: 1, background: "var(--line)", margin: "0 16px" }} />}
        <HistoryEntry e={e} onUndo={() => setUndoing(e)} />
      </div>)}</div>}
    <LoadMore hasMore={more && all.length > 0} load={loadMore} />
    {undoing && <UndoSheet e={undoing} onClose={() => setUndoing(null)} />}
  </>
}

/** One history line. Milestones show their vehicle and no points; taps can be undone. */
function HistoryEntry({ e, onUndo }: { e: Event; onUndo: () => void }) {
  const s = S.value
  const vehicle = e.kind === "milestone" ? meta.value?.vehicles.find((x) => x.key === e.label) : undefined
  const label = vehicle ? s.milestoneEvent(s.vehicle(vehicle.key, vehicle.label))
    : e.label ? e.label : e.kind === "daily" ? s.dailyDecay : e.kind === "report" ? s.reportBonus : s.resetDone
  const sec = secondsSince(e.at)
  const extra = e.id < 0 ? ` · ${s.pendingSync}` : e.auto_from ? ` · ${s.autoTapped(e.auto_from)}` : ""
  return <div class="row list-item history-entry" style={{ padding: "10px 16px", gap: 12, cursor: e.undoable ? "pointer" : undefined }}
    onClick={e.undoable ? onUndo : undefined}>
    {vehicle ? <div class="center" style={{ width: 32 }}><VehicleSprite vkey={vehicle.key} dpPerPixel={.5} animate={false} /></div>
      : e.icon ? <PixelIcon name={e.icon} size={24} /> : <div style={{ width: 24, height: 24, borderRadius: 6, background: "var(--faint)" }} />}
    <div class="col grow" style={{ minWidth: 0 }}>
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
