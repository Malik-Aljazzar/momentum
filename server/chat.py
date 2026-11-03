"""Direct messages and group chats. Clients poll for new messages (prototype)."""
import time
from datetime import timedelta

from quart import current_app, g, jsonify, request
from sqlalchemy import delete, func, or_, select, update

from .clients import hidden_message_kinds
from .db import Conversation, ConversationMember, Media, Message, MessageReaction, Post, Rule, User, utcnow
from .e2e import KeyChanged, StaleKeys, envelope_for, keys_hash, mentioned_ids, parse_envelope
from .mentions import encode, names_for, render, render_one
from .posts import media_json, post_thumbs
from .social import block_ids, card_map, connected, iso

MAX_TEXT = 2000
PAGE = 50


async def unread_messages(s, user_id):
    """Messages from others newer than my read marker, across all conversations (muted ones only
    when they tag me)."""
    return await s.scalar(
        select(func.count()).select_from(Message)
        .join(ConversationMember, (ConversationMember.conversation_id == Message.conversation_id)
              & (ConversationMember.user_id == user_id))
        .where(Message.id > ConversationMember.last_read_id, Message.user_id != user_id, Message.kind.is_(None),
               ConversationMember.muted.is_(False) | mentions_of(user_id)))


async def _members(s, conv_id):
    return list(await s.scalars(select(ConversationMember.user_id).where(ConversationMember.conversation_id == conv_id)))


async def _member_keys(s, conv_id):
    """{user id: public key or None} of a chat's members (server/e2e.py)."""
    rows = (await s.execute(select(User.id, User.e2e_public).join(ConversationMember, ConversationMember.user_id == User.id)
                            .where(ConversationMember.conversation_id == conv_id))).all()
    return {uid: pub for uid, pub in rows}


SHARE_KINDS = ("rule", "profile", "post", "story")

# "X schreibt..." / "X nimmt eine Sprachnachricht auf...": kept in memory for a few seconds (one server
# process); clients repeat it while typing / recording and the poll of the chat (and the chat list) brings it
TYPING_SECONDS = 6
_typing: dict[int, dict[int, tuple[str, float]]] = {}


def set_typing(conv_id, user_id, kind):
    users = _typing.setdefault(conv_id, {})
    if kind in ("text", "audio"):
        users[user_id] = (kind, time.monotonic() + TYPING_SECONDS)
    else:
        users.pop(user_id, None)


async def typing_json(s, conv_id, me_id):
    """Who else is typing / recording right now: [{"username", "kind"}]."""
    now = time.monotonic()
    users = _typing.get(conv_id, {})
    for uid in [u for u, (_, until) in users.items() if until < now]:
        del users[uid]
    active = {u: k for u, (k, _) in users.items() if u != me_id}
    if not active:
        return []
    names = dict((await s.execute(select(User.id, User.username).where(User.id.in_(active)))).all())
    return [dict(username=names[u], kind=k) for u, k in sorted(active.items()) if u in names]


def e2e_default():
    """New chats are encrypted (create_app(e2e_default=False): the tests' plain chats)."""
    return bool(current_app.config.get("E2E_DEFAULT", True))


async def direct_conversation(s, me_id, other_id):
    """The DM between two people, created if there's none yet."""
    mine = select(ConversationMember.conversation_id).where(ConversationMember.user_id == me_id)
    theirs = select(ConversationMember.conversation_id).where(ConversationMember.user_id == other_id)
    conv = await s.scalar(select(Conversation).where(
        Conversation.kind == "direct", Conversation.id.in_(mine), Conversation.id.in_(theirs)))
    if conv is None:
        conv = Conversation(kind="direct", created_by=me_id, e2e=e2e_default())
        s.add(conv)
        await s.flush()
        s.add_all([ConversationMember(conversation_id=conv.id, user_id=u) for u in (me_id, other_id)])
        await s.flush()
    return conv


