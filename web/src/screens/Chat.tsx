// Chats (android/.../ui/Chat.kt + ShareCard from Community.kt): list, chat room with swipe to
// reply, read receipts, voice messages, shared rules/profiles/posts/challenges.
import { useEffect, useLayoutEffect, useRef, useState } from "preact/hooks"
import { clock, countdown, dayKey, dayLabel, minuteKey } from "../chattime"
import { secondsSince } from "../format"
import { chatLine } from "../lines"
import { api, ApiError } from "../api"
import {
  chatKeys, closeMessage, e2eState, keyWarnings, mediaUrl, openLast, openMessage, openMessages, recoveryPending, sealMedia, sealMessage, useMediaUrl,
  type MemberKey,
} from "../e2e"
import type { ChatMessage, Conversation, MediaItem, Reaction, Share, Typing, UserCard } from "../model"
import { nav, stack, tab } from "../nav"
import { clearNotifications } from "../push"
import { foreground, onError, online, refresh, req, showToast, state, uploadMedia } from "../store"
import { cache } from "../storage"
import { lang, S, type Strings } from "../strings"
import { photoSize } from "../chatphoto"
import {
  Avatar, EmptyFriends, IconBtn, Label, LoadMore, MentionSuggestions, MentionText, OfflineState, OfflineStrip, PixelIcon,
  PxButton, PxTextField, RemoteImage, Sheet, TopBar, usePress, UserRow,
} from "../ui/components"
import { pickFiles, securePhoto, shrinkImage } from "../ui/media"
import { AudioBubble, MicButton, RecordingIndicator } from "../ui/voice"
import { ChallengeMini } from "./Community"
import { ConnectedPeople } from "./Feed"
import { openViewer } from "./media"
import { loadStoryRings, openSharedStory, openStoriesOf, StoryAvatar, unseenStories } from "./Stories"
import { ConversationAvatar, ConversationName, conversationTitle, MilestoneTile, RuleDetailSheet, RuleIconBox } from "./shared"

const POLL_MS = 3000

/** One line for a message: its text, or what it contains. */
function previewOf(s: Strings, text: string | undefined, mediaKind?: string | null, shareKind?: string | null, hasMedia = false) {
  if (text?.trim()) return text
  if (mediaKind === "audio") return `♪ ${s.voiceMessage}`
  if (mediaKind || hasMedia) return `▣ ${s.photo}`
  if (shareKind === "rule") return `↗ ${s.sharedRule}`
  if (shareKind === "post") return `↗ ${s.sharedPost}`
  if (shareKind === "profile") return `↗ ${s.sharedProfile}`
  if (shareKind === "story") return `↗ ${s.sharedStory}`
  if (shareKind === "challenge") return `▲ ${s.challenge}`
  return ""
}


/** "anna schreibt…", "anna und ben schreiben…", "anna nimmt eine Sprachnachricht auf…" (report #32). */
export function typingLine(s: Strings, t: Typing[] | undefined): string | null {
  const audio = (t ?? []).filter((x) => x.kind === "audio")
  const text = (t ?? []).filter((x) => x.kind !== "audio")
  if (audio.length === 1) return s.recordingOne(audio[0].username)
  if (audio.length > 1) return s.recordingMany(audio.length)
  if (text.length === 1) return s.typingOne(text[0].username)
  if (text.length === 2) return s.typingTwo(text[0].username, text[1].username)
  if (text.length > 2) return s.typingMany(text.length)
  return null
}

/** The chat list: "schreibt…" / "nimmt Audio auf…" with the name. */
function typingShort(s: Strings, t: Typing[] | undefined): string | null {
  if (!t?.length) return null
  if (t.length > 1) return typingLine(s, t)
  return `${t[0].username} ${t[0].kind === "audio" ? s.recordingShort : s.typingShort}`
}

/** Images from a paste (clipboard, screenshots): report #31. */
function pastedImages(e: ClipboardEvent): File[] {
  return Array.from(e.clipboardData?.files ?? []).filter((f) => f.type.startsWith("image/"))
}

export function ChatsScreen({ isTab = false }: { isTab?: boolean }) {
  const s = S.value
  const [convs, setConvs] = useState<Conversation[] | null>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const keepTop = useRef(false)
  // the top chats whenever the list comes up (its tab, back from a chat: report #66)
  const onTop = !isTab || (tab.value === "chats" && stack.value.length === 0)
  useEffect(() => { if (!onTop) return; if (scroller.current) scroller.current.scrollTop = 0; loadStoryRings() }, [onTop])
  // a chat moving to the top stays in view while the list is at the top
  useLayoutEffect(() => { if (keepTop.current && scroller.current) scroller.current.scrollTop = 0 }, [convs])
  useEffect(() => {
    let alive = true
    const load = async () => {
      if (!foreground.value) return
      const r = await req<{ conversations: Conversation[] }>("GET", "/conversations")
      // encrypted last messages: their preview is opened here
      const list = r && await Promise.all(r.conversations.map(async (c) => (c.last ? { ...c, last: await openLast(c.id, c.last) } : c)))
      keepTop.current = (scroller.current?.scrollTop ?? 0) < 4
      if (list && alive) setConvs(list)
    }
    void load()
    const h = setInterval(load, POLL_MS * 3)
    return () => { alive = false; clearInterval(h) }
  }, [e2eState.value])
  const [et, esub] = s.empty("chats")
  return <div class="fill col">
    <TopBar title={s.chats} onBack={isTab ? null : nav.pop}>
      <IconBtn icon="ui_plus" onClick={() => nav.push({ r: "newChat" })} label="new chat" />
      <IconBtn icon="chat" onClick={() => nav.push({ r: "newGroup" })} label="new group" />
    </TopBar>
    <OfflineStrip />
    {!convs ? (!online.value && <OfflineState />) : <div ref={scroller} class="scroll chat-list" style={{ flex: 1, paddingBottom: isTab ? "calc(var(--tabbar) + 24px + var(--safe-bottom))" : undefined }}>
      {e2eState.value === "locked" && <E2ECard title={s.unlockCard} hint={s.unlockCardHint} onClick={() => nav.push({ r: "e2eUnlock" })} />}
      {e2eState.value === "ready" && recoveryPending.value && <E2ECard title={s.recoveryCard} hint={s.recoveryCardHint} onClick={() => nav.push({ r: "e2eRecovery" })} />}
      {convs.length === 0 && <EmptyFriends title={et} sub={esub} />}
      {convs.map((c) => {
        const last = c.last
        const who = last ? (last.mine ? `${s.you}: ` : c.kind === "group" ? `${last.sender}: ` : "") : ""
        const sec = last ? secondsSince(last.at) : null
        return <div key={c.id} class="row list-item" style={{ padding: "10px 16px", gap: 12, cursor: "pointer" }} onClick={() => nav.push({ r: "chat", id: c.id })}>
          <ChatListAvatar c={c} />
          <div class="col grow">
            <ConversationName c={c} />
            {typingShort(s, c.typing) ? <span class="t-small ellipsis" style={{ color: "var(--lime)" }}>{typingShort(s, c.typing)}</span>
              : last ? <span class={"t-small ellipsis user-text"} style={{ color: c.mentioned ? "var(--yellow)" : (c.unread ?? 0) > 0 ? "var(--text)" : undefined }}>
              {(c.mentioned ? s.mentionedPreview + " · " : "") + (last.kind ? chatLine(s, last.kind, last.mine ? null : last.sender ?? "", last.target_me ? null : last.target?.display_name ?? "")
                : who + (last.sealed ? "🔒 " + s.e2eNewMessage : previewOf(s, last.text, last.media_kind, last.share_kind, last.has_media))) + " · " + (sec != null ? s.timeAgo(sec) : "")}
            </span> : c.kind === "group" ? <span class="t-small">{s.members(c.members.length + 1)}</span> : null}
          </div>
          {/* the pixel font has no "@" */}
          {c.mentioned && <span style={{ borderRadius: 8, background: "var(--yellow)", padding: "2px 7px", font: "900 12px system-ui", color: "#000" }}>@</span>}
          {(c.unread ?? 0) > 0 && <span class="t-block" style={{ borderRadius: 8, background: "var(--orange)", padding: "2px 7px", fontSize: 11, color: "#000" }}>{c.unread}</span>}
        </div>
      })}
    </div>}
  </div>
}

