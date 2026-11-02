"""Anime-inspired banners: famous settings as scenes (no characters, logos or emblems)."""
import math
import random

from banners import FAR_H, NEAR_H, W, Strip, pines, ridge, round_trees, skyline, spots


def roofs(s, seed, wall, roof, dark, hmin=6, hmax=12, wmin=6, wmax=10, windows=None):
    """Row of small houses with pitched roofs (old town / village)."""
    r = random.Random(seed)
    x = 0
    while x < W:
        w = min(r.randint(wmin, wmax), W - x)
        if w < 4:
            break
        h = r.randint(hmin, hmax)
        s.rect(x, s.h - h, x + w - 1, s.h - 1, wall)
        s.tri(x + w // 2, s.h - h - w // 2 - 1, w // 2 + 1, s.h - h, roof)
        s.col(x + w - 1, s.h - h, dark)
        if windows:
            for wy in range(s.h - h + 2, s.h - 2, 3):
                if r.random() < .6:
                    s.set(x + 2, wy, windows)
        x += w + r.choice((0, 1))


def b_wallcity():
    """Giant stone wall around the city (Attack on Titan)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    far.rect(0, 6, W - 1, FAR_H - 1, "#8a8070")              # the wall, filling the far horizon
    for y in range(8, FAR_H, 4):                              # stone courses
        off = (y // 4 % 2) * 6
        far.rect(0, y, W - 1, y, "#756c5e")
        for x in range(off, W, 12):
            far.col(x, y - 3, "#756c5e", y)
    far.rect(0, 4, W - 1, 6, "#a69c8a")                       # wall walk
    for x in range(0, W, 8):                                  # battlements
        far.rect(x, 2, x + 3, 3, "#a69c8a")
    for x in (40, 104):                                       # watch towers
        far.rect(x - 4, 0, x + 4, 12, "#9a9080")
        far.tri(x, -3, 6, 1, "#6e3a2e")
    roofs(near, 5, "#d8c8a8", "#8a3b2e", "#a89878", 8, 16, 7, 11, windows="#3a2a20")
    return far, near, None


def b_ninjavillage():
    """Village in the forest with round towers and red roofs (Naruto)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 26, [(1, 5, .9), (2, 3, 2.2)], "#4f6f4a", cap="#6a8a5a")      # big cliff / hills
    for x in range(W):                                                        # rock face stripes
        if x % 7 == 0:
            for y in range(14, FAR_H, 3):
                far.set(x, y, "#5f7a54")
    pines(far, 12, 14, "#2f4f34", 10, 16)
    r = random.Random(13)
    x = 2
    while x < W - 10:                                                         # round buildings + boxy houses
        if r.random() < .4:
            near.rect(x, NEAR_H - 14, x + 9, NEAR_H - 1, "#e8d8b8")
            near.circle(x + 5, NEAR_H - 14, 6, "#c0443a")
            near.rect(x - 1, NEAR_H - 14, x + 10, NEAR_H - 14, "#8a2a24")
            near.rect(x + 3, NEAR_H - 8, x + 6, NEAR_H - 1, "#6a4a30")
            x += 12
        else:
            w = r.randint(6, 9)
            h = r.randint(8, 12)
            near.rect(x, NEAR_H - h, x + w, NEAR_H - 1, "#f0e2c8")
            near.rect(x - 1, NEAR_H - h - 2, x + w + 1, NEAR_H - h, "#d05a3a")
            near.set(x + 2, NEAR_H - h + 3, "#3b4a6a")
            near.set(x + w - 2, NEAR_H - h + 3, "#3b4a6a")
            x += w + 3
    return far, near, None


