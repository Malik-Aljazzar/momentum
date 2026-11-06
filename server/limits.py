"""Daily momentum limit, shared by all players.

The limit is a growth rate that adapts once per day to how much the competition grew:
the average growth (accelerators minus decelerators, zero included) of the competing players
who tapped a rule themselves on day D-1 is compared with D-1's rate (automatic taps of idle rules
and the daily decay don't make a player count; undone taps neither - but a counted player's growth
includes their automatic taps):

    average > 0.75 * rate  ->  rate doubles (at most 12800)
    average < 0.25 * rate  ->  rate halves (at least 1000)
    otherwise              ->  rate stays

The first rate is 1000 (= the minimum). The value is computed once per day and stored, so it doesn't change
during the day. (Stored in DailyCap: cap = bias = the rate, average = the average it came from.)

Days are server days in MOMENTUM_TZ (default Europe/Berlin).
"""
import os
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import func, select
from sqlalchemy.dialects.sqlite import insert

from .db import Config, DailyCap, Event, Settings, UserActivity, utcnow

SERVER_TZ = ZoneInfo(os.environ.get("MOMENTUM_TZ", "Europe/Berlin"))
START_RATE = float(os.environ.get("MOMENTUM_START_RATE", 1000))
MIN_RATE, MAX_RATE = 1000.0, 12800.0   # minimum 1000 since 2026-11-06 (was 25)
RATE_KEY = "daily_cap_bias"          # one-off override for the next computed rate (server.admin rate)


class LimitReached(Exception):
    pass


def server_day(naive_utc=None):
    dt = (naive_utc or utcnow()).replace(tzinfo=timezone.utc)
    return dt.astimezone(SERVER_TZ).date()


def day_bounds(day):
    """[start, end) of a server day as naive UTC."""
    start = datetime.combine(day, time.min, SERVER_TZ).astimezone(timezone.utc).replace(tzinfo=None)
    end = datetime.combine(day + timedelta(days=1), time.min, SERVER_TZ).astimezone(timezone.utc).replace(tzinfo=None)
    return start, end


async def next_rate(s, day, average, freeze):
    """Rate (= limit) of day D, from the last stored rate before D (see module doc)."""
    override = await s.get(Config, RATE_KEY)
    if override is not None:
        if freeze:                   # used once
            await s.delete(override)
            await s.flush()
        return min(MAX_RATE, max(MIN_RATE, float(override.value)))
    prev = await s.scalar(select(DailyCap).where(DailyCap.day < day).order_by(DailyCap.day.desc()).limit(1))
    if prev is None:
        return START_RATE
    rate = prev.cap
    if average > 0.75 * rate:
        return min(MAX_RATE, max(rate * 2, MIN_RATE))
    if average < 0.25 * rate:
        return max(MIN_RATE, min(rate / 2, MAX_RATE))
    return min(MAX_RATE, max(MIN_RATE, rate))


async def set_rate(s, value):
    row = await s.get(Config, RATE_KEY)
    if row is None:
        s.add(Config(key=RATE_KEY, value=str(float(value))))
    else:
        row.value = str(float(value))


async def mark_active(s, user_id):
    """Remember that the player used the app online today (admin statistics)."""
    day = server_day()
    if await s.get(UserActivity, (user_id, day)) is None:
        # two requests at once both found none: the second one must not fail (UNIQUE user_id, day)
        await s.execute(insert(UserActivity).values(user_id=user_id, day=day).on_conflict_do_nothing())


LIMITED = ("acc", "dec", "report")   # event kinds that count against the daily limit


async def net_by_user(s, day):
    start, end = day_bounds(day)
    rows = await s.execute(
        select(Event.user_id, func.sum(Event.delta)).where(
            Event.kind.in_(LIMITED), Event.undone.is_(False),
            Event.created_at >= start, Event.created_at < end).group_by(Event.user_id))
    return dict(rows.all())


async def average_growth(s, day):
    """(average growth, players) of the competing players who actively tapped on that day - what the next
    day's rate comes from. Only own taps count (no automatic idle taps, no daily decay). Players without the
    daily limit play outside the competition: they don't set it either."""
    start, end = day_bounds(day)
    active = list(await s.scalars(
        select(Event.user_id).distinct().join(Settings, Settings.user_id == Event.user_id)
        .where(Event.kind.in_(("acc", "dec")), Event.undone.is_(False), Event.auto_from_rule_id.is_(None),
               Event.created_at >= start, Event.created_at < end, Settings.daily_limit.is_(True))))
    nets = await net_by_user(s, day)
    return (sum(nets.get(u, 0.0) for u in active) / len(active) if active else 0.0), len(active)


async def cap_for(s, day):
    """Stored limit of a day; computed from the day before on first use."""
    row = await s.get(DailyCap, day)
    if row is not None:
        return row
    prev = day - timedelta(days=1)
    average, players = await average_growth(s, prev)
    freeze = day <= server_day()     # never freeze a day that hasn't started
    rate = await next_rate(s, day, average, freeze)
    row = DailyCap(day=day, average=average, bias=rate, cap=rate, players=players)
    if freeze:
        s.add(row)
        await s.flush()
    return row


async def gained_on(s, user_id, day):
    """What counts against today's limit: accelerators (and report bonuses) minus decelerators, in order.

    The running total never drops below 0, so decelerating first doesn't create extra room:
    limit 200: +200, -50 -> 150 (50 left); -50, +250 -> capped at 200.
    """
    start, end = day_bounds(day)
    rows = await s.execute(select(Event.delta).where(
        Event.user_id == user_id, Event.kind.in_(LIMITED), Event.undone.is_(False),
        Event.created_at >= start, Event.created_at < end).order_by(Event.created_at, Event.id))
    total = 0.0
    for (delta,) in rows:
        total = max(0.0, total + delta)
    return total


async def remaining(s, user_id, day):
    cap = await cap_for(s, day)
    return max(0.0, cap.cap - await gained_on(s, user_id, day))


async def status(s, user_id):
    day = server_day()
    cap = await cap_for(s, day)
    gained = await gained_on(s, user_id, day)
    # day + tz: the app notices a new day while offline (yesterday's count no longer applies)
    # where tomorrow's rate is heading: today's average so far (report #46; it can change until midnight)
    today_average, today_players = await average_growth(s, day)
    next_cap = await next_rate(s, day + timedelta(days=1), today_average, freeze=False)
    # the day before's limit: the apps say so on a day's first start when it changed (null on the very first day)
    prev_cap = await s.scalar(select(DailyCap.cap).where(DailyCap.day < day).order_by(DailyCap.day.desc()).limit(1))
    return dict(cap=cap.cap, gained=gained, remaining=max(0.0, cap.cap - gained),
                average=round(cap.average, 1), bias=cap.bias, players=cap.players,
                today_average=round(today_average, 1), today_players=today_players, next_cap=next_cap,
                prev_cap=prev_cap, day=day.isoformat(), tz=SERVER_TZ.key)
