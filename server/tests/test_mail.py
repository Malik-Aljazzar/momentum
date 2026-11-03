"""E-mails with codes (server/mail.py): confirming the address is required (accounts from before get time), a new
password by code, all off without the MAIL_* config."""
import asyncio
import re
from datetime import timedelta

from sqlalchemy import select, update

from server.app import create_app
from server.blobs import MemoryStore
from server.clients import CONFIRM_APP
from server.db import User, utcnow
from server.mail import GRACE_DAYS, MAX_TRIES, EmailCode, mask
from .test_social import Client


class FakeMailer:
    """Keeps what would go out; the code is in the subject."""
    enabled = True

    def __init__(self):
        self.sent = []

    async def send(self, to, subject, html):
        self.sent.append((to, subject, html))

    def code_for(self, to):
        return re.search(r"\d{6}", next(s for t, s, _ in reversed(self.sent) if t == to)).group()


def make(tmp_path, mail=None):
    return create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(), mail=mail)


async def signup(c, name, lang="de"):
    r = await c.post("/api/auth/register", json={"email": f"{name}@example.com", "password": "secret1", "language": lang})
    assert r.status_code == 200, await r.get_json()
    return Client(c, name, {"Authorization": "Bearer " + (await r.get_json())["token"]})


async def back_in_time(app, name, **values):
    async with app.db.session() as s:
        await s.execute(update(User).where(User.email == f"{name}@example.com").values(**values))
        await s.commit()


