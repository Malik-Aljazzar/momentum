// Stories (24 h) and highlights (android/.../ui/Stories.kt): the row at the top of the feed, the full
// screen viewer, the profile picture's ring and the highlights on the profile.
import { signal } from "@preact/signals"
import { useEffect, useRef, useState } from "preact/hooks"
import { secondsSince } from "../format"
import { e2eState, sealMessage } from "../e2e"
import type { Conversation, Highlight, Story, StoryRow, UserCard } from "../model"
import { nav } from "../nav"
import { feedVersion, req, showToast, state } from "../store"
import { S } from "../strings"
import { Avatar, IconBtn, Label, LoadMore, PixelIcon, PxButton, PxTextField, RemoteImage, Sheet, UserRow, useUserPages } from "../ui/components"
import { storiesVersion } from "./Compose"
import { TriggerList } from "./media"
import { SharePicker } from "./shared"
import { useCoverLayer } from "../ui/video"

/** Quick reactions to a story: sent at once as a message to the author. */
const STORY_REACTIONS = ["❤️", "😂", "😮", "😢", "🔥", "👏"]

/** A story shared into a chat, opened from its card (404: gone or not visible any more). */
export async function openSharedStory(id: number) {
  const r = await req<{ story: Story }>("GET", `/stories/${id}`)
  if (r) storyViewer.value = { rows: [{ user: r.story.user, stories: [r.story], unseen: false }], row: 0 }
}

/** The open story viewer: whose stories (rows), starting at row; a highlight when opened from one. */
export const storyViewer = signal<{ rows: StoryRow[]; row: number; highlight?: Highlight; onChange?: () => void } | null>(null)

const IMAGE_MS = 5000

/** Who has stories (the feed's row): rings on profile pictures elsewhere, in the chats (report #67). */
export const storyRings = signal<StoryRow[]>([])
export function loadStoryRings() { void req<{ rows: StoryRow[] }>("GET", "/stories").then((r) => { if (r) storyRings.value = r.rows }) }
/** The stories of [username] while some are new to me (never mine), else null. */
export const unseenStories = (username?: string) => storyRings.value.find((r) => r.user.username === username && !r.user.is_me && r.unseen) ?? null
export function openStoriesOf(row: StoryRow) { storyViewer.value = { rows: [row], row: 0, onChange: loadStoryRings } }

/** Rendered once at the app's root. */
export function StoryHost() {
  const v = storyViewer.value
  return v ? <StoryViewer key={v.rows.map((r) => r.user.username).join() + v.row} {...v} onClose={() => { storyViewer.value = null; v.onChange?.() }} /> : null
}

/** A profile picture with the story ring: colourful while there's something new, grey once seen. */
export function StoryAvatar({ user, size, ring, onClick, plus }: {
  user: UserCard; size: number; ring: "new" | "seen" | null; onClick?: () => void; plus?: () => void
}) {
  const pad = ring ? 3 : 0
  return <div style={{ position: "relative", flex: "none", cursor: onClick ? "pointer" : undefined }} onClick={onClick}>
    <div class={ring === "new" ? "story-ring new" : ring === "seen" ? "story-ring seen" : undefined}
      style={{ padding: pad, borderRadius: size * .32 + pad }}>
      <div style={{ padding: ring ? 2 : 0, borderRadius: size * .3, background: "var(--bg)" }}>
        <Avatar name={user.display_name} url={user.avatar} size={size} />
      </div>
    </div>
    {plus && <button aria-label="new story" onClick={(e) => { e.stopPropagation(); plus() }} class="center t-block" style={{
      position: "absolute", right: -2, bottom: -2, width: 22, height: 22, borderRadius: 8, background: "var(--cyan)", color: "#000",
      fontSize: 16, border: "2px solid var(--bg)",
    }}>+</button>}
  </div>
}

