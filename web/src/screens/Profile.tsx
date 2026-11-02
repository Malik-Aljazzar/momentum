// Profiles, people lists, search, activity (android/.../ui/ProfileScreens.kt).
import { useEffect, useState } from "preact/hooks"
import { fmt, fmtVal, secondsSince, signed } from "../format"
import { activityText } from "../lines"
import { defaultBanner } from "../gfx/banners"
import type { GroupHit, HistoryItem, NotificationItem, Post, Profile, ProfileBadge, PublicRule, Story, UserCard } from "../model"
import { nav } from "../nav"
import { clearNotifications } from "../push"
import { meta, online, refresh, req, state } from "../store"
import { lang, S } from "../strings"
import {
  Avatar, BannerBg, EmptyFriends, EmptyHint, FitText, FollowButton, IconBtn, Label, LoadMore, MentionText, OfflineState, OfflineStrip,
  PixelIcon, PxButton, PxTextField, RemoteImage, ScoreLabel, SegBar, Sheet, TopBar, UserRow, useUserPages, VehicleSprite,
} from "../ui/components"
import { MilestoneTile, RuleDetailSheet, SharePicker, stamp } from "./shared"
import { DecayDetailSheet, DecayRow } from "./Decays"
import { HighlightsRow, StoryAvatar, storyViewer } from "./Stories"

/** Toggle following; returns the updated card. */
export async function toggleFollow(u: UserCard): Promise<UserCard | null> {
  const r = await req<{ profile: Profile }>(u.following ? "DELETE" : "POST", `/users/${u.username}/follow`)
  return r ? cardOf(r.profile) : null
}
const cardOf = (p: Profile): UserCard => ({
  username: p.username, display_name: p.display_name, avatar: p.avatar, vehicle: p.vehicle, is_me: p.is_me,
  following: p.following, follows_me: p.follows_me, friend: p.friend, private: p.private,
})

/** Profile tabs (like Instagram); the first one is the momentum card with the banner. */
type Section = "momentum" | "posts" | "rules" | "history"
const SECTIONS: Section[] = ["momentum", "posts", "rules", "history"]
const SECTION_ICONS: Record<Section, string> = { momentum: "ui_gauge", posts: "ui_grid", rules: "ui_rules", history: "ui_clock" }

