// Notifications of what was read in the app are taken away (chat-<id>, note-<id> tags).
// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest"
import { callNote } from "../../src/calls"
import { clearNotifications, NOTIFY_HINT_DAYS, notifyHint, notifyHintUntil, postponeNotifyHint } from "../../src/push"

describe("the home tab's card while notifications are off (= Android NotifyHintTest)", () => {
  const base = { wanted: true, permission: "default" as const, ios: false, installed: false, hiddenUntil: 0, now: 1_000 }
  it("asks while a tap can still allow them, else points to the phone's settings", () => {
    expect(notifyHint(base)).toBe("ask")
    expect(notifyHint({ ...base, permission: "denied" })).toBe("denied")
    expect(notifyHint({ ...base, permission: "granted" })).toBeNull()
    expect(notifyHint({ ...base, permission: "unsupported" })).toBeNull()
  })
  it("on an iPhone only the home-screen app gets them", () => {
    expect(notifyHint({ ...base, ios: true, permission: "unsupported" })).toBe("install")
    expect(notifyHint({ ...base, ios: true, installed: true })).toBe("ask")
    expect(notifyHint({ ...base, ios: true, installed: true, permission: "granted" })).toBeNull()
  })
  it("stays away when switched off in the app, and a week after 'not now'", () => {
    expect(notifyHint({ ...base, wanted: false })).toBeNull()
    postponeNotifyHint(base.now)
    expect(notifyHintUntil.value).toBe(base.now + NOTIFY_HINT_DAYS * 86_400_000)
    expect(localStorage.getItem("notify_hint_until")).toBe(String(notifyHintUntil.value))
    expect(notifyHint({ ...base, hiddenUntil: notifyHintUntil.value })).toBeNull()
    expect(notifyHint({ ...base, hiddenUntil: notifyHintUntil.value, now: notifyHintUntil.value })).toBe("ask")
  })
})

describe("a call's notifications", () => {
  it("are its ringing rounds and its missed one, not another call's", () => {
    const mine = callNote(7)
    expect(["call-7", "call-7-0", "call-7-3"].every(mine)).toBe(true)
    expect(["call-70", "call-70-1", "chat-7", "call-"].some(mine)).toBe(false)
  })
})

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
