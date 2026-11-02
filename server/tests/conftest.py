"""Shared test setup (pytest imports this before the test modules).

Rebuilt on 2026-11-02 - the earlier file was overwritten by mistake; what it set was reconstructed from
the suite's expectations (every test passes with these two settings).
"""
import os

import pytest

# the adaptive daily limit starts high in tests: rule maths and social flows tap freely; tests of the limit
# itself write their own DailyCap rows (test_game). limits.py reads this at import, so it is set here first.
os.environ.setdefault("MOMENTUM_START_RATE", "1000000")

from server import app as app_module  # noqa: E402


@pytest.fixture(autouse=True)
def no_local_apk(monkeypatch):
    """Tests never depend on an APK built on this machine: with one, the version gate (426) would block
    the "old app" clients some tests send. Tests of the gate set latest_app themselves."""
    monkeypatch.setattr(app_module, "latest_app", lambda: None)
