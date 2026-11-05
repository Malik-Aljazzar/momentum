"""Server storage: what the database and the blob store take (by category) and the admins' limit.

The DB file plus all blobs never grow past `storage_limit` (config, bytes; set in the admin app, at most
the whole disk): uploads that would cross it are refused, and once it's reached every other write is
refused too (HTTP 507) until something is deleted or the limit is raised.
"""
import os
import shutil
import time
from pathlib import Path

from sqlalchemy import select

from .blobs import StoreFull
from .db import Config, Conversation, Media, Message, Report, User

LIMIT_KEY = "storage_limit"
# free disk the server never fills (other services share the disk; MinIO itself refuses writes when
# less than ~1 % of the disk is free, and the host needs room for logs / the DB's WAL)
DISK_RESERVE_BYTES = 512 * 1024 * 1024
DISK_RESERVE_FRACTION = 0.02
SIZES_TTL = 600            # the blob listing is redone at most every 10 minutes (puts/deletes keep it current)

# blob categories (Media rows by what they belong to; avatars by key; the rest is orphaned) and their group
BLOB_CATEGORIES = {
    "post_image": "posts", "post_video": "posts", "post_sound": "posts",
    "story_image": "stories", "story_video": "stories", "story_sound": "stories",
    "chat_image": "chat", "chat_video": "chat", "chat_voice": "chat",
    "avatar": "profiles", "group_image": "profiles",
    "report_image": "reports",
    "draft": "other", "other_media": "other", "orphan": "other",
}
# DB tables -> category (unknown tables count as db_other)
DB_TABLES = {
    "db_users": ["users", "settings", "daily_decay", "follows", "follow_requests", "blocks", "auth_tokens",
                 "user_activity", "push_tokens"],
    "db_rules": ["rules", "group_decays"],
    "db_events": ["events", "daily_caps"],
    "db_posts": ["posts", "media", "media_triggers", "post_likes", "post_tags", "comments", "comment_likes"],
    "db_stories": ["stories", "story_seen", "highlights", "highlight_stories"],
    "db_messages": ["messages", "message_reactions", "conversations", "conversation_members"],
    "db_challenges": ["challenges", "challenge_participants", "challenge_rules"],
    "db_notifications": ["notifications"],
    "db_other": ["reports", "config"],
}
TABLE_CATEGORY = {t: c for c, tables in DB_TABLES.items() for t in tables}


class QuotaExceeded(Exception):
    """The server's storage limit is reached."""


class MeteredStore:
    """Wraps the blob store: knows every object's size and refuses puts past the storage limit."""

    def __init__(self, inner, quota):
        self.inner, self.quota = inner, quota
        self._sizes, self._at = None, 0.0
        quota.store = self

    def __getattr__(self, name):          # e.g. .objects of the test store
        return getattr(self.inner, name)

    async def setup(self):
        await self.inner.setup()

    async def sizes(self, refresh=False):
        """{key: bytes} of every object."""
        if self._sizes is None or refresh or time.monotonic() - self._at > SIZES_TTL:
            self._sizes, self._at = await self.inner.sizes(), time.monotonic()
        return self._sizes

    async def total(self):
        return sum((await self.sizes()).values())

    async def put(self, key, data, content_type):
        sizes = await self.sizes()
        if self.quota.db_bytes() + sum(sizes.values()) - sizes.get(key, 0) + len(data) > self.quota.limit():
            raise QuotaExceeded()
        if len(data) - sizes.get(key, 0) > self.quota.headroom():
            raise QuotaExceeded()
        try:
            await self.inner.put(key, data, content_type)
        except StoreFull as e:                    # the disk filled up behind our back (other services)
            raise QuotaExceeded() from e
        sizes[key] = len(data)

    async def get(self, key):
        return await self.inner.get(key)

    async def stat(self, key):
        return await self.inner.stat(key)

    async def read(self, key, start, length):
        return await self.inner.read(key, start, length)

    async def delete(self, key):
        await self.inner.delete(key)
        if self._sizes is not None:
            self._sizes.pop(key, None)