export function ChatRoomScreen({ id }: { id: number }) {
  const s = S.value
  const [conv, setConv] = useState<Conversation | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [text, setText] = useState("")
  const [sendingPhoto, setSendingPhoto] = useState(false)
  const [readUpto, setReadUpto] = useState(0)
  const [openRule, setOpenRule] = useState<number | null>(null)
  const [recording, setRecording] = useState<number | null>(null)
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)
  const [flash, setFlash] = useState<number | null>(null)
  const [olderLeft, setOlderLeft] = useState(true)
  const list = useRef<HTMLDivElement>(null)
  const msgs = useRef<ChatMessage[]>([])
  msgs.current = messages
  const convRef = useRef<Conversation | null>(null)
  convRef.current = conv
  const cacheKey = `get:/conversations/${id}/messages`
  // encrypted: the members' keys (who can't read new messages yet, whose safety code changed)
  const [keys, setKeys] = useState<MemberKey[]>([])
  useEffect(() => {
    if (conv?.e2e && e2eState.value === "ready") void chatKeys(id).then(setKeys).catch(() => {})
  }, [conv?.e2e, e2eState.value, id])
  // unlocked meanwhile: open what couldn't be opened before
  useEffect(() => {
    if (e2eState.value !== "ready" || !msgs.current.some((m) => m.sealed)) return
    void openMessages(id, msgs.current).then((all) => { msgs.current = all; setMessages(all) })
  }, [e2eState.value])

  const atBottom = () => { const el = list.current; return !el || el.scrollTop > -40 }       // column-reverse: 0 = bottom
  const toBottom = () => list.current?.scrollTo({ top: 0, behavior: "smooth" })

  const poll = async () => {
    const after = msgs.current.at(-1)?.id
    const r = await req<{ messages: ChatMessage[]; read_upto: number; reactions?: Record<string, Reaction[]>; reacted_ids?: number[]; typing?: Typing[] }>(
      "GET", `/conversations/${id}/messages` + (after ? `?after=${after}` : ""))
    if (!r) return
    const incoming = await openMessages(id, r.messages)       // encrypted ones are opened on this device
    setTyping(r.typing ?? [])
    // reactions of the latest messages may have changed
    if (r.reacted_ids?.length) {
      const recent = new Set(r.reacted_ids)
      let changed = false
      const next = msgs.current.map((m) => {
        if (!recent.has(m.id)) return m
        const now = r.reactions?.[String(m.id)] ?? []
        if (JSON.stringify(now) === JSON.stringify(m.reactions ?? [])) return m
        changed = true
        return { ...m, reactions: now }
      })
      if (changed) { msgs.current = next; setMessages(next) }
    }
    void clearNotifications((tag) => tag === `chat-${id}`)   // read: its notifications go away
    setReadUpto((x) => Math.max(x, r.read_upto ?? 0))      // from the server or the cached chat
    const known = new Set(msgs.current.map((m) => m.id))
    const fresh = incoming.filter((m) => !known.has(m.id))
    if (fresh.length) {
      const stick = atBottom()
      const all = [...msgs.current, ...fresh]
      msgs.current = all
      setMessages(all)
      if (stick) requestAnimationFrame(toBottom)            // new messages pull the chat down
    }
    // opens offline like WhatsApp; encrypted messages are cached as they came (opened again on load)
    if (online.value) void cache.set(cacheKey, JSON.stringify({ messages: msgs.current.slice(-200).map(closeMessage), read_upto: r.read_upto }))
  }
  useEffect(() => {
    void req<{ conversation: Conversation }>("GET", `/conversations/${id}`).then((r) => setConv(r?.conversation ?? null))
    loadStoryRings()                                        // the pictures' rings: new stories (report #67)
    void poll()
    // in the background: no polling (it would mark messages as read -> no notification)
    const h = setInterval(() => { if (foreground.value) void poll() }, POLL_MS)
    return () => clearInterval(h)
  }, [id])

  const loadOlder = async () => {
    const first = msgs.current[0]?.id
    if (!first) return
    const r = await req<{ messages: ChatMessage[] }>("GET", `/conversations/${id}/messages?before=${first}`)
    if (!r) return
    const known = new Set(msgs.current.map((m) => m.id))
    setMessages([...(await openMessages(id, r.messages)).filter((m) => !known.has(m.id)), ...msgs.current])
    setOlderLeft(r.messages.length >= 50)
  }

  // encrypted chats: text and the media key go into the envelope, only members can open it (e2e.ts)
  const send = async (out: Outgoing, reply: ChatMessage | null = replyTo, retry = true): Promise<void> => {
    setReplyTo(null)
    if (!online.value) { showToast(s.needsInternet, true); return }
    // an answer to a swiped message quotes it
    const body: Record<string, unknown> = { ...(reply ? { reply_to: reply.id } : {}), ...(out.share ? { share: out.share } : {}),
      ...(out.media ? { media_id: out.media.id } : {}) }
    try {
      if (convRef.current?.e2e) {
        if (e2eState.value !== "ready") { showToast(s.e2eNeedsUnlock, true); return }
        Object.assign(body, await sealMessage(id, out.text ?? "", out.media?.key ? { key: out.media.key, mime: out.media.mime ?? "", kind: out.media.kind } : null))
      } else if (out.text) body.text = out.text
      const m = await openMessage(id, (await api.call<{ message: ChatMessage }>("POST", `/conversations/${id}/messages`, body)).message)
      if (!msgs.current.some((x) => x.id === m.id)) {
        const all = [...msgs.current, m]
        msgs.current = all
        setMessages(all)
        requestAnimationFrame(toBottom)
      }
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
      // the encryption was switched / a key or the members changed in the meantime: reload and send again
      if (e.status === 409 && retry) {
        const fresh = (await req<{ conversation: Conversation }>("GET", `/conversations/${id}`))?.conversation
        if (fresh) { convRef.current = fresh; setConv(fresh) }
        await chatKeys(id, true).catch(() => {})
        // a photo / voice message is uploaded encrypted or plain already: it goes again if the chat still matches
        if (!out.media || (fresh && !!fresh.e2e === !!out.media.key)) return send(out, reply, false)
      }
      onError(e)
    }
  }
  const sendText = () => {
    const t = text.trim()
    if (!t) return
    setText("")
    void send({ text: t })
  }
  const [queue, setQueue] = useState<QueuedPhoto[]>([])
  const addFiles = (files: File[]) =>
    setQueue((q) => [...q, ...files.slice(0, MAX_PHOTOS - q.length).map((file) => ({ file, url: URL.createObjectURL(file), caption: "" }))])
  // a pasted picture (a copied screenshot, ...) goes into the photo queue (report #31)
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const files = pastedImages(e)
      if (!files.length) return
      e.preventDefault()
      if (!online.value) { showToast(s.needsInternet, true); return }
      addFiles(files)
    }
    document.addEventListener("paste", onPaste)
    return () => document.removeEventListener("paste", onPaste)
  }, [])

  // the others see "X schreibt…" / "X nimmt eine Sprachnachricht auf…" (report #32): repeated while it lasts,
  // quietly (no error toasts for a hint)
  const [typing, setTyping] = useState<Typing[]>([])
  const ping = (kind: "text" | "audio" | null) => { void api.raw("POST", `/conversations/${id}/typing`, { kind }).catch(() => {}) }
  const typingSent = useRef(0)
  useEffect(() => {
    const now = Date.now()
    if (text.trim() && now - typingSent.current > 3000) { typingSent.current = now; ping("text") }
    else if (!text.trim() && typingSent.current) { typingSent.current = 0; ping(null) }
  }, [text])
  useEffect(() => {
    if (recording == null) return
    ping("audio")
    const h = setInterval(() => ping("audio"), 3000)
    return () => { clearInterval(h); ping(null) }
  }, [recording != null])
  const pickPhotos = async (camera = false) => {
    if (!online.value) { showToast(s.needsInternet, true); return }
    if (sendingPhoto) return
    addFiles(await pickFiles("image/*", !camera, camera ? "environment" : undefined))
  }
  const sendQueue = async () => {
    const items = queue
    setQueue([])
    setSendingPhoto(true)
    // one message per photo, in order, each with its own caption
    for (const it of items) {
      const caption = it.caption.trim()
      if (convRef.current?.e2e) {
        // encrypted here: the server can't shrink it, so it gets the picture and its small copy ready-made
        if (e2eState.value !== "ready") { showToast(s.e2eNeedsUnlock, true); break }
        const p = await securePhoto(it.file)
        if (!p) continue
        const sealed = await sealMedia(p.full, p.thumb)
        const m = await uploadMedia(sealed.full, "application/octet-stream", `/encrypted?kind=image&width=${p.width}&height=${p.height}`)
        const md = m && sealed.thumb ? await uploadMedia(sealed.thumb, "application/octet-stream", `/${m.id}/thumb`) : null
        if (md) await send({ text: caption, media: { ...md, key: sealed.key, mime: "image/jpeg" } })
      } else {
        const data = await shrinkImage(it.file)
        const m = await uploadMedia(data, data.type || "image/jpeg")
        if (m) await send({ media: m, text: caption })
      }
    }
    setSendingPhoto(false)
  }
  const jump = (qid: number) => {
    const el = list.current?.querySelector(`[data-msg="${qid}"]`)
    if (!el) return
    el.scrollIntoView({ behavior: "smooth", block: "center" })
    setFlash(qid)
    setTimeout(() => setFlash(null), 1200)
  }
  const c = conv
  const tz = state.value?.settings.timezone || "Europe/Berlin"
  const ends = chainEnds(photoGroups(messages), tz)
  return <div class="fill col">
    <TopBar title={c ? conversationTitle(c) : ""}>
      {c && <IconBtn icon={c.muted ? "ui_muted" : "ui_bell"} alpha={c.muted ? .55 : .9} label="mute" onClick={async () => {
        const r = await req<{ conversation: Conversation }>("PUT", `/conversations/${id}/mute`, { muted: !c.muted })
        if (r) { setConv(r.conversation); showToast(r.conversation.muted ? s.chatMuted : s.chatUnmuted); void refresh() }
      }} />}
      {c?.kind === "group" && <IconBtn icon="ui_group" onClick={() => nav.push({ r: "group", id })} label="group" />}
      {c?.kind === "direct" && <IconBtn icon="ui_dots" onClick={() => nav.push({ r: "chatInfo", id })} label="chat info" />}
    </TopBar>
    <OfflineStrip />
    {queue.length > 0 && <PhotoQueue queue={queue} onChange={setQueue} onAdd={pickPhotos} onSend={sendQueue} onClose={() => setQueue([])} />}
    {/* reversed: the newest message sits at the bottom, so the chat opens there */}
    <div ref={list} class="scroll" style={{ flex: 1, display: "flex", flexDirection: "column-reverse", padding: "8px 12px 14px", gap: 6 }}>
      {/* photos one person sent in a row: one collage (the newest message carries it) */}
      {/* a separator above each day's first message: today, yesterday, weekday, date (report #49) */}
      {withDays(photoGroups(messages), tz).reverse().map((it) => "day" in it
        ? <div key={`day-${it.day}`} class="center day-sep" style={{ flex: "none", padding: "8px 0 4px" }}>
          <span class="t-small" style={{ fontSize: 12, borderRadius: 10, background: "var(--card2)", padding: "3px 10px" }}>
            {dayLabel(it.at, tz, lang.value, new Date(), s.today, s.yesterday)}
          </span>
        </div>
        : it.m.kind ? <SystemLine key={it.m.id} m={it.m} />
        : <Bubble key={it.m.id} m={it.m} photos={it.photos} tz={tz} last={ends.has(it.m.id)} group={c?.kind === "group"} read={it.m.id <= readUpto} highlighted={flash === it.m.id}
          onOpenRule={setOpenRule} onReply={() => setReplyTo(it.m)} onQuote={jump} onReactions={(reactions) => {
            const next = msgs.current.map((x) => (x.id === it.m.id ? { ...x, reactions } : x))
            msgs.current = next
            setMessages(next)
          }} />)}
      <LoadMore hasMore={olderLeft && online.value && messages.length >= 50} load={loadOlder} />
      {c?.e2e && !(olderLeft && messages.length >= 50) && <E2ENote onClick={() => nav.push(c.kind === "group" ? { r: "group", id } : { r: "chatInfo", id })} />}
    </div>
    {c?.e2e && keys.some((k) => keyWarnings.value[k.user_id]) && <button class="row key-warning" onClick={() => nav.push(c.kind === "group" ? { r: "group", id } : { r: "chatInfo", id })}
      style={{ gap: 8, padding: "8px 16px", background: "rgba(255,205,117,0.12)", textAlign: "left" }}>
      <PixelIcon name="ui_lock" size={14} color="var(--yellow)" />
      <span class="t-small grow" style={{ color: "var(--yellow)" }}>{s.keyChanged(keys.filter((k) => keyWarnings.value[k.user_id]).map((k) => k.username).join(", "))}</span>
    </button>}
    {typingLine(s, typing) && <span class="t-small ellipsis typing-line" style={{ color: "var(--lime)", padding: "2px 16px" }}>{typingLine(s, typing)}</span>}
    <div style={{ padding: "0 12px" }}><MentionSuggestions text={text} scope={`&members_of=${id}`} onPick={setText} /></div>
    {replyTo && <div class="row" style={{ background: "var(--card)", padding: "8px 4px 0 12px", gap: 10 }}>
      {/* answering: the quoted message above the input field */}
      <i style={{ width: 3, height: 36, background: "var(--yellow)" }} />
      <div class="col grow">
        <span class="t-small" style={{ fontSize: 12, color: "var(--yellow)" }}>{s.replyingTo(replyTo.mine ? s.you : replyTo.user?.username ?? "")}</span>
        <span class="t-small ellipsis user-text" style={{ color: "var(--text)" }}>{previewOf(s, replyTo.text, replyTo.media?.kind, replyTo.share?.kind)}</span>
      </div>
      <IconBtn icon="ui_close" alpha={.6} onClick={() => setReplyTo(null)} label="cancel reply" />
    </div>}
    {c?.e2e && e2eState.value === "locked" ? <div class="col" style={{ background: "var(--card)", padding: "10px 12px calc(var(--safe-bottom) + 10px)" }}>
      <PxButton text={s.unlockTitle} style={{ width: "100%" }} onClick={() => nav.push({ r: "e2eUnlock" })} />
    </div> : c?.e2e && e2eState.value === "off" ? <div class="center" style={{ background: "var(--card)", padding: "14px 12px calc(var(--safe-bottom) + 14px)" }}>
      <span class="t-small">{s.e2eUnsupported}</span>
    </div> : <div class="row" style={{ background: "var(--card)", padding: "8px 8px calc(var(--safe-bottom) + 8px)" }}>
      <IconBtn icon="ui_image" alpha={sendingPhoto || !online.value ? .3 : .8} onClick={() => pickPhotos()} label="photo" />
      <div class="grow">
        {recording != null ? <RecordingIndicator since={recording} hint={s.releaseToSend} />
          : <PxTextField value={text} onInput={setText} user emoji placeholder={online.value ? s.typeMessage : s.offlineStale} onDone={sendText} />}
      </div>
      {/* voice message: hold to record, release to send */}
      {!text.trim() ? <MicButton onRecording={setRecording} onHint={showToast} onRecorded={async (b, seconds) => {
        if (convRef.current?.e2e) {
          if (e2eState.value !== "ready") { showToast(s.e2eNeedsUnlock, true); return }
          const sealed = await sealMedia(b)
          const m = await uploadMedia(sealed.full, "application/octet-stream", `/encrypted?kind=audio&duration=${seconds.toFixed(1)}`)
          if (m) await send({ media: { ...m, key: sealed.key, mime: b.type || "audio/mp4" } })
        } else {
          const m = await uploadMedia(b, b.type || "audio/mp4")
          if (m) await send({ media: m })
        }
      }} /> : <IconBtn icon="ui_send" onClick={sendText} label="send" />}
    </div>}
    {openRule != null && <RuleDetailSheet ruleId={openRule} onClose={() => setOpenRule(null)} />}
  </div>
}

