"""Quart app: JSON API under /api, welcome page with the APK download under /, sprites under /sprites."""
import asyncio
import functools
import html
import logging
import json
from datetime import datetime, timedelta, timezone
import re
import os
import secrets
from pathlib import Path
from zoneinfo import available_timezones

from quart import Quart, Response, g, jsonify, redirect, request, send_from_directory
from sqlalchemy import delete, func, select, update
from werkzeug.security import check_password_hash, generate_password_hash

from . import momentum as m
from . import adminapi, security, challenges, chat, community, decays, e2e, emoji, limits, mentions, posts, push, service, social, stories, storage, updates
from . import google as google_mod
from . import github as github_mod
from . import mail as mail_mod
from .clients import CONFIRM_APP, knows
from sqlalchemy.exc import DBAPIError
from werkzeug.exceptions import HTTPException

from .blobs import BlobUnavailable, MinioStore
from .google import Google, GoogleError
from .images import InvalidImage, compress_avatar
from .db import ConversationMember, AuthToken, Database, Event, Rule, User, UserActivity

ROOT = Path(__file__).resolve().parent.parent
SPRITE_DIR = ROOT / "pixelart" / "out"
WEB_DIR = ROOT / "web" / "dist"          # the PWA for iOS (built by `npm run build` in web/)
APK_DIR = ROOT / "android" / "app" / "build" / "outputs" / "apk"
# optimized release build preferred; debug build as fallback
APKS = [APK_DIR / "release" / "app-release.apk", APK_DIR / "debug" / "app-debug.apk"]
# the admin app (android/admin): not linked anywhere, only accounts with User.admin can use it
ADMIN_APK = ROOT / "android" / "admin" / "build" / "outputs" / "apk" / "release" / "admin-release.apk"
# what never leaves the server in an error's detail (the log keeps the full traceback)
_SECRETS = re.compile(r"(?i)(password|passwd|secret|token|key|authorization|bearer|cookie)\w*\s*[=:]?\s*\S+")
_EMAILS = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_URLS = re.compile(r"\b[a-z][a-z0-9+.-]*://\S+")
_HOSTS = re.compile(r"\b[\w.-]+:\d{2,5}\b")
_PATHS = re.compile(r"(?:[A-Za-z]:\\|/)[^\s'\"]+")
_HEX = re.compile(r"\b[0-9a-fA-F]{20,}\b")


def public_detail(e):
    """What the players may know about an unexpected error: its type and a cleaned-up message - the database
    driver's words without the statement and its data, a store error's code without resource / request ids,
    and never paths, hosts, URLs, keys, tokens or e-mail addresses."""
    if isinstance(e, DBAPIError):
        text = str(e.orig)
    elif type(e).__name__ == "S3Error":
        text = f"{getattr(e, 'code', '')}: {getattr(e, 'message', '')}"
    else:
        text = str(e)
    for rx, sub in ((_URLS, "…"), (_EMAILS, "…"), (_SECRETS, r"\1 …"), (_HOSTS, "…"), (_PATHS, "…"), (_HEX, "…")):
        text = rx.sub(sub, text)
    return f"{type(e).__name__}: {text}".replace("\n", " ").replace("\r", " ")[:160]


def latest_app():
    """Version of the APK offered at /app.apk (Gradle writes output-metadata.json next to it).

    Apps older than this get "426 Upgrade Required" and show the update screen. None = no
    release build here (local dev without APK): nobody is blocked.
    """
    for apk in APKS:
        meta = apk.parent / "output-metadata.json"
        if apk.exists() and meta.exists():
            try:
                el = json.loads(meta.read_text(encoding="utf-8"))["elements"][0]
                return dict(version_code=int(el["versionCode"]), version_name=el.get("versionName", ""))
            except (OSError, ValueError, KeyError, IndexError):
                return None
    return None


DEFAULT_DB = f"sqlite+aiosqlite:///{(Path(__file__).parent / 'momentum.db').as_posix()}"


class AccountLocked(Exception):
    """Too many wrong passwords (server/mail.py LOCK_AFTER): a reset code is mailed, only a new password opens it."""


class ConfirmRequired(Exception):
    """The account's e-mail address isn't confirmed yet and it's due (server/mail.py): only the confirm screen."""


# what an account that still has to confirm its address may call: the state (tells the app), the confirm / password
# calls, logging out, deleting the account, the background checks
CONFIRM_FREE = ("/api/state", "/api/account", "/api/updates")
CONFIRM_FREE_PREFIXES = ("/api/auth/", "/api/push/")


class App(Quart):
    async def handle_request(self, request):
        """A client that hangs up cancels its request (Quart: http.disconnect); the handler still runs to its
        end, its answer is dropped. Cancelled in the middle of a query, a DB connection kept its SQLite lock and
        every write failed with "database is locked" until a restart (2026-11-03)."""
        return await asyncio.shield(asyncio.ensure_future(super().handle_request(request)))


class ApiError(Exception):
    """Error with a German and an English message; the client's Accept-Language picks one."""

    def __init__(self, de, en, status=400):
        super().__init__(en)
        self.messages, self.status = {"de": de, "en": en}, status


# ---------------------------------------------------------------- validation

def _num(data, key, lo, hi, default=None):
    v = data.get(key, default)
    try:
        v = float(v)
    except (TypeError, ValueError):
        raise ApiError(f"{key}: Zahl erwartet", f"{key}: number expected")
    if not lo <= v <= hi:
        raise ApiError(f"{key}: muss zwischen {lo:g} und {hi:g} liegen",
                       f"{key}: must be between {lo:g} and {hi:g}")
    return v


def _str(data, key, lo, hi):
    v = str(data.get(key, "")).strip()
    if not lo <= len(v) <= hi:
        raise ApiError(f"{key}: {lo}-{hi} Zeichen", f"{key}: {lo}-{hi} characters")
    return v


def parse_rule(data, icons):
    mode = data.get("mode", "constant")
    if mode not in m.RULE_MODES:
        raise ApiError("mode ungültig", "invalid mode")
    icon = str(data.get("icon", "star"))
    if icon not in icons and not emoji.is_emoji(icon):      # a sprite name or one pixel emoji
        raise ApiError("icon ungültig", "invalid icon")
    base = _num(data, "base", 0.01, 1e6)
    out = dict(name=_str(data, "name", 1, 40), icon=icon, mode=mode, base=base,
               param=0.0, min_value=0.0, reset_days=0, reset_unit="d", public=bool(data.get("public", False)),
               tappable=data.get("tappable", True) is not False)
    if mode == "linear":
        out["param"] = _num(data, "param", 0, 1e6)
    elif mode == "exponential":
        out["param"] = _num(data, "param", 0.01, 0.99)
    if mode != "constant":
        out["min_value"] = _num(data, "min_value", 0, base, 0)
        out["reset_days"] = int(_num(data, "reset_days", 0, 365, 0))
        out["reset_unit"] = "h" if data.get("reset_unit") == "h" else "d"
    return out


