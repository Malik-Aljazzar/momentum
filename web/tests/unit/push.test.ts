// Notifications of what was read in the app are taken away (chat-<id>, note-<id> tags).
// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest"
import { clearNotifications } from "../../src/push"

describe("clearNotifications", () => {
  it("closes only the matching shown notifications", async () => {
    const shown = ["chat-1", "chat-2", "note-7"].map((tag) => ({ tag, close: vi.fn() }))
    Object.defineProperty(window, "PushManager", { value: class {}, configurable: true })
    Object.defineProperty(navigator, "serviceWorker", {
      value: { getRegistration: async () => ({ getNotifications: async () => shown }) }, configurable: true,
    })
    await clearNotifications((tag) => tag === "chat-1")
    expect(shown.map((n) => n.close.mock.calls.length)).toEqual([1, 0, 0])
    await clearNotifications((tag) => tag.startsWith("note-"))
    expect(shown[2].close).toHaveBeenCalledOnce()
  })

  it("does nothing without service worker support", async () => {
    Object.defineProperty(navigator, "serviceWorker", { value: undefined, configurable: true })
    await expect(clearNotifications(() => true)).resolves.toBeUndefined()
  })
})
