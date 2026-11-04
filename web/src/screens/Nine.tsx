// One of the Nine (android/.../ui/Nine.kt): the nine titles (server/nine.py) - their animated icons, the title
// screens (each with its own colour, animated background and pixel art), the rankings' "The Nine" tab and the
// Larper election.
import { useEffect, useState } from "preact/hooks"
import { NINE_COLORS } from "../gfx/banners"
import { pixelScale } from "../gfx/engine"
import { fmt } from "../format"
import type { ElectionState, NineResponse, NineTitle, UserCard } from "../model"
import { nav } from "../nav"
import { nineLore, online, req, showToast, state } from "../store"
import { lang, S } from "../strings"
import { Avatar, BannerBg, Label, OfflineState, PixelIcon, PxButton, PxTextField, TopBar } from "../ui/components"

/** The title's animated critter: NINE_PX square, NINE_FRAMES frames of NINE_FRAME_MS (pixelart/nine.py). */
export const NINE_PX = 20
export const NINE_FRAMES = 8
export const NINE_FRAME_MS = 120
export function NineIcon({ nkey, size = 32, style }: { nkey: string; size?: number; style?: preact.JSX.CSSProperties }) {
  const s = pixelScale(size / NINE_PX) / (window.devicePixelRatio || 1)
  const w = NINE_PX * s
  return <div class="pixel" aria-hidden style={{
    width: w, height: w, flex: "none", backgroundImage: `url(/app/sprites/nine_${nkey}/sheet.png)`,
    backgroundSize: `${w * NINE_FRAMES}px ${w}px`, backgroundRepeat: "no-repeat",
    animation: `vehicle ${NINE_FRAMES * NINE_FRAME_MS}ms steps(${NINE_FRAMES}) infinite`, ["--vw" as string]: `-${w * NINE_FRAMES}px`, ...style,
  }} />
}

const unit = (key: string, n: number) => S.value.nineUnit(key, fmt(n))

/** "12 Kommentare · Platz 3 weltweit" for me, or that nothing is counted yet. */
function Mine({ t }: { t: NineTitle }) {
  const s = S.value
  if (t.key === "larper") return null
  return <span class="t-small" style={{ color: "var(--text)" }}>{t.mine ? s.nineMine(unit(t.key, t.mine.value), t.mine.rank) : s.nineNotCounted}</span>
}

function useClock(maxMs: number) {
  const [sec, setSec] = useState(0)
  useEffect(() => {
    const start = performance.now()
    let id = 0
    const tick = (now: number) => { const t = (now - start) / 1000; setSec(t); if (t * 1000 < maxMs) id = requestAnimationFrame(tick) }
    id = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(id)
  }, [maxMs])
  return sec
}

const dateOf = (iso: string) => new Date(iso).toLocaleDateString(lang.value === "de" ? "de-DE" : "en-GB", { day: "numeric", month: "long" })

