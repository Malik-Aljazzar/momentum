"""Shared decays: follow a person's public decay (it replaces your own), group decays, a challenge's
forced decay, and that the night uses the followed settings."""
from datetime import timedelta

from sqlalchemy import func, select

from ..db import DailyDecay, Event, User
from .test_community import RULE, friends
from .test_social import run, user

DECAY = dict(base=80, threshold=300, catalyst_mode="none", catalyst_a=0, catalyst_b=1.5)


def test_follow_a_persons_public_decay(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        st = (await ann.req("put", "/api/decay", json={**DECAY, "public": True}))["state"]
        assert st["decay"]["public"] is True and st["decay"]["follow"] is None
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["decay"]["base"] == 80 and prof["decay"]["can_follow"] is True
        own = (await ben.req("get", "/api/state"))["state"]["decay"]["base"]
        st = (await ben.req("post", "/api/decay/follow", json={"username": "ann"}))["state"]
        assert st["decay"]["base"] == 80 and st["decay"]["follow"]["kind"] == "user" and st["decay"]["follow"]["label"] == "ann"
        # the owner changes it: followers follow
        await ann.req("put", "/api/decay", json={**DECAY, "base": 120})
        assert (await ben.req("get", "/api/state"))["state"]["decay"]["base"] == 120
        assert (await cat.req("get", "/api/decays/user/ann"))["decay"]["followers"] == 1
        # the night uses the followed decay (ben's own streak)
        async with app.db.session() as s:
            b = await s.scalar(select(User).where(User.username == "ben"))
            dd = await s.get(DailyDecay, b.id)
            dd.last_processed_day = dd.last_processed_day - timedelta(days=1)
            b.momentum = 1000
            await s.commit()
        await ben.req("get", "/api/state")
        async with app.db.session() as s:
            b = await s.scalar(select(User).where(User.username == "ben"))
            ev = await s.scalar(select(Event).where(Event.user_id == b.id, Event.kind == "daily"))
            assert ev is not None and ev.delta == -120
        # private again: back to his own
        await ann.req("put", "/api/decay", json={**DECAY, "base": 120, "public": False})
        st = (await ben.req("get", "/api/state"))["state"]
        assert st["decay"]["follow"] is None and st["decay"]["base"] == own
        await ben.req("post", "/api/decay/follow", json={"username": "ann"}, status=404)
        assert (await ben.req("get", "/api/users/ann"))["profile"]["decay"] is None
    run(tmp_path, scenario)


def test_group_decays_and_a_challenge_forcing_one(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        await friends(ann, ben, cat)
        await ann.req("put", "/api/decay", json={**DECAY, "base": 10, "public": True})
        gid = (await ann.req("post", "/api/conversations/group", json={"name": "Crew", "usernames": ["ben"]}))["conversation"]["id"]
        # admins make decays (several), follow a person's public one; members can't
        await ben.req("post", f"/api/conversations/{gid}/decays", json={**DECAY, "name": "x"}, status=403)
        hard = (await ann.req("post", f"/api/conversations/{gid}/decays", json={**DECAY, "name": "Hart", "base": 200}))["decay"]
        linked = (await ann.req("post", f"/api/conversations/{gid}/decays", json={"from_user": "ann"}))["decay"]
        assert linked["linked_from"] == "ann" and linked["base"] == 10 and not linked["editable"]
        decays = (await ben.req("get", f"/api/conversations/{gid}/decays"))["decays"]
        assert [d["name"] for d in decays] == ["Hart", "ann"]
        # ann's own decay, followed by the group: she can't follow it through the group
        mine = (await ann.req("get", f"/api/decays/{linked['id']}"))["decay"]
        assert mine["is_mine"] and not mine["can_follow"]
        await ann.req("post", "/api/decay/follow", json={"decay_id": linked["id"]}, status=400)
        # a member follows a group decay; an outsider of the private group can't
        st = (await ben.req("post", "/api/decay/follow", json={"decay_id": hard["id"]}))["state"]
        assert st["decay"]["base"] == 200 and st["decay"]["follow"] == {
            "kind": "group", "label": "Crew", "decay_id": hard["id"], "group_id": gid, "name": "Hart"}
        await cat.req("post", "/api/decay/follow", json={"decay_id": hard["id"]}, status=404)
        await ann.req("put", f"/api/conversations/{gid}/decays/{hard['id']}", json={**DECAY, "name": "Hart", "base": 250})
        assert (await ben.req("get", "/api/state"))["state"]["decay"]["base"] == 250
        # a challenge forces its decay: ben follows "linked" while taking part, then "Hart" again
        push = (await ann.req("post", f"/api/conversations/{gid}/rules", json=RULE))["rule"]["id"]
        ch = (await ann.req("post", f"/api/conversations/{gid}/challenges", json=dict(
            title="Woche", mode="ranking", days=7, start_in=0, reward1=100, rules=[{"rule_id": push}],
            decay_id=linked["id"])))["challenge"]
        assert ch["decay"]["id"] == linked["id"]
        await ben.req("post", f"/api/challenges/{ch['id']}/join")
        st = (await ben.req("get", "/api/state"))["state"]
        assert st["decay"]["follow"]["decay_id"] == linked["id"] and st["decay"]["base"] == 10 and st["decay"]["locked_by"] == "Woche"
        await ben.req("delete", "/api/decay/follow", status=400)
        await ann.req("delete", f"/api/conversations/{gid}/decays/{linked['id']}", status=400)   # in use
        await ben.req("post", f"/api/challenges/{ch['id']}/leave")
        st = (await ben.req("get", "/api/state"))["state"]
        assert st["decay"]["follow"]["decay_id"] == hard["id"] and st["decay"]["locked_by"] is None
        # the group decay is deleted: back to the own decay
        await ann.req("delete", f"/api/conversations/{gid}/decays/{hard['id']}")
        assert (await ben.req("get", "/api/state"))["state"]["decay"]["follow"] is None
    run(tmp_path, scenario)


def test_daily_decay_only_once_with_requests_at_the_same_time(tmp_path):
    """Report #27: two requests catching up at the same time both booked the night's decay."""
    async def scenario(app, c):
        from .. import service
        ann = await user(c, "ann")
        await ann.req("get", "/api/state")
        async with app.db.session() as s:
            a = await s.scalar(select(User).where(User.username == "ann"))
            dd = await s.get(DailyDecay, a.id)
            dd.last_processed_day = dd.last_processed_day - timedelta(days=1)
            a.momentum = a.free_score = 1000
            await s.commit()
        # two sessions load the same (old) state, then both catch up
        async with app.db.session() as s1, app.db.session() as s2:
            u1 = await s1.scalar(select(User).where(User.username == "ann"))
            u2 = await s2.scalar(select(User).where(User.username == "ann"))
            _ = (u1.decay.last_processed_day, u2.decay.last_processed_day, u1.settings.timezone, u2.settings.timezone)
            await service.catch_up(s1, u1)
            await s1.commit()
            await service.catch_up(s2, u2)
            await s2.commit()
        async with app.db.session() as s:
            a = await s.scalar(select(User).where(User.username == "ann"))
            n = await s.scalar(select(func.count()).select_from(Event).where(Event.user_id == a.id, Event.kind == "daily"))
            assert n == 1
    run(tmp_path, scenario)


def test_activity_marked_once_with_requests_at_the_same_time(tmp_path):
    """Two requests of the same player at once both found no activity row for today; the second one
    failed on the unique (user_id, day) instead of answering (seen in the production log)."""
    async def scenario(app, c):
        from .. import limits
        from ..db import UserActivity
        ann = await user(c, "ann")
        await ann.req("get", "/api/state")
        async with app.db.session() as s:
            a = await s.scalar(select(User).where(User.username == "ann"))
            await s.execute(UserActivity.__table__.delete())
            await s.commit()
        async with app.db.session() as s:
            await limits.mark_active(s, a.id)
            await s.commit()
        # the second request had looked before the first one wrote its row
        async with app.db.session() as s:
            async def nothing_yet(*_):
                return None
            s.get = nothing_yet
            await limits.mark_active(s, a.id)
            await s.commit()
        async with app.db.session() as s:
            assert await s.scalar(select(func.count()).select_from(UserActivity)) == 1
    run(tmp_path, scenario)