def test_without_mail_config_nothing_changes(tmp_path):
    async def go():
        app = make(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            ann = await signup(c, "ann")
            assert (await ann.req("get", "/api/state"))["state"]["user"]["email_confirm"] is None
            await ann.req("get", "/api/feed")
            assert (await (await c.get("/api/meta")).get_json())["mail"] is False
            await c.post("/api/auth/password/forgot", json={"login": "ann@example.com"})
            assert (await c.post("/api/auth/password/forgot", json={"login": "ann@example.com"})).status_code == 404
    asyncio.run(go())


def test_a_new_account_confirms_its_address_before_anything_else(tmp_path):
    async def go():
        mailer = FakeMailer()
        app = make(tmp_path, mailer)
        async with app.test_app() as ta:
            c = ta.test_client()
            assert (await (await c.get("/api/meta")).get_json())["mail"] is True
            ann = await signup(c, "ann")
            to, subject, html = mailer.sent[-1]
            assert to == "ann@example.com" and subject.startswith("Dein Momentum-Code: ") and "Bestätige" in html
            st = (await ann.req("get", "/api/state"))["state"]["user"]
            assert st["email_confirm"]["required"] is True
            # locked: only the confirm screen's calls
            body = await ann.req("get", "/api/feed", status=403)
            assert body["confirm_email"] is True and "confirm" in body["error"]
            await ann.req("put", "/api/settings", json={"username": "ann"}, status=403)
            # a second code only after a minute
            assert "wait" in (await ann.req("post", "/api/auth/email/send", status=429))["error"]
            # wrong codes count; the right one opens the app
            code = mailer.code_for("ann@example.com")
            wrong = "000000" if code != "000000" else "111111"
            assert (await ann.req("post", "/api/auth/email/confirm", json={"code": wrong}, status=400))["error"] == "That code is wrong"
            st = (await ann.req("post", "/api/auth/email/confirm", json={"code": code[:3] + " " + code[3:]}))["state"]
            assert st["user"]["email_confirm"] is None
            await ann.req("put", "/api/settings", json={"username": "ann"})
            await ann.req("get", "/api/feed")
            # a used code doesn't work twice, and nothing is left to confirm
            await ann.req("post", "/api/auth/email/send")
            assert len([m for m in mailer.sent if m[0] == "ann@example.com"]) == 1
    asyncio.run(go())


def test_wrong_tries_spend_a_code_and_another_address_gets_its_own(tmp_path):
    async def go():
        mailer = FakeMailer()
        app = make(tmp_path, mailer)
        async with app.test_app() as ta:
            c = ta.test_client()
            ben = await signup(c, "ben")
            code = mailer.code_for("ben@example.com")
            wrong = "000000" if code != "000000" else "111111"
            for _ in range(MAX_TRIES):
                await ben.req("post", "/api/auth/email/confirm", json={"code": wrong}, status=400)
            assert "expired" in (await ben.req("post", "/api/auth/email/confirm", json={"code": code}, status=400))["error"]
            # typed the address wrong: a new one gets a new code at once (the old one is gone), taken addresses aren't
            await signup(c, "cat")
            await ben.req("post", "/api/auth/email/send", json={"email": "cat@example.com"}, status=400)
            r = await ben.req("post", "/api/auth/email/send", json={"email": "Ben.New@Example.com"})
            assert r["sent_to"] == "ben.new@example.com" and r["resend_in"] == 60
            assert r["state"]["user"]["email"] == "ben.new@example.com"
            await ben.req("post", "/api/auth/email/confirm", json={"code": code}, status=400)
            await ben.req("post", "/api/auth/email/confirm", json={"code": mailer.code_for("ben.new@example.com")})
            await ben.req("get", "/api/feed")
            # the account goes, its codes too
            await ben.req("delete", "/api/account")
            async with app.db.session() as s:
                assert not list(await s.scalars(select(EmailCode).where(EmailCode.email == "ben.new@example.com")))
    asyncio.run(go())


def test_accounts_from_before_get_time_and_older_android_apps_are_not_locked_out(tmp_path):
    async def go():
        app = make(tmp_path)                                         # mails still off
        async with app.test_app() as ta:
            ann = await signup(ta.test_client(), "ann")
        mailer = FakeMailer()
        app = make(tmp_path, mailer)                                 # on now: ann has GRACE_DAYS
        async with app.test_app() as ta:
            c = ta.test_client()
            ann = Client(c, "ann", ann.h)
            confirm = (await ann.req("get", "/api/state"))["state"]["user"]["email_confirm"]
            assert confirm["required"] is False and confirm["due"] > utcnow().isoformat()
            await ann.req("get", "/api/feed")
            # the time is up: locked - not for an Android app from before the confirm screen
            await back_in_time(app, "ann", email_due_at=utcnow() - timedelta(minutes=1))
            await ann.req("get", "/api/feed", status=403)
            old = {**ann.h, "X-App-Version": str(CONFIRM_APP - 1)}
            assert (await c.get("/api/feed", headers=old)).status_code == 200
            assert (await c.get("/api/feed", headers={**ann.h, "X-App-Version": str(CONFIRM_APP)})).status_code == 403
        async with make(tmp_path, mailer).test_app() as ta:        # the deadline is set only once
            confirm = (await Client(ta.test_client(), "ann", ann.h).req("get", "/api/state"))["state"]["user"]["email_confirm"]
            assert confirm["required"] is True
        assert GRACE_DAYS == 14
    asyncio.run(go())


def test_a_new_password_by_code_to_the_confirmed_address(tmp_path):
    async def go():
        mailer = FakeMailer()
        app = make(tmp_path, mailer)
        async with app.test_app() as ta:
            c = ta.test_client()
            ann = await signup(c, "ann", lang="en")
            await ann.req("put", "/api/settings", json={"username": "ann"}, status=403)
            # not confirmed yet: the same answer, but nothing is sent
            before = len(mailer.sent)
            assert (await (await c.post("/api/auth/password/forgot", json={"login": "ann@example.com"})).get_json()) == {"ok": True}
            assert len(mailer.sent) == before
            await ann.req("post", "/api/auth/email/confirm", json={"code": mailer.code_for("ann@example.com")})
            await ann.req("put", "/api/settings", json={"username": "ann"})
            # unknown accounts: the same answer
            assert (await c.post("/api/auth/password/forgot", json={"login": "nobody"})).status_code == 200
            # by username; the mail is in the app's language
            await c.post("/api/auth/password/forgot", json={"login": "ann"}, headers={"Accept-Language": "en"})
            to, subject, html = mailer.sent[-1]
            assert to == "ann@example.com" and subject.startswith("Your Momentum code") and "new password" in html
            code = mailer.code_for("ann@example.com")
            wrong = "000000" if code != "000000" else "111111"
            r = await c.post("/api/auth/password/reset", json={"login": "ann", "code": wrong, "password": "brandnew"})
            assert r.status_code == 400
            r = await c.post("/api/auth/password/reset", json={"login": "ann", "code": code, "password": "abc"})
            assert r.status_code == 400                                 # too short, the code still works
            r = await c.post("/api/auth/password/reset", json={"login": "ANN@example.com", "code": code, "password": "brandnew"})
            assert r.status_code == 200
            fresh = {"Authorization": "Bearer " + (await r.get_json())["token"]}
            assert (await c.get("/api/state", headers=fresh)).status_code == 200
            assert (await c.get("/api/state", headers=ann.h)).status_code == 401          # other sessions end
            r = await c.post("/api/auth/login", json={"login": "ann", "password": "brandnew"})
            assert r.status_code == 200
            # spent
            r = await c.post("/api/auth/password/reset", json={"login": "ann", "code": code, "password": "another1"})
            assert r.status_code == 400
    asyncio.run(go())


def test_mask_hides_the_address():
    assert mask("ann@example.com") == "a***@example.com"
    assert mask("") == ""


def test_five_wrong_passwords_lock_the_account_and_a_login_mails_a_reset_code(tmp_path):
    async def go():
        mailer = FakeMailer()
        app = make(tmp_path, mailer)
        async with app.test_app() as ta:
            c = ta.test_client()
            ann = await signup(c, "ann")
            await ann.req("post", "/api/auth/email/confirm", json={"code": mailer.code_for("ann@example.com")})
            await ann.req("put", "/api/settings", json={"username": "ann"})

            async def login(password, ip):
                r = await c.post("/api/auth/login", json={"login": "ann", "password": password},
                                 headers={"Accept-Language": "en", "X-Real-IP": ip})
                return r.status_code, await r.get_json()
            # a right password in between starts the count again
            for i in range(4):
                await login("wrong", f"10.0.0.{i}")
            assert (await login("secret1", "10.0.1.1"))[0] == 200
            for i in range(3):                                   # from one IP: security.py's lockout holds it too
                status, body = await login("wrong", "10.0.3.2")
            assert status == 401 and "2 more tries" in body["error"]               # warned before it locks
            assert "1 more try" in (await login("wrong", "10.0.3.2"))[1]["error"]
            sent = len(mailer.sent)
            status, body = await login("wrong", "10.0.3.2")                         # the fifth: locked, code mailed
            assert status == 423 and body["locked"] is True and "locked" in body["error"]
            assert len(mailer.sent) == sent + 1 and "new password" in mailer.sent[-1][2]
            # even the right password doesn't open it; a login mails the code again (not within the minute)
            assert (await login("secret1", "10.0.4.1"))[0] == 423
            assert len(mailer.sent) == sent + 1
            async with app.db.session() as s:
                await s.execute(update(EmailCode).values(created_at=utcnow() - timedelta(minutes=2)))
                await s.commit()
            assert (await login("secret1", "10.0.4.2"))[0] == 423
            assert len(mailer.sent) == sent + 2
            # the session that was open stays (locking is about logging in)
            await ann.req("get", "/api/state")
            # the new password opens it - also from the IP that the lockout of security.py holds
            r = await c.post("/api/auth/password/reset", headers={"X-Real-IP": "10.0.3.2"},
                             json={"login": "ann", "code": mailer.code_for("ann@example.com"), "password": "brandnew"})
            assert r.status_code == 200
            assert (await login("brandnew", "10.0.3.2"))[0] == 200
            async with app.db.session() as s:
                u = await s.scalar(select(User).where(User.username == "ann"))
                assert (u.failed_logins, u.locked_at) == (0, None)
    asyncio.run(go())


def test_without_a_confirmed_address_only_the_time_lockout_applies(tmp_path):
    async def go():
        mailer = FakeMailer()
        app = make(tmp_path, mailer)
        async with app.test_app() as ta:
            c = ta.test_client()
            await signup(c, "ben")                                                  # not confirmed
            for i in range(6):
                r = await c.post("/api/auth/login", json={"login": "ben@example.com", "password": "wrong"},
                                 headers={"X-Real-IP": f"10.9.0.{i}"})
                assert r.status_code == 401
            async with app.db.session() as s:
                assert (await s.scalar(select(User).where(User.email == "ben@example.com"))).locked_at is None
    asyncio.run(go())
