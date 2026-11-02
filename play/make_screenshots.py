"""Play Store screenshots: the emulator shots in play/raw (raw_<lang>_<screen>.png, 1080x2400) with a caption on
a pixel night sky, 1080x1920 (Play allows at most 2:1).
    .venv/Scripts/python play/make_screenshots.py      -> play/out/screens/<lang>/
"""
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
FONTS = ROOT / "web" / "public" / "fonts"
W, H = 1080, 1920

SCREENS = [   # file, (title, line) per language
    ("home", {"de": ("SAMMLE MOMENTUM", "Vom Fahrrad bis ins Raumschiff"),
              "en": ("BUILD MOMENTUM", "From a bicycle to a spaceship")}),
    ("rules", {"de": ("DEINE REGELN", "Ein Tap für alles, was dich voranbringt"),
               "en": ("YOUR RULES", "One tap for everything that moves you")}),
    ("feed", {"de": ("TEILE ERFOLGE", "Feed, Stories und Beiträge"),
              "en": ("SHARE YOUR WINS", "Feed, stories and posts")}),
    ("group", {"de": ("GEMEINSAM", "Verschlüsselte Chats und Gruppen"),
               "en": ("TOGETHER", "Encrypted chats and groups")}),
    ("ranking", {"de": ("RANGLISTEN", "Weltweit, mit Freunden, in Gruppen"),
                 "en": ("LEADERBOARDS", "Global, friends and groups")}),
    ("profile", {"de": ("DEIN PROFIL", "Fahrzeuge, Garage und Verlauf"),
                 "en": ("YOUR PROFILE", "Vehicles, garage and history")}),
]


def background():
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    bands = ["#07080f", "#0d0f1d", "#141832", "#1c2246", "#232a4f"]
    for i, c in enumerate(bands):
        d.rectangle([0, i * H // len(bands), W, (i + 1) * H // len(bands)], fill=c)
    rnd = random.Random(3)
    for _ in range(140):                                  # pixel stars
        x, y, s = rnd.randrange(W), rnd.randrange(H), rnd.choice([3, 3, 6])
        d.rectangle([x, y, x + s, y + s], fill=rnd.choice(["#f4f4f4", "#94b0c2", "#ffcd75"]))
    return img


def frame(shot):
    """The screenshot, scaled to fit under the caption, with rounded phone-like corners and a rim."""
    h = 1450
    w = round(shot.width * h / shot.height)
    shot = shot.convert("RGB").resize((w, h), Image.LANCZOS)
    rim, r = 10, 48
    out = Image.new("RGBA", (w + 2 * rim, h + 2 * rim), (0, 0, 0, 0))
    ImageDraw.Draw(out).rounded_rectangle([0, 0, out.width - 1, out.height - 1], r + rim, fill="#ffcd75")
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255)
    out.paste(shot, (rim, rim), mask)
    return out


def main():
    title_font = ImageFont.truetype(str(FONTS / "silkscreen_bold.ttf"), 76)
    line_font = ImageFont.truetype(str(FONTS / "pixelify_semibold.ttf"), 46)
    for lang in ("de", "en"):
        out_dir = HERE / "out" / "screens" / lang
        out_dir.mkdir(parents=True, exist_ok=True)
        for i, (name, texts) in enumerate(SCREENS, start=1):
            title, line = texts[lang]
            img = background()
            d = ImageDraw.Draw(img)
            for text, font, y, colour in ((title, title_font, 120, "#f4f4f4"), (line, line_font, 240, "#ffcd75")):
                tw = d.textlength(text, font=font)
                if colour == "#f4f4f4":
                    d.text(((W - tw) / 2 + 6, y + 6), text, font=font, fill="#000000")     # pixel drop shadow
                d.text(((W - tw) / 2, y), text, font=font, fill=colour)
            phone = frame(Image.open(HERE / "raw" / f"raw_{lang}_{name}.png"))
            img.paste(phone, ((W - phone.width) // 2, 360), phone)
            img.save(out_dir / f"{i:02d}_{name}.png")
    print("written to", HERE / "out" / "screens")


if __name__ == "__main__":
    main()
