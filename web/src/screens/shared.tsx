// Pieces several screens share (android/.../ui/Community.kt top part, Chat.kt avatars).
import type { ComponentChildren } from "preact"
import { useEffect, useRef, useState } from "preact/hooks"
import { applied, fmtVal, nextValue } from "../format"
import type { Conversation, RuleDetail } from "../model"
import { nav } from "../nav"
import { bulk, call, meta, online, refresh, req, save, showToast, state } from "../store"
import { S } from "../strings"
import {
  Avatar, BannerBg, EmptyHint, IconBtn, Label, OfflineState, PixelCheckbox, PixelIcon, PxButton, RemoteImage, SceneBg, Sheet, Switch,
  usePress, VehicleSprite,
} from "../ui/components"
import { sceneFor } from "../gfx/scenes"
import { RuleSentence } from "./Editors"

export function RuleIconBox({ icon, size = 44 }: { icon: string; size?: number }) {
  return <div class="center" style={{ width: size, height: size, borderRadius: size / 3, background: "var(--faint)", flex: "none" }}>
    <PixelIcon name={icon} size={Math.round(size * .55)} />
  </div>
}

export const signedValue = (kind: string, v: number) => (kind === "acc" ? "+" : "−") + fmtVal(applied(kind, v))
export const kindColor = (kind: string) => (kind === "acc" ? "var(--lime)" : "var(--orange)")

export function SectionTitle({ text, action }: { text: string; action?: ComponentChildren }) {
  return <div class="row" style={{ padding: "22px 8px 6px 16px" }}>
    <Label text={text} class="grow" />
    {action}
  </div>
}

/** A row for a rule seen from outside (profile, group, challenge). */
export function RuleDetailRow({ r, trailing, selected, onLongClick, onClick }: {
  r: RuleDetail; trailing?: string; selected?: boolean | null; onLongClick?: () => void; onClick: () => void
}) {
  const press = usePress(onClick, onLongClick)
  return <div class="row list-item" {...press} style={{
    padding: "10px 16px", gap: 12, cursor: "pointer", background: selected ? "rgba(255,205,117,0.14)" : undefined,
  }}>
    {selected != null && <PixelCheckbox checked={selected} />}
    <RuleIconBox icon={r.icon} />
    <div class="col grow">
      <span class="t-strong ellipsis">{r.name}</span>
      <span class="t-small ellipsis">{trailing ?? S.value.modeHint({ ...r, value: r.base })}</span>
    </div>
    <span class="t-block" style={{ fontSize: 16, color: kindColor(r.kind) }}>{signedValue(r.kind, r.base)}</span>
  </div>
}

// ---------------------------------------------------------------- rule detail + subscribe

