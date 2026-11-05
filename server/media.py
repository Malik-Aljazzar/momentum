"""Processing of uploaded photos, short videos (posts, chat) and voice messages (chat).

Photos: EXIF-rotated, longest side <= 1080 px, WebP, plus a 480 px WebP thumbnail (lists, grids, chats).
Videos: H.264/AAC MP4, shorter side <= 720 px, at most 30 fps and VIDEO_MAX_SECONDS
(longer clips are trimmed), plus a JPEG poster frame. Post and story videos get an adaptive HEVC stream
afterwards (server/hls.py).
Voice messages and post sounds: mono AAC (.m4a), at most AUDIO_MAX_SECONDS (a post sound is a
chosen part: from `start`, at most POST_SOUND_SECONDS).
Uses the ffmpeg binary bundled with imageio-ffmpeg.
"""
import io
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError

from .images import InvalidImage

IMAGE_MAX_SIDE = 1080
THUMB_SIDE = 480
VIDEO_MAX_SECONDS = 60
VIDEO_SHORT_SIDE = 720
AUDIO_MAX_SECONDS = 300
POST_SOUND_SECONDS = 30


class InvalidMedia(Exception):
    pass


@dataclass
class Processed:
    kind: str            # image | video | audio
    data: bytes
    content_type: str
    ext: str
    width: int
    height: int
    duration: float = 0.0
    thumb: bytes | None = None


def process_image(raw: bytes) -> Processed:
    try:
        img = ImageOps.exif_transpose(Image.open(io.BytesIO(raw)))
        img.load()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError, InvalidImage) as e:
        raise InvalidMedia(str(e)) from e
    img = img.convert("RGBA" if img.mode in ("RGBA", "LA", "P") else "RGB")
    img.thumbnail((IMAGE_MAX_SIDE, IMAGE_MAX_SIDE), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "WEBP", quality=78, method=5)
    small = img.copy()
    small.thumbnail((THUMB_SIDE, THUMB_SIDE), Image.LANCZOS)
    tbuf = io.BytesIO()
    small.save(tbuf, "WEBP", quality=70, method=5)
    return Processed("image", buf.getvalue(), "image/webp", "webp", img.width, img.height, thumb=tbuf.getvalue())


def _ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def process_video(raw: bytes) -> Processed:
    with tempfile.TemporaryDirectory() as tmp:
        src, out, poster = Path(tmp, "in"), Path(tmp, "out.mp4"), Path(tmp, "poster.jpg")
        src.write_bytes(raw)
        scale = (f"scale='if(gt(iw,ih),-2,min({VIDEO_SHORT_SIDE},iw))':"
                 f"'if(gt(iw,ih),min({VIDEO_SHORT_SIDE},ih),-2)'")
        run = subprocess.run(
            # the quick copy that plays everywhere; posts' and stories' adaptive streams follow (server/hls.py)
            [_ffmpeg(), "-y", "-i", str(src), "-t", str(VIDEO_MAX_SECONDS), "-vf", scale, "-fpsmax", "30",
             "-c:v", "libx264", "-preset", "medium", "-crf", "28", "-maxrate", "1500k", "-bufsize", "3000k",
             "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", str(out)],
            capture_output=True, text=True, timeout=300)
        if run.returncode != 0 or not out.exists():
            raise InvalidMedia(run.stderr[-500:])
        m = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", run.stderr)
        duration = min(VIDEO_MAX_SECONDS, int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3])) if m else 0.0
        subprocess.run([_ffmpeg(), "-y", "-i", str(out), "-frames:v", "1", "-q:v", "4", str(poster)],
                       capture_output=True, timeout=60)
        thumb = poster.read_bytes() if poster.exists() else None
        w = h = 0
        if thumb:
            with Image.open(io.BytesIO(thumb)) as im:
                w, h = im.size
        return Processed("video", out.read_bytes(), "video/mp4", "mp4", w, h, duration, thumb)


def _duration(stderr, cap):
    m = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", stderr)
    return min(cap, int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3])) if m else 0.0


def process_audio(raw: bytes, start: float = 0.0, seconds: float = AUDIO_MAX_SECONDS) -> Processed:
    seconds = max(1.0, min(seconds, AUDIO_MAX_SECONDS))
    with tempfile.TemporaryDirectory() as tmp:
        src, out = Path(tmp, "in"), Path(tmp, "out.m4a")
        src.write_bytes(raw)
        run = subprocess.run(
            [_ffmpeg(), "-y", "-ss", f"{max(0.0, start):.2f}", "-i", str(src), "-t", f"{seconds:.2f}", "-vn", "-ac", "1",
             "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", str(out)],
            capture_output=True, text=True, timeout=120)
        if run.returncode != 0 or not out.exists() or out.stat().st_size < 200:
            raise InvalidMedia(run.stderr[-500:])
        # duration of the result (the input's is printed first)
        probe = subprocess.run([_ffmpeg(), "-i", str(out)], capture_output=True, text=True, timeout=30)
        return Processed("audio", out.read_bytes(), "audio/mp4", "m4a", 0, 0, _duration(probe.stderr, seconds))


# still images in an ISO container (HEIC/AVIF) - everything else with "ftyp" is a video
_IMAGE_BRANDS = {b"heic", b"heix", b"hevc", b"heim", b"heis", b"mif1", b"msf1", b"avif", b"avis"}


def looks_like_video(raw: bytes) -> bool:
    """MP4/MOV (ftyp box) or WebM/MKV (EBML header), whatever type the client claimed."""
    if raw[4:8] == b"ftyp":
        return raw[8:12] not in _IMAGE_BRANDS
    return raw[:4] == b"\x1a\x45\xdf\xa3"


def process(raw: bytes, content_type: str, start: float = 0.0, seconds: float = AUDIO_MAX_SECONDS) -> Processed:
    # a video sent with a wrong type (older Android apps sent in-app camera clips as image/jpeg)
    if not content_type.startswith(("audio/", "video/")) and looks_like_video(raw):
        return process_video(raw)
    if content_type.startswith("audio/"):
        return process_audio(raw, start, seconds)
    if content_type.startswith("video/"):
        return process_video(raw)
    if content_type.startswith("image/"):
        return process_image(raw)
    raise InvalidMedia(f"unsupported type {content_type}")