/** The title screen: its icon, name, the (tall) tale, how to get it, who holds it and where I stand. */
export function NineOverlay({ nkey, onClose }: { nkey: string; onClose: () => void }) {
  const s = S.value
  const color = NINE_COLORS[nkey] ?? "var(--yellow)"
  const [data, setData] = useState<NineResponse | null>(null)
  useEffect(() => { void req<NineResponse>("GET", "/nine").then((d) => setData(d)) }, [nkey])
  useEffect(() => { (document.activeElement as HTMLElement | null)?.blur?.() }, [])
  const sec = useClock(4000)
  const lore = s.nineLore(nkey)
  const shown = Math.max(0, Math.min(lore.length, Math.floor((sec - .4) / 2.6 * lore.length)))
  const t = data?.titles.find((x) => x.key === nkey)
  const e = data?.election
  const open = (u: UserCard) => { onClose(); nav.push({ r: "user", username: u.username }) }
  const keep = (ev: Event) => ev.stopPropagation()      // a tap in a card doesn't close
  // closes with the X (pinned: stays while the tale scrolls) or a tap on the free space, like the Hyperspace screen
  return <div class="fill lore nine-screen" role="dialog" aria-label={s.nineName(nkey)} style={{ zIndex: 82, background: "#000", ["--nine" as string]: color }} onClick={onClose}>
    <BannerBg bkey={`nine_${nkey}`} />
    <div class="fill col scroll" style={{ padding: "calc(var(--safe-top) + 14px) 22px calc(var(--safe-bottom) + 40px)", alignItems: "center", textAlign: "center", gap: 14 }}>
      <div class="row" style={{ width: "100%", alignItems: "center", minHeight: 44, paddingRight: 56 }}>
        <Label text={s.oneOfNine} color={color} />
      </div>
      <div style={{ height: 6 }} />
      <NineIcon nkey={nkey} size={160} />
      <div class="t-block" style={{ fontSize: 30, lineHeight: "36px", color }}>{s.nineName(nkey).toUpperCase()}</div>
      <div class="t-title nine-lore" style={{ fontSize: 19, lineHeight: "27px", minHeight: 108, width: "100%" }}>
        {lore.slice(0, shown)}<span style={{ visibility: "hidden" }}>{lore.slice(shown)}</span>
      </div>
      <div class="col nine-card" style={{ width: "100%", gap: 6, textAlign: "left" }} onClick={keep}>
        <Label text={s.nineHowTitle} color={color} />
        <span class="t-body">{s.nineHow(nkey)}</span>
      </div>
      <div class="col nine-card" style={{ width: "100%", gap: 8, textAlign: "left" }} onClick={keep}>
        <Label text={s.nineHolder} color={color} />
        {!data ? (!online.value && <OfflineState />) : t?.holder
          ? <div class="row" style={{ gap: 10, alignItems: "center", cursor: "pointer" }} onClick={() => open(t.holder!)}>
            <Avatar name={t.holder.display_name} url={t.holder.avatar} size={40} />
            <div class="col grow">
              <span class="t-strong ellipsis">{t.holder.username}</span>
              {t.value != null && <span class="t-small">{unit(nkey, t.value)}</span>}
            </div>
            <PixelIcon name="trophy" size={18} />
          </div>
          : <span class="t-body" style={{ color: "var(--muted)" }}>{s.nineNobody}</span>}
        {t && <Mine t={t} />}
        {nkey === "larper" && e && <>
          <span class="t-small" style={{ color: "var(--text)" }}>{e.open ? `${s.electionVotes(e.voters, e.needed)}${e.closes_at ? " · " + s.electionEnds(dateOf(e.closes_at)) : ""}` : e.next_at ? s.electionNext(dateOf(e.next_at)) : s.electionClosed}</span>
          {e.open && <PxButton text={s.electionOpenAction} onClick={() => { onClose(); nav.selectTab("election") }} />}
        </>}
      </div>
    </div>
    <button class="t-button nine-close" aria-label="close" onClick={(ev) => { ev.stopPropagation(); onClose() }}><PixelIcon name="ui_close" size={20} /></button>
  </div>
}

/** The rankings' tab: all nine titles, who holds them, where I stand; a tap opens the title screen. */
export function NineTab() {
  const s = S.value
  const [data, setData] = useState<NineResponse | null>(null)
  useEffect(() => { void req<NineResponse>("GET", "/nine").then((d) => setData(d)) }, [])
  if (!data) return !online.value ? <OfflineState /> : null
  return <div class="scroll" style={{ flex: 1, paddingBottom: "calc(var(--tabbar) + 24px)" }}>
    {data.titles.map((t) => <div key={t.key} class="row list-item" role="button" aria-label={s.nineName(t.key)} onClick={() => { nineLore.value = t.key }}
      style={{ padding: "10px 16px", gap: 12, cursor: "pointer", alignItems: "center" }}>
      <NineIcon nkey={t.key} size={40} />
      <div class="col grow" style={{ gap: 2 }}>
        <span class="t-strong" style={{ color: NINE_COLORS[t.key] }}>{s.nineName(t.key)}</span>
        {t.holder ? <div class="row" style={{ gap: 6, alignItems: "center" }}>
          <Avatar name={t.holder.display_name} url={t.holder.avatar} size={18} />
          <span class="t-small ellipsis" style={{ color: "var(--text)" }}>{t.holder.username}{t.value != null ? ` · ${unit(t.key, t.value)}` : ""}</span>
        </div> : <span class="t-small">{s.nineNobody}</span>}
        <Mine t={t} />
      </div>
      <PixelIcon name="ui_back" size={14} alpha={.5} style={{ transform: "scaleX(-1)" }} />
    </div>)}
  </div>
}

