import { describe, expect, it } from "vitest"
import { parseChangelog, pendingReleases, RELEASES, releasesSince } from "../../src/changelog"

const sample = `# Changelog

Some words about the format.

## 2026-11-05 b
### DE
- Zweites Release am selben Tag.
### EN
- Second release on the same day.

## 2026-11-05

### DE
- Erster Punkt,
  der weitergeht.
* Zweiter Punkt.

### EN
- First point,
  continued.
* Second point.

## 2026-11-02
### de
- Changelog.
### en
- Changelog.

## empty
### DE
`

describe("the changelog shown after an update", () => {
  it("parses versions, languages and continued lines", () => {
    const r = parseChangelog(sample)
    expect(r.map((x) => x.version)).toEqual(["2026-11-05 b", "2026-11-05", "2026-11-02"])
    expect(r[1].de).toEqual(["Erster Punkt, der weitergeht.", "Zweiter Punkt."])
    expect(r[1].en).toEqual(["First point, continued.", "Second point."])
    expect(r[2].de).toEqual(["Changelog."])
  })

  it("gives the versions newer than the one seen", () => {
    const r = parseChangelog(sample)
    expect(releasesSince("2026-11-02", r).map((x) => x.version)).toEqual(["2026-11-05 b", "2026-11-05"])
    expect(releasesSince("2026-11-05 b", r)).toEqual([])
    expect(releasesSince("", r)).toEqual(r)
    expect(releasesSince(null, r)).toEqual([])
    expect(releasesSince("1999", r)).toEqual([])
  })

  it("remembers the newest version; an existing app sees everything once, a fresh one nothing", () => {
    const r = parseChangelog(sample)
    localStorage.clear()
    expect(pendingReleases(false, r)).toEqual([])                       // fresh install
    expect(localStorage.getItem("changelog_seen")).toBe(JSON.stringify("2026-11-05 b"))
    localStorage.clear()
    expect(pendingReleases(true, r).length).toBe(3)                     // had the app before the changelog existed
    expect(pendingReleases(true, r)).toEqual([])                        // seen now
    localStorage.setItem("changelog_seen", JSON.stringify("2026-11-05"))
    expect(pendingReleases(true, r).map((x) => x.version)).toEqual(["2026-11-05 b"])
  })

  it("the real CHANGELOG.md has both languages everywhere", () => {
    expect(RELEASES.length).toBeGreaterThan(0)
    for (const rel of RELEASES) {
      expect(rel.de.length, `${rel.version}: DE missing`).toBeGreaterThan(0)
      expect(rel.en.length, `${rel.version}: DE and EN differ`).toBe(rel.de.length)
    }
    expect(new Set(RELEASES.map((r) => r.version)).size).toBe(RELEASES.length)
  })
})
