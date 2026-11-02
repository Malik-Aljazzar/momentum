"""Group member lines in the chat (report #56), activity when someone subscribes to my rule or follows my
decay (report #54), and a video picked as a story's sound (report #60)."""
from .test_community import RULE, by_name, friends
from .test_decays import DECAY
from .test_social import Client, png, run, tiny_video, user


def lines(msgs):
    return [(m["kind"], m["user"]["username"], (m.get("target") or {}).get("username")) for m in msgs if m["kind"]]


def test_group_member_lines(tmp_path):
    async def scenario(app, c):
        ann, ben, cat, dan = [await user(c, n) for n in ("ann", "ben", "cat", "dan")]
        await friends(ann, ben, cat, dan)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Gym", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("post", f"/api/conversations/{g}/members", json={"usernames": ["cat"]})
        await ann.req("put", f"/api/conversations/{g}", json={"public": True, "open": True})
        await dan.req("post", f"/api/conversations/{g}/join")
        await ben.req("delete", f"/api/conversations/{g}/members/cat", status=403)           # admins only
        await ann.req("delete", f"/api/conversations/{g}/members/ann", status=400)
        await ann.req("delete", f"/api/conversations/{g}/members/cat")
        await cat.req("get", f"/api/conversations/{g}/messages", status=404)                   # removed
        await dan.req("delete", f"/api/conversations/{g}/members/me")

        msgs = (await ben.req("get", f"/api/conversations/{g}/messages"))["messages"]
        assert lines(msgs) == [("member_add", "ann", "cat"), ("member_join", "dan", None),
                               ("member_remove", "ann", "cat"), ("member_leave", "dan", None)]
        assert not any(m.get("target_me") for m in msgs)
        # lines are no unread messages and the chat list shows the latest one
        conv = next(cv for cv in (await ben.req("get", "/api/conversations"))["conversations"] if cv["id"] == g)
        assert conv["unread"] == 0 and conv["last"]["kind"] == "member_leave" and conv["last"]["sender"] == "dan"
        # an Android app that doesn't know them yet doesn't get them
        old = Client(c, "ben", {**ben.h, "X-App-Version": "888000"})
        assert lines((await old.req("get", f"/api/conversations/{g}/messages"))["messages"]) == []
        conv = next(cv for cv in (await old.req("get", "/api/conversations"))["conversations"] if cv["id"] == g)
        assert conv["last"] is None or conv["last"]["kind"] is None
    run(tmp_path, scenario)


def test_activity_when_rules_and_decays_get_subscribed(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = [await user(c, n) for n in ("ann", "ben", "cat")]
        await friends(ann, ben, cat)
        rid = by_name((await ann.req("post", "/api/rules", json={**RULE, "public": True}))["state"], "Push-ups")["id"]
        await ben.req("post", f"/api/rules/{rid}/subscribe")
        g = (await cat.req("post", "/api/conversations/group", json={"name": "Gym", "usernames": ["ben"]}))["conversation"]["id"]
        await cat.req("post", f"/api/conversations/{g}/rules", json={"from_rule": rid})
        await ann.req("put", "/api/decay", json={**DECAY, "public": True})
        await ben.req("post", "/api/decay/follow", json={"username": "ann"})
        await ben.req("post", "/api/decay/follow", json={"username": "ann"})                  # again: no second one
        await cat.req("post", f"/api/conversations/{g}/decays", json={"from_user": "ann"})

        items = (await ann.req("get", "/api/notifications"))["items"]
        got = [(n["kind"], n["actor"]["username"], (n.get("rule") or {}).get("name"), (n.get("group") or {}).get("name"))
               for n in items if n["kind"] != "follow"]
        assert got == [("sub_decay", "cat", None, "Gym"), ("sub_decay", "ben", None, None),
                       ("sub_rule", "cat", "Push-ups", "Gym"), ("sub_rule", "ben", "Push-ups", None)]
        # nobody hears about the group's members following the group's rules
        assert all(n["kind"] not in ("sub_rule", "sub_decay") for n in (await cat.req("get", "/api/notifications"))["items"])
        old = Client(c, "ann", {**ann.h, "X-App-Version": "888000"})
        assert all(n["kind"] not in ("sub_rule", "sub_decay") for n in (await old.req("get", "/api/notifications"))["items"])
    run(tmp_path, scenario)


def test_video_as_story_sound(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        photo = (await ann.req("post", "/api/media", data=png(400, 600), ctype="image/png"))["media"]
        # the PWA uploads every sound as audio, also a picked video (an iOS screen recording)
        sound = (await ann.req("post", "/api/media?start=0.5&seconds=1", data=tiny_video(), ctype="audio/mp4"))["media"]
        assert sound["kind"] == "audio"
        await ann.req("post", "/api/stories", json={"media_id": photo["id"], "sound": {"media_id": sound["id"], "title": "rec"}})
    run(tmp_path, scenario)