def b_redclouds():
    """Dark night sky with drifting red clouds (Akatsuki mood; generic cloud shapes)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 10, [(2, 4, 1.7), (5, 2, .4)], "#1c1420")
    ridge(near, 7, [(3, 2, .6), (7, 1, 2.0)], "#0c080e", cap="#22161e")
    sky = Strip(W, 36)
    for cx, cy, s in ((16, 8, 1.0), (60, 20, .8), (100, 10, 1.1)):
        # puffy cloud: red fill, light outline
        parts = [(0, 0, 5), (6, -2, 6), (12, 0, 5), (6, 3, 5)]
        for dx, dy, rr in parts:
            sky.circle(cx + dx * s, cy + dy * s, rr * s + 1, "#f4e8e8")
        for dx, dy, rr in parts:
            sky.circle(cx + dx * s, cy + dy * s, rr * s, "#c8283a")
        sky.circle(cx + 5 * s, cy - 2 * s, 2 * s, "#e0485a")
    return far, near, sky


def b_grassroute():
    """Route through tall grass with small trees (Pokémon)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 12, [(2, 3, .4), (4, 1.5, 1.2)], "#78c06a", cap="#98e08a")
    round_trees(far, 22, 9, "#3a9a4a", "#5aba5a", "#6a4a30", 30, 36, 5, 7)
    near.rect(0, 0, W - 1, NEAR_H - 1, "#58b048")
    near.rect(0, 10, W - 1, 15, "#e8d098")                    # the path
    near.rect(0, 10, W - 1, 10, "#c8b078")
    for x in range(0, W, 4):                                  # tall grass tufts
        for y0 in (2, 19):
            near.set(x, y0, "#2e7a3a")
            near.set(x + 1, y0 - 1, "#2e7a3a")
            near.set(x + 2, y0, "#2e7a3a")
            near.rect(x, y0 + 1, x + 2, y0 + 2, "#3a8a3a")
    for cx in spots(23, 3):                                   # a few flowers
        near.set(cx, 6, "#ff6a6a")
        near.set(cx + 1, 22, "#ffe070")
    return far, near, None


def b_spiritforest():
    """Huge old tree, misty forest, small shrine (Ghibli forest mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    for cx in range(0, W, 10):
        far.circle(cx + 3, FAR_H - 8, 10, "#2f5a3a")
    cx = 64                                                    # the giant tree
    far.rect(cx - 4, 10, cx + 4, FAR_H - 1, "#4a3424")
    far.rect(cx - 6, FAR_H - 4, cx + 6, FAR_H - 1, "#4a3424")
    for dx, dy, r in ((0, 4, 13), (-12, 10, 9), (12, 10, 9), (-6, 0, 8), (8, 1, 8)):
        far.circle(cx + dx, dy + 6, r, "#3a6a3e")
    for dx, dy, r in ((-4, 2, 5), (6, 6, 4)):
        far.circle(cx + dx, dy + 6, r, "#4f8a4e")
    near.rect(0, NEAR_H - 4, W - 1, NEAR_H - 1, "#1f3a26")
    for x in range(0, W, 3):
        near.col(x, NEAR_H - 4 - (x * 7 % 5), "#2a4a2e", NEAR_H - 4)
    near.rect(20, NEAR_H - 9, 26, NEAR_H - 4, "#b8a888")       # small stone shrine
    near.tri(23, NEAR_H - 13, 5, NEAR_H - 9, "#8a3a2e")
    return far, near, None


def b_skyisland():
    """Floating island with ruins above the clouds (Laputa)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    for cx in range(0, W, 8):
        far.circle(cx, FAR_H - 3, 7, "#e6eef8")
    for cx in range(0, W, 11):
        near.circle(cx + 3, NEAR_H, 9, "#ffffff")
        near.circle(cx + 9, NEAR_H - 2, 6, "#f0f6ff")
    isl = Strip(52, 40)
    isl.rect(8, 16, 44, 20, "#6a8a4a")                         # grass top
    for y in range(20, 36):                                    # rocky underside, tapering
        k = (y - 20) / 16
        hw = int(18 * (1 - k) + 2)
        isl.rect(26 - hw, y, 26 + hw, y, "#7a6a5a" if y % 3 else "#5f5246")
    isl.rect(14, 8, 18, 16, "#c8bca8")                          # ruin towers
    isl.rect(30, 4, 35, 16, "#c8bca8")
    isl.rect(31, 6, 34, 7, "#8a8070")
    round_trees(isl, 31, 3, "#3a8a4a", "#5aaa5a", "#5a4030", 18, 22, 3, 5)
    for x in (12, 40):                                          # hanging roots
        isl.col(x, 20, "#4a6a3a", 30)
    return far, near, isl


