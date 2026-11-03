// Feed, posts, comments, new post (android/.../ui/Feed.kt, PostScreens.kt, Overlay.kt).
import { Fragment } from "preact"
import { useEffect, useRef, useState } from "preact/hooks"
import { fmt, secondsSince } from "../format"
import type { CommentItem, MediaItem, Post, TextOverlay, Trigger, UserCard } from "../model"
import { nav } from "../nav"
import { call, feedVersion, meta, online, req, state } from "../store"
import { S } from "../strings"
import {
  Avatar, EmptyFriends, IconBtn, LoadMore, MentionSuggestions, MentionText, OfflineState, OfflineStrip, PixelIcon,
  PxButton, PxTextField, RemoteImage, Sheet, TopBar, UserRow, useUserPages, usePress,
} from "../ui/components"
import { OVERLAY_COLORS, OverlayText, SoundArea, SoundLabel, VideoPlayer } from "../ui/video"
import { openViewer, TriggerBadges } from "./media"
import { StoriesRow } from "./Stories"
import { MilestoneTile, RuleDetailSheet, RuleIconBox, SharePicker } from "./shared"

export function FeedScreen() {
  const s = S.value
  const [creating, setCreating] = useState(false)          // "+": post or story?
  const [posts, setPosts] = useState<Post[]>([])
  const [following, setFollowing] = useState(-1)
  const [loading, setLoading] = useState(false)
  const [end, setEnd] = useState(false)
  const busy = useRef(false)
  const load = async (reset: boolean) => {
    if (busy.current) return
    busy.current = true
    setLoading(true)
    const before = reset ? null : posts.at(-1)?.id
    const r = await req<{ posts: Post[]; following: number }>("GET", "/feed" + (before ? `?before=${before}` : ""))
    if (r) {
      setPosts((old) => {
        const base = reset ? [] : old
        const seen = new Set(base.map((p) => p.id))
        return [...base, ...r.posts.filter((p) => !seen.has(p.id))]
      })
      setFollowing(r.following)
      setEnd(r.posts.length === 0)
    }
    setLoading(false)
    busy.current = false
  }
  useEffect(() => { void load(true) }, [feedVersion.value])
  const unread = state.value?.unread
  const update = (p: Post) => setPosts((list) => list.map((x) => (x.id === p.id ? p : x)))
  return <div class="fill col">
    <TopBar title={s.feed} onBack={null}>
      <IconBtn icon="ui_plus" onClick={() => setCreating(true)} label="new post" />
      <IconBtn icon="ui_heart" badge={unread?.notifications ?? 0} onClick={() => nav.push({ r: "notifications" })} label="activity" />
      <IconBtn icon="ui_cup" onClick={() => nav.push({ r: "leaderboard" })} label="leaderboard" />
    </TopBar>
    <OfflineStrip />
    <div style={{ position: "relative", flex: 1 }}>
      {creating && <CreateChoice onClose={() => setCreating(false)} />}
      <PullToRefresh onRefresh={() => load(true)}>
        {/* stories at the top (like Instagram): mine first, then people with new ones */}
        <StoriesRow />
        <button class="row" onClick={() => nav.push({ r: "search" })} style={{
          margin: "6px 16px", width: "calc(100% - 32px)", borderRadius: 14, background: "var(--faint)", padding: "12px 14px", gap: 10,
        }}>
          <PixelIcon name="ui_search" size={16} alpha={.55} />
          <span class="t-body" style={{ color: "var(--muted)" }}>{s.findPeople}</span>
        </button>
        {posts.length === 0 && !loading && !online.value ? <OfflineState />
          : posts.length === 0 && !loading && following >= 0 ? <EmptyFriends title={s.emptyFeed} sub={s.emptyFeedSub} /> : null}
        {posts.map((p) => <PostCard key={p.id} post={p} onChange={update} onDeleted={() => setPosts((l) => l.filter((x) => x.id !== p.id))} />)}
        <LoadMore hasMore={!end && posts.length > 0} load={() => load(false)} />
      </PullToRefresh>
    </div>
  </div>
}

/** Pull down at the top to reload (like PullToRefreshBox). */
function PullToRefresh({ onRefresh, children }: { onRefresh: () => Promise<void>; children: preact.ComponentChildren }) {
  const ref = useRef<HTMLDivElement>(null)
  const [pull, setPull] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  useEffect(() => {
    const el = ref.current!
    let y0 = -1
    const start = (e: TouchEvent) => { y0 = el.scrollTop <= 0 ? e.touches[0].clientY : -1 }
    const move = (e: TouchEvent) => { if (y0 >= 0) setPull(Math.max(0, Math.min(90, (e.touches[0].clientY - y0) / 2))) }
    const end = async () => {
      if (y0 < 0) return
      y0 = -1
      setPull((p) => {
        if (p > 60) { setRefreshing(true); void onRefresh().finally(() => setRefreshing(false)) }
        return 0
      })
    }
    el.addEventListener("touchstart", start, { passive: true })
    el.addEventListener("touchmove", move, { passive: true })
    el.addEventListener("touchend", end)
    return () => { el.removeEventListener("touchstart", start); el.removeEventListener("touchmove", move); el.removeEventListener("touchend", end) }
  }, [onRefresh])
  return <div ref={ref} class="fill scroll" style={{ paddingBottom: "calc(var(--tabbar) + 40px + var(--safe-bottom))" }}>
    {(pull > 0 || refreshing) && <div class="center" style={{ height: refreshing ? 40 : pull, overflow: "hidden" }}>
      <PixelIcon name="ui_down" size={20} style={{ transform: `rotate(${pull * 4}deg)`, animation: refreshing ? "spin 1s linear infinite" : undefined }} />
    </div>}
    {children}
  </div>
}

