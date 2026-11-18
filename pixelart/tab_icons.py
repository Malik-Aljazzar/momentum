"""Tab icons: the library extension tabs and tab groups pick their icon from (`tab_<name>`, 16x16).

White glyphs without an outline like the UI glyphs in generate.py (the clients tint them), 1 px margin, mostly
2 px strokes. Each function draws on a Canvas (rect/line/poly/circle/set, or `_art` text rows: "X" = white).
generate.py writes them with the other icons; `python tab_icons.py` writes only these (plus a preview sheet:
`python tab_icons.py <preview.png>`).
"""
import math
import os
import sys

W = "white"
N = 16


def _art(cv, rows):
    assert len(rows) == N and all(len(r) == N for r in rows), rows
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "X":
                cv.set(x, y, W)


def _thick(cv, pts, c=W):
    """A polyline drawn with a 2x2 brush."""
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            cv.line(x0 + dx, y0 + dy, x1 + dx, y1 + dy, c)


def _ring(cv, cx, cy, ro, ri):
    cv.circle(cx, cy, ro, W)
    cv.circle(cx, cy, ri, None)


def _each(fn):
    for y in range(N):
        for x in range(N):
            fn(x, y)


def home(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        "......XXXX......",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXX..XXXXX..",
        "..XXXXX..XXXXX..",
        "..XXXXX..XXXXX..",
        "..XXXXX..XXXXX..",
        "................",
    ])


def star(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        ".......XX.......",
        "......XXXX......",
        "......XXXX......",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "...XXXX..XXXX...",
        "..XXXX....XXXX..",
        "..XXX......XXX..",
        ".XX..........XX.",
        "................",
    ])


def heart(cv):
    _art(cv, [
        "................",
        "................",
        "..XXXX....XXXX..",
        ".XXXXXX..XXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "......XXXX......",
        ".......XX.......",
        "................",
        "................",
    ])


def bolt(cv):
    _art(cv, [
        "................",
        ".........XXXXX..",
        "........XXXXX...",
        ".......XXXXX....",
        "......XXXXX.....",
        ".....XXXXX......",
        "....XXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "........XXXX....",
        ".......XXXX.....",
        "......XXXX......",
        ".....XXX........",
        "....XXX.........",
        "....XX..........",
        "...XX...........",
        "................",
    ])


def flame(cv):
    _art(cv, [
        "................",
        ".......X........",
        "......XX........",
        "......XXX.......",
        ".....XXXXX..X...",
        ".....XXXXXX.XX..",
        "....XXXXXXXXXX..",
        "...XXXXXXXXXXXX.",
        "...XXXXX.XXXXXX.",
        "..XXXXX...XXXXX.",
        "..XXXX.....XXXX.",
        "..XXXX.....XXXX.",
        "...XXXX...XXXX..",
        "....XXXXXXXXXX..",
        "......XXXXXX....",
        "................",
    ])


def leaf(cv):
    # an almond turned 45 degrees, its vein cut out, a short stem
    def f(x, y):
        a, b = x + 0.5 - 8.5, y + 0.5 - 6.5
        u = (a - b) / math.sqrt(2)              # along: towards the top right
        v = (a + b) / math.sqrt(2)
        if (u / 7.3) ** 2 + (v / 4.0) ** 2 <= 1:
            cv.set(x, y, W)
    _each(f)
    for i in range(7):                          # the vein
        cv.set(4 + i, 10 - i, None)
    cv.line(3, 12, 1, 14, W)                    # stem
    cv.line(4, 12, 2, 14, W)


def tree(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        "......XXXX......",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "......XXXX......",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "..XXXXXXXXXXXX..",
        ".......XX.......",
        ".......XX.......",
        "................",
    ])


def flower(cv):
    """A tulip with two leaves."""
    _art(cv, [
        "................",
        "...X...XX...X...",
        "...XX.XXXX.XX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        ".......XX.......",
        "..X....XX....X..",
        "..XX...XX...XX..",
        "..XXX..XX..XXX..",
        "...XXX.XX.XXX...",
        ".....XXXXXX.....",
        ".......XX.......",
        "................",
    ])


