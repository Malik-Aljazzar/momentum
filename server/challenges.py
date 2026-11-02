"""Group challenges.

A challenge belongs to a group chat and runs over whole server days [start_day, end_day].
It plays with rules from the group's list (ChallengeRule). When it starts (or when someone
joins a running one) every participant subscribes to them. While taking part, these
subscriptions can't be removed; on leaving / at the end they go away again unless the
participant chose to keep them (keep_rules). Subscriptions that existed before stay.

Score = net momentum of a participant's non-undone events from the challenge rules inside
the period, after joining (decelerators count negative; the daily limit applies as for every
other rule).

(Older challenges had their own rule templates with temporary copies (Rule.challenge_id);
they keep working until they end.)

  ranking: place 1/2/3 get reward1/2/3 (only defined rewards, only with a score > 0);
           equal scores: whoever got there first (earlier last event) ranks higher.
  target:  everyone with score >= target gets reward1.

Rewards become challenge credit, paid out over the following days into the room each day leaves
under its limit (service.apply_credit). They're absolute numbers, at most days x today's limit;
in target mode at most the target (100 % of what had to be collected).
Leaving is final (no rejoin, no reward). Only the creator can cancel; a challenge can't be
changed after creation (participants joined under its conditions).
"""
from datetime import timedelta, timezone

from quart import g, jsonify, request
from sqlalchemy import delete, func, or_, select, update

from . import limits
from .db import (Challenge, ChallengeParticipant, ChallengeRule, Conversation, ConversationMember, Event, Message,
                 Rule, User, utcnow)
from .social import card_map, notify

MIN_DAYS = 7          # a challenge runs at least a week (report #19)
MAX_DAYS = 90
MAX_START_IN = 30
MAX_RULES = 10
MAX_REWARD = 100_000
MAX_TARGET = 1_000_000


def window(ch):
    return limits.day_bounds(ch.start_day)[0], limits.day_bounds(ch.end_day)[1]


async def scores(s, ch):
    """{user_id: (score, last event time)} of participants."""
    start, end = window(ch)
    rule_ids = await challenge_rule_ids(s, ch)
    copies = select(Rule.id).where(Rule.source_rule_id.in_(rule_ids), Rule.template.is_(False))
    rows = await s.execute(
        select(Event.user_id, func.coalesce(func.sum(Event.delta), 0.0), func.max(Event.created_at))
        .join(ChallengeParticipant, (ChallengeParticipant.challenge_id == ch.id)
              & (ChallengeParticipant.user_id == Event.user_id))
        .where(or_(Event.challenge_id == ch.id,
                   Event.rule_id.in_(copies) & (Event.created_at >= ChallengeParticipant.joined_at)),
               Event.undone.is_(False), Event.created_at >= start, Event.created_at < end)
        .group_by(Event.user_id))
    return {uid: (total, last) for uid, total, last in rows}


async def challenge_rule_ids(s, ch):
    return list(await s.scalars(select(ChallengeRule.rule_id).where(ChallengeRule.challenge_id == ch.id)))


async def challenge_rules(s, ch):
    """The group rules it plays with (older challenges: their own templates)."""
    ids = await challenge_rule_ids(s, ch)
    if ids:
        return list(await s.scalars(select(Rule).where(Rule.id.in_(ids)).order_by(Rule.id)))
    return await templates(s, ch)


async def subscribe_rules(s, ch, user_id):
    """Challenge start / joining a running one: subscribe to its rules (existing subscriptions stay as they are)."""
    from .community import chain_copy, my_copy, user_copies
    from .decays import force_follow
    for tpl in list(await s.scalars(select(Rule).where(Rule.id.in_(await challenge_rule_ids(s, ch))).order_by(Rule.id))):
        if await my_copy(s, user_id, tpl.id) is None:
            await chain_copy(s, tpl, *user_copies(s, user_id, via_challenge_id=ch.id))
    await force_follow(s, ch, user_id)          # its decay replaces mine while I take part


async def release_rules(s, ch, user_id=None):
    """Leaving / end: the subscriptions the challenge made go away, unless the participant keeps them."""
    from .decays import release
    await release(s, ch, user_id)
    for p in await participants(s, ch, include_left=True):
        if user_id is not None and p.user_id != user_id:
            continue
        mine = select(Rule.id).where(Rule.user_id == p.user_id, Rule.via_challenge_id == ch.id)
        if p.keep_rules:
            await s.execute(update(Rule).where(Rule.id.in_(mine)).values(via_challenge_id=None))
        else:
            ids = list(await s.scalars(mine))
            if ids:
                await s.execute(update(Rule).where(Rule.idle_rule_id.in_(ids)).values(idle_rule_id=None))
                await s.execute(delete(Rule).where(Rule.id.in_(ids)))
    if user_id is None:
        await take_copies(s, ch.id)
    else:
        await take_copies(s, ch.id, user_id)


