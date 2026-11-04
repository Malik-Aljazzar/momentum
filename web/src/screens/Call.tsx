// The call screen (= android/.../ui/Call.kt): ringing, the others' pictures / avatars, the buttons; minimized it is a
// small window over the app (the call goes on). The sound of the others plays from hidden <audio>s that stay mounted.
import { useEffect, useRef, useState } from "preact/hooks"
import {
  acceptCall, call, declineCall, flipCam, hangUp, inCall, minimize, toggleCam, toggleMic, type CallView,
} from "../calls"
import { callDuration } from "../lines"
import type { CallPeer } from "../model"
import { state } from "../store"
import { S, type Strings } from "../strings"
import { Avatar, PixelIcon } from "../ui/components"
import { Cover, useCoverLayer } from "../ui/video"

export function CallHost() {
  const c = call.value
  if (!c) return null
  return <>
    {Object.entries(c.remotes).map(([uid, stream]) => <Sound key={uid} stream={stream} />)}
    {c.minimized && c.phase !== "incoming" ? <CallBubble c={c} /> : <CallScreen c={c} />}
  </>
}

/** The others' sound (always mounted, also while minimized). */
function Sound({ stream }: { stream: MediaStream }) {
  const ref = useRef<HTMLAudioElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (el.srcObject !== stream) el.srcObject = stream
    void el.play().catch(() => {})
  }, [stream])
  return <audio ref={ref} autoplay class="call-sound" />
}

/** A picture: someone's camera (muted: the sound comes from <Sound>). */
function Picture({ stream, mirror = false, fit = "cover" }: { stream: MediaStream; mirror?: boolean; fit?: "cover" | "contain" }) {
  const ref = useRef<HTMLVideoElement>(null)
  const tracks = stream.getVideoTracks().length
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (el.srcObject !== stream) el.srcObject = stream
    void el.play().catch(() => {})
  }, [stream, tracks])
  return <video ref={ref} autoplay playsInline muted class="call-video"
    style={{ objectFit: fit, transform: mirror ? "scaleX(-1)" : undefined }} />
}

/** Seconds since the talking started, ticking. */
function useClock(since: number | null): number | null {
  const [, tick] = useState(0)
  useEffect(() => {
    if (since == null) return
    const h = setInterval(() => tick((n) => n + 1), 1000)
    return () => clearInterval(h)
  }, [since])
  return since == null ? null : Math.floor((Date.now() - since) / 1000)
}

export function statusText(s: Strings, c: CallView, seconds: number | null): string {
  if (c.phase === "ended") return c.endText ?? s.callEnded
  if (c.phase === "incoming") return c.info.video ? s.callIncomingVideo : s.callIncomingVoice
  if (c.phase === "outgoing") return c.info.id && c.info.participants.some((p) => p.state === "ringing") ? s.callRinging : s.callCalling
  const me = state.value?.user.id ?? -1
  const others = inCall(c.info, me)
  if (!others.length) return c.info.group ? s.callWaiting : s.callConnecting
  if (c.phase === "connecting") return s.callConnecting
  if (others.some((p) => c.linked[p.user.id] === false)) return s.callReconnecting
  return seconds != null ? callDuration(seconds) : s.callConnecting
}

function CallScreen({ c }: { c: CallView }) {
  const s = S.value
  const cover = useCoverLayer()                  // the feed's videos pause under it
  const seconds = useClock(c.since)
  const me = state.value?.user
  const others = inCall(c.info, me?.id ?? -1)
  const talking = c.phase === "active" || c.phase === "connecting"
  const status = statusText(s, c, seconds)
  // one other with the camera on: their picture fills the screen, mine sits in the corner
  const single = !c.info.group && others.length === 1 ? others[0] : null
  const bigVideo = single && single.cam && c.remotes[single.user.id]?.getVideoTracks().length ? c.remotes[single.user.id] : null
  const grid = talking && (c.info.group && others.length > 0)
  return <Cover.Provider value={cover}><div class="call-screen" role="dialog" aria-label="call">
    {bigVideo && <div class="fill"><Picture stream={bigVideo} /></div>}
    <div class="call-top row">
      {c.phase !== "incoming" && c.phase !== "ended"
        ? <button class="icon-btn" aria-label={s.callMinimize} onClick={() => minimize(true)}><PixelIcon name="ui_down" size={24} /></button>
        : <span style={{ width: 44 }} />}
      <span class="t-small grow center" style={{ color: "var(--text)" }}>🔒 {c.info.video ? s.videoCall : s.voiceCall}</span>
      <span style={{ width: 44 }} />
    </div>
    {grid ? <Tiles c={c} others={others} />
      : <div class={"call-who col center" + (bigVideo ? " over-video" : "")}>
        {!bigVideo && <div class={"call-avatar" + (c.phase === "incoming" || c.phase === "outgoing" ? " ringing" : "")}>
          <Avatar name={single?.user.display_name ?? c.title} url={single?.user.avatar ?? c.avatar} size={112} />
        </div>}
        <div class="t-title ellipsis" style={{ fontSize: 24, maxWidth: "90%", textAlign: "center" }}>{single?.user.display_name ?? c.title}</div>
        {c.info.group && c.phase === "incoming" && <span class="t-small">{c.info.started_by?.display_name}</span>}
        <span class="t-small call-status">{status}</span>
        {single && !single.mic && talking && <PixelIcon name="ui_micoff" size={18} color="var(--orange)" />}
      </div>}
    {grid && <div class="center" style={{ padding: "6px 0" }}><span class="t-small call-status">{status}</span></div>}
    {/* my own picture: small in the corner (in a group it is one of the tiles) */}
    {!grid && c.cam && c.local && c.phase !== "incoming" && <div class="call-self"><Picture stream={c.local} mirror={c.facing === "user"} /></div>}
    <Controls c={c} s={s} />
  </div></Cover.Provider>
}