def sun(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        "..XX...XX...XX..",
        "..XX........XX..",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        ".XX.XXXXXXXX.XX.",
        ".XX.XXXXXXXX.XX.",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "..XX........XX..",
        "..XX...XX...XX..",
        ".......XX.......",
        "................",
    ])


def moon(cv):
    cv.circle(7.5, 8, 6.6, W)
    cv.circle(11, 5.5, 5.4, None)


def cloud(cv):
    cv.circle(6, 8.5, 3.6, W)
    cv.circle(10, 7, 4.2, W)
    cv.circle(3.6, 10.6, 2.4, W)
    cv.circle(12.4, 10.6, 2.4, W)
    cv.rect(3, 10, 12, 12, W)


def drop(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        ".......XX.......",
        "......XXXX......",
        "......XXXX......",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "...X.XXXXXXXX...",
        "...X.XXXXXXXX...",
        "...XX.XXXXXXX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "................",
        "................",
    ])


def mountain(cv):
    """A snowy peak with a smaller one behind."""
    _art(cv, [
        "................",
        "................",
        "......XX........",
        ".....XXXX.......",
        "....XXXXXX......",
        "....X.XX.X......",
        "...XXX..XXX.....",
        "...XXXXXXXXX.X..",
        "..XXXXXXXXXXXXX.",
        "..XXXXXXXXXX.XX.",
        ".XXXXXXXXXXXX.X.",
        ".XXXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        "................",
    ])


def globe(cv):
    _art(cv, [
        "................",
        ".....XXXXXX.....",
        "...XXXXXXXXXX...",
        "..XX...XX...XX..",
        ".XX...X..X...XX.",
        ".XX..X....X..XX.",
        ".XXXXXXXXXXXXXX.",
        ".XX..X....X..XX.",
        ".XX..X....X..XX.",
        ".XXXXXXXXXXXXXX.",
        ".XX..X....X..XX.",
        ".XX...X..X...XX.",
        "..XX...XX...XX..",
        "...XXXXXXXXXX...",
        ".....XXXXXX.....",
        "................",
    ])


def pin(cv):
    _art(cv, [
        "................",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "...XXXX..XXXX...",
        "...XXX....XXX...",
        "...XXX....XXX...",
        "...XXXX..XXXX...",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "......XXXX......",
        "......XXXX......",
        ".......XX.......",
        "................",
    ])


def compass(cv):
    _ring(cv, 8, 8, 7.5, 5.8)
    cv.poly([(11.8, 4.2), (9.2, 9.2), (4.2, 11.8), (6.8, 6.8)], W)
    cv.rect(7, 7, 8, 8, None)


def rocket(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        "......XXXX......",
        ".....XXXXXX.....",
        ".....XXXXXX.....",
        ".....XX..XX.....",
        ".....XX..XX.....",
        ".....XXXXXX.....",
        ".....XXXXXX.....",
        "...X.XXXXXX.X...",
        "..XX.XXXXXX.XX..",
        "..XX.XXXXXX.XX..",
        "..XX..XXXX..XX..",
        "......X..X......",
        ".......XX.......",
        "................",
    ])


def planet(cv):
    """A ringed planet: the ring's front passes below its middle."""
    _art(cv, [
        "................",
        "................",
        "......XXXX......",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        ".X.XXXXXXXXXX.X.",
        ".XX..........XX.",
        "..XXXXXXXXXXXX..",
        "................",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        "......XXXX......",
        "................",
        "................",
        "................",
    ])


def trophy(cv):
    _art(cv, [
        "................",
        "...XXXXXXXXXX...",
        ".XXXXXXXXXXXXXX.",
        ".XX.XXXXXXXX.XX.",
        ".XX.XXXXXXXX.XX.",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "......XXXX......",
        "......XXXX......",
        "................",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        "................",
    ])


def medal(cv):
    _art(cv, [
        "................",
        ".XXXXX....XXXXX.",
        "..XXXXX..XXXXX..",
        "...XXXX..XXXX...",
        "....XXX..XXX....",
    ] + ["................"] * 11)
    cv.circle(8, 10.5, 4.6, W)
    cv.circle(8, 10.5, 3.2, None)
    cv.circle(8, 10.5, 2.3, W)


