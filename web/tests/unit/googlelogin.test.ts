import { describe, expect, it } from "vitest"
import { googleReturn, googleStartUrl } from "../../src/googlelogin"

describe("google sign-in return", () => {
  it("reads the token from the fragment", () => {
    expect(googleReturn("#token=abc-_123")).toEqual({ token: "abc-_123" })
  })
  it("tells a cancelled sign-in from a failed one", () => {
    expect(googleReturn("#google=cancel")).toEqual({ failed: false })
    expect(googleReturn("#google=error")).toEqual({ failed: true })
  })
  it("ignores other fragments", () => {
    expect(googleReturn("")).toBeNull()
    expect(googleReturn("#settings")).toBeNull()
  })
  it("starts in the player's language", () => {
    expect(googleStartUrl("de")).toBe("/api/auth/google/start?lang=de")
    expect(googleStartUrl("fr")).toBe("/api/auth/google/start?lang=en")
  })
})
