"""Text written onto a photo / video is burned into the picture when the post is made, so it looks
the same on every device (before, each client drew it with its own fonts and sizes).

Layout as in the clients' editor preview (Overlay.kt / video.tsx): centred at (x, y) of the picture,
font size = width x size, wrapped at 90 % of the width, line height 1.1, a soft shadow (a light glow
for black text), kept inside the picture. Font: Pixelify Sans Bold, which the preview uses too; emojis are
the pixel sprites (server/emoji.py), the same the apps' fonts show.
"""
import io
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from . import emoji

FONT = Path(__file__).parent / "fonts" / "pixelify_bold.ttf"
COLORS = {"white": (255, 255, 255), "yellow": (0xFF, 0xCD, 0x75), "cyan": (0x73, 0xEF, 0xF7),
          "pink": (0xFF, 0x8A, 0xD8), "lime": (0xA7, 0xF0, 0x70), "black": (0x1A, 0x1C, 0x2C)}
THUMB_SIDE = 480


def _width(draw, text, font, size):
    """Width of a line: the font's text plus the emoji sprites (1.1875 em each)."""
    return sum(emoji.EM_ADVANCE * size if is_emoji else draw.textlength(part, font=font) for is_emoji, part in emoji.split(text))


def _draw(text_img, mask, x, y_center, line, font, size, color):
    """One line at (x, y_center): text through the font, emojis pasted from the sheet (nearest neighbour)."""
    td, md = ImageDraw.Draw(text_img), ImageDraw.Draw(mask)
    ascent, descent = font.getmetrics()
    baseline = y_center + (ascent - descent) / 2            # Pillow's "m" anchor = between ascender and descender
    for is_emoji, part in emoji.split(line):
        if not is_emoji:
            td.text((x, y_center), part, font=font, fill=color + (255,), anchor="lm")
            md.text((x, y_center), part, font=font, fill=255, anchor="lm")
            x += td.textlength(part, font=font)
            continue
        side = max(1, round(emoji.EM_WIDTH * size))
        sprite = emoji.sprite(part).resize((side, side), Image.NEAREST)
        at = (round(x), round(baseline - emoji.EM_ABOVE * size))
        text_img.alpha_composite(sprite, at)
        mask.paste(sprite.getchannel("A"), at, sprite.getchannel("A"))
        x += emoji.EM_ADVANCE * size


def _wrap(draw, text, font, max_w, size):
    lines = []
    for para in text.split("\n"):
        line = ""
        for word in para.split(" "):
            cand = word if not line else f"{line} {word}"
            if _width(draw, cand, font, size) <= max_w:
                line = cand
                continue
            if line:
                lines.append(line)
            # a word wider than the picture breaks anywhere
            while len(word) > 1 and _width(draw, word, font, size) > max_w:
                cut = len(word) - 1
                while cut > 1 and _width(draw, word[:cut], font, size) > max_w:
                    cut -= 1
                lines.append(word[:cut])
                word = word[cut:]
            line = word
        lines.append(line)
    return lines


def text_layer(o, w, h):
    """The overlay as a transparent RGBA picture of w x h."""
    size = max(8, round(w * o["size"]))
    font = ImageFont.truetype(str(FONT), size)
    text = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mask = Image.new("L", (w, h), 0)
    td = ImageDraw.Draw(text)
    lines = _wrap(td, o["text"], font, w * .9, size)
    line_h = size * 1.1
    widths = [_width(td, line, font, size) for line in lines]
    block_w, block_h = max(widths, default=0), line_h * len(lines)
    left = min(max(0.0, o["x"] * w - block_w / 2), max(0.0, w - block_w))
    top = min(max(0.0, o["y"] * h - block_h / 2), max(0.0, h - block_h))
    color = COLORS.get(o.get("color"), COLORS["white"])
    for i, (line, lw) in enumerate(zip(lines, widths)):
        _draw(text, mask, left + (block_w - lw) / 2, top + i * line_h + line_h / 2, line, font, size, color)
    # the shadow: the glyphs blurred, dark (light around black text)
    glow_rgb, strength = ((255, 255, 255), .55) if o.get("color") == "black" else ((0, 0, 0), .8)
    blurred = mask.filter(ImageFilter.GaussianBlur(max(1.0, w * .012)))
    shadow = Image.new("RGBA", (w, h), glow_rgb + (0,))
    shadow.putalpha(blurred.point(lambda a: min(255, int(a * strength * 1.6))))
    return Image.alpha_composite(shadow, text)


def bake_image(data, o):
    """Photo (WebP) with the text burned in -> (photo, thumbnail) as WebP."""
    img = Image.open(io.BytesIO(data)).convert("RGBA")
    img = Image.alpha_composite(img, text_layer(o, img.width, img.height)).convert("RGB")
    out = io.BytesIO()
    img.save(out, "WEBP", quality=82, method=5)
    small = img.copy()
    small.thumbnail((THUMB_SIDE, THUMB_SIDE), Image.LANCZOS)
    tout = io.BytesIO()
    small.save(tout, "WEBP", quality=72, method=5)
    return out.getvalue(), tout.getvalue()


def bake_video(data, o):
    """Video (MP4) with the text burned in -> (video, poster JPEG, width, height)."""
    from .media import _ffmpeg
    with tempfile.TemporaryDirectory() as tmp:
        src, layer, out, poster = (Path(tmp, n) for n in ("in.mp4", "text.png", "out.mp4", "poster.jpg"))
        src.write_bytes(data)
        probe = subprocess.run([_ffmpeg(), "-y", "-i", str(src), "-frames:v", "1", str(poster)], capture_output=True, timeout=60)
        if probe.returncode != 0 or not poster.exists():
            raise ValueError("no frame")
        with Image.open(poster) as frame:
            w, h = frame.size
        text_layer(o, w, h).save(layer)
        run = subprocess.run(
            [_ffmpeg(), "-y", "-i", str(src), "-i", str(layer), "-filter_complex", "[0:v][1:v]overlay=0:0",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-maxrate", "1500k", "-bufsize", "3000k",
             "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", str(out)],
            capture_output=True, text=True, timeout=300)
        if run.returncode != 0 or not out.exists():
            raise ValueError(run.stderr[-300:])
        poster.unlink()
        subprocess.run([_ffmpeg(), "-y", "-i", str(out), "-frames:v", "1", "-q:v", "4", str(poster)], capture_output=True, timeout=60)
        return out.read_bytes(), poster.read_bytes() if poster.exists() else None, w, h
