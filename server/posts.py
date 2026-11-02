"""Posts (photos, short videos, text, shared milestones), feed, likes, comments, tags."""
import asyncio
import json
import secrets
from datetime import timedelta

from quart import Response, g, jsonify, request
from sqlalchemy import delete, func, or_, select

from . import momentum as m
from .blobs import BlobUnavailable
from .db import Comment, CommentLike, Follow, Media, Notification, Post, PostLike, PostTag, User, utcnow
from .media import POST_SOUND_SECONDS, InvalidMedia, process
from .service import VEHICLES
from . import mentions
from .social import card_map, cards, connected, iso, mentioned_users, notify

PAGE = 20


def page_args(default=30):
    """?offset=&limit= for long lists (infinite scroll)."""
    offset = max(request.args.get("offset", 0, type=int) or 0, 0)
    limit = min(max(request.args.get("limit", default, type=int) or default, 1), 100)
    return offset, limit
MAX_MEDIA = 10
MAX_TEXT = 2200
MAX_COMMENT = 500
VEHICLE_KEYS = [v[0] for v in VEHICLES]


OVERLAY_COLORS = {"white", "yellow", "cyan", "pink", "lime", "black"}


def clean_overlay(raw):
    """Text over a photo / video: position and size are fractions of the picture."""
    if not isinstance(raw, dict):
        return None
    text = str(raw.get("text", "")).strip()[:120]
    if not text:
        return None
    def frac(key, lo, hi, default):
        try:
            return min(hi, max(lo, float(raw.get(key, default))))
        except (TypeError, ValueError):
            return default
    color = raw.get("color") if raw.get("color") in OVERLAY_COLORS else "white"
    return dict(text=text, x=frac("x", 0, 1, .5), y=frac("y", 0, 1, .5), color=color, size=frac("size", .03, .16, .07))


def clean_location(raw):
    """{"name": ..., "lat": .., "lon": ..} -> Post fields; a name alone (typed in) is fine too."""
    if not isinstance(raw, dict) or not str(raw.get("name", "")).strip():
        return dict(location=None, lat=None, lon=None)
    def coord(key, lim):
        try:
            v = float(raw.get(key))
            return v if -lim <= v <= lim else None
        except (TypeError, ValueError):
            return None
    lat, lon = coord("lat", 90), coord("lon", 180)
    if lat is None or lon is None:
        lat = lon = None
    return dict(location=str(raw["name"]).strip()[:80], lat=lat, lon=lon)


def media_json(md):
    # keys look like "media/<uid>/<name>" and are served under the same path
    return dict(id=md.id, kind=md.kind, url=f"/{md.key}", thumb=f"/{md.thumb_key or md.key}",
                width=md.width, height=md.height, duration=md.duration,
                overlay=json.loads(md.overlay) if md.overlay else None,
                encrypted=bool(md.encrypted))      # ciphertext: the key is in the (encrypted) message


async def bake_overlay(blobs, md, delete_old=True):
    """Burn a media item's text into the picture / video (server/overlay.py). New blob keys, as the
    old ones are cached forever; afterwards md.overlay is empty (clients draw nothing on top).
    False (text stays a live overlay) if the file can't be read or processed."""
    from . import overlay
    o = json.loads(md.overlay)
    try:
        obj = await blobs.get(md.key)
        if obj is None:
            return False
        if md.kind == "video":
            data, thumb, w, h = await asyncio.to_thread(overlay.bake_video, obj[0], o)
            ext, ctype, thumb_ext, thumb_type = "mp4", "video/mp4", "jpg", "image/jpeg"
            md.width, md.height = w, h
        else:
            data, thumb = await asyncio.to_thread(overlay.bake_image, obj[0], o)
            ext, ctype, thumb_ext, thumb_type = "webp", "image/webp", "webp", "image/webp"
        base = md.key.rsplit(".", 1)[0] + "_t"
        key, thumb_key = f"{base}.{ext}", f"{base}_thumb.{thumb_ext}" if thumb else None
        await blobs.put(key, data, ctype)
        if thumb_key:
            await blobs.put(thumb_key, thumb, thumb_type)
    except (BlobUnavailable, ValueError, OSError):
        return False
    old = [k for k in (md.key, md.thumb_key) if k]
    md.key, md.thumb_key, md.overlay = key, thumb_key, None
    for k in old if delete_old else []:
        try:
            await blobs.delete(k)
        except BlobUnavailable:
            pass
    return True