USERNAME = re.compile(r"^[a-z0-9_.]{3,30}$")
EMAIL = re.compile(r"^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}$")


def parse_email(data):
    email = str(data.get("email", "")).strip().lower()
    if len(email) > 254 or not EMAIL.match(email) or ".." in email:
        raise ApiError("Bitte eine gültige E-Mail-Adresse eingeben", "Please enter a valid e-mail address")
    return email


def parse_username(data):
    username = str(data.get("username", "")).strip().lower()
    if not USERNAME.match(username):
        raise ApiError("Benutzername: 3-30 Zeichen, nur a-z, 0-9, _ und .",
                       "Username: 3-30 characters, only a-z, 0-9, _ and .")
    return username


def parse_decay(data):
    mode = data.get("catalyst_mode", "none")
    if mode not in m.CATALYST_MODES:
        raise ApiError("catalyst_mode ungültig", "invalid catalyst_mode")
    out = dict(base=_num(data, "base", 0, 1e6), threshold=_num(data, "threshold", 0, 1e6),
               catalyst_mode=mode, catalyst_a=_num(data, "catalyst_a", 0, 1e6, 0))
    if mode == "exponential":
        out["catalyst_b"] = _num(data, "catalyst_b", 1, 5)
    elif mode == "inverse":
        out["catalyst_b"] = _num(data, "catalyst_b", 0.01, 0.99)
    return out


# ---------------------------------------------------------------- app

