"""Glue between the database and the pure momentum rules."""
import secrets
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import func, select, update

from . import momentum as m
from . import limits
from .db import DailyDecay, Event, Follow, Rule, Settings, User, utcnow

# Order = progression. The milestones double from `milestone_step` on (momentum.LADDER).
VEHICLES = [
    ("fahrrad", "Fahrrad", "land"),
    ("motorroller", "Motorroller", "land"),
    ("kleinwagen", "Kleinwagen", "land"),
    ("pkw", "PKW", "land"),
    ("suv", "SUV", "land"),
    ("lkw", "LKW", "land"),
    ("panzer", "Panzer", "land"),
    ("kleinflugzeug", "Kleinflugzeug", "air"),
    ("passagierflugzeug", "Passagierflugzeug", "air"),
    ("kampfjet", "Kampfjet", "air"),
    ("shuttle", "Space Shuttle", "space"),
    ("raumschiff", "Raumschiff", "space"),
    ("kreuzer", "Raumkreuzer", "space"),
]

# Backgrounds for the vehicle (profile, start screen), groups and challenges; drawn by the app
# from pixelart/banners.py layers. None = the classic scene of the vehicle's category.
BANNERS = ("dusk", "sky", "cosmos", "city", "desert", "forest", "beach", "snow", "aurora", "sakura",
           "volcano", "canyon", "rain", "moon", "synthwave", "autumn", "fields", "deepsea", "storm", "sunrise",
           "cottoncandy", "hearts", "strawberry", "meadow", "glitter", "lavender", "balloons", "jungle",
           "harbor", "glacier",
           # anime-inspired settings
           "wallcity", "ninjavillage", "redclouds", "grassroute", "spiritforest", "skyisland", "tournament",
           "pirates", "wisteria", "moonprincess", "neotokyo", "comet", "torii", "bathhouse", "fuji",
           "ramenalley", "schoolroof",
           # space
           "ringplanet", "redplanet", "iceplanet", "lavaplanet", "earthorbit", "twinsuns", "nebula", "asteroids",
           "blackhole", "galaxy", "battlestation")
# Which vehicle types a banner suits (own vehicle). Groups and challenges may use any banner.
SPACE_BANNERS = {"cosmos", "moon", "ringplanet", "redplanet", "iceplanet", "lavaplanet", "earthorbit", "twinsuns",
                 "nebula", "asteroids", "blackhole", "galaxy", "battlestation", "hyperspace", "ultrahyperspace"}
AIR_ONLY = {"sky", "skyisland"}
LAND_ONLY = {"deepsea"}
BANNER_FIELDS = {"land": "banner", "air": "banner_air", "space": "banner_space"}


def banner_kinds(key):
    if key in SPACE_BANNERS:
        return {"space"}
    if key in AIR_ONLY:
        return {"air"}
    if key in LAND_ONLY:
        return {"land"}
    return {"land", "air"}            # landscapes work as sky for planes (no road)
# Easter egg: the milestone after the last vehicle (4 million) jumps the spaceship into hyperspace
# and unlocks this banner for the own vehicle; it's locked again below that.
HYPERSPACE = "hyperspace"
# Secret: the golden hyperspace at 16 million; it isn't even shown (locked) before that.
ULTRA = "ultrahyperspace"


def ranked(user):
    """Daily limit on = takes part in the community competition (ranked momentum runs)."""
    return bool(user.settings.daily_limit)


def score(user):
    """The value the player plays with: ranked momentum, or the free score without the limit.

    Vehicle, milestones and hyperspace follow it; others see whether it is ranked.
    """
    return user.momentum if ranked(user) else (user.free_score or 0.0)


def hyperspace_unlocked(user):
    """4 million and flying the spaceship (the last vehicle) - hyperspace is the spaceship's background.

    Exclusive to the competition (daily limit on): the free score never reaches it.
    """
    if not ranked(user):
        return False
    value = score(user)
    return value >= m.hyperspace_at(user.settings.milestone_step, len(VEHICLES)) and         m.vehicle_index(value, user.settings.milestone_step, len(VEHICLES)) == len(VEHICLES) - 1


