"""Pixel emojis (made by pixelart/emoji.py): which strings are emojis (rules may use one as their icon)
and their 18x18 sprites (burned into photo / video overlays, where the apps' fonts aren't available).

Both apps render emojis with the generated fonts; here only the sheet is needed. Variation selectors
(FE0F) don't matter: "❤" and "❤️" are the same emoji.
"""
import json
from functools import lru_cache
from pathlib import Path

from PIL import Image

DIR = Path(__file__).resolve().parent.parent / "pixelart" / "out" / "emoji"
VS16 = "️"
MAX_CODEPOINTS = 10          # the longest sequences (families, flags of tags)


def strip(s):
    return s.replace(VS16, "")


@lru_cache(maxsize=None)
def _index():
    """{emoji without FE0F: position on the sheet} in the order of emoji.json (groups, entry, its variants)."""
    try:
        with open(DIR / "emoji.json", encoding="utf-8") as f:
            data = json.load(f)
    except OSError:
        return {}, 18, 64
    index = {}
    for g in data["groups"]:
        for item in g["emoji"]:
            for e in [item["e"], *item.get("v", [])]:
                index.setdefault(strip(e), len(index))
    return index, data["cell"], data["cols"]


def is_emoji(s):
    """True for exactly one emoji (any spelling with / without FE0F)."""
    return bool(s) and strip(s) in _index()[0]


@lru_cache(maxsize=None)
def _sheet():
    return Image.open(DIR / "sheet.png").convert("RGBA")


def sprite(e):
    """The 18x18 RGBA sprite of an emoji (None if unknown)."""
    index, cell, cols = _index()
    i = index.get(strip(e))
    if i is None:
        return None
    x, y = (i % cols) * cell, (i // cols) * cell
    return _sheet().crop((x, y, x + cell, y + cell))


def split(text):
    """[(is_emoji, part)]: the text cut into emojis (longest match) and the plain text between them."""
    index = _index()[0]
    if not index:
        return [(False, text)]
    cps = [c for c in text]
    out, plain, i = [], "", 0
    while i < len(cps):
        hit = None
        for n in range(min(MAX_CODEPOINTS, len(cps) - i), 0, -1):
            cand = "".join(cps[i:i + n])
            if strip(cand) in index and strip(cand):
                hit = cand, n
                break
        if hit is None:
            plain += cps[i]
            i += 1
            continue
        if plain:
            out.append((False, plain))
            plain = ""
        out.append((True, hit[0]))
        i += hit[1]
    if plain:
        out.append((False, plain))
    return out


# the fonts' geometry (pixelart/emoji.py): 1 em = 1600 units, the sprite is 1800 wide and sits from -400 to 1400
EM_WIDTH = 1800 / 1600       # sprite width / height in em
EM_ADVANCE = 1900 / 1600
EM_ABOVE = 1400 / 1600       # sprite top above the baseline
