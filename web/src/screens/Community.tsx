// Groups and challenges (android/.../ui/Community.kt): group overview with tabs, group rules and
// their bulk actions, challenges, follow requests.
import { useEffect, useState } from "preact/hooks"
import { fmt, fmtShort } from "../format"
import type { Challenge, Conversation, DecayDetail, RankEntry, RuleDetail, UserCard } from "../model"
import { nav } from "../nav"
import { bulk, call, online, refresh, req, save, showToast, state, uploadMedia } from "../store"
import { S, type Strings } from "../strings"
import {
  Avatar, BannerBg, BannerPicker, EmptyHint, FitText, IconBtn, Label, OfflineState, OfflineStrip, PixelCheckbox, PixelIcon, PxButton, PxCard,
  PxTextField, RemoteImage, SceneBg, SegBar, Sheet, TopBar, UserRow,
} from "../ui/components"
import { pickFiles, shrinkImage } from "../ui/media"
import { RuleEditor } from "./Editors"
import { DecayDetailSheet, DecayIconBox, DecayRow, decayRef, GroupDecaysSection } from "./Decays"
import { PeriodSwitch, rankPeriod, RankRow } from "./Leaderboard"
import { EncryptionSection } from "./Secure"
import {
  kindColor, RuleDetailRow, RuleDetailSheet, RuleIconBox, SectionTitle, SelectionAction, SelectionBar, SharePicker, signedValue, SwitchRow,
} from "./shared"

const rewardText = (s: Strings, c: Challenge) => c.mode === "target"
  ? s.targetLine(fmt(c.target ?? 0), fmt(c.rewards?.[0] ?? 0)) : s.rewardsLine((c.rewards ?? []).map(fmt))

