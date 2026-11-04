"""Admin app API: reports with status, statistics, storage by category and the storage limit."""
from ..security import new_totp_secret, totp_code, totp_step
from .test_social import Client, png, run, tiny_voice, user


async def make_admin(app, c, player):
    """What `python -m server.admin grant <name>` does, then the admin app's login (password + code).
    Returns a client with the admin session."""
    from sqlalchemy import select
    from ..db import User
    secret = new_totp_secret()
    async with app.db.session() as s:
        u = await s.scalar(select(User).where(User.username == player.name))
        u.admin, u.totp_secret = True, secret
        await s.commit()
    r = await c.post("/api/admin/login", json={"login": player.name, "password": "secret1",
                                                "code": totp_code(secret, totp_step())})
    assert r.status_code == 200, await r.get_json()
    return Client(c, player.name, {"Authorization": "Bearer " + (await r.get_json())["token"]})


def test_reports_need_an_admin_and_move_through_new_todo_done(tmp_path):
    async def scenario(app, c):
        ann, boss = await user(c, "ann"), await user(c, "boss")
        media = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": "Chat stürzt ab", "media_ids": [media["id"]]})
        await ann.req("post", "/api/reports", json={"kind": "feature", "text": "Dunkler Modus"})
        await boss.req("get", "/api/admin/reports", status=401)          # a game session
        boss = await make_admin(app, c, boss)
        assert (await boss.req("get", "/api/admin/me"))["username"] == "boss"

        res = await boss.req("get", "/api/admin/reports?status=new")
        assert res["counts"] == {"new": 2, "todo": 0, "done": 0}
        assert [r["text"] for r in res["reports"]] == ["Dunkler Modus", "Chat stürzt ab"]     # newest first
        bug = res["reports"][1]
        # the screenshot left the public path: only the admin API serves it
        assert bug["images"] == [media["url"].replace("/media/", "/reports/")]
        assert bug["status"] == "new" and bug["username"] == "ann"
        await ann.req("get", media["url"], status=404)
        assert (await boss.req("get", "/api/admin/blob" + bug["images"][0]))[:4] == b"RIFF"     # WebP
        await ann.req("get", "/api/admin/blob" + bug["images"][0], status=401)

        # approved: reworded for the agents, then done
        r = (await boss.req("put", f"/api/admin/reports/{bug['id']}", json={"status": "todo", "note": "Fix the crash"}))["report"]
        assert r["status"] == "todo" and r["note"] == "Fix the crash" and r["status_at"]
        assert [x["id"] for x in (await boss.req("get", "/api/admin/reports?status=todo"))["reports"]] == [bug["id"]]
        await boss.req("put", f"/api/admin/reports/{bug['id']}", json={"status": "done"})
        res = await boss.req("get", "/api/admin/reports?status=done")
        assert res["counts"] == {"new": 1, "todo": 0, "done": 1} and res["reports"][0]["note"] == "Fix the crash"
        await boss.req("put", f"/api/admin/reports/{bug['id']}", json={"status": "open"}, status=400)
        assert (await boss.req("get", f"/api/admin/reports/{bug['id']}"))["report"]["status"] == "done"

        # whoever had the most reports implemented is Hermes of the Nine (the King of Reporters badge is gone);
        # a tie goes to whoever got there first
        from .. import nine

        async def hermes(viewer, name):
            nine.forget_tables()
            profile = (await viewer.req("get", f"/api/users/{name}"))["profile"]
            assert "badges" not in profile and "Chat stürzt ab" not in str(profile)    # what was reported stays private
            return "hermes" in profile["titles"]

        cat = await user(c, "cat")                                   # boss is the admin session now
        assert await hermes(cat, "ann") and not await hermes(ann, "cat")
        await cat.req("post", "/api/reports", json={"kind": "feature", "text": "Cats Wunsch"})
        rid = (await boss.req("get", "/api/admin/reports?status=new"))["reports"][0]["id"]
        await boss.req("put", f"/api/admin/reports/{rid}", json={"status": "done"})
        assert await hermes(cat, "ann") and not await hermes(ann, "cat")             # ann was first
        await cat.req("post", "/api/reports", json={"kind": "feature", "text": "Noch einer"})
        rid = (await boss.req("get", "/api/admin/reports?status=new"))["reports"][0]["id"]
        await boss.req("put", f"/api/admin/reports/{rid}", json={"status": "done"})
        assert await hermes(ann, "cat") and not await hermes(cat, "ann")
    run(tmp_path, scenario)


def test_stats(tmp_path):
    async def scenario(app, c):
        ann, boss = await user(c, "ann"), await user(c, "boss")
        boss = await make_admin(app, c, boss)
        await ann.req("post", "/api/posts", json={"text": "Hallo"})
        st = await boss.req("get", "/api/admin/stats")
        assert st["totals"]["users"] == 2 and st["totals"]["posts"] == 1 and st["totals"]["active_today"] == 2
        assert len(st["days"]) == 30 and st["days"][-1]["posts"] == 1 and st["days"][-1]["signups"] == 2
    run(tmp_path, scenario)


