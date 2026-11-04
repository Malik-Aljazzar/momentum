import asyncio
import io
import os
import subprocess
import tempfile

import imageio_ffmpeg
from PIL import Image

from .test_api import make_app, set_step


def png(w=800, h=600):
    buf = io.BytesIO()
    Image.frombytes("RGB", (w, h), os.urandom(w * h * 3)).save(buf, "PNG")
    return buf.getvalue()


def tiny_video():
    """2 s 1080x1920 test clip with audio."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "v.mp4")
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "lavfi", "-i", "testsrc=size=1080x1920:rate=30",
                        "-f", "lavfi", "-i", "sine=frequency=440", "-t", "2", "-c:v", "libx264", "-c:a", "aac",
                        "-shortest", out], capture_output=True, check=True)
        return open(out, "rb").read()


class Client:
    """Test helper: one logged-in user."""

    def __init__(self, c, name, headers):
        self.c, self.name, self.h = c, name, headers

    async def req(self, method, path, json=None, data=None, ctype=None, status=200):
        headers = {**self.h, "Accept-Language": "en", **({"Content-Type": ctype} if ctype else {})}
        r = await getattr(self.c, method)(path, headers=headers, **({"json": json} if json is not None else {}),
                                          **({"data": data} if data is not None else {}))
        body = await r.get_json() if r.content_type == "application/json" else await r.get_data()
        assert r.status_code == status, (path, r.status_code, body)
        return body


async def user(c, name):
    r = await c.post("/api/auth/register", json={"email": f"{name}@example.com", "password": "secret1", "language": "en"})
    h = {"Authorization": "Bearer " + (await r.get_json())["token"]}
    await c.put("/api/settings", headers=h, json={"username": name})
    return Client(c, name, h)


def run(tmp_path, scenario):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            await scenario(app, ta.test_client())
    asyncio.run(go())


def test_follow_feed_likes_comments_mentions(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")

        followed = (await ben.req("post", "/api/users/ann/follow"))["profile"]
        assert followed["following"] is True and followed["followers_count"] == 1   # response reflects the change
        prof = (await ann.req("get", "/api/users/ann"))["profile"]
        assert prof["followers_count"] == 1 and prof["following_count"] == 0
        assert (await ben.req("get", "/api/users/ann"))["profile"]["following"] is True

        media = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        assert media["kind"] == "image" and media["url"].endswith(".webp")
        post = (await ann.req("post", "/api/posts", json={"text": "Lauf! @cat", "media_ids": [media["id"]],
                                                          "tags": ["ben", "cat"]}))["post"]
        # ben follows ann -> taggable; cat has no connection -> silently not tagged
        assert [t["username"] for t in post["tags"]] == ["ben"]
        img = await ann.req("get", media["url"])
        assert Image.open(io.BytesIO(img)).format == "WEBP"

        # feed: people I follow and my own posts
        assert [p["id"] for p in (await ben.req("get", "/api/feed"))["posts"]] == [post["id"]]
        assert [p["id"] for p in (await ann.req("get", "/api/feed"))["posts"]] == [post["id"]]
        assert (await cat.req("get", "/api/feed"))["posts"] == []

        await ben.req("post", f"/api/posts/{post['id']}/like")
        detail = (await ben.req("post", f"/api/posts/{post['id']}/comments", json={"text": "Stark @cat!"}))["post"]
        assert detail["likes"] == 1 and detail["liked"] and detail["comments"] == 1
        assert detail["comment_list"][0]["user"]["username"] == "ben"

        st = (await ann.req("get", "/api/state"))["state"]
        assert st["unread"]["notifications"] == 3          # follow, like, comment
        kinds = [n["kind"] for n in (await ann.req("get", "/api/notifications"))["items"]]
        assert sorted(kinds) == ["comment", "follow", "like"]
        assert (await ann.req("get", "/api/state"))["state"]["unread"]["notifications"] == 0
        cat_kinds = sorted(n["kind"] for n in (await cat.req("get", "/api/notifications"))["items"])
        assert cat_kinds == ["mention", "mention"]           # in the post text and in ben's comment
        ben_kinds = [n["kind"] for n in (await ben.req("get", "/api/notifications"))["items"]]
        assert ben_kinds == ["tag"]

        await ann.req("delete", f"/api/posts/{post['id']}")
        assert (await ben.req("get", "/api/feed"))["posts"] == []
        assert not any(k.startswith("media/") for k in app.blobs.objects)
    run(tmp_path, scenario)


def test_video_post(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        media = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        assert media["kind"] == "video" and media["url"].endswith(".mp4") and media["thumb"].endswith(".jpg")
        assert (media["width"], media["height"]) == (720, 1280)
        assert 1.5 < media["duration"] <= 2.5
        r = await c.get(media["url"], headers={"Range": "bytes=0-99"})
        assert r.status_code == 206 and len(await r.get_data()) == 100
        assert r.headers["Content-Range"].startswith("bytes 0-99/")
    run(tmp_path, scenario)


def test_video_sent_as_image_is_still_a_video(tmp_path):
    """Older Android apps sent in-app camera clips as image/jpeg (report #10)."""
    async def scenario(app, c):
        ann = await user(c, "ann")
        media = (await ann.req("post", "/api/media", data=tiny_video(), ctype="image/jpeg"))["media"]
        assert media["kind"] == "video" and media["url"].endswith(".mp4")
    run(tmp_path, scenario)


def test_milestone_share_only_when_reached_and_once(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/posts/milestone", json={"vehicle": "motorroller"}, status=400)
        await set_step(app, "ann", 100)
        st = (await ann.req("get", "/api/state"))["state"]
        task = next(r for r in st["rules"] if r["name"] == "Task done")      # +100
        r = await ann.req("post", f"/api/rules/{task['id']}/trigger")
        assert r["milestone"]
        post = (await ann.req("post", "/api/posts/milestone", json={"vehicle": "motorroller", "text": "Brumm"}))["post"]
        assert post["kind"] == "milestone" and post["vehicle"] == "motorroller"
        await ann.req("post", "/api/posts/milestone", json={"vehicle": "motorroller"}, status=400)
        await ann.req("post", "/api/posts/milestone", json={"vehicle": "raumschiff"}, status=400)
        assert len((await ann.req("get", "/api/users/ann/posts"))["posts"]) == 1
    run(tmp_path, scenario)


def test_direct_messages_groups_and_unread(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ann.req("post", "/api/conversations/direct", json={"username": "ben"}, status=403)  # not connected
        await ben.req("post", "/api/users/ann/follow")
        await cat.req("post", "/api/users/ann/follow")

        dm = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]
        again = (await ben.req("post", "/api/conversations/direct", json={"username": "ann"}))["conversation"]
        assert dm["id"] == again["id"]
        await ann.req("post", f"/api/conversations/{dm['id']}/messages", json={"text": "Hi Ben"})
        media = (await ann.req("post", "/api/media", data=png(200, 200), ctype="image/png"))["media"]
        sent = (await ann.req("post", f"/api/conversations/{dm['id']}/messages", json={"media_id": media["id"]}))["message"]
        assert sent["mine"] and sent["media"]["kind"] == "image"

        assert (await ben.req("get", "/api/state"))["state"]["unread"]["messages"] == 2
        convs = (await ben.req("get", "/api/conversations"))["conversations"]
        assert convs[0]["unread"] == 2 and convs[0]["members"][0]["username"] == "ann"
        msgs = (await ben.req("get", f"/api/conversations/{dm['id']}/messages"))["messages"]
        assert [mm["text"] for mm in msgs] == ["Hi Ben", ""] and not msgs[0]["mine"]
        assert (await ben.req("get", "/api/state"))["state"]["unread"]["messages"] == 0
        polled = (await ben.req("get", f"/api/conversations/{dm['id']}/messages?after={msgs[-1]['id']}"))["messages"]
        assert polled == []

        group = (await ann.req("post", "/api/conversations/group",
                               json={"name": "Laufgruppe", "usernames": ["ben", "cat"]}))["conversation"]
        assert group["kind"] == "group" and {u["username"] for u in group["members"]} == {"ben", "cat"}
        assert (await ben.req("get", f"/api/conversations/{group['id']}"))["conversation"]["name"] == "Laufgruppe"
        await cat.req("post", f"/api/conversations/{group['id']}/messages", json={"text": "Bin dabei"})
        assert (await ben.req("get", "/api/state"))["state"]["unread"]["messages"] == 1
        await cat.req("delete", f"/api/conversations/{group['id']}/members/me")
        await cat.req("get", f"/api/conversations/{group['id']}/messages", status=404)
    run(tmp_path, scenario)


def test_chat_photos_keep_only_the_small_copy_after_two_weeks(tmp_path):
    async def scenario(app, c):
        from datetime import timedelta
        from server import chat
        from server.db import Media, Message, utcnow
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        dm = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]
        media = (await ann.req("post", "/api/media", data=png(1600, 1200), ctype="image/png"))["media"]
        sent = (await ann.req("post", f"/api/conversations/{dm['id']}/messages", json={"media_id": media["id"]}))["message"]
        assert sent["media"]["archived"] is False and sent["media"]["expires_at"]
        full = sent["media"]["url"].lstrip("/")
        async with app.db.session() as s:
            assert await chat.archive_chat_images(s, app.blobs) == 0          # still fresh
            later = utcnow() + timedelta(days=chat.CHAT_IMAGE_DAYS, minutes=1)
            assert await chat.archive_chat_images(s, app.blobs, later) == 1
            await s.commit()
            md = await s.get(Media, media["id"])
            assert md.archived and md.key == md.thumb_key
        assert await app.blobs.get(full) is None                              # the big picture is gone
        msg = (await ben.req("get", f"/api/conversations/{dm['id']}/messages"))["messages"][0]
        assert msg["media"]["archived"] is True and "expires_at" not in msg["media"]
        assert msg["media"]["url"] == msg["media"]["thumb"]
        assert await app.blobs.get(msg["media"]["url"].lstrip("/")) is not None
    run(tmp_path, scenario)


def test_account_deletion_cleans_up_social_data(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        media = (await ann.req("post", "/api/media", data=png(100, 100), ctype="image/png"))["media"]
        post = (await ann.req("post", "/api/posts", json={"text": "x", "media_ids": [media["id"]]}))["post"]
        await ben.req("post", f"/api/posts/{post['id']}/comments", json={"text": "nice"})
        dm = (await ben.req("post", "/api/conversations/direct", json={"username": "ann"}))["conversation"]
        await ann.req("post", f"/api/conversations/{dm['id']}/messages", json={"text": "bye"})

        await ann.req("delete", "/api/account")
        assert (await ben.req("get", "/api/feed"))["posts"] == []
        assert (await ben.req("get", "/api/conversations"))["conversations"] == []
        assert (await ben.req("get", "/api/users/ben"))["profile"]["following_count"] == 0
        assert not any(k.startswith("media/") for k in app.blobs.objects)
    run(tmp_path, scenario)


def test_public_rules_and_history_on_profile(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        st = (await ann.req("get", "/api/state"))["state"]
        assert not any(r["public"] for r in st["rules"])            # private by default
        task = next(r for r in st["rules"] if r["name"] == "Task done")
        await ann.req("post", f"/api/rules/{task['id']}/trigger")
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["rules"] == [] and prof["history"] == []

        body = {k: task[k] for k in ("kind", "name", "icon", "mode", "base")}
        await ann.req("put", f"/api/rules/{task['id']}", json={**body, "public": True})
        await ann.req("post", f"/api/rules/{task['id']}/trigger")
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert [r["name"] for r in prof["rules"]] == ["Task done"]
        assert [h["delta"] for h in prof["history"]] == [100, 100] and prof["history"][0]["at"]
        assert prof["momentum"] == 200 and prof["next_at"] == 1000
    run(tmp_path, scenario)


def test_email_signup_username_onboarding_and_login(tmp_path):
    async def scenario(app, c):
        for bad in ("anna", "anna@", "anna@test", "a b@test.de", "anna@@test.de"):
            r = await c.post("/api/auth/register", json={"email": bad, "password": "secret1"}, headers={"Accept-Language": "en"})
            assert r.status_code == 400 and "valid e-mail" in (await r.get_json())["error"], bad
        r = await c.post("/api/auth/register", json={"email": "Anna.K@Test.de", "password": "secret1"})
        h = {"Authorization": "Bearer " + (await r.get_json())["token"]}
        st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
        assert st["user"]["needs_username"] and st["user"]["email"] == "anna.k@test.de"
        assert "anna" not in st["user"]["display_name"] and "anna" not in st["user"]["username"]   # nothing from the e-mail
        r = await c.post("/api/auth/register", json={"email": "anna.k@test.de", "password": "secret1"})
        assert r.status_code == 400                                                 # e-mail taken

        await user(c, "ben")
        for bad in ("ben", "Bad Name", "x"):
            r = await c.put("/api/settings", headers=h, json={"username": bad})
            assert r.status_code == 400, bad
        st = (await (await c.put("/api/settings", headers=h, json={"username": "anna"})).get_json())["state"]
        assert not st["user"]["needs_username"] and st["user"]["display_name"] == "anna"

        for login in ("anna", "ANNA.K@test.de"):
            r = await c.post("/api/auth/login", json={"login": login, "password": "secret1"})
            assert r.status_code == 200, login
        prof = (await (await c.get("/api/users/anna", headers=h)).get_json())["profile"]
        assert "email" not in prof                                                  # never public
    run(tmp_path, scenario)


def test_legacy_email_usernames_are_migrated(tmp_path):
    async def scenario(app, c):
        from sqlalchemy import text
        await user(c, "old")
        async with app.db.engine.begin() as conn:
            await conn.execute(text("UPDATE users SET username='Old@Mail.de', email=NULL, username_set=1"))
        await app.db.create_all()                   # runs the data migration again
        r = await c.post("/api/auth/login", json={"login": "old@mail.de", "password": "secret1"})
        h = {"Authorization": "Bearer " + (await r.get_json())["token"]}
        st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
        assert st["user"]["needs_username"] and st["user"]["email"] == "old@mail.de"
        assert "@" not in st["user"]["username"]
        found = (await (await c.get("/api/users/search?q=old", headers=h)).get_json())["users"]
        assert found == []                          # hidden until a username is chosen
    run(tmp_path, scenario)


def test_comment_likes_and_likers(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ben.req("post", "/api/users/ann/follow")
        await cat.req("post", "/api/users/ann/follow")
        post = (await ann.req("post", "/api/posts", json={"text": "hi"}))["post"]
        await ben.req("post", f"/api/posts/{post['id']}/like")
        await cat.req("post", f"/api/posts/{post['id']}/like")
        likers = (await ann.req("get", f"/api/posts/{post['id']}/likes"))["users"]
        assert [u["username"] for u in likers] == ["cat", "ben"]

        detail = (await ben.req("post", f"/api/posts/{post['id']}/comments", json={"text": "nice"}))["post"]
        cid = detail["comment_list"][0]["id"]
        detail = (await ann.req("post", f"/api/comments/{cid}/like"))["post"]
        assert detail["comment_list"][0]["likes"] == 1 and detail["comment_list"][0]["liked"]
        assert [u["username"] for u in (await cat.req("get", f"/api/comments/{cid}/likes"))["users"]] == ["ann"]
        assert "clike" in [n["kind"] for n in (await ben.req("get", "/api/notifications"))["items"]]
        detail = (await ann.req("delete", f"/api/comments/{cid}/like"))["post"]
        assert detail["comment_list"][0]["likes"] == 0
        await ann.req("delete", f"/api/posts/{post['id']}")      # cascades without errors
    run(tmp_path, scenario)


def test_comment_replies(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        post = (await ann.req("post", "/api/posts", json={"text": "hi"}))["post"]
        pid = post["id"]
        first = (await ben.req("post", f"/api/posts/{pid}/comments", json={"text": "nice"}))["post"]["comment_list"][0]
        assert first["parent_id"] is None
        detail = (await cat.req("post", f"/api/posts/{pid}/comments",
                                json={"text": "@ben agreed", "parent_id": first["id"]}))["post"]
        reply = detail["comment_list"][1]
        assert reply["parent_id"] == first["id"] and detail["comments"] == 2
        # a reply to a reply stays in the same thread
        detail = (await ann.req("post", f"/api/posts/{pid}/comments",
                                json={"text": "thanks", "parent_id": reply["id"]}))["post"]
        assert detail["comment_list"][2]["parent_id"] == first["id"]
        # ben: reply (cat) + reply (ann); no extra mention notification for the reply that mentions him
        ben_notes = (await ben.req("get", "/api/notifications"))["items"]
        assert sorted(n["kind"] for n in ben_notes) == ["reply", "reply"]
        # the activity shows a preview of the comment (mentions as names)
        assert sorted(n["comment"] for n in ben_notes) == ["@ben agreed", "thanks"]
        ann_notes = (await ann.req("get", "/api/notifications"))["items"]
        assert "comment" in [n["kind"] for n in ann_notes]
        # a comment of another post can't be the parent
        other = (await ann.req("post", "/api/posts", json={"text": "other"}))["post"]
        await ben.req("post", f"/api/posts/{other['id']}/comments", json={"text": "x", "parent_id": first["id"]}, status=404)
        # deleting the first comment removes its thread
        detail = (await ben.req("delete", f"/api/comments/{first['id']}"))["post"]
        assert detail["comment_list"] == [] and detail["comments"] == 0
        # account deletion takes the replies to the user's comments with it
        first = (await ben.req("post", f"/api/posts/{pid}/comments", json={"text": "again"}))["post"]["comment_list"][0]
        await cat.req("post", f"/api/posts/{pid}/comments", json={"text": "yo", "parent_id": first["id"]})
        await ben.req("delete", "/api/account")
        assert (await ann.req("get", f"/api/posts/{pid}"))["post"]["comment_list"] == []
    run(tmp_path, scenario)



def test_bio_and_single_name(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        st = (await ann.req("put", "/api/settings", json={"bio": "  Läuft bei mir. @ben  "}))["state"]
        assert st["user"]["bio"] == "Läuft bei mir. @ben"
        await ann.req("put", "/api/settings", json={"bio": "x" * 151}, status=400)
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["bio"] == "Läuft bei mir. @ben" and prof["display_name"] == "ann"
        st = (await ann.req("put", "/api/settings", json={"username": "anna", "display_name": "Something else"}))["state"]
        assert st["user"]["display_name"] == st["user"]["username"] == "anna"     # display name follows the username
    run(tmp_path, scenario)


def test_mentions_are_references_and_autocomplete_ranking(tmp_path):
    async def scenario(app, c):
        ann, tim, timo, otim = await user(c, "ann"), await user(c, "tim"), await user(c, "timo"), await user(c, "otimist")
        await ann.req("post", "/api/users/timo/follow")          # timo: connection, tim: exact match
        names = [u["username"] for u in (await ann.req("get", "/api/mentions?q=tim"))["users"]]
        assert names == ["tim", "timo", "otimist"]                # exact > followed prefix > others
        assert [u["username"] for u in (await ann.req("get", "/api/mentions?q="))["users"]] == ["timo"]

        post = (await ann.req("post", "/api/posts", json={"text": "Lauf mit @Tim und @nobody."}))["post"]
        assert post["text"] == "Lauf mit @tim und @nobody."
        await ann.req("put", "/api/settings", json={"bio": "Coach: @timo."})
        await tim.req("post", f"/api/posts/{post['id']}/comments", json={"text": "@ann ja!"})

        # rename: every mention follows without touching the stored text
        await tim.req("put", "/api/settings", json={"username": "tim.renamed"})
        await timo.req("put", "/api/settings", json={"username": "timo2"})
        p = (await ann.req("get", f"/api/posts/{post['id']}"))["post"]
        assert p["text"] == "Lauf mit @tim.renamed und @nobody."
        assert p["comment_list"][0]["text"] == "@ann ja!"
        assert (await tim.req("get", "/api/users/ann"))["profile"]["bio"] == "Coach: @timo2."
        assert (await ann.req("get", "/api/state"))["state"]["user"]["bio"] == "Coach: @timo2."
        async with app.db.session() as s:
            from sqlalchemy import select
            from ..db import Post
            assert "@[" in (await s.scalar(select(Post.text)))      # stored as a reference
    run(tmp_path, scenario)


def test_updates_for_background_notifications(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        conv = (await ben.req("post", "/api/conversations/direct", json={"username": "ann"}))["conversation"]["id"]
        await ben.req("post", f"/api/conversations/{conv}/messages", json={"text": "Hi @ann"})
        up = await ann.req("get", "/api/updates")
        assert [m["text"] for m in up["messages"]] == ["Hi @ann"] and up["messages"][0]["title"] == "ben"
        assert [n["kind"] for n in up["notifications"]] == ["follow"]
        assert up["unread_chats"] == [conv] and up["unread_notifications"] == [up["notifications"][0]["id"]]
        # nothing was marked as read by asking
        assert (await ann.req("get", "/api/state"))["state"]["unread"]["messages"] == 1
        # after the newest ids: nothing new; reading the chat in the app also counts as seen
        st = (await ann.req("get", "/api/state"))["state"]["unread"]
        again = await ann.req("get", f"/api/updates?after_message={st['latest_message_id']}&after_notification={st['latest_notification_id']}")
        assert again["messages"] == [] and again["notifications"] == []
        await ben.req("post", f"/api/conversations/{conv}/messages", json={"text": "noch da?"})
        await ann.req("get", f"/api/conversations/{conv}/messages")
        up = await ann.req("get", "/api/updates")
        assert up["messages"] == [] and up["unread_chats"] == []          # read: the phone clears its notifications
        await ann.req("get", "/api/notifications")
        assert (await ann.req("get", "/api/updates"))["unread_notifications"] == []
    run(tmp_path, scenario)



def test_bug_reports_and_feature_requests(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        media = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        await ann.req("post", "/api/reports", json={"kind": "other", "text": "x"}, status=400)
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": ""}, status=400)
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": "Chat stürzt ab", "media_ids": [media["id"]],
                                                   "app_version": "0.1.1", "device": "Pixel 7 · Android 15"})
        await ann.req("post", "/api/reports", json={"kind": "feature", "text": "Dunkler Modus für Widgets", "platform": "pwa"})
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": "altes PWA", "app_version": "web 123"})
        async with app.db.session() as s:
            from sqlalchemy import select
            from ..db import Report
            reports = list(await s.scalars(select(Report).order_by(Report.id)))
            assert [(r.kind, r.username, r.status) for r in reports][:2] == [("bug", "ann", "new"), ("feature", "ann", "new")]
            # the platform: sent by the app, or guessed from the version for older apps
            assert [r.platform for r in reports] == ["android", "pwa", "pwa"]
            assert reports[0].media_keys == media["url"].lstrip("/").replace("media/", "reports/", 1) and reports[0].device == "Pixel 7 · Android 15"
    run(tmp_path, scenario)


def tiny_voice():
    """1.5 s of sine as an AAC file (like a phone recording)."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "v.m4a")
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "lavfi", "-i", "sine=frequency=300", "-t", "1.5",
                        "-c:a", "aac", out], capture_output=True, check=True)
        return open(out, "rb").read()


def test_voice_messages(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        voice = (await ann.req("post", "/api/media", data=tiny_voice(), ctype="audio/mp4"))["media"]
        assert voice["kind"] == "audio" and voice["url"].endswith(".m4a") and 1.0 < voice["duration"] < 2.0
        msg = (await ann.req("post", f"/api/conversations/{conv}/messages", json={"media_id": voice["id"]}))["message"]
        assert msg["media"]["kind"] == "audio"
        got = (await ben.req("get", f"/api/conversations/{conv}/messages"))["messages"][-1]
        assert got["media"]["url"] == voice["url"]
        audio = await ben.req("get", voice["url"])
        assert len(audio) > 500
        # not in posts
        other = (await ann.req("post", "/api/media", data=tiny_voice(), ctype="audio/mp4"))["media"]
        await ann.req("post", "/api/posts", json={"text": "hör mal", "media_ids": [other["id"]]}, status=400)
    run(tmp_path, scenario)


def test_add_member_candidates(tmp_path):
    async def scenario(app, c):
        ann, ben, tim, timo, eve = [await user(c, n) for n in ("ann", "ben", "tim", "timo", "eve")]
        for n in ("ben", "timo"):
            await ann.req("post", f"/api/users/{n}/follow")
        await tim.req("post", "/api/users/ann/follow")           # follower, ranked after people I follow
        conv = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{conv}", json={"anarchy": True})   # everyone may change it (not about admins)
        names = lambda r: [u["username"] for u in r["users"]]
        # members and unconnected people (eve) are left out; following before followers
        assert names(await ann.req("get", f"/api/mentions?conv={conv}&q=")) == ["timo", "tim"]
        assert names(await ann.req("get", f"/api/mentions?conv={conv}&q=tim")) == ["tim", "timo"]   # exact first
        assert names(await eve.req("get", f"/api/mentions?conv={conv}&q=")) == []                 # not a member
        members = (await ann.req("post", f"/api/conversations/{conv}/members", json={"usernames": ["tim"]}))["conversation"]["members"]
        assert {m["username"] for m in members} == {"ben", "tim"}
        assert names(await ann.req("get", f"/api/mentions?conv={conv}&q=")) == ["timo"]
    run(tmp_path, scenario)


def test_mentions_in_group_messages(tmp_path):
    async def scenario(app, c):
        ann, ben, cat, dan = [await user(c, n) for n in ("ann", "ben", "cat", "dan")]
        for n in ("ben", "cat", "dan"):
            await ann.req("post", f"/api/users/{n}/follow")
        conv = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben", "cat"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{conv}", json={"anarchy": True})   # everyone may change it (not about admins)
        names = lambda r: [u["username"] for u in r["users"]]
        assert names(await ann.req("get", f"/api/mentions?members_of={conv}&q=")) == ["ben", "cat"]   # members only
        assert names(await ann.req("get", f"/api/mentions?members_of={conv}&q=da")) == []

        msg = (await ann.req("post", f"/api/conversations/{conv}/messages", json={"text": "@Ben kommst du?"}))["message"]
        assert msg["text"] == "@ben kommst du?" and not msg["mentions_me"]
        up = await ben.req("get", "/api/updates")
        assert up["messages"][0]["mention"] is True
        assert (await cat.req("get", "/api/updates"))["messages"][0]["mention"] is False
        convs = {x["id"]: x for x in (await ben.req("get", "/api/conversations"))["conversations"]}
        assert convs[conv]["mentioned"] and convs[conv]["last"]["text"] == "@ben kommst du?"
        assert not {x["id"]: x for x in (await cat.req("get", "/api/conversations"))["conversations"]}[conv]["mentioned"]
        got = (await ben.req("get", f"/api/conversations/{conv}/messages"))["messages"]
        assert got[-1]["mentions_me"]
        # read: no longer highlighted; a rename shows up in the stored message
        assert not {x["id"]: x for x in (await ben.req("get", "/api/conversations"))["conversations"]}[conv]["mentioned"]
        await ben.req("put", "/api/settings", json={"username": "benny"})
        assert (await cat.req("get", f"/api/conversations/{conv}/messages"))["messages"][-1]["text"] == "@benny kommst du?"
    run(tmp_path, scenario)


def test_muted_chats(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("post", "/api/users/ben/follow")
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        # group messages are notified
        await ann.req("post", f"/api/conversations/{g}/messages", json={"text": "Hallo Gruppe"})
        assert [m["text"] for m in (await ben.req("get", "/api/updates"))["messages"]] == ["Hallo Gruppe"]
        assert (await ben.req("put", f"/api/conversations/{g}/mute", json={"muted": True}))["conversation"]["muted"]
        # muted: quiet and out of the badge, except when tagged
        assert (await ben.req("get", "/api/updates"))["messages"] == []
        assert (await ben.req("get", "/api/state"))["state"]["unread"]["messages"] == 0
        await ann.req("post", f"/api/conversations/{g}/messages", json={"text": "@ben schau mal"})
        assert [m["text"] for m in (await ben.req("get", "/api/updates"))["messages"]] == ["@ben schau mal"]
        await ben.req("put", f"/api/conversations/{g}/mute", json={"muted": False})
        assert len((await ben.req("get", "/api/updates"))["messages"]) == 2
    run(tmp_path, scenario)


def test_group_picture_banner_image_and_total(tmp_path):
    async def scenario(app, c):
        import io
        from PIL import Image
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("post", "/api/users/ben/follow")
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        st = (await ben.req("get", "/api/state"))["state"]
        task = next(r for r in st["rules"] if r["name"] == "Task done")["id"]
        await ben.req("post", f"/api/rules/{task}/trigger")
        assert (await ann.req("get", f"/api/conversations/{g}"))["conversation"]["total_momentum"] == 100
        buf = io.BytesIO()
        Image.new("RGB", (600, 300), (10, 120, 200)).save(buf, "JPEG")
        media = (await ann.req("post", "/api/media", data=buf.getvalue(), ctype="image/jpeg"))["media"]
        conv = (await ann.req("put", f"/api/conversations/{g}", json={"banner_media": media["id"]}))["conversation"]
        assert conv["banner_image"] == media["url"] and conv["avatar"] is None
        await ann.req("put", f"/api/conversations/{g}", json={"avatar_media": media["id"]}, status=400)   # used already
        conv = (await ann.req("put", f"/api/conversations/{g}", json={"banner_media": None}))["conversation"]
        assert conv["banner_image"] is None
    run(tmp_path, scenario)


def test_replies_in_chats(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("post", "/api/users/ben/follow")
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        first = (await ann.req("post", f"/api/conversations/{conv}/messages", json={"text": "Laufen um 7?"}))["message"]
        ans = (await ben.req("post", f"/api/conversations/{conv}/messages", json={"text": "Klar!", "reply_to": first["id"]}))["message"]
        assert ans["reply"]["id"] == first["id"] and ans["reply"]["text"] == "Laufen um 7?" and ans["reply"]["user"] == "ann"
        got = (await ann.req("get", f"/api/conversations/{conv}/messages"))["messages"]
        assert got[-1]["reply"]["mine"] is True
        await ben.req("post", f"/api/conversations/{conv}/messages", json={"text": "x", "reply_to": 99999}, status=404)
    run(tmp_path, scenario)


def test_post_with_sound(tmp_path):
    async def scenario(app, c):
        import io, math, struct, wave
        ann = await user(c, "ann")
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:                      # 3 s of a tone
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000)
            w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(i / 8))) for i in range(24000)))
        sound = (await ann.req("post", "/api/media?start=1&seconds=1.5", data=buf.getvalue(), ctype="audio/wav"))["media"]
        assert sound["kind"] == "audio" and 1.0 <= sound["duration"] <= 2.0
        post = (await ann.req("post", "/api/posts", json={"text": "Mit Sound", "sound": {"media_id": sound["id"], "title": "Mein Beat"}}))["post"]
        assert post["sound"]["title"] == "Mein Beat" and post["sound"]["url"] == sound["url"] and post["media"] == []
        # a sound can't be used twice / a voice message isn't a post medium
        await ann.req("post", "/api/posts", json={"text": "x", "sound": {"media_id": sound["id"]}}, status=400)
    run(tmp_path, scenario)


def test_group_names_are_unique(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("post", "/api/users/ben/follow")
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Lauftreff", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        r = await ben.req("post", "/api/conversations/group", json={"name": "lauftreff", "usernames": ["ann"]}, status=400)
        assert "taken" in r["error"]
        g2 = (await ben.req("post", "/api/conversations/group", json={"name": "Gym", "usernames": ["ann"]}))["conversation"]["id"]
        await ben.req("put", f"/api/conversations/{g2}", json={"anarchy": True})   # everyone may change it (not about admins)
        await ben.req("put", f"/api/conversations/{g2}", json={"name": "LAUFTREFF"}, status=400)
        await ann.req("put", f"/api/conversations/{g}", json={"name": "Lauftreff"})        # its own name is fine
    run(tmp_path, scenario)


def test_list_pages(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        others = [await user(c, f"u{i:02d}") for i in range(5)]
        for o in others:
            await o.req("post", "/api/users/ann/follow")
        first = (await ann.req("get", "/api/users/ann/followers?limit=2"))["users"]
        second = (await ann.req("get", "/api/users/ann/followers?limit=2&offset=2"))["users"]
        assert len(first) == 2 and len(second) == 2 and not {u["username"] for u in first} & {u["username"] for u in second}
        assert len((await ann.req("get", "/api/users/search?q=u&limit=3"))["users"]) == 3
        notes = (await ann.req("get", "/api/notifications?limit=2"))["items"]
        older = (await ann.req("get", f"/api/notifications?limit=10&before={notes[-1]['id']}"))["items"]
        assert len(notes) == 2 and len(older) == 3
    run(tmp_path, scenario)


def test_text_over_media(tmp_path):
    async def scenario(app, c):
        import io
        from PIL import Image
        ann = await user(c, "ann")
        buf = io.BytesIO()
        Image.new("RGB", (400, 300), (30, 30, 30)).save(buf, "JPEG")
        media = (await ann.req("post", "/api/media", data=buf.getvalue(), ctype="image/jpeg"))["media"]
        from ..posts import clean_overlay
        assert clean_overlay({"text": "Morgenrunde!", "x": 0.3, "y": 1.7, "color": "nope", "size": 0.09}) ==             {"text": "Morgenrunde!", "x": 0.3, "y": 1.0, "color": "white", "size": 0.09}
        post = (await ann.req("post", "/api/posts", json={"media_ids": [media["id"]], "overlays": {
            str(media["id"]): {"text": "Morgenrunde!", "x": 0.3, "y": 1.7, "color": "nope", "size": 0.09}}}))["post"]
        # the text is part of the picture now: no live overlay, new files, white pixels at the bottom left
        m = post["media"][0]
        assert m["overlay"] is None and m["url"] != media["url"] and m["url"].endswith("_t.webp")
        assert media["url"].lstrip("/") not in app.blobs.objects                      # the old file is gone
        img = Image.open(io.BytesIO(await (await c.get(m["url"])).get_data())).convert("RGB")
        brightest = lambda box: sum(hi for _, hi in img.crop(box).getextrema())      # noqa: E731
        assert brightest((0, 200, 240, 300)) > 600 and brightest((300, 0, 400, 100)) < 120
        assert (await c.get(m["thumb"])).status_code == 200
    run(tmp_path, scenario)


def test_text_burned_into_video(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        media = (await ann.req("post", "/api/media", data=tiny_video(), ctype="video/mp4"))["media"]
        post = (await ann.req("post", "/api/posts", json={"media_ids": [media["id"]], "overlays": {
            str(media["id"]): {"text": "Sprint", "x": 0.5, "y": 0.5, "color": "yellow", "size": 0.12}}}))["post"]
        m = post["media"][0]
        assert m["overlay"] is None and m["url"].endswith("_t.mp4") and m["thumb"].endswith("_t_thumb.jpg")
        assert (m["width"], m["height"]) == (720, 1280)
        assert (await c.get(m["url"], headers={"Range": "bytes=0-99"})).status_code == 206
    run(tmp_path, scenario)


def test_subscribed_rules_shown_in_profile_when_chosen(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        st = (await ann.req("get", "/api/state"))["state"]
        task = next(r for r in st["rules"] if r["name"] == "Task done")
        body = {k: task[k] for k in ("kind", "name", "icon", "mode", "base")}
        await ann.req("put", f"/api/rules/{task['id']}", json={**body, "public": True})
        st = (await ben.req("post", f"/api/rules/{task['id']}/subscribe"))["state"]
        sub = next(r for r in st["rules"] if r.get("root_id") == task["id"])
        assert sub["public"] is False                                     # not in the profile by default
        await ben.req("post", f"/api/rules/{sub['id']}/trigger")
        prof = (await ann.req("get", "/api/users/ben"))["profile"]
        assert prof["rules"] == [] and prof["history"] == []
        r = await ben.req("post", "/api/rules/visibility-many", json={"ids": [sub["id"]], "public": True})
        assert r["done"] == 1 and next(x for x in r["state"]["rules"] if x["id"] == sub["id"])["public"] is True
        prof = (await ann.req("get", "/api/users/ben"))["profile"]
        assert [x["name"] for x in prof["rules"]] == ["Task done"] and [h["delta"] for h in prof["history"]] == [100]
    run(tmp_path, scenario)



def test_subscribed_group_rule_in_profile_visible_to_all_subscribable_by_members(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ann.req("post", "/api/users/ben/follow")
        await ben.req("post", "/api/users/ann/follow")
        group = (await ann.req("post", "/api/conversations/group", json={"name": "Geheimbund", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{group}", json={"anarchy": True})   # everyone may change it (not about admins)
        rule = (await ann.req("post", f"/api/conversations/{group}/rules", json={"kind": "acc", "name": "Mitternachtslauf",
                                                                               "icon": "star", "mode": "constant", "base": 20}))["rule"]
        st = (await ben.req("post", "/api/rules/subscribe-many", json={"ids": [rule["id"]]}))["state"]
        copy = next(r for r in st["rules"] if r.get("root_id") == rule["id"])
        await ben.req("post", "/api/rules/visibility-many", json={"ids": [copy["id"]], "public": True})
        # everyone sees it in ben's profile and may open it ...
        prof = (await cat.req("get", "/api/users/ben"))["profile"]
        assert [r["name"] for r in prof["rules"]] == ["Mitternachtslauf"]
        d = (await cat.req("get", f"/api/rules/{copy['id']}/detail"))["rule"]
        assert d["name"] == "Mitternachtslauf" and d["origin"] == "group"
        # ... but only members may subscribe, and the private group's name stays hidden
        assert d["can_subscribe"] is False and d["members_only"] is True and d["group_name"] is None
        await cat.req("post", f"/api/rules/{copy['id']}/subscribe", status=400)
        r = await cat.req("post", "/api/rules/subscribe-many", json={"ids": [copy["id"]]})
        assert r["done"] == 0 and r["skipped"][0]["reason"] == "members_only"
        # a public group: its name shows, still members-only for a private rule
        await ann.req("put", f"/api/conversations/{group}", json={"public": True})
        d = (await cat.req("get", f"/api/rules/{copy['id']}/detail"))["rule"]
        assert d["group_name"] == "Geheimbund" and d["can_subscribe"] is False
        # made public in the public group: now anyone may subscribe
        await ann.req("post", f"/api/conversations/{group}/rules/bulk", json={"action": "public", "ids": [rule["id"]]})
        d = (await cat.req("get", f"/api/rules/{copy['id']}/detail"))["rule"]
        assert d["can_subscribe"] is True and d["members_only"] is False
        await cat.req("post", f"/api/rules/{copy['id']}/subscribe")
    run(tmp_path, scenario)


def test_block(tmp_path):
    """Report #15: blocking ends follows, hides each other and stops follows / messages."""
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ann.req("post", "/api/users/ben/follow")
        await ben.req("post", "/api/users/ann/follow")
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]
        post = (await ann.req("post", "/api/posts", json={"text": "hi"}))["post"]
        await cat.req("post", "/api/users/ann/follow")
        await ben.req("post", f"/api/posts/{post['id']}/comments", json={"text": "von ben"})
        await cat.req("post", f"/api/posts/{post['id']}/comments", json={"text": "von cat"})

        prof = (await ann.req("post", "/api/users/ben/block"))["profile"]
        assert prof["blocked"] and not prof["following"] and not prof["follows_me"]
        assert [u["username"] for u in (await ann.req("get", "/api/blocks"))["users"]] == ["ben"]
        # ben: ann doesn't exist any more, can't follow or message her, doesn't see her post
        await ben.req("get", "/api/users/ann", status=404)
        await ben.req("post", "/api/users/ann/follow", status=404)
        await ben.req("post", f"/api/conversations/{conv['id']}/messages", json={"text": "hey"}, status=403)
        await ben.req("get", f"/api/posts/{post['id']}", status=404)
        assert (await ben.req("get", "/api/users/search?q=ann"))["users"] == []
        # ann: ben's comment is hidden, cat's stays; ann can't follow ben while blocking
        assert [c["text"] for c in (await ann.req("get", f"/api/posts/{post['id']}"))["post"]["comment_list"]] == ["von cat"]
        await ann.req("post", "/api/users/ben/follow", status=403)
        # unblocking: back to normal (follows stay ended)
        prof = (await ann.req("delete", "/api/users/ben/block"))["profile"]
        assert not prof["blocked"] and not prof["following"]
        assert (await ben.req("get", "/api/users/ann"))["profile"]["username"] == "ann"
    run(tmp_path, scenario)


def test_message_reactions(tmp_path):
    """Report #13: an emoji per person on a message; the same one again takes it back."""
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("post", "/api/users/ben/follow")
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]
        msg = (await ann.req("post", f"/api/conversations/{conv['id']}/messages", json={"text": "Servus"}))
        mid = (await ann.req("get", f"/api/conversations/{conv['id']}/messages"))["messages"][0]["id"]
        r = await ben.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "❤️"})
        assert r["reactions"] == [{"emoji": "❤️", "count": 1, "mine": True}]
        await ann.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "❤️"})
        await ann.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "💩"}, status=400)
        page = await ann.req("get", f"/api/conversations/{conv['id']}/messages")
        assert page["messages"][0]["reactions"] == [{"emoji": "❤️", "count": 2, "mine": True}]
        # polling (after=) brings the reactions of the latest messages along
        page = await ann.req("get", f"/api/conversations/{conv['id']}/messages?after={mid}")
        assert page["messages"] == [] and page["reactions"][str(mid)][0]["count"] == 2
        # another emoji replaces mine; the same again takes it back
        r = await ben.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "😂"})
        assert sorted((e["emoji"], e["count"]) for e in r["reactions"]) == [("❤️", 1), ("😂", 1)]
        r = await ben.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "😂"})
        assert r["reactions"] == [{"emoji": "❤️", "count": 1, "mine": False}]
        # who reacted with what (report #63: a long press on the reactions), newest first
        await ben.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "🔥"})
        who = (await ann.req("get", f"/api/messages/{mid}/reactions"))["reactions"]
        assert [(x["user"]["username"], x["emoji"]) for x in who] == [("ben", "🔥"), ("ann", "❤️")]
        # not a member: no reacting, no list
        cat = await user(c, "cat")
        await cat.req("put", f"/api/messages/{mid}/reaction", json={"emoji": "❤️"}, status=404)
        await cat.req("get", f"/api/messages/{mid}/reactions", status=404)
        await ann.req("delete", "/api/account")           # cascades without errors
    run(tmp_path, scenario)


