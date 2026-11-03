// Background scenes for cards (android/.../ui/Scenes.kt): stepped colour bands, animated pixel
// layers, dither on top.
import { blit, type Bands, type Ctx, drawBands, dither, phase, pixelScale, rect, rng, sprite, tileAll, tileX } from "./engine"

export type Scene = "land" | "air" | "space" | "acc" | "dec" | "settings"

export const sceneFor = (category: string): Scene => (category === "air" ? "air" : category === "space" ? "space" : "land")

const BANDS: Record<Scene, Bands> = {
  land: [[0, "#232A4F"], [.22, "#2E2B55"], [.36, "#48295A"], [.48, "#6F2F57"], [.58, "#96435A"], [.66, "#B86455"]],
  air: [[0, "#22305E"], [.20, "#2B4282"], [.38, "#3459A0"], [.56, "#3F73B8"], [.72, "#5791C8"]],
  space: [[0, "#07080F"], [.30, "#0D0F1D"], [.55, "#141832"], [.75, "#1C2246"]],
  acc: [[0, "#14232A"], [.30, "#173634"], [.55, "#1D4A3C"], [.78, "#275D43"]],
  dec: [[0, "#1C111C"], [.30, "#2C1424"], [.55, "#40182C"], [.78, "#561D33"]],
  settings: [[0, "#12141D"], [.35, "#1A1F2E"], [.65, "#232A3D"]],
}

const r = rng(7)
const STARS = Array.from({ length: 70 }, () => [r(), r(), r()] as const)

/** @param groundDp height of the road in the land scene; vehicles stand on its top edge. */
export function drawScene(c: Ctx, scene: Scene, groundDp = 120, sun = true, speed = 1) {
  const sp = speed
  const s2 = pixelScale(2), s3 = pixelScale(3), s4 = pixelScale(4)
  const dp = c.dp, t = c.t
  drawBands(c, BANDS[scene])
  switch (scene) {
    case "land": {
      const far = sprite("icons/scene_mountains_far.png"), near = sprite("icons/scene_mountains_near.png")
      const sunImg = sprite("icons/scene_sun.png")
      const ground = c.h - groundDp * dp
      // the sun sits above the mountains, independent of how tall the card is
      if (sun) blit(c, sunImg, c.w * .64, Math.min(Math.max(c.h * .30, ground - 150 * dp), ground - 70 * dp), s4, .85)
      tileX(c, far, s4, ground - far.naturalHeight * s4, phase(t, 40_000 / sp), .8)
      tileX(c, near, s4, ground - near.naturalHeight * s4, phase(t, 16_000 / sp))
      rect(c, "#2A2F45", 0, ground, c.w, 6 * dp)
      rect(c, "#1A1C2C", 0, ground + 6 * dp, c.w, c.h)
      const dash = 32 * dp
      for (let x = -phase(t, 450 / sp) * dash; x < c.w; x += dash) rect(c, "rgba(255,205,117,0.55)", x, ground + 30 * dp, dash / 2, 4 * dp)
      break
    }
    case "air": {
      const cloud = sprite("icons/scene_cloud.png")
      tileX(c, cloud, s3, c.h * .26, phase(t, 9_000 / sp), .8)
      tileX(c, cloud, s2, c.h * .62, phase(t, 5_000 / sp), .55)
      break
    }
    case "space": case "settings": {
      const slow = phase(t, 40_000 / sp), fast = phase(t, 9_000 / sp)
      for (const [fx, fy, depth] of STARS) {
        const x = (((fx - (depth > .6 ? fast : slow)) % 1) + 1) % 1
        const dot = depth > .85 ? 2 * dp : dp * 1.2
        rect(c, `rgba(244,244,244,${.3 + depth * .6})`, x * c.w, fy * c.h, dot, dot)
      }
      if (scene === "space" && sun) {
        const planet = sprite("icons/scene_planet.png")
        blit(c, planet, c.w - planet.naturalWidth * s3 - 22 * dp, Math.min(c.h * .30, 120 * dp), s3, .75)
      }
      break
    }
    case "acc": tileAll(c, sprite("icons/ui_up.png"), s2, 1 - phase(t, 3_000), .07); break
    case "dec": tileAll(c, sprite("icons/scene_rain.png"), s3, phase(t, 900), .45); break
  }
  dither(c)
}
