"""Shared daily decays.

A person has exactly one decay: their own, or one they follow (the followed one replaces theirs;
unfollowing brings their own back). Their own decay can be public: others may follow it, groups
too. A group has any number of decays: made in the group, or following a person's / another
group's public decay. A challenge may force at most one of its group's decays on its participants
(followed while they take part, the previous choice comes back afterwards unless they keep it).

Followers keep their own streak (it counts their own days); the settings come from the source and
follow its changes. Made private / deleted: followers who may no longer see it fall back to their
own decay (a group's members keep a members-only group decay).

    PUT    /api/decay                       own decay (+ "public")
    POST   /api/decay/follow                {"username": ...} | {"decay_id": ...}
    DELETE /api/decay/follow                back to my own decay
    GET    /api/decays/user/<username>      a person's public decay
    GET    /api/decays/<id>                 a group decay
    GET    /api/conversations/<id>/decays   a group's decays
    POST   /api/conversations/<id>/decays   {"name", settings..., "public"} | {"from_user": u} | {"from_decay": id}
    PUT    /api/conversations/<id>/decays/<did>, DELETE ...      (admins)
"""
from datetime import datetime

from quart import g, jsonify, request
from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, func, select, update
from sqlalchemy.orm import Mapped, mapped_column

from . import momentum as m
from .db import Base, Challenge, ChallengeParticipant, Conversation, DailyDecay, User, utcnow
from .social import notify_once

SETTINGS = ("base", "threshold", "catalyst_mode", "catalyst_a", "catalyst_b")


class GroupDecay(Base):
    """A group's decay: its own settings, or following a person's (source_user_id) or another group's
    (source_decay_id) public decay."""
    __tablename__ = "group_decays"
    id: Mapped[int] = mapped_column(primary_key=True)
    group_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    name: Mapped[str] = mapped_column(String(40), default="")
    base: Mapped[float] = mapped_column(Float, default=50.0)
    threshold: Mapped[float] = mapped_column(Float, default=200.0)
    catalyst_mode: Mapped[str] = mapped_column(String(16), default="none")
    catalyst_a: Mapped[float] = mapped_column(Float, default=0.0)
    catalyst_b: Mapped[float] = mapped_column(Float, default=1.5)
    public: Mapped[bool] = mapped_column(Boolean, default=False)
    source_user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    source_decay_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


# ---------------------------------------------------------------- resolving

async def _user_decay(s, user_id):
    return await s.get(DailyDecay, user_id)


async def group_settings(s, gd, depth=0):
    """The settings a group decay stands for (its own, or its source's)."""
    if gd.source_user_id:
        src = await _user_decay(s, gd.source_user_id)
        if src is not None:
            return src
    if gd.source_decay_id and depth < 3:
        src = await s.get(GroupDecay, gd.source_decay_id)
        if src is not None:
            return await group_settings(s, src, depth + 1)
    return gd


async def source_user_id(s, gd, depth=0):
    """The person whose decay a group decay follows in the end (also through other groups), or None."""
    if gd.source_user_id:
        return gd.source_user_id
    if gd.source_decay_id and depth < 3:
        src = await s.get(GroupDecay, gd.source_decay_id)
        if src is not None:
            return await source_user_id(s, src, depth + 1)
    return None


async def effective(s, dd):
    """(settings object, origin) of the decay a person plays with; origin is None for their own."""
    if dd.follow_user_id:
        src = await _user_decay(s, dd.follow_user_id)
        owner = await s.get(User, dd.follow_user_id)
        if src is not None and owner is not None:
            return src, dict(kind="user", label=owner.username, username=owner.username)
    if dd.follow_decay_id:
        gd = await s.get(GroupDecay, dd.follow_decay_id)
        if gd is not None:
            conv = await s.get(Conversation, gd.group_id)
            return await group_settings(s, gd), dict(kind="group", label=conv.name if conv else "", decay_id=gd.id,
                                                     group_id=gd.group_id, name=gd.name)
    return dd, None


def tonight(src, streak_after):
    return m.applied("dec", src.base + m.catalyst(src.catalyst_mode, src.catalyst_a, src.catalyst_b, streak_after))


async def is_member(s, conv_id, user_id):
    from .db import ConversationMember
    return await s.get(ConversationMember, (conv_id, user_id)) is not None


async def can_see_group_decay(s, user_id, gd):
    if await is_member(s, gd.group_id, user_id):
        return True
    conv = await s.get(Conversation, gd.group_id)
    return bool(conv and conv.public and gd.public)