export function LikeHeart({ liked, size = 24, onToggle, onShowLikers }: { liked: boolean; size?: number; onToggle: () => void; onShowLikers: () => void }) {
  const press = usePress(onToggle, onShowLikers)
  return <button class="icon-btn" aria-label="like" {...press}><PixelIcon name={liked ? "heart" : "ui_heart"} size={size} /></button>
}

/** People who liked a post or comment (path like "/posts/3/likes"). */
export function LikersSheet({ path, onClose }: { path: string; onClose: () => void }) {
  const pages = useUserPages(async (offset, limit) => (await req<{ users: UserCard[] }>("GET", `${path}?offset=${offset}&limit=${limit}`))?.users ?? null, [path])
  return <Sheet onClose={onClose}>
    <div class="t-title" style={{ fontSize: 20 }}>{S.value.likedBy}</div>
    <div class="col">
      {(pages.users ?? []).map((u) => <UserRow key={u.username} user={u} onClick={() => { onClose(); nav.push({ r: "user", username: u.username }) }} />)}
      {pages.users?.length === 0 && <span class="t-small" style={{ padding: 20 }}>—</span>}
      <LoadMore hasMore={pages.hasMore && !!pages.users?.length} load={pages.more} />
    </div>
  </Sheet>
}

/** A rule shared in the feed: its icon, name and value big; a tap shows it (and lets others subscribe). */
function RuleTile({ rule, onOpen }: { rule: NonNullable<Post["rule"]>; onOpen: () => void }) {
  const acc = rule.kind === "acc"
  return <div class="col center feed-rule" onClick={onOpen} style={{
    margin: "4px 14px", padding: "26px 16px", gap: 12, borderRadius: "var(--card-radius)", cursor: "pointer",
    background: acc ? "linear-gradient(160deg, #1d3a2c, #13151d)" : "linear-gradient(160deg, #3a1d2a, #13151d)",
    border: `2px solid ${acc ? "rgba(167,240,112,0.35)" : "rgba(239,125,87,0.35)"}`,
  }}>
    <RuleIconBox icon={rule.icon} size={72} />
    <span class="t-title" style={{ fontSize: 22, textAlign: "center" }}>{rule.name}</span>
    <span class="t-block" style={{ fontSize: 26, color: acc ? "var(--lime)" : "var(--orange)" }}>{acc ? "+" : "−"}{fmt(rule.base)}</span>
  </div>
}

