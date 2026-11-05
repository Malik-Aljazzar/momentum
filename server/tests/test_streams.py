"""Adaptive video streams (server/hls.py): post and story videos get an HEVC HLS ladder from the background job,
`url` stays the H.264 MP4 every player can play."""
import re

from .. import hls, posts, storage
from ..db import Media
from .test_social import run, tiny_video, user


def test_ladder():
    assert hls.ladder(720, 1280) == [(720, 1000, 96), (480, 550, 64), (360, 300, 64)]
    assert hls.ladder(1280, 720) == hls.ladder(720, 1280)                   # the short side counts
    assert [r[0] for r in hls.ladder(540, 960)] == [540, 360]                 # never above the source, 480 too close
    assert [r[0] for r in hls.ladder(321, 568)] == [320]
    assert [r[0] for r in hls.ladder(0, 0)] == [720, 480, 360]


def test_command_keeps_renditions_switchable():
    args = " ".join(hls.command(720, 1280, 59.94, audio=True))
    assert "fps=30," in args and "split=3" in args and "libx265" in args and "hvc1" in args
    assert "expr:gte(t,n_forced*2)" in args and "scenecut=0" in args and "open-gop=0" in args
    assert "v:0,a:0 v:1,a:1 v:2,a:2" in args and "single_file" in args
    quiet = " ".join(hls.command(540, 960, 30, audio=False))
    assert "fps=" not in quiet and "0:a" not in quiet and "-var_stream_map v:0 v:1" in quiet


async def _get(c, path, **headers):
    r = await c.get(path, headers=headers)
    return r, await r.get_data()


def test_a_post_video_gets_its_stream(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        md = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        assert "hls" not in md
        # not posted yet: nothing to do
        assert await hls.encode_all(app.db, app.blobs) == 0
        post = (await ann.req("post", "/api/posts", json={"text": "run", "media_ids": [md["id"]]}))["post"]
        assert "hls" not in post["media"][0]
        assert await hls.encode_all(app.db, app.blobs) == 1
        assert await hls.encode_all(app.db, app.blobs) == 0                   # done once
        got = (await ann.req("get", f"/api/posts/{post['id']}"))["post"]["media"][0]
        assert got["url"] == md["url"] and got["hls"].endswith("/master.m3u8")

        # the master lists three HEVC renditions (the upload is 720x1280), the players pick one
        r, master = await _get(c, got["hls"])
        assert r.status_code == 200 and r.content_type == hls.PLAYLIST_TYPE
        master = master.decode()
        assert master.count("#EXT-X-STREAM-INF") == 3 and 'CODECS="hvc1' in master and "mp4a.40.2" in master
        assert "RESOLUTION=720x1280" in master and "RESOLUTION=480x854" in master and "RESOLUTION=360x640" in master
        folder = got["hls"].rsplit("/", 1)[0]
        r, playlist = await _get(c, f"{folder}/v0.m3u8")
        playlist = playlist.decode()
        assert "#EXT-X-PLAYLIST-TYPE:VOD" in playlist and "#EXT-X-MAP:URI=\"v0.mp4\"" in playlist
        # segments are byte ranges of one file: the server reads just that part
        length, offset = map(int, re.search(r"#EXT-X-BYTERANGE:(\d+)@(\d+)", playlist).groups())
        r, part = await _get(c, f"{folder}/v0.mp4", Range=f"bytes={offset}-{offset + length - 1}")
        assert r.status_code == 206 and len(part) == length and part[4:8] in (b"sidx", b"moof", b"styp")
        size = len((await app.blobs.get(f"{folder.lstrip('/')}/v0.mp4"))[0])
        assert r.headers["Content-Range"] == f"bytes {offset}-{offset + length - 1}/{size}"
        r, tail = await _get(c, f"{folder}/v0.mp4", Range="bytes=-10")
        assert r.status_code == 206 and len(tail) == 10
        r, _ = await _get(c, f"{folder}/v0.mp4", Range=f"bytes={size}-")
        assert r.status_code == 416
        r, _ = await _get(c, f"{folder}/v0.mp4", Range="bytes=x-")
        assert r.status_code == 416
        r, _ = await _get(c, f"{folder}/gone.mp4", Range="bytes=0-1")
        assert r.status_code == 404

        # the storage overview counts the stream with its video
        async with app.db.session() as s:
            items = await storage.blob_items(s, app.blobs, refresh=True)
        assert not [i for i in items if i["category"] == "orphan"]
        video = next(i for i in items if i["category"] == "post_video")
        assert video["size"] > len((await app.blobs.get(md["url"].lstrip("/")))[0])

        # the post deleted: all of its files go
        files = [k for k in app.blobs.objects if k.startswith(folder.lstrip("/"))]
        assert len(files) == 7                                       # master + 3 playlists + 3 renditions
        await ann.req("delete", f"/api/posts/{post['id']}")
        assert not [k for k in app.blobs.objects if k.startswith(folder.lstrip("/"))]
    run(tmp_path, scenario)


def test_stories_yes_chats_and_failures_no(tmp_path, monkeypatch):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        story_md = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        await ann.req("post", "/api/stories", json={"media_id": story_md["id"]})
        dm = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        chat_md = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        await ann.req("post", f"/api/conversations/{dm}/messages", json={"media_id": chat_md["id"]})
        assert await hls.encode_all(app.db, app.blobs) == 1                    # the story, not the chat video
        async with app.db.session() as s:
            assert (await s.get(Media, story_md["id"])).hls_key
            assert (await s.get(Media, chat_md["id"])).hls_key is None

        # a video ffmpeg can't stream gets MAX_TRIES, then it stays with its MP4
        async def broken(data):
            raise hls.StreamError("broken")
        monkeypatch.setattr(hls, "build", broken)
        md = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        post = (await ann.req("post", "/api/posts", json={"media_ids": [md["id"]]}))["post"]
        assert await hls.encode_all(app.db, app.blobs) == hls.MAX_TRIES
        assert await hls.encode_all(app.db, app.blobs) == 0
        got = (await ann.req("get", f"/api/posts/{post['id']}"))["post"]["media"][0]
        assert "hls" not in got and got["url"].endswith(".mp4")
    run(tmp_path, scenario)


def test_new_text_new_stream(tmp_path):
    """A video whose file is replaced (text burned in) drops its stream; the job makes one of the new file."""
    async def scenario(app, c):
        ann = await user(c, "ann")
        md = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        await ann.req("post", "/api/posts", json={"media_ids": [md["id"]]})
        assert await hls.encode_all(app.db, app.blobs) == 1
        async with app.db.session() as s:
            row = await s.get(Media, md["id"])
            old = hls.files_of(row)
            row.overlay = '{"text": "Hi", "x": 0.5, "y": 0.5, "color": "white", "size": 0.07}'
            assert await posts.bake_overlay(app.blobs, row)
            assert row.hls_key is None and row.hls_files is None and row.hls_tries == 0
            await s.commit()
        assert old and not [k for k in old if k in app.blobs.objects]
        assert await hls.encode_all(app.db, app.blobs) == 1
        async with app.db.session() as s:
            row = await s.get(Media, md["id"])
            assert row.hls_key.startswith(row.key.rsplit(".", 1)[0] + "_hls")
    run(tmp_path, scenario)
