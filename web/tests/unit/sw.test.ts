// @vitest-environment node
// (esbuild, which bundles the worker, refuses jsdom's TextEncoder)
// The service worker (scripts/sw-plugin.ts), run in a sandbox: a new build's precache asks the server for every file
// instead of taking the browser's HTTP-cached copy as it is (old 5-frame vehicle sheets made the vehicles blink); a
// call rings in rounds, each one a notification of its own (an iPhone sounds / buzzes only for a new one).
import { runInNewContext } from "node:vm"
import { describe, expect, it } from "vitest"
import { serviceWorker } from "../../scripts/sw-plugin"

function swSource(): string {
  let source = ""
  const plugin = serviceWorker("test") as unknown as { generateBundle: (this: unknown, o: unknown, b: Record<string, unknown>) => void }
  plugin.generateBundle.call({ emitFile: (f: { source: string }) => { source = f.source } }, {}, { "assets/index-test.js": {} })
  return source
}

interface Note { title: string; tag: string; close(): void }

/** The worker with a fake browser around it: its notifications, the open app's messages. */
function worker(extra: Record<string, unknown> = {}) {
  const handlers: Record<string, (e: unknown) => void> = {}
  const notes: Note[] = []
  const messages: unknown[] = []
  const note = (title: string, tag: string): Note => {
    const n: Note = { title, tag, close: () => { const i = notes.indexOf(n); if (i >= 0) notes.splice(i, 1) } }
    return n
  }
  const self = {
    addEventListener: (kind: string, f: (e: unknown) => void) => { handlers[kind] = f },
    skipWaiting: async () => {},
    registration: {
      // like the browser: the same tag replaces the shown one
      showNotification: async (title: string, o: { tag: string }) => {
        for (const n of notes.filter((x) => x.tag === o.tag)) n.close()
        notes.push(note(title, o.tag))
      },
      getNotifications: async () => [...notes],
    },
    clients: { matchAll: async () => [{ postMessage: (m: unknown) => messages.push(m) }] },
  }
  runInNewContext(swSource(), {
    self, location: { origin: "https://example.org" }, TextEncoder, TextDecoder, crypto, atob, btoa, ...extra,
  })
  const event = async (kind: string, e: Record<string, unknown> = {}) => {
    let done: Promise<unknown> = Promise.resolve()
    handlers[kind]({ ...e, waitUntil: (p: Promise<unknown>) => { done = p } })
    await done
  }
  const push = (data: Record<string, unknown>) => event("push", { data: { json: () => data, text: () => JSON.stringify(data) } })
  return { event, push, notes, messages }
}

describe("service worker", () => {
  it("precaches the app checked with the server, not straight from the HTTP cache", async () => {
    const added: { url: string; cache?: string }[] = []
    const caches = { open: async () => ({ addAll: async (reqs: { url: string; cache?: string }[]) => { added.push(...reqs) } }) }
    function Request(url: string, init: { cache?: string } = {}) { return { url, cache: init.cache } }
    await worker({ caches, Request }).event("install")
    const urls = added.map((r) => r.url)
    expect(urls).toContain("/app/")
    expect(urls).toContain("/app/assets/index-test.js")
    expect(urls).toContain("/app/sprites/pkw/sheet.png")
    for (const r of added) expect(r.cache, r.url).toBe("no-cache")
  })

  it("rings in rounds: each one a new notification, the call's earlier ones closed, missed closes them all", async () => {
    const w = worker()
    const ring = (round: number, call = 7) => ({ title: "Ann", body: "Eingehender Sprachanruf", tag: `call-${call}`, call, ring: true, round })
    await w.push(ring(0, 70))                                  // another call (its tag starts the same)
    await w.push({ title: "Ben", body: "Hi", tag: "chat-3" })
    await w.push(ring(0))
    expect(w.notes.map((n) => n.tag)).toEqual(["call-70-0", "chat-3", "call-7-0"])
    await w.push(ring(1))
    await w.push(ring(2))
    expect(w.notes.map((n) => n.tag)).toEqual(["call-70-0", "chat-3", "call-7-2"])
    // the open app hears of it (it clears a call it isn't ringing for any more: calls.ts)
    expect(w.messages).toContainEqual({ type: "call-push", call: 7 })
    await w.push({ title: "Ann", body: "Verpasster Sprachanruf", tag: "call-7", call: 7 })
    expect(w.notes.map((n) => [n.tag, n.title])).toEqual([["call-70-0", "Ann"], ["chat-3", "Ben"], ["call-7", "Ann"]])
    // an older server's ringing (no round) still works
    await w.push({ title: "Cat", body: "Eingehender Videoanruf", tag: "call-9", call: 9, ring: true })
    expect(w.notes.at(-1)!.tag).toBe("call-9-0")
  })
})
