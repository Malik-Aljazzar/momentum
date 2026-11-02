"""Private accounts, rule subscriptions and group rules.

Rule subscriptions: subscribing creates a personal copy (source_rule_id -> root rule).
When the owner changes the root rule, every copy follows (and restarts at its base).
Copies can't be edited, only removed (= unsubscribe). When the root rule is deleted or made
private again, every subscription of it goes away (of people and groups, and the subscriptions
of the group's members); a group's own rule made private only loses its outside subscribers.

Rules linked by an automatic tap (idle_rule_id: "not tapped for n days -> tap that rule") are
subscribed, followed and copied as a whole chain, linked among each other like the originals.
Removing a subscription takes the subscribed rules that tap it automatically along.

Group rules are templates (template=True, group_id) in the group's list. They get there in
three ways:
  - followed: a public rule; linked (source_rule_id), follows its owner's changes
  - created in the group, like on a profile; may be public so others can subscribe
  - copied from one of my rules (own or subscribed): a real copy, no link
Created and copied ones belong to the group (editable by their creator or the group admin).
Members subscribe like to any other rule. Public groups show their followed and public rules
to everyone. Leaving the group keeps the copies as own rules (subscriptions to rules that stay
publicly visible keep following them).
"""
from quart import g, jsonify, request
from sqlalchemy import delete, func, select, update

from .db import (Challenge, ChallengeParticipant, ChallengeRule, Conversation, ConversationMember, Follow,
                 FollowRequest, Media, Message, Notification, Post, PostTag, Rule, User, utcnow)
from .social import cards, notify, notify_once
from .chat import is_admin

RULE_FIELDS = ("kind", "name", "icon", "mode", "base", "param", "min_value", "reset_days", "reset_unit", "tappable")


# ---------------------------------------------------------------- privacy

async def can_view(s, me_id, user):
    """Content of private accounts is only for accepted followers; blocked people (either way) see nothing."""
    from .social import is_blocked
    if user.id == me_id:
        return True
    if await is_blocked(s, me_id, user.id):
        return False
    if not user.private:
        return True
    return await s.get(Follow, (me_id, user.id)) is not None


async def shared_by_owner(s, me_id, kind, ref, owner_id):
    """The owner shared this item into a chat I'm in (a share by someone else doesn't bypass privacy)."""
    mine = select(ConversationMember.conversation_id).where(ConversationMember.user_id == me_id)
    return bool(await s.scalar(select(func.count()).select_from(Message).where(
        Message.share_kind == kind, Message.share_ref == str(ref), Message.user_id == owner_id,
        Message.conversation_id.in_(mine))))


async def can_view_post(s, me_id, post):
    from .social import is_blocked
    owner = await s.get(User, post.user_id)
    if owner is not None and await is_blocked(s, me_id, owner.id):
        return False
    if owner is None or await can_view(s, me_id, owner):
        return owner is not None
    if await s.get(PostTag, (post.id, me_id)):
        return True
    return await shared_by_owner(s, me_id, "post", post.id, owner.id)


async def is_member(s, conv_id, user_id):
    return await s.get(ConversationMember, (conv_id, user_id)) is not None


async def accept_all_requests(s, user):
    """Account switched to public: pending requests become follows."""
    for req in list(await s.scalars(select(FollowRequest).where(FollowRequest.target_id == user.id))):
        if not await s.get(Follow, (req.requester_id, user.id)):
            s.add(Follow(follower_id=req.requester_id, followee_id=user.id))
            notify(s, req.requester_id, user.id, "accepted")
        await s.delete(req)
    await s.execute(delete(Notification).where(Notification.user_id == user.id, Notification.kind == "request"))


# ---------------------------------------------------------------- rules

def copy_fields(src, dst):
    for f in RULE_FIELDS:
        setattr(dst, f, getattr(src, f))
    dst.current = dst.base


class Reused:
    """find() result: the subscriber has this rule already another way (same origin) - link to it, leave it as it is."""
    def __init__(self, rule):
        self.rule = rule


async def origin_of(s, rule):
    """The rule all linked copies go back to: a subscription of a group rule that follows a profile rule ->
    the profile rule. Two rules with the same origin are the same rule (real copies are rules of their own)."""
    seen = set()
    while rule.source_rule_id and rule.id not in seen:
        seen.add(rule.id)
        nxt = await s.get(Rule, rule.source_rule_id)
        if nxt is None:
            break
        rule = nxt
    return rule


