"""The player's tabs (settings -> tabs; clients web/src/tablayout.ts = android .../data/TabLayout.kt).

The bottom bar holds up to MAX_TOP items: tabs or groups. A group holds any number of tabs; below it shows as one icon,
on its page as a tab menu at the top (the tabs' names, icons or both - `show`, never mixed). The tabs to choose from:
the standard ones (STANDARD) and those of the player's downloaded extensions ("ext:<extension>:<tab>", while switched
on). A tab that isn't placed is hidden. The profile always stays (the settings live there).

Stored as JSON in Settings.tabs, null = DEFAULT:
  [{"tab": "rules"}, {"id": "g1", "name": "Social", "icon": "tab_people", "show": "both", "tabs": ["chats", "feed"]},
   {"tab": "ext:journal:today"}, {"tab": "profile"}]
"""
import json
import re
from functools import lru_cache
from pathlib import Path

STANDARD = ("rules", "chats", "feed", "profile")
DEFAULT = [{"tab": t} for t in STANDARD]
MAX_TOP = 5                         # items in the bottom bar (tabs and groups)
MAX_GROUP_TABS = 60
SHOW = ("icons", "names", "both")
NAME_MAX = 24
GROUP_ID_RE = re.compile(r"^g[a-z0-9]{1,11}$")
ICON_DIR = Path(__file__).resolve().parent.parent / "pixelart" / "out" / "icons"


class Invalid(Exception):
    def __init__(self, de, en):
        super().__init__(en)
        self.de, self.en = de, en


@lru_cache(maxsize=1)
def tab_icons():
    """The library tabs and groups take their icon from: white glyphs (pixelart/tab_icons.py), "tab_<name>"."""
    return tuple(sorted(p.stem for p in ICON_DIR.glob("tab_*.png")))


def ext_key(ext_id, key):
    return f"ext:{ext_id}:{key}"


def loads(text):
    """The stored layout (a list) or None (= DEFAULT)."""
    if not text:
        return None
    try:
        v = json.loads(text)
    except ValueError:
        return None
    return v if isinstance(v, list) else None


def check(value, available):
    """A layout from the app, cleaned (None = back to the default); raises Invalid. `available` = the tab keys the
    player may place (STANDARD + the tabs of the extensions they downloaded)."""
    if value is None:
        return None
    if not isinstance(value, list) or not value:
        raise Invalid("tabs: Liste erwartet", "tabs: a list expected")
    if len(value) > MAX_TOP:
        raise Invalid(f"Unten haben höchstens {MAX_TOP} Tabs Platz", f"The bar holds at most {MAX_TOP} tabs")
    seen, groups, out = set(), set(), []

    def tab(k):
        if not isinstance(k, str) or k not in available:
            raise Invalid(f"Unbekannter Tab: {str(k)[:60]}", f"Unknown tab: {str(k)[:60]}")
        if k in seen:
            raise Invalid(f"Tab doppelt: {k}", f"Tab twice: {k}")
        seen.add(k)
        return k

    for item in value:
        if not isinstance(item, dict):
            raise Invalid("tabs: Einträge sind Objekte", "tabs: entries are objects")
        if "tab" in item:
            out.append({"tab": tab(item["tab"])})
            continue
        gid = item.get("id")
        if not isinstance(gid, str) or not GROUP_ID_RE.match(gid) or gid in groups:
            raise Invalid("Gruppe: ungültige oder doppelte id", "Group: invalid or repeated id")
        groups.add(gid)
        name = item.get("name", "")
        if not isinstance(name, str):
            raise Invalid("Gruppe: Name ist Text", "Group: the name is text")
        name = " ".join(name.split())
        if len(name) > NAME_MAX:
            raise Invalid(f"Gruppenname: höchstens {NAME_MAX} Zeichen", f"Group name: at most {NAME_MAX} characters")
        if item.get("icon") not in tab_icons():
            raise Invalid("Gruppe: ein Icon aus der Tab-Bibliothek", "Group: an icon from the tab library")
        show = item.get("show", "icons")
        if show not in SHOW:
            raise Invalid("Gruppe: show = icons, names oder both", "Group: show = icons, names or both")
        tabs = item.get("tabs", [])
        if not isinstance(tabs, list) or len(tabs) > MAX_GROUP_TABS:
            raise Invalid("Gruppe: tabs ist eine Liste", "Group: tabs is a list")
        out.append(dict(id=gid, name=name, icon=item["icon"], show=show, tabs=[tab(k) for k in tabs]))
    if "profile" not in seen:
        raise Invalid("Das Profil bleibt immer – dort sind die Einstellungen",
                      "The profile always stays - the settings live there")
    return out


def without(layout, gone):
    """The layout without the tabs `gone(key)` says are gone (an extension removed); None stays None."""
    if layout is None:
        return None
    out = []
    for item in layout:
        if "tab" in item:
            if not gone(item["tab"]):
                out.append(item)
        else:
            out.append({**item, "tabs": [k for k in item.get("tabs", []) if not gone(k)]})
    return out


def with_tabs(keys, name, icon):
    """The default layout plus extension tabs (the move from API 1's one tab per extension): in the bar while there's
    room, else together in one group."""
    if len(STANDARD) + len(keys) <= MAX_TOP:
        middle = [{"tab": k} for k in keys]
    else:
        middle = [dict(id="g1", name=name[:NAME_MAX], icon=icon, show="both", tabs=list(keys))]
    return [{"tab": "rules"}, {"tab": "chats"}, {"tab": "feed"}, *middle, {"tab": "profile"}]