export function ChallengeMini({ c, onClick }: { c: Challenge; onClick: () => void }) {
  const s = S.value
  return <div class="col" onClick={onClick} style={{ width: 260, borderRadius: 16, background: "linear-gradient(135deg, #3b2a12, #1b1520)", padding: 14, cursor: "pointer" }}>
    <div class="row" style={{ gap: 10 }}>
      <PixelIcon name="trophy" size={28} />
      <div class="col grow">
        <Label text={s.challenge + " · " + (c.mode === "target" ? s.modeTarget : s.modeRanking)} color="var(--yellow)" />
        <span class="t-strong" style={{ overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}>{c.title}</span>
      </div>
    </div>
    <div style={{ height: 6 }} />
    <span class="t-small">{s.days(c.days) + " · " + s.challengeStatus(c.status, c.days_left ?? null, c.starts_in ?? null)}</span>
    <span class="t-small" style={{ color: "var(--yellow)" }}>{rewardText(s, c)}</span>
  </div>
}

export function ChallengeDetailScreen({ id }: { id: number }) {
  const s = S.value
  const [c, setC] = useState<Challenge | null>(null)
  const [openRule, setOpenRule] = useState<number | null>(null)
  const [openDecay, setOpenDecay] = useState<DecayDetail | null>(null)
  const [confirm, setConfirm] = useState<string | null>(null)
  useEffect(() => { void req<{ challenge: Challenge }>("GET", `/challenges/${id}`).then((r) => setC(r?.challenge ?? null)) }, [id])
  const act = async (path: string) => {
    const r = await req<{ challenge: Challenge }>("POST", `/challenges/${id}/${path}`)
    if (r) setC(r.challenge)
    setConfirm(null)
    void refresh()           // challenge rules appear / disappear in the rule lists
  }
  const ch = c
  const over = ch && (ch.status === "finished" || ch.status === "cancelled")
  const target = ch?.mode === "target"
  return <div class="fill col">
    <TopBar title={s.challenge} />
    <OfflineStrip />
    {!ch ? (!online.value && <OfflineState />) : <div class="scroll" style={{ flex: 1, paddingBottom: 40 }}>
      <div class="banner-card" style={{ position: "relative", margin: "0 16px", minHeight: 190, borderRadius: "var(--card-radius)", overflow: "hidden" }}>
        {ch.banner ? <BannerBg bkey={ch.banner} /> : <SceneBg scene="acc" />}
        <div class="col" style={{ position: "relative", padding: "60px 20px 20px" }}>
          <div class="row" style={{ gap: 8 }}><PixelIcon name="trophy" size={28} /><Label text={target ? s.modeTarget : s.modeRanking} color="var(--yellow)" /></div>
          <FitText text={ch.title} max={28} />
          <span class="t-small" style={{ color: "var(--text)" }}>{s.days(ch.days) + " · " + s.challengeStatus(ch.status, ch.days_left ?? null, ch.starts_in ?? null) + " · " + s.joinedCount(ch.participants ?? 0)}</span>
          <span class="t-body" style={{ color: "var(--yellow)" }}>{rewardText(s, ch)}</span>
        </div>
      </div>
      <div class="col" style={{ padding: 16, gap: 10 }}>
        <span class="t-small">{target ? s.modeTargetHint : s.modeRankingHint}</span>
        {ch.my_score != null && <PxCard padding={16}>
          <Label text={s.yourScore} />
          <div class="row" style={{ alignItems: "flex-end" }}>
            <span class="t-block" style={{ fontSize: 34, lineHeight: "38px" }}>{fmt(ch.my_score)}</span>
            {target && <span class="t-block" style={{ fontSize: 16, color: "var(--muted)", paddingBottom: 4 }}> / {fmt(ch.target ?? 0)}</span>}
            <span class="grow" />
            {!target && ch.my_rank != null && <span class="t-block" style={{ fontSize: 20, color: "var(--yellow)" }}>{s.place(ch.my_rank)}</span>}
          </div>
          {target && <><div style={{ height: 8 }} /><SegBar fraction={(ch.target ?? 0) > 0 ? ch.my_score / ch.target! : 0} color={ch.my_score >= (ch.target ?? 0) ? "var(--lime)" : "var(--yellow)"} /></>}
          {(ch.my_reward ?? 0) > 0 && <span class="t-strong" style={{ color: "var(--lime)" }}>+{fmt(ch.my_reward ?? 0)} Momentum</span>}
        </PxCard>}
        {ch.left && <span class="t-small" style={{ color: "var(--orange)" }}>{s.leftChallenge}</span>}
        {ch.joined && !over && <SwitchRow title={s.keepRules} hint={s.keepRulesHint} on={!!ch.keep_rules} onChange={async (on) => {
          const r = await req<{ challenge: Challenge }>("PUT", `/challenges/${id}/keep-rules`, { keep: on })
          if (r) setC(r.challenge)
        }} />}
        <div class="row" style={{ gap: 10 }}>
          {ch.can_join && <PxButton text={s.join} class="grow" onClick={() => act("join")} />}
          {ch.joined && !over && <PxButton text={confirm === "leave" ? s.reallyLeave : s.leaveChallenge} kind="ghost" class="grow"
            onClick={() => (confirm !== "leave" ? setConfirm("leave") : act("leave"))} />}
          {ch.can_cancel && <PxButton text={confirm === "cancel" ? s.reallyCancel : s.cancelChallenge} kind="danger" class="grow"
            onClick={() => (confirm !== "cancel" ? setConfirm("cancel") : act("cancel"))} />}
        </div>
      </div>
      {(ch.leaderboard?.length ?? 0) > 0 && <>
        <SectionTitle text={s.leaderboard} />
        {ch.leaderboard!.map((e, i) => e.user && <div key={i} class="row list-item" onClick={() => nav.push({ r: "user", username: e.user!.username })}
          style={{ padding: "8px 16px", gap: 10, cursor: "pointer", background: e.user.is_me ? "var(--faint)" : undefined }}>
          <span class="t-block" style={{ width: 28, fontSize: 16, color: (e.rank ?? 0) >= 1 && (e.rank ?? 0) <= 3 && !target ? "var(--yellow)" : "var(--muted)" }}>{e.rank ?? 0}</span>
          <RemoteImage url={e.user.avatar} style={{ width: 36, height: 36, borderRadius: 10, background: "var(--faint)" }} />
          <span class="t-strong ellipsis grow">{e.user.username}</span>
          {(e.reward ?? 0) > 0 && <><PixelIcon name="trophy" size={16} /><span class="t-small" style={{ color: "var(--lime)", marginRight: 10 }}> +{fmt(e.reward ?? 0)}</span></>}
          <span class="t-block" style={{ fontSize: 16 }}>{fmt(e.score ?? 0)}</span>
        </div>)}
      </>}
      <SectionTitle text={s.challengeRules} />
      {(ch.rules ?? []).map((r) => <RuleDetailRow key={r.id} r={r} onClick={() => setOpenRule(r.id)} />)}
      {ch.decay && <><SectionTitle text={s.dailyDecay} /><DecayRow d={ch.decay} trailing={s.challengeDecayHint} onClick={() => setOpenDecay(ch.decay!)} /></>}
      {ch.creator && <div class="t-small" style={{ padding: 16 }}>{s.createdBy(ch.creator.username)}</div>}
    </div>}
    {openRule != null && <RuleDetailSheet ruleId={openRule} onClose={() => setOpenRule(null)} />}
    {openDecay && <DecayDetailSheet dref={decayRef(openDecay)} onClose={() => setOpenDecay(null)} />}
  </div>
}

const MAX_CHALLENGE_RULES = 10

export function NewChallengeScreen({ convId }: { convId: number }) {
  const s = S.value
  const [title, setTitle] = useState("")
  const [mode, setMode] = useState("ranking")
  const [days, setDays] = useState(7)
  const [startIn, setStartIn] = useState(0)
  const [r1, setR1] = useState("300")
  const [r2, setR2] = useState("")
  const [r3, setR3] = useState("")
  const [target, setTarget] = useState("1000")
  const [groupRules, setGroupRules] = useState<RuleDetail[]>([])
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const [groupDecays, setGroupDecays] = useState<DecayDetail[]>([])
  const [pickedDecay, setPickedDecay] = useState<number | null>(null)
  const [banner, setBanner] = useState<string | null>(null)
  const [pickBanner, setPickBanner] = useState(false)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    void req<{ rules: RuleDetail[] }>("GET", `/conversations/${convId}/rules`).then((r) => setGroupRules(r?.rules ?? []))
    void req<{ decays: DecayDetail[] }>("GET", `/conversations/${convId}/decays`).then((r) => setGroupDecays(r?.decays ?? []))
  }, [convId])
  const ready = title.trim() !== "" && picked.size > 0
  const create = async () => {
    if (!ready) { showToast(s.challengeMissing, true); return }
    setBusy(true)
    const body: Record<string, unknown> = {
      title: title.trim(), mode, days, start_in: startIn, reward1: Number(r1) || 0, rules: [...picked].map((id) => ({ rule_id: id })),
    }
    if (mode === "ranking") { body.reward2 = Number(r2) || 0; body.reward3 = Number(r3) || 0 } else body.target = Number(target) || 0
    if (banner) body.banner = banner
    if (pickedDecay != null) body.decay_id = pickedDecay
    const r = await req<{ challenge: Challenge }>("POST", `/conversations/${convId}/challenges`, body)
    if (r) { void refresh(); nav.replace({ r: "challenge", id: r.challenge.id }) }
    setBusy(false)
  }
  const limit = state.value?.limit.cap ?? 0
  let maxReward = limit >= 1 && limit <= 1e8 ? days * limit : Infinity
  if (mode === "target") maxReward = Math.min(maxReward, Number(target) || 0)
  return <div class="fill col">
    <TopBar title={s.newChallenge}>
      <button class="t-button" disabled={busy} onClick={create} style={{ marginRight: 8, borderRadius: 12, padding: "10px 16px", background: ready ? "var(--text)" : "var(--faint)", color: ready ? "#000" : "var(--muted)" }}>{s.create}</button>
    </TopBar>
    {!online.value ? <OfflineState /> : <div class="scroll col" style={{ flex: 1, padding: 16, gap: 12 }}>
      <PxTextField value={title} onInput={(v) => { if (v.length <= 60) setTitle(v) }} placeholder={s.challengeName} />
      <button class="banner-card" onClick={() => setPickBanner(true)} style={{ position: "relative", height: 84, borderRadius: 14, overflow: "hidden", flex: "none" }}>
        {banner ? <BannerBg bkey={banner} still /> : <SceneBg scene="acc" />}
        <span class="t-small" style={{ position: "absolute", left: 10, bottom: 10, background: "rgba(0,0,0,0.6)", borderRadius: 8, padding: "3px 8px", color: "var(--text)" }}>
          {s.banner + ": " + (banner ? s.bannerName(banner) : s.challenge) + "  ▾"}
        </span>
      </button>
      <ChipRow options={[["ranking", s.modeRanking], ["target", s.modeTarget]]} selected={mode} onPick={setMode} />
      <span class="t-small">{mode === "target" ? s.modeTargetHint : s.modeRankingHint}</span>
      <Label text={s.duration} />
      <div class="row" style={{ gap: 0 }}>
        <StepBtn text="−" onClick={() => setDays(Math.max(7, days - 1))} />
        <span class="t-block" style={{ fontSize: 18, padding: "0 16px" }}>{s.days(days)}</span>
        <StepBtn text="+" onClick={() => setDays(Math.min(90, days + 1))} />
        <span style={{ width: 12 }} />
        {/* at least a week */}{[7, 14, 30, 60].map((n) => <button key={n} class="t-small" onClick={() => setDays(n)} style={{ padding: 8, color: n === days ? "var(--yellow)" : undefined }}>{n}</button>)}
      </div>
      <Label text={s.startLabel} />
      <ChipRow options={[0, 1, 2, 7].map((n) => [n, s.startOption(n)] as [number, string])} selected={startIn} onPick={setStartIn} />
      {Number.isFinite(maxReward) && <span class="t-small">{s.rewardMax(fmt(maxReward)) + " · " + s.challengeCreditHint}</span>}
      {mode === "ranking" ? <>
        <NumberField label={s.rewardPlace(1)} value={r1} onChange={setR1} />
        <NumberField label={`${s.rewardPlace(2)} (${s.optional})`} value={r2} onChange={setR2} />
        {(Number(r2) || 0) > 0 && <NumberField label={`${s.rewardPlace(3)} (${s.optional})`} value={r3} onChange={setR3} />}
      </> : <>
        <NumberField label={s.targetLabel} value={target} onChange={setTarget} />
        <NumberField label={s.rewardLabel} value={r1} onChange={setR1} />
      </>}
      <Label text={s.challengeRules} />
      <span class="t-small">{s.challengeOnlyGroupRules}</span>
      {groupRules.length === 0 && <span class="t-small" style={{ color: "var(--yellow)" }}>{s.noGroupRules}</span>}
      {groupRules.map((r) => {
        const on = picked.has(r.id)
        return <div key={r.id} class="row" style={{ borderRadius: 14, background: on ? "var(--faint)" : undefined, padding: 10, gap: 10, cursor: "pointer" }} onClick={() => {
          const n = new Set(picked)
          if (on) n.delete(r.id); else if (n.size < MAX_CHALLENGE_RULES) n.add(r.id)
          setPicked(n)
        }}>
          <RuleIconBox icon={r.icon} size={36} />
          <span class="t-strong ellipsis grow">{r.name}</span>
          <span class="t-block" style={{ fontSize: 14, color: kindColor(r.kind) }}>{signedValue(r.kind, r.base)}</span>
          <PixelCheckbox checked={on} />
        </div>
      })}
      {groupDecays.length > 0 && <>
        <Label text={s.challengeDecay} />
        <span class="t-small">{s.challengeDecayHint}</span>
        {[null, ...groupDecays].map((d) => {
          const on = pickedDecay === (d?.id ?? null)
          return <div key={d?.id ?? "none"} class="row decay-pick" style={{ borderRadius: 14, background: on ? "var(--faint)" : undefined, padding: 10, gap: 10, cursor: "pointer" }}
            onClick={() => setPickedDecay(d?.id ?? null)}>
            {d ? <DecayIconBox size={36} /> : <span style={{ width: 36 }} />}
            <span class="t-strong ellipsis grow">{d?.name ?? s.noDecay}</span>
            {d && <span class="t-block" style={{ fontSize: 14, color: "var(--orange)" }}>{signedValue("dec", d.base)}</span>}
            <PixelCheckbox checked={on} />
          </div>
        })}
      </>}
      <div style={{ height: 40 }} />
    </div>}
    {pickBanner && <BannerPicker selected={banner} allowAuto={false} onClose={() => setPickBanner(false)} onPick={(k) => { setBanner(k); setPickBanner(false) }} />}
  </div>
}