/** What the room sends: a text, an uploaded photo / voice message (with its key when encrypted), a shared item. */
interface Outgoing { text?: string; media?: (MediaItem & { key?: string; mime?: string }) | null; share?: unknown }

/** A line from the chat itself: who switched the encryption. */
function SystemLine({ m }: { m: ChatMessage }) {
  const s = S.value
  return <div class="center system-line" style={{ flex: "none", padding: "6px 24px", gap: 6 }}>
    <span class="t-small" style={{ fontSize: 12, textAlign: "center", borderRadius: 10, background: "var(--card2)", padding: "4px 10px" }}>
      {chatLine(s, m.kind ?? "", m.mine ? null : m.user?.display_name ?? "", m.target_me ? null : m.target?.display_name ?? "")}
    </span>
  </div>
}

/** At the start of an encrypted chat (like WhatsApp): a tap shows the details. */
function E2ENote({ onClick }: { onClick: () => void }) {
  const s = S.value
  return <button class="center e2e-note" onClick={onClick} style={{ flex: "none", margin: "4px 20px 10px", gap: 8, borderRadius: 12, background: "rgba(255,205,117,0.1)", padding: "8px 12px" }}>
    <PixelIcon name="ui_lock" size={14} color="var(--yellow)" />
    <span class="t-small" style={{ fontSize: 12, color: "var(--yellow)", textAlign: "left" }}>{s.e2eChatNote}</span>
  </button>
}