async def users_same_rule(s, user_id, rule):
    """The user's rule with the same origin (their own original or a subscription by any way), or None."""
    o = await origin_of(s, rule)
    if o.user_id == user_id and not o.template and not o.challenge_id:
        return o
    for r in list(await s.scalars(select(Rule).where(Rule.user_id == user_id, Rule.template.is_(False),
                                                    Rule.source_rule_id.is_not(None)))):
        if (await origin_of(s, r)).id == o.id:
            return r
    return None


async def groups_same_rule(s, conv_id, rule):
    """A rule the group follows that has the same origin, or None."""
    o = await origin_of(s, rule)
    for r in list(await s.scalars(select(Rule).where(Rule.group_id == conv_id, Rule.template.is_(True)))):
        if r.id == o.id or (r.source_rule_id and (await origin_of(s, r)).id == o.id):
            return r
    return None


async def chain_copy(s, root, find, make, seen=None):
    """The copy of `root` for a subscriber (a user or a group) together with the rules its automatic
    tap leads to, linked among each other like the originals. find(root_id) -> existing copy or None,
    make(rule) -> new copy."""
    seen = {} if seen is None else seen
    if root.id in seen:
        return seen[root.id]
    copy = await find(root.id)
    if isinstance(copy, Reused):
        seen[root.id] = copy.rule
        return copy.rule
    if copy is None:
        copy = await make(root)
    seen[root.id] = copy
    target = await s.get(Rule, root.idle_rule_id) if root.idle_rule_id and root.idle_days else None
    if target is not None:
        linked = await chain_copy(s, target, find, make, seen)
        if (copy.idle_rule_id, copy.idle_days, copy.idle_unit) != (linked.id, root.idle_days, root.idle_unit):
            copy.idle_since = utcnow()            # counting starts now
        copy.idle_rule_id, copy.idle_days, copy.idle_unit = linked.id, root.idle_days, root.idle_unit
    else:
        copy.idle_rule_id, copy.idle_days = None, 0
    return copy


def user_copies(s, user_id, via_challenge_id=None):
    """find/make for subscriptions of a user. Nobody has a rule twice: one they have another way
    (own original, via a person or a group) is reused - except challenge copies, which count the challenge."""
    async def find(root_id):
        copy = await my_copy(s, user_id, root_id)
        if copy is None and via_challenge_id is None:
            other = await users_same_rule(s, user_id, await s.get(Rule, root_id))
            return Reused(other) if other is not None else None
        return copy

    async def make(src):
        pos = await s.scalar(select(func.coalesce(func.max(Rule.position), 0)).where(Rule.user_id == user_id))
        copy = Rule(user_id=user_id, position=pos + 1, source_rule_id=src.id, public=False,
                    via_challenge_id=via_challenge_id)
        copy_fields(src, copy)
        s.add(copy)
        await s.flush()
        return copy
    return find, make


def group_follows(s, conv_id, added_by):
    """find/make for rules a group follows (linked templates)."""
    async def find(root_id):
        found = await s.scalar(select(Rule).where(Rule.group_id == conv_id, Rule.template.is_(True),
                                                  Rule.source_rule_id == root_id))
        if found is None:              # the group follows it another way already (same origin)
            other = await groups_same_rule(s, conv_id, await s.get(Rule, root_id))
            return Reused(other) if other is not None else None
        return found

    async def make(src):
        rule = Rule(user_id=added_by, template=True, group_id=conv_id, source_rule_id=src.id, public=False)
        copy_fields(src, rule)
        s.add(rule)
        await s.flush()
        return rule
    return find, make


async def group_copy_of(s, conv_id, rule_id):
    """The group's copy of this rule (or the group follows it already)."""
    return await s.scalar(select(Rule).where(
        Rule.group_id == conv_id, Rule.template.is_(True),
        (Rule.copied_from_id == rule_id) | (Rule.source_rule_id == rule_id)).limit(1))


