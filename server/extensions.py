"""Extensions: optional parts of the app a player downloads and switches on (settings -> extensions).

An extension is a manifest (server/shipped_extensions/<id>/manifest.json) and one self-contained HTML page (its UI). Both
apps show the page shut off from everything else - the PWA in a sandboxed iframe (scripts only, opaque origin), Android
in a WebView that may not navigate - behind a strict CSP (no network at all: fonts and pictures of this server only).
The page talks to the app only through the bridge `momentum.*` (shipped_extensions/sdk.js; hosts web/src/extensions.ts and
android .../data/Extensions.kt): its own data here, the player's language and name, a toast. It never sees the token.

API 1 - what an extension can do today:
- `tabs`: tabs of its own (up to MAX_EXT_TABS; each a key, a name and an icon from the tab library tab_*). While it's
  switched on they're in the player's choice of tabs (settings -> tabs, server/tablayout.py): the bar, a group, or hidden.
  The page is the same for every tab; it learns which one it is from the context (`tab`).
- `permissions`: what else it may do through the bridge, shown before downloading. "rules": see the player's tappable
  rules and tap them (and take such a tap back) - the apps answer rules.list / rules.tap / rules.undo only then.
- `collections`: data in its own format, stored per player: documents (JSON) under a key, checked against the JSON
  schema (a subset, see check_schema) the manifest gives each collection; limits DOC_BYTES, `max_docs`, TOTAL_BYTES

The catalog is the extensions shipped in server/shipped_extensions/ (synced into the table at start). The table already has
room for extensions by players (author_id, status "review" until approved) - uploading and reviewing them comes later.
Author documentation: server/shipped_extensions/README.md.
"""
import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path

from quart import g, jsonify, request
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, delete, func, select
from sqlalchemy.orm import Mapped, mapped_column

from . import tablayout
from .db import Base, Config, Settings, utcnow

ROOT = Path(__file__).parent / "shipped_extensions"
API = 1
MAX_EXT_TABS = 6                   # tabs one extension may bring
PERMISSIONS = ("rules",)           # see / tap the player's rules (bridge rules.*)
TAB_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,19}$")
PAGE_BYTES = 512 * 1024            # an extension's page
DOC_BYTES = 16 * 1024              # one stored document (its JSON)
TOTAL_BYTES = 2 * 1024 * 1024      # all documents of one extension and player
MAX_DOCS = 1000                    # per collection, unless the manifest says otherwise (at most MAX_DOCS_CAP)
MAX_DOCS_CAP = 10_000
ID_RE = re.compile(r"^[a-z][a-z0-9_]{1,39}$")
VERSION_RE = re.compile(r"^\d{1,4}\.\d{1,4}\.\d{1,4}$")
COLLECTION_RE = re.compile(r"^[a-z][a-z0-9_]{0,39}$")
KEY_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,80}$")
ICON_RE = re.compile(r"^[a-z0-9_]{1,32}$")
TYPES = ("object", "array", "string", "number", "integer", "boolean", "null")
SCHEMA_KEYS = {"type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
               "minLength", "maxLength", "pattern", "minimum", "maximum", "enum", "title", "description"}
ORDERS = {"key": ("key", False), "-key": ("key", True), "updated": ("updated_at", False), "-updated": ("updated_at", True)}
# no network for the page: only this server's fonts / pictures (__ORIGIN__ = the server, filled in by the app)
CSP = ("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; font-src __ORIGIN__; "
       "img-src data: blob: __ORIGIN__; media-src data: blob:; base-uri 'none'; form-action 'none'")


