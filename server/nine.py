"""One of the Nine: nine titles, each held by exactly one player (or nobody yet).

Eight are counted - the holder is whoever has the most, a tie goes to the older account (Hermes: whoever
got there first, like the King of Reporters badge it mirrors):

    goat          the most ranked momentum (players in the competition, daily limit on)
    hermes        the most implemented reports (= the King of Reporters)
    yapper        the most comments written
    charmeur      the most likes on own posts
    gamemaster    the most subscribers on own personal rules and the own decay (links are followed to the
                  origin; rules made in a group don't count)
    veteran       the most days with the app used online (UserActivity)
    clown         the most posts and stories ever made - deleted ones still count (User.posts_total);
                  at most POSTS_PER_DAY a day
    supercharged  the biggest change of the ranked momentum in the last SUPERCHARGED_DAYS days

The ninth, the Larper, is elected: whoever the players think gathers momentum undeservedly. An election
opens on the 1st of every month for that day, and at any time there is no Larper (the start, the Larper
deleted the account). It closes once its day is over AND at least VOTERS_NEEDED players voted - otherwise
it stays open until they did. One vote per player (changeable), never for yourself. Clients: the "The Nine"
tab of the rankings, the profile's title icons, the Nine screens (web/src/screens/Nine.tsx = ui/Nine.kt).
"""
import time
from datetime import datetime, timedelta, timezone

from quart import g, jsonify, request
from sqlalchemy import DateTime, ForeignKey, Integer, String, delete, func, or_, select
from sqlalchemy.orm import Mapped, mapped_column

from . import limits
from .db import (Base, Comment, DailyDecay, Event, Post, PostLike, Report, Rule, Settings, User, UserActivity,
                 utcnow)

KEYS = ("goat", "larper", "hermes", "yapper", "charmeur", "gamemaster", "veteran", "clown", "supercharged")
COUNTED = tuple(k for k in KEYS if k != "larper")
POSTS_PER_DAY = 100
VOTERS_NEEDED = 10
SUPERCHARGED_DAYS = 30
TABLE_TTL = 10.0          # seconds the counted tables are kept (profiles and rankings ask often)
ENSURE_EVERY = 20.0       # seconds between checks whether an election has to open or close


class Election(Base):
    """A Larper election; closed_at / winner_id once it ended."""
    __tablename__ = "elections"
    id: Mapped[int] = mapped_column(primary_key=True)
    opened_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    closes_at: Mapped[datetime] = mapped_column(DateTime)        # not before this; later until enough voted
    closed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    winner_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    reason: Mapped[str] = mapped_column(String(10), default="monthly")    # monthly | vacant


