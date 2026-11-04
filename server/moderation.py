"""Content filter for the photos and videos of posts, stories and profile / group pictures.

A local image model (server/nsfw_model.py) gives every uploaded photo / video a score 0..1 for nudity or sexual
content (`Media.nsfw`; a video: the highest of one frame every FRAME_SECONDS). It runs on the server's CPU,
nothing is sent anywhere. Without the model (file missing, tests) nothing is scored and nothing filtered; an error
while scoring lets the upload through (logged).

- score >= BLOCK: a post / story with it is refused (`blocked_error`); profile and group pictures already from
  FLAG on (they have no cover).
- FLAG <= score < BLOCK: posted, but behind a pixelated cover (`Media.cover_key`, made from the thumbnail when it
  is attached): `media_json` sends `sensitive` + `cover`, the thumbnail is the cover, the apps show the media
  only after a tap. It waits in the admin app's review list (`Media.review` ""): "ok" = shown normally,
  "keep" = stays covered, or the post / story is removed.
- Android apps older than `clients.MODERATION_APP` can't cover: they get the cover instead of the media.

Not filtered: chat photos (private; encrypted ones can't be read anyway) and texts.
"""
import io
import logging
import subprocess
import tempfile
import threading
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter

from . import nsfw_model

FLAG = 0.5
BLOCK = 0.95
FRAME_SECONDS = 2          # a video: one frame every 2 s ...
MAX_FRAMES = 30            # ... at most 30 (the 60 s maximum)
SIZE = 224                 # the model's input
COVER_CELLS = 12           # the cover: this many pixel blocks on the long side
COVER_SIDE = 480

log = logging.getLogger(__name__)


class OnnxModel:
    """The real model, loaded on first use (about 150 MB of memory). One picture batch at a time: the server
    has two cores."""

    def __init__(self, path):
        self.path, self.session, self.lock = Path(path), None, threading.Lock()

    def __call__(self, frames):
        import numpy as np
        with self.lock:
            if self.session is None:
                import onnxruntime as ort
                opts = ort.SessionOptions()
                opts.intra_op_num_threads, opts.inter_op_num_threads = 2, 1
                self.session = ort.InferenceSession(str(self.path), opts, providers=["CPUExecutionProvider"])
            # ViT preprocessing: 224x224 RGB, (x / 255 - 0.5) / 0.5, channels first
            x = (frames.astype(np.float32) / 127.5 - 1.0).transpose(0, 3, 1, 2)
            scores = []
            for i in range(0, len(x), 8):
                logits = self.session.run(None, {"pixel_values": x[i:i + 8]})[0]
                e = np.exp(logits - logits.max(axis=1, keepdims=True))
                scores += list((e[:, 1] / e.sum(axis=1)).astype(float))     # label 1 = nsfw
            return scores


