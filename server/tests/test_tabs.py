"""The player's tabs (server/tablayout.py): the bar (up to 5), groups with an icon from the tab library, hidden tabs,
extension tabs in the choice while their extension is downloaded, and the move from API 1's one tab per extension."""
import json

from sqlalchemy import delete

from .. import extensions as ext
from .. import tablayout
from ..db import Config, Settings, User
from .test_social import run, user

GROUP = {"id": "g1", "name": "  Social   Kram ", "icon": "tab_people", "show": "both", "tabs": ["chats", "feed"]}


def test_the_tab_library_and_the_default(tmp_path):
    icons = tablayout.tab_icons()
    assert len(icons) >= 60 and {"tab_book", "tab_pen", "tab_people", "tab_puzzle"} <= set(icons)

    async def scenario(app, c):
        ann = await user(c, "ann")
        meta = await ann.req("get", "/api/meta")
        assert meta["tab_icons"] == list(icons)
        assert not any(i.startswith("tab_") for i in meta["icons"])          # not rule icons
        assert (await ann.req("get", "/api/state"))["state"]["settings"]["tabs"] is None
    run(tmp_path, scenario)


def test_a_layout_is_checked_and_kept(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        layout = [{"tab": "rules"}, GROUP, {"tab": "profile"}]
        st = (await ann.req("put", "/api/settings", json={"tabs": layout}))["state"]
        assert st["settings"]["tabs"] == [{"tab": "rules"}, {**GROUP, "name": "Social Kram"}, {"tab": "profile"}]
        # an empty group, a hidden tab: fine
        empty = {"id": "g2", "name": "", "icon": "tab_star", "show": "icons", "tabs": []}
        await ann.req("put", "/api/settings", json={"tabs": [{"tab": "profile"}, empty]})

        too_many = [{"tab": "rules"}, {"tab": "chats"}, {"tab": "feed"}, {"tab": "profile"}, empty, {**GROUP, "id": "g3", "tabs": []}]
        for bad in (too_many,                                                  # 6 in the bar
                    [{"tab": "rules"}, {"tab": "chats"}],                      # no profile
                    [{"tab": "profile"}, {"tab": "profile"}],                  # twice
                    [{"tab": "profile"}, {**GROUP, "tabs": ["chats", "chats"]}],
                    [{"tab": "profile"}, {"tab": "nope"}],
                    [{"tab": "profile"}, {"tab": "ext:journal:today"}],        # not downloaded
                    [{"tab": "profile"}, {**GROUP, "icon": "ui_chat"}],        # not from the library
                    [{"tab": "profile"}, {**GROUP, "show": "mixed"}],
                    [{"tab": "profile"}, {**GROUP, "id": "x1"}],
                    [{"tab": "profile"}, GROUP, {**GROUP, "tabs": []}],        # same group id
                    [{"tab": "profile"}, {**GROUP, "name": "x" * 25}],
                    [], "rules", [["profile"]]):
            await ann.req("put", "/api/settings", json={"tabs": bad}, status=422)
        st = (await ann.req("get", "/api/state"))["state"]
        assert st["settings"]["tabs"] == [{"tab": "profile"}, empty]
        # back to the default
        assert (await ann.req("put", "/api/settings", json={"tabs": None}))["state"]["settings"]["tabs"] is None
    run(tmp_path, scenario)


def test_extension_tabs_in_the_choice_and_out_when_removed(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        await ann.req("post", "/api/extensions/journal/install")
        group = {"id": "gj", "name": "Tagebuch", "icon": "tab_book", "show": "names",
                 "tabs": ["ext:journal:entries", "ext:journal:today"]}
        layout = [{"tab": "rules"}, {"tab": "chats"}, {"tab": "feed"}, group, {"tab": "profile"}]
        await ann.req("put", "/api/settings", json={"tabs": layout})
        await ann.req("put", "/api/settings", json={"tabs": [{"tab": "profile"}, {"tab": "ext:journal:nope"}]}, status=422)
        # switched off: the place is kept (the apps leave the tabs out meanwhile)
        await ann.req("put", "/api/extensions/journal", json={"enabled": False})
        assert (await ann.req("get", "/api/state"))["state"]["settings"]["tabs"] == layout
        await ann.req("put", "/api/settings", json={"tabs": layout})              # still placeable while off
        # removed: its tabs leave the layout
        await ann.req("delete", "/api/extensions/journal")
        tabs = (await ann.req("get", "/api/state"))["state"]["settings"]["tabs"]
        assert tabs == [{"tab": "rules"}, {"tab": "chats"}, {"tab": "feed"}, {**group, "tabs": []}, {"tab": "profile"}]
        await ann.req("put", "/api/settings", json={"tabs": layout}, status=422)
    run(tmp_path, scenario)


def test_extension_tabs_from_before_layouts_keep_their_place(tmp_path):
    async def scenario(app, c):
        ann = await user(c, "ann")
        ben = await user(c, "ben")
        cid = await user(c, "cid")
        await ann.req("post", "/api/extensions/journal/install")           # on: moved into the layout
        await ben.req("post", "/api/extensions/journal/install")
        await ben.req("put", "/api/extensions/journal", json={"enabled": False})     # off: nothing to keep
        await cid.req("post", "/api/extensions/journal/install")
        mine = [{"tab": "profile"}, {"tab": "rules"}]
        await cid.req("put", "/api/settings", json={"tabs": mine})        # own layout already: untouched
        async with app.db.session() as s:
            await s.execute(delete(Config).where(Config.key == "tab_layout_init"))
            await ext.migrate_layouts(s)
            await s.commit()
        tabs = (await ann.req("get", "/api/state"))["state"]["settings"]["tabs"]
        # the journal's two tabs don't fit next to the four: one group, named after it
        assert tabs == [{"tab": "rules"}, {"tab": "chats"}, {"tab": "feed"},
                        {"id": "g1", "name": "Tagebuch", "icon": "tab_pen", "show": "both",
                         "tabs": ["ext:journal:today", "ext:journal:entries"]}, {"tab": "profile"}]
        assert (await ben.req("get", "/api/state"))["state"]["settings"]["tabs"] is None
        assert (await cid.req("get", "/api/state"))["state"]["settings"]["tabs"] == mine
        # once only
        await ann.req("put", "/api/settings", json={"tabs": None})
        async with app.db.session() as s:
            await ext.migrate_layouts(s)
            await s.commit()
        assert (await ann.req("get", "/api/state"))["state"]["settings"]["tabs"] is None
    run(tmp_path, scenario)


def test_one_extension_tab_fits_in_the_bar():
    assert tablayout.with_tabs(["ext:x:a"], "X", "tab_star") == [
        {"tab": "rules"}, {"tab": "chats"}, {"tab": "feed"}, {"tab": "ext:x:a"}, {"tab": "profile"}]
    assert tablayout.without(None, lambda k: True) is None
