from datetime import date, timedelta

import pytest

from server import momentum as m

D = date(2026, 10, 1)


def test_constant_value_never_changes():
    assert m.next_value("constant", 5, 5, 0, 0) == 5
    assert m.effective_value("constant", 5, 1, 3, D, D) == 5


def test_linear_decreases_until_min():
    v = 10
    seq = []
    for _ in range(5):
        seq.append(v)
        v = m.next_value("linear", 10, v, 3, 2)
    assert seq == [10, 7, 4, 2, 2]


def test_exponential_shrinks_fast_then_slow():
    v, seq = 100, []
    for _ in range(4):
        seq.append(v)
        v = m.next_value("exponential", 100, v, 0.5, 10)
    assert seq == [100, 50, 25, 12.5]
    assert m.next_value("exponential", 100, 12.5, 0.5, 10) == 10


def test_reset_after_idle_days():
    assert m.effective_value("linear", 10, 4, 10, D, D + timedelta(days=9)) == 4
    assert m.effective_value("linear", 10, 4, 10, D, D + timedelta(days=10)) == 10
    assert m.effective_value("linear", 10, 4, 0, D, D + timedelta(days=999)) == 4


@pytest.mark.parametrize("mode,a,b,expected", [
    ("none", 10, 0, [0, 0, 0]),
    ("linear", 10, 0, [10, 20, 30]),
    ("exponential", 10, 2, [10, 20, 40]),
    ("inverse", 100, 0.5, [50, 75, 87.5]),
])
def test_catalyst(mode, a, b, expected):
    assert [m.catalyst(mode, a, b, n) for n in (1, 2, 3)] == expected
    assert m.catalyst(mode, a, b, 0) == 0


def test_daily_decay_streak_resets_when_threshold_met():
    gains = {D + timedelta(days=2): 150}
    results, streak = m.run_daily_decay(D, D + timedelta(days=3), gains,
                                        base=10, threshold=100, mode="linear",
                                        a=5, b=0, streak=0)
    assert [r.streak for r in results] == [1, 2, 0, 1]
    assert [r.decay for r in results] == [15, 20, 10, 15]
    assert streak == 1


def test_vehicle_index_follows_the_doubling_ladder():
    assert m.milestones(1000, 13) == [0, 1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000, 256000, 512000,
                                      1_000_000, 2_000_000]
    assert m.hyperspace_at(1000, 13) == 4_000_000
    assert m.vehicle_index(0, 1000, 13) == 0
    assert m.vehicle_index(999, 1000, 13) == 0
    assert m.vehicle_index(5000, 1000, 13) == 3
    assert m.vehicle_index(10**9, 1000, 13) == 12


def test_whole_numbers_acc_up_dec_down():
    assert m.applied("acc", 137.2) == 138 and m.applied("dec", 137.8) == 137
    assert m.applied("acc", 100.0) == 100 and m.applied("dec", 100.0) == 100
    assert m.applied("acc", 100.00000001) == 100      # float noise is not "a fraction"
