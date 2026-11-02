"""Rule triggers on post slides / stories, stories (24 h) and highlights."""
from datetime import timedelta

from sqlalchemy import select

from ..db import utcnow
from .test_social import png, run, user


def by_name(st, name):
    return next(r for r in st["rules"] if r["name"] == name)


async def public_rule(c, who, name="Task done"):
    st = (await who.req("get", "/api/state"))["state"]
    r = by_name(st, name)
    body = {k: r[k] for k in ("kind", "name", "icon", "mode", "base")}
    await who.req("put", f"/api/rules/{r['id']}", json={**body, "public": True})
    return r["id"]


def test_post_slides_tap_rules(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        task = await public_rule(c, ann)
        private = by_name((await ann.req("get", "/api/state"))["state"], "Doomscrolling")["id"]
        m1 = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        m2 = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        ids = [m1["id"], m2["id"]]
        # only rules shown on the profile, up to 5 per slide, only existing slides, at most x10
        for bad in ([{"slide": 0, "rule_id": private}], [{"slide": 2, "rule_id": task}],
                    [{"slide": 0, "rule_id": task, "times": 11}], [{"slide": 0, "rule_id": task}] * 6):
            await ann.req("post", "/api/posts", json={"media_ids": ids, "triggers": bad}, status=400)
        before = (await ann.req("get", "/api/state"))["state"]["momentum"]
        post = (await ann.req("post", "/api/posts", json={"media_ids": ids, "triggers": [
            {"slide": 1, "rule_id": task, "times": 2}]}))["post"]
        assert post["triggers"] == [{"slide": 1, "rule_id": task, "name": "Task done", "icon": "check", "kind": "acc", "times": 2, "points": 200}]
        assert (await ann.req("get", "/api/state"))["state"]["momentum"] == before + 200        # tapped twice (x2)
        # ben sees them on the post and can open the rule like on ann's profile
        feed = (await ben.req("get", "/api/feed"))["posts"]
        assert feed[0]["triggers"][0]["times"] == 2
        assert (await ben.req("get", f"/api/rules/{task}/detail"))["rule"]["name"] == "Task done"
    run(tmp_path, scenario)


def test_stories_feed_seen_and_highlights(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ben.req("post", "/api/users/ann/follow")
        task = await public_rule(c, ann)
        media = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        st = (await ann.req("post", "/api/stories", json={"media_id": media["id"], "triggers": [{"rule_id": task, "times": 1}],
                                                          "overlay": {"text": "Lauf!", "x": .5, "y": .5}}))["story"]
        assert st["mine"] and st["active"] and st["triggers"][0]["name"] == "Task done"
        assert st["triggers"][0]["points"] > 0                     # what the tap actually gave
        assert st["media"]["overlay"] is None and st["media"]["url"].endswith("_t.webp")      # text burned in
        await ann.req("post", "/api/stories", json={"media_id": media["id"]}, status=400)        # used already
        # ben: ann's story at the top, not seen yet; cat (not following) sees nothing in the feed
        rows = (await ben.req("get", "/api/stories"))["rows"]
        assert [r["user"]["username"] for r in rows] == ["ann"] and rows[0]["unseen"]
        assert (await cat.req("get", "/api/stories"))["rows"] == []
        assert (await ben.req("get", "/api/users/ann"))["profile"]["story"] == {"active": 1, "unseen": True}
        await ben.req("post", f"/api/stories/{st['id']}/seen")
        await ben.req("post", f"/api/stories/{st['id']}/seen")              # once is enough
        assert not (await ben.req("get", "/api/stories"))["rows"][0]["unseen"]
        # ann (who posted it) sees who saw it; nobody else can ask
        mine = (await ann.req("get", "/api/users/ann/stories"))["stories"][0]
        assert mine["views"] == 1
        viewers = (await ann.req("get", f"/api/stories/{st['id']}/viewers"))["viewers"]
        assert [v["user"]["username"] for v in viewers] == ["ben"] and viewers[0]["at"]
        await ben.req("get", f"/api/stories/{st['id']}/viewers", status=404)
        assert (await ben.req("get", "/api/users/ann/stories"))["stories"][0]["views"] is None
        assert (await ben.req("get", "/api/users/ann"))["profile"]["story"]["unseen"] is False
        # a highlight keeps it after the 24 hours
        hl = (await ann.req("post", "/api/highlights", json={"title": "Läufe", "story_ids": [st["id"]]}))["highlight"]
        assert hl["title"] == "Läufe" and [s["id"] for s in hl["stories"]] == [st["id"]]
        async with app.db.session() as s:
            from ..db import Story
            row = await s.get(Story, st["id"])
            row.expires_at -= timedelta(hours=25)
            await s.commit()
        assert (await ben.req("get", "/api/stories"))["rows"] == []
        assert (await ben.req("get", "/api/users/ann/stories"))["stories"] == []
        hls = (await ben.req("get", "/api/users/ann/highlights"))["highlights"]
        assert hls[0]["stories"][0]["id"] == st["id"] and hls[0]["cover"]
        assert [s["id"] for s in (await ann.req("get", "/api/stories/archive"))["stories"]] == [st["id"]]
        await ben.req("put", f"/api/highlights/{hl['id']}", json={"title": "x"}, status=404)      # not his
        hl = (await ann.req("put", f"/api/highlights/{hl['id']}", json={"remove": [st["id"]]}))["highlight"]
        assert hl["stories"] == []
        await ann.req("delete", f"/api/stories/{st['id']}")
        await ann.req("delete", f"/api/highlights/{hl['id']}")
        await ann.req("delete", "/api/account")                # cascades without errors
    run(tmp_path, scenario)


def test_stories_shared_in_chats_answered_and_reacted_to(tmp_path):
    """Report #33: share a story into a chat (the author's privacy counts for every reader), answer it
    with a message or a quick reaction."""
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ann.req("put", "/api/settings", json={"private": True})
        await ben.req("post", "/api/users/ann/follow")
        await ann.req("post", "/api/follow-requests/ben/accept")
        md = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        story = (await ann.req("post", "/api/stories", json={"media_id": md["id"]}))["story"]

        # ben may see it, answer it and share it; cat (not following the private ann) may not see it
        seen = (await ben.req("get", f"/api/stories/{story['id']}"))["story"]
        assert seen["can_reply"] is True and seen["mine"] is False
        await cat.req("get", f"/api/stories/{story['id']}", status=404)
        assert (await ann.req("get", f"/api/stories/{story['id']}"))["story"]["can_reply"] is False

        r = await ben.req("post", f"/api/stories/{story['id']}/reply", json={"text": "🔥"})
        msgs = (await ann.req("get", f"/api/conversations/{r['conversation_id']}/messages"))["messages"]
        assert msgs[-1]["text"] == "🔥" and msgs[-1]["share"]["kind"] == "story"
        assert msgs[-1]["share"]["available"] is True and msgs[-1]["share"]["user"]["username"] == "ann"
        await ben.req("post", f"/api/stories/{story['id']}/reply", json={"text": "  "}, status=400)
        await ann.req("post", f"/api/stories/{story['id']}/reply", json={"text": "me"}, status=403)
        await cat.req("post", f"/api/stories/{story['id']}/reply", json={"text": "hi"}, status=404)

        # shared into a group with cat: cat gets the card, but not the story
        await ben.req("post", "/api/users/cat/follow")
        await cat.req("post", "/api/users/ben/follow")
        grp = (await ben.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["cat"]}))["conversation"]["id"]
        await ben.req("post", f"/api/conversations/{grp}/messages", json={"share": {"kind": "story", "id": story["id"]}})
        assert (await ben.req("get", f"/api/conversations/{grp}/messages"))["messages"][-1]["share"]["available"] is True
        assert (await cat.req("get", f"/api/conversations/{grp}/messages"))["messages"][-1]["share"] == {"kind": "story", "available": False}
        await cat.req("post", f"/api/conversations/{grp}/messages", json={"share": {"kind": "story", "id": story["id"]}}, status=404)

        # after 24 h it's gone for the others (the author still sees it)
        async with app.db.session() as s:
            from ..db import Story
            (await s.get(Story, story["id"])).expires_at = utcnow() - timedelta(minutes=1)
            await s.commit()
        assert (await ben.req("get", f"/api/conversations/{grp}/messages"))["messages"][-1]["share"]["available"] is False
        await ann.req("get", f"/api/stories/{story['id']}")
    run(tmp_path, scenario)