/** A reminder above the chat list: save the recovery key / unlock this device. */
function E2ECard({ title, hint, onClick }: { title: string; hint: string; onClick: () => void }) {
  return <button class="row e2e-card" onClick={onClick} style={{ margin: "6px 16px 10px", gap: 12, borderRadius: 16, background: "var(--card)", border: "1px solid var(--yellow)", padding: 14, textAlign: "left", width: "calc(100% - 32px)" }}>
    <PixelIcon name="ui_lock" size={22} color="var(--yellow)" />
    <div class="col grow"><span class="t-strong">{title}</span><span class="t-small">{hint}</span></div>
  </button>
}

/** A chat photo (an encrypted one is opened here first). */
function ChatImage({ media, class: klass, style }: { media: MediaItem; class?: string; style?: preact.JSX.CSSProperties }) {
  return <RemoteImage url={useMediaUrl(media, "thumb")} class={klass} style={style} />
}

/** A voice message (an encrypted one is opened here first). */
function ChatAudio({ media }: { media: MediaItem }) {
  const url = useMediaUrl(media, "url")
  return url ? <AudioBubble url={url} duration={media.duration ?? 0} /> : <div style={{ width: 220, height: 52 }} />
}

/** A chat's picture in the list; a person with new stories gets the ring, a tap on it shows them (report #67). */
function ChatListAvatar({ c }: { c: Conversation }) {
  const ring = c.kind !== "group" ? unseenStories(c.members[0]?.username) : null
  if (!ring) return <ConversationAvatar c={c} size={52} />
  return <div class="chat-story" onClick={(e) => { e.stopPropagation(); openStoriesOf(ring) }}>
    <StoryAvatar user={ring.user} size={42} ring="new" />
  </div>
}

