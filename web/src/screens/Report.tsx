// Bug report or feature request with a description and screenshots (android/.../ui/Report.kt).
import { useState } from "preact/hooks"
import type { MediaItem } from "../model"
import { nav } from "../nav"
import { refresh, req, showToast, uploadMedia } from "../store"
import { fmt } from "../format"
import { S } from "../strings"
import { Label, PixelIcon, PxButton, TopBar } from "../ui/components"
import { pickFiles, shrinkImage } from "../ui/media"

declare const __BUILD__: string

interface Attachment { key: number; preview: string; media: MediaItem | null; failed: boolean }

export function ReportScreen() {
  const s = S.value
  const [kind, setKind] = useState("bug")
  const [text, setText] = useState("")
  const [sending, setSending] = useState(false)
  const [files, setFiles] = useState<Attachment[]>([])
  const uploading = files.some((f) => !f.media && !f.failed)
  const ready = text.trim().length >= 3 && !uploading && !sending
  const add = async () => {
    for (const f of (await pickFiles("image/*", true)).slice(0, 6 - files.length)) {
      const a: Attachment = { key: Math.random(), preview: URL.createObjectURL(f), media: null, failed: false }
      setFiles((l) => [...l, a])
      const data = await shrinkImage(f)
      const media = await uploadMedia(data, data.type || "image/jpeg")
      setFiles((l) => l.map((x) => (x.key === a.key ? { ...x, media, failed: !media } : x)))
    }
  }
  return <div class="fill col">
    <TopBar title={s.reportTitle} />
    <div class="scroll col" style={{ flex: 1, padding: 16, gap: 14 }}>
      <div class="row" style={{ gap: 8 }}>
        {([["bug", s.reportBug], ["feature", s.reportFeature]] as const).map(([k, label]) =>
          <button key={k} class={"chip t-button" + (kind === k ? " on" : "")} onClick={() => setKind(k)}>{label}</button>)}
      </div>
      <span class="t-small">{kind === "bug" ? s.reportBugHint : s.reportFeatureHint}</span>
      <textarea class="field user-text" rows={6} value={text} placeholder={s.reportPlaceholder} style={{ minHeight: 140 }}
        onInput={(e) => { const v = (e.target as HTMLTextAreaElement).value; if (v.length <= 4000) setText(v) }} />
      <Label text={s.reportImages} />
      <div class="row" style={{ flexWrap: "wrap", gap: 8 }}>
        {files.map((a) => <div key={a.key} style={{ position: "relative", width: 84, height: 84, borderRadius: 12, overflow: "hidden", background: "var(--card2)" }}>
          <img src={a.preview} style={{ width: "100%", height: "100%", objectFit: "cover", opacity: a.media ? 1 : .45 }} />
          <button aria-label="remove" style={{ position: "absolute", top: 4, right: 4 }} onClick={() => setFiles(files.filter((x) => x.key !== a.key))}><PixelIcon name="ui_close" size={14} /></button>
          {a.failed && <div class="fill center"><PixelIcon name="storm" size={20} /></div>}
        </div>)}
        {files.length < 6 && <button class="center" onClick={add} style={{ width: 84, height: 84, borderRadius: 12, background: "var(--faint)" }}><PixelIcon name="ui_plus" size={20} alpha={.7} /></button>}
      </div>
      <PxButton text={sending ? "…" : s.reportSend} style={{ width: "100%", opacity: ready ? 1 : .4 }} onClick={async () => {
        if (!ready) return
        setSending(true)
        const body = {
          kind, text: text.trim(), media_ids: files.filter((f) => f.media).map((f) => f.media!.id),
          platform: "pwa", app_version: `web ${__BUILD__}`, device: navigator.userAgent.slice(0, 200),
        }
        const sent = await req<{ bonus?: number }>("POST", "/reports", body)
        if (sent) {
          const bonus = sent.bonus ?? 0
          showToast(bonus >= 1 ? s.reportThanksBonus(fmt(bonus)) : s.reportThanks)
          if (bonus >= 1) void refresh()            // the bonus shows in the history
          nav.pop()
        }
        setSending(false)
      }} />
    </div>
  </div>
}