def group_copies(s, conv_id, added_by):
    """find/make for real copies into a group (no link to the original; a rule is copied only once,
    rules of the idle chain that are in the group already are reused)."""
    async def find(root_id):
        found = await group_copy_of(s, conv_id, root_id)
        if found is None:                # a subscription: its original may be in the group already
            src = await s.get(Rule, root_id)
            if src is not None and src.source_rule_id:
                found = await group_copy_of(s, conv_id, src.source_rule_id)
        return found

    async def make(src):
        rule = Rule(user_id=added_by, template=True, group_id=conv_id, public=False, copied_from_id=src.id)
        copy_fields(src, rule)
        s.add(rule)
        await s.flush()
        return rule
    return find, make


async def propagate(s, root):
    """Owner changed a rule: subscribed copies follow (incl. its automatic tap, whose target is
    subscribed along), and group rules made from it (with their subscribers)."""
    for copy in list(await s.scalars(select(Rule).where(Rule.source_rule_id == root.id, Rule.challenge_id.is_(None)))):
        copy_fields(root, copy)
        finder = group_follows(s, copy.group_id, copy.user_id) if copy.template else user_copies(s, copy.user_id)
        await chain_copy(s, root, *finder, seen={})
        if copy.template:
            await propagate(s, copy)


async def remove_copies(s, root_id, keep=None):
    """The root rule was deleted or made private: its subscriptions and group follows go away,
    with the members' subscriptions of such a group follow. keep(copy) -> True spares a copy."""
    for copy in list(await s.scalars(select(Rule).where(Rule.source_rule_id == root_id, Rule.challenge_id.is_(None)))):
        if keep is not None and await keep(copy):
            continue
        if copy.template:
            await remove_copies(s, copy.id)
            await s.execute(delete(ChallengeRule).where(ChallengeRule.rule_id == copy.id))
        await s.execute(update(Rule).where(Rule.idle_rule_id == copy.id).values(idle_rule_id=None, idle_days=0))
        await s.delete(copy)
    await s.flush()


async def detach_copies(s, root_id):
    """Root rule goes away: copies become normal personal rules (challenge copies are removed elsewhere)."""
    await s.execute(update(Rule).where(Rule.source_rule_id == root_id, Rule.challenge_id.is_(None))
                    .values(source_rule_id=None))


def publicly_visible(conv, rule):
    """Group rules outsiders see in a public group: followed (public originals) and public own ones."""
    return bool(conv and conv.public and (rule.public or rule.source_rule_id))


async def can_view_rule(s, me, rule):
    if rule.template:
        if rule.group_id:
            return await is_member(s, rule.group_id, me.id) or publicly_visible(await s.get(Conversation, rule.group_id), rule)
        if rule.challenge_id:
            ch = await s.get(Challenge, rule.challenge_id)
            return ch is not None and await is_member(s, ch.conversation_id, me.id)
        return False
    if rule.user_id == me.id:
        return True
    owner = await s.get(User, rule.user_id)
    # an own rule marked public, or a subscription the owner shows in the profile: everyone who sees
    # the profile may look at it (subscribing still needs the original, see rule_detail / subscribe)
    shown = rule.in_profile if rule.source_rule_id else rule.public
    if shown and owner is not None and await can_view(s, me.id, owner):
        return True
    return await shared_by_owner(s, me.id, "rule", rule.id, rule.user_id)


async def root_of(s, rule):
    if rule.template:            # group rules (also ones added from a profile) are their own root
        return rule
    if rule.source_rule_id:
        src = await s.get(Rule, rule.source_rule_id)
        if src is not None:
            return src
    return rule


async def my_copy(s, me_id, root_id):
    return await s.scalar(select(Rule).where(Rule.user_id == me_id, Rule.source_rule_id == root_id,
                                             Rule.template.is_(False)))


async def challenge_lock(s, user_id, root_id):
    """Title of an active challenge I take part in that plays with this rule (can't unsubscribe until I leave)."""
    return await s.scalar(
        select(Challenge.title).join(ChallengeRule, ChallengeRule.challenge_id == Challenge.id)
        .join(ChallengeParticipant, ChallengeParticipant.challenge_id == Challenge.id)
        .where(ChallengeRule.rule_id == root_id, ChallengeParticipant.user_id == user_id,
               ChallengeParticipant.left.is_(False), Challenge.status.in_(("upcoming", "running"))).limit(1))


