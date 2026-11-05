// Link previews in chats, like WhatsApp (= android/.../ui/LinkPreview.kt; server/linkpreview.py): while a
// message with a web link is typed, its preview is fetched and shown above the input field (x = without);
// it is sent along with the message - inside the envelope in an encrypted chat. In the chat it shows as a
// card in the bubble; only for a link that is in the text, with the host from that link.
import { useEffect, useRef, useState } from "preact/hooks"
import { api } from "./api"
import { firstLink, linkHost, linkLayout } from "./links"
import type { LinkPreview } from "./model"
import { online } from "./store"
import { S } from "./strings"
import { IconBtn } from "./ui/components"

const WAIT_MS = 600            // typing pause before the preview is fetched
const fetched = new Map<string, LinkPreview | null>()

export { shownLink } from "./links"

/** The link preview for the text being typed: url (null: no link / dismissed), the preview when it came,
 *  loading meanwhile; dismiss = send without it. */
export function useLinkDraft(text: string) {
  const url = firstLink(text)
  const [dismissed, setDismissed] = useState<string | null>(null)
  const [, setTick] = useState(0)
  useEffect(() => { if (!text.trim()) setDismissed(null) }, [!text.trim()])
  const active = url && url !== dismissed ? url : null
  useEffect(() => {
    if (!active || fetched.has(active) || !online.value) return
    let alive = true
    const h = setTimeout(async () => {
      const r = await api.call<{ preview: LinkPreview | null }>("GET", `/link-preview?url=${encodeURIComponent(active)}`).catch(() => null)
      if (fetched.size >= 200) fetched.delete(fetched.keys().next().value!)
      fetched.set(active, r?.preview && r.preview.url === active ? r.preview : null)         // unreachable: none
      if (alive) setTick((t) => t + 1)
    }, WAIT_MS)
    return () => { alive = false; clearTimeout(h) }
  }, [active])
  const preview = active ? fetched.get(active) ?? null : null
  return { url: active, preview, loading: !!active && !fetched.has(active) && online.value, dismiss: () => setDismissed(url) }
}

/** Above the input field: the preview that will go with the message (or that it's coming); x = without. */
export function DraftLink({ preview, loading, onDismiss }: { preview: LinkPreview | null; loading: boolean; onDismiss: () => void }) {
  const s = S.value
  if (!preview && !loading) return null
  return <div class="row draft-link" style={{ background: "var(--card)", padding: "8px 4px 0 12px", gap: 10 }}>
    <i style={{ width: 3, height: 40, background: "var(--cyan)", flex: "none" }} />
    {preview?.image && <img src={`data:image/webp;base64,${preview.image}`} alt=""
      style={{ width: 40, height: 40, objectFit: "cover", borderRadius: 8, flex: "none" }} />}
    <div class="col grow" style={{ minWidth: 0 }}>
      {preview ? <>
        <span class="t-strong ellipsis" style={{ fontSize: 14 }}>{preview.title || preview.description || linkHost(preview.url)}</span>
        <span class="t-small ellipsis" style={{ fontSize: 12 }}>{linkHost(preview.url)}</span>
      </> : <span class="t-small">{s.linkPreviewLoading}</span>}
    </div>
    <IconBtn icon="ui_close" alpha={.6} onClick={onDismiss} label="without preview" />
  </div>
}

/** The preview in a chat bubble: a tap opens the page (holding is the bubble's: reactions). */
export function LinkCard({ link }: { link: LinkPreview }) {
  const layout = linkLayout(link)
  const host = linkHost(link.url)
  const down = useRef(0)
  const img = link.image ? `data:image/webp;base64,${link.image}` : null
  const ratio = Math.max(1.3, Math.min(2.4, (link.width || 16) / (link.height || 9)))
  const words = <div class="col" style={{ minWidth: 0, gap: 2, padding: layout === "small" ? 0 : "8px 10px 9px" }}>
    {link.title && <span class="t-strong user-text link-title" style={{ fontSize: 14, overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}>{link.title}</span>}
    {link.description && <span class="t-small user-text" style={{ fontSize: 12, color: "var(--text)", opacity: .8, overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}>{link.description}</span>}
    <span class="t-small ellipsis link-host" style={{ fontSize: 11 }}>{host}</span>
  </div>
  return <a class={`col link-card ${layout}`} href={link.url} target="_blank" rel="noopener noreferrer"
    onPointerDown={() => { down.current = Date.now() }}
    // a long press (the reactions) doesn't open it as well
    onClick={(e) => { e.stopPropagation(); if (Date.now() - down.current > 450) e.preventDefault() }}
    style={{ width: 260, maxWidth: "100%", borderRadius: 12, overflow: "hidden", background: "rgba(0,0,0,0.2)", color: "inherit", textDecoration: "none", WebkitTouchCallout: "none" }}>
    {layout === "big" && img && <img src={img} alt="" draggable={false} style={{ width: "100%", aspectRatio: String(ratio), objectFit: "cover", display: "block", background: "#0b0c12" }} />}
    {layout === "small" && img ? <div class="row" style={{ gap: 10, padding: 8, alignItems: "center" }}>
      <img src={img} alt="" draggable={false} style={{ width: 56, height: 56, objectFit: "cover", borderRadius: 8, flex: "none" }} />
      {words}
    </div> : words}
  </a>
}
