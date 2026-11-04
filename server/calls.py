"""Voice and video calls in direct chats and groups (like WhatsApp): WebRTC from phone to phone.

The server only brings the phones together: who rings, who is in, and it passes the WebRTC offers / answers /
ICE candidates between the participants. Sound and picture go directly from phone to phone (DTLS-SRTP, encrypted
end to end) or, when no direct path works, through the TURN relay (coturn next to the app; it can't read them
either). Groups: everyone connects to everyone (mesh), so a call holds at most MAX_JOINED people.

    GET  /api/calls/ice                                 STUN / TURN servers (the TURN login is valid for a day)
    GET  /api/calls/current                             the call ringing for me / that I'm in (or null)
    GET  /api/calls/<id>                                one call (members of its chat)
    POST /api/conversations/<id>/call {video, device}   ring the chat (a call running there: join it)
    POST /api/calls/<id>/accept {device}                answer / join
    POST /api/calls/<id>/decline                        not now (all my devices stop ringing)
    POST /api/calls/<id>/leave                          hang up (before anyone answered: cancel)
    WS   /api/calls/ws                                  the events, and what goes from phone to phone

The socket: the apps keep it open while they run (Android also during a call in the background). The first
message is {"token", "device"} - device: a random id per app start (a player can have several phones; the one
that answered gets the call's signals). Then:
    server -> app  {"type": "hello", "user": id, "ice": [...]}
                   {"type": "call", "call": {...}}        something changed: ringing, joined, left, mic / camera, ended
                   {"type": "signal", "call": id, "from": user id, "data": {...}}
                   {"type": "pong"}
    app -> server  {"type": "signal", "call": id, "to": user id, "data": {"sdp": {...}} | {"ice": [...]}}
                   {"type": "media", "call": id, "mic": bool, "cam": bool}
                   {"type": "ping"}
Who sends the offer: the one who joined later (higher "seq") to everyone already in.

A call ends when fewer than two are in and nobody rings any more (hung up, declined, nobody answered within
RING_SECONDS; a group call waits that long for members it didn't ring). It then becomes a chat line
(Message.kind "call", Message.call_id): its duration, or missed / declined / busy. Whoever it rang for and didn't
answer gets a "missed call" push. Live calls are kept in memory (one server process, like typing); a restart ends
them (their Call rows are closed on the next start). Ringing is pushed at once to every device of the players it
rings for (FCM data "call": Android shows the incoming call screen; Web Push: a notification), the end of an
unanswered call as FCM "call_end" / a "missed call" notification in its place.
"""
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import time
from dataclasses import dataclass, field
from datetime import datetime

from quart import g, jsonify, request, websocket
from sqlalchemy import Boolean, DateTime, Integer, String, select
from sqlalchemy.orm import Mapped, mapped_column

from .db import AuthToken, Base, Conversation, ConversationMember, Message, User, utcnow
from .service import avatar_url
from .social import is_blocked, iso

log = logging.getLogger("momentum.calls")

RING_SECONDS = 45          # unanswered that long: missed
LOST_SECONDS = 30          # in the call, but no socket for that long (app killed, no network): left
MAX_JOINED = 8             # mesh: every phone sends its picture to every other one
TURN_TTL = 24 * 3600
SIGNAL_MAX = 64 * 1024     # an offer with all its candidates is a few kB


class Call(Base):
    """A call as it happened (the live state is in memory): the chat line reads it."""
    __tablename__ = "calls"
    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(Integer, index=True)
    started_by: Mapped[int] = mapped_column(Integer)
    video: Mapped[bool] = mapped_column(Boolean, default=False)
    is_group: Mapped[bool] = mapped_column(Boolean, default=False)
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    answered_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    reason: Mapped[str | None] = mapped_column(String(10), nullable=True)   # done | missed | declined | busy | cancelled
    joined: Mapped[str] = mapped_column(String(400), default=",")           # ",3,8,": everyone who was in


# ---------------------------------------------------------------- live state