class Quota:
    def __init__(self, db_path, data_dir):
        self.db_path, self.data_dir = Path(db_path) if db_path else None, data_dir
        self.store = None
        self.configured = None            # bytes, None = not set (the whole disk)

    async def load(self, s):
        row = await s.get(Config, LIMIT_KEY)
        self.configured = int(row.value) if row else None

    async def save(self, s, value):
        row = await s.get(Config, LIMIT_KEY)
        if row is None:
            s.add(Config(key=LIMIT_KEY, value=str(value)))
        else:
            row.value = str(value)
        self.configured = value

    def disk(self):
        """(total, free) bytes of the disk holding the data."""
        d = shutil.disk_usage(self.data_dir)
        return d.total, d.free

    def limit(self):
        total = self.disk()[0]
        return min(self.configured, total) if self.configured else total

    def reserve(self):
        """Free bytes the disk must keep (see DISK_RESERVE_*)."""
        return max(DISK_RESERVE_BYTES, int(self.disk()[0] * DISK_RESERVE_FRACTION))

    def headroom(self):
        """Bytes that may still be written before the disk's reserve is touched (0 when it already is)."""
        return max(0, self.disk()[1] - self.reserve())

    def db_bytes(self):
        if self.db_path is None:
            return 0
        return sum(os.path.getsize(p) for p in (self.db_path, Path(f"{self.db_path}-wal"))
                   if p.exists())

    async def used(self):
        return self.db_bytes() + await self.store.total()

    async def full(self):
        return self.headroom() == 0 or await self.used() >= self.limit()


def db_path_of(url):
    """File of a sqlite URL (None for memory / other databases)."""
    if not url.startswith("sqlite") or ":memory:" in url:
        return None
    return url.split(":///", 1)[1]


# ---------------------------------------------------------------- usage by category

async def _media_categories(s):
    """[(Media, category)] for every media row."""
    chat = set(await s.scalars(select(Message.media_id).where(Message.media_id.is_not(None))))
    groups = set()
    for a, b in (await s.execute(select(Conversation.avatar_media_id, Conversation.banner_media_id))).all():
        groups.update(x for x in (a, b) if x)
    report_keys = set()
    for keys in await s.scalars(select(Report.media_keys)):
        report_keys.update(filter(None, keys.split("\n")))
    out = []
    for md in await s.scalars(select(Media)):
        kind = {"image": "image", "video": "video", "audio": "sound"}.get(md.kind, "image")
        if md.story_id:
            cat = f"story_{kind}"
        elif md.post_id:
            cat = f"post_{kind}"
        elif md.id in chat:
            cat = "chat_voice" if kind == "sound" else f"chat_{kind}"
        elif md.id in groups:
            cat = "group_image"
        elif md.key in report_keys:
            cat = "report_image"
        elif not md.attached:
            cat = "draft"
        else:
            cat = "other_media"
        out.append((md, cat))
    return out


async def blob_items(s, store, refresh=False):
    """Every blob item with its size and category: dicts with category, key, thumb, size, user_id, media."""
    sizes = dict(await store.sizes(refresh))
    items = []
    for md, cat in await _media_categories(s):
        from .hls import files_of
        size = sizes.pop(md.key, 0) + sum(sizes.pop(k, 0) for k in (md.thumb_key, md.cover_key, *files_of(md)) if k)
        items.append(dict(category=cat, key=md.key, thumb=md.thumb_key or md.key, size=size, user_id=md.user_id,
                          media=md, created_at=md.created_at))
    for key, size in sizes.items():
        if key.startswith("avatars/"):
            try:
                uid = int(key.split("/")[1])
            except (IndexError, ValueError):
                uid = None
            items.append(dict(category="avatar", key=key, thumb=key, size=size, user_id=uid, media=None, created_at=None))
        else:
            items.append(dict(category="orphan", key=key, thumb=key, size=size, user_id=None, media=None, created_at=None))
    return items


