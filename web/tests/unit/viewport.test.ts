import { describe, expect, it } from "vitest"
import { appFrame, appHeight } from "../../src/viewport"

describe("app height on iOS home-screen apps (reports #28, #36)", () => {
  it("takes the whole screen, also when innerHeight already reports it", () => {
    expect(appHeight(true, 844, 390, 844, 390)).toBe("844px")       // iPhone 13: CSS 100% may still be short
    expect(appHeight(true, 797, 390, 844, 390)).toBe("844px")       // reported a status bar short
    expect(appHeight(true, 956, 440, 956, 440)).toBe("956px")       // iPhone 16 Pro Max
  })
  it("keeps 100% while the keyboard is up, in landscape it uses the short side", () => {
    expect(appHeight(true, 500, 390, 844, 390)).toBe("100%")
    expect(appHeight(true, 390, 844, 844, 390)).toBe("390px")
  })
  it("leaves the browser alone", () => {
    expect(appHeight(false, 700, 390, 844, 390)).toBe("100%")
  })
})

describe("the keyboard on iOS home-screen apps (report #43)", () => {
  it("fits the app to what is visible above the keyboard", () => {
    expect(appFrame(true, 844, 390, 844, 390, 480, 120)).toEqual({ height: "480px", top: "120px" })
  })
  it("no keyboard: the whole screen", () => {
    expect(appFrame(true, 844, 390, 844, 390, 844, 0)).toEqual({ height: "844px", top: "0px" })
    expect(appFrame(false, 700, 390, 844, 390, 400, 50)).toEqual({ height: "100%", top: "0px" })
  })
})
