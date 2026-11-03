// Failed requests carry the server's explanation; without one (nginx while the app restarts) the status is worded.
import { afterEach, describe, expect, it, vi } from "vitest"
import { api, ApiError } from "../../src/api"
import { DE, EN } from "../../src/strings"

const answer = (status: number, body: string) =>
  vi.fn(async () => new Response(body, { status, headers: { "Content-Type": "text/html" } }))

describe("api errors", () => {
  afterEach(() => vi.unstubAllGlobals())

  it("shows the server's own explanation", async () => {
    vi.stubGlobal("fetch", answer(507, JSON.stringify({ error: "Der Server-Speicher ist voll.", storage_full: true })))
    const e = await api.raw("POST", "/media").catch((x: unknown) => x) as ApiError
    expect(e).toBeInstanceOf(ApiError)
    expect([e.message, e.status, e.fromServer]).toEqual(["Der Server-Speicher ist voll.", 507, true])
  })

  it("words a bad gateway in the user's language", async () => {
    vi.stubGlobal("fetch", answer(502, "<html>502 Bad Gateway</html>"))
    api.language = "de"
    let e = await api.upload("/media", new Blob(["x"]), "image/png").catch((x: unknown) => x) as ApiError
    expect([e.message, e.status, e.fromServer]).toEqual([DE.serverDown(502), 502, false])
    expect(e.message).toContain("502")
    api.language = "en"
    e = await api.raw("GET", "/state").catch((x: unknown) => x) as ApiError
    expect(e.message).toBe(EN.serverDown(502))
    vi.stubGlobal("fetch", answer(418, "teapot"))
    e = await api.raw("GET", "/state").catch((x: unknown) => x) as ApiError
    expect([e.message, e.fromServer]).toEqual([EN.serverError(418), false])
  })

  it("tells how far an upload is (the progress bar) and still words its failures", async () => {
    // a fake XMLHttpRequest: sends in two steps, then answers
    let reply = { status: 200, text: JSON.stringify({ media: { id: 7 } }) }
    class FakeXhr {
      upload: { onprogress?: (e: { lengthComputable: boolean; loaded: number; total: number }) => void } = {}
      status = 0; responseText = ""; headers: Record<string, string> = {}
      onload?: () => void; onerror?: () => void; ontimeout?: () => void
      open() {}
      setRequestHeader(k: string, v: string) { this.headers[k] = v }
      send() {
        this.upload.onprogress?.({ lengthComputable: true, loaded: 50, total: 100 })
        this.upload.onprogress?.({ lengthComputable: true, loaded: 100, total: 100 })
        this.status = reply.status; this.responseText = reply.text
        this.onload?.()
      }
    }
    vi.stubGlobal("XMLHttpRequest", FakeXhr)
    const seen: number[] = []
    const r = await api.upload<{ media: { id: number } }>("/media", new Blob(["x"]), "video/mp4", (d) => seen.push(d))
    expect(r.media.id).toBe(7)
    expect(seen).toEqual([.5, 1])
    reply = { status: 503, text: "<html>busy</html>" }
    api.language = "en"
    const e = await api.upload("/media", new Blob(["x"]), "video/mp4", () => {}).catch((x: unknown) => x) as ApiError
    expect([e.message, e.status]).toEqual([EN.serverDown(503), 503])
  })

  it("marks a lost connection", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch") }))
    const e = await api.raw("GET", "/state").catch((x: unknown) => x) as ApiError
    expect([e.status, e.fromServer]).toEqual([0, false])
  })
})
