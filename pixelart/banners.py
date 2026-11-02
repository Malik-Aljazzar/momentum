"""Banner layers: horizontally seamless landscape strips (far + near) and sky objects.

Each banner is drawn by the app as: sky bands -> sky object -> far strip -> near strip ->
particles (snow, rain, ...) -> road (land vehicles). The strips scroll at different speeds,
so every x-pattern here repeats exactly every W pixels.

    python banners.py            # writes out/banners/*.png and out/_banners.png (preview)
"""
import math
import os
import random

from PIL import Image

W = 128           # strip width = horizontal period
FAR_H = 40
NEAR_H = 26


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


class Strip:
    def __init__(self, w, h):
        self.im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.w, self.h = w, h

    def set(self, x, y, c):
        x %= self.w                               # wraps: features at the edge continue on the other side
        if 0 <= y < self.h:
            self.im.putpixel((x, y), hexc(c) if isinstance(c, str) else c)

    def get(self, x, y):
        return self.im.getpixel((x % self.w, y))[3] > 0 if 0 <= y < self.h else False

    def col(self, x, top, c, bottom=None):
        for y in range(max(0, top), (self.h if bottom is None else bottom + 1)):
            self.set(x, y, c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def circle(self, cx, cy, r, c):
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                if (x + .5 - cx) ** 2 + (y + .5 - cy) ** 2 <= r * r:
                    self.set(x, y, c)

    def tri(self, cx, top, half, bottom, c):
        """Pine/peak shape: apex at (cx, top), base width 2*half at `bottom`."""
        for y in range(top, bottom + 1):
            k = (y - top + 1) / (bottom - top + 1)
            hw = round(half * k)
            for x in range(cx - hw, cx + hw + 1):
                self.set(x, y, c)


def wave(x, terms):
    """Periodic height profile: sum of sines with integer frequencies (seamless over W)."""
    t = 2 * math.pi * x / W
    return sum(a * math.sin(f * t + p) for f, a, p in terms)


def ridge(s, base, terms, c, cap=None, cap2=None):
    hs = []
    for x in range(s.w):
        h = round(base + wave(x, terms))
        s.col(x, s.h - h, c)
        if cap:
            s.set(x, s.h - h, cap)
        if cap2:
            s.set(x, s.h - h + 1, cap2)
        hs.append(s.h - h)
    return hs                                     # top y per column


def spots(seed, n, lo=0, hi=W):
    r = random.Random(seed)
    return sorted(r.randrange(lo, hi) for _ in range(n))


# ---------------------------------------------------------------- building blocks

def skyline(s, seed, color, window, lit=.25, min_h=8, max_h=None, wmin=6, wmax=13, roof=None):
    r = random.Random(seed)
    x = 0
    max_h = max_h or s.h - 2
    while x < s.w:
        bw = r.randint(wmin, wmax)
        bh = r.randint(min_h, max_h)
        if x + bw > s.w:
            bw = s.w - x
        top = s.h - bh
        s.rect(x, top, x + bw - 1, s.h - 1, color)
        if roof and r.random() < .3 and bw > 5:              # antenna / roof box
            s.col(x + bw // 2, top - 3, roof, top - 1)
        for wy in range(top + 2, s.h - 1, 3):
            for wx in range(x + 1, x + bw - 1, 2):
                if r.random() < lit:
                    s.set(wx, wy, window)
        x += bw + r.choice((0, 0, 1))


def pines(s, seed, n, color, hmin, hmax, trunk=None, snow=None, ground=0):
    r = random.Random(seed)
    for cx in spots(seed + 1, n):
        h = r.randint(hmin, hmax)
        bottom = s.h - 1 - ground
        top = bottom - h
        half = max(2, h // 3)
        # stacked tiers look like conifers
        for k in range(3):
            t0 = top + k * h // 4
            s.tri(cx, t0, half * (k + 2) // 4 + 1, min(bottom, t0 + h // 2), color)
        if trunk:
            s.col(cx, bottom - 1, trunk, bottom)
        if snow:
            for k in range(3):
                t0 = top + k * h // 4
                s.set(cx, t0, snow)
                s.set(cx - 1, t0 + 1, snow)
    if ground:
        s.rect(0, s.h - ground, s.w - 1, s.h - 1, color)


def round_trees(s, seed, n, crown, crown_hi, trunk, hmin, hmax, rmin=3, rmax=6, flowers=None):
    r = random.Random(seed)
    for cx in spots(seed + 2, n):
        h = r.randint(hmin, hmax)
        rad = r.randint(rmin, rmax)
        s.col(cx, s.h - h + rad, trunk)
        s.col(cx + 1, s.h - h + rad + 1, trunk)
        cy = s.h - h
        s.circle(cx, cy, rad, crown)
        s.circle(cx + rad * .5, cy + rad * .3, rad * .8, crown)
        s.circle(cx - rad * .3, cy - rad * .4, rad * .55, crown_hi)
        if flowers:
            for _ in range(rad * 2):
                s.set(cx + r.randint(-rad, rad), cy + r.randint(-rad, rad // 2), flowers)


# ---------------------------------------------------------------- banners

def b_city():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    skyline(far, 4, "#262b55", "#4a4f86", lit=.12, min_h=14, max_h=36, wmin=7, wmax=14, roof="#262b55")
    skyline(near, 5, "#0f1020", "#ffcd75", lit=.3, min_h=8, max_h=24, wmin=8, wmax=15, roof="#0f1020")
    moon = Strip(20, 20)
    moon.circle(10, 10, 8.5, "#f4f4d0")
    moon.circle(13.5, 7.5, 7, (0, 0, 0, 0))
    return far, near, moon


def b_desert():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 12, [(2, 4, .3), (3, 2, 1.7)], "#e9a16b", cap="#f7c88f")
    ridge(near, 9, [(1, 3, 2.2), (4, 1.5, .4)], "#c26a45", cap="#e08a5a")
    for cx in spots(11, 3):                       # cacti
        base = NEAR_H - 9 - round(wave(cx, [(1, 3, 2.2), (4, 1.5, .4)]))
        near.col(cx, base - 9, "#38b764", base)
        near.col(cx + 1, base - 9, "#257179", base)
        near.rect(cx - 3, base - 6, cx - 2, base - 5, "#38b764")
        near.col(cx - 3, base - 9, "#38b764", base - 6)
        near.rect(cx + 2, base - 4, cx + 3, base - 3, "#257179")
        near.col(cx + 3, base - 7, "#257179", base - 4)
    sun = Strip(28, 28)
    sun.circle(14, 14, 13, "#ffd9a0")
    sun.circle(14, 14, 10.5, "#fff1c9")
    return far, near, sun


def b_forest():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    pines(far, 21, 26, "#1f4a49", 14, 26, ground=6)
    pines(far, 22, 14, "#1a3d3d", 18, 30)
    pines(near, 23, 16, "#0c1a19", 14, 24, trunk="#0c1a19", ground=3)
    return far, near, None


def b_beach():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    # sea from the horizon down, with glittering wave crests
    far.rect(0, 18, W - 1, FAR_H - 1, "#2d5f9a")
    far.rect(0, 18, W - 1, 18, "#5a8fd0")
    r = random.Random(31)
    for y in range(20, FAR_H, 3):
        for x in spots(32 + y, 7):
            far.rect(x, y, x + r.randint(1, 4), y, "#9ec5f0" if y < 28 else "#6aa3e0")
    ridge(near, 6, [(2, 1.5, .5), (5, .8, 1.2)], "#f2d29b", cap="#fff0c4")
    for cx in spots(33, 2):                       # palms
        base = NEAR_H - 6
        for i in range(16):
            near.set(cx + i // 5, base - i, "#7a4a32")
        top_x, top_y = cx + 3, base - 16
        for dx, dy in ((-1, 0), (1, 0)):
            for i in range(1, 7):
                near.set(top_x + dx * i, top_y + abs(i) // 2 + (i > 4), "#257179")
                near.set(top_x + dx * i, top_y + abs(i) // 2 - 1 + (i > 4), "#38b764")
        for i in range(1, 5):
            near.set(top_x + i // 2, top_y - i, "#38b764")
            near.set(top_x - i // 2, top_y - i, "#257179")
    sun = Strip(26, 26)
    sun.circle(13, 13, 12, "#ff9e6b")
    sun.circle(13, 13, 9.5, "#ffcd75")
    return far, near, sun


def b_snow():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    tops = ridge(far, 22, [(2, 9, .4), (5, 4, 1.1), (7, 2, 2.0)], "#5b6f99")
    for x, t in enumerate(tops):                  # snow caps on the upper part
        for y in range(t, min(FAR_H, t + 4 + (x * 7 % 3))):
            if y < 22:
                far.set(x, y, "#e8f1ff")
    pines(near, 41, 14, "#1e2c45", 12, 22, snow="#e8f1ff", ground=4)
    near.rect(0, NEAR_H - 4, W - 1, NEAR_H - 1, "#d6e4f5")
    return far, near, None


def b_aurora():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 10, [(2, 4, 1.4), (3, 2, .2)], "#141a33", cap="#1f2850")
    pines(near, 51, 18, "#05070f", 10, 22, ground=3)
    aur = Strip(W, 36)                            # ribbons, drawn as a high layer
    for x in range(W):
        mid = 16 + wave(x, [(2, 6, .8), (3, 3, 2.4)])
        for y in range(36):
            d = y - mid
            if -2 <= d <= 10:
                a = int(170 * (1 - abs(d - 3) / 8)) if abs(d - 3) < 8 else 0
                if a > 0 and (x + y) % 2 == 0 or d in (-1, 0):
                    aur.set(x, y, (115, 239, 170, max(60, a)) if d < 4 else (65, 166, 246, max(40, a - 40)))
    return far, near, aur


def b_sakura():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    tops = ridge(far, 12, [(1, 9, 2.2), (3, 2, .5)], "#8f86b8")
    for x, t in enumerate(tops):
        for y in range(t, min(FAR_H, t + 3)):
            if y < FAR_H - 14:
                far.set(x, y, "#f4f0ff")
    round_trees(near, 61, 7, "#e88bb8", "#ffc4dc", "#5d3a4a", 14, 20, 4, 7, flowers="#fff0f6")
    near.rect(0, NEAR_H - 2, W - 1, NEAR_H - 1, "#6d8f5a")
    return far, near, None


def b_volcano():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 7, [(2, 2, .7)], "#3a1f2a")
    for x in range(W):                            # one big cone (with a second smaller one)
        for cx, top, slope in ((40, 4, 2.2), (104, 16, 2.8)):
            d = abs(x - cx)
            h = FAR_H - (top + max(0, d - 4) * 2.2 / slope * 1.4) if d < 40 else 0
            if h > 0:
                far.col(x, int(FAR_H - h), "#4a2531")
    far.rect(36, 4, 44, 5, "#ef7d57")
    far.rect(38, 3, 42, 3, "#ffcd75")
    for i, x in enumerate((37, 41, 44)):          # lava streaks
        for y in range(6, 6 + 8 + i * 3):
            far.set(x + (y - 6) // (3 + i) * (1 if x > 40 else -1), y, "#ef7d57" if y % 3 else "#b13e53")
    ridge(near, 7, [(3, 3, .9), (5, 1.5, 2.2)], "#1a0f14", cap="#33191f")
    return far, near, None


def b_canyon():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(71)
    x = 0
    while x < W:                                  # flat-topped mesas with stripes
        w = r.randint(10, 22)
        h = r.randint(14, 30)
        for xx in range(x, min(W, x + w)):
            edge = min(xx - x, x + w - 1 - xx)
            top = FAR_H - h + max(0, 3 - edge) * 2
            far.col(xx, top, "#b45a3c")
            for y in range(top, FAR_H, 5):
                far.set(xx, y, "#d27a4f")
        x += w + r.randint(4, 12)
    ridge(near, 8, [(2, 3, .3), (6, 1.2, 1.9)], "#6e2f2a", cap="#8f3f33")
    return far, near, None


def b_rain():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    skyline(far, 81, "#3d4a5f", "#56677f", lit=.05, min_h=16, max_h=36, roof="#3d4a5f")
    skyline(near, 82, "#1a202b", "#9ec5f0", lit=.15, min_h=10, max_h=24, roof="#1a202b")
    return far, near, None


def b_moon():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 8, [(2, 3, 1.0), (5, 1.5, .1)], "#3a3f52", cap="#565c73")
    tops = ridge(near, 10, [(1, 2, .5), (3, 1.5, 1.6)], "#8a8fa3", cap="#b3b8c9")
    for cx in spots(91, 6):                       # craters
        y = tops[cx] + 4 + cx % 5
        near.rect(cx - 2, y, cx + 2, y + 1, "#6c7186")
        near.rect(cx - 1, y + 2, cx + 1, y + 2, "#6c7186")
        near.rect(cx - 2, y - 1, cx + 2, y - 1, "#b3b8c9")
    earth = Strip(24, 24)
    earth.circle(12, 12, 11, "#3b5dc9")
    earth.circle(12, 12, 9, "#41a6f6")
    for x, y, w in ((7, 7, 4), (13, 10, 5), (9, 15, 3), (15, 16, 2)):
        earth.rect(x, y, x + w, y + 1, "#38b764")
    earth.rect(9, 4, 13, 4, "#f4f4f4")
    return far, near, earth


def b_synthwave():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    tops = ridge(far, 14, [(3, 6, .3), (5, 3, 1.5)], "#2a1240")
    for x, t in enumerate(tops):
        far.set(x, t, "#ff4fd8")
        if x % 16 == 0:                           # wireframe lines down the slopes
            for y in range(t, FAR_H, 2):
                far.set(x, y, "#a03fb8")
    near.rect(0, 0, W - 1, NEAR_H - 1, "#1a0b2e")
    for i, y in enumerate((0, 2, 5, 9, 14, 20)):  # perspective grid
        near.rect(0, y, W - 1, y, "#ff4fd8" if i == 0 else "#b23fd0")
    for x in range(0, W, 8):
        near.set(x, 1, "#b23fd0")
        for y in range(NEAR_H):
            near.set(x + (x - 64) * y // 40, y, "#b23fd0")
    sun = Strip(34, 34)
    for y in range(34):
        for x in range(34):
            if (x - 16.5) ** 2 + (y - 16.5) ** 2 <= 16 ** 2:
                if y < 18 or (y // 2) % 2 == 0:   # striped lower half
                    t = y / 33
                    sun.set(x, y, (255, int(220 - 140 * t), int(90 + 120 * t), 255))
    return far, near, sun


def b_autumn():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 16, [(2, 4, .9), (5, 2, 2.1)], "#a8513a")
    round_trees(far, 101, 14, "#c96a3a", "#e08a4f", "#5a2f25", 18, 26, 4, 6)
    round_trees(near, 102, 6, "#e0832f", "#ffcd75", "#3b2420", 14, 22, 5, 7)
    near.rect(0, NEAR_H - 2, W - 1, NEAR_H - 1, "#5a3a26")
    return far, near, None


def b_fields():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 14, [(2, 4, .2), (3, 2, 1.8)], "#7cc35a", cap="#a7f070")
    tops = ridge(near, 12, [(1, 3, 1.1), (4, 1, .3)], "#38b764", cap="#5ad37a")
    for x in range(W):                            # field rows
        for y in range(tops[x] + 3, NEAR_H, 3):
            if (x + y) % 6 < 3:
                near.set(x, y, "#2e9a55")
    cx = 84                                       # windmill
    base = tops[cx]
    near.rect(cx - 2, base - 12, cx + 2, base, "#f4f4f4")
    near.rect(cx - 1, base - 14, cx + 1, base - 13, "#b13e53")
    for i in range(-7, 8):
        near.set(cx + i, base - 13 + i // 2, "#94b0c2")
        near.set(cx + i // 2, base - 13 - i, "#94b0c2")
    return far, near, None


def b_deepsea():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(121)
    for cx in spots(122, 12):                     # kelp
        h = r.randint(14, 36)
        for y in range(FAR_H - h, FAR_H):
            far.set(cx + round(1.5 * math.sin(y / 3 + cx)), y, "#1f6f6a")
            if y % 4 == 0:
                far.set(cx + round(1.5 * math.sin(y / 3 + cx)) + 1, y, "#2a8a74")
    ridge(far, 4, [(3, 1.5, .4)], "#143a52")
    tops = ridge(near, 7, [(2, 2, .8), (5, 1, 2.0)], "#c9a46a", cap="#e6c88f")
    for cx in spots(123, 5):                      # coral
        b = tops[cx]
        for k, c in ((0, "#ef7d57"), (2, "#ff6f9f"), (-2, "#ef7d57")):
            near.col(cx + k, b - 4 - abs(k), c, b)
        near.set(cx - 1, b - 3, "#ff6f9f")
        near.set(cx + 1, b - 5, "#ffcd75")
    rays = Strip(W, 40)                           # light from above
    for cx in (12, 46, 90):
        for y in range(40):
            for x in range(cx + y // 2, cx + y // 2 + 6):
                rays.set(x, y, (180, 230, 255, max(0, 50 - y)))
    return far, near, rays


def b_storm():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 12, [(2, 4, 2.0), (4, 2, .6)], "#2a2a3d")
    ridge(near, 8, [(3, 2, .1), (6, 1, 1.0)], "#111119", cap="#1c1c28")
    clouds = Strip(W, 30)
    r = random.Random(131)
    for cx in range(0, W, 8):                     # one heavy, continuous cloud deck
        clouds.circle(cx + r.randint(0, 4), 8 + r.randint(0, 6), r.randint(7, 10), "#2f2b42")
    for cx in range(4, W, 11):
        clouds.circle(cx + r.randint(0, 4), 6 + r.randint(0, 4), r.randint(5, 7), "#3d3854")
    for x in range(W):
        for y in range(0, 5):
            clouds.set(x, y, "#2f2b42")
    for cx in spots(132, 8):                      # lit edges
        clouds.rect(cx, 3 + cx % 5, cx + 4, 3 + cx % 5, "#5a5378")
    return far, near, clouds


def b_sunrise():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 18, [(2, 3, .6), (3, 2, 2.2)], "#b48fb8")
    ridge(far, 10, [(3, 3, 1.5), (5, 1, .3)], "#8f6b9e")
    ridge(near, 9, [(2, 2, .2), (4, 1.5, 2.6)], "#4d3a5f", cap="#6b5279")
    sun = Strip(36, 20)                           # half sun on the horizon
    for y in range(20):
        for x in range(36):
            if (x - 17.5) ** 2 + (y - 19.5) ** 2 <= 17 ** 2:
                sun.set(x, y, "#ffe6a0" if (x - 17.5) ** 2 + (y - 19.5) ** 2 <= 13 ** 2 else "#ffb37a")
    return far, near, sun


BANNERS = {
    "city": b_city, "desert": b_desert, "forest": b_forest, "beach": b_beach, "snow": b_snow,
    "aurora": b_aurora, "sakura": b_sakura, "volcano": b_volcano, "canyon": b_canyon, "rain": b_rain,
    "moon": b_moon, "synthwave": b_synthwave, "autumn": b_autumn, "fields": b_fields,
    "deepsea": b_deepsea, "storm": b_storm, "sunrise": b_sunrise,
}


def build(out):
    d = os.path.join(out, "banners")
    os.makedirs(d, exist_ok=True)
    for key, fn in BANNERS.items():
        far, near, extra = fn()                   # far / near may be None (space: sky object only)
        if far is not None:
            far.im.save(os.path.join(d, f"{key}_far.png"))
        if near is not None:
            near.im.save(os.path.join(d, f"{key}_near.png"))
        if extra is not None:
            extra.im.save(os.path.join(d, f"{key}_sky.png"))
    print("wrote", len(BANNERS), "banners")


if __name__ == "__main__":
    build(os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))


# Sky per banner: color bands (start fraction, hex), sky-object placement (x, y as fractions,
# "high" layers span the width), particles drawn by the app. Shared with the app via
# banners_kotlin() so both stay in sync.
SKY = {
    "city":      dict(bands=[(0, "0b0d24"), (.3, "15183a"), (.55, "222052"), (.75, "35285f")], obj=(.72, .12), fx="stars"),
    "desert":    dict(bands=[(0, "5fa0d8"), (.3, "8fbfe0"), (.55, "f2c9a0"), (.72, "f4a774")], obj=(.62, .18), fx="none"),
    "forest":    dict(bands=[(0, "16323a"), (.3, "1f4a4a"), (.55, "3f6a55"), (.8, "6f7a52")], obj=None, fx="fireflies"),
    "beach":     dict(bands=[(0, "3a2f6b"), (.25, "7a3f7a"), (.45, "c45a78"), (.62, "f08a6a")], obj=(.66, .40), fx="none"),
    "snow":      dict(bands=[(0, "6f8fc0"), (.35, "93b0d8"), (.6, "b8cde8"), (.8, "d6e2f2")], obj=None, fx="snow"),
    "aurora":    dict(bands=[(0, "050817"), (.35, "0a1030"), (.65, "12193f")], obj="high", fx="stars"),
    "sakura":    dict(bands=[(0, "9a8cc8"), (.3, "c6a6d8"), (.55, "f0c0d8"), (.75, "ffe0e8")], obj=None, fx="petals"),
    "volcano":   dict(bands=[(0, "1a0c14"), (.3, "2e1020"), (.55, "5a1a26"), (.75, "8f2f2a")], obj=None, fx="embers"),
    "canyon":    dict(bands=[(0, "e07a4a"), (.3, "eb9a5a"), (.55, "f5bf73"), (.75, "ffd98f")], obj=None, fx="none"),
    "rain":      dict(bands=[(0, "2a3142"), (.35, "39435a"), (.65, "4a566e")], obj=None, fx="rain"),
    "moon":      dict(bands=[(0, "04050a"), (.4, "0a0c18"), (.7, "12152a")], obj=(.70, .14), fx="stars"),
    "synthwave": dict(bands=[(0, "1a0b2e"), (.3, "3a1252"), (.5, "6a1a6e"), (.68, "b02a7a")], obj=(.76, .30), fx="stars"),
    "autumn":    dict(bands=[(0, "e8a46a"), (.35, "f0bc80"), (.6, "f7d29a")], obj=None, fx="leaves"),
    "fields":    dict(bands=[(0, "3b7fd0"), (.35, "5a9be0"), (.6, "8cc0ee"), (.8, "bfe0f8")], obj=None, fx="clouds"),
    "deepsea":   dict(bands=[(0, "1f5a8a"), (.3, "174a75"), (.6, "103a60"), (.8, "0a2a4a")], obj="high", fx="bubbles"),
    "storm":     dict(bands=[(0, "17151f"), (.35, "221f2e"), (.65, "2e2a3d")], obj="high", fx="storm"),
    "sunrise":   dict(bands=[(0, "2e2f63"), (.25, "6b4a86"), (.45, "c77a9a"), (.62, "f7b48a"), (.75, "ffd9a0")], obj=(.40, -1), fx="birds"),
}


def preview(path, scale=2, w=150, h=120, ground=18):
    """Static composite of all banners (sky object, far + near strip) for checking the look."""
    from PIL import ImageDraw
    cols = 4
    keys = list(BANNERS)
    sheet = Image.new("RGBA", (cols * (w + 4) * scale, ((len(keys) + cols - 1) // cols) * (h + 4) * scale), (0, 0, 0, 255))
    for i, key in enumerate(keys):
        far, near, extra = BANNERS[key]()
        sky = SKY[key]
        im = Image.new("RGBA", (w, h), (0, 0, 0, 255))
        dr = ImageDraw.Draw(im)
        bands = sky["bands"]
        for j, (start, c) in enumerate(bands):
            end = bands[j + 1][0] if j + 1 < len(bands) else 1
            dr.rectangle((0, int(start * h), w, int(end * h)), fill=hexc(c))
        horizon = h - ground
        if extra is not None:
            if sky["obj"] == "high":
                im.alpha_composite(extra.im.crop((0, 0, w if w <= W else W, extra.h)), (0, 4))
            elif sky["obj"]:
                ox, oy = sky["obj"]
                y = horizon - FAR_H // 2 - extra.h if oy < 0 else int(oy * h)
                im.alpha_composite(extra.im, (int(ox * w) - extra.w // 2, y))
        for strip, y in ((far, horizon - FAR_H), (near, horizon - NEAR_H)):
            if strip is None:
                continue
            for x0 in range(0, w, W):
                im.alpha_composite(strip.im.crop((0, 0, min(W, w - x0), strip.h)), (x0, y))
        dr.rectangle((0, horizon, w, h), fill=(26, 28, 44, 255))
        dr.rectangle((0, horizon, w, horizon + 1), fill=(42, 47, 69, 255))
        big = im.resize((w * scale, h * scale), Image.NEAREST)
        sheet.paste(big, ((i % cols) * (w + 4) * scale, (i // cols) * (h + 4) * scale))
    sheet.save(path)


# more banners live in banners_more.py (playful / pastel ones and a few others)
from banners_more import MORE, MORE_SKY  # noqa: E402

BANNERS.update(MORE)
SKY.update(MORE_SKY)

# anime-inspired settings live in banners_anime.py
from banners_anime import ANIME, ANIME_SKY  # noqa: E402

BANNERS.update(ANIME)
SKY.update(ANIME_SKY)

# space scenes live in banners_space.py
from banners_space import SPACE, SPACE_SKY  # noqa: E402

BANNERS.update(SPACE)
SKY.update(SPACE_SKY)