export function ProfileScreen({ username, isTab }: { username: string; isTab: boolean }) {
  const s = S.value
  const [profile, setProfile] = useState<Profile | null>(null)
  const [posts, setPosts] = useState<Post[]>([])
  const [more, setMore] = useState(true)
  const [reload, setReload] = useState(0)
  const [section, setSection] = useState<Section>("momentum")
  const [openRule, setOpenRule] = useState<number | null>(null)
  const [openDecay, setOpenDecay] = useState<string | null>(null)
  const [sharing, setSharing] = useState(false)
  const me = state.value?.user
  useEffect(() => {
    void (async () => {
      setProfile((await req<{ profile: Profile }>("GET", `/users/${username}`))?.profile ?? null)
      const first = (await req<{ posts: Post[] }>("GET", `/users/${username}/posts?limit=30`))?.posts ?? []
      setPosts(first)
      setMore(first.length >= 30)
    })()
  }, [username, reload, me?.avatar, me?.bio, me?.banner, me?.username])
  // older posts as the grid is scrolled
  const loadMore = async () => {
    const older = (await req<{ posts: Post[] }>("GET", `/users/${username}/posts?limit=30&before=${posts.at(-1)?.id ?? 0}`))?.posts
    if (older) { const seen = new Set(posts.map((p) => p.id)); setPosts([...posts, ...older.filter((p) => !seen.has(p.id))]); setMore(older.length >= 30) }
  }
  const p = profile
  const [menu, setMenu] = useState(false)
  const [confirmBlock, setConfirmBlock] = useState(false)
  const [badge, setBadge] = useState<ProfileBadge | null>(null)      // tapped: why they have it
  const setBlock = async (on: boolean) => {
    const r = await req<{ profile: Profile }>(on ? "POST" : "DELETE", `/users/${username}/block`)
    if (r) { setProfile(r.profile); setReload((n) => n + 1) }
  }
  return <div class="fill col">
    <TopBar title={username} onBack={isTab ? null : nav.pop}>
      {p && <IconBtn icon="ui_share" alpha={.8} onClick={() => setSharing(true)} label="share" />}
      {p?.is_me && <IconBtn icon="ui_gear" onClick={() => nav.push({ r: "settings" })} label="settings" />}
      {p && !p.is_me && <IconBtn icon="ui_dots" alpha={.8} onClick={() => setMenu(true)} label="menu" />}
    </TopBar>
    {menu && p && <Sheet onClose={() => setMenu(false)}>
      <PxButton text={p.blocked ? s.unblockUser : s.blockUser} kind={p.blocked ? "ghost" : "danger"} onClick={() => {
        setMenu(false)
        if (p.blocked) void setBlock(false); else setConfirmBlock(true)
      }} />
    </Sheet>}
    {confirmBlock && p && <Sheet onClose={() => setConfirmBlock(false)}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.blockConfirm(p.username)}</div>
      <span class="t-small">{s.blockConfirmHint}</span>
      <PxButton text={s.blockUser} kind="danger" onClick={() => { setConfirmBlock(false); void setBlock(true) }} />
    </Sheet>}
    {badge && <Sheet onClose={() => setBadge(null)} label={s.reporterKing}>
      <div class="row" style={{ gap: 8, alignItems: "center" }}>
        <span style={{ fontSize: 22 }}>👑</span>
        <div class="t-title" style={{ fontSize: 20, color: "var(--yellow)" }}>{s.reporterKing}</div>
      </div>
      <div class="t-body">{s.reporterKingWhy(badge.count, badge.bugs, badge.features)}</div>
      <span class="t-small">{s.reporterKingHint}</span>
      <div class="row" style={{ gap: 16, marginTop: 8 }}>
        <Stat n={badge.bugs} label={s.reportKindBug} />
        <Stat n={badge.features} label={s.reportKindFeature} />
      </div>
    </Sheet>}
    <OfflineStrip />
    {!p ? (!online.value && <OfflineState />) : <div class="scroll" style={{ flex: 1, paddingBottom: "calc(var(--tabbar) + 40px + var(--safe-bottom))" }}>
      {/* like Instagram: the picture on the left, the numbers next to it, then name and bio */}
      <div class="col" style={{ padding: "0 16px" }}>
        <div class="row profile-head" style={{ padding: "6px 0 10px", gap: 4 }}>
          {/* the picture: my own starts a story (or shows the running one), others show theirs */}
          <StoryAvatar user={cardOf(p)} size={80} ring={p.story?.active ? (p.story.unseen ? "new" : "seen") : null}
            plus={p.is_me ? () => nav.push({ r: "newStory" }) : undefined}
            onClick={async () => {
              if (!p.story?.active) { if (p.is_me) nav.push({ r: "newStory" }); return }
              const r = await req<{ stories: Story[] }>("GET", `/users/${p.username}/stories`)
              if (r?.stories.length) storyViewer.value = { rows: [{ user: cardOf(p), stories: r.stories, unseen: !!p.story.unseen }], row: 0, onChange: () => setReload((n) => n + 1) }
            }} />
          <div class="row grow" style={{ justifyContent: "space-evenly" }}>
            <Stat n={p.posts} label={s.posts} />
            {/* private account: the numbers stay visible, the lists only for followers */}
            <Stat n={p.followers_count} label={s.followers} onClick={() => { if (!p.locked) nav.push({ r: "follows", username: p.username, followers: true }) }} />
            <Stat n={p.following_count} label={s.followingLabel} onClick={() => { if (!p.locked) nav.push({ r: "follows", username: p.username, followers: false }) }} />
          </div>
        </div>
        <div class="row" style={{ gap: 6, padding: "0 2px" }}>
          <span class="t-strong ellipsis">{p.display_name}</span>
          {p.private && <PixelIcon name="ui_lock" size={14} alpha={.7} />}
          {p.friend && <Label text={s.friend} color="var(--lime)" />}
          {/* the King of Reporters: tap = why */}
          {p.badges?.filter((b) => b.key === "reporter_king").map((b) => <button key={b.key} class="badge-btn" onClick={() => setBadge(b)} aria-label={s.reporterKing}>
            <Label text={"👑 " + s.reporterKing} color="var(--yellow)" />
          </button>)}
        </div>
        {p.bio.trim() && <div style={{ padding: "2px 4px 0 2px" }}><MentionText text={p.bio} /></div>}
        <div style={{ height: 12 }} />
        {!p.locked && <HighlightsRow username={p.username} mine={!!p.is_me} />}
        <div class="row" style={{ gap: 8 }}>
          {p.is_me ? <PxButton text={s.settingsTitle} kind="ghost" class="grow" onClick={() => nav.push({ r: "settings" })} />
            : p.blocked ? <PxButton text={s.unblockUser} kind="ghost" class="grow" onClick={() => setBlock(false)} /> : <>
            <div class="grow" style={{ display: "flex" }}>
              <FollowButton user={cardOf(p)} small={false} requested={p.requested} onToggle={async () => {
                // follow, or for private accounts: request / withdraw the request
                const r = await req<{ profile: Profile }>(p.following || p.requested ? "DELETE" : "POST", `/users/${p.username}/follow`)
                if (r) { setProfile(r.profile); setReload((n) => n + 1) }
              }} />
            </div>
            {(p.following || p.follows_me) && <PxButton text={s.message} kind="ghost" class="grow" onClick={async () => {
              const r = await req<{ conversation: { id: number } }>("POST", "/conversations/direct", { username: p.username })
              if (r) nav.push({ r: "chat", id: r.conversation.id })
            }} />}
          </>}
        </div>
        <div style={{ height: 14 }} />
      </div>
      {p.locked ? <>
        {/* private: the momentum stays visible, the rest only for followers */}
        {!p.blocked && <ProfileHero p={p} />}
        <div style={{ height: 1, background: "var(--line)" }} />
        {p.blocked ? <EmptyHint title={s.blockedTitle} sub={s.blockedSub} icon="ui_lock" />
          : <EmptyHint title={s.lockedTitle} sub={s.lockedSub} icon="ui_lock" />}
      </> : <>
        {/* posts / rules / history as tabs, like posts and reels on Instagram */}
        <div class="col" style={{ position: "sticky", top: 0, zIndex: 2, background: "var(--bg)" }}>
          <div class="row">{SECTIONS.map((sec) => <button key={sec} class="col grow" aria-label={sec}
            style={{ alignItems: "center", paddingTop: 12 }} onClick={() => setSection(sec)}>
            <PixelIcon name={SECTION_ICONS[sec]} size={20} alpha={sec === section ? 1 : .4} />
            <i style={{ marginTop: 10, height: 2, width: "100%", background: sec === section ? "var(--text)" : "transparent" }} />
          </button>)}</div>
          <div style={{ height: 1, background: "var(--line)" }} />
        </div>
        {section === "momentum" && <><div style={{ height: 12 }} /><ProfileHero p={p} /></>}
        {section === "posts" && (posts.length === 0 ? <EmptyHint title={s.noPosts} icon="ui_image" /> : <>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 2, padding: "0 1px" }}>
            {posts.map((post) => <PostTile key={post.id} post={post} onClick={() => nav.push({ r: "post", id: post.id })} />)}
          </div>
          <LoadMore hasMore={more} load={loadMore} />
        </>)}
        {section === "rules" && p.decay && <DecayRow d={p.decay} onClick={() => setOpenDecay(p.username)} />}
        {section === "rules" && (p.rules.length === 0 ? (!p.decay && <EmptyHint title={s.noPublicRules} sub={p.is_me ? s.noPublicRulesMine : undefined} icon="ui_rules" />)
          : p.rules.map((r) => <PublicRuleRow key={r.id} r={r} onClick={() => setOpenRule(r.id)} />))}
        {section === "history" && (p.history.length === 0
          ? <EmptyHint title={s.noHistory} sub={p.is_me && p.rules.length === 0 ? s.noPublicRulesMine : undefined} icon="ui_clock" />
          : p.history.map((h, i) => <HistoryRow key={i} e={h} />))}
      </>}
    </div>}
    {openRule != null && <RuleDetailSheet ruleId={openRule} onClose={() => setOpenRule(null)} />}
    {openDecay != null && <DecayDetailSheet dref={{ user: openDecay }} onClose={() => { setOpenDecay(null); setReload((n) => n + 1) }} />}
    {sharing && p && <SharePicker shares={[{ kind: "profile", username: p.username }]} onClose={() => setSharing(false)} />}
  </div>
}

