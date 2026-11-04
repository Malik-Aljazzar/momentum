"""One of the Nine: a little 8-bit critter per title (server/nine.py KEYS), hand-drawn on a N x N grid.

Classic low-res style: flat colours, one darker tone on the right edge of every area (light from the left),
no outlines, big heads, 1-pixel eyes. Each critter has FRAMES frames of idle animation (bob, blink, its prop).

    python nine.py            # writes out/nine_<key>/sheet.png (+ previews), out/_nine.png
generate.py calls build() too.
"""
import os

from PIL import Image

N = 20
FRAMES = 8
FRAME_MS = 120
SCALE = 10


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


class Sprite:
    def __init__(self, pal, shade=None):
        self.g = [["."] * N for _ in range(N)]
        self.pal = pal
        self.shade_map = shade or {}

    def half(self, rows, y0=0):
        """Left half (10 columns), mirrored to the right."""
        for i, r in enumerate(rows):
            assert len(r) <= N // 2, r
            r = r.ljust(N // 2, ".")
            for x, c in enumerate(r + r[::-1]):
                if c != ".":
                    self.g[y0 + i][x] = c

    def put(self, x0, y0, rows):
        for i, r in enumerate(rows):
            for j, c in enumerate(r):
                if c != "." and 0 <= x0 + j < N and 0 <= y0 + i < N:
                    self.g[y0 + i][x0 + j] = " " if c == "_" else c

    def px(self, x, y, c):
        if 0 <= x < N and 0 <= y < N:
            self.g[y][x] = c

    def get(self, x, y):
        return self.g[y][x] if 0 <= x < N and 0 <= y < N else "."

    def shade(self):
        """The right edge of every area gets its darker tone (and the bottom edge of the ones marked so)."""
        old = [row[:] for row in self.g]
        for y in range(N):
            for x in range(N):
                c = old[y][x]
                if c not in self.shade_map:
                    continue
                s = self.shade_map[c]
                right = old[y][x + 1] if x + 1 < N else "."
                if right not in (c, s):
                    self.g[y][x] = s

    def bob(self, until, dy=1):
        """Rows above `until` sink by dy (the feet stay): breathing / a little bounce."""
        if dy == 0:
            return
        moved = [row[:] for row in self.g[:until]]
        for y in range(until):
            self.g[y] = ["."] * N
        for y, row in enumerate(moved):
            ty = y + dy
            if 0 <= ty < N:
                for x, c in enumerate(row):
                    if c != ".":
                        self.g[ty][x] = c

    def lift(self, dy):
        """Everything moves up by dy (hover / hop)."""
        if dy:
            self.g = self.g[dy:] + [["."] * N for _ in range(dy)] if dy > 0 else \
                [["."] * N for _ in range(-dy)] + self.g[:dy]

    def image(self):
        im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        for y in range(N):
            for x in range(N):
                c = self.g[y][x]
                if c not in (".", " "):
                    im.putpixel((x, y), hexrgb(self.pal[c]) + (255,))
        return im


INK = "#1A1C2C"
BOB = [0, 0, 1, 1, 0, 0, 1, 1]          # the body sinks a pixel twice per loop


# ---------------------------------------------------------------- the critters

def goat(f):
    """The GOAT: a white goat with golden ram horns, a crown, a champion's cape and a fist in the air."""
    s = Sprite({"W": "#F4F4F4", "w": "#C2C5D0", "Y": "#FFCD75", "y": "#E09A3A", "K": INK, "P": "#F29393",
                "R": "#C0394E", "r": "#7E2840", "H": "#4A4E63", "G": "#FFF4C8"},
               {"W": "w", "Y": "y", "R": "r"})
    s.half([
        "..........",
        "..YYY.....",
        ".YY.YY....",
        ".Y...YY...",
        "..y..YYWWW",
        "......WWWW",
        "...WWWWWWW",
        "..WWWWWWWW",
        "......WWWW",
        "......WWWW",
        ".......WWW",
        ".......WWW",
        "........WW",
        ".....RRWWW",
        "....RRWWWW",
        "....RRWWWW",
        "....RRWWWW",
        "....RR.WW.",
        ".......WW.",
        ".......HH.",
    ])
    # the crown between the horns
    s.put(8, 0, ["Y..Y"[:0] + "Y.YY.Y"[:0]]) if False else None
    s.put(7, 1, ["Y.YY.Y", "YYYYYY", "YYRYYY"])
    s.shade()
    blink = f == 5
    for x in (8, 11):
        s.px(x, 8, "W" if blink else "K")
        s.px(x, 9, "K")
    s.put(9, 11, ["PP"])
    s.put(9, 12, ["Ww"])
    s.put(9, 13, ["RR"])                      # the medal on its ribbon
    s.put(9, 14, ["YY", "yy"])
    # a fist in the air: up on the beat, the arm by the side otherwise
    up = f in (2, 3, 4, 5)
    if up:
        s.put(16, 9, ["W", "W", "W", "W"])
        s.put(16, 8, ["W"])
    else:
        s.put(16, 13, ["W", "W"])
    if f in (6, 7):                           # the cape flutters
        s.px(4, 17, "."), s.px(3, 16, "R")
    s.bob(17, BOB[f])
    sparkle = [((6, 0), (13, 0)), ((5, 1), (14, 2)), ((13, 0),), ((6, 0), (14, 1))][f % 4]
    for (x, y) in sparkle:                    # the crown shines
        s.px(x, y, "G")
    if up and f in (3, 4):
        s.px(18, 7, "G"), s.px(17, 6, "G")
    return s


def larper(f):
    """The Larper: a little gecko pretending to be a dragon - felt hood with teeth, paper wings, cardboard sword."""
    s = Sprite({"R": "#C93C3C", "r": "#8E2A3A", "Y": "#FFCD75", "y": "#E0A040", "G": "#6CC24A", "g": "#3E8E3A",
                "K": INK, "W": "#F4F4F4", "B": "#C99A5B", "b": "#8E6436", "S": "#D6DEE8", "s": "#97A3B8",
                "A": "#73EFF7"},
               {"R": "r", "Y": "y", "G": "g", "S": "s", "B": "b"})
    s.half([
        "..........",
        "...Y......",
        "...YY.....",
        "....YRRRRR",
        "...RRRRRRR",
        "...RRGGGGG",
        "..RRGGGGGG",
        "..RRGGGGGG",
        "..RRGGGGGG",
        "...RRGGGGG",
        "....RYRYRR",
        ".....RRRRR",
        "....GRRYYY",
        "....GRRYYY",
        ".....RRYYY",
        ".....RRYYY",
        "......RRRR",
        "......RR..",
        "......RR..",
        "......GG..",
    ])
    # felt wings sticking up behind the shoulders, the costume's tail
    flap = 1 if f in (1, 2) else 0
    s.put(0, 9 - flap, ["R...", "RR..", "RYR.", ".RYR"])
    s.put(16, 9 - flap, ["...R", "..RR", ".RYR", "RYR."])
    s.put(1, 15, ["RR", ".RR", "..R"])
    s.shade()
    blink = f == 6
    for x in (7, 12):
        s.px(x, 7, "G" if blink else "K")
        s.px(x, 8, "K")
    s.put(8, 9, ["KKKK"] if f in (2, 3, 4) else [".KK."])     # roaring (it thinks)
    if f in (4, 5, 6, 7):                     # the right wing droops (it's only paper)
        for y in range(8, 13):
            for x in range(16, 20):
                if s.get(x, y) in "RrYy":
                    s.px(x, y, ".")
        s.put(16, 11, ["RRR.", "RYRR", "..YR"])
    raise_ = 2 if f in (2, 3, 4) else 0
    # the cardboard sword in the right hand
    s.put(16, 2 - raise_, ["S", "S", "S", "S", "S", "S", "S", "s", "s"])
    s.put(15, 11 - raise_, ["BBB"])
    s.put(16, 12 - raise_, ["b"])
    s.px(15, 13 - (1 if raise_ else 0), "G")
    if f in (5, 6, 7):                        # a nervous drop
        s.px(3, 5 + (f - 5), "A")
    s.bob(17, BOB[f])
    if raise_ and f == 3:
        s.px(16, 0, "W"), s.px(17, 0, "W"), s.px(15, 0, "W")
    return s


def hermes(f):
    """Hermes: a fox messenger with a winged golden cap, a satchel and a letter in the paw."""
    s = Sprite({"O": "#EF7D57", "o": "#B8513F", "C": "#F7EBD5", "c": "#D2BFA2", "Y": "#FFCD75", "y": "#E0A040",
                "W": "#F4F4F4", "w": "#C2C5D0", "K": INK, "B": "#8A5A33", "b": "#5E3D24", "R": "#C0394E",
                "L": "#FFFFFF"},
               {"O": "o", "C": "c", "Y": "y", "W": "w", "B": "b"})
    flap = f in (1, 2, 5, 6)
    s.half([
        "..........",
        "..........",
        "W.....YYYY" if not flap else ".W....YYYY",
        "WW...YYYYY" if not flap else "WWW..YYYYY",
        ".WWW.YYYYY" if not flap else "..WW.YYYYY",
        "..WWWYYYYY",
        ".....OOOOO",
        "....OOOOOO",
        "....OOOOOO",
        "....OCOOOO",
        ".....CCCCC",
        "......CCCC",
        ".......OOO",
        "......OOCC",
        ".....OOOCC",
        ".....OOOCC",
        "......OOOO",
        "......OO..",
        "......OO..",
        "......KK..",
    ])
    # the fluffy tail on the left
    wag = f in (2, 3, 6, 7)
    s.put(1, 11 if wag else 12, [".W", "WO", "OO", "OO.", ".OOO"])
    s.shade()
    blink = f == 4
    for x in (7, 12):
        s.px(x, 8, "O" if blink else "K")
        s.px(x, 9, "K")
    s.put(9, 11, ["KK"])
    # satchel strap and bag
    for i in range(5):
        s.px(13 - i, 12 + i, "B")
    s.put(12, 15, ["BBB", "bbb"])
    s.px(13, 15, "Y")
    # the letter held up in the right paw
    ly = 11 if f in (0, 1, 4, 5) else 10
    s.put(15, ly, ["LLLW", "LRLW", "WWWW"])
    s.px(14, ly + 3, "O")
    s.bob(17, BOB[f])
    return s


def yapper(f):
    """The Yapper: a round red parrot that never stops talking."""
    s = Sprite({"R": "#E04A3B", "r": "#9E2A35", "Y": "#F7C948", "y": "#D49A2E", "B": "#3B5DC9", "b": "#29366F",
                "C": "#F1E7D4", "c": "#A89F8E", "K": INK, "W": "#F4F4F4", "w": "#C2C5D0", "O": "#EF7D57",
                "M": "#5D1A2A", "T": "#F08A9A", "G": "#38B764"},
               {"R": "r", "Y": "y", "B": "b", "C": "c", "W": "w"})
    s.half([
        "..........",
        "..........",
        "..........",
        "..........",
        ".....RRRRR",
        "....RRRRRR",
        "...RRRRRRR",
        "...RRRRRRR",
        "...RRRRRCC",
        "..BRRRRCCC",
        "..BBRRRRcc",
        ".BBBRRRYYY",
        ".BBBRRYYYY",
        ".BBBRRYYYY",
        "..BBRRYYYY",
        "...BRRRYYY",
        ".....RRRRR",
        ".......O..",
        "......OO..",
    ])
    # crest feathers, a tail feather
    s.put(7, 0, ["..R", ".RRY", "RRR"])
    s.put(4, 16, ["G", "B"])
    s.shade()
    blink = f == 3
    for x in (6, 13):
        s.px(x - (1 if x < 10 else -1), 7, "W")
        s.px(x, 7, "R" if blink else "K")
        s.px(x, 8, "K")
    talk = f in (1, 2, 5, 6)
    if talk:
        s.put(8, 10, ["MTTM"])
        s.put(8, 11, ["cccc"])
    # the speech bubble
    word = ["!", "!", "!", "?", "?", "?", "~", "~"][f]
    glyph = {"!": ["..K..", "..K..", ".....", "..K.."], "?": [".KK..", "...K.", "..K..", "..K.."],
             "~": [".....", "K.K.K", ".....", "....."]}[word]
    bx, by = 14, 0
    s.put(bx, by, [".WWWW.", "WWWWWW", "WWWWWW", "WWWWWw", ".WWWw.", ".W...."])
    for i, row in enumerate(glyph):
        for j, c in enumerate(row):
            if c == "K" and j < 5:
                s.px(bx + j + 1 - (1 if j == 4 else 0), by + i + 1 - (1 if i == 3 else 0) + 0, "K") if False else None
    for i, row in enumerate(glyph[:3] if word != "?" else glyph[:3]):
        pass
    # simple glyphs inside the 4x3 interior
    inner = {"!": [(2, 1), (2, 2), (2, 4)], "?": [(1, 1), (2, 1), (3, 2), (2, 3), (2, 4)], "~": [(1, 2), (3, 2), (5, 2)]}[word]
    for (gx, gy) in inner:
        if gy <= 3 or word == "!":
            s.px(bx + gx, by + min(gy, 3), "K")
    s.bob(17, BOB[f])
    return s


def charmeur(f):
    """The Charmeur: a pink cat with a rose between the teeth, a heart on the chest and a wink for everyone."""
    s = Sprite({"P": "#F39CBB", "p": "#C76A92", "C": "#FFF0E8", "c": "#E3C6BD", "M": "#E5487F", "m": "#A8305E",
                "K": INK, "W": "#F4F4F4", "G": "#38B764", "g": "#22804A", "R": "#D23A55", "r": "#8E2340"},
               {"P": "p", "C": "c", "M": "m", "R": "r"})
    s.half([
        "..........",
        "..........",
        "....P.....",
        "....PP....",
        "....PMP...",
        "....PPPPPP",
        "...PPPPPPP",
        "...PPPPPPP",
        "...PPPPPPP",
        "...PPPPCCC",
        "....PPCCCC",
        ".....PPPPP",
        "......PPPP",
        ".....PPCCC",
        ".....PPCCC",
        ".....PPCCC",
        "......PPPP",
        "......PP..",
        "......PP..",
        "......pp..",
    ])
    # the tail curls up the right side, a heart at its tip
    sway = 1 if f in (2, 3, 4, 5) else 0
    s.put(15, 9 + sway, ["..M.M", "..MMM", "...M.", "...P.", "...P.", "..P..", ".P..."])
    s.shade()
    wink = f in (4, 5, 6)
    s.px(7, 8, "K"), s.px(7, 9, "K"), s.px(6, 7, "K")        # left eye with a lash
    if wink:
        s.px(11, 9, "K"), s.px(12, 8, "K"), s.px(13, 9, "K")
    else:
        s.px(12, 8, "K"), s.px(12, 9, "K"), s.px(13, 7, "K")
    s.px(5, 10, "M"), s.px(14, 10, "M")                      # blush
    s.put(9, 10, ["MM"])                                      # nose
    # the rose between the teeth: the stem across the mouth, the bloom on the side
    s.put(6, 11, ["GgGGg"])
    s.put(3, 10, [".RR", "RRR", ".r."])
    s.put(8, 13, ["M..M"]), s.put(8, 14, ["MMMM"]), s.put(9, 15, ["MM"])   # the heart
    s.bob(17, BOB[f])
    if f < 4:                                                 # a heart floating up
        s.put(2 + (f % 2), 6 - f, ["M.M", "MMM", ".M."])
    return s


def gamemaster(f):
    """The Gamemaster: a hooded wizard whose eyes glow in the shadow, a twenty-sided die on the staff."""
    s = Sprite({"P": "#5B3FA6", "p": "#3A2A75", "D": "#141523", "L": "#A7F070", "l": "#5FA84A", "Y": "#FFCD75",
                "y": "#E0A040", "B": "#8A5A33", "b": "#5E3D24", "C": "#73EFF7", "c": "#3B9DC9", "W": "#F4F4F4",
                "H": "#B9A8E6"},
               {"P": "p", "Y": "y", "B": "b"})
    s.half([
        "..........",
        "..........",
        "........PP",
        ".......PPP",
        "......PPPP",
        ".....YYYYY",
        "...PPPPPPP",
        ".....PDDDD",
        ".....PDDDD",
        ".....PDDDD",
        "....PPPDDD",
        "...PPPPPPP",
        "..PPPPPPPP",
        "..PPPPPPPP",
        ".PPPPPPPPP",
        ".PPPPPPPPP",
        "PPPPPPPPPP",
        "YYYYYYYYYY",
    ])
    s.put(12, 0, ["PP", ".P"])               # the hat's tip bends over
    s.shade()
    glow = "L" if f not in (6,) else "l"
    s.put(7, 8, [glow + glow])
    s.put(11, 8, [glow + glow])
    for i, (x, y) in enumerate(((5, 13), (12, 15), (8, 16), (14, 12))):  # stars on the robe
        if (f + i) % 4 < 2:
            s.px(x, y, "Y")
    # the staff in the right hand, the die on top
    s.put(17, 4, ["B"] * 15)
    s.put(16, 11, ["HH"])
    face = f % 4
    s.put(16, 0, [".C.", "CWc", ".c."] if face < 2 else [".c.", "cWC", ".C."])
    # a rune card circling
    rx = [2, 1, 0, 1, 2, 3, 3, 3][f]
    ry = [9, 10, 11, 12, 13, 12, 11, 10][f]
    s.put(rx, ry, ["YY", "LY"]) if f < 6 else None
    s.lift(1 if f in (2, 3, 4, 5) else 0)     # it hovers
    return s


def veteran(f):
    """The Veteran: an old tortoise with bushy brows, a long white beard, a medal and a cane."""
    s = Sprite({"G": "#7EB36A", "g": "#4F8A4A", "S": "#8A5A33", "s": "#5E3D24", "H": "#C49455", "h": "#946A3A",
                "Y": "#E3D29A", "y": "#B9A770", "W": "#F4F4F4", "w": "#C2C5D0", "M": "#FFCD75", "R": "#C0394E",
                "B": "#A0703C", "b": "#6E4A28", "K": INK},
               {"G": "g", "S": "s", "H": "h", "Y": "y", "W": "w", "B": "b"})
    s.half([
        "..........",
        "..........",
        "..........",
        ".......GGG",
        "......GGGG",
        ".....GGGGG",
        ".....GGGGG",
        ".....GGGGG",
        "...SSSGGGG",
        "..SHHSSWWW",
        ".SHHHHSWWW",
        ".SHSSHSWWW",
        ".SSHHSGYWW",
        ".SHHHSGYYW",
        "..SHSSYYYY",
        "...SSSYYYY",
        "....SSSSSS",
        "......GG..",
        "......GG..",
        "......gg..",
    ])
    s.shade()
    s.put(6, 4, ["WWW"]), s.put(11, 4, ["WWW"])              # bushy brows
    blink = f in (5, 6)
    s.px(7, 6, "G" if blink else "K"), s.px(12, 6, "G" if blink else "K")
    s.put(9, 8, ["KK"])                                       # a smile above the beard
    sway = 1 if f in (2, 3, 4, 5) else 0
    s.put(9, 12, ["WW"]), s.put(9 + sway, 13, ["W"])          # the long beard, swaying
    s.put(14, 13, ["R", "M"])                                 # the medal
    # the cane in the right hand
    s.put(17, 10, ["BB", "B.", "B.", "B.", "B.", "B.", "B.", "B.", "B.", "b."])
    s.put(15, 13, ["GG"])
    s.bob(17, 1 if f in (3, 4) else 0)
    return s


def clown(f):
    """The Clown: a jester imp with a three-pointed hat and a red nose, juggling."""
    s = Sprite({"P": "#8E47C9", "p": "#5E2F8C", "Y": "#F4C43A", "y": "#C08A20", "W": "#F4F4F4", "w": "#C7C9D1",
                "R": "#E8383D", "K": INK, "B": "#FFE08A", "O": "#F08A2E", "C": "#3DC6E8", "L": "#7ED957",
                "Q": "#F07CC0"},
               {"P": "p", "Y": "y", "W": "w"})
    jingle = f % 2
    s.half([
        "..........",
        ".........Y",
        "........YY",
        "PP.....YYY",
        ".PPP.PPYYY",
        "..PPPPPPPP",
        "...PWWWWWW",
        "...WWWWWWW",
        "...WWWWWWW",
        "....WWWWWW",
        "....WWWWWW",
        ".....WWWWW",
        "....WWWWWW",
        ".....PPPPP",
        ".....PPPPP",
        ".....PPPPP",
        "......PPPP",
        "......PP..",
        "......PP..",
        ".....PPP..",
    ])
    # the right half of the suit is yellow (harlequin)
    for y in range(13, 19):
        for x in range(10, 20):
            if s.get(x, y) == "P":
                s.px(x, y, "Y")
    s.px(9, 0, "B"), s.px(10, 0, "B")
    s.px(0, 2 + jingle, "B"), s.px(19, 2 + (1 - jingle), "B")
    s.put(5, 12, ["W.........W"]) if False else None
    s.shade()
    for x in (7, 12):
        s.px(x, 8, "K"), s.px(x, 9, "K")
        s.px(x, 10, "C")                                       # painted diamonds under the eyes
    s.put(9, 9, ["RR"]), s.put(9, 10, ["RR"])                 # the nose
    s.put(7, 11, ["R....R"]), s.put(8, 12, ["RRRR"])          # the grin
    s.put(5, 12, ["Q"]), s.put(14, 12, ["Q"])
    # hands up, juggling three balls
    left_up = f % 4 < 2
    s.put(3, 12 if left_up else 13, ["W"])
    s.put(16, 13 if left_up else 12, ["W"])
    s.put(4, 13, ["P"]), s.put(15, 13, ["Y"])
    path = [(3, 10), (5, 6), (9, 4), (14, 6), (16, 10), (12, 8), (8, 8), (5, 9)]
    for i, col in enumerate(("R", "C", "L")):
        x, y = path[(f + i * 3) % 8]
        s.put(x, y - 3, [col + col, col + col])
    s.bob(17, BOB[f])
    return s


def supercharged(f):
    """Supercharged: a yellow electric critter, cheeks crackling cyan, its tail a lightning bolt."""
    charged = f in (2, 3, 6, 7)
    s = Sprite({"Y": "#FFD23F", "y": "#D99A22", "C": "#FFF2C2", "c": "#E0C890", "K": INK, "D": "#3B3868",
                "A": "#5CE1FF", "a": "#3B9DC9", "W": "#FFFFFF", "R": "#E8383D", "T": "#FFF8D6" if charged else "#FFD23F"},
               {"Y": "y", "C": "c", "T": "y"})
    s.half([
        "..........",
        "..D.......",
        "..DD......",
        "..YYY.....",
        "...YYYYYYY",
        "..YYYYYYYY",
        "..YYYYYYYY",
        "..YYYYYYYY",
        "..YYYYYYYY",
        "..AYYYYCCC",
        "...YYYYCCC",
        "....YYYYYY",
        ".....YYCCC",
        "....YYCCCC",
        "....YYCCCC",
        ".....YYYYY",
        "......YY..",
        "......YY..",
        "......yy..",
    ])
    # the lightning-bolt tail on the left
    s.put(0, 4, ["TTT.", ".TT.", "..TT", ".TTT", "TTT.", "TT..", ".TTT", "..TTT", "...TT"])
    s.shade()
    for x in (6, 13):
        s.px(x, 7, "K"), s.px(x, 8, "K")
    s.put(5, 6, ["KK"]), s.put(13, 6, ["KK"])                 # fierce brows
    s.put(8, 10, ["RRRR"]) if charged else s.put(8, 10, ["KKKK"])
    s.put(9, 11, ["W"]) if charged else None
    s.bob(16, 1 if f in (0, 1, 4, 5) else 0)
    if charged:                                                # sparks fly
        for x, y in ((1, 9), (18, 9), (17, 4), (2, 3)) if f % 2 else ((0, 10), (19, 8), (18, 2), (3, 2)):
            s.px(x, y, "W" if (x + y) % 2 else "A")
        s.px(18, 10, "A"), s.px(1, 10, "A")
    return s


NINE = {
    "goat": goat, "larper": larper, "hermes": hermes, "yapper": yapper, "charmeur": charmeur,
    "gamemaster": gamemaster, "veteran": veteran, "clown": clown, "supercharged": supercharged,
}


def build(out):
    rows = []
    for key, fn in NINE.items():
        frames = [fn(f).image() for f in range(FRAMES)]
        d = os.path.join(out, f"nine_{key}")
        os.makedirs(d, exist_ok=True)
        sheet = Image.new("RGBA", (N * FRAMES, N), (0, 0, 0, 0))
        for i, im in enumerate(frames):
            sheet.paste(im, (i * N, 0))
        sheet.save(os.path.join(d, "sheet.png"))
        sheet.resize((N * FRAMES * SCALE, N * SCALE), Image.NEAREST).save(os.path.join(d, f"sheet_x{SCALE}.png"))
        gif = []
        for im in frames:
            bg = Image.new("RGBA", (N, N), (38, 43, 68, 255))
            bg.alpha_composite(im)
            gif.append(bg.resize((N * SCALE, N * SCALE), Image.NEAREST).convert("P"))
        gif[0].save(os.path.join(d, f"anim_x{SCALE}.gif"), save_all=True, append_images=gif[1:], duration=FRAME_MS, loop=0)
        rows.append(frames)
    # overview: every critter's frames on a dark row, plus the first frames large
    pad = 2
    ov = Image.new("RGBA", ((N + pad) * FRAMES + pad, (N + pad) * len(rows) + pad), (38, 43, 68, 255))
    for r, frames in enumerate(rows):
        for c, im in enumerate(frames):
            ov.alpha_composite(im, (pad + c * (N + pad), pad + r * (N + pad)))
    ov.resize((ov.width * 5, ov.height * 5), Image.NEAREST).save(os.path.join(out, "_nine.png"))
    hero = Image.new("RGBA", ((N + pad) * 3 + pad, (N + pad) * 3 + pad), (38, 43, 68, 255))
    for i, frames in enumerate(rows):
        hero.alpha_composite(frames[0], (pad + (i % 3) * (N + pad), pad + (i // 3) * (N + pad)))
    hero.resize((hero.width * 12, hero.height * 12), Image.NEAREST).save(os.path.join(out, "_nine_hero.png"))
    print("wrote", len(rows), "titles")


if __name__ == "__main__":
    build(os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
