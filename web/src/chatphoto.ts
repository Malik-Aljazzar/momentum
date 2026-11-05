// Photos and videos in a chat bubble (android/.../ui/Chat.kt CHAT_MEDIA_DP, videoLength).

/** A single chat photo / video: a 240 px square, cut to fit like WhatsApp (it was its own shape, report #68);
 *  the big view shows all of it. */
export const CHAT_MEDIA = 240

/** A video's length on its still: "0:07", "1:00", "10:05" ("" without one). */
export function videoLength(seconds?: number): string {
  const s = Math.round(seconds ?? 0)
  if (!(s > 0)) return ""
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`
}