def crown(cv):
    _art(cv, [
        "................",
        "................",
        ".X.....XX.....X.",
        ".XX....XX....XX.",
        ".XXX..XXXX..XXX.",
        ".XXXX.XXXX.XXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXX.XX..XX.XXX.",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "................",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "................",
        "................",
    ])


def gem(cv):
    _art(cv, [
        "................",
        "................",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        "................",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "......XXXX......",
        ".......XX.......",
        "................",
        "................",
    ])


def key(cv):
    _art(cv, [
        "................",
        "................",
        "................",
        "................",
        "..XXXXX.........",
        ".XXXXXXX........",
        ".XX...XXXXXXXXX.",
        ".XX...XXXXXXXXX.",
        ".XX...XX..XX.XX.",
        ".XXXXXXX..XX.XX.",
        "..XXXXX.........",
        "................",
        "................",
        "................",
        "................",
        "................",
    ])


def lock(cv):
    _art(cv, [
        "................",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "....XX....XX....",
        "....XX....XX....",
        "....XX....XX....",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXX..XXXXX..",
        "..XXXXX..XXXXX..",
        "..XXXXXX.XXXXX..",
        "..XXXXXX.XXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "................",
        "................",
    ])


def bell(cv):
    _art(cv, [
        "................",
        ".......XX.......",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        "......XXXX......",
        ".......XX.......",
        "................",
    ])


def mail(cv):
    _art(cv, [
        "................",
        "................",
        "................",
        ".XXXXXXXXXXXXXX.",
        ".X.XXXXXXXXXX.X.",
        ".XX.XXXXXXXX.XX.",
        ".XXX.XXXXXX.XXX.",
        ".XXXX.XXXX.XXXX.",
        ".XXXXX....XXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        "................",
        "................",
    ])


def chat(cv):
    cv.rect(1, 1, 10, 7, W)                     # the bubble behind
    cv.rect(2, 8, 3, 8, W)
    cv.set(2, 9, W)
    cv.rect(4, 4, 15, 13, None)                 # a gap around the front one
    cv.rect(5, 5, 14, 11, W)
    cv.rect(12, 12, 13, 12, W)
    cv.set(13, 13, W)
    for x in (7, 9, 11):
        cv.set(x, 8, None)


def people(cv):
    """Three: one in front, two behind."""
    _art(cv, [
        "................",
        "......XXXX......",
        ".....XXXXXX.....",
        ".XXX.XXXXXX.XXX.",
        ".XXX.XXXXXX.XXX.",
        ".XXX..XXXX..XXX.",
        "................",
        ".....XXXXXX.....",
        ".XX.XXXXXXXX.XX.",
        ".XXX.XXXXXX.XXX.",
        ".XXX.XXXXXX.XXX.",
        ".XXX.XXXXXX.XXX.",
        ".XXX.XXXXXX.XXX.",
        "....XXXXXXXX....",
        "................",
        "................",
    ])


def person(cv):
    cv.circle(8, 5, 3.6, W)
    cv.circle(8, 16, 6.8, W, max_y=14)
    cv.rect(0, 9, 15, 9, None)


def gamepad(cv):
    _art(cv, [
        "................",
        "................",
        "................",
        "................",
        "..XXXXXXXXXXXX..",
        ".XXXX.XXXXXX.XX.",
        ".XX...XXXXX.X.X.",
        ".XXXX.XXXXXX.XX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXX......XXXX.",
        ".XXX........XXX.",
        "..X..........X..",
        "................",
        "................",
        "................",
    ])


def dice(cv):
    _art(cv, [
        "................",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        ".XX..XXXXXX..XX.",
        ".XX..XXXXXX..XX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXX..XXXXXX.",
        ".XXXXXX..XXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XX..XXXXXX..XX.",
        ".XX..XXXXXX..XX.",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "................",
    ])


