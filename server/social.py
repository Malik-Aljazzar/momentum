"""Social graph: users, profiles, following (one-way; mutual = friends), activity."""
import re
from datetime import timezone

from quart import g, jsonify, request
from sqlalchemy import delete, func, or_, select, update

from . import momentum as m
from .db import Block, ConversationMember, Follow, FollowRequest, Notification, Post, Report, User
from .mentions import render_one
from .service import VEHICLES, avatar_url, follow_counts, public_rules, ranked, score, shown_banner

MENTION = re.compile(r"@([a-z0-9_.\-]{3,40})", re.IGNORECASE)


def iso(dt):
    return dt.replace(tzinfo=timezone.utc).isoformat()


# ---------------------------------------------------------------- badges

async def reporter_king(s):
    """User id of the King of Reporters: the player whose most reports were implemented (status done);
    a tie goes to whoever got there first. None while nothing was implemented."""
    row = (await s.execute(
        select(Report.user_id).where(Report.status == "done", Report.user_id.is_not(None))
        .group_by(Report.user_id)
        .order_by(func.count().desc(), func.min(Report.status_at).asc(), Report.user_id.asc()))).first()
    return row[0] if row else None


async def badges_of(s, user):
    """The badges in a profile, each with why it's there: `reporter_king` for the King of Reporters (how many
    reports were implemented, bugs / features). Only numbers: what someone reported stays between them and the owner."""
    if await reporter_king(s) != user.id:
        return []
    kinds = list(await s.scalars(select(Report.kind).where(Report.user_id == user.id, Report.status == "done")))
    return [dict(key="reporter_king", count=len(kinds), bugs=sum(k == "bug" for k in kinds),
                 features=sum(k != "bug" for k in kinds))]


async def relations(s, me_id, user_ids):
    """(ids I follow, ids following me) restricted to user_ids."""
    if not user_ids:
        return set(), set()
    i_follow = set(await s.scalars(select(Follow.followee_id).where(
        Follow.follower_id == me_id, Follow.followee_id.in_(user_ids))))
    follow_me = set(await s.scalars(select(Follow.follower_id).where(
        Follow.followee_id == me_id, Follow.follower_id.in_(user_ids))))
    return i_follow, follow_me


async def cards(s, me_id, users):
    """Compact user info as shown in lists, posts and chats."""
    users = list(users)
    i_follow, follow_me = await relations(s, me_id, [u.id for u in users])
    out = []
    for u in users:
        following, follows_me = u.id in i_follow, u.id in follow_me
        out.append(dict(
            id=u.id, username=u.username, display_name=u.display_name, avatar=avatar_url(u.avatar),
            vehicle=m.vehicle_index(score(u), u.settings.milestone_step, len(VEHICLES)),
            is_me=u.id == me_id, following=following, follows_me=follows_me,
            friend=following and follows_me, private=bool(u.private),
        ))
    return out


async def card_map(s, me_id, user_ids):
    users = list(await s.scalars(select(User).where(User.id.in_(set(user_ids))))) if user_ids else []
    return {u.id: c for u, c in zip(users, await cards(s, me_id, users))}


async def is_blocked(s, a, b):
    """Either of the two blocked the other."""
    if a == b:
        return False
    return bool(await s.scalar(select(func.count()).select_from(Block).where(or_(
        (Block.blocker_id == a) & (Block.blocked_id == b), (Block.blocker_id == b) & (Block.blocked_id == a)))))


async def block_ids(s, me_id):
    """People I blocked and people who blocked me: we don't see each other."""
    mine = set(await s.scalars(select(Block.blocked_id).where(Block.blocker_id == me_id)))
    return mine | set(await s.scalars(select(Block.blocker_id).where(Block.blocked_id == me_id)))


async def connected(s, a, b):
    """True if either user follows the other (required for DMs, groups, tags) - and no block."""
    if await is_blocked(s, a, b):
        return False
    return bool(await s.scalar(select(func.count()).select_from(Follow).where(or_(
        (Follow.follower_id == a) & (Follow.followee_id == b),
        (Follow.follower_id == b) & (Follow.followee_id == a)))))