@dataclass
class Peer:
    state: str                          # ringing | joined | left | declined | missed | busy
    device: str | None = None
    seq: int = 0
    mic: bool = True
    cam: bool = False
    since: float = field(default_factory=time.monotonic)
    lost_at: float | None = None


@dataclass
class Live:
    id: int
    conv_id: int
    group: bool
    title: str
    video: bool
    started_by: int
    started_at: datetime
    ring_until: float
    peers: dict = field(default_factory=dict)       # user id -> Peer
    cards: dict = field(default_factory=dict)       # user id -> {id, username, display_name, avatar}
    answered_at: datetime | None = None
    seq: int = 0
    ended: bool = False
    reason: str | None = None
    ever_joined: set = field(default_factory=set)

    def joined(self):
        return [u for u, p in self.peers.items() if p.state == "joined"]

    def ringing(self):
        return [u for u, p in self.peers.items() if p.state == "ringing"]


class Hub:
    """The open sockets: user id -> device -> queues (a device may briefly have two while it reconnects)."""

    def __init__(self):
        self.sockets: dict[int, dict[str, set]] = {}

    def add(self, uid, device, q):
        self.sockets.setdefault(uid, {}).setdefault(device, set()).add(q)

    def remove(self, uid, device, q):
        devices = self.sockets.get(uid, {})
        devices.get(device, set()).discard(q)
        if not devices.get(device):
            devices.pop(device, None)
        if not devices:
            self.sockets.pop(uid, None)

    def online(self, uid, device):
        return bool(self.sockets.get(uid, {}).get(device))

    def send(self, uid, event, device=None):
        for dev, queues in self.sockets.get(uid, {}).items():
            if device is None or dev == device:
                for q in queues:
                    q.put_nowait(event)


class Registry:
    def __init__(self):
        self.live: dict[int, Live] = {}
        self.hub = Hub()

    def of_conv(self, conv_id):
        return next((c for c in self.live.values() if c.conv_id == conv_id), None)

    def of_user(self, uid):
        """Calls ringing for me or that I'm in."""
        return [c for c in self.live.values() if uid in c.peers and c.peers[uid].state in ("ringing", "joined")]

    def busy(self, uid, except_id=None):
        return any(c.id != except_id for c in self.of_user(uid))

    def in_call(self, uid, except_id=None):
        """In another call already (ringing elsewhere doesn't count: that one is declined)."""
        return any(c.id != except_id and uid in c.peers and c.peers[uid].state == "joined" for c in self.live.values())

    def brief(self, conv_id, me):
        """For the chat (list): a call running there - the group's "join" bar."""
        c = self.of_conv(conv_id)
        if c is None:
            return None
        p = c.peers.get(me)
        return dict(id=c.id, video=c.video, joined=len(c.joined()), me=p.state if p else None,
                    status="active" if c.answered_at else "ringing")


def registry(app) -> Registry:
    if not hasattr(app, "calls"):
        app.calls = Registry()
    return app.calls


def call_json(c: Live, me):
    p = c.peers.get(me)
    return dict(id=c.id, conversation_id=c.conv_id, group=c.group, title=c.title, video=c.video,
                status="ended" if c.ended else "active" if c.answered_at else "ringing", reason=c.reason,
                started_by=c.cards.get(c.started_by), started_at=iso(c.started_at),
                answered_at=iso(c.answered_at) if c.answered_at else None, max=MAX_JOINED,
                me=dict(state=p.state, device=p.device) if p else None,
                participants=[dict(user=c.cards.get(u), state=q.state, seq=q.seq, mic=q.mic, cam=q.cam)
                              for u, q in c.peers.items() if u in c.cards])


def broadcast(reg: Registry, c: Live):
    for uid in c.peers:
        reg.hub.send(uid, dict(type="call", call=call_json(c, uid)))


