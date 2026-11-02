"""More banners: playful / pastel ones and a few others (layers like banners.py)."""
import math
import random

from banners import FAR_H, NEAR_H, W, Strip, ridge, spots


def heart(s, cx, cy, c, size=1):
    """Tiny pixel heart (5x4 cells)."""
    rows = ["01010", "11111", "01110", "00100"]
    for dy, row in enumerate(rows):
        for dx, on in enumerate(row):
            if on == "1":
                s.rect(cx + (dx - 2) * size, cy + dy * size, cx + (dx - 2) * size + size - 1, cy + dy * size + size - 1, c)


def b_cottoncandy():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(141)
    for cx in range(0, W, 9):                     # puffy pink cloud bank
        far.circle(cx + r.randint(0, 4), FAR_H - 6 + r.randint(-3, 2), r.randint(7, 10), "#ffc6e4")
    for cx in range(4, W, 13):
        far.circle(cx, FAR_H - 2, 8, "#c9d8ff")
    for cx in range(2, W, 11):                    # white tops on the near clouds
        near.circle(cx + r.randint(0, 3), NEAR_H - 2 + r.randint(-2, 2), r.randint(6, 8), "#fff3fa")
        near.circle(cx + 4, NEAR_H + 2, 7, "#ffd9ee")
    rainbow = Strip(56, 30)
    for i, c in enumerate(("#ff8fb1", "#ffc78a", "#fff3a0", "#a8f0b0", "#9fd0ff", "#c8a8ff")):
        for x in range(56):
            for y in range(30):
                d = math.hypot(x + .5 - 28, y + .5 - 30)
                if 26 - i * 2.6 >= d > 26 - (i + 1) * 2.6:
                    rainbow.set(x, y, c)
    return far, near, rainbow


def b_hearts():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 14, [(2, 4, .5), (3, 2, 2.0)], "#e486b8", cap="#f5a8cf")
    tops = ridge(near, 9, [(1, 2, 1.3), (4, 1, .2)], "#b8508a", cap="#d06aa2")
    for cx in spots(151, 7):                      # heart-shaped bushes
        y = tops[cx] - 7
        heart(near, cx, y, "#ff5c9a", 2)
        near.set(cx - 3, y + 1, "#ffb3d1")
    return far, near, None


def b_strawberry():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 13, [(2, 4, 1.0), (5, 2, .3)], "#7ac46a", cap="#9be08a")
    near.rect(0, 2, W - 1, NEAR_H - 1, "#5aa04a")
    near.rect(0, 2, W - 1, 2, "#7cc36a")
    r = random.Random(161)
    for row, y in enumerate(range(5, NEAR_H, 5)):            # planted rows with berries
        near.rect(0, y + 3, W - 1, y + 3, "#3f7a36")
        for x in range((row * 3) % 6, W, 6):
            near.rect(x, y, x + 3, y + 1, "#3a8a3c")
            if r.random() < .7:
                near.rect(x + 1, y + 1, x + 2, y + 2, "#e63950")
                near.set(x + 1, y + 1, "#ff8a9a")
    sun = Strip(22, 22)
    sun.circle(11, 11, 10, "#ffe0a0")
    sun.circle(11, 11, 8, "#fff4cf")
    return far, near, sun


def b_meadow():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 12, [(1, 3, .4), (3, 2, 1.7)], "#9fd88a", cap="#c3efa8")
    tops = ridge(near, 12, [(2, 2, 2.1), (5, 1, .7)], "#5fbf5a", cap="#7fd870")
    r = random.Random(171)
    for x in range(W):
        for y in range(tops[x] + 1, NEAR_H):
            k = r.random()
            if k < .06:
                near.set(x, y, r.choice(("#ff8fb8", "#fff0a0", "#ffffff", "#c8a8ff")))
            elif k < .1:
                near.set(x, y, "#4aa84a")
    return far, near, None