/** Settings of a rule someone else made (or a group/challenge rule): subscribe, unsubscribe, share. */
export function RuleDetailSheet({ ruleId, onClose, onEdit, inGroup = false }: {
  ruleId: number; onClose: () => void; onEdit?: (d: RuleDetail) => void
  /** opened from the group overview: only there a rule can be taken out of the group */
  inGroup?: boolean
}) {
  const s = S.value
  const [detail, setDetail] = useState<RuleDetail | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [reload, setReload] = useState(0)
  const [sharing, setSharing] = useState(false)
  const [adding, setAdding] = useState(false)
  const [copying, setCopying] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)
  useEffect(() => {
    void req<{ rule: RuleDetail }>("GET", `/rules/${ruleId}/detail`).then((r) => { setDetail(r?.rule ?? null); setLoaded(true) })
  }, [ruleId, reload])

  const d = detail
  const body = () => {
    if (!d) return loaded ? (online.value ? <EmptyHint title={s.notAvailable} icon="ui_rules" /> : <OfflineState />) : <div style={{ height: 160 }} />
    const acc = d.kind === "acc"
    const group = d.origin === "group"
    // a group rule belongs to the group: its name and picture, not who made it
    const origin = group ? (d.group_name ? s.groupRuleOf(d.group_name) : s.groupRule) + (d.linked_from ? ` · ${s.followsRuleOf(d.linked_from)}` : "")
      : d.is_mine ? s.yourRule : d.origin === "challenge" ? s.challengeRule : s.ruleBy(d.owner?.username ?? "?")
    const openable = (d.owner && d.origin === "profile") || (group && d.group_name && d.group_id != null)
    const steps: number[] = []
    for (let v = d.base, i = 0; i < 6; i++, v = nextValue(d.mode, v, d.param, d.min_value)) steps.push(v)
    const copy = state.value?.rules.find((r) => r.id === d.my_copy_id)
    return <>
      <div class="row" style={{ gap: 14 }}>
        <RuleIconBox icon={d.icon} size={56} />
        <div class="col grow">
          <Label text={acc ? s.accTag : s.decTag} />
          <span class="t-title" style={{ fontSize: 22, lineHeight: "26px", overflowWrap: "anywhere" }}>{d.name}</span>
        </div>
        <span class="t-block" style={{ fontSize: 24, color: kindColor(d.kind) }}>{signedValue(d.kind, d.base)}</span>
      </div>
      <div class="row" style={{ borderRadius: 14, background: "var(--faint)", padding: 12, gap: 10, cursor: openable ? "pointer" : undefined }}
        onClick={() => {
          if (!openable) return
          onClose()
          if (group) nav.push({ r: "group", id: d.group_id! }); else nav.push({ r: "user", username: d.owner!.username })
        }}>
        {group ? (d.group_image ? <RemoteImage url={d.group_image} style={{ width: 32, height: 32, borderRadius: 9, flex: "none" }} />
          : d.group_banner ? <div style={{ position: "relative", width: 32, height: 32, borderRadius: 9, overflow: "hidden", flex: "none" }}><BannerBg bkey={d.group_banner} still /></div>
            : <div class="center" style={{ width: 32, height: 32, borderRadius: 9, background: "var(--faint)", flex: "none" }}><PixelIcon name="ui_group" size={18} /></div>)
          : d.owner && <Avatar name={d.owner.display_name} url={d.owner.avatar} size={32} />}
        <div class="col grow">
          <span class="t-strong">{origin}</span>
          {d.origin !== "challenge" && <span class="t-small">{s.subscribers(d.subscribers ?? 0)}</span>}
        </div>
      </div>
      {/* same explanation as in the editor, only the values can't be changed */}
      <RuleSentence rule={d} />
      <span class="t-small">{s.previewRule}</span>
      <span class="t-block" style={{ fontSize: 14 }}>{steps.map((v) => signedValue(d.kind, v)).join("  ›  ")}</span>
      {d.tappable === false && <div class="row" style={{ gap: 8 }}><PixelIcon name="ui_notap" size={16} /><span class="t-small">{s.notTappable}</span></div>}
      {d.subscribed && <span class="t-small" style={{ color: "var(--cyan)" }}>{group ? s.subscribedHintGroup : s.subscribedHint(d.owner?.username ?? "?")}</span>}
      {d.members_only && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.onlyMembersCanSubscribe(d.group_name ?? null)}</span>}
      {d.have_already && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.haveRuleAlready}</span>}
      {d.locked_by && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.lockedByChallenge(d.locked_by)}</span>}
      {d.idle_rule && <>
        {/* linked: its automatic tap goes to another rule, which is subscribed along */}
        <span class="t-small" style={{ color: "var(--cyan)" }}>{s.idleLinked(s.idleSpan(d.idle_days ?? 0, d.idle_unit ?? "d"), d.idle_rule.name)}</span>
        {copy && <SwitchRow title={s.idlePause} hint={s.idlePauseHint} on={!!copy.idle_paused}
          onChange={(on) => save("PUT", `/rules/${copy.id}/idle-pause`, { paused: on }, true)} />}
      </>}
      {/* my subscription: shown in my profile (the rule and my taps) or not */}
      {copy && copy.origin !== "challenge" && <SwitchRow title={s.inProfile} hint={s.inProfileHint} on={!!copy.public}
        onChange={(on) => bulk("/rules/visibility-many", { public: on, ids: [copy.id] })} />}
      <div class="row" style={{ gap: 10 }}>
        {d.origin !== "challenge" && <PxButton text={s.share} kind="ghost" class="grow" onClick={() => setSharing(true)} />}
        {d.can_subscribe ? <PxButton text={s.subscribe} class="grow" onClick={async () => {
          if (await save("POST", `/rules/${d.id}/subscribe`, undefined, true)) { showToast(s.subscribed); setReload((n) => n + 1) }
        }} /> : d.subscribed && d.my_copy_id != null && !d.locked_by
          ? <PxButton text={s.unsubscribe} kind="danger" class="grow" onClick={async () => { if (await save("DELETE", `/rules/${d.my_copy_id}`)) setReload((n) => n + 1) }} />
          : null}
      </div>
      {/* own rules, or public rules of public accounts, can go into groups (they stay linked) */}
      {d.origin === "profile" && d.public && (d.is_mine || d.owner?.private === false) &&
        <PxButton text={s.subscribeForGroup} kind="ghost" onClick={() => setAdding(true)} />}
      {/* my rules and the ones I subscribed to can be copied into a group (a real copy, no link) */}
      {d.origin !== "challenge" && (d.is_mine || d.my_copy_id != null) && <PxButton text={s.copyToGroup} kind="ghost" onClick={() => setCopying(true)} />}
      {onEdit && d.editable && <PxButton text={s.edit} kind="ghost" onClick={() => onEdit(d)} />}
      {/* a rule the group follows (or one of its own): take it out of the group */}
      {inGroup && d.origin === "group" && d.removable && d.group_id != null && <PxButton text={confirmRemove ? s.reallyRemoveFromGroup : s.removeFromGroup} kind="danger" onClick={async () => {
        if (!confirmRemove) { setConfirmRemove(true); return }
        if (await call("DELETE", `/conversations/${d.group_id}/rules/${d.id}`)) { void refresh(); onClose() }
      }} />}
    </>
  }
  return <>
    <Sheet onClose={onClose}>{body()}</Sheet>
    {adding && d && <GroupPicker title={s.subscribeForGroup} onClose={() => setAdding(false)} onPick={async (c) => {
      if (await req("POST", `/conversations/${c.id}/rules`, { from_rule: d.id })) { showToast(s.subscribed); setAdding(false) }
    }} />}
    {copying && d && <GroupPicker title={s.copyToGroup} onClose={() => setCopying(false)} onPick={async (c) => {
      if (await req("POST", `/conversations/${c.id}/rules`, { copy_rule: d.is_mine ? d.id : d.my_copy_id ?? d.id })) { showToast(s.copied); setCopying(false) }
    }} />}
    {sharing && d && <SharePicker shares={[{ kind: "rule", id: d.id }]} onClose={() => setSharing(false)} />}
  </>
}