def puzzle(cv):
    _art(cv, [
        "................",
        "....XXXX........",
        "....XXXX........",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXXXXX.",
        "...XXXXXXXXXXXX.",
        "...XXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXX....",
        ".XXXXXXXXXXX....",
        "................",
    ])


def palette(cv):
    def f(x, y):
        a, b = x + 0.5 - 8, y + 0.5 - 8
        if (a / 7.1) ** 2 + (b / 6.4) ** 2 <= 1:
            cv.set(x, y, W)
    _each(f)
    cv.circle(12.2, 11.6, 2.6, None)            # the notch for the thumb
    for x, y in ((4, 4), (7, 3), (10, 4), (3, 8)):
        cv.rect(x, y, x + 1, y + 1, None)       # paint


def music(cv):
    _art(cv, [
        "................",
        ".......XXXXXXXX.",
        ".....XXXXXXXXXX.",
        ".....XXXXXX..XX.",
        ".....XX......XX.",
        ".....XX......XX.",
        ".....XX......XX.",
        ".....XX......XX.",
        ".....XX......XX.",
        ".....XX...XXXXX.",
        ".....XX..XXXXXX.",
        "..XXXXX..XXXXXX.",
        ".XXXXXX...XXXX..",
        ".XXXXXX.........",
        "..XXXX..........",
        "................",
    ])


def camera(cv):
    cv.rect(5, 2, 10, 3, W)
    cv.rect(1, 4, 14, 13, W)
    cv.circle(8, 8.5, 4.1, None)
    cv.circle(8, 8.5, 2.7, W)
    cv.set(12, 6, None)


def film(cv):
    """A film strip: holes along the edges, two frames."""
    _art(cv, [
        "................",
        "................",
        ".XXXXXXXXXXXXXX.",
        ".XX.XX.XX.XX.XX.",
        ".XXXXXXXXXXXXXX.",
        ".XX....XX....XX.",
        ".XX....XX....XX.",
        ".XX....XX....XX.",
        ".XX....XX....XX.",
        ".XX....XX....XX.",
        ".XX....XX....XX.",
        ".XXXXXXXXXXXXXX.",
        ".XX.XX.XX.XX.XX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        "................",
    ])


def book(cv):
    cv.poly([(1, 2.5), (4.5, 2), (7.5, 3.5), (7.5, 14), (4.5, 12.5), (1, 13)], W)
    cv.poly([(15, 2.5), (11.5, 2), (8.5, 3.5), (8.5, 14), (11.5, 12.5), (15, 13)], W)
    for y in (5, 8):
        cv.rect(2, y, 5, y, None)
        cv.rect(10, y, 13, y, None)


def pen(cv):
    def f(x, y):
        along = x - y                            # -15 (bottom left) .. 15 (top right)
        across = x + y - 15
        if -6 <= along <= 12 and abs(across) <= 2 and along != 8:
            cv.set(x, y, W)
        elif -12 <= along < -6 and abs(across) <= (along + 12) // 2:
            cv.set(x, y, W)
    _each(f)


def note(cv):
    _art(cv, [
        "................",
        "..XXXXXXXX......",
        "..XXXXXXXX.X....",
        "..XXXXXXXX.XX...",
        "..XXXXXXXX.XXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XX........XX..",
        "..XXXXXXXXXXXX..",
        "..XX........XX..",
        "..XXXXXXXXXXXX..",
        "..XX......XXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "................",
    ])


def list_(cv):
    _art(cv, [
        "................",
        "................",
        "................",
        ".XX..XXXXXXXXXX.",
        ".XX..XXXXXXXXXX.",
        "................",
        "................",
        ".XX..XXXXXXXXXX.",
        ".XX..XXXXXXXXXX.",
        "................",
        "................",
        ".XX..XXXXXXXXXX.",
        ".XX..XXXXXXXXXX.",
        "................",
        "................",
        "................",
    ])


list_.__name__ = "list"


def check(cv):
    _art(cv, [
        "................",
        "................",
        "................",
        ".............XX.",
        "............XXX.",
        "...........XXX..",
        "..........XXX...",
        ".XX......XXX....",
        ".XXX....XXX.....",
        "..XXX..XXX......",
        "...XXXXXX.......",
        "....XXXX........",
        ".....XX.........",
        "................",
        "................",
        "................",
    ])


