// Videos and post sounds like on Instagram (android/.../ui/Video.kt):
//  - one sound switch for everything: turned on once (tap), every following video / sound plays
//    with sound until it's turned off again
//  - only one plays at a time: the most visible one on the screen that is on top
//  - nothing plays while the app is in the background
//  - the position carries over (feed -> comments shows the same moment); scrolled away and back, a video starts over
// iOS plays muted videos inline on its own; sound needs a tap first (the switch is that tap).
import type { ComponentChildren } from "preact"
import { createContext } from "preact"
import { signal } from "@preact/signals"
import { useContext, useEffect, useRef, useState } from "preact/hooks"
import type { TextOverlay } from "../model"
import { foreground } from "../store"
import { S } from "../strings"
import { PixelIcon, RemoteImage } from "./components"

export const soundOn = signal(false)
const candidates = signal(new Map<object, number>())
const positions = new Map<string, number>()

/* Adaptive streams like Instagram (server/hls.py): a post / story video has an HLS ladder in HEVC (`hls`), the player
 * picks the rendition that fits the connection. Safari (iPhone) plays HLS itself; where the browser can't, the MP4
 * (`url`) plays. A stream that fails (Android's Chrome says it plays HLS but can't play these HEVC streams) switches
 * every video to the MP4 until the app starts again - one failed try, not one per video (= Video.kt). */
let hlsSupport: boolean | undefined
export function canPlayHls(): boolean {
  return (hlsSupport ??= typeof document !== "undefined"
    && document.createElement("video").canPlayType("application/vnd.apple.mpegurl") !== "")
}
const streamsOff = signal(false)

/** What a <video> plays: the stream where it works, else the file. */
export function videoSrc(url: string, hls?: string | null): string {
  return hls && canPlayHls() && !streamsOff.value ? hls : url
}

/** onError of a <video> playing videoSrc(url, hls): the files from now on. */
export function streamFailed(hls?: string | null) {
  if (hls) streamsOff.value = true
}

/** False for screens covered by another one (their media must not play). */
export const OnTop = createContext(true)

/** Full-screen layers over the screens (story, big view): only media on the topmost one may play, and there only
 *  the most visible one - exactly one sound, the one of what is shown (reports #38, #42). */
const covers = signal<object[]>([])
/** The layer this content lies on (null: the screens). */
export const Cover = createContext<object | null>(null)

/** Registers a full-screen layer while the calling component is shown; returns its key (for <Cover.Provider>). */
export function useCoverLayer(): object {
  const key = useRef({}).current
  useEffect(() => {
    covers.value = [...covers.value, key]
    return () => { covers.value = covers.value.filter((k) => k !== key) }
  }, [])
  return key
}

const isTop = (cover: object | null) => (cover == null ? covers.value.length === 0 : covers.value[covers.value.length - 1] === cover)

function winner(): object | null {
  let best: object | null = null, max = .5
  for (const [k, v] of candidates.value) if (v >= max) { best = k; max = v }
  return best
}

/** Takes part in "only the most visible medium plays"; returns whether this one is active, and whether it is only held
 *  (covered by another screen / layer, in the background, not its turn): see useTrack. */
function useMediaFocus(ref: { current: HTMLElement | null }, playing: boolean): [boolean, boolean] {
  const onTop = useContext(OnTop)
  const cover = useContext(Cover)
  const key = useRef({}).current
  const [visible, setVisible] = useState(0)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver((es) => setVisible(es[0]?.intersectionRatio ?? 0), { threshold: [0, .25, .5, .75, 1] })
    io.observe(el)
    return () => { io.disconnect(); const m = new Map(candidates.value); m.delete(key); candidates.value = m }
  }, [])
  const eligible = playing && onTop && foreground.value && isTop(cover)
  useEffect(() => {
    const m = new Map(candidates.value)
    if (eligible && visible > 0) m.set(key, visible); else m.delete(key)
    candidates.value = m
  }, [eligible, visible])
  return [eligible && winner() === key, !eligible]
}

/** Plays [el] (looping) while [active]; volume follows the sound switch. Stopped while [held] (covered, in the
 *  background, not its turn), it goes on at that moment next time - also in another player of the same url (feed ->
 *  comments / big view and back). Gone from view (scrolled away, another medium more visible), it starts over, like on
 *  Instagram. */
function useTrack(el: HTMLMediaElement | null, url: string | null | undefined, active: boolean, held: boolean, audible: boolean,
  src?: string) {
  const was = useRef(false)                                                          // it was playing
  useEffect(() => {
    if (!el || !url) return
    const want = active && audible && soundOn.value
    el.muted = !want
    if (active) {
      // only when it starts playing (the sound switch alone must not move it)
      const pos = positions.get(url)
      if (!was.current && pos != null && Math.abs(pos - el.currentTime) > .3) el.currentTime = pos
      el.play().catch(() => { el.muted = true; void el.play().catch(() => {}) })      // no sound allowed yet: play muted
    } else {
      if (was.current) positions.set(url, held ? el.currentTime : 0)
      el.pause()
    }
    was.current = active
  }, [el, url, active, audible, soundOn.value, src])          // a new source (stream -> file) has to start again
  // closed while playing (comments -> feed): the feed goes on at this moment
  useEffect(() => () => { if (el && url && was.current) positions.set(url, el.currentTime) }, [el, url])
}