async def resolve_share(s, me, kind, ref):
    """A shared item as the viewer may see it (available=False: deleted or not visible)."""
    from . import challenges, community
    try:
        ref_id = int(ref)
    except (TypeError, ValueError):
        return dict(kind=kind, available=False)
    if kind == "rule":
        rule = await s.get(Rule, ref_id)
        if rule is None:
            return dict(kind=kind, available=False)
        owner = await s.get(User, rule.user_id)
        return dict(kind=kind, id=rule.id, name=rule.name, icon=rule.icon, rule_kind=rule.kind, mode=rule.mode,
                    owner=owner.username if owner else "", available=await community.can_view_rule(s, me, rule))
    if kind == "profile":
        user = await s.get(User, ref_id)
        if user is None:
            return dict(kind=kind, available=False)
        return dict(kind=kind, user=(await card_map(s, me.id, [user.id]))[user.id], available=True)
    if kind == "post":
        post = await s.get(Post, ref_id)
        if post is None or not await community.can_view_post(s, me.id, post):
            return dict(kind=kind, available=False)
        owner = (await card_map(s, me.id, [post.user_id])).get(post.user_id)
        media = list(await s.scalars(select(Media).where(Media.post_id == post.id).order_by(Media.position)))
        return dict(kind=kind, id=post.id, post_owner=owner, caption=(await render_one(s, post.text))[:120], post_kind=post.kind, vehicle=post.vehicle,
                    thumb=(await post_thumbs(s, [post.id])).get(post.id), available=True,
                    media_count=len(media), video=bool(media and media[0].kind == "video"),
                    ratio=(media[0].width / media[0].height) if media and media[0].height else 1.0)
    if kind == "story":
        from .stories import Story, story_visible
        st = await s.get(Story, ref_id)
        md = await s.get(Media, st.media_id) if st is not None else None
        # the author's privacy counts for every reader: followers only, blocks, gone after 24 h
        if st is None or md is None or not await story_visible(s, me.id, st):
            return dict(kind=kind, available=False)
        return dict(kind=kind, id=st.id, user=(await card_map(s, me.id, [st.user_id])).get(st.user_id),
                    thumb=f"/{md.thumb_key or md.key}", video=md.kind == "video",
                    ratio=(md.width / md.height) if md.height else 0.5625, available=True)
    if kind == "challenge":
        ch = await s.get(challenges.Challenge, ref_id)
        if ch is None:
            return dict(kind=kind, available=False)
        return dict(kind=kind, id=ch.id, challenge=await challenges.challenge_json(s, me.id, ch, full=False), available=True)
    return dict(kind=kind, available=False)


# the emojis offered on a long press (double tap = the first one)
REACTIONS = ("❤️", "😂", "😮", "😢", "🔥", "👍")


async def reactions_for(s, me_id, message_ids):
    """{message id: [{emoji, count, mine}]} - most used first."""
    out = {}
    if not message_ids:
        return out
    for r in await s.scalars(select(MessageReaction).where(MessageReaction.message_id.in_(set(message_ids)))
                             .order_by(MessageReaction.created_at)):
        items = out.setdefault(r.message_id, {})
        e = items.setdefault(r.emoji, dict(emoji=r.emoji, count=0, mine=False))
        e["count"] += 1
        e["mine"] = e["mine"] or r.user_id == me_id
    return {mid: sorted(v.values(), key=lambda e: -e["count"]) for mid, v in out.items()}


async def delete_reactions(s, message_ids_query):
    await s.execute(delete(MessageReaction).where(MessageReaction.message_id.in_(message_ids_query)))


# chat photos: after two weeks the full picture is deleted, the small copy stays (report #48)
CHAT_IMAGE_DAYS = 14


async def archive_chat_images(s, blobs, now=None):
    """Chat photos older than CHAT_IMAGE_DAYS keep only their 480 px thumbnail. Returns how many."""
    cutoff = (now or utcnow()) - timedelta(days=CHAT_IMAGE_DAYS)
    old = list(await s.scalars(select(Media).join(Message, Message.media_id == Media.id).where(
        Media.kind == "image", Media.archived.is_(False), Message.created_at < cutoff).limit(500)))
    for md in old:
        if md.thumb_key and md.thumb_key != md.key:
            await blobs.delete(md.key)
            md.key = md.thumb_key
        md.archived = True
    await s.flush()
    return len(old)


def image_expiry(mm, md):
    """When a chat photo loses its full picture (None: not a photo / already archived)."""
    if md is None or md.kind != "image":
        return {}
    if md.archived:
        return dict(archived=True)
    return dict(expires_at=iso(mm.created_at + timedelta(days=CHAT_IMAGE_DAYS)), archived=False)


async def member_line(s, conv, actor_id, kind, target_id=None):
    """A line in the group's chat: joined / added / removed / left (report #56) - not unread, no push."""
    msg = Message(conversation_id=conv.id, user_id=actor_id, text="", kind=kind, target_id=target_id)
    s.add(msg)
    conv.last_message_at = utcnow()
    await s.flush()
    actor = await s.get(ConversationMember, (conv.id, actor_id))
    if actor is not None:
        actor.last_read_id = msg.id