export function PostCard({ post, onChange, onDeleted, detail = false }: { post: Post; onChange: (p: Post) => void; onDeleted: () => void; detail?: boolean }) {
  const s = S.value
  const [menu, setMenu] = useState(false)
  const [likers, setLikers] = useState(false)
  const [sharing, setSharing] = useState(false)
  const [openRule, setOpenRule] = useState(false)
  const sec = secondsSince(post.at)
  const sound = post.sound
  const v = post.kind === "milestone" ? meta.value?.vehicles.find((x) => x.key === post.vehicle) : undefined
  // double tap on the picture likes the post (never unlikes) and a big heart pops up
  const [hearts, setHearts] = useState(0)
  const taps = useRef({ down: [0, 0], last: 0, at: [0, 0], open: 0 })
  const onDown = (e: PointerEvent) => { taps.current.down = [e.clientX, e.clientY] }
  const onUp = (e: PointerEvent) => {
    const t = taps.current
    if (Math.hypot(e.clientX - t.down[0], e.clientY - t.down[1]) > 10) return          // a swipe, not a tap
    if (e.timeStamp - t.last < 300 && Math.hypot(e.clientX - t.at[0], e.clientY - t.at[1]) < 40) {
      t.last = 0
      clearTimeout(t.open)
      setHearts((n) => n + 1)
      if (!post.liked) void req<{ post: Post }>("POST", `/posts/${post.id}/like`).then((r) => { if (r) onChange(r.post) })
    } else {
      t.last = e.timeStamp; t.at = [e.clientX, e.clientY]
      // a single tap on a photo / video opens the big view (after the double-tap time)
      const slide = (e.currentTarget as HTMLElement).querySelector<HTMLElement>("[data-pager]")
      const index = slide ? Math.round(slide.scrollLeft / Math.max(1, slide.clientWidth)) : 0
      if (post.media.length && !(e.target as HTMLElement).closest(".trigger-badges, button")) {
        t.open = window.setTimeout(() => openViewer(post.media.map((m) => ({ media: m, triggers: post.triggers?.filter((x) => (x.slide ?? 0) === post.media.indexOf(m)) })), index), 300)
      }
    }
  }
  // no content-visibility here: older Android Chrome left skipped cards blank inside the sideways tab pager
  return <article class="col" style={{ paddingBottom: 18 }}>
    <div class="row" style={{ padding: "8px 14px" }}>
      <div class="row grow" style={{ gap: 10, cursor: "pointer" }} onClick={() => nav.push({ r: "user", username: post.user.username })}>
        <Avatar name={post.user.display_name} url={post.user.avatar} size={36} />
        <div class="col">
          <span class="t-strong">{post.user.display_name}</span>
          {/* the place under the name (like Instagram), then the time */}
          {post.location && <span class="t-small ellipsis post-place" style={{ fontSize: 12, color: "var(--text)" }}>{post.location.name}</span>}
          <span class="t-small" style={{ fontSize: 12 }}>{sec != null ? s.timeAgo(sec) : ""}</span>
        </div>
      </div>
      {post.mine && <IconBtn icon="ui_dots" alpha={.7} onClick={() => setMenu(true)} label="menu" />}
    </div>
    <div class="dbltap" style={{ position: "relative" }} onPointerDownCapture={onDown} onPointerUpCapture={onUp}>
    {/* a sound over photos plays like a video's sound; videos play it instead of their own */}
    {post.kind === "rule" && post.rule ? <RuleTile rule={post.rule} onOpen={() => setOpenRule(true)} />
      : post.kind === "milestone" ? <div style={{ position: "relative", width: "100%", aspectRatio: "1.5" }}><MilestoneTile vehicle={post.vehicle ?? ""} /></div>
      : post.media.length > 0 && sound && !post.media.some((m) => m.kind === "video")
        ? <SoundArea url={sound.url}><MediaPager media={post.media} triggers={post.triggers} /></SoundArea>
        : post.media.length > 0 ? <MediaPager media={post.media} soundUrl={sound?.url} triggers={post.triggers} />
          : sound ? <SoundArea url={sound.url} style={{ margin: "6px 16px", borderRadius: 14, background: "var(--faint)", padding: 14 }}>
            <SoundLabel title={sound.title} />
          </SoundArea> : null}
    {hearts > 0 && <div key={hearts} class="big-heart"><PixelIcon name="heart" size={96} /></div>}
    </div>
    {sound && post.media.length > 0 && <SoundLabel title={sound.title} style={{ padding: "8px 16px 0" }} />}
    <div class="row" style={{ padding: "2px 6px" }}>
      <LikeHeart liked={post.liked} onShowLikers={() => setLikers(true)} onToggle={async () => {
        const r = await req<{ post: Post }>(post.liked ? "DELETE" : "POST", `/posts/${post.id}/like`)
        if (r) onChange(r.post)
      }} />
      <IconBtn icon="ui_comment" onClick={() => { if (!detail) nav.push({ r: "post", id: post.id }) }} label="comments" />
      <IconBtn icon="ui_share" alpha={.85} onClick={() => setSharing(true)} label="share" />
    </div>
    <div class="col" style={{ padding: "0 16px", gap: 4 }}>
      {post.likes > 0 && <span class="t-strong" style={{ cursor: "pointer" }} onClick={() => setLikers(true)}>{s.likes(post.likes)}</span>}
      {post.kind === "milestone" && <MentionText prefixBold={post.user.display_name} text={s.milestonePost(v ? s.vehicle(v.key, v.label) : "")} />}
      {post.kind === "rule" && post.rule && <MentionText prefixBold={post.user.display_name} text={post.rule.updated ? s.ruleUpdatedPost : s.ruleSharedPost} />}
      {post.text?.trim() && <MentionText prefixBold={post.kind === "milestone" ? null : post.user.display_name} text={post.text} />}
      {post.tags.length > 0 && <MentionText class="t-small" text={`${s.with} ` + post.tags.map((t) => "@" + t.username).join(", ")} />}
      {!detail && post.comments > 0 && <span class="t-small" style={{ cursor: "pointer" }} onClick={() => nav.push({ r: "post", id: post.id })}>{s.viewComments(post.comments)}</span>}
    </div>
    {menu && <Sheet onClose={() => setMenu(false)}>
      <PxButton text={s.deletePost} kind="danger" onClick={async () => {
        setMenu(false)
        if (await call("DELETE", `/posts/${post.id}`)) onDeleted()
      }} />
    </Sheet>}
    {likers && <LikersSheet path={`/posts/${post.id}/likes`} onClose={() => setLikers(false)} />}
    {openRule && post.rule && <RuleDetailSheet ruleId={post.rule.id} onClose={() => setOpenRule(false)} />}
    {sharing && <SharePicker shares={[{ kind: "post", id: post.id }]} onClose={() => setSharing(false)} />}
  </article>
}

