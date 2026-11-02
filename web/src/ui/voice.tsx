// Voice messages and recorded post sounds (android/.../ui/Voice.kt). MediaRecorder gives m4a on
// iOS and webm elsewhere; the server converts every recording to AAC.
import { useEffect, useRef, useState } from "preact/hooks"
import { foreground } from "../store"
import { S } from "../strings"
import { PixelIcon, SegBar } from "./components"

const KEEP_STREAM_MS = 30_000

class VoiceRecorder {
  private rec: MediaRecorder | null = null
  private chunks: Blob[] = []
  // kept for a while after a recording: the iOS home-screen app asks for the microphone again for
  // every new stream, and the dialog takes the finger off the button (report #51)
  private stream: MediaStream | null = null
  private idle = 0
  startedAt = 0

  /** Microphone stream; asking for it may show the permission dialog (slow). */
  async open(): Promise<boolean> {
    clearTimeout(this.idle)
    if (this.stream?.getAudioTracks().some((t) => t.readyState === "live")) return true
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      return true
    } catch {
      this.release()
      return false
    }
  }

  start(): boolean {
    try {
      const type = ["audio/mp4", "audio/webm;codecs=opus", "audio/webm"].find((t) => MediaRecorder.isTypeSupported?.(t))
      this.rec = new MediaRecorder(this.stream!, type ? { mimeType: type } : undefined)
      this.chunks = []
      this.rec.ondataavailable = (e) => { if (e.data.size) this.chunks.push(e.data) }
      this.rec.start()
      this.startedAt = Date.now()
      return true
    } catch {
      this.release()
      return false
    }
  }

  get recording() { return this.rec !== null }

  /** Stop; the recording if it's long enough to send (too short / failed: null). */
  stop(keep: boolean): Promise<Blob | null> {
    const rec = this.rec
    if (!rec) return Promise.resolve(null)
    const long = Date.now() - this.startedAt >= 800
    return new Promise((resolve) => {
      rec.onstop = () => {
        const blob = new Blob(this.chunks, { type: rec.mimeType || "audio/mp4" })
        this.rec = null
        this.idle = window.setTimeout(() => this.release(), KEEP_STREAM_MS)
        resolve(keep && long && blob.size > 0 ? blob : null)
      }
      try { rec.stop() } catch { this.release(); resolve(null) }
    })
  }

  release() {
    clearTimeout(this.idle)
    this.stream?.getTracks().forEach((t) => t.stop())
    this.stream = null
    this.rec = null
  }
}

/** Hold to record, release to send. [onHint] shows a toast: a tap (too short to be a message), the
 *  microphone just allowed (the dialog took the press) or not allowed (= Voice.kt). */
export function MicButton({ onRecording, onHint, onRecorded }: {
  onRecording: (since: number | null) => void; onHint: (text: string) => void; onRecorded: (b: Blob, seconds: number) => void
}) {
  const rec = useRef(new VoiceRecorder()).current
  const holding = useRef(false)
  useEffect(() => () => rec.release(), [])
  useEffect(() => { if (!foreground.value && !rec.recording) rec.release() }, [foreground.value])
  const down = async (e: PointerEvent) => {
    e.preventDefault()
    ;(e.currentTarget as Element).setPointerCapture?.(e.pointerId)
    holding.current = true
    if (!(await rec.open())) { holding.current = false; onHint(S.value.micBlocked); return }
    if (!holding.current) { onHint(S.value.micReady); return }                // released while the mic was asked for
    if (!rec.start()) { holding.current = false; onHint(S.value.micBlocked); return }
    onRecording(rec.startedAt)
  }
  const up = async (keep: boolean) => {
    if (!holding.current) return
    holding.current = false
    if (!rec.recording) return                  // still waiting for the microphone: down() handles it
    onRecording(null)
    const seconds = (Date.now() - rec.startedAt) / 1000
    const blob = await rec.stop(keep)
    if (blob) onRecorded(blob, seconds); else if (keep) onHint(S.value.holdToRecord)
  }
  return <button aria-label="mic" style={{ padding: 10, borderRadius: 12, touchAction: "none" }}
    onPointerDown={down} onPointerUp={() => up(true)} onPointerCancel={() => up(false)} onContextMenu={(e) => e.preventDefault()}>
    <PixelIcon name="ui_mic" size={24} />
  </button>
}

/** "● 0:07  release to send" while recording. */
export function RecordingIndicator({ since, hint }: { since: number; hint: string }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => { const h = setInterval(() => setNow(Date.now()), 200); return () => clearInterval(h) }, [])
  const s = Math.floor((now - since) / 1000)
  return <div class="row grow" style={{ padding: 12, gap: 10 }}>
    <i style={{ width: 10, height: 10, borderRadius: 5, background: s % 2 === 0 ? "var(--orange)" : "var(--red)" }} />
    <span class="t-block" style={{ fontSize: 16 }}>{`${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`}</span>
    <span class="t-small">{hint}</span>
  </div>
}

/** Voice message in a bubble: play/pause, progress, duration. */
export function AudioBubble({ url, duration }: { url: string; duration: number }) {
  const audio = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  const [progress, setProgress] = useState(0)
  // app in the background: stop
  useEffect(() => { if (!foreground.value) { audio.current?.pause(); setPlaying(false) } }, [foreground.value])
  const s = Math.floor(duration)
  return <div class="row" style={{ padding: 6, width: 220, gap: 10 }}>
    <audio ref={audio} src={url} preload="none"
      onTimeUpdate={(e) => { const a = e.currentTarget; setProgress(a.duration ? a.currentTime / a.duration : 0) }}
      onEnded={() => { setPlaying(false); setProgress(0) }} />
    <button class="center" style={{ width: 40, height: 40, borderRadius: 20, background: "var(--faint)", flex: "none" }} onClick={(e) => {
      e.stopPropagation()
      const a = audio.current!
      if (playing) a.pause(); else void a.play()
      setPlaying(!playing)
    }}><PixelIcon name={playing ? "ui_pause" : "ui_play"} size={18} /></button>
    <div class="grow"><SegBar fraction={progress} segments={14} color="var(--lime)" /></div>
    <span class="t-small" style={{ fontSize: 12, color: "var(--text)" }}>{`${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`}</span>
  </div>
}