/** The Larper election: the running one (vote, who leads, my vote) or when the next comes. */
export function ElectionTab() {
  const s = S.value
  const [e, setE] = useState<ElectionState | null>(null)
  const [q, setQ] = useState("")
  const [hits, setHits] = useState<UserCard[]>([])
  useEffect(() => { void req<{ election: ElectionState }>("GET", "/nine/election").then((r) => setE(r?.election ?? null)) }, [])
  useEffect(() => {
    const term = q.trim()
    if (!term) { setHits([]); return }
    const h = setTimeout(() => { void req<{ users: UserCard[] }>("GET", `/users/search?q=${encodeURIComponent(term)}&limit=20`).then((r) => setHits(r?.users ?? [])) }, 300)
    return () => clearTimeout(h)
  }, [q])
  const vote = async (u: UserCard) => {
    const r = await req<{ election: ElectionState }>("POST", "/nine/election/vote", { username: u.username })
    if (r) { setE(r.election); showToast(s.electionVoted) }
  }
  if (!e) return !online.value ? <OfflineState /> : null
  const me = state.value?.user.username
  const row = (u: UserCard, right: preact.ComponentChildren) => <div key={u.username} class="row list-item" style={{ padding: "8px 16px", gap: 10, alignItems: "center" }}>
    <Avatar name={u.display_name} url={u.avatar} size={36} />
    <span class="t-strong ellipsis grow" style={{ cursor: "pointer" }} onClick={() => nav.push({ r: "user", username: u.username })}>{u.username}</span>
    {right}
  </div>
  return <div class="scroll col" style={{ flex: 1, paddingBottom: "calc(var(--tabbar) + 24px)" }}>
    <div class="col" style={{ margin: "8px 16px", borderRadius: "var(--card-radius)", background: "linear-gradient(135deg, #2a1a3b, #15121f)", padding: 16, gap: 8 }}>
      <div class="row" style={{ gap: 10, alignItems: "center" }}>
        <NineIcon nkey="larper" size={40} />
        <span class="t-block" style={{ fontSize: 20, color: NINE_COLORS.larper }}>{s.nineName("larper")}</span>
      </div>
      {e.open ? <>
        <span class="t-body">{s.electionIntro}</span>
        <span class="t-small" style={{ color: "var(--text)" }}>{s.electionVotes(e.voters, e.needed)}{e.closes_at ? ` · ${s.electionEnds(dateOf(e.closes_at))}` : ""}</span>
        {e.my_vote && <Label text={s.electionMyVote(e.my_vote.username)} color={NINE_COLORS.larper} />}
      </> : <>
        <span class="t-body">{s.electionClosed}</span>
        {e.next_at && <span class="t-small" style={{ color: "var(--text)" }}>{s.electionNext(dateOf(e.next_at))}</span>}
      </>}
    </div>
    {e.larper && <>
      <Label text={s.electionCurrentLarper} style={{ padding: "6px 16px 2px" }} />
      {row(e.larper, <NineIcon nkey="larper" size={24} />)}
    </>}
    {e.open && <>
      <div style={{ padding: "8px 16px 4px" }}><PxTextField value={q} onInput={setQ} placeholder={s.electionSearch} /></div>
      {hits.filter((u) => u.username !== me).map((u) => row(u, <PxButton text={e.my_vote?.username === u.username ? s.electionVoted : s.electionVote}
        kind={e.my_vote?.username === u.username ? "ghost" : "primary"} onClick={() => void vote(u)} />))}
      {e.top.length > 0 && <>
        <Label text={s.electionLeading} style={{ padding: "10px 16px 2px" }} />
        {e.top.map((c) => row(c.user, <span class="t-block" style={{ fontSize: 16 }}>{c.votes}</span>))}
      </>}
    </>}
  </div>
}

/** The election's own tab (only there while it runs: nav.tabsNow). */
export function ElectionScreen() {
  const s = S.value
  return <div class="fill col">
    <TopBar title={s.electionTitle} onBack={null} />
    <ElectionTab />
  </div>
}

/** The home tab's hint while the election is on. */
export function ElectionCard() {
  const s = S.value
  if (!state.value?.user.election) return null
  return <button class="row election-card" onClick={() => nav.selectTab("election")} style={{
    gap: 12, padding: 14, borderRadius: 16, background: "var(--card)", border: `1px solid ${NINE_COLORS.larper}`, textAlign: "left", width: "100%", alignItems: "center",
  }}>
    <NineIcon nkey="larper" size={40} />
    <div class="col grow" style={{ gap: 6 }}>
      <span class="t-small" style={{ color: "var(--text)" }}>{s.electionOpenHint}</span>
      <span class="t-button" style={{ color: NINE_COLORS.larper }}>{s.electionOpenAction}</span>
    </div>
  </button>
}
