"""Pixel-art vehicle sprites, animated.

All vehicles share the same canvas (64x32), palette (Sweetie 16),
side view facing right, and an automatic 1px dark outline.
Every vehicle has FRAMES animation frames (looping).

Run: python generate.py  ->  writes to ./out/<name>/
    frame_0..4.png   single frames, native size
    sheet.png        all frames side by side (320x32), sheet_x8.png scaled
    anim_x8.gif      animated preview, 8x
and ./out/_uebersicht.gif / _uebersicht.png for all vehicles at once.
"""
import math
import os
import shutil
import struct
import zlib

W, H = 64, 32
SCALE = 8
FRAMES = 5
DELAY = 10  # GIF frame delay in 1/100 s

# Sweetie 16 palette
PAL = {
    "black": (0x1A, 0x1C, 0x2C), "purple": (0x5D, 0x27, 0x5D),
    "red": (0xB1, 0x3E, 0x53), "orange": (0xEF, 0x7D, 0x57),
    "yellow": (0xFF, 0xCD, 0x75), "lime": (0xA7, 0xF0, 0x70),
    "green": (0x38, 0xB7, 0x64), "teal": (0x25, 0x71, 0x79),
    "navy": (0x29, 0x36, 0x6F), "blue": (0x3B, 0x5D, 0xC9),
    "sky": (0x41, 0xA6, 0xF6), "cyan": (0x73, 0xEF, 0xF7),
    "white": (0xF4, 0xF4, 0xF4), "silver": (0x94, 0xB0, 0xC2),
    "gray": (0x56, 0x6C, 0x86), "dark": (0x33, 0x3C, 0x57),
}
OUTLINE = "black"

# per-frame offsets
BOUNCE = [0, -1, -1, 0, 0]   # ground vehicles: body suspension
BOB = [0, -1, -1, 1, 1]      # aircraft: gentle up/down wave


class Canvas:
    """Pixel grid. All drawing calls are shifted by (ox, oy)."""

    def __init__(self, w=W, h=H):
        self.w, self.h = w, h
        self.px = [[None] * w for _ in range(h)]
        self.ox = self.oy = 0

    def offset(self, ox=0, oy=0):
        self.ox, self.oy = ox, oy

    def set(self, x, y, c):
        x, y = x + self.ox, y + self.oy
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def paint(self, x0, y0, x1, y1, c):
        """Like rect, but only recolors pixels that are already filled."""
        for y in range(y0 + self.oy, y1 + self.oy + 1):
            for x in range(x0 + self.ox, x1 + self.ox + 1):
                if 0 <= x < self.w and 0 <= y < self.h and self.px[y][x] is not None:
                    self.px[y][x] = c

    def line(self, x0, y0, x1, y1, c):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.set(x0, y0, c)
            if x0 == x1 and y0 == y1:
                return
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def poly(self, pts, c):
        """Fill polygon; pixel (x,y) is filled if its center lies inside."""
        pts = [(x + self.ox, y + self.oy) for x, y in pts]
        n = len(pts)
        for y in range(self.h):
            for x in range(self.w):
                px, py = x + 0.5, y + 0.5
                inside = False
                for i in range(n):
                    x1, y1 = pts[i]
                    x2, y2 = pts[(i + 1) % n]
                    if (y1 > py) != (y2 > py):
                        if px < x1 + (py - y1) * (x2 - x1) / (y2 - y1):
                            inside = not inside
                if inside:
                    self.px[y][x] = c

    def circle(self, cx, cy, r, c, max_y=None):
        cx, cy = cx + self.ox, cy + self.oy
        if max_y is not None:
            max_y += self.oy
        for y in range(self.h):
            if max_y is not None and y > max_y:
                continue
            for x in range(self.w):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                    self.px[y][x] = c

    def outline(self):
        add = []
        for y in range(self.h):
            for x in range(self.w):
                if self.px[y][x] is not None:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.w and 0 <= ny < self.h and self.px[ny][nx] is not None:
                        add.append((x, y))
                        break
        for x, y in add:
            self.px[y][x] = OUTLINE


# ---------------------------------------------------------------- file output

