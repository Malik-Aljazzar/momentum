// Accelerators + decelerators in one tab (android/.../ui/Rules.kt): split banner, rule cards,
// long press selects (bulk actions), one selected = sort it with the arrows.
import { useEffect, useRef, useState } from "preact/hooks"
import { fmt, fmtVal } from "../format"
import { type AppState, isOwn, type Rule } from "../model"
import { nav } from "../nav"
import { bulk, moveRule, pop, showToast, trigger } from "../store"
import { S } from "../strings"
import {
  FitText, Label, PixelArrowButton, SlidingText, PixelCheckbox, PixelDivider, PixelIcon, PixelInfoIcon, PxButton, PxCard, SceneBg, SegBar, Sheet, usePress,
} from "../ui/components"
import { DecayEditor, RuleEditor } from "./Editors"
import { GroupPicker, RuleDetailSheet, SelectionAction, SelectionBar, SharePicker } from "./shared"
import { DecayDetailSheet, type DecayRef } from "./Decays"
import { useCountUp } from "./Home"

type Editing = { rule: Rule | null } | "decay" | null

export function RulesScreen({ st }: { st: AppState }) {
  const s = S.value
  const [kind, setKind] = useState<"acc" | "dec">("acc")
  const acc = kind === "acc"
  const [editing, setEditing] = useState<Editing>(null)
  const [detailOf, setDetailOf] = useState<Rule | null>(null)
  const [decayDetail, setDecayDetail] = useState<DecayRef | null>(null)     // the decay I follow
  const [limitInfo, setLimitInfo] = useState(false)
  // long press: selection (then delete / unsubscribe / share / copy to a group / visibility)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [sharing, setSharing] = useState<Record<string, unknown>[] | null>(null)
  const [copying, setCopying] = useState(false)
  const lists = { acc: useRef<HTMLDivElement>(null), dec: useRef<HTMLDivElement>(null) }
  useEffect(() => setSelected(new Set()), [kind])
  const selecting = selected.size > 0
  const [barH, setBarH] = useState(0)          // the selection bar's height: the list makes room for it
  const rules = st.rules.filter((r) => r.kind === kind)

  const toggle = (r: Rule) => {
    if (r.id <= 0) return
    const n = new Set(selected)
    if (n.has(r.id)) n.delete(r.id); else n.add(r.id)
    setSelected(n)
  }
  const openDetail = (r: Rule) => {
    if (r.origin === "challenge" && r.challenge_id != null) nav.push({ r: "challenge", id: r.challenge_id })
    else setDetailOf(r)
  }

  const sel = st.rules.filter((r) => selected.has(r.id))
  const own = sel.filter(isOwn)
  const subs = sel.filter((r) => !isOwn(r) && r.origin !== "challenge" && r.locked_by == null)
  const copyable = sel.filter((r) => r.origin !== "challenge")
  const visible = [...own, ...subs]                  // subscribed: shown in my profile or not
  const done = (r: { done: number; skipped: unknown[] } | null) => { if (r) { showToast(s.bulkResult(r.done, r.skipped.length)); setSelected(new Set()) } }
  const visibility = async (rs: Rule[], pub: boolean) => done(await bulk("/rules/visibility-many", { public: pub, ids: rs.map((r) => r.id) }))

  return <div class="fill col">
    {/* banner stays fixed; only the list below scrolls */}
    <div class="row" style={{ margin: "calc(var(--safe-top) + 12px) 16px 4px", height: 192, gap: 8, flex: "none" }}>
      {(["acc", "dec"] as const).map((k) => {
        const active = k === kind
        return <div key={k} class="banner-card" onClick={active ? undefined : () => setKind(k)} style={{
          position: "relative", height: "100%", borderRadius: "var(--card-radius)", overflow: "hidden", cursor: active ? undefined : "pointer",
          flex: active ? ".76 1 0" : ".24 1 0", transition: "flex .35s, filter .35s", filter: active ? "none" : "saturate(0)", minWidth: 0,
        }}>
          <SceneBg scene={k} />
          {active ? <RulesBanner st={st} acc={k === "acc"} onLimitInfo={() => setLimitInfo(true)} /> : <>
            {/* the other half: greyed out, a tap switches over */}
            <div class="fill" style={{ background: "rgba(0,0,0,0.4)" }} />
            <div class="fill col center" style={{ flexDirection: "column" }}>
              <PixelIcon name={k === "acc" ? "ui_up" : "ui_down"} size={28} alpha={.9} />
              <div style={{ height: 8 }} />
              <span class="t-block" style={{ fontSize: 10, whiteSpace: "nowrap" }}>{k === "acc" ? s.accShort : s.decShort}</span>
              <span class="t-block" style={{ fontSize: 18 }}>{st.rules.filter((r) => r.kind === k).length}</span>
            </div>
          </>}
        </div>
      })}
    </div>
    <div style={{ position: "relative", flex: 1, minHeight: 0 }}>
      {/* while selecting, the bar covers the bottom: the last cards (and their arrows) can scroll above it */}
      <div ref={lists[kind]} key={kind} class="fill scroll" style={{ padding: `10px 16px calc(var(--safe-bottom) + var(--tabbar) + ${selecting ? Math.max(40, barH + 28) : 40}px)` }}>
        <div class="col" style={{ gap: 10 }}>
          {/* my own decay: edit it; a followed one: its detail (stop following there) */}
          {!acc && <DecayCard st={st} onClick={() => {
            const f = st.decay.follow
            if (!f) setEditing("decay")
            else setDecayDetail(f.kind === "group" && f.decay_id != null ? { id: f.decay_id } : { user: f.username ?? f.label })
          }} />}
          {rules.map((r, idx) => {
            // exactly one selected: arrows in that card move it up / down step by step
            const sorting = selected.size === 1 && selected.has(r.id)
            return <RuleCard key={r.id} r={r} selected={selecting ? selected.has(r.id) : null}
              onMove={sorting ? (step) => { navigator.vibrate?.(5); moveRule(r.id, step) } : undefined}
              canUp={idx > 0} canDown={idx < rules.length - 1}
              onLongPress={() => { if (r.id > 0) setSelected(new Set([...selected, r.id])) }}
              onTap={() => (selecting ? toggle(r) : trigger(r))}
              onEdit={() => (selecting ? toggle(r) : isOwn(r) ? setEditing({ rule: r }) : openDetail(r))} />
          })}
          <button class="row center" onClick={() => setEditing({ rule: null })} style={{
            borderRadius: 22, padding: 18, gap: 10, border: "2px dashed rgba(255,255,255,0.15)",
          }}>
            <PixelIcon name="ui_plus" size={16} alpha={.55} />
            <Label text={acc ? s.newAcc : s.newDec} />
          </button>
          {rules.length > 1 && <div class="t-small" style={{ fontSize: 12, textAlign: "center", paddingTop: 2 }}>{s.dragToSort}</div>}
        </div>
      </div>
      {selecting && <SelectionBar title={s.selectedCount(sel.length)} onClose={() => setSelected(new Set())} onHeight={setBarH}
        onAll={() => setSelected(new Set(rules.map((r) => r.id).filter((id) => id > 0)))}>
        {(own.length > 0 || subs.length > 0) && <SelectionAction danger={own.length > 0} onClick={() => setConfirmRemove(true)}
          label={own.length && subs.length ? s.removeN(own.length + subs.length) : own.length ? s.deleteN(own.length) : s.unsubscribeN(subs.length)} />}
        <SelectionAction label={s.share} onClick={() => setSharing(sel.map((r) => ({ kind: "rule", id: r.root_id ?? r.id })))} />
        {copyable.length > 0 && <SelectionAction label={s.copyToGroup} onClick={() => setCopying(true)} />}
        {visible.some((r) => !r.public) && <SelectionAction label={s.makePublicN(visible.filter((r) => !r.public).length)} onClick={() => visibility(visible.filter((r) => !r.public), true)} />}
        {visible.some((r) => r.public) && <SelectionAction label={s.makePrivateN(visible.filter((r) => r.public).length)} onClick={() => visibility(visible.filter((r) => r.public), false)} />}
      </SelectionBar>}
    </div>

    {confirmRemove && <Sheet onClose={() => setConfirmRemove(false)}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.confirmBulkRemove(own.length, subs.length)}</div>
      {sel.length - own.length - subs.length > 0 && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.bulkLockedHint(sel.length - own.length - subs.length)}</span>}
      <div class="row" style={{ gap: 10 }}>
        <PxButton text={s.cancel} kind="ghost" class="grow" onClick={() => setConfirmRemove(false)} />
        <PxButton text={s.removeN(own.length + subs.length)} kind="danger" class="grow" onClick={async () => {
          setConfirmRemove(false)
          done(await bulk("/rules/remove-many", { ids: [...own, ...subs].map((r) => r.id) }))
        }} />
      </div>
    </Sheet>}
    {copying && <GroupPicker title={s.copyToGroup} onClose={() => setCopying(false)} onPick={async (c) => {
      setCopying(false)
      done(await bulk(`/conversations/${c.id}/rules/bulk`, { action: "copy", ids: copyable.map((r) => r.id) }))
    }} />}
    {sharing && <SharePicker shares={sharing} onClose={() => { setSharing(null); setSelected(new Set()) }} />}

    {limitInfo && <Sheet onClose={() => setLimitInfo(false)}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.limitInfoTitle}</div>
      <SegBar fraction={st.limit.cap > 0 ? st.limit.gained / st.limit.cap : 1} color={st.limit.remaining < 1 ? "var(--orange)" : "var(--lime)"} />
      {s.limitInfo(fmt(st.limit.cap), fmt(st.limit.average), fmt(st.limit.bias), fmt(st.limit.gained), fmt(Math.floor(st.limit.remaining)))
        .map((t, i) => <span key={i} class="t-body">{t}</span>)}
      {st.limit.next_cap != null && <span class="t-body limit-forecast" style={{ color: "var(--cyan)" }}>
        {s.limitForecast(fmt(st.limit.today_average ?? 0), st.limit.today_players ?? 0, fmt(st.limit.next_cap))}</span>}
      {st.limit.provisional && <span class="t-body" style={{ color: "var(--yellow)" }}>{s.limitProvisional}</span>}
      {(st.challenge_credit ?? 0) >= 1 && <>
        <span class="t-strong" style={{ color: "var(--yellow)" }}>{s.challengeCreditLine(fmt(st.challenge_credit ?? 0))}</span>
        <span class="t-small">{s.challengeCreditHint}</span>
      </>}
    </Sheet>}

    {decayDetail && <DecayDetailSheet dref={decayDetail} onClose={() => setDecayDetail(null)} />}
    {detailOf && <RuleDetailSheet ruleId={detailOf.root_id ?? detailOf.id} onClose={() => setDetailOf(null)} />}
    {editing && <Sheet onClose={() => setEditing(null)}>
      {editing === "decay" ? <DecayEditor decay={st.decay} onClose={() => setEditing(null)} />
        : <RuleEditor rule={editing.rule} kind={kind} onClose={() => setEditing(null)} />}
    </Sheet>}
  </div>
}