def create_app(db_url=None, blobs=None, push_enabled=None, e2e_default=None, google=None, github=None, mail=None):
    """push_enabled: send real-time pushes (default: only the real server - tests and the e2e server
    pass their own blob store and stay quiet). e2e_default: new chats are end-to-end encrypted (default:
    the real server; the tests' chats are plain unless a test switches it on). google: the Google sign-in
    (server/google.py; default: configured from the environment). github: the reports' issues
    (server/github.py; default: from the environment, off without GITHUB_REPO + GITHUB_TOKEN). mail: e-mails with
    codes (server/mail.py; default: from the environment, off without MAIL_*)."""
    if push_enabled is None:
        push_enabled = blobs is None
    if e2e_default is None:
        e2e_default = blobs is None
    # clients: the Android app (APK at /app.apk) and the PWA (web/dist at /app/)
    app = App(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = 100 * 1024 * 1024   # raw photo/video uploads
    app.config["E2E_DEFAULT"] = bool(e2e_default)
    db_url = db_url or os.environ.get("MOMENTUM_DB", DEFAULT_DB)
    db = Database(db_url)
    # every blob put is checked against the storage limit (DB + blobs), set in the admin app
    db_file = storage.db_path_of(db_url)
    quota = storage.Quota(db_file, Path(db_file).parent if db_file else ROOT)
    blobs = storage.MeteredStore(blobs or MinioStore.from_env(), quota)
    app.db, app.blobs, app.quota = db, blobs, quota
    google = google or Google()
    github = github or github_mod.GitHub()
    app.github = github
    mailer = mail or mail_mod.Mailer()
    app.mail = mailer
    icon_dir = SPRITE_DIR / "icons"
    icons = sorted(p.stem for p in icon_dir.glob("*.png")
                   if not p.stem.startswith(("ui_", "scene_")))
    timezones = sorted(available_timezones())

    @app.before_serving
    async def startup():
        await db.create_all()
        async with db.session() as s:          # one-off: plain @names -> account references
            await mentions.migrate(s)
            await quota.load(s)
            await s.commit()
        await blobs.setup()
        async with db.session() as s:          # one-off: older report screenshots leave the public path
            await posts.protect_old_reports(s, blobs)
        if mailer.enabled:
            async with db.session() as s:      # mails on for the first time: accounts from before get time to confirm
                await mail_mod.start_deadlines(s)
                await s.commit()
        loop = asyncio.get_running_loop()
        app.background = [loop.create_task(archive_loop())]
        if github.enabled:
            app.background.append(loop.create_task(github_loop()))
        else:
            logging.getLogger(__name__).info("GitHub issues off (GITHUB_REPO / GITHUB_TOKEN not set)")

    async def archive_loop():
        """Hourly: chat photos older than two weeks keep only their small copy (report #48)."""
        while True:
            try:
                async with db.session() as s:
                    await chat.archive_chat_images(s, blobs)
                    await s.commit()
            except Exception:
                logging.getLogger(__name__).exception("archiving chat images failed")
            await asyncio.sleep(3600)

    async def github_loop():
        """Every minute: reports that changed go to their issues in the public repo."""
        while True:
            try:
                async with db.session() as s:
                    await github.sync(s)
            except Exception:
                logging.getLogger(__name__).exception("syncing reports to GitHub failed")
            await asyncio.sleep(github_mod.SYNC_SECONDS)

    @app.after_serving
    async def shutdown():
        for task in app.background:
            task.cancel()
        for task in app.background:
            try:
                await task                   # let it leave its DB session before the engine closes
            except asyncio.CancelledError:
                pass
        await db.close()

    BACKGROUND_READS = {"/api/updates", "/api/state", "/api/leaderboard"}

    @app.before_request
    async def require_current_app():
        """Outdated app (X-App-Version below the APK on offer): block everything but /api/meta and the
        read-only calls of the background (notifications, widgets) - those keep working until the
        app is updated. Play Store builds (X-App-Channel: play) are updated by Play, not by this APK."""
        sent = request.headers.get("X-App-Version", "")
        if not request.path.startswith("/api/") or request.path == "/api/meta" or not sent.isdigit():
            return None
        if request.headers.get("X-App-Channel") == "play":
            return None
        if request.method == "GET" and (request.path in BACKGROUND_READS or request.path.endswith("/ranking")):
            return None
        latest = latest_app()
        if latest and int(sent) < latest["version_code"]:
            lang = request.accept_languages.best_match(["en", "de"]) or "en"
            msg = {"de": "Bitte aktualisiere die App", "en": "Please update the app"}[lang]
            return jsonify(error=msg, update_required=True, update_url="/"), 426
        return None

    # writes that never free space; deleting, logging in/out and the admin app keep working when it's full
    QUOTA_FREE = ("/api/auth/", "/api/admin/")

    @app.before_request
    async def storage_full():
        if request.method not in ("POST", "PUT", "PATCH") or not request.path.startswith("/api/")                 or request.path.startswith(QUOTA_FREE):
            return None
        if await quota.full():
            raise storage.QuotaExceeded()
        return None

    @app.errorhandler(storage.QuotaExceeded)
    async def quota_exceeded(e):
        lang = request.accept_languages.best_match(["en", "de"]) or "en"
        msg = {"de": "Der Server-Speicher ist voll. Bitte später nochmal versuchen.",
               "en": "The server storage is full. Please try again later."}[lang]
        return jsonify(error=msg, storage_full=True), 507

    @app.errorhandler(AccountLocked)
    async def account_locked(e):
        lang = request.accept_languages.best_match(["en", "de"]) or "en"
        msg = {"de": f"Dein Konto ist nach {mail_mod.LOCK_AFTER} falschen Passwörtern gesperrt. Wir haben dir einen Code per "
                     "E-Mail geschickt – leg damit ein neues Passwort fest.",
               "en": f"Your account is locked after {mail_mod.LOCK_AFTER} wrong passwords. We've e-mailed you a code – "
                     "use it to set a new password."}[lang]
        return jsonify(error=msg, locked=True), 423

    @app.errorhandler(ConfirmRequired)
    async def confirm_required(e):
        lang = request.accept_languages.best_match(["en", "de"]) or "en"
        msg = {"de": "Bitte bestätige zuerst deine E-Mail-Adresse.", "en": "Please confirm your e-mail address first."}[lang]
        return jsonify(error=msg, confirm_email=True), 403

    @app.errorhandler(ApiError)
    async def api_error(e):
        lang = request.accept_languages.best_match(["en", "de"]) or "en"
        return jsonify(error=e.messages[lang]), e.status

    @app.errorhandler(BlobUnavailable)
    async def blob_unavailable(e):
        """The media store (MinIO) can't be reached - a route that doesn't catch it itself."""
        lang = request.accept_languages.best_match(["en", "de"]) or "en"
        msg = {"de": "Medienspeicher nicht erreichbar – der Server kann gerade keine Fotos, Videos oder Audios annehmen.",
               "en": "Media storage unavailable – the server can't take photos, videos or audio right now."}[lang]
        return jsonify(error=msg, detail=str(e)[:300]), 503

    @app.errorhandler(Exception)
    async def unexpected(e):
        """Anything unforeseen: the players see what went wrong in the backend (the exception, in one line,
        cleaned by `public_detail`) instead of a bare "Fehler 500". Known infrastructure failures get their own words."""
        if isinstance(e, HTTPException):
            return e
        app.logger.exception("Exception on request %s %s", request.method, request.path)
        lang = request.accept_languages.best_match(["en", "de"]) or "en"
        detail = public_detail(e)
        text = str(e).lower()
        if "disk is full" in text or "no space left" in text:
            return await quota_exceeded(e)
        if "database is locked" in text:
            msg = {"de": "Die Datenbank ist gerade beschäftigt – bitte gleich nochmal versuchen.",
                   "en": "The database is busy right now – please try again in a moment."}[lang]
            return jsonify(error=msg, detail=detail), 503
        if not request.path.startswith("/api/"):
            return f"500 Internal Server Error\n{detail}\n", 500, {"Content-Type": "text/plain; charset=utf-8"}
        msg = {"de": f"Serverfehler – im Backend ist etwas schiefgegangen: {detail}",
               "en": f"Server error – something went wrong in the backend: {detail}"}[lang]
        return jsonify(error=msg, detail=detail), 500

    def authed(view):
        """Opens a DB session, resolves the bearer token, catches up daily decay."""
        @functools.wraps(view)
        async def wrapper(*args, **kwargs):
            token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
            async with db.session() as s:
                user = await session_user(s, token)
                g.s, g.user, g.token = s, user, token
                confirm_gate(user)
                await limits.mark_active(s, user.id)      # online today (admin statistics; the limit counts active taps)
                await service.catch_up(s, user)
                await challenges.tick(s)                  # start / finish group challenges
                await service.apply_credit(s, user)       # challenge rewards, spread over the days
                await service.process_idle(s, user)       # rules left untapped -> automatic taps
                result = await view(*args, **kwargs)
                await s.commit()
                return result
        return wrapper

    def confirm_gate(user):
        """Not confirmed and due: only the confirm screen's calls (apps that can show it; older APKs aren't locked out)."""
        path = request.path
        if path in CONFIRM_FREE or path.startswith(CONFIRM_FREE_PREFIXES) or not knows(CONFIRM_APP):
            return
        st = mail_mod.confirm_state(user, mailer.enabled)
        if st and st["required"]:
            raise ConfirmRequired()

    def lang_of_request():
        return "de" if (request.accept_languages.best_match(["en", "de"]) or "de") == "de" else "en"

    async def send_code(s, user, purpose):
        """A new code to the account's address (429 while the last one is too fresh, 503 when the mail doesn't go out)."""
        code, wait = await mail_mod.new_code(s, user, purpose, user.email)
        if code is None:
            raise ApiError(f"Du bekommst gleich wieder einen Code – warte noch {wait} s.", f"You'll get another code shortly – wait {wait} s.", 429)
        try:
            await mailer.send(user.email, *mail_mod.render(purpose, lang_of_request(), code, user.username if user.username_set else ""))
        except mail_mod.MailError as e:
            logging.getLogger(__name__).warning("mail to user %s failed: %s", user.id, e)
            raise ApiError("Die E-Mail konnte gerade nicht gesendet werden – versuch es gleich nochmal.",
                           "The e-mail couldn't be sent right now – try again in a moment.", 503)

    async def state_response(**extra):
        await g.s.flush()
        return jsonify(state=await service.state(g.s, g.user), **extra)

    async def apply_idle(rule, data):
        """{"idle_days": n, "idle_unit": "d"|"h", "idle_rule_id": id}: tap that rule automatically after n
        untapped days (or hours).

        An unusable target (e.g. a rule created offline that the server doesn't know yet) just
        switches it off instead of rejecting the whole change.
        """
        if "idle_days" not in data and "idle_rule_id" not in data:
            return
        try:
            days = max(0, min(365, int(data.get("idle_days") or 0)))
            unit = "h" if data.get("idle_unit") == "h" else "d"
            target = await g.s.get(Rule, int(data["idle_rule_id"])) if data.get("idle_rule_id") else None
        except (TypeError, ValueError):
            days, unit, target = 0, "d", None
        if not days or not service.valid_idle_target(rule, target, g.user.id):
            rule.idle_days, rule.idle_rule_id = 0, None
            return
        if (days, unit, target.id) != (rule.idle_days, rule.idle_unit or "d", rule.idle_rule_id):
            rule.idle_since = datetime.now(timezone.utc).replace(tzinfo=None)   # counting starts now
        rule.idle_days, rule.idle_unit, rule.idle_rule_id = days, unit, target.id

    async def sync_link_visibility(rule):
        """Linked rules share their visibility: a public rule's idle target is public too, and a
        rule made private takes the public rules that tap it automatically along."""
        await g.s.flush()
        if rule.public and rule.idle_rule_id:
            target = await g.s.get(Rule, rule.idle_rule_id)
            if target is not None and target.user_id == g.user.id and not target.public and not target.source_rule_id:
                target.public = True
        if not rule.public:
            dependents = list(await g.s.scalars(select(Rule).where(
                Rule.user_id == g.user.id, Rule.idle_rule_id == rule.id, Rule.public.is_(True))))
            for dep in dependents:          # private again: their subscriptions go away too
                dep.public = False
                await community.remove_copies(g.s, dep.id)

    async def get_rule(rule_id):
        rule = await g.s.get(Rule, rule_id)
        if rule is None or rule.user_id != g.user.id:
            raise ApiError("Nicht gefunden", "Not found", 404)
        return rule

    # ---- static

    @app.get("/")
    async def index():
        return await send_from_directory(Path(__file__).parent, "landing.html")

    # privacy policy, terms of use, account deletion (linked in both apps and the Play Store listing); the operator
    # (name, address, e-mail) comes from MOMENTUM_OPERATOR in the server's .env ("\n" = new line)
    @app.get("/legal")
    async def legal():
        operator = os.environ.get("MOMENTUM_OPERATOR", "").replace("\\n", "\n").strip()
        page = (Path(__file__).parent / "legal.html").read_text(encoding="utf-8")
        return Response(page.replace("{{OPERATOR}}", html.escape(operator or "[MOMENTUM_OPERATOR fehlt]")),
                        content_type="text/html; charset=utf-8")

    @app.get("/privacy")
    async def privacy():
        return redirect("/legal#privacy")

    @app.get("/terms")
    async def terms():
        return redirect("/legal#terms")

    @app.get("/delete-account")
    async def delete_account_info():
        return redirect("/legal#delete")

    @app.get("/app")
    async def web_app_redirect():
        return redirect("/app/")

    @app.get("/app/")
    @app.get("/app/<path:path>")
    async def web_app(path="index.html"):
        """The PWA (iOS and any browser). Unknown paths inside it get the app itself (client routing);
        index.html and the service worker are never cached, so a deploy reaches everyone."""
        if not (WEB_DIR / "index.html").exists():
            return Response("PWA not built yet: cd web && npm run build", status=404)
        target = (WEB_DIR / path).resolve()
        if WEB_DIR.resolve() not in target.parents or not target.is_file():
            path = "index.html"
        response = await send_from_directory(WEB_DIR, path)
        if path in ("index.html", "sw.js", "manifest.webmanifest"):
            response.headers["Cache-Control"] = "no-cache"
        elif path.startswith("assets/"):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        if path == "sw.js":
            response.headers["Service-Worker-Allowed"] = "/app/"
        return response

    @app.get("/app.apk")
    async def download_apk():
        """Latest build of the Android app, to install it straight from the phone browser."""
        apk = next((a for a in APKS if a.exists()), None)
        if apk is None:
            return Response("APK not built yet: cd android && gradlew assembleRelease", status=404)
        # unique file name + no caching, so the phone never installs a stale download
        stamp = datetime.fromtimestamp(apk.stat().st_mtime).strftime("%Y%m%d-%H%M")
        response = await send_from_directory(apk.parent, apk.name, mimetype="application/vnd.android.package-archive",
                                             as_attachment=True, attachment_filename=f"momentum-{stamp}.apk")
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/admin.apk")
    async def download_admin_apk():
        if not ADMIN_APK.exists():
            return Response("Admin APK not built yet: deploy/deploy.sh --admin", status=404)
        stamp = datetime.fromtimestamp(ADMIN_APK.stat().st_mtime).strftime("%Y%m%d-%H%M")
        response = await send_from_directory(ADMIN_APK.parent, ADMIN_APK.name, mimetype="application/vnd.android.package-archive",
                                             as_attachment=True, attachment_filename=f"momentum-admin-{stamp}.apk")
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/sprites/<path:path>")
    async def sprites(path):
        return await send_from_directory(SPRITE_DIR, path)

    @app.get("/api/meta")
    async def meta():
        return jsonify(
            vehicles=[dict(key=k, label=l, category=c) for k, l, c in service.VEHICLES],
            icons=icons, timezones=timezones,
            # newest app version; older apps ask the user to update (download on the welcome page)
            app=dict(latest_app() or {}, update_url="/"),
            # sign in with Google: the web client id (Android's serverClientId); google_web = the PWA's flow works
            google_client_id=google.client_id or None, google_web=google.web,
            # e-mails with codes work: "forgot password" is offered (server/mail.py)
            mail=mailer.enabled)

    # ---- auth

    async def session_user(s, token):
        """The user of a game session (scope "app"); 401 for unknown, admin-only or expired tokens."""
        row = await s.get(AuthToken, token) if token else None
        now = datetime.now(timezone.utc).replace(tzinfo=None)
        if row is not None and row.scope == "app" and row.last_used_at and now - row.last_used_at > security.APP_TOKEN_IDLE:
            await s.delete(row)                  # unused for half a year: log in again
            await s.commit()
            row = None
        user = await s.get(User, row.user_id) if row is not None and row.scope == "app" else None
        if user is None:
            raise ApiError("Nicht angemeldet", "Not logged in", 401)
        if row.last_used_at is None or now - row.last_used_at > timedelta(days=1):
            row.last_used_at = now               # at most one write a day
        return user

    async def issue_token(s, user, scope="app", expires_at=None):
        token = secrets.token_urlsafe(32)
        s.add(AuthToken(token=token, user_id=user.id, scope=scope, expires_at=expires_at,
                        last_used_at=datetime.now(timezone.utc).replace(tzinfo=None)))
        await s.commit()
        return token

    def lockable(user):
        """Wrong passwords can lock it: mails work and the address is confirmed (a reset code can open it again)."""
        return mailer.enabled and bool(user.password_hash) and bool(user.email) and user.email_confirmed_at is not None

    async def prompt_reset(s, user):
        """A locked account: the reset code to its address (unless one just went), kept even though the login fails."""
        try:
            await send_code(s, user, "reset")
        except ApiError:
            pass                               # sent a moment ago / mail down: the reset screen can ask again
        await s.commit()

    async def check_password_login(s, data):
        """The account for {"login": e-mail or username, "password"}; failed tries lock it (and the IP) for a while,
        LOCK_AFTER wrong passwords in a row lock the account until a new password is set (server/mail.py)."""
        login_id = str(data.get("login", data.get("username", ""))).strip().lower()[:254]
        column = User.email if "@" in login_id else User.username
        user = await s.scalar(select(User).where(column == login_id)) if login_id else None
        if user is not None and user.locked_at is not None and lockable(user):
            await prompt_reset(s, user)
            raise AccountLocked()
        keys = security.login_keys(f"u{user.id}" if user else login_id)
        wait = await security.locked_minutes(s, keys)
        if wait:
            raise ApiError(f"Zu viele Fehlversuche. Versuch es in {wait} Min. nochmal.",
                           f"Too many failed attempts. Try again in {wait} min.", 429)
        # Google-only accounts have no password (""): never a password login
        if user is None or not user.password_hash or not check_password_hash(user.password_hash, str(data.get("password", ""))):
            await security.record_failure(s, keys)
            left = None
            if user is not None and lockable(user):
                user.failed_logins = (user.failed_logins or 0) + 1
                if user.failed_logins >= mail_mod.LOCK_AFTER:
                    user.locked_at = mail_mod.utcnow()
                    await prompt_reset(s, user)
                    raise AccountLocked()
                left = mail_mod.LOCK_AFTER - user.failed_logins
            await s.commit()
            if left is not None and left <= 2:
                raise ApiError(f"Passwort falsch – noch {left} {'Versuch' if left == 1 else 'Versuche'}, dann wird dein Konto gesperrt.",
                               f"Wrong password – {left} more {'try' if left == 1 else 'tries'}, then your account locks.", 401)
            raise ApiError("Login oder Passwort falsch", "Wrong login or password", 401)
        user.failed_logins = 0
        return user, keys

    @app.post("/api/auth/register")
    async def register():
        """Sign up with e-mail + password; the username is chosen right after (needs_username)."""
        data = await request.get_json(force=True) or {}
        email = parse_email(data)
        password = str(data.get("password", ""))
        if len(password) < 6:
            raise ApiError("Passwort: mindestens 6 Zeichen", "Password: at least 6 characters")
        async with db.session() as s:
            if await s.scalar(select(func.count()).where(User.email == email)):
                raise ApiError("Für diese E-Mail gibt es schon ein Konto", "An account with this e-mail already exists")
            lang = "de" if data.get("language") == "de" else "en"
            user = await service.create_user(s, email, generate_password_hash(password), lang)
            if mailer.enabled:                 # confirming is required at once; the code is on its way
                user.email_due_at = mail_mod.utcnow()
                try:
                    await send_code(s, user, "confirm")
                except ApiError:
                    pass                       # the confirm screen offers to send it again
            return jsonify(token=await issue_token(s, user))

    @app.post("/api/auth/login")
    async def login():
        """Log in with e-mail or username."""
        data = await request.get_json(force=True) or {}
        async with db.session() as s:
            user, keys = await check_password_login(s, data)
            await security.clear_failures(s, keys)
            return jsonify(token=await issue_token(s, user))

    # ---- sign in with Google (server/google.py): Android posts an ID token, the PWA uses the web flow

    def google_redirect_uri():
        if os.environ.get("GOOGLE_REDIRECT_URI"):
            return os.environ["GOOGLE_REDIRECT_URI"]
        proto = request.headers.get("X-Forwarded-Proto", request.scheme)
        return f"{proto}://{request.host}/api/auth/google/callback"

    @app.post("/api/auth/google")
    async def google_login():
        """{"id_token", "language"}: the token from Android's Credential Manager."""
        data = await request.get_json(force=True) or {}
        if not google.enabled:
            raise ApiError("Google-Anmeldung ist nicht eingerichtet", "Google sign-in is not set up", 404)
        try:
            claims = await google.verify(str(data.get("id_token", "")))
        except GoogleError:
            raise ApiError("Google-Anmeldung fehlgeschlagen", "Google sign-in failed", 401)
        async with db.session() as s:
            user = await google_mod.account_for(s, claims, "de" if data.get("language") == "de" else "en")
            return jsonify(token=await issue_token(s, user))

    @app.get("/api/auth/google/start")
    async def google_start():
        """PWA: off to Google; state + nonce wait in a short-lived cookie for the callback."""
        if not google.web:
            return Response("Google sign-in is not set up", status=404)
        lang = "de" if request.args.get("lang") == "de" else "en"
        state, nonce = secrets.token_urlsafe(16), secrets.token_urlsafe(16)
        response = redirect(google.auth_url(google_redirect_uri(), state, nonce, lang))
        response.set_cookie("g_login", f"{state}.{nonce}.{lang}", max_age=600, httponly=True, samesite="Lax",
                            secure=google_redirect_uri().startswith("https:"), path="/api/auth/google")
        return response

    @app.get("/api/auth/google/callback")
    async def google_callback():
        """Back from Google: the session token goes to the PWA in the URL fragment (never sent to a server)."""
        state, nonce, lang = (request.cookies.get("g_login", "").split(".") + ["", "", ""])[:3]
        token = None
        if state and request.args.get("state") == state and request.args.get("code") and google.web:
            try:
                claims = await google.exchange(request.args["code"], google_redirect_uri(), nonce)
                async with db.session() as s:
                    token = await issue_token(s, await google_mod.account_for(s, claims, lang))
            except GoogleError as e:
                logging.getLogger(__name__).warning("google sign-in failed: %s", e)
        cancelled = request.args.get("error") == "access_denied"
        response = redirect("/app/#" + (f"token={token}" if token else "google=cancel" if cancelled else "google=error"))
        response.headers["Cache-Control"] = "no-store"
        response.delete_cookie("g_login", path="/api/auth/google")
        return response

    # ---- e-mail: confirm the address (required), a new password by code (server/mail.py)

    @app.post("/api/auth/email/send")
    @authed
    async def send_confirm_code():
        """{"email"?: a new address first}: a code to the account's address. Answers the state, sent_to, resend_in."""
        if not mailer.enabled:
            raise ApiError("E-Mails sind nicht eingerichtet", "E-mail is not set up", 404)
        data = await request.get_json(force=True, silent=True) or {}
        user = g.user
        if not user.password_hash:
            raise ApiError("Dein Konto meldet sich mit Google an", "Your account signs in with Google")
        if data.get("email") and str(data["email"]).strip().lower() != user.email:
            email = parse_email(data)
            if await g.s.scalar(select(func.count()).where(User.email == email, User.id != user.id)):
                raise ApiError("Für diese E-Mail gibt es schon ein Konto", "An account with this e-mail already exists")
            # another address: it has to be confirmed (at once)
            user.email, user.email_confirmed_at, user.email_due_at = email, None, mail_mod.utcnow()
        if user.email_confirmed_at is None:
            await send_code(g.s, user, "confirm")
        return await state_response(sent_to=user.email, resend_in=mail_mod.RESEND_SECONDS)

    @app.post("/api/auth/email/confirm")
    @authed
    async def confirm_email():
        """{"code"}: the address is confirmed, the app opens up."""
        data = await request.get_json(force=True) or {}
        user = g.user
        if user.email_confirmed_at is None:
            result = await mail_mod.check_code(g.s, user, "confirm", user.email, data.get("code", ""))
            if result != "ok":
                await g.s.commit()             # a wrong try counts
                raise code_error(result)
            user.email_confirmed_at, user.email_due_at = mail_mod.utcnow(), None
        return await state_response()

    def code_error(result):
        if result == "wrong":
            return ApiError("Der Code stimmt nicht", "That code is wrong")
        return ApiError("Der Code ist abgelaufen – fordere einen neuen an", "The code has expired – ask for a new one")

    async def account_of(s, data):
        login_id = str(data.get("login", "")).strip().lower()[:254]
        if not login_id:
            return None, login_id
        column = User.email if "@" in login_id else User.username
        return await s.scalar(select(User).where(column == login_id)), login_id

    @app.post("/api/auth/password/forgot")
    async def forgot_password():
        """{"login": e-mail or username}: a code to the account's confirmed address. Always "ok" - it never tells
        whether there is such an account."""
        if not mailer.enabled:
            raise ApiError("E-Mails sind nicht eingerichtet", "E-mail is not set up", 404)
        data = await request.get_json(force=True) or {}
        async with db.session() as s:
            user, _ = await account_of(s, data)
            if user is not None and user.password_hash and user.email and user.email_confirmed_at is not None:
                try:
                    await send_code(s, user, "reset")
                except ApiError as e:
                    if e.status != 429:        # too soon: quietly the same answer
                        raise
                await s.commit()
        return jsonify(ok=True)

    @app.post("/api/auth/password/reset")
    async def reset_password():
        """{"login", "code", "password"}: the new password; every other session ends, this one starts."""
        data = await request.get_json(force=True) or {}
        password = str(data.get("password", ""))
        if len(password) < 6:
            raise ApiError("Passwort: mindestens 6 Zeichen", "Password: at least 6 characters")
        async with db.session() as s:
            user, login_id = await account_of(s, data)
            # its own limit: a password lockout mustn't block the way out of it
            keys = security.login_keys(f"reset:u{user.id}" if user else f"reset:{login_id}")
            wait = await security.locked_minutes(s, keys)
            if wait:
                raise ApiError(f"Zu viele Fehlversuche. Versuch es in {wait} Min. nochmal.",
                               f"Too many failed attempts. Try again in {wait} min.", 429)
            result = "expired"
            if user is not None and user.password_hash and user.email_confirmed_at is not None:
                result = await mail_mod.check_code(s, user, "reset", user.email, data.get("code", ""))
            if result != "ok":
                await security.record_failure(s, keys)
                await s.commit()
                raise code_error(result)
            user.password_hash = generate_password_hash(password)
            user.failed_logins, user.locked_at = 0, None          # a locked account opens again
            await s.execute(delete(AuthToken).where(AuthToken.user_id == user.id))
            await security.clear_failures(s, keys)
            await security.clear_failures(s, security.login_keys(f"u{user.id}"))   # logging in works again from here
            return jsonify(token=await issue_token(s, user))

    @app.post("/api/auth/logout")
    @authed
    async def logout():
        await g.s.execute(delete(AuthToken).where(AuthToken.token == g.token))
        return jsonify(ok=True)

    # ---- state + rules

    @app.get("/api/state")
    @authed
    async def get_state():
        return await state_response()

    @app.post("/api/rules")
    @authed
    async def create_rule():
        data = await request.get_json(force=True) or {}
        kind = data.get("kind")
        if kind not in ("acc", "dec"):
            raise ApiError("kind ungültig", "invalid kind")
        fields = parse_rule(data, icons)
        client_id = str(data.get("client_id") or "")[:40] or None
        if client_id and await g.s.scalar(select(Rule).where(Rule.user_id == g.user.id, Rule.client_id == client_id)):
            return await state_response()       # already synced (offline queue retried)
        pos = await g.s.scalar(select(func.coalesce(func.max(Rule.position), 0))
                               .where(Rule.user_id == g.user.id))
        before = await posts.feed_rule_ids(g.s, g.user.id)
        rule = Rule(user_id=g.user.id, kind=kind, position=pos + 1,
                    current=fields["base"], client_id=client_id, **fields)
        g.s.add(rule)
        await g.s.flush()
        await apply_idle(rule, data)
        await sync_link_visibility(rule)
        await posts.sync_rule_posts(g.s, blobs, g.user.id, before)      # public right away: into the feed
        return await state_response()

    @app.put("/api/rules/order")
    @authed
    async def order_rules():
        """{"ids": [...]}: my rules in the order I arranged them (drag and drop); others keep theirs after."""
        try:
            ids = [int(i) for i in (await request.get_json(force=True) or {}).get("ids", [])]
        except (TypeError, ValueError):
            raise ApiError("ids ungültig", "invalid ids")
        mine = {r.id: r for r in await g.s.scalars(select(Rule).where(Rule.user_id == g.user.id, Rule.template.is_(False)))}
        ordered = [mine[i] for i in dict.fromkeys(ids) if i in mine]
        rest = sorted((r for r in mine.values() if r not in ordered), key=lambda r: (r.position, r.id))
        for pos, r in enumerate(ordered + rest, start=1):
            r.position = pos
        return await state_response()

    @app.put("/api/rules/<int:rule_id>")
    @authed
    async def update_rule(rule_id):
        rule = await get_rule(rule_id)
        if rule.challenge_id or rule.source_rule_id:
            raise ApiError("Abonnierte Regeln kann nur ihr Ersteller ändern",
                           "Only the creator can change a subscribed rule")
        data = await request.get_json(force=True) or {}
        was_public = bool(rule.public)
        before = await posts.feed_rule_ids(g.s, g.user.id)
        for k, v in parse_rule(data, icons).items():
            setattr(rule, k, v)
        rule.current = rule.base   # new settings start fresh
        await apply_idle(rule, data)
        if was_public and not rule.public:
            await community.remove_copies(g.s, rule.id)     # private again: subscriptions go away
        await sync_link_visibility(rule)
        await community.propagate(g.s, rule)      # subscribers get the change
        await posts.sync_rule_posts(g.s, blobs, g.user.id, before, changed=[rule.id])   # back to the top of the feed
        return await state_response()

    @app.post("/api/rules/visibility-many")
    @authed
    async def visibility_many():
        """{"ids": [...], "public": bool}: make many own rules public / private at once. For subscribed rules
        it's whether they're shown in my profile; challenge rules are skipped."""
        data = await request.get_json(force=True) or {}
        public = bool(data.get("public"))
        done, skipped = 0, []
        before = await posts.feed_rule_ids(g.s, g.user.id)
        for rid in dict.fromkeys(int(i) for i in data.get("ids", []) if str(i).isdigit()):
            rule = await g.s.get(Rule, rid)
            if rule is None or rule.user_id != g.user.id or rule.template:
                skipped.append(dict(id=rid, reason="not_found"))
                continue
            if rule.challenge_id:
                skipped.append(dict(id=rid, reason="challenge"))
                continue
            if rule.source_rule_id:
                rule.in_profile = public
                done += 1
                continue
            if bool(rule.public) != public:
                rule.public = public
                if not public:
                    await community.remove_copies(g.s, rule.id)     # private again: subscriptions go away
                await sync_link_visibility(rule)
                await community.propagate(g.s, rule)
            done += 1
        await posts.sync_rule_posts(g.s, blobs, g.user.id, before)
        return await state_response(done=done, skipped=skipped)

    @app.delete("/api/rules/<int:rule_id>")
    @authed
    async def delete_rule(rule_id):
        rule = await get_rule(rule_id)
        before = await posts.feed_rule_ids(g.s, g.user.id)
        reason = await remove_mine(rule)
        if reason == "challenge":
            raise ApiError("Challenge-Regeln verschwinden, wenn du die Challenge verlässt",
                           "Challenge rules go away when you leave the challenge")
        if reason:
            raise ApiError(f"Die Challenge „{reason}“ spielt mit dieser Regel – verlass sie zuerst",
                           f"The challenge “{reason}” uses this rule – leave it first")
        await posts.sync_rule_posts(g.s, blobs, g.user.id, before)      # its feed post goes too
        return await state_response()

    async def remove_mine(rule):
        """Delete an own rule / unsubscribe. Returns why it can't be removed ("challenge", or the title of
        the challenge that plays with it), or None when done."""
        if rule.challenge_id:
            return "challenge"
        # a subscription goes together with the subscribed rules that tap it automatically
        doomed, todo = [], [rule]
        while todo:
            r = todo.pop()
            if r in doomed:
                continue
            if r.source_rule_id and (title := await community.challenge_lock(g.s, g.user.id, r.source_rule_id)):
                return title
            doomed.append(r)
            if r.source_rule_id:
                todo += list(await g.s.scalars(select(Rule).where(
                    Rule.user_id == g.user.id, Rule.idle_rule_id == r.id, Rule.source_rule_id.is_not(None))))
        for r in doomed:
            # unsubscribed from a group rule: "always follow all" would bring it back, so it goes off
            root = await g.s.get(Rule, r.source_rule_id) if r.source_rule_id else None
            if root is not None and root.template and root.group_id:
                member = await g.s.get(ConversationMember, (root.group_id, g.user.id))
                if member is not None:
                    member.auto_follow = False
            await community.remove_copies(g.s, r.id)   # deleted: subscriptions of it go away
            await g.s.execute(update(Rule).where(Rule.idle_rule_id == r.id).values(idle_rule_id=None))
            await g.s.delete(r)
        await g.s.flush()
        return None

    @app.post("/api/rules/remove-many")
    @authed
    async def remove_many():
        """{"ids": [...]}: delete own rules / unsubscribe, in one go. Rules a running challenge plays with
        stay; the answer lists what was skipped and why."""
        ids = [int(i) for i in (await request.get_json(force=True) or {}).get("ids", []) if str(i).lstrip("-").isdigit()]
        done, skipped = 0, []
        for rid in dict.fromkeys(ids):
            rule = await g.s.get(Rule, rid)
            if rule is None:                     # gone already (e.g. taken along with an unsubscribed rule)
                continue
            if rule.user_id != g.user.id or rule.template:
                skipped.append(dict(id=rid, reason="not_yours"))
                continue
            reason = await remove_mine(rule)
            if reason:
                skipped.append(dict(id=rid, reason="challenge", challenge=None if reason == "challenge" else reason))
            else:
                done += 1
        return await state_response(done=done, skipped=skipped)

    @app.put("/api/rules/<int:rule_id>/idle-pause")
    @authed
    async def pause_idle(rule_id):
        """{"paused": bool}: pause the automatic tap (also of subscribed rules). Counting restarts on resume."""
        rule = await get_rule(rule_id)
        paused = bool((await request.get_json(force=True) or {}).get("paused"))
        if rule.idle_paused and not paused:
            rule.idle_since = datetime.now(timezone.utc).replace(tzinfo=None)
        rule.idle_paused = paused
        return await state_response()

    @app.post("/api/rules/<int:rule_id>/trigger")
    @authed
    async def trigger_rule(rule_id):
        """Body (optional): {"at": ISO time of an offline tap, "op_id": unique id for de-duplication}."""
        data = await request.get_json(silent=True) or {}
        op_id = str(data.get("op_id") or "")[:40] or None
        if op_id and await g.s.scalar(select(Event).where(Event.user_id == g.user.id, Event.op_id == op_id)):
            return await state_response(delta=0, free_delta=0, milestone=False, capped=False, duplicate=True)
        at = None
        if data.get("at"):
            try:
                at = datetime.fromisoformat(str(data["at"]).replace("Z", "+00:00"))
                at = at.astimezone(timezone.utc).replace(tzinfo=None) if at.tzinfo else at
            except ValueError:
                raise ApiError("Zeitpunkt ungültig", "Invalid time")
        rule = await get_rule(rule_id)
        if rule.tappable is False:            # only its automatic tap counts it
            raise ApiError("Diese Regel kann man nicht antippen", "This rule can't be tapped", 409)
        if rule.challenge_id and not await challenges.in_window(g.s, rule, service.clamp_time(at)):
            raise ApiError("Die Challenge läuft nicht mehr", "The challenge is not running", 409)
        try:
            result = await service.trigger(g.s, g.user, rule, at)
        except limits.LimitReached:
            raise ApiError("Tageslimit erreicht – morgen geht's weiter", "Daily limit reached – more tomorrow", 409)
        if op_id:
            await g.s.flush()
            last = await g.s.scalar(select(Event).where(Event.user_id == g.user.id, Event.rule_id == rule_id)
                                    .order_by(Event.id.desc()).limit(1))
            last.op_id = op_id
        return await state_response(**result)

    @app.post("/api/events/<int:event_id>/undo")
    @authed
    async def undo_event(event_id):
        event = await g.s.get(Event, event_id)
        if event is None or event.user_id != g.user.id or event.kind not in ("acc", "dec"):
            raise ApiError("Nicht gefunden", "Not found", 404)
        if event.challenge_id and not event.undone:
            ch = await g.s.get(challenges.Challenge, event.challenge_id)
            if ch is not None and ch.status != "running":
                raise ApiError("Die Challenge ist vorbei", "The challenge is over")
        if not event.undone:
            await service.undo(g.s, g.user, event)
        return await state_response()

    # ---- settings

    @app.put("/api/settings")
    @authed
    async def update_settings():
        data = await request.get_json(force=True) or {}
        st, user = g.user.settings, g.user
        if "timezone" in data:
            if data["timezone"] not in timezones:
                raise ApiError("Zeitzone unbekannt", "Unknown timezone")
            st.timezone = data["timezone"]
        # milestone_step is a per-player server setting (python -m server.admin), not editable in the app
        for key in ("haptics", "show_history", "daily_limit"):
            if key in data:
                setattr(st, key, bool(data[key]))
        for kind, field in service.BANNER_FIELDS.items():     # banner (road), banner_air, banner_space
            if field in data:
                try:
                    setattr(user, field, service.parse_banner(data[field], user, kind))
                except ValueError:
                    raise ApiError("Dieser Hintergrund passt nicht zu dem Fahrzeug",
                                   "This background doesn't suit that vehicle")
        if "private" in data:
            was, user.private = user.private, bool(data["private"])
            if was and not user.private:
                await community.accept_all_requests(g.s, user)   # public: pending requests become follows
        if "username" in data and str(data["username"]).strip().lower() != user.username:
            username = parse_username(data)
            if await g.s.scalar(select(func.count()).where(User.username == username)):
                raise ApiError("Benutzername ist vergeben", "Username is taken")
            user.username = username
            user.username_set = True
            user.display_name = username          # one name everywhere: the username
        if "bio" in data:
            bio = str(data.get("bio") or "").strip()
            if len(bio) > 150:
                raise ApiError("Bio: höchstens 150 Zeichen", "Bio: at most 150 characters")
            user.bio = await mentions.encode(g.s, bio)       # length counts what people see
        return await state_response()

    # ---- profile picture (MinIO)

    async def drop_avatar(user):
        if user.avatar:
            try:
                await blobs.delete(user.avatar)
            except BlobUnavailable:
                pass   # orphaned object is harmless; the key is gone from the DB
            user.avatar = None

    @app.post("/api/settings/avatar")
    @authed
    async def upload_avatar():
        """Body: the (client-cropped) image bytes. Stored as WebP < 100 KB."""
        raw = await request.get_data()
        if not raw:
            raise ApiError("Kein Bild gesendet", "No image sent")
        try:
            data = compress_avatar(raw)
        except InvalidImage:
            raise ApiError("Bild konnte nicht gelesen werden", "Could not read the image")
        key = f"avatars/{g.user.id}/{secrets.token_hex(8)}.webp"
        try:
            await blobs.put(key, data, "image/webp")
        except BlobUnavailable:
            raise ApiError("Bildspeicher nicht erreichbar", "Image storage unavailable", 503)
        await drop_avatar(g.user)
        g.user.avatar = key
        return await state_response()

    @app.delete("/api/settings/avatar")
    @authed
    async def delete_avatar():
        await drop_avatar(g.user)
        return await state_response()

    @app.get("/avatars/<int:user_id>/<name>")
    async def get_avatar(user_id, name):
        # unguessable, immutable names: a new upload gets a new key
        try:
            obj = await blobs.get(f"avatars/{user_id}/{name}")
        except BlobUnavailable:
            return Response(status=503)
        if obj is None:
            return Response(status=404)
        data, content_type = obj
        return Response(data, content_type=content_type,
                        headers={"Cache-Control": "public, max-age=31536000, immutable"})

    @app.post("/api/settings/password")
    @authed
    async def change_password():
        data = await request.get_json(force=True) or {}
        if not check_password_hash(g.user.password_hash, str(data.get("old", ""))):
            raise ApiError("Altes Passwort falsch", "Old password is wrong")
        new = str(data.get("new", ""))
        if len(new) < 6:
            raise ApiError("Passwort: mindestens 6 Zeichen", "Password: at least 6 characters")
        g.user.password_hash = generate_password_hash(new)
        return jsonify(ok=True)

    @app.post("/api/momentum/reset")
    @authed
    async def reset():
        service.reset_momentum(g.s, g.user)
        return await state_response()

    @app.post("/api/momentum/lower")
    @authed
    async def lower():
        """{"value": n}: set the own momentum down to n (0 = start over). Never up."""
        data = await request.get_json(force=True) or {}
        value = _num(data, "value", 0, 1e12)
        if not await service.lower_momentum(g.s, g.user, value):
            raise ApiError("Der Wert muss unter deinem aktuellen Momentum liegen",
                           "The value must be below your current momentum")
        return await state_response()

    @app.delete("/api/account")
    @authed
    async def delete_account():
        await drop_avatar(g.user)
        await challenges.delete_user_challenges(g.s, g.user.id)
        await community.delete_user_community(g.s, g.user.id)
        for r in await service.load_rules(g.s, g.user):
            await community.remove_copies(g.s, r.id)
        await chat.delete_user_chats(g.s, g.user.id)
        await decays.delete_user_decay(g.s, g.user.id)
        await stories.delete_user_stories(g.s, blobs, g.user.id)
        await posts.delete_user_content(g.s, blobs, g.user.id)
        await g.s.execute(delete(AuthToken).where(AuthToken.user_id == g.user.id))
        await mail_mod.delete_user_codes(g.s, g.user.id)
        await g.s.execute(delete(UserActivity).where(UserActivity.user_id == g.user.id))   # no longer a player
        # challenge templates stay: the challenge goes on for the others
        await g.s.execute(delete(Rule).where(Rule.user_id == g.user.id,
                                             (Rule.template.is_(False)) | (Rule.challenge_id.is_(None))))
        await g.s.execute(delete(Event).where(Event.user_id == g.user.id))
        await g.s.delete(g.user)
        return jsonify(ok=True)

    social.register(app, authed, ApiError)
    adminapi.register(app, db, ApiError, quota, check_password_login, issue_token)
    posts.register(app, authed, ApiError, blobs)
    stories.register(app, authed, ApiError, blobs)
    chat.register(app, authed, ApiError)
    e2e.register(app, authed, ApiError, blobs)
    community.register(app, authed, ApiError, parse_rule, icons, state_response)
    updates.register(app, authed)
    push.register(app, authed, ApiError, push_enabled)
    decays.register(app, authed, ApiError, parse_decay, state_response)
    challenges.register(app, authed, ApiError, parse_rule, icons, state_response)
    return app
