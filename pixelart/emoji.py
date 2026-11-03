"""Pixel-art emojis: every emoji of Unicode 16.0 as an 18x18 sprite (16x16 picture + the same
1 px dark outline as the vehicles / rule icons), packed into two colour fonts so that both apps
render emojis in pixel art everywhere - in texts, inputs, reactions and as rule icons.

Sources (downloaded into pixelart/cache/ when missing):
  emoji-test.txt   Unicode's emoji list (groups, names, qualified sequences)
  twemoji zip      Twemoji 16 (CC-BY 4.0, https://github.com/jdecked/twemoji) 72x72 PNGs: shrunk to 16x16,
                   (Lanczos), alpha cut at 50 %, colours reduced to 8 per emoji, then outlined.

Run: python emoji.py  ->
  out/emoji/emoji.json                      groups + emojis (names, skin-tone variants) for the pickers and the server
  out/emoji/sheet.png                       all sprites, 64 per row, in the order of emoji.json (groups, entry, its variants)
  out/emoji/_preview.png                    the first rows at 4x, to look at
  out/emoji/pixel_emoji.woff2               COLRv0 vector font for the PWA (Safari, Chrome, Firefox)
  ../android/app/src/main/res/font/pixel_emoji.ttf   CBDT bitmap font for Android (6 bitmap px per art px)

Both fonts: 1 em = 1600 units, the sprite is 18 x 100 units wide (1.125 em) and sits from -400 to 1400
(a little below the baseline like other emoji fonts); FE0F / 200D / keycap / tag characters are empty
glyphs so that every sequence of emoji-test.txt is a 'ccmp' ligature of them.
"""
import io
import json
import pickle
import os
import re
import sys
import urllib.request
import zipfile
from collections import OrderedDict

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
OUT = os.path.join(HERE, "out", "emoji")
ANDROID_FONT = os.path.join(HERE, "..", "android", "app", "src", "main", "res", "font", "pixel_emoji.ttf")

EMOJI_VERSION = "16.0"
TWEMOJI_VERSION = "16.0.1"
EMOJI_TEST_URL = f"https://unicode.org/Public/emoji/{EMOJI_VERSION}/emoji-test.txt"
TWEMOJI_URL = f"https://github.com/jdecked/twemoji/archive/refs/tags/v{TWEMOJI_VERSION}.zip"

ART = 16          # the picture
CELL = ART + 2    # with the outline
OUTLINE = (0x1A, 0x1C, 0x2C, 255)   # Sweetie 16 "black", like generate.py
COLORS = 8        # colours per emoji (besides the outline)
SHEET_COLS = 64

UPEM = 1600
PX = 100          # font units per art pixel
X0, TOP = 50, 1400                  # sprite box: x 50..1850, y -400..1400
ADVANCE = 1900
ASCENT, DESCENT = 1472, -448        # Pixelify's 920/-280 scaled to 1600
BITMAP_SCALE = 6                    # CBDT: 6 bitmap px per art px -> 108 px strike
PPEM = BITMAP_SCALE * CELL * UPEM // (CELL * PX)   # 96: 108 px = 1.125 em

# "component": skin tones and hair, parts of other sequences (own sprites too; the pickers hide their group)
SPRITED = ("fully-qualified", "component")
SKIN_TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
VS16, VS15, ZWJ, KEYCAP = 0xFE0F, 0xFE0E, 0x200D, 0x20E3


# ---------------------------------------------------------------- sources

def fetch(url, name):
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        print("downloading", url)
        urllib.request.urlretrieve(url, path)
    return path


def parse_emoji_test(path):
    """[(group, [entry])]; entry = dict(seq, name, status). Keeps the file's order."""
    groups, group = [], None
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("# group:"):
                group = (line.split(":", 1)[1].strip(), [])
                groups.append(group)
            elif line and line[0] != "#" and ";" in line:
                codes, rest = line.split(";", 1)
                status, comment = rest.split("#", 1)
                seq = tuple(int(c, 16) for c in codes.split())
                m = re.match(r"\s*\S+\s+E\d+(?:\.\d+)?\s+(.*)", comment)
                group[1].append(dict(seq=seq, status=status.strip(), name=m.group(1).strip() if m else ""))
    return groups


