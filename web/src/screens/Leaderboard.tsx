// Leaderboard (android/.../ui/Leaderboard.kt): world top 10 with my place, friends, public groups.
import { useEffect, useState } from "preact/hooks"
import { fmt, fmtShort } from "../format"
import { signal } from "@preact/signals"
import type { GroupRank, LeaderboardResponse, RankEntry } from "../model"
import { nav } from "../nav"
import { online, req } from "../store"
import { NineTab } from "./Nine"
import { S } from "../strings"
import { Avatar, BannerBg, EmptyHint, Label, LoadMore, OfflineState, OfflineStrip, PixelIcon, TopBar } from "../ui/components"

/** Ranked by the momentum, or by what changed today since 0:00 Berlin (reports #40, #45); kept while switching
 *  scopes. */
export const rankPeriod = signal<"total" | "24h">("total")
const periodQ = () => (rankPeriod.value === "24h" ? "&period=today" : "")

/** "+120" / "−40" for the 24 h ranking. */
export const signed = (n: number) => (n >= 0 ? "+" : "−") + fmtShort(Math.abs(n))

/** Total | 24 h */
export function PeriodSwitch() {
  const s = S.value
  return <div class="row period-switch" style={{ margin: "0 16px 6px", gap: 6 }}>
    {([["total", s.periodTotal], ["24h", s.period24h]] as const).map(([k, label]) => <button key={k} class="t-button" onClick={() => { rankPeriod.value = k }}
      aria-pressed={rankPeriod.value === k} style={{
        borderRadius: 10, padding: "6px 12px", background: rankPeriod.value === k ? "var(--yellow)" : "var(--faint)",
        color: rankPeriod.value === k ? "#000" : "var(--text)", fontSize: 12,
      }}>{label}</button>)}
  </div>
}

type Scope = "global" | "friends" | "groups" | "nine"

export function LeaderboardScreen({ initial }: { initial?: Scope }) {
  const s = S.value
  const [scope, setScope] = useState<Scope>(initial ?? "global")
  const period = rankPeriod.value
  const [data, setData] = useState<LeaderboardResponse | null>(null)
  const [more, setMore] = useState(false)
  useEffect(() => {
    setData(null)
    if (scope === "groups" || scope === "nine") return
    void req<LeaderboardResponse>("GET", `/leaderboard?scope=${scope}&limit=50${periodQ()}`).then((d) => {
      setData(d)
      setMore(scope === "friends" && (d?.entries.length ?? 0) >= 50)
    })
  }, [scope, period])
  const loadMore = async () => {
    if (!data) return
    const r = await req<LeaderboardResponse>("GET", `/leaderboard?scope=friends&limit=50&offset=${data.entries.length}${periodQ()}`)
    if (r) {
      const seen = new Set(data.entries.map((e) => e.user.username))
      setData({ ...data, entries: [...data.entries, ...r.entries.filter((e) => !seen.has(e.user.username))] })
      setMore(r.entries.length >= 50)
    }
  }
  const d = data
  return <div class="fill col">
    <TopBar title={s.leaderboardTitle} />
    <OfflineStrip />
    <div class="row scope-row" style={{ margin: "6px 16px", borderRadius: 14, background: "var(--faint)", padding: 4, overflowX: "auto" }}>
      {([["global", s.scopeGlobal], ["friends", s.scopeFriends], ["groups", s.groupsTitle], ["nine", s.theNine]] as [Scope, string][]).map(([k, label]) =>
        <button key={k} class="t-button grow" onClick={() => setScope(k)} aria-pressed={k === scope} style={{
          borderRadius: 10, padding: "10px 8px", whiteSpace: "nowrap", background: k === scope ? "var(--text)" : "transparent", color: k === scope ? "#000" : "var(--text)",
        }}>{label}</button>)}
    </div>
    {scope !== "nine" && <PeriodSwitch />}
    {scope === "nine" ? <NineTab />
      : scope === "groups" ? <GroupLeaderboard key={period} /> : !d ? (!online.value && <OfflineState />) : <>
      {/* my place stays visible above the list */}
      {d.me && <div class="col" style={{ margin: "8px 16px", borderRadius: "var(--card-radius)", background: "linear-gradient(135deg, #3b2a12, #1b1520)", padding: 16 }}>
        <Label text={s.yourPlace} color="var(--yellow)" />
        <div class="row" style={{ alignItems: "flex-end", gap: 12 }}>
          {/* the pixel font has no "#" */}
          <span class="t-block" style={{ fontSize: 34, lineHeight: "38px" }}>{d.me.rank}.</span>
          <span class="t-body" style={{ paddingBottom: 6 }}>{d.me.delta != null ? `${signed(d.me.delta)} · ${s.period24h}` : `${fmt(d.me.momentum ?? 0)} Momentum`}</span>
        </div>
        <span class="t-small">{s.placeOf(d.me.rank, d.total || d.entries.length) + (d.me.competing === false ? " · " + s.paused : "")}</span>
      </div>}
      <div class="scroll" style={{ flex: 1, paddingBottom: "calc(var(--tabbar) + 24px)" }}>
        {d.entries.map((e) => <RankRow key={e.user.username} e={e} onClick={() => nav.push({ r: "user", username: e.user.username })} />)}
        <LoadMore hasMore={more} load={loadMore} />
      </div>
    </>}
  </div>
}