export function MediaPager({ media, soundUrl, triggers }: { media: MediaItem[]; soundUrl?: string | null; triggers?: Trigger[] }) {
  const first = media[0]
  const ratio = first.width && first.height ? Math.max(.8, Math.min(1.91, first.width / first.height)) : 1
  const [page, setPage] = useState(0)
  const ref = useRef<HTMLDivElement>(null)
  return <div style={{ position: "relative", width: "100%", aspectRatio: String(ratio), background: "#0b0c12" }}>
    <div ref={ref} class="fill" data-pager onScroll={() => { const el = ref.current!; setPage(Math.round(el.scrollLeft / el.clientWidth)) }}
      style={{ display: "flex", overflowX: "auto", scrollSnapType: "x mandatory", scrollbarWidth: "none" }}>
      {media.map((m, i) => <div key={m.id} style={{ position: "relative", flex: "0 0 100%", height: "100%", scrollSnapAlign: "start" }}>
        {m.kind === "video" ? <VideoPlayer url={m.url} thumb={m.thumb} playing={page === i} soundUrl={soundUrl} />
          : <RemoteImage url={m.url} class="fill" fallback={<RemoteImage url={m.thumb} class="fill" />} />}
        {m.overlay && <OverlayText o={m.overlay} />}
        {/* the rules tapped with this slide: subtle, in the corner */}
        <TriggerBadges triggers={triggers?.filter((t) => (t.slide ?? 0) === i)} />
      </div>)}
    </div>
    {media.length > 1 && <div class="row" style={{ position: "absolute", left: 0, right: 0, bottom: 10, justifyContent: "center", gap: 5, pointerEvents: "none" }}>
      {media.map((_, i) => <i key={i} style={{ width: 6, height: 6, borderRadius: 1, background: i === page ? "var(--text)" : "rgba(244,244,244,0.35)" }} />)}
    </div>}
  </div>
}

// ---------------------------------------------------------------- post detail + comments

export function PostDetailScreen({ id }: { id: number }) {
  const s = S.value
  const [post, setPost] = useState<Post | null>(null)
  const [comment, setComment] = useState("")
  const [likers, setLikers] = useState<string | null>(null)
  const [selected, setSelected] = useState<number | null>(null)     // long-pressed comment: can be deleted
  const [replyTo, setReplyTo] = useState<CommentItem | null>(null)  // the comment being answered
  const [expanded, setExpanded] = useState<Set<number>>(new Set())  // threads with their replies shown
  const input = useRef<HTMLDivElement>(null)
  useEffect(() => { void req<{ post: Post }>("GET", `/posts/${id}`).then((r) => setPost(r?.post ?? null)) }, [id])
  const send = async () => {
    const text = comment.trim()
    if (!text) return
    const parent = replyTo
    const r = await req<{ post: Post }>("POST", `/posts/${id}/comments`, parent ? { text, parent_id: parent.id } : { text })
    if (r) {
      setPost(r.post); setComment(""); setReplyTo(null)
      // show the thread the reply landed in
      if (parent) setExpanded((e) => new Set(e).add(parent.parent_id ?? parent.id))
    }
  }
  const reply = (c: CommentItem) => {
    setReplyTo(c)
    const u = c.user.username
    if (u !== me?.username && !comment.includes(`@${u}`)) setComment(`@${u} ${comment}`)
    input.current?.querySelector<HTMLElement>("input, textarea")?.focus()
  }
  const row = (c: CommentItem) => <CommentRow key={c.id} c={c} selected={selected === c.id}
    onSelect={() => { if (c.can_delete) setSelected(c.id) }} onDeselect={() => setSelected(null)}
    onLikers={() => setLikers(`/comments/${c.id}/likes`)} onReply={() => reply(c)}
    onLike={async () => { const r = await req<{ post: Post }>(c.liked ? "DELETE" : "POST", `/comments/${c.id}/like`); if (r) setPost(r.post) }}
    onDelete={async () => { setSelected(null); const r = await req<{ post: Post }>("DELETE", `/comments/${c.id}`); if (r) setPost(r.post) }} />
  const list = post?.comment_list ?? []
  const me = state.value?.user
  return <div class="fill col">
    <TopBar title={s.comments} />
    <OfflineStrip />
    {!post ? (!online.value && <OfflineState />) : <>
      <div class="scroll" style={{ flex: 1 }}>
        <PostCard post={post} detail onChange={(p) => setPost({ ...p, comment_list: post.comment_list })} onDeleted={nav.pop} />
        {/* threads: top-level comments, their replies (folded until opened) below them */}
        {list.filter((c) => c.parent_id == null).map((top) => {
          const thread = list.filter((c) => c.parent_id === top.id)
          const open = expanded.has(top.id)
          return <Fragment key={top.id}>
            {row(top)}
            {open && thread.map(row)}
            {thread.length > 0 && <div class="t-small" style={{ fontSize: 12, padding: "2px 0 6px 58px", cursor: "pointer" }}
              onClick={() => setExpanded((e) => { const n = new Set(e); if (open) n.delete(top.id); else n.add(top.id); return n })}>
              {open ? s.hideReplies : s.viewReplies(thread.length)}</div>}
          </Fragment>
        })}
      </div>
      <div style={{ padding: "4px 12px" }}><MentionSuggestions text={comment} onPick={setComment} /></div>
      {replyTo && <div class="row" style={{ background: "var(--card)", padding: "6px 4px 0 12px" }}>
        <span class="t-small grow" style={{ fontSize: 12, color: "var(--yellow)" }}>{s.replyTo(replyTo.user.display_name)}</span>
        <IconBtn icon="ui_close" alpha={.6} onClick={() => setReplyTo(null)} label="cancel reply" />
      </div>}
      <div class="row" style={{ background: "var(--card)", padding: "8px 12px calc(var(--safe-bottom) + 8px)", gap: 10 }}>
        {me && <Avatar name={me.display_name} url={me.avatar} size={32} />}
        <div class="grow" ref={input}><PxTextField value={comment} onInput={setComment} user emoji placeholder={s.addComment} onDone={send} /></div>
        <IconBtn icon="ui_send" alpha={comment.trim() ? 1 : .3} onClick={send} label="send" />
      </div>
    </>}
    {likers && <LikersSheet path={likers} onClose={() => setLikers(null)} />}
  </div>
}

