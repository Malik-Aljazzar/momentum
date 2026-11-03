// Making a post or a story (android/.../ui/Compose.kt): a compact form - pictures in a strip (tap one:
// text and rules for it), a caption, then small chips for sound, place and tagged people.
import { signal } from "@preact/signals"
import { useEffect, useRef, useState } from "preact/hooks"
import type { MediaItem, Rule, TextOverlay } from "../model"
import { nav } from "../nav"
import { feedVersion, online, refresh, req, showToast, state, uploadMedia } from "../store"
import { S } from "../strings"
import { IconBtn, Label, MentionSuggestions, OfflineState, PixelIcon, PxButton, PxTextArea, Sheet, TopBar, UserRow } from "../ui/components"
import { pickFiles, shrinkImage } from "../ui/media"
import { openOverlay } from "../ui/overlay"
import { OverlayText, VideoPlayer } from "../ui/video"
import { MicButton } from "../ui/voice"
import {
  clampRange, ConnectedPeople, type Draft, OverlayEditor, type Place, PlaceSheet, type SoundDraft, soundDraft, SoundEditor, SongSearch,
} from "./Feed"

/** Bumped when a story was shared: the feed's story row loads again. */
export const storiesVersion = signal(0)

const MAX_RULES = 5
const MAX_TIMES = 10

/** A picture being prepared, with the rules tapped with it ({rule id: times}). */
type Slide = Draft & { rules: Record<number, number> }

/** Rules that may go with a picture: shown on the profile, tappable, not a challenge copy. */
export const profileRules = (rules: Rule[]) => rules.filter((r) => r.id > 0 && r.public && r.tappable !== false && r.challenge_id == null)

export const NewPostScreen = () => <Composer story={false} />
export const NewStoryScreen = () => <Composer story />