/** A reaction under a message: a tap is mine (again = taken back), holding shows who reacted. */
function ReactionChip({ r, onTap, onHold }: { r: Reaction; onTap: () => void; onHold: () => void }) {
  const press = usePress(onTap, onHold)
  return <button {...press} style={{ fontSize: 14, padding: "0 3px", color: r.mine ? "var(--yellow)" : "var(--text)", WebkitTouchCallout: "none", userSelect: "none" }}>
    {r.count > 1 ? `${r.emoji} ${r.count}` : r.emoji}
  </button>
}

/** Who reacted with what (report #63): everyone with their emoji, a tap opens the profile. */
function ReactorsSheet({ messageId, onClose }: { messageId: number; onClose: () => void }) {
  const s = S.value
  const [data, setData] = useState<{ reactions: { user: UserCard; emoji: string }[] } | null>(null)
  useEffect(() => { void req<{ reactions: { user: UserCard; emoji: string }[] }>("GET", `/messages/${messageId}/reactions`).then(setData) }, [messageId])
  return <Sheet onClose={onClose} label={s.reactions}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.reactions}</div>
    {data && <div class="col reactors" style={{ maxHeight: 380, overflowY: "auto" }}>
      {data.reactions.map((x) => <UserRow key={x.user.username} user={x.user} onClick={() => { onClose(); nav.push({ r: "user", username: x.user.username }) }}
        trailing={<span style={{ fontSize: 22 }}>{x.emoji}</span>} />)}
    </div>}
  </Sheet>
}

/** Who read one of my messages (report #44). */
function ReadersSheet({ messageId, onClose }: { messageId: number; onClose: () => void }) {
  const s = S.value
  const [data, setData] = useState<{ readers: UserCard[]; unread: number } | null>(null)
  useEffect(() => { void req<{ readers: UserCard[]; unread: number }>("GET", `/messages/${messageId}/readers`).then(setData) }, [messageId])
  return <Sheet onClose={onClose} label={s.readBy}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.readBy}</div>
    {data && <div class="col readers" style={{ maxHeight: 380, overflowY: "auto" }}>
      {data.readers.map((u) => <UserRow key={u.username} user={u} onClick={() => { onClose(); nav.push({ r: "user", username: u.username }) }} />)}
      {data.unread > 0 && <span class="t-small" style={{ padding: "8px 0" }}>{s.notReadYet(data.unread)}</span>}
    </div>}
  </Sheet>
}

/** The emojis on a long press (same list as the server); double tap = the first one. */
const REACTIONS = ["❤️", "😂", "😮", "😢", "🔥", "👍"]

const MAX_PHOTOS = 10

interface QueuedPhoto { file: File; url: string; caption: string }

const isPhoto = (m: ChatMessage) => m.media?.kind === "image" && !m.share

/** Messages for the list; photos the same person sent one after another become one group. */
export function photoGroups(messages: ChatMessage[]): { m: ChatMessage; photos: ChatMessage[] }[] {
  const out: { m: ChatMessage; photos: ChatMessage[] }[] = []
  for (const m of messages) {
    const last = out[out.length - 1]
    const same = last && isPhoto(m) && isPhoto(last.m) && !m.reply && !!last.m.mine === !!m.mine && last.m.user?.username === m.user?.username
    if (same) { last.photos.push(m); last.m = m } else out.push({ m, photos: isPhoto(m) ? [m] : [] })
  }
  return out
}

/** The message groups with a day marker before each new day (in the player's time zone). */
export function withDays<T extends { m: ChatMessage }>(groups: T[], tz: string): (T | { day: string; at: string })[] {
  const out: (T | { day: string; at: string })[] = []
  let last = ""
  for (const g of groups) {
    const first = "photos" in g && (g as { photos: ChatMessage[] }).photos.length ? (g as { photos: ChatMessage[] }).photos[0] : g.m
    const day = dayKey(first.at, tz)
    if (day !== last) { out.push({ day, at: first.at }); last = day }
    out.push(g)
  }
  return out
}

/** The last message of each run one person sent within the same minute: only it shows the picture and the time. */
export function chainEnds(groups: { m: ChatMessage }[], tz: string): Set<number> {
  const out = new Set<number>()
  groups.forEach(({ m }, i) => {
    const next = groups[i + 1]?.m
    if (!next || !!next.mine !== !!m.mine || next.user?.username !== m.user?.username || minuteKey(next.at, tz) !== minuteKey(m.at, tz)) out.add(m.id)
  })
  return out
}

/** Under a chat photo: when only its small copy is left (report #48). */
function PhotoExpiry({ photos }: { photos: ChatMessage[] }) {
  const s = S.value
  const media = photos.map((p) => p.media!).filter(Boolean)
  if (media.length && media.every((md) => md.archived)) return <span class="t-small photo-expiry grow" style={{ fontSize: 10 }}>{s.photoArchived}</span>
  const soonest = media.map((md) => md.expires_at).filter((x): x is string => !!x).sort()[0]
  const left = soonest ? countdown(soonest, new Date()) : null
  return left ? <span class="t-small photo-expiry grow" style={{ fontSize: 10 }}>{s.deletedIn(left.days, left.hm)}</span> : null
}

/** Up to 4 photos in a grid; more: "+N" over the last one. A tap: the big view of all. */
function Collage({ photos, onOpen }: { photos: ChatMessage[]; onOpen: (i: number) => void }) {
  const shown = photos.slice(0, 4)
  const more = photos.length - 4
  const n = shown.length
  const cell = (p: ChatMessage, i: number, style: preact.JSX.CSSProperties) => <div key={p.id} data-photo={i} class="chat-photo"
    style={{ position: "relative", borderRadius: 10, overflow: "hidden", cursor: "pointer", ...style }} onClick={() => onOpen(i)}>
    <ChatImage media={p.media!} class="fill" />
    {i === 3 && more > 0 && <div class="fill center t-block" style={{ background: "rgba(0,0,0,0.5)", color: "#fff", fontSize: 22 }}>+{more}</div>}
  </div>
  return <div class="collage" style={{ display: "grid", width: 240, gap: 3, gridTemplateColumns: "1fr 1fr", gridAutoRows: n === 2 ? "170px" : "118px" }}>
    {shown.map((p, i) => cell(p, i, n === 3 && i === 0 ? { gridRow: "span 2" } : {}))}
  </div>
}