def test_post_location_and_place_search(tmp_path, monkeypatch):
    """A post can have a place (picked from the search or just typed)."""
    from server import places
    calls = []
    monkeypatch.setattr(places, "fetch_places", lambda q, lang: calls.append((q, lang)) or
                        [dict(name="Englischer Garten", detail="München, Bayern, Deutschland", lat=48.16, lon=11.6)])

    async def scenario(app, c):
        ann = await user(c, "ann")
        assert (await ann.req("get", "/api/places?q=e"))["places"] == []                 # too short: no search
        hits = (await ann.req("get", "/api/places?q=englischer"))["places"]
        assert hits[0]["name"] == "Englischer Garten" and calls == [("englischer", "en")]
        post = (await ann.req("post", "/api/posts", json={"text": "Lauf", "location": {**hits[0]}}))["post"]
        assert post["location"] == {"name": "Englischer Garten", "lat": 48.16, "lon": 11.6}
        post = (await ann.req("post", "/api/posts", json={"text": "Zuhause", "location": {"name": "  Bei Oma  ", "lat": 999}}))["post"]
        assert post["location"] == {"name": "Bei Oma", "lat": None, "lon": None}
        assert (await ann.req("post", "/api/posts", json={"text": "ohne"}))["post"]["location"] is None
    run(tmp_path, scenario)