function Composer({ story }: { story: boolean }) {
  const s = S.value
  const max = story ? 1 : 10
  const [slides, setSlides] = useState<Slide[]>([])
  const [text, setText] = useState("")
  const [tags, setTags] = useState<Set<string>>(new Set())
  const [location, setLocation] = useState<Place | null>(null)
  const [sheet, setSheet] = useState<"sound" | "place" | "tags" | number | null>(null)
  const [busy, setBusy] = useState(false)
  const [sound, setSound] = useState<SoundDraft | null>(null)
  const [storySecs, setStorySecs] = useState(5)                     // a photo story's length (report #39)
  const keyN = useRef(0)
  const patch = (key: number, p: Partial<Slide>) => setSlides((list) => list.map((d) => (d.key === key ? { ...d, ...p } : d)))

  const [cameraAsk, setCameraAsk] = useState(false)
  /** camera: "photo" / "video" (one type each: with both, Chrome opens the gallery instead) */
  const add = async (camera: "photo" | "video" | null) => {
    const accept = camera === "photo" ? "image/*" : camera === "video" ? "video/*" : "image/*,video/*"
    const files = await pickFiles(accept, !camera && !story, camera ? "environment" : undefined)
    for (const file of files.slice(0, max - slides.length)) {
      const video = file.type.startsWith("video")
      // a video plays (muted) right away from the file (report #65)
      const d: Slide = { key: keyN.current++, file, video, preview: URL.createObjectURL(file), media: null, failed: false, overlay: null, rules: {}, progress: 0 }
      setSlides((list) => [...list, d])
      void (async () => {
        // photos are scaled down first (camera pictures are several MB); the server shrinks videos
        const data = video ? file : await shrinkImage(file)
        const media = await uploadMedia(data, video ? file.type || "video/mp4" : data.type || "image/jpeg", "", (done) => patch(d.key, { progress: done }))
        patch(d.key, { media, failed: media == null, progress: undefined })
      })()
    }
  }
  const uploading = slides.some((d) => !d.media && !d.failed)
  // a story's sound is exactly as long as the story: the photo's chosen length, or the video's
  const first = slides[0]
  const storyMs = !story ? undefined : first?.video ? Math.min(30_000, Math.round((first.media?.duration || 30) * 1000)) : storySecs * 1000
  useEffect(() => {
    if (sound && storyMs) {
      const [a, b] = clampRange(sound.durationMs, sound.startMs, sound.endMs, storyMs)
      if (a !== sound.startMs || b !== sound.endMs) setSound({ ...sound, startMs: a, endMs: b })
    }
  }, [storyMs, sound?.url])
  const ready = !uploading && !busy && (story ? slides.some((d) => d.media) : text.trim() !== "" || slides.some((d) => d.media))

  const publish = async () => {
    if (!ready) return
    setBusy(true)
    // the chosen part of the sound goes up first
    let soundMedia: MediaItem | null = null
    if (sound) {
      // always as audio (= Android): a video picked as sound (iOS screen recording) became a video and the story was refused (report #60)
      const type = sound.blob.type.startsWith("audio/") ? sound.blob.type : "audio/mp4"
      soundMedia = await uploadMedia(sound.blob, type, `?start=${sound.startMs / 1000}&seconds=${(sound.endMs - sound.startMs) / 1000}`)
      if (!soundMedia) { setBusy(false); return }
    }
    const done = slides.filter((d) => d.media)
    const triggers = done.flatMap((d, slide) => Object.entries(d.rules).filter(([, n]) => n > 0)
      .map(([id, times]) => ({ slide, rule_id: Number(id), times })))
    const soundBody = sound && soundMedia ? { media_id: soundMedia.id, title: sound.title.trim() } : undefined
    let ok
    if (story) {
      ok = await req("POST", "/stories", { media_id: done[0].media!.id, overlay: done[0].overlay, sound: soundBody, triggers,
        ...(done[0].video ? {} : { duration: storySecs }) })
      if (ok) { showToast(s.storyShared); storiesVersion.value++ }
    } else {
      const overlays: Record<string, TextOverlay> = {}
      for (const d of done) if (d.overlay) overlays[String(d.media!.id)] = d.overlay
      ok = await req("POST", "/posts", {
        text, media_ids: done.map((d) => d.media!.id), tags: [...tags], overlays, triggers,
        ...(location ? { location } : {}), ...(soundBody ? { sound: soundBody } : {}),
      })
      if (ok) { showToast(s.shared); feedVersion.value++ }
    }
    if (ok) { if (triggers.length) void refresh(); nav.pop() }
    setBusy(false)
  }

  const editText = (d: Slide) => {
    const ratio = d.media?.width && d.media?.height ? d.media.width / d.media.height : story ? 9 / 16 : 4 / 5
    const close = openOverlay(() => <OverlayEditor image={d.video ? d.media?.thumb ?? null : d.preview} ratio={ratio} initial={d.overlay}
      onClose={() => close()} onDone={(o) => { close(); patch(d.key, { overlay: o }) }} />)
  }
  const open = typeof sheet === "number" ? slides.find((d) => d.key === sheet) : undefined

  return <div class="fill col compose">
    <TopBar title={story ? s.newStory : s.newPost}>
      <button class="t-button" disabled={!ready} onClick={publish} style={{
        marginRight: 8, borderRadius: 12, padding: "10px 16px", background: ready ? "var(--text)" : "var(--faint)", color: ready ? "#000" : "var(--muted)",
      }}>{story ? s.shareStory : s.publish}</button>
    </TopBar>
    {!online.value ? <OfflineState /> : <div class="scroll col" style={{ flex: 1, padding: "8px 14px 24px", gap: 12 }}>
      {/* nothing picked yet: camera and gallery side by side over the whole width (report #41) */}
      {slides.length === 0 ? <div class="row add-row" style={{ gap: 8, flex: "none" }}>
        <AddTile icon="camera" label={s.camera} height={story ? 160 : 120} onClick={() => setCameraAsk(true)} />
        <AddTile icon="ui_image" label={s.gallery} height={story ? 160 : 120} onClick={() => add(null)} />
      </div>
        // the pictures (a tap opens text and rules for that one), then a narrow column to add more
        : <div class="row" style={{ overflowX: "auto", gap: 8, flex: "none", paddingBottom: 2 }}>
          {slides.map((d) => <SlideTile key={d.key} d={d} story={story} onOpen={() => setSheet(d.key)}
            onRemove={() => setSlides((l) => l.filter((x) => x.key !== d.key))} />)}
          {slides.length < max && <div class="col" style={{ gap: 8, flex: "none", width: 72 }}>
            <AddTile icon="camera" label={s.camera} height={story ? 81 : 56} onClick={() => setCameraAsk(true)} />
            <AddTile icon="ui_image" label={s.gallery} height={story ? 81 : 56} onClick={() => add(null)} />
          </div>}
        </div>}
      {slides.length > 0 && <span class="t-small" style={{ fontSize: 11, marginTop: -6 }}>{s.tapToEdit}</span>}
      {!story && <>
        <PxTextArea class="t-body" value={text} placeholder={s.captionHint} rows={3} onInput={setText} user
          style={{ fontSize: 17, lineHeight: "23px", background: "var(--faint)", padding: "10px 12px" }} />
        <MentionSuggestions text={text} onPick={setText} />
      </>}
      {/* the rest as small chips */}
      <div class="row" style={{ flexWrap: "wrap", gap: 8 }}>
        <Chip icon="note" label={sound ? sound.title || s.originalSound : s.addSound} on={!!sound} onClick={() => setSheet("sound")} />
        {!story && <Chip icon="compass" label={location?.name ?? s.addLocation} on={!!location} onClick={() => setSheet("place")} />}
        {!story && <Chip icon="smile" label={tags.size ? [...tags].map((t) => "@" + t).join(", ") : s.tagPeople} on={tags.size > 0} onClick={() => setSheet("tags")} />}
      </div>
      {location && <button class="t-small" style={{ alignSelf: "flex-start", color: "var(--muted)" }} onClick={() => setLocation(null)}>✕ {location.name}</button>}
      {/* how long a photo story runs */}
      {story && first && !first.video && <div class="row story-length" style={{ gap: 8, alignItems: "center" }}>
        <Label text={s.storyDuration} />
        {[5, 10, 15, 30].map((n) => <button key={n} class="t-button" onClick={() => setStorySecs(n)} style={{
          borderRadius: 10, padding: "8px 12px", background: storySecs === n ? "var(--text)" : "var(--faint)", color: storySecs === n ? "#000" : "var(--text)",
        }}>{n} s</button>)}
      </div>}
      {sound && <SoundEditor sd={sound} fixedMs={storyMs} onChange={setSound} onRemove={() => setSound(null)} />}
    </div>}

    {open && <Sheet onClose={() => setSheet(null)} label={s.slideRules}>
      <div class="row" style={{ gap: 12, alignItems: "flex-start" }}>
        <div style={{ position: "relative", width: 96, height: 120, borderRadius: 12, overflow: "hidden", background: "var(--faint)", flex: "none" }}>
          {(open.media?.thumb ?? (open.video ? null : open.preview)) && <img src={(open.media?.thumb ?? open.preview)!} style={{ width: "100%", height: "100%", objectFit: "cover" }} />}
          {open.overlay && <OverlayText o={open.overlay} />}
        </div>
        <div class="col grow" style={{ gap: 8 }}>
          <PxButton text={"Aa · " + s.textOnImage} kind="ghost" onClick={() => editText(open)} />
          <PxButton text={s.delete} kind="danger" onClick={() => { setSlides((l) => l.filter((x) => x.key !== open.key)); setSheet(null) }} />
        </div>
      </div>
      <RulePicker picked={open.rules} onChange={(rules) => patch(open.key, { rules })} />
      <PxButton text={s.done} onClick={() => setSheet(null)} />
    </Sheet>}
    {cameraAsk && <Sheet onClose={() => setCameraAsk(false)} label={s.camera}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.camera}</div>
      <PxButton text={s.takePhoto} onClick={() => { setCameraAsk(false); void add("photo") }} />
      <PxButton text={s.takeVideo} kind="ghost" onClick={() => { setCameraAsk(false); void add("video") }} />
    </Sheet>}
    {sheet === "place" && <PlaceSheet onClose={() => setSheet(null)} onPick={(p) => { setLocation(p); setSheet(null) }} />}
    {sheet === "tags" && <Sheet onClose={() => setSheet(null)} label={s.tagPeople}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.tagPeople}</div>
      <ConnectedPeople context="tag">{(people) => <div class="col" style={{ maxHeight: 380, overflowY: "auto" }}>
        {people.map((u) => {
          const on = tags.has(u.username)
          return <UserRow key={u.username} user={u} onClick={() => { const n = new Set(tags); if (on) n.delete(u.username); else n.add(u.username); setTags(n) }}
            trailing={<i style={{ width: 22, height: 22, borderRadius: 6, background: on ? "var(--lime)" : "var(--faint)" }} />} />
        })}
      </div>}</ConnectedPeople>
      <PxButton text={s.done} onClick={() => setSheet(null)} />
    </Sheet>}
    {sheet === "sound" && <Sheet onClose={() => setSheet(null)} label={s.addSound}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.addSound}</div>
      {/* a song like on Instagram (Deezer's 30 s preview), taken like an own sound file */}
      <SongSearch onPick={async (song) => {
        const r = await fetch(`/api/songs/${song.id}/preview`).catch(() => null)
        if (!r?.ok) { showToast(s.uploadFailed, true); return }
        setSound(await soundDraft(await r.blob(), [song.artist, song.title].filter(Boolean).join(" – ")))
        setSheet(null)
      }} />
      <Label text={s.ownSound} />
      <PxButton text={s.pickAudioFile} kind="ghost" onClick={async () => {
        setSheet(null)
        const [f] = await pickFiles("audio/*")
        if (f) setSound(await soundDraft(f, f.name.replace(/\.[^.]+$/, "")))
      }} />
      <div class="row">
        <MicButton onRecording={() => {}} onHint={showToast} onRecorded={async (b) => { setSheet(null); setSound(await soundDraft(b, s.originalSound)) }} />
        <span class="t-body">{s.recordSound}</span>
      </div>
    </Sheet>}
  </div>
}