class Extension(Base):
    """An extension in the catalog: its current version's manifest and page."""
    __tablename__ = "extensions"
    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    version: Mapped[str] = mapped_column(String(20))
    manifest: Mapped[str] = mapped_column(Text)        # JSON, checked by check_manifest
    page: Mapped[str] = mapped_column(Text)            # the HTML
    author_id: Mapped[int | None] = mapped_column(Integer, nullable=True)       # None = shipped with Momentum
    status: Mapped[str] = mapped_column(String(10), default="published")        # published | review | hidden
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class UserExtension(Base):
    """An extension a player downloaded; `enabled` = switched on (its tab shows)."""
    __tablename__ = "user_extensions"
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    extension_id: Mapped[str] = mapped_column(String(40), primary_key=True)
    version: Mapped[str] = mapped_column(String(20))   # the version downloaded last
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    installed_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class ExtensionDoc(Base):
    """One stored document of an extension for a player: a collection's value under a key (JSON)."""
    __tablename__ = "extension_docs"
    __table_args__ = (UniqueConstraint("user_id", "extension_id", "collection", "key"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    extension_id: Mapped[str] = mapped_column(String(40))
    collection: Mapped[str] = mapped_column(String(40))
    key: Mapped[str] = mapped_column(String(80))
    value: Mapped[str] = mapped_column(Text)
    size: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Invalid(Exception):
    """A manifest, schema or document that doesn't fit; `de` / `en` say why."""

    def __init__(self, de, en):
        super().__init__(en)
        self.de, self.en = de, en


# ---------------------------------------------------------------- manifests and schemas

def _texts(v, what, limit):
    """{"de": ..., "en": ...}, both there and short enough."""
    if not isinstance(v, dict) or not all(isinstance(v.get(k), str) and 0 < len(v[k].strip()) <= limit for k in ("de", "en")):
        raise Invalid(f"{what}: Text auf Deutsch und Englisch (bis {limit} Zeichen) fehlt",
                      f"{what}: needs a German and an English text (up to {limit} characters)")
    return {"de": v["de"].strip(), "en": v["en"].strip()}


def _icon(v, what):
    from .emoji import is_emoji
    if not isinstance(v, str) or not (ICON_RE.match(v) or is_emoji(v)):
        raise Invalid(f"{what}: Icon-Name oder ein Emoji erwartet", f"{what}: an icon name or one emoji expected")
    return v


def check_schema(schema, path="schema", depth=0):
    """The JSON-schema subset documents are checked against (SCHEMA_KEYS); raises Invalid."""
    if depth > 8:
        raise Invalid(f"{path}: zu tief verschachtelt", f"{path}: nested too deeply")
    if not isinstance(schema, dict):
        raise Invalid(f"{path}: Objekt erwartet", f"{path}: object expected")
    unknown = set(schema) - SCHEMA_KEYS
    if unknown:
        raise Invalid(f"{path}: unbekannt: {', '.join(sorted(unknown))}", f"{path}: unknown: {', '.join(sorted(unknown))}")
    types = schema.get("type")
    for t in ([types] if isinstance(types, str) else types or []):
        if t not in TYPES:
            raise Invalid(f"{path}.type: {t!r} gibt es nicht", f"{path}.type: no such type {t!r}")
    if types is not None and not isinstance(types, (str, list)):
        raise Invalid(f"{path}.type: Text oder Liste erwartet", f"{path}.type: string or list expected")
    for k in ("minItems", "maxItems", "minLength", "maxLength"):
        if k in schema and (not isinstance(schema[k], int) or isinstance(schema[k], bool) or schema[k] < 0):
            raise Invalid(f"{path}.{k}: Zahl ≥ 0 erwartet", f"{path}.{k}: number >= 0 expected")
    for k in ("minimum", "maximum"):
        if k in schema and (not isinstance(schema[k], (int, float)) or isinstance(schema[k], bool)):
            raise Invalid(f"{path}.{k}: Zahl erwartet", f"{path}.{k}: number expected")
    if "pattern" in schema:
        _pattern(schema["pattern"], f"{path}.pattern")
    if "enum" in schema and (not isinstance(schema["enum"], list) or not schema["enum"]):
        raise Invalid(f"{path}.enum: Liste erwartet", f"{path}.enum: list expected")
    if "required" in schema and (not isinstance(schema["required"], list) or not all(isinstance(x, str) for x in schema["required"])):
        raise Invalid(f"{path}.required: Liste von Namen erwartet", f"{path}.required: list of names expected")
    if "additionalProperties" in schema and not isinstance(schema["additionalProperties"], bool):
        check_schema(schema["additionalProperties"], f"{path}.additionalProperties", depth + 1)
    props = schema.get("properties", {})
    if not isinstance(props, dict):
        raise Invalid(f"{path}.properties: Objekt erwartet", f"{path}.properties: object expected")
    for name, sub in props.items():
        check_schema(sub, f"{path}.properties.{name}", depth + 1)
    if "items" in schema:
        check_schema(schema["items"], f"{path}.items", depth + 1)


def _pattern(p, what):
    if not isinstance(p, str) or len(p) > 200:
        raise Invalid(f"{what}: Muster (bis 200 Zeichen) erwartet", f"{what}: pattern (up to 200 characters) expected")
    try:
        return re.compile(p)
    except re.error as e:
        raise Invalid(f"{what}: ungültiges Muster ({e})", f"{what}: invalid pattern ({e})")


def _type_of(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, int):
        return "integer"
    if isinstance(v, float):
        return "integer" if v.is_integer() else "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    if isinstance(v, dict):
        return "object"
    return "?"


def validate(value, schema, path="value"):
    """Checks a document against its collection's schema; raises Invalid naming the place that doesn't fit."""
    t = _type_of(value)
    if isinstance(value, float) and not math.isfinite(value):
        raise Invalid(f"{path}: keine gültige Zahl", f"{path}: not a valid number")
    want = schema.get("type")
    if want is not None:
        allowed = [want] if isinstance(want, str) else want
        if not (t in allowed or (t == "integer" and "number" in allowed)):
            raise Invalid(f"{path}: {'/'.join(allowed)} erwartet", f"{path}: {'/'.join(allowed)} expected")
    if "enum" in schema and value not in schema["enum"]:
        raise Invalid(f"{path}: nicht erlaubter Wert", f"{path}: value not allowed")
    if t == "string":
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", DOC_BYTES):
            raise Invalid(f"{path}: Länge passt nicht", f"{path}: wrong length")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            raise Invalid(f"{path}: passt nicht zum Muster", f"{path}: doesn't match the pattern")
    if t in ("integer", "number"):
        if "minimum" in schema and value < schema["minimum"] or "maximum" in schema and value > schema["maximum"]:
            raise Invalid(f"{path}: außerhalb des erlaubten Bereichs", f"{path}: out of range")
    if t == "array":
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", 10_000):
            raise Invalid(f"{path}: Anzahl der Einträge passt nicht", f"{path}: wrong number of items")
        if "items" in schema:
            for i, item in enumerate(value):
                validate(item, schema["items"], f"{path}[{i}]")
    if t == "object":
        props = schema.get("properties", {})
        for name in schema.get("required", []):
            if name not in value:
                raise Invalid(f"{path}.{name} fehlt", f"{path}.{name} is missing")
        extra = schema.get("additionalProperties", True)
        for name, item in value.items():
            if name in props:
                validate(item, props[name], f"{path}.{name}")
            elif extra is False:
                raise Invalid(f"{path}.{name}: nicht vorgesehen", f"{path}.{name}: not allowed")
            elif isinstance(extra, dict):
                validate(item, extra, f"{path}.{name}")


def check_manifest(m, page):
    """A manifest (+ its page) as stored; raises Invalid."""
    if not isinstance(m, dict):
        raise Invalid("Manifest: Objekt erwartet", "manifest: object expected")
    if not isinstance(m.get("id"), str) or not ID_RE.match(m["id"]):
        raise Invalid("id: a-z, 0-9, _ (2-40 Zeichen)", "id: a-z, 0-9, _ (2-40 characters)")
    if not isinstance(m.get("version"), str) or not VERSION_RE.match(m["version"]):
        raise Invalid("version: z. B. 1.0.0", "version: e.g. 1.0.0")
    if m.get("api") != API:
        raise Invalid(f"api: {API} erwartet", f"api: {API} expected")
    out = dict(id=m["id"], version=m["version"], api=API, name=_texts(m.get("name"), "name", 40),
               description=_texts(m.get("description"), "description", 300), icon=_icon(m.get("icon"), "icon"),
               author=str(m.get("author", "")).strip()[:40], tabs=[], permissions=[], collections={})
    perms = m.get("permissions", [])
    if not isinstance(perms, list) or any(p not in PERMISSIONS for p in perms) or len(set(perms)) != len(perms):
        raise Invalid(f"permissions: nur {', '.join(PERMISSIONS)}", f"permissions: only {', '.join(PERMISSIONS)}")
    out["permissions"] = list(perms)
    if "tab" in m:
        raise Invalid("tab: heißt jetzt tabs (eine Liste)", "tab: is now tabs (a list)")
    tabs = m.get("tabs", [])
    if not isinstance(tabs, list) or len(tabs) > MAX_EXT_TABS:
        raise Invalid(f"tabs: Liste mit bis zu {MAX_EXT_TABS} Tabs", f"tabs: a list of up to {MAX_EXT_TABS} tabs")
    for i, t in enumerate(tabs):
        if not isinstance(t, dict):
            raise Invalid(f"tabs[{i}]: Objekt erwartet", f"tabs[{i}]: object expected")
        key = t.get("key")
        if not isinstance(key, str) or not TAB_KEY_RE.match(key) or any(x["key"] == key for x in out["tabs"]):
            raise Invalid(f"tabs[{i}].key: a-z, 0-9, _ (bis 20 Zeichen), jeder nur einmal",
                          f"tabs[{i}].key: a-z, 0-9, _ (up to 20 characters), each once")
        if t.get("icon") not in tablayout.tab_icons():
            raise Invalid(f"tabs[{i}].icon: ein Icon aus der Tab-Bibliothek (tab_…)",
                          f"tabs[{i}].icon: an icon from the tab library (tab_…)")
        out["tabs"].append(dict(key=key, name=_texts(t.get("name"), f"tabs[{i}].name", 20), icon=t["icon"]))
    cols = m.get("collections", {})
    if not isinstance(cols, dict) or len(cols) > 20:
        raise Invalid("collections: Objekt mit bis zu 20 Einträgen", "collections: object with up to 20 entries")
    for name, c in cols.items():
        if not COLLECTION_RE.match(name) or not isinstance(c, dict):
            raise Invalid(f"collections.{name}: a-z, 0-9, _", f"collections.{name}: a-z, 0-9, _")
        check_schema(c.get("schema", {}), f"collections.{name}.schema")
        if "key_pattern" in c:
            _pattern(c["key_pattern"], f"collections.{name}.key_pattern")
        n = c.get("max_docs", MAX_DOCS)
        if not isinstance(n, int) or isinstance(n, bool) or not 1 <= n <= MAX_DOCS_CAP:
            raise Invalid(f"collections.{name}.max_docs: 1-{MAX_DOCS_CAP}", f"collections.{name}.max_docs: 1-{MAX_DOCS_CAP}")
        out["collections"][name] = dict(schema=c.get("schema", {}), key_pattern=c.get("key_pattern"), max_docs=n)
    if not isinstance(page, str) or not page.strip() or len(page.encode()) > PAGE_BYTES:
        raise Invalid(f"Seite fehlt oder ist größer als {PAGE_BYTES // 1024} KB",
                      f"page missing or bigger than {PAGE_BYTES // 1024} KB")
    return out


# ---------------------------------------------------------------- the catalog

def shipped():
    """The extensions in server/shipped_extensions/<id>/: (manifest, page) each, checked."""
    out = []
    for d in sorted(p for p in ROOT.iterdir() if p.is_dir()):
        raw = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
        page = (d / raw.get("entry", "index.html")).read_text(encoding="utf-8")
        m = check_manifest(raw, page)
        if m["id"] != d.name:
            raise Invalid(f"{d.name}: id passt nicht zum Ordner", f"{d.name}: id doesn't match its folder")
        out.append((m, page))
    return out


async def sync_shipped(s):
    """Puts the shipped extensions into the catalog (new ones, changed versions / pages); a shipped one that's gone
    from the folder leaves the catalog (hidden: its players' rows stay, nobody sees it)."""
    current = shipped()
    ids = {m["id"] for m, _ in current}
    for e in await s.scalars(select(Extension).where(Extension.author_id.is_(None), Extension.status == "published")):
        if e.id not in ids:
            e.status = "hidden"
    for m, page in current:
        e = await s.get(Extension, m["id"])
        text = json.dumps(m, ensure_ascii=False)
        if e is None:
            s.add(Extension(id=m["id"], version=m["version"], manifest=text, page=page, author_id=None, status="published"))
        elif (e.version, e.manifest, e.page, e.status) != (m["version"], text, page, "published"):
            e.version, e.manifest, e.page, e.updated_at, e.status = m["version"], text, page, utcnow(), "published"


def manifest_of(e):
    return json.loads(e.manifest)


def legacy_tab(m):
    """API 1's single tab for Android apps from before tab layouts (versionCode <= 916548 show `tab` of every switched-on
    extension): the first tab's name with the extension's own icon (those apps don't have the tab_* library)."""
    return dict(title=m["tabs"][0]["name"], icon=m["icon"]) if m["tabs"] else None


def extension_json(e, mine=None):
    """A catalog entry; `mine` = the player's UserExtension (installed) or None."""
    m = manifest_of(e)
    return dict(id=e.id, version=e.version, name=m["name"], description=m["description"], author=m["author"],
                icon=m["icon"], tabs=m["tabs"], tab=legacy_tab(m), permissions=m.get("permissions", []),
                collections=sorted(m["collections"]),
                official=e.author_id is None, size=len(e.page.encode()), installed=mine is not None,
                enabled=bool(mine and mine.enabled))


async def installed(s, user_id):
    """The player's downloaded extensions (for the state: their tabs are in the choice of tabs while switched on),
    oldest first."""
    rows = (await s.execute(select(UserExtension, Extension).join(Extension, Extension.id == UserExtension.extension_id)
                            .where(UserExtension.user_id == user_id, Extension.status == "published")
                            .order_by(UserExtension.installed_at, UserExtension.extension_id))).all()
    out = []
    for ue, e in rows:
        m = manifest_of(e)
        out.append(dict(id=e.id, version=e.version, name=m["name"], icon=m["icon"], tabs=m["tabs"], tab=legacy_tab(m),
                        permissions=m.get("permissions", []), enabled=bool(ue.enabled)))
    return out


async def available_tabs(s, user_id):
    """The tab keys a player may place: the standard ones and those of every extension they downloaded (switched off
    ones too - their place is kept while they're off)."""
    return set(tablayout.STANDARD) | {tablayout.ext_key(x["id"], t["key"]) for x in await installed(s, user_id)
                                      for t in x["tabs"]}


async def migrate_layouts(s):
    """Once (config flag): players who had extension tabs before tab layouts keep them where they were - in the bar,
    or together in a group when they don't fit."""
    if await s.get(Config, "tab_layout_init"):
        return
    rows = (await s.execute(select(UserExtension, Extension).join(Extension, Extension.id == UserExtension.extension_id)
                            .where(UserExtension.enabled.is_(True), Extension.status == "published")
                            .order_by(UserExtension.installed_at, UserExtension.extension_id))).all()
    per_user = {}
    for ue, e in rows:
        if manifest_of(e)["tabs"]:
            per_user.setdefault(ue.user_id, []).append(manifest_of(e))
    for user_id, ms in per_user.items():
        st = await s.get(Settings, user_id)
        if st is None or st.tabs:
            continue
        keys = [tablayout.ext_key(m["id"], t["key"]) for m in ms for t in m["tabs"]]
        name, icon = (ms[0]["name"]["de"], ms[0]["tabs"][0]["icon"]) if len(ms) == 1 else ("Extensions", "tab_puzzle")
        st.tabs = json.dumps(tablayout.with_tabs(keys, name, icon))
    s.add(Config(key="tab_layout_init", value="1"))


def compose(e):
    """The page as the apps show it: base styles + the bridge (sdk.js) in front, the CSP that keeps it offline, and
    <base> pointing at the server (its fonts, pictures). __ORIGIN__ = the server's origin, filled in by the app."""
    page = re.sub(r"^\s*<!doctype[^>]*>", "", e.page, flags=re.I)
    css = (ROOT / "base.css").read_text(encoding="utf-8")
    sdk = (ROOT / "sdk.js").read_text(encoding="utf-8")
    return ('<!doctype html><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
            '<base href="__ORIGIN__/">'
            f'<meta http-equiv="Content-Security-Policy" content="{CSP}">'
            f"<style>{css}</style><script>{sdk}</script>\n" + page)


async def delete_user_data(s, user_id):
    """Account deleted: its extensions and their data go too."""
    await s.execute(delete(ExtensionDoc).where(ExtensionDoc.user_id == user_id))
    await s.execute(delete(UserExtension).where(UserExtension.user_id == user_id))


def _iso(dt):
    return dt.replace(tzinfo=timezone.utc).isoformat()


def doc_json(d):
    return dict(key=d.key, value=json.loads(d.value), updated_at=_iso(d.updated_at))


# ---------------------------------------------------------------- routes

def register(app, authed, ApiError):
    from .posts import page_args

    def invalid(e: Invalid, status=422):
        return ApiError(e.de, e.en, status)

    async def published(ext_id):
        e = await g.s.get(Extension, ext_id)
        if e is None or e.status != "published":
            raise ApiError("Extension nicht gefunden", "Extension not found", 404)
        return e

    async def mine(ext_id):
        """An extension the player downloaded (data and page need it)."""
        e = await published(ext_id)
        ue = await g.s.get(UserExtension, (g.user.id, ext_id))
        if ue is None:
            raise ApiError("Extension nicht installiert", "Extension not installed", 409)
        return e, ue

    @app.get("/api/extensions")
    @authed
    async def catalog():
        """Every published extension, with whether I have it and whether it's on."""
        offset, limit = page_args(50)
        rows = list(await g.s.scalars(select(Extension).where(Extension.status == "published")
                                      .order_by(Extension.author_id.is_not(None), Extension.id).offset(offset).limit(limit)))
        mine_ = {u.extension_id: u for u in await g.s.scalars(select(UserExtension).where(UserExtension.user_id == g.user.id))}
        # max_tabs: Android apps from before tab layouts (<= 916548) read it; there's no such limit any more
        return jsonify(extensions=[extension_json(e, mine_.get(e.id)) for e in rows], max_tabs=99)

    @app.post("/api/extensions/<ext_id>/install")
    @authed
    async def install(ext_id):
        """Download: the extension is mine (switched on) and its page comes along."""
        e = await published(ext_id)
        ue = await g.s.get(UserExtension, (g.user.id, ext_id))
        if ue is None:
            ue = UserExtension(user_id=g.user.id, extension_id=ext_id, version=e.version, enabled=True)
            g.s.add(ue)
        ue.version = e.version
        await g.s.flush()
        return jsonify(extension=extension_json(e, ue), page=compose(e), extensions=await installed(g.s, g.user.id))

    @app.get("/api/extensions/<ext_id>/page")
    @authed
    async def page(ext_id):
        """The page again (a new version, or the app lost its copy)."""
        e, ue = await mine(ext_id)
        ue.version = e.version
        return jsonify(id=e.id, version=e.version, page=compose(e))

    @app.put("/api/extensions/<ext_id>")
    @authed
    async def switch(ext_id):
        e, ue = await mine(ext_id)
        on = (await request.get_json(silent=True) or {}).get("enabled")
        if not isinstance(on, bool):
            raise ApiError("enabled: true/false erwartet", "enabled: true/false expected")
        ue.enabled = on
        await g.s.flush()
        return jsonify(extension=extension_json(e, ue), extensions=await installed(g.s, g.user.id))

    @app.delete("/api/extensions/<ext_id>")
    @authed
    async def uninstall(ext_id):
        """Removed: the extension and everything it stored for me; its tabs leave my layout."""
        await g.s.execute(delete(ExtensionDoc).where(ExtensionDoc.user_id == g.user.id, ExtensionDoc.extension_id == ext_id))
        st = g.user.settings
        layout = tablayout.loads(st.tabs)
        if layout is not None:
            st.tabs = json.dumps(tablayout.without(layout, lambda k: k.startswith(f"ext:{ext_id}:")))
        await g.s.execute(delete(UserExtension).where(UserExtension.user_id == g.user.id, UserExtension.extension_id == ext_id))
        await g.s.flush()
        return jsonify(ok=True, extensions=await installed(g.s, g.user.id))

    # ---- data

    async def collection(ext_id, name):
        e, _ = await mine(ext_id)
        col = manifest_of(e)["collections"].get(name)
        if col is None:
            raise ApiError(f"Sammlung {name!r} gibt es nicht", f"No collection {name!r}", 404)
        return col

    def check_key(col, key):
        if not KEY_RE.match(key) or (col.get("key_pattern") and not re.search(col["key_pattern"], key)):
            raise ApiError("Ungültiger Schlüssel", "Invalid key", 422)

    async def find(ext_id, name, key):
        return await g.s.scalar(select(ExtensionDoc).where(
            ExtensionDoc.user_id == g.user.id, ExtensionDoc.extension_id == ext_id,
            ExtensionDoc.collection == name, ExtensionDoc.key == key))

    @app.get("/api/extensions/<ext_id>/data/<name>")
    @authed
    async def list_docs(ext_id, name):
        await collection(ext_id, name)
        offset, limit = page_args()
        column, desc = ORDERS.get(request.args.get("order", "key"), ORDERS["key"])
        col = getattr(ExtensionDoc, column)
        where = (ExtensionDoc.user_id == g.user.id, ExtensionDoc.extension_id == ext_id, ExtensionDoc.collection == name)
        docs = await g.s.scalars(select(ExtensionDoc).where(*where).order_by(col.desc() if desc else col, ExtensionDoc.id)
                                 .offset(offset).limit(limit))
        total = await g.s.scalar(select(func.count()).select_from(ExtensionDoc).where(*where))
        return jsonify(docs=[doc_json(d) for d in docs], total=total)

    @app.get("/api/extensions/<ext_id>/data/<name>/<key>")
    @authed
    async def get_doc(ext_id, name, key):
        await collection(ext_id, name)
        d = await find(ext_id, name, key)
        if d is None:
            raise ApiError("Nicht gefunden", "Not found", 404)
        return jsonify(doc=doc_json(d))

    @app.put("/api/extensions/<ext_id>/data/<name>/<key>")
    @authed
    async def put_doc(ext_id, name, key):
        col = await collection(ext_id, name)
        check_key(col, key)
        body = await request.get_json(silent=True)
        if not isinstance(body, dict) or "value" not in body:
            raise ApiError("value fehlt", "value missing")
        value = body["value"]
        try:
            validate(value, col["schema"])
        except Invalid as e:
            raise invalid(e)
        text = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        size = len(text.encode())
        if size > DOC_BYTES:
            raise ApiError(f"Zu groß (höchstens {DOC_BYTES // 1024} KB)", f"Too big (at most {DOC_BYTES // 1024} KB)", 413)
        d = await find(ext_id, name, key)
        used = await g.s.scalar(select(func.coalesce(func.sum(ExtensionDoc.size), 0)).where(
            ExtensionDoc.user_id == g.user.id, ExtensionDoc.extension_id == ext_id)) or 0
        if used - (d.size if d else 0) + size > TOTAL_BYTES:
            raise ApiError("Der Speicher dieser Extension ist voll", "This extension's storage is full", 507)
        if d is None:
            count = await g.s.scalar(select(func.count()).select_from(ExtensionDoc).where(
                ExtensionDoc.user_id == g.user.id, ExtensionDoc.extension_id == ext_id, ExtensionDoc.collection == name))
            if count >= col["max_docs"]:
                raise ApiError(f"Höchstens {col['max_docs']} Einträge", f"At most {col['max_docs']} entries", 507)
            d = ExtensionDoc(user_id=g.user.id, extension_id=ext_id, collection=name, key=key)
            g.s.add(d)
        d.value, d.size, d.updated_at = text, size, utcnow()
        await g.s.flush()
        return jsonify(doc=doc_json(d))

    @app.delete("/api/extensions/<ext_id>/data/<name>/<key>")
    @authed
    async def delete_doc(ext_id, name, key):
        await collection(ext_id, name)
        await g.s.execute(delete(ExtensionDoc).where(
            ExtensionDoc.user_id == g.user.id, ExtensionDoc.extension_id == ext_id,
            ExtensionDoc.collection == name, ExtensionDoc.key == key))
        return jsonify(ok=True)