def feed_rule(rule):
    """Rules that go into the feed: own personal rules shown on the profile (no subscriptions, group or
    challenge rules)."""
    return rule is not None and bool(rule.public) and not rule.template and rule.source_rule_id is None \
        and rule.group_id is None and rule.challenge_id is None


async def feed_rule_ids(s, user_id):
    from .db import Rule
    rules = await s.scalars(select(Rule).where(Rule.user_id == user_id, Rule.public.is_(True)))
    return {r.id for r in rules if feed_rule(r)}


async def sync_rule_posts(s, blobs, user_id, before, changed=()):
    """After a change of my rules: a rule that just became public gets a feed post, a changed public rule's post
    moves to the top again, a rule that is private again (or gone) loses its post."""
    await s.flush()
    now_public = await feed_rule_ids(s, user_id)
    posts = {p.rule_id: p for p in await s.scalars(select(Post).where(Post.user_id == user_id, Post.kind == "rule"))}
    await delete_posts(s, blobs, [p.id for rid, p in posts.items() if rid not in now_public])
    now = utcnow()
    for rid in sorted(now_public):
        fresh, touched = rid not in before, rid in set(changed)
        if not (fresh or touched):
            continue
        if rid in posts:
            posts[rid].bumped_at = now
        else:
            s.add(Post(user_id=user_id, kind="rule", rule_id=rid, created_at=now, bumped_at=now))


async def post_thumbs(s, post_ids):
    """First media thumbnail per post (for activity lists and profile grids)."""
    if not post_ids:
        return {}
    rows = await s.scalars(select(Media).where(Media.post_id.in_(set(post_ids)), Media.position == 0))
    return {md.post_id: media_json(md)["thumb"] for md in rows}


async def serialize(s, me_id, posts, with_comments=False):
    posts = list(posts)
    if not posts:
        return []
    ids = [p.id for p in posts]
    media = {}
    sounds = {}
    for md in await s.scalars(select(Media).where(Media.post_id.in_(ids)).order_by(Media.position)):
        if md.kind == "audio":
            sounds[md.post_id] = md
        else:
            media.setdefault(md.post_id, []).append(media_json(md))
    likes = dict((await s.execute(select(PostLike.post_id, func.count()).where(PostLike.post_id.in_(ids))
                                  .group_by(PostLike.post_id))).all())
    liked = set(await s.scalars(select(PostLike.post_id).where(PostLike.post_id.in_(ids), PostLike.user_id == me_id)))
    n_comments = dict((await s.execute(select(Comment.post_id, func.count()).where(Comment.post_id.in_(ids))
                                       .group_by(Comment.post_id))).all())
    tags = {}
    for t in await s.scalars(select(PostTag).where(PostTag.post_id.in_(ids))):
        tags.setdefault(t.post_id, []).append(t.user_id)
    comments, c_likes, c_liked = {}, {}, set()
    if with_comments:
        from .social import block_ids
        hidden = await block_ids(s, me_id)          # comments of blocked people (either way) aren't shown
        for c in await s.scalars(select(Comment).where(Comment.post_id.in_(ids)).order_by(Comment.id)):
            if c.user_id not in hidden:
                comments.setdefault(c.post_id, []).append(c)
        cids = [c.id for v in comments.values() for c in v]
        if cids:
            c_likes = dict((await s.execute(select(CommentLike.comment_id, func.count())
                                            .where(CommentLike.comment_id.in_(cids)).group_by(CommentLike.comment_id))).all())
            c_liked = set(await s.scalars(select(CommentLike.comment_id).where(
                CommentLike.comment_id.in_(cids), CommentLike.user_id == me_id)))
    user_ids = {p.user_id for p in posts} | {u for v in tags.values() for u in v} | \
               {c.user_id for v in comments.values() for c in v}
    people = await card_map(s, me_id, user_ids)
    # @[id] references -> current usernames, one query for the whole page
    names = await mentions.names_for(s, [p.text for p in posts] + [c.text for v in comments.values() for c in v])
    from .triggers import for_posts
    triggers = await for_posts(s, ids)          # rules tapped with the slides
    from .db import Rule
    rules = {r.id: r for r in await s.scalars(select(Rule).where(Rule.id.in_({p.rule_id for p in posts if p.rule_id})))}
    out = []
    for p in posts:
        rule = rules.get(p.rule_id) if p.kind == "rule" else None
        if p.kind == "rule" and not feed_rule(rule):
            continue                              # private again / deleted meanwhile
        bumped = p.bumped_at or p.created_at
        item = dict(
            id=p.id, kind=p.kind, text=mentions.render(p.text, names), vehicle=p.vehicle,
            at=iso(bumped if p.kind == "rule" else p.created_at),
            user=people[p.user_id], media=media.get(p.id, []),
            tags=[people[u] for u in tags.get(p.id, []) if u in people],
            likes=likes.get(p.id, 0), liked=p.id in liked, comments=n_comments.get(p.id, 0),
            mine=p.user_id == me_id,
            location=dict(name=p.location, lat=p.lat, lon=p.lon) if p.location else None,
            triggers=triggers.get(p.id, []),
            sound=dict(url=f"/{sounds[p.id].key}", title=p.sound_title, duration=sounds[p.id].duration)
            if p.id in sounds else None,
        )
        if rule is not None:
            # a rule shared in the feed: new, or changed since (then it came to the top again)
            item["rule"] = dict(id=rule.id, name=rule.name, icon=rule.icon, kind=rule.kind, mode=rule.mode,
                                base=rule.base, updated=bumped > p.created_at)
        if with_comments:
            item["comment_list"] = [comment_json(c, people[c.user_id], me_id, p.user_id,
                                                 c_likes.get(c.id, 0), c.id in c_liked, names)
                                    for c in comments.get(p.id, [])]
        out.append(item)
    return out