def ultra_unlocked(user):
    value = score(user)
    return hyperspace_unlocked(user) and value >= m.ultra_at(user.settings.milestone_step, len(VEHICLES))


def vehicle_kind(user):
    """road | air | space of the current vehicle ("land" for road)."""
    idx = m.vehicle_index(score(user), user.settings.milestone_step, len(VEHICLES))
    return VEHICLES[idx][2]


def own_banners(user):
    return {kind: getattr(user, field) for kind, field in BANNER_FIELDS.items()}


def shown_banner(user):
    """The banner behind the current vehicle (hyperspace only while it is unlocked)."""
    key = getattr(user, BANNER_FIELDS[vehicle_kind(user)])
    if key == HYPERSPACE and not hyperspace_unlocked(user) or key == ULTRA and not ultra_unlocked(user):
        return None
    return key


def parse_banner(value, user=None, kind=None):
    """Valid banner key or None (default); anything else raises ValueError.

    Own vehicle: pass the user and the vehicle type; the banner must suit it, hyperspace only
    when unlocked. Groups and challenges: any banner except hyperspace.
    """
    if value in (None, ""):
        return None
    if value in (HYPERSPACE, ULTRA):
        unlocked = hyperspace_unlocked if value == HYPERSPACE else ultra_unlocked
        if user is not None and kind == "space" and unlocked(user):
            return value
        raise ValueError(value)
    if value not in BANNERS or (kind is not None and kind not in banner_kinds(value)):
        raise ValueError(value)
    return value


# Example rules for new accounts; names in German / English.
DEFAULT_RULES = [
    (dict(kind="acc", icon="check", mode="constant", base=100), "Aufgabe erledigt", "Task done"),
    (dict(kind="acc", icon="dumbbell", mode="linear", base=400, param=100, min_value=100,
          reset_days=2), "Sport", "Workout"),
    (dict(kind="acc", icon="book", mode="exponential", base=300, param=0.7, min_value=30,
          reset_days=1), "Lesen", "Reading"),
    (dict(kind="acc", icon="drop", mode="constant", base=20), "Wasser getrunken", "Drank water"),
    (dict(kind="dec", icon="phone", mode="constant", base=150), "Doomscrolling", "Doomscrolling"),
    (dict(kind="dec", icon="burger", mode="linear", base=200, param=50, min_value=50,
          reset_days=3), "Fast Food", "Fast food"),
]


def to_utc_naive(dt):
    return dt.astimezone(timezone.utc).replace(tzinfo=None)


def local_date(naive_utc, tz):
    return naive_utc.replace(tzinfo=timezone.utc).astimezone(tz).date()


def user_tz(user):
    return ZoneInfo(user.settings.timezone)


def confirm_state_of(user):
    from quart import current_app, has_app_context
    from .mail import confirm_state
    mailer = getattr(current_app, "mail", None) if has_app_context() else None
    return confirm_state(user, bool(mailer and mailer.enabled))


async def create_user(s, email, password_hash, lang="de"):
    """New account: placeholder username until the user picks one (username_set=False)."""
    placeholder = "u" + secrets.token_hex(6)       # replaced when the user picks a username
    user = User(username=placeholder, email=email, username_set=False,
                password_hash=password_hash, display_name=placeholder)
    user.settings = Settings()
    user.decay = DailyDecay()
    s.add(user)
    await s.flush()
    tz = user_tz(user)
    user.decay.last_processed_day = datetime.now(tz).date() - timedelta(days=1)
    for i, (r, de, en) in enumerate(DEFAULT_RULES):
        s.add(Rule(user_id=user.id, position=i, current=r["base"],
                   name=de if lang == "de" else en, **r))
    return user


def _apply(s, user, delta, kind, label, icon, rule_id=None, at=None, free=None, both=False, **extra):
    """Change ranked momentum and free score (both never below 0) and log the event.

    `free` is the change of the free score (default: the same as `delta`). Without the daily
    limit the ranked momentum is paused (no taps, no decay) unless `both` (rewards, reset).
    Returns (actual ranked delta, event); the event keeps both actual changes for undo.
    """
    if not both and not ranked(user):
        free, delta = (delta if free is None else free), 0.0
    old = user.momentum
    user.momentum = max(0.0, old + delta)
    actual = user.momentum - old
    old_free = user.free_score or 0.0
    user.free_score = max(0.0, old_free + (delta if free is None else free))
    event = Event(user_id=user.id, rule_id=rule_id, kind=kind, label=label, icon=icon,
                  delta=actual, momentum_after=user.momentum, created_at=at or utcnow(),
                  free_delta=user.free_score - old_free, **extra)
    s.add(event)
    return actual, event