/** Momentum is the centre of a profile: big number over the current vehicle's scene. */
function ProfileHero({ p }: { p: Profile }) {
  const s = S.value
  const vs = meta.value?.vehicles
  if (!vs) return null
  const idx = Math.max(0, Math.min(vs.length - 1, p.vehicle ?? 0))
  const v = vs[idx], next = vs[idx + 1]
  const land = v.category !== "air" && v.category !== "space"
  // road vehicles stand on the road, planes fly just above the landscape, spaceships stay low
  const lift = land ? 64 : v.category === "air" ? 44 : 20
  const competing = p.competing !== false
  const reached = p.reached_at ?? 0
  return <div class="col" style={{ padding: "0 16px" }}>
    <div class={competing ? "banner-card glow" : "banner-card"} style={{ position: "relative", height: 300, borderRadius: "var(--card-radius)", overflow: "hidden" }}>
      <BannerBg bkey={p.banner ?? defaultBanner(v.category)} groundDp={land ? 70 : 0} />
      <div class="col" style={{ position: "relative", padding: 20 }}>
        <ScoreLabel competing={competing} />
        <FitText text={fmt(p.momentum)} max={64} class="t-block" />
        <span class="t-small" style={{ color: "var(--text)" }}>{s.vehicle(v.key, v.label)} · {s.stage(idx + 1, vs.length)}</span>
        {!competing && <span class="t-small" style={{ fontSize: 12 }}>{s.rankedPaused(fmt(p.ranked_momentum ?? 0))}</span>}
      </div>
      <div style={{ position: "absolute", left: 0, right: 0, bottom: lift, display: "flex", justifyContent: "center" }}><VehicleSprite vkey={v.key} dpPerPixel={2.5} /></div>
    </div>
    {next && p.next_at != null && <>
      <div style={{ height: 10 }} />
      <SegBar fraction={p.next_at > reached ? (p.momentum - reached) / (p.next_at - reached) : 1} segments={24} />
      <span class="t-small" style={{ paddingTop: 6 }}>{s.toGo(fmt(p.next_at - p.momentum))} · {s.vehicle(next.key, next.label)}</span>
    </>}
    <div style={{ height: 6 }} />
  </div>
}

