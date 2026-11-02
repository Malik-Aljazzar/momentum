"""Rules tapped with a post's slide or a story (like a sticker in the picture's corner).

Only the poster's rules that are visible on their profile (own public rules, subscriptions shown on
the profile) and can be tapped; up to MAX_PER_SLIDE per slide, each up to MAX_TIMES times ("x3").
They are tapped when posting, with the normal rules (value, daily limit). A tap on the shown
trigger opens the rule like on the profile.
"""
from collections import defaultdict

from sqlalchemy import select

from . import limits, service
from .db import MediaTrigger

MAX_PER_SLIDE = 5
MAX_TIMES = 10


class InvalidTriggers(Exception):
    pass


async def profile_rules(s, user):
    """{rule id: rule} the user may attach: shown on the profile, tappable, not a challenge copy."""
    return {r.id: r for r in await service.load_rules(s, user)
            if not r.challenge_id and r.tappable is not False and (r.in_profile if r.source_rule_id else r.public)}


async def parse(s, user, raw, slides):
    """[{"slide": i, "rule_id": id, "times": n}] -> [(slide, rule, times)]. slides: how many there are."""
    if not raw:
        return []
    if not isinstance(raw, list):
        raise InvalidTriggers()
    allowed = await profile_rules(s, user)
    per_slide = defaultdict(int)
    out = []
    for t in raw:
        try:
            slide, rule_id, times = int(t.get("slide", 0)), int(t["rule_id"]), int(t.get("times", 1))
        except (AttributeError, KeyError, TypeError, ValueError):
            raise InvalidTriggers()
        rule = allowed.get(rule_id)
        if rule is None or not 0 <= slide < max(1, slides) or not 1 <= times <= MAX_TIMES:
            raise InvalidTriggers()
        per_slide[slide] += 1
        if per_slide[slide] > MAX_PER_SLIDE:
            raise InvalidTriggers()
        out.append((slide, rule, times))
    return out


async def apply(s, user, parsed, post_id=None, story_id=None):
    """Store the triggers and tap their rules (a reached daily limit just stops an accelerator)."""
    for pos, (slide, rule, times) in enumerate(parsed):
        t = MediaTrigger(post_id=post_id, story_id=story_id, slide=slide, rule_id=rule.id, name=rule.name,
                         icon=rule.icon, kind=rule.kind, times=times, position=pos)
        s.add(t)
        points = 0.0
        for _ in range(times):
            try:
                r = await service.trigger(s, user, rule)
            except limits.LimitReached:
                break
            # the value the player plays with: ranked momentum, or the free score without the limit
            points += r["delta"] if service.ranked(user) else r.get("free_delta", r["delta"])
        t.points = points
    await s.flush()


def trigger_json(t):
    return dict(slide=t.slide, rule_id=t.rule_id, name=t.name, icon=t.icon, kind=t.kind, times=t.times, points=t.points or 0)


async def for_posts(s, post_ids):
    out = defaultdict(list)
    if post_ids:
        for t in await s.scalars(select(MediaTrigger).where(MediaTrigger.post_id.in_(post_ids)).order_by(MediaTrigger.position)):
            out[t.post_id].append(trigger_json(t))
    return out


async def for_stories(s, story_ids):
    out = defaultdict(list)
    if story_ids:
        for t in await s.scalars(select(MediaTrigger).where(MediaTrigger.story_id.in_(story_ids)).order_by(MediaTrigger.position)):
            out[t.story_id].append(trigger_json(t))
    return out
