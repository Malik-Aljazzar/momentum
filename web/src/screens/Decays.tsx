// Shared decays: a person's public one or a group's; following one replaces my own (Android: ui/Decays.kt).
import { useEffect, useState } from "preact/hooks"
import { applied, fmtVal } from "../format"
import type { Conversation, Decay, DecayDetail } from "../model"
import { nav } from "../nav"
import { call, online, refresh, req, save, showToast } from "../store"
import { S } from "../strings"
import { Avatar, EmptyHint, IconBtn, Label, OfflineState, PixelIcon, PxButton, Sheet } from "../ui/components"
import { DecayEditor, DecaySentence, decaySteps } from "./Editors"
import { GroupPicker, SectionTitle } from "./shared"

export type DecayRef = { user: string } | { id: number }

export const decayRef = (d: DecayDetail): DecayRef => (d.kind === "group" && d.id != null ? { id: d.id } : { user: d.username ?? "" })

export const asDecay = (d: DecayDetail): Decay => ({
  base: d.base, threshold: d.threshold, catalyst_mode: d.catalyst_mode, catalyst_a: d.catalyst_a, catalyst_b: d.catalyst_b,
  streak: 0, today_gain: 0, tonight: 0, public: d.public,
})

const loss = (base: number) => "−" + fmtVal(applied("dec", base))

export function DecayIconBox({ size = 44 }: { size?: number }) {
  return <div class="center" style={{ width: size, height: size, borderRadius: size / 3, background: "rgba(239,125,87,0.18)", flex: "none" }}>
    <PixelIcon name="calendar" size={Math.round(size * .55)} />
  </div>
}

/** A shared decay in a list (profile, group, challenge). */
export function DecayRow({ d, trailing, onClick }: { d: DecayDetail; trailing?: string; onClick: () => void }) {
  const s = S.value
  return <div class="row list-item decay-row" style={{ padding: "10px 16px", gap: 12, cursor: "pointer" }} onClick={onClick}>
    <DecayIconBox />
    <div class="col grow" style={{ minWidth: 0 }}>
      <span class="t-strong ellipsis">{d.kind === "group" ? d.name : s.decayOf(d.username ?? "?")}</span>
      <span class="t-small ellipsis">{trailing ?? decaySteps(asDecay(d))}</span>
    </div>
    {d.following && <Label text="✓" color="var(--lime)" />}
    <span class="t-block" style={{ fontSize: 16, color: "var(--orange)" }}>{loss(d.base)}</span>
  </div>
}

/**
 * A shared decay seen from outside: its values, whose it is, follow (it replaces my own) or stop.
 * inGroup: opened from the group overview, where admins edit and remove the group's decays.
 */
