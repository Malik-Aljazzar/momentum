// What's new (android/.../data/Changelog.kt): CHANGELOG.md at the project root is bundled into every
// build; after an update the app shows the versions added since the one it ran before.
//
//   ## <version>      any unique text, newest first - only the order counts
//   ### DE / ### EN   the language of the lines below
//   - <entry>         a point (a following line indented by two spaces continues it)
import changelogText from "../../CHANGELOG.md?raw"
import { local } from "./storage"
import type { Lang } from "./strings"

export interface Release { version: string; de: string[]; en: string[] }

export function parseChangelog(md: string): Release[] {
  const out: Release[] = []
  let cur: Release | null = null
  let lang: Lang | null = null
  for (const raw of md.replace(/\r\n/g, "\n").split("\n")) {
    const line = raw.trimEnd()
    if (line.startsWith("## ") && !line.startsWith("### ")) {
      cur = { version: line.slice(3).trim(), de: [], en: [] }
      lang = null
      out.push(cur)
    } else if (line.startsWith("### ")) {
      const l = line.slice(4).trim().toLowerCase()
      lang = l === "de" || l === "en" ? l : null
    } else if (cur && lang && /^[-*] /.test(line)) {
      cur[lang].push(line.slice(2).trim())
    } else if (cur && lang && /^\s{2,}\S/.test(line) && cur[lang].length) {
      cur[lang][cur[lang].length - 1] += " " + line.trim()
    }
  }
  return out.filter((r) => r.de.length || r.en.length)
}

export const RELEASES: Release[] = parseChangelog(changelogText)

/** The releases newer than [seen] (the newest version shown before); all of them for "" and none for
 *  an unknown version (the file was rewritten: don't flood the screen). null = never shown before. */
export function releasesSince(seen: string | null, releases: Release[] = RELEASES): Release[] {
  if (seen === "") return releases
  if (seen == null) return []
  const i = releases.findIndex((r) => r.version === seen)
  return i < 0 ? [] : releases.slice(0, i)
}

const KEY = "changelog_seen"

/** At start: what to show. A device that already had the app (a token) but never saw a changelog gets the
 *  whole list once; a fresh install starts at the newest version and sees nothing. */
export function pendingReleases(hadApp: boolean, releases: Release[] = RELEASES): Release[] {
  const seen = local.get<string | null>(KEY, null)
  const show = releasesSince(seen ?? (hadApp ? "" : null), releases)
  if (releases.length) local.set(KEY, releases[0].version)
  return show
}
