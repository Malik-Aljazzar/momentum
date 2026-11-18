"""Extensions (server/extensions.py): the catalog, downloading / switching on and off, their tabs in the state, data in
their own format (checked against the manifest's schema), limits, and that nobody reaches another player's data."""
import pytest
from sqlalchemy import func, select

from .. import extensions as ext
from .test_social import run, user

ENTRY = {"text": "Heute war gut.", "mood": 4}


def test_catalog_download_and_tab_in_the_state(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        cat = (await ann.req("get", "/api/extensions"))
        journal = next(e for e in cat["extensions"] if e["id"] == "journal")
        assert journal["official"] and not journal["installed"] and not journal["enabled"]
        assert journal["name"] == {"de": "Tagebuch", "en": "Journal"}
        assert [t["key"] for t in journal["tabs"]] == ["today", "entries"]
        # Android apps from before tab layouts show `tab`: the first tab's name, the extension's own icon
        assert journal["tab"] == {"title": {"de": "Heute", "en": "Today"}, "icon": "openbook"}
        assert (await ann.req("get", "/api/state"))["state"]["extensions"] == []

        got = await ann.req("post", "/api/extensions/journal/install")
        assert got["extension"]["installed"] and got["extension"]["enabled"]
        page = got["page"]
        # the bridge and the look in front, offline by CSP, addresses lead to the server (filled in by the app)
        assert page.startswith("<!doctype html>") and page.lower().count("<!doctype") == 1
        assert "Content-Security-Policy" in page and "default-src 'none'" in page and "connect-src" not in page
        assert '<base href="__ORIGIN__/">' in page and "window.__momentumReceive" in page and "--yellow" in page
        assert page.index("Content-Security-Policy") < page.index("<script>") < page.index("momentum.data.list")
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["extensions"] == [dict(id="journal", version=journal["version"], name=journal["name"], icon="openbook",
                                         tabs=journal["tabs"], tab=journal["tab"], enabled=True)]
        assert st["settings"]["tabs"] is None              # its tabs wait in the choice (settings -> tabs)
        # downloaded again (a new phone): the same page
        assert (await ann.req("get", "/api/extensions/journal/page"))["page"] == page

        # switched off: no tab; on again
        off = await ann.req("put", "/api/extensions/journal", json={"enabled": False})
        assert off["extensions"][0]["enabled"] is False and off["extension"]["installed"]
        assert (await ann.req("get", "/api/state"))["state"]["extensions"][0]["enabled"] is False
        await ann.req("put", "/api/extensions/journal", json={"enabled": "ja"}, status=400)
        await ann.req("put", "/api/extensions/journal", json={"enabled": True})
        await ann.req("get", "/api/extensions/nope/page", status=404)
    run(tmp_path, scenario)


def test_data_in_the_extensions_own_format(tmp_path):
    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        # not downloaded: no data
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-18", json={"value": ENTRY}, status=409)
        await ann.req("post", "/api/extensions/journal/install")
        doc = (await ann.req("put", "/api/extensions/journal/data/entries/2026-11-18", json={"value": ENTRY}))["doc"]
        assert doc["key"] == "2026-11-18" and doc["value"] == ENTRY and doc["updated_at"]
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-17", json={"value": {"text": "Gestern"}})
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-16", json={"value": {"text": "Vorgestern", "mood": 1}})
        assert (await ann.req("get", "/api/extensions/journal/data/entries/2026-11-18"))["doc"]["value"] == ENTRY
        await ann.req("get", "/api/extensions/journal/data/entries/2026-01-01", status=404)
        page = await ann.req("get", "/api/extensions/journal/data/entries?order=-key&limit=2")
        assert [d["key"] for d in page["docs"]] == ["2026-11-18", "2026-11-17"] and page["total"] == 3
        page = await ann.req("get", "/api/extensions/journal/data/entries?order=-key&limit=2&offset=2")
        assert [d["key"] for d in page["docs"]] == ["2026-11-16"]
        assert [d["key"] for d in (await ann.req("get", "/api/extensions/journal/data/entries"))["docs"]][0] == "2026-11-16"
        # overwritten, not doubled
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-18", json={"value": {"text": "Doch anders"}})
        assert (await ann.req("get", "/api/extensions/journal/data/entries"))["total"] == 3

        # the manifest's format: key pattern, schema
        r = await ann.req("put", "/api/extensions/journal/data/entries/heute", json={"value": ENTRY}, status=422)
        assert "key" in r["error"].lower()
        for bad, where in (({"mood": 3}, "text"), ({"text": "x", "mood": 6}, "mood"), ({"text": 5}, "text"),
                           ({"text": "x", "weather": "sun"}, "weather"), ("nur Text", "value"), ({"text": "x" * 2001}, "text")):
            r = await ann.req("put", "/api/extensions/journal/data/entries/2026-11-15", json={"value": bad}, status=422)
            assert where in r["error"], (bad, r)
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-15", json={}, status=400)
        await ann.req("get", "/api/extensions/journal/data/other", status=404)

        # someone else: nothing of ann's
        await ben.req("get", "/api/extensions/journal/data/entries", status=409)
        await ben.req("post", "/api/extensions/journal/install")
        assert (await ben.req("get", "/api/extensions/journal/data/entries"))["total"] == 0
        await ben.req("get", "/api/extensions/journal/data/entries/2026-11-18", status=404)

        # one entry deleted; the extension removed: all its data with it
        await ann.req("delete", "/api/extensions/journal/data/entries/2026-11-16")
        assert (await ann.req("get", "/api/extensions/journal/data/entries"))["total"] == 2
        assert (await ann.req("delete", "/api/extensions/journal"))["extensions"] == []
        await ann.req("post", "/api/extensions/journal/install")
        assert (await ann.req("get", "/api/extensions/journal/data/entries"))["total"] == 0
    run(tmp_path, scenario)


def test_limits_size_count_storage(tmp_path, monkeypatch):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/extensions/journal/install")

        monkeypatch.setattr(ext, "DOC_BYTES", 40)
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-01", json={"value": {"text": "x" * 50}}, status=413)
        monkeypatch.setattr(ext, "DOC_BYTES", 16 * 1024)
        monkeypatch.setattr(ext, "TOTAL_BYTES", 110)              # a document below is 51 bytes of JSON
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-01", json={"value": {"text": "x" * 40}})
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-02", json={"value": {"text": "x" * 40}})
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-03", json={"value": {"text": "x" * 40}}, status=507)
        # rewriting one in place still fits
        await ann.req("put", "/api/extensions/journal/data/entries/2026-11-02", json={"value": {"text": "y" * 40}})
    run(tmp_path, scenario)


def test_max_docs_and_account_deletion(tmp_path, monkeypatch):
    real = ext.check_manifest

    def two_docs(m, page):
        out = real(m, page)
        out["collections"]["entries"]["max_docs"] = 2
        return out
    monkeypatch.setattr(ext, "check_manifest", two_docs)

    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/extensions/journal/install")
        for d in ("01", "02"):
            await ann.req("put", f"/api/extensions/journal/data/entries/2026-11-{d}", json={"value": {"text": d}})
        r = await ann.req("put", "/api/extensions/journal/data/entries/2026-11-03", json={"value": {"text": "3"}}, status=507)
        assert "2" in r["error"]
        await ann.req("delete", "/api/account")
        async with app.db.session() as s:
            assert await s.scalar(select(func.count()).select_from(ext.ExtensionDoc)) == 0
            assert await s.scalar(select(func.count()).select_from(ext.UserExtension)) == 0
    run(tmp_path, scenario)


def test_every_shipped_extension_is_valid():
    shipped = ext.shipped()
    assert {m["id"] for m, _ in shipped} >= {"journal"}
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
