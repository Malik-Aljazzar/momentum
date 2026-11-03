"""E-mails from momentum@scriptum-zeta.com: a code that confirms a player's address, a code to set a new password.

Sent through Microsoft Graph as the app itself (client credentials): POST /users/<MAIL_FROM>/sendMail. The app
registration may only send as that one mailbox - Exchange "RBAC for Applications" (role "Application Mail.Send"
with a management scope on the mailbox), no Mail.Send permission in Entra (that would be every mailbox).

Config in the server's .env: MAIL_TENANT_ID, MAIL_CLIENT_ID, MAIL_CLIENT_SECRET, MAIL_FROM; any empty = off: no
mails, nobody has to confirm. `python -m server.admin mail-test <address>` sends a test mail.

Confirming is required: a new account at once, accounts from before mails were on get GRACE_DAYS (the apps remind
them meanwhile). Until then the API only answers what the confirm screen needs (`app.CONFIRM_FREE`). Google-only
accounts are exempt (Google confirmed their address). A new password is only sent to a confirmed address.

LOCK_AFTER wrong passwords in a row lock an account with a confirmed address: every login answers 423 and mails a
reset code (at most every RESEND_SECONDS); only the new password opens it again. Without mails (or a confirmed
address) the time lockout of security.py stays the only one - a lock nobody could open would be worse.
"""
import asyncio
import hashlib
import hmac
import json
import logging
import os
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

from sqlalchemy import DateTime, ForeignKey, Integer, String, select, update
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base, utcnow

CODE_MINUTES = 30          # a code works this long
RESEND_SECONDS = 60        # a new code at most this often
HOURLY = 6                 # codes per account and purpose an hour
MAX_TRIES = 5              # wrong entries before a code is spent
GRACE_DAYS = 14            # accounts from before: time to confirm
LOCK_AFTER = 5             # wrong passwords in a row that lock the account
log = logging.getLogger(__name__)


class MailError(Exception):
    """Graph didn't take the mail (not configured right, the mailbox's right missing, Microsoft down)."""


class EmailCode(Base):
    """A 6-digit code sent to an address: purpose "confirm" (the account's address) or "reset" (new password).
    Only its hash is kept; a new one of the same purpose replaces the old one."""
    __tablename__ = "email_codes"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(10))
    email: Mapped[str] = mapped_column(String(254))
    code_hash: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    tries: Mapped[int] = mapped_column(Integer, default=0)
    used_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


def _hash(user_id, purpose, code):
    return hashlib.sha256(f"{user_id}:{purpose}:{code}".encode()).hexdigest()


class Mailer:
    """Microsoft Graph sendMail as MAIL_FROM. Tests pass their own (anything with `enabled` and `send`)."""

    def __init__(self, tenant=None, client_id=None, secret=None, sender=None):
        self.tenant = tenant if tenant is not None else os.environ.get("MAIL_TENANT_ID", "")
        self.client_id = client_id if client_id is not None else os.environ.get("MAIL_CLIENT_ID", "")
        self.secret = secret if secret is not None else os.environ.get("MAIL_CLIENT_SECRET", "")
        self.sender = sender if sender is not None else os.environ.get("MAIL_FROM", "")
        self._token, self._until = None, 0.0

    @property
    def enabled(self):
        return bool(self.tenant and self.client_id and self.secret and self.sender)

    def _post(self, url, data, headers):
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            body = e.read()[:500].decode("utf-8", "replace")
            raise MailError(f"HTTP {e.code}: {body}") from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise MailError(str(e)) from e

    def _access_token(self):
        if self._token and time.time() < self._until:
            return self._token
        body = urllib.parse.urlencode({"client_id": self.client_id, "client_secret": self.secret,
                                       "grant_type": "client_credentials", "scope": "https://graph.microsoft.com/.default"})
        data = json.loads(self._post(f"https://login.microsoftonline.com/{self.tenant}/oauth2/v2.0/token", body.encode(),
                                     {"Content-Type": "application/x-www-form-urlencoded"}))
        self._token, self._until = data["access_token"], time.time() + int(data.get("expires_in", 3600)) - 120
        return self._token

    def _send(self, to, subject, html):
        message = {"message": {"subject": subject, "body": {"contentType": "HTML", "content": html},
                               "toRecipients": [{"emailAddress": {"address": to}}]}, "saveToSentItems": False}
        self._post(f"https://graph.microsoft.com/v1.0/users/{urllib.parse.quote(self.sender)}/sendMail",
                   json.dumps(message).encode(), {"Authorization": "Bearer " + self._access_token(),
                                                  "Content-Type": "application/json"})

    async def send(self, to, subject, html):
        if not self.enabled:
            raise MailError("mail is off (MAIL_* not set)")
        await asyncio.to_thread(self._send, to, subject, html)


# ---------------------------------------------------------------- codes

async def new_code(s, user, purpose, email):
    """A fresh code for [user] (the older ones of this purpose stop working); None while one went to [email] less
    than RESEND_SECONDS ago (another address - typed wrong - may get one at once) or HOURLY are used up - the wait in
    seconds is the second value then."""
    now = utcnow()
    recent = list(await s.scalars(select(EmailCode).where(
        EmailCode.user_id == user.id, EmailCode.purpose == purpose, EmailCode.created_at > now - timedelta(hours=1))
        .order_by(EmailCode.created_at.desc())))
    same = [c for c in recent if c.email == email]
    if same:
        wait = RESEND_SECONDS - (now - same[0].created_at).total_seconds()
        if wait > 0:
            return None, int(wait) + 1
    if len(recent) >= HOURLY:
        return None, int(3600 - (now - recent[-1].created_at).total_seconds()) + 1
    await s.execute(update(EmailCode).where(EmailCode.user_id == user.id, EmailCode.purpose == purpose,
                                            EmailCode.used_at.is_(None)).values(used_at=now))
    code = f"{secrets.randbelow(1_000_000):06d}"
    s.add(EmailCode(user_id=user.id, purpose=purpose, email=email, code_hash=_hash(user.id, purpose, code), created_at=now))
    await s.flush()
    return code, 0