/** One comment: avatar, the username on its own line above the text, time + "reply", like heart.
 *  Replies are indented and get a smaller avatar. */
function CommentRow({ c, selected, onSelect, onDeselect, onLike, onLikers, onDelete, onReply }: {
  c: CommentItem; selected: boolean; onSelect: () => void; onDeselect: () => void
  onLike: () => void; onLikers: () => void; onDelete: () => void; onReply: () => void
}) {
  const s = S.value
  const press = usePress(onDeselect, onSelect)
  const sec = secondsSince(c.at)
  const reply = c.parent_id != null
  const profile = (e: Event) => { e.stopPropagation(); nav.push({ r: "user", username: c.user.username }) }
  const stop = { onPointerDown: (e: Event) => e.stopPropagation(), onPointerUp: (e: Event) => e.stopPropagation() }
  return <div class="row list-item comment" {...press} style={{ padding: `8px 16px 8px ${reply ? 58 : 16}px`, gap: 10, alignItems: "flex-start", background: selected ? "var(--faint)" : undefined }}>
    <div style={{ cursor: "pointer" }} onClick={profile}>
      <Avatar name={c.user.display_name} url={c.user.avatar} size={reply ? 24 : 32} />
    </div>
    <div class="col grow">
      <b class="t-small" style={{ fontSize: 13, fontWeight: 600, color: "var(--text)", cursor: "pointer" }} onClick={profile}>{c.user.display_name}</b>
      <MentionText text={c.text} />
      <div class="row" style={{ gap: 14 }}>
        <span class="t-small" style={{ fontSize: 12 }}>{sec != null ? s.timeAgo(sec) : ""}</span>
        <span class="t-small" {...stop} style={{ fontSize: 12, fontWeight: 600, cursor: "pointer" }}
          onClick={(e) => { e.stopPropagation(); onReply() }}>{s.reply}</span>
      </div>
    </div>
    <div class="col" style={{ alignItems: "center" }} onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}>
      <LikeHeart liked={!!c.liked} size={16} onToggle={onLike} onShowLikers={onLikers} />
      {(c.likes ?? 0) > 0 && <span class="t-small" style={{ fontSize: 11, cursor: "pointer" }} onClick={onLikers}>{c.likes}</span>}
    </div>
    {/* the delete button only after a long press on the comment */}
    {c.can_delete && selected && <div onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}>
      <IconBtn icon="ui_close" alpha={.8} onClick={onDelete} label="delete" />
    </div>}
  </div>
}

// ---------------------------------------------------------------- new post

/** The feed's "+": a post or a story? */
function CreateChoice({ onClose }: { onClose: () => void }) {
  const s = S.value
  const option = (icon: string, title: string, hint: string, label: string, go: () => void) =>
    <button class="row" aria-label={label} onClick={() => { onClose(); go() }} style={{
      gap: 14, padding: "14px 16px", borderRadius: 16, background: "var(--faint)", border: "1px solid var(--line)", textAlign: "left",
    }}>
      <PixelIcon name={icon} size={30} />
      <div class="col grow"><span class="t-strong">{title}</span><span class="t-small" style={{ fontSize: 12 }}>{hint}</span></div>
    </button>
  return <Sheet onClose={onClose} label={s.whatToCreate}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.whatToCreate}</div>
    {option("ui_grid", s.newPost, s.postHint, "create post", () => nav.push({ r: "newPost" }))}
    {option("camera", s.newStory, s.storyHint, "create story", () => nav.push({ r: "newStory" }))}
  </Sheet>
}

/** A song for a post's sound (Deezer): its 30 s preview comes through the server. */
export interface Song { id: number; title: string; artist?: string; cover?: string | null }