async def gains_by_day(s, user, first_day, last_day, tz):
    start = to_utc_naive(datetime.combine(first_day, time.min, tz))
    end = to_utc_naive(datetime.combine(last_day + timedelta(days=1), time.min, tz))
    # daily decay works on the value the player plays with (free score without the limit)
    col = Event.delta if ranked(user) else func.coalesce(Event.free_delta, Event.delta)
    rows = await s.execute(
        select(Event.created_at, col).where(
            Event.user_id == user.id, Event.kind == "acc", Event.undone.is_(False),
            Event.created_at >= start, Event.created_at < end))
    gains = {}
    for created, delta in rows:
        d = local_date(created, tz)
        gains[d] = gains.get(d, 0.0) + delta
    return gains


async def catch_up(s, user):
    """Apply daily decay for every finished day since the last run."""
    tz = user_tz(user)
    today = datetime.now(tz).date()
    dd = user.decay
    if dd.last_processed_day is None:
        dd.last_processed_day = today - timedelta(days=1)
        return
    last = today - timedelta(days=1)
    first = max(dd.last_processed_day + timedelta(days=1), last - timedelta(days=365))
    if first > last:
        return
    # claim the days first: the app opens with several requests at once, and each catches up -
    # only the one whose update still finds the old day books the decay (report #27: it came twice)
    claimed = await s.execute(update(DailyDecay).where(
        DailyDecay.user_id == dd.user_id, DailyDecay.last_processed_day == dd.last_processed_day,
    ).values(last_processed_day=last).execution_options(synchronize_session=False))
    if claimed.rowcount != 1:
        await s.refresh(dd)
        return
    gains = await gains_by_day(s, user, first, last, tz)
    from .decays import effective
    src, _ = await effective(s, dd)             # own decay, or the followed one (my own streak either way)
    results, dd.streak = m.run_daily_decay(
        first, last, gains, src.base, src.threshold,
        src.catalyst_mode, src.catalyst_a, src.catalyst_b, dd.streak)
    for r in results:
        if r.decay > 0:
            end_of_day = datetime.combine(r.day, time(23, 59, 59), tz)
            _apply(s, user, -m.applied("dec", r.decay), "daily", "", "calendar", at=to_utc_naive(end_of_day))
    dd.last_processed_day = last


def avatar_url(key):
    """Blob key "avatars/<uid>/<name>.webp" -> path served by the backend."""
    return f"/{key}" if key and key.startswith("avatars/") else None


def span(n, unit):
    """reset_days / idle_days with their unit as a duration."""
    return timedelta(hours=n) if unit == "h" else timedelta(days=n)


def rule_value(rule, tz, today):
    if rule.reset_unit == "h" and rule.reset_days:
        # hours: measured from the last tap (days count calendar days)
        if rule.mode == "constant" or rule.current is None:
            return rule.base
        last = rule.last_triggered_at
        return rule.base if last is not None and utcnow() - last >= span(rule.reset_days, "h") else rule.current
    last_day = local_date(rule.last_triggered_at, tz) if rule.last_triggered_at else None
    return m.effective_value(rule.mode, rule.base, rule.current, rule.reset_days,
                             last_day, today)


MAX_OFFLINE_AGE = timedelta(days=14)


def clamp_time(at):
    """Client timestamps (offline actions): not in the future, at most 14 days old."""
    now = utcnow()
    if at is None or at > now:
        return now
    return max(at, now - MAX_OFFLINE_AGE)


def guards_idle(rule):
    """Tapping it keeps an automatic tap away (idle_days with an idle rule, not paused)."""
    return bool(rule.idle_days and rule.idle_rule_id and not rule.idle_paused)


