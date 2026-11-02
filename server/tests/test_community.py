"""Private accounts, rule subscriptions, sharing in chats, group rules and challenges."""
from datetime import timedelta

from sqlalchemy import select

from ..db import Challenge, ChallengeParticipant, Event, User
from .test_social import run, user

RULE = dict(kind="acc", name="Push-ups", icon="dumbbell", mode="constant", base=50)


def by_name(state, name):
    return next(r for r in state["rules"] if r["name"] == name)


async def friends(*clients):
    for a in clients:
        for b in clients:
            if a is not b:
                await a.req("post", f"/api/users/{b.name}/follow")


async def new_day(app):
    """Pretend a new day started for everyone (challenge credit is paid out on day changes)."""
    async with app.db.session() as s:
        for u in await s.scalars(select(User)):
            if u.credit_day:
                u.credit_day -= timedelta(days=1)
        await s.commit()


async def age(app, ch_id, days):
    """Move a challenge (and its events) `days` into the past."""
    async with app.db.session() as s:
        ch = await s.get(Challenge, ch_id)
        ch.start_day -= timedelta(days=days)
        ch.end_day -= timedelta(days=days)
        # everything since it was created (taps on its rules, joining) moves along
        for e in await s.scalars(select(Event).where(Event.created_at >= ch.created_at - timedelta(seconds=1))):
            e.created_at -= timedelta(days=days)
        for p in await s.scalars(select(ChallengeParticipant).where(ChallengeParticipant.challenge_id == ch_id)):
            p.joined_at -= timedelta(days=days)
        ch.created_at -= timedelta(days=days)
        await s.commit()


def test_private_account_requests_and_visibility(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        st = (await ann.req("put", "/api/settings", json={"private": True}))["state"]
        assert st["user"]["private"] is True
        rid = by_name(st, "Workout")["id"]
        await ann.req("put", f"/api/rules/{rid}", json={**RULE, "name": "Workout", "public": True})
        await ann.req("post", "/api/posts", json={"text": "hi"})

        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["locked"] and prof["rules"] == [] and prof["history"] == [] and prof["posts"] == 1
        assert (await ben.req("get", "/api/users/ann/posts"))["posts"] == []
        await ben.req("get", "/api/users/ann/followers", status=403)
        await ben.req("get", f"/api/rules/{rid}/detail", status=404)

        prof = (await ben.req("post", "/api/users/ann/follow"))["profile"]
        assert prof["requested"] and not prof["following"] and prof["followers_count"] == 0
        notes = await ann.req("get", "/api/notifications")
        assert notes["requests"] == 1 and notes["items"][0]["kind"] == "request"
        assert [u["username"] for u in (await ann.req("get", "/api/follow-requests"))["users"]] == ["ben"]

        await ann.req("post", "/api/follow-requests/ben/accept")
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["following"] and not prof["locked"] and len(prof["rules"]) == 1
        assert len((await ben.req("get", "/api/users/ann/posts"))["posts"]) == 1
        assert (await ann.req("get", "/api/notifications"))["requests"] == 0

        # removing a follower locks again; a new request can be declined
        await ann.req("delete", "/api/users/ben/follower")
        assert (await ben.req("get", "/api/users/ann"))["profile"]["locked"]
        await ben.req("post", "/api/users/ann/follow")
        await ann.req("delete", "/api/follow-requests/ben")
        assert not (await ben.req("get", "/api/users/ann"))["profile"]["requested"]

        # going public accepts pending requests
        cat = await user(c, "cat")
        await cat.req("post", "/api/users/ann/follow")
        await ann.req("put", "/api/settings", json={"private": False})
        assert (await cat.req("get", "/api/users/ann"))["profile"]["following"]
    run(tmp_path, scenario)


def test_rule_subscription_follows_owner_changes(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        st = (await ann.req("post", "/api/rules", json={**RULE, "public": True}))["state"]
        root = by_name(st, "Push-ups")["id"]
        private = by_name(st, "Workout")["id"]
        await ben.req("get", f"/api/rules/{private}/detail", status=404)    # not public

        d = (await ben.req("get", f"/api/rules/{root}/detail"))["rule"]
        assert d["can_subscribe"] and d["owner"]["username"] == "ann" and d["base"] == 50
        st = (await ben.req("post", f"/api/rules/{root}/subscribe"))["state"]
        copy = by_name(st, "Push-ups")
        assert copy["origin"] == "sub" and copy["origin_label"] == "ann" and copy["root_id"] == root
        n = len(st["rules"])
        assert len((await ben.req("post", f"/api/rules/{root}/subscribe"))["state"]["rules"]) == n   # no duplicate
        await ann.req("post", f"/api/rules/{root}/subscribe", status=400)                          # own rule
        await ben.req("put", f"/api/rules/{copy['id']}", json={**RULE, "base": 999}, status=400)  # read-only
        d = (await ann.req("get", f"/api/rules/{root}/detail"))["rule"]
        assert d["is_mine"] and d["subscribers"] == 1

        # the owner changes it: the copy follows (and so do its new values)
        await ann.req("put", f"/api/rules/{root}", json={**RULE, "base": 80, "public": True})
        st = (await ben.req("get", "/api/state"))["state"]
        assert by_name(st, "Push-ups")["value"] == 80
        # triggering the copy is ben's own momentum
        r = await ben.req("post", f"/api/rules/{copy['id']}/trigger")
        assert r["delta"] == 80 and r["state"]["momentum"] == 80

        # owner deletes: the subscription goes away
        await ann.req("delete", f"/api/rules/{root}")
        st = (await ben.req("get", "/api/state"))["state"]
        assert "Push-ups" not in [r["name"] for r in st["rules"]]

        # subscribing via a shared copy subscribes to the original
        await friends(ann, cat)
        root2 = by_name((await ann.req("post", "/api/rules", json={**RULE, "name": "Plank", "public": True}))["state"], "Plank")["id"]
        copy2 = by_name((await ben.req("post", f"/api/rules/{root2}/subscribe"))["state"], "Plank")["id"]
        await friends(ben, cat)
        conv = (await ben.req("post", "/api/conversations/direct", json={"username": "cat"}))["conversation"]["id"]
        m = (await ben.req("post", f"/api/conversations/{conv}/messages", json={"share": {"kind": "rule", "id": copy2}}))["message"]
        assert m["share"]["id"] == root2 and m["share"]["owner"] == "ann"
        st = (await cat.req("post", f"/api/rules/{root2}/subscribe"))["state"]
        assert by_name(st, "Plank")["root_id"] == root2
        # unsubscribe = delete the copy
        st = (await ben.req("delete", f"/api/rules/{copy2}"))["state"]
        assert "Plank" not in [r["name"] for r in st["rules"]]
        assert (await ann.req("get", f"/api/rules/{root2}/detail"))["rule"]["subscribers"] == 1
    run(tmp_path, scenario)


def test_sharing_in_chats(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await friends(ann, ben)
        await ann.req("put", "/api/settings", json={"private": True})
        st = (await ann.req("get", "/api/state"))["state"]
        hidden = by_name(st, "Workout")["id"]                    # not public, private account
        post = (await ann.req("post", "/api/posts", json={"text": "secret run"}))["post"]
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]

        # cat can't see ann's stuff and can't share it
        await cat.req("post", f"/api/rules/{hidden}/subscribe", status=404)

        m1 = (await ann.req("post", f"/api/conversations/{conv}/messages",
                            json={"share": {"kind": "rule", "id": hidden}}))["message"]
        assert m1["share"]["name"] == "Workout" and m1["share"]["available"]
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"share": {"kind": "post", "id": post["id"]}})
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"share": {"kind": "profile", "username": "cat"}})
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"share": {"kind": "rule", "id": 99999}}, status=404)

        msgs = (await ben.req("get", f"/api/conversations/{conv}/messages"))["messages"]
        kinds = [m["share"]["kind"] for m in msgs]
        assert kinds == ["rule", "post", "profile"] and all(m["share"]["available"] for m in msgs)
        assert msgs[1]["share"]["caption"] == "secret run" and msgs[2]["share"]["user"]["username"] == "cat"
        # shared by its owner -> ben may open and subscribe it
        assert (await ben.req("get", f"/api/rules/{hidden}/detail"))["rule"]["can_subscribe"]
        await ben.req("post", f"/api/rules/{hidden}/subscribe")
        await ben.req("get", f"/api/posts/{post['id']}")

        # ben re-shares into a group with cat: cat still can't see ann's private content
        await friends(ben, cat)
        group = (await ben.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["cat"]}))["conversation"]["id"]
        await ben.req("put", f"/api/conversations/{group}", json={"anarchy": True})   # everyone may change it (not about admins)
        await ben.req("post", f"/api/conversations/{group}/messages", json={"share": {"kind": "post", "id": post["id"]}})
        shared = (await cat.req("get", f"/api/conversations/{group}/messages"))["messages"][-1]["share"]
        assert shared["available"] is False
        await cat.req("get", f"/api/posts/{post['id']}", status=404)
    run(tmp_path, scenario)