/** Songs for a post's sound: search, listen to a preview, pick one. */
export function SongSearch({ onPick }: { onPick: (s: Song) => void }) {
  const s = S.value
  const [q, setQ] = useState("")
  const [hits, setHits] = useState<Song[]>([])
  const [playing, setPlaying] = useState<number | null>(null)
  const audio = useRef<HTMLAudioElement>(null)
  useEffect(() => {
    const term = q.trim()
    if (term.length < 2) { setHits([]); return }
    const h = setTimeout(() => {                      // wait until typing pauses
      void req<{ songs: Song[] }>("GET", `/songs?q=${encodeURIComponent(term)}`).then((r) => setHits(r?.songs ?? []))
    }, 350)
    return () => clearTimeout(h)
  }, [q])
  useEffect(() => {
    const a = audio.current
    if (!a) return
    if (playing == null) { a.pause(); return }
    a.src = `/api/songs/${playing}/preview`
    void a.play().catch(() => setPlaying(null))
  }, [playing])
  return <>
    <PxTextField value={q} onInput={(v) => setQ(v.slice(0, 80))} placeholder={s.searchSong} user />
    <audio ref={audio} onEnded={() => setPlaying(null)} />
    <div class="col" style={{ maxHeight: 320, overflowY: "auto" }}>
      {hits.map((song) => <div key={song.id} class="row song-hit" style={{ gap: 10, padding: "6px 0" }}>
        <button class="row grow" style={{ gap: 10, textAlign: "left", minWidth: 0 }} onClick={() => { setPlaying(null); onPick(song) }}>
          <RemoteImage url={song.cover} style={{ width: 44, height: 44, borderRadius: 8, flex: "none" }} />
          <div class="col" style={{ minWidth: 0 }}>
            <span class="t-strong ellipsis">{song.title}</span>
            <span class="t-small ellipsis" style={{ fontSize: 12 }}>{song.artist}</span>
          </div>
        </button>
        <button aria-label="preview" onClick={() => setPlaying(playing === song.id ? null : song.id)}
          style={{ width: 36, height: 36, borderRadius: 18, background: "var(--card2)", flex: "none" }} class="center">
          <PixelIcon name={playing === song.id ? "ui_pause" : "ui_play"} size={16} />
        </button>
      </div>)}
    </div>
    {hits.length > 0 && <span class="t-small" style={{ fontSize: 11 }}>{s.songsByDeezer}</span>}
  </>
}

/** A hit of the place search (OpenStreetMap via the server). */
export interface Place { name: string; detail?: string; lat?: number | null; lon?: number | null }

/** Pick the post's place: search or just take the typed name. */
export function PlaceSheet({ onClose, onPick }: { onClose: () => void; onPick: (p: Place) => void }) {
  const s = S.value
  const [q, setQ] = useState("")
  const [hits, setHits] = useState<Place[]>([])
  useEffect(() => {
    const term = q.trim()
    if (term.length < 2) { setHits([]); return }
    const h = setTimeout(() => {                      // wait until typing pauses
      void req<{ places: Place[] }>("GET", `/places?q=${encodeURIComponent(term)}`).then((r) => setHits(r?.places ?? []))
    }, 350)
    return () => clearTimeout(h)
  }, [q])
  return <Sheet onClose={onClose} label={s.addLocation}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.addLocation}</div>
    <PxTextField value={q} onInput={(v) => setQ(v.slice(0, 80))} placeholder={s.searchPlace} user autoFocus />
    <div class="col" style={{ maxHeight: 380, overflowY: "auto" }}>
      {q.trim().length >= 2 && <button class="t-body" style={{ color: "var(--cyan)", textAlign: "left", padding: "12px 0" }}
        onClick={() => onPick({ name: q.trim() })}>{s.useTypedPlace(q.trim())}</button>}
      {hits.map((h, i) => <button key={i} class="col place-hit" style={{ textAlign: "left", padding: "10px 0", alignItems: "flex-start" }} onClick={() => onPick(h)}>
        <span class="t-strong">{h.name}</span>
        {h.detail && <span class="t-small" style={{ fontSize: 12 }}>{h.detail}</span>}
      </button>)}
    </div>
  </Sheet>
}

export interface Draft {
  key: number; file: File; video: boolean; preview: string | null; media: MediaItem | null; failed: boolean; overlay: TextOverlay | null
  /** while uploading: the part sent (0..1; 1 = the server's turn) - the tile's progress bar */
  progress?: number
}

export const SOUND_SECONDS = 30
export interface SoundDraft { blob: Blob; title: string; durationMs: number; startMs: number; endMs: number; url: string }

export async function soundDraft(blob: Blob, title: string): Promise<SoundDraft> {
  const url = URL.createObjectURL(blob)
  const durationMs = await new Promise<number>((resolve) => {
    const a = new Audio()
    a.preload = "metadata"
    a.onloadedmetadata = () => resolve(Number.isFinite(a.duration) ? a.duration * 1000 : 0)
    a.onerror = () => resolve(0)
    a.src = url
  })
  // unknown length (the browser can't read it): the first 30 s, like before - the server cuts what is there
  return { blob, title: title.slice(0, 60), durationMs, startMs: 0, endMs: durationMs > 0 ? Math.min(durationMs, SOUND_SECONDS * 1000) : SOUND_SECONDS * 1000, url }
}

/** Name, the part that is used and a preview. Posts: drag both ends (5-30 s). Stories: the part is as long as
 *  the story ([fixedMs]) and only slides. Song previews are 30 s long, so before a post's sound always started at
 *  the song's start. */