async def can_see_user_decay(s, viewer_id, owner):
    from .community import can_view
    dd = await _user_decay(s, owner.id)
    return bool(dd and dd.public and (viewer_id == owner.id or await can_view(s, viewer_id, owner)))


async def challenge_lock(s, user_id):
    """Title of a running challenge that forces my decay (can't change it until I leave)."""
    dd = await _user_decay(s, user_id)
    if dd is None or not dd.decay_challenge_id:
        return None
    ch = await s.get(Challenge, dd.decay_challenge_id)
    return ch.title if ch and ch.status in ("upcoming", "running") else None


async def unfollow_all(s, *, user_id=None, decay_id=None, keep_members_of=None):
    """Followers of a person's / a group decay fall back to their own decay (made private, deleted)."""
    from .db import ConversationMember
    q = select(DailyDecay)
    q = q.where(DailyDecay.follow_user_id == user_id) if user_id is not None else q.where(DailyDecay.follow_decay_id == decay_id)
    for dd in list(await s.scalars(q)):
        if keep_members_of is not None and await s.get(ConversationMember, (keep_members_of, dd.user_id)):
            continue
        dd.follow_user_id = dd.follow_decay_id = None
    if user_id is not None:          # group decays that followed this person lose the link (keep the values)
        for gd in list(await s.scalars(select(GroupDecay).where(GroupDecay.source_user_id == user_id))):
            src = await _user_decay(s, user_id)
            for f in SETTINGS:
                setattr(gd, f, getattr(src, f))
            gd.source_user_id = None


async def delete_group_decays(s, conv_id):
    """The group is gone: so are its decays (followers fall back to their own)."""
    for gd in list(await s.scalars(select(GroupDecay).where(GroupDecay.group_id == conv_id))):
        await unfollow_all(s, decay_id=gd.id)
        await s.execute(update(GroupDecay).where(GroupDecay.source_decay_id == gd.id).values(source_decay_id=None))
        await s.delete(gd)


async def delete_user_decay(s, user_id):
    """Account deleted: whoever followed their decay falls back to their own."""
    await unfollow_all(s, user_id=user_id)


# ---------------------------------------------------------------- challenges

async def force_follow(s, ch, user_id):
    """Challenge start / joining: its decay replaces the participant's (the previous choice is kept)."""
    if not ch.decay_id:
        return
    dd = await _user_decay(s, user_id)
    if dd is None or dd.decay_challenge_id == ch.id:
        return
    dd.prev_follow_user_id, dd.prev_follow_decay_id = dd.follow_user_id, dd.follow_decay_id
    dd.follow_user_id, dd.follow_decay_id, dd.decay_challenge_id = None, ch.decay_id, ch.id


async def release(s, ch, user_id=None):
    """Leaving / end: the previous decay comes back, unless the participant keeps the challenge's."""
    if not ch.decay_id:
        return
    rows = await s.scalars(select(ChallengeParticipant).where(ChallengeParticipant.challenge_id == ch.id))
    for p in list(rows):
        if user_id is not None and p.user_id != user_id:
            continue
        dd = await _user_decay(s, p.user_id)
        if dd is None or dd.decay_challenge_id != ch.id:
            continue
        if not p.keep_rules:
            dd.follow_user_id, dd.follow_decay_id = dd.prev_follow_user_id, dd.prev_follow_decay_id
        dd.prev_follow_user_id = dd.prev_follow_decay_id = None
        dd.decay_challenge_id = None


# ---------------------------------------------------------------- json

async def followers(s, *, user_id=None, decay_id=None):
    q = select(func.count()).select_from(DailyDecay)
    q = q.where(DailyDecay.follow_user_id == user_id) if user_id is not None else q.where(DailyDecay.follow_decay_id == decay_id)
    return await s.scalar(q) or 0


def settings_json(src):
    return dict(base=src.base, threshold=src.threshold, catalyst_mode=src.catalyst_mode,
                catalyst_a=src.catalyst_a, catalyst_b=src.catalyst_b)


async def user_decay_json(s, me, owner):
    from .social import cards
    dd = await _user_decay(s, owner.id)
    mine = await _user_decay(s, me.id)
    return dict(kind="user", username=owner.username, owner=(await cards(s, me.id, [owner]))[0],
                public=bool(dd.public), followers=await followers(s, user_id=owner.id),
                following=bool(mine and mine.follow_user_id == owner.id), is_mine=owner.id == me.id,
                can_follow=owner.id != me.id and not (mine and mine.follow_user_id == owner.id),
                locked_by=await challenge_lock(s, me.id), **settings_json(dd))