def test_storage_by_category_and_the_limit(tmp_path):
    async def scenario(app, c):
        ann, ben, boss = await user(c, "ann"), await user(c, "ben"), await user(c, "boss")
        boss = await make_admin(app, c, boss)
        photo = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        post = (await ann.req("post", "/api/posts", json={"text": "x", "media_ids": [photo["id"]]}))["post"]
        story = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        await ann.req("post", "/api/stories", json={"media_id": story["id"]})
        await ben.req("post", "/api/users/ann/follow")
        conv = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        voice = (await ann.req("post", "/api/media", data=tiny_voice(), ctype="audio/mp4"))["media"]
        await ann.req("post", f"/api/conversations/{conv}/messages", json={"text": "", "media_id": voice["id"]})
        await ann.req("post", "/api/settings/avatar", data=png(200, 200), ctype="image/png")
        await ann.req("post", "/api/media", data=png(), ctype="image/png")          # never attached: a draft
        await app.blobs.inner.put("media/9/lost.webp", b"x" * 100, "image/webp")    # no DB row: orphaned

        o = await boss.req("get", "/api/admin/storage?refresh=1")
        cats = {k["key"]: k for k in o["categories"]}
        for key in ("post_image", "story_image", "chat_voice", "avatar", "draft", "orphan", "db_users", "db_posts"):
            assert cats[key]["bytes"] > 0, key
        assert cats["orphan"]["bytes"] == 100 and cats["post_image"]["count"] == 1 and cats["post_image"]["group"] == "posts"
        assert o["used"] == o["db_bytes"] + o["blob_bytes"] and o["blob_bytes"] == sum(
            k["bytes"] for k in o["categories"] if k["type"] == "blob")
        assert sum(k["bytes"] for k in o["categories"] if k["type"] == "db") in range(o["db_bytes"] - 20, o["db_bytes"] + 20)
        assert o["limit"] == o["disk_total"] and o["limit_set"] is False

        items = (await boss.req("get", "/api/admin/storage/post_image"))["items"]
        assert [(i["post_id"], i["username"], i["url"]) for i in items] == [(post["id"], "ann", photo["url"])]
        assert items[0]["size"] == cats["post_image"]["bytes"]
        tables = (await boss.req("get", "/api/admin/storage/db_messages"))["tables"]
        assert {t["table"] for t in tables} >= {"messages", "conversations"}
        await boss.req("get", "/api/admin/storage/nope", status=404)

        # the limit: at most the disk; once reached, uploads and other writes stop, deleting still works
        await boss.req("put", "/api/admin/storage/limit", json={"limit": o["disk_total"] + 1}, status=400)
        limit = o["used"] + 200_000             # room for small writes, not for another (noisy, big) photo
        o = await boss.req("put", "/api/admin/storage/limit", json={"limit": limit})
        assert o["limit_set"] is True and o["limit"] == limit
        await ben.req("post", "/api/posts", json={"text": "passt noch"})
        r = await c.post("/api/media", headers={**ann.h, "Content-Type": "image/png", "Accept-Language": "de"}, data=png())
        assert r.status_code == 507 and "voll" in (await r.get_json())["error"]
        await boss.req("put", "/api/admin/storage/limit", json={"limit": o["used"]})
        await ann.req("post", "/api/posts", json={"text": "voll"}, status=507)
        await ann.req("delete", f"/api/posts/{post['id']}")
        await ann.req("post", "/api/auth/logout")                                    # still possible
        o = await boss.req("put", "/api/admin/storage/limit", json={"limit": None})
        assert o["limit_set"] is False
        await ben.req("post", "/api/posts", json={"text": "wieder Platz"})

        # the disk itself (shared with other services) nearly full: writes stop before the reserve, and a
        # store that refuses because its disk is full is a 507 too, not a 500
        total, free = app.quota.disk()
        reserve = app.quota.reserve()
        app.quota.disk = lambda: (total, reserve + 10_000)
        await ben.req("post", "/api/posts", json={"text": "noch 10 kB"})
        r = await c.post("/api/media", headers={**ben.h, "Content-Type": "image/png", "Accept-Language": "en"}, data=png())
        assert r.status_code == 507 and (await r.get_json())["storage_full"] is True
        app.quota.disk = lambda: (total, reserve - 1)
        await ben.req("post", "/api/posts", json={"text": "voll"}, status=507)
        app.quota.disk = lambda: (total, free)
        inner_put = app.blobs.inner.put

        async def refusing_put(*a, **kw):
            from server.blobs import StoreFull
            raise StoreFull("Storage backend has reached its minimum free drive threshold")
        app.blobs.inner.put = refusing_put
        r = await c.post("/api/media", headers={**ben.h, "Content-Type": "image/png"}, data=png())
        assert r.status_code == 507
        app.blobs.inner.put = inner_put
        await ben.req("post", "/api/media", data=png(), ctype="image/png")
    run(tmp_path, scenario)


def test_the_cli_sets_a_reports_task(tmp_path, monkeypatch, capsys):
    """`python -m server.admin report-note <id> <text>`: the owner's reworded task, shown with `reports`."""
    import asyncio
    from ..admin import main
    monkeypatch.setenv("MOMENTUM_DB", f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}")

    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": "Emoji-Picker schließt sich"})
    run(tmp_path, scenario)
    asyncio.run(main(["report-note", "1", "Keep the emoji picker open after a pick"]))
    asyncio.run(main(["report-status", "1", "todo"]))
    asyncio.run(main(["reports", "todo"]))
    out = capsys.readouterr().out
    assert "#1 [bug] todo" in out and "NOTE: Keep the emoji picker open after a pick" in out
