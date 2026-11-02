"""Sign in with Google: token checks, own identity (never the e-mail), Android token + PWA web flow."""
import asyncio
import base64
import json
import time
import urllib.parse

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from ..app import create_app
from ..blobs import MemoryStore
from ..google import CERTS_URL, TOKEN_URL, Google, GoogleError

CLIENT = "web-client.apps.googleusercontent.com"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def jwks():
    nums = KEY.public_key().public_numbers()
    as_b64 = lambda i: b64(i.to_bytes((i.bit_length() + 7) // 8, "big"))
    return {"keys": [{"kid": "k1", "kty": "RSA", "alg": "RS256", "n": as_b64(nums.n), "e": as_b64(nums.e)}]}


def id_token(sub="1001", email="ann@gmail.com", key=KEY, **over):
    claims = dict(iss="https://accounts.google.com", aud=CLIENT, sub=sub, email=email, email_verified=True,
                  iat=int(time.time()), exp=int(time.time()) + 3600)
    claims.update(over)
    head = b64(json.dumps({"alg": "RS256", "kid": "k1", "typ": "JWT"}).encode())
    body = b64(json.dumps(claims).encode())
    sig = key.sign(f"{head}.{body}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{head}.{body}.{b64(sig)}"


class FakeGoogle:
    """Stands in for Google's servers: the certs and the code exchange."""
    def __init__(self):
        self.codes = {}

    def __call__(self, url, data):
        if url == CERTS_URL:
            return jwks()
        assert url == TOKEN_URL and data["client_secret"] == "secret"
        return {"id_token": self.codes.pop(data["code"])}


def make(tmp_path, fake=None):
    google = Google(CLIENT, "secret", fetch=fake or FakeGoogle())
    return create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(), google=google), google


def run(tmp_path, scenario, fake=None):
    async def go():
        app, google = make(tmp_path, fake)
        async with app.test_app() as ta:
            await asyncio.wait_for(scenario(app, ta.test_client(), google), 20)
    asyncio.run(go())


def test_tokens_are_checked():
    g = Google(CLIENT, "", fetch=FakeGoogle())
    assert asyncio.run(g.verify(id_token()))["sub"] == "1001"
    other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    for bad in (id_token(key=other), id_token(aud="someone-else"), id_token(iss="evil.example"),
                id_token(exp=int(time.time()) - 3600), "not.a.token", ""):
        try:
            asyncio.run(g.verify(bad))
            raise AssertionError(f"accepted {bad[:20]}")
        except GoogleError:
            pass
    try:
        asyncio.run(g.verify(id_token(nonce="a"), nonce="b"))
        raise AssertionError("wrong nonce accepted")
    except GoogleError:
        pass


def test_google_account_is_separate_from_a_password_account_with_the_same_email(tmp_path):
    async def scenario(app, c, google):
        meta = await (await c.get("/api/meta")).get_json()
        assert meta["google_client_id"] == CLIENT and meta["google_web"] is True
        # a password account with the very address the Google account has
        r = await c.post("/api/auth/register", json={"email": "ann@gmail.com", "password": "secret1"})
        pw_token = (await r.get_json())["token"]
        r = await c.post("/api/auth/google", json={"id_token": id_token(), "language": "de"})
        assert r.status_code == 200
        g_token = (await r.get_json())["token"]
        pw = (await (await c.get("/api/state", headers={"Authorization": f"Bearer {pw_token}"})).get_json())["state"]["user"]
        gu = (await (await c.get("/api/state", headers={"Authorization": f"Bearer {g_token}"})).get_json())["state"]["user"]
        assert pw["id"] != gu["id"]
        assert pw["email"] == "ann@gmail.com" and pw["has_password"] is True and pw["google_email"] is None
        assert gu["email"] is None and gu["has_password"] is False and gu["google_email"] == "ann@gmail.com"
        assert gu["needs_username"] is True
        # the address logs into the password account only; the Google account has no password at all
        r = await c.post("/api/auth/login", json={"login": "ann@gmail.com", "password": "secret1"})
        assert (await r.get_json())["token"] and r.status_code == 200
        r = await c.post("/api/auth/login", json={"login": "ann@gmail.com", "password": ""})
        assert r.status_code == 401
        # signing up with an address a Google account uses is fine (no hint that it exists)
        r = await c.post("/api/auth/register", json={"email": "ben@gmail.com", "password": "secret1"})
        assert r.status_code == 200
        r = await c.post("/api/auth/google", json={"id_token": id_token(sub="2002", email="ben@gmail.com")})
        assert r.status_code == 200
        # the same Google account again = the same player (even with a new address)
        r = await c.post("/api/auth/google", json={"id_token": id_token(email="ann.new@gmail.com")})
        again = (await (await c.get("/api/state", headers={"Authorization": f"Bearer {(await r.get_json())['token']}"})).get_json())
        assert again["state"]["user"]["id"] == gu["id"] and again["state"]["user"]["google_email"] == "ann.new@gmail.com"
        # Google-only accounts can't log in with their username + any password
        h = {"Authorization": f"Bearer {g_token}"}
        await c.put("/api/settings", headers=h, json={"username": "anng"})
        r = await c.post("/api/auth/login", json={"login": "anng", "password": ""})
        assert r.status_code == 401
        r = await c.post("/api/auth/google", json={"id_token": id_token(aud="other-app")})
        assert r.status_code == 401
        # deleting the account frees the Google identity: the next sign-in is a new player
        assert (await c.delete("/api/account", headers=h)).status_code == 200
        r = await c.post("/api/auth/google", json={"id_token": id_token()})
        fresh = (await (await c.get("/api/state", headers={"Authorization": f"Bearer {(await r.get_json())['token']}"})).get_json())
        assert fresh["state"]["user"]["id"] != gu["id"]
    run(tmp_path, scenario)


def test_web_flow(tmp_path):
    fake = FakeGoogle()

    async def scenario(app, c, google):
        r = await c.get("/api/auth/google/start?lang=de")
        assert r.status_code == 302
        url = urllib.parse.urlparse(r.headers["Location"])
        q = dict(urllib.parse.parse_qsl(url.query))
        assert url.netloc == "accounts.google.com" and q["client_id"] == CLIENT and q["hl"] == "de"
        assert q["redirect_uri"].endswith("/api/auth/google/callback")
        fake.codes["c1"] = id_token(sub="3003", nonce=q["nonce"])
        # a forged state (login CSRF) is refused, and the attempt is used up
        r = await c.get(f"/api/auth/google/callback?state=forged&code=c1")
        assert r.headers["Location"] == "/app/#google=error"
        r = await c.get(f"/api/auth/google/callback?state={q['state']}&code=c1")
        assert r.headers["Location"] == "/app/#google=error"
        r = await c.get("/api/auth/google/start?lang=de")
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(r.headers["Location"]).query))
        fake.codes["c1"] = id_token(sub="3003", nonce=q["nonce"])
        r = await c.get(f"/api/auth/google/callback?state={q['state']}&code=c1")
        loc = r.headers["Location"]
        assert loc.startswith("/app/#token=")
        token = loc.split("=", 1)[1]
        st = (await (await c.get("/api/state", headers={"Authorization": f"Bearer {token}"})).get_json())["state"]
        assert st["user"]["google_email"] == "ann@gmail.com"
        # back from Google with "cancel"
        await c.get("/api/auth/google/start?lang=en")
        r = await c.get("/api/auth/google/callback?error=access_denied")
        assert r.headers["Location"] == "/app/#google=cancel"
    run(tmp_path, scenario, fake)


def test_not_configured(tmp_path):
    async def go():
        app = create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(),
                         google=Google("", ""))
        async with app.test_app() as ta:
            await asyncio.wait_for(scenario(ta.test_client()), 20)

    async def scenario(c):
            meta = await (await c.get("/api/meta")).get_json()
            assert meta["google_client_id"] is None and meta["google_web"] is False
            assert (await c.post("/api/auth/google", json={"id_token": id_token()})).status_code == 404
            assert (await c.get("/api/auth/google/start")).status_code == 404
    asyncio.run(go())