def b_glitter():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(181)
    x = 0
    while x < W:                                  # crystal peaks
        w = r.randint(6, 14)
        h = r.randint(12, 34)
        far.tri(x + w // 2, FAR_H - h, w // 2, FAR_H - 1, "#9a7ad0")
        far.tri(x + w // 2 - 1, FAR_H - h + 2, max(1, w // 4), FAR_H - 1, "#c4a8f0")
        x += w + r.randint(0, 3)
    ridge(near, 6, [(3, 1.5, .2)], "#3b2558", cap="#5a3a80")
    for cx in spots(182, 9):
        near.tri(cx, NEAR_H - 9, 2, NEAR_H - 4, "#ff9ee0")
        near.set(cx, NEAR_H - 9, "#ffffff")
    moon = Strip(22, 22)
    moon.circle(11, 11, 10, "#ffd6f0")
    moon.circle(8, 8, 3, "#ffeefa")
    moon.circle(14, 14, 2, "#f2b8dc")
    return far, near, moon


def b_lavender():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 10, [(2, 2, .9)], "#8a6aa8")
    for i, y in enumerate(range(FAR_H - 10, FAR_H, 2)):      # field rows, wider towards us
        for x in range(W):
            if (x + i * 3) % 8 < 4 + i // 2:
                far.set(x, y, "#a77bd0" if i % 2 else "#8f62bd")
    near.rect(0, 6, W - 1, NEAR_H - 1, "#5a3f7a")
    r = random.Random(191)
    for cx in range(0, W, 5):                     # lavender bushes
        near.col(cx, 5, "#4f8a4a", 9)
        for _ in range(10):
            near.set(cx + r.randint(-2, 2), 4 + r.randint(-4, 2), r.choice(("#b98ae6", "#9b6ad0", "#d8b8ff")))
    sun = Strip(24, 24)
    sun.circle(12, 12, 11, "#ffb89a")
    sun.circle(12, 12, 8.5, "#ffd9b8")
    return far, near, sun


def b_balloons():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 10, [(2, 3, .3), (3, 1.5, 1.4)], "#9cc8a8", cap="#bfe3c6")
    r = random.Random(201)
    x = 0
    while x < W:                                  # pastel houses
        w = r.randint(7, 11)
        if x + w > W:
            break
        h = r.randint(8, 14)
        c = r.choice(("#ffd1dc", "#ffe6a8", "#cde7ff", "#d9ffd6", "#f0d6ff"))
        near.rect(x, NEAR_H - h, x + w - 2, NEAR_H - 1, c)
        near.tri(x + (w - 2) // 2, NEAR_H - h - 4, (w - 1) // 2, NEAR_H - h, "#e0706a")
        near.rect(x + 2, NEAR_H - h + 3, x + 3, NEAR_H - h + 4, "#7a9ad0")
        x += w + r.randint(0, 2)
    sky = Strip(W, 34)                            # hot-air balloons (slowly drifting high layer)
    for cx, cy, a, b in ((14, 10, "#ff7aa8", "#ffd1e1"), (58, 18, "#ffcf5a", "#fff1b8"), (98, 7, "#7ac8ff", "#d6f0ff")):
        sky.circle(cx, cy, 6, a)
        sky.col(cx, cy - 6, b, cy + 5)
        sky.col(cx - 3, cy - 5, b, cy + 4)
        sky.col(cx + 3, cy - 5, b, cy + 4)
        sky.rect(cx - 1, cy + 8, cx + 1, cy + 10, "#8a5a3a")
        sky.set(cx - 2, cy + 7, "#8a5a3a")
        sky.set(cx + 2, cy + 7, "#8a5a3a")
    return far, near, sky


def b_jungle():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(211)
    for cx in range(0, W, 7):                     # canopy
        far.circle(cx + r.randint(0, 3), FAR_H - 10 + r.randint(-6, 4), r.randint(7, 11), "#1f6a3e")
    for cx in range(3, W, 9):
        far.circle(cx, FAR_H - 4, 8, "#2a8048")
    for cx in spots(212, 6):                      # trunks with vines
        far.col(cx, FAR_H - 14, "#3b2a1e")
        for y in range(FAR_H - 20, FAR_H - 6, 2):
            far.set(cx + 2, y, "#4aa85a")
    for cx in range(0, W, 6):                     # big leaves in front
        h = r.randint(10, 22)
        for i in range(h):
            near.set(cx + i // 3, NEAR_H - 1 - i, "#0f3a22")
            near.set(cx - i // 3, NEAR_H - 1 - i, "#0f3a22")
            near.set(cx, NEAR_H - 1 - i, "#1a5a32")
    return far, near, None


def b_harbor():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    far.rect(0, 22, W - 1, FAR_H - 1, "#1f3a5f")
    far.rect(0, 22, W - 1, 22, "#3f6a96")
    r = random.Random(221)
    for y in range(24, FAR_H, 3):
        for x in spots(222 + y, 6):
            far.rect(x, y, x + r.randint(1, 3), y, "#4f7ab0")
    lx = 96                                       # lighthouse on a rock
    far.rect(lx - 7, 18, lx + 7, 22, "#2a2f45")
    far.rect(lx - 2, 4, lx + 2, 18, "#f4f4f4")
    for y in (7, 12, 16):
        far.rect(lx - 2, y, lx + 2, y + 1, "#b13e53")
    far.rect(lx - 3, 1, lx + 3, 3, "#ffcd75")
    for i in range(1, 12):                        # light beam
        far.set(lx - 3 - i, 2, (255, 236, 160, 150))
        far.set(lx - 3 - i, 1 + (i > 6), (255, 236, 160, 90))
    near.rect(0, NEAR_H - 5, W - 1, NEAR_H - 1, "#3b2a1e")    # pier
    for x in range(2, W, 10):
        near.col(x, NEAR_H - 8, "#2a1e16", NEAR_H - 1)
    for cx in (30, 70):                           # sail boats
        near.rect(cx - 7, NEAR_H - 9, cx + 7, NEAR_H - 6, "#e0e6f0")
        near.rect(cx - 6, NEAR_H - 6, cx + 6, NEAR_H - 6, "#b13e53")
        near.col(cx, NEAR_H - 22, "#5a4a3a", NEAR_H - 9)
        near.tri(cx + 4, NEAR_H - 21, 4, NEAR_H - 11, "#f4f4f4")
    return far, near, None


def b_glacier():
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    r = random.Random(231)
    x = 0
    while x < W:                                  # ice cliffs
        w = min(r.randint(8, 18), W - x)
        h = r.randint(14, 30)
        far.rect(x, FAR_H - h, x + w - 1, FAR_H - 1, "#bfe2f5")
        far.rect(x, FAR_H - h, x + w - 1, FAR_H - h, "#ffffff")
        far.col(x + w - 1, FAR_H - h, "#8fc4e0")
        for y in range(FAR_H - h + 3, FAR_H, 4):
            far.rect(x + 1, y, x + w // 2, y, "#a4d4ee")
        x += w
    near.rect(0, NEAR_H - 4, W - 1, NEAR_H - 1, "#2f5f8f")    # water
    for cx in spots(232, 4):                      # ice floes with penguins
        near.rect(cx - 8, NEAR_H - 7, cx + 8, NEAR_H - 4, "#eaf6ff")
        near.rect(cx - 8, NEAR_H - 4, cx + 8, NEAR_H - 4, "#9fcbe8")
        for px in (cx - 5, cx + 1):               # penguin: dark back, white belly, beak, feet
            top = NEAR_H - 16
            near.rect(px + 1, top, px + 3, top + 2, "#1a1c2c")          # head
            near.rect(px, top + 3, px + 4, top + 8, "#1a1c2c")          # body
            near.rect(px + 1, top + 4, px + 3, top + 8, "#f4f4f4")      # belly
            near.set(px + 2, top + 1, "#f4f4f4")                        # eye
            near.set(px + 4, top + 1, "#ffcd75")                        # beak
            near.rect(px, top + 9, px + 1, top + 9, "#ef7d57")          # feet
            near.rect(px + 3, top + 9, px + 4, top + 9, "#ef7d57")
    return far, near, None


MORE = {
    "cottoncandy": b_cottoncandy, "hearts": b_hearts, "strawberry": b_strawberry, "meadow": b_meadow,
    "glitter": b_glitter, "lavender": b_lavender, "balloons": b_balloons, "jungle": b_jungle,
    "harbor": b_harbor, "glacier": b_glacier,
}

MORE_SKY = {
    "cottoncandy": dict(bands=[(0, "9fc4ff"), (.3, "c4c8ff"), (.55, "f0c4f0"), (.75, "ffd6ea")], obj=(.5, .22), fx="sparkles"),
    "hearts":      dict(bands=[(0, "b03a7a"), (.3, "d85a98"), (.55, "f08ab8"), (.75, "ffb8d6")], obj=None, fx="hearts"),
    "strawberry":  dict(bands=[(0, "8fc8f0"), (.35, "b8dcf5"), (.6, "f8d8e0")], obj=(.74, .16), fx="none"),
    "meadow":      dict(bands=[(0, "7ab8f0"), (.35, "a0d0f5"), (.65, "d0ecfa")], obj=None, fx="butterflies"),
    "glitter":     dict(bands=[(0, "1e1438"), (.35, "34205a"), (.65, "4f2f78")], obj=(.74, .14), fx="sparkles"),
    "lavender":    dict(bands=[(0, "4a3a7a"), (.3, "8a5a9a"), (.55, "d08aa0"), (.72, "f4b8a0")], obj=(.3, .34), fx="none"),
    "balloons":    dict(bands=[(0, "8ab8ea"), (.35, "a8ccf0"), (.65, "cfe4f8")], obj="high", fx="none"),
    "jungle":      dict(bands=[(0, "1a4a3a"), (.35, "2a6a4a"), (.65, "5a8a4a")], obj=None, fx="fireflies"),
    "harbor":      dict(bands=[(0, "141a3a"), (.35, "2a2a5a"), (.6, "5a3a6a")], obj=None, fx="stars"),
    "glacier":     dict(bands=[(0, "6aa8d8"), (.35, "9ac4e8"), (.65, "c8e2f4")], obj=None, fx="snow"),
}
