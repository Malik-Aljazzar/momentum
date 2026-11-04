"""Voice / video calls (server/calls.py): ringing, answering, the signals between the phones, the chat lines."""
import asyncio
import base64
import hashlib
import hmac
import json
import time

from .. import calls
from .test_social import run, user


async def befriend(*players):
    for a in players:
        for b in players:
            if a is not b:
                await b.req("post", f"/api/users/{a.name}/follow")


async def token_of(p):
    return p.h["Authorization"].removeprefix("Bearer ")


class Socket:
    """One phone's call socket."""

    def __init__(self, c, player, device):
        self.c, self.player, self.device = c, player, device

    async def __aenter__(self):
        self.cm = self.c.websocket("/api/calls/ws")
        self.ws = await self.cm.__aenter__()
        await self.ws.send(json.dumps({"token": await token_of(self.player), "device": self.device}))
        hello = await self.next()
        assert hello["type"] == "hello" and hello["ice"], hello
        return self

    async def __aexit__(self, *exc):
        await self.cm.__aexit__(*exc)

    async def next(self, timeout=3):
        return json.loads(await asyncio.wait_for(self.ws.receive(), timeout))

    async def event(self, kind="call", timeout=3):
        """The next event of this kind (skips others)."""
        while True:
            e = await self.next(timeout)
            if e["type"] == kind:
                return e

    async def send(self, msg):
        await self.ws.send(json.dumps(msg))


def states(call):
    return {p["user"]["username"]: p["state"] for p in call["participants"]}


class FakeSender:
    def __init__(self):
        self.sent = []

    def send(self, target, data, ttl=3600, urgency=None):
        self.sent.append((target, data, ttl))
        return True