export function SwitchRow({ title, hint, on, onChange }: { title: string; hint?: string; on: boolean; onChange: (on: boolean) => void }) {
  return <div class="row" style={{ gap: 10 }}>
    <div class="col grow">
      <span class="t-body">{title}</span>
      {hint && <span class="t-small" style={{ fontSize: 12 }}>{hint}</span>}
    </div>
    <Switch on={on} onChange={onChange} label={title} />
  </div>
}

// ---------------------------------------------------------------- conversations

export const conversationTitle = (c: Conversation) => (c.kind === "group" ? c.name ?? "" : c.members[0]?.display_name ?? "?")

export function ConversationAvatar({ c, size }: { c: Conversation; size: number }) {
  const radius = size * 0.28
  if (c.kind !== "group") { const u = c.members[0]; return <Avatar name={u?.display_name ?? "?"} url={u?.avatar} size={size} /> }
  if (c.avatar || c.banner_image) return <RemoteImage url={c.avatar ?? c.banner_image} style={{ width: size, height: size, borderRadius: radius, flex: "none" }} />
  if (c.banner) return <div style={{ position: "relative", width: size, height: size, borderRadius: radius, overflow: "hidden", flex: "none" }}><BannerBg bkey={c.banner} still /></div>
  return <Avatar name={c.name ?? "?"} size={size} />
}

/** Name with a small group icon in front for groups (the only marker that it's a group). */
export function ConversationName({ c }: { c: Conversation }) {
  return <div class="row grow" style={{ gap: 6, minWidth: 0 }}>
    {c.kind === "group" && <PixelIcon name="ui_group" size={14} alpha={.55} />}
    <span class="t-strong ellipsis">{conversationTitle(c)}</span>
    {c.muted && <PixelIcon name="ui_muted" size={12} alpha={.5} />}
  </div>
}

/** Share items (rule / profile / post / challenge) into chats: one message each. */
export function SharePicker({ shares, onClose }: { shares: Record<string, unknown>[]; onClose: () => void }) {
  const s = S.value
  const [convs, setConvs] = useState<Conversation[] | null>(null)
  const [sent, setSent] = useState<Set<number>>(new Set())
  useEffect(() => { void req<{ conversations: Conversation[] }>("GET", "/conversations").then((r) => setConvs(r?.conversations ?? null)) }, [])
  return <Sheet onClose={onClose} label={s.shareTo}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.shareTo}</div>
    {!online.value ? <OfflineState /> : convs && (convs.length === 0 ? <EmptyHint title={s.noChats} /> :
      <div class="col">{convs.map((c) => {
        const done = sent.has(c.id)
        return <div key={c.id} class="row" style={{ padding: "8px 0", gap: 12, cursor: done ? undefined : "pointer" }} onClick={async () => {
          if (done) return
          let all = true
          for (const share of shares) all = !!(await req("POST", `/conversations/${c.id}/messages`, { share })) && all
          if (all) { setSent(new Set([...sent, c.id])); showToast(s.shared) }
        }}>
          <ConversationAvatar c={c} size={44} />
          <ConversationName c={c} />
          <span class="t-block" style={{ fontSize: 16, color: done ? "var(--lime)" : "var(--muted)" }}>{done ? "✓" : "↗"}</span>
        </div>
      })}</div>)}
  </Sheet>
}

