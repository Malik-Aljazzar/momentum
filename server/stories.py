"""Stories (24 h, like Instagram) and highlights (stories kept on the profile).

A story is one uploaded photo / video (text burned in like posts), optionally a sound and up to 5
rules tapped with it (triggers.py). The feed shows the people with stories at the top, the ones
not seen yet first (with a frame). Expired stories stay in the owner's archive for highlights.
"""
import json
from datetime import timedelta

from quart import g, jsonify, request
from sqlalchemy import delete, func, select

from .db import Follow, Highlight, HighlightStory, Media, MediaTrigger, Story, StorySeen, User, utcnow
from .social import block_ids, card_map, connected, iso

STORY_HOURS = 24
PHOTO_SECONDS = (5, 10, 15, 30)        # how long a photo story may run (report #39)
MAX_HIGHLIGHTS = 30
MAX_HIGHLIGHT_STORIES = 100


async def stories_json(s, me_id, stories):
    from .posts import media_json
    from .triggers import for_stories
    stories = list(stories)
    if not stories:
        return []
    ids = [st.id for st in stories]
    media = {md.id: md for md in await s.scalars(select(Media).where(Media.id.in_({st.media_id for st in stories})))}
    sounds = {md.story_id: md for md in await s.scalars(select(Media).where(Media.story_id.in_(ids), Media.kind == "audio"))}
    seen = set(await s.scalars(select(StorySeen.story_id).where(StorySeen.story_id.in_(ids), StorySeen.user_id == me_id)))
    # my own stories: how many have seen them (who: /api/stories/<id>/viewers)
    mine = [st.id for st in stories if st.user_id == me_id]
    views = dict((await s.execute(select(StorySeen.story_id, func.count()).where(StorySeen.story_id.in_(mine))
                                  .group_by(StorySeen.story_id))).all()) if mine else {}
    people = await card_map(s, me_id, {st.user_id for st in stories})
    # others' stories can be answered (a message to the author) by people who may write to them
    can_reply = {u: await connected(s, me_id, u) for u in {st.user_id for st in stories} if u != me_id}
    trig = await for_stories(s, ids)
    now = utcnow()
    out = []
    for st in stories:
        md = media.get(st.media_id)
        if md is None:
            continue
        snd = sounds.get(st.id)
        out.append(dict(
            id=st.id, at=iso(st.created_at), expires_at=iso(st.expires_at), active=st.expires_at > now,
            duration=md.duration if md.kind == "video" and md.duration else (st.duration or 5.0),
            user=people.get(st.user_id), mine=st.user_id == me_id, seen=st.id in seen or st.user_id == me_id,
            media=media_json(md), triggers=trig.get(st.id, []),
            sound=dict(url=f"/{snd.key}", title=st.sound_title, duration=snd.duration) if snd else None,
            views=views.get(st.id, 0) if st.user_id == me_id else None,
            can_reply=can_reply.get(st.user_id, False),
        ))
    return out


async def story_visible(s, me_id, st):
    """May this person see the story? The author always; others while it runs (or in a highlight), if they
    may see the author's profile and nobody blocked anybody."""
    if st.user_id == me_id:
        return True
    from .community import can_view
    if st.user_id in await block_ids(s, me_id):
        return False
    author = await s.get(User, st.user_id)
    if author is None or not await can_view(s, me_id, author):
        return False
    return st.expires_at > utcnow() or bool(await s.scalar(
        select(func.count()).select_from(HighlightStory).where(HighlightStory.story_id == st.id)))


async def story_state(s, me_id, user_id):
    """For a profile picture's ring: active stories and whether some are new to me."""
    active = list(await s.scalars(select(Story.id).where(Story.user_id == user_id, Story.expires_at > utcnow())))
    if not active:
        return dict(active=0, unseen=False)
    seen = await s.scalar(select(func.count()).select_from(StorySeen).where(StorySeen.story_id.in_(active), StorySeen.user_id == me_id))
    return dict(active=len(active), unseen=user_id != me_id and seen < len(active))


async def delete_stories(s, blobs, story_ids):
    from .posts import delete_media_blobs
    if not story_ids:
        return
    media = list(await s.scalars(select(Media).where(Media.story_id.in_(story_ids))))
    media_ids = list(await s.scalars(select(Story.media_id).where(Story.id.in_(story_ids))))
    media += list(await s.scalars(select(Media).where(Media.id.in_(media_ids))))
    await delete_media_blobs(blobs, media)
    await s.execute(delete(Media).where(Media.id.in_({md.id for md in media})))
    for model, col in ((MediaTrigger, MediaTrigger.story_id), (StorySeen, StorySeen.story_id), (HighlightStory, HighlightStory.story_id)):
        await s.execute(delete(model).where(col.in_(story_ids)))
    await s.execute(delete(Story).where(Story.id.in_(story_ids)))


