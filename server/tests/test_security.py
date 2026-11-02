"""Login lockout, separate admin sessions with a second factor, session expiry, protected report screenshots."""
from datetime import timedelta

from sqlalchemy import select

from ..db import AuthToken, Media, Report, User, utcnow
from ..security import totp_code, totp_step
from .test_admin import make_admin
from .test_social import png, run, user


async def login(c, name, password, ip="10.0.0.1", path="/api/auth/login", **extra):
    r = await c.post(path, json={"login": name, "password": password, **extra},
                     headers={"X-Real-IP": ip, "Accept-Language": "en"})
    return r.status_code, await r.get_json()


def test_failed_logins_lock_the_account_for_a_while(tmp_path):
    async def scenario(app, c):
        await user(c, "ann")
        for _ in range(5):
            assert (await login(c, "ann", "wrong"))[0] == 401
        status, body = await login(c, "ann", "secret1")                 # right password, still locked
        assert status == 429 and "15 min" in body["error"]
        assert (await login(c, "ann", "secret1", ip="10.0.0.2"))[0] == 200   # the owner elsewhere isn't locked out
        # one IP trying many accounts
        for i in range(20):
            await login(c, f"nobody{i}", "x", ip="10.0.0.9")
        assert (await login(c, "ann", "secret1", ip="10.0.0.9"))[0] == 429
        # the lock ends
        from ..security import LoginFailure
        async with app.db.session() as s:
            for row in await s.scalars(select(LoginFailure)):
                row.locked_until = utcnow() - timedelta(seconds=1)
            await s.commit()
        assert (await login(c, "ann", "secret1"))[0] == 200
    run(tmp_path, scenario)


def test_admin_sessions_need_a_code_and_stay_apart_from_game_sessions(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        boss = await make_admin(app, c, ann)
        async with app.db.session() as s:
            u = await s.scalar(select(User).where(User.username == "ann"))
            secret, last_step = u.totp_secret, u.totp_last_step
        # wrong / missing / reused codes, or no admin, all fail the same way
        assert (await login(c, "ann", "secret1", path="/api/admin/login", code="000000"))[0] == 401
        assert (await login(c, "ann", "secret1", path="/api/admin/login"))[0] == 401
        used = totp_code(secret, last_step)            # make_admin just logged in with it
        assert (await login(c, "ann", "secret1", path="/api/admin/login", code=used))[0] == 401
        assert (await login(c, "ben", "secret1", path="/api/admin/login", code=used))[0] == 401

        # an admin session is no game session, a game session no admin session
        await boss.req("get", "/api/state", status=401)
        await ann.req("get", "/api/admin/me", status=401)
        await ben.req("get", "/api/admin/me", status=401)
        assert (await boss.req("get", "/api/admin/me"))["username"] == "ann"

        # 30 days, then it ends
        async with app.db.session() as s:
            row = await s.get(AuthToken, boss.h["Authorization"].split()[1])
            assert row.scope == "admin" and row.expires_at > utcnow() + timedelta(days=29)
            row.expires_at = utcnow() - timedelta(seconds=1)
            await s.commit()
        await boss.req("get", "/api/admin/me", status=401)

        # admin taken away: the session stops working at once
        async with app.db.session() as s:                                 # as if the next 30 s step had come
            u = await s.scalar(select(User).where(User.username == "ann"))
            u.totp_last_step = 0
            await s.commit()
        status, body = await login(c, "ann", "secret1", path="/api/admin/login", code=totp_code(secret, totp_step()))
        assert status == 200
        fresh = {"Authorization": "Bearer " + body["token"]}
        assert (await c.get("/api/admin/me", headers=fresh)).status_code == 200
        async with app.db.session() as s:
            (await s.scalar(select(User).where(User.username == "ann"))).admin = False
            await s.commit()
        assert (await c.get("/api/admin/me", headers=fresh)).status_code == 401
        await (await c.post("/api/admin/logout", headers=fresh)).get_data()
    run(tmp_path, scenario)


def test_game_sessions_end_after_half_a_year_unused(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("get", "/api/state")
        token = ann.h["Authorization"].split()[1]
        async with app.db.session() as s:
            row = await s.get(AuthToken, token)
            assert row.last_used_at is not None
            row.last_used_at = utcnow() - timedelta(days=179)
            await s.commit()
        await ann.req("get", "/api/state")                                # still fine, and counted as used
        async with app.db.session() as s:
            row = await s.get(AuthToken, token)
            assert row.last_used_at > utcnow() - timedelta(minutes=1)
            row.last_used_at = utcnow() - timedelta(days=181)
            await s.commit()
        await ann.req("get", "/api/state", status=401)
        async with app.db.session() as s:
            assert await s.get(AuthToken, token) is None
    run(tmp_path, scenario)


def test_older_report_screenshots_leave_the_public_path(tmp_path):
    async def scenario(app, c):
        from ..posts import protect_old_reports
        ann = await user(c, "ann")
        media = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        key = media["url"].lstrip("/")
        async with app.db.session() as s:                                 # filed before the protection
            (await s.get(Media, media["id"])).attached = True
            s.add(Report(user_id=1, username="ann", kind="bug", text="alt", media_keys=key, status="new"))
            await s.commit()
        await ann.req("get", media["url"])
        async with app.db.session() as s:
            await protect_old_reports(s, app.blobs)
        await ann.req("get", media["url"], status=404)
        async with app.db.session() as s:
            r = await s.scalar(select(Report))
            assert r.media_keys == key.replace("media/", "reports/", 1)
            assert (await s.get(Media, media["id"])).key == r.media_keys
        assert r.media_keys in app.blobs.objects
    run(tmp_path, scenario)