async def cards_of(s, ids):
    users = list(await s.scalars(select(User).where(User.id.in_(set(ids))))) if ids else []
    return {u.id: dict(id=u.id, username=u.username, display_name=u.display_name, avatar=avatar_url(u.avatar)) for u in users}


def ice_servers(user_id, now=None):
    """Our coturn (TURN_HOST + TURN_SECRET in the server's .env: time-limited logins, coturn's use-auth-secret),
    else a public STUN server (development: direct connections only)."""
    host, secret = os.environ.get("TURN_HOST", "").strip(), os.environ.get("TURN_SECRET", "").strip()
    if not (host and secret):
        return [dict(urls=["stun:stun.l.google.com:19302"])]
    username = f"{int(now or time.time()) + TURN_TTL}:{user_id}"
    credential = base64.b64encode(hmac.new(secret.encode(), username.encode(), hashlib.sha1).digest()).decode()
    return [dict(urls=[f"stun:{host}:3478"]),
            dict(urls=[f"turn:{host}:3478?transport=udp", f"turn:{host}:3478?transport=tcp"],
                 username=username, credential=credential)]


# ---------------------------------------------------------------- changes (in memory first, then the database)

def should_end(c: Live, now):
    joined = len(c.joined())
    if joined == 0:
        return True
    if joined >= 2 or c.ringing():
        return False
    # one person alone and nobody rings any more; a group call waits a while for the members it didn't ring
    return not c.group or c.answered_at is not None or now >= c.ring_until


def end_reason(c: Live):
    if c.answered_at is not None:
        return "done"
    others = [p.state for u, p in c.peers.items() if u != c.started_by]
    if not c.group and "declined" in others:
        return "declined"
    if not c.group and "busy" in others:
        return "busy"
    if c.peers[c.started_by].state == "left" and "ringing" in others:
        return "cancelled"
    return "missed"


async def finish(s, app, c: Live):
    """The call is over: out of memory, its row closed, a line in the chat, "missed" for those it rang for."""
    reg = registry(app)
    if c.ended:
        return
    c.ended, c.reason = True, end_reason(c)
    reg.live.pop(c.id, None)
    missed = [u for u, p in c.peers.items() if p.state in ("ringing", "busy")]       # (timed out ones got theirs)
    for u in missed:
        c.peers[u].state = "missed"
    for p in c.peers.values():
        if p.state == "joined":
            p.state = "left"
    now = utcnow()
    row = await s.get(Call, c.id)
    if row is not None:
        row.answered_at, row.ended_at, row.reason = c.answered_at, now, c.reason
        row.joined = "," + "".join(f"{u}," for u in sorted(c.ever_joined))
    conv = await s.get(Conversation, c.conv_id)
    if conv is not None:
        s.add(Message(conversation_id=c.conv_id, user_id=c.started_by, text="", kind="call", call_id=c.id))
        conv.last_message_at = now
    await s.flush()
    broadcast(reg, c)
    if missed:
        spawn(push_call(app, c, missed, "call_end", missed=True))