async def catch_up_users(s, users):
    """Apply pending daily decay (it's otherwise applied when a player opens the app)."""
    from .service import catch_up
    for u in users:
        await catch_up(s, u)
    await s.flush()


def period_arg(value):
    """?period=today (older apps send "24h"): the ranking by today's change."""
    return "today" if value in ("today", "24h") else None


async def deltas_24h(s, user_ids=None):
    """{user id: change of the competitive momentum today} - since 0:00 of the game's day (Berlin), not the
    last 24 hours (report #45); taps, decay, rewards - undone ones not."""
    from . import limits
    from .db import Event
    q = select(Event.user_id, func.coalesce(func.sum(Event.delta), 0.0)).where(
        Event.created_at >= limits.day_bounds(limits.server_day())[0], Event.undone.is_(False))
    if user_ids is not None:
        q = q.where(Event.user_id.in_(list(user_ids)))
    return {u: d or 0.0 for u, d in (await s.execute(q.group_by(Event.user_id))).all()}


async def ranking_rows(s, me_id, users, period=None):
    """Players ranked by competitive momentum (ranked value; paused while playing without the limit), or with
    period="today" by what they gained today (reports #40, #45; every row then has "delta")."""
    await catch_up_users(s, users)
    deltas = await deltas_24h(s, [u.id for u in users]) if period_arg(period) else None
    if deltas is not None:
        users = sorted(users, key=lambda u: (-deltas.get(u.id, 0.0), -(u.momentum or 0.0), u.username))
    else:
        users = sorted(users, key=lambda u: (-(u.momentum or 0.0), u.username))
    people = await card_map(s, me_id, [u.id for u in users])
    return [dict(rank=i + 1, momentum=u.momentum or 0.0, competing=bool(u.settings.daily_limit), user=people[u.id],
                 **({"delta": deltas.get(u.id, 0.0)} if deltas is not None else {}))
            for i, u in enumerate(users) if u.id in people]


def notify(s, recipient_id, actor_id, kind, post_id=None, comment_id=None, challenge_id=None, to_self=False,
           rule_id=None, group_id=None):
    if recipient_id != actor_id or to_self:
        s.add(Notification(user_id=recipient_id, actor_id=actor_id, kind=kind, post_id=post_id, comment_id=comment_id,
                           challenge_id=challenge_id, rule_id=rule_id, group_id=group_id))


async def notify_once(s, recipient_id, actor_id, kind, rule_id=None, group_id=None):
    """Activity that may happen again and again (subscribe, unsubscribe, subscribe ...): only the latest stays."""
    if recipient_id == actor_id:
        return
    await s.execute(delete(Notification).where(
        Notification.user_id == recipient_id, Notification.actor_id == actor_id, Notification.kind == kind,
        Notification.rule_id.is_(None) if rule_id is None else Notification.rule_id == rule_id,
        Notification.group_id.is_(None) if group_id is None else Notification.group_id == group_id))
    notify(s, recipient_id, actor_id, kind, rule_id=rule_id, group_id=group_id)


async def subject_of(s, notes):
    """The rule and the group an activity is about: {notification id: {"rule": {...}, "group": {...}}}."""
    from .db import Conversation, Rule
    out = {}
    for n in notes:
        extra = {}
        rule = await s.get(Rule, n.rule_id) if n.rule_id else None
        if rule is not None:
            extra["rule"] = dict(id=rule.id, name=rule.name, icon=rule.icon)
        conv = await s.get(Conversation, n.group_id) if n.group_id else None
        if conv is not None:
            extra["group"] = dict(id=conv.id, name=conv.name)
        out[n.id] = extra
    return out


async def mentioned_users(s, text):
    names = {n.lower() for n in MENTION.findall(text or "")}
    return list(await s.scalars(select(User).where(User.username.in_(names)))) if names else []


async def unread_notifications(s, user_id):
    return await s.scalar(select(func.count()).select_from(Notification).where(
        Notification.user_id == user_id, Notification.read.is_(False)))


