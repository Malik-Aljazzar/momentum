import asyncio
from datetime import timedelta

from sqlalchemy import select

from server.app import create_app
from server.blobs import MemoryStore
from server.db import DailyDecay


def run(coro):
    return asyncio.run(coro)


async def set_step(app, username, step):
    """Momentum per milestone is a server-side per-player setting."""
    from server.admin import set_milestone_step
    async with app.db.session() as s:
        await set_milestone_step(s, username, step)
        await s.commit()


def make_app(tmp_path):
    return create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore())


async def register(client, name="alice"):
    """Sign up with e-mail, then pick the username (the app's onboarding)."""
    r = await client.post("/api/auth/register", json={"email": f"{name}@example.com", "password": "geheim1", "language": "de"})
    assert r.status_code == 200, await r.get_json()
    h = {"Authorization": "Bearer " + (await r.get_json())["token"]}
    r = await client.put("/api/settings", headers=h, json={"username": name})
    assert r.status_code == 200, await r.get_json()
    return h


def test_register_trigger_and_milestone(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
            assert st["momentum"] == 0
            acc = next(r for r in st["rules"] if r["name"] == "Sport")
            assert acc["value"] == 400

            await set_step(app, "alice", 700)
            r = await (await c.post(f"/api/rules/{acc['id']}/trigger", headers=h)).get_json()
            assert r["delta"] == 400 and not r["milestone"]
            r = await (await c.post(f"/api/rules/{acc['id']}/trigger", headers=h)).get_json()
            assert r["delta"] == 300 and r["milestone"]
            st = r["state"]
            assert st["momentum"] == 700 and st["vehicle"]["index"] == 1
            assert next(x for x in st["rules"] if x["id"] == acc["id"])["value"] == 200

            dec = next(x for x in st["rules"] if x["kind"] == "dec")
            r = await (await c.post(f"/api/rules/{dec['id']}/trigger", headers=h)).get_json()
            assert r["delta"] == -dec["value"]
            assert r["state"]["decay"]["today_gain"] == 700
    run(go())


def test_auth_required_and_wrong_password(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            assert (await c.get("/api/state")).status_code == 401
            await register(c)
            r = await c.post("/api/auth/login", json={"username": "alice", "password": "x"})
            assert r.status_code == 401
            r = await c.post("/api/auth/login", json={"username": "ALICE", "password": "geheim1"})
            assert r.status_code == 200
    run(go())


def test_language_of_errors_and_default_rules(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            r = await c.post("/api/auth/register", json={"email": "bob@example.com", "password": "secret1",
                                                         "language": "en"})
            h = {"Authorization": "Bearer " + (await r.get_json())["token"]}
            await c.put("/api/settings", headers=h, json={"username": "bob"})
            st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
            assert "Workout" in [x["name"] for x in st["rules"]]
            bad = {"username": "bob", "password": "nope"}
            r = await c.post("/api/auth/login", json=bad, headers={"Accept-Language": "en-US,en"})
            assert (await r.get_json())["error"] == "Wrong login or password"
            r = await c.post("/api/auth/login", json=bad, headers={"Accept-Language": "de-DE,de"})
            assert (await r.get_json())["error"] == "Login oder Passwort falsch"
    run(go())


def test_rule_validation_and_crud(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            bad = {"kind": "acc", "name": "X", "icon": "star", "mode": "exponential",
                   "base": 10, "param": 1.5}
            assert (await c.post("/api/rules", headers=h, json=bad)).status_code == 400
            ok = dict(bad, param=0.5, min_value=1, reset_days=3)
            st = (await (await c.post("/api/rules", headers=h, json=ok)).get_json())["state"]
            new = next(r for r in st["rules"] if r["name"] == "X")
            assert new["param"] == 0.5 and new["reset_days"] == 3
            st = (await (await c.delete(f"/api/rules/{new['id']}", headers=h)).get_json())["state"]
            assert all(r["name"] != "X" for r in st["rules"])
    run(go())


def test_daily_decay_catch_up(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
            acc = next(r for r in st["rules"] if r["name"] == "Aufgabe erledigt")
            for _ in range(10):
                await c.post(f"/api/rules/{acc['id']}/trigger", headers=h)
            # pretend the last decay run was 3 days ago -> 2 finished days to process
            async with app.db.session() as s:
                dd = await s.scalar(select(DailyDecay))
                dd.last_processed_day -= timedelta(days=2)
                await s.commit()
            st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
            daily = [e for e in st["events"] if e["kind"] == "daily"]
            # defaults: base 50, threshold 200, linear catalyst 25/day of streak
            assert [e["delta"] for e in daily] == [-100, -75]
            assert st["momentum"] == 1000 - 175
            assert st["decay"]["streak"] == 2
    run(go())


def test_exponential_rule_applies_whole_numbers(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            st = (await (await c.get("/api/state", headers=h)).get_json())["state"]
            read = next(r for r in st["rules"] if r["name"] == "Lesen")        # 300, x0.7 per click
            deltas = []
            for _ in range(4):
                r = await (await c.post(f"/api/rules/{read['id']}/trigger", headers=h)).get_json()
                deltas.append(r["delta"])
            assert deltas == [300, 210, 147, 103]          # 102.9 rounded up for accelerators
            assert r["state"]["momentum"] == 760
            assert all(x["value"] == int(x["value"]) for x in r["state"]["rules"])
    run(go())


def test_outdated_app_is_asked_to_update(tmp_path, monkeypatch):
    from server import app as app_module
    monkeypatch.setattr(app_module, "latest_app", lambda: dict(version_code=500, version_name="0.5"))

    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            meta = await (await c.get("/api/meta", headers={"X-App-Version": "1"})).get_json()   # meta stays open
            assert meta["app"] == {"version_code": 500, "version_name": "0.5", "update_url": "/"}
            r = await c.post("/api/auth/login", json={"login": "x", "password": "y"}, headers={"X-App-Version": "499"})
            assert r.status_code == 426 and (await r.get_json())["update_required"] is True
            r = await c.post("/api/auth/login", json={"login": "x", "password": "y"}, headers={"X-App-Version": "500"})
            assert r.status_code != 426                    # current app passes (login itself fails: 4xx)
            assert (await c.get("/")).status_code == 200   # welcome page with the download
            # privacy policy, terms and account deletion (Play Store listing), the operator from the environment
            monkeypatch.setenv("MOMENTUM_OPERATOR", "Ann <Admin>\\nStreet 1")
            page = await (await c.get("/legal")).get_data(as_text=True)
            assert "Ann &lt;Admin&gt;\nStreet 1" in page and "Konto löschen" in page
            for path, anchor in (("/privacy", "privacy"), ("/terms", "terms"), ("/delete-account", "delete")):
                assert (await c.get(path)).headers["Location"] == f"/legal#{anchor}"
            # the background's read-only calls (notifications, widgets) aren't blocked
            r = await c.get("/api/updates", headers={"X-App-Version": "499"})
            assert r.status_code != 426
            r = await c.post("/api/rules", json={}, headers={"X-App-Version": "499"})
            assert r.status_code == 426
            # a Play Store build gets its updates from Play: the APK on offer here doesn't block it
            r = await c.post("/api/auth/login", json={"login": "x", "password": "y"},
                             headers={"X-App-Version": "499", "X-App-Channel": "play"})
            assert r.status_code != 426
    asyncio.run(go())
