"""Momentum rules as pure functions (no database, no I/O).

Accelerators / decelerators ("rules") have a current value that is applied on
each click and then shrinks according to the rule's mode:
    constant     value stays at base
    linear       value -= param per click, never below min_value
    exponential  value *= param per click (0 < param < 1), never below min_value
After reset_days days without a click the value jumps back to base
(reset_days = 0 disables the reset).

Daily decay: every day momentum drops by `base`. On days where less than
`threshold` momentum was gained, a streak counter grows and a catalyst term
is added on top:
    linear       a * streak
    exponential  a * b^(streak-1)        (b >= 1, grows faster and faster)
    inverse      a * (1 - b^streak)      (0 < b < 1, grows fast, levels off at a)
"""
import math
from dataclasses import dataclass
from datetime import date, timedelta

RULE_MODES = ("constant", "linear", "exponential")
CATALYST_MODES = ("none", "linear", "exponential", "inverse")
CATALYST_CAP = 10_000_000


def effective_value(mode, base, current, reset_days, last_day, today):
    """Value the next click will apply."""
    if mode == "constant" or current is None:
        return base
    if reset_days and last_day is not None and (today - last_day).days >= reset_days:
        return base
    return current


def applied(kind, value):
    """Whole momentum only: accelerators round up, decelerators (and the daily decay) round down."""
    v = round(value, 6)            # float noise like 100.00000001 is not a fraction
    return math.ceil(v) if kind == "acc" else math.floor(v)


def next_value(mode, base, value, param, min_value):
    """Value after a click that applied `value`."""
    if mode == "linear":
        return max(min_value, value - param)
    if mode == "exponential":
        return max(min_value, value * param)
    return base


def catalyst(mode, a, b, streak):
    if streak <= 0 or mode == "none":
        return 0.0
    if mode == "linear":
        v = a * streak
    elif mode == "exponential":
        v = a * b ** (streak - 1)
    elif mode == "inverse":
        v = a * (1 - b ** streak)
    else:
        raise ValueError(f"unknown catalyst mode {mode!r}")
    return min(v, CATALYST_CAP)


@dataclass
class DayResult:
    day: date
    gained: float
    streak: int
    decay: float


def run_daily_decay(first_day, last_day, gains, base, threshold, mode, a, b, streak):
    """Evaluate the daily decay for each day in [first_day, last_day].

    gains: {date: momentum gained that day}. Returns (results, final streak).
    """
    results = []
    d = first_day
    while d <= last_day:
        gained = gains.get(d, 0.0)
        streak = streak + 1 if gained < threshold else 0
        results.append(DayResult(d, gained, streak, base + catalyst(mode, a, b, streak)))
        d += timedelta(days=1)
    return results, streak


# Milestones double: with the default step of 1000 they're at 1k, 2k, 4k ... 512k, 1M, 2M
# (round numbers from a million on); hyperspace comes after the last vehicle, at 4M.
LADDER = (0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1000, 2000, 4000)


def milestone(i, step):
    """Momentum needed for the i-th vehicle (0 = the first, always there)."""
    if i < len(LADDER):
        return LADDER[i] * step
    return LADDER[-1] * step * 2 ** (i - len(LADDER) + 1)


def milestones(step, count):
    return [milestone(i, step) for i in range(count)]


def hyperspace_at(step, count):
    """One step past the last vehicle."""
    return milestone(count, step)


def ultra_at(step, count):
    """Secret golden hyperspace: two more steps (16M with the default step)."""
    return milestone(count + 2, step)


def vehicle_index(momentum, step, count):
    if step <= 0:
        return 0
    i = 0
    while i + 1 < count and momentum >= milestone(i + 1, step):
        i += 1
    return i