def comment_json(c, user_card, me_id, post_owner_id, likes=0, liked=False, names=None):
    return dict(id=c.id, text=mentions.render(c.text, names or {}), at=iso(c.created_at), user=user_card,
                can_delete=me_id in (c.user_id, post_owner_id), likes=likes, liked=liked, parent_id=c.parent_id)


async def delete_media_blobs(blobs, media_rows):
    for md in media_rows:
        for key in filter(None, (md.key, md.thumb_key)):
            try:
                await blobs.delete(key)
            except BlobUnavailable:
                pass


async def drop_stale_uploads(s, blobs, user_id):
    """Uploads that were never attached (abandoned drafts) are cleaned up after a day."""
    stale = list(await s.scalars(select(Media).where(
        Media.user_id == user_id, Media.attached.is_(False), Media.created_at < utcnow() - timedelta(days=1))))
    await delete_media_blobs(blobs, stale)
    for old in stale:
        await s.delete(old)


async def delete_posts(s, blobs, post_ids):
    if not post_ids:
        return
    comment_ids = select(Comment.id).where(Comment.post_id.in_(post_ids))
    await s.execute(delete(Notification).where(or_(Notification.post_id.in_(post_ids),
                                                   Notification.comment_id.in_(comment_ids))))
    await delete_media_blobs(blobs, list(await s.scalars(select(Media).where(Media.post_id.in_(post_ids)))))
    await s.execute(delete(CommentLike).where(CommentLike.comment_id.in_(comment_ids)))
    from .db import MediaTrigger
    for model in (Media, PostLike, PostTag, Comment, MediaTrigger):
        await s.execute(delete(model).where(model.post_id.in_(post_ids)))
    await s.execute(delete(Post).where(Post.id.in_(post_ids)))


async def protect_report_media(blobs, media_rows):
    """Report screenshots aren't public: their files move from media/ (served to anyone with the link)
    to reports/ (only the admin API serves it). Rows whose files can't be moved now keep their keys."""
    for md in media_rows:
        if not md.key.startswith("media/"):
            continue
        keys = [k for k in (md.key, md.thumb_key) if k]
        try:
            for k in keys:
                obj = await blobs.get(k)
                if obj is not None:
                    await blobs.put("reports/" + k.removeprefix("media/"), *obj)
        except BlobUnavailable:
            continue
        for k in keys:
            try:
                await blobs.delete(k)
            except BlobUnavailable:
                pass
        md.key = "reports/" + md.key.removeprefix("media/")
        if md.thumb_key:
            md.thumb_key = "reports/" + md.thumb_key.removeprefix("media/")


