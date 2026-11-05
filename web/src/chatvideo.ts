// A video for an encrypted chat: the server can't re-encode it, so the phone does (= android/.../data/VideoCompress.kt):
// 720p on the short side, ~2 Mbit/s, at most 60 s, with a 480 px still for the bubble (like a photo's small copy).
// Browsers with WebCodecs (iOS 16.4+, Chrome) shrink it with mediabunny, loaded only then; elsewhere - or when that
// fails - the video goes as it is when it's short and small enough. Plain chats send the file as it is: the server
// re-encodes it there.

/** = server media.VIDEO_MAX_SECONDS */
export const VIDEO_SECONDS = 60
/** = server e2e.MAX_VIDEO */
export const VIDEO_BYTES = 100 * 1024 * 1024
const SHORT_SIDE = 720
const BITRATE = 2_000_000
const STILL_SIDE = 480

export interface VideoInfo { width: number; height: number; duration: number; still: Blob | null }
export interface SecureVideo extends VideoInfo { full: Blob; mime: string }

/** A picked file is a video (some pickers leave the type empty). */
export const isVideoFile = (f: File) => f.type.startsWith("video/") || (!f.type && /\.(mov|mp4|m4v|webm|3gp)$/i.test(f.name))

/** Width, height, length and a still (JPEG, 480 px on the long side) of a video, read by the browser's own player;
 *  zeros and no still when it can't play it. */
export function videoInfo(file: Blob, timeoutMs = 10_000): Promise<VideoInfo> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const v = document.createElement("video")
    v.muted = true
    v.playsInline = true
    v.preload = "auto"
    let done = false
    const finish = (still: Blob | null) => {
      if (done) return
      done = true
      clearTimeout(timer)
      clearTimeout(late)
      const info = { width: v.videoWidth || 0, height: v.videoHeight || 0, duration: Number.isFinite(v.duration) ? v.duration : 0, still }
      v.removeAttribute("src")
      v.load()
      URL.revokeObjectURL(url)
      resolve(info)
    }
    const grab = () => {
      const w = v.videoWidth, h = v.videoHeight
      if (!w || !h) return finish(null)
      const k = Math.min(1, STILL_SIDE / Math.max(w, h))
      const canvas = document.createElement("canvas")
      canvas.width = Math.max(1, Math.round(w * k))
      canvas.height = Math.max(1, Math.round(h * k))
      try {
        canvas.getContext("2d")!.drawImage(v, 0, 0, canvas.width, canvas.height)
        canvas.toBlob((b) => finish(b), "image/jpeg", .8)
      } catch {
        finish(null)
      }
    }
    const timer = setTimeout(() => finish(null), timeoutMs)
    let late = 0
    v.onerror = () => finish(null)
    // a moment in (the very first frame is often black); a player that never reports the seek: the frame it has
    v.onloadedmetadata = () => { v.currentTime = Math.min(.3, (Number.isFinite(v.duration) ? v.duration : 0) / 2) }
    v.onloadeddata = () => { late = window.setTimeout(grab, 2000) }
    v.onseeked = grab
    v.src = url
  })
}

/** The video re-encoded in the browser (null: no WebCodecs, a track it would have to drop, or it failed). */
async function shrink(file: Blob, onProgress: (done: number) => void): Promise<Blob | null> {
  if (typeof VideoEncoder === "undefined" || typeof VideoDecoder === "undefined") return null
  try {
    const mb = await import("mediabunny")
    const input = new mb.Input({ source: new mb.BlobSource(file), formats: [mb.MP4, mb.QTFF, mb.WEBM, mb.MATROSKA] })
    const track = await input.getPrimaryVideoTrack()
    if (!track) return null
    const w = track.displayWidth, h = track.displayHeight
    const k = Math.min(1, SHORT_SIDE / Math.min(w, h))
    const even = (x: number) => Math.max(2, Math.round((x * k) / 2) * 2)          // H.264 wants even sizes
    const output = new mb.Output({ format: new mb.Mp4OutputFormat({ fastStart: "in-memory" }), target: new mb.BufferTarget() })
    // the sound is kept as it is (AAC from phones; iOS before 26 can't encode audio in the browser)
    const conversion = await mb.Conversion.init({
      input, output, trim: { start: 0, end: VIDEO_SECONDS },
      video: { width: even(w), height: even(h), fit: "fill", codec: "avc", quality: new mb.Quality({ bitrate: BITRATE }), forceTranscode: true },
    })
    // a video without its sound would be worse than the big original
    if (!conversion.isValid || conversion.discardedTracks.some((d) => d.track.isAudioTrack() || d.track.isVideoTrack())) return null
    conversion.onProgress = (p) => onProgress(Math.max(0, Math.min(1, p)))
    await conversion.execute()
    const buf = output.target.buffer
    return buf && buf.byteLength > 0 ? new Blob([buf], { type: "video/mp4" }) : null
  } catch {
    return null
  }
}

/** A picked video ready to be encrypted and sent; null when it can't go (too long / too big and not shrinkable). */
export async function secureVideo(file: File, onProgress: (done: number) => void = () => {}): Promise<SecureVideo | null> {
  const shrunk = await shrink(file, onProgress)
  if (shrunk) {
    const info = await videoInfo(shrunk)
    return { ...info, duration: Math.min(VIDEO_SECONDS, info.duration), full: shrunk, mime: "video/mp4" }
  }
  const info = await videoInfo(file)
  if (file.size > VIDEO_BYTES || info.duration > VIDEO_SECONDS + .5) return null
  return { ...info, full: file, mime: file.type || "video/mp4" }
}