async def start(s, app, user, conv, video, device):
    reg = registry(app)
    running = reg.of_conv(conv.id)
    if running is not None:                 # a call is on here (both rang at once / a group call): join it
        return await accept(s, app, user, running, device)
    members = list(await s.scalars(select(ConversationMember).where(ConversationMember.conversation_id == conv.id)))
    others = [m for m in members if m.user_id != user.id]
    if not others:
        raise CallError("Niemand zum Anrufen", "Nobody to call", 400)
    if conv.kind == "direct" and await is_blocked(s, user.id, others[0].user_id):
        raise CallError("Blockiert", "Blocked", 403)
    if reg.in_call(user.id):
        raise CallError("Du bist schon in einem Anruf", "You're already in a call", 409)
    row = Call(conversation_id=conv.id, started_by=user.id, video=bool(video), is_group=conv.kind == "group")
    s.add(row)
    await s.flush()
    cards = await cards_of(s, [m.user_id for m in members])
    running = reg.of_conv(conv.id)          # someone was quicker meanwhile
    if running is not None:
        await s.delete(row)
        return await accept(s, app, user, running, device)
    for o in reg.of_user(user.id):          # it was ringing for me elsewhere: not now
        if decline(o, user.id):
            spawn(push_call(app, o, [user.id], "call_end", missed=False))
            await settle(s, app, o)
    now = time.monotonic()
    c = Live(id=row.id, conv_id=conv.id, group=conv.kind == "group", title=conv.name if conv.kind == "group" else "",
             video=bool(video), started_by=user.id, started_at=row.started_at or utcnow(), ring_until=now + RING_SECONDS,
             cards=cards)
    c.seq = 1
    c.peers[user.id] = Peer("joined", device, seq=1, cam=bool(video),
                            lost_at=None if reg.hub.online(user.id, device) else now)
    c.ever_joined.add(user.id)
    rung = []
    for m in others:
        if conv.kind == "group" and m.muted:
            continue                         # a muted group doesn't ring; its chat shows the call to join
        if reg.busy(m.user_id):
            c.peers[m.user_id] = Peer("busy")
        else:
            c.peers[m.user_id] = Peer("ringing")
            rung.append(m.user_id)
    reg.live[c.id] = c
    if should_end(c, now):
        await finish(s, app, c)
    else:
        broadcast(reg, c)
    if rung:
        spawn(push_call(app, c, rung, "call"))
    return c


async def accept(s, app, user, c: Live, device):
    reg = registry(app)
    if c.ended:
        raise CallError("Der Anruf ist schon vorbei", "The call has already ended", 410)
    p = c.peers.get(user.id)
    if p is None and not (c.group and await s.get(ConversationMember, (c.conv_id, user.id))):
        raise CallError("Anruf nicht gefunden", "Call not found", 404)
    if reg.in_call(user.id, except_id=c.id):
        raise CallError("Du bist schon in einem Anruf", "You're already in a call", 409)
    if (p is None or p.state != "joined") and len(c.joined()) >= MAX_JOINED:
        raise CallError(f"Der Anruf ist voll (höchstens {MAX_JOINED})", f"The call is full ({MAX_JOINED} at most)", 409)
    if user.id not in c.cards:
        c.cards.update(await cards_of(s, [user.id]))
    if p is not None and p.state == "joined" and p.device == device:
        return c                             # pressed twice
    for o in reg.of_user(user.id):           # ringing for me elsewhere too: not now
        if o.id != c.id and decline(o, user.id):
            spawn(push_call(app, o, [user.id], "call_end", missed=False))
            await settle(s, app, o)
    if p is not None and p.state == "ringing":     # my other phones stop ringing
        spawn(push_call(app, c, [user.id], "call_end", missed=False))
    now = time.monotonic()
    c.seq += 1
    c.peers[user.id] = Peer("joined", device, seq=c.seq, cam=c.video if p is None or p.state != "joined" else p.cam,
                            mic=True if p is None else p.mic, lost_at=None if reg.hub.online(user.id, device) else now)
    c.ever_joined.add(user.id)
    if c.answered_at is None:
        c.answered_at = utcnow()
    broadcast(reg, c)
    return c


def decline(c: Live, uid):
    """Not now (True = changed; settle() then tells the others)."""
    p = c.peers.get(uid)
    if p is not None and p.state == "ringing":
        p.state, p.since = "declined", time.monotonic()
        return True
    return False


def leave(c: Live, uid):
    p = c.peers.get(uid)
    if p is not None and p.state == "joined":
        p.state, p.since = "left", time.monotonic()
        return True
    return decline(c, uid)


async def settle(s, app, c: Live, now=None):
    """After a change: over (the end goes to everyone), or everyone gets the new state."""
    if c.ended:
        return
    if should_end(c, now if now is not None else time.monotonic()):
        await finish(s, app, c)
    else:
        broadcast(registry(app), c)


