import { describe, expect, it } from "vitest"
import { firstLink, linkHost, linkLayout, linksIn, shownLink, textParts } from "../../src/links"

describe("links in user text are clickable (report #58)", () => {
  it("finds links, without the punctuation after them", () => {
    expect(textParts("look: https://example.com/a?b=1. ok")).toEqual([
      { text: "look: " }, { url: "https://example.com/a?b=1", text: "https://example.com/a?b=1" }, { text: ". ok" },
    ])
    expect(textParts("(www.momentum.de)")).toEqual([
      { text: "(" }, { url: "https://www.momentum.de", text: "www.momentum.de" }, { text: ")" },
    ])
  })
  it("keeps mentions, also next to links, and leaves @ inside a link alone", () => {
    expect(textParts("@max_1 see http://x.io/@foo")).toEqual([
      { user: "max_1", text: "@max_1" }, { text: " see " }, { url: "http://x.io/@foo", text: "http://x.io/@foo" },
    ])
  })
  it("plain text stays one part", () => {
    expect(textParts("no link here, www. alone")).toEqual([{ text: "no link here, www. alone" }])
  })
})

describe("link previews in chats (like WhatsApp)", () => {
  it("previews the first link, as it opens", () => {
    expect(linksIn("a www.x.de/a. b https://y.io/b?c=1, c")).toEqual(["https://www.x.de/a", "https://y.io/b?c=1"])
    expect(firstLink("hi www.x.de, and https://y.io")).toBe("https://www.x.de")
    expect(firstLink("no link, www. alone")).toBeNull()
  })
  it("names the host from the link itself", () => {
    expect(linkHost("https://www.youtube.com/watch?v=1")).toBe("youtube.com")
    expect(linkHost("https://de.wikipedia.org/wiki/X")).toBe("de.wikipedia.org")
    expect(linkHost("nonsense")).toBe("")
  })
  it("puts wide pictures over the text, others beside it", () => {
    expect(linkLayout({ image: "x", width: 480, height: 252 })).toBe("big")
    expect(linkLayout({ image: "x", width: 480, height: 480 })).toBe("small")
    expect(linkLayout({ image: "x", width: 233, height: 135 })).toBe("small")
    expect(linkLayout({ image: null, width: 480, height: 252 })).toBe("none")
  })
  it("shows a preview only for a link in the text, once opened", () => {
    const link = { url: "https://x.de/a", title: "X" }
    expect(shownLink({ text: "see https://x.de/a!", link })).toBe(link)
    expect(shownLink({ text: "see https://evil.example/", link })).toBeNull()     // the sender can't dress up another link
    expect(shownLink({ text: "", sealed: true, link })).toBeNull()
    expect(shownLink({ text: "see https://x.de/a" })).toBeNull()
  })
})