async def trigger(s, user, rule, at=None, auto_from=None):
    """Apply a rule (optionally at an earlier time from an offline queue).

    Accelerators are capped by the daily limit for the competitive momentum. The free score
    always gets the full value; without the limit setting a reached limit doesn't block the tap.
    Returns dict(delta, free_delta, milestone, capped, hyperspace).
    """
    at = clamp_time(at)
    tz = user_tz(user)
    value = rule_value(rule, tz, local_date(at, tz))
    full = m.applied(rule.kind, value)
    amount = full
    capped = False
    if rule.kind == "acc":
        left = m.applied("dec", await limits.remaining(s, user.id, limits.server_day(at)))
        if ranked(user):
            if left <= 0:
                # limit used up: still a tap with +0 when it keeps the rule's automatic decelerator away
                if auto_from is not None or not guards_idle(rule):
                    raise limits.LimitReached()
                amount, capped = 0.0, True
            elif amount > left:
                amount, capped = left, True
    step = user.settings.milestone_step
    old_idx = m.vehicle_index(score(user), step, len(VEHICLES))
    was_unlocked = hyperspace_unlocked(user)
    sign = 1 if rule.kind == "acc" else -1
    actual, event = _apply(s, user, sign * amount, rule.kind, rule.name, rule.icon, rule_id=rule.id, at=at,
                           free=sign * full,
                           prev_current=rule.current, prev_triggered_at=rule.last_triggered_at,
                           challenge_id=rule.challenge_id, auto_from_rule_id=auto_from)
    rule.current = m.next_value(rule.mode, rule.base, value, rule.param, rule.min_value)
    rule.last_triggered_at = max(rule.last_triggered_at or at, at)
    new_idx = m.vehicle_index(score(user), step, len(VEHICLES))
    # only a new vehicle counts (on the last stage more points are no new milestone)
    milestone = new_idx > old_idx
    if milestone:
        s.add(Event(user_id=user.id, kind="milestone", label=VEHICLES[new_idx][0], icon="",
                    delta=0.0, free_delta=0.0, momentum_after=user.momentum, created_at=at))
    return dict(delta=actual if ranked(user) else event.free_delta, free_delta=event.free_delta,
                milestone=milestone, capped=capped,
                hyperspace=not was_unlocked and hyperspace_unlocked(user))


async def undo(s, user, event):
    """Revert an accelerator/decelerator event from the history."""
    step = user.settings.milestone_step
    user.momentum = max(0.0, user.momentum - event.delta)
    free = event.delta if event.free_delta is None else event.free_delta
    user.free_score = max(0.0, (user.free_score or 0.0) - free)
    event.undone = True
    if event.rule_id:
        later = await s.scalar(select(func.count()).select_from(Event).where(
            Event.rule_id == event.rule_id, Event.undone.is_(False), Event.id != event.id,
            Event.created_at >= event.created_at, Event.kind.in_(("acc", "dec"))))
        rule = await s.get(Rule, event.rule_id)
        if rule is not None and not later:      # it was the rule's latest use: restore its state
            rule.current = event.prev_current
            rule.last_triggered_at = event.prev_triggered_at
    # milestones that are no longer reached disappear again
    idx = m.vehicle_index(score(user), step, len(VEHICLES))
    reached = {k for k, _, _ in VEHICLES[:idx + 1]}
    for ms in await s.scalars(select(Event).where(Event.user_id == user.id, Event.kind == "milestone",
                                                  Event.undone.is_(False))):
        if ms.label not in reached:
            ms.undone = True


async def grant(s, user, amount, label, at=None):
    """Challenge reward: outside the daily limit, may unlock the next vehicle."""
    step = user.settings.milestone_step
    old_idx = m.vehicle_index(score(user), step, len(VEHICLES))
    _apply(s, user, m.applied("acc", amount), "reward", label, "trophy", at=at, both=True)   # earned ranked
    new_idx = m.vehicle_index(score(user), step, len(VEHICLES))
    if new_idx > old_idx:
        s.add(Event(user_id=user.id, kind="milestone", label=VEHICLES[new_idx][0], icon="",
                    delta=0.0, free_delta=0.0, momentum_after=user.momentum, created_at=at or utcnow()))


REPORT_BONUS = 0.01        # of today's limit, for every bug report / feature request


