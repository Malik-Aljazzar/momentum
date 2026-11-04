// @vitest-environment node
// (esbuild, which bundles the worker, refuses jsdom's TextEncoder)
// The service worker (scripts/sw-plugin.ts): a new build's precache asks the server for every file instead of
// taking the browser's HTTP-cached copy as it is (old 5-frame vehicle sheets made the vehicles blink).
import { runInNewContext } from "node:vm"
import { describe, expect, it } from "vitest"
import { serviceWorker } from "../../scripts/sw-plugin"

function swSource(): string {
  let source = ""
  const plugin = serviceWorker("test") as unknown as { generateBundle: (this: unknown, o: unknown, b: Record<string, unknown>) => void }
  plugin.generateBundle.call({ emitFile: (f: { source: string }) => { source = f.source } }, {}, { "assets/index-test.js": {} })
  return source
}

describe("service worker", () => {
  it("precaches the app checked with the server, not straight from the HTTP cache", async () => {
    const handlers: Record<string, (e: unknown) => void> = {}
    const added: { url: string; cache?: string }[] = []
    const self = { addEventListener: (kind: string, f: (e: unknown) => void) => { handlers[kind] = f }, skipWaiting: async () => {} }
    const caches = { open: async () => ({ addAll: async (reqs: { url: string; cache?: string }[]) => { added.push(...reqs) } }) }
    function Request(url: string, init: { cache?: string } = {}) { return { url, cache: init.cache } }
    runInNewContext(swSource(), {
      self, caches, Request, location: { origin: "https://example.org" }, TextEncoder, TextDecoder, crypto, atob, btoa,
    })
    let installed: Promise<unknown> = Promise.resolve()
    handlers.install({ waitUntil: (p: Promise<unknown>) => { installed = p } })
    await installed
    const urls = added.map((r) => r.url)
    expect(urls).toContain("/app/")
    expect(urls).toContain("/app/assets/index-test.js")
    expect(urls).toContain("/app/sprites/pkw/sheet.png")
    for (const r of added) expect(r.cache, r.url).toBe("no-cache")
  })
})