function SlideTile({ d, story, onOpen, onRemove }: { d: Slide; story: boolean; onOpen: () => void; onRemove: () => void }) {
  const n = Object.values(d.rules).filter((t) => t > 0).length
  const w = 96, h = story ? 170 : 120
  return <div class="slide-tile" style={{ position: "relative", width: w, height: h, borderRadius: 14, overflow: "hidden", background: "var(--faint)", flex: "none",
    border: "1px solid var(--line)", cursor: "pointer" }} onClick={onOpen}>
    {d.video && d.preview ? <VideoPlayer url={d.preview} thumb={d.media?.thumb} playing preview />
      : (d.media?.thumb ?? d.preview) && <img src={(d.media?.thumb ?? d.preview)!} style={{ width: "100%", height: "100%", objectFit: "cover" }} />}
    {d.overlay && <OverlayText o={d.overlay} />}
    {d.video && <div style={{ position: "absolute", bottom: 6, left: 6 }}><PixelIcon name="ui_video" size={14} /></div>}
    {!d.media && <div class="fill center t-small" style={{ background: "rgba(0,0,0,0.6)", color: "var(--text)" }}>{d.failed ? "!" : <UploadBar progress={d.progress} />}</div>}
    <div style={{ position: "absolute", top: -4, left: -4 }} onClick={(e) => e.stopPropagation()}>
      <IconBtn icon="ui_close" alpha={.85} onClick={onRemove} label="remove" />
    </div>
    {n > 0 && <span class="t-block" style={{ position: "absolute", top: 6, right: 6, fontSize: 10, padding: "2px 5px", borderRadius: 8,
      background: "rgba(10,12,20,0.6)", color: "var(--yellow)" }}>⚡{n}</span>}
  </div>
}