/** The feed's top row: my story first (+ for a new one), then people with stories, new ones first. */
export function StoriesRow() {
  const s = S.value
  const me = state.value?.user
  const [rows, setRows] = useState<StoryRow[]>([])
  const load = () => { void req<{ rows: StoryRow[] }>("GET", "/stories").then((r) => { if (r) { setRows(r.rows); storyRings.value = r.rows } }) }
  useEffect(load, [storiesVersion.value, feedVersion.value])
  if (!me) return null
  const mine = rows.find((r) => r.user.is_me)
  const others = rows.filter((r) => !r.user.is_me)
  const open = (row: number) => storyViewer.value = { rows, row, onChange: load }
  const meCard: UserCard = { username: me.username, display_name: me.display_name, avatar: me.avatar, is_me: true }
  return <div class="row stories-row" style={{ overflowX: "auto", gap: 14, padding: "6px 14px 10px", flex: "none", scrollbarWidth: "none" }}>
    <div class="col" style={{ alignItems: "center", gap: 4, width: 68 }}>
      <StoryAvatar user={meCard} size={58} ring={mine ? "seen" : null} plus={() => nav.push({ r: "newStory" })}
        onClick={() => (mine ? open(rows.indexOf(mine)) : nav.push({ r: "newStory" }))} />
      <span class="t-small ellipsis" style={{ fontSize: 11, maxWidth: 68 }}>{s.yourStory}</span>
    </div>
    {others.map((r) => <div key={r.user.username} class="col story-bubble" style={{ alignItems: "center", gap: 4, width: 68 }}>
      <StoryAvatar user={r.user} size={58} ring={r.unseen ? "new" : "seen"} onClick={() => open(rows.indexOf(r))} />
      <span class="t-small ellipsis" style={{ fontSize: 11, maxWidth: 68, color: r.unseen ? "var(--text)" : undefined }}>{r.user.username}</span>
    </div>)}
  </div>
}