async def report_bonus(s, user):
    """Thanks for a report: 1 % of today's limit (rounded up). Counts against the limit like an accelerator
    (nothing once it's reached), the free score gets it in full. Kind "report", no rule: in the own history,
    not in the profile; not an active tap for the limit's average. Returns what was added (0: nothing)."""
    day = limits.server_day()
    full = m.applied("acc", (await limits.cap_for(s, day)).cap * REPORT_BONUS)
    amount = full
    if ranked(user):
        amount = min(amount, m.applied("dec", await limits.remaining(s, user.id, day)))
        if amount <= 0:
            return 0.0
    step = user.settings.milestone_step
    old_idx = m.vehicle_index(score(user), step, len(VEHICLES))
    actual, event = _apply(s, user, amount, "report", "", "bulb", free=full)
    new_idx = m.vehicle_index(score(user), step, len(VEHICLES))
    if new_idx > old_idx:
        s.add(Event(user_id=user.id, kind="milestone", label=VEHICLES[new_idx][0], icon="",
                    delta=0.0, free_delta=0.0, momentum_after=user.momentum, created_at=event.created_at))
    return actual if ranked(user) else event.free_delta


async def apply_credit(s, user):
    """Challenge rewards are paid out over the days: whenever a new day starts, the credit fills the
    room the day before left under its limit (credit 100, limit 1000, 950 collected -> +50)."""
    yesterday = limits.server_day() - timedelta(days=1)
    day = user.credit_day + timedelta(days=1) if user.credit_day else yesterday
    day = max(day, yesterday - timedelta(days=30))
    while day <= yesterday and (user.challenge_credit or 0) >= 1:
        room = (await limits.cap_for(s, day)).cap - await limits.gained_on(s, user.id, day)
        amount = float(int(min(user.challenge_credit, max(0.0, room))))
        if amount >= 1:
            await grant(s, user, amount, "Challenge-Bonus")
            user.challenge_credit -= amount
        day += timedelta(days=1)
    user.credit_day = yesterday


IDLE_MAX_CATCH_UP = 60        # automatic taps per rule and request (long absences)


def valid_idle_target(rule, target, user_id):
    """The automatic rule: an own rule of the other kind (no group/challenge rule)."""
    return (target is not None and target.id != rule.id and target.user_id == user_id and not target.template
            and target.challenge_id is None and target.kind != rule.kind)


async def process_idle(s, user):
    """Rules not tapped for their idle_days: tap their idle_rule automatically, at the due time.

    Runs like the daily decay on every request and catches up missed periods. The automatic tap
    follows all normal rules (value, mode, rounding, daily limit for accelerators).
    """
    now = utcnow()
    for rule in [r for r in await load_rules(s, user) if r.idle_days and r.idle_rule_id and not r.idle_paused]:
        target = await s.get(Rule, rule.idle_rule_id)
        if not valid_idle_target(rule, target, user.id):
            rule.idle_rule_id = None
            continue
        start = max([t for t in (rule.last_triggered_at, rule.idle_since) if t is not None], default=None)
        if start is None:
            rule.idle_since = now
            continue
        due = start + span(rule.idle_days, rule.idle_unit)
        dues = []
        while due <= now and len(dues) < IDLE_MAX_CATCH_UP:
            dues.append(due)
            due += span(rule.idle_days, rule.idle_unit)
        if not dues:
            continue
        # claimed like the daily decay: requests at the same time mustn't both tap automatically
        old = rule.idle_since
        claimed = await s.execute(update(Rule).where(
            Rule.id == rule.id, Rule.idle_since.is_(None) if old is None else Rule.idle_since == old,
        ).values(idle_since=dues[-1]).execution_options(synchronize_session=False))
        if claimed.rowcount != 1:
            await s.refresh(rule)
            continue
        for at in dues:
            try:
                await trigger(s, user, target, at=at, auto_from=rule.id)
            except limits.LimitReached:
                pass                        # accelerator but no room left that day: nothing to add
        rule.idle_since = dues[-1]