function ChipRow<T extends string | number>({ options, selected, onPick }: { options: [T, string][]; selected: T; onPick: (v: T) => void }) {
  return <div class="row" style={{ flexWrap: "wrap", gap: 8 }}>
    {options.map(([v, label]) => <button key={String(v)} class={"chip t-button" + (v === selected ? " on" : "")} onClick={() => onPick(v)}>{label}</button>)}
  </div>
}

const StepBtn = ({ text, onClick }: { text: string; onClick: () => void }) =>
  <button class="center t-block" onClick={onClick} style={{ width: 40, height: 40, borderRadius: 12, background: "var(--faint)", fontSize: 18 }}>{text}</button>

function NumberField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return <div class="col" style={{ gap: 4 }}>
    <span class="t-small">{label}</span>
    <PxTextField value={value} inputMode="numeric" onInput={(v) => onChange(v.replace(/\D/g, "").slice(0, 7))} />
  </div>
}

// ---------------------------------------------------------------- group overview

type GroupSection = "challenges" | "rules" | "ranking" | "members"
const SECTION_ICONS: Record<GroupSection, string> = { challenges: "ui_cup", rules: "ui_updown", ranking: "ui_rules", members: "ui_group" }

export function GroupInfoScreen({ convId }: { convId: number }) {
  const s = S.value
  const [conv, setConv] = useState<Conversation | null>(null)
  const [rules, setRules] = useState<RuleDetail[]>([])
  const [challenges, setChallenges] = useState<Challenge[]>([])
  const [ranking, setRanking] = useState<RankEntry[]>([])
  const [reload, setReload] = useState(0)
  const [openRule, setOpenRule] = useState<number | null>(null)
  const [editing, setEditing] = useState<{ kind: string; rule: RuleDetail | null } | "pick" | null>(null)
  const [confirmLeave, setConfirmLeave] = useState(false)
  const [pickBanner, setPickBanner] = useState(false)
  const [addRule, setAddRule] = useState<"menu" | "copy" | null>(null)
  const [addMembers, setAddMembers] = useState(false)
  const [section, setSection] = useState<GroupSection>("challenges")
  const [sel, setSel] = useState<Set<number>>(new Set())        // long-pressed group rules
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [sharing, setSharing] = useState<Record<string, unknown>[] | null>(null)
  const [editLook, setEditLook] = useState(false)
  const [memberMenu, setMemberMenu] = useState<UserCard | null>(null)
  const [confirmKick, setConfirmKick] = useState(false)
  useEffect(() => setConfirmKick(false), [memberMenu])
  const bump = () => setReload((n) => n + 1)
  useEffect(() => setSel(new Set()), [section])
  useEffect(() => {
    void (async () => {
      const c = (await req<{ conversation: Conversation }>("GET", `/conversations/${convId}`))?.conversation ?? null
      setConv(c)
      setRules((await req<{ rules: RuleDetail[] }>("GET", `/conversations/${convId}/rules`))?.rules ?? [])
      setChallenges(c?.member ? (await req<{ challenges: Challenge[] }>("GET", `/conversations/${convId}/challenges`))?.challenges ?? [] : [])
      setRanking((await req<{ entries: RankEntry[] }>("GET", `/conversations/${convId}/ranking${rankPeriod.value === "24h" ? "?period=today" : ""}`))?.entries ?? [])
    })()
  }, [convId, reload, rankPeriod.value])
  const update = async (body: Record<string, unknown>) => {
    const r = await req<{ conversation: Conversation }>("PUT", `/conversations/${convId}`, body)
    if (r) setConv(r.conversation)
  }

  const c = conv
  const sections = (["challenges", "rules", "ranking", "members"] as GroupSection[]).filter((x) => c?.member || x !== "challenges")
  const current = sections.includes(section) ? section : sections[0]
  const picked = rules.filter((r) => sel.has(r.id))
  const toSubscribe = picked.filter((r) => r.can_subscribe)
  const toUnsubscribe = picked.filter((r) => r.subscribed && r.my_copy_id != null && !r.locked_by)
  const toRemove = picked.filter((r) => r.removable)
  const toPublic = picked.filter((r) => r.editable && !r.public)
  const toPrivate = picked.filter((r) => r.editable && r.public)
  const done = (r: { done: number; skipped: unknown[] } | null) => { if (r) { showToast(s.bulkResult(r.done, r.skipped.length)); setSel(new Set()); bump() } }
  const myRules = (state.value?.rules ?? []).filter((r) => r.id > 0 && r.origin !== "challenge")

  return <div class="fill col">
    <TopBar title={c?.name ?? s.groupInfo} />
    <OfflineStrip />
    {!c ? (!online.value && <OfflineState />) : <div class="scroll" style={{ flex: 1, paddingBottom: 40 }}>
      {/* the group's banner (a pixel banner or an own image), name, total momentum */}
      <div class="banner-card" style={{ position: "relative", margin: "4px 16px", height: 180, borderRadius: "var(--card-radius)", overflow: "hidden" }}>
        {c.banner_image ? <RemoteImage url={c.banner_image} class="fill" /> : <BannerBg bkey={c.banner ?? "dusk"} />}
        <div class="fill" style={{ background: "linear-gradient(180deg, transparent, rgba(0,0,0,0.6))" }} />
        <div class="row" style={{ position: "absolute", left: 16, right: 16, bottom: 16, alignItems: "flex-end", gap: 10 }}>
          <div class="col grow">
            <Label text={s.members(c.members.length + (c.member ? 1 : 0)) + " · " + (c.public ? s.tagPublic : s.tagPrivate) + (c.open ? " · " + s.tagOpen : "")} color="var(--text)" />
            <FitText text={c.name ?? ""} max={26} />
          </div>
          <div class="col" style={{ alignItems: "flex-end" }}>
            <Label text={s.groupTotal} color="var(--yellow)" />
            <span class="t-block" style={{ fontSize: 22 }}>{fmtShort(c.total_momentum ?? 0)}</span>
          </div>
        </div>
        {c.is_admin && <div style={{ position: "absolute", top: 8, right: 8, borderRadius: 12, background: "rgba(0,0,0,0.4)" }}>
          <IconBtn icon="ui_edit" alpha={.9} onClick={() => setEditLook(true)} label="edit look" />
        </div>}
      </div>
      {!c.member && <div style={{ padding: 16 }}>
        {/* a public group seen from outside: join if it's open */}
        {c.open ? <PxButton text={s.joinGroup} style={{ width: "100%" }} onClick={async () => {
          const r = await req<{ conversation: Conversation }>("POST", `/conversations/${convId}/join`)
          if (r) { setConv(r.conversation); bump() }
        }} /> : <span class="t-small">{s.closedGroup}</span>}
      </div>}
      {/* the areas as tabs, like on profiles */}
      <div class="col" style={{ position: "sticky", top: 0, zIndex: 2, background: "var(--bg)", paddingTop: 8 }}>
        <div class="row">{sections.map((x) => <button key={x} class="col grow" aria-label={x} style={{ alignItems: "center", paddingTop: 12 }} onClick={() => setSection(x)}>
          <PixelIcon name={SECTION_ICONS[x]} size={20} alpha={x === current ? 1 : .4} />
          <i style={{ marginTop: 10, height: 2, width: "100%", background: x === current ? "var(--text)" : "transparent" }} />
        </button>)}</div>
        <div style={{ height: 1, background: "var(--line)" }} />
      </div>

      {current === "challenges" && <>
        {/* admins start challenges */}
        <SectionTitle text={s.challenges} action={c.is_admin ? <IconBtn icon="ui_plus" alpha={.8} onClick={() => nav.push({ r: "newChallenge", conversationId: convId })} label="new challenge" /> : undefined} />
        {challenges.length === 0 && <div class="t-small" style={{ padding: "0 16px" }}>{s.noChallenges}</div>}
        {challenges.map((ch) => {
          const over = ch.status === "finished" || ch.status === "cancelled"
          return <div key={ch.id} class="row list-item" style={{ padding: "10px 16px", gap: 12, cursor: "pointer" }} onClick={() => nav.push({ r: "challenge", id: ch.id })}>
            <div class="center" style={{ width: 44, height: 44, borderRadius: 14, background: "rgba(255,205,117,0.2)", flex: "none" }}><PixelIcon name="trophy" size={26} alpha={over ? .5 : 1} /></div>
            <div class="col grow">
              <span class="t-strong ellipsis">{ch.title}</span>
              <span class="t-small">{s.challengeStatus(ch.status, ch.days_left ?? null, ch.starts_in ?? null) + " · " + s.joinedCount(ch.participants ?? 0)}</span>
            </div>
            {ch.joined && !over && <Label text="✓" color="var(--lime)" />}
          </div>
        })}
      </>}

      {current === "rules" && <>
        <SectionTitle text={s.groupRules} action={c.is_admin ? <IconBtn icon="ui_plus" alpha={.8} onClick={() => setAddRule("menu")} label="add rule" /> : undefined} />
        {/* always follow all: every rule of the group is subscribed automatically */}
        {c.member && <div style={{ padding: "8px 16px" }}><SwitchRow title={s.autoFollow} hint={s.autoFollowHint} on={!!c.auto_follow} onChange={async () => {
          if (await save("PUT", `/conversations/${convId}/auto-follow`, { on: !c.auto_follow }, true)) { setConv({ ...c, auto_follow: !c.auto_follow }); bump() }
        }} /></div>}
        {c.member && rules.some((r) => r.subscribed) && <div style={{ padding: "4px 16px" }}>
          <PxButton text={s.unsubscribeAll} kind="ghost" style={{ width: "100%" }} onClick={async () => {
            if (await save("DELETE", `/conversations/${convId}/subscriptions`, undefined, true)) { setConv({ ...c, auto_follow: false }); bump() }
          }} />
        </div>}
        {rules.length === 0 && <div class="t-small" style={{ padding: "0 16px" }}>{s.noGroupRules}</div>}
        {rules.map((r) => <RuleDetailRow key={r.id} r={r} selected={sel.size ? sel.has(r.id) : null}
          trailing={s.subscribers(r.subscribers ?? 0) + (r.subscribed ? " · ✓" : "")}
          onLongClick={() => setSel(new Set([...sel, r.id]))}
          onClick={() => {
            if (!sel.size) { setOpenRule(r.id); return }
            const n = new Set(sel); if (n.has(r.id)) n.delete(r.id); else n.add(r.id); setSel(n)
          }} />)}
        <GroupDecaysSection c={c} reloadKey={reload} />
      </>}

      {current === "ranking" && <>
        <SectionTitle text={s.groupRanking} />
        <PeriodSwitch />
        {ranking.map((e) => <RankRow key={e.user.username} e={e} onClick={() => nav.push({ r: "user", username: e.user.username })} />)}
      </>}

      {current === "members" && <>
        <SectionTitle text={s.membersTitle} action={c.is_admin ? <IconBtn icon="ui_plus" alpha={.8} onClick={() => setAddMembers(true)} label="add members" /> : undefined} />
        {/* admins (not in anarchy, where everyone is one) manage the others' admin rights */}
        {c.members.map((u) => <UserRow key={u.username} user={u}
          onClick={() => (c.is_admin && !c.anarchy ? setMemberMenu(u) : nav.push({ r: "user", username: u.username }))}
          trailing={c.admins?.includes(u.username) ? <Label text={s.adminTag} color="var(--yellow)" /> : undefined} />)}
        {c.member && !c.is_admin && <div class="t-small" style={{ padding: "10px 16px" }}>{s.onlyAdmins}</div>}
        {c.member && c.is_admin && <div class="col" style={{ padding: "12px 16px", gap: 8 }}>
          {/* visibility: public (found in the search) and open (anyone may join, only when public) */}
          <SwitchRow title={s.groupPublic} hint={s.groupPublicHint} on={!!c.public} onChange={(v) => update({ public: v })} />
          <div style={{ opacity: c.public ? 1 : .45, pointerEvents: c.public ? undefined : "none" }}>
            <SwitchRow title={s.groupOpen} hint={s.groupOpenHint} on={!!c.open} onChange={(v) => update({ open: v })} />
          </div>
          <SwitchRow title={s.anarchyMode} hint={s.anarchyHint} on={!!c.anarchy} onChange={(v) => update({ anarchy: v })} />
        </div>}
        {/* end-to-end encryption (admins switch it) and the members' safety codes */}
        {c.member && <EncryptionSection conv={c} onConv={setConv} />}
        {c.member && <div style={{ padding: 16 }}><PxButton text={confirmLeave ? s.reallyLeave : s.leaveGroup} kind="danger" style={{ width: "100%" }} onClick={async () => {
          if (!confirmLeave) { setConfirmLeave(true); return }
          if (await call("DELETE", `/conversations/${convId}/members/me`)) { void refresh(); nav.pop(); nav.pop() }   // group info and the chat
        }} /></div>}
      </>}
    </div>}

    {sel.size > 0 && <SelectionBar title={s.selectedCount(picked.length)} onClose={() => setSel(new Set())} onAll={() => setSel(new Set(rules.map((r) => r.id)))}>
      {toSubscribe.length > 0 && <SelectionAction label={s.subscribeN(toSubscribe.length)} onClick={async () => done(await bulk("/rules/subscribe-many", { ids: toSubscribe.map((r) => r.id) }))} />}
      {toUnsubscribe.length > 0 && <SelectionAction label={s.unsubscribeN(toUnsubscribe.length)} onClick={async () => done(await bulk("/rules/remove-many", { ids: toUnsubscribe.map((r) => r.my_copy_id) }))} />}
      {toRemove.length > 0 && <SelectionAction label={s.removeN(toRemove.length)} danger onClick={() => setConfirmRemove(true)} />}
      {/* the group's own rules: who may edit them also decides who can see them */}
      {toPublic.length > 0 && <SelectionAction label={s.makePublicN(toPublic.length)} onClick={async () => done(await bulk(`/conversations/${convId}/rules/bulk`, { action: "public", ids: toPublic.map((r) => r.id) }))} />}
      {toPrivate.length > 0 && <SelectionAction label={s.makePrivateN(toPrivate.length)} onClick={async () => done(await bulk(`/conversations/${convId}/rules/bulk`, { action: "private", ids: toPrivate.map((r) => r.id) }))} />}
      <SelectionAction label={s.share} onClick={() => setSharing(picked.map((r) => ({ kind: "rule", id: r.id })))} />
    </SelectionBar>}
    {confirmRemove && <Sheet onClose={() => setConfirmRemove(false)}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.confirmRemoveFromGroup(toRemove.length)}</div>
      <span class="t-small">{s.removeFromGroupHint}</span>
      <div class="row" style={{ gap: 10 }}>
        <PxButton text={s.cancel} kind="ghost" class="grow" onClick={() => setConfirmRemove(false)} />
        <PxButton text={s.removeN(toRemove.length)} kind="danger" class="grow" onClick={async () => {
          setConfirmRemove(false)
          done(await bulk(`/conversations/${convId}/rules/bulk`, { action: "remove", ids: toRemove.map((r) => r.id) }))
        }} />
      </div>
    </Sheet>}
    {sharing && <SharePicker shares={sharing} onClose={() => setSharing(null)} />}
    {editLook && c && <Sheet onClose={() => setEditLook(false)}>
      {/* look of the group: pixel banner, own banner image (also its picture in the chat list) */}
      <div class="t-title" style={{ fontSize: 20 }}>{s.groupLook}</div>
      <PxButton text={s.pickPixelBanner} kind="ghost" onClick={() => { setEditLook(false); setPickBanner(true) }} />
      <PxButton text={s.ownBannerImage} kind="ghost" onClick={async () => {
        setEditLook(false)
        const [f] = await pickFiles("image/*")
        if (!f) return
        const data = await shrinkImage(f)
        const m = await uploadMedia(data, data.type || "image/jpeg")
        if (m) await update({ banner_media: m.id })
      }} />
      {(c.banner_image || c.avatar) && <PxButton text={s.removeImages} kind="danger" onClick={async () => { setEditLook(false); await update({ banner_media: null, avatar_media: null }) }} />}
    </Sheet>}
    {openRule != null && <RuleDetailSheet ruleId={openRule} inGroup onClose={() => { setOpenRule(null); bump() }}
      onEdit={(d) => { setOpenRule(null); setEditing({ kind: d.kind, rule: d }) }} />}
    {pickBanner && c && <BannerPicker selected={c.banner} allowAuto={false} onClose={() => setPickBanner(false)} onPick={async (k) => { setPickBanner(false); await update({ banner: k }) }} />}
    {memberMenu && c && <Sheet onClose={() => setMemberMenu(null)}>
      <div class="row" style={{ gap: 12 }}>
        <Avatar name={memberMenu.display_name} url={memberMenu.avatar} size={44} />
        <span class="t-title" style={{ fontSize: 20 }}>{memberMenu.username}</span>
      </div>
      <PxButton text={s.viewProfile} kind="ghost" onClick={() => { const u = memberMenu; setMemberMenu(null); nav.push({ r: "user", username: u.username }) }} />
      {(() => {
        const admin = !!c.admins?.includes(memberMenu.username)
        return <PxButton text={admin ? s.removeAdmin : s.makeAdmin} kind={admin ? "danger" : "primary"} onClick={async () => {
          const u = memberMenu
          setMemberMenu(null)
          const r = await req<{ conversation: Conversation }>("PUT", `/conversations/${convId}/admins`, { username: u.username, admin: !admin })
          if (r) setConv(r.conversation)
        }} />
      })()}
      {/* report #56: admins remove members (asked once more) */}
      <PxButton text={confirmKick ? s.removeMemberAsk(memberMenu.username) : s.removeMember} kind="danger" onClick={async () => {
        if (!confirmKick) { setConfirmKick(true); return }
        const u = memberMenu
        setMemberMenu(null)
        const r = await req<{ conversation: Conversation }>("DELETE", `/conversations/${convId}/members/${encodeURIComponent(u.username)}`)
        if (r) setConv(r.conversation)
      }} />
    </Sheet>}
    {addMembers && <AddMembersSheet convId={convId} onClose={() => setAddMembers(false)} onAdded={(u) => { setAddMembers(false); setConv(u) }} />}
    {addRule === "menu" && <Sheet onClose={() => setAddRule(null)}>
      {/* ways into the group: follow a public rule (from its detail), create one, copy one of mine */}
      <div class="t-title" style={{ fontSize: 20 }}>{s.addGroupRule}</div>
      <span class="t-small">{s.followPublicFromRule}</span>
      {([[s.createGroupRule, s.createGroupRuleHint, "create"], [s.copyRule, s.copyRuleHint, "copy"]] as const).map(([t, hint, key]) =>
        <button key={key} class="col" style={{ borderRadius: 14, background: "var(--faint)", padding: 14, textAlign: "left" }}
          onClick={() => { if (key === "create") { setAddRule(null); setEditing("pick") } else setAddRule("copy") }}>
          <span class="t-strong">{t}</span><span class="t-small">{hint}</span>
        </button>)}
    </Sheet>}
    {addRule === "copy" && <Sheet onClose={() => setAddRule(null)}>
      {/* any of my rules, own or subscribed (rules it taps automatically are copied along) */}
      <div class="t-title" style={{ fontSize: 20 }}>{s.copyRule}</div>
      <span class="t-small">{s.copyRuleHint}</span>
      {myRules.length === 0 && <span class="t-small">{s.noRulesToCopy}</span>}
      <div class="col">{myRules.map((r) => <div key={r.id} class="row" style={{ padding: "8px 0", gap: 12, cursor: "pointer" }} onClick={async () => {
        if (await req("POST", `/conversations/${convId}/rules`, { copy_rule: r.id })) { showToast(s.copied); setAddRule(null); bump(); void refresh() }
      }}>
        <RuleIconBox icon={r.icon} size={40} />
        <div class="col grow">
          <span class="t-strong ellipsis">{r.name}</span>
          {(r.origin ?? "own") !== "own" && <span class="t-small ellipsis" style={{ fontSize: 12 }}>{s.origin(r.origin ?? "", r.origin_label ?? "")}</span>}
        </div>
        <span class="t-block" style={{ fontSize: 14, color: kindColor(r.kind) }}>{signedValue(r.kind, r.base)}</span>
      </div>)}</div>
    </Sheet>}
    {editing === "pick" && <Sheet onClose={() => setEditing(null)}>
      {/* a new group rule: accelerator or decelerator */}
      <div class="t-title" style={{ fontSize: 20 }}>{s.createGroupRule}</div>
      <div class="row" style={{ gap: 10 }}>
        <PxButton text={s.accTitle} kind="ghost" class="grow" onClick={() => setEditing({ kind: "acc", rule: null })} />
        <PxButton text={s.decTitle} kind="ghost" class="grow" onClick={() => setEditing({ kind: "dec", rule: null })} />
      </div>
    </Sheet>}
    {editing && editing !== "pick" && <Sheet onClose={() => setEditing(null)}>
      <RuleEditor rule={editing.rule} kind={editing.kind} onClose={() => setEditing(null)} title={s.groupRule}
        publicToggle={[s.groupPublic, s.groupRulePublicHint]}
        submit={async (body) => {
          const path = `/conversations/${convId}/rules` + (editing.rule ? `/${editing.rule.id}` : "")
          const ok = (await req(editing.rule ? "PUT" : "POST", path, body)) != null
          if (ok) { bump(); void refresh() }
          return ok
        }}
        onDelete={editing.rule ? async () => {
          const ok = await call("DELETE", `/conversations/${convId}/rules/${editing.rule!.id}`)
          if (ok) { bump(); void refresh() }
          return ok
        } : undefined} />
    </Sheet>}
  </div>
}