def b_tournament():
    """Martial arts tournament stage on an island (Dragon Ball)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    far.rect(0, 20, W - 1, FAR_H - 1, "#3a8ad0")                # sea
    for y in range(22, FAR_H, 3):
        for x in spots(41 + y, 5):
            far.rect(x, y, x + 2, y, "#7ab8f0")
    for cx in (20, 108):                                        # palm islets
        far.rect(cx - 6, 18, cx + 6, 21, "#f0d898")
        for i in range(10):
            far.set(cx + i // 4, 18 - i, "#7a4a32")
        for i in range(-5, 6):
            far.set(cx + 2 + i, 8 + abs(i) // 2, "#38a050")
    near.rect(0, 6, W - 1, NEAR_H - 1, "#d8d0c0")              # stone tiles of the stage
    for y in range(6, NEAR_H, 4):
        near.rect(0, y, W - 1, y, "#b8b0a0")
        for x in range((y // 4 % 2) * 8, W, 16):
            near.col(x, y, "#b8b0a0", min(NEAR_H - 1, y + 4))
    near.rect(0, 4, W - 1, 5, "#f0e8d8")
    return far, near, None


def b_pirates():
    """Open sea with a sailing ship and gulls (One Piece mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    far.rect(0, 26, W - 1, FAR_H - 1, "#2a6ab0")
    far.rect(0, 26, W - 1, 26, "#5a9ad8")
    cx = 70                                                     # the ship (generic sails, no flag emblem)
    far.rect(cx - 14, 22, cx + 14, 26, "#7a4a2a")
    far.rect(cx - 16, 21, cx - 12, 22, "#7a4a2a")
    far.rect(cx + 12, 19, cx + 16, 22, "#7a4a2a")
    far.rect(cx - 13, 23, cx + 13, 23, "#e0b860")
    for mx, top in ((cx - 5, 2), (cx + 6, 6)):
        far.col(mx, top, "#4a3020", 22)
        far.rect(mx - 6, top + 2, mx + 6, top + 9, "#f4f0e0")
        far.rect(mx - 5, top + 11, mx + 5, top + 16, "#f4f0e0")
    far.rect(cx - 5, 0, cx + 1, 2, "#c83a3a")                   # plain pennant
    for i in range(3):                                          # gulls
        gx, gy = 20 + i * 14, 6 + i * 3
        far.set(gx, gy, "#f4f4f4")
        far.set(gx + 1, gy - 1, "#f4f4f4")
        far.set(gx + 2, gy, "#f4f4f4")
        far.set(gx + 3, gy - 1, "#f4f4f4")
        far.set(gx + 4, gy, "#f4f4f4")
    near.rect(0, NEAR_H - 9, W - 1, NEAR_H - 1, "#1f5a9a")      # near water only at the bottom
    r = random.Random(51)
    for y in range(NEAR_H - 8, NEAR_H, 3):                      # waves
        for x in spots(52 + y, 8):
            near.rect(x, y, x + r.randint(2, 5), y, "#6aa8e8" if y < 12 else "#4a88c8")
    return far, near, None


def b_wisteria():
    """Night under hanging wisteria (Demon Slayer mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 8, [(2, 2, .8)], "#1a1430")
    near.rect(0, NEAR_H - 3, W - 1, NEAR_H - 1, "#141022")
    blooms = Strip(W, 38)                                       # hanging from the top
    r = random.Random(61)
    for x in range(0, W, 2):
        length = 6 + int(10 * (.5 + .5 * math.sin(x / 5.0))) + r.randint(0, 6)
        for y in range(length):
            c = "#c9a0f0" if (x + y) % 3 else "#e8ccff"
            if y > length - 3:
                c = "#9a6ad8"
            blooms.set(x, y, c)
    blooms.rect(0, 0, W - 1, 1, "#3a5a3a")
    return far, near, blooms


def b_moonprincess():
    """Pastel night with a big crescent moon and sparkles (Sailor Moon mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    skyline(far, 71, "#5a4a8a", "#ffe0f0", lit=.12, min_h=10, max_h=30, wmin=6, wmax=12)
    skyline(near, 72, "#3a2a5a", "#ffd0e8", lit=.2, min_h=6, max_h=18, wmin=7, wmax=12)
    moon = Strip(36, 36)
    moon.circle(18, 18, 16, "#fff2b0")
    moon.circle(25, 13, 14, (0, 0, 0, 0))
    moon.circle(9, 22, 2, "#ffe080")
    return far, near, moon


def b_neotokyo():
    """Neon megacity at night (Akira / cyberpunk anime)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    skyline(far, 81, "#2a1a3a", "#ff4a8a", lit=.18, min_h=16, max_h=40, wmin=6, wmax=11, roof="#2a1a3a")
    skyline(near, 82, "#0e0a16", "#4ae0ff", lit=.25, min_h=8, max_h=22, wmin=7, wmax=13, roof="#0e0a16")
    for x in spots(83, 5):                                       # neon signs
        near.rect(x, NEAR_H - 18, x + 1, NEAR_H - 12, "#ff3a6a")
    near.rect(0, NEAR_H - 2, W - 1, NEAR_H - 2, "#ff3a3a")       # light trail on the street
    near.rect(0, NEAR_H - 1, W - 1, NEAR_H - 1, "#ffb03a")
    return far, near, None


def b_comet():
    """Twilight lake town under a splitting comet (Your Name mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    ridge(far, 16, [(1, 4, 2.0), (3, 2, .5)], "#3a3a6a", cap="#4f4f86")
    far.rect(0, FAR_H - 8, W - 1, FAR_H - 1, "#5a6ab0")          # lake reflecting the sky
    far.rect(0, FAR_H - 8, W - 1, FAR_H - 8, "#8a9ae0")
    roofs(near, 91, "#2a2440", "#3a2f55", "#1e1a30", 5, 10, 5, 8, windows="#ffd080")
    comet = Strip(60, 40)
    for i in range(40):                                          # two tails splitting
        x0, y0 = 4 + i, 2 + int(i * .8)
        a = int(60 + i * 4)
        comet.set(x0, y0, (180, 220, 255, min(255, a)))
        comet.set(x0 + 1, y0, (180, 220, 255, min(255, a)))
        if i > 22:
            comet.set(x0 + (i - 22) // 2, y0 + (i - 22) // 3 + 1, (255, 190, 230, min(255, a)))
    comet.circle(45, 34, 2.5, "#ffffff")
    return far, near, comet


def b_torii():
    """Torii gate and shrine at dusk (Jujutsu Kaisen / shrine anime mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    pines(far, 101, 18, "#1f2a2a", 14, 28, ground=4)
    for tx in (44,):                                              # big torii
        far.rect(tx - 14, 6, tx + 14, 8, "#d83a2a")
        far.rect(tx - 16, 5, tx + 16, 5, "#1a1a1a")
        far.rect(tx - 12, 12, tx + 12, 13, "#d83a2a")
        far.rect(tx - 10, 6, tx - 8, FAR_H - 1, "#d83a2a")
        far.rect(tx + 8, 6, tx + 10, FAR_H - 1, "#d83a2a")
    far.rect(84, 20, 108, FAR_H - 1, "#5a3a2a")                    # shrine hall
    far.tri(96, 10, 16, 20, "#2a2a30")
    near.rect(0, NEAR_H - 4, W - 1, NEAR_H - 1, "#7a7068")         # stone path
    for x in range(0, W, 12):                                     # stone lanterns
        near.rect(x + 2, NEAR_H - 12, x + 4, NEAR_H - 4, "#9a9088")
        near.rect(x, NEAR_H - 14, x + 6, NEAR_H - 12, "#9a9088")
        near.set(x + 3, NEAR_H - 11, "#ffd070")
    return far, near, None


def b_bathhouse():
    """Lantern-lit bathhouse over the water (Spirited Away mood)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    cx = 64
    for i, (w, h, c) in enumerate(((44, 8, "#a8402e"), (36, 8, "#b84a34"), (28, 8, "#a8402e"), (18, 6, "#b84a34"))):
        y = FAR_H - 8 - i * 8
        far.rect(cx - w // 2, y - h, cx + w // 2, y, c)
        far.rect(cx - w // 2 - 3, y - h - 1, cx + w // 2 + 3, y - h, "#2a3a4a")    # roof edge
        for wx in range(cx - w // 2 + 2, cx + w // 2 - 1, 4):
            far.set(wx, y - h // 2, "#ffd070")
    far.rect(0, FAR_H - 8, W - 1, FAR_H - 1, "#2a4a6a")            # water
    near.rect(0, NEAR_H - 6, W - 1, NEAR_H - 1, "#1f3a5a")
    near.rect(0, NEAR_H - 9, W - 1, NEAR_H - 8, "#4a2a20")          # bridge
    for x in range(0, W, 10):
        near.col(x, NEAR_H - 14, "#4a2a20", NEAR_H - 8)
        near.rect(x - 1, NEAR_H - 17, x + 1, NEAR_H - 15, "#ff9a4a")   # lanterns
        near.set(x, NEAR_H - 16, "#ffe090")
    return far, near, None


def b_fuji():
    """Snowy volcano cone with a bullet train passing (classic anime landscape)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    for x in range(W):
        d = abs(x - 64)
        top = 4 + int(d * .55) if d < 64 else FAR_H
        far.col(x, top, "#5a6aa8")
        for y in range(top, min(FAR_H, top + 6 - d // 12)):
            far.set(x, y, "#f4f6ff")
    far.rect(0, FAR_H - 6, W - 1, FAR_H - 1, "#4a7a5a")
    near.rect(0, NEAR_H - 4, W - 1, NEAR_H - 1, "#3a3a44")       # embankment
    near.rect(0, NEAR_H - 5, W - 1, NEAR_H - 5, "#8a8a94")       # rail
    near.rect(10, NEAR_H - 12, 110, NEAR_H - 6, "#f4f4f8")       # long white train
    near.rect(10, NEAR_H - 9, 110, NEAR_H - 9, "#2a5ab0")
    near.tri(114, NEAR_H - 12, 4, NEAR_H - 6, "#f4f4f8")
    for x in range(14, 108, 4):
        near.set(x, NEAR_H - 11, "#4a6a8a")
    return far, near, None


def b_ramenalley():
    """Narrow alley at night with lanterns and a noodle stand."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    skyline(far, 111, "#1e1a2e", "#ffb060", lit=.15, min_h=20, max_h=38, wmin=8, wmax=14)
    near.rect(0, NEAR_H - 3, W - 1, NEAR_H - 1, "#2a2230")
    r = random.Random(112)
    for x in range(0, W, 16):                                   # stalls with cloth curtains
        near.rect(x, NEAR_H - 18, x + 13, NEAR_H - 3, "#4a2a22")
        near.rect(x - 1, NEAR_H - 20, x + 14, NEAR_H - 18, "#2a1a16")
        for k in range(4):
            near.rect(x + 1 + k * 3, NEAR_H - 17, x + 2 + k * 3, NEAR_H - 12, "#e8dcc8" if k % 2 else "#c84a3a")
        near.rect(x + 4, NEAR_H - 11, x + 9, NEAR_H - 9, "#ffd080")       # warm light inside
    for x in range(6, W, 12):                                   # string of lanterns
        near.rect(x, 1, x + 2, 4, "#e85a3a")
        near.set(x + 1, 2, "#ffd090")
    near.rect(0, 0, W - 1, 0, "#3a2a2a")
    return far, near, None


def b_schoolroof():
    """School rooftop with a fence and cherry trees below (slice-of-life anime)."""
    far, near = Strip(W, FAR_H), Strip(W, NEAR_H)
    skyline(far, 121, "#8aa0c8", "#c8d8f0", lit=.1, min_h=8, max_h=22, wmin=8, wmax=16)
    round_trees(far, 122, 8, "#f0a8c8", "#ffd0e4", "#6a4a40", 26, 34, 5, 7, flowers="#ffffff")
    near.rect(0, NEAR_H - 5, W - 1, NEAR_H - 1, "#9a9aa4")       # roof floor
    near.rect(0, NEAR_H - 18, W - 1, NEAR_H - 18, "#4a5a5a")     # fence top rail
    near.rect(0, NEAR_H - 6, W - 1, NEAR_H - 6, "#4a5a5a")
    for x in range(0, W, 3):                                    # fence mesh
        near.col(x, NEAR_H - 17, "#5a6a6a", NEAR_H - 7)
    for y in range(NEAR_H - 15, NEAR_H - 7, 3):
        near.rect(0, y, W - 1, y, "#5a6a6a")
    return far, near, None


ANIME = {
    "wallcity": b_wallcity, "ninjavillage": b_ninjavillage, "redclouds": b_redclouds, "grassroute": b_grassroute,
    "spiritforest": b_spiritforest, "skyisland": b_skyisland, "tournament": b_tournament, "pirates": b_pirates,
    "wisteria": b_wisteria, "moonprincess": b_moonprincess, "neotokyo": b_neotokyo, "comet": b_comet,
    "torii": b_torii, "bathhouse": b_bathhouse, "fuji": b_fuji, "ramenalley": b_ramenalley,
    "schoolroof": b_schoolroof,
}

ANIME_SKY = {
    "wallcity":     dict(bands=[(0, "8ab0d8"), (.35, "a8c4e0"), (.65, "d8dce0")], obj=None, fx="birds"),
    "ninjavillage": dict(bands=[(0, "5aa8e8"), (.35, "8ac4f0"), (.65, "c8e4f8")], obj=None, fx="leaves"),
    "redclouds":    dict(bands=[(0, "0a0810"), (.35, "160e1c"), (.65, "24142a")], obj="high", fx="rain"),
    "grassroute":   dict(bands=[(0, "5ab0f0"), (.35, "88c8f5"), (.65, "c0e4fa")], obj=None, fx="clouds"),
    "spiritforest": dict(bands=[(0, "2a4a4a"), (.35, "3a6058"), (.65, "6a8a6a")], obj=None, fx="fireflies"),
    "skyisland":    dict(bands=[(0, "4a8ae0"), (.35, "7aaaf0"), (.65, "b0d0f8")], obj=(.62, .18), fx="birds"),
    "tournament":   dict(bands=[(0, "4a9ae8"), (.35, "7ab8f0"), (.65, "b8dcf8")], obj=None, fx="clouds"),
    "pirates":      dict(bands=[(0, "3a7ad0"), (.35, "6aa0e0"), (.65, "a8cff0")], obj=None, fx="clouds"),
    "wisteria":     dict(bands=[(0, "0e0a20"), (.35, "1a1436"), (.65, "2a2050")], obj="high", fx="petals"),
    "moonprincess": dict(bands=[(0, "2a2058"), (.35, "4a3080"), (.6, "8a5aa8"), (.8, "d08ac0")], obj=(.70, .14), fx="sparkles"),
    "neotokyo":     dict(bands=[(0, "0a0614"), (.35, "1a0a2a"), (.6, "3a0f40"), (.8, "6a1a50")], obj=None, fx="rain"),
    "comet":        dict(bands=[(0, "1a2058"), (.3, "3a3a80"), (.55, "8a5aa0"), (.75, "f0a0a0")], obj=(.55, .10), fx="stars"),
    "torii":        dict(bands=[(0, "2a2050"), (.3, "6a3a6a"), (.55, "c86a5a"), (.72, "f0a060")], obj=None, fx="petals"),
    "fuji":         dict(bands=[(0, "6a9ae0"), (.35, "9ab8ec"), (.65, "f0d0c8")], obj=None, fx="birds"),
    "ramenalley":   dict(bands=[(0, "0c0a18"), (.35, "1a1428"), (.65, "2a1e36")], obj=None, fx="stars"),
    "schoolroof":   dict(bands=[(0, "6aa8e8"), (.35, "98c4f0"), (.65, "f0d8e8")], obj=None, fx="petals"),
    "bathhouse":    dict(bands=[(0, "0e1a3a"), (.35, "1a2a50"), (.65, "3a3a6a")], obj=None, fx="fireflies"),
}
