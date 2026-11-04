// Voice and video calls (server/calls.py = android/.../data/Calls.kt): a socket to the server brings the ringing and
// the WebRTC signals; there is one RTCPeerConnection to every other participant (mesh, a group call holds up to
// `info.max`). Every connection carries an audio and a video line from the start, so the camera can go on and off
// later without a new offer. Who offers: the one who joined later (higher seq) to everyone already in.
import { signal } from "@preact/signals"
import { api, ApiError } from "./api"
import { tones } from "./calltones"
import type { CallInfo, CallPeer } from "./model"
import { clearNotifications } from "./push"
import { foreground, onError, online, showToast, state } from "./store"
import { S } from "./strings"

export type Phase = "incoming" | "outgoing" | "connecting" | "active" | "ended"

export interface CallView {
  info: CallInfo
  phase: Phase
  /** what the screen shows before the server answered: the chat's name and picture */
  title: string
  avatar: string | null
  local: MediaStream | null
  /** the others' sound and picture, by user id */
  remotes: Record<number, MediaStream>
  /** the connection to this participant is up */
  linked: Record<number, boolean>
  mic: boolean
  cam: boolean
  facing: "user" | "environment"
  minimized: boolean
  /** when the talking started (this device's clock), for the timer */
  since: number | null
  /** the end: why (busy, declined, no answer) */
  endText?: string
}

export const call = signal<CallView | null>(null)

/** This app start (a player may have several devices; the one that answered gets the call). */
export const device = (crypto.randomUUID?.() ?? Math.random().toString(36).slice(2) + Date.now().toString(36)).replace(/[^a-zA-Z0-9-]/g, "").slice(0, 40)

interface Peer {
  pc: RTCPeerConnection
  seq: number
  offerer: boolean
  /** candidates that came before the offer / answer */
  early: RTCIceCandidateInit[]
  /** my candidates, sent in small batches */
  out: RTCIceCandidateInit[]
  timer?: ReturnType<typeof setTimeout>
  chain: Promise<void>
}

const peers = new Map<number, Peer>()
let ice: RTCIceServer[] = [{ urls: ["stun:stun.l.google.com:19302"] }]
let local: MediaStream | null = null
let wakeLock: { release(): Promise<void> } | null = null
let accepting = false

const AUDIO: MediaTrackConstraints = { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
const videoOf = (facing: "user" | "environment"): MediaTrackConstraints =>
  ({ facingMode: facing, width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 24, max: 30 } })

const myId = () => state.value?.user.id ?? -1
const mine = (info: CallInfo) => info.participants.find((p) => p.user.id === myId())
const update = (patch: Partial<CallView>) => { if (call.value) call.value = { ...call.value, ...patch } }

/** Who sends the offer to whom: the later one (higher seq) to the earlier one. */
export function offersTo(me: number, other: number): boolean { return me > other }

/** The others who are in the call right now. */
export function inCall(info: CallInfo, me: number): CallPeer[] {
  return info.participants.filter((p) => p.user.id !== me && p.state === "joined")
}

// ---------------------------------------------------------------- the socket

let ws: WebSocket | null = null
let retries = 0
let retryTimer: ReturnType<typeof setTimeout> | undefined
let pingTimer: ReturnType<typeof setInterval> | undefined