class ElectionVote(Base):
    __tablename__ = "election_votes"
    election_id: Mapped[int] = mapped_column(ForeignKey("elections.id"), primary_key=True)
    voter_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    candidate_id: Mapped[int] = mapped_column(Integer, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


def iso(dt):
    return dt.replace(tzinfo=timezone.utc).isoformat() if dt else None


# ---------------------------------------------------------------- the counted titles

_tables = {"at": 0.0, "value": None}


async def stat_tables(s, fresh=False):
    """{key: {user id: value}} for the counted titles (only players with a value > 0), kept TABLE_TTL seconds."""
    if not fresh and _tables["value"] is not None and time.monotonic() - _tables["at"] < TABLE_TTL:
        return _tables["value"]
    competing = select(Settings.user_id).where(Settings.daily_limit.is_(True))
    out = {}
    out["goat"] = {u: v for u, v in (await s.execute(
        select(User.id, User.momentum).where(User.username_set.is_(True), User.id.in_(competing), User.momentum > 0))).all()}
    since = utcnow() - timedelta(days=SUPERCHARGED_DAYS)
    out["supercharged"] = {u: v for u, v in (await s.execute(
        select(Event.user_id, func.sum(Event.delta)).where(Event.created_at >= since, Event.undone.is_(False),
                                                           Event.user_id.in_(competing))
        .group_by(Event.user_id))).all() if v and v > 0}
    # Hermes ties go to whoever got there first: the value carries the time as a tiny fraction (earlier = larger)
    out["hermes"] = {u: n - (first.timestamp() / 1e12 if first else 0) for u, n, first in (await s.execute(
        select(Report.user_id, func.count(), func.min(Report.status_at))
        .where(Report.status == "done", Report.user_id.is_not(None)).group_by(Report.user_id))).all()}
    out["yapper"] = {u: n for u, n in (await s.execute(select(Comment.user_id, func.count()).group_by(Comment.user_id))).all()}
    out["charmeur"] = {u: n for u, n in (await s.execute(
        select(Post.user_id, func.count()).select_from(PostLike).join(Post, Post.id == PostLike.post_id)
        .group_by(Post.user_id))).all()}
    out["gamemaster"] = await _subscriber_counts(s)
    out["veteran"] = {u: n for u, n in (await s.execute(
        select(UserActivity.user_id, func.count()).group_by(UserActivity.user_id))).all()}
    out["clown"] = {u: n for u, n in (await s.execute(
        select(User.id, User.posts_total).where(User.posts_total > 0))).all()}
    _tables["at"], _tables["value"] = time.monotonic(), out
    return out


def forget_tables():
    """Counted something: the next look computes anew."""
    _tables["value"] = None


async def _subscriber_counts(s):
    """{user id: people whose rules / decay link to that person's own ones}."""
    from .decays import GroupDecay
    rows = (await s.execute(select(Rule.id, Rule.user_id, Rule.source_rule_id, Rule.group_id, Rule.challenge_id,
                                   Rule.template))).all()
    by_id = {r.id: r for r in rows}
    counts = {}
    for r in rows:
        if r.source_rule_id is None or r.template:        # roots; group / challenge templates aren't subscribers
            continue
        cur, seen = r, set()
        while cur.source_rule_id is not None and cur.source_rule_id in by_id and cur.id not in seen:
            seen.add(cur.id)
            cur = by_id[cur.source_rule_id]
        if cur.group_id is None and cur.challenge_id is None and not cur.template and cur.user_id != r.user_id:
            counts[cur.user_id] = counts.get(cur.user_id, 0) + 1
    gds = {gd.id: gd for gd in await s.scalars(select(GroupDecay))}

    def decay_origin(gd):
        seen = set()
        while gd is not None and gd.source_decay_id is not None and gd.id not in seen:
            seen.add(gd.id)
            gd = gds.get(gd.source_decay_id)
        return gd.source_user_id if gd is not None else None

    follows = (await s.execute(select(DailyDecay.user_id, DailyDecay.follow_user_id, DailyDecay.follow_decay_id).where(
        or_(DailyDecay.follow_user_id.is_not(None), DailyDecay.follow_decay_id.is_not(None))))).all()
    for user_id, fu, fd in follows:
        target = fu if fu else decay_origin(gds.get(fd))
        if target and target != user_id:
            counts[target] = counts.get(target, 0) + 1
    return counts


def _holder(table):
    """(user id, value) with the most, ties to the older account; None without anyone."""
    if not table:
        return None
    uid = min(table, key=lambda u: (-table[u], u))
    return uid, table[uid]


def _rank(table, user_id):
    """(my value, my place) - None when I have nothing counted yet."""
    mine = table.get(user_id)
    if mine is None:
        return None
    return mine, 1 + sum(1 for v in table.values() if v > mine)


def _shown(key, value):
    """The number a player sees (Hermes' tie-break fraction gone)."""
    return int(round(value)) if key != "goat" and key != "supercharged" else value


# ---------------------------------------------------------------- the Larper's election

_ensure_at = {"at": 0.0}


async def open_election(s):
    return await s.scalar(select(Election).where(Election.closed_at.is_(None)).order_by(Election.id.desc()).limit(1))


async def larper_id(s):
    """The winner of the last finished election, while that account exists."""
    e = await s.scalar(select(Election).where(Election.closed_at.is_not(None), Election.winner_id.is_not(None))
                       .order_by(Election.closed_at.desc(), Election.id.desc()).limit(1))
    if e is None:
        return None
    return e.winner_id if await s.get(User, e.winner_id) is not None else None


async def tally(s, election_id):
    """[(candidate id, votes, time the count was reached)] best first; ties to whoever got there first."""
    rows = (await s.execute(
        select(ElectionVote.candidate_id, func.count(), func.max(ElectionVote.created_at))
        .where(ElectionVote.election_id == election_id).group_by(ElectionVote.candidate_id))).all()
    return sorted(rows, key=lambda r: (-r[1], r[2], r[0]))


async def ensure(s, now=None, force=False):
    """Open the election when one is due, close the running one when it may end. Cheap enough to run on
    every state request, so it only looks every ENSURE_EVERY seconds unless forced (a vote)."""
    if not force and time.monotonic() - _ensure_at["at"] < ENSURE_EVERY:
        return
    _ensure_at["at"] = time.monotonic()
    now = now or utcnow()
    e = await open_election(s)
    if e is not None and now >= e.closes_at:
        rows = await tally(s, e.id)
        if rows and sum(n for _, n, _ in rows) >= VOTERS_NEEDED:
            e.closed_at, e.winner_id = now, rows[0][0]
            e = None
    if e is not None:
        return
    today = limits.server_day(now)
    month_start = limits.day_bounds(today.replace(day=1))[0]
    opened_this_month = await s.scalar(select(func.count()).select_from(Election).where(Election.opened_at >= month_start))
    monthly = today.day == 1 and not opened_this_month
    if monthly or await larper_id(s) is None:
        closes = limits.day_bounds(today)[1] if monthly else now + timedelta(days=1)
        s.add(Election(opened_at=now, closes_at=closes, reason="monthly" if monthly else "vacant"))
        await s.flush()


def next_first(now=None):
    """When the next monthly election opens (the 1st, 0:00 server time) as naive UTC."""
    today = limits.server_day(now or utcnow())
    first = (today.replace(day=1) + timedelta(days=32)).replace(day=1)
    return limits.day_bounds(first)[0]


async def election_json(s, me):
    """What the election tab shows: the running election (votes, mine, the leading candidates) or when the
    next one comes, plus the current Larper."""
    from .social import card_map
    e = await open_election(s)
    larper = await larper_id(s)
    ids = {larper} if larper else set()
    top, my_vote, voters = [], None, 0
    if e is not None:
        rows = (await tally(s, e.id))[:10]
        voters = await s.scalar(select(func.count()).select_from(ElectionVote).where(ElectionVote.election_id == e.id))
        my_vote = await s.scalar(select(ElectionVote.candidate_id).where(ElectionVote.election_id == e.id,
                                                                         ElectionVote.voter_id == me.id))
        ids |= {r[0] for r in rows} | ({my_vote} if my_vote else set())
    people = await card_map(s, me.id, [i for i in ids if i])
    return dict(
        open=e is not None, closes_at=iso(e.closes_at) if e else None, opened_at=iso(e.opened_at) if e else None,
        voters=voters, needed=VOTERS_NEEDED,
        my_vote=people.get(my_vote) if my_vote else None,
        top=[dict(user=people[c], votes=n) for c, n, _ in (rows if e is not None else []) if c in people],
        larper=people.get(larper) if larper else None,
        next_at=iso(next_first()) if e is None else None,
    )


async def election_open(s):
    await ensure(s)
    return await open_election(s) is not None


# ---------------------------------------------------------------- holders, profiles, counting posts

async def holders(s):
    """{key: (user id, value)} for the titles somebody holds."""
    tables = await stat_tables(s)
    out = {k: h for k in COUNTED if (h := _holder(tables[k]))}
    larper = await larper_id(s)
    if larper:
        out["larper"] = (larper, None)
    return out


async def titles_of(s, user_id):
    """The title keys a player holds, in the fixed order of KEYS."""
    await ensure(s)
    held = await holders(s)
    return [k for k in KEYS if held.get(k, (None,))[0] == user_id]


def count_post(user, api_error):
    """A post or story is being made: counts for the Clown forever, at most POSTS_PER_DAY a day."""
    today = limits.server_day()
    if user.posts_day != today:
        user.posts_day, user.posts_today = today, 0
    if (user.posts_today or 0) >= POSTS_PER_DAY:
        raise api_error(f"Mehr als {POSTS_PER_DAY} Posts am Tag gehen nicht – morgen wieder",
                        f"No more than {POSTS_PER_DAY} posts a day – again tomorrow")
    user.posts_today = (user.posts_today or 0) + 1
    user.posts_total = (user.posts_total or 0) + 1
    forget_tables()


async def delete_user(s, user_id):
    """Account deletion: the votes go, a Larper leaving opens the next election."""
    await s.execute(delete(ElectionVote).where(or_(ElectionVote.voter_id == user_id, ElectionVote.candidate_id == user_id)))
    forget_tables()
    _ensure_at["at"] = 0.0


# ---------------------------------------------------------------- API

def register(app, authed, ApiError):
    _ensure_at["at"] = 0.0                     # a new app (tests make many): look right away
    forget_tables()

    @app.get("/api/nine")
    @authed
    async def nine():
        """All nine titles: who holds each (with the value), and my own value and place."""
        await ensure(g.s)
        tables = await stat_tables(g.s)
        held = await holders(g.s)
        ids = {h[0] for h in held.values()}
        from .social import card_map
        people = await card_map(g.s, g.user.id, list(ids))
        titles = []
        for k in KEYS:
            h = held.get(k)
            mine = _rank(tables[k], g.user.id) if k != "larper" else None
            titles.append(dict(
                key=k, holder=people.get(h[0]) if h else None,
                value=_shown(k, h[1]) if h and h[1] is not None else None,
                mine=dict(value=_shown(k, mine[0]), rank=mine[1]) if mine else None,
                total=len(tables[k]) if k != "larper" else None,
            ))
        return jsonify(titles=titles, election=await election_json(g.s, g.user))

    @app.get("/api/nine/election")
    @authed
    async def election():
        await ensure(g.s)
        return jsonify(election=await election_json(g.s, g.user))

    @app.post("/api/nine/election/vote")
    @authed
    async def vote():
        """{"username": ...}: my vote in the running election (a second vote replaces the first)."""
        from .social import is_blocked
        await ensure(g.s, force=True)
        e = await open_election(g.s)
        if e is None:
            raise ApiError("Gerade läuft keine Larper-Wahl", "There's no Larper election right now")
        data = await request.get_json(force=True) or {}
        name = str(data.get("username", "")).strip().lstrip("@").lower()
        cand = await g.s.scalar(select(User).where(func.lower(User.username) == name, User.username_set.is_(True)))
        if cand is None:
            raise ApiError("Spieler nicht gefunden", "Player not found")
        if cand.id == g.user.id:
            raise ApiError("Für dich selbst kannst du nicht stimmen", "You can't vote for yourself")
        if await is_blocked(g.s, g.user.id, cand.id):
            raise ApiError("Spieler nicht gefunden", "Player not found")
        row = await g.s.get(ElectionVote, (e.id, g.user.id))
        if row is None:
            g.s.add(ElectionVote(election_id=e.id, voter_id=g.user.id, candidate_id=cand.id))
        else:
            row.candidate_id, row.created_at = cand.id, utcnow()
        await g.s.flush()
        await ensure(g.s, force=True)              # the tenth vote after the day may end it
        return jsonify(election=await election_json(g.s, g.user))