async def lower_momentum(s, user, value):
    """Set the value the player plays with (ranked momentum, or the free score) down to `value`.

    Only lower (raising would be cheating); milestones no longer reached disappear from the history.
    """
    current = score(user)
    value = max(0.0, float(int(value)))
    if value >= current:
        return False
    if ranked(user):
        _apply(s, user, value - current, "reset", "", "", free=0.0)
    else:
        _apply(s, user, 0.0, "reset", "", "", free=value - current)
    idx = m.vehicle_index(score(user), user.settings.milestone_step, len(VEHICLES))
    reached = {k for k, _, _ in VEHICLES[:idx + 1]}
    for ms in await s.scalars(select(Event).where(Event.user_id == user.id, Event.kind == "milestone",
                                                  Event.undone.is_(False))):
        if ms.label not in reached:
            ms.undone = True
    return True


def reset_momentum(s, user):
    _apply(s, user, -user.momentum, "reset", "", "", free=-(user.free_score or 0.0), both=True)


async def public_rules(s, user, limit=50):
    """Rules the user marked as visible, plus the history of those rules (for the profile)."""
    tz = user_tz(user)
    today = datetime.now(tz).date()
    # own rules marked public, and subscribed ones the user chose to show (rule.in_profile)
    rules = [r for r in await load_rules(s, user) if not r.challenge_id
             and (r.in_profile if r.source_rule_id else r.public)]
    if not rules:
        return [], []
    events = await s.scalars(select(Event).where(Event.user_id == user.id, Event.rule_id.in_([r.id for r in rules]),
                                                 Event.undone.is_(False))
                             .order_by(Event.created_at.desc(), Event.id.desc()).limit(limit))
    return (
        [dict(id=r.id, kind=r.kind, name=r.name, icon=r.icon, mode=r.mode,
              value=m.applied(r.kind, rule_value(r, tz, today)))
         for r in rules],
        [dict(kind=e.kind, label=e.label, icon=e.icon, delta=e.delta,
              at=e.created_at.replace(tzinfo=timezone.utc).isoformat()) for e in events],
    )


async def follow_counts(s, user_id):
    followers = await s.scalar(select(func.count()).select_from(Follow).where(Follow.followee_id == user_id))
    following = await s.scalar(select(func.count()).select_from(Follow).where(Follow.follower_id == user_id))
    return dict(followers=followers, following=following)


async def load_rules(s, user):
    rows = await s.execute(
        select(Rule).where(Rule.user_id == user.id, Rule.template.is_(False)).order_by(Rule.position, Rule.id))
    return list(rows.scalars())


async def render_mentions(s, text):
    from .mentions import render_one
    return await render_one(s, text)


async def rule_origins(s, rules):
    """Where subscribed / challenge rules come from (they can't be edited by the subscriber)."""
    from .db import Challenge, Conversation
    linked = [r for r in rules if r.source_rule_id]
    if not linked:
        return {}
    roots = {r.id: r for r in await s.scalars(select(Rule).where(Rule.id.in_({r.source_rule_id for r in linked})))}
    owners = {u.id: u.username for u in await s.scalars(select(User).where(User.id.in_({r.user_id for r in roots.values()})))}
    out = {}
    for r in linked:
        root = roots.get(r.source_rule_id)
        if r.challenge_id:
            ch = await s.get(Challenge, r.challenge_id)
            out[r.id] = dict(origin="challenge", origin_label=ch.title if ch else "", root_id=r.source_rule_id,
                             challenge_id=r.challenge_id)
        elif root is not None and root.group_id:
            from .community import challenge_lock
            conv = await s.get(Conversation, root.group_id)
            out[r.id] = dict(origin="group", origin_label=conv.name if conv else "", root_id=root.id, challenge_id=None,
                             locked_by=await challenge_lock(s, r.user_id, root.id))
        elif root is not None:
            out[r.id] = dict(origin="sub", origin_label=owners.get(root.user_id, ""), root_id=root.id, challenge_id=None)
    return out


def vehicle_info(idx, step):
    """Where the player is on the milestone ladder (the client draws progress bars from it)."""
    return dict(index=idx, reached_at=m.milestone(idx, step),
                next_at=m.milestone(idx + 1, step) if idx + 1 < len(VEHICLES) else None,
                milestones=m.milestones(step, len(VEHICLES)),
                hyperspace_at=m.hyperspace_at(step, len(VEHICLES)))