export function DecayDetailSheet({ dref, onClose, inGroup = false, onEdit, onChanged }: {
  dref: DecayRef; onClose: () => void; inGroup?: boolean; onEdit?: (d: DecayDetail) => void; onChanged?: () => void
}) {
  const s = S.value
  const [detail, setDetail] = useState<DecayDetail | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [reload, setReload] = useState(0)
  const [toGroup, setToGroup] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const key = "id" in dref ? `g${dref.id}` : `u${dref.user}`
  useEffect(() => {
    const path = "id" in dref ? `/decays/${dref.id}` : `/decays/user/${dref.user}`
    void req<{ decay: DecayDetail }>("GET", path).then((r) => { setDetail(r?.decay ?? null); setLoaded(true) })
  }, [key, reload])

  const d = detail
  const body = () => {
    if (!d) return loaded ? (online.value ? <EmptyHint title={s.notAvailable} icon="calendar" /> : <OfflineState />) : <div style={{ height: 160 }} />
    const group = d.kind === "group"
    const openable = group ? d.group_name != null && d.group_id != null : !!d.owner
    const origin = group ? (d.group_name ? s.groupDecayOf(d.group_name) : s.groupDecays) + (d.linked_from ? ` · ${s.followsDecayOf(d.linked_from)}` : "")
      : d.is_mine ? s.publicDecay : s.decayOf(d.username ?? "?")
    const locked = d.locked_by
    return <>
      <div class="row" style={{ gap: 14 }}>
        <DecayIconBox size={56} />
        <div class="col grow">
          <Label text={s.dailyDecay} />
          <span class="t-title" style={{ fontSize: 22, lineHeight: "26px", overflowWrap: "anywhere" }}>{group ? d.name : s.decayOf(d.username ?? "?")}</span>
        </div>
        <span class="t-block" style={{ fontSize: 24, color: "var(--orange)" }}>{loss(d.base)}</span>
      </div>
      {/* whose it is: the group (its name stays hidden for private groups) or the person */}
      <div class="row" style={{ borderRadius: 14, background: "var(--faint)", padding: 12, gap: 10, cursor: openable ? "pointer" : undefined }}
        onClick={() => {
          if (!openable) return
          onClose()
          if (group) nav.push({ r: "group", id: d.group_id! }); else nav.push({ r: "user", username: d.owner!.username })
        }}>
        {group ? <div class="center" style={{ width: 32, height: 32, borderRadius: 9, background: "var(--faint)", flex: "none" }}>
          <PixelIcon name="ui_group" size={18} color="var(--cyan)" /></div>
          : d.owner && <Avatar name={d.owner.display_name} url={d.owner.avatar} size={32} />}
        <div class="col grow">
          <span class="t-strong">{origin}</span>
          <span class="t-small">{s.subscribers(d.followers ?? 0)}</span>
        </div>
      </div>
      <DecaySentence decay={asDecay(d)} />
      <span class="t-small">{s.previewDecay}</span>
      <span class="t-block" style={{ fontSize: 14 }}>{decaySteps(asDecay(d))}</span>
      {d.following && <span class="t-small" style={{ color: "var(--cyan)" }}>{s.decayFollowing}</span>}
      {group && d.is_mine && <span class="t-small" style={{ color: "var(--cyan)" }}>{s.ownDecayInGroup}</span>}
      {d.members_only && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.onlyMembersCanSubscribe(d.group_name ?? null)}</span>}
      {locked && (d.following || d.can_follow) && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.decayLockedBy(locked)}</span>}
      {!locked && d.can_follow && <>
        <span class="t-small">{s.followDecayHint}</span>
        <PxButton text={s.followDecay} onClick={async () => {
          if (await save("POST", "/decay/follow", group ? { decay_id: d.id } : { username: d.username }, true)) { showToast(s.subscribed); setReload((n) => n + 1) }
        }} />
      </>}
      {!locked && !d.can_follow && d.following && <PxButton text={s.unsubscribe} kind="danger" onClick={async () => {
        if (await save("DELETE", "/decay/follow", undefined, true)) setReload((n) => n + 1)
      }} />}
      {/* public decays can go into groups (they stay linked to the original) */}
      {d.public && (!group || d.group_name != null) && <PxButton text={s.subscribeForGroup} kind="ghost" onClick={() => setToGroup(true)} />}
      {onEdit && d.editable && <PxButton text={s.edit} kind="ghost" onClick={() => onEdit(d)} />}
      {inGroup && group && d.removable && d.group_id != null && <PxButton text={confirmRemove ? s.reallyRemoveFromGroup : s.removeFromGroup} kind="danger" onClick={async () => {
        if (!confirmRemove) { setConfirmRemove(true); return }
        if (await call("DELETE", `/conversations/${d.group_id}/decays/${d.id}`)) { onChanged?.(); void refresh(); onClose() }
      }} />}
    </>
  }
  return <>
    <Sheet onClose={onClose}>{body()}</Sheet>
    {toGroup && d && <GroupPicker title={s.subscribeForGroup} onClose={() => setToGroup(false)} onPick={async (c: Conversation) => {
      setToGroup(false)
      if (await req("POST", `/conversations/${c.id}/decays`, d.kind === "group" ? { from_decay: d.id } : { from_user: d.username })) showToast(s.subscribed)
    }} />}
  </>
}

const NEW_DECAY: Decay = { base: 50, threshold: 0, catalyst_mode: "none", catalyst_a: 0, catalyst_b: 1.5, streak: 0, today_gain: 0, tonight: 0 }

/** The group's decays (group overview, rules tab): admins create them; members follow one. */
export function GroupDecaysSection({ c, reloadKey }: { c: Conversation; reloadKey: number }) {
  const s = S.value
  const [decays, setDecays] = useState<DecayDetail[]>([])
  const [reload, setReload] = useState(0)
  const [open, setOpen] = useState<DecayDetail | null>(null)
  const [editing, setEditing] = useState<DecayDetail | "new" | null>(null)
  const bump = () => setReload((n) => n + 1)
  useEffect(() => {
    void req<{ decays: DecayDetail[] }>("GET", `/conversations/${c.id}/decays`).then((r) => setDecays(r?.decays ?? []))
  }, [c.id, reloadKey, reload])
  const e = editing === "new" ? null : editing
  return <>
    <SectionTitle text={s.groupDecays} action={c.is_admin ? <IconBtn icon="ui_plus" alpha={.8} onClick={() => setEditing("new")} label="new decay" /> : undefined} />
    {decays.length === 0 && <div class="t-small" style={{ padding: "6px 16px" }}>{s.noGroupDecays}</div>}
    {decays.map((d) => <DecayRow key={d.id} d={d} onClick={() => setOpen(d)} />)}
    {open && <DecayDetailSheet dref={decayRef(open)} inGroup onClose={() => { setOpen(null); bump() }}
      onEdit={(x) => { setOpen(null); setEditing(x) }} onChanged={bump} />}
    {editing && <Sheet onClose={() => setEditing(null)}>
      <DecayEditor decay={e ? asDecay(e) : NEW_DECAY} onClose={() => setEditing(null)} title={e ? s.groupDecays : s.newGroupDecay} name={e?.name ?? ""}
        submit={async (body) => {
          const ok = (await req(e ? "PUT" : "POST", `/conversations/${c.id}/decays` + (e ? `/${e.id}` : ""), body)) != null
          if (ok) { bump(); void refresh() }
          return ok
        }}
        onDelete={e ? async () => {
          const ok = await call("DELETE", `/conversations/${c.id}/decays/${e.id}`)
          if (ok) { bump(); void refresh() }
          return ok
        } : undefined} />
    </Sheet>}
  </>
}