def calendar(cv):
    _art(cv, [
        "................",
        "....XX....XX....",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XX..........XX.",
        ".XX.XX.XX.XX.XX.",
        ".XX.XX.XX.XX.XX.",
        ".XX..........XX.",
        ".XX.XX.XX.XX.XX.",
        ".XX.XX.XX.XX.XX.",
        ".XX..........XX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        "................",
    ])


def clock(cv):
    cv.circle(8, 8, 7.3, W)
    cv.rect(7, 3, 8, 8, None)
    cv.rect(8, 7, 11, 8, None)


def hourglass(cv):
    _art(cv, [
        "................",
        ".XXXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "..XX........XX..",
        "...XX......XX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        "......XXXX......",
        "......XXXX......",
        ".....XX..XX.....",
        "....XX....XX....",
        "...XX..XX..XX...",
        "..XX.XXXXXX.XX..",
        "..XXXXXXXXXXXX..",
        ".XXXXXXXXXXXXXX.",
        "................",
    ])


def chart(cv):
    _art(cv, [
        "................",
        "................",
        "...........XXX..",
        "...........XXX..",
        "...........XXX..",
        "......XXX..XXX..",
        "......XXX..XXX..",
        "......XXX..XXX..",
        "......XXX..XXX..",
        ".XXX..XXX..XXX..",
        ".XXX..XXX..XXX..",
        ".XXX..XXX..XXX..",
        ".XXX..XXX..XXX..",
        "................",
        ".XXXXXXXXXXXXXX.",
        "................",
    ])


def target(cv):
    _ring(cv, 8, 8, 7.4, 5.6)
    _ring(cv, 8, 8, 4.0, 2.4)
    cv.rect(7, 7, 8, 8, W)


def flag(cv):
    _art(cv, [
        "................",
        "..XX............",
        "..XXXXXXXXXXXXX.",
        "..XXXXXXXXXXXXX.",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXX...",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXXX.",
        "..XXXXXXXXXXXXX.",
        "..XX............",
        "..XX............",
        "..XX............",
        "..XX............",
        "..XX............",
        "..XX............",
        "................",
    ])


def bookmark(cv):
    _art(cv, [
        "................",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "...XXXX..XXXX...",
        "...XXX....XXX...",
        "...XX......XX...",
        "................",
    ])


def tag(cv):
    _art(cv, [
        "................",
        ".XXXXXX.........",
        ".XXXXXXX........",
        ".XX..XXXX.......",
        ".XX..XXXXX......",
        ".XXXXXXXXXX.....",
        ".XXXXXXXXXXX....",
        "..XXXXXXXXXXX...",
        "...XXXXXXXXXXX..",
        "....XXXXXXXXXXX.",
        ".....XXXXXXXXX..",
        "......XXXXXXX...",
        ".......XXXXX....",
        "........XXX.....",
        ".........X......",
        "................",
    ])


def folder(cv):
    _art(cv, [
        "................",
        "................",
        ".XXXXX..........",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        "................",
        "................",
    ])


def box(cv):
    """A cube (a parcel)."""
    _art(cv, [
        "................",
        ".......XX.......",
        ".....XXXXXX.....",
        "...XXXXXXXXXX...",
        ".XXXXXXXXXXXXXX.",
        ".X.XXXXXXXXXX.X.",
        ".XX..XXXXXX..XX.",
        ".XXXX..XX..XXXX.",
        ".XXXXXX..XXXXXX.",
        ".XXXXXX..XXXXXX.",
        ".XXXXXX..XXXXXX.",
        ".XXXXXX..XXXXXX.",
        "..XXXXX..XXXXX..",
        "....XXX..XXX....",
        "......X..X......",
        "................",
    ])


def bag(cv):
    _art(cv, [
        "................",
        ".....XXXXXX.....",
        "....XXX..XXX....",
        "....XX....XX....",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "................",
        "................",
    ])


def coin(cv):
    cv.circle(8, 8, 7.2, W)
    cv.circle(8, 8, 5.9, None)
    cv.circle(8, 8, 5.0, W)
    cv.rect(7, 5, 8, 10, None)


