# Momentum extensions – API 1

An extension is an optional part of the app. Players download it in **Settings → Extensions** and switch it on.
Today an extension can:

- bring **tabs** of its own (up to 6),
- with the permission `rules`: see the player's tappable **rules** and tap them for the player (and take such a tap
  back), and
- store **data in a format it defines** on the Momentum server, per player.

The same extension runs in both apps (Android and the iPhone PWA): it's a web page, shown sandboxed.

## Files

```
<id>/
  manifest.json   what it is, its tabs, its data format
  index.html      its whole UI: one self-contained page (inline CSS / JS, at most 512 KB)
```

Shipped extensions live in `server/shipped_extensions/` and are synced into the catalog when the server starts
(a new `version` reaches the players on their next start). Extensions by players are planned; until then the
catalog holds only shipped ones.

## manifest.json

```json
{
  "id": "daylist",                       // a-z, 0-9, _ (2-40), the folder's name
  "version": "1.0.0",                    // bump it on every change
  "api": 1,
  "name": { "de": "Daylist", "en": "Daylist" },           // up to 40 characters each
  "description": { "de": "…", "en": "…" },              // up to 300
  "author": "Momentum",
  "icon": "check",                       // a Momentum pixel icon name (see /api/meta "icons") or one emoji
  "tabs": [                              // optional, up to 6
    { "key": "list", "name": { "de": "Aufgaben", "en": "Tasks" }, "icon": "tab_list" },
    { "key": "done", "name": { "de": "Erledigt", "en": "Done" }, "icon": "tab_check" }
  ],
  "permissions": ["rules"],              // optional; today only "rules"
  "entry": "index.html",
  "collections": {                       // up to 20
    "days": {
      "key_pattern": "^\\d{4}-\\d{2}-\\d{2}$",           // optional, besides the general key rule
      "max_docs": 400,                   // optional, default 1000, at most 10000
      "schema": { "type": "object", "required": ["t"], "additionalProperties": false,
                  "properties": { "t": { "type": "object", "additionalProperties": { "type": "object" } } } }
    }
  }
}
```

The whole daylist (`server/shipped_extensions/daylist/`) is a good example to start from.

**Tabs:** every tab needs a `key` (a-z, 0-9, _, up to 20, unique in the extension), a `name` in German and English
(up to 20 characters) and an `icon` from the tab library: the white `tab_*` glyphs (`/api/meta` → `tab_icons`, drawn
in `pixelart/tab_icons.py`). Players see your tabs by that name and icon: once the extension is switched on they're in
**Settings → Tabs**, where each player puts them into the bottom bar (up to 5 places, icons only), into a group (a tab
menu at the top with names, icons or both - the player decides) or leaves them out. Every tab shows the same page; the
context tells it which one it is (`tab`), so one page can be several screens.

**Permissions:** `rules` lets the page see the player's tappable rules and tap them (the catalog shows the player
this before they download). Without it the `momentum.rules.*` calls fail with status 403.

**Schema:** a subset of JSON Schema – `type` (object, array, string, number, integer, boolean, null, or a list of
them), `properties`, `required`, `additionalProperties` (true / false / a schema), `items`, `minItems`, `maxItems`,
`minLength`, `maxLength`, `pattern` (up to 200 characters), `minimum`, `maximum`, `enum`, plus `title` /
`description` (ignored). Anything else makes the manifest invalid. Every document is checked on the server before it
is stored; a document that doesn't fit is refused with a message naming the place (`value.mood: out of range`).

**Limits per player:** keys `A-Z a-z 0-9 _ . : -`, 1-80 characters; one document at most 16 KB (as JSON);
everything of one extension at most 2 MB; `max_docs` per collection.

## The page and the bridge

The app puts Momentum's look and the bridge in front of your page: the fonts (`Silkscreen`, `Pixelify`, pixel
emojis), the colours as CSS variables (`--bg`, `--card`, `--text`, `--muted`, `--yellow`, `--orange`, `--lime`,
`--cyan`, …) and a few ready-made classes: `m-title`, `m-label`, `m-small`, `m-card`, `m-button` (`.ghost`),
`m-field`. The page fills the tab between the status bar and the tab bar.

The page talks to Momentum only through `window.momentum`:

```js
const ctx = await momentum.ready      // {api: 1, lang: "de" | "en", user: {username, display_name}, extension: {id, version}, tab}
// ctx.tab = the key of the tab this page is shown as (null in apps from before tabs - show everything then)
await momentum.data.put("days", "2026-11-18", { t: {} })                    // -> {key, value, updated_at}
await momentum.data.get("days", "2026-11-18")                                // -> the value, or null
await momentum.data.list("days", { order: "-key", offset: 0, limit: 20 })    // -> {docs: [{key, value, updated_at}], total}
await momentum.data.delete("days", "2026-11-18")
// with the permission "rules" (online only, like the player's own taps: daily limit, milestones and all)
await momentum.rules.list()           // -> [{id, name, icon, kind: "acc" | "dec", value}] - the tappable ones
await momentum.rules.tap(12)          // -> {event, delta, capped}: keep `event` to take the tap back
await momentum.rules.undo(event)      // -> true
momentum.toast("Gespeichert")
momentum.on("show", () => reload())   // the tab came into view ("hide": it went away)
```

Calls return promises; a failure rejects with an `Error` whose `message` is the server's explanation and `status`
its HTTP status (0 = offline, 409 = not installed, 413 too big, 422 doesn't fit the schema, 507 storage full).

## What a page can't do

It runs in a sandbox: an iframe with only `allow-scripts` (PWA) or a WebView that may not navigate (Android), with
a Content-Security-Policy that allows **no network** (no `fetch`, no external scripts, styles, images or fonts –
only Momentum's own fonts and pictures under `/app/fonts/` and `/sprites/`, `data:` and `blob:`). It has no access
to the player's login, the app's storage or anything outside its own data. Keep everything in the one HTML file.

## Versions

`api` is the bridge's version. Extensions written for API 1 keep working when later APIs add more.