async def protect_old_reports(s, blobs):
    """One-off on start: screenshots of reports filed before they were protected."""
    from .db import Report
    for r in list(await s.scalars(select(Report).where(Report.media_keys.like("%media/%")))):
        keys = [k for k in r.media_keys.split("\n") if k]
        rows = list(await s.scalars(select(Media).where(Media.key.in_(keys))))
        await protect_report_media(blobs, rows)
        moved = {md.key for md in rows}
        r.media_keys = "\n".join(("reports/" + k.removeprefix("media/")) if ("reports/" + k.removeprefix("media/")) in moved else k
                                 for k in keys)
    await s.commit()


def register(app, authed, api_error, blobs):
    async def visible_post(post_id):
        from .community import can_view_post
        post = await g.s.get(Post, post_id)
        if post is None or not await can_view_post(g.s, g.user.id, post):
            raise api_error("Post nicht gefunden", "Post not found", 404)
        return post

    async def one(post, with_comments=False):
        return (await serialize(g.s, g.user.id, [post], with_comments))[0]

    async def tag_users(post, usernames):
        users = list(await g.s.scalars(select(User).where(User.username.in_([u.lower() for u in usernames]))))
        for u in users:
            if u.id != g.user.id and await connected(g.s, g.user.id, u.id):
                g.s.add(PostTag(post_id=post.id, user_id=u.id))
                notify(g.s, u.id, g.user.id, "tag", post_id=post.id)

    async def notify_mentions(text, post_id, comment_id=None, skip=()):
        for u in await mentioned_users(g.s, text):
            if u.id not in skip:
                notify(g.s, u.id, g.user.id, "mention", post_id=post_id, comment_id=comment_id)

    # ---- media

    @app.post("/api/media")
    @authed
    async def upload_media():
        """Raw image/video bytes -> processed blob. Attach it to a post or message afterwards."""
        raw = await request.get_data()
        ctype = request.content_type or ""
        if not raw:
            raise api_error("Keine Datei gesendet", "No file sent")
        # audio: ?start=<s>&seconds=<s> cuts a part (a post's sound)
        start = request.args.get("start", 0.0, type=float) or 0.0
        seconds = request.args.get("seconds", 300.0, type=float) or 300.0
        try:
            p = await asyncio.to_thread(process, raw, ctype, start, seconds)
        except InvalidMedia:
            raise api_error("Datei konnte nicht verarbeitet werden", "Could not process the file")
        base = f"media/{g.user.id}/{secrets.token_hex(10)}"
        thumb_ext, thumb_type = ("webp", "image/webp") if p.kind == "image" else ("jpg", "image/jpeg")
        md = Media(user_id=g.user.id, kind=p.kind, key=f"{base}.{p.ext}", width=p.width, height=p.height,
                   duration=p.duration, thumb_key=f"{base}_thumb.{thumb_ext}" if p.thumb else None)
        try:
            await blobs.put(md.key, p.data, p.content_type)
            if p.thumb:
                await blobs.put(md.thumb_key, p.thumb, thumb_type)
        except BlobUnavailable:
            raise api_error("Medienspeicher nicht erreichbar", "Media storage unavailable", 503)
        await drop_stale_uploads(g.s, blobs, g.user.id)
        g.s.add(md)
        await g.s.flush()
        return jsonify(media=media_json(md))

    @app.post("/api/reports")
    @authed
    async def create_report():
        """{"kind": "bug"|"feature", "text": ..., "media_ids": [...], "app_version": ..., "device": ..., "platform": "android"|"pwa"}"""
        from .db import Report
        data = await request.get_json(force=True) or {}
        kind = data.get("kind")
        if kind not in ("bug", "feature"):
            raise api_error("Art ungültig", "Invalid kind")
        text = str(data.get("text", "")).strip()[:4000]
        if len(text) < 3:
            raise api_error("Beschreib kurz, worum es geht", "Please describe it briefly")
        ids = [int(i) for i in data.get("media_ids", [])][:6]
        media = list(await g.s.scalars(select(Media).where(Media.id.in_(ids), Media.user_id == g.user.id,
                                                           Media.attached.is_(False)))) if ids else []
        for md in media:
            md.attached = True                       # kept: not cleaned up as an abandoned draft
        await protect_report_media(blobs, media)
        g.s.add(Report(user_id=g.user.id, username=g.user.username, kind=kind, text=text,
                       media_keys="\n".join(md.key for md in media),       # already moved to reports/
                       app_version=str(data.get("app_version", ""))[:40], device=str(data.get("device", ""))[:120],
                       platform=report_platform(data)))
        from .service import report_bonus
        return jsonify(ok=True, bonus=await report_bonus(g.s, g.user))

    @app.get("/media/<path:key>")
    async def get_media(key):
        """Serves photos/videos; supports Range requests so video players can stream/seek."""
        try:
            obj = await blobs.get(f"media/{key}")
        except BlobUnavailable:
            return Response(status=503)
        if obj is None:
            return Response(status=404)
        data, ctype = obj
        headers = {"Cache-Control": "public, max-age=31536000, immutable", "Accept-Ranges": "bytes"}
        rng = request.headers.get("Range", "")
        if rng.startswith("bytes="):
            start_s, _, end_s = rng[6:].split(",")[0].partition("-")
            size = len(data)
            if start_s:
                start = int(start_s)
                end = min(int(end_s), size - 1) if end_s else size - 1
            else:   # suffix range: last N bytes
                start, end = max(0, size - int(end_s)), size - 1
            if start >= size or start > end:
                return Response(status=416, headers={"Content-Range": f"bytes */{size}"})
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
            return Response(data[start:end + 1], status=206, content_type=ctype, headers=headers)
        return Response(data, content_type=ctype, headers=headers)

    # ---- posts

    @app.post("/api/posts")
    @authed
    async def create_post():
        data = await request.get_json(force=True) or {}
        text = str(data.get("text", "")).strip()[:MAX_TEXT]
        ids = [int(i) for i in data.get("media_ids", [])][:MAX_MEDIA]
        media = {md.id: md for md in await g.s.scalars(select(Media).where(
            Media.id.in_(ids), Media.user_id == g.user.id, Media.attached.is_(False)))}
        if len(media) != len(ids) or any(md.kind == "audio" for md in media.values()):
            raise api_error("Medien ungültig", "Invalid media")      # voice messages are for chats
        if not text and not ids:
            raise api_error("Schreib etwas oder füge ein Bild hinzu", "Write something or add a photo")
        # rules tapped with the slides (checked before anything is stored)
        from . import triggers as trg
        try:
            tapped = await trg.parse(g.s, g.user, data.get("triggers"), len(ids))
        except trg.InvalidTriggers:
            raise api_error("Regeln ungültig (nur antippbare Regeln aus deinem Profil, bis zu 5 pro Bild)",
                            "Invalid rules (only tappable rules from your profile, up to 5 per picture)")
        if tapped and not ids:
            raise api_error("Regeln gehören zu einem Bild", "Rules go with a picture")
        # optional sound (like on Instagram / Snapchat): an uploaded audio part and its name
        sound = None
        if (data.get("sound") or {}).get("media_id") is not None:
            try:
                sound = await g.s.get(Media, int(data["sound"]["media_id"]))
            except (TypeError, ValueError):
                sound = None
            if sound is None or sound.user_id != g.user.id or sound.kind != "audio" or sound.attached \
                    or sound.duration > POST_SOUND_SECONDS + 1:
                raise api_error("Sound ungültig", "Invalid sound")
        post = Post(user_id=g.user.id, kind="post", text=await mentions.encode(g.s, text),
                    sound_title=str((data.get("sound") or {}).get("title") or "").strip()[:60] if sound else "",
                    **clean_location(data.get("location")))
        g.s.add(post)
        await g.s.flush()
        if sound is not None:
            sound.post_id, sound.position, sound.attached = post.id, -1, True
        overlays = data.get("overlays") or {}
        for pos, mid in enumerate(ids):
            media[mid].post_id, media[mid].position, media[mid].attached = post.id, pos, True
            o = clean_overlay(overlays.get(str(mid)) if isinstance(overlays, dict) else None)
            if o:
                media[mid].overlay = json.dumps(o)
                # the text becomes part of the picture: the same on every device
                await bake_overlay(blobs, media[mid])
        await tag_users(post, data.get("tags", []))
        await notify_mentions(text, post.id)
        await trg.apply(g.s, g.user, tapped, post_id=post.id)
        await g.s.flush()
        return jsonify(post=await one(post))

    @app.get("/api/songs")
    @authed
    async def songs():
        """?q=<text>: songs with a 30 s preview (Deezer) for a post's sound."""
        from . import deezer
        q = request.args.get("q", "").strip()[:80]
        if len(q) < 2:
            return jsonify(songs=[])
        return jsonify(songs=await asyncio.to_thread(deezer.search, q))

    @app.get("/api/songs/<int:track_id>/preview")
    async def song_preview(track_id):
        """The song's 30 s preview as MP3 (the client takes it like an own sound file). No login needed:
        it only passes Deezer's public preview on (Android downloads it without the auth header)."""
        from . import deezer
        got = await asyncio.to_thread(deezer.preview, track_id)
        if got is None:
            raise api_error("Song nicht verfügbar", "Song not available", 404)
        data, title = got
        from urllib.parse import quote
        return Response(data, content_type="audio/mpeg", headers={"X-Song-Title": quote(title), "Cache-Control": "no-store"})

    @app.get("/api/places")
    @authed
    async def places():
        """?q=<text>: places for a post's location (OpenStreetMap via Photon)."""
        from . import places as pl
        q = request.args.get("q", "").strip()[:80]
        if len(q) < 2:
            return jsonify(places=[])
        lang = request.accept_languages.best_match(["en", "de"]) or "de"
        return jsonify(places=await asyncio.to_thread(pl.fetch_places, q, lang))

    @app.post("/api/posts/milestone")
    @authed
    async def share_milestone():
        """Posted only when the user taps "share" on the milestone screen."""
        data = await request.get_json(force=True) or {}
        vehicle = data.get("vehicle")
        if vehicle not in VEHICLE_KEYS:
            raise api_error("Fahrzeug unbekannt", "Unknown vehicle")
        from .service import score
        reached = m.vehicle_index(score(g.user), g.user.settings.milestone_step, len(VEHICLES))
        if VEHICLE_KEYS.index(vehicle) > reached or VEHICLE_KEYS.index(vehicle) == 0:
            raise api_error("Meilenstein nicht erreicht", "Milestone not reached")
        if await g.s.scalar(select(Post).where(Post.user_id == g.user.id, Post.kind == "milestone",
                                               Post.vehicle == vehicle)):
            raise api_error("Schon geteilt", "Already shared")
        text = str(data.get("text", "")).strip()[:MAX_TEXT]
        post = Post(user_id=g.user.id, kind="milestone", vehicle=vehicle, text=await mentions.encode(g.s, text))
        g.s.add(post)
        await g.s.flush()
        await notify_mentions(text, post.id)
        return jsonify(post=await one(post))

    @app.get("/api/posts/<int:post_id>")
    @authed
    async def get_post(post_id):
        return jsonify(post=await one(await visible_post(post_id), with_comments=True))

    @app.delete("/api/posts/<int:post_id>")
    @authed
    async def delete_post(post_id):
        post = await visible_post(post_id)
        if post.user_id != g.user.id:
            raise api_error("Nicht erlaubt", "Not allowed", 403)
        await delete_posts(g.s, blobs, [post.id])
        return jsonify(ok=True)

    @app.get("/api/feed")
    @authed
    async def feed():
        """Posts of the people I follow and mine, newest first (by bumped_at: a changed rule comes up again).
        ?before=<post id>: the page after that post."""
        followee_ids = select(Follow.followee_id).where(Follow.follower_id == g.user.id)
        q = select(Post).where(Post.user_id.in_(followee_ids) | (Post.user_id == g.user.id))   # mine too
        before = request.args.get("before", type=int)
        last = await g.s.get(Post, before) if before else None
        if last is not None:
            at = last.bumped_at or last.created_at
            q = q.where((Post.bumped_at < at) | ((Post.bumped_at == at) & (Post.id < last.id)))
        elif before:
            q = q.where(Post.id < before)
        posts = await g.s.scalars(q.order_by(Post.bumped_at.desc(), Post.id.desc()).limit(PAGE))
        following = await g.s.scalar(select(func.count()).select_from(Follow).where(Follow.follower_id == g.user.id))
        return jsonify(posts=await serialize(g.s, g.user.id, posts), following=following)

    @app.get("/api/users/<username>/posts")
    @authed
    async def user_posts(username):
        user = await g.s.scalar(select(User).where(User.username == username.lower()))
        if user is None:
            raise api_error("Nutzer nicht gefunden", "User not found", 404)
        from .community import can_view
        if not await can_view(g.s, g.user.id, user):
            return jsonify(posts=[], locked=True)
        q = select(Post).where(Post.user_id == user.id, Post.kind != "rule")     # rules have their own tab
        before = request.args.get("before", type=int)
        if before:
            q = q.where(Post.id < before)
        limit = min(max(request.args.get("limit", 30, type=int) or 30, 1), 60)
        return jsonify(posts=await serialize(g.s, g.user.id, await g.s.scalars(q.order_by(Post.id.desc()).limit(limit))))

    # ---- likes + comments

    @app.post("/api/posts/<int:post_id>/like")
    @authed
    async def like(post_id):
        post = await visible_post(post_id)
        if not await g.s.get(PostLike, (post.id, g.user.id)):
            g.s.add(PostLike(post_id=post.id, user_id=g.user.id))
            notify(g.s, post.user_id, g.user.id, "like", post_id=post.id)
            await g.s.flush()
        return jsonify(post=await one(post))

    @app.delete("/api/posts/<int:post_id>/like")
    @authed
    async def unlike(post_id):
        post = await visible_post(post_id)
        await g.s.execute(delete(PostLike).where(PostLike.post_id == post.id, PostLike.user_id == g.user.id))
        return jsonify(post=await one(post))

    @app.post("/api/posts/<int:post_id>/comments")
    @authed
    async def add_comment(post_id):
        post = await visible_post(post_id)
        body = await request.get_json(force=True) or {}
        text = str(body.get("text", "")).strip()
        if not 1 <= len(text) <= MAX_COMMENT:
            raise api_error(f"Kommentar: 1-{MAX_COMMENT} Zeichen", f"Comment: 1-{MAX_COMMENT} characters")
        parent = None
        if body.get("parent_id") is not None:
            parent = await g.s.get(Comment, int(body["parent_id"]))
            if parent is None or parent.post_id != post.id:
                raise api_error("Kommentar nicht gefunden", "Comment not found", 404)
            # a reply to a reply joins the same thread
            if parent.parent_id is not None:
                parent = await g.s.get(Comment, parent.parent_id)
        c = Comment(post_id=post.id, user_id=g.user.id, text=await mentions.encode(g.s, text),
                    parent_id=parent.id if parent else None)
        g.s.add(c)
        await g.s.flush()
        notified = {post.user_id}
        if parent and parent.user_id != post.user_id:
            notify(g.s, parent.user_id, g.user.id, "reply", post_id=post.id, comment_id=c.id)
            notified.add(parent.user_id)
        notify(g.s, post.user_id, g.user.id, "reply" if parent and parent.user_id == post.user_id else "comment",
               post_id=post.id, comment_id=c.id)
        await notify_mentions(text, post.id, c.id, skip=notified)
        await g.s.flush()
        return jsonify(post=await one(post, with_comments=True))

    async def comment_of(comment_id):
        c = await g.s.get(Comment, comment_id)
        if c is None:
            raise api_error("Kommentar nicht gefunden", "Comment not found", 404)
        return c, await g.s.get(Post, c.post_id)

    @app.post("/api/comments/<int:comment_id>/like")
    @authed
    async def like_comment(comment_id):
        c, post = await comment_of(comment_id)
        if not await g.s.get(CommentLike, (c.id, g.user.id)):
            g.s.add(CommentLike(comment_id=c.id, user_id=g.user.id))
            notify(g.s, c.user_id, g.user.id, "clike", post_id=post.id, comment_id=c.id)
            await g.s.flush()
        return jsonify(post=await one(post, with_comments=True))

    @app.delete("/api/comments/<int:comment_id>/like")
    @authed
    async def unlike_comment(comment_id):
        c, post = await comment_of(comment_id)
        await g.s.execute(delete(CommentLike).where(CommentLike.comment_id == c.id, CommentLike.user_id == g.user.id))
        return jsonify(post=await one(post, with_comments=True))

    async def likers(model, column, value):
        offset, limit = page_args()
        users = await g.s.scalars(select(User).join(model, model.user_id == User.id)
                                  .where(column == value).order_by(model.created_at.desc()).offset(offset).limit(limit))
        return jsonify(users=await cards(g.s, g.user.id, users))

    @app.get("/api/posts/<int:post_id>/likes")
    @authed
    async def post_likers(post_id):
        await visible_post(post_id)
        return await likers(PostLike, PostLike.post_id, post_id)

    @app.get("/api/comments/<int:comment_id>/likes")
    @authed
    async def comment_likers(comment_id):
        await comment_of(comment_id)
        return await likers(CommentLike, CommentLike.comment_id, comment_id)

    @app.delete("/api/comments/<int:comment_id>")
    @authed
    async def delete_comment(comment_id):
        c = await g.s.get(Comment, comment_id)
        post = await g.s.get(Post, c.post_id) if c else None
        if c is None or g.user.id not in (c.user_id, post.user_id):
            raise api_error("Nicht erlaubt", "Not allowed", 403)
        # a top-level comment takes its thread with it
        ids = [c.id] + list(await g.s.scalars(select(Comment.id).where(Comment.parent_id == c.id)))
        await g.s.execute(delete(Notification).where(Notification.comment_id.in_(ids)))
        await g.s.execute(delete(CommentLike).where(CommentLike.comment_id.in_(ids)))
        await g.s.execute(delete(Comment).where(Comment.id.in_(ids)))
        await g.s.flush()
        return jsonify(post=await one(post, with_comments=True))


