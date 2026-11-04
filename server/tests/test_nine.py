"""One of the Nine (server/nine.py): the counted titles, the profile's titles, the post cap and the Larper's
election."""
from datetime import datetime, timedelta

from sqlalchemy import select

from .. import nine
from ..db import Report, User, utcnow
from .test_social import run, user


async def nine_of(client):
    return {t["key"]: t for t in (await client.req("get", "/api/nine"))["titles"]}


def test_counted_titles_profile_and_post_cap(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        ids = {u.name: (await u.req("get", "/api/state"))["state"]["user"]["id"] for u in (ann, ben, cat)}
        # nobody has anything yet (except the veteran: everyone used the app today, the oldest account holds it)
        titles = await nine_of(ann)
        assert set(titles) == set(nine.KEYS) and len(titles) == 9
        assert titles["veteran"]["holder"]["username"] == "ann" and titles["veteran"]["value"] == 1
        assert titles["veteran"]["mine"] == {"value": 1, "rank": 1}
        assert titles["clown"]["holder"] is None and titles["clown"]["mine"] is None
        # the Clown: posts and stories ever made, deleted ones still count
        p1 = (await ann.req("post", "/api/posts", json={"text": "eins"}))["post"]
        await ann.req("post", "/api/posts", json={"text": "zwei"})
        gone = (await ben.req("post", "/api/posts", json={"text": "weg"}))["post"]
        await ben.req("delete", f"/api/posts/{gone['id']}")
        titles = await nine_of(ben)
        assert titles["clown"]["holder"]["username"] == "ann" and titles["clown"]["value"] == 2
        assert titles["clown"]["mine"] == {"value": 1, "rank": 2} and titles["clown"]["total"] == 2
        # the Yapper: comments; the Charmeur: likes on own posts
        for text in ("a", "b", "c"):
            await ben.req("post", f"/api/posts/{p1['id']}/comments", json={"text": text})
        await cat.req("post", f"/api/posts/{p1['id']}/comments", json={"text": "d"})
        await ben.req("post", f"/api/posts/{p1['id']}/like")
        await cat.req("post", f"/api/posts/{p1['id']}/like")
        nine.forget_tables()
        titles = await nine_of(cat)
        assert titles["yapper"]["holder"]["username"] == "ben" and titles["yapper"]["value"] == 3
        assert titles["yapper"]["mine"] == {"value": 1, "rank": 2}
        assert titles["charmeur"]["holder"]["username"] == "ann" and titles["charmeur"]["value"] == 2
        # the GOAT and Supercharged: ranked momentum (ben plays without the limit: not counted)
        st = (await ann.req("post", "/api/rules", json={"kind": "acc", "name": "Lauf", "icon": "shoe", "mode": "constant", "base": 50}))["state"]
        rule = next(r for r in st["rules"] if r["name"] == "Lauf")
        await ann.req("post", f"/api/rules/{rule['id']}/trigger")
        st = (await ben.req("post", "/api/rules", json={"kind": "acc", "name": "Mehr", "icon": "shoe", "mode": "constant", "base": 500}))["state"]
        await ben.req("put", "/api/settings", json={"daily_limit": False})
        await ben.req("post", f"/api/rules/{next(r for r in st['rules'] if r['name'] == 'Mehr')['id']}/trigger")
        nine.forget_tables()
        titles = await nine_of(ann)
        assert titles["goat"]["holder"]["username"] == "ann" and titles["goat"]["value"] == 50
        assert titles["supercharged"]["holder"]["username"] == "ann" and titles["supercharged"]["mine"] == {"value": 50, "rank": 1}
        assert (await nine_of(ben))["goat"]["mine"] is None
        # the Gamemaster: subscribers of own rules (links followed to the origin) and the own decay
        await ann.req("put", f"/api/rules/{rule['id']}", json={"kind": "acc", "name": "Lauf", "icon": "shoe", "mode": "constant", "base": 50, "public": True})
        st = (await ben.req("post", f"/api/rules/{rule['id']}/subscribe"))["state"]
        copy = next(r for r in st["rules"] if r.get("source_rule_id") == rule["id"] or r["name"] == "Lauf")
        await cat.req("post", f"/api/rules/{rule['id']}/subscribe")
        await ann.req("put", "/api/decay", json={"base": 80, "threshold": 300, "catalyst_mode": "none", "catalyst_a": 0, "catalyst_b": 1.5, "public": True})
        await cat.req("post", "/api/decay/follow", json={"username": "ann"})
        nine.forget_tables()
        titles = await nine_of(ann)
        assert titles["gamemaster"]["holder"]["username"] == "ann" and titles["gamemaster"]["value"] == 3
        # Hermes = the King of Reporters (implemented reports)
        async with app.db.session() as s:
            s.add(Report(user_id=ids["cat"], kind="bug", text="x", status="done", status_at=utcnow()))
            await s.commit()
        nine.forget_tables()
        assert (await nine_of(ann))["hermes"]["holder"]["username"] == "cat"
        # the profile lists the titles, in the fixed order
        assert (await ben.req("get", "/api/users/ann"))["profile"]["titles"] == ["goat", "charmeur", "gamemaster", "veteran", "clown", "supercharged"]
        assert (await ann.req("get", "/api/users/ben"))["profile"]["titles"] == ["yapper"]
        assert (await ann.req("get", "/api/users/cat"))["profile"]["titles"] == ["hermes"]
        # at most 100 posts a day, stories included; deleting doesn't make room
        async with app.db.session() as s:
            u = await s.get(User, ids["ann"])
            u.posts_today = nine.POSTS_PER_DAY - 1
            await s.commit()
        await ann.req("post", "/api/posts", json={"text": "hundert"})
        err = await ann.req("post", "/api/posts", json={"text": "zu viel"}, status=400)
        assert "100 posts" in err["error"]
        await ann.req("delete", f"/api/posts/{p1['id']}")
        await ann.req("post", "/api/posts", json={"text": "immer noch zu viel"}, status=400)
        async with app.db.session() as s:
            u = await s.get(User, ids["ann"])
            assert u.posts_total == 3 and u.posts_today == nine.POSTS_PER_DAY
            u.posts_day = u.posts_day - timedelta(days=1)             # a new day
            await s.commit()
        await ann.req("post", "/api/posts", json={"text": "morgen"})
    run(tmp_path, scenario)


def test_larper_election(tmp_path):
    async def scenario(app, c):
        players = [await user(c, f"pl{i}") for i in range(12)]
        ann, ben = players[0], players[1]
        # no Larper yet: an election is open right away, for a day
        e = (await ann.req("get", "/api/nine/election"))["election"]
        assert e["open"] and e["voters"] == 0 and e["needed"] == nine.VOTERS_NEEDED and e["larper"] is None
        assert e["my_vote"] is None and e["next_at"] is None
        err = await ann.req("post", "/api/nine/election/vote", json={"username": "pl0"}, status=400)
        assert "yourself" in err["error"]
        await ann.req("post", "/api/nine/election/vote", json={"username": "nobody"}, status=400)
        e = (await ann.req("post", "/api/nine/election/vote", json={"username": "@PL1"}))["election"]
        assert e["my_vote"]["username"] == "pl1" and e["voters"] == 1 and e["top"][0] == {"user": e["top"][0]["user"], "votes": 1}
        # a second vote replaces the first
        e = (await ann.req("post", "/api/nine/election/vote", json={"username": "pl2"}))["election"]
        assert e["my_vote"]["username"] == "pl2" and e["voters"] == 1
        await ann.req("post", "/api/nine/election/vote", json={"username": "pl1"})
        for p in players[2:10]:                                        # 9 voters for p1 (ann + 8)
            await p.req("post", "/api/nine/election/vote", json={"username": "pl1"})
        await ben.req("post", "/api/nine/election/vote", json={"username": "pl3"})     # the 10th voter
        # the day isn't over: still open, even with 10 voters
        e = (await ann.req("get", "/api/nine/election"))["election"]
        assert e["open"] and e["voters"] == 10 and e["top"][0]["user"]["username"] == "pl1" and e["top"][0]["votes"] == 9
        assert (await nine_of(ann))["larper"]["holder"] is None
        async with app.db.session() as s:
            el = await nine.open_election(s)
            el.closes_at = utcnow() - timedelta(minutes=1)
            await s.commit()
        nine._ensure_at["at"] = 0.0
        e = (await ann.req("get", "/api/nine/election"))["election"]
        assert not e["open"] and e["larper"]["username"] == "pl1" and e["next_at"] and e["voters"] == 0
        assert (await nine_of(ann))["larper"]["holder"]["username"] == "pl1"
        assert (await ann.req("get", "/api/users/pl1"))["profile"]["titles"] == ["larper"]
        assert (await ann.req("get", "/api/state"))["state"]["user"]["election"] is False
        await ann.req("post", "/api/nine/election/vote", json={"username": "pl2"}, status=400)
        # the 1st of a month opens the next election for that day; too few voters keep it open
        async with app.db.session() as s:
            first = nine.next_first()
            await nine.ensure(s, now=first + timedelta(hours=1), force=True)
            el = await nine.open_election(s)
            assert el is not None and el.reason == "monthly" and el.closes_at > first
            await nine.ensure(s, now=first + timedelta(hours=2), force=True)      # not twice
            assert await s.scalar(select(nine.Election.id).where(nine.Election.id > el.id)) is None
            await nine.ensure(s, now=first + timedelta(days=3), force=True)       # the day is over, nobody voted
            assert (await nine.open_election(s)).id == el.id
            await s.commit()
        nine._ensure_at["at"] = 0.0
        assert (await ann.req("get", "/api/state"))["state"]["user"]["election"] is True
        for p in players[1:11]:
            await p.req("post", "/api/nine/election/vote", json={"username": "pl0"})
        e = (await ann.req("get", "/api/nine/election"))["election"]
        assert e["open"] and e["voters"] == 10                        # its day (the 1st) isn't over yet
        async with app.db.session() as s:
            (await nine.open_election(s)).closes_at = utcnow() - timedelta(minutes=1)
            await s.commit()
        nine._ensure_at["at"] = 0.0
        e = (await ann.req("get", "/api/nine/election"))["election"]
        assert not e["open"] and e["larper"]["username"] == "pl0"
        # the Larper deletes the account: the title is vacant, an election opens at once
        await ann.req("delete", "/api/account")
        nine._ensure_at["at"] = 0.0
        e = (await ben.req("get", "/api/nine/election"))["election"]
        assert e["open"] and e["larper"] is None
        async with app.db.session() as s:
            assert (await nine.open_election(s)).reason == "vacant"
    run(tmp_path, scenario)
