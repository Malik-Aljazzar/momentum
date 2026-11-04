"""Content filter (server/moderation.py): refused, covered and reviewed photos / videos."""
import asyncio
import io
import os
import subprocess
import tempfile

import imageio_ffmpeg
import pytest
from PIL import Image

from .. import moderation, nsfw_model
from ..app import create_app
from ..blobs import MemoryStore
from ..moderation import ColorModel, Moderator
from .test_admin import make_admin
from .test_community import friends
from .test_social import png, user

CYAN, MAGENTA, RED = (0, 255, 255), (255, 0, 255), (255, 0, 0)   # refused, covered, clean


def solid(color, w=640, h=480, fmt="PNG"):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, fmt)
    return buf.getvalue()


def color_video(color):
    """3 s clip of one colour (the stand-in model looks at the middle of every frame)."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "v.mp4")
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "lavfi", "-i", f"color=c=0x{bytes(color).hex()}:size=640x360:rate=25",
                        "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", out], capture_output=True, check=True)
        with open(out, "rb") as f:
            return f.read()


def run(tmp_path, scenario, moderator=None):
    async def go():
        app = create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(),
                         moderator=moderator or Moderator(ColorModel()))
        async with app.test_app() as ta:
            await scenario(app, ta.test_client())
    asyncio.run(go())


async def upload(who, data, ctype="image/png"):
    return (await who.req("post", "/api/media", data=data, ctype=ctype))["media"]


def test_posts_refuse_clear_cases_and_cover_doubtful_ones(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        # clear: refused, with a message that says why
        red = await upload(ann, solid(RED))                  # the other tests' everyday picture stays clean
        await ann.req("post", "/api/posts", json={"media_ids": [red["id"]]})
        clear = await upload(ann, solid(CYAN))
        r = await ann.req("post", "/api/posts", json={"media_ids": [clear["id"]], "text": "hi"}, status=422)
        assert "nudity" in r["error"]
        # doubtful: posted behind a cover (also its thumbnail); a clean picture next to it stays as it is
        doubt, clean = await upload(ann, solid(MAGENTA)), await upload(ann, png())
        assert "sensitive" not in doubt                     # covered only once it is posted
        post = (await ann.req("post", "/api/posts", json={"media_ids": [doubt["id"], clean["id"]]}))["post"]
        first, second = post["media"]
        assert first["sensitive"] and first["cover"].endswith("_cover.webp") and first["thumb"] == first["cover"]
        assert first["url"] == doubt["url"] and first["kind"] == "image"
        assert "sensitive" not in second and second["thumb"] == clean["thumb"]
        cover = await c.get(first["cover"])
        with Image.open(io.BytesIO(await cover.get_data())) as im:
            assert max(im.size) == moderation.COVER_SIDE
            assert len(im.getcolors(10_000)) <= moderation.COVER_CELLS ** 2    # big blocks, nothing more
        # others see it covered too, also in the profile grid
        feed = (await ben.req("get", "/api/feed"))["posts"]
        assert feed[0]["media"][0]["sensitive"]
        grid = (await ben.req("get", "/api/users/ann/posts"))["posts"]
        assert grid[0]["media"][0]["thumb"] == first["cover"]
        # an Android app from before the filter can't cover it: it gets only the cover
        r = await c.get(f"/api/posts/{post['id']}", headers={**ben.h, "X-App-Version": "896000"})
        md = (await r.get_json())["post"]["media"][0]
        assert md["url"] == md["cover"] and md["kind"] == "image"
    run(tmp_path, scenario)


def test_videos_are_checked_frame_by_frame(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        clear = await upload(ann, color_video(CYAN), "video/mp4")
        await ann.req("post", "/api/posts", json={"media_ids": [clear["id"]]}, status=422)
        doubt = await upload(ann, color_video(MAGENTA), "video/mp4")
        post = (await ann.req("post", "/api/posts", json={"media_ids": [doubt["id"]]}))["post"]
        md = post["media"][0]
        assert md["sensitive"] and md["kind"] == "video" and md["url"].endswith(".mp4")
        # an old Android app gets the cover as a picture (no video to play)
        r = await c.get(f"/api/posts/{post['id']}", headers={**ann.h, "X-App-Version": "896000"})
        old = (await r.get_json())["post"]["media"][0]
        assert old["kind"] == "image" and old["url"] == md["cover"]
    run(tmp_path, scenario)


def test_stories_profile_and_group_pictures(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        clear = await upload(ann, solid(CYAN))
        await ann.req("post", "/api/stories", json={"media_id": clear["id"]}, status=422)
        doubt = await upload(ann, solid(MAGENTA))
        st = (await ann.req("post", "/api/stories", json={"media_id": doubt["id"]}))["story"]
        assert st["media"]["sensitive"] and st["media"]["thumb"] == st["media"]["cover"]
        # a profile / group picture has no cover: doubtful ones are refused already
        r = await ann.req("post", "/api/settings/avatar", data=solid(MAGENTA, 300, 300), ctype="image/png", status=422)
        assert "picture" in r["error"]
        await ann.req("post", "/api/settings/avatar", data=png(300, 300), ctype="image/png")
        await friends(ann, ben)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Gym", "usernames": ["ben"]}))["conversation"]["id"]
        pic = await upload(ann, solid(MAGENTA))
        await ann.req("put", f"/api/conversations/{g}", json={"avatar_media": pic["id"]}, status=422)
        ok = await upload(ann, png())
        await ann.req("put", f"/api/conversations/{g}", json={"avatar_media": ok["id"]})
        # a chat photo isn't filtered (private)
        dm = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        photo = await upload(ann, solid(CYAN))
        await ann.req("post", f"/api/conversations/{dm}/messages", json={"media_id": photo["id"]})
    run(tmp_path, scenario)


def test_the_admins_review_list(tmp_path):
    async def scenario(app, c):
        ann, ben, boss = await user(c, "ann"), await user(c, "ben"), await user(c, "boss")
        admin = await make_admin(app, c, boss)
        posts = []
        for _ in range(3):
            md = await upload(ann, solid(MAGENTA))
            posts.append((await ann.req("post", "/api/posts", json={"media_ids": [md["id"]]}))["post"])
        st_md = await upload(ben, solid(MAGENTA))
        story = (await ben.req("post", "/api/stories", json={"media_id": st_md["id"]}))["story"]
        clean = await upload(ann, png())
        await ann.req("post", "/api/posts", json={"media_ids": [clean["id"]]})
        await ann.req("get", "/api/admin/flagged", status=401)                  # a game session isn't enough
        listed = await admin.req("get", "/api/admin/flagged")
        assert listed["counts"] == {"waiting": 4, "ok": 0, "keep": 0}
        items = listed["items"]
        assert [i["username"] for i in items] == ["ben", "ann", "ann", "ann"]   # newest first
        assert items[0]["story_id"] == story["id"] and items[1]["post_id"] == posts[2]["id"]
        assert items[1]["thumb"] != items[1]["cover"] and items[1]["score"] == 0.7   # the admin sees the real picture
        a, b, d = (p["media"][0]["id"] for p in posts)
        # ok: shown normally; keep: stays covered; both leave the waiting list
        await admin.req("put", f"/api/admin/flagged/{a}", json={"action": "ok"})
        await admin.req("put", f"/api/admin/flagged/{b}", json={"action": "keep"})
        await admin.req("put", f"/api/admin/flagged/{b}", json={"action": "nope"}, status=400)
        shown = (await ben.req("get", f"/api/posts/{posts[0]['id']}"))["post"]["media"][0]
        assert "sensitive" not in shown and not shown["thumb"].endswith("_cover.webp")
        assert (await ben.req("get", f"/api/posts/{posts[1]['id']}"))["post"]["media"][0]["sensitive"]
        assert (await admin.req("get", "/api/admin/flagged"))["counts"] == {"waiting": 2, "ok": 1, "keep": 1}
        assert [i["media_id"] for i in (await admin.req("get", "/api/admin/flagged?review=keep"))["items"]] == [b]
        # remove: the post / story is deleted, with its files
        cover = posts[2]["media"][0]["cover"]
        await admin.req("put", f"/api/admin/flagged/{d}", json={"action": "remove"})
        await ben.req("get", f"/api/posts/{posts[2]['id']}", status=404)
        assert (await c.get(cover)).status_code == 404
        await admin.req("put", f"/api/admin/flagged/{st_md['id']}", json={"action": "remove"})
        await ann.req("get", f"/api/stories/{story['id']}", status=404)
        assert (await admin.req("get", "/api/admin/flagged"))["counts"]["waiting"] == 0
        await admin.req("put", f"/api/admin/flagged/{clean['id']}", json={"action": "ok"}, status=404)   # never flagged
    run(tmp_path, scenario)


def test_without_the_model_nothing_is_filtered(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        clear = await upload(ann, solid(CYAN))
        post = (await ann.req("post", "/api/posts", json={"media_ids": [clear["id"]]}))["post"]
        assert "sensitive" not in post["media"][0]
        await ann.req("post", "/api/settings/avatar", data=solid(CYAN, 300, 300), ctype="image/png")
    run(tmp_path, scenario, Moderator())


def test_a_failing_model_lets_uploads_through(tmp_path):
    def broken(frames):
        raise RuntimeError("model crashed")

    async def scenario(app, c):
        ann = await user(c, "ann")
        md = await upload(ann, solid(CYAN))
        await ann.req("post", "/api/posts", json={"media_ids": [md["id"]]})
    run(tmp_path, scenario, Moderator(broken))


@pytest.mark.skipif(not nsfw_model.path().exists(), reason="model not downloaded (python server/nsfw_model.py)")
def test_the_real_model_passes_everyday_pictures():
    """The real model on harmless pictures: low scores (a sanity check of the preprocessing)."""
    m = Moderator.from_env()
    assert m.enabled
    for raw in (solid((40, 120, 200)), png(400, 300), solid((230, 190, 160))):       # sky, noise, a skin tone
        score = m.score_image(raw)
        assert score is not None and score < moderation.FLAG, score
    frames = moderation.video_frames(color_video((30, 160, 60)))
    assert len(frames) == 2 and frames.shape[1:] == (224, 224, 3)                     # one frame every 2 s