/** Add people to a group: same ranking as @mentions; only connected people who aren't members yet. */
function AddMembersSheet({ convId, onClose, onAdded }: { convId: number; onClose: () => void; onAdded: (c: Conversation) => void }) {
  const s = S.value
  const [query, setQuery] = useState("")
  const [users, setUsers] = useState<UserCard[]>([])
  const [picked, setPicked] = useState<UserCard[]>([])
  const [sending, setSending] = useState(false)
  useEffect(() => {
    const h = setTimeout(async () => {
      setUsers((await req<{ users: UserCard[] }>("GET", `/mentions?conv=${convId}&q=` + encodeURIComponent(query.trim().replace(/^@/, ""))))?.users ?? [])
    }, query ? 150 : 0)
    return () => clearTimeout(h)
  }, [query])
  // picked people stay visible even when the search no longer matches them
  const shown = [...picked, ...users.filter((u) => !picked.some((p) => p.username === u.username))]
  const ready = picked.length > 0 && !sending
  return <Sheet onClose={onClose}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.addMembers}</div>
    <PxTextField value={query} onInput={setQuery} placeholder={s.searchHint} />
    {shown.length === 0 && <span class="t-small" style={{ padding: "8px 0" }}>{query.trim() ? s.noUsers : s.noOneToAdd}</span>}
    <div class="col">{shown.map((u) => {
      const on = picked.some((p) => p.username === u.username)
      return <UserRow key={u.username} user={u} onClick={() => setPicked(on ? picked.filter((p) => p.username !== u.username) : [...picked, u])}
        trailing={<i style={{ width: 22, height: 22, borderRadius: 6, background: on ? "var(--lime)" : "var(--faint)" }} />} />
    })}</div>
    <PxButton text={picked.length ? s.addMembersCount(picked.length) : s.addMembers} style={{ opacity: ready ? 1 : .4 }} onClick={async () => {
      if (!ready) return
      setSending(true)
      const r = await req<{ conversation: Conversation }>("POST", `/conversations/${convId}/members`, { usernames: picked.map((u) => u.username) })
      if (r) onAdded(r.conversation)
      setSending(false)
    }} />
  </Sheet>
}

