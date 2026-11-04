// Big view of photos / videos (posts, stories, chat) and the rules tapped with a picture
// (android/.../ui/MediaViewer.kt).
import { signal } from "@preact/signals"
import { useEffect, useRef, useState } from "preact/hooks"
import type { MediaItem, Trigger } from "../model"
import { fmt, signed } from "../format"
import { S } from "../strings"
import { IconBtn, PixelIcon, RemoteImage, SlidingText } from "../ui/components"
import { RuleDetailSheet } from "./shared"
import { useCoverLayer } from "../ui/video"

export interface ViewerItem { media: MediaItem; caption?: string | null; triggers?: Trigger[] }

/** The open big view: its pictures and the one shown first. */
export const viewer = signal<{ items: ViewerItem[]; index: number } | null>(null)
/** A tapped rule trigger: its detail sheet (like on the poster's profile). */
export const openRule = signal<number | null>(null)

export const openViewer = (items: ViewerItem[], index = 0) => { if (items.length) viewer.value = { items, index } }

/** Photos / videos flagged by the content filter (server/moderation.py) that were tapped open - for this app run,
 *  in every view (= ui/Sensitive.kt). */
export const revealed = signal<ReadonlySet<number>>(new Set())
export const isCovered = (m: MediaItem) => !!m.sensitive && !!m.cover && !revealed.value.has(m.id)
export const reveal = (m: MediaItem) => { revealed.value = new Set([...revealed.value, m.id]) }

/** A flagged photo / video: its pixel mosaic with a warning; a tap (on the button only with `button`: stories,
 *  where taps elsewhere go to the next story) shows the media. */
export function SensitiveCover({ media, fit = "cover", button = false }: { media: MediaItem; fit?: "cover" | "contain"; button?: boolean }) {
  const s = S.value
  const open = (e: Event) => { e.stopPropagation(); reveal(media) }
  const stop = (e: Event) => e.stopPropagation()
  return <div class="fill sensitive-cover" role={button ? undefined : "button"} aria-label={s.sensitiveTitle}
    style={{ background: "#0b0c12", cursor: button ? undefined : "pointer" }}
    onPointerDown={button ? undefined : stop} onPointerUp={button ? undefined : stop} onClick={button ? undefined : open}>
    <RemoteImage url={media.cover} class="fill" fit={fit} />
    <div class="col" style={{ position: "absolute", inset: 0, alignItems: "center", justifyContent: "center", gap: 8, padding: 24, textAlign: "center", pointerEvents: "none" }}>
      <PixelIcon name="ui_eye" size={28} />
      <span class="t-strong" style={{ color: "#fff" }}>{s.sensitiveTitle}</span>
      <span class="t-small" style={{ color: "rgba(255,255,255,0.8)", maxWidth: 260 }}>{s.sensitiveHint}</span>
      <button class="btn t-button ghost" style={{ pointerEvents: "auto", marginTop: 4 }}
        onPointerDown={stop} onPointerUp={stop} onClick={open}>{s.sensitiveShow}</button>
    </div>
  </div>
}

/** Rendered once at the app's root. */
export function MediaHost() {
  const v = viewer.value
  return <>
    {v && <MediaViewer items={v.items} start={v.index} onClose={() => { viewer.value = null }} />}
    {openRule.value != null && <RuleDetailSheet ruleId={openRule.value} onClose={() => { openRule.value = null }} />}
  </>
}

/** The rules tapped with a picture: small pills in its top right corner, a tap shows the rule. */
export function TriggerBadges({ triggers, top = 8 }: { triggers?: Trigger[] | null; top?: number }) {
  if (!triggers?.length) return null
  return <div class="col trigger-badges" style={{ position: "absolute", top, right: 8, gap: 4, alignItems: "flex-end", zIndex: 3 }}>
    {triggers.map((t, i) => <button key={i} class="row" aria-label={t.name} title={t.name}
      onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}
      onClick={(e) => { e.stopPropagation(); if (t.rule_id != null) openRule.value = t.rule_id }}
      style={{ gap: 4, padding: "3px 6px", borderRadius: 10, background: "rgba(10,12,20,0.45)", backdropFilter: "blur(2px)" }}>
      <PixelIcon name={t.icon} size={14} />
      {t.times > 1 && <span class="t-block" style={{ fontSize: 10, color: t.kind === "acc" ? "var(--lime)" : "var(--orange)" }}>×{t.times}</span>}
    </button>)}
  </div>
}

