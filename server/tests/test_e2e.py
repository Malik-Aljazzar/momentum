"""End-to-end encrypted chats (server/e2e.py): the server keeps ciphertext only, readers open it."""
import asyncio
import json
import os
from datetime import timedelta

from cryptography.hazmat.primitives import serialization

from .. import chat
from ..app import create_app
from ..blobs import MemoryStore
from ..db import Message
from ..push import Dispatcher
from . import e2e_crypto as ec
from .test_push import FakeFcm, FakeWeb, sub
from .test_social import user


def run(tmp_path, scenario):
    """Like the real server: new chats are encrypted."""
    async def go():
        app = create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(), e2e_default=True)
        async with app.test_app() as ta:
            await scenario(app, ta.test_client())
    asyncio.run(go())


async def keyed(c, name):
    """A player with a key (as the apps make it on the first start)."""
    u = await user(c, name)
    u.key, u.recovery = ec.new_key(), os.urandom(32)
    await u.req("put", "/api/e2e/me", json={"public": ec.public_b64(u.key), "backup": ec.backup(u.key, u.recovery)})
    return u


async def ids(u, conv_id):
    keys = (await u.req("get", f"/api/conversations/{conv_id}/keys"))["keys"]
    return {k["username"]: k for k in keys}


async def send(u, conv_id, text, readers=None, status=200, media=None, kind=None, **extra):
    """An encrypted message from u (for every member with a key, or the given readers)."""
    keys = await ids(u, conv_id)
    readers = readers or {k["user_id"]: k["public"] for k in keys.values() if k["public"]}
    env = ec.encrypt(u.key, conv_id, readers, {"t": text, "m": media}, {"t": text[:180], "k": kind})
    return await u.req("post", f"/api/conversations/{conv_id}/messages", json={"e2e": env, **extra}, status=status)


def test_keys_backup_and_a_second_device(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        assert await ann.req("get", "/api/e2e/me") == {"public": None, "backup": None}
        key, recovery = ec.new_key(), os.urandom(32)
        await ann.req("put", "/api/e2e/me", json={"public": "AAAA", "backup": "AAAA"}, status=400)        # not a P-256 point
        await ann.req("put", "/api/e2e/me", json={"public": ec.public_b64(key), "backup": ec.backup(key, recovery)})
        # a new phone: the backup + the recovery key give back the same key - the server only has ciphertext
        mine = await ann.req("get", "/api/e2e/me")
        assert ec.raw_public(ec.restore(mine["backup"], recovery)) == ec.raw_public(key)
        # a second device must not quietly make its own key ...
        other = ec.new_key()
        await ann.req("put", "/api/e2e/me", json={"public": ec.public_b64(other), "backup": ec.backup(other, recovery)}, status=409)
        # ... only a deliberate reset (recovery key lost) replaces it
        await ann.req("put", "/api/e2e/me", json={"public": ec.public_b64(other), "backup": ec.backup(other, recovery), "replace": True})
        assert (await ann.req("get", "/api/e2e/me"))["public"] == ec.public_b64(other)
    run(tmp_path, scenario)


def test_encrypted_direct_chat(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await keyed(c, "ann"), await keyed(c, "ben"), await keyed(c, "cat")
        await ben.req("post", "/api/users/ann/follow")
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]
        assert conv["e2e"] and conv["can_switch_e2e"]
        cid = conv["id"]
        # plain text is refused in an encrypted chat (an app with an old setting reloads and encrypts)
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"text": "hi"}, status=409)
        keys = await ids(ann, cid)
        assert set(keys) == {"ann", "ben"} and keys["ben"]["public"] == ec.public_b64(ben.key)
        # only members may read it (409: the app loads the keys again); a key that isn't the sender's current one is refused
        await send(ann, cid, "nope", readers={999: ec.public_b64(cat.key)}, status=409)
        stale = ec.new_key()
        env = ec.encrypt(stale, cid, {keys["ben"]["user_id"]: keys["ben"]["public"]}, {"t": "x"}, {"t": "x"})
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"e2e": env}, status=409)
        # a real one, tagging ben
        sent = (await send(ann, cid, "Geheim, @ben!", mentions=["ben"]))["message"]
        assert sent["text"] == "" and sent["e2e"]["key"]
        async with app.db.session() as s:
            row = await s.get(Message, sent["id"])
            assert row.text == "" and "Geheim" not in row.e2e and row.mentions == f",{keys['ben']['user_id']},"
        got = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]
        assert got["mentions_me"] and set(got["e2e"]) == {"v", "eph", "spk", "pv", "ct", "key"}
        assert ec.decrypt(got["e2e"], ben.key, cid, keys["ben"]["user_id"])["t"] == "Geheim, @ben!"
        # ann reads her own message on another phone too; the preview in the chat list
        mine = (await ann.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]
        assert ec.decrypt(mine["e2e"], ann.key, cid, keys["ann"]["user_id"])["t"] == "Geheim, @ben!"
        last = (await ben.req("get", "/api/conversations"))["conversations"][0]["last"]
        assert "ct" not in last["e2e"] and ec.decrypt(last["e2e"], ben.key, cid, keys["ben"]["user_id"], "pv")["t"] == "Geheim, @ben!"
        # an answer quotes it (the preview, encrypted)
        ans = (await send(ben, cid, "ok", reply_to=sent["id"]))["message"]
        assert ec.decrypt(ans["reply"]["e2e"], ben.key, cid, keys["ben"]["user_id"], "pv")["t"] == "Geheim, @ben!"
    run(tmp_path, scenario)