// ---------------------------------------------------------------- follow requests

export function FollowRequestsScreen() {
  const s = S.value
  const [users, setUsers] = useState<UserCard[] | null>(null)
  useEffect(() => { void req<{ users: UserCard[] }>("GET", "/follow-requests").then((r) => setUsers(r?.users ?? null)) }, [])
  const [t, sub] = s.empty("requests")
  return <div class="fill col">
    <TopBar title={s.followRequests} />
    <OfflineStrip />
    {!users ? (!online.value && <OfflineState />) : <div class="scroll" style={{ flex: 1 }}>
      {users.length === 0 && <EmptyHint title={t} sub={sub} icon="ui_lock" />}
      {users.map((u) => <UserRow key={u.username} user={u} onClick={() => nav.push({ r: "user", username: u.username })} trailing={<div class="row" style={{ gap: 6 }}>
        <SmallBtn text={s.accept} primary onClick={async () => { if (await call("POST", `/follow-requests/${u.username}/accept`)) setUsers(users.filter((x) => x !== u)) }} />
        <SmallBtn text={s.decline} onClick={async () => { if (await call("DELETE", `/follow-requests/${u.username}`)) setUsers(users.filter((x) => x !== u)) }} />
      </div>} />)}
    </div>}
  </div>
}

const SmallBtn = ({ text, primary, onClick }: { text: string; primary?: boolean; onClick: () => void }) =>
  <button class="t-button" onClick={(e) => { e.stopPropagation(); onClick() }} style={{ borderRadius: 12, padding: "9px 12px", background: primary ? "var(--text)" : "var(--faint)", color: primary ? "#000" : "var(--text)" }}>{text}</button>