async def _messages_json(s, me_id, msgs):
    hidden = hidden_message_kinds()
    msgs = [mm for mm in msgs if mm.kind not in hidden]
    reactions = await reactions_for(s, me_id, [mm.id for mm in msgs])
    me = await s.get(User, me_id)
    shares = {mm.id: await resolve_share(s, me, mm.share_kind, mm.share_ref) for mm in msgs if mm.share_kind}
    people = await card_map(s, me_id, {mm.user_id for mm in msgs} | {mm.target_id for mm in msgs if mm.target_id})
    media_ids = [mm.media_id for mm in msgs if mm.media_id]
    rows = {md.id: md for md in await s.scalars(select(Media).where(Media.id.in_(media_ids)))} if media_ids else {}
    media = {mm.id: media_json(rows[mm.media_id]) | image_expiry(mm, rows[mm.media_id]) for mm in msgs if mm.media_id in rows}
    # answers quote the message they reply to
    replied = {r.id: r for r in await s.scalars(select(Message).where(
        Message.id.in_({mm.reply_to_id for mm in msgs if mm.reply_to_id})))} if any(mm.reply_to_id for mm in msgs) else {}
    reply_media = {md.id: md.kind for md in await s.scalars(select(Media).where(
        Media.id.in_({r.media_id for r in replied.values() if r.media_id})))} if replied else {}
    people.update(await card_map(s, me_id, {r.user_id for r in replied.values()} - set(people)))
    names = await names_for(s, [mm.text for mm in msgs] + [r.text for r in replied.values()])

    def reply(mm):
        r = replied.get(mm.reply_to_id)
        if r is None:
            return None
        return dict(id=r.id, user=(people.get(r.user_id) or {}).get("username", ""), mine=r.user_id == me_id,
                    text=render(r.text, names)[:120], media_kind=reply_media.get(r.media_id), share_kind=r.share_kind,
                    e2e=envelope_for(r, me_id, full=False))

    # encrypted: "e2e" is the envelope for me (the client opens it), text stays empty; "kind": a chat line
    return [dict(id=mm.id, text=render(mm.text, names), at=iso(mm.created_at), user=people.get(mm.user_id),
                 mine=mm.user_id == me_id, media=media.get(mm.id), share=shares.get(mm.id),
                 mentions_me=mm.user_id != me_id and me_id in mentioned_ids(mm), reply=reply(mm),
                 reactions=reactions.get(mm.id, []), e2e=envelope_for(mm, me_id), kind=mm.kind,
                 **({"target": people.get(mm.target_id), "target_me": mm.target_id == me_id} if mm.target_id else {}))
            for mm in msgs]


def mentions_of(user_id):
    """Messages that tag this user ("@[id]" reference in a plain text, or named by the sender of an encrypted one)."""
    return or_(Message.text.contains(f"@[{user_id}]"), Message.mentions.contains(f",{user_id},"))


async def _conversation_json(s, me_id, conv, member_ids, read_marker):
    me_member = await s.get(ConversationMember, (conv.id, me_id))
    auto_follow = bool(me_member and me_member.auto_follow)
    people = await card_map(s, me_id, member_ids)
    others = [people[u] for u in member_ids if u != me_id and u in people]
    q = select(Message).where(Message.conversation_id == conv.id)
    if hidden := hidden_message_kinds():
        q = q.where(or_(Message.kind.is_(None), Message.kind.not_in(hidden)))
    last = await s.scalar(q.order_by(Message.id.desc()).limit(1))
    unread = await s.scalar(select(func.count()).select_from(Message).where(
        Message.conversation_id == conv.id, Message.id > read_marker, Message.user_id != me_id, Message.kind.is_(None)))
    # unread messages that tag me: highlighted in the chat list
    mentioned = bool(unread) and bool(await s.scalar(select(func.count()).select_from(Message).where(
        Message.conversation_id == conv.id, Message.id > read_marker, Message.user_id != me_id, mentions_of(me_id))))
    last_json = None
    if last:
        last_media = await s.get(Media, last.media_id) if last.media_id else None
        if last.user_id not in people or last.target_id:      # someone who left / was removed
            people.update(await card_map(s, me_id, {last.user_id, last.target_id} - {None} - set(people)))
        last_json = dict(text=await render_one(s, last.text), has_media=last.media_id is not None, mine=last.user_id == me_id,
                         media_kind=last_media.kind if last_media else None,
                         share_kind=last.share_kind,
                         sender=people[last.user_id]["display_name"] if last.user_id in people else "",
                         at=iso(last.created_at), kind=last.kind, e2e=envelope_for(last, me_id, full=False),
                         **({"target": people.get(last.target_id), "target_me": last.target_id == me_id} if last.target_id else {}))
    images = {}
    for field, media_id in (("avatar", conv.avatar_media_id), ("banner_image", conv.banner_media_id)):
        md = await s.get(Media, media_id) if media_id else None
        images[field] = f"/{md.thumb_key or md.key}" if md and field == "avatar" else f"/{md.key}" if md else None
    # the group's total (competitive) momentum
    total = await s.scalar(select(func.coalesce(func.sum(User.momentum), 0.0)).where(User.id.in_(member_ids))) \
        if conv.kind == "group" else 0.0
    return dict(id=conv.id, kind=conv.kind, name=conv.name, members=others, unread=unread, last=last_json, mentioned=mentioned,
                avatar=images["avatar"], banner_image=images["banner_image"], total_momentum=total or 0.0,
                is_admin=await is_admin(s, conv, me_id), anarchy=bool(conv.anarchy),
                typing=await typing_json(s, conv.id, me_id),
                admins=[u for u in await admin_names(s, conv)] if conv.kind == "group" else [],
                banner=conv.banner, auto_follow=auto_follow,
                public=bool(conv.public), open=bool(conv.open), member=me_member is not None,
                muted=bool(me_member and me_member.muted), e2e=bool(conv.e2e),
                # who may switch the encryption: both in a direct chat, the admins of a group
                can_switch_e2e=me_member is not None and (conv.kind == "direct" or await is_admin(s, conv, me_id)))