/** The photos picked for sending: a big preview with its caption, the strip, add more, send. */
function PhotoQueue({ queue, onChange, onAdd, onSend, onClose }: {
  queue: QueuedPhoto[]; onChange: (q: QueuedPhoto[]) => void; onAdd: (camera: boolean) => void; onSend: () => void; onClose: () => void
}) {
  const s = S.value
  const [sel, setSel] = useState(0)
  const cur = queue[Math.min(sel, queue.length - 1)]
  if (!cur) return null
  const i = queue.indexOf(cur)
  return <div class="fill col photo-queue" role="dialog" aria-label="photos" style={{ position: "fixed", zIndex: 60, background: "#000" }}>
    <div class="row" style={{ padding: "calc(var(--safe-top, 0px) + 6px) 8px 6px" }}>
      <IconBtn icon="ui_close" onClick={onClose} label="close" />
      <span class="t-small grow" style={{ color: "var(--text)" }}>{i + 1} / {queue.length}</span>
    </div>
    <div style={{ flex: 1, position: "relative", minHeight: 0 }}>
      <img src={cur.url} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "contain" }} />
    </div>
    <div class="col" style={{ gap: 8, padding: "10px 10px calc(var(--safe-bottom) + 10px)", background: "var(--card)" }}>
      <div class="row" style={{ gap: 6, overflowX: "auto" }}>
        {queue.map((q, k) => <div key={q.url} style={{ position: "relative", flex: "none" }}>
          <img src={q.url} onClick={() => setSel(k)} style={{ width: 54, height: 54, objectFit: "cover", borderRadius: 8, outline: k === i ? "2px solid var(--cyan)" : undefined }} />
          <button aria-label="remove photo" class="center t-block" onClick={() => { onChange(queue.filter((_, j) => j !== k)); setSel(Math.max(0, k - 1)) }}
            style={{ position: "absolute", top: -4, right: -4, width: 18, height: 18, borderRadius: 6, background: "rgba(0,0,0,0.7)", color: "#fff", fontSize: 10 }}>✕</button>
        </div>)}
        {queue.length < MAX_PHOTOS && <>
          <button aria-label="add photos" class="center" onClick={() => onAdd(false)} style={{ width: 54, height: 54, borderRadius: 8, border: "2px dashed var(--line)", flex: "none" }}><PixelIcon name="ui_image" size={18} /></button>
          <button aria-label="camera" class="center" onClick={() => onAdd(true)} style={{ width: 54, height: 54, borderRadius: 8, border: "2px dashed var(--line)", flex: "none" }}><PixelIcon name="camera" size={18} /></button>
        </>}
      </div>
      <div class="row" style={{ gap: 8 }}>
        <div class="grow"><PxTextField value={cur.caption} emoji onInput={(v) => onChange(queue.map((q, k) => (k === i ? { ...q, caption: v.slice(0, 500) } : q)))}
          user placeholder={s.captionForPhoto} /></div>
        <IconBtn icon="ui_send" onClick={onSend} label="send photos" />
      </div>
    </div>
  </div>
}