function Stat({ n, label, onClick }: { n: number; label: string; onClick?: () => void }) {
  return <button class="col" style={{ alignItems: "center", padding: 8, borderRadius: 12 }} onClick={onClick}>
    <span class="t-block" style={{ fontSize: 18 }}>{n}</span>
    <span class="t-small" style={{ fontSize: 12 }}>{label}</span>
  </button>
}

function PublicRuleRow({ r, onClick }: { r: PublicRule; onClick: () => void }) {
  const s = S.value
  const acc = r.kind === "acc"
  return <div class="row list-item" style={{ padding: "10px 16px", gap: 12, cursor: "pointer" }} onClick={onClick}>
    <div class="center" style={{ width: 44, height: 44, borderRadius: 14, background: "var(--faint)", flex: "none" }}><PixelIcon name={r.icon} size={24} /></div>
    <div class="col grow">
      <span class="t-strong ellipsis">{r.name}</span>
      <span class="t-small">{acc ? s.accTitle : s.decTitle}</span>
    </div>
    <span class="t-block" style={{ fontSize: 16, color: acc ? "var(--lime)" : "var(--orange)" }}>{(acc ? "+" : "−") + fmtVal(r.value)}</span>
  </div>
}

function HistoryRow({ e }: { e: HistoryItem }) {
  return <div class="row list-item" style={{ padding: "10px 16px", gap: 12 }}>
    <PixelIcon name={e.icon} size={24} />
    <div class="col grow">
      <span class="t-body ellipsis">{e.label}</span>
      <span class="t-small" style={{ fontSize: 12 }}>{stamp(e.at, lang.value)}</span>
    </div>
    <span class="t-block" style={{ fontSize: 14, color: e.delta >= 0 ? "var(--lime)" : "var(--orange)" }}>{signed(e.delta, fmt)}</span>
  </div>
}

