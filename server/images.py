"""Profile picture processing: square, at most 512px, WebP below MAX_BYTES."""
import io

from PIL import Image, ImageOps, UnidentifiedImageError

MAX_BYTES = 100 * 1024
MAX_SIDE = 512
MIN_SIDE = 64
Image.MAX_IMAGE_PIXELS = 40_000_000   # reject decompression bombs early


class InvalidImage(Exception):
    pass


def compress_avatar(raw: bytes) -> bytes:
    """Return a WebP image < MAX_BYTES. The client crops; we center-crop as a safety net."""
    try:
        img = Image.open(io.BytesIO(raw))
        img = ImageOps.exif_transpose(img)
        img.load()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as e:
        raise InvalidImage(str(e)) from e

    img = img.convert("RGBA" if img.mode in ("RGBA", "LA", "P") else "RGB")
    side = min(img.size)
    left, top = (img.width - side) // 2, (img.height - side) // 2
    img = img.crop((left, top, left + side, top + side))

    size = min(side, MAX_SIDE)
    while True:
        frame = img.resize((size, size), Image.LANCZOS) if size != img.width else img
        for quality in (85, 75, 65, 55, 45):
            buf = io.BytesIO()
            frame.save(buf, "WEBP", quality=quality, method=6)
            if buf.tell() < MAX_BYTES:
                return buf.getvalue()
        if size <= MIN_SIDE:
            return buf.getvalue()   # noise images: smallest attempt, still tiny at 64px
        size = max(MIN_SIDE, int(size * 0.75))
