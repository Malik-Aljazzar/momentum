"""Throwaway server for the PWA's end-to-end tests (web/tests/e2e): a fresh SQLite file, blobs in
memory, the built PWA under /app/.
    python -m server.testserver [port]          (web/playwright.config.ts starts it)
"""
import os
import sys
import tempfile
from pathlib import Path

from .app import create_app
from .blobs import MemoryStore


class TestMailer:
    """E-mails stay here (server/mail.py): /api/test/mail tells the last code sent to an address, the specs confirm
    their players with it like a player would."""
    enabled = True

    def __init__(self):
        self.codes = {}

    async def send(self, to, subject, html):
        import re
        found = re.search(r"\d{6}", subject)
        if found:
            self.codes[to] = found.group()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    db = Path(tempfile.mkdtemp(prefix="momentum-e2e-")) / "e2e.db"
    os.environ.setdefault("MOMENTUM_TEST", "1")
    print(f"e2e server on http://127.0.0.1:{port}  (db {db})", flush=True)
    mailer = TestMailer()
    # the content filter with a stand-in model: cyan pictures are refused, magenta ones covered (server/moderation.py)
    from .moderation import ColorModel, Moderator
    app = create_app(f"sqlite+aiosqlite:///{db.as_posix()}", blobs=MemoryStore(), mail=mailer,
                     moderator=Moderator(ColorModel()))

    @app.get("/api/test/mail")
    async def test_mail():
        """Test-only: the last code mailed to ?to=<address>."""
        from quart import jsonify, request
        return jsonify(code=mailer.codes.get(request.args.get("to", "").lower()))

    @app.post("/api/test/reports/done")
    async def test_reports_done():
        """Test-only (this server never runs in production): what the admin app does - the new reports of
        {"username": ...} become "done" (implemented). Scoped to one player: specs run in parallel."""
        from datetime import datetime, timezone

        from quart import jsonify, request
        from sqlalchemy import select

        from .db import Report
        username = (await request.get_json(silent=True) or {}).get("username", "")
        async with app.db.session() as s:
            rows = list(await s.scalars(select(Report).where(Report.status == "new", Report.username == username)))
            for r in rows:
                r.status, r.status_at = "done", datetime.now(timezone.utc).replace(tzinfo=None)
            await s.commit()
        return jsonify(done=len(rows))

    @app.post("/api/test/limit/yesterday")
    async def test_limit_yesterday():
        """Test-only: yesterday's daily limit {"cap": n} (today's stays) - the apps compare them on a day's first start.
        The PWA only shows it with a stored limit_seen_day, so the specs running in parallel don't notice."""
        from datetime import timedelta

        from quart import jsonify, request

        from . import limits
        from .db import DailyCap
        cap = float((await request.get_json(silent=True) or {}).get("cap", 50))
        day = limits.server_day()
        async with app.db.session() as s:
            await limits.cap_for(s, day)                      # today's limit is fixed first (from the days before)
            row = await s.get(DailyCap, day - timedelta(days=1))
            if row is None:
                s.add(DailyCap(day=day - timedelta(days=1), average=0, bias=cap, cap=cap, players=0))
            else:
                row.cap = row.bias = cap
            await s.commit()
        return jsonify(cap=cap)

    app.run(host="127.0.0.1", port=port, debug=False)