/** A story's rules (report #30): each on its own line - icon, the name (sliding when it's long),
 *  how often, and the points it gave / took. A tap shows the rule. */
export function TriggerList({ triggers, top = 92 }: { triggers?: Trigger[] | null; top?: number }) {
  if (!triggers?.length) return null
  return <div class="col trigger-list" style={{ position: "absolute", top, left: 12, gap: 6, zIndex: 3, maxWidth: "78%" }}>
    {triggers.map((t, i) => <button key={i} class="row" aria-label={t.name}
      onPointerDown={(e) => e.stopPropagation()} onPointerUp={(e) => e.stopPropagation()}
      onClick={(e) => { e.stopPropagation(); if (t.rule_id != null) openRule.value = t.rule_id }}
      style={{ gap: 8, padding: "6px 10px", borderRadius: 12, background: "rgba(10,12,20,0.62)", backdropFilter: "blur(3px)", alignSelf: "flex-start", maxWidth: "100%" }}>
      <PixelIcon name={t.icon} size={18} />
      <SlidingText text={t.name} class="t-small" style={{ color: "#fff", fontSize: 13, maxWidth: 170 }} />
      {t.times > 1 && <span class="t-small" style={{ color: "rgba(255,255,255,0.75)", fontSize: 12, flex: "none" }}>×{t.times}</span>}
      {!!t.points && <span class="t-block" style={{ fontSize: 12, flex: "none", color: t.points > 0 ? "var(--lime)" : "var(--orange)" }}>{signed(t.points, fmt)}</span>}
    </button>)}
  </div>
}

/** Full screen, swipe sideways through the pictures, X closes; the caption under each. */
function MediaViewer({ items, start, onClose }: { items: ViewerItem[]; start: number; onClose: () => void }) {
  const pager = useRef<HTMLDivElement>(null)
  const [page, setPage] = useState(start)
  useCoverLayer()                    // the feed under the big view stays quiet (report #42)
  // only the shown page's video plays (swiping on left the last one running)
  useEffect(() => {
    pager.current?.querySelectorAll("video").forEach((v, i) => {
      if (i === page) void v.play().catch(() => {}); else v.pause()
    })
  }, [page])
  useEffect(() => {
    const el = pager.current
    if (el) el.scrollLeft = start * el.clientWidth
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose() }
    addEventListener("keydown", key)
    return () => removeEventListener("keydown", key)
  }, [])
  return <div class="fill media-viewer" role="dialog" aria-label="viewer" style={{ position: "fixed", zIndex: 84, background: "#000" }}>
    <div ref={pager} class="fill" onScroll={() => { const el = pager.current!; setPage(Math.round(el.scrollLeft / el.clientWidth)) }}
      style={{ display: "flex", overflowX: "auto", scrollSnapType: "x mandatory", scrollbarWidth: "none" }}>
      {items.map((it, i) => <div key={i} class="col" style={{ flex: "0 0 100%", height: "100%", scrollSnapAlign: "start", position: "relative" }}>
        <div style={{ flex: 1, position: "relative", minHeight: 0 }}>
          {isCovered(it.media) ? <SensitiveCover media={it.media} fit="contain" />
            : it.media.kind === "video"
            ? <video src={it.media.url} poster={it.media.thumb} controls playsInline loop autoPlay={i === page}
              style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "contain" }} />
            : <RemoteImage url={it.media.url} class="fill" fit="contain" fallback={<RemoteImage url={it.media.thumb} class="fill" fit="contain" />} />}
          <TriggerBadges triggers={it.triggers} top={64} />
        </div>
        {it.caption?.trim() && <div class="t-body user-text" style={{ padding: "12px 16px calc(var(--safe-bottom) + 16px)", whiteSpace: "pre-wrap", color: "var(--text)" }}>{it.caption}</div>}
      </div>)}
    </div>
    <div class="row" style={{ position: "absolute", top: "calc(var(--safe-top, 0px) + 8px)", left: 8, right: 16, alignItems: "center" }}>
      <IconBtn icon="ui_close" onClick={onClose} label="close" />
      <span class="grow" />
      {items.length > 1 && <span class="t-small" style={{ color: "var(--text)" }}>{page + 1} / {items.length}</span>}
    </div>
  </div>
}