def _table_estimates(conn):
    """{table: (rows, bytes)}: exact page sizes where SQLite has dbstat, else the sum of the values' lengths."""
    tables = [r[0] for r in conn.exec_driver_sql(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").fetchall()]
    rows = {t: conn.exec_driver_sql(f'SELECT COUNT(*) FROM "{t}"').scalar() for t in tables}
    try:
        pages = dict(conn.exec_driver_sql(
            "SELECT coalesce(m.tbl_name, d.name), SUM(d.pgsize) FROM dbstat d "
            "LEFT JOIN sqlite_master m ON m.name = d.name GROUP BY 1").fetchall())
        return {t: (rows[t], int(pages.get(t, 0))) for t in tables}
    except Exception:
        pass
    out = {}
    for t in tables:
        cols = [r[1] for r in conn.exec_driver_sql(f'PRAGMA table_info("{t}")').fetchall()]
        expr = " + ".join(f'coalesce(length("{c}"), 0)' for c in cols) or "0"
        out[t] = (rows[t], int(conn.exec_driver_sql(f'SELECT coalesce(SUM({expr}), 0) FROM "{t}"').scalar()
                               + rows[t] * (8 + 2 * len(cols))))
    return out


async def db_tables(s, db_bytes):
    """[{table, category, rows, bytes}], bytes scaled so they add up to the DB file."""
    conn = await s.connection()
    est = await conn.run_sync(_table_estimates)
    total = sum(b for _, b in est.values()) or 1
    return sorted((dict(table=t, category=TABLE_CATEGORY.get(t, "db_other"), rows=n,
                        bytes=round(b * db_bytes / total)) for t, (n, b) in est.items()),
                  key=lambda r: -r["bytes"])


async def overview(s, quota, refresh=False):
    items = await blob_items(s, quota.store, refresh)
    db_bytes = quota.db_bytes()
    tables = await db_tables(s, db_bytes)
    cats = {}
    for it in items:
        c = cats.setdefault(it["category"], dict(key=it["category"], group=BLOB_CATEGORIES[it["category"]],
                                                  type="blob", bytes=0, count=0))
        c["bytes"] += it["size"]
        c["count"] += 1
    for t in tables:
        c = cats.setdefault(t["category"], dict(key=t["category"], group="database", type="db", bytes=0, count=0))
        c["bytes"] += t["bytes"]
        c["count"] += t["rows"]
    blob_bytes = sum(it["size"] for it in items)
    disk_total, disk_free = quota.disk()
    return dict(used=db_bytes + blob_bytes, db_bytes=db_bytes, blob_bytes=blob_bytes, limit=quota.limit(),
                limit_set=quota.configured is not None, disk_total=disk_total, disk_free=disk_free,
                categories=sorted(cats.values(), key=lambda c: -c["bytes"]))


async def category_items(s, quota, category, offset, limit):
    """One category's entries, largest first: files (blob categories) or tables (DB categories)."""
    if category.startswith("db_"):
        tables = [t for t in await db_tables(s, quota.db_bytes()) if t["category"] == category]
        return dict(tables=tables, items=[], total=len(tables))
    items = sorted((it for it in await blob_items(s, quota.store) if it["category"] == category), key=lambda it: -it["size"])
    page = items[offset:offset + limit]
    users = {u.id: u.username for u in await s.scalars(
        select(User).where(User.id.in_({it["user_id"] for it in page if it["user_id"]})))}
    out = []
    for it in page:
        md = it["media"]
        out.append(dict(key=it["key"], url=f"/{it['key']}", thumb=f"/{it['thumb']}", size=it["size"],
                        kind=md.kind if md else ("image" if it["key"].endswith((".webp", ".jpg", ".png")) else "file"),
                        username=users.get(it["user_id"], ""), media_id=md.id if md else None,
                        post_id=md.post_id if md else None, story_id=md.story_id if md else None,
                        width=md.width if md else 0, height=md.height if md else 0,
                        duration=md.duration if md else 0,
                        created_at=it["created_at"].isoformat() + "Z" if it["created_at"] else None))
    return dict(items=out, tables=[], total=len(items))