def test_direct_call_rings_connects_and_becomes_a_chat_line(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await befriend(ann, ben)
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        async with Socket(c, ann, "ann-phone") as a, Socket(c, ben, "ben-phone") as b, Socket(c, ben, "ben-tablet") as b2:
            r = await ann.req("post", f"/api/conversations/{conv}/call", json={"video": True, "device": "ann-phone"})
            call = r["call"]
            assert r["ice_servers"] and call["status"] == "ringing" and call["video"] and not call["group"]
            assert states(call) == {"ann": "joined", "ben": "ringing"} and call["me"] == {"state": "joined", "device": "ann-phone"}
            # both of ben's devices ring
            for sock in (b, b2):
                ring = (await sock.event())["call"]
                assert ring["id"] == call["id"] and ring["me"]["state"] == "ringing" and ring["started_by"]["username"] == "ann"
            assert (await a.event())["call"]["status"] == "ringing"
            # the chat shows the call while it's on; the current call is there for an app opened from the notification
            assert (await ben.req("get", f"/api/conversations/{conv}"))["conversation"]["call"]["id"] == call["id"]
            assert (await ben.req("get", "/api/calls/current"))["call"]["id"] == call["id"]
            await ann.req("post", f"/api/conversations/{conv}/call", json={"device": "ann-phone"})   # pressed twice: the same call

            # ben answers on the phone: the tablet stops ringing (it sees ben joined with another device)
            got = (await ben.req("post", f"/api/calls/{call['id']}/accept", json={"device": "ben-phone"}))["call"]
            assert got["status"] == "active" and states(got)["ben"] == "joined"
            seen = (await b2.event())["call"]
            assert seen["me"] == {"state": "joined", "device": "ben-phone"}
            joined = (await a.event())["call"]
            seqs = {p["user"]["username"]: p["seq"] for p in joined["participants"]}
            assert seqs["ben"] > seqs["ann"]                     # ben joined later: ben sends the offer

            # the offer / answer / candidates go to the one device in the call
            await b.send({"type": "signal", "call": call["id"], "to": ann_id(joined, "ann"), "data": {"sdp": {"type": "offer", "sdp": "v=0"}}})
            sig = await a.event("signal")
            assert sig["from"] == ann_id(joined, "ben") and sig["data"]["sdp"]["type"] == "offer"
            await a.send({"type": "signal", "call": call["id"], "to": ann_id(joined, "ben"), "data": {"ice": [{"candidate": "c"}]}})
            assert (await b.event("signal"))["data"] == {"ice": [{"candidate": "c"}]}
            # the tablet (not in the call) can't send or get signals
            await b2.send({"type": "signal", "call": call["id"], "to": ann_id(joined, "ann"), "data": {"sdp": {}}})
            await b2.send({"type": "ping"})
            assert (await b2.next())["type"] == "pong"
            # microphone / camera off: everyone sees it
            await a.send({"type": "media", "call": call["id"], "mic": False, "cam": False})
            m = (await b.event())["call"]
            ann_p = next(p for p in m["participants"] if p["user"]["username"] == "ann")
            assert ann_p["mic"] is False and ann_p["cam"] is False

            # ann hangs up: over for both, a line in the chat
            await asyncio.sleep(1.1)
            await ann.req("post", f"/api/calls/{call['id']}/leave")
            end = (await b.event())["call"]
            assert end["status"] == "ended" and end["reason"] == "done"
            assert (await ben.req("get", f"/api/conversations/{conv}"))["conversation"]["call"] is None
            assert (await ben.req("get", "/api/calls/current"))["call"] is None
            await ben.req("post", f"/api/calls/{call['id']}/accept", json={"device": "ben-phone"}, status=410)
        line = (await ben.req("get", f"/api/conversations/{conv}/messages"))["messages"][-1]
        assert line["kind"] == "call" and "target" not in line
        assert line["call"]["video"] and line["call"]["joined"] and not line["call"]["outgoing"] and line["call"]["duration"] >= 1
        mine = (await ann.req("get", f"/api/conversations/{conv}/messages"))["messages"][-1]["call"]
        assert mine["outgoing"] and mine["reason"] == "done"
        last = (await ben.req("get", "/api/conversations"))["conversations"][0]["last"]
        assert last["kind"] == "call" and last["call"]["joined"]
        # a call line isn't unread and no push for it
        assert (await ben.req("get", "/api/conversations"))["conversations"][0]["unread"] == 0
        # an Android app from before calls doesn't get the line (it can't show it)
        old = {**ben.h, "X-App-Version": "900000"}
        r = await c.get(f"/api/conversations/{conv}/messages", headers=old)
        assert all(m["kind"] != "call" for m in (await r.get_json())["messages"])
        assert (await (await c.get(f"/api/calls/{call['id']}", headers=ben.h)).get_json())["call"]["status"] == "ended"
    run(tmp_path, scenario)


def ann_id(call, name):
    return next(p["user"]["id"] for p in call["participants"] if p["user"]["username"] == name)


def test_missed_declined_busy_and_pushes(tmp_path):
    async def scenario(app, c):
        fcm, web = FakeSender(), FakeSender()
        app.push_senders = (fcm, web)
        ann, ben, cat, dan = await user(c, "ann"), await user(c, "ben"), await user(c, "cat"), await user(c, "dan")
        await befriend(ann, ben, cat, dan)
        await ben.req("post", "/api/push/register", json={"platform": "fcm", "token": "ben-fcm"})
        await ben.req("post", "/api/push/register", json={"platform": "web", "lang": "de",
                                                           "subscription": {"endpoint": "https://push/ben", "keys": {}}})
        ab = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        # nobody answers: missed after RING_SECONDS
        call = (await ann.req("post", f"/api/conversations/{ab}/call", json={"device": "a"}))["call"]
        await asyncio.sleep(0.2)
        assert [(t, d["type"], ttl) for t, d, ttl in fcm.sent] == [("ben-fcm", "call", calls.RING_SECONDS)]
        assert fcm.sent[0][1]["name"] == "ann" and fcm.sent[0][1]["video"] == 0
        ring = web.sent[0][1]
        assert ring["tag"] == f"call-{call['id']}" and ring["body"] == "Eingehender Sprachanruf" and ring["url"].endswith(f"call:{call['id']}")
        await calls.sweep(app, now=time.monotonic() + calls.RING_SECONDS + 1)
        await asyncio.sleep(0.2)
        assert (fcm.sent[-1][1]["type"], fcm.sent[-1][1]["missed"]) == ("call_end", 1)
        assert web.sent[-1][1]["body"] == "Verpasster Sprachanruf" and len(fcm.sent) == 2
        line = (await ben.req("get", f"/api/conversations/{ab}/messages"))["messages"][-1]["call"]
        assert line == dict(video=False, group=False, duration=None, reason="missed", outgoing=False, joined=False)
        assert (await ann.req("get", f"/api/conversations/{ab}/messages"))["messages"][-1]["call"]["outgoing"]

        # declined: no "missed" push
        pushes = len(fcm.sent)
        call = (await ann.req("post", f"/api/conversations/{ab}/call", json={"device": "a"}))["call"]
        await ben.req("post", f"/api/calls/{call['id']}/decline")
        await asyncio.sleep(0.2)
        # ben's phones stop ringing, but no "missed" (the PWA hears nothing: iOS would have to show it)
        assert [(d["type"], d["missed"]) for _, d, _ in fcm.sent[pushes:]] == [("call", 0), ("call_end", 0)]
        assert web.sent[-1][1].get("ring")
        assert (await ann.req("get", f"/api/conversations/{ab}/messages"))["messages"][-1]["call"]["reason"] == "declined"

        # ann cancels before ben answers
        call = (await ann.req("post", f"/api/conversations/{ab}/call", json={"device": "a"}))["call"]
        await ann.req("post", f"/api/calls/{call['id']}/leave")
        assert (await ann.req("get", f"/api/conversations/{ab}/messages"))["messages"][-1]["call"]["reason"] == "cancelled"

        # busy: cat is in a call with dan
        cd = (await cat.req("post", "/api/conversations/direct", json={"username": "dan"}))["conversation"]["id"]
        busy = (await cat.req("post", f"/api/conversations/{cd}/call", json={"device": "c"}))["call"]
        await dan.req("post", f"/api/calls/{busy['id']}/accept", json={"device": "d"})
        ac = (await ann.req("post", "/api/conversations/direct", json={"username": "cat"}))["conversation"]["id"]
        r = (await ann.req("post", f"/api/conversations/{ac}/call", json={"device": "a"}))["call"]
        assert r["status"] == "ended" and r["reason"] == "busy"
        # cat can't start a second call while in one, nor answer one
        await cat.req("post", f"/api/conversations/{ac}/call", json={"device": "c"}, status=409)
        assert (await cat.req("get", f"/api/conversations/{ac}/messages"))["messages"][-1]["call"]["joined"] is False

        # blocked: no ringing; not a member: no call
        await ben.req("post", "/api/users/ann/block")
        await ann.req("post", f"/api/conversations/{ab}/call", json={"device": "a"}, status=403)
        await dan.req("post", f"/api/conversations/{ab}/call", json={"device": "d"}, status=404)
        await dan.req("post", f"/api/calls/{busy['id']}/leave")
        await ann.req("post", f"/api/conversations/{ac}/call", json={}, status=400)       # which device?
    run(tmp_path, scenario)


def test_group_call_rings_the_unmuted_joins_and_ends_when_alone(tmp_path, monkeypatch):
    monkeypatch.setattr(calls, "MAX_JOINED", 3)
    monkeypatch.setattr(calls, "LOST_SECONDS", 10_000)       # these players have no sockets open

    async def scenario(app, c):
        ann, ben, cat, dan, eve = [await user(c, n) for n in ("ann", "ben", "cat", "dan", "eve")]
        await befriend(ann, ben, cat, dan, eve)
        g = (await ann.req("post", "/api/conversations/group",
                           json={"name": "Crew", "usernames": ["ben", "cat", "dan", "eve"]}))["conversation"]["id"]
        await cat.req("put", f"/api/conversations/{g}/mute", json={"muted": True})
        call = (await ann.req("post", f"/api/conversations/{g}/call", json={"video": True, "device": "a"}))["call"]
        assert call["group"] and call["title"] == "Crew"
        assert states(call) == {"ann": "joined", "ben": "ringing", "dan": "ringing", "eve": "ringing"}   # cat muted it
        brief = (await cat.req("get", f"/api/conversations/{g}"))["conversation"]["call"]
        assert brief == dict(id=call["id"], video=True, joined=1, me=None, status="ringing")
        # cat joins from the chat's bar (wasn't rung)
        await cat.req("post", f"/api/calls/{call['id']}/accept", json={"device": "c"})
        await ben.req("post", f"/api/calls/{call['id']}/accept", json={"device": "b"})
        # full (MAX_JOINED = 3 here)
        await dan.req("post", f"/api/calls/{call['id']}/accept", json={"device": "d"}, status=409)
        await eve.req("post", f"/api/calls/{call['id']}/decline")
        # not answered in time: dan missed it, the call goes on
        await calls.sweep(app, now=time.monotonic() + calls.RING_SECONDS + 1)
        now = (await ann.req("get", f"/api/calls/{call['id']}"))["call"]
        assert states(now) == {"ann": "joined", "ben": "joined", "cat": "joined", "dan": "missed", "eve": "declined"}
        # someone outside the group can't see it or join
        stranger = await user(c, "zed")
        await stranger.req("get", f"/api/calls/{call['id']}", status=404)
        await stranger.req("post", f"/api/calls/{call['id']}/accept", json={"device": "z"}, status=404)
        await ann.req("post", f"/api/calls/{call['id']}/leave")
        await ben.req("post", f"/api/calls/{call['id']}/leave")
        lines = (await dan.req("get", f"/api/conversations/{g}/messages"))["messages"]
        assert lines[-1]["call"]["group"] and lines[-1]["call"]["joined"] is False and lines[-1]["call"]["reason"] == "done"
        assert (await cat.req("get", f"/api/conversations/{g}/messages"))["messages"][-1]["call"]["joined"] is True

        # alone in a group call: it waits for the ones it didn't ring, then ends
        call = (await ann.req("post", f"/api/conversations/{g}/call", json={"device": "a"}))["call"]
        for p in (ben, dan, eve):
            await p.req("post", f"/api/calls/{call['id']}/decline")
        assert (await ann.req("get", f"/api/calls/{call['id']}"))["call"]["status"] == "ringing"
        await calls.sweep(app, now=time.monotonic() + calls.RING_SECONDS + 1)
        assert (await ann.req("get", f"/api/calls/{call['id']}"))["call"]["status"] == "ended"
    run(tmp_path, scenario)


def test_a_lost_phone_leaves_the_call_and_sockets_need_a_session(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await befriend(ann, ben)
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        async with Socket(c, ann, "a"):
            call = (await ann.req("post", f"/api/conversations/{conv}/call", json={"device": "a"}))["call"]
            await ben.req("post", f"/api/calls/{call['id']}/accept", json={"device": "b"})      # no socket for "b"
            await calls.sweep(app, now=time.monotonic() + 5)
            assert (await ann.req("get", f"/api/calls/{call['id']}"))["call"]["status"] == "active"
            await calls.sweep(app, now=time.monotonic() + calls.LOST_SECONDS + 1)
            assert (await ann.req("get", f"/api/calls/{call['id']}"))["call"]["status"] == "ended"
        # a socket without a valid session is closed
        async with c.websocket("/api/calls/ws") as ws:
            await ws.send(json.dumps({"token": "nope", "device": "x"}))
            try:
                await asyncio.wait_for(ws.receive(), 2)
                raise AssertionError("socket stayed open")
            except Exception as e:
                assert not isinstance(e, AssertionError)
    run(tmp_path, scenario)


def test_ice_servers_use_coturn_logins(monkeypatch):
    monkeypatch.delenv("TURN_HOST", raising=False)
    assert calls.ice_servers(7) == [{"urls": ["stun:stun.l.google.com:19302"]}]
    monkeypatch.setenv("TURN_HOST", "turn.example")
    monkeypatch.setenv("TURN_SECRET", "s3cret")
    stun, turn = calls.ice_servers(7, now=1000)
    assert stun["urls"] == ["stun:turn.example:3478"]
    assert turn["username"] == f"{1000 + calls.TURN_TTL}:7"
    expected = base64.b64encode(hmac.new(b"s3cret", turn["username"].encode(), hashlib.sha1).digest()).decode()
    assert turn["credential"] == expected and "turn:turn.example:3478?transport=tcp" in turn["urls"]


def test_calls_open_at_a_restart_are_closed(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await befriend(ann, ben)
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        call = (await ann.req("post", f"/api/conversations/{conv}/call", json={"device": "a"}))["call"]
        app.calls.live.clear()                   # the process ended
        async with app.db.session() as s:
            await calls.close_leftovers(s)
            await s.commit()
        line = (await ben.req("get", f"/api/conversations/{conv}/messages"))["messages"][-1]
        assert line["kind"] == "call" and line["call"]["reason"] == "missed"
        row = await (await c.get(f"/api/calls/{call['id']}", headers=ben.h)).get_json()
        assert row["call"]["status"] == "ended"
    run(tmp_path, scenario)
