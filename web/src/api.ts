// Client for the Quart JSON API (/api/...), same origin. Mirrors android/.../data/Api.kt.

import { DE, EN } from "./strings"

export class ApiError extends Error {
  /** `fromServer`: the text is the server's own explanation (its JSON `error`), not a worded status code. */
  constructor(message: string, readonly status: number, readonly fromServer = true) { super(message) }
}

export const api = {
  token: null as string | null,
  language: "de",
  /** Called on HTTP 426 (only sent to outdated APKs; the PWA updates itself, but stay safe). */
  onUpdateRequired: () => {},

  headers(extra: Record<string, string> = {}): Record<string, string> {
    const h: Record<string, string> = { "Accept-Language": api.language, ...extra }
    if (api.token) h.Authorization = `Bearer ${api.token}`
    return h
  },

  async raw(method: string, path: string, body?: unknown, timeoutMs = 15000): Promise<string> {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), timeoutMs)
    let res: Response
    try {
      res = await fetch("/api" + path, {
        method,
        headers: api.headers({ "Content-Type": "application/json" }),
        body: body !== undefined ? JSON.stringify(body) : method === "POST" || method === "PUT" ? "{}" : undefined,
        signal: ctl.signal,
      })
    } catch {
      throw new ApiError("Server nicht erreichbar", 0, false)
    } finally {
      clearTimeout(timer)
    }
    const text = await res.text()
    if (res.status === 426) api.onUpdateRequired()
    if (!res.ok) throw failure(text, res.status)
    return text
  },

  async call<T>(method: string, path: string, body?: unknown): Promise<T> {
    return JSON.parse(await api.raw(method, path, body)) as T
  },

  /** POST raw bytes (photo, video, audio) and return the parsed answer. */
  async upload<T>(path: string, data: Blob, type: string): Promise<T> {
    let res: Response
    try {
      res = await fetch("/api" + path, { method: "POST", headers: api.headers({ "Content-Type": type }), body: data })
    } catch {
      throw new ApiError("Server nicht erreichbar", 0, false)
    }
    const text = await res.text()
    if (!res.ok) throw failure(text, res.status)
    return JSON.parse(text) as T
  },
}

/** The server's explanation (JSON `error`); without one (nginx while the app restarts, a proxy page) the status
 *  is worded in the user's language: 502/503/504 = the server is down for a moment. */
function failure(text: string, status: number): ApiError {
  let explained: string | undefined
  try { explained = (JSON.parse(text) as { error?: string }).error } catch { /* no JSON */ }
  if (explained) return new ApiError(explained, status)
  const s = api.language === "de" ? DE : EN
  return new ApiError(status >= 502 && status <= 504 ? s.serverDown(status) : s.serverError(status), status, false)
}
