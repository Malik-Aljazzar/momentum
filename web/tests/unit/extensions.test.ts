import { describe, expect, it } from "vitest"
import { ApiError } from "../../src/api"
import { answer, appDeps, type BridgeDeps, MAX_MESSAGE, parseRequest, withOrigin } from "../../src/extensions"

/** A fake app: records the server calls, answers from a little table. */
function fake(answers: Record<string, unknown> = {}, permissions: string[] = []) {
  const calls: [string, string, unknown][] = []
  const toasts: string[] = []
  const taps: number[] = []
  const undone: number[] = []
  const asked: (string | null)[] = []
  const deps: BridgeDeps = {
    call: async (method, path, body) => {
      calls.push([method, path, body])
      const a = answers[`${method} ${path}`]
      if (a instanceof Error) throw a
      return a ?? {}
    },
    toast: (t) => toasts.push(t),
    context: () => ({ api: 1, lang: "de", user: { username: "ann", display_name: "ann" }, extension: { id: "journal", version: "1.0.0" } }),
    permissions,
    rules: () => [{ id: 3, name: "Sport", icon: "ball", kind: "acc", value: 40 }],
    tap: async (rule) => {
      if (rule === 99) throw new ApiError("Tageslimit erreicht", 409)
      taps.push(rule)
      return { event: 500 + rule, delta: 40, capped: false }
    },
    undo: async (event) => { undone.push(event) },
    // the sheet: "star" picked when it showed "ball", closed without a pick otherwise
    pickIcon: async (current) => { asked.push(current); return current === "ball" ? "star" : null },
  }
  return { deps, calls, toasts, taps, undone, asked }
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
    // the app's own bridge tells the page which of its tabs it is
    const journal = { id: "journal", version: "1.1.0", name: { de: "T", en: "J" }, icon: "openbook", tabs: [], tab: null, enabled: true }
    expect(appDeps(journal, "entries").context()).toMatchObject({ extension: { id: "journal" }, tab: "entries" })
    expect(appDeps(journal).context()).toMatchObject({ tab: null })
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


  it("the player's rules only with the permission: list, tap, take back", async () => {
    const without = fake()
    expect((await answer("daylist", ask("rules.list"), without.deps)).error).toEqual({ message: "permission rules missing", status: 403 })
    expect((await answer("daylist", ask("rules.tap", { rule: 3 }), without.deps)).error?.status).toBe(403)
    expect(without.taps).toEqual([])
    const { deps, taps, undone } = fake({}, ["rules"])
    expect((await answer("daylist", ask("rules.list"), deps)).result).toEqual([{ id: 3, name: "Sport", icon: "ball", kind: "acc", value: 40 }])
    expect((await answer("daylist", ask("rules.tap", { rule: 3 }), deps)).result).toEqual({ event: 503, delta: 40, capped: false })
    expect(taps).toEqual([3])
    expect((await answer("daylist", ask("rules.tap", { rule: 99 }), deps)).error).toEqual({ message: "Tageslimit erreicht", status: 409 })
    expect((await answer("daylist", ask("rules.tap", { rule: "x" }), deps)).error?.status).toBe(400)
    expect((await answer("daylist", ask("rules.undo", { event: 503 }), deps)).result).toBe(true)
    expect(undone).toEqual([503])
  })

  it("opens the rules' icon picker for the page: the pick, or null when closed (no permission needed)", async () => {
    const { deps, asked } = fake()
    expect((await answer("daylist", ask("pickIcon", { current: "ball" }), deps)).result).toBe("star")
    expect((await answer("daylist", ask("pickIcon", {}), deps)).result).toBeNull()
    await answer("daylist", ask("pickIcon", { current: 5 }), deps)
    expect(asked).toEqual(["ball", null, null])          // only a text is a current icon
    // the app's bridge without a sheet picks nothing
    const ext = { id: "daylist", version: "1.1.0", name: { de: "D", en: "D" }, icon: "check", tabs: [], tab: null, enabled: true }
    expect(await appDeps(ext, "list").pickIcon("star")).toBeNull()
  })

  it("fills in the server's address", () => {
    expect(withOrigin('<base href="__ORIGIN__/"><meta content="font-src __ORIGIN__">', "https://m.example"))
      .toBe('<base href="https://m.example/"><meta content="font-src https://m.example">')
  })
})