def report_platform(data):
    """android | pwa - older apps don't send it: the PWA's version starts with "web"."""
    p = str(data.get("platform", "")).lower()
    if p in ("android", "pwa"):
        return p
    return "pwa" if str(data.get("app_version", "")).startswith("web") else "android"


async def delete_user_content(s, blobs, user_id):
    """Account deletion: own posts (with media), likes, comments, tags, notifications."""
    await delete_posts(s, blobs, list(await s.scalars(select(Post.id).where(Post.user_id == user_id))))
    # own comments and the replies to them (a thread goes with its first comment)
    own_ids = list(await s.scalars(select(Comment.id).where(Comment.user_id == user_id)))
    own_comments = select(Comment.id).where(or_(Comment.id.in_(own_ids), Comment.parent_id.in_(own_ids)))
    await s.execute(delete(Notification).where(or_(Notification.user_id == user_id, Notification.actor_id == user_id,
                                                   Notification.comment_id.in_(own_comments))))
    await s.execute(delete(CommentLike).where(or_(CommentLike.user_id == user_id, CommentLike.comment_id.in_(own_comments))))
    await s.execute(delete(Comment).where(or_(Comment.id.in_(own_ids), Comment.parent_id.in_(own_ids))))
    await s.execute(delete(PostLike).where(PostLike.user_id == user_id))
    await s.execute(delete(PostTag).where(PostTag.user_id == user_id))
    await delete_media_blobs(blobs, list(await s.scalars(select(Media).where(Media.user_id == user_id))))
    await s.execute(delete(Media).where(Media.user_id == user_id))
    await s.execute(delete(Follow).where(or_(Follow.follower_id == user_id, Follow.followee_id == user_id)))
    from .db import Block
    await s.execute(delete(Block).where(or_(Block.blocker_id == user_id, Block.blocked_id == user_id)))
