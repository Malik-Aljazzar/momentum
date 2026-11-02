"""Reports as issues of the public GitHub repo (server/github.py): one issue per report, labels for kind /
platform / status, done closes, changes go out once, nothing private leaves the server."""
import asyncio
import urllib.error

from ..app import create_app
from ..blobs import MemoryStore
from ..github import LABELS, GitHub, issue_of
from .test_admin import make_admin
from .test_social import Client, png, user


class FakeHub:
    """Stands in for api.github.com: remembers the labels and issues it was sent."""

    def __init__(self, labels=("bug",)):
        self.labels = set(labels)
        self.issues = {}
        self.calls = []

    def fetch(self, method, path, body=None):
        self.calls.append((method, path, body))
        if path.startswith("/repos/me/momentum/labels"):
            if method == "GET":
                return [{"name": n} for n in self.labels]
            if body["name"] in self.labels:
                raise urllib.error.HTTPError(path, 422, "exists", {}, None)
            self.labels.add(body["name"])
            return body
        if path == "/repos/me/momentum/issues" and method == "POST":
            number = len(self.issues) + 1
            self.issues[number] = dict(body, number=number, state="open")
            return self.issues[number]
        if path.startswith("/repos/me/momentum/issues/") and method == "PATCH":
            number = int(path.rsplit("/", 1)[1])
            self.issues[number].update(body)
            return self.issues[number]
        raise AssertionError(f"unexpected call {method} {path}")

    @property
    def writes(self):
        return [c for c in self.calls if c[0] != "GET"]


def run(tmp_path, scenario, hub):
    async def go():
        gh = GitHub("me/momentum", "token", fetch=hub.fetch, pause=0)
        app = create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(), github=gh)
        async with app.test_app() as ta:
            await scenario(app, ta.test_client(), gh)
    asyncio.run(go())


def test_reports_become_issues_with_labels_and_status(tmp_path):
    hub = FakeHub()

    async def scenario(app, c, gh):
        ann, boss = await user(c, "ann"), await user(c, "boss")
        media = (await ann.req("post", "/api/media", data=png(), ctype="image/png"))["media"]
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": "Chat stürzt ab\nbeim Öffnen", "media_ids": [media["id"]],
                                                     "platform": "android", "app_version": "0.1.251102", "device": "Pixel 7"})
        await ann.req("post", "/api/reports", json={"kind": "feature", "text": "Dunkler Modus", "platform": "pwa"})

        async with app.db.session() as s:
            assert await gh.sync(s) == 2
        # the repo got the missing labels once, then one issue per report
        assert hub.labels == set(LABELS)
        bug, wish = hub.issues[1], hub.issues[2]
        assert bug["title"] == "Bug report #1: Chat stürzt ab"
        assert bug["labels"] == ["bug", "android", "status: new"]
        assert "> Chat stürzt ab\n> beim Öffnen" in bug["body"] and "Pixel 7" in bug["body"] and "app 0.1.251102" in bug["body"]
        assert "1 screenshot (in the admin app)" in bug["body"]
        assert "ann" not in bug["body"] and "reports/" not in bug["body"]      # who and the pictures stay private
        assert wish["title"] == "Feature request #2: Dunkler Modus" and wish["labels"] == ["feature", "pwa", "status: new"]
        assert "screenshot" not in wish["body"]

        # nothing changed: nothing is sent again
        hub.calls.clear()
        async with app.db.session() as s:
            assert await gh.sync(s) == 0
        assert hub.calls == []

        # the owner approves it with a task, then finishes it: labels follow, done closes the issue
        boss = await make_admin(app, c, boss)
        r = (await boss.req("put", "/api/admin/reports/1", json={"status": "todo", "note": "Fix the crash on open"}))["report"]
        assert r["issue"] == "https://github.com/me/momentum/issues/1"
        async with app.db.session() as s:
            assert await gh.sync(s) == 1
        assert hub.issues[1]["labels"] == ["bug", "android", "status: todo"] and hub.issues[1]["state"] == "open"
        assert "### Task\nFix the crash on open" in hub.issues[1]["body"]
        await boss.req("put", "/api/admin/reports/1", json={"status": "done"})
        async with app.db.session() as s:
            await gh.sync(s)
        assert hub.issues[1]["state"] == "closed" and hub.issues[1]["state_reason"] == "completed"
        assert hub.issues[1]["labels"] == ["bug", "android", "status: done"]
        # ... and back to todo reopens it
        await boss.req("put", "/api/admin/reports/1", json={"status": "todo"})
        async with app.db.session() as s:
            await gh.sync(s)
        assert hub.issues[1]["state"] == "open" and hub.issues[1]["labels"][-1] == "status: todo"
        assert len(hub.issues) == 2                                       # never a second issue for a report
    run(tmp_path, scenario, hub)


def test_a_report_already_done_is_created_closed(tmp_path):
    hub = FakeHub()

    async def scenario(app, c, gh):
        ann, boss = await user(c, "ann"), await user(c, "boss")
        await ann.req("post", "/api/reports", json={"kind": "feature", "text": "Alt, längst erledigt"})
        boss = await make_admin(app, c, boss)
        await boss.req("put", "/api/admin/reports/1", json={"status": "done"})
        async with app.db.session() as s:
            assert await gh.sync(s) == 1
        assert hub.issues[1]["state"] == "closed" and hub.issues[1]["labels"] == ["feature", "android", "status: done"]
    run(tmp_path, scenario, hub)


def test_off_without_config_and_issue_text(tmp_path):
    gh = GitHub("", "", fetch=lambda *a: (_ for _ in ()).throw(AssertionError("must not call")))
    assert not gh.enabled

    class R:
        id, kind, text, platform, app_version, device, note, media_keys, status, created_at = \
            7, "bug", "x" * 100, "pwa", "", "", "", "", "new", None
    issue = issue_of(R)
    assert issue["title"] == "Bug report #7: " + "x" * 69 + "…" and issue["state"] == "open"
    assert issue["labels"] == ["bug", "pwa", "status: new"]
    assert gh.issue_url(R) is None

    async def scenario(app, c, _gh):
        ann = await user(c, "ann")
        await ann.req("post", "/api/reports", json={"kind": "bug", "text": "Nichts passiert"})
        async with app.db.session() as s:
            assert await gh.sync(s) == 0                                  # off: nothing leaves the server
    run(tmp_path, scenario, FakeHub())
