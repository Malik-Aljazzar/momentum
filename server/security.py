"""Login protection: lockout after failed logins, the caller's IP, TOTP (second factor of admin logins)."""
import base64
import hashlib
import hmac
import ipaddress
import secrets
import struct
import time
from datetime import datetime, timedelta

from quart import request
from sqlalchemy import DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base, utcnow

WINDOW = timedelta(minutes=15)      # failures count within this window; a lock lasts as long
# key prefix -> failures that lock it: one account from one IP, one IP (many accounts), one account (many IPs)
LIMITS = {"pair": 5, "ip": 20, "acct": 50}

APP_TOKEN_IDLE = timedelta(days=180)    # game sessions end after half a year without use
ADMIN_TOKEN_TTL = timedelta(days=30)    # admin sessions end 30 days after the login, used or not


class LoginFailure(Base):
    """Failed logins per key ("pair:<login>|<ip>", "ip:<ip>", "acct:<login>") in the current window."""
    __tablename__ = "login_failures"
    key: Mapped[str] = mapped_column(String(320), primary_key=True)
    failures: Mapped[int] = mapped_column(Integer, default=0)
    first_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


def client_ip():
    """The caller's address; behind our own proxy (nginx on the host: a private address, or no IP at all like
    a unix socket) the one it passes on. Callers from the internet can't fake it."""
    remote = request.remote_addr or ""
    try:
        addr = ipaddress.ip_address(remote)
        behind_proxy = addr.is_private or addr.is_loopback
    except ValueError:
        behind_proxy = True
    forwarded = request.headers.get("X-Real-IP", "").strip()
    return forwarded if behind_proxy and forwarded else remote


def login_keys(login):
    ip = client_ip()
    return [f"pair:{login}|{ip}", f"ip:{ip}", f"acct:{login}"]


async def locked_minutes(s, keys):
    """Minutes until the login may be tried again (0 = not locked)."""
    now = utcnow()
    worst = 0
    for key in keys:
        row = await s.get(LoginFailure, key)
        if row is not None and row.locked_until and row.locked_until > now:
            worst = max(worst, int((row.locked_until - now).total_seconds() // 60) + 1)
    return worst


async def record_failure(s, keys):
    now = utcnow()
    for key in keys:
        row = await s.get(LoginFailure, key)
        if row is None:
            row = LoginFailure(key=key, failures=0, first_at=now)
            s.add(row)
        if now - row.first_at > WINDOW:
            row.failures, row.first_at = 0, now
        row.failures += 1
        if row.failures >= LIMITS[key.split(":", 1)[0]]:
            row.locked_until = now + WINDOW
            row.failures, row.first_at = 0, now


async def clear_failures(s, keys):
    """A successful login: this account from this IP starts fresh (IP and account totals stay)."""
    row = await s.get(LoginFailure, keys[0])
    if row is not None:
        await s.delete(row)


# ---------------------------------------------------------------- TOTP (RFC 6238: 30 s steps, 6 digits, SHA-1)

def new_totp_secret():
    return base64.b32encode(secrets.token_bytes(20)).decode()


def totp_uri(username, secret):
    return f"otpauth://totp/Momentum%20Admin:{username}?secret={secret}&issuer=Momentum%20Admin"


def totp_code(secret, step):
    digest = hmac.new(base64.b32decode(secret), struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def totp_step(at=None):
    return int((time.time() if at is None else at) // 30)


def totp_match(secret, code, last_step):
    """The step the code belongs to (the current one or a neighbour, for clock drift), or None. A step at or
    before `last_step` was used already: a code works only once."""
    code = "".join(ch for ch in str(code) if ch.isdigit())
    if not secret or len(code) != 6:
        return None
    now = totp_step()
    for step in (now, now - 1, now + 1):
        if step > (last_step or 0) and hmac.compare_digest(totp_code(secret, step), code):
            return step
    return None