/** The active half of the split banner: title, current momentum and the daily limit. */
function RulesBanner({ st, acc, onLimitInfo }: { st: AppState; acc: boolean; onLimitInfo: () => void }) {
  const s = S.value
  const shown = useCountUp(st.momentum, 600)
  const ranked = st.settings.daily_limit !== false
  return <div class="col" style={{ position: "absolute", left: 0, right: 0, bottom: 0, padding: 18 }}>
    <div class="row">
      <Label text={acc ? s.accTag : s.decTag} class="grow" />
      {/* current momentum, counting along with every tap */}
      <div class="col" style={{ alignItems: "flex-end" }}>
        <Label text={ranked ? "MOMENTUM" : s.unranked} color={ranked ? "var(--yellow)" : "var(--muted)"} />
        <span class="t-block" style={{ fontSize: 20, lineHeight: "24px" }}>{fmt(shown)}</span>
      </div>
    </div>
    <FitText text={acc ? s.accTitle : s.decTitle} max={28} />
    <div style={{ height: 6 }} />
    {acc && !ranked ? <span class="t-body" style={{ color: "var(--muted)" }}>{s.noLimitBanner}</span>
      : acc && st.limit.cap < 1e8 ? <div class="col" style={{ cursor: "pointer" }} onClick={onLimitInfo}>
        <div class="row" style={{ gap: 8 }}>
          <span class="t-body ellipsis" style={{ color: "var(--muted)" }}>
            {s.limitLeft(fmt(Math.floor(st.limit.remaining)), fmt(st.limit.cap)) + (st.limit.provisional ? " · " + s.limitProvisionalShort : "")}
          </span>
          <span class="center t-block" style={{ width: 20, height: 20, borderRadius: 10, background: "rgba(255,255,255,0.15)", fontSize: 11, flex: "none" }}>?</span>
        </div>
        <div style={{ height: 8 }} />
        <SegBar fraction={st.limit.cap > 0 ? st.limit.gained / st.limit.cap : 1} segments={16} color={st.limit.remaining < 1 ? "var(--orange)" : "var(--lime)"} />
      </div> : <span class="t-body" style={{ color: "var(--muted)" }}>{acc ? s.accSub : s.decSub}</span>}
  </div>
}

