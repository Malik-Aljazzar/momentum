"""End-to-end encrypted chats: the server only keeps and forwards ciphertext, it can't read it.

Keys: every player has one P-256 key pair. The public key (raw point, base64) is stored here; the private
key only exists on the player's devices and - encrypted with the recovery key, 32 random bytes the player
keeps and that never reach the server - as a backup here, so a new device can get it back:
    backup = iv || AES-256-GCM(HKDF(recovery, info "momentum-e2e-backup-v1"), iv, PKCS#8 private key)

A message (built by the clients: android/.../data/E2E.kt = web/src/e2e.ts):
    K    = 32 random bytes
    ct   = iv || AES-256-GCM(K, iv, {"t": text, "m": {"k": media key, "mime": ...},
                                     "l": a link preview (server/linkpreview.py) or absent}, aad "momentum-msg-v1:<conv>")
    pv   = iv || AES-256-GCM(K, iv, {"t": first 180 chars, "k": "image" | "audio" | null}, aad "...:pv")
           (the preview: chat list, quotes, push notifications)
    eph  = a new key pair per message; for every member u (the sender too):
    keys[u] = iv || AES-256-GCM(HKDF(ECDH(eph, u) || ECDH(sender, u), salt eph public,
                                     info "momentum-wrap-v1:<conv>:<u>"), iv, K)
The second ECDH ties the message to the sender's key: nobody without it - the server neither - can make
one. Stored as JSON {"v", "eph", "spk" (the sender's public key), "ct", "pv", "keys": {user id: ...}};
each reader only gets their own wrapped key.

A message must fit the chat as it is now: every member with a key gets a wrapped key (a new member would
read nothing otherwise), and the apps send "kh", the fingerprint of the members' keys they encrypted for
(GET /conversations/<id>/keys gives it as "hash"). Someone joined / left / reset their key since -> 409,
the app loads the keys again and sends again.

Media (chat photos, videos, voice messages) are encrypted on the phone with a key of their own (iv || AES-GCM)
and stored as they come (no processing here: the apps shrink videos to 720p / 60 s themselves); photos and
videos bring an encrypted small copy (a video's still), which is all that stays after chat.CHAT_IMAGE_DAYS.
"""
import base64
import json
import secrets

from quart import g, jsonify, request

from .db import Media
from .mentions import referenced_ids

VERSION = 1
MAX_CT = 100_000         # base64 characters of a message body (a link preview's small picture travels inside)
MAX_PV = 2_000
MAX_WRAPPED = 200
MAX_BACKUP = 2_000
MAX_MEDIA = 40 * 1024 * 1024
MAX_VIDEO = 100 * 1024 * 1024    # a chat video (the apps shrink it first; a phone that can't sends it as it is)


class KeyChanged(Exception):
    """The sender wrote with a key that isn't their current one (reset on another device)."""


class StaleKeys(Exception):
    """The message wasn't encrypted for the chat's current members and keys (someone joined, left or reset)."""


def keys_hash(members):
    """A fingerprint of who has which key ({user id: public key or None}): the apps send it back with a
    message ("kh"); one from before a member / key change is refused."""
    import hashlib
    items = sorted(f"{uid}:{pub}" for uid, pub in members.items() if pub)
    return hashlib.sha256("\n".join(items).encode()).hexdigest()[:16]


def _b64(value, limit, size=None):
    if not isinstance(value, str) or not value or len(value) > limit:
        return None
    try:
        raw = base64.b64decode(value, validate=True)
    except ValueError:
        return None
    return raw if size is None or len(raw) == size else None


def valid_public_key(value):
    """A P-256 point, uncompressed (65 bytes), base64."""
    raw = _b64(value, 200, 65)
    if raw is None:
        return False
    from cryptography.hazmat.primitives.asymmetric import ec
    try:
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)
    except ValueError:
        return False
    return True


def parse_envelope(data, members, sender):
    """The envelope a client sent, checked (shape, sizes, readers are members, every member with a key
    is a reader, "kh" is current) -> stored JSON. members: {user id: public key or None}."""
    if not isinstance(data, dict) or data.get("v") != VERSION:
        raise ValueError("version")
    if not sender.e2e_public or data.get("spk") != sender.e2e_public:
        raise KeyChanged()
    # eph must be a point on the curve: readers do ECDH with it (an invalid one could probe their key)
    if not valid_public_key(data.get("eph")) or _b64(data.get("ct"), MAX_CT) is None or _b64(data.get("pv"), MAX_PV) is None:
        raise ValueError("fields")
    keys = data.get("keys")
    if not isinstance(keys, dict) or not keys:
        raise ValueError("keys")
    out = {}
    for uid, wrapped in keys.items():
        try:
            uid = int(uid)
        except (TypeError, ValueError):
            raise ValueError("reader")
        if _b64(wrapped, MAX_WRAPPED) is None:
            raise ValueError("reader")
        if uid not in members:              # left the chat since the app loaded the keys
            raise StaleKeys()
        out[str(uid)] = wrapped
    if data.get("kh") is not None and data["kh"] != keys_hash(members):
        raise StaleKeys()
    if any(pub and str(uid) not in out for uid, pub in members.items()):      # a member who couldn't read it
        raise StaleKeys()
    return json.dumps(dict(v=VERSION, eph=data["eph"], spk=data["spk"], ct=data["ct"], pv=data["pv"], keys=out),
                      separators=(",", ":"))