def register(app, authed, api_error):
    async def find_user(username):
        user = await g.s.scalar(select(User).where(User.username == username.lower()))
        if user is None:
            raise api_error("Nutzer nicht gefunden", "User not found", 404)
        return user

    @app.get("/api/users/search")
    @authed
    async def search_users():
        q = request.args.get("q", "").strip().lower().lstrip("@")
        if len(q) < 1:
            return jsonify(users=[])
        like = f"%{q}%"
        from .posts import page_args
        offset, limit = page_args()
        hidden = await block_ids(g.s, g.user.id)
        users = await g.s.scalars(   # accounts still choosing a username (and blocked ones) are not listed
            select(User).where(User.username_set.is_(True), User.id.notin_(hidden),
                               or_(User.username.like(like), func.lower(User.display_name).like(like)))
            .order_by(User.username).offset(offset).limit(limit))
        return jsonify(users=await cards(g.s, g.user.id, users))

    @app.get("/api/leaderboard")
    @authed
    async def leaderboard():
        """?scope=global: top 10 and my place. ?scope=friends: me, people I follow and my followers.
        ?scope=groups: public groups by the total momentum of their members (and where my groups stand).
        ?period=24h: ranked by the change of the last 24 hours instead ("delta" / "total" is that change)."""
        me = g.user
        period = period_arg(request.args.get("period"))
        if request.args.get("scope") == "groups" and period:
            from .db import Conversation
            rows = (await g.s.execute(
                select(Conversation, ConversationMember.user_id)
                .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
                .where(Conversation.kind == "group", Conversation.public.is_(True)))).all()
            deltas = await deltas_24h(g.s, {u for _, u in rows})
            convs, sums, counts = {}, {}, {}
            for c, u in rows:
                convs[c.id] = c
                sums[c.id] = sums.get(c.id, 0.0) + deltas.get(u, 0.0)
                counts[c.id] = counts.get(c.id, 0) + 1
            order = sorted(convs, key=lambda i: (-sums[i], convs[i].name.lower()))
            mine = set(await g.s.scalars(select(ConversationMember.conversation_id).where(ConversationMember.user_id == me.id)))
            from .posts import page_args
            offset, limit = page_args(50)
            groups = [dict(rank=r + 1, id=i, name=convs[i].name, banner=convs[i].banner, members=counts[i], total=sums[i],
                           delta=sums[i], member=i in mine) for r, i in enumerate(order)]
            return jsonify(groups=groups[offset:offset + limit], mine=[x for x in groups if x["member"]], total=len(groups))
        if request.args.get("scope") == "groups":
            from .db import Conversation
            totals = (await g.s.execute(
                select(Conversation, func.coalesce(func.sum(User.momentum), 0.0), func.count(User.id))
                .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
                .join(User, User.id == ConversationMember.user_id)
                .where(Conversation.kind == "group", Conversation.public.is_(True))
                .group_by(Conversation.id).order_by(func.sum(User.momentum).desc()))).all()
            mine = set(await g.s.scalars(select(ConversationMember.conversation_id).where(ConversationMember.user_id == me.id)))
            from .posts import page_args
            offset, limit = page_args(50)
            groups = [dict(rank=i + 1, id=c.id, name=c.name, banner=c.banner, members=n, total=t or 0.0,
                           member=c.id in mine) for i, (c, t, n) in enumerate(totals)]
            return jsonify(groups=groups[offset:offset + limit], mine=[x for x in groups if x["member"]], total=len(groups))
        if request.args.get("scope") == "friends":
            ids = set(await g.s.scalars(select(Follow.followee_id).where(Follow.follower_id == me.id))) \
                | set(await g.s.scalars(select(Follow.follower_id).where(Follow.followee_id == me.id))) | {me.id}
            users = list(await g.s.scalars(select(User).where(User.id.in_(ids), User.username_set.is_(True))))
            entries = await ranking_rows(g.s, me.id, users, period)
            from .posts import page_args
            offset, limit = page_args(50)
            return jsonify(entries=entries[offset:offset + limit], me=next((e for e in entries if e["user"]["is_me"]), None),
                           total=len(entries))
        if period:
            # global by the last 24 h: everyone who changed, the best 30 get their pending decay first
            deltas = await deltas_24h(g.s)
            top = sorted(deltas, key=lambda u: -deltas[u])[:30]
            candidates = list(await g.s.scalars(select(User).where(User.id.in_(top), User.username_set.is_(True))))
            entries = (await ranking_rows(g.s, me.id, candidates + ([me] if me.id not in top else []), period))[:10]
            mine_delta = (await deltas_24h(g.s, [me.id])).get(me.id, 0.0)
            ahead = sum(1 for u, d in (await deltas_24h(g.s)).items() if d > mine_delta and u != me.id)
            mine = next((e for e in entries if e["user"]["is_me"]), None) or dict(
                rank=ahead + 1, momentum=me.momentum, competing=me.settings.daily_limit, delta=mine_delta,
                user=(await cards(g.s, me.id, [me]))[0])
            total = await g.s.scalar(select(func.count()).select_from(User).where(User.username_set.is_(True)))
            return jsonify(entries=entries, me=mine, total=total)
        # global: the top candidates get their pending daily decay first, so nobody stale tops the list
        candidates = list(await g.s.scalars(select(User).where(User.username_set.is_(True))
                                            .order_by(User.momentum.desc()).limit(30)))
        entries = (await ranking_rows(g.s, me.id, candidates))[:10]
        await catch_up_users(g.s, [me])
        place = 1 + await g.s.scalar(select(func.count()).select_from(User).where(
            User.username_set.is_(True), User.momentum > me.momentum))
        mine = next((e for e in entries if e["user"]["is_me"]), None) or dict(
            rank=place, momentum=me.momentum, competing=me.settings.daily_limit,
            user=(await cards(g.s, me.id, [me]))[0])
        total = await g.s.scalar(select(func.count()).select_from(User).where(User.username_set.is_(True)))
        return jsonify(entries=entries, me=mine, total=total)

    @app.get("/api/mentions")
    @authed
    async def mention_suggestions():
        """Autocomplete after "@": exact name first, then people I follow / who follow me, then everyone.

        Within a group: prefix matches before matches in the middle of the name.
        With ?conv=<group id>: candidates to add to that group (connected people who aren't members yet).
        With ?members_of=<chat id>: only the chat's members (tagging in messages).
        """
        q = request.args.get("q", "").strip().lower().lstrip("@")
        me = g.user.id
        following = set(await g.s.scalars(select(Follow.followee_id).where(Follow.follower_id == me)))
        followers = set(await g.s.scalars(select(Follow.follower_id).where(Follow.followee_id == me)))
        connected_ids = following | followers
        base = select(User).where(User.username_set.is_(True), User.id != me, User.id.notin_(await block_ids(g.s, me)))
        limit = 8
        conv_id = request.args.get("conv", type=int)
        if conv_id is not None:
            members = set(await g.s.scalars(select(ConversationMember.user_id)
                                            .where(ConversationMember.conversation_id == conv_id)))
            if me not in members:
                return jsonify(users=[])
            base = base.where(User.id.in_(connected_ids - members))      # adding requires a connection
            limit = 40
        in_chat = request.args.get("members_of", type=int)
        if in_chat is not None:
            members = set(await g.s.scalars(select(ConversationMember.user_id)
                                            .where(ConversationMember.conversation_id == in_chat)))
            if me not in members:
                return jsonify(users=[])
            base = base.where(User.id.in_(members - {me}))
            if not q:                  # just "@": every member, ranked like the rest
                users = list(await g.s.scalars(base))
                users.sort(key=lambda u: (0 if u.id in following else 1 if u.id in followers else 2, u.username))
                return jsonify(users=await cards(g.s, me, users[:limit]))
        if not q:                      # just "@": my people, the ones I follow first
            users = list(await g.s.scalars(base.where(User.id.in_(connected_ids)))) if connected_ids else []
        else:
            users = list(await g.s.scalars(base.where(User.username.like(f"%{q}%")).limit(200)))

        def rank(u):
            name = u.username
            close = 0 if u.id in following else 1 if u.id in followers else 2
            if name == q:
                return (0, 0, name)
            return (1 + close * 2 + (0 if name.startswith(q) else 1), len(name), name)
        users.sort(key=rank)
        return jsonify(users=await cards(g.s, me, users[:limit]))

    async def public_decay(user, locked):
        from .decays import can_see_user_decay, user_decay_json
        if locked or not await can_see_user_decay(g.s, g.user.id, user):
            return None
        return await user_decay_json(g.s, g.user, user)

    async def profile_response(user):
        """Built in the caller's session (calling another @authed route would open a new one)."""
        from .community import can_view
        card = (await cards(g.s, g.user.id, [user]))[0]
        posts = await g.s.scalar(select(func.count()).select_from(Post).where(Post.user_id == user.id, Post.kind != "rule"))
        counts = await follow_counts(g.s, user.id)
        # private account: counts stay visible, content only for accepted followers
        locked = not await can_view(g.s, g.user.id, user)
        rules, history = ([], []) if locked else await public_rules(g.s, user)
        requested = await g.s.get(FollowRequest, (g.user.id, user.id)) is not None
        step = user.settings.milestone_step
        idx = m.vehicle_index(score(user), step, len(VEHICLES))
        # counts get their own names: card["following"] means "I follow this user"
        return jsonify(profile=dict(
            card, bio=await render_one(g.s, user.bio), momentum=score(user), posts=posts,
            next_at=m.milestone(idx + 1, step) if idx + 1 < len(VEHICLES) else None, milestone_step=step,
            reached_at=m.milestone(idx, step),
            followers_count=counts["followers"], following_count=counts["following"],
            rules=rules, history=history, locked=locked, requested=requested, banner=shown_banner(user),
            free_score=user.free_score or 0.0, ranked_momentum=user.momentum, competing=ranked(user),
            decay=await public_decay(user, locked),
            blocked=await g.s.get(Block, (g.user.id, user.id)) is not None,     # I blocked them
            story=await story_ring(user, locked), badges=await badges_of(g.s, user)))

    async def story_ring(user, locked):
        """Active stories (the profile picture's ring) - not for a locked / blocked profile."""
        from .stories import story_state
        if locked:
            return dict(active=0, unseen=False)
        return await story_state(g.s, g.user.id, user.id)

    async def visible_user(username):
        """Someone who blocked me doesn't exist for me."""
        user = await find_user(username)
        if await g.s.get(Block, (user.id, g.user.id)):
            raise api_error("Nutzer nicht gefunden", "User not found", 404)
        return user

    @app.get("/api/users/<username>")
    @authed
    async def profile(username):
        return await profile_response(await visible_user(username))

    @app.post("/api/users/<username>/block")
    @authed
    async def block(username):
        user = await visible_user(username)
        if user.id == g.user.id:
            raise api_error("Du kannst dich nicht selbst blockieren", "You can't block yourself")
        if not await g.s.get(Block, (g.user.id, user.id)):
            g.s.add(Block(blocker_id=g.user.id, blocked_id=user.id))
        me, other = g.user.id, user.id
        pair = lambda a, b: or_((a == me) & (b == other), (a == other) & (b == me))     # noqa: E731
        await g.s.execute(delete(Follow).where(pair(Follow.follower_id, Follow.followee_id)))
        await g.s.execute(delete(FollowRequest).where(pair(FollowRequest.requester_id, FollowRequest.target_id)))
        await g.s.execute(delete(Notification).where(pair(Notification.user_id, Notification.actor_id)))
        await g.s.flush()
        return await profile_response(user)

    @app.delete("/api/users/<username>/block")
    @authed
    async def unblock(username):
        user = await find_user(username)
        await g.s.execute(delete(Block).where(Block.blocker_id == g.user.id, Block.blocked_id == user.id))
        return await profile_response(user)

    @app.get("/api/blocks")
    @authed
    async def blocked_users():
        """The people I blocked (settings: unblock them there)."""
        from .posts import page_args
        offset, limit = page_args()
        users = await g.s.scalars(select(User).join(Block, Block.blocked_id == User.id).where(Block.blocker_id == g.user.id)
                                  .order_by(Block.created_at.desc()).offset(offset).limit(limit))
        return jsonify(users=await cards(g.s, g.user.id, users))

    @app.post("/api/users/<username>/follow")
    @authed
    async def follow(username):
        user = await visible_user(username)
        if user.id == g.user.id:
            raise api_error("Du kannst dir nicht selbst folgen", "You can't follow yourself")
        if await is_blocked(g.s, g.user.id, user.id):
            raise api_error("Blockiert", "Blocked", 403)
        if await g.s.get(Follow, (g.user.id, user.id)):
            pass
        elif user.private:                       # private account: ask first
            if not await g.s.get(FollowRequest, (g.user.id, user.id)):
                g.s.add(FollowRequest(requester_id=g.user.id, target_id=user.id))
                notify(g.s, user.id, g.user.id, "request")
                await g.s.flush()
        else:
            g.s.add(Follow(follower_id=g.user.id, followee_id=user.id))
            notify(g.s, user.id, g.user.id, "follow")
            await g.s.flush()
        return await profile_response(user)

    @app.delete("/api/users/<username>/follow")
    @authed
    async def unfollow(username):
        user = await find_user(username)
        await g.s.execute(delete(Follow).where(Follow.follower_id == g.user.id, Follow.followee_id == user.id))
        # also withdraws a pending request
        await g.s.execute(delete(FollowRequest).where(FollowRequest.requester_id == g.user.id,
                                                      FollowRequest.target_id == user.id))
        await g.s.execute(delete(Notification).where(Notification.user_id == user.id, Notification.actor_id == g.user.id,
                                                     Notification.kind == "request"))
        return await profile_response(user)

    async def follow_list(username, followers):
        from .community import can_view
        user = await find_user(username)
        if not await can_view(g.s, g.user.id, user):
            raise api_error("Dieses Konto ist privat", "This account is private", 403)
        if followers:
            q = select(User).join(Follow, Follow.follower_id == User.id).where(Follow.followee_id == user.id)
        else:
            q = select(User).join(Follow, Follow.followee_id == User.id).where(Follow.follower_id == user.id)
        from .posts import page_args
        offset, limit = page_args()
        return jsonify(users=await cards(g.s, g.user.id, await g.s.scalars(
            q.order_by(Follow.created_at.desc()).offset(offset).limit(limit))))

    @app.get("/api/users/<username>/followers")
    @authed
    async def followers(username):
        return await follow_list(username, True)

    @app.get("/api/users/<username>/following")
    @authed
    async def following(username):
        return await follow_list(username, False)

    @app.get("/api/notifications")
    @authed
    async def notifications():
        """Latest activity; opening the list marks everything as read."""
        from .clients import hidden_notification_kinds
        q = select(Notification).where(Notification.user_id == g.user.id, Notification.kind.not_in(hidden_notification_kinds()))
        if request.args.get("before", type=int):
            q = q.where(Notification.id < request.args.get("before", type=int))
        rows = list(await g.s.scalars(q.order_by(Notification.id.desc()).limit(min(request.args.get("limit", 30, type=int) or 30, 60))))
        actors = await card_map(g.s, g.user.id, [n.actor_id for n in rows])
        from .posts import post_thumbs   # avoid import cycle
        thumbs = await post_thumbs(g.s, [n.post_id for n in rows if n.post_id])
        from .db import Challenge, ChallengeParticipant
        challenges = {}
        for cid in {n.challenge_id for n in rows if n.challenge_id}:
            ch = await g.s.get(Challenge, cid)
            p = await g.s.get(ChallengeParticipant, (cid, g.user.id))
            if ch is not None:
                challenges[cid] = dict(id=ch.id, title=ch.title, conversation_id=ch.conversation_id,
                                       rank=p.rank if p else None, reward=p.reward if p else 0.0, mode=ch.mode)
        subjects = await subject_of(g.s, rows)
        items = [dict(id=n.id, kind=n.kind, actor=actors[n.actor_id], post_id=n.post_id,
                      post_thumb=thumbs.get(n.post_id), challenge=challenges.get(n.challenge_id),
                      read=n.read, at=iso(n.created_at), **subjects[n.id])
                 for n in rows if n.actor_id in actors]
        requests = await g.s.scalar(select(func.count()).select_from(FollowRequest)
                                    .where(FollowRequest.target_id == g.user.id))
        await g.s.execute(update(Notification).where(Notification.user_id == g.user.id, Notification.read.is_(False))
                          .values(read=True))
        return jsonify(items=items, requests=requests)