function StoryViewer({ rows, row: startRow, highlight, onClose }: { rows: StoryRow[]; row: number; highlight?: Highlight; onClose: () => void }) {
  const s = S.value
  useCoverLayer()                    // nothing under the story plays
  const [row, setRow] = useState(startRow)
  const first = rows[startRow]?.stories.findIndex((st) => !st.seen)
  const [i, setI] = useState(highlight ? 0 : Math.max(0, first ?? 0))
  const [progress, setProgress] = useState(0)
  const [paused, setPaused] = useState(false)
  const [menu, setMenu] = useState<"highlight" | "viewers" | "share" | null>(null)
  const [reply, setReply] = useState("")
  const [replyFocus, setReplyFocus] = useState(false)
  const holding = !!menu || replyFocus || reply !== ""      // typing an answer holds the story
  const [stories, setStories] = useState(rows.map((r) => r.stories))
  const video = useRef<HTMLVideoElement>(null)
  const audio = useRef<HTMLAudioElement>(null)
  const list = stories[row] ?? []
  const st: Story | undefined = list[i]
  const user = rows[row]?.user

  const next = () => {
    if (i + 1 < list.length) { setI(i + 1); return }
    if (!highlight && row + 1 < rows.length) { setRow(row + 1); setI(0); return }
    onClose()
  }
  const prev = () => {
    if (i > 0) { setI(i - 1); return }
    if (!highlight && row > 0) { setRow(row - 1); setI(Math.max(0, (stories[row - 1]?.length ?? 1) - 1)) }
  }
  // seen once shown; images run 5 s, videos as long as they are
  useEffect(() => {
    setProgress(0)
    if (!st) { onClose(); return }
    if (!st.mine && !st.seen) {
      void req("POST", `/stories/${st.id}/seen`)
      setStories((all) => all.map((l, r) => (r === row ? l.map((x) => (x.id === st.id ? { ...x, seen: true } : x)) : l)))
    }
    const a = audio.current
    if (a) { if (st.sound) { a.src = st.sound.url; a.currentTime = 0; void a.play().catch(() => {}) } else a.pause() }
  }, [row, i])
  useEffect(() => {
    if (!st || paused || holding) { video.current?.pause(); audio.current?.pause(); return }
    if (st.media.kind === "video") { void video.current?.play().catch(() => {}) }
    if (st.sound) void audio.current?.play().catch(() => {})
    const imageMs = (st.duration ?? IMAGE_MS / 1000) * 1000          // a photo's chosen length (report #39)
    const started = performance.now() - progress * imageMs
    let raf = 0
    const tick = () => {
      const v = video.current
      const p = st.media.kind === "video" && v && v.duration > 0 ? v.currentTime / v.duration : (performance.now() - started) / imageMs
      setProgress(Math.min(1, p))
      if (p >= 1) next(); else raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [row, i, paused, holding])

  if (!st || !user) return null
  const sec = secondsSince(st.at)
  const remove = async () => {
    if (!(await req("DELETE", `/stories/${st.id}`))) return
    showToast(s.deleteStory)
    setStories((all) => all.map((l, r) => (r === row ? l.filter((x) => x.id !== st.id) : l)))
    storiesVersion.value++
    if (list.length <= 1) onClose(); else if (i >= list.length - 1) setI(i - 1)
  }
  const answer = async (text: string) => {
    const t = text.trim()
    if (!t) return
    // the author's chat; encrypted: sent as a chat message with the story attached (the server can't read it)
    const conv = (await req<{ conversation: Conversation }>("POST", "/conversations/direct", { username: user.username }))?.conversation
    if (!conv) return
    if (conv.e2e && e2eState.value !== "ready") { showToast(s.e2eNeedsUnlock, true); return }
    const sent = conv.e2e
      ? await req("POST", `/conversations/${conv.id}/messages`, { ...(await sealMessage(conv.id, t)), share: { kind: "story", id: st.id } })
      : await req("POST", `/stories/${st.id}/reply`, { text: t })
    if (!sent) return
    setReply("")
    showToast(s.storyReplySent)
  }
  const fromHighlight = async () => {
    if (!highlight || !(await req("PUT", `/highlights/${highlight.id}`, { remove: [st.id] }))) return
    setStories((all) => all.map((l, r) => (r === row ? l.filter((x) => x.id !== st.id) : l)))
    if (list.length <= 1) onClose()
  }
  return <div class="fill story-viewer" role="dialog" aria-label="story" style={{ position: "fixed", zIndex: 83, background: "#000" }}>
    <div class="fill" onPointerDown={() => setPaused(true)} onPointerUp={(e) => {
      setPaused(false)
      const x = e.clientX / (e.currentTarget as HTMLElement).clientWidth
      if (x < .3) prev(); else next()
    }}>
      {st.media.kind === "video"
        ? <video key={st.id} ref={video} src={st.media.url} poster={st.media.thumb} playsInline muted={!!st.sound}
          style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "contain" }} />
        : <RemoteImage key={st.id} url={st.media.url} class="fill" fit="contain" fallback={<RemoteImage url={st.media.thumb} class="fill" fit="contain" />} />}
    </div>
    <audio ref={audio} loop />
    {/* one bar per story, the current one filling up */}
    <div class="row" style={{ position: "absolute", top: "calc(var(--safe-top, 0px) + 8px)", left: 8, right: 8, gap: 3 }}>
      {list.map((x, k) => <i key={x.id} style={{ flex: 1, height: 3, background: "rgba(255,255,255,0.3)" }}>
        <i style={{ display: "block", height: 3, background: "#fff", width: `${k < i ? 100 : k === i ? progress * 100 : 0}%` }} />
      </i>)}
    </div>
    <div class="row" style={{ position: "absolute", top: "calc(var(--safe-top, 0px) + 18px)", left: 12, right: 4, gap: 8 }}>
      <div class="row grow" style={{ gap: 8, cursor: "pointer" }} onClick={() => { onClose(); nav.push({ r: "user", username: user.username }) }}>
        <Avatar name={user.display_name} url={user.avatar} size={30} />
        <span class="t-strong" style={{ color: "#fff" }}>{highlight ? highlight.title : user.username}</span>
        <span class="t-small" style={{ color: "rgba(255,255,255,0.7)" }}>{sec != null ? s.timeAgo(sec) : ""}</span>
      </div>
      <IconBtn icon="ui_close" onClick={onClose} label="close" />
    </div>
    <TriggerList triggers={st.triggers} top={st.sound ? 112 : 92} />
    {st.sound && <span class="t-small ellipsis" style={{ position: "absolute", left: 14, top: "calc(var(--safe-top, 0px) + 60px)", color: "#fff", maxWidth: "60%" }}>♪ {st.sound.title}</span>}
    {/* others' stories: quick reactions, an answer (a message to the author), share it into a chat (report #33) */}
    {!st.mine && <div class="col story-reply" style={{ position: "absolute", left: 12, right: 12, bottom: "calc(var(--safe-bottom) + 12px)", gap: 10 }}>
      {st.can_reply && <div class="row" style={{ justifyContent: "space-evenly" }}>
        {STORY_REACTIONS.map((e) => <button key={e} aria-label={`react ${e}`} style={{ fontSize: 26, padding: 6, borderRadius: 10 }}
          onClick={() => void answer(e)}>{e}</button>)}
      </div>}
      <div class="row" style={{ gap: 8 }} onFocusCapture={() => setReplyFocus(true)} onBlurCapture={() => setReplyFocus(false)}>
        {st.can_reply ? <>
          <div class="grow"><PxTextField value={reply} onInput={(v) => setReply(v.slice(0, 2000))} user emoji placeholder={s.replyToStory} onDone={() => void answer(reply)} /></div>
          {reply.trim() && <IconBtn icon="ui_send" onClick={() => void answer(reply)} label="send reply" />}
        </> : <span class="grow" />}
        <IconBtn icon="ui_share" onClick={() => setMenu("share")} label="share story" />
      </div>
    </div>}
    {st.mine && <div class="row" style={{ position: "absolute", left: 12, right: 12, bottom: "calc(var(--safe-bottom) + 14px)", gap: 8 }}>
      <IconBtn icon="ui_share" onClick={() => setMenu("share")} label="share story" />
      {/* who saw it */}
      <button class="row story-views" aria-label="viewers" onClick={() => setMenu("viewers")}
        style={{ gap: 6, padding: "0 12px", borderRadius: 12, background: "rgba(10,12,20,0.62)", flex: "none" }}>
        <PixelIcon name="ui_eye" size={18} /><span class="t-block" style={{ fontSize: 13, color: "#fff" }}>{st.views ?? 0}</span>
      </button>
      {highlight ? <PxButton text={s.removeFromHighlight} kind="ghost" class="grow" onClick={fromHighlight} />
        : <PxButton text={s.addToHighlight} kind="ghost" class="grow" onClick={() => setMenu("highlight")} />}
      {!highlight && <PxButton text={s.delete} kind="danger" onClick={remove} />}
    </div>}
    {menu === "highlight" && <HighlightPicker story={st} onClose={() => setMenu(null)} />}
    {menu === "viewers" && <ViewersSheet story={st} onClose={() => setMenu(null)} />}
    {menu === "share" && <SharePicker shares={[{ kind: "story", id: st.id }]} onClose={() => setMenu(null)} />}
  </div>
}