export function SoundEditor({ sd, onChange, onRemove, fixedMs }: {
  sd: SoundDraft; onChange: (s: SoundDraft) => void; onRemove: () => void; fixedMs?: number
}) {
  const s = S.value
  const audio = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  useEffect(() => {
    const a = audio.current!
    if (!playing) { a.pause(); return }
    a.currentTime = sd.startMs / 1000
    void a.play()
    const stop = () => { if (a.currentTime * 1000 >= sd.endMs) setPlaying(false) }
    a.addEventListener("timeupdate", stop)
    return () => a.removeEventListener("timeupdate", stop)
  }, [playing, sd.startMs, sd.endMs])
  const t = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, "0")}`
  return <div class="col sound-editor" style={{ borderRadius: 14, background: "var(--faint)", padding: 14, gap: 10 }}>
    <audio ref={audio} src={sd.url} />
    <div class="row" style={{ gap: 10 }}>
      <button class="center" aria-label={playing ? "pause" : "play"} onClick={() => setPlaying(!playing)}
        style={{ width: 36, height: 36, borderRadius: 18, background: "var(--card2)", flex: "none" }}>
        <PixelIcon name={playing ? "ui_pause" : "ui_play"} size={16} />
      </button>
      <div class="grow"><PxTextField value={sd.title} onInput={(v) => onChange({ ...sd, title: v.slice(0, 60) })} placeholder={s.soundTitle} /></div>
      <IconBtn icon="ui_close" alpha={.6} onClick={onRemove} label="remove" />
    </div>
    <span class="t-small">{s.soundPart(t(sd.startMs), t(sd.endMs))} · {Math.round((sd.endMs - sd.startMs) / 1000)} s</span>
    {sd.durationMs > 0 && <SoundRange sd={sd} fixedMs={fixedMs} onChange={(a, b) => { setPlaying(false); onChange({ ...sd, startMs: a, endMs: b }) }} />}
    <span class="t-small" style={{ fontSize: 11 }}>{fixedMs ? s.soundStoryHint : s.soundRangeHint}</span>
  </div>
}

export const SOUND_MIN_MS = 5000

/** Keeps a part inside the sound: [min, max] long (a story: exactly its length), within 0..duration. */
export function clampRange(durationMs: number, startMs: number, endMs: number, fixedMs?: number): [number, number] {
  const dur = Math.max(0, durationMs)
  if (fixedMs) {
    const len = Math.min(fixedMs, dur)
    const a = Math.max(0, Math.min(startMs, dur - len))
    return [Math.round(a), Math.round(a + len)]
  }
  const maxLen = Math.min(SOUND_SECONDS * 1000, dur)
  const minLen = Math.min(SOUND_MIN_MS, maxLen)
  let a = Math.max(0, Math.min(startMs, dur - minLen))
  let b = Math.max(a + minLen, Math.min(endMs, dur, a + maxLen))
  if (b - a > maxLen) a = b - maxLen
  return [Math.round(a), Math.round(b)]
}

/** The whole sound as a pixel bar, the used part lit: drag its ends (posts) or the part itself. */
function SoundRange({ sd, fixedMs, onChange }: { sd: SoundDraft; fixedMs?: number; onChange: (a: number, b: number) => void }) {
  const bar = useRef<HTMLDivElement>(null)
  const drag = useRef<{ mode: "start" | "end" | "move"; grab: number } | null>(null)
  const dur = sd.durationMs
  const msAt = (x: number) => { const r = bar.current!.getBoundingClientRect(); return Math.max(0, Math.min(1, (x - r.left) / r.width)) * dur }
  const pct = (ms: number) => `${(ms / dur) * 100}%`
  const down = (e: PointerEvent) => {
    bar.current!.setPointerCapture(e.pointerId)
    const ms = msAt(e.clientX)
    const w = bar.current!.getBoundingClientRect().width
    const near = (v: number) => Math.abs((ms - v) / dur) * w < 22
    const mode = !fixedMs && near(sd.startMs) ? "start" : !fixedMs && near(sd.endMs) ? "end" : "move"
    let grab = ms - sd.startMs
    if (mode === "move" && (ms < sd.startMs || ms > sd.endMs)) {        // a tap outside: the part jumps there
      grab = (sd.endMs - sd.startMs) / 2
      onChange(...clampRange(dur, ms - grab, ms - grab + (sd.endMs - sd.startMs), fixedMs))
    }
    drag.current = { mode, grab }
  }
  const move = (e: PointerEvent) => {
    const d = drag.current
    if (!d) return
    const ms = msAt(e.clientX)
    if (d.mode === "start") onChange(...clampRange(dur, Math.max(Math.min(ms, sd.endMs - SOUND_MIN_MS), sd.endMs - SOUND_SECONDS * 1000), sd.endMs))
    else if (d.mode === "end") onChange(...clampRange(dur, sd.startMs, Math.max(ms, sd.startMs + SOUND_MIN_MS)))
    else onChange(...clampRange(dur, ms - d.grab, ms - d.grab + (sd.endMs - sd.startMs), fixedMs))
  }
  // a pixel "waveform": fixed bars, so the bar has some texture to aim at
  const bars = Array.from({ length: 48 }, (_, i) => 30 + ((i * 37) % 11) * 6 + ((i * 13) % 5) * 4)
  return <div ref={bar} class="sound-range" role="slider" aria-label="sound part" aria-valuemin={0} aria-valuemax={dur} aria-valuenow={sd.startMs}
    onPointerDown={down} onPointerMove={move} onPointerUp={() => { drag.current = null }}
    style={{ position: "relative", height: 48, touchAction: "none", cursor: "pointer", background: "var(--card2)" }}>
    <div class="row" style={{ position: "absolute", inset: "6px 4px", gap: 2, alignItems: "center" }}>
      {bars.map((h, i) => {
        const at = ((i + .5) / bars.length) * dur
        const on = at >= sd.startMs && at <= sd.endMs
        return <i key={i} style={{ flex: 1, height: `${h}%`, background: on ? "var(--yellow)" : "var(--line)" }} />
      })}
    </div>
    {/* the used part: a frame, square handles on its ends (posts) */}
    <div style={{ position: "absolute", top: 0, bottom: 0, left: pct(sd.startMs), width: pct(sd.endMs - sd.startMs),
      border: "2px solid var(--yellow)", boxSizing: "border-box" }} />
    {!fixedMs && [sd.startMs, sd.endMs].map((v, i) => <div key={i} style={{ position: "absolute", top: 12, width: 10, height: 24,
      left: `calc(${pct(v)} - 5px)`, background: "var(--yellow)", border: "2px solid #000" }} />)}
  </div>
}

/** Everyone I follow or who follows me (for tagging, DMs and groups). */
export function ConnectedPeople({ context, children }: { context: string; children: (people: UserCard[]) => preact.ComponentChildren }) {
  const me = state.value?.user.username
  const [people, setPeople] = useState<UserCard[] | null>(null)
  useEffect(() => {
    if (!me) return
    void (async () => {
      const a = (await req<{ users: UserCard[] }>("GET", `/users/${me}/following`))?.users ?? []
      const b = (await req<{ users: UserCard[] }>("GET", `/users/${me}/followers`))?.users ?? []
      const seen = new Map<string, UserCard>()
      for (const u of [...a, ...b]) if (!seen.has(u.username)) seen.set(u.username, u)
      setPeople([...seen.values()].sort((x, y) => x.display_name.toLowerCase().localeCompare(y.display_name.toLowerCase())))
    })()
  }, [me])
  if (!people) return null
  if (people.length === 0) { const [t, sub] = S.value.empty(context); return <EmptyFriends title={t} sub={sub} /> }
  return <>{children(people)}</>
}

/** Write a text onto a photo / video: type it, drag it into place, pick colour and size. */
export function OverlayEditor({ image, ratio, initial, onClose, onDone }: {
  image: string | null; ratio: number; initial: TextOverlay | null; onClose: () => void; onDone: (o: TextOverlay | null) => void
}) {
  const s = S.value
  const [o, setO] = useState<TextOverlay>(initial ?? { text: "", x: .5, y: .5, color: "white", size: .07 })
  const area = useRef<HTMLDivElement>(null)
  const drag = (e: PointerEvent) => {
    if (!(e.buttons & 1) && e.pointerType === "mouse") return
    const b = area.current!.getBoundingClientRect()
    setO((x) => ({ ...x, x: Math.max(0, Math.min(1, (e.clientX - b.left) / b.width)), y: Math.max(0, Math.min(1, (e.clientY - b.top) / b.height)) }))
  }
  const r = Math.max(.5, Math.min(2, ratio))
  return <div class="fill col" style={{ background: "#000", paddingTop: "var(--safe-top)", paddingBottom: "var(--safe-bottom)" }}>
    <div class="row" style={{ padding: 8, gap: 8 }}>
      <IconBtn icon="ui_close" onClick={onClose} label="close" />
      <span class="t-title grow">{s.addText}</span>
      <button class="t-button" onClick={() => onDone(o.text.trim() ? o : null)} style={{ borderRadius: 12, background: "var(--text)", color: "#000", padding: "10px 16px" }}>{s.done}</button>
    </div>
    {/* the picture with the text on it: drag the text around */}
    <div class="center" style={{ flex: 1, minHeight: 0 }}>
      <div ref={area} onPointerDown={(e) => { area.current!.setPointerCapture(e.pointerId); drag(e) }} onPointerMove={(e) => { if (e.buttons || e.pointerType !== "mouse") drag(e) }}
        style={{ position: "relative", width: "100%", maxHeight: "100%", aspectRatio: String(r), touchAction: "none", overflow: "hidden" }}>
        {image && <img src={image} style={{ width: "100%", height: "100%", objectFit: "cover" }} />}
        {o.text.trim() && <OverlayText o={o} />}
      </div>
    </div>
    <div class="col" style={{ padding: 16, gap: 12 }}>
      <PxTextField value={o.text} onInput={(v) => setO({ ...o, text: v.slice(0, 120) })} placeholder={s.overlayHint} user emoji autoFocus />
      <div class="row" style={{ gap: 10 }}>
        {Object.entries(OVERLAY_COLORS).map(([key, c]) => <button key={key} aria-label={key} onClick={() => setO({ ...o, color: key })} style={{
          width: 30, height: 30, borderRadius: 15, background: c, boxShadow: o.color === key ? "0 0 0 3px var(--yellow)" : "0 0 0 1px rgba(255,255,255,0.33)",
        }} />)}
      </div>
      <input type="range" min={.03} max={.16} step={.005} value={o.size} style={{ accentColor: "var(--yellow)" }}
        onInput={(e) => setO({ ...o, size: Number((e.target as HTMLInputElement).value) })} />
    </div>
  </div>
}

