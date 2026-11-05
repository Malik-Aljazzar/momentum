"""Push notifications, in real time.

    POST   /api/push/register   {"platform": "fcm", "token": ...} | {"platform": "web", "subscription": {...}}, "lang"
    DELETE /api/push/register   {"token": ...} | {"endpoint": ...}
    GET    /api/push/vapid      the Web Push public key (the PWA subscribes with it)

A loop in the server looks every few seconds for new chat messages and activity and pushes them:
  - Android (Firebase Cloud Messaging): a silent wake-up; the app then runs its usual update check
    (/api/updates) and builds the notifications itself (its texts, mute rules, no doubles).
  - PWA (Web Push, iOS 16.4+ when installed): the notification itself, in the user's language
    (iOS shows every push, so the text comes from here). An encrypted message comes with its preview
    for this reader (still encrypted); the service worker opens it with the key on the phone and shows
    the text - the server never sees it (server/e2e.py).
FCM needs the Firebase service account key (secrets/firebase-admin.json, /data/firebase-admin.json
or $FIREBASE_CREDENTIALS); without it Android keeps polling. Web Push keys are made on first start.
"""
import asyncio
import base64
import json
import logging
import os
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

from quart import g, jsonify, request
from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, delete, func, select
from sqlalchemy.orm import Mapped, mapped_column

from .db import AuthToken, Base, Challenge, ChallengeParticipant, Config, Conversation, ConversationMember, Media, Message, \
    Notification, User, utcnow
from .e2e import envelope_for, mentioned_ids
from .mentions import render_one

log = logging.getLogger("momentum.push")
TICK_S = 3
ROOT = Path(__file__).resolve().parent.parent


class PushToken(Base):
    """A device that gets pushes: an FCM token (Android) or a Web Push subscription (PWA)."""
    __tablename__ = "push_tokens"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    platform: Mapped[str] = mapped_column(String(8))                 # fcm | web
    token: Mapped[str] = mapped_column(Text, unique=True)            # FCM token / Web Push endpoint
    keys: Mapped[str | None] = mapped_column(Text, nullable=True)    # Web Push: subscription JSON
    lang: Mapped[str] = mapped_column(String(2), default="de")
    # the login session it belongs to: logged out (or the session expired) -> no more pushes
    auth: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    failures: Mapped[int] = mapped_column(Integer, default=0)


# ---------------------------------------------------------------- texts (PWA notifications)
# Same wording as Strings.kt notification()/voiceMessage/photo/... - keep them in sync.

TEXTS = {
    "de": dict(follow="folgt dir jetzt.", like="gefällt dein Post.", comment="hat deinen Post kommentiert.",
               reply="hat auf deinen Kommentar geantwortet.",
               clike="gefällt dein Kommentar.", mention="hat dich erwähnt.", tag="hat dich in einem Post markiert.",
               request="möchte dir folgen.", accepted="hat deine Anfrage bestätigt.", ch_cancel="hat eine Challenge abgebrochen.",
               ch_end="Eine Challenge ist vorbei.", voice="♪ Sprachnachricht", photo="▣ Foto", video="▶ Video", share="↗ geteilt",
               mentioned="{sender} hat dich erwähnt", activity="Momentum", encrypted="Neue Nachricht",
               sub_rule="hat deine Regel „{rule}“ abonniert.", sub_rule_group="hat deine Regel „{rule}“ für die Gruppe {group} abonniert.",
               sub_decay="folgt jetzt deinem Decay.", sub_decay_group="hat deinen Decay in die Gruppe {group} übernommen.",
               call_voice="Sprachanruf", call_video="Videoanruf", call_incoming_voice="Eingehender Sprachanruf",
               call_incoming_video="Eingehender Videoanruf", call_missed_voice="Verpasster Sprachanruf",
               call_missed_video="Verpasster Videoanruf"),
    "en": dict(follow="started following you.", like="liked your post.", comment="commented on your post.",
               reply="replied to your comment.",
               clike="liked your comment.", mention="mentioned you.", tag="tagged you in a post.",
               request="wants to follow you.", accepted="accepted your request.", ch_cancel="cancelled a challenge.",
               ch_end="A challenge is over.", voice="♪ Voice message", photo="▣ Photo", video="▶ Video", share="↗ shared",
               mentioned="{sender} mentioned you", activity="Momentum", encrypted="New message",
               sub_rule="subscribed to your rule “{rule}”.", sub_rule_group="subscribed to your rule “{rule}” for the group {group}.",
               sub_decay="now follows your decay.", sub_decay_group="added your decay to the group {group}.",
               call_voice="Voice call", call_video="Video call", call_incoming_voice="Incoming voice call",
               call_incoming_video="Incoming video call", call_missed_voice="Missed voice call",
               call_missed_video="Missed video call"),
}