async def participants(s, ch, include_left=False):
    q = select(ChallengeParticipant).where(ChallengeParticipant.challenge_id == ch.id)
    if not include_left:
        q = q.where(ChallengeParticipant.left.is_(False))
    return list(await s.scalars(q))


def ranked(parts, score_map):
    """Participants sorted by score (desc), ties: earlier last event first, no events last."""
    far = utcnow() + timedelta(days=36500)

    def key(p):
        score, last = score_map.get(p.user_id, (0.0, None))
        return (-score, last or far, p.joined_at)
    return sorted(parts, key=key)


async def give_copies(s, ch, user_id):
    if await s.scalar(select(func.count()).select_from(Rule).where(
            Rule.user_id == user_id, Rule.challenge_id == ch.id, Rule.template.is_(False))):
        return
    pos = await s.scalar(select(func.coalesce(func.max(Rule.position), 0)).where(Rule.user_id == user_id))
    from .community import copy_fields
    for i, tpl in enumerate(await templates(s, ch)):
        copy = Rule(user_id=user_id, position=pos + 1 + i, source_rule_id=tpl.id, challenge_id=ch.id, public=False)
        copy_fields(tpl, copy)
        s.add(copy)


async def take_copies(s, ch_id, user_id=None):
    q = delete(Rule).where(Rule.challenge_id == ch_id, Rule.template.is_(False))
    if user_id is not None:
        q = q.where(Rule.user_id == user_id)
    await s.execute(q)


async def templates(s, ch):
    return list(await s.scalars(select(Rule).where(Rule.challenge_id == ch.id, Rule.template.is_(True)).order_by(Rule.id)))


async def claim(s, ch, old, new):
    """Atomic status change, so two parallel requests never start/finish a challenge twice."""
    res = await s.execute(update(Challenge).where(Challenge.id == ch.id, Challenge.status == old).values(status=new))
    if res.rowcount:
        ch.status = new
    return bool(res.rowcount)


async def start(s, ch):
    if await claim(s, ch, "upcoming", "running"):
        for p in await participants(s, ch):
            await give_copies(s, ch, p.user_id)
            await subscribe_rules(s, ch, p.user_id)


async def finish(s, ch):
    if not await claim(s, ch, "running", "finishing"):
        return
    parts = await participants(s, ch)
    score_map = await scores(s, ch)
    rewards = [ch.reward1, ch.reward2, ch.reward3]
    for place, p in enumerate(ranked(parts, score_map), start=1):
        p.score = score_map.get(p.user_id, (0.0, None))[0]
        p.rank = place
        if ch.mode == "ranking":
            p.reward = rewards[place - 1] if place <= 3 and p.score > 0 else 0.0
        else:
            p.reward = ch.reward1 if p.score >= ch.target else 0.0
        if p.reward > 0:
            user = await s.get(User, p.user_id)
            if user is not None:
                user.challenge_credit = (user.challenge_credit or 0.0) + p.reward
        notify(s, p.user_id, ch.created_by, "ch_end", challenge_id=ch.id, to_self=True)
    await release_rules(s, ch)
    ch.status = "finished"


async def cancel(s, ch):
    for p in await participants(s, ch):
        notify(s, p.user_id, ch.created_by, "ch_cancel", challenge_id=ch.id)
    await release_rules(s, ch)
    ch.status = "cancelled"


async def tick(s):
    """Start and finish challenges whose day has come (runs on every authenticated request)."""
    today = limits.server_day()
    for ch in list(await s.scalars(select(Challenge).where(
            ((Challenge.status == "upcoming") & (Challenge.start_day <= today))
            | ((Challenge.status == "running") & (Challenge.end_day < today))))):
        if ch.status == "upcoming":
            await start(s, ch)
        if ch.status == "running" and ch.end_day < today:
            await finish(s, ch)


async def in_window(s, rule, at):
    """A challenge rule only counts while its challenge runs."""
    ch = await s.get(Challenge, rule.challenge_id)
    if ch is None or ch.status != "running":
        return False
    start_, end = window(ch)
    return start_ <= at < end