export function RankRow({ e, onClick }: { e: RankEntry; onClick: () => void }) {
  const s = S.value
  const u = e.user
  const competing = e.competing !== false
  return <div class="row list-item" onClick={onClick} style={{ padding: "8px 16px", gap: 10, cursor: "pointer", background: u.is_me ? "var(--faint)" : undefined }}>
    <span class="t-block" style={{ width: 36, fontSize: 16, color: e.rank <= 3 ? "var(--yellow)" : "var(--muted)", flex: "none" }}>{e.rank}</span>
    <Avatar name={u.display_name} url={u.avatar} size={36} />
    <div class="col grow">
      <span class="t-strong ellipsis">{u.username}</span>
      {!competing && <span class="t-small" style={{ fontSize: 12 }}>{s.paused}</span>}
    </div>
    {e.rank === 1 && <PixelIcon name="trophy" size={18} />}
    {e.delta != null
      ? <span class="t-block rank-delta" style={{ fontSize: 16, color: e.delta > 0 ? "var(--lime)" : e.delta < 0 ? "var(--orange)" : "var(--muted)" }}>{signed(e.delta)}</span>
      : <span class="t-block" style={{ fontSize: 16, color: competing ? "var(--text)" : "var(--muted)" }}>{fmtShort(e.momentum ?? 0)}</span>}
  </div>
}

/** Public groups worldwide, by the total momentum of their members; my groups stand out. */
function GroupLeaderboard() {
  const s = S.value
  const [groups, setGroups] = useState<GroupRank[] | null>(null)
  const [more, setMore] = useState(false)
  useEffect(() => {
    void req<{ groups: GroupRank[] }>("GET", `/leaderboard?scope=groups&limit=50${periodQ()}`).then((r) => { setGroups(r?.groups ?? null); setMore((r?.groups.length ?? 0) >= 50) })
  }, [])
  const loadMore = async () => {
    const r = await req<{ groups: GroupRank[] }>("GET", `/leaderboard?scope=groups&limit=50&offset=${groups?.length ?? 0}${periodQ()}`)
    if (r) { const seen = new Set((groups ?? []).map((g) => g.id)); setGroups([...(groups ?? []), ...r.groups.filter((g) => !seen.has(g.id))]); setMore(r.groups.length >= 50) }
  }
  if (!groups) return online.value ? null : <OfflineState />
  if (groups.length === 0) return <EmptyHint title={s.noPublicGroups} icon="ui_group" />
  return <div class="scroll" style={{ flex: 1, paddingBottom: "calc(var(--tabbar) + 24px)" }}>
    {groups.map((gr) => <div key={gr.id} class="row list-item" onClick={() => nav.push({ r: "group", id: gr.id })}
      style={{ padding: "8px 16px", gap: 10, cursor: "pointer", background: gr.member ? "var(--faint)" : undefined }}>
      <span class="t-block" style={{ width: 36, fontSize: 16, color: gr.rank <= 3 ? "var(--yellow)" : "var(--muted)", flex: "none" }}>{gr.rank}</span>
      <div style={{ position: "relative", width: 40, height: 40, borderRadius: 12, overflow: "hidden", flex: "none" }}><BannerBg bkey={gr.banner ?? "dusk"} still /></div>
      <div class="col grow">
        <span class="t-strong ellipsis">{gr.name}</span>
        <span class="t-small" style={{ fontSize: 12 }}>{s.members(gr.members ?? 0)}</span>
      </div>
      {gr.rank === 1 && <PixelIcon name="trophy" size={18} />}
      {gr.delta != null
        ? <span class="t-block" style={{ fontSize: 16, color: gr.delta > 0 ? "var(--lime)" : gr.delta < 0 ? "var(--orange)" : "var(--muted)" }}>{signed(gr.delta)}</span>
        : <span class="t-block" style={{ fontSize: 16 }}>{fmtShort(gr.total ?? 0)}</span>}
    </div>)}
    <LoadMore hasMore={more} load={loadMore} />
  </div>
}
