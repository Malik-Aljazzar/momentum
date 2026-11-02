"""@mentions are stored as account references, not names.

Saving turns "@tim" into "@[8]" (only for existing accounts); reading turns "@[8]" back into
the *current* username. A renamed account therefore shows its new name in every post,
comment and bio at once, without rewriting any stored text. Resolving is batched: one query
per response for all references in it.
"""
import re

from sqlalchemy import select

from .db import Comment, Config, Post, User

NAME = re.compile(r"@([a-z0-9_.]{3,30})", re.IGNORECASE)
REF = re.compile(r"@\[(\d+)\]")
MIGRATED = "mentions_as_refs"


async def encode(s, text):
    """"Hi @Tim." -> "Hi @[8]." (trailing dots are punctuation, not part of the name)."""
    if not text or "@" not in text:
        return text or ""
    found = {}
    for m in NAME.finditer(text):
        found[m.group(1).rstrip(".").lower()] = None
    if not found:
        return text
    rows = await s.execute(select(User.username, User.id).where(User.username.in_(found), User.username_set.is_(True)))
    ids = dict(rows.all())

    def repl(m):
        raw = m.group(1)
        name = raw.rstrip(".")
        uid = ids.get(name.lower())
        return f"@[{uid}]{raw[len(name):]}" if uid else m.group(0)
    return NAME.sub(repl, text)


async def names_for(s, texts):
    """{user id: current username} for all references in the given texts."""
    ids = {int(i) for t in texts if t for i in REF.findall(t)}
    if not ids:
        return {}
    return dict((await s.execute(select(User.id, User.username).where(User.id.in_(ids)))).all())


def render(text, names):
    """References -> "@username" (deleted accounts keep a neutral marker)."""
    if not text or "@[" not in text:
        return text or ""
    return REF.sub(lambda m: "@" + names.get(int(m.group(1)), "unknown"), text)


async def render_one(s, text):
    return render(text, await names_for(s, [text]))


def referenced_ids(text):
    return {int(i) for i in REF.findall(text or "")}


async def migrate(s):
    """One-off: older posts, comments and bios still contain plain @names."""
    if await s.get(Config, MIGRATED):
        return
    for model, field in ((Post, "text"), (Comment, "text"), (User, "bio")):
        for row in await s.scalars(select(model).where(getattr(model, field).like("%@%"))):
            setattr(row, field, await encode(s, getattr(row, field)))
    s.add(Config(key=MIGRATED, value="1"))