async def group_decay_json(s, me, gd):
    from .chat import is_admin
    conv = await s.get(Conversation, gd.group_id)
    member = await is_member(s, gd.group_id, me.id)
    hidden = bool(conv and not member and not conv.public)
    src = await group_settings(s, gd)
    linked = await s.get(User, gd.source_user_id) if gd.source_user_id else None
    mine = await _user_decay(s, me.id)
    following = bool(mine and mine.follow_decay_id == gd.id)
    may = await can_see_group_decay(s, me.id, gd)
    own = await source_user_id(s, gd) == me.id        # it follows my own decay: nothing to follow
    return dict(kind="group", id=gd.id, name=gd.name, group_id=gd.group_id, is_mine=own,
                group_name=None if hidden else conv.name, public=bool(gd.public),
                linked_from=linked.username if linked else None, followers=await followers(s, decay_id=gd.id),
                following=following, can_follow=not following and may and not own,
                members_only=not following and not may and not own,
                editable=bool(conv and await is_admin(s, conv, me.id) and not gd.source_user_id and not gd.source_decay_id),
                removable=bool(conv and await is_admin(s, conv, me.id)),
                locked_by=await challenge_lock(s, me.id), **settings_json(src))


# ---------------------------------------------------------------- endpoints

def register(app, authed, api_error, parse_decay, state_response):
    def name_of(data):
        name = str(data.get("name", "")).strip()[:40]
        if not name:
            raise api_error("Name fehlt", "Name missing")
        return name

    async def admin_group(conv_id):
        from .chat import is_admin
        conv = await g.s.get(Conversation, conv_id)
        if conv is None or conv.kind != "group" or not await is_member(g.s, conv_id, g.user.id):
            raise api_error("Gruppe nicht gefunden", "Group not found", 404)
        if not await is_admin(g.s, conv, g.user.id):
            raise api_error("Das dürfen nur Admins der Gruppe", "Only the group's admins can do that", 403)
        return conv

    async def get_group_decay(decay_id):
        gd = await g.s.get(GroupDecay, decay_id)
        if gd is None or not (await can_see_group_decay(g.s, g.user.id, gd) or
                              (await _user_decay(g.s, g.user.id)).follow_decay_id == gd.id):
            raise api_error("Decay nicht gefunden", "Decay not found", 404)
        return gd

    async def user_with_public_decay(username):
        owner = await g.s.scalar(select(User).where(User.username == str(username or "").lower()))
        if owner is None or not await can_see_user_decay(g.s, g.user.id, owner):
            raise api_error("Decay nicht gefunden", "Decay not found", 404)
        return owner

    @app.put("/api/decay")
    @authed
    async def update_decay():
        data = await request.get_json(force=True) or {}
        dd = g.user.decay
        for k, v in parse_decay(data).items():
            setattr(dd, k, v)
        if "public" in data:
            was, dd.public = bool(dd.public), bool(data["public"])
            if was and not dd.public:                 # private again: followers fall back to their own
                await unfollow_all(g.s, user_id=g.user.id)
        return await state_response()

    @app.post("/api/decay/follow")
    @authed
    async def follow_decay():
        data = await request.get_json(force=True) or {}
        dd = g.user.decay
        if await challenge_lock(g.s, g.user.id):
            raise api_error("Eine Challenge legt deinen Decay fest – erst nach dem Austritt änderbar",
                            "A challenge sets your decay – change it after leaving")
        if data.get("username"):
            owner = await user_with_public_decay(data["username"])
            if owner.id == g.user.id:
                raise api_error("Das ist dein eigener Decay", "That's your own decay")
            if dd.follow_user_id != owner.id:
                await notify_once(g.s, owner.id, g.user.id, "sub_decay")            # report #54
            dd.follow_user_id, dd.follow_decay_id = owner.id, None
        elif data.get("decay_id") is not None:
            gd = await get_group_decay(int(data["decay_id"]))
            if not await can_see_group_decay(g.s, g.user.id, gd):
                raise api_error("Folgen können nur Mitglieder der Gruppe", "Only the group's members can follow it")
            if await source_user_id(g.s, gd) == g.user.id:
                raise api_error("Das ist dein eigener Decay", "That's your own decay")
            dd.follow_user_id, dd.follow_decay_id = None, gd.id
        else:
            raise api_error("Welcher Decay?", "Which decay?")
        return await state_response()

    @app.delete("/api/decay/follow")
    @authed
    async def unfollow_decay():
        if await challenge_lock(g.s, g.user.id):
            raise api_error("Eine Challenge legt deinen Decay fest – erst nach dem Austritt änderbar",
                            "A challenge sets your decay – change it after leaving")
        dd = g.user.decay
        dd.follow_user_id = dd.follow_decay_id = None
        return await state_response()

    @app.get("/api/decays/user/<username>")
    @authed
    async def user_decay(username):
        return jsonify(decay=await user_decay_json(g.s, g.user, await user_with_public_decay(username)))

    @app.get("/api/decays/<int:decay_id>")
    @authed
    async def group_decay(decay_id):
        return jsonify(decay=await group_decay_json(g.s, g.user, await get_group_decay(decay_id)))

    @app.get("/api/conversations/<int:conv_id>/decays")
    @authed
    async def group_decays(conv_id):
        conv = await g.s.get(Conversation, conv_id)
        member = conv is not None and await is_member(g.s, conv_id, g.user.id)
        if conv is None or conv.kind != "group" or not (member or conv.public):
            raise api_error("Gruppe nicht gefunden", "Group not found", 404)
        rows = await g.s.scalars(select(GroupDecay).where(GroupDecay.group_id == conv_id).order_by(GroupDecay.id))
        return jsonify(decays=[await group_decay_json(g.s, g.user, gd) for gd in rows if member or gd.public])

    @app.post("/api/conversations/<int:conv_id>/decays")
    @authed
    async def add_group_decay(conv_id):
        await admin_group(conv_id)
        data = await request.get_json(force=True) or {}
        gd = GroupDecay(group_id=conv_id, created_by=g.user.id)
        if data.get("from_user"):                   # follow a person's public decay
            owner = await user_with_public_decay(data["from_user"])
            src = await _user_decay(g.s, owner.id)
            gd.source_user_id, gd.name, gd.public = owner.id, owner.username, True
            for f in SETTINGS:
                setattr(gd, f, getattr(src, f))
            await notify_once(g.s, owner.id, g.user.id, "sub_decay", group_id=conv_id)
        elif data.get("from_decay") is not None:    # follow another group's public decay
            other = await g.s.get(GroupDecay, int(data["from_decay"]))
            if other is None or not await can_see_group_decay(g.s, g.user.id, other) or other.group_id == conv_id:
                raise api_error("Decay nicht gefunden", "Decay not found", 404)
            gd.source_decay_id, gd.name, gd.public = other.id, other.name, True
            src = await group_settings(g.s, other)            # its values stay if the original goes away
            for f in SETTINGS:
                setattr(gd, f, getattr(src, f))
        else:
            gd.name = name_of(data)
            gd.public = bool(data.get("public", False))
            for k, v in parse_decay(data).items():
                setattr(gd, k, v)
        g.s.add(gd)
        await g.s.flush()
        return jsonify(decay=await group_decay_json(g.s, g.user, gd))

    @app.put("/api/conversations/<int:conv_id>/decays/<int:decay_id>")
    @authed
    async def edit_group_decay(conv_id, decay_id):
        await admin_group(conv_id)
        gd = await g.s.get(GroupDecay, decay_id)
        if gd is None or gd.group_id != conv_id:
            raise api_error("Decay nicht gefunden", "Decay not found", 404)
        if gd.source_user_id or gd.source_decay_id:
            raise api_error("Dieser Decay folgt seinem Original", "This decay follows its original")
        data = await request.get_json(force=True) or {}
        if "name" in data:
            gd.name = name_of(data)
        for k, v in parse_decay(data).items():
            setattr(gd, k, v)
        if "public" in data:
            was, gd.public = bool(gd.public), bool(data["public"])
            if was and not gd.public:               # private again: only members keep following it
                await unfollow_all(g.s, decay_id=gd.id, keep_members_of=conv_id)
        return jsonify(decay=await group_decay_json(g.s, g.user, gd))

    @app.delete("/api/conversations/<int:conv_id>/decays/<int:decay_id>")
    @authed
    async def delete_group_decay(conv_id, decay_id):
        await admin_group(conv_id)
        gd = await g.s.get(GroupDecay, decay_id)
        if gd is None or gd.group_id != conv_id:
            raise api_error("Decay nicht gefunden", "Decay not found", 404)
        if await g.s.scalar(select(func.count()).select_from(Challenge).where(
                Challenge.decay_id == gd.id, Challenge.status.in_(("upcoming", "running")))):
            raise api_error("Eine laufende Challenge nutzt diesen Decay", "A running challenge uses this decay")
        await unfollow_all(g.s, decay_id=gd.id)
        await g.s.execute(update(GroupDecay).where(GroupDecay.source_decay_id == gd.id).values(source_decay_id=None))
        await g.s.delete(gd)
        return jsonify(ok=True)
