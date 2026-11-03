// The size of a single chat photo (android/.../ui/Chat.kt photoSize).

/** A single chat photo in its own shape (report #68: it was cut to a square): 240 px on its long side,
 *  very long / wide ones a little cut. */
export function photoSize(width?: number, height?: number): { width: number; height: number } {
  const ratio = width && height ? Math.min(2, Math.max(.5, width / height)) : 1
  return ratio >= 1 ? { width: 240, height: Math.round(240 / ratio) } : { width: Math.round(240 * ratio), height: 240 }
}