def seq_str(seq):
    return "".join(chr(c) for c in seq)


def strip_vs(seq):
    return tuple(c for c in seq if c != VS16)


def twemoji_name(seq):
    return "-".join(f"{c:x}" for c in seq)


# ---------------------------------------------------------------- pixelizing

def pixelize(png_bytes):
    """72x72 RGBA PNG -> CELL x CELL list of rows of RGBA tuples (None = transparent)."""
    img = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    small = img.convert("RGBa").resize((ART, ART), Image.LANCZOS).convert("RGBA")
    px = small.load()
    for cut in (128, 80, 40):
        opaque = [(x, y) for y in range(ART) for x in range(ART) if px[x, y][3] >= cut]
        if len(opaque) >= 12:
            break
    # colours: the opaque pixels, reduced per emoji (median cut)
    strip = Image.new("RGB", (max(1, len(opaque)), 1))
    strip.putdata([px[x, y][:3] for x, y in opaque] or [(0, 0, 0)])
    q = strip.quantize(colors=COLORS, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    pal = q.getpalette()
    idx = list(q.get_flattened_data()) if hasattr(q, "get_flattened_data") else list(q.getdata())
    grid = [[None] * CELL for _ in range(CELL)]
    for (x, y), i in zip(opaque, idx):
        r, g, b = pal[3 * i:3 * i + 3]
        grid[y + 1][x + 1] = (r // 4 * 4, g // 4 * 4, b // 4 * 4, 255)     # 6 bit/channel: fewer palette entries
    # outline: transparent pixels next to the picture (4-neighbourhood, like Canvas.outline) - found first, set
    # after: a pixel marked during the scan counted as picture and the outline flooded to the right and bottom
    # edges, a dark box behind every emoji (report #64)
    edge = [(x, y) for y in range(CELL) for x in range(CELL) if grid[y][x] is None and any(
        0 <= x + dx < CELL and 0 <= y + dy < CELL and grid[y + dy][x + dx] is not None
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for x, y in edge:
        grid[y][x] = OUTLINE
    return grid


def grid_image(grid, scale=1):
    img = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, c in enumerate(row):
            if c is not None:
                img.putpixel((x, y), c)
    return img if scale == 1 else img.resize((CELL * scale, CELL * scale), Image.NEAREST)


def rects(grid, color):
    """Maximal rectangles (x, y, w, h) of one colour: horizontal runs, merged downwards when equal."""
    runs = []
    for y, row in enumerate(grid):
        x = 0
        while x < CELL:
            if row[x] == color:
                x1 = x
                while x1 + 1 < CELL and row[x1 + 1] == color:
                    x1 += 1
                runs.append([x, y, x1 - x + 1, 1])
                x = x1 + 1
            else:
                x += 1
    merged = []
    for r in runs:
        for m in merged:
            if m[0] == r[0] and m[2] == r[2] and m[1] + m[3] == r[1]:
                m[3] += 1
                break
        else:
            merged.append(r)
    return [tuple(m) for m in merged]


# ---------------------------------------------------------------- fonts

def glyph_name(seq):
    return "u" + "_".join(f"{c:04X}" for c in seq)


def empty_glyph():
    from fontTools.pens.ttGlyphPen import TTGlyphPen
    return TTGlyphPen(None).glyph()


def base_font(names, cmap, ligatures, advances, family):
    """A TTF skeleton (empty glyf) with the cmap + the ccmp ligatures; the colour tables come on top."""
    from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
    from fontTools.fontBuilder import FontBuilder

    fb = FontBuilder(UPEM, isTTF=True)
    fb.setupGlyphOrder(names)
    fb.setupCharacterMap(cmap)
    # the variation sequences (emoji + FE0F): without a format 14 subtable Android's font picker prefers the
    # system emoji font for "☺️" and for ZWJ sequences ending in FE0F (it splits them up)
    from fontTools.ttLib.tables._c_m_a_p import cmap_format_14
    uvs = cmap_format_14(14)
    uvs.platformID, uvs.platEncID, uvs.language = 0, 5, 0
    uvs.cmap = {}
    uvs.uvsDict = {VS16: [(cp, None) for cp in sorted(cmap) if not cmap[cp].startswith(".")]}
    fb.font["cmap"].tables.append(uvs)
    fb.setupGlyf({n: empty_glyph() for n in names})
    fb.setupHorizontalMetrics({n: (advances.get(n, 0), 0) for n in names})
    fb.setupHorizontalHeader(ascent=ASCENT, descent=DESCENT)
    fb.setupOS2(sTypoAscender=ASCENT, sTypoDescender=DESCENT, sTypoLineGap=0, usWinAscent=ASCENT, usWinDescent=-DESCENT,
                fsSelection=0x40 | 0x80, achVendID="MOMT")
    fb.setupNameTable(dict(familyName=family, styleName="Regular", fullName=family, psName=family.replace(" ", ""),
                           uniqueFontIdentifier=f"{family} {EMOJI_VERSION}", version=f"Version {EMOJI_VERSION}",
                           copyright="Pixel emojis after Twemoji (c) Twitter and contributors, CC-BY 4.0", licenseDescription="CC-BY 4.0"))
    fb.setupPost(keepGlyphNames=False)
    fb.setupHead(unitsPerEm=UPEM)
    if ligatures:
        lines = [f"sub {' '.join(parts)} by {lig};" for parts, lig in ligatures]
        fea = "languagesystem DFLT dflt;\nlanguagesystem latn dflt;\nfeature ccmp {\n" + "\n".join(lines) + "\n} ccmp;\n"
        addOpenTypeFeaturesFromString(fb.font, fea)
    return fb.font


def add_colr(font, grids):
    """COLRv0: every colour of a sprite is one glyf layer of rectangles."""
    from fontTools.colorLib.builder import buildCOLR, buildCPAL
    from fontTools.pens.ttGlyphPen import TTGlyphPen

    glyf, hmtx = font["glyf"], font["hmtx"]
    palette, color_index = [], {}
    layer_cache, layers = {}, {}
    for name, grid in grids.items():
        colors = OrderedDict()
        for row in grid:
            for c in row:
                if c is not None:
                    colors[c] = True
        entry = []
        for c in colors:
            if c not in color_index:
                color_index[c] = len(palette)
                palette.append((c[0] / 255, c[1] / 255, c[2] / 255, 1.0))
            key = tuple(rects(grid, c))
            lname = layer_cache.get(key)
            if lname is None:
                lname = f"L{len(layer_cache)}"
                layer_cache[key] = lname
                pen = TTGlyphPen(None)
                for x, y, w, h in key:
                    x0, x1 = X0 + x * PX, X0 + (x + w) * PX
                    y1, y0 = TOP - y * PX, TOP - (y + h) * PX
                    pen.moveTo((x0, y0)); pen.lineTo((x0, y1)); pen.lineTo((x1, y1)); pen.lineTo((x1, y0)); pen.closePath()
                g = pen.glyph()
                g.recalcBounds(glyf)
                glyf[lname] = g                                # glyf appends the name to the font's glyph order
                hmtx[lname] = (ADVANCE, g.xMin if key else 0)
            entry.append((lname, color_index[c]))
        layers[name] = entry
    font["CPAL"] = buildCPAL([palette])
    font["COLR"] = buildCOLR(layers)
    font["maxp"].recalc(font)


def add_cbdt(font, grids):
    """CBDT/CBLC: one strike of PNGs (format 17, small metrics) for the consecutive emoji glyphs."""
    from fontTools import ttLib
    from fontTools.ttLib.tables.BitmapGlyphMetrics import SmallGlyphMetrics
    from fontTools.ttLib.tables.C_B_D_T_ import cbdt_bitmap_format_17
    from fontTools.ttLib.tables.E_B_L_C_ import SbitLineMetrics, Strike, eblc_index_sub_table_1

    names = list(grids)
    gids = [font.getGlyphID(n) for n in names]
    assert gids == list(range(gids[0], gids[0] + len(gids))), "emoji glyphs must be consecutive"
    size = CELL * BITMAP_SCALE
    data, locations, offset = {}, [], 4
    for n in names:
        buf = io.BytesIO()
        grid_image(grids[n], BITMAP_SCALE).save(buf, "PNG", optimize=True)
        png = buf.getvalue()
        g = cbdt_bitmap_format_17(b"", None)
        g.metrics = SmallGlyphMetrics()
        g.metrics.width = g.metrics.height = size
        g.metrics.BearingX = X0 * PPEM // UPEM
        g.metrics.BearingY = TOP * PPEM // UPEM
        g.metrics.Advance = ADVANCE * PPEM // UPEM
        g.imageData = png
        data[n] = g
        locations.append((offset, offset + 9 + len(png)))
        offset += 9 + len(png)
    strike = Strike()
    bst = strike.bitmapSizeTable
    bst.startGlyphIndex, bst.endGlyphIndex = gids[0], gids[-1]
    bst.ppemX = bst.ppemY = PPEM
    bst.colorRef, bst.bitDepth, bst.flags = 0, 32, 1
    lm = SbitLineMetrics()
    for k in ("caretSlopeNumerator", "caretSlopeDenominator", "caretOffset", "minOriginSB", "minAdvanceSB",
              "maxBeforeBL", "minAfterBL", "pad1", "pad2"):
        setattr(lm, k, 0)
    lm.ascender = ASCENT * PPEM // UPEM
    lm.descender = DESCENT * PPEM // UPEM
    lm.widthMax = ADVANCE * PPEM // UPEM
    bst.hori = bst.vert = lm
    ist = eblc_index_sub_table_1(b"", font)
    ist.indexFormat, ist.imageFormat, ist.imageSize = 1, 17, size
    ist.names, ist.locations = names, locations
    strike.indexSubTables = [ist]
    cblc, cbdt = ttLib.newTable("CBLC"), ttLib.newTable("CBDT")
    cblc.version = cbdt.version = 3.0
    cblc.strikes, cbdt.strikeData = [strike], [data]
    font["CBLC"], font["CBDT"] = cblc, cbdt


# ---------------------------------------------------------------- main

def load_sprites(test):
    """{glyph name: grid} for every fully-qualified emoji with a Twemoji picture (cached in cache/sprites.pickle;
    delete it after changing the pixelizing)."""
    cache = os.path.join(CACHE, "sprites.pickle")
    if os.path.exists(cache):
        with open(cache, "rb") as f:
            return pickle.load(f)
    zf = zipfile.ZipFile(fetch(TWEMOJI_URL, f"twemoji-{TWEMOJI_VERSION}.zip"))
    pngs = {os.path.basename(n)[:-4]: n for n in zf.namelist() if "/assets/72x72/" in n and n.endswith(".png")}
    grids = OrderedDict()
    for _, entries in test:
        for e in entries:
            if e["status"] not in SPRITED:
                continue
            seq = e["seq"]
            name = twemoji_name(seq) if twemoji_name(seq) in pngs else twemoji_name(strip_vs(seq))
            if name in pngs:
                grids[glyph_name(seq)] = pixelize(zf.read(pngs[name]))
    with open(cache, "wb") as f:
        pickle.dump(grids, f)
    return grids


def main():
    test = parse_emoji_test(fetch(EMOJI_TEST_URL, "emoji-test.txt"))
    sprites = load_sprites(test)

    # fully-qualified emojis in order, with their other spellings; skin-tone variants under their base
    grids, groups, missing, ligatures, cmap = OrderedDict(), [], [], [], {}
    for gname, entries in test:
        full = [e for e in entries if e["status"] in SPRITED]
        others = [e for e in entries if e["status"] not in SPRITED]
        items, by_base = [], {}
        for e in full:
            seq = e["seq"]
            if glyph_name(seq) not in sprites:
                missing.append(e["name"])
                continue
            grids[glyph_name(seq)] = sprites[glyph_name(seq)]
            base = tuple(c for c in seq if c not in SKIN_TONES)
            if base != seq and base in by_base:
                by_base[base].setdefault("v", []).append(seq_str(seq))
            else:
                item = {"e": seq_str(seq), "n": e["name"]}
                items.append(item)
                by_base[base if base != seq else seq] = item
                if base != seq:
                    by_base[seq] = item
        groups.append({"name": gname, "emoji": items})
        # spellings: the sequence itself, the minimally-/unqualified ones from the file, and without any FE0F
        for e in full:
            g = glyph_name(e["seq"])
            if g not in grids:
                continue
            spellings = {e["seq"], strip_vs(e["seq"])}
            spellings |= {o["seq"] for o in others if strip_vs(o["seq"]) == strip_vs(e["seq"])}
            for s in spellings:
                if len(s) == 1:
                    cmap[s[0]] = g
                else:
                    ligatures.append((s, g))
    print(f"{len(grids)} emojis, {len(missing)} without a Twemoji picture: {', '.join(missing[:10])}")

    # the empty helper glyphs: selectors, joiner, keycap, tags, regional indicators, keycap bases
    helpers = {".vs16": [VS16], ".vs15": [VS15], ".zwj": [ZWJ], ".keycap": [KEYCAP]}
    helpers.update({f".tag{c:X}": [c] for c in range(0xE0020, 0xE0080)})
    helpers.update({f".ri{c:X}": [c] for c in range(0x1F1E6, 0x1F200)})
    helpers.update({f".key{c:X}": [c] for c in [ord(x) for x in "0123456789#*"]})
    helper_names = list(helpers)
    helper_cp = {c: n for n, cs in helpers.items() for c in cs}
    for cp, n in helper_cp.items():
        cmap.setdefault(cp, n)
    names = [".notdef"] + helper_names + list(grids)
    advances = {n: ADVANCE for n in grids}
    lig_names = []
    seen = set()
    for s, g in ligatures:
        parts = []
        for c in s:
            parts.append(cmap[c] if c in cmap else None)
        if None in parts or (tuple(parts), g) in seen:
            continue
        seen.add((tuple(parts), g))
        lig_names.append((parts, g))
    # a ligature's parts must not themselves be ligatures of the same lookup: fine, components are single glyphs

    os.makedirs(OUT, exist_ok=True)
    # sheet + preview, in the order of emoji.json (groups -> entry -> its variants)
    order = []
    for g in groups:
        for item in g["emoji"]:
            order.append(item["e"])
            order.extend(item.get("v", []))
    rows = (len(order) + SHEET_COLS - 1) // SHEET_COLS
    sheet = Image.new("RGBA", (SHEET_COLS * CELL, rows * CELL), (0, 0, 0, 0))
    for i, e in enumerate(order):
        sheet.paste(grid_image(grids[glyph_name(tuple(ord(c) for c in e))]), ((i % SHEET_COLS) * CELL, (i // SHEET_COLS) * CELL))
    sheet.save(os.path.join(OUT, "sheet.png"), optimize=True)
    sheet.crop((0, 0, SHEET_COLS * CELL, 8 * CELL)).resize((SHEET_COLS * CELL * 4, 8 * CELL * 4), Image.NEAREST).save(os.path.join(OUT, "_preview.png"))
    with open(os.path.join(OUT, "emoji.json"), "w", encoding="utf-8") as f:
        json.dump({"version": EMOJI_VERSION, "cell": CELL, "cols": SHEET_COLS, "groups": groups}, f, ensure_ascii=False, separators=(",", ":"))

    web = base_font(names, cmap, lig_names, advances, "Pixel Emoji")
    add_colr(web, grids)
    web.flavor = "woff2"
    web.save(os.path.join(OUT, "pixel_emoji.woff2"))
    android = base_font(names, cmap, lig_names, advances, "Pixel Emoji")
    add_cbdt(android, grids)
    android.save(ANDROID_FONT)
    for p in (os.path.join(OUT, "pixel_emoji.woff2"), ANDROID_FONT, os.path.join(OUT, "sheet.png"), os.path.join(OUT, "emoji.json")):
        print(f"{os.path.getsize(p) / 1024:7.0f} KB  {os.path.relpath(p, HERE)}")


if __name__ == "__main__":
    sys.exit(main())