def cup(cv):
    _art(cv, [
        "................",
        "....X..X..X.....",
        "...X..X..X......",
        "....X..X..X.....",
        "................",
        "..XXXXXXXXXX....",
        "..XXXXXXXXXXXXX.",
        "..XXXXXXXXXX..X.",
        "..XXXXXXXXXX..X.",
        "..XXXXXXXXXXXXX.",
        "..XXXXXXXXXX....",
        "...XXXXXXXX.....",
        "....XXXXXX......",
        "................",
        ".XXXXXXXXXXXX...",
        "................",
    ])


def food(cv):
    _art(cv, [
        "................",
        "..X.X.X......XX.",
        "..X.X.X.....XXX.",
        "..X.X.X.....XXX.",
        "..X.X.X.....XXX.",
        "..XXXXX.....XXX.",
        "..XXXXX.....XXX.",
        "...XXX......XXX.",
        "...XXX.......XX.",
        "...XXX.......XX.",
        "...XXX.......XX.",
        "...XXX.......XX.",
        "...XXX.......XX.",
        "...XXX.......XX.",
        "...XXX.......XX.",
        "................",
    ])


def apple(cv):
    _art(cv, [
        "................",
        ".........XX.....",
        "........XX......",
        ".......XX.......",
        "...XXXX..XXXX...",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "...XXXXXXXXXX...",
        "....XXX..XXX....",
        "................",
        "................",
    ])


def dumbbell(cv):
    _art(cv, [
        "................",
        "................",
        "................",
        "...XX......XX...",
        "...XX......XX...",
        ".XXXX......XXXX.",
        ".XXXX......XXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXXXXXXXXXXXX.",
        ".XXXX......XXXX.",
        ".XXXX......XXXX.",
        "...XX......XX...",
        "...XX......XX...",
        "................",
        "................",
        "................",
    ])


def run(cv):
    """A runner, leaning forward."""
    _art(cv, [
        "................",
        "..........XXX...",
        "..........XXX...",
        "..........XXX...",
        "......XXXX......",
        ".....XXXXXXX.XX.",
        "....XX.XXX.XXX..",
        "....X..XXX......",
        "......XXX.......",
        "......XXXX......",
        ".....XXXXXX.....",
        "....XX...XX.....",
        "..XXX.....XX....",
        "..X.......XX....",
        "..........XXX...",
        "................",
    ])


def brain(cv):
    """Seen from above: two halves with their folds."""
    _art(cv, [
        "................",
        "................",
        "....XXX..XXX....",
        "..XXXXX..XXXXX..",
        ".XX..XX..XX..XX.",
        ".XXXX.X..X.XXXX.",
        ".XXXXXX..XXXXXX.",
        ".X..XXX..XXX..X.",
        ".XXX..X..X..XXX.",
        ".XXXXXX..XXXXXX.",
        "..XX.XX..XX.XX..",
        "...XXXX..XXXX...",
        "....XXX..XXX....",
        "................",
        "................",
        "................",
    ])


def bulb(cv):
    _art(cv, [
        "................",
        ".....XXXXXX.....",
        "....XXXXXXXX....",
        "...XX.XXXXXXX...",
        "...X.XXXXXXXX...",
        "...X.XXXXXXXX...",
        "...XXXXXXXXXX...",
        "....XXXXXXXX....",
        ".....XXXXXX.....",
        ".....XXXXXX.....",
        "................",
        ".....XXXXXX.....",
        "................",
        "......XXXX......",
        "................",
        "................",
    ])


def code(cv):
    _thick(cv, [(4, 4), (1, 7), (4, 10)])
    _thick(cv, [(10, 4), (13, 7), (10, 10)])
    _thick(cv, [(9, 2), (6, 12)])