function SoundBadge() {
  return <div style={{ position: "absolute", right: 10, bottom: 10, borderRadius: 10, background: "rgba(0,0,0,0.4)", padding: 5 }}>
    <PixelIcon name={soundOn.value ? "note" : "ui_muted"} size={14} alpha={.9} />
  </div>
}

/** [preview]: making a post (report #65) - always muted, taps go to what's around it. [hls]: the adaptive stream
 *  (played instead of [url] where it works; [url] stays the key for the position). */
export function VideoPlayer({ url, hls, thumb, playing, soundUrl, preview }: {
  url: string; hls?: string | null; thumb?: string | null; playing: boolean; soundUrl?: string | null; preview?: boolean
}) {
  const box = useRef<HTMLDivElement>(null)
  const [video, setVideo] = useState<HTMLVideoElement | null>(null)
  const [audio, setAudio] = useState<HTMLAudioElement | null>(null)
  const [first, setFirst] = useState(false)
  const [active, held] = useMediaFocus(box, playing)
  const src = videoSrc(url, hls)
  useTrack(video, url, active, held, !soundUrl && !preview, src)
  useTrack(audio, soundUrl, active, held, true)
  return <div ref={box} class="fill" style={{ overflow: "hidden" }} onClick={preview ? undefined : () => { soundOn.value = !soundOn.value }}>
    <video ref={setVideo} src={src} poster={thumb ?? undefined} playsInline muted loop preload="metadata"
      onLoadedData={() => setFirst(true)} onError={() => streamFailed(hls)} style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }} />
    {soundUrl && <audio ref={setAudio} src={soundUrl} loop preload="none" />}
    {!first && thumb && <RemoteImage url={thumb} class="fill" />}
    {!preview && <SoundBadge />}
  </div>
}

/** A post's sound over photos or text: plays while this area is the most visible medium. */
export function SoundArea({ url, children, style }: { url: string; children: ComponentChildren; style?: preact.JSX.CSSProperties }) {
  const box = useRef<HTMLDivElement>(null)
  const [audio, setAudio] = useState<HTMLAudioElement | null>(null)
  const [active, held] = useMediaFocus(box, true)
  useTrack(audio, url, active, held, true)
  return <div ref={box} style={{ position: "relative", ...style }} onClick={() => { soundOn.value = !soundOn.value }}>
    {children}
    <audio ref={setAudio} src={url} loop preload="none" />
    <SoundBadge />
  </div>
}

/** "♪ title" under a post with a sound. */
export function SoundLabel({ title, style }: { title?: string; style?: preact.JSX.CSSProperties }) {
  return <div class="row" style={{ gap: 6, ...style }}>
    <PixelIcon name="note" size={14} alpha={.8} />
    <span class="t-small ellipsis" style={{ fontSize: 12, color: "var(--text)" }}>{title?.trim() || S.value.originalSound}</span>
  </div>
}

export const OVERLAY_COLORS: Record<string, string> = {
  white: "#ffffff", yellow: "#ffcd75", cyan: "#73eff7", pink: "#ff8ad8", lime: "#a7f070", black: "#1a1c2c",
}

/** The text over a photo / video, placed by its center at (x, y), sized by the area's width. */
export function OverlayText({ o }: { o: TextOverlay }) {
  const box = useRef<HTMLDivElement>(null)
  const [w, setW] = useState(0)
  useEffect(() => {
    const ro = new ResizeObserver(() => requestAnimationFrame(() => setW(box.current?.clientWidth ?? 0)))
    ro.observe(box.current!)
    return () => ro.disconnect()
  }, [])
  const size = w * o.size
  return <div ref={box} class="fill" style={{ pointerEvents: "none", overflow: "hidden" }}>
    {w > 0 && <div style={{
      position: "absolute", left: `${o.x * 100}%`, top: `${o.y * 100}%`, transform: "translate(-50%, -50%)", maxWidth: "90%",
      // the font the server burns the text in with (server/overlay.py): the preview is the result
      textAlign: "center", fontFamily: 'Pixelify, "Pixel Emoji"', fontWeight: 700, fontSize: size, lineHeight: 1.1,
      color: OVERLAY_COLORS[o.color] ?? "#fff", whiteSpace: "pre-wrap", overflowWrap: "break-word",
      textShadow: o.color === "black" ? "0 0 8px rgba(255,255,255,0.55)" : "0 0 8px rgba(0,0,0,0.8)",
    }}>{o.text}</div>}
  </div>
}