class ColorModel:
    """Tests and the e2e server: a frame whose middle is cyan scores 0.99 (refused), magenta 0.7 (covered),
    anything else 0.01 (the other specs' red / noise / test-pattern pictures stay clean)."""

    def __call__(self, frames):
        out = []
        for f in frames:
            r, g, b = (int(v) for v in f[SIZE // 2 - 16:SIZE // 2 + 16, SIZE // 2 - 16:SIZE // 2 + 16].reshape(-1, 3).mean(axis=0))
            out.append(0.99 if r < 60 and g > 200 and b > 200 else 0.7 if r > 200 and g < 60 and b > 200 else 0.01)
        return out


class Moderator:
    def __init__(self, model=None):
        self.model = model

    @classmethod
    def from_env(cls):
        path = nsfw_model.path()
        try:
            import numpy  # noqa: F401
            import onnxruntime  # noqa: F401
        except ImportError:
            log.warning("content filter off (onnxruntime / numpy missing)")
            return cls()
        if not path.exists():
            log.warning("content filter off (no model at %s - python server/nsfw_model.py)", path)
            return cls()
        return cls(OnnxModel(path))

    @property
    def enabled(self):
        return self.model is not None

    def score(self, processed):
        """The score of a processed upload (server/media.py), None for audio / without the model. Runs in a
        worker thread (it takes a moment, a video's frames longer)."""
        if self.model is None or processed.kind not in ("image", "video"):
            return None
        try:
            frames = video_frames(processed.data) if processed.kind == "video" else image_frames(processed.thumb or processed.data)
            return float(max(self.model(frames))) if len(frames) else None
        except Exception:
            log.exception("content filter failed - the upload goes through unchecked")
            return None

    def score_image(self, raw):
        """A picture's score (profile pictures, which don't go through /api/media)."""
        if self.model is None:
            return None
        try:
            return float(max(self.model(image_frames(raw))))
        except Exception:
            log.exception("content filter failed - the picture goes through unchecked")
            return None


def image_frames(raw):
    import numpy as np
    with Image.open(io.BytesIO(raw)) as img:
        rgb = img.convert("RGB").resize((SIZE, SIZE), Image.BILINEAR)
    return np.asarray(rgb, dtype=np.uint8)[None]


def video_frames(raw):
    import numpy as np
    from .media import _ffmpeg
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp, "in.mp4")
        src.write_bytes(raw)
        run = subprocess.run(
            [_ffmpeg(), "-v", "error", "-i", str(src), "-an",
             "-vf", f"fps=1/{FRAME_SECONDS},scale={SIZE}:{SIZE}:flags=bilinear", "-frames:v", str(MAX_FRAMES),
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            capture_output=True, timeout=120)
    n = len(run.stdout) // (SIZE * SIZE * 3)
    return np.frombuffer(run.stdout[:n * SIZE * SIZE * 3], dtype=np.uint8).reshape(n, SIZE, SIZE, 3)


def blocked(md):
    return md.nsfw is not None and md.nsfw >= BLOCK


def flagged(md):
    return md.nsfw is not None and md.nsfw >= FLAG


def covered(md):
    """Shown behind its cover: flagged when it was posted and not cleared by an admin."""
    return md.cover_key is not None and md.review != "ok"


def blocked_error(api_error, picture=False):
    if picture:
        return api_error("Dieses Bild ist nicht erlaubt: Es zeigt wahrscheinlich Nacktheit oder sexuelle Inhalte.",
                         "This picture isn't allowed: it probably shows nudity or sexual content.", 422)
    return api_error("Nicht erlaubt: Ein Foto oder Video zeigt wahrscheinlich Nacktheit oder sexuelle Inhalte.",
                     "Not allowed: a photo or video probably shows nudity or sexual content.", 422)


def make_cover(raw):
    """A pixel mosaic of the thumbnail: blurred, COVER_CELLS blocks on the long side, darker (the apps write on
    it), scaled up without smoothing so every client shows the same blocks."""
    with Image.open(io.BytesIO(raw)) as img:
        img = img.convert("RGB")
    w, h = img.size
    img = img.filter(ImageFilter.GaussianBlur(max(w, h) / 30))
    scale = COVER_CELLS / max(w, h)
    small = img.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.BILINEAR)
    small = ImageEnhance.Brightness(small).enhance(0.55)
    up = COVER_SIDE / max(small.size)
    big = small.resize((max(1, round(small.width * up)), max(1, round(small.height * up))), Image.NEAREST)
    buf = io.BytesIO()
    big.save(buf, "WEBP", lossless=True)
    return buf.getvalue()


async def cover_flagged(blobs, media_rows):
    """When flagged media is posted: its cover (from the thumbnail; a picture without one: from itself)."""
    import asyncio

    from .blobs import BlobUnavailable
    for md in media_rows:
        if not flagged(md) or md.cover_key:
            continue
        try:
            obj = await blobs.get(md.thumb_key or md.key)
            if obj is None:
                continue
            data = await asyncio.to_thread(make_cover, obj[0])
            key = md.key.rsplit(".", 1)[0] + "_cover.webp"
            await blobs.put(key, data, "image/webp")
        except (BlobUnavailable, OSError, ValueError):
            log.exception("cover for media %s failed", md.id)
            continue
        md.cover_key = key
