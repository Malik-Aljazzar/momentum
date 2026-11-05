"""Videos in chats: plain chats like posts (the server re-encodes them), encrypted ones shrunk and sealed by the
apps; both expire like chat photos (only the still stays), old Android apps get the still as a photo."""
import asyncio
import os
from datetime import timedelta

from .. import chat, e2e
from ..app import create_app
from ..blobs import MemoryStore
from ..clients import CHAT_VIDEO_APP
from ..db import Media, utcnow
from ..media import VIDEO_MAX_SECONDS
from ..push import Dispatcher
from . import e2e_crypto as ec
from .test_e2e import keyed, send
from .test_push import FakeFcm, FakeWeb, sub
from .test_social import tiny_video, user


def run(tmp_path, scenario, e2e_default=False):
    async def go():
        app = create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(), e2e_default=e2e_default)
        async with app.test_app() as ta:
            await scenario(app, ta.test_client())
    asyncio.run(go())


def test_a_video_in_a_plain_chat(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        await ben.req("post", "/api/push/register", json={"platform": "web", "subscription": sub("ben"), "lang": "de"})
        web = FakeWeb()
        d = Dispatcher(app.db, FakeFcm(), web)
        await d.tick()
        dm = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        md = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        sent = (await ann.req("post", f"/api/conversations/{dm}/messages", json={"media_id": md["id"]}))["message"]
        assert sent["media"]["kind"] == "video" and sent["media"]["url"].endswith(".mp4") and sent["media"]["thumb"].endswith(".jpg")
        assert sent["media"]["expires_at"] and sent["media"]["archived"] is False
        # the notification and the chat list say what it is
        await d.tick()
        assert web.sent[-1][1]["body"] == "▶ Video"
        chats = (await ben.req("get", "/api/conversations"))["conversations"]
        assert chats[0]["last"]["media_kind"] == "video"
        # after two weeks only the still is left
        async with app.db.session() as s:
            later = utcnow() + timedelta(days=chat.CHAT_IMAGE_DAYS, minutes=1)
            assert await chat.archive_chat_images(s, app.blobs, later) == 1
            await s.commit()
            row = await s.get(Media, md["id"])
            assert row.archived and row.key == row.thumb_key
        assert await app.blobs.get(sent["media"]["url"].lstrip("/")) is None
        got = (await ben.req("get", f"/api/conversations/{dm}/messages"))["messages"][-1]["media"]
        assert got["kind"] == "video" and got["archived"] and got["url"] == got["thumb"]
    run(tmp_path, scenario)


def test_an_encrypted_chat_video(tmp_path, monkeypatch):
    async def scenario(app, c):
        ann, ben = await keyed(c, "ann"), await keyed(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        cid = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        mkey = os.urandom(32)
        video, still = ec.seal(mkey, b"the video", None), ec.seal(mkey, b"its still", None)
        # the app says how long it is (it can't be checked): at most what a video keeps
        md = (await ann.req("post", "/api/media/encrypted?kind=video&width=720&height=1280&duration=75", data=video,
                            ctype="application/octet-stream"))["media"]
        assert md["kind"] == "video" and md["encrypted"] and md["duration"] == VIDEO_MAX_SECONDS and md["width"] == 720
        md = (await ann.req("post", f"/api/media/{md['id']}/thumb", data=still, ctype="application/octet-stream"))["media"]
        assert md["thumb"] != md["url"]
        # a plain video doesn't go into an encrypted chat
        plain = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        await send(ann, cid, "", media_id=plain["id"], status=400)
        sent = (await send(ann, cid, "", media_id=md["id"], kind="video", media={"k": ec.b64(mkey), "mime": "video/mp4"}))["message"]
        assert sent["media"]["kind"] == "video" and sent["media"]["expires_at"]
        blob = await c.get(sent["media"]["url"])
        assert ec.unseal(mkey, await blob.get_data(), None) == b"the video"

        # an Android app that can't play chat videos gets the still as a photo (it opens with the same key)
        ben.h = {**ben.h, "X-App-Version": str(CHAT_VIDEO_APP - 1)}
        old = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]["media"]
        assert old["kind"] == "image" and old["url"] == old["thumb"] == sent["media"]["thumb"]
        ben.h["X-App-Version"] = str(CHAT_VIDEO_APP)
        new = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]["media"]
        assert new["kind"] == "video" and new["url"] == sent["media"]["url"]

        # bigger than a chat video may be: refused (photos and voice messages keep their own limit)
        monkeypatch.setattr(e2e, "MAX_VIDEO", 100)
        await ann.req("post", "/api/media/encrypted?kind=video", data=os.urandom(101), ctype="application/octet-stream", status=400)
        await ann.req("post", "/api/media/encrypted?kind=image", data=os.urandom(101), ctype="application/octet-stream")

        # after two weeks only the (encrypted) still is left
        async with app.db.session() as s:
            later = utcnow() + timedelta(days=chat.CHAT_IMAGE_DAYS, minutes=1)
            assert await chat.archive_chat_images(s, app.blobs, later) == 1
            await s.commit()
        got = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]["media"]
        assert got["archived"] and got["url"] == got["thumb"]
        assert ec.unseal(mkey, await (await c.get(got["url"])).get_data(), None) == b"its still"
    run(tmp_path, scenario, e2e_default=True)