async def check_code(s, user, purpose, email, code):
    """"ok", "wrong" (a try is gone) or "expired" (none sent, used, too old, too many tries, another address)."""
    now = utcnow()
    row = await s.scalar(select(EmailCode).where(EmailCode.user_id == user.id, EmailCode.purpose == purpose,
                                                 EmailCode.used_at.is_(None)).order_by(EmailCode.created_at.desc()))
    if row is None or row.email != email or now - row.created_at > timedelta(minutes=CODE_MINUTES) or row.tries >= MAX_TRIES:
        return "expired"
    if not hmac.compare_digest(row.code_hash, _hash(user.id, purpose, "".join(c for c in str(code) if c.isdigit()))):
        row.tries += 1
        return "wrong"
    row.used_at = now
    return "ok"


async def delete_user_codes(s, user_id):
    from sqlalchemy import delete
    await s.execute(delete(EmailCode).where(EmailCode.user_id == user_id))


# ---------------------------------------------------------------- who has to confirm

def needs_confirming(user):
    """A password account whose address isn't confirmed (Google-only accounts never)."""
    return bool(user.password_hash) and user.email_confirmed_at is None


def confirm_state(user, enabled):
    """For the app: None (nothing to do) or {"due": when it's required, "required": the app is locked until then}."""
    if not enabled or not needs_confirming(user):
        return None
    due = user.email_due_at or utcnow()
    return dict(due=due.isoformat() + "Z", required=utcnow() >= due)


async def start_deadlines(s):
    """Once, when mails are on for the first time: unconfirmed password accounts get GRACE_DAYS."""
    from .db import Config, User
    if await s.get(Config, "email_confirm_since"):
        return
    now = utcnow()
    await s.execute(update(User).where(User.email_confirmed_at.is_(None), User.password_hash != "")
                    .values(email_due_at=now + timedelta(days=GRACE_DAYS)))
    s.add(Config(key="email_confirm_since", value=now.isoformat()))


def mask(email):
    """a***@example.com - for answers that must not give an address away."""
    name, _, domain = (email or "").partition("@")
    return (name[:1] + "***@" + domain) if domain else ""


# ---------------------------------------------------------------- texts

TEXTS = {
    "confirm": {
        "de": ("Dein Momentum-Code: {code}", "Bestätige deine E-Mail-Adresse",
               "Gib diesen Code in der App ein, um deine E-Mail-Adresse zu bestätigen. Er gilt {minutes} Minuten.",
               "Du hast kein Konto bei Momentum angelegt? Dann ignorier diese Mail einfach."),
        "en": ("Your Momentum code: {code}", "Confirm your e-mail address",
               "Enter this code in the app to confirm your e-mail address. It works for {minutes} minutes.",
               "You didn't sign up for Momentum? Then just ignore this e-mail."),
    },
    "reset": {
        "de": ("Dein Momentum-Code: {code}", "Neues Passwort",
               "Gib diesen Code in der App ein, um ein neues Passwort festzulegen. Er gilt {minutes} Minuten.",
               "Du hast das nicht angefordert? Dann ignorier diese Mail – dein Passwort bleibt, wie es ist."),
        "en": ("Your Momentum code: {code}", "New password",
               "Enter this code in the app to set a new password. It works for {minutes} minutes.",
               "You didn't ask for this? Then just ignore this e-mail – your password stays as it is."),
    },
    "test": {
        "de": ("Momentum: Testmail", "Testmail", "Der Mailversand des Servers funktioniert.", ""),
        "en": ("Momentum: test e-mail", "Test e-mail", "The server can send e-mails.", ""),
    },
}


def render(purpose, lang, code="", username=""):
    """(subject, html) of a mail: the code big, in the app's dark look."""
    subject, title, text, footer = TEXTS[purpose]["de" if lang == "de" else "en"]
    hello = ("Hallo" if lang == "de" else "Hi") + (f" {username}" if username else "") + ","
    code_block = (f'<div style="font:700 34px/1.2 ui-monospace,Menlo,Consolas,monospace;letter-spacing:8px;color:#ffcd75;'
                  f'background:#1b1e2b;border-radius:12px;padding:16px 20px;margin:18px 0;text-align:center">{code}</div>') if code else ""
    html = (f'<div style="background:#000;padding:24px 12px;font-family:system-ui,-apple-system,Segoe UI,sans-serif">'
            f'<div style="max-width:440px;margin:0 auto;background:#13151d;border-radius:18px;padding:24px;color:#f4f4f4">'
            f'<div style="font:700 13px system-ui;letter-spacing:3px;color:#8a8fa3">MOMENTUM</div>'
            f'<h1 style="font-size:22px;margin:10px 0 14px">{title}</h1>'
            f'<p style="margin:0 0 6px">{hello}</p><p style="margin:0">{text.format(minutes=CODE_MINUTES)}</p>{code_block}'
            f'<p style="margin:0;color:#8a8fa3;font-size:13px">{footer}</p></div></div>')
    return subject.format(code=code), html
