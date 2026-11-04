"""API of the admin app (android/admin): reports with their status, server statistics, storage and its limit,
the content filter's review list.

Only accounts with `User.admin` (python -m server.admin grant <username>) get in, through their own login
(password + a code from an authenticator app) and their own sessions (scope "admin", 30 days). Game
sessions don't work here and admin sessions don't work in the game.
"""
import functools
from datetime import timedelta

from quart import Response, g, jsonify, request
from sqlalchemy import delete, func, select

from . import security, storage
from .blobs import BlobUnavailable
from .db import (AuthToken, Challenge, Conversation, Media, Message, Post, Report, Rule, Story, User, UserActivity,
                 utcnow)
from .posts import page_args

STATUSES = ("new", "todo", "done")
REVIEWS = ("waiting", "ok", "keep")        # flagged media (server/moderation.py)


def iso(dt):
    return dt.isoformat() + "Z" if dt else None


def report_json(r, github=None):
    return dict(id=r.id, kind=r.kind, text=r.text, note=r.note or "", status=r.status, username=r.username,
                platform=r.platform, device=r.device, app_version=r.app_version, created_at=iso(r.created_at),
                status_at=iso(r.status_at), images=[f"/{k}" for k in filter(None, r.media_keys.split("\n"))],
                issue=github.issue_url(r) if github else None)      # the issue in the public repo (server/github.py)


