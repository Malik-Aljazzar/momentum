// Photos, videos, sounds from the phone (android/.../ui/MediaPicker.kt, Crop.kt, data/ImageShrink.kt).
// iOS: the file input offers the camera, the photo library and files. Its front camera shows a mirrored
// preview but hands over the picture unmirrored, so camera photos from the front camera are mirrored here
// (like the preview, = Android's camera, report #55).
import { useEffect, useRef, useState } from "preact/hooks"
import { S } from "../strings"
import { Label, PxButton } from "./components"
import { openOverlay } from "./overlay"

/** Opens the system picker; resolves with the chosen files (empty when cancelled). */
export function pickFiles(accept: string, multiple = false, capture?: "user" | "environment"): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement("input")
    input.type = "file"
    input.accept = accept
    input.multiple = multiple
    if (capture) input.setAttribute("capture", capture)
    input.style.display = "none"
    let done = false
    const finish = (files: File[]) => { if (!done) { done = true; input.remove(); resolve(files) } }
    input.onchange = async () => {
      const files = [...(input.files ?? [])]
      finish(capture ? await Promise.all(files.map(asPreviewed)) : files)
    }
    input.addEventListener("cancel", () => finish([]))
    document.body.appendChild(input)
    input.click()
  })
}

/** The lens in a JPEG's EXIF is the front camera ("iPhone 13 front camera 2.71mm f/2.2", "... front TrueDepth camera ..."). */
export function frontCameraJpeg(head: Uint8Array): boolean {
  if (head[0] !== 0xff || head[1] !== 0xd8) return false
  let text = ""
  for (let i = 0; i < head.length; i++) text += String.fromCharCode(head[i])
  return /front (truedepth )?camera/i.test(text)
}

/** A camera photo as its preview showed it: the front camera's picture mirrored. */
async function asPreviewed(file: File): Promise<File> {
  if (!file.type.startsWith("image/")) return file
  try {
    if (!frontCameraJpeg(new Uint8Array(await file.slice(0, 128 * 1024).arrayBuffer()))) return file
    const img = await decode(file)
    const canvas = document.createElement("canvas")
    canvas.width = img.width
    canvas.height = img.height
    const g = canvas.getContext("2d")!
    g.translate(img.width, 0)
    g.scale(-1, 1)
    g.drawImage(img, 0, 0)
    img.close()
    return new File([await toBlob(canvas, "image/jpeg", .92)], file.name, { type: "image/jpeg" })
  } catch {
    return file
  }
}

async function decode(file: Blob): Promise<ImageBitmap> {
  // EXIF rotation is applied by the browser (imageOrientation: from-image is the default)
  return createImageBitmap(file)
}

function toBlob(canvas: HTMLCanvasElement, type = "image/jpeg", quality = .88): Promise<Blob> {
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("encode"))), type, quality))
}

/** Photos are scaled down before the upload (at most 2048 px on the long side): fast to send. */
export async function shrinkImage(file: File, maxSide = 2048): Promise<Blob> {
  if (!file.type.startsWith("image/") || file.type === "image/gif") return file
  try {
    const img = await decode(file)
    const k = Math.min(1, maxSide / Math.max(img.width, img.height))
    const canvas = document.createElement("canvas")
    canvas.width = Math.round(img.width * k)
    canvas.height = Math.round(img.height * k)
    canvas.getContext("2d")!.drawImage(img, 0, 0, canvas.width, canvas.height)
    img.close()
    return await toBlob(canvas)
  } catch {
    return file
  }
}

/** A photo for an encrypted chat (the server can't shrink it): JPEG with 1600 px and a 480 px copy for the chat. */
export async function securePhoto(file: File): Promise<{ full: Blob; thumb: Blob; width: number; height: number } | null> {
  try {
    const img = await decode(file)
    const scaled = async (side: number, quality: number) => {
      const k = Math.min(1, side / Math.max(img.width, img.height))
      const canvas = document.createElement("canvas")
      canvas.width = Math.round(img.width * k)
      canvas.height = Math.round(img.height * k)
      canvas.getContext("2d")!.drawImage(img, 0, 0, canvas.width, canvas.height)
      return { blob: await toBlob(canvas, "image/jpeg", quality), width: canvas.width, height: canvas.height }
    }
    const full = await scaled(1600, .85)
    const thumb = await scaled(480, .8)
    img.close()
    return { full: full.blob, thumb: thumb.blob, width: full.width, height: full.height }
  } catch {
    return null
  }
}

const MAX_ZOOM = 5
const OUTPUT_SIDE = 1024

/**
 * Full-screen square crop: drag to move, pinch to zoom; the crop window always stays inside the
 * photo. Resolves with a JPEG (max 1024x1024) or null when cancelled.
 */
export function cropImage(file: File, title?: string): Promise<Blob | null> {
  return new Promise((resolve) => {
    void decode(file).then((img) => {
      const close = openOverlay(() => <CropView img={img} title={title} onDone={(b) => { close(); img.close(); resolve(b) }} />)
    }).catch(() => resolve(null))
  })
}