async def rule_detail(s, me, rule):
    root = await root_of(s, rule)
    owner = await s.get(User, root.user_id)
    copy = await my_copy(s, me.id, root.id)
    # the same rule another way (own original, via a person or a group): no second subscription
    have_already = copy is None and not (root.user_id == me.id and not root.template) and \
        await users_same_rule(s, me.id, root) is not None
    subscribers = await s.scalar(select(func.count()).select_from(Rule).where(
        Rule.source_rule_id == root.id, Rule.template.is_(False)))
    origin = "group" if root.group_id else "challenge" if root.challenge_id else "profile"
    is_mine = root.user_id == me.id and not root.template
    linked = await s.get(Rule, root.source_rule_id) if root.template and root.source_rule_id else None
    linked_owner = await s.get(User, linked.user_id) if linked else None
    conv = await s.get(Conversation, root.group_id) if root.group_id else None
    member = bool(conv and await is_member(s, conv.id, me.id))
    admin = await is_admin(s, conv, me.id) if conv else False
    can_edit = bool(conv and admin and linked is None)          # the group's admins change its rules
    removable = bool(conv and admin and root.template)          # ... and take rules out (also followed ones)
    idle_target = await s.get(Rule, root.idle_rule_id) if root.idle_rule_id and root.idle_days else None
    # seen through someone's profile: the original itself may be members-only
    may_have = await can_view_rule(s, me, root)
    hidden_group = bool(conv and not member and not conv.public)        # a private group's name stays private
    group_image = None
    if conv and not hidden_group:               # group rules show the group (its picture), not who made them
        md = await s.get(Media, conv.avatar_media_id or conv.banner_media_id) if (conv.avatar_media_id or conv.banner_media_id) else None
        group_image = f"/{md.thumb_key or md.key}" if md else None
    return dict(
        id=root.id, kind=root.kind, name=root.name, icon=root.icon, mode=root.mode, base=root.base,
        param=root.param, min_value=root.min_value, reset_days=root.reset_days, reset_unit=root.reset_unit or "d",
        tappable=root.tappable is not False,
        owner=(await cards(s, me.id, [linked_owner or owner]))[0] if (linked_owner or owner) else None,
        origin=origin, group_id=root.group_id, subscribers=subscribers,
        subscribed=copy is not None, my_copy_id=copy.id if copy else None, is_mine=is_mine,
        can_subscribe=not is_mine and copy is None and origin != "challenge" and may_have and not have_already,
        have_already=have_already,
        # why not: only the group's members may (group_name is None for a private group)
        members_only=not is_mine and copy is None and origin == "group" and not may_have,
        # group rule that follows a profile rule (only its owner changes it)
        linked_from=linked_owner.username if linked_owner else None,
        public=bool(root.public),
        editable=can_edit, removable=removable,
        group_name=conv.name if conv and not hidden_group else None, group_member=member,
        group_image=group_image, group_banner=conv.banner if conv and not hidden_group else None,
        # my subscription is part of a running challenge: only leaving the challenge releases it
        locked_by=await challenge_lock(s, me.id, root.id) if copy is not None else None,
        # not tapped for idle_days -> idle_rule is tapped automatically (subscribed along)
        idle_days=root.idle_days if idle_target else 0, idle_unit=root.idle_unit or "d",
        idle_rule=dict(id=idle_target.id, name=idle_target.name, icon=idle_target.icon, kind=idle_target.kind)
        if idle_target else None,
        idle_paused=bool(copy.idle_paused) if copy is not None else False,
    )


async def can_add_to_group(s, me, rule):
    """A group follows public rules, like a user would: own public rules, or public rules of public
    accounts (a private account's rules stay with its followers)."""
    if rule.template or rule.challenge_id or not rule.public:
        return False
    if rule.user_id == me.id:
        return True
    owner = await s.get(User, rule.user_id)
    return bool(owner is not None and not owner.private)


async def follow_all(s, conv_id, user_id):
    """Subscribe a member to every rule the group follows (skips ones already subscribed)."""
    for tpl in list(await s.scalars(select(Rule).where(Rule.group_id == conv_id, Rule.template.is_(True)))):
        if await my_copy(s, user_id, tpl.id) is None:
            await chain_copy(s, tpl, *user_copies(s, user_id))