def test_group_rules(tmp_path):
    """Group rules: follow public rules, create own ones, copy own or subscribed rules; auto-follow."""
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await friends(ann, ben)
        await friends(ann, cat)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Gym", "usernames": ["ben", "cat"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}/admins", json={"username": "ben", "admin": True})
        await cat.req("post", f"/api/conversations/{g}/rules", json={**RULE, "name": "Nope"}, status=403)   # members don't add rules
        # a private (not public) rule can't be followed by a group
        hidden = by_name((await ben.req("post", "/api/rules", json={**RULE, "name": "Secret"}))["state"], "Secret")["id"]
        await ben.req("post", f"/api/conversations/{g}/rules", json={"from_rule": hidden}, status=403)

        # cat switches "always follow all" on before anything is listed
        await cat.req("put", f"/api/conversations/{g}/auto-follow", json={"on": True})
        assert (await cat.req("get", f"/api/conversations/{g}"))["conversation"]["auto_follow"] is True

        root = by_name((await ben.req("post", "/api/rules", json={**RULE, "public": True}))["state"], "Push-ups")["id"]
        rule = (await ben.req("post", f"/api/conversations/{g}/rules", json={"from_rule": root}))["rule"]
        assert rule["origin"] == "group" and rule["linked_from"] == "ben" and not rule["editable"]
        await cat.req("get", f"/api/rules/{rule['id']}/detail")                          # members see it
        outsider = await user(c, "dan")
        await outsider.req("get", f"/api/conversations/{g}/rules", status=404)          # private group
        # cat got it automatically, ann subscribes by hand
        assert by_name((await cat.req("get", "/api/state"))["state"], "Push-ups")["origin"] == "group"
        rules = (await ann.req("get", f"/api/conversations/{g}/rules"))["rules"]
        assert [r["name"] for r in rules] == ["Push-ups"] and rules[0]["can_subscribe"]
        st = (await ann.req("post", f"/api/rules/{rule['id']}/subscribe"))["state"]
        assert by_name(st, "Push-ups")["origin"] == "group" and by_name(st, "Push-ups")["origin_label"] == "Gym"

        # the owner changes the original: group rule and every subscriber follow
        await ben.req("put", f"/api/rules/{root}", json={**RULE, "base": 70, "public": True})
        assert by_name((await ann.req("get", "/api/state"))["state"], "Push-ups")["value"] == 70
        assert by_name((await cat.req("get", "/api/state"))["state"], "Push-ups")["value"] == 70

        # a rule of the group itself (like on a profile): its creator or the admin edits it
        own = (await ben.req("post", f"/api/conversations/{g}/rules", json={**RULE, "name": "Squats"}))["rule"]
        assert own["editable"] and own["linked_from"] is None and own["group_name"] == "Gym"
        assert (await ann.req("get", f"/api/rules/{own['id']}/detail"))["rule"]["editable"]       # admin
        assert not (await cat.req("get", f"/api/rules/{own['id']}/detail"))["rule"]["editable"]
        await cat.req("put", f"/api/conversations/{g}/rules/{own['id']}", json={**RULE, "name": "Squats", "base": 5}, status=403)
        await ben.req("put", f"/api/conversations/{g}/rules/{own['id']}", json={**RULE, "name": "Squats", "base": 60})
        assert not own["public"]                                   # members subscribe anyway
        assert by_name((await ann.req("post", f"/api/rules/{own['id']}/subscribe"))["state"], "Squats")["origin"] == "group"
        assert by_name((await cat.req("get", "/api/state"))["state"], "Squats")["value"] == 60   # auto-follow

        # a real copy of one of my rules - own or subscribed - no link to the original
        # (not my subscription of the group's own rule: the group has that one already)
        await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": by_name(st, "Push-ups")["id"]}, status=400)
        mine = by_name((await ann.req("post", "/api/rules", json={**RULE, "name": "Plank", "base": 30}))["state"], "Plank")["id"]
        copy = (await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": mine}))["rule"]
        assert copy["editable"] and copy["linked_from"] is None and copy["base"] == 30
        await ann.req("put", f"/api/rules/{mine}", json={**RULE, "name": "Plank", "base": 99})
        assert next(r for r in (await ben.req("get", f"/api/conversations/{g}/rules"))["rules"] if r["id"] == copy["id"])["base"] == 30
        await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": hidden}, status=404)   # not mine

        # switching auto-follow off keeps the subscriptions; new rules aren't added any more
        await cat.req("put", f"/api/conversations/{g}/auto-follow", json={"on": False})
        assert "Push-ups" in [r["name"] for r in (await cat.req("get", "/api/state"))["state"]["rules"]]
        await ann.req("post", f"/api/conversations/{g}/rules", json={**RULE, "name": "Lunges"})
        assert "Lunges" not in [r["name"] for r in (await cat.req("get", "/api/state"))["state"]["rules"]]
        await cat.req("put", f"/api/conversations/{g}/auto-follow", json={"on": True})
        assert "Lunges" in [r["name"] for r in (await cat.req("get", "/api/state"))["state"]["rules"]]

        # leaving the group keeps the rule as an own rule
        await ann.req("delete", f"/api/conversations/{g}/members/me")
        assert by_name((await ann.req("get", "/api/state"))["state"], "Push-ups")["origin"] == "own"
    run(tmp_path, scenario)


def test_public_and_open_groups(tmp_path):
    async def scenario(app, c):
        ann, ben, eve = await user(c, "ann"), await user(c, "ben"), await user(c, "eve")
        await friends(ann, ben)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Lauftreff", "usernames": ["ben"]}))["conversation"]["id"]
        assert (await eve.req("get", "/api/groups/search?q=lauf"))["groups"] == []        # private: not found
        await eve.req("get", f"/api/conversations/{g}", status=404)
        await ben.req("put", f"/api/conversations/{g}", json={"public": True}, status=403)  # admin only
        # open only together with public
        conv = (await ann.req("put", f"/api/conversations/{g}", json={"open": True}))["conversation"]
        assert not conv["public"] and not conv["open"]
        conv = (await ann.req("put", f"/api/conversations/{g}", json={"public": True}))["conversation"]
        assert conv["public"] and not conv["open"]

        found = (await eve.req("get", "/api/groups/search?q=lauf"))["groups"]
        assert [(x["name"], x["open"], x["members"], x["member"]) for x in found] == [("Lauftreff", False, 2, False)]
        view = (await eve.req("get", f"/api/conversations/{g}"))["conversation"]
        assert view["member"] is False and view["public"]
        await eve.req("get", f"/api/conversations/{g}/messages", status=404)             # no messages for outsiders
        await eve.req("post", f"/api/conversations/{g}/join", status=403)                # closed

        # outsiders see followed and public rules, not the group's private ones
        await ann.req("post", f"/api/conversations/{g}/rules", json={**RULE, "name": "Members only"})
        pub = (await ann.req("post", f"/api/conversations/{g}/rules", json={**RULE, "name": "Intervall", "public": True}))["rule"]
        assert [r["name"] for r in (await eve.req("get", f"/api/conversations/{g}/rules"))["rules"]] == ["Intervall"]
        st = (await eve.req("post", f"/api/rules/{pub['id']}/subscribe"))["state"]
        assert by_name(st, "Intervall")["origin"] == "group"

        await ann.req("put", f"/api/conversations/{g}", json={"open": True})
        joined = (await eve.req("post", f"/api/conversations/{g}/join"))["conversation"]
        assert joined["member"] and len(joined["members"]) == 2
        await eve.req("get", f"/api/conversations/{g}/messages")
        # private again: no longer open either
        conv = (await ann.req("put", f"/api/conversations/{g}", json={"public": False}))["conversation"]
        assert not conv["public"] and not conv["open"]
    run(tmp_path, scenario)


def test_leaderboards(tmp_path):
    async def scenario(app, c):
        ann, ben, cat, dan = [await user(c, n) for n in ("ann", "ben", "cat", "dan")]
        await ann.req("post", "/api/users/ben/follow")                 # ann follows ben, cat follows ann
        await cat.req("post", "/api/users/ann/follow")
        for client, times in ((ann, 1), (ben, 3), (cat, 2), (dan, 4)):
            st = (await client.req("get", "/api/state"))["state"]
            for _ in range(times):
                await client.req("post", f"/api/rules/{by_name(st, 'Task done')['id']}/trigger")
        top = await ann.req("get", "/api/leaderboard?scope=global")
        assert [e["user"]["username"] for e in top["entries"]] == ["dan", "ben", "cat", "ann"]
        assert top["me"]["rank"] == 4 and top["total"] == 4
        friends_ = await ann.req("get", "/api/leaderboard?scope=friends")
        assert [(e["rank"], e["user"]["username"]) for e in friends_["entries"]] == [(1, "ben"), (2, "cat"), (3, "ann")]
        assert friends_["me"]["rank"] == 3
        await friends(ann, ben)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        assert [e["user"]["username"] for e in (await ben.req("get", f"/api/conversations/{g}/ranking"))["entries"]] == ["ben", "ann"]
        await dan.req("get", f"/api/conversations/{g}/ranking", status=404)
    run(tmp_path, scenario)


async def setup_group(c, *names):
    clients = [await user(c, n) for n in names]
    await friends(*clients)
    g = (await clients[0].req("post", "/api/conversations/group",
                              json={"name": "Crew", "usernames": list(names[1:])}))["conversation"]["id"]
    # the group's rules a challenge can play with
    push = (await clients[0].req("post", f"/api/conversations/{g}/rules", json=RULE))["rule"]["id"]
    sofa = (await clients[0].req("post", f"/api/conversations/{g}/rules",
                                 json=dict(kind="dec", name="Sofa", icon="tv", mode="constant", base=20)))["rule"]["id"]
    return clients, g, push, sofa


def challenge(push, sofa, **kw):
    return dict(dict(title="Push-up week", mode="ranking", days=7, start_in=0, reward1=300, reward2=200, reward3=100,
                     rules=[{"rule_id": push}, {"rule_id": sofa}]), **kw)


def test_challenge_ranking_rewards_and_cleanup(tmp_path):
    async def scenario(app, c):
        (ann, ben, cat, dan), g, push, sofa_id = await setup_group(c, "ann", "ben", "cat", "dan")
        # only rules from the group's list
        await ann.req("post", f"/api/conversations/{g}/challenges",
                      json=challenge(push, sofa_id, rules=[RULE]), status=404)
        ch = (await ann.req("post", f"/api/conversations/{g}/challenges", json=challenge(push, sofa_id)))["challenge"]
        assert ch["status"] == "running" and ch["joined"] and ch["days"] == 7 and ch["days_left"] == 7
        assert [r["name"] for r in ch["rules"]] == ["Push-ups", "Sofa"]
        msgs = (await ben.req("get", f"/api/conversations/{g}/messages"))["messages"]
        assert msgs[-1]["share"]["kind"] == "challenge" and msgs[-1]["share"]["challenge"]["title"] == "Push-up week"

        # the creator subscribed on start; can't unsubscribe while taking part
        st = (await ann.req("get", "/api/state"))["state"]
        mine = by_name(st, "Push-ups")
        assert mine["origin"] == "group" and mine["locked_by"] == "Push-up week"
        await ann.req("delete", f"/api/rules/{mine['id']}", status=400)
        await ann.req("put", f"/api/conversations/{g}/rules/{push}", json=RULE)            # still editable
        await ann.req("delete", f"/api/conversations/{g}/rules/{push}", status=400)       # but not removable
        # cat was subscribed before: that subscription stays after the challenge
        await cat.req("post", f"/api/rules/{push}/subscribe")
        for p in (ben, cat, dan):
            await p.req("post", f"/api/challenges/{ch['id']}/join")
        rid = {p.name: by_name((await p.req("get", "/api/state"))["state"], "Push-ups")["id"] for p in (ann, ben, cat, dan)}
        sofa = by_name((await ben.req("get", "/api/state"))["state"], "Sofa")["id"]
        await ben.req("post", f"/api/challenges/{ch['id']}/join")       # idempotent
        # ben keeps the rules afterwards
        assert (await ben.req("put", f"/api/challenges/{ch['id']}/keep-rules", json={"keep": True}))["challenge"]["keep_rules"]

        for _ in range(2):
            await ann.req("post", f"/api/rules/{rid['ann']}/trigger")
            await ben.req("post", f"/api/rules/{rid['ben']}/trigger")
        await ben.req("post", f"/api/rules/{sofa}/trigger")
        await cat.req("post", f"/api/rules/{rid['cat']}/trigger")
        for _ in range(3):
            await dan.req("post", f"/api/rules/{rid['dan']}/trigger")
        await dan.req("post", f"/api/challenges/{ch['id']}/leave")
        assert "Push-ups" not in [r["name"] for r in (await dan.req("get", "/api/state"))["state"]["rules"]]
        await dan.req("post", f"/api/challenges/{ch['id']}/join", status=400)        # leaving is final

        live = (await cat.req("get", f"/api/challenges/{ch['id']}"))["challenge"]
        assert [(b["user"]["username"], b["score"]) for b in live["leaderboard"]] == [("ann", 100), ("ben", 80), ("cat", 50)]
        assert live["my_rank"] == 3

        await age(app, ch["id"], 7)
        done = (await ann.req("get", f"/api/challenges/{ch['id']}"))["challenge"]
        assert done["status"] == "finished"
        # rewards are credit: paid out when the next day starts
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["challenge_credit"] == 300 and not any(e["kind"] == "reward" for e in st["events"])
        await new_day(app)
        before = {p.name: (await p.req("get", "/api/state"))["state"]["momentum"] for p in (ann, ben, cat)}
        assert [(b["user"]["username"], b["rank"], b["reward"]) for b in done["leaderboard"]] == \
            [("ann", 1, 300), ("ben", 2, 200), ("cat", 3, 100)]
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["momentum"] == before["ann"] and st["challenge_credit"] == 0
        assert any(e["kind"] == "reward" and e["delta"] == 300 for e in st["events"])
        # ann loses the challenge's rules, ben kept them, cat's earlier subscription stays (unlocked)
        assert "Push-ups" not in [r["name"] for r in st["rules"]]
        bst = (await ben.req("get", "/api/state"))["state"]
        assert by_name(bst, "Push-ups")["locked_by"] is None and by_name(bst, "Sofa")
        cst = (await cat.req("get", "/api/state"))["state"]
        assert by_name(cst, "Push-ups")["locked_by"] is None and "Sofa" not in [r["name"] for r in cst["rules"]]
        note = (await cat.req("get", "/api/notifications"))["items"][0]
        assert note["kind"] == "ch_end" and note["challenge"]["rank"] == 3 and note["challenge"]["reward"] == 100
        assert (await dan.req("get", "/api/state"))["state"]["momentum"] == 150
        await ann.req("post", f"/api/challenges/{ch['id']}/join", status=400)
        await ann.req("delete", f"/api/conversations/{g}/rules/{push}")                  # free again
    run(tmp_path, scenario)


def test_challenge_target_mode_upcoming_and_cancel(tmp_path):
    async def scenario(app, c):
        (ann, ben, cat), g, push, sofa = await setup_group(c, "ann", "ben", "cat")
        await ann.req("post", f"/api/conversations/{g}/challenges",
                      json=challenge(push, sofa, mode="target", target=100, rules=[{"rule_id": sofa}]), status=400)
        await ann.req("post", f"/api/conversations/{g}/challenges", json=challenge(push, sofa, reward2=500), status=400)
        await ann.req("post", f"/api/conversations/{g}/challenges", json=challenge(push, sofa, days=6), status=400)  # at least a week

        ch = (await ann.req("post", f"/api/conversations/{g}/challenges",
                            json=challenge(push, sofa, mode="target", target=100, start_in=1, days=7, reward1=100)))["challenge"]
        assert ch["status"] == "upcoming" and ch["starts_in"] == 1 and ch["rewards"] == [100]
        await ben.req("post", f"/api/challenges/{ch['id']}/join")
        await cat.req("post", f"/api/challenges/{ch['id']}/join")
        assert "Push-ups" not in [r["name"] for r in (await ben.req("get", "/api/state"))["state"]["rules"]]

        await age(app, ch["id"], 1)                                     # start day reached
        st = (await ben.req("get", "/api/state"))["state"]
        assert by_name(st, "Push-ups")["locked_by"] == "Push-up week"
        await ben.req("post", f"/api/challenges/{ch['id']}/cancel", status=403)
        for _ in range(2):
            await ben.req("post", f"/api/rules/{by_name(st, 'Push-ups')['id']}/trigger")
        cat_rule = by_name((await cat.req("get", "/api/state"))["state"], "Push-ups")["id"]
        await cat.req("post", f"/api/rules/{cat_rule}/trigger")

        await age(app, ch["id"], 7)
        done = (await ben.req("get", f"/api/challenges/{ch['id']}"))["challenge"]
        rewards = {b["user"]["username"]: b["reward"] for b in done["leaderboard"]}
        assert rewards == {"ben": 100, "cat": 0, "ann": 0}
        # in target mode the reward is at most the target
        await ann.req("post", f"/api/conversations/{g}/challenges",
                      json=challenge(push, sofa, mode="target", target=100, reward1=101), status=400)

        # a cancelled challenge gives nothing and removes the rules
        ch2 = (await ann.req("post", f"/api/conversations/{g}/challenges", json=challenge(push, sofa)))["challenge"]
        await ben.req("post", f"/api/challenges/{ch2['id']}/join")
        m = (await ben.req("get", "/api/state"))["state"]["momentum"]
        await ann.req("post", f"/api/challenges/{ch2['id']}/cancel")
        st = (await ben.req("get", "/api/state"))["state"]
        assert "Push-ups" not in [r["name"] for r in st["rules"]] and st["momentum"] == m
        assert (await ben.req("get", "/api/notifications"))["items"][0]["kind"] == "ch_cancel"

        # leaving the group ends participation
        ch3 = (await ann.req("post", f"/api/conversations/{g}/challenges", json=challenge(push, sofa)))["challenge"]
        await cat.req("post", f"/api/challenges/{ch3['id']}/join")
        await cat.req("delete", f"/api/conversations/{g}/members/me")
        assert "Push-ups" not in [r["name"] for r in (await cat.req("get", "/api/state"))["state"]["rules"]]
        await cat.req("get", f"/api/challenges/{ch3['id']}", status=404)
    run(tmp_path, scenario)


def test_banners_and_rules_followed_from_profiles(tmp_path):
    async def scenario(app, c):
        (ann, ben, cat), g, push, sofa = await setup_group(c, "ann", "ben", "cat")
        st = (await ann.req("put", "/api/settings", json={"banner": "synthwave"}))["state"]
        assert st["user"]["banner"] == "synthwave"
        assert (await ben.req("get", "/api/users/ann"))["profile"]["banner"] == "synthwave"
        await ann.req("put", "/api/settings", json={"banner": "nope"}, status=400)
        await ann.req("put", "/api/settings", json={"banner": "nebula"}, status=400)        # space scene on the road
        await ann.req("put", "/api/settings", json={"banner_air": "deepsea"}, status=400)   # underwater in the air
        st = (await ann.req("put", "/api/settings", json={"banner_air": "sakura", "banner_space": "nebula"}))["state"]
        assert st["user"]["banners"] == {"land": "synthwave", "air": "sakura", "space": "nebula"}
        assert st["user"]["banner"] == "synthwave"
        await ann.req("put", f"/api/conversations/{g}/admins", json={"username": "ben", "admin": True})
        conv = (await ben.req("put", f"/api/conversations/{g}", json={"banner": "forest"}))["conversation"]
        assert conv["banner"] == "forest" and conv["name"] == "Crew"
        assert (await ann.req("put", "/api/settings", json={"banner": None}))["state"]["user"]["banner"] is None

        await ann.req("post", f"/api/conversations/{g}/rules", json={"from_rule": by_name(
            (await ann.req("get", "/api/state"))["state"], "Workout")["id"]}, status=403)      # not public
        root = by_name((await ann.req("post", "/api/rules", json={**RULE, "name": "Dips", "public": True}))["state"], "Dips")["id"]
        gr = (await ann.req("post", f"/api/conversations/{g}/rules", json={"from_rule": root}))["rule"]
        assert gr["origin"] == "group" and gr["linked_from"] == "ann" and not gr["editable"] and gr["removable"]
        await ann.req("post", f"/api/conversations/{g}/rules", json={"from_rule": root}, status=400)   # twice
        await ann.req("put", f"/api/conversations/{g}/rules/{gr['id']}", json={**RULE, "base": 1}, status=400)
        st = (await ben.req("post", f"/api/rules/{gr['id']}/subscribe"))["state"]
        assert by_name(st, "Dips")["origin"] == "group"
        await ann.req("put", f"/api/rules/{root}", json={**RULE, "name": "Dips", "base": 90, "public": True})
        assert next(r for r in (await cat.req("get", f"/api/conversations/{g}/rules"))["rules"] if r["id"] == gr["id"])["base"] == 90
        assert by_name((await ben.req("get", "/api/state"))["state"], "Dips")["value"] == 90

        hidden = by_name((await cat.req("post", "/api/rules", json={**RULE, "name": "Secret"}))["state"], "Secret")["id"]
        await ben.req("post", f"/api/conversations/{g}/rules", json={"from_rule": hidden}, status=404)
        pub = by_name((await cat.req("post", "/api/rules", json={**RULE, "name": "Open", "public": True}))["state"], "Open")["id"]
        await cat.req("put", "/api/settings", json={"private": True})
        await ben.req("post", f"/api/conversations/{g}/rules", json={"from_rule": pub}, status=403)
        await cat.req("put", "/api/settings", json={"private": False})
        await ben.req("post", f"/api/conversations/{g}/rules", json={"from_rule": pub})

        # private again: the group's follow and its members' subscriptions go away
        await ann.req("put", f"/api/rules/{root}", json={**RULE, "name": "Dips", "base": 90, "public": False})
        assert gr["id"] not in [r["id"] for r in (await ann.req("get", f"/api/conversations/{g}/rules"))["rules"]]
        assert "Dips" not in [r["name"] for r in (await ben.req("get", "/api/state"))["state"]["rules"]]
        # deleted: the same (cat's public rule the group followed)
        await cat.req("delete", f"/api/rules/{pub}")
        assert "Open" not in [r["name"] for r in (await ann.req("get", f"/api/conversations/{g}/rules"))["rules"]]

        # a challenge plays with the live group rule (changes apply)
        ch = (await ann.req("post", f"/api/conversations/{g}/challenges",
                            json=challenge(push, sofa, banner="volcano", rules=[{"rule_id": push}, {"rule_id": push}])))["challenge"]
        assert ch["banner"] == "volcano" and [r["name"] for r in ch["rules"]] == ["Push-ups"]
        await ann.req("put", f"/api/conversations/{g}/rules/{push}", json={**RULE, "base": 5})
        assert (await ann.req("get", f"/api/challenges/{ch['id']}"))["challenge"]["rules"][0]["base"] == 5
        await ann.req("post", f"/api/conversations/{g}/challenges", json=challenge(push, sofa, rules=[{"rule_id": 99999}]), status=404)
    run(tmp_path, scenario)


def test_linked_rules_are_subscribed_as_a_chain(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await friends(ann, ben)
        # ann: "Sofa" (dec) taps "Walk" (acc) after 2 idle days; public -> Walk goes public too
        walk = by_name((await ann.req("post", "/api/rules", json={**RULE, "name": "Walk"}))["state"], "Walk")["id"]
        st = (await ann.req("post", "/api/rules", json=dict(kind="dec", name="Sofa", icon="tv", mode="constant", base=20,
                                                            public=True, idle_days=2, idle_rule_id=walk)))["state"]
        sofa = by_name(st, "Sofa")["id"]
        assert by_name(st, "Walk")["public"]
        detail = (await ben.req("get", f"/api/rules/{sofa}/detail"))["rule"]
        assert detail["idle_days"] == 2 and detail["idle_rule"]["name"] == "Walk"
        # ben subscribes to Sofa: Walk comes along, linked like the original
        st = (await ben.req("post", f"/api/rules/{sofa}/subscribe"))["state"]
        bsofa, bwalk = by_name(st, "Sofa"), by_name(st, "Walk")
        assert bsofa["idle_rule_id"] == bwalk["id"] and bsofa["idle_days"] == 2 and bwalk["origin"] == "sub"
        # ben pauses the automatic tap of his subscription
        st = (await ben.req("put", f"/api/rules/{bsofa['id']}/idle-pause", json={"paused": True}))["state"]
        assert by_name(st, "Sofa")["idle_paused"] is True
        # the owner changes the link's days: the subscription follows
        await ann.req("put", f"/api/rules/{sofa}", json=dict(kind="dec", name="Sofa", icon="tv", mode="constant", base=20,
                                                             public=True, idle_days=5, idle_rule_id=walk))
        assert by_name((await ben.req("get", "/api/state"))["state"], "Sofa")["idle_days"] == 5
        # a group copy takes the chain along, linked among the copies
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": sofa})
        names = [r["name"] for r in (await ben.req("get", f"/api/conversations/{g}/rules"))["rules"]]
        assert sorted(names) == ["Sofa", "Walk"]
        # unsubscribing Walk takes the Sofa subscription that taps it along
        st = (await ben.req("delete", f"/api/rules/{bwalk['id']}"))["state"]
        assert not [r for r in st["rules"] if r["name"] in ("Sofa", "Walk")]
    run(tmp_path, scenario)


def test_challenge_credit_fills_the_days_room(tmp_path):
    """Credit 100, yesterday's limit 1000, 950 collected -> +50 when the new day starts."""
    async def scenario(app, c):
        from .. import limits
        from ..db import DailyCap
        ann = await user(c, "ann")
        await ann.req("get", "/api/state")
        yesterday = limits.server_day() - timedelta(days=1)
        async with app.db.session() as s:
            u = await s.scalar(select(User).where(User.username == "ann"))
            s.add(DailyCap(day=yesterday, average=0, bias=1000, cap=1000, players=1))
            s.add(Event(user_id=u.id, kind="acc", label="x", delta=950, momentum_after=950,
                        created_at=limits.day_bounds(yesterday)[0] + timedelta(hours=10)))
            u.challenge_credit, u.credit_day = 100.0, yesterday - timedelta(days=1)
            await s.commit()
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["challenge_credit"] == 50
        assert any(e["kind"] == "reward" and e["delta"] == 50 for e in st["events"])
        # the same day again: nothing more
        assert (await ann.req("get", "/api/state"))["state"]["challenge_credit"] == 50
    run(tmp_path, scenario)


def test_private_group_rule_keeps_member_subscriptions(tmp_path):
    async def scenario(app, c):
        ann, ben, eve = await user(c, "ann"), await user(c, "ben"), await user(c, "eve")
        await friends(ann, ben)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        await ann.req("put", f"/api/conversations/{g}", json={"public": True})
        rule = (await ann.req("post", f"/api/conversations/{g}/rules", json={**RULE, "name": "Intervall", "public": True}))["rule"]
        await ben.req("post", f"/api/rules/{rule['id']}/subscribe")
        await eve.req("post", f"/api/rules/{rule['id']}/subscribe")
        await ann.req("put", f"/api/conversations/{g}/rules/{rule['id']}", json={**RULE, "name": "Intervall", "public": False})
        assert "Intervall" in [r["name"] for r in (await ben.req("get", "/api/state"))["state"]["rules"]]      # member
        assert "Intervall" not in [r["name"] for r in (await eve.req("get", "/api/state"))["state"]["rules"]]  # outsider
        # deleted: everyone loses it
        await ann.req("delete", f"/api/conversations/{g}/rules/{rule['id']}")
        assert "Intervall" not in [r["name"] for r in (await ben.req("get", "/api/state"))["state"]["rules"]]
    run(tmp_path, scenario)


def test_group_copies_once_and_unsubscribe_all(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await friends(ann, ben)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        # "Sofa" (dec) and "Walk" (acc, taps Sofa when idle)
        sofa = by_name((await ann.req("post", "/api/rules", json=dict(kind="dec", name="Sofa", icon="tv", mode="constant", base=20)))["state"], "Sofa")["id"]
        walk = by_name((await ann.req("post", "/api/rules", json={**RULE, "name": "Walk", "idle_days": 2, "idle_rule_id": sofa}))["state"], "Walk")["id"]
        await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": sofa})
        await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": walk})        # chain reuses Sofa
        await ann.req("post", f"/api/conversations/{g}/rules", json={"copy_rule": walk}, status=400)   # once
        names = sorted(r["name"] for r in (await ben.req("get", f"/api/conversations/{g}/rules"))["rules"])
        assert names == ["Sofa", "Walk"]
        # ben follows all, then unsubscribes all at once (auto-follow goes off)
        await ben.req("put", f"/api/conversations/{g}/auto-follow", json={"on": True})
        assert {"Sofa", "Walk"} <= {r["name"] for r in (await ben.req("get", "/api/state"))["state"]["rules"]}
        st = (await ben.req("delete", f"/api/conversations/{g}/subscriptions"))["state"]
        assert not {"Sofa", "Walk"} & {r["name"] for r in st["rules"]}
        assert (await ben.req("get", f"/api/conversations/{g}"))["conversation"]["auto_follow"] is False
    run(tmp_path, scenario)


def test_group_leaderboard(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await friends(ann, ben, cat)
        st = (await ben.req("get", "/api/state"))["state"]
        await ben.req("post", f"/api/rules/{by_name(st, 'Task done')['id']}/trigger")      # ben: 100
        g1 = (await ann.req("post", "/api/conversations/group", json={"name": "Eins", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g1}", json={"anarchy": True})   # everyone may change it (not about admins)
        g2 = (await ann.req("post", "/api/conversations/group", json={"name": "Zwei", "usernames": ["cat"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g2}", json={"anarchy": True})   # everyone may change it (not about admins)
        g3 = (await ann.req("post", "/api/conversations/group", json={"name": "Privat", "usernames": ["cat"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g3}", json={"anarchy": True})   # everyone may change it (not about admins)
        for g in (g1, g2):
            await ann.req("put", f"/api/conversations/{g}", json={"public": True})
        r = await cat.req("get", "/api/leaderboard?scope=groups")
        assert [(x["name"], x["total"], x["member"]) for x in r["groups"]] == [("Eins", 100, False), ("Zwei", 0, True)]
        assert [x["name"] for x in r["mine"]] == ["Zwei"]                                  # private groups aren't listed
    run(tmp_path, scenario)


def test_bulk_actions(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await friends(ann, ben)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        await ann.req("put", f"/api/conversations/{g}", json={"anarchy": True})   # everyone may change it (not about admins)
        st = (await ann.req("get", "/api/state"))["state"]
        mine = [r["id"] for r in st["rules"]][:3]
        # copy three into the group at once; a second time they're skipped
        r = await ann.req("post", f"/api/conversations/{g}/rules/bulk", json={"action": "copy", "ids": mine})
        assert r["done"] == 3 and r["skipped"] == []
        r = await ann.req("post", f"/api/conversations/{g}/rules/bulk", json={"action": "copy", "ids": mine})
        assert r["done"] == 0 and {x["reason"] for x in r["skipped"]} == {"already"}
        tpl = [x["id"] for x in (await ben.req("get", f"/api/conversations/{g}/rules"))["rules"]]
        # ben subscribes to all of them, with auto-follow on; unsubscribing turns auto-follow off
        await ben.req("put", f"/api/conversations/{g}/auto-follow", json={"on": True})
        r = await ben.req("post", "/api/rules/subscribe-many", json={"ids": tpl})
        assert r["done"] == 3
        copies = [x["id"] for x in r["state"]["rules"] if x["origin"] == "group"]
        r = await ben.req("post", "/api/rules/remove-many", json={"ids": copies[:2] + [999999]})
        assert r["done"] == 2
        assert (await ben.req("get", f"/api/conversations/{g}"))["conversation"]["auto_follow"] is False
        # ben can't take ann's rules out of the group, ann can
        r = await ben.req("post", f"/api/conversations/{g}/rules/bulk", json={"action": "remove", "ids": tpl})
        assert r["done"] == 0 and {x["reason"] for x in r["skipped"]} == {"not_allowed"}
        r = await ann.req("post", f"/api/conversations/{g}/rules/bulk", json={"action": "remove", "ids": tpl})
        assert r["done"] == 3 and (await ann.req("get", f"/api/conversations/{g}/rules"))["rules"] == []
        # own rules: deleted in one go
        r = await ann.req("post", "/api/rules/remove-many", json={"ids": mine})
        assert r["done"] == 3 and not set(mine) & {x["id"] for x in r["state"]["rules"]}
    run(tmp_path, scenario)


def test_bulk_visibility(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await friends(ann, ben)
        st = (await ann.req("get", "/api/state"))["state"]
        task, doom = by_name(st, "Task done")["id"], by_name(st, "Doomscrolling")["id"]
        r = await ann.req("post", "/api/rules/visibility-many", json={"ids": [task, doom], "public": True})
        assert r["done"] == 2 and all(x["public"] for x in r["state"]["rules"] if x["id"] in (task, doom))
        await ben.req("post", f"/api/rules/{task}/subscribe")
        r = await ann.req("post", "/api/rules/visibility-many", json={"ids": [task], "public": False})
        assert r["done"] == 1
        st = (await ben.req("get", "/api/state"))["state"]
        assert not [x for x in st["rules"] if x.get("root_id") == task]           # private again: unsubscribed
        # the group's own rules: only their creator (or the group's) changes the visibility
        conv = (await ann.req("post", "/api/conversations/group", json={"name": "G", "usernames": ["ben"]}))["conversation"]["id"]
        own = (await ann.req("post", f"/api/conversations/{conv}/rules", json={**RULE, "name": "Squats"}))["rule"]["id"]
        mine = (await ann.req("post", f"/api/conversations/{conv}/rules", json={**RULE, "name": "Plank"}))["rule"]["id"]
        await ben.req("post", f"/api/conversations/{conv}/rules/bulk", json={"action": "public", "ids": [own, mine]}, status=403)
        r = await ann.req("post", f"/api/conversations/{conv}/rules/bulk", json={"action": "public", "ids": [own, mine]})
        assert r["done"] == 2                                                     # the group's creator may
        rules = {x["id"]: x for x in (await ann.req("get", f"/api/conversations/{conv}/rules"))["rules"]}
        assert rules[own]["public"] and rules[mine]["public"]
    run(tmp_path, scenario)


def test_untappable_rule_is_kept_by_copies_and_only_the_owner_changes_it(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await friends(ann, ben)
        st = (await ann.req("post", "/api/rules", json={**RULE, "name": "Nur automatisch", "public": True, "tappable": False}))["state"]
        rule = by_name(st, "Nur automatisch")
        assert rule["tappable"] is False
        await ann.req("post", f"/api/rules/{rule['id']}/trigger", status=409)
        st = (await ben.req("post", f"/api/rules/{rule['id']}/subscribe"))["state"]
        copy = next(r for r in st["rules"] if r.get("root_id") == rule["id"])
        assert copy["tappable"] is False                                   # taken over when subscribing
        await ben.req("post", f"/api/rules/{copy['id']}/trigger", status=409)
        await ben.req("put", f"/api/rules/{copy['id']}", json={**RULE, "name": "x", "tappable": True}, status=400)   # not his
        assert (await ben.req("get", f"/api/rules/{rule['id']}/detail"))["rule"]["tappable"] is False
        # the owner allows it again: the subscription follows
        await ann.req("put", f"/api/rules/{rule['id']}", json={**RULE, "name": "Nur automatisch", "public": True, "tappable": True})
        st = (await ben.req("get", "/api/state"))["state"]
        assert next(r for r in st["rules"] if r["id"] == copy["id"])["tappable"] is True
        await ben.req("post", f"/api/rules/{copy['id']}/trigger")
    run(tmp_path, scenario)


def test_group_admins_and_anarchy(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await friends(ann, ben, cat)
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben"]}))["conversation"]
        assert g["is_admin"] is True and g["admins"] == ["ann"] and g["anarchy"] is False
        gid = g["id"]
        conv = (await ben.req("get", f"/api/conversations/{gid}"))["conversation"]
        assert conv["is_admin"] is False
        # members can't change the group, add people or rules, or start challenges
        await ben.req("put", f"/api/conversations/{gid}", json={"name": "Meine"}, status=403)
        await ben.req("post", f"/api/conversations/{gid}/members", json={"usernames": ["cat"]}, status=403)
        await ben.req("post", f"/api/conversations/{gid}/rules", json={**RULE, "name": "x"}, status=403)
        rule = (await ann.req("post", f"/api/conversations/{gid}/rules", json={**RULE, "name": "Lauf"}))["rule"]
        await ben.req("post", f"/api/conversations/{gid}/challenges", status=403,
                      json=dict(title="Woche", mode="ranking", days=7, start_in=0, reward1=100, rules=[{"rule_id": rule["id"]}]))
        await ben.req("put", f"/api/conversations/{gid}/admins", json={"username": "ben", "admin": True}, status=403)
        # but they still chat and subscribe
        await ben.req("post", f"/api/conversations/{gid}/messages", json={"text": "hi"})
        await ben.req("post", f"/api/rules/{rule['id']}/subscribe")
        # an admin makes another admin; the last admin can't step down
        await ann.req("put", f"/api/conversations/{gid}/admins", json={"username": "ann", "admin": False}, status=400)
        conv = (await ann.req("put", f"/api/conversations/{gid}/admins", json={"username": "ben", "admin": True}))["conversation"]
        assert conv["admins"] == ["ann", "ben"]
        await ben.req("post", f"/api/conversations/{gid}/members", json={"usernames": ["cat"]})
        await ben.req("put", f"/api/conversations/{gid}/admins", json={"username": "ann", "admin": False})
        await ben.req("put", f"/api/conversations/{gid}/admins", json={"username": "ben", "admin": False}, status=400)
        # anarchy: everyone is an admin
        await ben.req("put", f"/api/conversations/{gid}", json={"anarchy": True})
        assert (await cat.req("get", f"/api/conversations/{gid}"))["conversation"]["is_admin"] is True
        await cat.req("put", f"/api/conversations/{gid}", json={"name": "Chaos"})
        await cat.req("put", f"/api/conversations/{gid}", json={"anarchy": False})
        assert (await cat.req("get", f"/api/conversations/{gid}"))["conversation"]["is_admin"] is False
        # the last admin leaves: the longest member takes over
        await ben.req("delete", f"/api/conversations/{gid}/members/me")
        assert (await ann.req("get", f"/api/conversations/{gid}"))["conversation"]["admins"] == ["ann"]
    run(tmp_path, scenario)


def test_nobody_has_the_same_rule_twice(tmp_path):
    """A rule followed by a group is the same rule: whoever has it (own, via the person, via the group)
    can't subscribe it a second way; a group can't take it in twice either."""
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await friends(ann, ben, cat)
        root = by_name((await ann.req("post", "/api/rules", json={**RULE, "public": True}))["state"], "Push-ups")["id"]
        g = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben", "cat"]}))["conversation"]["id"]
        tpl = (await ann.req("post", f"/api/conversations/{g}/rules", json={"from_rule": root}))["rule"]["id"]

        def pushups(state):
            return [r for r in state["rules"] if r["name"] == "Push-ups"]
        # ben has it from ann: the group's version is the same rule
        await ben.req("post", f"/api/rules/{root}/subscribe")
        d = (await ben.req("get", f"/api/rules/{tpl}/detail"))["rule"]
        assert d["have_already"] and not d["can_subscribe"]
        await ben.req("post", f"/api/rules/{tpl}/subscribe", status=400)
        r = await ben.req("post", "/api/rules/subscribe-many", json={"ids": [tpl]})
        assert r["done"] == 0 and r["skipped"][0]["reason"] == "already"
        await ben.req("put", f"/api/conversations/{g}/auto-follow", json={"on": True})     # follow all: still once
        assert len(pushups((await ben.req("get", "/api/state"))["state"])) == 1
        # the owner can't subscribe their own rule through the group
        assert (await ann.req("get", f"/api/rules/{tpl}/detail"))["rule"]["have_already"]
        await ann.req("post", f"/api/rules/{tpl}/subscribe", status=400)
        assert len(pushups((await ann.req("get", "/api/state"))["state"])) == 1
        # the other way round: cat has it via the group, then not from ann
        await cat.req("post", f"/api/rules/{tpl}/subscribe")
        await cat.req("post", f"/api/rules/{root}/subscribe", status=400)
        assert len(pushups((await cat.req("get", "/api/state"))["state"])) == 1
        # a group takes it in once: also not through someone's subscription of it
        bens = pushups((await ben.req("get", "/api/state"))["state"])[0]["id"]
        await ann.req("post", f"/api/conversations/{g}/rules", json={"from_rule": bens}, status=400)
        assert len((await ann.req("get", f"/api/conversations/{g}/rules"))["rules"]) == 1
    run(tmp_path, scenario)