function Bubble({ m, photos = [], tz, last = true, group, read, highlighted, onOpenRule, onReply, onQuote, onReactions }: {
  m: ChatMessage; photos?: ChatMessage[]; tz: string; last?: boolean; group: boolean; read: boolean; highlighted: boolean; onOpenRule: (id: number) => void; onReply: () => void; onQuote: (id: number) => void
  onReactions: (r: Reaction[]) => void
}) {
  // a tap on a photo: the big view (all photos of the group, each with its caption)
  // (after the double-tap time: a double tap is the heart)
  const opening = useRef(0)
  const view = (i: number) => {
    clearTimeout(opening.current)
    opening.current = window.setTimeout(async () => {
      const items = await Promise.all(photos.map(async (p) => {
        const md = p.media!
        if (!md.encrypted) return { media: md, caption: p.text }
        const url = (await mediaUrl(md, "url")) ?? ""
        return { media: { ...md, url, thumb: url }, caption: p.text }
      }))
      openViewer(items, i)
    }, 300)
  }
  const collage = photos.length > 1
  const s = S.value
  // reactions: hold for the emoji row, double tap for a heart (like Instagram)
  const [picking, setPicking] = useState(false)
  const [readers, setReaders] = useState(false)
  const [reactors, setReactors] = useState(false)
  const press = useRef<{ timer: number; x: number; y: number; last: number }>({ timer: 0, x: 0, y: 0, last: 0 })
  const react = async (emoji: string) => {
    const r = await req<{ reactions: Reaction[] }>("PUT", `/messages/${m.id}/reaction`, { emoji })
    if (r) onReactions(r.reactions)
  }
  const onDown = (e: PointerEvent) => {
    const p = press.current
    p.x = e.clientX; p.y = e.clientY
    clearTimeout(p.timer)
    p.timer = window.setTimeout(() => { navigator.vibrate?.(10); setPicking(true) }, 480)
  }
  const onMove = (e: PointerEvent) => {
    const p = press.current
    if (Math.hypot(e.clientX - p.x, e.clientY - p.y) > 10) clearTimeout(p.timer)          // scrolling or swiping
  }
  const onUp = (e: PointerEvent) => {
    const p = press.current
    clearTimeout(p.timer)
    if (Math.hypot(e.clientX - p.x, e.clientY - p.y) > 10) return
    if (e.timeStamp - p.last < 300) {
      p.last = 0
      clearTimeout(opening.current)
      if (!(m.reactions ?? []).some((r) => r.emoji === REACTIONS[0] && r.mine)) void react(REACTIONS[0])
    } else p.last = e.timeStamp
  }
  // swipe to the right to answer (like WhatsApp)
  const [dx, setDx] = useState(0)
  const start = useRef<{ x: number; y: number; on: boolean } | null>(null)
  const TRIGGER = 64
  const bare = m.share?.kind === "post" && !m.text?.trim()
  // the open emoji bar lifts this message above its neighbours (report #37: it sat behind the next message)
  return <div data-msg={m.id} style={{ position: "relative", zIndex: picking ? 40 : undefined, background: highlighted ? "rgba(255,205,117,0.12)" : undefined, flex: "none" }}
    onTouchStart={(e) => { const t = e.touches[0]; start.current = { x: t.clientX, y: t.clientY, on: false } }}
    onTouchMove={(e) => {
      const st = start.current, t = e.touches[0]
      if (!st) return
      const ddx = t.clientX - st.x, ddy = t.clientY - st.y
      if (!st.on && Math.abs(ddy) > Math.abs(ddx)) { start.current = null; return }
      if (ddx > 8) st.on = true
      if (st.on) { const n = Math.max(0, Math.min(TRIGGER * 1.4, ddx * .6)); if (n >= TRIGGER && dx < TRIGGER) navigator.vibrate?.(8); setDx(n) }
    }}
    onTouchEnd={() => { if (dx >= TRIGGER) onReply(); start.current = null; setDx(0) }}>
    <div style={{ position: "absolute", left: 6, top: "50%", transform: "translateY(-50%)", opacity: Math.min(1, dx / TRIGGER) }}>
      <PixelIcon name="ui_back" size={18} alpha={dx >= TRIGGER ? 1 : .6} />
    </div>
    <div class="row" style={{ justifyContent: m.mine ? "flex-end" : "flex-start", transform: dx ? `translateX(${dx}px)` : undefined, transition: dx ? undefined : "transform .2s", alignItems: "flex-end" }}>
      {/* the picture only next to the last message of a run (the others keep its space) */}
      {/* with new stories: the ring, a tap shows them (report #67) */}
      {!m.mine && m.user && (last ? <div class="msg-avatar" style={{ marginRight: 8, cursor: "pointer" }} onClick={() => {
        const ring = unseenStories(m.user!.username)
        if (ring) openStoriesOf(ring); else nav.push({ r: "user", username: m.user!.username })
      }}>
        {unseenStories(m.user.username) ? <StoryAvatar user={m.user} size={20} ring="new" /> : <Avatar name={m.user.display_name} url={m.user.avatar} size={28} />}
      </div> : <div style={{ width: 28, marginRight: 8, flex: "none" }} />)}
      <div class="col" style={{ alignItems: m.mine ? "flex-end" : "flex-start", position: "relative" }}>
      {picking && <>
        <div class="fill" style={{ position: "fixed", zIndex: 30 }} onClick={() => setPicking(false)} />
        <div class="row" role="menu" aria-label="reactions" style={{
          position: "absolute", bottom: "100%", [m.mine ? "right" : "left"]: 0, zIndex: 31, marginBottom: 6,
          borderRadius: 24, background: "var(--card2)", border: "1px solid var(--line)", padding: "4px 6px",
        }}>
          {REACTIONS.map((e) => <button key={e} role="menuitem" aria-label={e} onClick={() => { setPicking(false); void react(e) }} style={{
            fontSize: 24, padding: "4px 6px", borderRadius: 16,
            background: (m.reactions ?? []).some((r) => r.emoji === e && r.mine) ? "var(--faint)" : undefined,
          }}>{e}</button>)}
          {/* my message: who read it (report #44) */}
          {m.mine && <button role="menuitem" aria-label="read by" class="center" onClick={() => { setPicking(false); setReaders(true) }}
            style={{ padding: "4px 8px", borderRadius: 16 }}><PixelIcon name="ui_eye" size={20} /></button>}
        </div>
      </>}
      {readers && <ReadersSheet messageId={m.id} onClose={() => setReaders(false)} />}
      {reactors && <ReactorsSheet messageId={m.id} onClose={() => setReactors(false)} />}
      <div class="col bubble" onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={() => clearTimeout(press.current.timer)}
        onContextMenu={(e) => e.preventDefault()} style={{
        maxWidth: 280, borderRadius: 18, overflow: "hidden", padding: m.media || m.share ? 4 : 0,
        WebkitUserSelect: "none", userSelect: "none", WebkitTouchCallout: "none",
        // a shared post is a card of its own: no bubble around it
        background: bare ? "transparent" : m.mine ? "#2b4a3a" : m.mentions_me ? "#3a3424" : "var(--card2)",
        boxShadow: m.mentions_me ? "inset 0 0 0 1.5px var(--yellow)" : undefined,
      }}>
        {group && !m.mine && m.user && <span class="t-small" style={{ fontSize: 12, color: "var(--yellow)", padding: "8px 12px 0" }}>{m.user.display_name}</span>}
        {m.reply && <button class="row" onClick={() => onQuote(m.reply!.id)} style={{ margin: "8px 8px 0", borderRadius: 10, background: "rgba(0,0,0,0.2)", paddingRight: 10, textAlign: "left" }}>
          {/* the quoted message; a tap jumps to it */}
          <i style={{ width: 3, height: 38, background: m.reply.mine ? "var(--lime)" : "var(--yellow)", flex: "none" }} />
          <div class="col" style={{ padding: "5px 0 5px 8px", minWidth: 0 }}>
            <span class="t-small" style={{ fontSize: 11, color: m.reply.mine ? "var(--lime)" : "var(--yellow)" }}>{m.reply.mine ? s.you : m.reply.user}</span>
            <span class="t-small user-text" style={{ fontSize: 13, color: "var(--text)", overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}>
              {previewOf(s, m.reply.text, m.reply.media_kind, m.reply.share_kind)}
            </span>
          </div>
        </button>}
        {m.sealed && <div class="row sealed" style={{ padding: "8px 12px 0", gap: 6 }}>
          <PixelIcon name="ui_lock" size={14} alpha={.7} />
          <span class="t-small">{e2eState.value === "locked" ? s.e2eLocked : s.e2eSealed}</span>
        </div>}
        {collage ? <Collage photos={photos} onOpen={view} />
          : m.media && !m.sealed && (m.media.kind === "audio" ? <ChatAudio media={m.media} />
            : <div class="chat-photo" style={{ cursor: "pointer" }} onClick={() => view(0)}>
              <ChatImage media={m.media} style={{ ...photoSize(m.media.width, m.media.height), borderRadius: 14 }} />
            </div>)}
        {m.share && <ShareCard share={m.share} onOpenRule={onOpenRule} />}
        {!collage && m.text?.trim() && <div style={{ padding: "8px 12px 0" }}><MentionText text={m.text} /></div>}
        {/* the time once per run of the same minute (report #49); mine: ✓ sent, eye: read (by everyone in the chat) */}
        {/* a single photo: as wide as the photo (the countdown may wrap), so the bubble keeps the photo's shape */}
        {last || m.mine || photos.length > 0 ? <div class="row msg-meta" style={{ alignSelf: photos.length ? "stretch" : "flex-end", gap: 5, padding: "2px 10px 6px 12px",
          width: photos.length === 1 && m.media ? photoSize(m.media.width, m.media.height).width + 8 : undefined, boxSizing: "border-box" }}>
          {photos.length > 0 && <PhotoExpiry photos={photos} />}
          {last && <span class="t-small msg-time" style={{ fontSize: 11 }}>{clock(m.at, tz)}</span>}
          {m.mine && (read ? <PixelIcon name="ui_eye" size={14} color="var(--lime)" /> : <span class="t-small" style={{ fontSize: 11 }}>✓</span>)}
        </div> : <div style={{ height: 8 }} />}
      </div>
      {/* the reactions under the bubble; a tap on my own takes it back, holding shows who reacted (report #63) */}
      {(m.reactions ?? []).length > 0 && <div class="row reactions" style={{
        padding: "1px 4px", margin: "-6px 8px 0", borderRadius: 12, background: "var(--card)", border: "1px solid var(--line)", gap: 2, zIndex: 1,
      }}>
        {m.reactions!.map((r) => <ReactionChip key={r.emoji} r={r} onTap={() => react(r.emoji)} onHold={() => setReactors(true)} />)}
      </div>}
      </div>
    </div>
  </div>
}

/** A shared item inside a chat bubble. */
export function ShareCard({ share, onOpenRule }: { share: Share; onOpenRule: (id: number) => void }) {
  const s = S.value
  const box = { width: 260, borderRadius: 14, background: "rgba(0,0,0,0.13)", padding: 12, gap: 10, cursor: "pointer" }
  if (!share.available) return <div class="row" style={{ ...box, cursor: undefined, gap: 8 }}>
    <PixelIcon name="ui_lock" size={16} alpha={.6} /><span class="t-small">{s.notAvailable}</span>
  </div>
  switch (share.kind) {
    case "rule": return <div class="row" style={box} onClick={() => share.id != null && onOpenRule(share.id)}>
      <RuleIconBox icon={share.icon ?? "star"} size={40} />
      <div class="col" style={{ minWidth: 0 }}>
        <Label text={s.sharedRule} />
        <span class="t-strong ellipsis">{share.name}</span>
        <span class="t-small">{s.ruleBy(share.owner ?? "")}</span>
      </div>
    </div>
    case "profile": return share.user ? <div class="row" style={box} onClick={() => nav.push({ r: "user", username: share.user!.username })}>
      <Avatar name={share.user.display_name} url={share.user.avatar} size={40} />
      <div class="col"><Label text={s.sharedProfile} /><span class="t-strong">{share.user.username}</span></div>
    </div> : null
    // a shared post as its own card, like on Instagram: author, the picture full width, caption, "view post"
    case "post": {
      const ratio = share.post_kind === "milestone" ? 1.5 : Math.max(.8, Math.min(1.91, share.ratio ?? 1))
      return <div class="col" style={{ width: 250, borderRadius: 16, background: "#14161f", overflow: "hidden", cursor: "pointer" }}
        onClick={() => share.id != null && nav.push({ r: "post", id: share.id })}>
        {share.post_owner && <div class="row" style={{ padding: "9px 10px", gap: 8 }}>
          <Avatar name={share.post_owner.display_name} url={share.post_owner.avatar} size={26} />
          <span class="t-strong ellipsis" style={{ fontSize: 14 }}>{share.post_owner.username}</span>
        </div>}
        <div style={{ position: "relative", width: "100%", aspectRatio: String(ratio), background: "#0b0c12" }}>
          {share.post_kind === "milestone" ? <MilestoneTile vehicle={share.vehicle ?? ""} dpPerPixel={2} />
            : share.thumb ? <RemoteImage url={share.thumb} class="fill" />
              : <div class="fill center" style={{ padding: 14 }}><span class="t-body user-text">{share.caption}</span></div>}
          {share.video ? <div style={{ position: "absolute", top: 8, right: 8 }}><PixelIcon name="ui_video" size={16} /></div>
            : (share.media_count ?? 0) > 1 ? <div style={{ position: "absolute", top: 8, right: 8 }}><PixelIcon name="ui_grid" size={14} /></div> : null}
        </div>
        {share.caption?.trim() && (share.thumb || share.post_kind === "milestone") && <span class="t-small user-text"
          style={{ color: "var(--text)", padding: "8px 10px 0", overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}>
          {share.post_owner && <b style={{ fontWeight: 600 }}>{share.post_owner.username}  </b>}{share.caption}
        </span>}
        <span class="t-small" style={{ fontSize: 12, padding: "6px 10px 10px" }}>{s.viewPost} ›</span>
      </div>
    }
    case "challenge": return share.challenge ? <ChallengeMini c={share.challenge} onClick={() => nav.push({ r: "challenge", id: share.challenge!.id })} /> : null
    // a shared story: the author, its picture upright, "view story" (report #33)
    case "story": return <div class="col story-share" style={{ width: 140, borderRadius: 16, background: "#14161f", overflow: "hidden", cursor: "pointer" }}
      onClick={() => share.id != null && void openSharedStory(share.id)}>
      {share.user && <div class="row" style={{ padding: "9px 10px", gap: 8 }}>
        <Avatar name={share.user.display_name} url={share.user.avatar} size={24} />
        <span class="t-strong ellipsis" style={{ fontSize: 14 }}>{share.user.username}</span>
      </div>}
      <div style={{ position: "relative", width: "100%", aspectRatio: String(Math.max(.5, Math.min(1, share.ratio ?? .5625))), background: "#0b0c12" }}>
        {share.thumb && <RemoteImage url={share.thumb} class="fill" />}
        {share.video && <div style={{ position: "absolute", top: 8, right: 8 }}><PixelIcon name="ui_video" size={16} /></div>}
      </div>
      <span class="t-small" style={{ fontSize: 12, padding: "6px 10px 10px" }}>{s.viewStory} ›</span>
    </div>
    default: return null
  }
}

export function NewChatScreen() {
  const s = S.value
  return <div class="fill col">
    <TopBar title={s.newMessage} />
    <div class="scroll" style={{ flex: 1 }}>
      <ConnectedPeople context="chat">{(people) => people.map((u) => <UserRow key={u.username} user={u} onClick={async () => {
        const r = await req<{ conversation: Conversation }>("POST", "/conversations/direct", { username: u.username })
        if (r) nav.replace({ r: "chat", id: r.conversation.id })
      }} />)}</ConnectedPeople>
    </div>
  </div>
}

export function NewGroupScreen() {
  const s = S.value
  const [name, setName] = useState("")
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const ready = name.trim() !== "" && picked.size > 0
  return <div class="fill col">
    <TopBar title={s.newGroup}>
      <button class="t-button" disabled={!ready} style={{ marginRight: 8, borderRadius: 12, padding: "10px 16px", background: ready ? "var(--text)" : "var(--faint)", color: ready ? "#000" : "var(--muted)" }}
        onClick={async () => {
          const r = await req<{ conversation: Conversation }>("POST", "/conversations/group", { name: name.trim(), usernames: [...picked] })
          if (r) nav.replace({ r: "chat", id: r.conversation.id })
        }}>{s.create}</button>
    </TopBar>
    <div style={{ padding: 16 }}><PxTextField value={name} onInput={setName} emoji placeholder={s.groupName} /></div>
    <span class="t-small" style={{ padding: "0 16px" }}>{s.pickPeople}</span>
    <div class="scroll" style={{ flex: 1 }}>
      <ConnectedPeople context="group">{(people) => people.map((u) => {
        const on = picked.has(u.username)
        return <UserRow key={u.username} user={u} onClick={() => { const n = new Set(picked); if (on) n.delete(u.username); else n.add(u.username); setPicked(n) }}
          trailing={<i style={{ width: 22, height: 22, borderRadius: 6, background: on ? "var(--lime)" : "var(--faint)" }} />} />
      })}</ConnectedPeople>
    </div>
  </div>
}