/** Opens the call socket (logged in, app in the foreground); it comes back by itself after a drop. */
export function connectCalls() {
  if (ws || !api.token || typeof WebSocket === "undefined") return
  const sock = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/calls/ws`)
  ws = sock
  sock.onopen = () => sock.send(JSON.stringify({ token: api.token, device }))
  sock.onmessage = (e) => { try { onEvent(JSON.parse(String(e.data))) } catch { /* not for us */ } }
  sock.onerror = () => sock.close()
  sock.onclose = () => {
    if (ws !== sock) return
    ws = null
    clearInterval(pingTimer)
    clearTimeout(retryTimer)
    if (api.token && (foreground.value || call.value)) retryTimer = setTimeout(connectCalls, Math.min(15_000, 1000 * 2 ** retries++))
  }
  clearInterval(pingTimer)
  pingTimer = setInterval(() => send({ type: "ping" }), 25_000)
}

/** Logged out: hang up, socket closed. */
export function disconnectCalls() {
  void hangUp()
  const sock = ws
  ws = null
  clearTimeout(retryTimer)
  clearInterval(pingTimer)
  sock?.close()
}

function send(msg: unknown) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg))
}

function onEvent(e: { type: string; ice?: RTCIceServer[]; call?: CallInfo | number; from?: number; data?: SignalData }) {
  if (e.type === "hello") {
    retries = 0
    if (e.ice?.length) ice = e.ice
  } else if (e.type === "call" && typeof e.call === "object" && e.call) applyInfo(e.call)
  else if (e.type === "signal" && typeof e.call === "number" && e.from != null && e.data) onSignal(e.call, e.from, e.data)
}

if (typeof window !== "undefined") {
  foreground.subscribe((fg) => { if (fg) connectCalls() })
  // the page closes in the middle of a call: hang up (the others don't wait for the lost-phone timeout)
  addEventListener("pagehide", () => {
    const id = call.value?.info.id
    if (!id || !api.token) return
    void fetch(`/api/calls/${id}/leave`, { method: "POST", keepalive: true, headers: api.headers({ "Content-Type": "application/json" }), body: "{}" }).catch(() => {})
  })
}

// ---------------------------------------------------------------- what the server says

function isCurrent(cur: CallView, info: CallInfo) {
  return cur.info.id === info.id || (cur.info.id === 0 && cur.info.conversation_id === info.conversation_id)
}

/** A call's new state (socket, or the answer to my own request). */
export function applyInfo(info: CallInfo) {
  const cur = call.value
  const me = info.me
  if (!cur || !isCurrent(cur, info)) {
    if (info.status === "ended") { void clearNotifications((t) => t === `call-${info.id}`); return }
    if (cur) return                                  // in another call: the server tells them I'm busy
    if (me?.state === "ringing") { ringing(info); return }
    // this page was reloaded in the middle of the call: its sound is gone, so it leaves
    if (me?.state === "joined" && me.device === device) void api.raw("POST", `/calls/${info.id}/leave`).catch(() => {})
    return
  }
  if (info.status === "ended") { ended(info); return }
  if (cur.phase === "incoming") {
    if (me?.state === "ringing") { update({ info }); return }
    tones.stop()                                     // answered on another device, declined, missed
    call.value = null
    void clearNotifications((t) => t === `call-${info.id}`)
    return
  }
  if (me?.state !== "joined" || me.device !== device) {
    if (accepting && me?.state === "ringing") { update({ info }); return }
    cleanup()                                        // out of it (another device, lost)
    call.value = null
    return
  }
  const phase: Phase = cur.phase === "outgoing" && info.status === "active" ? "connecting" : cur.phase
  update({ info, phase })
  if (phase === "outgoing") tones.ringback(); else if (cur.phase === "outgoing") tones.stop()
  syncPeers(info)
}

function ringing(info: CallInfo) {
  const s = S.value
  call.value = {
    info, phase: "incoming", title: info.group ? info.title || s.groupVoiceCall : info.started_by?.display_name ?? "",
    avatar: info.started_by?.avatar ?? null, local: null, remotes: {}, linked: {}, mic: true, cam: info.video,
    facing: "user", minimized: false, since: null,
  }
  tones.ring()
}

function ended(info: CallInfo) {
  const cur = call.value
  if (!cur) return
  if (cur.phase === "ended") { update({ info }); return }        // I hung up myself
  const s = S.value
  const mineStarted = info.started_by?.id === myId()
  const endText = mineStarted && !info.answered_at
    ? info.reason === "busy" ? s.callBusy : info.reason === "declined" ? s.callDeclined : s.callNoAnswer
    : s.callEnded
  const wasRinging = cur.phase === "incoming"
  cleanup()
  void clearNotifications((t) => t === `call-${info.id}`)
  if (wasRinging) { call.value = null; return }     // a missed call: the chat line tells it
  tones.end()
  call.value = { ...cur, info, phase: "ended", endText, local: null, remotes: {}, minimized: false }
  setTimeout(() => { if (call.value?.phase === "ended" && call.value.info.id === info.id) call.value = null }, 1800)
}

// ---------------------------------------------------------------- what I do

async function media(video: boolean, facing: "user" | "environment" = "user"): Promise<MediaStream | null> {
  const s = S.value
  if (!navigator.mediaDevices?.getUserMedia || typeof RTCPeerConnection === "undefined") { showToast(s.callUnsupported, true); return null }
  try {
    return await navigator.mediaDevices.getUserMedia({ audio: AUDIO, video: video ? videoOf(facing) : false })
  } catch {
    if (video) {                                     // no camera: with sound only
      try {
        const sound = await navigator.mediaDevices.getUserMedia({ audio: AUDIO })
        showToast(s.callNoCamera, true)
        return sound
      } catch { /* no microphone either */ }
    }
    showToast(s.callNoMic, true)
    return null
  }
}

function useLocal(stream: MediaStream) {
  local = stream
  update({ local: new MediaStream(stream.getTracks()), cam: stream.getVideoTracks().length > 0, mic: true })
  void keepAwake()
}

/** Rings the chat (or joins the call running there). */
export async function startCall(convId: number, video: boolean, title: string, avatar: string | null) {
  const s = S.value
  if (call.value) { if (call.value.info.conversation_id !== convId) showToast(s.callAlready, true); else update({ minimized: false }); return }
  if (!online.value) { showToast(s.callNeedsInternet, true); return }
  tones.unlock()
  connectCalls()
  const info: CallInfo = { id: 0, conversation_id: convId, group: false, video, status: "ringing", participants: [] }
  call.value = { info, phase: "outgoing", title, avatar, local: null, remotes: {}, linked: {}, mic: true, cam: video,
    facing: "user", minimized: false, since: null }
  const stream = await media(video)
  if (!call.value || call.value.info.conversation_id !== convId) { stream?.getTracks().forEach((t) => t.stop()); return }
  if (!stream) { call.value = null; return }
  useLocal(stream)
  try {
    const r = await api.call<{ call: CallInfo; ice_servers?: RTCIceServer[] }>("POST", `/conversations/${convId}/call`, { video, device })
    if (r.ice_servers?.length) ice = r.ice_servers
    if (!call.value) { void api.raw("POST", `/calls/${r.call.id}/leave`).catch(() => {}); return }    // hung up meanwhile
    applyInfo(r.call)
  } catch (e) {
    cleanup()
    call.value = null
    if (e instanceof ApiError) onError(e); else throw e
  }
}

export async function acceptCall() {
  const cur = call.value
  if (!cur || cur.phase !== "incoming") return
  tones.stop()
  tones.unlock()
  connectCalls()
  accepting = true
  update({ phase: "connecting" })
  try {
    const stream = await media(cur.info.video)
    if (!stream || call.value?.info.id !== cur.info.id) {
      stream?.getTracks().forEach((t) => t.stop())
      if (!stream) await declineCall(cur.info.id)
      return
    }
    useLocal(stream)
    const r = await api.call<{ call: CallInfo; ice_servers?: RTCIceServer[] }>("POST", `/calls/${cur.info.id}/accept`, { device })
    if (r.ice_servers?.length) ice = r.ice_servers
    accepting = false
    applyInfo(r.call)
  } catch (e) {
    cleanup()
    call.value = null
    if (e instanceof ApiError) onError(e); else throw e
  } finally {
    accepting = false
  }
}

export async function declineCall(id = call.value?.info.id) {
  tones.stop()
  cleanup()
  call.value = null
  if (id) await api.raw("POST", `/calls/${id}/decline`).catch(() => {})
}

export async function hangUp() {
  const cur = call.value
  if (!cur) return
  const id = cur.info.id
  cleanup()
  if (cur.phase === "incoming") { call.value = null; if (id) await api.raw("POST", `/calls/${id}/decline`).catch(() => {}); return }
  tones.end()
  call.value = { ...cur, phase: "ended", endText: S.value.callEnded, local: null, remotes: {} }
  setTimeout(() => { if (call.value?.phase === "ended" && call.value.info.id === id) call.value = null }, 1200)
  if (id) await api.raw("POST", `/calls/${id}/leave`).catch(() => {})
}

/** Opened from the ringing notification: still ringing -> the incoming call screen. */
export async function openCall(id: number): Promise<CallInfo | null> {
  connectCalls()
  try {
    const r = await api.call<{ call: CallInfo }>("GET", `/calls/${id}`)
    applyInfo(r.call)
    return r.call
  } catch { return null }
}

export function minimize(on: boolean) { update({ minimized: on }) }

export function toggleMic() {
  const cur = call.value
  if (!cur || !local) return
  const on = !cur.mic
  for (const t of local.getAudioTracks()) t.enabled = on
  update({ mic: on })
  sendMedia()
}

export async function toggleCam() {
  const cur = call.value
  if (!cur || !local) return
  if (cur.cam) {
    for (const t of local.getVideoTracks()) { t.stop(); local.removeTrack(t) }
    await replaceVideo(null)
    update({ cam: false, local: new MediaStream(local.getTracks()) })
  } else {
    const track = await cameraTrack(cur.facing)
    if (!track || !call.value) return
    local.addTrack(track)
    await replaceVideo(track)
    tuneVideo(peers.size)
    update({ cam: true, local: new MediaStream(local.getTracks()) })
  }
  sendMedia()
  void keepAwake()
}

export async function flipCam() {
  const cur = call.value
  if (!cur?.cam || !local) return
  const facing = cur.facing === "user" ? "environment" : "user"
  for (const t of local.getVideoTracks()) { t.stop(); local.removeTrack(t) }      // iOS opens one camera at a time
  const track = await cameraTrack(facing)
  if (!track || !call.value) { update({ cam: false }); await replaceVideo(null); sendMedia(); return }
  local.addTrack(track)
  await replaceVideo(track)
  update({ facing, local: new MediaStream(local.getTracks()) })
}

async function cameraTrack(facing: "user" | "environment"): Promise<MediaStreamTrack | null> {
  try {
    return (await navigator.mediaDevices.getUserMedia({ video: videoOf(facing) })).getVideoTracks()[0] ?? null
  } catch {
    showToast(S.value.callNoCamera, true)
    return null
  }
}

function sendMedia() {
  const cur = call.value
  if (cur?.info.id) send({ type: "media", call: cur.info.id, mic: cur.mic, cam: cur.cam })
}

async function keepAwake() {
  try {
    if (!wakeLock) wakeLock = await (navigator as Navigator & { wakeLock?: { request(t: string): Promise<{ release(): Promise<void> }> } }).wakeLock?.request("screen") ?? null
  } catch { /* not allowed now */ }
}

function cleanup() {
  tones.stop()
  for (const uid of [...peers.keys()]) closePeer(uid)
  local?.getTracks().forEach((t) => t.stop())
  local = null
  void wakeLock?.release().catch(() => {})
  wakeLock = null
  accepting = false
}

// ---------------------------------------------------------------- the connections

interface SignalData { sdp?: RTCSessionDescriptionInit; ice?: RTCIceCandidateInit[] }

function signalTo(uid: number, data: SignalData) {
  const id = call.value?.info.id
  if (id) send({ type: "signal", call: id, to: uid, data })
}

function syncPeers(info: CallInfo) {
  const me = mine(info)
  if (!me || me.state !== "joined") return
  const here = new Set<number>()
  for (const p of info.participants) {
    if (p.user.id === me.user.id) continue
    const peer = peers.get(p.user.id)
    if (p.state !== "joined") { if (peer) closePeer(p.user.id); continue }
    here.add(p.user.id)
    if (peer && peer.seq !== p.seq) closePeer(p.user.id)           // they came again (another device, a reload)
    if (!peers.has(p.user.id) && offersTo(me.seq, p.seq)) void offer(p.user.id, p.seq)
  }
  for (const uid of [...peers.keys()]) if (!here.has(uid)) closePeer(uid)
  tuneVideo(here.size)
}

function newPeer(uid: number, seq: number, offerer: boolean): Peer {
  const pc = new RTCPeerConnection({ iceServers: ice, bundlePolicy: "max-bundle" })
  const peer: Peer = { pc, seq, offerer, early: [], out: [], chain: Promise.resolve() }
  pc.onicecandidate = (e) => {
    if (!e.candidate) return
    peer.out.push(e.candidate.toJSON())
    peer.timer ??= setTimeout(() => { peer.timer = undefined; signalTo(uid, { ice: peer.out.splice(0) }) }, 80)
  }
  pc.ontrack = (e) => {
    const cur = call.value
    if (!cur) return
    const stream = cur.remotes[uid] ?? new MediaStream()
    if (!stream.getTracks().includes(e.track)) stream.addTrack(e.track)
    update({ remotes: { ...cur.remotes, [uid]: stream } })
    e.track.onunmute = () => { const c = call.value; if (c?.remotes[uid]) update({ remotes: { ...c.remotes } }) }
  }
  const watch = () => {
    const ice = pc.iceConnectionState
    const up = pc.connectionState === "connected" || ice === "connected" || ice === "completed"
    const cur = call.value
    if (!cur || peers.get(uid) !== peer) return
    if (cur.linked[uid] !== up) update({ linked: { ...cur.linked, [uid]: up } })
    if (up && (cur.phase === "connecting" || cur.phase === "outgoing")) { tones.stop(); update({ phase: "active", since: cur.since ?? Date.now() }) }
    if ((pc.connectionState === "failed" || ice === "failed") && peer.offerer) void restart(uid, peer)
  }
  pc.onconnectionstatechange = watch
  pc.oniceconnectionstatechange = watch
  peers.set(uid, peer)
  return peer
}

function closePeer(uid: number) {
  const peer = peers.get(uid)
  if (!peer) return
  peers.delete(uid)
  clearTimeout(peer.timer)
  try { peer.pc.close() } catch { /* closed already */ }
  const cur = call.value
  if (cur && (cur.remotes[uid] || cur.linked[uid] != null)) {
    const remotes = { ...cur.remotes }, linked = { ...cur.linked }
    delete remotes[uid]
    delete linked[uid]
    update({ remotes, linked })
  }
}

async function offer(uid: number, seq: number) {
  const peer = newPeer(uid, seq, true)
  const audio = local?.getAudioTracks()[0]
  const video = local?.getVideoTracks()[0]
  const streams = local ? [local] : []
  peer.pc.addTransceiver(audio ?? "audio", { direction: "sendrecv", streams })
  peer.pc.addTransceiver(video ?? "video", { direction: "sendrecv", streams })
  try {
    await peer.pc.setLocalDescription(await peer.pc.createOffer())
    signalTo(uid, { sdp: peer.pc.localDescription!.toJSON() })
  } catch { closePeer(uid) }
}

async function restart(uid: number, peer: Peer) {
  if (peers.get(uid) !== peer || peer.pc.signalingState !== "stable") return
  try {
    peer.pc.restartIce?.()
    await peer.pc.setLocalDescription(await peer.pc.createOffer({ iceRestart: true }))
    signalTo(uid, { sdp: peer.pc.localDescription!.toJSON() })
  } catch { /* the next state change tries again */ }
}

function onSignal(callId: number, from: number, data: SignalData) {
  const cur = call.value
  if (!cur || cur.info.id !== callId) return
  let peer = peers.get(from)
  if (!peer && data.sdp?.type === "offer") {
    const p = cur.info.participants.find((x) => x.user.id === from)
    peer = newPeer(from, p?.seq ?? 0, false)
  }
  if (!peer) return
  const pr = peer
  pr.chain = pr.chain.then(() => handleSignal(from, pr, data)).catch(() => {})
}

async function handleSignal(uid: number, peer: Peer, data: SignalData) {
  const pc = peer.pc
  if (data.sdp) {
    await pc.setRemoteDescription(data.sdp)
    if (data.sdp.type === "offer") {
      const audio = local?.getAudioTracks()[0] ?? null
      const video = local?.getVideoTracks()[0] ?? null
      for (const t of pc.getTransceivers()) {
        if (t.currentDirection === "stopped") continue
        const kind = t.receiver.track?.kind
        if (!t.sender.track) await t.sender.replaceTrack(kind === "audio" ? audio : video)
        t.direction = "sendrecv"
        if (local) { try { t.sender.setStreams?.(local) } catch { /* old Safari */ } }
      }
      await pc.setLocalDescription(await pc.createAnswer())
      signalTo(uid, { sdp: pc.localDescription!.toJSON() })
    }
    for (const c of peer.early.splice(0)) await pc.addIceCandidate(c).catch(() => {})
  }
  for (const c of data.ice ?? []) {
    if (pc.remoteDescription) await pc.addIceCandidate(c).catch(() => {})
    else peer.early.push(c)
  }
}

async function replaceVideo(track: MediaStreamTrack | null) {
  for (const peer of peers.values()) {
    for (const t of peer.pc.getTransceivers()) {
      if (t.receiver.track?.kind === "video" && t.currentDirection !== "stopped") await t.sender.replaceTrack(track).catch(() => {})
    }
  }
}

/** Several pictures to send in a group: each one smaller (mesh: every phone sends to every other). */
function tuneVideo(others: number) {
  const max = others > 2 ? 300_000 : others > 1 ? 600_000 : 1_200_000
  for (const peer of peers.values()) {
    for (const sender of peer.pc.getSenders()) {
      if (sender.track?.kind !== "video") continue
      const p = sender.getParameters()
      if (!p.encodings?.length) continue
      p.encodings[0].maxBitrate = max
      void sender.setParameters(p).catch(() => {})
    }
  }
}
