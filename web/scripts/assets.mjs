// Copies what the app draws with from the shared sources into public/ (same selection as the
// Android build's syncSprites task), and writes the list the service worker precaches.
//   sprites  <- ../pixelart/out   (*/sheet.png, icons/*.png, banners/*.png, app icons)
//   fonts    <- ../android/app/src/main/res/font (+ ../pixelart/out/emoji/pixel_emoji.woff2)
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs"
import { dirname, join, relative } from "node:path"
import { fileURLToPath } from "node:url"

const web = join(dirname(fileURLToPath(import.meta.url)), "..")
const pixel = join(web, "..", "pixelart", "out")
const fonts = join(web, "..", "android", "app", "src", "main", "res", "font")
const pub = join(web, "public")

function wanted(rel) {
  const parts = rel.split("/")
  if (parts.length === 2 && parts[1] === "sheet.png" && parts[0] !== "emoji") return true   // emoji/sheet.png: server only
  if (parts.length === 2 && (parts[0] === "icons" || parts[0] === "banners") && rel.endsWith(".png")) return true
  if (rel === "emoji/emoji.json") return true                       // the emoji picker's groups
  return rel === "app_icon_192.png" || rel === "app_icon_512.png"
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}

rmSync(join(pub, "sprites"), { recursive: true, force: true })
const sprites = []
for (const file of walk(pixel)) {
  const rel = relative(pixel, file).replaceAll("\\", "/")
  if (!wanted(rel)) continue
  mkdirSync(dirname(join(pub, "sprites", rel)), { recursive: true })
  cpSync(file, join(pub, "sprites", rel))
  sprites.push("sprites/" + rel)
}

mkdirSync(join(pub, "fonts"), { recursive: true })
// the Android emoji font (CBDT bitmaps) stays there; the web gets the COLR version from pixelart/out/emoji
const fontFiles = readdirSync(fonts).filter((f) => f.endsWith(".ttf") && f !== "pixel_emoji.ttf")
for (const f of fontFiles) cpSync(join(fonts, f), join(pub, "fonts", f))
if (existsSync(join(pixel, "emoji", "pixel_emoji.woff2"))) {
  cpSync(join(pixel, "emoji", "pixel_emoji.woff2"), join(pub, "fonts", "pixel_emoji.woff2"))
  fontFiles.push("pixel_emoji.woff2")
}

// icons for the home screen (iOS uses apple-touch-icon)
for (const size of [192, 512]) {
  const src = join(pixel, `app_icon_${size}.png`)
  if (existsSync(src)) cpSync(src, join(pub, `icon-${size}.png`))
}

writeFileSync(join(web, "src", "precache.gen.json"),
  JSON.stringify({ assets: [...sprites, ...fontFiles.map((f) => "fonts/" + f)].sort() }, null, 0) + "\n")
console.log(`assets: ${sprites.length} sprites, ${fontFiles.length} fonts`)
