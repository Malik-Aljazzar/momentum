"""Extensions (server/extensions.py): the catalog, downloading / switching on and off, their tabs in the state, data in
their own format (checked against the manifest's schema), limits, permissions, and that nobody reaches another
player's data. The shipped one: the daylist (recurring tasks that tap rules)."""
import json

import pytest
from sqlalchemy import func, select

from .. import extensions as ext
from .test_social import run, user

TASK = {"name": "Beten", "repeat": {"type": "days", "every": 1}, "times": 5, "taps": [{"rule": 1, "n": 1}]}
BASE = "/api/extensions/daylist/data/tasks"


def test_catalog_download_and_tab_in_the_state(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        cat = (await ann.req("get", "/api/extensions"))
        daylist = next(e for e in cat["extensions"] if e["id"] == "daylist")
        assert daylist["official"] and not daylist["installed"] and not daylist["enabled"]
        assert daylist["name"] == {"de": "Daylist", "en": "Daylist"} and daylist["permissions"] == ["rules"]
        assert [t["key"] for t in daylist["tabs"]] == ["list", "done"]
        # Android apps from before tab layouts show `tab`: the first tab's name, the extension's own icon
        assert daylist["tab"] == {"title": {"de": "Aufgaben", "en": "Tasks"}, "icon": "check"}
        assert all(e["id"] != "journal" for e in cat["extensions"])          # retired
        assert (await ann.req("get", "/api/state"))["state"]["extensions"] == []

        got = await ann.req("post", "/api/extensions/daylist/install")
        assert got["extension"]["installed"] and got["extension"]["enabled"]
        page = got["page"]
        # the bridge and the look in front, offline by CSP, addresses lead to the server (filled in by the app)
        assert page.startswith("<!doctype html>") and page.lower().count("<!doctype") == 1
        assert "Content-Security-Policy" in page and "default-src 'none'" in page and "connect-src" not in page
        assert '<base href="__ORIGIN__/">' in page and "window.__momentumReceive" in page and "--yellow" in page
        assert page.index("Content-Security-Policy") < page.index("<script>") < page.index("momentum.rules.tap(")
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["extensions"] == [dict(id="daylist", version=daylist["version"], name=daylist["name"], icon="check",
                                         tabs=daylist["tabs"], tab=daylist["tab"], permissions=["rules"], enabled=True)]
        assert st["settings"]["tabs"] is None              # its tabs wait in the choice (settings -> tabs)
        # downloaded again (a new phone): the same page
        assert (await ann.req("get", "/api/extensions/daylist/page"))["page"] == page

        # switched off: no tab; on again
        off = await ann.req("put", "/api/extensions/daylist", json={"enabled": False})
        assert off["extensions"][0]["enabled"] is False and off["extension"]["installed"]
        assert (await ann.req("get", "/api/state"))["state"]["extensions"][0]["enabled"] is False
        await ann.req("put", "/api/extensions/daylist", json={"enabled": "ja"}, status=400)
        await ann.req("put", "/api/extensions/daylist", json={"enabled": True})
        await ann.req("get", "/api/extensions/nope/page", status=404)
    run(tmp_path, scenario)


def test_data_in_the_extensions_own_format(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        # not downloaded: no data
        await ann.req("put", f"{BASE}/task001", json={"value": TASK}, status=409)
        await ann.req("post", "/api/extensions/daylist/install")
        doc = (await ann.req("put", f"{BASE}/task003", json={"value": TASK}))["doc"]
        assert doc["key"] == "task003" and doc["value"] == TASK and doc["updated_at"]
        await ann.req("put", f"{BASE}/task002", json={"value": {"name": "Joggen", "repeat": {"type": "weekdays", "days": [1, 3, 5]}}})
        await ann.req("put", f"{BASE}/task001", json={"value": {"name": "Einkaufen", "repeat": {"type": "once", "date": "2026-11-18"}, "done": None}})
        assert (await ann.req("get", f"{BASE}/task003"))["doc"]["value"] == TASK
        await ann.req("get", f"{BASE}/nothere1", status=404)
        page = await ann.req("get", f"{BASE}?order=-key&limit=2")
        assert [d["key"] for d in page["docs"]] == ["task003", "task002"] and page["total"] == 3
        page = await ann.req("get", f"{BASE}?order=-key&limit=2&offset=2")
        assert [d["key"] for d in page["docs"]] == ["task001"]
        assert [d["key"] for d in (await ann.req("get", BASE))["docs"]][0] == "task001"
        # overwritten, not doubled
        await ann.req("put", f"{BASE}/task003", json={"value": {**TASK, "times": 3}})
        assert (await ann.req("get", BASE))["total"] == 3
        # a day's record
        day = {"t": {"task003": {"n": 5, "done": "2026-11-18T08:00:00Z", "ev": [12, 13], "gain": 40, "name": "Beten"}}}
        await ann.req("put", "/api/extensions/daylist/data/days/2026-11-18", json={"value": day})
        await ann.req("put", "/api/extensions/daylist/data/days/2026-11-18", json={"value": {"t": {"x": {"n": 100}}}}, status=422)

        # the manifest's format: key pattern, schema
        r = await ann.req("put", f"{BASE}/Heute!", json={"value": TASK}, status=422)
        assert "key" in r["error"].lower()
        for bad, where in (({"repeat": {"type": "days"}}, "name"), ({"name": "x", "repeat": {"type": "yearly"}}, "type"),
                           ({"name": 5, "repeat": {"type": "week"}}, "name"), ({**TASK, "color": "red"}, "color"),
                           ("nur Text", "value"), ({**TASK, "name": "x" * 81}, "name"), ({**TASK, "times": 0}, "times"),
                           ({**TASK, "taps": [{"rule": 1, "n": 21}]}, "n"), ({"name": "x", "repeat": {"type": "weekdays", "days": [8]}}, "days")):
            r = await ann.req("put", f"{BASE}/task009", json={"value": bad}, status=422)
            assert where in r["error"], (bad, r)
        await ann.req("put", f"{BASE}/task009", json={}, status=400)
        await ann.req("get", "/api/extensions/daylist/data/other", status=404)

        # someone else: nothing of ann's
        await ben.req("get", BASE, status=409)
        await ben.req("post", "/api/extensions/daylist/install")
        assert (await ben.req("get", BASE))["total"] == 0
        await ben.req("get", f"{BASE}/task003", status=404)

        # one deleted; the extension removed: all its data with it
        await ann.req("delete", f"{BASE}/task001")
        assert (await ann.req("get", BASE))["total"] == 2
        assert (await ann.req("delete", "/api/extensions/daylist"))["extensions"] == []
        await ann.req("post", "/api/extensions/daylist/install")
        assert (await ann.req("get", BASE))["total"] == 0
    run(tmp_path, scenario)


def test_limits_size_count_storage(tmp_path, monkeypatch):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/extensions/daylist/install")
        task = {"name": "x" * 40, "repeat": {"type": "week"}}
        size = len(json.dumps(task, separators=(",", ":")))

        monkeypatch.setattr(ext, "DOC_BYTES", 40)
        await ann.req("put", f"{BASE}/task001", json={"value": task}, status=413)
        monkeypatch.setattr(ext, "DOC_BYTES", 16 * 1024)
        monkeypatch.setattr(ext, "TOTAL_BYTES", size * 2 + 10)
        await ann.req("put", f"{BASE}/task001", json={"value": task})
        await ann.req("put", f"{BASE}/task002", json={"value": task})
        await ann.req("put", f"{BASE}/task003", json={"value": task}, status=507)
        # rewriting one in place still fits
        await ann.req("put", f"{BASE}/task002", json={"value": {**task, "name": "y" * 40}})
    run(tmp_path, scenario)


def test_max_docs_and_account_deletion(tmp_path, monkeypatch):
    real = ext.check_manifest

    def two_docs(m, page):
        out = real(m, page)
        out["collections"]["tasks"]["max_docs"] = 2
        return out
    monkeypatch.setattr(ext, "check_manifest", two_docs)

    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/extensions/daylist/install")
        for d in ("01", "02"):
            await ann.req("put", f"{BASE}/task0{d}", json={"value": {"name": d, "repeat": {"type": "week"}}})
        r = await ann.req("put", f"{BASE}/task003", json={"value": {"name": "3", "repeat": {"type": "week"}}}, status=507)
        assert "2" in r["error"]
        await ann.req("delete", "/api/account")
        async with app.db.session() as s:
            assert await s.scalar(select(func.count()).select_from(ext.ExtensionDoc)) == 0
            assert await s.scalar(select(func.count()).select_from(ext.UserExtension)) == 0
    run(tmp_path, scenario)


def test_every_shipped_extension_is_valid():
    shipped = ext.shipped()
    assert {m["id"] for m, _ in shipped} >= {"daylist"}
    for m, page in shipped:
        assert all(set(t["name"]) == {"de", "en"} and t["icon"].startswith("tab_") for t in m["tabs"])
        assert len(page.encode()) <= ext.PAGE_BYTES


def test_manifest_and_schema_checks():
    good = dict(id="habit_notes", version="1.2.3", api=1, name={"de": "Notizen", "en": "Notes"},
                description={"de": "Kurz.", "en": "Short."}, icon="note",
                collections={"notes": {"schema": {"type": "object", "properties": {"t": {"type": "string", "maxLength": 10}}}}})
    m = ext.check_manifest(good, "<p>hi</p>")
    assert m["tabs"] == [] and m["collections"]["notes"]["max_docs"] == ext.MAX_DOCS and ext.legacy_tab(m) is None
    tab = {"key": "list", "name": {"de": "Liste", "en": "List"}, "icon": "tab_list"}
    two = ext.check_manifest({**good, "tabs": [tab, {**tab, "key": "stats", "icon": "tab_chart"}]}, "x")
    assert [t["key"] for t in two["tabs"]] == ["list", "stats"] and ext.legacy_tab(two) == {"title": tab["name"], "icon": "note"}
    assert ext.check_manifest({**good, "permissions": ["rules"]}, "x")["permissions"] == ["rules"]
    for bad in ({**good, "permissions": ["network"]}, {**good, "permissions": ["rules", "rules"]}, {**good, "permissions": "rules"}):
        with pytest.raises(ext.Invalid):
            ext.check_manifest(bad, "x")
    for bad in ({**good, "tab": {"title": {"de": "N", "en": "N"}}},                 # API 1's single tab is gone
                {**good, "tabs": [tab, tab]}, {**good, "tabs": [{**tab, "key": "Bad"}]},
                {**good, "tabs": [{**tab, "icon": "note"}]}, {**good, "tabs": [{**tab, "name": {"de": "x"}}]},
                {**good, "tabs": [{**tab, "key": f"t{i}"} for i in range(ext.MAX_EXT_TABS + 1)]}, {**good, "tabs": tab}):
        with pytest.raises(ext.Invalid):
            ext.check_manifest(bad, "x")
    for bad in ({**good, "id": "Bad Id"}, {**good, "version": "1"}, {**good, "api": 2}, {**good, "name": {"de": "x"}},
                {**good, "icon": "no icon!"}, {**good, "collections": {"x": {"schema": {"type": "thing"}}}},
                {**good, "collections": {"x": {"schema": {"$ref": "#"}}}},
                {**good, "collections": {"x": {"schema": {}, "max_docs": 0}}},
                {**good, "collections": {"x": {"schema": {"pattern": "("}}}}):
        with pytest.raises(ext.Invalid):
            ext.check_manifest(bad, "<p>hi</p>")
    with pytest.raises(ext.Invalid):
        ext.check_manifest(good, "")
    with pytest.raises(ext.Invalid):
        ext.check_manifest(good, "x" * (ext.PAGE_BYTES + 1))

    schema = {"type": "object", "required": ["n"], "properties": {
        "n": {"type": "integer", "minimum": 0, "maximum": 10},
        "tags": {"type": "array", "maxItems": 2, "items": {"type": "string", "enum": ["a", "b"]}},
        "v": {"type": ["number", "null"]}, "code": {"type": "string", "pattern": "^[A-Z]{3}$"}}}
    ext.validate({"n": 3, "tags": ["a", "b"], "v": None, "code": "ABC", "extra": True}, schema)
    ext.validate({"n": 3.0, "v": 2.5}, schema)                     # 3.0 is an integer in JSON
    for bad in ({}, {"n": 11}, {"n": 1.5}, {"n": True}, {"n": 1, "tags": ["a", "b", "a"]}, {"n": 1, "tags": ["c"]},
                {"n": 1, "v": "x"}, {"n": 1, "code": "abc"}, [1]):
        with pytest.raises(ext.Invalid):
            ext.validate(bad, schema)


def test_a_shipped_extension_that_is_gone_leaves_the_catalog(tmp_path, monkeypatch):
    real = ext.shipped

    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/extensions/daylist/install")
        monkeypatch.setattr(ext, "shipped", lambda: [])
        async with app.db.session() as s:
            await ext.sync_shipped(s)
            await s.commit()
        assert [e["id"] for e in (await ann.req("get", "/api/extensions"))["extensions"]] == []
        assert (await ann.req("get", "/api/state"))["state"]["extensions"] == []
        await ann.req("get", "/api/extensions/daylist/page", status=404)
        # shipped again: back, with what the player had
        monkeypatch.setattr(ext, "shipped", real)
        async with app.db.session() as s:
            await ext.sync_shipped(s)
            await s.commit()
        assert [e["id"] for e in (await ann.req("get", "/api/state"))["state"]["extensions"]] == ["daylist"]
    run(tmp_path, scenario)


def test_a_tap_answers_with_its_event(tmp_path):
    """The daylist remembers the taps it made (rules.tap -> event_id) to take them back (rules.undo)."""
    async def scenario(app, c):
        ann = await user(c, "ann")
        acc = next(r for r in (await ann.req("get", "/api/state"))["state"]["rules"] if r["kind"] == "acc")
        r = await ann.req("post", f"/api/rules/{acc['id']}/trigger", json={})
        assert r["event_id"] == r["state"]["events"][0]["id"] and r["delta"] > 0
        back = await ann.req("post", f"/api/events/{r['event_id']}/undo")
        assert all(e["id"] != r["event_id"] for e in back["state"]["events"])
    run(tmp_path, scenario)