def register(app, authed, api_error):
    async def read_upto(conv_id):
        """Read receipts: my messages up to this id were read by everyone else in the chat."""
        others = list(await g.s.scalars(select(ConversationMember.last_read_id).where(
            ConversationMember.conversation_id == conv_id, ConversationMember.user_id != g.user.id)))
        return min(others) if others else 0

    async def membership(conv_id):
        conv = await g.s.get(Conversation, conv_id)
        member = await g.s.get(ConversationMember, (conv_id, g.user.id)) if conv else None
        if member is None:
            raise api_error("Chat nicht gefunden", "Chat not found", 404)
        return conv, member

    async def name_taken(name, except_id=None):
        """Group names are unique, like usernames (not case-sensitive)."""
        q = select(Conversation.id).where(Conversation.kind == "group", func.lower(Conversation.name) == name.lower())
        if except_id is not None:
            q = q.where(Conversation.id != except_id)
        return await g.s.scalar(q.limit(1)) is not None

    async def users_by_name(usernames):
        names = {str(u).lower() for u in usernames}
        return list(await g.s.scalars(select(User).where(User.username.in_(names)))) if names else []

    async def conv_response(conv):
        member = await g.s.get(ConversationMember, (conv.id, g.user.id))
        return jsonify(conversation=await _conversation_json(
            g.s, g.user.id, conv, await _members(g.s, conv.id), member.last_read_id if member else 0))

    async def viewable(conv_id):
        """Members, or anyone for a public group's overview (not its messages)."""
        conv = await g.s.get(Conversation, conv_id)
        if conv is not None and (await g.s.get(ConversationMember, (conv_id, g.user.id))
                                 or (conv.kind == "group" and conv.public)):
            return conv
        raise api_error("Chat nicht gefunden", "Chat not found", 404)

    async def valid_share(share):
        """Only things the sender can see may be shared."""
        from . import community
        kind = str((share or {}).get("kind", ""))
        try:
            ref = int((share or {}).get("id"))
        except (TypeError, ValueError):
            ref = None
        ok = False
        if kind == "rule" and ref:
            rule = await g.s.get(Rule, ref)
            ok = rule is not None and await community.can_view_rule(g.s, g.user, rule)
            if ok:
                ref = (await community.root_of(g.s, rule)).id      # a subscribed copy shares its original
        elif kind == "post" and ref:
            post = await g.s.get(Post, ref)
            ok = post is not None and await community.can_view_post(g.s, g.user.id, post)
        elif kind == "story" and ref:
            from .stories import Story, story_visible
            st = await g.s.get(Story, ref)
            ok = st is not None and await story_visible(g.s, g.user.id, st)
        elif kind == "profile":
            name = str((share or {}).get("username", "")).lower()
            user = await g.s.scalar(select(User).where(User.username == name)) if name else await g.s.get(User, ref or 0)
            ok = user is not None and user.username_set
            ref = user.id if ok else None
        if not ok:
            raise api_error("Kann nicht geteilt werden", "Can't be shared", 404)
        return kind, str(ref)

    @app.get("/api/conversations")
    @authed
    async def conversations():
        rows = (await g.s.execute(
            select(Conversation, ConversationMember.last_read_id)
            .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
            .where(ConversationMember.user_id == g.user.id)
            .order_by(Conversation.last_message_at.desc()))).all()
        out = [await _conversation_json(g.s, g.user.id, c, await _members(g.s, c.id), read) for c, read in rows]
        return jsonify(conversations=out)

    @app.post("/api/conversations/direct")
    @authed
    async def open_direct():
        """Get or create the DM with a user. Allowed if either follows the other."""
        data = await request.get_json(force=True) or {}
        other = (await users_by_name([data.get("username", "")]) or [None])[0]
        if other is None or other.id == g.user.id:
            raise api_error("Nutzer nicht gefunden", "User not found", 404)
        if not await connected(g.s, g.user.id, other.id):
            raise api_error("Folgt euch zuerst", "Follow each other first", 403)
        return await conv_response(await direct_conversation(g.s, g.user.id, other.id))

    @app.post("/api/conversations/group")
    @authed
    async def create_group():
        data = await request.get_json(force=True) or {}
        name = str(data.get("name", "")).strip()
        if not 1 <= len(name) <= 60:
            raise api_error("Gruppenname: 1-60 Zeichen", "Group name: 1-60 characters")
        if await name_taken(name):
            raise api_error("Den Gruppennamen gibt es schon", "That group name is taken")
        users = [u for u in await users_by_name(data.get("usernames", []))
                 if u.id != g.user.id and await connected(g.s, g.user.id, u.id)]
        if not users:
            raise api_error("Wähle mindestens eine Person", "Pick at least one person")
        conv = Conversation(kind="group", name=name, created_by=g.user.id, e2e=e2e_default())    # its creator is the first admin
        g.s.add(conv)
        await g.s.flush()
        g.s.add_all([ConversationMember(conversation_id=conv.id, user_id=u, admin=u == g.user.id)
                     for u in [g.user.id, *(u.id for u in users)]])
        await g.s.flush()
        return await conv_response(conv)

    @app.get("/api/conversations/<int:conv_id>")
    @authed
    async def get_conversation(conv_id):
        return await conv_response(await viewable(conv_id))

    @app.get("/api/groups/search")
    @authed
    async def search_groups():
        """Public groups by name."""
        q = request.args.get("q", "").strip().lower()
        if not q:
            return jsonify(groups=[])
        rows = list(await g.s.scalars(select(Conversation).where(
            Conversation.kind == "group", Conversation.public.is_(True),
            func.lower(Conversation.name).like(f"%{q}%")).order_by(Conversation.last_message_at.desc()).limit(30)))
        out = []
        for conv in rows:
            ids = await _members(g.s, conv.id)
            out.append(dict(id=conv.id, name=conv.name, banner=conv.banner, open=bool(conv.open),
                            members=len(ids), member=g.user.id in ids))
        return jsonify(groups=out)

    @app.put("/api/conversations/<int:conv_id>/mute")
    @authed
    async def mute(conv_id):
        """{"muted": bool}: no notifications for this chat (mentions still come through)."""
        conv, member = await membership(conv_id)
        member.muted = bool((await request.get_json(force=True) or {}).get("muted"))
        await g.s.flush()
        return await conv_response(conv)

    @app.post("/api/conversations/<int:conv_id>/join")
    @authed
    async def join_group(conv_id):
        """Open groups: anyone may join."""
        conv = await g.s.get(Conversation, conv_id)
        if conv is None or conv.kind != "group" or not conv.public:
            raise api_error("Gruppe nicht gefunden", "Group not found", 404)
        if not await g.s.get(ConversationMember, (conv_id, g.user.id)):
            if not conv.open:
                raise api_error("Diese Gruppe ist geschlossen", "This group is closed", 403)
            g.s.add(ConversationMember(conversation_id=conv.id, user_id=g.user.id))
            await g.s.flush()
            await member_line(g.s, conv, g.user.id, "member_join")
        return await conv_response(conv)

    @app.get("/api/conversations/<int:conv_id>/ranking")
    @authed
    async def group_ranking(conv_id):
        """The members ranked by competitive momentum (?period=24h: by the change of the last 24 hours)."""
        from .social import ranking_rows
        conv = await viewable(conv_id)
        users = list(await g.s.scalars(select(User).where(User.id.in_(await _members(g.s, conv.id)))))
        from .social import period_arg
        period = period_arg(request.args.get("period"))
        return jsonify(entries=await ranking_rows(g.s, g.user.id, users, period))

    @app.put("/api/conversations/<int:conv_id>")
    @authed
    async def rename_group(conv_id):
        """The group's settings (name, banner, pictures, public / open, anarchy): admins only."""
        from .service import parse_banner
        conv, _ = await membership(conv_id)
        data = await request.get_json(force=True) or {}
        if conv.kind != "group":
            raise api_error("Nur in Gruppen möglich", "Only possible in groups")
        await require_admin(conv)
        if "anarchy" in data:
            conv.anarchy = bool(data["anarchy"])
        if "name" in data:
            name = str(data.get("name") or "").strip()
            if not 1 <= len(name) <= 60:
                raise api_error("Gruppenname: 1-60 Zeichen", "Group name: 1-60 characters")
            if await name_taken(name, except_id=conv.id):
                raise api_error("Den Gruppennamen gibt es schon", "That group name is taken")
            conv.name = name
        if "banner" in data:
            try:
                conv.banner = parse_banner(data["banner"])
            except ValueError:
                raise api_error("Banner unbekannt", "Unknown banner")
        for field, attr in (("avatar_media", "avatar_media_id"), ("banner_media", "banner_media_id")):
            if field not in data:
                continue
            if data[field] is None:                  # removed: back to the pixel banner / initials
                setattr(conv, attr, None)
                continue
            try:
                media = await g.s.get(Media, int(data[field]))
            except (TypeError, ValueError):
                media = None
            if media is None or media.user_id != g.user.id or media.kind != "image" or media.attached:
                raise api_error("Bild ungültig", "Invalid image")
            media.attached = True                    # kept (not cleaned up as an abandoned upload)
            setattr(conv, attr, media.id)
        if "public" in data or "open" in data:
            conv.public = bool(data.get("public", conv.public))
            conv.open = bool(data.get("open", conv.open)) and conv.public      # open only when public
        return await conv_response(conv)

    async def require_admin(conv):
        if not await is_admin(g.s, conv, g.user.id):
            raise api_error("Das dürfen nur Admins der Gruppe", "Only the group's admins can do that", 403)

    @app.put("/api/conversations/<int:conv_id>/admins")
    @authed
    async def set_admin(conv_id):
        """{"username": ..., "admin": bool}: admins make others admins (or not); one admin always stays."""
        conv, _ = await membership(conv_id)
        if conv.kind != "group":
            raise api_error("Nur in Gruppen möglich", "Only possible in groups")
        await require_admin(conv)
        data = await request.get_json(force=True) or {}
        target = (await users_by_name([data.get("username", "")]) or [None])[0]
        member = await g.s.get(ConversationMember, (conv.id, target.id)) if target else None
        if member is None:
            raise api_error("Kein Mitglied der Gruppe", "Not a member of the group", 404)
        make = bool(data.get("admin"))
        if not make and member.admin:
            others = await g.s.scalar(select(func.count()).select_from(ConversationMember).where(
                ConversationMember.conversation_id == conv.id, ConversationMember.admin.is_(True),
                ConversationMember.user_id != member.user_id))
            if not others:
                raise api_error("Es muss immer einen Admin geben", "There must always be an admin")
        member.admin = make
        await g.s.flush()
        return await conv_response(conv)

    @app.post("/api/conversations/<int:conv_id>/members")
    @authed
    async def add_members(conv_id):
        conv, _ = await membership(conv_id)
        if conv.kind != "group":
            raise api_error("Nur in Gruppen möglich", "Only possible in groups")
        await require_admin(conv)
        current = set(await _members(g.s, conv.id))
        for u in await users_by_name((await request.get_json(force=True) or {}).get("usernames", [])):
            if u.id not in current and await connected(g.s, g.user.id, u.id):
                g.s.add(ConversationMember(conversation_id=conv.id, user_id=u.id))
                await g.s.flush()
                await member_line(g.s, conv, g.user.id, "member_add", u.id)
        return await conv_response(conv)

    @app.delete("/api/conversations/<int:conv_id>/members/<username>")
    @authed
    async def remove_member(conv_id, username):
        """An admin removes someone from the group (report #56); leaving yourself is members/me."""
        from . import challenges
        conv, _ = await membership(conv_id)
        if conv.kind != "group":
            raise api_error("Nur in Gruppen möglich", "Only possible in groups")
        await require_admin(conv)
        target = (await users_by_name([username]) or [None])[0]
        member = await g.s.get(ConversationMember, (conv.id, target.id)) if target else None
        if member is None:
            raise api_error("Kein Mitglied der Gruppe", "Not a member of the group", 404)
        if target.id == g.user.id:
            raise api_error("Verlasse die Gruppe stattdessen", "Leave the group instead")
        await challenges.leave_group(g.s, conv.id, target.id)
        await g.s.delete(member)
        await g.s.flush()
        await keep_an_admin(g.s, conv.id)
        await member_line(g.s, conv, g.user.id, "member_remove", target.id)
        return await conv_response(conv)

    @app.delete("/api/conversations/<int:conv_id>/members/me")
    @authed
    async def leave(conv_id):
        from . import challenges
        conv, member = await membership(conv_id)
        if conv.kind == "group":
            await challenges.leave_group(g.s, conv.id, g.user.id)
        was_admin = member.admin
        await g.s.delete(member)
        await g.s.flush()
        if conv.kind == "group" and was_admin:
            await keep_an_admin(g.s, conv.id)
        if conv.kind == "group" and await _members(g.s, conv.id):
            await member_line(g.s, conv, g.user.id, "member_leave")
        if not await _members(g.s, conv.id) or conv.kind == "direct":
            await challenges.delete_group(g.s, conv.id)
            await g.s.execute(delete(ConversationMember).where(ConversationMember.conversation_id == conv.id))
            await delete_reactions(g.s, select(Message.id).where(Message.conversation_id == conv.id))
            await g.s.execute(delete(Message).where(Message.conversation_id == conv.id))
            await g.s.delete(conv)
        return jsonify(ok=True)

    @app.get("/api/conversations/<int:conv_id>/keys")
    @authed
    async def member_keys(conv_id):
        """The members' public keys: a new message is encrypted for each of them (null: no key yet).
        "hash": their fingerprint, sent back with the message as "kh" (stale -> 409, load again)."""
        conv, _ = await membership(conv_id)
        rows = (await g.s.execute(select(User.id, User.username, User.e2e_public)
                                  .join(ConversationMember, ConversationMember.user_id == User.id)
                                  .where(ConversationMember.conversation_id == conv.id))).all()
        return jsonify(keys=[dict(user_id=u, username=name, public=pub) for u, name, pub in rows],
                       hash=keys_hash({u: pub for u, _, pub in rows}))

    @app.put("/api/conversations/<int:conv_id>/e2e")
    @authed
    async def switch_e2e(conv_id):
        """{"on": bool}: end-to-end encryption of new messages - both in a direct chat, a group's admins.
        The chat shows who switched it."""
        conv, member = await membership(conv_id)
        if conv.kind == "group":
            await require_admin(conv)
        on = bool((await request.get_json(force=True) or {}).get("on"))
        if bool(conv.e2e) != on:
            conv.e2e = on
            msg = Message(conversation_id=conv.id, user_id=g.user.id, text="", kind="e2e_on" if on else "e2e_off")
            g.s.add(msg)
            conv.last_message_at = utcnow()
            await g.s.flush()
            member.last_read_id = msg.id
        return await conv_response(conv)

    @app.post("/api/conversations/<int:conv_id>/typing")
    @authed
    async def typing(conv_id):
        """{"kind": "text" | "audio" | null}: the others see it for a few seconds (repeat it while typing)."""
        await membership(conv_id)
        set_typing(conv_id, g.user.id, (await request.get_json(force=True) or {}).get("kind"))
        return jsonify(ok=True)

    @app.get("/api/conversations/<int:conv_id>/messages")
    @authed
    async def messages(conv_id):
        """?after=<id> for polling new ones, ?before=<id> for older history. Marks as read."""
        conv, member = await membership(conv_id)
        q = select(Message).where(Message.conversation_id == conv.id)
        after, before = request.args.get("after", type=int), request.args.get("before", type=int)
        if after:
            msgs = list(await g.s.scalars(q.where(Message.id > after).order_by(Message.id).limit(PAGE)))
        else:
            if before:
                q = q.where(Message.id < before)
            msgs = list(reversed(list(await g.s.scalars(q.order_by(Message.id.desc()).limit(PAGE)))))
        if msgs and msgs[-1].id > member.last_read_id:
            member.last_read_id = msgs[-1].id
        # polling only brings new messages: reactions of the latest ones come along to stay current
        recent = list(await g.s.scalars(select(Message.id).where(Message.conversation_id == conv.id)
                                        .order_by(Message.id.desc()).limit(100)))
        return jsonify(messages=await _messages_json(g.s, g.user.id, msgs), read_upto=await read_upto(conv.id),
                       typing=await typing_json(g.s, conv.id, g.user.id),
                       reactions={str(k): v for k, v in (await reactions_for(g.s, g.user.id, recent)).items()},
                       reacted_ids=recent)

    @app.get("/api/messages/<int:message_id>/readers")
    @authed
    async def readers(message_id):
        """Who read this message (report #44): the other members whose read marker reached it."""
        msg = await g.s.get(Message, message_id)
        if msg is None:
            raise api_error("Nachricht nicht gefunden", "Message not found", 404)
        await membership(msg.conversation_id)
        rows = list(await g.s.scalars(select(ConversationMember).where(
            ConversationMember.conversation_id == msg.conversation_id, ConversationMember.user_id != msg.user_id)))
        read = [m.user_id for m in rows if m.last_read_id >= msg.id]
        people = await card_map(g.s, g.user.id, read)
        return jsonify(readers=[people[u] for u in read if u in people], unread=len(rows) - len(read))

    @app.get("/api/messages/<int:message_id>/reactions")
    @authed
    async def reactors(message_id):
        """Who reacted with what (report #63, a long press on the reactions): newest first."""
        msg = await g.s.get(Message, message_id)
        if msg is None:
            raise api_error("Nachricht nicht gefunden", "Message not found", 404)
        await membership(msg.conversation_id)
        rows = list(await g.s.scalars(select(MessageReaction).where(MessageReaction.message_id == msg.id)
                                      .order_by(MessageReaction.created_at.desc())))
        hidden = await block_ids(g.s, g.user.id)
        people = await card_map(g.s, g.user.id, [r.user_id for r in rows if r.user_id not in hidden])
        return jsonify(reactions=[dict(user=people[r.user_id], emoji=r.emoji) for r in rows if r.user_id in people])

    @app.put("/api/messages/<int:message_id>/reaction")
    @authed
    async def react(message_id):
        """{"emoji": "❤️"}: my reaction (replaces my earlier one; the same one again takes it back)."""
        msg = await g.s.get(Message, message_id)
        if msg is None:
            raise api_error("Nachricht nicht gefunden", "Message not found", 404)
        await membership(msg.conversation_id)
        emoji = str((await request.get_json(force=True) or {}).get("emoji", ""))
        if emoji not in REACTIONS:
            raise api_error("Reaktion ungültig", "Invalid reaction")
        mine = await g.s.get(MessageReaction, (msg.id, g.user.id))
        if mine is not None and mine.emoji == emoji:
            await g.s.delete(mine)
        elif mine is not None:
            mine.emoji, mine.created_at = emoji, utcnow()
        else:
            g.s.add(MessageReaction(message_id=msg.id, user_id=g.user.id, emoji=emoji))
        await g.s.flush()
        return jsonify(reactions=(await reactions_for(g.s, g.user.id, [msg.id])).get(msg.id, []))

    @app.delete("/api/messages/<int:message_id>/reaction")
    @authed
    async def unreact(message_id):
        msg = await g.s.get(Message, message_id)
        if msg is None:
            raise api_error("Nachricht nicht gefunden", "Message not found", 404)
        await membership(msg.conversation_id)
        await g.s.execute(delete(MessageReaction).where(MessageReaction.message_id == msg.id, MessageReaction.user_id == g.user.id))
        return jsonify(reactions=(await reactions_for(g.s, g.user.id, [msg.id])).get(msg.id, []))

    @app.post("/api/conversations/<int:conv_id>/messages")
    @authed
    async def send(conv_id):
        conv, member = await membership(conv_id)
        if conv.kind == "direct":
            from .social import is_blocked
            other = await g.s.scalar(select(ConversationMember.user_id).where(
                ConversationMember.conversation_id == conv.id, ConversationMember.user_id != g.user.id))
            if other is not None and await is_blocked(g.s, g.user.id, other):
                raise api_error("Blockiert", "Blocked", 403)
        data = await request.get_json(force=True) or {}
        # an encrypted chat takes no plain text and only encrypted media (and the other way round): an app that
        # still knows the old setting gets 409, reloads the chat and sends again
        if conv.e2e and str(data.get("text", "")).strip():
            raise api_error("Dieser Chat ist verschlüsselt", "This chat is encrypted", 409)
        if not conv.e2e and data.get("e2e") is not None:
            raise api_error("Die Verschlüsselung ist in diesem Chat aus", "Encryption is off in this chat", 409)
        text = await encode(g.s, str(data.get("text", "")).strip()[:MAX_TEXT])     # "@tim" -> account reference
        envelope = mentions = None
        if data.get("e2e") is not None:
            members = await _member_keys(g.s, conv.id)
            try:
                envelope = parse_envelope(data["e2e"], members, g.user)
            except KeyChanged:
                raise api_error("Dein Schlüssel hat sich geändert - lade den Chat neu",
                                "Your key changed - reload the chat", 409)
            except StaleKeys:
                raise api_error("Die Mitglieder oder ihre Schlüssel haben sich geändert - lade den Chat neu",
                                "The members or their keys changed - reload the chat", 409)
            except ValueError:
                raise api_error("Verschlüsselte Nachricht ungültig", "Invalid encrypted message")
            # who is tagged: the server can't read it, the sender names them (members only)
            names = {str(n).lower() for n in (data.get("mentions") or [])[:50]}
            ids = set(members) - {g.user.id}
            tagged = sorted(u.id for u in await users_by_name(names) if u.id in ids) if names else []
            mentions = "," + ",".join(map(str, tagged)) + "," if tagged else None
        media = None
        if data.get("media_id"):
            media = await g.s.get(Media, int(data["media_id"]))
            if media is None or media.user_id != g.user.id or media.attached or bool(media.encrypted) != bool(envelope):
                raise api_error("Medien ungültig", "Invalid media")
            media.attached = True
        share_kind = share_ref = None
        if data.get("share"):
            share_kind, share_ref = await valid_share(data["share"])
        if not text and media is None and share_kind is None and envelope is None:
            raise api_error("Leere Nachricht", "Empty message")
        reply_to = None
        if data.get("reply_to") is not None:
            try:
                target = await g.s.get(Message, int(data["reply_to"]))
            except (TypeError, ValueError):
                target = None
            if target is None or target.conversation_id != conv.id:
                raise api_error("Nachricht nicht gefunden", "Message not found", 404)
            reply_to = target.id
        msg = Message(conversation_id=conv.id, user_id=g.user.id, text=text, media_id=media.id if media else None,
                      share_kind=share_kind, share_ref=share_ref, reply_to_id=reply_to, e2e=envelope, mentions=mentions)
        set_typing(conv.id, g.user.id, None)                 # sent: no longer typing
        g.s.add(msg)
        conv.last_message_at = utcnow()
        await g.s.flush()
        member.last_read_id = msg.id
        return jsonify(message=(await _messages_json(g.s, g.user.id, [msg]))[0])