def test_song_search_and_preview(tmp_path, monkeypatch):
    """A post's sound from Deezer: search, then the preview MP3 through our server."""
    from server import deezer
    monkeypatch.setattr(deezer, "search", lambda q: [dict(id=3135556, title="Harder", artist="Daft Punk", cover=None, duration=30)])
    monkeypatch.setattr(deezer, "preview", lambda tid: (b"ID3fake-mp3", "Daft Punk – Harder") if tid == 3135556 else None)

    async def scenario(app, c):
        ann = await user(c, "ann")
        assert (await ann.req("get", "/api/songs?q=d"))["songs"] == []
        hits = (await ann.req("get", "/api/songs?q=daft"))["songs"]
        assert hits[0]["title"] == "Harder"
        r = await c.get("/api/songs/3135556/preview")
        assert r.status_code == 200 and r.content_type == "audio/mpeg" and await r.get_data() == b"ID3fake-mp3"
        from urllib.parse import unquote
        assert unquote(r.headers["X-Song-Title"]) == "Daft Punk – Harder"
        assert (await c.get("/api/songs/1/preview")).status_code == 404
    run(tmp_path, scenario)


def test_videos_are_cut_to_60_seconds(tmp_path):
    """Videos keep at most 60 s (report #69; it was 30 s) - a story with one runs as long as the video."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "long.mp4")
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
                        "-t", "65", "-c:v", "libx264", out], capture_output=True, check=True)
        raw = open(out, "rb").read()

    async def scenario(app, c):
        ann = await user(c, "ann")
        media = (await ann.req("post", "/api/media", data=raw, ctype="video/mp4"))["media"]
        assert media["kind"] == "video" and 59 <= media["duration"] <= 60.1
        st = (await ann.req("post", "/api/stories", json={"media_id": media["id"]}))["story"]
        assert 59 <= st["duration"] <= 60.1
    run(tmp_path, scenario)


def test_rules_made_public_show_up_in_the_feed_and_come_up_again_when_changed(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        rules = (await ann.req("get", "/api/state"))["state"]["rules"]
        sport = next(r for r in rules if r["kind"] == "acc" and r["mode"] == "constant")
        body = {k: sport[k] for k in ("kind", "name", "icon", "mode", "base")}
        # rules that were public before don't flood the feed; making one public does
        assert (await ben.req("get", "/api/feed"))["posts"] == []
        await ann.req("put", f"/api/rules/{sport['id']}", json={**body, "public": True})
        feed = (await ben.req("get", "/api/feed"))["posts"]
        assert [(p["kind"], p["rule"]["name"], p["rule"]["updated"]) for p in feed] == [("rule", sport["name"], False)]
        assert (await ann.req("get", "/api/feed"))["posts"][0]["rule"]["id"] == sport["id"]       # mine too
        # a newer post comes on top - until the rule changes again
        await ann.req("post", "/api/posts", json={"text": "Hallo"})
        assert [p["kind"] for p in (await ben.req("get", "/api/feed"))["posts"]] == ["post", "rule"]
        await ann.req("put", f"/api/rules/{sport['id']}", json={**body, "base": 300, "public": True})
        feed = (await ben.req("get", "/api/feed"))["posts"]
        assert [p["kind"] for p in feed] == ["rule", "post"] and feed[0]["rule"]["updated"] is True
        assert feed[0]["rule"]["base"] == 300
        # paging follows the same order
        page2 = (await ben.req("get", f"/api/feed?before={feed[0]['id']}"))["posts"]
        assert [p["kind"] for p in page2] == ["post"]
        # not on the profile grid / count; private again or deleted: gone
        assert [p["kind"] for p in (await ben.req("get", "/api/users/ann/posts"))["posts"]] == ["post"]
        assert (await ben.req("get", "/api/users/ann"))["profile"]["posts"] == 1
        await ann.req("put", f"/api/rules/{sport['id']}", json={**body, "public": False})
        assert [p["kind"] for p in (await ben.req("get", "/api/feed"))["posts"]] == ["post"]
        new = (await ann.req("post", "/api/rules", json={"kind": "acc", "name": "Laufen", "icon": "star", "mode": "constant",
                                                        "base": 40, "public": True}))["state"]["rules"]
        laufen = next(r for r in new if r["name"] == "Laufen")
        assert (await ben.req("get", "/api/feed"))["posts"][0]["rule"]["name"] == "Laufen"
        await ann.req("delete", f"/api/rules/{laufen['id']}")
        assert [p["kind"] for p in (await ben.req("get", "/api/feed"))["posts"]] == ["post"]
    run(tmp_path, scenario)


def test_rankings_by_today(tmp_path):
    """Reports #40, #45: leaderboards and group rankings by today's change (since 0:00 Berlin, +/-)."""
    async def scenario(app, c):
        from datetime import timedelta
        from ..db import Event, utcnow
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("post", "/api/users/ben/follow")
        await ben.req("post", "/api/users/ann/follow")
        st = lambda who: who.req("get", "/api/state")
        a_rule = next(r for r in (await st(ann))["state"]["rules"] if r["kind"] == "acc")
        b_rule = next(r for r in (await st(ben))["state"]["rules"] if r["kind"] == "acc")
        for _ in range(3):
            await ann.req("post", f"/api/rules/{a_rule['id']}/trigger")        # ann: a lot, but long ago
        await ben.req("post", f"/api/rules/{b_rule['id']}/trigger")            # ben: a little, just now
        async with app.db.session() as s:
            from sqlalchemy import select, update
            from ..db import User
            ann_id = (await s.scalar(select(User).where(User.username == "ann"))).id
            await s.execute(update(Event).where(Event.user_id == ann_id).values(created_at=utcnow() - timedelta(hours=30)))
            await s.commit()
        total = (await ann.req("get", "/api/leaderboard?scope=friends"))["entries"]
        assert [e["user"]["username"] for e in total] == ["ann", "ben"] and "delta" not in total[0]
        day = (await ann.req("get", "/api/leaderboard?scope=friends&period=today"))["entries"]
        assert day == (await ann.req("get", "/api/leaderboard?scope=friends&period=24h"))["entries"]      # older apps
        assert [e["user"]["username"] for e in day] == ["ben", "ann"]
        assert day[0]["delta"] > 0 and day[1]["delta"] == 0
        glob = await ann.req("get", "/api/leaderboard?period=24h")
        assert glob["entries"][0]["user"]["username"] == "ben" and glob["me"]["delta"] == 0
        grp = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben"]}))["conversation"]["id"]
        ranked = (await ann.req("get", f"/api/conversations/{grp}/ranking?period=24h"))["entries"]
        assert [e["user"]["username"] for e in ranked] == ["ben", "ann"]
    run(tmp_path, scenario)