def test_typing_indicator(tmp_path):
    """Report #32: "X schreibt..." / "nimmt Audio auf" in the chat and the chat list, for a few seconds."""
    async def scenario(app, c):
        from .. import chat
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await ben.req("post", "/api/users/ann/follow")
        await cat.req("post", "/api/users/ann/follow")
        grp = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben", "cat"]}))["conversation"]["id"]
        await ben.req("post", f"/api/conversations/{grp}/typing", json={"kind": "text"})
        await cat.req("post", f"/api/conversations/{grp}/typing", json={"kind": "audio"})
        res = await ann.req("get", f"/api/conversations/{grp}/messages")
        assert res["typing"] == [{"username": "ben", "kind": "text"}, {"username": "cat", "kind": "audio"}]
        assert [x["username"] for x in (await ben.req("get", f"/api/conversations/{grp}/messages"))["typing"]] == ["cat"]
        conv = next(x for x in (await ann.req("get", "/api/conversations"))["conversations"] if x["id"] == grp)
        assert len(conv["typing"]) == 2
        # sending ends it, so does stopping, so does time
        await ben.req("post", f"/api/conversations/{grp}/messages", json={"text": "hi"})
        await cat.req("post", f"/api/conversations/{grp}/typing", json={"kind": None})
        assert (await ann.req("get", f"/api/conversations/{grp}/messages"))["typing"] == []
        await ben.req("post", f"/api/conversations/{grp}/typing", json={"kind": "text"})
        chat._typing[grp] = {k: (v[0], 0.0) for k, v in chat._typing[grp].items()}
        assert (await ann.req("get", f"/api/conversations/{grp}/messages"))["typing"] == []
        await cat.req("post", "/api/conversations/999/typing", json={"kind": "text"}, status=404)
    run(tmp_path, scenario)


def test_photo_stories_run_5_10_15_or_30_seconds(tmp_path):
    """Report #39: a photo story's length is chosen; videos run as long as they are."""
    async def scenario(app, c):
        ann = await user(c, "ann")
        md = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        await ann.req("post", "/api/stories", json={"media_id": md["id"], "duration": 7}, status=400)
        st = (await ann.req("post", "/api/stories", json={"media_id": md["id"], "duration": 15}))["story"]
        assert st["duration"] == 15
        md2 = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        assert (await ann.req("post", "/api/stories", json={"media_id": md2["id"]}))["story"]["duration"] == 5
        md3 = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        await ann.req("post", "/api/stories", json={"media_id": md3["id"], "duration": 29}, status=400)
        assert (await ann.req("post", "/api/stories", json={"media_id": md3["id"], "duration": 30}))["story"]["duration"] == 30
    run(tmp_path, scenario)