async def delete_user_stories(s, blobs, user_id):
    """Account deletion: own stories (with media), highlights, seen marks."""
    await delete_stories(s, blobs, list(await s.scalars(select(Story.id).where(Story.user_id == user_id))))
    hl = select(Highlight.id).where(Highlight.user_id == user_id)
    await s.execute(delete(HighlightStory).where(HighlightStory.highlight_id.in_(hl)))
    await s.execute(delete(Highlight).where(Highlight.user_id == user_id))
    await s.execute(delete(StorySeen).where(StorySeen.user_id == user_id))


def register(app, authed, api_error, blobs):
    from .community import can_view

    async def visible_user(username):
        user = await s_user(username)
        if user.id in await block_ids(g.s, g.user.id) or not await can_view(g.s, g.user.id, user):
            raise api_error("Nicht sichtbar", "Not visible", 403)
        return user

    async def s_user(username):
        user = await g.s.scalar(select(User).where(User.username == username.lower()))
        if user is None:
            raise api_error("Nutzer nicht gefunden", "User not found", 404)
        return user

    async def own_story(story_id):
        st = await g.s.get(Story, story_id)
        if st is None or st.user_id != g.user.id:
            raise api_error("Story nicht gefunden", "Story not found", 404)
        return st

    @app.post("/api/stories")
    @authed
    async def create_story():
        """{"media_id", "overlay"?, "sound"?: {"media_id", "title"}, "triggers"?: [{"rule_id", "times"}],
        "duration"?: 5|10|15|30 (photos; videos run as long as they are)}"""
        from . import triggers as trg
        from .media import POST_SOUND_SECONDS
        from .posts import bake_overlay, clean_overlay
        data = await request.get_json(force=True) or {}
        try:
            md = await g.s.get(Media, int(data.get("media_id")))
        except (TypeError, ValueError):
            md = None
        if md is None or md.user_id != g.user.id or md.attached or md.kind not in ("image", "video"):
            raise api_error("Bild oder Video fehlt", "Photo or video missing")
        sound = None
        if (data.get("sound") or {}).get("media_id") is not None:
            try:
                sound = await g.s.get(Media, int(data["sound"]["media_id"]))
            except (TypeError, ValueError):
                sound = None
            if sound is None or sound.user_id != g.user.id or sound.kind != "audio" or sound.attached \
                    or sound.duration > POST_SOUND_SECONDS + 1:
                raise api_error("Sound ungültig", "Invalid sound")
        try:
            tapped = await trg.parse(g.s, g.user, [{**t, "slide": 0} for t in data.get("triggers") or [] if isinstance(t, dict)], 1)
        except trg.InvalidTriggers:
            raise api_error("Regeln ungültig (nur antippbare Regeln aus deinem Profil, bis zu 5)",
                            "Invalid rules (only tappable rules from your profile, up to 5)")
        duration = data.get("duration", 5)
        if md.kind == "image" and duration not in PHOTO_SECONDS:
            raise api_error("Dauer: 5, 10, 15 oder 30 Sekunden", "Duration: 5, 10, 15 or 30 seconds")
        now = utcnow()
        from .nine import count_post
        count_post(g.user, api_error)                  # stories count as posts (the Clown, 100 a day)
        st = Story(user_id=g.user.id, media_id=md.id, created_at=now, expires_at=now + timedelta(hours=STORY_HOURS),
                   duration=float(duration) if md.kind == "image" else float(md.duration or 5.0),
                   sound_title=str((data.get("sound") or {}).get("title") or "").strip()[:60] if sound else "")
        g.s.add(st)
        await g.s.flush()
        md.attached, md.story_id = True, st.id
        if sound is not None:
            sound.attached, sound.story_id = True, st.id
        o = clean_overlay(data.get("overlay"))
        if o:
            md.overlay = json.dumps(o)
            await bake_overlay(blobs, md)
        await trg.apply(g.s, g.user, tapped, story_id=st.id)
        return jsonify(story=(await stories_json(g.s, g.user.id, [st]))[0])

    @app.get("/api/stories")
    @authed
    async def story_feed():
        """People with stories for the feed's top row: me first, then the ones with new stories."""
        me = g.user.id
        following = set(await g.s.scalars(select(Follow.followee_id).where(Follow.follower_id == me)))
        hidden = await block_ids(g.s, me)
        ids = (following | {me}) - hidden
        active = list(await g.s.scalars(select(Story).where(Story.user_id.in_(ids), Story.expires_at > utcnow())
                                        .order_by(Story.created_at)))
        if not active:
            return jsonify(rows=[])
        js = await stories_json(g.s, me, active)
        rows = {}
        for st in js:
            rows.setdefault(st["user"]["username"], dict(user=st["user"], stories=[]))["stories"].append(st)
        out = list(rows.values())
        for r in out:
            r["unseen"] = any(not st["seen"] for st in r["stories"])
            r["latest"] = r["stories"][-1]["at"]
        out.sort(key=lambda r: r["latest"], reverse=True)                       # newest first ...
        out.sort(key=lambda r: (not r["user"]["is_me"], not r["unseen"]))       # ... me, then new ones
        return jsonify(rows=out)

    @app.get("/api/users/<username>/stories")
    @authed
    async def user_stories(username):
        user = await visible_user(username)
        rows = await g.s.scalars(select(Story).where(Story.user_id == user.id, Story.expires_at > utcnow()).order_by(Story.created_at))
        return jsonify(stories=await stories_json(g.s, g.user.id, rows))

    @app.post("/api/stories/<int:story_id>/seen")
    @authed
    async def story_seen(story_id):
        st = await g.s.get(Story, story_id)
        if st is not None and st.user_id != g.user.id and not await g.s.get(StorySeen, (story_id, g.user.id)):
            g.s.add(StorySeen(story_id=story_id, user_id=g.user.id))
        return jsonify(ok=True)

    @app.get("/api/stories/<int:story_id>/viewers")
    @authed
    async def story_viewers(story_id):
        """Who saw my story, the latest first (only for the one who posted it)."""
        from .posts import page_args
        from .social import cards
        st = await own_story(story_id)
        offset, limit = page_args()
        hidden = await block_ids(g.s, g.user.id)
        rows = (await g.s.execute(select(User, StorySeen.created_at).join(StorySeen, StorySeen.user_id == User.id)
                                  .where(StorySeen.story_id == st.id, User.id.notin_(hidden))
                                  .order_by(StorySeen.created_at.desc()).offset(offset).limit(limit))).all()
        people = await cards(g.s, g.user.id, [u for u, _ in rows])
        return jsonify(viewers=[dict(user=c, at=iso(at)) for c, (_, at) in zip(people, rows)])

    @app.delete("/api/stories/<int:story_id>")
    @authed
    async def delete_story(story_id):
        st = await own_story(story_id)
        await delete_stories(g.s, blobs, [st.id])
        return jsonify(ok=True)

    async def visible_story(story_id):
        st = await g.s.get(Story, story_id)
        if st is None or not await story_visible(g.s, g.user.id, st):
            raise api_error("Story nicht verfügbar", "Story not available", 404)
        return st

    @app.get("/api/stories/<int:story_id>")
    @authed
    async def one_story(story_id):
        """A single story (opened from a chat it was shared into)."""
        st = await visible_story(story_id)
        return jsonify(story=(await stories_json(g.s, g.user.id, [st]))[0])

    @app.post("/api/stories/<int:story_id>/reply")
    @authed
    async def reply_story(story_id):
        """{"text": ...}: an answer or a quick reaction (an emoji) - a message to the author with the story."""
        from .chat import MAX_TEXT, direct_conversation
        from .db import Message
        from .mentions import encode
        st = await visible_story(story_id)
        text = str((await request.get_json(force=True) or {}).get("text", "")).strip()[:MAX_TEXT]
        if not text:
            raise api_error("Leere Nachricht", "Empty message")
        if st.user_id == g.user.id or not await connected(g.s, g.user.id, st.user_id):
            raise api_error("Antworten nicht möglich", "Can't reply", 403)
        conv = await direct_conversation(g.s, g.user.id, st.user_id)
        if conv.e2e:           # encrypted: the apps send it as a chat message with the story attached
            raise api_error("Dieser Chat ist verschlüsselt", "This chat is encrypted", 409)
        msg = Message(conversation_id=conv.id, user_id=g.user.id, text=await encode(g.s, text),
                      share_kind="story", share_ref=str(st.id))
        g.s.add(msg)
        conv.last_message_at = utcnow()
        await g.s.flush()
        from .db import ConversationMember
        member = await g.s.get(ConversationMember, (conv.id, g.user.id))
        member.last_read_id = msg.id
        return jsonify(ok=True, conversation_id=conv.id)

    @app.get("/api/stories/archive")
    @authed
    async def story_archive():
        """All my stories, newest first (to put older ones into highlights)."""
        from .posts import page_args
        offset, limit = page_args()
        rows = await g.s.scalars(select(Story).where(Story.user_id == g.user.id).order_by(Story.created_at.desc())
                                 .offset(offset).limit(limit))
        return jsonify(stories=await stories_json(g.s, g.user.id, rows))

    # ---- highlights

    async def highlights_json(hls):
        hls = list(hls)
        links = list(await g.s.scalars(select(HighlightStory).where(HighlightStory.highlight_id.in_([h.id for h in hls]))
                                       .order_by(HighlightStory.created_at))) if hls else []
        stories = {st.id: st for st in await g.s.scalars(select(Story).where(Story.id.in_({l.story_id for l in links})))} if links else {}
        js = {st["id"]: st for st in await stories_json(g.s, g.user.id, stories.values())}
        out = []
        for h in hls:
            items = [js[l.story_id] for l in links if l.highlight_id == h.id and l.story_id in js]
            out.append(dict(id=h.id, title=h.title, cover=items[0]["media"]["thumb"] if items else None, stories=items))
        return out

    async def own_highlight(hid):
        h = await g.s.get(Highlight, hid)
        if h is None or h.user_id != g.user.id:
            raise api_error("Highlight nicht gefunden", "Highlight not found", 404)
        return h

    async def add_stories(h, ids):
        mine = set(await g.s.scalars(select(Story.id).where(Story.id.in_(ids), Story.user_id == g.user.id)))
        have = set(await g.s.scalars(select(HighlightStory.story_id).where(HighlightStory.highlight_id == h.id)))
        for sid in [i for i in ids if i in mine and i not in have][:MAX_HIGHLIGHT_STORIES - len(have)]:
            g.s.add(HighlightStory(highlight_id=h.id, story_id=sid))
        await g.s.flush()

    def ids_of(data, key):
        try:
            return [int(i) for i in data.get(key) or []][:MAX_HIGHLIGHT_STORIES]
        except (TypeError, ValueError):
            raise api_error("Stories ungültig", "Invalid stories")

    @app.get("/api/users/<username>/highlights")
    @authed
    async def user_highlights(username):
        user = await visible_user(username)
        hls = await g.s.scalars(select(Highlight).where(Highlight.user_id == user.id).order_by(Highlight.created_at))
        return jsonify(highlights=[h for h in await highlights_json(hls) if h["stories"] or user.id == g.user.id])

    @app.post("/api/highlights")
    @authed
    async def create_highlight():
        """{"title", "story_ids": [...]}"""
        data = await request.get_json(force=True) or {}
        title = str(data.get("title", "")).strip()[:30]
        if not title:
            raise api_error("Titel fehlt", "Title missing")
        count = await g.s.scalar(select(func.count()).select_from(Highlight).where(Highlight.user_id == g.user.id))
        if count >= MAX_HIGHLIGHTS:
            raise api_error(f"Höchstens {MAX_HIGHLIGHTS} Highlights", f"At most {MAX_HIGHLIGHTS} highlights")
        h = Highlight(user_id=g.user.id, title=title)
        g.s.add(h)
        await g.s.flush()
        await add_stories(h, ids_of(data, "story_ids"))
        return jsonify(highlight=(await highlights_json([h]))[0])

    @app.put("/api/highlights/<int:hid>")
    @authed
    async def update_highlight(hid):
        """{"title"?, "add"?: [story ids], "remove"?: [story ids]}"""
        h = await own_highlight(hid)
        data = await request.get_json(force=True) or {}
        if "title" in data:
            title = str(data.get("title") or "").strip()[:30]
            if not title:
                raise api_error("Titel fehlt", "Title missing")
            h.title = title
        await add_stories(h, ids_of(data, "add"))
        remove = ids_of(data, "remove")
        if remove:
            await g.s.execute(delete(HighlightStory).where(HighlightStory.highlight_id == h.id, HighlightStory.story_id.in_(remove)))
        return jsonify(highlight=(await highlights_json([h]))[0])

    @app.delete("/api/highlights/<int:hid>")
    @authed
    async def delete_highlight(hid):
        h = await own_highlight(hid)
        await g.s.execute(delete(HighlightStory).where(HighlightStory.highlight_id == h.id))
        await g.s.delete(h)
        return jsonify(ok=True)