def write_png(path, px, scale=1):
    h, w = len(px), len(px[0])
    rows = []
    for y in range(h * scale):
        row = bytearray([0])
        for x in range(w * scale):
            c = px[y // scale][x // scale]
            row += bytes(PAL[c] + (255,)) if c else b"\0\0\0\0"
        rows.append(bytes(row))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w * scale, h * scale, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", zlib.compress(b"".join(rows), 9)))
        f.write(chunk(b"IEND", b""))


def _lzw_literal(data):
    """GIF LZW stream using only literal codes (min code size 5).
    A clear code every 24 pixels keeps the code width fixed at 6 bits."""
    clear, eoi = 32, 33
    out, buf, nbits = bytearray(), 0, 0

    def emit(code):
        nonlocal buf, nbits
        buf |= code << nbits
        nbits += 6
        while nbits >= 8:
            out.append(buf & 0xFF)
            buf >>= 8
            nbits -= 8

    for i, v in enumerate(data):
        if i % 24 == 0:
            emit(clear)
        emit(v)
    emit(eoi)
    if nbits:
        out.append(buf & 0xFF)
    return bytes(out)


def write_gif(path, frames, scale=1, delay=DELAY):
    names = list(PAL)
    index = {n: i for i, n in enumerate(names)}
    trans = len(names)
    h, w = len(frames[0]), len(frames[0][0])
    gw, gh = w * scale, h * scale
    out = bytearray(b"GIF89a")
    out += struct.pack("<HHBBB", gw, gh, 0xF4, trans, 0)
    for n in names:
        out += bytes(PAL[n])
    out += bytes(3 * (32 - len(names)))
    out += b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00"   # loop forever
    for px in frames:
        out += b"\x21\xF9\x04" + struct.pack("<BHBB", 0x09, delay, trans, 0)
        out += b"\x2C" + struct.pack("<HHHHB", 0, 0, gw, gh, 0)
        data = []
        for y in range(gh):
            row = px[y // scale]
            for x in range(gw):
                c = row[x // scale]
                data.append(index[c] if c else trans)
        lz = _lzw_literal(data)
        out += b"\x05"
        for i in range(0, len(lz), 255):
            block = lz[i:i + 255]
            out += bytes([len(block)]) + block
        out += b"\x00"
    out += b"\x3B"
    with open(path, "wb") as f:
        f.write(out)


def compose(grid, gap=4, bg=None):
    """Place a 2D list of canvases (rows of columns) on one pixel grid."""
    rows, cols = len(grid), max(len(r) for r in grid)
    sw, sh = cols * (W + gap) + gap, rows * (H + gap) + gap
    px = [[bg] * sw for _ in range(sh)]
    for r, row in enumerate(grid):
        for c, cv in enumerate(row):
            ox, oy = gap + c * (W + gap), gap + r * (H + gap)
            for y in range(H):
                for x in range(W):
                    if cv.px[y][x]:
                        px[oy + y][ox + x] = cv.px[y][x]
    return px


# ---------------------------------------------------------------- shared parts

def spoke_end(cx, cy, rr, ang):
    """Endpoint at radius rr / angle ang around a circle centered at (cx, cy)."""
    return (round(cx - 0.5 + rr * math.cos(ang)),
            round(cy - 0.5 + rr * math.sin(ang)))


def wheel(cv, cx, cy, f, r=5):
    """Tire + rim; a spoke through the hub turns 36 deg per frame (clockwise)."""
    cv.circle(cx, cy, r, "dark")
    cv.circle(cx, cy, r - 1.6, "silver")
    a = math.radians(36 * f)
    rr = r - 2.2
    cv.line(*spoke_end(cx, cy, rr, a), *spoke_end(cx, cy, rr, a + math.pi), "gray")
    cv.circle(cx, cy, 1.2, "gray")


def glare(cv, pts):
    for x, y in pts:
        cv.set(x, y, "cyan")


def flame(cv, x_end, y0, y1, length):
    """Exhaust flame pointing left, ending at x_end. Drawn after outline."""
    for y in range(y0, y1 + 1):
        edge = y in (y0, y1) and y1 - y0 >= 2
        n = length - (1 if edge else 0)
        for i in range(n):
            x = x_end - i
            if edge or i >= n - 1:
                c = "red" if i >= n - 1 else "orange"
            else:
                c = "white" if i == 0 else ("yellow" if i < n * 0.6 else "orange")
            cv.set(x, y, c)


def puff(cv, x, y, f, period=FRAMES):
    """Small exhaust cloud drifting left/up; drawn after outline."""
    for phase in (0, 2.5):
        t = ((f + phase) % period) / period
        px, py = round(x - 6 * t), round(y - 3 * t)
        c = "white" if t < 0.5 else "silver"
        cv.set(px, py, c)
        if t > 0.25:
            cv.set(px - 1, py, c)
        if t > 0.5:
            cv.set(px, py - 1, c)
            cv.set(px - 1, py - 1, c)


# ---------------------------------------------------------------- vehicles

def fahrrad(f):
    cv = Canvas()
    for cx in (17, 46):
        cv.circle(cx, 22, 8, "dark")
        cv.circle(cx, 22, 6.2, None)
        a = math.radians(18 * f)
        for k in range(2):
            b = a + k * math.pi / 2
            cv.line(*spoke_end(cx, 22, 5.6, b), *spoke_end(cx, 22, 5.6, b + math.pi),
                    "gray")
        cv.circle(cx, 22, 1.3, "silver")
    frame = "blue"
    rh, bb, seat, head, fh = (17, 22), (30, 23), (27, 13), (42, 12), (46, 22)
    # crank behind the frame (opposite pedal)
    a = math.radians(72 * f)
    back = (round(30 - 3 * math.cos(a)), round(23 - 3 * math.sin(a)))
    cv.line(*bb, *back, "dark")
    cv.rect(back[0] - 1, back[1], back[0] + 1, back[1], "dark")
    cv.line(*rh, *bb, frame)
    cv.line(*rh, *seat, frame)
    cv.line(*seat, *bb, frame)
    cv.line(*seat, *head, frame)
    cv.line(43, 14, *bb, frame)
    cv.line(*head, *fh, "silver")
    cv.line(42, 12, 41, 8, "silver")
    cv.rect(39, 8, 41, 8, "dark")            # handlebar grip
    cv.line(27, 12, 26, 10, "silver")        # seat post
    cv.rect(23, 9, 28, 9, "dark")            # saddle
    cv.circle(30.5, 23.5, 1.8, "silver")     # chainring
    front = (round(30 + 3 * math.cos(a)), round(23 + 3 * math.sin(a)))
    cv.line(*bb, *front, "gray")             # crank
    cv.rect(front[0] - 1, front[1], front[0] + 1, front[1], "dark")
    cv.outline()
    return cv


def motorroller(f):
    cv = Canvas()
    body, light, shade = "blue", "sky", "navy"
    cv.offset(0, BOUNCE[f])
    cv.poly([(7, 23), (9, 17), (14, 13), (29, 13), (31, 18), (31, 24),
             (7, 24)], body)                                     # rear cowl
    cv.rect(28, 21, 41, 23, body)                                # floorboard
    cv.poly([(39, 24), (41, 10), (45, 10), (45, 23)], body)      # leg shield
    cv.poly([(43, 19), (51, 19), (54, 22), (43, 22)], body)      # fender
    cv.rect(13, 14, 28, 14, light)
    cv.rect(42, 10, 44, 10, light)
    cv.rect(8, 22, 41, 23, shade)
    cv.line(44, 9, 45, 5, "silver")                              # steering
    cv.rect(41, 5, 47, 5, "dark")                                # handlebar
    cv.rect(45, 7, 47, 8, "yellow")                              # headlight
    cv.rect(13, 11, 28, 12, "dark")                              # seat
    cv.rect(7, 17, 8, 18, "red")                                 # taillight
    cv.rect(8, 24, 12, 24, "gray")                               # exhaust pipe
    cv.offset()
    cv.line(47, 21, 48, 25, "gray")                              # fork
    for cx in (17, 48):
        wheel(cv, cx, 25.5, f, r=4.5)
    cv.outline()
    puff(cv, 6, 24, f)
    return cv


def kleinwagen(f):
    cv = Canvas()
    body, light, shade = "green", "lime", "teal"
    cv.offset(0, BOUNCE[f])
    cv.poly([(16, 15), (22, 8), (39, 8), (46, 15)], body)
    cv.poly([(11, 18), (13, 15), (48, 15), (52, 18), (53, 25), (11, 25)], body)
    cv.poly([(19, 14), (23, 9), (29, 9), (29, 14)], "sky")
    cv.poly([(31, 9), (38, 9), (43, 14), (31, 14)], "sky")
    glare(cv, [(25, 12), (26, 11), (27, 10), (37, 13), (38, 12), (39, 13)])
    cv.rect(23, 8, 38, 8, light)
    cv.rect(13, 15, 48, 15, light)
    cv.rect(12, 22, 52, 24, shade)
    cv.rect(30, 16, 30, 22, shade)
    cv.rect(26, 17, 27, 17, "silver")
    cv.rect(50, 17, 52, 18, "yellow")
    cv.rect(11, 16, 12, 18, "red")
    cv.rect(10, 22, 13, 23, "silver")
    cv.rect(50, 22, 54, 23, "silver")
    for cx in (20, 45):
        cv.circle(cx, 25, 6.6, "black", max_y=24)
    cv.offset()
    for cx in (20, 45):
        wheel(cv, cx, 25, f)
    cv.outline()
    return cv


def pkw(f):
    cv = Canvas()
    body, light, shade = "red", "orange", "purple"
    cv.offset(0, BOUNCE[f])
    cv.poly([(14, 16), (21, 9), (36, 9), (45, 16)], body)
    cv.poly([(5, 17), (8, 15), (44, 15), (55, 16), (59, 19),
             (59, 25), (5, 25)], body)
    cv.poly([(17, 15), (22, 10), (28, 10), (28, 15)], "sky")
    cv.poly([(30, 10), (35, 10), (41, 15), (30, 15)], "sky")
    glare(cv, [(24, 13), (25, 12), (26, 11), (34, 14), (35, 13),
               (36, 12), (36, 14), (37, 13)])
    cv.rect(22, 9, 35, 9, light)
    cv.rect(9, 16, 54, 16, light)
    cv.rect(6, 23, 58, 24, shade)
    for x in (16, 29, 43):
        cv.rect(x, 17, x, 23, shade)
    cv.rect(25, 18, 26, 18, "silver")
    cv.rect(38, 18, 39, 18, "silver")
    cv.rect(56, 18, 58, 19, "yellow")
    cv.rect(5, 17, 6, 19, light)
    cv.rect(55, 23, 59, 24, "silver")
    cv.rect(4, 23, 8, 24, "silver")
    for cx in (15, 48):
        cv.circle(cx, 25, 6.6, "black", max_y=24)
    cv.offset()
    for cx in (15, 48):
        wheel(cv, cx, 25, f)
    cv.outline()
    return cv


def suv(f):
    cv = Canvas()
    body, light, shade = "gray", "silver", "dark"
    cv.offset(0, BOUNCE[f])
    cv.poly([(5, 13), (7, 6), (39, 6), (47, 13)], body)
    cv.poly([(4, 14), (6, 13), (44, 13), (55, 14), (59, 17), (59, 25),
             (4, 25)], body)
    cv.poly([(9, 12), (10, 8), (18, 8), (18, 12)], "sky")
    cv.poly([(20, 8), (28, 8), (28, 12), (20, 12)], "sky")
    cv.poly([(30, 8), (38, 8), (43, 12), (30, 12)], "sky")
    glare(cv, [(13, 11), (14, 10), (15, 9), (23, 11), (24, 10), (25, 9),
               (36, 11), (37, 10), (38, 11)])
    cv.rect(10, 5, 36, 5, "black")                    # roof rails
    cv.rect(8, 6, 38, 6, light)
    cv.rect(5, 13, 54, 13, light)
    cv.rect(5, 21, 58, 24, shade)
    for x in (19, 29, 43):
        cv.rect(x, 14, x, 21, shade)
    cv.rect(24, 15, 25, 15, light)
    cv.rect(37, 15, 38, 15, light)
    cv.rect(56, 16, 58, 17, "yellow")
    cv.rect(4, 14, 5, 17, "red")
    cv.rect(56, 21, 60, 23, "black")
    cv.rect(3, 21, 6, 23, "black")
    for cx in (15, 48):
        cv.circle(cx, 24, 8, "black", max_y=23)
    cv.offset()
    for cx in (15, 48):
        wheel(cv, cx, 24, f, r=6)
    cv.outline()
    return cv


def lkw(f):
    cv = Canvas()
    body, light, shade = "red", "orange", "purple"
    cv.offset(0, BOUNCE[f])
    # box trailer
    cv.rect(2, 3, 44, 21, "white")
    cv.rect(2, 20, 44, 21, "silver")
    for x in (10, 18, 26, 34, 42):
        cv.rect(x, 4, x, 19, "silver")
    cv.rect(2, 12, 44, 14, body)
    cv.rect(2, 12, 44, 12, light)
    # cab
    cv.poly([(46, 6), (57, 6), (60, 12), (61, 25), (46, 25)], body)
    cv.poly([(48, 8), (56, 8), (59, 14), (48, 14)], "sky")
    glare(cv, [(52, 12), (53, 11), (54, 10), (55, 12), (56, 11)])
    cv.rect(47, 6, 56, 6, light)
    cv.rect(47, 16, 47, 22, shade)
    cv.rect(50, 17, 51, 17, "silver")
    cv.rect(59, 19, 60, 20, "yellow")
    cv.rect(62, 9, 62, 12, "dark")                    # mirror
    cv.rect(3, 22, 60, 24, "dark")                    # chassis
    cv.rect(57, 23, 61, 24, "silver")
    cv.offset()
    for cx in (10, 19, 53):
        wheel(cv, cx, 26, f, r=4.6)
    cv.outline()
    return cv


def kleinflugzeug(f):
    cv = Canvas()
    cv.offset(0, BOB[f])
    cv.poly([(4, 14), (6, 4), (10, 4), (17, 13)], "white")             # fin
    cv.poly([(4, 14), (20, 13), (30, 11), (44, 11), (52, 14), (54, 16),
             (54, 19), (44, 21), (20, 19), (4, 16)], "white")          # body
    cv.rect(2, 14, 14, 15, "silver")                                   # stabilizer
    cv.rect(5, 4, 9, 6, "red")
    cv.paint(4, 16, 54, 17, "red")
    cv.paint(4, 18, 54, 21, "silver")
    cv.poly([(32, 12), (43, 12), (47, 15), (32, 15)], "sky")
    cv.rect(37, 12, 37, 15, "white")
    glare(cv, [(34, 14), (35, 13), (41, 14), (42, 13)])
    cv.rect(24, 9, 46, 10, "white")                                    # wing
    cv.rect(24, 10, 46, 10, "silver")
    cv.rect(44, 9, 46, 10, "red")
    cv.line(31, 11, 37, 17, "gray")                                    # strut
    cv.circle(55, 17.5, 2.2, "silver")                                 # spinner
    # propeller: two-blade prop seen from the side -> blade length ~ |cos|
    blade = [8, 7, 3, 3, 7][f]
    cv.rect(57, 17 - blade, 57, 17 + blade, "gray")
    cv.line(36, 20, 36, 24, "gray")
    cv.line(50, 20, 50, 24, "gray")
    cv.circle(36.5, 26, 2.6, "dark")
    cv.circle(50.5, 26, 2.1, "dark")
    cv.outline()
    if blade < 7:   # motion blur where the blade swept past
        for y in (17 - 8, 17 + 8):
            cv.set(57, y, "silver")
    return cv


def passagierflugzeug(f):
    cv = Canvas()
    cv.offset(0, BOB[f])
    cv.poly([(4, 11), (5, 2), (10, 2), (19, 12)], "blue")              # fin
    cv.poly([(4, 11), (12, 12), (54, 12), (58, 13), (61, 16), (59, 19),
             (54, 20), (14, 20), (6, 15)], "white")                    # body
    cv.poly([(2, 11), (14, 11), (14, 13), (5, 13)], "silver")          # stabilizer
    cv.rect(5, 2, 10, 3, "sky")
    cv.paint(4, 17, 61, 17, "blue")
    cv.paint(4, 19, 61, 20, "silver")
    for x in range(16, 50, 2):
        cv.set(x, 14, "navy")
    cv.rect(54, 14, 56, 14, "navy")
    cv.rect(57, 15, 57, 15, "navy")
    cv.rect(51, 13, 51, 16, "silver")                                  # door
    cv.poly([(22, 18), (40, 18), (44, 20), (24, 20)], "silver")        # wing
    cv.rect(29, 20, 38, 23, "gray")                                    # engine
    cv.rect(29, 20, 38, 20, "silver")
    cv.rect(38, 21, 38, 22, "dark")
    # blinking lights: tail strobe and belly beacon
    if f == 0:
        cv.set(6, 2, "white")
    if f in (2, 3):
        cv.set(33, 20, "red")
    cv.outline()
    # jet exhaust shimmer behind the engine
    for i, x in enumerate(range(22, 29)):
        if (x + f) % 3 == 0:
            cv.set(x, 21 + (i % 2), "silver")
    return cv


def shuttle(f):
    cv = Canvas()
    cv.offset(3, BOB[f])
    # solid rocket booster (top)
    cv.poly([(12, 3), (50, 3), (57, 5.5), (50, 8), (12, 8)], "white")
    cv.rect(12, 7, 50, 7, "silver")
    cv.rect(9, 4, 11, 7, "gray")
    # external tank
    cv.poly([(13, 10), (48, 10), (59, 13.5), (48, 17), (13, 17)], "orange")
    cv.rect(13, 10, 48, 10, "yellow")
    cv.paint(13, 16, 59, 16, "red")
    # orbiter tail fin (sticks up behind the tank)
    cv.poly([(4, 19), (6, 11), (10, 11), (14, 19)], "white")
    cv.rect(6, 11, 9, 11, "silver")
    # orbiter
    cv.poly([(6, 19), (46, 19), (52, 20), (57, 22), (52, 25), (9, 25),
             (6, 23)], "white")
    cv.paint(9, 24, 52, 25, "dark")
    cv.poly([(51, 19), (57, 22), (52, 25), (51, 25)], "dark")
    cv.rect(46, 20, 49, 20, "navy")
    cv.rect(14, 21, 40, 21, "silver")                                  # payload door
    cv.poly([(16, 25), (38, 25), (33, 28), (13, 28)], "gray")          # wing
    cv.rect(3, 20, 5, 23, "gray")                                      # engines
    cv.outline()
    flame(cv, 8, 4, 7, [5, 7, 6, 8, 6][f])
    flame(cv, 2, 20, 23, [5, 6, 4, 6, 5][f])
    return cv


def raumschiff(f):
    """Star-wars-style starfighter (X-wing-like), S-foils open."""
    cv = Canvas()
    cv.offset(0, BOB[f])
    # wings (upper + lower S-foil)
    cv.poly([(10, 13), (29, 13), (22, 6), (10, 6)], "silver")
    cv.poly([(10, 20), (29, 20), (22, 27), (10, 27)], "silver")
    cv.paint(14, 7, 16, 12, "red")
    cv.paint(14, 21, 16, 26, "red")
    # laser cannons at the wing tips: housing + long thin barrel
    for hy, by in ((4, 5), (28, 28)):
        cv.rect(11, hy, 24, hy + 1, "gray")
        cv.rect(11, hy, 24, hy, "silver") if hy < 16 else None
        cv.rect(25, by, 50, by, "gray")
        cv.rect(51, by, 53, by, "silver")
    # engines
    for y0 in (7, 23):
        cv.rect(6, y0, 21, y0 + 3, "white")
        cv.rect(6, y0 + 3, 21, y0 + 3, "silver")
        cv.rect(20, y0, 21, y0 + 3, "gray")
        cv.rect(6, y0, 7, y0 + 3, "dark")
    # fuselage
    cv.poly([(8, 13), (30, 12), (38, 14), (59, 16), (59, 17), (38, 19),
             (8, 20)], "white")
    cv.paint(8, 18, 59, 20, "silver")
    cv.paint(42, 15, 50, 16, "red")
    cv.paint(8, 13, 30, 13, "silver")
    cv.rect(34, 16, 35, 17, "gray")                                    # panel
    # cockpit canopy
    cv.poly([(26, 12), (30, 9), (35, 9), (40, 13)], "dark")
    cv.poly([(29, 12), (31, 10), (34, 10), (37, 12)], "sky")
    cv.set(32, 10, "cyan")
    cv.set(33, 11, "cyan")
    # droid dome behind the cockpit
    cv.circle(22, 12, 2.5, "white")
    cv.set(21, 10, "blue")
    cv.set(22, 11, "blue")
    cv.outline()
    # engine glow
    for y0 in (7, 23):
        n = [3, 4, 3, 5, 4][(f + (y0 > 15) * 2) % 5]
        for i in range(n):
            x = 5 - i
            for y in range(y0, y0 + 4):
                edge = y in (y0, y0 + 3)
                if edge and i >= n - 1:
                    continue
                c = "white" if i == 0 and not edge else (
                    "orange" if i < n - 1 else "red")
                cv.set(x, y + BOB[f], c)
    return cv


def kreuzer(f):
    """Star-Wars-inspired cruiser (Venator-like): a long flat dagger hull with a red rim towards the
    bow, a raised dorsal ridge, twin T-shaped command towers at the back and a big engine block.
    No emblems or markings of the original."""
    cv = Canvas()
    cv.offset(6, BOB[f])                       # room behind for the engine trail
    # flat dagger hull
    cv.poly([(2, 12), (36, 14), (57, 18), (57, 19), (36, 22), (2, 24)], "white")
    cv.poly([(2, 19), (36, 19), (57, 19), (36, 22), (2, 24)], "silver")    # underside
    cv.rect(4, 22, 34, 22, "gray")
    # raised dorsal ridge sloping down to the bow
    cv.poly([(4, 9), (24, 10), (44, 15), (36, 15), (4, 13)], "silver")
    cv.line(4, 9, 24, 10, "white")
    # red rim towards the bow (top and bottom edge)
    cv.line(30, 14, 56, 18, "red")
    cv.line(32, 21, 56, 19, "red")
    cv.rect(18, 16, 30, 16, "red")
    # twin T-shaped command towers at the back (the far one darker)
    cv.rect(12, 4, 15, 9, "gray")
    cv.rect(10, 3, 18, 4, "gray")
    cv.rect(7, 5, 10, 9, "silver")
    cv.rect(5, 4, 13, 5, "silver")
    cv.rect(6, 4, 12, 4, "white")
    for x in (7, 9, 11):
        cv.set(x, 5, "cyan")
    for x in (13, 15, 17):
        cv.set(x, 3, "sky")
    # hangar bay slit along the side and windows
    cv.rect(10, 17, 28, 17, "dark")
    for x in range(6, 36, 3):
        cv.set(x, 15, "yellow" if (x // 3 + f) % 6 else "white")
    # engine block: three big thrusters
    cv.rect(0, 10, 3, 25, "gray")
    cv.rect(0, 10, 3, 10, "silver")
    for y0 in (11, 16, 21):
        cv.rect(0, y0, 1, y0 + 3, "sky")
    cv.outline()
    if f in (0, 1):                                  # position light at the bow
        cv.set(56, 18, "orange")
    # blue engine trail, flickering
    for k, y0 in enumerate((11, 16, 21)):
        n = [4, 5, 4, 6, 5][(f + k) % 5]
        for i in range(1, n + 1):
            for y in range(y0, y0 + 4):
                edge = y in (y0, y0 + 3)
                if edge and i >= n - 1:
                    continue
                c = "white" if i == 1 and not edge else ("cyan" if i <= 2 else ("sky" if i < n else "blue"))
                cv.set(-i, y, c)
    return cv


def panzer(f):
    cv = Canvas()
    body, light, shade = "green", "lime", "teal"
    cv.offset(0, BOUNCE[f] if f in (1, 2) else 0)
    cv.poly([(6, 22), (10, 17), (54, 17), (58, 22)], body)             # hull
    cv.rect(10, 17, 54, 17, light)
    cv.poly([(18, 17), (20, 11), (38, 11), (42, 13), (42, 17)], body)  # turret
    cv.rect(20, 11, 37, 11, light)
    cv.rect(19, 16, 42, 16, shade)
    cv.rect(42, 13, 58, 14, shade)                                     # barrel
    cv.rect(58, 12, 61, 15, shade)
    cv.rect(24, 9, 30, 10, shade)                                      # hatch
    cv.line(21, 10, 17, 2, "dark")                                     # antenna
    cv.offset()
    # tracks: links move backwards on the ground, forwards on top
    cv.circle(10, 26, 4, "dark")
    cv.circle(54, 26, 4, "dark")
    cv.rect(10, 22, 54, 29, "dark")
    for x in range(8, 57):
        if (x + f) % 5 == 0:
            cv.set(x, 29, "gray")
        if (x - f) % 5 == 0 and 10 <= x <= 54:
            cv.set(x, 22, "gray")
    for cx in (10, 54):
        wheel(cv, cx, 26, f, r=3)
    for cx in (18, 25, 32, 39, 46):
        cv.circle(cx, 26, 2.4, "gray")
        cv.circle(cx, 26, 0.9, "silver")
    cv.outline()
    puff(cv, 4, 20, f)
    return cv


def kampfjet(f):
    cv = Canvas()
    body, light, shade = "gray", "silver", "dark"
    cv.offset(0, BOB[f])
    cv.poly([(8, 15), (10, 4), (14, 4), (22, 14)], body)               # fin
    cv.poly([(8, 15), (18, 14), (40, 13), (50, 14), (62, 17), (50, 19),
             (18, 19), (8, 18)], body)                                 # fuselage
    cv.rect(10, 4, 13, 4, light)
    cv.paint(8, 14, 50, 14, light)
    cv.poly([(38, 13), (42, 10), (48, 10), (54, 14)], "sky")           # canopy
    glare(cv, [(43, 11), (44, 11), (46, 12)])
    cv.poly([(6, 17), (18, 17), (18, 19), (8, 20)], shade)             # stabilizer
    cv.rect(22, 18, 37, 19, shade)                                     # wing
    cv.poly([(34, 19), (45, 19), (45, 22), (37, 22)], shade)           # intake
    cv.rect(44, 20, 45, 21, "black")
    cv.rect(5, 15, 7, 18, "dark")                                      # nozzle
    cv.rect(23, 23, 34, 23, "white")                                   # missile
    cv.set(35, 23, "red")
    cv.rect(23, 22, 24, 24, "silver")
    cv.rect(28, 21, 28, 22, shade)                                     # pylon
    cv.set(61, 17, "white")
    cv.outline()
    flame(cv, 3 + 0, 15 + BOB[f], 18 + BOB[f], [4, 5, 3, 5, 4][f])
    return cv


# ---------------------------------------------------------------- icons (16x16)
# Same palette + outline as the vehicles. Used for accelerators/decelerators.

IW = 16


def icon(fn):
    def wrapper():
        cv = Canvas(IW, IW)
        fn(cv)
        cv.outline()
        return cv
    wrapper.__name__ = fn.__name__
    return wrapper


@icon
def check(cv):
    cv.rect(2, 2, 13, 13, "white")
    cv.rect(2, 13, 13, 13, "silver")
    for dy in (0, 1):
        cv.line(4, 7 + dy, 6, 9 + dy, "green")
        cv.line(6, 9 + dy, 11, 4 + dy, "green")


@icon
def dumbbell(cv):
    cv.rect(6, 7, 9, 8, "silver")
    for x0, x1, y0, y1, c in ((1, 2, 5, 10, "gray"), (3, 5, 3, 12, "dark"),
                              (13, 14, 5, 10, "gray"), (10, 12, 3, 12, "dark")):
        cv.rect(x0, y0, x1, y1, c)
    cv.rect(4, 4, 4, 6, "gray")
    cv.rect(11, 4, 11, 6, "gray")


@icon
def shoe(cv):
    cv.poly([(2, 11), (2, 5), (6, 5), (8, 8), (13, 9), (15, 11)], "red")
    cv.rect(1, 11, 14, 12, "white")
    cv.rect(1, 12, 14, 12, "silver")
    cv.rect(2, 5, 5, 5, "orange")
    for x, y in ((6, 7), (7, 8), (8, 9)):
        cv.set(x, y, "white")
    cv.rect(10, 10, 12, 10, "orange")


@icon
def book(cv):
    cv.rect(3, 2, 12, 13, "blue")
    cv.rect(3, 2, 4, 13, "navy")
    cv.rect(5, 12, 12, 13, "white")
    cv.rect(5, 13, 12, 13, "silver")
    cv.rect(7, 5, 10, 6, "yellow")


@icon
def apple(cv):
    cv.circle(8, 9.5, 5.5, "red")
    cv.set(5, 7, "orange")
    cv.set(5, 8, "orange")
    cv.set(6, 6, "orange")
    cv.rect(8, 2, 8, 4, "dark")
    cv.poly([(9, 4), (13, 1.5), (12, 4)], "green")


@icon
def drop(cv):
    cv.circle(8, 10, 4.6, "sky")
    cv.poly([(8, 1), (3.6, 9.5), (12.4, 9.5)], "sky")
    cv.set(6, 9, "cyan")
    cv.set(6, 10, "cyan")
    cv.set(7, 7, "cyan")
    cv.set(6, 11, "white")
    cv.rect(9, 13, 10, 13, "blue")


@icon
def moon(cv):
    cv.circle(7, 9, 6, "yellow")
    cv.circle(10, 7, 4.8, None)
    cv.set(3, 9, "white")
    cv.set(3, 10, "white")
    cv.rect(10, 1, 13, 1, "white")
    cv.line(13, 1, 10, 4, "white")
    cv.rect(10, 4, 13, 4, "white")


@icon
def sun(cv):
    cv.circle(8, 8, 4.2, "yellow")
    cv.set(6, 6, "white")
    for x, y in ((7, 1), (8, 1), (7, 14), (8, 14), (1, 7), (1, 8), (14, 7), (14, 8),
                 (3, 3), (12, 3), (3, 12), (12, 12)):
        cv.set(x, y, "orange")


@icon
def heart(cv):
    cv.circle(5, 6, 3.3, "red")
    cv.circle(11, 6, 3.3, "red")
    cv.poly([(1.8, 7), (14.2, 7), (8, 14)], "red")
    cv.set(4, 5, "white")
    cv.set(4, 4, "orange")
    cv.set(5, 4, "orange")


@icon
def note(cv):
    cv.rect(6, 2, 12, 3, "blue")
    cv.rect(6, 4, 6, 10, "blue")
    cv.rect(12, 4, 12, 9, "blue")
    cv.circle(4.5, 11.5, 2.3, "blue")
    cv.circle(10.5, 10.5, 2.3, "blue")
    cv.set(4, 11, "sky")
    cv.set(10, 10, "sky")


@icon
def chat(cv):
    cv.rect(1, 2, 14, 10, "white")
    for x, y in ((1, 2), (14, 2), (1, 10), (14, 10)):
        cv.set(x, y, None)
    cv.poly([(3, 10), (8, 10), (3, 14)], "white")
    cv.rect(2, 10, 13, 10, "silver")
    for x in (4, 7, 10):
        cv.rect(x, 5, x + 1, 6, "dark")


@icon
def pencil(cv):
    for y in range(IW):
        for x in range(IW):
            if abs(x + y - 15) > 1:
                continue
            t = x - y
            if t < -11 or t > 11:
                continue
            if t <= -10:
                c = "dark"
            elif t <= -7:
                c = "white"
            elif t >= 8:
                c = "red"
            elif t >= 6:
                c = "silver"
            else:
                c = "orange" if x + y == 16 else "yellow"
            cv.set(x, y, c)


@icon
def plant(cv):
    cv.poly([(4, 10), (12, 10), (11, 15), (5, 15)], "orange")
    cv.rect(3, 9, 12, 10, "red")
    cv.rect(7, 4, 8, 9, "green")
    cv.poly([(7, 8), (1, 4), (2, 2), (7, 5)], "green")
    cv.poly([(8, 7), (14, 2), (15, 4), (8, 9)], "green")
    cv.set(3, 3, "lime")
    cv.set(13, 3, "lime")


@icon
def star(cv):
    pts = []
    for i in range(10):
        r = 7.2 if i % 2 == 0 else 3.0
        a = math.radians(-90 + 36 * i)
        pts.append((8 + r * math.cos(a), 8.6 + r * math.sin(a)))
    cv.poly(pts, "yellow")
    cv.set(7, 6, "white")
    cv.paint(0, 11, 15, 15, "orange")


@icon
def bulb(cv):
    cv.circle(8, 6.5, 5, "yellow")
    cv.rect(6, 10, 9, 11, "yellow")
    cv.set(5, 4, "white")
    cv.set(5, 5, "white")
    cv.set(6, 3, "white")
    cv.rect(6, 12, 9, 14, "silver")
    cv.rect(6, 13, 9, 13, "gray")


@icon
def phone(cv):
    cv.rect(4, 1, 11, 14, "dark")
    cv.rect(5, 3, 10, 11, "sky")
    cv.set(6, 4, "cyan")
    cv.set(6, 5, "cyan")
    cv.rect(7, 13, 8, 13, "gray")


@icon
def burger(cv):
    cv.circle(8, 8, 6.4, "orange", max_y=6)
    for x, y in ((5, 4), (8, 3), (10, 5), (11, 3)):
        cv.set(x, y, "yellow")
    cv.rect(1, 7, 14, 7, "green")
    cv.rect(2, 8, 13, 9, "purple")
    cv.rect(3, 8, 5, 8, "yellow")
    cv.rect(10, 8, 12, 8, "yellow")
    cv.rect(2, 10, 13, 12, "orange")
    cv.rect(2, 12, 13, 12, "red")


@icon
def beer(cv):
    cv.rect(11, 6, 14, 12, "silver")
    cv.rect(12, 7, 13, 11, None)
    cv.rect(3, 5, 10, 14, "yellow")
    cv.rect(9, 5, 10, 14, "orange")
    cv.set(5, 9, "white")
    cv.set(6, 12, "white")
    cv.rect(2, 2, 11, 5, "white")
    cv.rect(2, 1, 4, 1, "white")
    cv.rect(7, 1, 9, 1, "white")
    cv.rect(2, 5, 11, 5, "silver")


@icon
def tv(cv):
    cv.line(4, 0, 6, 3, "gray")
    cv.line(11, 0, 9, 3, "gray")
    cv.rect(1, 4, 14, 13, "dark")
    cv.rect(2, 5, 10, 12, "sky")
    cv.set(3, 6, "cyan")
    cv.set(3, 7, "cyan")
    cv.set(4, 6, "cyan")
    cv.rect(12, 6, 13, 6, "silver")
    cv.rect(12, 8, 13, 8, "silver")
    cv.set(2, 14, "dark")
    cv.set(13, 14, "dark")


@icon
def storm(cv):
    cv.circle(5, 7, 3.2, "gray")
    cv.circle(9, 5.5, 4, "gray")
    cv.circle(12, 7.5, 2.8, "gray")
    cv.rect(3, 7, 13, 10, "gray")
    cv.rect(3, 10, 13, 10, "dark")
    cv.set(7, 3, "silver")
    cv.set(8, 3, "silver")
    cv.poly([(8, 9), (11, 9), (9.5, 11.5), (11, 11.5), (6.5, 15.8), (8, 12),
             (6.5, 12)], "yellow")


@icon
def calendar(cv):
    cv.rect(2, 3, 13, 14, "white")
    cv.rect(2, 3, 13, 6, "red")
    cv.rect(2, 14, 13, 14, "silver")
    cv.rect(5, 1, 5, 4, "dark")
    cv.rect(10, 1, 10, 4, "dark")
    for x in (4, 7, 10):
        for y in (8, 11):
            cv.rect(x, y, x + 1, y + 1, "red" if (x, y) == (10, 11) else "silver")


@icon
def trophy(cv):
    cv.rect(4, 2, 11, 7, "yellow")
    cv.rect(5, 8, 10, 8, "yellow")
    cv.rect(6, 9, 9, 9, "orange")
    cv.rect(7, 10, 8, 11, "orange")
    cv.rect(4, 12, 11, 13, "purple")
    cv.rect(5, 12, 10, 12, "orange")
    for x in (2, 13):                      # handles
        cv.rect(x, 3, x, 5, "yellow")
    cv.set(3, 6, "yellow")
    cv.set(12, 6, "yellow")
    cv.rect(10, 3, 10, 6, "orange")        # shading
    cv.rect(5, 3, 5, 4, "white")           # shine


ICONS = [check, dumbbell, shoe, book, apple, drop, moon, sun, heart, note, chat,
         pencil, plant, star, bulb, phone, burger, beer, tv, storm, calendar, trophy]

# more rule icons (icons_more.py), outlined like the others
from icons_more import DRAW as _MORE_ICONS  # noqa: E402

ICONS += [icon(fn) for fn in _MORE_ICONS]


# UI glyphs: white, no outline (tinted by the client)

def ui_icon(fn):
    def wrapper():
        cv = Canvas(IW, IW)
        fn(cv)
        return cv
    wrapper.__name__ = "ui_" + fn.__name__
    return wrapper


@ui_icon
def home(cv):
    cv.poly([(8, 1.5), (15, 8), (1, 8)], "white")
    cv.rect(3, 8, 12, 14, "white")
    cv.rect(7, 10, 8, 14, None)


@ui_icon
def up(cv):
    cv.poly([(8, 1), (15, 8.5), (1, 8.5)], "white")
    cv.rect(5, 8, 10, 14, "white")


@ui_icon
def down(cv):
    cv.poly([(8, 15), (15, 7.5), (1, 7.5)], "white")
    cv.rect(5, 2, 10, 8, "white")


@ui_icon
def gear(cv):
    cv.circle(8, 8, 5.2, "white")
    for x0, y0, x1, y1 in ((7, 0, 8, 15), (0, 7, 15, 8)):
        cv.rect(x0, y0, x1, y1, "white")
    for x, y in ((2, 2), (12, 2), (2, 12), (12, 12)):
        cv.rect(x, y, x + 1, y + 1, "white")
    cv.circle(8, 8, 2.2, None)


@ui_icon
def plus(cv):
    cv.rect(7, 2, 8, 13, "white")
    cv.rect(2, 7, 13, 8, "white")


@ui_icon
def edit(cv):
    for y in range(IW):
        for x in range(IW):
            if abs(x + y - 15) <= 1 and -9 <= x - y <= 11:
                cv.set(x, y, "white")
    cv.set(1, 14, "white")


@ui_icon
def back(cv):
    cv.poly([(1, 8), (8, 1.5), (8, 14.5)], "white")
    cv.rect(7, 6, 14, 9, "white")


@ui_icon
def feed(cv):
    cv.rect(1, 1, 14, 6, "white")
    cv.rect(1, 9, 14, 14, "white")
    cv.rect(3, 3, 12, 4, None)
    cv.rect(3, 11, 12, 12, None)


@ui_icon
def heart(cv):
    cv.circle(5, 6, 3.6, "white")
    cv.circle(11, 6, 3.6, "white")
    cv.poly([(1.5, 7), (14.5, 7), (8, 14.5)], "white")
    cv.circle(5, 6, 2.1, None)
    cv.circle(11, 6, 2.1, None)
    cv.poly([(2.9, 6.5), (13.1, 6.5), (8, 12.4)], None)


@ui_icon
def comment(cv):
    cv.rect(1, 2, 14, 11, "white")
    cv.rect(3, 4, 12, 9, None)
    cv.poly([(3, 11), (7, 11), (3, 15)], "white")


@ui_icon
def chat(cv):
    cv.rect(1, 2, 14, 11, "white")
    cv.poly([(9, 11), (13, 11), (13, 15)], "white")
    for x in (4, 7, 10):
        cv.rect(x, 6, x + 1, 7, None)


@ui_icon
def search(cv):
    cv.circle(6.5, 6.5, 5.5, "white")
    cv.circle(6.5, 6.5, 3.5, None)
    for i in range(4):
        cv.rect(10 + i, 10 + i, 11 + i, 11 + i, "white")


@ui_icon
def send(cv):
    cv.poly([(1, 2), (15, 8), (1, 14), (5, 8)], "white")


@ui_icon
def image(cv):
    cv.rect(1, 2, 14, 13, "white")
    cv.rect(2, 3, 13, 12, None)
    cv.poly([(2, 12.5), (6, 7), (9, 10), (11, 8), (13.5, 12.5)], "white")
    cv.rect(10, 4, 11, 5, "white")


@ui_icon
def dots(cv):
    for x in (2, 7, 12):
        cv.rect(x, 7, x + 1, 8, "white")


@ui_icon
def close(cv):
    for i in range(12):
        cv.rect(2 + i, 2 + i, 3 + i, 3 + i, "white")
        cv.rect(13 - i, 2 + i, 14 - i, 3 + i, "white")


@ui_icon
def backspace(cv):
    """The emoji picker's delete key (report #64): a key pointing left with a cross cut out."""
    cv.poly([(0, 8), (5.5, 2.5), (16, 2.5), (16, 13.5), (5.5, 13.5)], "white")
    for i in range(5):
        cv.rect(8 + i, 6 + i, 9 + i, 6 + i, None)
        cv.rect(12 - i, 6 + i, 13 - i, 6 + i, None)


@ui_icon
def video(cv):
    cv.poly([(4, 2), (14, 8), (4, 14)], "white")


@ui_icon
def grid(cv):
    for gy in range(3):
        for gx in range(3):
            cv.rect(gx * 6, gy * 6, gx * 6 + 3, gy * 6 + 3, "white")


@ui_icon
def rules(cv):
    for y in (1, 6, 11):
        cv.rect(1, y, 3, y + 3, "white")
        cv.rect(6, y + 1, 14, y + 2, "white")


@ui_icon
def clock(cv):
    cv.circle(8, 8, 7.5, "white")
    cv.circle(8, 8, 5.6, None)
    cv.rect(7, 4, 8, 8, "white")
    cv.rect(8, 7, 11, 8, "white")


@ui_icon
def gauge(cv):
    """Speedometer (the profile's momentum tab)."""
    cv.circle(8, 12, 7.6, "white", max_y=12)                             # dial
    cv.circle(8, 12, 6.2, None, max_y=12)
    for x, y in ((3, 8), (5, 6), (8, 5), (11, 6)):                     # scale ticks
        cv.set(x, y, "white")
    cv.line(8, 12, 12, 7, "white")                                      # needle
    cv.rect(7, 11, 8, 12, "white")                                      # hub


@ui_icon
def mic(cv):
    cv.rect(6, 1, 9, 9, "white")                       # capsule
    cv.rect(5, 2, 10, 8, "white")
    cv.rect(3, 6, 3, 9, "white"); cv.rect(12, 6, 12, 9, "white")   # holder
    cv.rect(4, 10, 11, 11, "white")
    cv.rect(7, 12, 8, 13, "white")
    cv.rect(4, 14, 11, 14, "white")


@ui_icon
def play(cv):
    cv.poly([(4, 2), (13.5, 8), (4, 14)], "white")


@ui_icon
def pause(cv):
    cv.rect(3, 2, 6, 13, "white")
    cv.rect(9, 2, 12, 13, "white")


@ui_icon
def eye(cv):
    cv.rect(5, 4, 10, 4, "white")          # lid outline
    cv.rect(3, 5, 4, 5, "white")
    cv.rect(11, 5, 12, 5, "white")
    cv.rect(1, 6, 2, 7, "white")
    cv.rect(13, 6, 14, 7, "white")
    cv.rect(0, 8, 0, 8, "white")
    cv.rect(15, 8, 15, 8, "white")
    cv.rect(1, 9, 2, 10, "white")
    cv.rect(13, 9, 14, 10, "white")
    cv.rect(3, 11, 4, 11, "white")
    cv.rect(11, 11, 12, 11, "white")
    cv.rect(5, 12, 10, 12, "white")
    cv.rect(6, 6, 9, 10, "white")          # iris
    cv.rect(5, 7, 10, 9, "white")
    cv.rect(7, 7, 8, 8, None)              # pupil highlight


@ui_icon
def lock(cv):
    cv.rect(3, 7, 12, 14, "white")
    cv.rect(6, 1, 9, 1, "white")
    cv.rect(5, 2, 5, 6, "white")
    cv.rect(10, 2, 10, 6, "white")
    cv.rect(7, 9, 8, 12, None)


@ui_icon
def share(cv):
    cv.rect(2, 6, 3, 14, "white")          # box
    cv.rect(2, 13, 13, 14, "white")
    cv.rect(12, 6, 13, 14, "white")
    cv.rect(7, 1, 8, 10, "white")          # arrow up
    for i in range(3):
        cv.rect(6 - i, 2 + i, 9 + i, 3 + i, "white")
    cv.rect(4, 6, 5, 6, None)
    cv.rect(10, 6, 11, 6, None)


@ui_icon
def group(cv):
    cv.rect(3, 2, 6, 6, "white")           # heads
    cv.rect(10, 2, 13, 6, "white")
    cv.rect(1, 9, 7, 14, "white")           # bodies
    cv.rect(9, 9, 15, 14, "white")


@ui_icon
def updown(cv):
    """Accelerators and decelerators share one tab: an arrow up next to an arrow down."""
    cv.poly([(4, 0.5), (8, 5.5), (0, 5.5)], "white")
    cv.rect(2, 5, 5, 15, "white")
    cv.poly([(12, 15.5), (16, 10.5), (8, 10.5)], "white")
    cv.rect(10, 1, 13, 11, "white")


@ui_icon
def cup(cv):
    """Leaderboard: a trophy glyph."""
    cv.rect(4, 1, 11, 6, "white")
    cv.rect(5, 7, 10, 7, "white")
    cv.rect(6, 8, 9, 8, "white")
    cv.rect(7, 9, 8, 11, "white")
    cv.rect(4, 12, 11, 14, "white")
    for x in (1, 14):                      # handles
        cv.rect(x, 2, x, 4, "white")
    cv.set(2, 5, "white")
    cv.set(13, 5, "white")
    cv.set(2, 1, "white")
    cv.set(13, 1, "white")


@ui_icon
def grip(cv):
    """Drag handle."""
    for y in (4, 7, 10):
        for x in (5, 9):
            cv.rect(x, y, x + 1, y + 1, "white")


@ui_icon
def bell(cv):
    cv.rect(5, 3, 10, 10, "white")
    cv.rect(4, 6, 11, 11, "white")
    cv.rect(3, 11, 12, 12, "white")
    cv.rect(7, 1, 8, 2, "white")
    cv.rect(6, 13, 9, 14, "white")


@ui_icon
def muted(cv):
    """A bell, crossed out."""
    cv.rect(5, 3, 10, 10, "white")
    cv.rect(4, 6, 11, 11, "white")
    cv.rect(3, 11, 12, 12, "white")
    cv.rect(7, 1, 8, 2, "white")
    cv.rect(6, 13, 9, 14, "white")
    for i in range(16):
        cv.set(i, i, None)
        if i + 1 < 16:
            cv.set(i + 1, i, None)
    for i in range(1, 15):
        cv.set(i, i - 1 if i > 0 else 0, "white")


@ui_icon
def notap(cv):
    """A pointing finger with a red "no" sign over it: this rule can't be tapped."""
    hand = set()
    def fill(x0, y0, x1, y1):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                hand.add((x, y))
    fill(6, 2, 8, 8)                       # index finger (3 wide)
    fill(9, 6, 10, 8)                      # the other fingers, curled
    fill(11, 7, 12, 9)
    fill(5, 8, 12, 12)                     # palm
    fill(3, 8, 4, 10)                      # thumb
    fill(6, 13, 11, 13)
    for (x, y) in hand:
        cv.set(x, y, "white")
    for (x, y) in hand:                    # dark outline so the hand reads on any card
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in hand and 0 <= q[0] < IW and 0 <= q[1] < IW:
                cv.set(q[0], q[1], "black")
    for y in range(IW):                    # thin red ring and slash
        for x in range(IW):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 6.6 <= d <= 7.7 or (d < 6.6 and abs(x - (15 - y)) <= 0):
                cv.set(x, y, "red")


UI_ICONS = [home, gauge, up, down, gear, plus, edit, back, feed, heart, comment, chat, search, send, image,
            dots, close, video, grid, rules, clock, lock, share, group, eye, mic, play, pause, updown, cup, grip, bell, muted,
            notap, backspace]


# Scene pieces for the app's card backgrounds. Strips/tiles wrap horizontally.

def _cloud(cv, x, y, s=1.0):
    cv.circle(x + 9 * s, y + 7 * s, 4.5 * s, "white")
    cv.circle(x + 17 * s, y + 5.5 * s, 5.5 * s, "white")
    cv.circle(x + 25 * s, y + 8 * s, 3.5 * s, "white")
    cv.rect(round(x + 4 * s), round(y + 8 * s), round(x + 28 * s), round(y + 11 * s), "white")
    cv.paint(0, round(y + 10 * s), cv.w - 1, round(y + 11 * s), "silver")


def scene_cloud():
    cv = Canvas(128, 40)
    _cloud(cv, 2, 2)
    _cloud(cv, 64, 22, 0.7)
    return cv


def _mountains(color, cap, f1, f2, phase, base, amp):
    cv = Canvas(64, 24)
    for x in range(64):
        t = 2 * math.pi * x / 64
        h = round(base + amp * math.sin(f1 * t + phase) + amp * 0.5 * math.sin(f2 * t))
        cv.rect(x, 24 - h, x, 23, color)
        if cap:
            cv.set(x, 24 - h, cap)
    return cv


def scene_mountains_far():
    return _mountains("purple", None, 2, 5, 0.6, 14, 4)


def scene_mountains_near():
    return _mountains("black", "dark", 3, 7, 2.1, 8, 3)


def scene_planet():
    cv = Canvas(24, 24)
    cv.circle(12, 12, 11, "navy")
    cv.circle(10, 10, 8.5, "blue")
    cv.circle(8.5, 8.5, 4, "sky")
    cv.circle(7.5, 7.5, 1.5, "cyan")
    for x, y in ((15, 15), (16, 15), (15, 16), (6, 16), (17, 8)):
        cv.set(x, y, "navy")
    return cv


def scene_sun():
    cv = Canvas(16, 16)
    cv.circle(8, 8, 7.5, "orange")
    cv.circle(8, 8, 5.5, "yellow")
    return cv


def scene_rain():
    cv = Canvas(16, 24)
    for x, y in ((2, 1), (9, 7), (5, 14), (13, 18), (12, 3)):
        cv.rect(x, y, x, y + 2, "sky")
    return cv


SCENES = [scene_cloud, scene_mountains_far, scene_mountains_near, scene_planet,
          scene_sun, scene_rain]


def app_icon():
    px = [["black"] * 32 for _ in range(32)]
    st = star()
    for y in range(16):
        for x in range(16):
            if st.px[y][x]:
                px[8 + y][8 + x] = st.px[y][x]
    return px


# ---------------------------------------------------------------- empty-state animation

def _person(cv, x, y, shirt, arm_angle=None):
    """Small pixel person, feet at y+27. arm_angle (deg) raises the right arm."""
    cv.circle(x + 6, y + 5, 4.2, "yellow")              # head
    cv.rect(x + 2, y + 1, x + 10, y + 2, "dark")         # hair
    cv.rect(x + 2, y + 3, x + 2, y + 5, "dark")
    cv.set(x + 5, y + 5, "black")
    cv.set(x + 8, y + 5, "black")
    cv.rect(x + 2, y + 10, x + 10, y + 19, shirt)        # body
    cv.rect(x + 3, y + 20, x + 5, y + 25, "navy")        # legs
    cv.rect(x + 7, y + 20, x + 9, y + 25, "navy")
    cv.rect(x + 2, y + 26, x + 5, y + 27, "dark")        # shoes
    cv.rect(x + 7, y + 26, x + 10, y + 27, "dark")
    cv.rect(x - 1, y + 11, x + 1, y + 18, shirt)         # left arm down
    cv.rect(x - 1, y + 18, x + 1, y + 19, "yellow")
    if arm_angle is None:
        cv.rect(x + 11, y + 11, x + 13, y + 18, shirt)
        cv.rect(x + 11, y + 18, x + 13, y + 19, "yellow")
    else:
        a = math.radians(arm_angle)
        for t in range(9):
            px = round(x + 12 + t * math.cos(a))
            py = round(y + 12 - t * math.sin(a))
            cv.rect(px - 1, py - 1, px, py, shirt if t < 7 else "yellow")


def friends(f):
    """A person waving at an outlined 'missing friend' silhouette with a '?'."""
    cv = Canvas()
    bob = [0, -1, -1, 0, 0][f]
    cv.offset(0, bob)
    _person(cv, 14, 3, "blue", arm_angle=[35, 55, 70, 55, 35][f])
    cv.offset()
    cv.outline()
    # silhouette: draw a person on a scratch canvas and keep only its outline
    ghost = Canvas()
    _person(ghost, 38, 3, "white")
    for y in range(H):
        for x in range(W):
            if ghost.px[y][x] is None:
                continue
            edge = any(not (0 <= x + dx < W and 0 <= y + dy < H) or ghost.px[y + dy][x + dx] is None
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                cv.set(x, y, "gray")
    # bobbing question mark above the silhouette
    q = [0, -1, -2, -1, 0][f]
    for x, y in ((51, 2), (52, 1), (53, 1), (54, 2), (54, 3), (53, 4), (52, 5), (52, 6), (52, 8)):
        cv.set(x, y + q + 1, "yellow")
    return cv


# ---------------------------------------------------------------- momentometer gauge (login + app icon)

def _gauge(cv, cx, cy, r_out, r_in, needle_deg, segments=9, ticks=True):
    """Half-circle speedometer. needle_deg: 0 = far left, 180 = far right.
    Segments up to the needle light up (lime -> yellow -> red), the rest stay dark."""
    palette = ["lime", "yellow", "red"] if segments == 3 else         ["lime", "lime", "lime", "yellow", "yellow", "yellow", "orange", "red", "red"]
    seg_deg = 180 / segments
    for y in range(cv.h):
        for x in range(cv.w):
            dx, dy = x + 0.5 - cx, cy - (y + 0.5)
            r = math.hypot(dx, dy)
            if dy < -0.5 or r > r_out:
                continue
            if r < r_in:
                cv.px[y][x] = "black"                                # dial face
                continue
            deg = 180 - math.degrees(math.atan2(max(dy, 0), dx))      # 0 left .. 180 right
            seg = min(segments - 1, int(deg / seg_deg))
            if segments > 3 and deg % seg_deg < 2.2:
                cv.px[y][x] = "black"                                # gap between segments
                continue
            cv.px[y][x] = palette[seg] if deg <= needle_deg else "dark"
    if ticks:
        for k in range(1, segments):
            a = math.radians(180 - k * seg_deg)
            rr = r_in - 2
            cv.px[round(cy - 0.5 - rr * math.sin(a))][round(cx - 0.5 + rr * math.cos(a))] = "gray"
    # needle: 2px wide near the hub, 1px towards the tip
    a = math.radians(180 - needle_deg)
    length = r_out - 2
    nx, ny = -math.sin(a), -math.cos(a)                              # perpendicular
    steps = int(length * 3)
    for i in range(steps + 1):
        t = length * i / steps
        px, py = cx - 0.5 + t * math.cos(a), cy - 0.5 - t * math.sin(a)
        cv.px[round(py)][round(px)] = "white"
        if t < length * .6:
            cv.px[round(py + ny * .7)][round(px + nx * .7)] = "white"
    cv.circle(cx, cy, max(2.2, r_out * .14), "gray")
    cv.circle(cx, cy, max(1.1, r_out * .07), "silver")


def gauge(f):
    """Login animation: the momentometer revving in the red zone."""
    cv = Canvas()
    cv.rect(4, 28, 59, 30, "dark")                                   # base plate
    cv.rect(4, 28, 59, 28, "gray")
    _gauge(cv, 32, 28.5, 26, 18, [138, 158, 146, 170, 152][f])
    # little sparks flying off the red end
    for (x, y), on in zip(((56, 6), (59, 10), (61, 4)), ([1, 0, 1, 0, 1][f], [0, 1, 0, 1, 1][f], [1, 1, 0, 0, 1][f])):
        if on:
            cv.set(x, y, "yellow")
    cv.outline()
    return cv


def app_icon_art(grid, cx, cy, r_out, r_in, bg=None):
    """Chunky 3-segment momentometer for the launcher icon."""
    cv = Canvas(grid, grid)
    if bg:
        cv.rect(0, 0, grid - 1, grid - 1, bg)
    inner = Canvas(grid, grid)
    _gauge(inner, cx, cy, r_out, r_in, 128, segments=3, ticks=False)
    inner.outline()
    for y in range(grid):
        for x in range(grid):
            if inner.px[y][x]:
                cv.px[y][x] = inner.px[y][x]
    return cv


VEHICLES = {
    "fahrrad": fahrrad,
    "motorroller": motorroller,
    "kleinwagen": kleinwagen,
    "pkw": pkw,
    "suv": suv,
    "lkw": lkw,
    "kleinflugzeug": kleinflugzeug,
    "passagierflugzeug": passagierflugzeug,
    "shuttle": shuttle,
    "raumschiff": raumschiff,
    "panzer": panzer,
    "kampfjet": kampfjet,
    "kreuzer": kreuzer,
}

if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    all_frames = []
    for name, fn in VEHICLES.items():
        d = os.path.join(out, name)
        os.makedirs(d)
        frames = [fn(f) for f in range(FRAMES)]
        for i, cv in enumerate(frames):
            write_png(os.path.join(d, f"frame_{i}.png"), cv.px)
        sheet = compose([frames], gap=0)
        write_png(os.path.join(d, "sheet.png"), sheet)
        write_png(os.path.join(d, f"sheet_x{SCALE}.png"), sheet, SCALE)
        write_gif(os.path.join(d, f"anim_x{SCALE}.gif"), [cv.px for cv in frames], SCALE)
        all_frames.append(frames)
        print("wrote", name)

    # extra animations (empty contact lists, login), same sheet format as vehicles
    for name, fn in (("friends", friends), ("gauge", gauge)):
        d = os.path.join(out, name)
        os.makedirs(d)
        fr = [fn(f) for f in range(FRAMES)]
        write_png(os.path.join(d, "sheet.png"), compose([fr], gap=0))
        write_png(os.path.join(d, f"sheet_x{SCALE}.png"), compose([fr], gap=0), SCALE)
        write_gif(os.path.join(d, f"anim_x{SCALE}.gif"), [cv.px for cv in fr], SCALE)

    # overview: all vehicles animated (4 columns) + all frames as one PNG
    cols = 4
    gif_frames = []
    for f in range(FRAMES):
        cvs = [frames[f] for frames in all_frames]
        grid = [cvs[i:i + cols] for i in range(0, len(cvs), cols)]
        gif_frames.append(compose(grid, bg="white"))
    write_gif(os.path.join(out, "_uebersicht.gif"), gif_frames, 4)
    write_png(os.path.join(out, "_uebersicht.png"), compose(all_frames, bg="white"), 3)

    # icons
    d = os.path.join(out, "icons")
    os.makedirs(d)
    icon_cvs = [fn() for fn in ICONS + UI_ICONS]
    for fn, cv in zip(ICONS + UI_ICONS, icon_cvs):
        write_png(os.path.join(d, f"{fn.__name__}.png"), cv.px)
    for fn in SCENES:
        write_png(os.path.join(d, f"{fn.__name__}.png"), fn().px)
    import banners                      # profile/group/challenge banner layers
    banners.build(out)
    legacy = app_icon_art(24, 12, 17, 10.5, 5, bg="navy").px
    write_png(os.path.join(out, "app_icon_192.png"), legacy, 8)
    write_png(os.path.join(out, "app_icon_512.png"), [row for row in legacy for _ in (0,)], 21)
    fg = app_icon_art(36, 18, 23, 11, 5.5).px      # inside the 22px adaptive-icon safe zone
    write_png(os.path.join(out, "app_icon_foreground_432.png"), fg, 12)
    gap, cols = 2, 7
    rows = (len(icon_cvs) + cols - 1) // cols
    sheet = [["navy"] * (cols * (IW + gap) + gap) for _ in range(rows * (IW + gap) + gap)]
    for i, cv in enumerate(icon_cvs):
        ox, oy = gap + (i % cols) * (IW + gap), gap + (i // cols) * (IW + gap)
        for y in range(IW):
            for x in range(IW):
                if cv.px[y][x]:
                    sheet[oy + y][ox + x] = cv.px[y][x]
    write_png(os.path.join(out, "_icons.png"), sheet, 8)
    print("wrote", len(icon_cvs), "icons")