/** Square grid tile: first photo/video frame, milestone scene, or text. */
export function PostTile({ post, onClick }: { post: Post; onClick: () => void }) {
  const first = post.media[0]
  return <div onClick={onClick} style={{ position: "relative", aspectRatio: "1", background: "var(--card2)", cursor: "pointer", overflow: "hidden" }}>
    {post.kind === "milestone" ? <MilestoneTile vehicle={post.vehicle ?? ""} dpPerPixel={1.5} />
      : first ? <>
        <RemoteImage url={first.thumb} class="fill" />
        {first.kind === "video" && <div style={{ position: "absolute", top: 6, right: 6 }}><PixelIcon name="ui_video" size={16} /></div>}
        {post.media.length > 1 && <div style={{ position: "absolute", top: 6, left: 6 }}><PixelIcon name="ui_feed" size={16} /></div>}
      </> : <div class="t-small" style={{ color: "var(--text)", padding: 10, overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 5, WebkitBoxOrient: "vertical" }}>{post.text}</div>}
  </div>
}

function PeopleList({ users, onChange, hasMore, more }: { users: UserCard[] | null; onChange: (u: UserCard) => void; hasMore: boolean; more: () => Promise<void> }) {
  return <div class="scroll" style={{ flex: 1 }}>
    {(users ?? []).map((u) => <UserRow key={u.username} user={u} onClick={() => nav.push({ r: "user", username: u.username })}
      trailing={<FollowButton user={u} onToggle={async () => { const c = await toggleFollow(u); if (c) onChange(c) }} />} />)}
    <LoadMore hasMore={hasMore && !!users?.length} load={more} />
  </div>
}

export function FollowListScreen({ username, followers }: { username: string; followers: boolean }) {
  const s = S.value
  const mine = state.value?.user.username === username
  const ctx = (followers ? "followers" : "following") + (mine ? "" : "Of")
  const path = followers ? "followers" : "following"
  const pages = useUserPages(async (offset, limit) => (await req<{ users: UserCard[] }>("GET", `/users/${username}/${path}?offset=${offset}&limit=${limit}`))?.users ?? null, [username, followers])
  const [t, sub] = s.empty(ctx, username)
  return <div class="fill col">
    <TopBar title={followers ? s.followers : s.followingLabel} />
    {pages.users?.length === 0 ? <EmptyFriends title={t} sub={sub} /> : <PeopleList users={pages.users} onChange={pages.update} hasMore={pages.hasMore} more={pages.more} />}
  </div>
}

/** The people I blocked (settings); their profile has "unblock". */
export function BlockedListScreen() {
  const s = S.value
  const pages = useUserPages(async (offset, limit) => (await req<{ users: UserCard[] }>("GET", `/blocks?offset=${offset}&limit=${limit}`))?.users ?? null, [])
  return <div class="fill col">
    <TopBar title={s.blockedAccounts} />
    {pages.users?.length === 0 ? <EmptyFriends title={s.blockedAccounts} sub={s.noBlocked} />
      : <PeopleList users={pages.users} onChange={pages.update} hasMore={pages.hasMore} more={pages.more} />}
  </div>
}

export function SearchScreen() {
  const s = S.value
  const [q, setQ] = useState("")
  const [term, setTerm] = useState("")
  const [groups, setGroups] = useState<GroupHit[]>([])
  useEffect(() => { const h = setTimeout(() => setTerm(q.trim()), 300); return () => clearTimeout(h) }, [q])     // debounce typing
  const pages = useUserPages(async (offset, limit) => term
    ? (await req<{ users: UserCard[] }>("GET", `/users/search?q=${encodeURIComponent(term)}&offset=${offset}&limit=${limit}`))?.users ?? null : [], [term])
  useEffect(() => {
    if (!term) { setGroups([]); return }
    void req<{ groups: GroupHit[] }>("GET", `/groups/search?q=${encodeURIComponent(term)}`).then((r) => setGroups(r?.groups ?? []))
  }, [term])
  return <div class="fill col">
    <TopBar title={s.findPeople} />
    {!online.value ? <OfflineState /> : <>
      <div style={{ padding: "4px 16px" }}><PxTextField value={q} onInput={setQ} placeholder={s.searchHint} autoFocus /></div>
      {groups.length > 0 && <>
        {/* public groups: a horizontal row above the people */}
        <Label text={s.groupsTitle} style={{ padding: "10px 16px 6px" }} />
        <div class="row scroll" style={{ overflowX: "auto", gap: 10, padding: "0 16px", flex: "none" }}>
          {groups.map((gr) => <div key={gr.id} onClick={() => nav.push({ r: "group", id: gr.id })} style={{
            position: "relative", width: 150, height: 96, borderRadius: 16, overflow: "hidden", flex: "none", cursor: "pointer",
          }}>
            <BannerBg bkey={gr.banner ?? "dusk"} still />
            <div class="fill" style={{ background: "rgba(0,0,0,0.33)" }} />
            <div class="col" style={{ position: "absolute", left: 10, right: 10, bottom: 10 }}>
              <span class="t-strong ellipsis">{gr.name}</span>
              <span class="t-small" style={{ fontSize: 11, color: "var(--text)" }}>{s.members(gr.members ?? 0) + (gr.open ? " · " + s.tagOpen : "")}</span>
            </div>
          </div>)}
        </div>
      </>}
      {term && pages.users?.length === 0 ? <EmptyHint title={s.noUsers} icon="ui_search" />
        : <PeopleList users={term ? pages.users : null} onChange={pages.update} hasMore={pages.hasMore} more={pages.more} />}
    </>}
  </div>
}

