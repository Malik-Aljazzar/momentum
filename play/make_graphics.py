"""Play Store graphics from the pixel art (pixelart/out): the 512 px icon and the 1024x500 feature graphic.
    .venv/Scripts/python play/make_graphics.py      -> play/out/
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "pixelart" / "out"
FONTS = ROOT / "web" / "public" / "fonts"
OUT = Path(__file__).resolve().parent / "out"


def icon():
    """512x512, full bleed (Play rounds the corners itself): the 504 px icon on its own background colour."""
    art = Image.open(ART / "app_icon_512.png").convert("RGBA")
    bg = art.getpixel((0, 0))
    out = Image.new("RGBA", (512, 512), bg)
    out.alpha_composite(art, ((512 - art.width) // 2, (512 - art.height) // 2))
    out.convert("RGB").save(OUT / "icon_512.png")


def strip(img, layer, scale, bottom):
    """A banner layer, scaled up without smoothing and repeated across the width."""
    part = Image.open(ART / "banners" / layer).convert("RGBA")
    part = part.resize((part.width * scale, part.height * scale), Image.NEAREST)
    for x in range(0, img.width, part.width):
        img.alpha_composite(part, (x, bottom - part.height))


def feature():
    w, h, s = 1024, 500, 5
    img = Image.new("RGBA", (w, h))
    # the evening sky of the start screen (landing.html .sky-land)
    bands = [(0, "#232a4f"), (.22, "#2e2b55"), (.36, "#48295a"), (.48, "#6f2f57"), (.58, "#96435a"), (.66, "#b86455")]
    d = ImageDraw.Draw(img)
    for i, (start, colour) in enumerate(bands):
        end = bands[i + 1][0] if i + 1 < len(bands) else 1
        d.rectangle([0, int(start * h), w, int(end * h)], fill=colour)
    road_top = h - 110
    strip(img, "city_far.png", s, road_top)
    strip(img, "city_near.png", s, road_top)
    d.rectangle([0, road_top, w, h], fill="#1a1c2c")
    d.rectangle([0, road_top, w, road_top + 6], fill="#2a2f45")
    for x in range(0, w, 40):
        d.rectangle([x, road_top + 40, x + 20, road_top + 45], fill="#b39a6a")
    bike = Image.open(ART / "fahrrad" / "frame_0.png").convert("RGBA")
    bike = bike.resize((bike.width * s, bike.height * s), Image.NEAREST)
    img.alpha_composite(bike, (w - bike.width - 70, road_top - bike.height + 18))
    title = ImageFont.truetype(str(FONTS / "silkscreen_bold.ttf"), 96)
    tag = ImageFont.truetype(str(FONTS / "pixelify_semibold.ttf"), 34)
    d.text((56, 70), "MOMENTUM", font=title, fill="#000000")       # pixel drop shadow
    d.text((50, 64), "MOMENTUM", font=title, fill="#f4f4f4")
    d.text((54, 190), "Gewohnheiten · Pixel · Momentum", font=tag, fill="#ffcd75")
    img.convert("RGB").save(OUT / "feature_1024x500.png")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    icon()
    feature()
    print("written to", OUT)
