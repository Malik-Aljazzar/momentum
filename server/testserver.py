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

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    db = Path(tempfile.mkdtemp(prefix="momentum-e2e-")) / "e2e.db"
    os.environ.setdefault("MOMENTUM_TEST", "1")
    print(f"e2e server on http://127.0.0.1:{port}  (db {db})", flush=True)
    app = create_app(f"sqlite+aiosqlite:///{db.as_posix()}", blobs=MemoryStore())

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

    app.run(host="127.0.0.1", port=port, debug=False)