function DecayCard({ st, onClick }: { st: AppState; onClick: () => void }) {
  const s = S.value
  const d = st.decay
  const f = d.follow
  const color = d.locked_by ? "var(--yellow)" : "var(--cyan)"
  const frac = d.threshold > 0 ? d.today_gain / d.threshold : 1
  return <PxCard onClick={onClick} bg="linear-gradient(135deg, #2b1830, #191220)">
    <div class="row" style={{ gap: 14 }}>
      <PixelIcon name="calendar" size={48} />
      <div class="col grow">
        <Label text={s.dailyDecay} />
        {f && <span class="t-small row decay-origin" style={{ fontSize: 12, color, gap: 0, whiteSpace: "nowrap", overflow: "hidden" }}>
          {/* "subscribed · [group icon] group · decay", like group rules */}
          {s.subscribedLabel + " · "}
          {f.kind === "group" && <><PixelIcon name="ui_group" size={12} color={color} /><span style={{ width: 4 }} /></>}
          <span class="ellipsis">{f.label + (f.kind === "group" && f.name && f.name !== f.label ? ` · ${f.name}` : "")}</span>
        </span>}
        <span class="t-block" style={{ fontSize: 28, lineHeight: "32px", color: "var(--orange)" }}>−{fmt(d.tonight)}</span>
        <span class="t-small">{s.decayInfo(fmt(d.base), d.streak)}</span>
      </div>
      {!f && <PixelIcon name="ui_edit" size={16} alpha={.45} style={{ alignSelf: "flex-start" }} />}
    </div>
    {d.catalyst_mode !== "none" && <>
      <div style={{ height: 14 }} />
      <span class="t-small">{s.collectedToday(fmt(d.today_gain), fmt(d.threshold))}</span>
      <div style={{ height: 6 }} />
      <SegBar fraction={frac} color={frac >= 1 ? "var(--lime)" : "var(--yellow)"} />
    </>}
  </PxCard>
}