function Tiles({ c, others }: { c: CallView; others: CallPeer[] }) {
  const me = state.value?.user
  const tiles: { key: string; name: string; avatar?: string | null; stream: MediaStream | null; cam: boolean; mic: boolean; self: boolean; linked: boolean }[] = [
    ...others.map((p) => ({ key: String(p.user.id), name: p.user.display_name, avatar: p.user.avatar, stream: c.remotes[p.user.id] ?? null,
      cam: p.cam, mic: p.mic, self: false, linked: c.linked[p.user.id] !== false })),
    { key: "me", name: me?.display_name ?? "", avatar: me?.avatar, stream: c.local, cam: c.cam, mic: c.mic, self: true, linked: true },
  ]
  const cols = tiles.length <= 2 ? 1 : 2
  return <div class="call-tiles" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)` }}>
    {tiles.map((t) => <div key={t.key} class="call-tile">
      {t.cam && t.stream?.getVideoTracks().length ? <Picture stream={t.stream} mirror={t.self && c.facing === "user"} />
        : <div class="fill center"><Avatar name={t.name} url={t.avatar} size={64} /></div>}
      <div class="call-tile-name row">
        {!t.mic && <PixelIcon name="ui_micoff" size={12} color="var(--orange)" />}
        <span class="t-small ellipsis" style={{ color: "var(--text)", fontSize: 12 }}>{t.self ? S.value.you : t.name}</span>
        {!t.linked && <span class="t-small" style={{ fontSize: 12 }}>…</span>}
      </div>
    </div>)}
  </div>
}

function CallButton({ icon, label, onClick, kind = "plain", on = false }: {
  icon: string; label: string; onClick: () => void; kind?: "plain" | "red" | "green"; on?: boolean
}) {
  return <div class="col center" style={{ gap: 6, width: 72 }}>
    <button class={`call-btn ${kind}${on ? " on" : ""}`} aria-label={label} onClick={onClick}>
      <PixelIcon name={icon} size={28} color={kind === "green" || on ? "#000" : "#fff"} />
    </button>
    <span class="t-small" style={{ fontSize: 11, color: "var(--text)", textAlign: "center" }}>{label}</span>
  </div>
}

function Controls({ c, s }: { c: CallView; s: Strings }) {
  if (c.phase === "ended") return <div class="call-controls" />
  if (c.phase === "incoming") {
    return <div class="call-controls row" style={{ justifyContent: "space-around" }}>
      <CallButton icon="ui_hangup" label={s.callDecline} kind="red" onClick={() => void declineCall()} />
      <CallButton icon={c.info.video ? "ui_cam" : "ui_handset"} label={s.callAccept} kind="green" onClick={() => void acceptCall()} />
    </div>
  }
  return <div class="call-controls row" style={{ justifyContent: "space-evenly" }}>
    <CallButton icon={c.cam ? "ui_cam" : "ui_camoff"} label={s.callCamera} on={c.cam} onClick={() => void toggleCam()} />
    {c.cam && <CallButton icon="ui_flip" label={s.callFlip} onClick={() => void flipCam()} />}
    <CallButton icon={c.mic ? "ui_mic" : "ui_micoff"} label={s.callMute} on={!c.mic} onClick={toggleMic} />
    <CallButton icon="ui_hangup" label={s.callHangUp} kind="red" onClick={() => void hangUp()} />
  </div>
}

/** Minimized: a small window over the app (the other's picture, or the avatar and the time); a tap opens the call. */
function CallBubble({ c }: { c: CallView }) {
  const s = S.value
  const seconds = useClock(c.since)
  const me = state.value?.user.id ?? -1
  const other = inCall(c.info, me)[0]
  const stream = other && other.cam ? c.remotes[other.user.id] : null
  return <button class="call-bubble col center" aria-label={s.callBackTo} onClick={() => minimize(false)}>
    {stream?.getVideoTracks().length ? <div class="fill"><Picture stream={stream} /></div>
      : <Avatar name={other?.user.display_name ?? c.title} url={other?.user.avatar ?? c.avatar} size={44} />}
    <span class="t-small call-bubble-time">{statusText(s, c, seconds)}</span>
  </button>
}
