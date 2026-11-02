"""Sign in with Google.

Android gets an ID token from the Credential Manager and posts it (POST /api/auth/google). The PWA goes through
Google's web flow (GET /api/auth/google/start -> Google -> /api/auth/google/callback; the code is exchanged with
the client secret). Either way the ID token's signature, issuer, audience and expiry are checked here.

Config (environment): GOOGLE_CLIENT_ID = the OAuth *web* client (Android passes it as serverClientId, so its
tokens carry the same audience), GOOGLE_CLIENT_SECRET = that client's secret (only the PWA flow needs it),
GOOGLE_REDIRECT_URI (optional, default <scheme>://<host>/api/auth/google/callback). Without a client id the
clients don't show the button.

A Google account is its own identity: `User.google_sub` (Google's stable account id), never the e-mail. Its
address is kept in `User.google_email` only to show it in the settings - it is no login and never collides with
the e-mail of a password account: the same address can belong to both, as two separate accounts.
"""
import asyncio
import base64
import json
import os
import time
import urllib.parse
import urllib.request

from sqlalchemy import select

from . import service
from .db import User

CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
ISSUERS = {"accounts.google.com", "https://accounts.google.com"}
LEEWAY = 60                      # seconds of clock difference allowed for exp / iat


class GoogleError(Exception):
    pass


def _fetch_json(url, data=None):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(url, body), timeout=10) as res:
        return json.loads(res.read())


def _b64(part: str) -> bytes:
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


class Google:
    def __init__(self, client_id=None, client_secret=None, fetch=None):
        self.client_id = client_id if client_id is not None else os.environ.get("GOOGLE_CLIENT_ID", "").strip()
        self.client_secret = client_secret if client_secret is not None else os.environ.get("GOOGLE_CLIENT_SECRET", "").strip()
        self.fetch = fetch or _fetch_json          # (url, form data or None) -> JSON; tests replace it
        self._keys, self._keys_at = {}, 0.0

    @property
    def enabled(self):
        return bool(self.client_id)

    @property
    def web(self):
        return bool(self.client_id and self.client_secret)

    async def _key(self, kid):
        # Google rotates its keys: refetch hourly, or sooner (at most once a minute) for an unknown kid
        age = time.time() - self._keys_at
        if age > 3600 or (kid not in self._keys and age > 60):
            try:
                jwks = await asyncio.to_thread(self.fetch, CERTS_URL, None)
            except Exception as e:
                raise GoogleError(f"certs: {e}")
            self._keys = {k["kid"]: k for k in jwks.get("keys", []) if "kid" in k}
            self._keys_at = time.time()
        return self._keys.get(kid)

    async def verify(self, id_token, nonce=None) -> dict:
        """The claims of a valid ID token for our client; GoogleError otherwise."""
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding, rsa

        try:
            head_b, claims_b, sig_b = str(id_token).split(".")
            head, claims = json.loads(_b64(head_b)), json.loads(_b64(claims_b))
            sig = _b64(sig_b)
        except (ValueError, TypeError):
            raise GoogleError("malformed token")
        if head.get("alg") != "RS256":
            raise GoogleError("unexpected alg")
        jwk = await self._key(head.get("kid"))
        if jwk is None:
            raise GoogleError("unknown key")
        n, e = (int.from_bytes(_b64(jwk[k]), "big") for k in ("n", "e"))
        try:
            rsa.RSAPublicNumbers(e, n).public_key().verify(
                sig, f"{head_b}.{claims_b}".encode(), padding.PKCS1v15(), hashes.SHA256())
        except InvalidSignature:
            raise GoogleError("bad signature")
        now = time.time()
        aud = claims.get("aud")
        if claims.get("iss") not in ISSUERS:
            raise GoogleError("wrong issuer")
        if not self.client_id or (aud != self.client_id and not (isinstance(aud, list) and self.client_id in aud)):
            raise GoogleError("wrong audience")
        if not isinstance(claims.get("exp"), (int, float)) or claims["exp"] < now - LEEWAY:
            raise GoogleError("expired")
        if not claims.get("sub"):
            raise GoogleError("no subject")
        if nonce is not None and claims.get("nonce") != nonce:
            raise GoogleError("wrong nonce")
        return claims

    def auth_url(self, redirect_uri, state, nonce, lang):
        return AUTH_URL + "?" + urllib.parse.urlencode(dict(
            client_id=self.client_id, redirect_uri=redirect_uri, response_type="code", scope="openid email",
            state=state, nonce=nonce, prompt="select_account", hl=lang))

    async def exchange(self, code, redirect_uri, nonce) -> dict:
        """Web flow: the authorization code -> the verified ID token's claims."""
        try:
            res = await asyncio.to_thread(self.fetch, TOKEN_URL, dict(
                code=code, client_id=self.client_id, client_secret=self.client_secret,
                redirect_uri=redirect_uri, grant_type="authorization_code"))
        except Exception as e:
            raise GoogleError(f"token exchange: {e}")
        if not isinstance(res, dict) or not res.get("id_token"):
            raise GoogleError("no id_token")
        return await self.verify(res["id_token"], nonce)


async def account_for(s, claims, lang):
    """The player of a Google account (by `sub`), created on the first sign-in (username chosen after)."""
    sub = str(claims["sub"])[:64]
    user = await s.scalar(select(User).where(User.google_sub == sub))
    if user is None:
        # no password and no login e-mail: only Google opens this account
        user = await service.create_user(s, None, "", lang)
        user.google_sub = sub
    email = str(claims.get("email") or "").lower()[:254]
    user.google_email = email or None          # shown in the settings only, never a login
    return user
