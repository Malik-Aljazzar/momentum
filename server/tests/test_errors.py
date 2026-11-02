"""Unexpected errors explain themselves: the players see what went wrong in the backend (JSON `error`
with the exception), known infrastructure failures (media store down, disk full, DB busy) get their own words."""
from sqlalchemy.exc import OperationalError

from server.blobs import BlobUnavailable
from .test_social import png, run, user


def test_unexpected_errors_tell_what_happened(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")

        async def boom(*a, **kw):
            raise RuntimeError("boom in the store")
        app.blobs.inner.put = boom
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png", "Accept-Language": "de"}, data=png())
        body = await r.get_json()
        assert r.status_code == 500
        assert body["error"].startswith("Serverfehler – im Backend ist etwas schiefgegangen: RuntimeError: boom in the store")
        assert body["detail"] == "RuntimeError: boom in the store"

        # the detail never carries data: no hosts, paths, keys, addresses, and no SQL statement with its values
        async def leaky(*a, **kw):
            raise RuntimeError("cannot reach minio:9000 (http://minio:9000/x) with access_key=AKIA123 token: t0k3n "
                               "at /data/momentum.db C:\\srv\\x for ann@example.com id 0123456789abcdef01234567")
        app.blobs.inner.put = leaky
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png"}, data=png())
        detail = (await r.get_json())["detail"]
        assert detail.startswith("RuntimeError: cannot reach")
        for secret in ("9000", "http", "AKIA123", "t0k3n", "/data", "momentum.db", "srv", "example.com", "0123456789abcdef"):
            assert secret not in detail, detail

        async def sql(*a, **kw):
            raise OperationalError("INSERT INTO users (email) VALUES (?)", ("secret@mail.de",), Exception("no such table: users"))
        app.blobs.inner.put = sql
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png"}, data=png())
        assert (await r.get_json())["detail"] == "OperationalError: no such table: users"
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png", "Accept-Language": "en"}, data=png())
        assert (await r.get_json())["error"].startswith("Server error – something went wrong in the backend: OperationalError")

        # sqlite's "database or disk is full" is the storage limit's message (507), "locked" asks to retry (503)
        async def full(*a, **kw):
            raise OperationalError("INSERT", {}, Exception("database or disk is full"))
        app.blobs.inner.put = full
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png", "Accept-Language": "de"}, data=png())
        assert r.status_code == 507 and "voll" in (await r.get_json())["error"]

        async def locked(*a, **kw):
            raise OperationalError("INSERT", {}, Exception("database is locked"))
        app.blobs.inner.put = locked
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png", "Accept-Language": "en"}, data=png())
        assert r.status_code == 503 and "busy" in (await r.get_json())["error"]

        # the media store can't be reached: a route that doesn't catch it itself still answers with words (503)
        async def down(*a, **kw):
            raise BlobUnavailable("connection refused")
        app.blobs.inner.sizes = down
        app.blobs._sizes = None
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png", "Accept-Language": "de"}, data=png())
        assert r.status_code == 503 and "Medienspeicher" in (await r.get_json())["error"]

        # 404 / 405 keep their usual answers (not wrapped as 500)
        assert (await c.get("/api/nope", headers=ann.h)).status_code == 404
    run(tmp_path, scenario)
