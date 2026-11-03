"""The database stays usable when clients hang up (2026-11-03: a request cancelled in the middle of a query
kept SQLite locked, every write failed with "database is locked" until a restart)."""
import asyncio

from quart import jsonify

from server.db import Config
from .test_api import make_app
from .test_social import run, user


def test_a_client_hanging_up_does_not_cut_its_request_short(tmp_path):
    async def go():
        app = make_app(tmp_path)
        started, finished = asyncio.Event(), asyncio.Event()

        @app.post("/api/test/slow")
        async def slow():
            async with app.db.session() as s:
                s.add(Config(key="slow", value="done"))
                await s.flush()
                started.set()
                await asyncio.sleep(0.2)
                await s.commit()
            finished.set()
            return jsonify(ok=True)

        async with app.test_app() as ta:
            async with ta.test_client().request("/api/test/slow", method="POST") as conn:
                await conn.send_complete()
                await started.wait()
                await conn.disconnect()               # Quart cancels the request's task
            await asyncio.wait_for(finished.wait(), 5)
            async with app.db.session() as s:
                assert (await s.get(Config, "slow")).value == "done"
    asyncio.run(go())


def test_a_reader_in_the_middle_of_a_query_does_not_block_writes(tmp_path):
    async def scenario(app, c):
        await user(c, "ann")
        await user(c, "ben")
        async with app.db.engine.connect() as conn:
            assert (await conn.exec_driver_sql("PRAGMA journal_mode")).scalar() == "wal"
            reader = (await conn.get_raw_connection()).driver_connection
            cursor = await reader.execute("SELECT id FROM users")    # stopped after its first row: still reading
            assert await cursor.fetchone()
            async with app.db.session() as s:
                s.add(Config(key="written", value="1"))
                await s.commit()
            await cursor.close()
    run(tmp_path, scenario)