/** An upload's progress bar: sent (with %), then the server's turn (= Compose.kt UploadBar). */
export function UploadBar({ progress }: { progress?: number }) {
  const s = S.value
  const sending = progress == null || progress < 1
  const done = Math.max(0, Math.min(1, progress ?? 0))
  return <div class="col upload-bar" data-testid="upload-bar" style={{ alignItems: "center", gap: 4, padding: "0 8px", width: "100%" }}>
    <span class="t-small" style={{ fontSize: 11, color: "var(--text)", textAlign: "center" }}>{sending ? s.uploading : s.processing}</span>
    {/* square pixel bar: the part sent, or a block moving back and forth while the server works */}
    <div style={{ position: "relative", width: "100%", height: 6, background: "var(--faint)", overflow: "hidden" }}>
      {sending ? <i style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: done * 100 + "%", background: "var(--cyan)" }} />
        : <i class="upload-busy" style={{ position: "absolute", top: 0, bottom: 0, width: "30%", background: "var(--cyan)" }} />}
    </div>
    {sending && <span class="t-small" style={{ fontSize: 10 }}>{Math.floor(done * 100)} %</span>}
  </div>
}

/** Camera / gallery: fills its row or column (width from the parent), [height] fixed. */
function AddTile({ icon, label, height, onClick }: { icon: string; label: string; height: number; onClick: () => void }) {
  const big = height >= 100
  return <button class="col center add-tile" onClick={onClick} style={{
    flex: 1, minWidth: 0, width: "100%", height, borderRadius: 14, border: "2px dashed var(--line)", flexDirection: "column", gap: 4,
  }}>
    <PixelIcon name={icon} size={big ? 28 : 18} alpha={.8} />
    <span class="t-small" style={{ fontSize: 11 }}>{label}</span>
  </button>
}

