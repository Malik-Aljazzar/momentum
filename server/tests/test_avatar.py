import asyncio
import io
import os

from PIL import Image

from server.images import MAX_BYTES, compress_avatar
from .test_api import make_app, register


def noise_jpeg(w, h):
    """Worst case for compression: random pixels."""
    img = Image.frombytes("RGB", (w, h), os.urandom(w * h * 3))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=95)
    return buf.getvalue()


def test_compress_noise_photo_below_limit():
    raw = noise_jpeg(2400, 1600)
    assert len(raw) > 2 * 1024 * 1024
    out = compress_avatar(raw)
    assert len(out) < MAX_BYTES
    img = Image.open(io.BytesIO(out))
    assert img.format == "WEBP" and img.width == img.height


def test_upload_replace_serve_delete(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            r = await c.post("/api/settings/avatar", headers=h, data=noise_jpeg(1200, 900))
            first = (await r.get_json())["state"]["user"]["avatar"]
            assert first.startswith("/avatars/") and first.endswith(".webp")

            r = await c.get(first)
            assert r.status_code == 200 and r.content_type == "image/webp"
            assert len(await r.get_data()) < MAX_BYTES

            r = await c.post("/api/settings/avatar", headers=h, data=noise_jpeg(300, 300))
            second = (await r.get_json())["state"]["user"]["avatar"]
            assert second != first
            assert (await c.get(first)).status_code == 404   # old blob removed
            assert len(app.blobs.objects) == 1

            r = await c.delete("/api/settings/avatar", headers=h)
            assert (await r.get_json())["state"]["user"]["avatar"] is None
            assert app.blobs.objects == {}
    asyncio.run(go())


def test_upload_rejects_non_images(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            r = await c.post("/api/settings/avatar", headers={**h, "Accept-Language": "en"}, data=b"not an image")
            assert r.status_code == 400
            assert (await r.get_json())["error"] == "Could not read the image"
    asyncio.run(go())