def gear(cv):
    _art(cv, [
        "................",
        "......XXXX......",
        "..XX..XXXX..XX..",
        "..XXXXXXXXXXXX..",
        "...XXXXXXXXXX...",
        "...XXXX..XXXX...",
        ".XXXXX....XXXXX.",
        ".XXXX......XXXX.",
        ".XXXX......XXXX.",
        ".XXXXX....XXXXX.",
        "...XXXX..XXXX...",
        "...XXXXXXXXXX...",
        "..XXXXXXXXXXXX..",
        "..XX..XXXX..XX..",
        "......XXXX......",
        "................",
    ])


def grid(cv):
    for ox in (1, 9):
        for oy in (1, 9):
            cv.rect(ox, oy, ox + 5, oy + 5, W)
            for dx, dy in ((0, 0), (5, 0), (0, 5), (5, 5)):
                cv.set(ox + dx, oy + dy, None)


def sparkle(cv):
    _art(cv, [
        "................",
        "................",
        "......X.........",
        "......X......X..",
        ".....XXX....XXX.",
        ".....XXX.....X..",
        "....XXXXX.......",
        "..XXXXXXXXX.....",
        ".XXXXXXXXXXX....",
        "..XXXXXXXXX.....",
        "....XXXXX.......",
        ".....XXX........",
        ".....XXX........",
        "......X.........",
        "......X.........",
        "................",
    ])


def smile(cv):
    cv.circle(8, 8, 7.2, W)
    cv.rect(5, 5, 6, 6, None)
    cv.rect(9, 5, 10, 6, None)
    cv.rect(4, 9, 4, 9, None)
    cv.rect(11, 9, 11, 9, None)
    cv.rect(5, 10, 10, 11, None)
    cv.rect(4, 10, 4, 10, None)
    cv.rect(11, 10, 11, 10, None)


def paw(cv):
    cv.circle(8, 11, 3.8, W)                    # the pad
    cv.rect(5, 12, 10, 13, W)
    cv.circle(3, 7, 1.8, W)                     # toes
    cv.circle(6, 3.8, 1.9, W)
    cv.circle(10, 3.8, 1.9, W)
    cv.circle(13, 7, 1.8, W)


def shield(cv):
    cv.poly([(1.5, 1.5), (14.5, 1.5), (14.5, 8), (8, 15), (1.5, 8)], W)
    cv.poly([(4, 4), (7.5, 4), (7.5, 11.5), (4, 7.8)], None)


def anchor(cv):
    _art(cv, [
        "................",
        ".....XXXXXX.....",
        ".....XX..XX.....",
        ".....XXXXXX.....",
        ".......XX.......",
        "....XXXXXXXX....",
        ".......XX.......",
        ".......XX.......",
        ".......XX.......",
        ".X.....XX.....X.",
        ".XX....XX....XX.",
        "..XX...XX...XX..",
        "...XXXXXXXXXX...",
        ".....XXXXXX.....",
        "................",
        "................",
    ])


DRAW = [home, star, heart, bolt, flame, leaf, tree, flower, sun, moon, cloud, drop, mountain, globe, pin, compass,
        rocket, planet, trophy, medal, crown, gem, key, lock, bell, mail, chat, people, person, gamepad, dice, puzzle,
        palette, music, camera, film, book, pen, note, list_, check, calendar, clock, hourglass, chart, target, flag,
        bookmark, tag, folder, box, bag, coin, cup, food, apple, dumbbell, run, brain, bulb, code, gear, grid, sparkle,
        smile, paw, shield, anchor]
NAMES = [fn.__name__ for fn in DRAW]


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from generate import Canvas, write_png, compose      # noqa: E402

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "icons")
    os.makedirs(out, exist_ok=True)
    cvs = []
    for fn in DRAW:
        cv = Canvas(N, N)
        fn(cv)
        write_png(os.path.join(out, f"tab_{fn.__name__}.png"), cv.px)
        cvs.append(cv)
    print("wrote", len(cvs), "tab icons")
    if len(sys.argv) > 1:                       # a preview sheet: navy, 8 to a row, scaled up
        cols = 8
        grid_ = [cvs[i:i + cols] for i in range(0, len(cvs), cols)]
        while len(grid_[-1]) < cols:
            grid_[-1].append(Canvas(N, N))
        write_png(sys.argv[1], compose(grid_, gap=4, bg="navy"), 6)
