"""Space banners (for the space vehicles): planets, nebulae, a battle station, ..."""
import math
import random

from banners import FAR_H, NEAR_H, W, Strip, ridge, spots


def sphere(s, cx, cy, r, light, mid, dark, lx=-.45, ly=-.45):
    """Shaded ball: lit from the upper left."""
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            dx, dy = (x + .5 - cx) / r, (y + .5 - cy) / r
            d2 = dx * dx + dy * dy
            if d2 > 1:
                continue
            k = dx * lx + dy * ly + math.sqrt(1 - d2) * .7
            s.set(x, y, light if k > .62 else mid if k > .2 else dark)


def b_ringplanet():
    p = Strip(72, 44)
    sphere(p, 36, 22, 15, "#f0d8a0", "#d0a868", "#8a6a40")
    for y in (17, 22, 27):                                   # bands
        for x in range(21, 52):
            if p.get(x, y):
                p.set(x, y, "#b88a50")
    for x in range(72):                                      # ring (in front of the lower half)
        t = (x - 36) / 34
        if abs(t) <= 1:
            y = 22 + t * 5
            for k, c in ((0, "#e8dcc0"), (1, "#c8b890"), (-1, "#f4ecd8")):
                p.set(x, int(y + k), c)
    return None, None, p


def b_redplanet():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 12, [(2, 4, .7), (5, 2, 1.9)], "#a8482e", cap="#c8603a")
    tops = ridge(near, 8, [(3, 2, .3), (6, 1, 2.2)], "#6e2a1e", cap="#8a3a26")
    for cx in spots(301, 7):                                 # rocks
        near.rect(cx, tops[cx] - 2, cx + 2, tops[cx], "#4a1c16")
    moons = Strip(40, 20)
    sphere(moons, 8, 8, 6, "#d8c8b8", "#a89888", "#6a5a50")
    sphere(moons, 30, 12, 3.5, "#c8b8a8", "#988878", "#5a4a40")
    return far, near, moons


