"""Daily limit, undo, offline sync, milestones on the last stage, read receipts."""
import asyncio
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete

from server import limits
from server.db import DailyCap, Event, UserActivity
from .test_api import make_app, set_step
from .test_social import user


def run(tmp_path, scenario):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            await scenario(app, ta.test_client())
    asyncio.run(go())


def rule_id(st, name):
    return next(r["id"] for r in st["rules"] if r["name"] == name)


def test_no_milestone_animation_on_last_stage(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await set_step(app, "ann", 100)
        st = (await ann.req("get", "/api/state"))["state"]
        task = rule_id(st, "Task done")                     # +100 each
        flags = [(await ann.req("post", f"/api/rules/{task}/trigger"))["milestone"] for _ in range(4)]
        assert flags == [True, True, False, True]           # milestones double: 100, 200, 400
        async with app.db.session() as s:                   # just below the last vehicle (2000 x step)
            from sqlalchemy import select as sel
            from ..db import User as U
            (await s.scalar(sel(U).where(U.username == "ann"))).momentum = 199_950
            await s.commit()
        flags = [(await ann.req("post", f"/api/rules/{task}/trigger"))["milestone"] for _ in range(4)]
        assert flags == [True, False, False, False]         # the cruiser; no "level up" beyond the last stage
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["vehicle"]["index"] == 12
        await ann.req("put", "/api/settings", json={"milestone_step": 5})
        assert (await ann.req("get", "/api/state"))["state"]["settings"]["milestone_step"] == 100   # not editable
    run(tmp_path, scenario)


def test_daily_limit_from_yesterdays_average(tmp_path):
    async def scenario(app, c):
        ann, ben, cat = await user(c, "ann"), await user(c, "ben"), await user(c, "cat")
        yesterday = limits.server_day() - timedelta(days=1)
        async with app.db.session() as s:
            for uid in (1, 2, 3):                           # all three were online yesterday (doesn't count)
                s.add(UserActivity(user_id=uid, day=yesterday))
            # cat only got an automatic tap, the daily decay and an undone tap: not an active player
            at = limits.day_bounds(yesterday)[0] + timedelta(hours=8)
            for kind, extra in (("acc", dict(auto_from_rule_id=1)), ("daily", {}), ("acc", dict(undone=True))):
                s.add(Event(user_id=3, kind=kind, label="x", delta=10, momentum_after=10, created_at=at, **extra))
            # ben counts for his own taps below - then his automatic taps are part of his growth (+20)
            for delta in (-40, 60):
                s.add(Event(user_id=2, kind="dec" if delta < 0 else "acc", label="x", delta=delta, momentum_after=0,
                            created_at=at, auto_from_rule_id=1))
            # yesterday's own limit is not under test here (offline taps count against their own day)
            # yesterday's rate 300: today's average 116.7 lies between 0.25 and 0.75 of it -> stays 300
            s.add(DailyCap(day=yesterday, average=0, bias=300, cap=300, players=0))
            await s.commit()
        # 10:00 server time yesterday
        y = datetime.combine(yesterday, datetime.min.time()).replace(hour=10, tzinfo=limits.SERVER_TZ).isoformat()
        st = (await ann.req("get", "/api/state"))["state"]
        for _ in range(3):                                   # ann: +300
            await ann.req("post", f"/api/rules/{rule_id(st, 'Task done')}/trigger", json={"at": y})
        bst = (await ben.req("get", "/api/state"))["state"]
        for _ in range(2):                                   # ben: +200 ...
            await ben.req("post", f"/api/rules/{rule_id(bst, 'Task done')}/trigger", json={"at": y})
        await ben.req("post", f"/api/rules/{rule_id(bst, 'Doomscrolling')}/trigger", json={"at": y})   # ... -150 = +50
        # today's limit was frozen at the first request of the day (registration); recompute it now
        async with app.db.session() as s:
            await s.execute(delete(DailyCap).where(DailyCap.day == limits.server_day()))
            await s.commit()
        # cat didn't tap herself -> not counted. Average (300 + 50 + 20) / 2 = 185 -> the rate stays 300
        st = (await cat.req("get", "/api/state"))["state"]
        assert st["limit"]["cap"] == 300 and st["limit"]["average"] == 185 and st["limit"]["players"] == 2
        r = await cat.req("post", f"/api/rules/{rule_id(st, 'Workout')}/trigger")        # 400 -> capped
        assert r["delta"] == 300 and r["capped"] and r["state"]["limit"]["remaining"] < 1
        r = await cat.req("post", f"/api/rules/{rule_id(st, 'Task done')}/trigger", status=409)
        assert "Daily limit" in r["error"]
        dec = await cat.req("post", f"/api/rules/{rule_id(st, 'Doomscrolling')}/trigger")   # decelerators always apply
        assert dec["delta"] == -150
        # decelerating lowers what counts against the limit: 300 - 150 = 150 counted
        assert round(dec["state"]["limit"]["remaining"]) == 150
        again = await cat.req("post", f"/api/rules/{rule_id(st, 'Task done')}/trigger")
        assert again["delta"] == 100 and not again["capped"]
        # report #46: where tomorrow's rate is heading - today only cat tapped (+250; ann and ben are online
        # but idle): average 250 > 75 % of 300 -> doubles
        lim = again["state"]["limit"]
        assert (lim["today_average"], lim["today_players"], lim["next_cap"]) == (250, 1, 600)
    run(tmp_path, scenario)


def test_undo_restores_points_rule_and_milestone(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await set_step(app, "ann", 500)
        st = (await ann.req("get", "/api/state"))["state"]
        workout = rule_id(st, "Workout")                    # 400, 300, ...
        await ann.req("post", f"/api/rules/{workout}/trigger")
        r = await ann.req("post", f"/api/rules/{workout}/trigger")
        assert r["milestone"] and r["state"]["momentum"] == 700
        events = r["state"]["events"]
        assert events[0]["kind"] == "milestone" and not events[0]["undoable"]
        last = next(e for e in events if e["kind"] == "acc")
        st = (await ann.req("post", f"/api/events/{last['id']}/undo"))["state"]
        assert st["momentum"] == 400 and st["vehicle"]["index"] == 0
        assert [e["kind"] for e in st["events"]] == ["acc"]                         # milestone gone too
        assert next(x for x in st["rules"] if x["id"] == workout)["value"] == 300  # rule back to its 2nd step
        await ann.req("post", f"/api/events/{last['id']}/undo")                     # idempotent
        assert (await ann.req("get", "/api/state"))["state"]["momentum"] == 400
    run(tmp_path, scenario)


def test_offline_sync_is_idempotent(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        body = {"kind": "acc", "name": "Offline", "icon": "star", "mode": "constant", "base": 30, "client_id": "c-123"}
        await ann.req("post", "/api/rules", json=body)
        st = (await ann.req("post", "/api/rules", json=body))["state"]               # retried after a lost response
        offline = [x for x in st["rules"] if x["client_id"] == "c-123"]
        assert len(offline) == 1
        at = (datetime.now(timezone.utc) - timedelta(hours=2)).replace(microsecond=0)
        for _ in range(2):
            r = await ann.req("post", f"/api/rules/{offline[0]['id']}/trigger",
                              json={"at": at.isoformat(), "op_id": "op-1"})
        assert r.get("duplicate") and r["state"]["momentum"] == 30
        assert r["state"]["events"][0]["at"].startswith(at.replace(tzinfo=None).isoformat()[:16])   # offline time kept
    run(tmp_path, scenario)


def test_read_receipts(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        dm = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]
        sent = (await ann.req("post", f"/api/conversations/{dm['id']}/messages", json={"text": "hi"}))["message"]
        r = await ann.req("get", f"/api/conversations/{dm['id']}/messages")
        assert r["read_upto"] < sent["id"]                                          # sent, not read yet
        await ben.req("get", f"/api/conversations/{dm['id']}/messages")             # ben opens the chat
        r = await ann.req("get", f"/api/conversations/{dm['id']}/messages")
        assert r["read_upto"] >= sent["id"]                                         # read
    run(tmp_path, scenario)


def test_without_daily_limit_free_score_runs_and_ranked_pauses(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await set_step(app, "ann", 100)
        async with app.db.session() as s:               # today's limit: 100
            await s.execute(delete(DailyCap).where(DailyCap.day == limits.server_day()))
            s.add(DailyCap(day=limits.server_day(), average=0, bias=100, cap=100, players=0))
            await s.commit()
        st = (await ann.req("get", "/api/state"))["state"]
        task = rule_id(st, "Task done")
        r = await ann.req("post", f"/api/rules/{task}/trigger")            # with limit: both +100
        assert r["state"]["momentum"] == 100 and r["state"]["free_score"] == 100
        await ann.req("post", f"/api/rules/{task}/trigger", status=409)     # limit reached

        st = (await ann.req("put", "/api/settings", json={"daily_limit": False}))["state"]
        assert st["settings"]["daily_limit"] is False and st["momentum"] == 100   # plays with the free score
        for _ in range(3):                                                  # no limit any more
            r = await ann.req("post", f"/api/rules/{task}/trigger")
        st = r["state"]
        assert st["free_score"] == 400 and st["momentum"] == 400 and st["ranked_momentum"] == 100   # ranked paused
        assert r["delta"] == 100 and st["vehicle"]["index"] == 3                 # vehicles follow the free score
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["competing"] is False and prof["momentum"] == 400 and prof["ranked_momentum"] == 100

        # undo reverts the free score only (the ranked value didn't move)
        ev = next(e for e in st["events"] if e["kind"] == "acc")
        st = (await ann.req("post", f"/api/events/{ev['id']}/undo"))["state"]
        assert st["free_score"] == 300 and st["ranked_momentum"] == 100

        # back on: the ranked value continues where it paused, still capped for today
        st = (await ann.req("put", "/api/settings", json={"daily_limit": True}))["state"]
        assert st["momentum"] == 100 and st["free_score"] == 300
        assert (await ben.req("get", "/api/users/ann"))["profile"]["competing"] is True
    run(tmp_path, scenario)


def test_ultra_hyperspace_is_secret_until_sixteen_million(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        async with app.db.session() as s:
            from sqlalchemy import select as sel
            from ..db import User as U
            u = await s.scalar(sel(U).where(U.username == "ann"))
            u.momentum = 4_000_000
            await s.commit()
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["user"]["hyperspace"] is True and st["user"]["ultra"] is False
        await ann.req("put", "/api/settings", json={"banner_space": "ultrahyperspace"}, status=400)
        async with app.db.session() as s:
            from sqlalchemy import select as sel
            from ..db import User as U
            u = await s.scalar(sel(U).where(U.username == "ann"))
            u.momentum = 16_000_000
            await s.commit()
        st = (await ann.req("put", "/api/settings", json={"banner_space": "ultrahyperspace"}))["state"]
        assert st["user"]["ultra"] is True and st["user"]["banner"] == "ultrahyperspace"
        await ann.req("put", "/api/settings", json={"banner": "ultrahyperspace"}, status=400)   # space only
        # exclusive to the competition: without the daily limit neither is unlocked
        async with app.db.session() as s:
            u = await s.scalar(sel(U).where(U.username == "ann"))
            u.free_score = 16_000_000
            await s.commit()
        st = (await ann.req("put", "/api/settings", json={"daily_limit": False}))["state"]
        assert st["user"]["hyperspace"] is False and st["user"]["ultra"] is False and st["user"]["banner"] is None
        await ann.req("put", "/api/settings", json={"banner_space": "hyperspace"}, status=400)
    run(tmp_path, scenario)


def test_hyperspace_unlocks_at_four_million(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("put", "/api/settings", json={"banner_space": "hyperspace"}, status=400)   # locked
        async with app.db.session() as s:
            from sqlalchemy import select as sel
            from ..db import User as U
            u = await s.scalar(sel(U).where(U.username == "ann"))
            u.momentum = 3_999_950
            await s.commit()
        st = (await ann.req("get", "/api/state"))["state"]
        r = await ann.req("post", f"/api/rules/{rule_id(st, 'Task done')}/trigger")
        assert r["hyperspace"] is True and r["state"]["user"]["hyperspace"] is True
        await ann.req("put", "/api/settings", json={"banner": "hyperspace"}, status=400)   # not for the road
        st = (await ann.req("put", "/api/settings", json={"banner_space": "hyperspace"}))["state"]
        assert st["user"]["banner"] == "hyperspace" and st["user"]["banners"]["space"] == "hyperspace"
        # below 4 million: locked again (shown as default, choice kept for later)
        r = await ann.req("post", f"/api/rules/{rule_id(st, 'Doomscrolling')}/trigger")
        assert r["state"]["user"]["hyperspace"] is False and r["state"]["user"]["banner"] is None
    run(tmp_path, scenario)


def test_idle_rule_taps_its_counterpart_automatically(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        st = (await ann.req("post", "/api/rules", json={"kind": "dec", "name": "No prayer", "icon": "moon",
                                                        "mode": "constant", "base": 30}))["state"]
        no_prayer = rule_id(st, "No prayer")
        st = (await ann.req("post", "/api/rules", json={"kind": "acc", "name": "Prayer", "icon": "star", "mode": "constant",
                                                        "base": 50, "idle_days": 2, "idle_rule_id": no_prayer}))["state"]
        prayer = next(r for r in st["rules"] if r["name"] == "Prayer")
        assert prayer["idle_days"] == 2 and prayer["idle_rule_id"] == no_prayer
        r = await ann.req("post", f"/api/rules/{prayer['id']}/trigger")              # +50
        assert r["state"]["momentum"] == 50 and r["state"]["events"][0]["auto_from"] is None
        # a counterpart of the same kind is refused (switches the feature off)
        task = rule_id(st, "Task done")
        st = (await ann.req("put", f"/api/rules/{task}", json={"kind": "acc", "name": "Task done", "icon": "check",
                                                               "mode": "constant", "base": 100, "idle_days": 1,
                                                               "idle_rule_id": prayer["id"]}))["state"]
        assert next(x for x in st["rules"] if x["id"] == task)["idle_days"] == 0

        # 5 days without praying: due after 2 and 4 days -> No prayer tapped twice, automatically
        async with app.db.session() as s:
            from ..db import Rule as R
            rule = await s.get(R, prayer["id"])
            rule.last_triggered_at -= timedelta(days=5)
            rule.idle_since = None
            await s.commit()
        st = (await ann.req("get", "/api/state"))["state"]
        auto = [e for e in st["events"] if e["auto_from"] == "Prayer"]
        assert [e["label"] for e in auto] == ["No prayer"] * 2
        assert sorted(e["delta"] for e in auto) == [-30, -20]                        # 50 -> 20 -> 0
        assert st["momentum"] == 0                                                   # 50 - 30 - 30, never below 0
        st = (await ann.req("get", "/api/state"))["state"]                            # not again on the next request
        assert len([e for e in st["events"] if e["auto_from"] == "Prayer"]) == 2
        # automatic taps can be undone like any other
        st = (await ann.req("post", f"/api/events/{auto[0]['id']}/undo"))["state"]
        assert len([e for e in st["events"] if e["auto_from"] == "Prayer"]) == 1
        # deleting the counterpart switches the feature off
        st = (await ann.req("delete", f"/api/rules/{no_prayer}"))["state"]
        assert next(x for x in st["rules"] if x["id"] == prayer["id"])["idle_rule_id"] is None
    run(tmp_path, scenario)


def test_hours_for_idle_and_reset(tmp_path):
    """Report #18: any number 0..365 with h (hours) or d (days)."""
    async def scenario(app, c):
        ann = await user(c, "ann")
        st = (await ann.req("post", "/api/rules", json={"kind": "dec", "name": "Skip", "icon": "moon",
                                                        "mode": "constant", "base": 10}))["state"]
        skip = rule_id(st, "Skip")
        st = (await ann.req("post", "/api/rules", json={
            "kind": "acc", "name": "Water", "icon": "star", "mode": "linear", "base": 50, "param": 10, "min_value": 10,
            "reset_days": 6, "reset_unit": "h", "idle_days": 5, "idle_unit": "h", "idle_rule_id": skip}))["state"]
        water = next(r for r in st["rules"] if r["name"] == "Water")
        assert (water["reset_days"], water["reset_unit"], water["idle_days"], water["idle_unit"]) == (6, "h", 5, "h")
        r = await ann.req("post", f"/api/rules/{water['id']}/trigger")                # +50, next 40
        assert next(x for x in r["state"]["rules"] if x["id"] == water["id"])["value"] == 40
        # 7 hours later: reset (after 6 h) and Skip tapped once (after 5 h)
        async with app.db.session() as s:
            from ..db import Rule as R
            rule = await s.get(R, water["id"])
            rule.last_triggered_at -= timedelta(hours=7)
            rule.idle_since = None
            await s.commit()
        st = (await ann.req("get", "/api/state"))["state"]
        assert next(x for x in st["rules"] if x["id"] == water["id"])["value"] == 50
        assert [e["label"] for e in st["events"] if e["auto_from"] == "Water"] == ["Skip"]
    run(tmp_path, scenario)



def test_lower_momentum(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await set_step(app, "ann", 100)
        st = (await ann.req("get", "/api/state"))["state"]
        for _ in range(3):                                            # 300 -> vehicle 2 (100, 200, then 400)
            st = (await ann.req("post", f"/api/rules/{rule_id(st, 'Task done')}/trigger"))["state"]
        assert st["momentum"] == 300 and st["vehicle"]["index"] == 2
        assert st["vehicle"]["reached_at"] == 200 and st["vehicle"]["next_at"] == 400
        await ann.req("post", "/api/momentum/lower", json={"value": 400}, status=400)   # never up
        await ann.req("post", "/api/momentum/lower", json={"value": 300}, status=400)
        st = (await ann.req("post", "/api/momentum/lower", json={"value": 150}))["state"]
        assert st["momentum"] == 150 and st["vehicle"]["index"] == 1
        milestones = [e["label"] for e in st["events"] if e["kind"] == "milestone"]
        assert milestones == ["motorroller"]                            # the ones above 150 are gone
        st = (await ann.req("post", "/api/momentum/lower", json={"value": 0}))["state"]
        assert st["momentum"] == 0
    run(tmp_path, scenario)


def test_rate_adapts(tmp_path):
    async def scenario(app, c):
        today = limits.server_day()
        async with app.db.session() as s:
            assert await limits.next_rate(s, today, 50, False) == limits.START_RATE       # no history
            s.add(DailyCap(day=today - timedelta(days=3), average=0, bias=100, cap=100, players=1))
            await s.flush()
            assert await limits.next_rate(s, today, 76, False) == 200      # > 0.75 * 100: doubles
            assert await limits.next_rate(s, today, 75, False) == 100      # in between: stays
            assert await limits.next_rate(s, today, 25, False) == 100
            assert await limits.next_rate(s, today, 24, False) == 50       # < 0.25 * 100: halves
            s.add(DailyCap(day=today - timedelta(days=1), average=0, bias=8000, cap=8000, players=1))
            s.add(DailyCap(day=today - timedelta(days=2), average=0, bias=30, cap=30, players=1))
            await s.flush()
            assert await limits.next_rate(s, today, 7000, False) == 12800              # latest rate, max 12800
            assert await limits.next_rate(s, today - timedelta(days=1), 0, False) == 25   # min 25
            await limits.set_rate(s, 300)                                               # admin override, once
            assert await limits.next_rate(s, today, 0, True) == 300
            assert await limits.next_rate(s, today, 0, True) == 4000
    run(tmp_path, scenario)


def test_rule_order(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        st = (await ann.req("get", "/api/state"))["state"]
        ids = [r["id"] for r in st["rules"]]
        st = (await ann.req("put", "/api/rules/order", json={"ids": [ids[-1], ids[0]]}))["state"]
        assert [r["id"] for r in st["rules"]] == [ids[-1], ids[0]] + ids[1:-1]
        await ann.req("put", "/api/rules/order", json={"ids": ["x"]}, status=400)
    run(tmp_path, scenario)


def test_linked_rules_share_visibility(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        st = (await ann.req("get", "/api/state"))["state"]
        acc = next(r for r in st["rules"] if r["name"] == "Task done")
        dec = next(r for r in st["rules"] if r["name"] == "Doomscrolling")
        body = {k: acc[k] for k in ("kind", "name", "icon", "mode", "base", "param", "min_value", "reset_days")}
        # the accelerator taps the decelerator when idle; made public, the decelerator goes public too
        st = (await ann.req("put", f"/api/rules/{acc['id']}", json={**body, "public": True, "idle_days": 2,
                                                                    "idle_rule_id": dec["id"]}))["state"]
        assert next(r for r in st["rules"] if r["id"] == dec["id"])["public"] is True
        # the decelerator made private: the public accelerator depending on it follows
        dbody = {k: dec[k] for k in ("kind", "name", "icon", "mode", "base", "param", "min_value", "reset_days")}
        st = (await ann.req("put", f"/api/rules/{dec['id']}", json={**dbody, "public": False}))["state"]
        assert next(r for r in st["rules"] if r["id"] == acc["id"])["public"] is False
    run(tmp_path, scenario)


def test_report_bonus_is_one_percent_of_the_limit(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        today = limits.server_day()
        async with app.db.session() as s:
            await s.execute(delete(DailyCap).where(DailyCap.day == today))
            s.add(DailyCap(day=today, average=0, bias=1050, cap=1050, players=0))
            await s.commit()
        report = {"kind": "bug", "text": "Etwas klemmt"}
        assert (await ann.req("post", "/api/reports", json=report))["bonus"] == 11       # 10.5 rounded up
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["momentum"] == 11 and st["events"][0]["kind"] == "report" and not st["events"][0]["undoable"]
        assert st["limit"]["gained"] == 11 and st["limit"]["today_players"] == 0       # counts, but no active tap
        assert (await ann.req("get", "/api/users/ann"))["profile"]["history"] == []      # not in the profile
        async with app.db.session() as s:                    # +1030 tapped: only 9 left under the limit
            s.add(Event(user_id=1, kind="acc", label="x", delta=1030, momentum_after=1041))
            await s.commit()
        assert (await ann.req("post", "/api/reports", json=report))["bonus"] == 9
        assert (await ann.req("post", "/api/reports", json=report))["bonus"] == 0        # limit reached: nothing
        assert (await ann.req("get", "/api/state"))["state"]["momentum"] == 20
    run(tmp_path, scenario)


def test_used_up_limit_still_takes_a_zero_tap_against_the_automatic_decelerator(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        st = (await ann.req("post", "/api/rules", json={"kind": "dec", "name": "No prayer", "icon": "moon",
                                                        "mode": "constant", "base": 30}))["state"]
        st = (await ann.req("post", "/api/rules", json={"kind": "acc", "name": "Prayer", "icon": "star", "mode": "constant",
                                                        "base": 50, "idle_days": 2, "idle_rule_id": rule_id(st, "No prayer")}))["state"]
        prayer, task = rule_id(st, "Prayer"), rule_id(st, "Task done")
        async with app.db.session() as s:                    # today's limit: 100, used up
            await s.execute(delete(DailyCap).where(DailyCap.day == limits.server_day()))
            s.add(DailyCap(day=limits.server_day(), average=0, bias=100, cap=100, players=0))
            await s.commit()
        await ann.req("post", f"/api/rules/{task}/trigger")
        await ann.req("post", f"/api/rules/{task}/trigger", status=409)          # an ordinary rule: refused
        r = await ann.req("post", f"/api/rules/{prayer}/trigger")               # it keeps the automatic -30 away: +0
        assert r["capped"] and r["delta"] == 0 and r["state"]["momentum"] == 100
        assert r["state"]["events"][0]["kind"] == "acc" and r["state"]["events"][0]["delta"] == 0
        async with app.db.session() as s:
            from ..db import Rule
            assert (await s.get(Rule, prayer)).last_triggered_at is not None    # the idle time starts over
        # paused automatic: nothing to keep away, refused like any other
        await ann.req("put", f"/api/rules/{prayer}/idle-pause", json={"paused": True})
        await ann.req("post", f"/api/rules/{prayer}/trigger", status=409)
    run(tmp_path, scenario)