async def sweep(app, now=None):
    """Every second: nobody answered in time, a participant's socket stayed away too long."""
    reg = registry(app)
    now = now if now is not None else time.monotonic()
    over = []
    for c in list(reg.live.values()):
        changed, timed_out = False, []
        for u, p in c.peers.items():
            if p.state == "ringing" and now - p.since >= RING_SECONDS:
                p.state, changed = "missed", True
                timed_out.append(u)
            elif p.state == "joined" and p.lost_at is not None and now - p.lost_at >= LOST_SECONDS:
                p.state, p.since, changed = "left", now, True
        if timed_out:
            spawn(push_call(app, c, timed_out, "call_end", missed=True))
        if should_end(c, now):
            over.append(c)
        elif changed:
            broadcast(reg, c)
    if over:
        async with app.db.session() as s:
            for c in over:
                await finish(s, app, c)
            await s.commit()


async def close_leftovers(s):
    """Calls still open from before a restart: closed, each with its chat line."""
    for row in list(await s.scalars(select(Call).where(Call.ended_at.is_(None)))):
        row.ended_at = utcnow()
        row.reason = "done" if row.answered_at else "missed"
        if await s.get(Conversation, row.conversation_id) is not None:
            s.add(Message(conversation_id=row.conversation_id, user_id=row.started_by, text="", kind="call", call_id=row.id))


def socket_back(app, uid, device):
    for c in registry(app).of_user(uid):
        p = c.peers[uid]
        if p.state == "joined" and p.device == device:
            p.lost_at = None


def socket_gone(app, uid, device):
    reg = registry(app)
    if reg.hub.online(uid, device):
        return
    for c in reg.of_user(uid):
        p = c.peers[uid]
        if p.state == "joined" and p.device == device and p.lost_at is None:
            p.lost_at = time.monotonic()


def on_message(app, uid, device, msg):
    """What a phone sends over the socket: WebRTC signals to one participant, its microphone / camera."""
    reg = registry(app)
    if not isinstance(msg, dict):
        return None
    kind = msg.get("type")
    if kind == "ping":
        return dict(type="pong")
    try:
        c = reg.live.get(int(msg.get("call")))
    except (TypeError, ValueError):
        return None
    me = c.peers.get(uid) if c else None
    if me is None or me.state != "joined" or me.device != device:
        return None
    if kind == "signal":
        try:
            to = int(msg.get("to"))
        except (TypeError, ValueError):
            return None
        target = c.peers.get(to)
        data = msg.get("data")
        if target is None or target.state != "joined" or not isinstance(data, dict) or len(json.dumps(data)) > SIGNAL_MAX:
            return None
        reg.hub.send(to, dict(type="signal", call=c.id, **{"from": uid}, data=data), device=target.device)
    elif kind == "media":
        me.mic, me.cam = bool(msg.get("mic", me.mic)), bool(msg.get("cam", me.cam))
        broadcast(reg, c)
    return None


# ---------------------------------------------------------------- the chat line

async def call_lines(s, me_id, msgs):
    """{message id: what the call line shows for me} for messages of kind "call"."""
    ids = {m.call_id for m in msgs if m.kind == "call" and m.call_id}
    rows = {r.id: r for r in await s.scalars(select(Call).where(Call.id.in_(ids)))} if ids else {}
    out = {}
    for m in msgs:
        r = rows.get(m.call_id) if m.kind == "call" else None
        if r is None:
            continue
        duration = int((r.ended_at - r.answered_at).total_seconds()) if r.answered_at and r.ended_at else None
        out[m.id] = dict(video=bool(r.video), group=bool(r.is_group), duration=duration, reason=r.reason,
                         outgoing=r.started_by == me_id, joined=f",{me_id}," in (r.joined or ""))
    return out


# ---------------------------------------------------------------- pushes

def spawn(coro):
    task = asyncio.get_running_loop().create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


_tasks: set = set()