function Chip({ icon, label, on, onClick }: { icon: string; label: string; on: boolean; onClick: () => void }) {
  return <button class="row chip" onClick={onClick} style={{
    gap: 6, padding: "8px 12px", borderRadius: 12, maxWidth: "100%",
    background: on ? "rgba(115,239,247,0.10)" : "var(--faint)", border: `1px solid ${on ? "var(--cyan)" : "var(--line)"}`,
  }}>
    <PixelIcon name={icon} size={16} />
    <span class="t-small ellipsis" style={{ color: on ? "var(--cyan)" : "var(--text)", fontSize: 13 }}>{label}</span>
  </button>
}

/** Up to 5 profile rules for a picture, each tapped up to 10 times. */
function RulePicker({ picked, onChange }: { picked: Record<number, number>; onChange: (p: Record<number, number>) => void }) {
  const s = S.value
  const rules = profileRules(state.value?.rules ?? [])
  const count = Object.values(picked).filter((n) => n > 0).length
  const set = (id: number, n: number) => {
    if (n > 0 && !(picked[id] > 0) && count >= MAX_RULES) { showToast(s.maxRulesPerSlide, true); return }
    onChange({ ...picked, [id]: Math.max(0, Math.min(MAX_TIMES, n)) })
  }
  return <div class="col" style={{ gap: 6 }}>
    <Label text={`${s.slideRules} · ${count}/${MAX_RULES}`} />
    <span class="t-small" style={{ fontSize: 12 }}>{rules.length ? s.slideRulesHint : s.noProfileRules}</span>
    <div class="col" style={{ maxHeight: 300, overflowY: "auto" }}>
      {rules.map((r) => {
        const n = picked[r.id] ?? 0
        return <div key={r.id} class="row rule-pick" style={{ gap: 10, padding: "6px 0" }}>
          <PixelIcon name={r.icon} size={24} />
          <span class="t-body grow ellipsis" style={{ color: n ? "var(--text)" : "var(--muted)" }}>{r.name}</span>
          <button aria-label="less" class="t-block" onClick={() => set(r.id, n - 1)} style={{ width: 30, height: 30, borderRadius: 8, background: "var(--faint)", opacity: n ? 1 : .35 }}>−</button>
          <span class="t-block" style={{ width: 28, textAlign: "center", fontSize: 13, color: r.kind === "acc" ? "var(--lime)" : "var(--orange)" }}>{n ? `×${n}` : "–"}</span>
          <button aria-label="more" class="t-block" onClick={() => set(r.id, n + 1)} style={{ width: 30, height: 30, borderRadius: 8, background: "var(--faint)" }}>+</button>
        </div>
      })}
    </div>
  </div>
}
