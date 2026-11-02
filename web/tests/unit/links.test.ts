import { describe, expect, it } from "vitest"
import { textParts } from "../../src/links"

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