/** Who saw my story, the latest first. */
function ViewersSheet({ story, onClose }: { story: Story; onClose: () => void }) {
  const s = S.value
  const pages = useUserPages(async (offset, limit) =>
    (await req<{ viewers: { user: UserCard }[] }>("GET", `/stories/${story.id}/viewers?offset=${offset}&limit=${limit}`))?.viewers.map((v) => v.user) ?? null, [story.id])
  return <Sheet onClose={onClose} label={s.seenBy}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.seenBy} · {story.views ?? 0}</div>
    {pages.users?.length === 0 ? <span class="t-small">{s.noViewsYet}</span> : <div class="col" style={{ maxHeight: 380, overflowY: "auto" }}>
      {(pages.users ?? []).map((u) => <UserRow key={u.username} user={u} onClick={() => { onClose(); storyViewer.value = null; nav.push({ r: "user", username: u.username }) }} />)}
      <LoadMore hasMore={pages.hasMore && !!pages.users?.length} load={pages.more} />
    </div>}
  </Sheet>
}

/** Put a story into one of my highlights, or a new one. */
function HighlightPicker({ story, onClose }: { story: Story; onClose: () => void }) {
  const s = S.value
  const me = state.value?.user.username ?? ""
  const [hls, setHls] = useState<Highlight[] | null>(null)
  const [title, setTitle] = useState("")
  useEffect(() => { void req<{ highlights: Highlight[] }>("GET", `/users/${me}/highlights`).then((r) => setHls(r?.highlights ?? [])) }, [])
  const done = () => { showToast(s.saved); onClose() }
  return <Sheet onClose={onClose} label={s.addToHighlight}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.addToHighlight}</div>
    <div class="row" style={{ overflowX: "auto", gap: 12 }}>
      {(hls ?? []).map((h) => <button key={h.id} class="col" style={{ alignItems: "center", gap: 4, width: 70 }}
        onClick={async () => { if (await req("PUT", `/highlights/${h.id}`, { add: [story.id] })) done() }}>
        <HighlightCircle h={h} />
        <span class="t-small ellipsis" style={{ fontSize: 11, maxWidth: 70 }}>{h.title}</span>
      </button>)}
    </div>
    <Label text={s.newHighlight} />
    <div class="row" style={{ gap: 8 }}>
      <div class="grow"><PxTextField value={title} onInput={(v) => setTitle(v.slice(0, 30))} placeholder={s.highlightName} user /></div>
      <PxButton text={s.done} onClick={async () => {
        if (title.trim() && await req("POST", "/highlights", { title: title.trim(), story_ids: [story.id] })) done()
      }} />
    </div>
  </Sheet>
}