def register(app, db, api_error, quota, check_password_login, issue_token):
    def admin_only(view):
        @functools.wraps(view)
        async def wrapper(*args, **kwargs):
            token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
            async with db.session() as s:
                row = await s.get(AuthToken, token) if token else None
                if row is not None and row.scope == "admin" and (row.expires_at is None or row.expires_at < utcnow()):
                    await s.delete(row)
                    await s.commit()
                    row = None
                user = await s.get(User, row.user_id) if row is not None and row.scope == "admin" else None
                if user is None or not user.admin:            # admin taken away: its sessions end too
                    raise api_error("Nicht angemeldet", "Not logged in", 401)
                g.s, g.user, g.token = s, user, token
                result = await view(*args, **kwargs)
                await s.commit()
                return result
        return wrapper

    @app.post("/api/admin/login")
    async def admin_login():
        """{"login", "password", "code"}: the code from the authenticator app. Every failure (password,
        not an admin, code) looks the same and counts for the lockout."""
        data = await request.get_json(force=True) or {}
        async with db.session() as s:
            user, keys = await check_password_login(s, data)
            step = security.totp_match(user.totp_secret, data.get("code", ""), user.totp_last_step) if user.admin else None
            if step is None:
                await security.record_failure(s, keys)
                await s.commit()
                raise api_error("Login, Passwort oder Code falsch", "Wrong login, password or code", 401)
            user.totp_last_step = step
            await security.clear_failures(s, keys)
            return jsonify(token=await issue_token(s, user, "admin", utcnow() + security.ADMIN_TOKEN_TTL))

    @app.post("/api/admin/logout")
    @admin_only
    async def admin_logout():
        await g.s.execute(delete(AuthToken).where(AuthToken.token == g.token))
        return jsonify(ok=True)

    @app.get("/api/admin/blob/<path:key>")
    @admin_only
    async def admin_blob(key):
        """Any stored file (report screenshots are only reachable here, not under /media)."""
        try:
            obj = await quota.store.get(key)
        except BlobUnavailable:
            return Response(status=503)
        if obj is None:
            return Response(status=404)
        return Response(obj[0], content_type=obj[1], headers={"Cache-Control": "private, max-age=3600"})

    @app.get("/api/admin/me")
    @admin_only
    async def admin_me():
        return jsonify(username=g.user.username)

    # ---- reports

    @app.get("/api/admin/reports")
    @admin_only
    async def admin_reports():
        """?status=new|todo|done, paged; newest first (done: last finished first)."""
        status = request.args.get("status", "new")
        if status not in STATUSES:
            raise api_error("Status ungültig", "Invalid status")
        offset, limit = page_args()
        order = (Report.status_at.desc(), Report.id.desc()) if status == "done" else (Report.id.desc(),)
        rows = list(await g.s.scalars(select(Report).where(Report.status == status).order_by(*order)
                                      .offset(offset).limit(limit)))
        counts = dict((await g.s.execute(select(Report.status, func.count()).group_by(Report.status))).all())
        return jsonify(reports=[report_json(r, app.github) for r in rows], counts={k: counts.get(k, 0) for k in STATUSES})

    async def get_report(report_id):
        r = await g.s.get(Report, report_id)
        if r is None:
            raise api_error("Nicht gefunden", "Not found", 404)
        return r

    @app.get("/api/admin/reports/<int:report_id>")
    @admin_only
    async def admin_report(report_id):
        return jsonify(report=report_json(await get_report(report_id), app.github))

    @app.put("/api/admin/reports/<int:report_id>")
    @admin_only
    async def admin_update_report(report_id):
        """{"status": "new"|"todo"|"done", "note": "..."}"""
        r = await get_report(report_id)
        data = await request.get_json(force=True) or {}
        if "status" in data:
            if data["status"] not in STATUSES:
                raise api_error("Status ungültig", "Invalid status")
            if data["status"] != r.status:
                r.status, r.status_at = data["status"], utcnow()
        if "note" in data:
            r.note = str(data["note"] or "").strip()[:4000]
        return jsonify(report=report_json(r, app.github))

    # ---- statistics

    @app.get("/api/admin/stats")
    @admin_only
    async def admin_stats():
        """Totals and the last 30 days (active players, new accounts, posts, messages per day)."""
        s = g.s
        from . import limits
        today = limits.server_day()          # the game's day (Berlin), like the activity it counts
        since = today - timedelta(days=29)

        async def count(model, *where):
            return await s.scalar(select(func.count()).select_from(model).where(*where)) or 0

        async def per_day(column, *where):
            """{game day: count} - timestamps are UTC, the days are Berlin's (like the activity)."""
            start = limits.day_bounds(since)[0]
            out = {}
            for (at,) in (await s.execute(select(column).where(column >= start, *where))).all():
                d = str(limits.server_day(at))
                out[d] = out.get(d, 0) + 1
            return out

        active_rows = (await s.execute(select(UserActivity.day, func.count()).where(UserActivity.day >= since)
                                       .group_by(UserActivity.day))).all()
        active = {str(d): n for d, n in active_rows}
        signups = await per_day(User.created_at)
        posts = await per_day(Post.created_at)
        messages = await per_day(Message.created_at)
        days = [str(since + timedelta(days=i)) for i in range(30)]
        week = today - timedelta(days=6)
        reports = dict((await s.execute(select(Report.status, func.count()).group_by(Report.status))).all())
        return jsonify(
            totals=dict(
                users=await count(User),
                active_today=await count(UserActivity, UserActivity.day == today),
                active_7d=await s.scalar(select(func.count(func.distinct(UserActivity.user_id)))
                                         .where(UserActivity.day >= week)) or 0,
                active_30d=await s.scalar(select(func.count(func.distinct(UserActivity.user_id)))
                                          .where(UserActivity.day >= since)) or 0,
                new_users_7d=await count(User, User.created_at >= utcnow() - timedelta(days=7)),
                posts=await count(Post), stories_live=await count(Story, Story.expires_at > utcnow()),
                stories=await count(Story), messages=await count(Message),
                groups=await count(Conversation, Conversation.kind == "group"),
                # own rules (not subscriptions, group or challenge rules)
                rules=await count(Rule, Rule.template.is_(False), Rule.source_rule_id.is_(None),
                                  Rule.challenge_id.is_(None)),
                challenges=await count(Challenge),
                reports_new=reports.get("new", 0), reports_todo=reports.get("todo", 0),
                reports_done=reports.get("done", 0)),
            days=[dict(day=d, active=active.get(d, 0), signups=signups.get(d, 0), posts=posts.get(d, 0),
                       messages=messages.get(d, 0)) for d in days])

    # ---- storage

    @app.get("/api/admin/storage")
    @admin_only
    async def admin_storage():
        """Used space (DB file + blobs) by category, the limit and the disk. ?refresh=1 lists the blob store again."""
        return jsonify(await storage.overview(g.s, quota, refresh=request.args.get("refresh") == "1"))

    @app.get("/api/admin/storage/<category>")
    @admin_only
    async def admin_storage_category(category):
        """One category: its files largest first (paged) or, for DB categories, its tables."""
        if category not in storage.BLOB_CATEGORIES and category not in storage.DB_TABLES:
            raise api_error("Kategorie unbekannt", "Unknown category", 404)
        offset, limit = page_args()
        return jsonify(await storage.category_items(g.s, quota, category, offset, limit))

    @app.put("/api/admin/storage/limit")
    @admin_only
    async def admin_storage_limit():
        """{"limit": bytes}: the most the DB + blobs may take (at most the whole disk; null = the whole disk)."""
        data = await request.get_json(force=True) or {}
        total = quota.disk()[0]
        value = data.get("limit")
        if value is None:
            row = await g.s.get(storage.Config, storage.LIMIT_KEY)
            if row is not None:
                await g.s.delete(row)
            quota.configured = None
        else:
            try:
                value = int(value)
            except (TypeError, ValueError):
                raise api_error("Zahl erwartet", "Number expected")
            if not 1 <= value <= total:
                raise api_error("Limit: höchstens die ganze Festplatte", "Limit: at most the whole disk")
            await quota.save(g.s, value)
        return jsonify(await storage.overview(g.s, quota))

    # ---- content filter (server/moderation.py): flagged photos / videos of posts and stories

    def flagged_json(md, username):
        return dict(media_id=md.id, kind=md.kind, score=round(md.nsfw or 0.0, 3), review=md.review or "waiting",
                    url=f"/{md.key}", thumb=f"/{md.thumb_key or md.key}", cover=f"/{md.cover_key}",
                    username=username or "", post_id=md.post_id, story_id=md.story_id, created_at=iso(md.created_at))

    @app.get("/api/admin/flagged")
    @admin_only
    async def admin_flagged():
        """?review=waiting|ok|keep (default waiting), paged, newest first; counts per review."""
        review = request.args.get("review", "waiting")
        if review not in REVIEWS:
            raise api_error("Status ungültig", "Invalid status")
        offset, limit = page_args()
        rows = (await g.s.execute(
            select(Media, User.username).join(User, User.id == Media.user_id, isouter=True)
            .where(Media.cover_key.is_not(None), Media.review == ("" if review == "waiting" else review))
            .order_by(Media.id.desc()).offset(offset).limit(limit))).all()
        counts = dict((await g.s.execute(select(Media.review, func.count()).where(Media.cover_key.is_not(None))
                                         .group_by(Media.review))).all())
        return jsonify(items=[flagged_json(md, name) for md, name in rows],
                       counts={k: counts.get("" if k == "waiting" else k, 0) for k in REVIEWS})

    @app.put("/api/admin/flagged/<int:media_id>")
    @admin_only
    async def admin_review_flagged(media_id):
        """{"action": "ok" (shown normally) | "keep" (stays covered) | "remove" (its post / story is deleted)}"""
        from .posts import delete_media_blobs, delete_posts
        from .stories import delete_stories
        md = await g.s.get(Media, media_id)
        if md is None or md.cover_key is None:
            raise api_error("Nicht gefunden", "Not found", 404)
        action = (await request.get_json(force=True) or {}).get("action")
        if action in ("ok", "keep"):
            md.review = action
            owner = await g.s.get(User, md.user_id)
            return jsonify(item=flagged_json(md, owner.username if owner else ""))
        if action != "remove":
            raise api_error("Aktion ungültig", "Invalid action")
        if md.post_id is not None:
            await delete_posts(g.s, app.blobs, [md.post_id])
        elif md.story_id is not None:
            await delete_stories(g.s, app.blobs, [md.story_id])
        else:
            await delete_media_blobs(app.blobs, [md])
            await g.s.delete(md)
        return jsonify(removed=True)