async def push_call(app, c: Live, user_ids, kind, missed=False):
    """Ringing ("call") / its end ("call_end": the phone stops ringing; missed = it shows "missed call") to every
    device of these players (server/push.py's senders). The PWA only hears of a missed call (iOS shows every push)."""
    senders = getattr(app, "push_senders", None)
    if not senders:
        return
    fcm, web = senders
    from .push import PushToken, TEXTS
    caller = c.cards.get(c.started_by) or {}
    data = dict(type=kind, call=c.id, conv=c.conv_id, video=int(c.video), name=caller.get("display_name") or "",
                user=caller.get("username") or "", group=c.title, avatar=caller.get("avatar") or "", missed=int(missed))
    try:
        async with app.db.session() as s:
            tokens = list(await s.scalars(select(PushToken).where(
                PushToken.user_id.in_(user_ids), PushToken.auth.in_(select(AuthToken.token)))))
        for t in tokens:
            if t.platform == "fcm" and fcm:
                await asyncio.to_thread(fcm.send, t.token, data, ttl=RING_SECONDS if kind == "call" else 3600)
            elif t.platform == "web" and web and (kind == "call" or missed):
                payload = web_payload(c, kind, TEXTS.get(t.lang, TEXTS["de"]))
                await asyncio.to_thread(web.send, json.loads(t.keys or "{}"), payload,
                                        ttl=RING_SECONDS if kind == "call" else 3600, urgency="high")
    except Exception:                       # a push must never break the call
        log.exception("call push failed")


def web_payload(c: Live, kind, t):
    caller = (c.cards.get(c.started_by) or {}).get("display_name") or ""
    what = t["call_video" if c.video else "call_voice"]
    if kind == "call":
        body = t["call_incoming_video" if c.video else "call_incoming_voice"]
        return dict(title=c.title or caller, body=f"{caller}: {body}" if c.group else body, tag=f"call-{c.id}",
                    url=f"/app/?open=call:{c.id}", call=c.id, ring=True)
    body = t["call_missed_video" if c.video else "call_missed_voice"]
    return dict(title=c.title or caller, body=f"{caller}: {body}" if c.group else body, tag=f"call-{c.id}",
                url=f"/app/?open=chat:{c.conv_id}")


# ---------------------------------------------------------------- endpoints

class CallError(Exception):
    def __init__(self, de, en, status):
        super().__init__(en)
        self.de, self.en, self.status = de, en, status


def clean_device(value):
    return "".join(ch for ch in str(value or "") if ch.isalnum() or ch in "-_")[:40]