def test_who_read_my_message(tmp_path):
    """Report #44: the readers of a message in a group."""
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ben.req("post", "/api/users/ann/follow")
        await cat.req("post", "/api/users/ann/follow")
        grp = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben", "cat"]}))["conversation"]["id"]
        msg = (await ann.req("post", f"/api/conversations/{grp}/messages", json={"text": "Hallo"}))["message"]
        r = await ann.req("get", f"/api/messages/{msg['id']}/readers")
        assert r["readers"] == [] and r["unread"] == 2
        await ben.req("get", f"/api/conversations/{grp}/messages")                  # ben opens the chat
        r = await ann.req("get", f"/api/messages/{msg['id']}/readers")
        assert [u["username"] for u in r["readers"]] == ["ben"] and r["unread"] == 1
        stranger = await user(c, "dan")
        await stranger.req("get", f"/api/messages/{msg['id']}/readers", status=404)
    run(tmp_path, scenario)

def test_activity_comment_preview(tmp_path):
    """Activities about a comment carry a short preview of it: one line, cut after PREVIEW_CHARS."""
    from ..social import PREVIEW_CHARS

    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        pid = (await ann.req("post", "/api/posts", json={"text": "hi"}))["post"]["id"]
        long = "wow\n\n" + "sehr " * 40
        cid = (await ben.req("post", f"/api/posts/{pid}/comments", json={"text": long}))["post"]["comment_list"][0]["id"]
        notes = {n["kind"]: n for n in (await ann.req("get", "/api/notifications"))["items"]}
        preview = notes["comment"]["comment"]
        assert preview.startswith("wow sehr sehr") and "\n" not in preview
        assert preview.endswith("…") and len(preview) <= PREVIEW_CHARS + 1
        assert notes["follow"]["comment"] is None                       # not about a comment
        # a liked comment: the owner sees which one
        await ann.req("post", f"/api/comments/{cid}/like")
        clike = next(n for n in (await ben.req("get", "/api/notifications"))["items"] if n["kind"] == "clike")
        assert clike["comment"] == preview
        # a mention in a comment shows it; a mention in a caption has no comment
        cat = await user(c, "cat")
        await ben.req("post", f"/api/posts/{pid}/comments", json={"text": "@cat schau mal"})
        await ann.req("post", "/api/posts", json={"text": "danke @cat"})
        mentions = [n["comment"] for n in (await cat.req("get", "/api/notifications"))["items"] if n["kind"] == "mention"]
        assert mentions == [None, "@cat schau mal"]                     # newest first
    run(tmp_path, scenario)