async def leave_group(s, conv_id, user_id):
    """Leaving a group ends its challenges for the user and detaches group-rule subscriptions
    (except rules a public group shows to everyone: those keep following)."""
    for ch in await s.scalars(select(Challenge).where(Challenge.conversation_id == conv_id,
                                                      Challenge.status.in_(("upcoming", "running")))):
        p = await s.get(ChallengeParticipant, (ch.id, user_id))
        if p is not None and not p.left:
            p.left = True
            await release_rules(s, ch, user_id)
    conv = await s.get(Conversation, conv_id)
    group_tpls = select(Rule.id).where(Rule.group_id == conv_id, Rule.template.is_(True))
    if conv is not None and conv.public:
        group_tpls = group_tpls.where(Rule.public.is_(False), Rule.source_rule_id.is_(None))
    await s.execute(update(Rule).where(Rule.user_id == user_id, Rule.source_rule_id.in_(group_tpls),
                                       Rule.template.is_(False)).values(source_rule_id=None))


async def delete_group(s, conv_id):
    """Group disappears: running challenges are cancelled, group rules removed with their subscriptions."""
    from .community import remove_copies
    for ch in list(await s.scalars(select(Challenge).where(Challenge.conversation_id == conv_id,
                                                           Challenge.status.in_(("upcoming", "running"))))):
        await release_rules(s, ch)
        ch.status = "cancelled"
    for tpl in list(await s.scalars(select(Rule).where(Rule.group_id == conv_id, Rule.template.is_(True)))):
        await remove_copies(s, tpl.id)
        await s.delete(tpl)
    from .decays import delete_group_decays
    await delete_group_decays(s, conv_id)


async def _decay_json(s, me_id, ch):
    if not ch.decay_id:
        return None
    from .decays import GroupDecay, group_decay_json
    gd = await s.get(GroupDecay, ch.decay_id)
    me = await s.get(User, me_id)
    return await group_decay_json(s, me, gd) if gd is not None else None


async def _admin(s, ch, me_id):
    from .chat import is_admin
    return await is_admin(s, await s.get(Conversation, ch.conversation_id), me_id)


async def challenge_json(s, me_id, ch, full=True):
    today = limits.server_day()
    parts = await participants(s, ch, include_left=True)
    active = [p for p in parts if not p.left]
    mine = next((p for p in parts if p.user_id == me_id), None)
    if ch.status == "finished":
        score_map = {p.user_id: (p.score, None) for p in active}
        order = sorted(active, key=lambda p: (p.rank or 10**9))
    else:
        score_map = await scores(s, ch)
        order = ranked(active, score_map)
    people = await card_map(s, me_id, [p.user_id for p in active] + [ch.created_by])
    board = [dict(user=people.get(p.user_id), score=score_map.get(p.user_id, (0.0, None))[0],
                  rank=p.rank if ch.status == "finished" else i + 1, reward=p.reward)
             for i, p in enumerate(order) if p.user_id in people]
    out = dict(
        id=ch.id, conversation_id=ch.conversation_id, title=ch.title, mode=ch.mode, status=ch.status, banner=ch.banner,
        start_day=ch.start_day.isoformat(), end_day=ch.end_day.isoformat(),
        days=(ch.end_day - ch.start_day).days + 1,
        days_left=max(0, (ch.end_day - today).days + 1) if ch.status == "running" else None,
        starts_in=max(0, (ch.start_day - today).days) if ch.status == "upcoming" else None,
        target=ch.target, rewards=[r for r in (ch.reward1, ch.reward2, ch.reward3) if r > 0],
        creator=people.get(ch.created_by), participants=len(active),
        joined=mine is not None and not mine.left, left=mine is not None and mine.left,
        my_score=score_map.get(me_id, (0.0, None))[0] if mine and not mine.left else None,
        my_rank=next((b["rank"] for b in board if b["user"] and b["user"]["is_me"]), None),
        my_reward=mine.reward if mine else 0.0,
        keep_rules=bool(mine and mine.keep_rules),
        decay=await _decay_json(s, me_id, ch) if full else None,
        can_join=mine is None and ch.status in ("upcoming", "running"),
        can_cancel=ch.status in ("upcoming", "running") and (ch.created_by == me_id or await _admin(s, ch, me_id)),
    )
    if full:
        from .community import rule_detail
        me = await s.get(User, me_id)
        out["leaderboard"] = board
        out["rules"] = [await rule_detail(s, me, r) for r in await challenge_rules(s, ch)]
    return out