function HighlightCircle({ h, size = 62 }: { h: Highlight; size?: number }) {
  return <div class="story-ring seen" style={{ padding: 2, borderRadius: size / 2 + 2 }}>
    <div style={{ width: size, height: size, borderRadius: size / 2, overflow: "hidden", background: "var(--faint)", border: "2px solid var(--bg)" }}>
      {h.cover && <RemoteImage url={h.cover} class="fill" style={{ position: "relative", width: "100%", height: "100%" }} />}
    </div>
  </div>
}

/** The highlights on a profile (circles under the bio); my own get a "new" circle. */
export function HighlightsRow({ username, mine }: { username: string; mine: boolean }) {
  const s = S.value
  const [hls, setHls] = useState<Highlight[]>([])
  const [creating, setCreating] = useState(false)
  const load = () => { void req<{ highlights: Highlight[] }>("GET", `/users/${username}/highlights`).then((r) => r && setHls(r.highlights)) }
  useEffect(load, [username, storiesVersion.value])
  if (!hls.length && !mine) return null
  return <div class="row highlights-row" style={{ overflowX: "auto", gap: 14, padding: "4px 0 12px", scrollbarWidth: "none" }}>
    {hls.map((h) => <button key={h.id} class="col" style={{ alignItems: "center", gap: 4, width: 70, flex: "none" }} onClick={() => {
      if (!h.stories.length) return
      storyViewer.value = { rows: [{ user: h.stories[0].user, stories: h.stories, unseen: false }], row: 0, highlight: h, onChange: load }
    }}>
      <HighlightCircle h={h} />
      <span class="t-small ellipsis" style={{ fontSize: 11, maxWidth: 70, color: "var(--text)" }}>{h.title}</span>
    </button>)}
    {mine && <button class="col" style={{ alignItems: "center", gap: 4, width: 70, flex: "none" }} onClick={() => setCreating(true)}>
      <div class="center t-block" style={{ width: 66, height: 66, borderRadius: 33, border: "2px dashed var(--line)", fontSize: 22, color: "var(--muted)" }}>+</div>
      <span class="t-small" style={{ fontSize: 11 }}>{s.newHighlight}</span>
    </button>}
    {creating && <NewHighlightSheet onClose={() => setCreating(false)} onDone={() => { setCreating(false); load() }} />}
  </div>
}

/** A new highlight: a name and stories from my archive. */
function NewHighlightSheet({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const s = S.value
  const [title, setTitle] = useState("")
  const [archive, setArchive] = useState<Story[] | null>(null)
  const [picked, setPicked] = useState<Set<number>>(new Set())
  useEffect(() => { void req<{ stories: Story[] }>("GET", "/stories/archive?limit=60").then((r) => setArchive(r?.stories ?? [])) }, [])
  return <Sheet onClose={onClose} label={s.newHighlight}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.highlights}</div>
    <PxTextField value={title} onInput={(v) => setTitle(v.slice(0, 30))} placeholder={s.highlightName} user />
    <Label text={s.pickStories} />
    {archive?.length === 0 ? <span class="t-small">{s.noStoriesYet}</span> :
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 4, maxHeight: 320, overflowY: "auto" }}>
        {(archive ?? []).map((st) => {
          const on = picked.has(st.id)
          return <button key={st.id} aria-label={`story ${st.id}`} onClick={() => { const n = new Set(picked); if (on) n.delete(st.id); else n.add(st.id); setPicked(n) }}
            style={{ position: "relative", aspectRatio: "9 / 16", borderRadius: 8, overflow: "hidden", outline: on ? "2px solid var(--cyan)" : undefined }}>
            <RemoteImage url={st.media.thumb} class="fill" />
            {on && <i class="center t-block" style={{ position: "absolute", top: 4, right: 4, width: 18, height: 18, borderRadius: 5, background: "var(--cyan)", color: "#000", fontSize: 11 }}>✓</i>}
          </button>
        })}
      </div>}
    <PxButton text={s.done} onClick={async () => {
      if (!title.trim()) { showToast(s.nameMissing, true); return }
      if (await req("POST", "/highlights", { title: title.trim(), story_ids: [...picked] })) onDone()
    }} />
  </Sheet>
}