async def is_admin(s, conv, user_id) -> bool:
    """Admins change a group; in anarchy mode every member is one."""
    if conv is None or conv.kind != "group":
        return False
    member = await s.get(ConversationMember, (conv.id, user_id))
    return member is not None and (bool(conv.anarchy) or bool(member.admin))


async def admin_names(s, conv):
    rows = await s.scalars(select(User.username).join(ConversationMember, ConversationMember.user_id == User.id)
                           .where(ConversationMember.conversation_id == conv.id, ConversationMember.admin.is_(True)))
    return sorted(rows)


async def keep_an_admin(s, conv_id):
    """The last admin left: the longest member takes over (a group always has an admin)."""
    if await s.scalar(select(func.count()).select_from(ConversationMember).where(
            ConversationMember.conversation_id == conv_id, ConversationMember.admin.is_(True))):
        return
    first = await s.scalar(select(ConversationMember).where(ConversationMember.conversation_id == conv_id)
                           .order_by(ConversationMember.joined_at, ConversationMember.user_id).limit(1))
    if first is not None:
        first.admin = True


async def delete_user_chats(s, user_id):
    """Account deletion: leave all chats, delete own messages (and reactions), drop empty conversations."""
    await s.execute(delete(MessageReaction).where(MessageReaction.user_id == user_id))
    await delete_reactions(s, select(Message.id).where(Message.user_id == user_id))
    await s.execute(delete(Message).where(Message.user_id == user_id))
    conv_ids = list(await s.scalars(select(ConversationMember.conversation_id).where(ConversationMember.user_id == user_id)))
    await s.execute(delete(ConversationMember).where(ConversationMember.user_id == user_id))
    for cid in conv_ids:
        await keep_an_admin(s, cid)
        conv = await s.get(Conversation, cid)
        left = await s.scalar(select(func.count()).select_from(ConversationMember).where(ConversationMember.conversation_id == cid))
        if conv is not None and conv.kind == "group":
            from . import challenges
            await challenges.leave_group(s, cid, user_id)
        if conv is not None and (left == 0 or conv.kind == "direct"):
            if conv.kind == "group":
                await challenges.delete_group(s, cid)
            await delete_reactions(s, select(Message.id).where(Message.conversation_id == cid))
            await s.execute(delete(Message).where(Message.conversation_id == cid))
            await s.execute(delete(ConversationMember).where(ConversationMember.conversation_id == cid))
            await s.delete(conv)