function RuleCard({ r, selected, onLongPress, onMove, canUp, canDown, onTap, onEdit }: {
  r: Rule; selected: boolean | null; onLongPress: () => void; onMove?: (step: number) => void; canUp: boolean; canDown: boolean
  onTap: () => void; onEdit: () => void
}) {
  const s = S.value
  const acc = r.kind === "acc"
  const press = usePress(() => {
    if (selected == null && r.tappable === false) { setBlocked((n) => n + 1); navigator.vibrate?.([20, 40, 20]) }
    else onTap()
  }, onLongPress)
  const [down, setDown] = useState(false)
  // can't be tapped: a try greys the card out and shows the "no tap" finger for a moment
  const [blocked, setBlocked] = useState(0)
  const p = pop.value
  // two parts: the big tap area (counts the points) and, behind a pixel line, the info button
  return <div style={{ position: "relative" }} data-rule={r.name}>
    <div class="row" key={blocked} style={{
      borderRadius: 22, alignItems: "stretch", transform: down ? "scale(.97)" : undefined, transition: "transform .1s",
      animation: blocked ? "blocked 1.1s ease-out" : undefined,
      background: acc ? "linear-gradient(135deg, #1b3532, #131e22)" : "linear-gradient(135deg, #3a1a2a, #1e1219)",
      boxShadow: selected ? "inset 0 0 0 2px var(--yellow)" : undefined,
    }}>
    <div class="row grow" {...press}
      onPointerDown={(e) => { setDown(true); press.onPointerDown(e) }}
      onPointerUp={() => { setDown(false); press.onPointerUp() }}
      onPointerLeave={() => setDown(false)}
      onPointerCancel={() => { setDown(false); press.onPointerCancel() }}
      style={{ padding: "14px 10px 14px 14px", cursor: "pointer" }}>
      <div class="center" style={{ width: 52, height: 52, borderRadius: 16, background: "rgba(255,255,255,0.07)", flex: "none" }}>
        <PixelIcon name={r.icon} size={32} />
      </div>
      <div class="col grow" style={{ marginLeft: 14 }}>
        {/* one line: a long name slides to its end and back instead of wrapping */}
        <SlidingText text={r.name} class="t-strong" style={{ fontSize: 17, lineHeight: "20px" }} />
        {r.origin === "group"
          // "subscribed · [group icon] name", like "subscribed · name" for people
          ? <span class="row t-small" style={{ fontSize: 12, color: "var(--cyan)", gap: 4, minWidth: 0 }}>
            <span style={{ flex: "none" }}>{s.subscribedLabel} ·</span>
            <PixelIcon name="ui_group" size={12} color="var(--cyan)" />
            <span class="ellipsis">{r.origin_label}</span>
          </span>
          : !isOwn(r) && <span class="t-small ellipsis" style={{ fontSize: 12, color: r.origin === "challenge" ? "var(--yellow)" : "var(--cyan)" }}>
            {s.origin(r.origin ?? "", r.origin_label ?? "")}
          </span>}
        {(r.idle_days ?? 0) > 0 && r.idle_paused && <span class="t-small ellipsis" style={{ fontSize: 12 }}>{s.idlePausedShort}</span>}
      </div>
      <span class="t-block" style={{ fontSize: 20, color: acc ? "var(--lime)" : "var(--orange)" }}>{(acc ? "+" : "−") + fmtVal(r.value)}</span>
      {onMove && <div class="col" style={{ gap: 6, marginLeft: 8 }} onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}>
        <PixelArrowButton up enabled={canUp} onClick={() => onMove(-1)} />
        <PixelArrowButton up={false} enabled={canDown} onClick={() => onMove(1)} />
      </div>}
    </div>
    <PixelDivider />
    {/* info: own rules open the editor, others their details (and the checkbox while selecting) */}
    <button aria-label="edit" class="center" style={{ width: 58, flex: "none" }} onClick={(e) => { e.stopPropagation(); onEdit() }}>
      {selected != null ? <PixelCheckbox checked={selected} /> : <PixelInfoIcon />}
    </button>
    </div>
    {blocked > 0 && <div key={blocked} class="fill center" style={{ borderRadius: 22, pointerEvents: "none", animation: "blocked-sign 1.1s ease-out forwards" }}>
      <PixelIcon name="ui_notap" size={48} />
    </div>}
    {p && p.ruleId === r.id && <span key={p.nonce} class="pop" style={{ right: 48, top: "30%", color: acc ? "var(--lime)" : "var(--orange)" }}
      onAnimationEnd={() => { if (pop.value === p) pop.value = null }}>{p.text}</span>}
  </div>
}
