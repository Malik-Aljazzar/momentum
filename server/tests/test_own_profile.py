"""The own profile as the main page (2026-11-18): the whole history page by page (GET /api/events), a profile's
vehicles to swipe through (milestones, a background per vehicle type), fonts / sprites for extension pages (CORS)."""
from sqlalchemy import select

from .. import app as app_module
from ..db import User
from .test_social import run, user


def rule_id(st, name):
    return next(r["id"] for r in st["rules"] if r["name"] == name)


def test_the_whole_history_page_by_page(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        st = (await ann.req("get", "/api/state"))["state"]
        task = rule_id(st, "Task done")
        for _ in range(35):
            await ann.req("post", f"/api/rules/{task}/trigger")
        st = (await ann.req("get", "/api/state"))["state"]
        assert len(st["events"]) == 30
        first = (await ann.req("get", "/api/events?limit=30"))["events"]
        assert [e["id"] for e in first] == [e["id"] for e in st["events"]]          # the same as the state's
        older = (await ann.req("get", f"/api/events?before={first[-1]['id']}&limit=30"))["events"]
        # 35 taps + the two milestones on the way (1,000 and 2,000, among the newest 30) = 37: the first 7 taps
        assert len(older) == 7 and older[0]["id"] < first[-1]["id"]
        assert sum(e["kind"] == "milestone" for e in first) == 2
        taps = [e for e in older if e["kind"] == "acc"]
        assert len(taps) == 7 and all(e["undoable"] for e in taps)
        assert (await ann.req("get", f"/api/events?before={older[-1]['id']}"))["events"] == []
        # undone taps leave the history
        await ann.req("post", f"/api/events/{taps[0]['id']}/undo")
        assert len((await ann.req("get", f"/api/events?before={first[-1]['id']}&limit=30"))["events"]) == 6
        # someone else's event isn't a cursor
        await ben.req("get", f"/api/events?before={first[0]['id']}", status=404)
    run(tmp_path, scenario)


def test_a_profile_carries_every_vehicles_threshold_and_background(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        await ann.req("put", "/api/settings", json={"banner_air": "skyisland", "banner_space": "nebula"})
        prof = (await ben.req("get", "/api/users/ann"))["profile"]
        assert prof["milestones"][:4] == [0, 1000, 2000, 4000] and len(prof["milestones"]) == 13
        assert prof["banners"] == {"land": None, "air": "skyisland", "space": "nebula"}
        # hyperspace stays secret while it's locked (chosen once, fallen below it since)
        async with app.db.session() as s:
            (await s.scalar(select(User).where(User.username == "ann"))).banner_space = "hyperspace"
            await s.commit()
        assert (await ben.req("get", "/api/users/ann"))["profile"]["banners"]["space"] is None
    run(tmp_path, scenario)


def test_extension_pages_may_load_the_fonts_and_sprites(tmp_path, monkeypatch):
    web = tmp_path / "dist"
    (web / "fonts").mkdir(parents=True)
    (web / "index.html").write_text("<!doctype html>")
    (web / "fonts" / "pixelify_regular.ttf").write_bytes(b"font")
    monkeypatch.setattr(app_module, "WEB_DIR", web)

    async def scenario(app, c):
        r = await c.get("/app/fonts/pixelify_regular.ttf")
        assert r.status_code == 200 and r.headers["Access-Control-Allow-Origin"] == "*"
        assert "Access-Control-Allow-Origin" not in (await c.get("/app/index.html")).headers
    run(tmp_path, scenario)