def envelope_for(msg, user_id, full=True):
    """What one reader gets: their wrapped key and the preview (full: the whole body too)."""
    if not msg.e2e:
        return None
    env = json.loads(msg.e2e)
    out = dict(v=env["v"], eph=env["eph"], spk=env["spk"], pv=env["pv"], key=env["keys"].get(str(user_id)))
    if full:
        out["ct"] = env["ct"]
    return out


def mentioned_ids(msg):
    """Tagged accounts: references in a plain text or, encrypted, the list the sender gave."""
    return referenced_ids(msg.text) | {int(x) for x in (msg.mentions or "").split(",") if x.isdigit()}


def register(app, authed, api_error, blobs):
    from .posts import drop_stale_uploads, media_json

    @app.get("/api/e2e/me")
    @authed
    async def my_keys():
        """My public key and the backup of my private key (only the recovery key opens it)."""
        return jsonify(public=g.user.e2e_public, backup=g.user.e2e_backup)

    @app.put("/api/e2e/me")
    @authed
    async def set_my_keys():
        """{"public", "backup", "replace"}: the first key, or a new one (replace: the recovery key is lost -
        the old encrypted messages can't be read any more)."""
        data = await request.get_json(force=True) or {}
        public, backup = data.get("public"), data.get("backup")
        if not valid_public_key(public) or _b64(backup, MAX_BACKUP) is None:
            raise api_error("Schlüssel ungültig", "Invalid key")
        if g.user.e2e_public and g.user.e2e_public != public and not data.get("replace"):
            raise api_error("Auf einem anderen Gerät gibt es schon einen Schlüssel",
                            "Another device already made a key", 409)
        g.user.e2e_public, g.user.e2e_backup = public, backup
        return jsonify(public=public, backup=backup)

    @app.post("/api/media/encrypted")
    @authed
    async def upload_encrypted():
        """An encrypted chat photo / video / voice message as it is (?kind=image|video|audio&width=&height=&duration=)."""
        from .media import VIDEO_MAX_SECONDS
        raw = await request.get_data()
        kind = request.args.get("kind", "")
        if kind not in ("image", "video", "audio") or not 28 <= len(raw) <= (MAX_VIDEO if kind == "video" else MAX_MEDIA):
            raise api_error("Datei ungültig", "Invalid file")                        # iv + tag at least
        duration = max(0.0, request.args.get("duration", 0.0, type=float))
        md = Media(user_id=g.user.id, kind=kind, key=f"media/{g.user.id}/{secrets.token_hex(10)}.bin", encrypted=True,
                   width=max(0, request.args.get("width", 0, type=int)), height=max(0, request.args.get("height", 0, type=int)),
                   duration=min(duration, VIDEO_MAX_SECONDS) if kind == "video" else duration)
        await put_blob(md.key, raw)
        await drop_stale_uploads(g.s, blobs, g.user.id)
        g.s.add(md)
        await g.s.flush()
        return jsonify(media=media_json(md))

    @app.post("/api/media/<int:media_id>/thumb")
    @authed
    async def upload_encrypted_thumb(media_id):
        """The encrypted small copy of an encrypted photo / a video's still (shown in the chat, kept after two weeks)."""
        md = await g.s.get(Media, media_id)
        raw = await request.get_data()
        if md is None or md.user_id != g.user.id or not md.encrypted or md.kind not in ("image", "video") or md.attached \
                or not 28 <= len(raw) <= MAX_MEDIA:
            raise api_error("Datei ungültig", "Invalid file")
        md.thumb_key = md.key.removesuffix(".bin") + "_thumb.bin"
        await put_blob(md.thumb_key, raw)
        return jsonify(media=media_json(md))

    async def put_blob(key, raw):
        from .blobs import BlobUnavailable
        try:
            await blobs.put(key, raw, "application/octet-stream")
        except BlobUnavailable:
            raise api_error("Medienspeicher nicht erreichbar", "Media storage unavailable", 503)