# ---------------------------------------------------------------- senders

class FcmSender:
    """Firebase Cloud Messaging HTTP v1 with a service account (OAuth token via a signed JWT)."""

    def __init__(self, creds: dict):
        self.creds = creds
        self.project = creds["project_id"]
        self._token, self._expires = None, 0.0

    @classmethod
    def from_env(cls):
        for p in (os.environ.get("FIREBASE_CREDENTIALS"), "/data/firebase-admin.json", ROOT / "secrets" / "firebase-admin.json"):
            if p and Path(p).is_file():
                return cls(json.loads(Path(p).read_text()))
        return None

    def _access_token(self) -> str:
        if self._token and time.time() < self._expires - 60:
            return self._token
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=")
        now = int(time.time())
        head = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = b64(json.dumps({"iss": self.creds["client_email"], "scope": "https://www.googleapis.com/auth/firebase.messaging",
                                 "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600}).encode())
        key = serialization.load_pem_private_key(self.creds["private_key"].encode(), password=None)
        sig = b64(key.sign(head + b"." + claims, padding.PKCS1v15(), hashes.SHA256()))
        body = ("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + (head + b"." + claims + b"." + sig).decode()).encode()
        with urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", body,
                                                           {"Content-Type": "application/x-www-form-urlencoded"}), timeout=15) as r:
            data = json.loads(r.read())
        self._token, self._expires = data["access_token"], time.time() + data.get("expires_in", 3600)
        return self._token

    def send(self, token: str, data: dict, ttl: int = 3600) -> bool:
        """Data-only, high priority (wakes the app). False = the token is dead and should be dropped.
        ttl: a call's ringing is worthless after it stopped (server/calls.py)."""
        msg = {"message": {"token": token, "data": {k: str(v) for k, v in data.items()},
                           "android": {"priority": "HIGH", "ttl": f"{int(ttl)}s"}}}
        req = urllib.request.Request(f"https://fcm.googleapis.com/v1/projects/{self.project}/messages:send",
                                     json.dumps(msg).encode(), {"Content-Type": "application/json",
                                                                "Authorization": "Bearer " + self._access_token()})
        try:
            with urllib.request.urlopen(req, timeout=15):
                return True
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):            # UNREGISTERED / INVALID_ARGUMENT: the app is gone
                return False
            log.warning("fcm %s: %s", e.code, e.read()[:200])
            return True


class WebPushSender:
    def __init__(self, private_pem: str, subject: str = "mailto:admin@momentum.mal-zeta.de"):
        from py_vapid import Vapid
        # pywebpush reads a key *string* as bare base64 DER, not PEM -> hand it the key object
        self.vapid, self.subject = Vapid.from_pem(private_pem.encode()), subject

    def send(self, subscription: dict, payload: dict, ttl: int = 3600, urgency: str | None = None) -> bool:
        from pywebpush import WebPushException, webpush
        try:
            webpush(subscription, json.dumps(payload), vapid_private_key=self.vapid,
                    vapid_claims={"sub": self.subject}, ttl=ttl, timeout=15,
                    headers={"Urgency": urgency} if urgency else None)
            return True
        except WebPushException as e:
            code = getattr(e.response, "status_code", 0)
            if code in (404, 410):              # unsubscribed / expired
                return False
            log.warning("web push %s: %s", code, e)
            return True
        except Exception as e:                  # a broken subscription must not stop the others
            log.warning("web push failed: %s", e)
            return True


async def config(s, key, default=None):
    row = await s.get(Config, key)
    return row.value if row else default


async def set_config(s, key, value):
    row = await s.get(Config, key)
    if row is None:
        s.add(Config(key=key, value=str(value)))
    else:
        row.value = str(value)


async def vapid_keys(s):
    """(private PEM, public key for the browser); made once and kept in the config table
    (SQLite doesn't cut the PEM at the column's 200 characters)."""
    pem = await config(s, "vapid_private")
    if not pem:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        key = ec.generate_private_key(ec.SECP256R1())
        pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
        await set_config(s, "vapid_private", pem)
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(pem.encode(), password=None)
    raw = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    return pem, base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


# ---------------------------------------------------------------- what to push

async def pending(s, after_msg: int, after_note: int):
    """New messages and notifications since the last round: [(user_id, item)], newest ids."""
    out = []
    msgs = list(await s.scalars(select(Message).where(Message.id > after_msg).order_by(Message.id).limit(200)))
    for m in msgs:
        if m.kind:                      # a chat line (encryption switched): no push
            continue
        mentioned = mentioned_ids(m)
        members = await s.scalars(select(ConversationMember).where(ConversationMember.conversation_id == m.conversation_id,
                                                                  ConversationMember.user_id != m.user_id))
        for mem in members:
            # read already (open in the app) or a muted chat (mentions still come through): quiet
            if mem.last_read_id >= m.id or (mem.muted and mem.user_id not in mentioned):
                continue
            out.append((mem.user_id, ("message", m)))
    notes = list(await s.scalars(select(Notification).where(Notification.id > after_note).order_by(Notification.id).limit(200)))
    for n in notes:
        if not n.read:
            out.append((n.user_id, ("note", n)))
    return out, (msgs[-1].id if msgs else after_msg), (notes[-1].id if notes else after_note)


async def web_payload(s, user_id: int, item, lang: str) -> dict:
    t = TEXTS.get(lang, TEXTS["de"])
    kind, obj = item
    if kind == "message":
        conv = await s.get(Conversation, obj.conversation_id)
        sender = (await s.get(User, obj.user_id)).username
        text = (await render_one(s, obj.text)) if obj.text else ""
        if not text:
            media = await s.get(Media, obj.media_id) if obj.media_id else None
            text = (t["voice"] if media and media.kind == "audio" else t["video"] if media and media.kind == "video"
                    else t["photo"] if media else t["share"] if obj.share_kind else "")
        group = conv.kind == "group"
        if user_id in mentioned_ids(obj):
            title = t["mentioned"].format(sender=sender) + (f" · {conv.name}" if group else "")
        else:
            title = conv.name if group else sender
        out = dict(title=title, body=(f"{sender}: " if group else "") + text, tag=f"chat-{conv.id}",
                   url=f"/app/?open=chat:{conv.id}")
        if obj.e2e:
            # the body is what shows if the phone can't open it; the service worker puts in the text
            out.update(body=(f"{sender}: " if group else "") + t["encrypted"],
                       e2e=dict(envelope_for(obj, user_id, full=False), conv=conv.id, reader=user_id, sender=sender if group else "",
                                photo=t["photo"], voice=t["voice"], video=t["video"]))
        return out
    actor = (await s.get(User, obj.actor_id)).username
    body = f"{actor} {t.get(obj.kind, '')}".strip()
    if obj.kind in ("sub_rule", "sub_decay"):
        from .db import Rule
        rule = await s.get(Rule, obj.rule_id) if obj.rule_id else None
        group = await s.get(Conversation, obj.group_id) if obj.group_id else None
        text = t[obj.kind + ("_group" if group else "")]
        body = f"{actor} " + text.format(rule=rule.name if rule else "", group=group.name if group else "")
    if obj.kind == "ch_end" and obj.challenge_id:
        ch = await s.get(Challenge, obj.challenge_id)
        body = f"„{ch.title}“ · {t['ch_end']}" if ch else t["ch_end"]
    url = f"/app/?open=challenge:{obj.challenge_id}" if obj.challenge_id else \
        f"/app/?open=post:{obj.post_id}" if obj.post_id else "/app/?open=notifications"
    return dict(title=t["activity"], body=body, tag=f"note-{obj.id}", url=url)


class Dispatcher:
    def __init__(self, db, fcm=None, web=None):
        self.db, self.fcm, self.web = db, fcm, web

    async def start_marks(self, s):
        """First run: start from now (no flood of old messages)."""
        if await config(s, "push_msg_after") is None:
            await set_config(s, "push_msg_after", await s.scalar(select(func.max(Message.id))) or 0)
            await set_config(s, "push_note_after", await s.scalar(select(func.max(Notification.id))) or 0)

    async def tick(self) -> int:
        """One round; returns how many pushes went out."""
        async with self.db.session() as s:
            await self.start_marks(s)
            after_msg = int(await config(s, "push_msg_after", 0))
            after_note = int(await config(s, "push_note_after", 0))
            items, new_msg, new_note = await pending(s, after_msg, after_note)
            await set_config(s, "push_msg_after", new_msg)
            await set_config(s, "push_note_after", new_note)
            await s.commit()
            if not items:
                return 0
            users = {u for u, _ in items}
            tokens = list(await s.scalars(select(PushToken).where(
                PushToken.user_id.in_(users), PushToken.auth.in_(select(AuthToken.token)))))
            sent, dead = 0, []
            pinged = set()
            for t in tokens:
                if t.platform == "fcm" and self.fcm and t.user_id not in pinged:
                    pinged.add(t.user_id)              # one wake-up per device and round is enough
                    if await asyncio.to_thread(self.fcm.send, t.token, {"type": "check"}):
                        sent += 1
                    else:
                        dead.append(t.id)
                elif t.platform == "web" and self.web:
                    sub = json.loads(t.keys or "{}")
                    for user_id, item in items:
                        if user_id != t.user_id:
                            continue
                        payload = await web_payload(s, user_id, item, t.lang)
                        if await asyncio.to_thread(self.web.send, sub, payload):
                            sent += 1
                        else:
                            dead.append(t.id)
                            break
            if dead:
                await s.execute(delete(PushToken).where(PushToken.id.in_(dead)))
                await s.commit()
            return sent

    async def run(self):
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:                       # never let one bad round stop the pushes
                log.exception("push round failed")
            await asyncio.sleep(TICK_S)


# ---------------------------------------------------------------- endpoints

def register(app, authed, ApiError, enabled: bool):
    @app.get("/api/push/vapid")
    async def vapid_public():
        async with app.db.session() as s:
            _, public = await vapid_keys(s)
            await s.commit()
        return jsonify(key=public)

    @app.post("/api/push/register")
    @authed
    async def push_register():
        data = await request.get_json(force=True) or {}
        platform = data.get("platform")
        lang = "en" if data.get("lang") == "en" else "de"
        if platform == "fcm" and isinstance(data.get("token"), str) and data["token"]:
            token, keys = data["token"][:4096], None
        elif platform == "web" and isinstance(data.get("subscription"), dict) and data["subscription"].get("endpoint"):
            token, keys = str(data["subscription"]["endpoint"])[:4096], json.dumps(data["subscription"])[:8192]
        else:
            raise ApiError("Ungültiges Gerät", "Invalid device")
        auth = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()[:64]
        row = await g.s.scalar(select(PushToken).where(PushToken.token == token))
        if row is None:
            g.s.add(PushToken(user_id=g.user.id, platform=platform, token=token, keys=keys, lang=lang, auth=auth))
        else:                                       # same device, maybe another account now
            row.user_id, row.keys, row.lang, row.platform, row.auth = g.user.id, keys, lang, platform, auth
        return jsonify(ok=True)

    @app.delete("/api/push/register")
    @authed
    async def push_unregister():
        data = await request.get_json(force=True) or {}
        token = data.get("token") or data.get("endpoint")
        if token:
            await g.s.execute(delete(PushToken).where(PushToken.token == token, PushToken.user_id == g.user.id))
        return jsonify(ok=True)

    @app.before_serving
    async def start_push():
        if not enabled or os.environ.get("MOMENTUM_NO_PUSH"):
            return
        async with app.db.session() as s:
            pem, _ = await vapid_keys(s)
            await s.commit()
        fcm = FcmSender.from_env()
        if fcm is None:
            log.warning("no Firebase service account: Android keeps polling (secrets/firebase-admin.json)")
        web = WebPushSender(pem)
        app.push_senders = (fcm, web)              # calls ring right away (server/calls.py), not in the next round
        app.push_task = asyncio.get_running_loop().create_task(Dispatcher(app.db, fcm, web).run())

    @app.after_serving
    async def stop_push():
        task = getattr(app, "push_task", None)
        if task:
            task.cancel()
