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

  it("marks a lost connection", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch") }))
    const e = await api.raw("GET", "/state").catch((x: unknown) => x) as ApiError
    expect([e.status, e.fromServer]).toEqual([0, false])
  })
})