def test_switching_the_encryption(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await keyed(c, "ann"), await keyed(c, "ben"), await keyed(c, "cat")
        for u in (ben, cat):
            await u.req("post", "/api/users/ann/follow")
        dm = (await ben.req("post", "/api/conversations/direct", json={"username": "ann"}))["conversation"]["id"]
        # direct chat: either of the two switches it; the chat shows who (a line, not an unread message)
        conv = (await ben.req("put", f"/api/conversations/{dm}/e2e", json={"on": False}))["conversation"]
        assert not conv["e2e"]
        line = (await ann.req("get", f"/api/conversations/{dm}/messages"))["messages"][-1]
        assert line["kind"] == "e2e_off" and line["user"]["username"] == "ben"
        assert (await ann.req("get", "/api/conversations"))["conversations"][0]["unread"] == 0
        # off: plain again, envelopes refused
        await ann.req("post", f"/api/conversations/{dm}/messages", json={"text": "offen"})
        await send(ann, dm, "x", status=409)
        await ann.req("put", f"/api/conversations/{dm}/e2e", json={"on": True})
        # groups: only admins
        grp = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben", "cat"]}))["conversation"]
        assert grp["e2e"] and grp["can_switch_e2e"]
        ben_view = (await ben.req("get", f"/api/conversations/{grp['id']}"))["conversation"]
        assert not ben_view["can_switch_e2e"]
        await ben.req("put", f"/api/conversations/{grp['id']}/e2e", json={"on": False}, status=403)
        await ann.req("put", f"/api/conversations/{grp['id']}/e2e", json={"on": False})
        # a story answer in an encrypted chat goes the chat way (with the story attached)
        story = (await ann.req("post", "/api/media", data=_png(), ctype="image/png"))["media"]
        st = (await ann.req("post", "/api/stories", json={"media_id": story["id"]}))["story"]
        await ben.req("post", f"/api/stories/{st['id']}/reply", json={"text": "wow"}, status=409)
        msg = (await send(ben, dm, "wow", share={"kind": "story", "id": st["id"]}))["message"]
        assert msg["share"]["kind"] == "story"
    run(tmp_path, scenario)


def _png():
    from .test_social import png
    return png(40, 30)


def test_encrypted_photos_and_voice(tmp_path):
    async def scenario(app, c):
        ann, ben = await keyed(c, "ann"), await keyed(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        cid = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        mkey = os.urandom(32)
        full, small = ec.seal(mkey, b"full picture", None), ec.seal(mkey, b"small picture", None)
        md = (await ann.req("post", "/api/media/encrypted?kind=image&width=1080&height=720", data=full,
                            ctype="application/octet-stream"))["media"]
        md = (await ann.req("post", f"/api/media/{md['id']}/thumb", data=small, ctype="application/octet-stream"))["media"]
        assert md["encrypted"] and md["thumb"] != md["url"] and md["width"] == 1080
        await ann.req("post", "/api/media/encrypted?kind=video", data=full, ctype="application/octet-stream", status=400)
        # a plain photo doesn't go into an encrypted chat
        plain = (await ann.req("post", "/api/media", data=_png(), ctype="image/png"))["media"]
        await send(ann, cid, "", media_id=plain["id"], status=400)
        sent = (await send(ann, cid, "", media_id=md["id"], kind="image", media={"k": ec.b64(mkey), "mime": "image/jpeg"}))["message"]
        # the blobs are stored as they came: only the key in the message opens them
        blob = await c.get(sent["media"]["thumb"])
        assert ec.unseal(mkey, await blob.get_data(), None) == b"small picture"
        # after two weeks only the small copy stays (still encrypted)
        async with app.db.session() as s:
            later = (await s.get(Message, sent["id"])).created_at + timedelta(days=chat.CHAT_IMAGE_DAYS, hours=1)
            assert await chat.archive_chat_images(s, app.blobs, later) == 1
            await s.commit()
        got = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]["media"]
        assert got["archived"] and got["url"] == got["thumb"]
        voice = (await ann.req("post", "/api/media/encrypted?kind=audio&duration=3.5", data=full, ctype="application/octet-stream"))["media"]
        await send(ann, cid, "", media_id=voice["id"], kind="audio", media={"k": ec.b64(mkey), "mime": "audio/mp4"})
    run(tmp_path, scenario)


def test_push_and_updates_carry_the_encrypted_preview(tmp_path):
    async def scenario(app, c):
        ann, ben = await keyed(c, "ann"), await keyed(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        await ben.req("post", "/api/push/register", json={"platform": "web", "subscription": sub("ben"), "lang": "de"})
        fcm, web = FakeFcm(), FakeWeb()
        d = Dispatcher(app.db, fcm, web)
        await d.tick()
        cid = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        await ben.req("put", f"/api/conversations/{cid}/mute", json={"muted": True})
        await send(ann, cid, "nur für dich")                      # muted: quiet
        await send(ann, cid, "psst @ben", mentions=["ben"])      # ... but a tag comes through
        await d.tick()
        assert len(web.sent) == 1
        payload = web.sent[0][1]
        # the server sends the encrypted preview, the phone opens it; the plain body is only the fallback
        assert payload["body"] == "Neue Nachricht" and "psst" not in json.dumps(payload)
        assert payload["title"].startswith("ann hat dich erwähnt")
        ben_id = (await ids(ben, cid))["ben"]["user_id"]
        assert ec.decrypt(payload["e2e"], ben.key, cid, ben_id, "pv")["t"] == "psst @ben"
        # Android asks /api/updates: the preview comes along there too
        up = await ben.req("get", "/api/updates?after_message=0&after_notification=0")
        assert [m["mention"] for m in up["messages"]] == [True]
        assert ec.decrypt(up["messages"][0]["e2e"], ben.key, cid, ben_id, "pv")["t"] == "psst @ben"
    run(tmp_path, scenario)


def test_messages_fit_the_members_and_keys(tmp_path):
    """A message is for everyone in the chat as it is now: the apps send the fingerprint of the keys they
    encrypted for; a stale one, a missing member or a reader who isn't one any more -> 409 (load again)."""
    async def scenario(app, c):
        ann, ben, cat, dan = await keyed(c, "ann"), await keyed(c, "ben"), await keyed(c, "cat"), await user(c, "dan")
        for u in (ben, cat, dan):
            await u.req("post", "/api/users/ann/follow")
        cid = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben"]}))["conversation"]["id"]
        before = await ann.req("get", f"/api/conversations/{cid}/keys")
        readers = {k["user_id"]: k["public"] for k in before["keys"]}
        env = ec.encrypt(ann.key, cid, readers, {"t": "x"}, {"t": "x"})
        # the ephemeral key must be a point on the curve (readers do ECDH with it)
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"e2e": dict(env, eph=ec.b64(b"\x04" + bytes(64)))}, status=400)
        # the fingerprint of the keys it was encrypted for comes along; a stale one is refused
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"e2e": dict(env, kh="0000000000000000")}, status=409)
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"e2e": dict(env, kh=before["hash"])})
        # cat joins: a message that isn't encrypted for her (an app with the old key list) is refused ...
        await ann.req("post", f"/api/conversations/{cid}/members", json={"usernames": ["cat"]})
        after = await ann.req("get", f"/api/conversations/{cid}/keys")
        assert after["hash"] != before["hash"]
        stale = ec.encrypt(ann.key, cid, readers, {"t": "y"}, {"t": "y"})
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"e2e": stale}, status=409)
        # ... one for everyone with a key goes through (dan has none: he can't read it, that's known)
        await ann.req("post", f"/api/conversations/{cid}/members", json={"usernames": ["dan"]})
        await send(ann, cid, "alle da")
        cat_id = (await ids(ann, cid))["cat"]["user_id"]
        got = (await cat.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]
        assert ec.decrypt(got["e2e"], cat.key, cid, cat_id)["t"] == "alle da"
        # ben leaves: a message still encrypted for him is refused too, then it goes without him
        await ben.req("delete", f"/api/conversations/{cid}/members/me")
        everyone = {k["user_id"]: k["public"] for k in (await ann.req("get", f"/api/conversations/{cid}/keys"))["keys"] if k["public"]}
        assert len(everyone) == 2
        env = ec.encrypt(ann.key, cid, {**everyone, **readers}, {"t": "z"}, {"t": "z"})
        await ann.req("post", f"/api/conversations/{cid}/messages", json={"e2e": env}, status=409)
        await send(ann, cid, "ohne ben")
    run(tmp_path, scenario)


def test_vector_for_the_apps():
    """The fixed message the apps' tests open (web/tests/unit/e2e-vector.json, android test resources)."""
    root = os.path.join(os.path.dirname(__file__), "..", "..")
    for path in ("web/tests/unit/e2e-vector.json", "android/app/src/test/resources/e2e-vector.json"):
        v = json.load(open(os.path.join(root, path), encoding="utf-8"))
        reader = serialization.load_der_private_key(ec.unb64(v["reader_pkcs8"]), None)
        assert ec.decrypt(v["envelope"], reader, v["conversation"], v["reader"])["t"] == v["text"]
        assert ec.raw_public(ec.restore(v["backup"], ec.unb64(v["recovery"]))) == ec.raw_public(reader)
        assert ec.unseal(ec.unb64(v["media_key"]), ec.unb64(v["media"]), None) == ec.unb64(v["media_plain"])
        assert ec.safety_code(v["reader_public"]) == v["reader_safety_code"]
