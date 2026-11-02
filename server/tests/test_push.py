"""Real-time pushes: who gets one for a chat message or an activity, and what the PWA shows."""
from sqlalchemy import select

from ..push import Dispatcher, PushToken
from .test_social import run, user


class FakeFcm:
    def __init__(self):
        self.sent, self.dead = [], set()

    def send(self, token, data):
        self.sent.append((token, data))
        return token not in self.dead


class FakeWeb:
    def __init__(self):
        self.sent = []

    def send(self, sub, payload):
        self.sent.append((sub["endpoint"], payload))
        return True


def sub(name):
    return {"endpoint": f"https://push.example/{name}", "keys": {"p256dh": "x", "auth": "y"}}


def test_push_for_messages_and_activity(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        assert (await c.get("/api/push/vapid")).status_code == 200
        await ben.req("post", "/api/push/register", json={"platform": "fcm", "token": "ben-phone"})
        await ben.req("post", "/api/push/register", json={"platform": "web", "subscription": sub("ben"), "lang": "de"})
        await cat.req("post", "/api/push/register", json={"platform": "web", "subscription": sub("cat"), "lang": "en"})
        await ann.req("post", "/api/push/register", json={"platform": "web", "subscription": sub("ann")})
        await ben.req("post", "/api/push/register", json={"platform": "nope"}, status=400)
        fcm, web = FakeFcm(), FakeWeb()
        d = Dispatcher(app.db, fcm, web)
        assert await d.tick() == 0                       # first round: starts from now
        for a, b in (("ann", "ben"), ("ben", "ann"), ("ann", "cat"), ("cat", "ann")):
            await {"ann": ann, "ben": ben, "cat": cat}[a].req("post", f"/api/users/{b}/follow")
        fcm.sent.clear(); web.sent.clear()
        await d.tick()
        # activity: "ann folgt dir jetzt." for ben (German), in English for cat; nothing for ann's own actions
        bodies = {e: p["body"] for e, p in web.sent}
        assert bodies["https://push.example/ben"] == "ann folgt dir jetzt."
        assert bodies["https://push.example/cat"] == "ann started following you."
        assert fcm.sent == [("ben-phone", {"type": "check"})]

        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        fcm.sent.clear(); web.sent.clear()
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"text": "Servus"})
        await d.tick()
        assert web.sent == [("https://push.example/ben", {"title": "ann", "body": "Servus", "tag": f"chat-{conv}",
                                                          "url": f"/app/?open=chat:{conv}"})]
        assert fcm.sent == [("ben-phone", {"type": "check"})]       # not to ann: her own message

        # a muted group stays quiet, unless someone tags me
        group = (await ann.req("post", "/api/conversations/group", json={"name": "Lauftreff", "usernames": ["ben", "cat"]}))["conversation"]["id"]
        await ben.req("put", f"/api/conversations/{group}/mute", json={"muted": True})
        web.sent.clear()
        await ann.req("post", f"/api/conversations/{group}/messages", json={"text": "Wer läuft mit?"})
        await d.tick()
        assert [e for e, _ in web.sent] == ["https://push.example/cat"]
        assert web.sent[0][1]["body"] == "ann: Wer läuft mit?" and web.sent[0][1]["title"] == "Lauftreff"
        web.sent.clear()
        await ann.req("post", f"/api/conversations/{group}/messages", json={"text": "@ben du auch?"})
        await d.tick()
        ben_push = dict(web.sent)["https://push.example/ben"]
        assert ben_push["title"] == "ann hat dich erwähnt · Lauftreff"

        # read in the app before the round: no push
        web.sent.clear()
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"text": "Gelesen?"})
        await ben.req("get", f"/api/conversations/{conv}/messages")
        await d.tick()
        assert web.sent == []

        # a dead FCM token is dropped
        fcm.dead.add("ben-phone")
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"text": "Hallo?"})
        await d.tick()
        async with app.db.session() as s:
            assert not list(await s.scalars(select(PushToken).where(PushToken.token == "ben-phone")))
        # logged out: cat's browser gets nothing any more
        await cat.req("post", "/api/auth/logout")
        web.sent.clear()
        await ann.req("post", f"/api/conversations/{group}/messages", json={"text": "Noch wer da?"})
        await d.tick()
        assert "https://push.example/cat" not in dict(web.sent)
        await ben.req("delete", "/api/push/register", json={"endpoint": "https://push.example/ben"})
        async with app.db.session() as s:
            assert [t.token for t in await s.scalars(select(PushToken).where(PushToken.user_id != 0))] == \
                ["https://push.example/cat", "https://push.example/ann"]
    run(tmp_path, scenario)


def test_web_push_signs_with_the_stored_key(monkeypatch):
    """The real pywebpush path: the PEM from the config table must sign and encrypt (it once
    crashed every round with "Could not deserialize key data")."""
    import asyncio
    import base64

    import requests
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    from ..db import Database
    from ..push import WebPushSender, vapid_keys

    async def keys():
        db = Database("sqlite+aiosqlite:///:memory:")
        await db.create_all()
        async with db.session() as s:
            return await vapid_keys(s)
    pem, _ = asyncio.run(keys())

    browser = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
    sub = {"endpoint": "https://push.example/x", "keys": {"p256dh": b64(browser), "auth": b64(b"0123456789abcdef")}}
    seen = []

    class Resp:
        status_code, text = 201, ""

    def post(url, data=None, headers=None, timeout=None, **kw):
        seen.append((url, headers))
        return Resp()
    monkeypatch.setattr(requests, "post", post)
    assert WebPushSender(pem).send(sub, {"title": "ann", "body": "Servus"})
    assert seen and seen[0][0] == "https://push.example/x" and seen[0][1]["authorization"].startswith("vapid t=")