def b_iceplanet():
    p = Strip(56, 56)
    sphere(p, 28, 28, 25, "#f0faff", "#a8d8f0", "#4a7aa8")
    r = random.Random(311)
    for _ in range(14):                                      # cracks / frost streaks
        x, y = r.randint(8, 48), r.randint(8, 48)
        for i in range(r.randint(3, 8)):
            if p.get(x + i, y + i // 2):
                p.set(x + i, y + i // 2, "#7ab0d8")
    return None, None, p


def b_lavaplanet():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 14, [(2, 5, 1.2), (4, 2, .1)], "#2a1416", cap="#4a1e1a")
    tops = ridge(near, 9, [(3, 2, 2.0), (5, 1, .7)], "#1a0c0e", cap="#2e1414")
    r = random.Random(321)
    for x0 in spots(322, 9):                                 # glowing cracks
        x, y = x0, tops[x0] + 2
        for _ in range(8):
            near.set(x, y, "#ff6a2a" if r.random() < .7 else "#ffcd75")
            x += r.choice((-1, 0, 1))
            y += 1
    for x in range(W):                                       # lava glow along the far ridge foot
        if x % 3 == 0:
            far.set(x, FAR_H - 1, "#ff6a2a")
    return far, near, None


def b_earthorbit():
    far = Strip(W, FAR_H)
    # the curved limb of a huge planet below (repeats seamlessly: a gentle wave)
    land = [(10, 22, 9), (44, 30, 12), (80, 20, 7), (104, 32, 10)]
    clouds = [(24, 18, 10), (64, 26, 14), (96, 24, 8), (118, 16, 6)]
    for x in range(W):
        top = 12 + int(6 * (1 - math.cos(2 * math.pi * x / W)) / 2)
        far.col(x, top, "#2a5ab0")
        for y in range(top + 2, FAR_H):
            if any(abs(x - cx) < r and abs(y - cy) < r // 3 for cx, cy, r in land):
                far.set(x, y, "#3a8a4a" if (x + y) % 5 else "#5aa85a")
        for cx, cy, r in clouds:                              # cloud bands
            for y in range(cy - 1, cy + 2):
                if abs(x - cx) < r - abs(y - cy) * 2 and y > top + 1:
                    far.set(x, y, "#e8f0ff")
        far.set(x, top, "#bfe8ff")                            # atmosphere rim
        far.set(x, top + 1, "#6aaae8")
        far.set(x, top - 1, (120, 190, 255, 110))
    return far, None, None


def b_twinsuns():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 10, [(2, 3, .5), (3, 2, 2.3)], "#e0a868", cap="#f0c888")
    ridge(near, 7, [(1, 2, 1.0), (4, 1, .3)], "#b87a48", cap="#d0905a")
    suns = Strip(46, 22)
    for cx, cy, r, a, b in ((10, 11, 9, "#ffe0a0", "#fff4d8"), (34, 8, 6, "#ffb070", "#ffd8a8")):
        suns.circle(cx, cy, r, a)
        suns.circle(cx, cy, r - 2.5, b)
    return far, near, suns


def b_nebula():
    neb = Strip(W, 44)
    r = random.Random(341)
    blobs = [(r.uniform(0, W), r.uniform(8, 36), r.uniform(16, 30), r.choice(
        [(200, 90, 200), (90, 120, 230), (230, 110, 150), (120, 200, 230)])) for _ in range(12)]
    for y in range(44):
        for x in range(W):
            acc, col = 0.0, [0.0, 0.0, 0.0]
            for bx, by, br, c in blobs:
                for wrap in (-W, 0, W):                       # seamless horizontally
                    d = math.hypot(x - bx - wrap, (y - by) * 1.6) / br
                    if d < 1:
                        w = (1 - d) ** 2
                        acc += w
                        col = [col[i] + c[i] * w for i in range(3)]
            if acc > .05:
                level = min(3, int(acc * 4))                  # 3 steps + checker dither between them
                if level == 0 and (x + y) % 2:
                    continue
                a = (60, 95, 130, 160)[level]
                neb.set(x, y, tuple(int(v / acc) for v in col) + (a,))
    return None, None, neb


def b_asteroids():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(351)
    for _ in range(18):
        x, y, s = r.randint(0, W), r.randint(4, FAR_H - 4), r.uniform(1.5, 3.5)
        far.circle(x, y, s, "#4a4a5a")
        far.set(int(x - s / 2), int(y - s / 2), "#7a7a8a")
    for _ in range(7):
        x, y, s = r.randint(0, W), r.randint(4, NEAR_H - 6), r.uniform(3, 6)
        near.circle(x, y, s, "#5a5260")
        near.circle(x - s / 3, y - s / 3, s / 2, "#8a8090")
        near.set(int(x + s / 3), int(y), "#3a3440")
    return far, near, None


def b_blackhole():
    p = Strip(64, 40)
    for y in range(40):                                       # accretion disk: flat glowing ellipse ring
        for x in range(64):
            dx, dy = (x + .5 - 32) / 30, (y + .5 - 20) / 7
            d = math.hypot(dx, dy)
            if .38 < d < 1:
                k = 1 - abs(d - .62) / .38
                if k > .55 or (x + y) % 2 == 0:
                    p.set(x, y, (255, int(140 + 100 * k), int(50 + 140 * k), int(110 + 145 * k)))
    for y in range(40):                                       # lensed back of the disk arching over the hole
        for x in range(64):
            d = math.hypot(x + .5 - 32, (y + .5 - 20) * 1.0)
            if 9 <= d < 10.5 and y < 20:
                p.set(x, y, (255, 220, 160, 230))
    p.circle(32, 20, 8.5, (2, 2, 6, 255))                     # event horizon
    for y in range(40):                                       # thin photon ring
        for x in range(64):
            d = math.hypot(x + .5 - 32, y + .5 - 20)
            if 8.5 <= d < 9.3:
                p.set(x, y, (255, 240, 210, 255))
    return None, None, p


def b_galaxy():
    p = Strip(60, 44)
    for i in range(900):                                     # two spiral arms
        a = i / 900 * 4 * math.pi
        for arm in (0, math.pi):
            rr = 2 + a * 2.0
            x = 30 + math.cos(a + arm) * rr
            y = 22 + math.sin(a + arm) * rr * .55
            jitter = (i * 7919 % 5) - 2
            alpha = max(40, 230 - int(rr * 7))
            p.set(int(x + jitter * .4), int(y), (200, 180, 255, alpha))
    p.circle(30, 22, 3, "#fff4d8")
    p.circle(30, 22, 1.5, "#ffffff")
    return None, None, p


def b_battlestation():
    """Grey battle moon with panel lines and an equatorial trench (no dish)."""
    p = Strip(56, 56)
    sphere(p, 28, 28, 25, "#b8bcc8", "#8a8e9a", "#4a4e5a")
    for y in range(4, 53, 5):                                # panel rows
        for x in range(3, 54):
            if p.get(x, y) and (x * 3 + y) % 7 < 4:
                p.set(x, y, "#6a6e7a")
    for x in range(3, 54):                                   # trench
        for y in (27, 28):
            if p.get(x, y):
                p.set(x, y, "#2a2c34")
    r = random.Random(371)
    for _ in range(40):                                      # tiny lights on the dark side
        x, y = r.randint(28, 52), r.randint(28, 52)
        if p.get(x, y):
            p.set(x, y, "#ffe8a0")
    return None, None, p


SPACE = {
    "ringplanet": b_ringplanet, "redplanet": b_redplanet, "iceplanet": b_iceplanet, "lavaplanet": b_lavaplanet,
    "earthorbit": b_earthorbit, "twinsuns": b_twinsuns, "nebula": b_nebula, "asteroids": b_asteroids,
    "blackhole": b_blackhole, "galaxy": b_galaxy, "battlestation": b_battlestation,
}

SPACE_SKY = {
    "ringplanet":    dict(bands=[(0, "05060f"), (.4, "0c0e22"), (.75, "141838")], obj=(.62, .16), fx="stars"),
    "redplanet":     dict(bands=[(0, "1a0a10"), (.35, "3a1418"), (.6, "6a2a22"), (.8, "a04a32")], obj=(.70, .10), fx="stars"),
    "iceplanet":     dict(bands=[(0, "04060e"), (.45, "0a1024"), (.8, "101a38")], obj=(.66, .14), fx="stars"),
    "lavaplanet":    dict(bands=[(0, "0e0608"), (.35, "2a0a0c"), (.6, "4a1210"), (.8, "8a2a14")], obj=None, fx="embers"),
    "earthorbit":    dict(bands=[(0, "020308"), (.45, "060a18"), (.8, "0a1430")], obj=None, fx="stars"),
    "twinsuns":      dict(bands=[(0, "4a7ab8"), (.3, "8aa8d0"), (.55, "f0c890"), (.75, "f8a868")], obj=(.62, .18), fx="none"),
    "nebula":        dict(bands=[(0, "06040e"), (.4, "0e0a20"), (.75, "180f30")], obj="high", fx="stars"),
    "asteroids":     dict(bands=[(0, "04050c"), (.45, "0a0c1c"), (.8, "12162e")], obj=None, fx="stars"),
    "blackhole":     dict(bands=[(0, "020205"), (.45, "06060e"), (.8, "0c0a1a")], obj=(.55, .20), fx="stars"),
    "galaxy":        dict(bands=[(0, "04030c"), (.4, "0a081c"), (.75, "120e2e")], obj=(.62, .16), fx="stars"),
    "battlestation": dict(bands=[(0, "030409"), (.45, "080a16"), (.8, "0e1226")], obj=(.66, .14), fx="stars"),
}