async def decay_state(s, user, dd, today_gain, would_streak):
    """The decay I play with: its values, whether it's mine or followed (and from whom), public or not."""
    from .decays import challenge_lock, effective, tonight
    src, origin = await effective(s, dd)
    return dict(base=src.base, threshold=src.threshold, catalyst_mode=src.catalyst_mode,
                catalyst_a=src.catalyst_a, catalyst_b=src.catalyst_b, streak=dd.streak, today_gain=today_gain,
                tonight=tonight(src, would_streak), public=bool(dd.public), follow=origin,
                locked_by=await challenge_lock(s, user.id))


async def state(s, user):
    """Everything the client needs to render all screens."""
    tz = user_tz(user)
    today = datetime.now(tz).date()
    st, dd = user.settings, user.decay
    idx = m.vehicle_index(score(user), st.milestone_step, len(VEHICLES))
    today_gain = (await gains_by_day(s, user, today, today, tz)).get(today, 0.0)
    would_streak = dd.streak + 1 if today_gain < dd.threshold else 0
    rules = []
    own = await load_rules(s, user)
    origins = await rule_origins(s, own)
    names = {r.id: r.name for r in own}
    for r in own:
        rules.append(dict(
            id=r.id, kind=r.kind, name=r.name, icon=r.icon, mode=r.mode, base=r.base,
            param=r.param, min_value=r.min_value, reset_days=r.reset_days, reset_unit=r.reset_unit or "d",
            value=m.applied(r.kind, rule_value(r, tz, today)), client_id=r.client_id,
            public=bool(r.in_profile) if r.source_rule_id else r.public,      # subscribed: shown in my profile
            idle_days=r.idle_days or 0, idle_unit=r.idle_unit or "d", idle_rule_id=r.idle_rule_id, idle_paused=bool(r.idle_paused),
            tappable=r.tappable is not False,
            **{"locked_by": None, **origins.get(r.id, dict(origin="own", origin_label="", root_id=None, challenge_id=None))},
        ))
    events = await s.execute(
        select(Event).where(Event.user_id == user.id, Event.undone.is_(False))
        .order_by(Event.created_at.desc(), Event.id.desc()).limit(30))
    from .chat import unread_messages          # avoid import cycle
    from .social import unread_notifications
    from .updates import latest_ids
    return dict(
        unread=dict(messages=await unread_messages(s, user.id),
                    notifications=await unread_notifications(s, user.id),
                    **await latest_ids(s, user.id)),       # seen in the app = no system notification
        challenge_credit=user.challenge_credit or 0.0,
        user=dict(id=user.id, username=user.username, display_name=user.display_name, bio=await render_mentions(s, user.bio),
                  email=user.email, google_email=user.google_email, has_password=bool(user.password_hash),
                  needs_username=not user.username_set, private=user.private,
                  # the address still to confirm (server/mail.py): None, or when it's due / whether the app is locked
                  email_confirm=confirm_state_of(user),
                  banner=shown_banner(user), banners=own_banners(user), hyperspace=hyperspace_unlocked(user),
                  ultra=ultra_unlocked(user),
                  avatar=avatar_url(user.avatar), **(await follow_counts(s, user.id))),
        momentum=score(user),                     # the value the player plays with
        ranked_momentum=user.momentum,
        free_score=user.free_score or 0.0,
        vehicle=vehicle_info(idx, st.milestone_step),
        settings=dict(timezone=st.timezone, milestone_step=st.milestone_step,
                      haptics=st.haptics, show_history=st.show_history, daily_limit=st.daily_limit),
        decay=await decay_state(s, user, dd, today_gain, would_streak),
        rules=rules,
        limit=await limits.status(s, user.id),
        events=[dict(id=e.id, kind=e.kind, label=e.label, icon=e.icon,
                     delta=e.delta if ranked(user) or e.free_delta is None else e.free_delta,
                     undoable=e.kind in ("acc", "dec"),
                     auto_from=names.get(e.auto_from_rule_id) if e.auto_from_rule_id else None,
                     at=e.created_at.replace(tzinfo=timezone.utc).isoformat())
                for e in events.scalars()],
    )