/** Pick one of my groups. */
export function GroupPicker({ title, onClose, onPick }: { title?: string; onClose: () => void; onPick: (c: Conversation) => void }) {
  const s = S.value
  const [convs, setConvs] = useState<Conversation[] | null>(null)
  useEffect(() => {
    // copying / following into a group: only groups where I'm an admin
    void req<{ conversations: Conversation[] }>("GET", "/conversations").then((r) => setConvs(r?.conversations.filter((c) => c.kind === "group" && c.is_admin) ?? null))
  }, [])
  const [t, sub] = s.empty("group")
  return <Sheet onClose={onClose}>
    <div class="t-title" style={{ fontSize: 20 }}>{title ?? s.addToGroup}</div>
    {convs && (convs.length === 0 ? <EmptyHint title={t} sub={sub} icon="ui_group" /> :
      <div class="col">{convs.map((c) => <div key={c.id} class="row" style={{ padding: "8px 0", gap: 12, cursor: "pointer" }} onClick={() => onPick(c)}>
        <ConversationAvatar c={c} size={44} /><ConversationName c={c} />
      </div>)}</div>)}
  </Sheet>
}

// ---------------------------------------------------------------- selection

/** Top of a selection: "3 ausgewählt", select all, close; the actions below depend on the selection. */
/** [onHeight]: the list below can make room so its last items stay reachable. */
export function SelectionBar({ title, onClose, onAll, onHeight, children }: {
  title: string; onClose: () => void; onAll: () => void; onHeight?: (px: number) => void; children: ComponentChildren
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!onHeight || !ref.current) return
    const ro = new ResizeObserver(() => onHeight(ref.current?.offsetHeight ?? 0))
    ro.observe(ref.current)
    return () => { ro.disconnect(); onHeight(0) }
  }, [])
  return <div ref={ref} class="col" role="toolbar" style={{
    position: "absolute", left: 12, right: 12, bottom: "calc(var(--safe-bottom) + var(--tabbar) + 12px)", zIndex: 22,
    borderRadius: 20, background: "rgba(27,30,43,0.95)", border: "1px solid var(--line)", padding: 12, gap: 10,
  }}>
    <div class="row">
      <IconBtn icon="ui_close" alpha={.8} onClick={onClose} label="close" />
      <span class="t-strong grow">{title}</span>
      <button class="t-small" style={{ color: "var(--cyan)", padding: 8 }} onClick={onAll}>{S.value.selectAll}</button>
    </div>
    <div class="row" style={{ flexWrap: "wrap", gap: 8 }}>{children}</div>
  </div>
}

export function SelectionAction({ label, danger, onClick }: { label: string; danger?: boolean; onClick: () => void }) {
  return <button class="t-button" onClick={onClick} style={{
    borderRadius: 12, background: danger ? "var(--red)" : "var(--faint)", padding: "10px 14px", color: "var(--text)",
  }}>{label}</button>
}

/** A milestone as a picture: the vehicle in its scene. */
export function MilestoneTile({ vehicle, dpPerPixel = 3 }: { vehicle: string; dpPerPixel?: number }) {
  const v = meta.value?.vehicles.find((x) => x.key === vehicle)
  if (!v) return null
  const scene = sceneFor(v.category)
  const land = scene === "land"
  // "banner-card": iOS Safari drew the animated scene over the vehicle and the star (report #52, like #47)
  return <div class="fill banner-card" style={{ borderRadius: 4, overflow: "hidden" }}>
    <SceneBg scene={scene} groundDp={land ? 24 * dpPerPixel : 0} />
    <div class="fill" style={{ display: "flex", justifyContent: "center", alignItems: land ? "flex-end" : "center", paddingBottom: land ? 22 * dpPerPixel : 0 }}>
      <VehicleSprite vkey={v.key} dpPerPixel={dpPerPixel} />
    </div>
    <div style={{ position: "absolute", top: 4 * dpPerPixel, left: 4 * dpPerPixel }}><PixelIcon name="star" size={8 * dpPerPixel} /></div>
  </div>
}

/** Absolute local timestamp, e.g. "17.10.2026, 14:32" / "Oct 17, 2026, 2:32 PM". */
export function stamp(iso: string, lang: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ""
  return new Intl.DateTimeFormat(lang === "de" ? "de-DE" : "en-US", { dateStyle: "medium", timeStyle: "short" }).format(d)
}