async def tell_owner(s, actor_id, root, group_id=None):
    """A person's rule got a new subscriber - a person or a group (report #54)."""
    if root.template or root.group_id or root.challenge_id:
        return
    await notify_once(s, root.user_id, actor_id, "sub_rule", rule_id=root.id, group_id=group_id)


async def subscribe(s, me, rule, api_error):
    root = await root_of(s, rule)
    if not await can_view_rule(s, me, root):         # e.g. a members-only group rule seen in a profile
        raise api_error("Abonnieren können nur Mitglieder der Gruppe", "Only the group's members can subscribe")
    if root.challenge_id:
        raise api_error("Challenge-Regeln bekommst du durch Mitmachen", "Join the challenge to get its rules")
    if root.user_id == me.id and not root.template:
        raise api_error("Das ist deine eigene Regel", "That's your own rule")
    if await my_copy(s, me.id, root.id) is None and await users_same_rule(s, me.id, root) is not None:
        raise api_error("Diese Regel hast du schon", "You have this rule already")
    new = await my_copy(s, me.id, root.id) is None
    # the rules its automatic tap leads to come along (linked like the originals)
    copy = await chain_copy(s, root, *user_copies(s, me.id))
    if new:
        await tell_owner(s, me.id, root)
    return copy


def register(app, authed, api_error, parse_rule, icons, state_response):
    async def get_any_rule(rule_id):
        rule = await g.s.get(Rule, rule_id)
        if rule is None or not await can_view_rule(g.s, g.user, rule):
            raise api_error("Regel nicht gefunden", "Rule not found", 404)
        return rule

    async def admin_group(conv_id):
        conv = await group(conv_id)
        if not await is_admin(g.s, conv, g.user.id):
            raise api_error("Das dürfen nur Admins der Gruppe", "Only the group's admins can do that", 403)
        return conv

    async def group(conv_id, view=False):
        """Members change a group; view=True: a public group's overview is readable for everyone."""
        conv = await g.s.get(Conversation, conv_id)
        ok = conv is not None and conv.kind == "group" and (
            await is_member(g.s, conv_id, g.user.id) or (view and conv.public))
        if not ok:
            raise api_error("Gruppe nicht gefunden", "Group not found", 404)
        return conv

    @app.get("/api/rules/<int:rule_id>/detail")
    @authed
    async def rule_detail_route(rule_id):
        return jsonify(rule=await rule_detail(g.s, g.user, await get_any_rule(rule_id)))

    @app.post("/api/rules/<int:rule_id>/subscribe")
    @authed
    async def do_subscribe(rule_id):
        await subscribe(g.s, g.user, await get_any_rule(rule_id), api_error)
        return await state_response()

    # ---- group rules

    @app.get("/api/conversations/<int:conv_id>/rules")
    @authed
    async def group_rules(conv_id):
        conv = await group(conv_id, view=True)
        member = await is_member(g.s, conv_id, g.user.id)
        rules = await g.s.scalars(select(Rule).where(Rule.group_id == conv_id, Rule.template.is_(True)).order_by(Rule.id))
        return jsonify(rules=[await rule_detail(g.s, g.user, r) for r in rules if member or publicly_visible(conv, r)])

    @app.post("/api/conversations/<int:conv_id>/rules")
    @authed
    async def create_group_rule(conv_id):
        """Three ways into the group's list:

            {"from_rule": id}   follow a public rule (linked, follows its owner's changes)
            {"copy_rule": id}   copy one of my rules (own or subscribed) - a real copy, no link
            {"kind": ..., ...}  a new rule of the group (like on a profile; "public": others may subscribe)

        Members with "always follow all" get it subscribed right away. Admins only.
        """
        await admin_group(conv_id)
        data = await request.get_json(force=True) or {}
        if data.get("copy_rule") is not None or data.get("from_rule") is None:
            if data.get("copy_rule") is not None:
                try:
                    src = await g.s.get(Rule, int(data["copy_rule"]))
                except (TypeError, ValueError):
                    src = None
                rule, reason = await copy_into_group(conv_id, src)
                if reason == "not_found":
                    raise api_error("Regel nicht gefunden", "Rule not found", 404)
                if reason == "already":
                    raise api_error("Die Regel ist schon in der Gruppe", "The rule is already in this group")
                await auto_follow_members(conv_id)
                return jsonify(rule=await rule_detail(g.s, g.user, rule))
            else:
                kind = data.get("kind")
                if kind not in ("acc", "dec"):
                    raise api_error("kind ungültig", "invalid kind")
                fields = parse_rule(data, icons)
                rule = Rule(user_id=g.user.id, kind=kind, template=True, group_id=conv_id,
                            current=fields["base"], **fields)
            g.s.add(rule)
            await g.s.flush()
            await auto_follow_members(conv_id)
            return jsonify(rule=await rule_detail(g.s, g.user, rule))
        try:
            src = await g.s.get(Rule, int(data["from_rule"]))
        except (TypeError, ValueError):
            src = None
        if src is not None and src.source_rule_id and not src.template and not src.challenge_id:
            src = await root_of(g.s, src)          # a subscribed copy adds its original
        if src is None or not await can_view_rule(g.s, g.user, src):
            raise api_error("Regel nicht gefunden", "Rule not found", 404)
        if not await can_add_to_group(g.s, g.user, src):
            raise api_error("Diese Regel kann nicht in Gruppen geteilt werden",
                            "This rule can't be added to groups", 403)
        if await groups_same_rule(g.s, conv_id, src) is not None:
            raise api_error("Die Regel ist schon in der Gruppe", "The rule is already in this group")
        rule = await chain_copy(g.s, src, *group_follows(g.s, conv_id, g.user.id))
        await tell_owner(g.s, g.user.id, await root_of(g.s, src), group_id=conv_id)
        await auto_follow_members(conv_id)
        return jsonify(rule=await rule_detail(g.s, g.user, rule))

    async def copy_into_group(conv_id, src):
        """A real copy of one of my rules (with its idle chain). Returns (rule, None) or (None, reason)."""
        if src is None or src.user_id != g.user.id or src.template or src.challenge_id:
            return None, "not_found"
        if await group_copies(g.s, conv_id, g.user.id)[0](src.id) is not None or \
                (src.source_rule_id and await groups_same_rule(g.s, conv_id, src) is not None):
            return None, "already"
        return await chain_copy(g.s, src, *group_copies(g.s, conv_id, g.user.id)), None

    async def remove_from_group(conv, rule):
        """Take a rule out of the group (with its subscriptions). Returns why not, or None."""
        if rule is None or rule.group_id != conv.id or not rule.template:
            return "not_found"
        if g.user.id not in (rule.user_id, conv.created_by):
            return "not_allowed"
        if await g.s.scalar(select(func.count()).select_from(ChallengeRule)
                            .join(Challenge, Challenge.id == ChallengeRule.challenge_id)
                            .where(ChallengeRule.rule_id == rule.id, Challenge.status.in_(("upcoming", "running")))):
            return "challenge"
        await remove_copies(g.s, rule.id)
        await g.s.execute(update(Rule).where(Rule.idle_rule_id == rule.id).values(idle_rule_id=None, idle_days=0))
        await g.s.delete(rule)
        await g.s.flush()
        return None

    @app.post("/api/conversations/<int:conv_id>/rules/bulk")
    @authed
    async def group_rules_bulk(conv_id):
        """{"action": "copy" | "remove" | "public" | "private", "ids": [...]}: copy many of my rules into the
        group, take many group rules out, or change the visibility of the group's own rules. The answer lists what was skipped and why."""
        conv = await admin_group(conv_id)
        data = await request.get_json(force=True) or {}
        ids = list(dict.fromkeys(int(i) for i in data.get("ids", []) if str(i).isdigit()))
        done, skipped = 0, []
        for rid in ids:
            rule = await g.s.get(Rule, rid)
            if data.get("action") == "copy":
                _, reason = await copy_into_group(conv_id, rule)
            elif data.get("action") == "remove":
                reason = await remove_from_group(conv, rule)
            elif data.get("action") in ("public", "private"):
                # the group's own rules; only who created the rule or the group decides
                if rule is None or rule.group_id != conv_id or not rule.template:
                    reason = "not_found"
                elif rule.source_rule_id:
                    reason = "not_own"
                elif not await is_admin(g.s, conv, g.user.id):
                    reason = "forbidden"
                else:
                    reason = None
                    was_public, rule.public = bool(rule.public), data["action"] == "public"
                    if was_public != rule.public:
                        await g.s.flush()
                        await group_visibility_changed(conv_id, rule, was_public)
                        await propagate(g.s, rule)
            else:
                raise api_error("Aktion unbekannt", "Unknown action")
            if reason:
                skipped.append(dict(id=rid, reason=reason))
            else:
                done += 1
        if data.get("action") == "copy" and done:
            await auto_follow_members(conv_id)
        return await state_response(done=done, skipped=skipped)

    @app.post("/api/rules/subscribe-many")
    @authed
    async def subscribe_many():
        """{"ids": [...]}: subscribe to many rules at once (skips own, challenge and invisible ones)."""
        ids = list(dict.fromkeys(int(i) for i in (await request.get_json(force=True) or {}).get("ids", []) if str(i).isdigit()))
        done, skipped = 0, []
        for rid in ids:
            rule = await g.s.get(Rule, rid)
            if rule is None or not await can_view_rule(g.s, g.user, rule):
                skipped.append(dict(id=rid, reason="not_found"))
                continue
            root = await root_of(g.s, rule)
            if not await can_view_rule(g.s, g.user, root):
                skipped.append(dict(id=rid, reason="members_only"))
                continue
            if root.challenge_id or (root.user_id == g.user.id and not root.template):
                skipped.append(dict(id=rid, reason="own" if not root.challenge_id else "challenge"))
                continue
            new = await my_copy(g.s, g.user.id, root.id) is None
            if new and await users_same_rule(g.s, g.user.id, root) is not None:
                skipped.append(dict(id=rid, reason="already"))
                continue
            await chain_copy(g.s, root, *user_copies(g.s, g.user.id))
            if new:
                await tell_owner(g.s, g.user.id, root)
            done += 1
        return await state_response(done=done, skipped=skipped)

    async def auto_follow_members(conv_id):
        for uid in list(await g.s.scalars(select(ConversationMember.user_id).where(
                ConversationMember.conversation_id == conv_id, ConversationMember.auto_follow.is_(True)))):
            await follow_all(g.s, conv_id, uid)

    @app.delete("/api/conversations/<int:conv_id>/subscriptions")
    @authed
    async def unsubscribe_group(conv_id):
        """Unsubscribe all rules of this group (and stop following all automatically). Rules a running
        challenge plays with stay until leaving it."""
        await group(conv_id)
        member = await g.s.get(ConversationMember, (conv_id, g.user.id))
        member.auto_follow = False
        tpl_ids = select(Rule.id).where(Rule.group_id == conv_id, Rule.template.is_(True))
        kept = 0
        for copy in list(await g.s.scalars(select(Rule).where(
                Rule.user_id == g.user.id, Rule.template.is_(False), Rule.source_rule_id.in_(tpl_ids)))):
            if await challenge_lock(g.s, g.user.id, copy.source_rule_id):
                kept += 1
                continue
            await g.s.execute(update(Rule).where(Rule.idle_rule_id == copy.id).values(idle_rule_id=None, idle_days=0))
            await g.s.delete(copy)
        await g.s.flush()
        return await state_response(kept=kept)

    @app.put("/api/conversations/<int:conv_id>/auto-follow")
    @authed
    async def set_auto_follow(conv_id):
        """{"on": bool}. On: subscribe to everything the group follows, now and later.
        Off: nothing is unsubscribed (that stays a manual decision)."""
        await group(conv_id)
        member = await g.s.get(ConversationMember, (conv_id, g.user.id))
        member.auto_follow = bool((await request.get_json(force=True) or {}).get("on"))
        if member.auto_follow:
            await follow_all(g.s, conv_id, g.user.id)
        await g.s.flush()
        return await state_response(auto_follow=member.auto_follow)

    async def own_group_rule(conv_id, rule_id):
        conv = await group(conv_id)
        rule = await g.s.get(Rule, rule_id)
        if rule is None or rule.group_id != conv_id or not rule.template:
            raise api_error("Regel nicht gefunden", "Rule not found", 404)
        if not await is_admin(g.s, conv, g.user.id):
            raise api_error("Das dürfen nur Admins der Gruppe", "Only the group's admins can do that", 403)
        return rule

    async def group_visibility_changed(conv_id, rule, was_public):
        if was_public and not rule.public:   # private again: only members keep their subscriptions
            async def member(copy):
                return not copy.template and await is_member(g.s, conv_id, copy.user_id)
            await remove_copies(g.s, rule.id, keep=member)
        # linked rules share their visibility (see app.sync_link_visibility)
        if rule.public and rule.idle_rule_id:
            target = await g.s.get(Rule, rule.idle_rule_id)
            if target is not None and target.group_id == conv_id:
                target.public = True
        if not rule.public:
            await g.s.execute(update(Rule).where(Rule.group_id == conv_id, Rule.template.is_(True),
                                                 Rule.idle_rule_id == rule.id).values(public=False))

    @app.put("/api/conversations/<int:conv_id>/rules/<int:rule_id>")
    @authed
    async def update_group_rule(conv_id, rule_id):
        rule = await own_group_rule(conv_id, rule_id)
        if rule.source_rule_id:
            raise api_error("Diese Regel folgt ihrem Original – nur dessen Ersteller ändert sie",
                            "This rule follows its original – only its creator changes it")
        fields = parse_rule(await request.get_json(force=True) or {}, icons)
        was_public = bool(rule.public)
        for k, v in fields.items():          # incl. "public": others may subscribe (in public groups)
            setattr(rule, k, v)
        rule.current = rule.base
        await group_visibility_changed(conv_id, rule, was_public)
        await propagate(g.s, rule)
        return jsonify(rule=await rule_detail(g.s, g.user, rule))

    @app.delete("/api/conversations/<int:conv_id>/rules/<int:rule_id>")
    @authed
    async def delete_group_rule(conv_id, rule_id):
        rule = await own_group_rule(conv_id, rule_id)
        if await remove_from_group(await group(conv_id), rule) == "challenge":
            raise api_error("Eine laufende Challenge spielt mit dieser Regel", "A running challenge uses this rule")
        return jsonify(ok=True)

    # ---- follow requests (private accounts)

    @app.get("/api/follow-requests")
    @authed
    async def follow_requests():
        users = await g.s.scalars(select(User).join(FollowRequest, FollowRequest.requester_id == User.id)
                                  .where(FollowRequest.target_id == g.user.id).order_by(FollowRequest.created_at.desc()))
        return jsonify(users=await cards(g.s, g.user.id, users))

    async def drop_request_notification(requester_id):
        await g.s.execute(delete(Notification).where(Notification.user_id == g.user.id,
                                                     Notification.actor_id == requester_id, Notification.kind == "request"))

    async def pending_from(username):
        requester = await g.s.scalar(select(User).where(User.username == username.lower()))
        req = await g.s.get(FollowRequest, (requester.id, g.user.id)) if requester else None
        if req is None:
            raise api_error("Keine Anfrage", "No request", 404)
        return requester, req

    @app.post("/api/follow-requests/<username>/accept")
    @authed
    async def accept_request(username):
        requester, req = await pending_from(username)
        if not await g.s.get(Follow, (requester.id, g.user.id)):
            g.s.add(Follow(follower_id=requester.id, followee_id=g.user.id))
            notify(g.s, requester.id, g.user.id, "accepted")
        await g.s.delete(req)
        await drop_request_notification(requester.id)
        return jsonify(ok=True)

    @app.delete("/api/follow-requests/<username>")
    @authed
    async def decline_request(username):
        requester, req = await pending_from(username)
        await g.s.delete(req)
        await drop_request_notification(requester.id)
        return jsonify(ok=True)

    @app.delete("/api/users/<username>/follower")
    @authed
    async def remove_follower(username):
        """Remove someone from my followers (they can request again)."""
        other = await g.s.scalar(select(User).where(User.username == username.lower()))
        if other is not None:
            await g.s.execute(delete(Follow).where(Follow.follower_id == other.id, Follow.followee_id == g.user.id))
        return jsonify(ok=True)


async def delete_user_community(s, user_id):
    await s.execute(delete(FollowRequest).where((FollowRequest.requester_id == user_id) | (FollowRequest.target_id == user_id)))
    for tpl in list(await s.scalars(select(Rule).where(Rule.user_id == user_id, Rule.template.is_(True), Rule.group_id.is_not(None)))):
        await remove_copies(s, tpl.id)
        await s.delete(tpl)