def register(app, authed, ApiError, session_user):
    reg = registry(app)

    def api(e: CallError):
        return ApiError(e.de, e.en, e.status)

    async def live_for(call_id):
        c = reg.live.get(call_id)
        if c is None or c.ended:
            raise ApiError("Der Anruf ist schon vorbei", "The call has already ended", 410)
        return c

    @app.get("/api/calls/ice")
    @authed
    async def ice():
        return jsonify(ice_servers=ice_servers(g.user.id))

    @app.get("/api/calls/current")
    @authed
    async def current():
        mine = reg.of_user(g.user.id)
        return jsonify(call=call_json(mine[0], g.user.id) if mine else None)

    @app.get("/api/calls/<int:call_id>")
    @authed
    async def get_call(call_id):
        c = reg.live.get(call_id)
        if c is not None and (g.user.id in c.peers or await g.s.get(ConversationMember, (c.conv_id, g.user.id))):
            return jsonify(call=call_json(c, g.user.id))
        row = await g.s.get(Call, call_id)
        if row is None or not await g.s.get(ConversationMember, (row.conversation_id, g.user.id)):
            raise ApiError("Anruf nicht gefunden", "Call not found", 404)
        return jsonify(call=dict(id=row.id, conversation_id=row.conversation_id, group=bool(row.is_group),
                                 video=bool(row.video), status="ended", reason=row.reason, participants=[], me=None))

    @app.post("/api/conversations/<int:conv_id>/call")
    @authed
    async def start_call(conv_id):
        data = await request.get_json(force=True) or {}
        device = clean_device(data.get("device"))
        if not device:
            raise ApiError("Gerät fehlt", "Device missing")
        conv = await g.s.get(Conversation, conv_id)
        if conv is None or not await g.s.get(ConversationMember, (conv_id, g.user.id)):
            raise ApiError("Chat nicht gefunden", "Chat not found", 404)
        try:
            c = await start(g.s, app, g.user, conv, bool(data.get("video")), device)
        except CallError as e:
            raise api(e)
        return jsonify(call=call_json(c, g.user.id), ice_servers=ice_servers(g.user.id))

    @app.post("/api/calls/<int:call_id>/accept")
    @authed
    async def accept_call(call_id):
        data = await request.get_json(force=True) or {}
        device = clean_device(data.get("device"))
        if not device:
            raise ApiError("Gerät fehlt", "Device missing")
        try:
            c = await accept(g.s, app, g.user, await live_for(call_id), device)
        except CallError as e:
            raise api(e)
        return jsonify(call=call_json(c, g.user.id), ice_servers=ice_servers(g.user.id))

    @app.post("/api/calls/<int:call_id>/decline")
    @authed
    async def decline_call(call_id):
        c = reg.live.get(call_id)
        if c is not None and decline(c, g.user.id):
            spawn(push_call(app, c, [g.user.id], "call_end", missed=False))
            await settle(g.s, app, c)
        return jsonify(call=call_json(c, g.user.id) if c else None)

    @app.post("/api/calls/<int:call_id>/leave")
    @authed
    async def leave_call(call_id):
        c = reg.live.get(call_id)
        was = c.peers[g.user.id].state if c is not None and g.user.id in c.peers else None
        if c is not None and leave(c, g.user.id):
            if was == "ringing":
                spawn(push_call(app, c, [g.user.id], "call_end", missed=False))
            await settle(g.s, app, c)
        return jsonify(call=call_json(c, g.user.id) if c else None)

    @app.websocket("/api/calls/ws")
    async def calls_socket():
        try:
            first = json.loads(await asyncio.wait_for(websocket.receive(), 15))
            token, device = str(first.get("token", "")), clean_device(first.get("device"))
        except (asyncio.TimeoutError, ValueError, AttributeError, TypeError):
            await websocket.close(4400)
            return
        user = None
        async with app.db.session() as s:
            try:
                user = await session_user(s, token)
                await s.commit()
            except ApiError:
                pass
        if user is None or not device:
            await websocket.close(4401)
            return
        uid, q = user.id, asyncio.Queue()
        reg.hub.add(uid, device, q)
        socket_back(app, uid, device)

        async def reader():
            while True:
                raw = await websocket.receive()
                try:
                    msg = json.loads(raw)
                except (TypeError, ValueError):
                    continue
                answer = on_message(app, uid, device, msg)
                if answer:
                    q.put_nowait(answer)

        task = asyncio.ensure_future(reader())
        try:
            await websocket.send(json.dumps(dict(type="hello", user=uid, ice=ice_servers(uid))))
            for c in reg.of_user(uid):
                await websocket.send(json.dumps(dict(type="call", call=call_json(c, uid))))
            while True:
                get = asyncio.ensure_future(q.get())
                done, _ = await asyncio.wait({get, task}, return_when=asyncio.FIRST_COMPLETED)
                if get not in done:
                    get.cancel()
                    break
                await websocket.send(json.dumps(get.result()))
        finally:
            task.cancel()
            reg.hub.remove(uid, device, q)
            socket_gone(app, uid, device)

    async def sweeper():
        while True:
            try:
                await sweep(app)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("call sweep failed")
            await asyncio.sleep(1)

    @app.before_serving
    async def start_calls():
        async with app.db.session() as s:
            await close_leftovers(s)
            await s.commit()
        app.calls_task = asyncio.get_running_loop().create_task(sweeper())

    @app.after_serving
    async def stop_calls():
        task = getattr(app, "calls_task", None)
        if task:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