export function NotificationsScreen() {
  const s = S.value
  const [items, setItems] = useState<NotificationItem[] | null>(null)
  const [requests, setRequests] = useState(0)
  const [more, setMore] = useState(true)
  useEffect(() => {
    void req<{ items: NotificationItem[]; requests: number }>("GET", "/notifications?limit=30").then((r) => {
      if (r) {
        setItems(r.items); setRequests(r.requests); setMore(r.items.length >= 30)
        void clearNotifications((tag) => tag.startsWith("note-"))      // seen: its notifications go away
      }
      void refresh()     // clears the badge
    })
  }, [])
  const loadMore = async () => {
    const r = await req<{ items: NotificationItem[] }>("GET", `/notifications?limit=30&before=${items?.at(-1)?.id ?? 0}`)
    if (r) { const seen = new Set((items ?? []).map((n) => n.id)); setItems([...(items ?? []), ...r.items.filter((n) => !seen.has(n.id))]); setMore(r.items.length >= 30) }
  }
  return <div class="fill col">
    <TopBar title={s.activity} />
    <OfflineStrip />
    {!items ? (!online.value && <OfflineState />) : <div class="scroll" style={{ flex: 1 }}>
      {items.length === 0 && requests === 0 && <EmptyHint title={s.noActivity} icon="ui_heart" />}
      {requests > 0 && <div class="row" style={{ padding: "14px 16px", gap: 12, cursor: "pointer" }} onClick={() => nav.push({ r: "requests" })}>
        <div class="center" style={{ width: 40, height: 40, borderRadius: 20, background: "var(--faint)" }}><PixelIcon name="ui_lock" size={18} /></div>
        <div class="col grow"><span class="t-strong">{s.followRequests}</span><span class="t-small">{s.requestsCount(requests)}</span></div>
        <i style={{ width: 8, height: 8, background: "var(--orange)" }} />
      </div>}
      {items.map((n) => {
        const ch = n.challenge
        const sec = secondsSince(n.at)
        return <div key={n.id} class="row list-item" style={{ padding: "10px 16px", gap: 12, cursor: "pointer", background: n.read ? undefined : "var(--faint)" }}
          onClick={() => nav.push(ch ? { r: "challenge", id: ch.id } : n.kind === "request" ? { r: "requests" } : n.post_id != null ? { r: "post", id: n.post_id }
            : n.group ? { r: "group", id: n.group.id } : { r: "user", username: n.actor.username })}>
          <Avatar name={n.actor.display_name} url={n.actor.avatar} size={40} />
          <div class="col grow">
            {n.kind === "ch_end" && ch ? <span class="t-body">{s.challengeResult(ch.title, ch.mode === "ranking" ? ch.rank ?? null : null, fmt(ch.reward ?? 0))}</span>
              : n.kind === "ch_cancel" && ch ? <MentionText prefixBold={n.actor.display_name} text={s.challengeCancelled(ch.title)} />
                : <MentionText prefixBold={n.actor.display_name} text={activityText(s, n)} />}
            <span class="t-small" style={{ fontSize: 12 }}>{sec != null ? s.timeAgo(sec) : ""}</span>
          </div>
          {n.post_thumb && <RemoteImage url={n.post_thumb} style={{ width: 44, height: 44, borderRadius: 6, flex: "none" }} />}
        </div>
      })}
      <LoadMore hasMore={more} load={loadMore} />
    </div>}
  </div>
}
