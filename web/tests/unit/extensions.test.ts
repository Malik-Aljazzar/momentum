import { describe, expect, it } from "vitest"
import { ApiError } from "../../src/api"
import { answer, type BridgeDeps, extensionTabs, MAX_MESSAGE, parseRequest, withOrigin } from "../../src/extensions"
import type { InstalledExtension } from "../../src/model"

/** A fake app: records the server calls, answers from a little table. */
function fake(answers: Record<string, unknown> = {}) {
  const calls: [string, string, unknown][] = []
  const toasts: string[] = []
  const deps: BridgeDeps = {
    call: async (method, path, body) => {
      calls.push([method, path, body])
      const a = answers[`${method} ${path}`]
      if (a instanceof Error) throw a
      return a ?? {}
    },
    toast: (t) => toasts.push(t),
    context: () => ({ api: 1, lang: "de", user: { username: "ann", display_name: "ann" }, extension: { id: "journal", version: "1.0.0" } }),
  }
  return { deps, calls, toasts }
}

const ask = (method: string, params: Record<string, unknown> = {}, id = 7) => ({ momentum: 1 as const, id, method, params })

describe("the extension bridge (server/shipped_extensions/sdk.js <-> the app)", () => {
  it("takes only its own messages", () => {
    expect(parseRequest(JSON.stringify(ask("context")))).toEqual(ask("context"))
    expect(parseRequest(JSON.stringify({ id: 1, method: "context" }))).toBeNull()             // not the bridge's
    expect(parseRequest({ momentum: 1, id: 1, method: "context" })).toBeNull()                 // objects aren't sent
    expect(parseRequest("{oops")).toBeNull()
    expect(parseRequest(JSON.stringify({ ...ask("data.put"), params: { value: "x".repeat(MAX_MESSAGE) } }))).toBeNull()
  })

  it("answers with the player's context and shows toasts (short ones)", async () => {
    const { deps, toasts } = fake()
    expect((await answer("journal", ask("context"), deps)).result).toMatchObject({ lang: "de", extension: { id: "journal" } })
    await answer("journal", ask("toast", { text: "x".repeat(300) }), deps)
    expect(toasts[0]).toHaveLength(140)
  })

  it("reaches only its own data on the server, every part escaped", async () => {
    const { deps, calls } = fake({
      "GET /extensions/journal/data/entries/2026-11-18": { doc: { key: "2026-11-18", value: { text: "hi" } } },
      "PUT /extensions/journal/data/entries/a%2F..%2Fb": { doc: { key: "a/../b", value: 1 } },
    })
    expect((await answer("journal", ask("data.get", { collection: "entries", key: "2026-11-18" }), deps)).result).toEqual({ text: "hi" })
    await answer("journal", ask("data.put", { collection: "entries", key: "a/../b", value: 1 }), deps)
    await answer("journal", ask("data.delete", { collection: "entries", key: "k" }), deps)
    await answer("journal", ask("data.list", { collection: "entries", offset: -5, limit: 1000, order: "drop table" }), deps)
    expect(calls.map(([m, p]) => `${m} ${p}`)).toEqual([
      "GET /extensions/journal/data/entries/2026-11-18",
      "PUT /extensions/journal/data/entries/a%2F..%2Fb",
      "DELETE /extensions/journal/data/entries/k",
      "GET /extensions/journal/data/entries?offset=0&limit=100&order=key",
    ])
    expect(calls[1][2]).toEqual({ value: 1 })
  })

  it("a missing document is null, other errors reach the page with their status", async () => {
    const { deps } = fake({
      "GET /extensions/journal/data/entries/none": new ApiError("Not found", 404),
      "PUT /extensions/journal/data/entries/big": new ApiError("value.text: wrong length", 422),
    })
    expect(await answer("journal", ask("data.get", { collection: "entries", key: "none" }), deps)).toEqual({ momentum: 1, id: 7, result: null })
    expect((await answer("journal", ask("data.put", { collection: "entries", key: "big", value: {} }), deps)).error)
      .toEqual({ message: "value.text: wrong length", status: 422 })
    expect((await answer("journal", ask("app.token"), deps)).error?.status).toBe(400)
  })

  it("tabs: switched-on extensions with a tab, in their order", () => {
    const e = (id: string, enabled: boolean, tab: boolean): InstalledExtension =>
      ({ id, version: "1.0.0", name: { de: id, en: id }, icon: "note", enabled, tab: tab ? { title: { de: id, en: id }, icon: "note" } : null })
    expect(extensionTabs([e("a", true, true), e("b", false, true), e("c", true, false), e("d", true, true)]).map((x) => x.id)).toEqual(["a", "d"])
    expect(extensionTabs(undefined)).toEqual([])
  })

  it("fills in the server's address", () => {
    expect(withOrigin('<base href="__ORIGIN__/"><meta content="font-src __ORIGIN__">', "https://m.example"))
      .toBe('<base href="https://m.example/"><meta content="font-src https://m.example">')
  })
})