def register(app, authed, api_error, parse_rule, icons, state_response):
    async def group(conv_id):
        conv = await g.s.get(Conversation, conv_id)
        if conv is None or conv.kind != "group" or not await g.s.get(ConversationMember, (conv_id, g.user.id)):
            raise api_error("Gruppe nicht gefunden", "Group not found", 404)
        return conv

    async def get_challenge(ch_id):
        ch = await g.s.get(Challenge, ch_id)
        if ch is None or not await g.s.get(ConversationMember, (ch.conversation_id, g.user.id)):
            raise api_error("Challenge nicht gefunden", "Challenge not found", 404)
        return ch

    def whole(data, key, lo, hi, default=0):
        try:
            v = int(float(data.get(key, default) or 0))
        except (TypeError, ValueError):
            raise api_error(f"{key}: Zahl erwartet", f"{key}: number expected")
        if not lo <= v <= hi:
            raise api_error(f"{key}: {lo}–{hi}", f"{key}: {lo}–{hi}")
        return v

    @app.get("/api/conversations/<int:conv_id>/challenges")
    @authed
    async def list_challenges(conv_id):
        await group(conv_id)
        rows = await g.s.scalars(select(Challenge).where(Challenge.conversation_id == conv_id)
                                 .order_by(Challenge.id.desc()).limit(30))
        return jsonify(challenges=[await challenge_json(g.s, g.user.id, ch, full=False) for ch in rows])

    @app.post("/api/conversations/<int:conv_id>/challenges")
    @authed
    async def create_challenge(conv_id):
        conv = await group(conv_id)
        from .chat import is_admin
        if not await is_admin(g.s, conv, g.user.id):
            raise api_error("Challenges starten nur Admins der Gruppe", "Only the group's admins start challenges", 403)
        data = await request.get_json(force=True) or {}
        title = str(data.get("title", "")).strip()
        if not 1 <= len(title) <= 60:
            raise api_error("Titel: 1–60 Zeichen", "Title: 1–60 characters")
        mode = data.get("mode")
        if mode not in ("ranking", "target"):
            raise api_error("Modus ungültig", "Invalid mode")
        days = whole(data, "days", MIN_DAYS, MAX_DAYS)
        start_in = whole(data, "start_in", 0, MAX_START_IN)
        reward1 = whole(data, "reward1", 1, MAX_REWARD)
        reward2 = reward3 = target = 0
        if mode == "ranking":
            reward2 = whole(data, "reward2", 0, reward1)
            reward3 = whole(data, "reward3", 0, reward2)
        else:
            target = whole(data, "target", 1, MAX_TARGET)
        specs = data.get("rules") or []
        if not isinstance(specs, list) or not 1 <= len(specs) <= MAX_RULES:
            raise api_error(f"1–{MAX_RULES} Regeln", f"1–{MAX_RULES} rules")
        rules = []
        for spec in specs:
            # only rules from the group's list (followed, created or copied into the group)
            try:
                src = await g.s.get(Rule, int((spec or {}).get("rule_id")))
            except (TypeError, ValueError):
                src = None
            if src is None or not src.template or src.group_id != conv.id:
                raise api_error("Challenges spielen mit Regeln aus der Gruppe", "Challenges use rules from the group", 404)
            if src not in rules:
                rules.append(src)
        if not 1 <= len(rules) <= MAX_RULES:
            raise api_error(f"1–{MAX_RULES} Regeln", f"1–{MAX_RULES} rules")
        from .service import parse_banner
        try:
            banner = parse_banner(data.get("banner"))
        except ValueError:
            raise api_error("Banner unbekannt", "Unknown banner")
        # rewards: absolute, at most days x today's limit; in target mode at most the target itself
        most = days * (await limits.cap_for(g.s, limits.server_day())).cap
        if mode == "target":
            most = min(most, target)
        if reward1 > most:
            raise api_error(f"Belohnung höchstens {int(most)}", f"Reward at most {int(most)}")
        if mode == "target" and not any(r.kind == "acc" for r in rules):
            raise api_error("Für ein Ziel braucht es mindestens einen Beschleuniger",
                            "A target needs at least one accelerator")
        decay_id = None
        if data.get("decay_id") is not None:          # at most one decay, one of the group's
            from .decays import GroupDecay
            try:
                gd = await g.s.get(GroupDecay, int(data["decay_id"]))
            except (TypeError, ValueError):
                gd = None
            if gd is None or gd.group_id != conv.id:
                raise api_error("Challenges spielen mit Decays aus der Gruppe", "Challenges use decays from the group", 404)
            decay_id = gd.id
        today = limits.server_day()
        ch = Challenge(conversation_id=conv.id, created_by=g.user.id, title=title, mode=mode, decay_id=decay_id,
                       start_day=today + timedelta(days=start_in), end_day=today + timedelta(days=start_in + days - 1),
                       target=target, reward1=reward1, reward2=reward2, reward3=reward3, banner=banner)
        g.s.add(ch)
        await g.s.flush()
        g.s.add_all([ChallengeRule(challenge_id=ch.id, rule_id=r.id) for r in rules])
        g.s.add(ChallengeParticipant(challenge_id=ch.id, user_id=g.user.id))   # the creator takes part
        # announced in the group as a challenge card
        g.s.add(Message(conversation_id=conv.id, user_id=g.user.id, text="", share_kind="challenge", share_ref=str(ch.id)))
        conv.last_message_at = utcnow()
        await g.s.flush()
        if start_in == 0:
            await start(g.s, ch)
        await g.s.flush()
        return jsonify(challenge=await challenge_json(g.s, g.user.id, ch))

    @app.get("/api/challenges/<int:ch_id>")
    @authed
    async def challenge_get(ch_id):
        return jsonify(challenge=await challenge_json(g.s, g.user.id, await get_challenge(ch_id)))

    @app.post("/api/challenges/<int:ch_id>/join")
    @authed
    async def challenge_join(ch_id):
        ch = await get_challenge(ch_id)
        p = await g.s.get(ChallengeParticipant, (ch.id, g.user.id))
        if p is not None and p.left:
            raise api_error("Du hast diese Challenge verlassen", "You left this challenge")
        if ch.status not in ("upcoming", "running"):
            raise api_error("Die Challenge ist vorbei", "The challenge is over")
        if p is None:
            g.s.add(ChallengeParticipant(challenge_id=ch.id, user_id=g.user.id))
            await g.s.flush()
            if ch.status == "running":
                await give_copies(g.s, ch, g.user.id)
                await subscribe_rules(g.s, ch, g.user.id)
        await g.s.flush()
        return jsonify(challenge=await challenge_json(g.s, g.user.id, ch))

    @app.post("/api/challenges/<int:ch_id>/leave")
    @authed
    async def challenge_leave(ch_id):
        ch = await get_challenge(ch_id)
        p = await g.s.get(ChallengeParticipant, (ch.id, g.user.id))
        if p is None or p.left:
            raise api_error("Du machst nicht mit", "You're not taking part")
        if ch.status not in ("upcoming", "running"):
            raise api_error("Die Challenge ist vorbei", "The challenge is over")
        p.left = True
        await release_rules(g.s, ch, g.user.id)
        await g.s.flush()
        return jsonify(challenge=await challenge_json(g.s, g.user.id, ch))

    @app.put("/api/challenges/<int:ch_id>/keep-rules")
    @authed
    async def challenge_keep_rules(ch_id):
        """{"keep": bool}: keep the challenge's rules after leaving / the end."""
        ch = await get_challenge(ch_id)
        p = await g.s.get(ChallengeParticipant, (ch.id, g.user.id))
        if p is None or p.left:
            raise api_error("Du machst nicht mit", "You're not taking part")
        p.keep_rules = bool((await request.get_json(force=True) or {}).get("keep"))
        await g.s.flush()
        return jsonify(challenge=await challenge_json(g.s, g.user.id, ch))

    @app.post("/api/challenges/<int:ch_id>/cancel")
    @authed
    async def challenge_cancel(ch_id):
        ch = await get_challenge(ch_id)
        from .chat import is_admin
        if ch.created_by != g.user.id and not await is_admin(g.s, await g.s.get(Conversation, ch.conversation_id), g.user.id):
            raise api_error("Abbrechen können nur Admins der Gruppe", "Only the group's admins can cancel it", 403)
        if ch.status not in ("upcoming", "running"):
            raise api_error("Die Challenge ist vorbei", "The challenge is over")
        await cancel(g.s, ch)
        await g.s.flush()
        return jsonify(challenge=await challenge_json(g.s, g.user.id, ch))


async def delete_user_challenges(s, user_id):
    """Account deletion: leave challenges (their rule templates stay for the others)."""
    for p in list(await s.scalars(select(ChallengeParticipant).where(ChallengeParticipant.user_id == user_id))):
        await take_copies(s, p.challenge_id, user_id)
        await s.delete(p)
    await s.execute(delete(ChallengeRule).where(ChallengeRule.rule_id.in_(
        select(Rule.id).where(Rule.user_id == user_id, Rule.template.is_(True)))))