function CropView({ img, title, onDone }: { img: ImageBitmap; title?: string; onDone: (b: Blob | null) => void }) {
  const s = S.value
  const box = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const view = useRef({ zoom: 1, x: 0, y: 0, crop: 0, w: 0, h: 0 })   // photo center relative to crop center (CSS px)
  const [, redraw] = useState(0)

  const base = () => (view.current.crop === 0 ? 1 : view.current.crop / Math.min(img.width, img.height))
  const clamp = (x: number, y: number, z: number) => {
    const sc = base() * z, v = view.current
    const mx = (img.width * sc - v.crop) / 2, my = (img.height * sc - v.crop) / 2
    return [Math.max(-mx, Math.min(mx, x)), Math.max(-my, Math.min(my, y))]
  }

  const draw = () => {
    const el = canvas.current, b = box.current
    if (!el || !b) return
    const dpr = window.devicePixelRatio || 1
    const v = view.current
    v.w = b.clientWidth; v.h = b.clientHeight
    v.crop = Math.min(v.w, v.h) - 48
    el.width = v.w * dpr; el.height = v.h * dpr
    const g = el.getContext("2d")!
    g.scale(dpr, dpr)
    const sc = base() * v.zoom
    const dw = img.width * sc, dh = img.height * sc
    g.drawImage(img, v.w / 2 + v.x - dw / 2, v.h / 2 + v.y - dh / 2, dw, dh)
    // dim everything outside the crop window
    const l = (v.w - v.crop) / 2, t = (v.h - v.crop) / 2, r = 28
    g.fillStyle = "rgba(0,0,0,0.7)"
    g.beginPath()
    g.rect(0, 0, v.w, v.h)
    g.roundRect(l, t, v.crop, v.crop, r)
    g.fill("evenodd")
    g.strokeStyle = "#f4f4f4"
    g.lineWidth = 2
    g.beginPath()
    g.roundRect(l, t, v.crop, v.crop, r)
    g.stroke()
  }

  useEffect(() => {
    draw()
    const ro = new ResizeObserver(draw)
    ro.observe(box.current!)
    return () => ro.disconnect()
  }, [])

  // one finger moves, two fingers zoom (and move)
  useEffect(() => {
    const el = box.current!
    const pts = new Map<number, { x: number; y: number }>()
    let last: { cx: number; cy: number; d: number } | null = null
    const gesture = () => {
      const ps = [...pts.values()]
      const cx = ps.reduce((a, p) => a + p.x, 0) / ps.length, cy = ps.reduce((a, p) => a + p.y, 0) / ps.length
      const d = ps.length > 1 ? Math.hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y) : 0
      return { cx, cy, d }
    }
    const down = (e: PointerEvent) => { el.setPointerCapture(e.pointerId); pts.set(e.pointerId, { x: e.clientX, y: e.clientY }); last = gesture() }
    const move = (e: PointerEvent) => {
      if (!pts.has(e.pointerId)) return
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY })
      const g = gesture(), v = view.current
      if (last) {
        const z = Math.max(1, Math.min(MAX_ZOOM, last.d > 0 && g.d > 0 ? v.zoom * g.d / last.d : v.zoom))
        ;[v.x, v.y] = clamp(v.x + g.cx - last.cx, v.y + g.cy - last.cy, z)
        v.zoom = z
        draw()
      }
      last = g
    }
    const up = (e: PointerEvent) => { pts.delete(e.pointerId); last = pts.size ? gesture() : null }
    const wheel = (e: WheelEvent) => {
      e.preventDefault()
      const v = view.current
      const z = Math.max(1, Math.min(MAX_ZOOM, v.zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1)))
      ;[v.x, v.y] = clamp(v.x, v.y, z)
      v.zoom = z
      draw()
    }
    el.addEventListener("pointerdown", down)
    el.addEventListener("pointermove", move)
    el.addEventListener("pointerup", up)
    el.addEventListener("pointercancel", up)
    el.addEventListener("wheel", wheel, { passive: false })
    return () => {
      el.removeEventListener("pointerdown", down); el.removeEventListener("pointermove", move)
      el.removeEventListener("pointerup", up); el.removeEventListener("pointercancel", up); el.removeEventListener("wheel", wheel)
    }
  }, [])

  const result = async () => {
    const v = view.current
    const sc = base() * v.zoom
    const side = Math.min(Math.round(v.crop / sc), Math.min(img.width, img.height))
    const cx = img.width / 2 - v.x / sc, cy = img.height / 2 - v.y / sc
    const left = Math.max(0, Math.min(img.width - side, Math.round(cx - side / 2)))
    const top = Math.max(0, Math.min(img.height - side, Math.round(cy - side / 2)))
    const out = document.createElement("canvas")
    out.width = out.height = Math.min(side, OUTPUT_SIDE)
    out.getContext("2d")!.drawImage(img, left, top, side, side, 0, 0, out.width, out.height)
    onDone(await toBlob(out, "image/jpeg", .92))
  }

  void redraw
  return <div class="fill col" style={{ background: "var(--bg)", paddingTop: "var(--safe-top)", paddingBottom: "var(--safe-bottom)" }}>
    <div class="col" style={{ padding: 20 }}>
      <Label text={title ?? s.profile} />
      <span class="t-title" style={{ fontSize: 22 }}>{s.cropTitle}</span>
    </div>
    <div ref={box} style={{ flex: 1, position: "relative", touchAction: "none", minHeight: 0 }}>
      <canvas ref={canvas} style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} />
    </div>
    <div class="t-small" style={{ textAlign: "center", padding: 12 }}>{s.cropHint}</div>
    <div class="row" style={{ padding: 16, gap: 10 }}>
      <PxButton text={s.cancel} kind="ghost" class="grow" onClick={() => onDone(null)} />
      <PxButton text={s.usePhoto} class="grow" onClick={result} />
    </div>
  </div>
}
