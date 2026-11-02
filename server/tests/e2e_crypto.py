"""The chat encryption of the apps (server/e2e.py) in Python: the tests build and open messages with it,
and it writes the test vector both apps must open (web/tests/unit/e2e-vector.json, android test resources)."""
import base64
import json
import os

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

b64 = lambda b: base64.b64encode(b).decode()
unb64 = base64.b64decode


def new_key():
    return ec.generate_private_key(ec.SECP256R1())


def raw_public(key):
    pub = key.public_key() if hasattr(key, "public_key") else key
    return pub.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def public_b64(key):
    return b64(raw_public(key))


def load_public(value):
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), unb64(value))


def hkdf(ikm, salt, info):
    return HKDF(hashes.SHA256(), 32, salt, info.encode()).derive(ikm)


def seal(key, plain, aad, iv=None):
    iv = iv or os.urandom(12)
    return iv + AESGCM(key).encrypt(iv, plain, aad.encode() if aad is not None else None)


def unseal(key, data, aad):
    return AESGCM(key).decrypt(data[:12], data[12:], aad.encode() if aad is not None else None)


def backup(private_key, recovery):
    pkcs8 = private_key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return b64(seal(hkdf(recovery, bytes(32), "momentum-e2e-backup-v1"), pkcs8, "momentum-backup"))


def restore(backup_b64, recovery):
    pkcs8 = unseal(hkdf(recovery, bytes(32), "momentum-e2e-backup-v1"), unb64(backup_b64), "momentum-backup")
    return serialization.load_der_private_key(pkcs8, None)


def _wrap_key(eph, sender, reader_pub, conv, uid):
    z1 = eph.exchange(ec.ECDH(), reader_pub)
    z2 = sender.exchange(ec.ECDH(), reader_pub)
    return hkdf(z1 + z2, raw_public(eph), f"momentum-wrap-v1:{conv}:{uid}")


def encrypt(sender, conv, readers, body, preview):
    """readers: {user id: public key (b64)} -> the envelope the apps send."""
    k = os.urandom(32)
    eph = new_key()
    eph_raw = raw_public(eph)
    keys = {}
    for uid, pub in readers.items():
        wk = _wrap_key(eph, sender, load_public(pub), conv, uid)
        keys[str(uid)] = b64(seal(wk, k, None))
    aad = f"momentum-msg-v1:{conv}"
    return dict(v=1, eph=b64(eph_raw), spk=public_b64(sender),
                ct=b64(seal(k, json.dumps(body).encode(), aad)), pv=b64(seal(k, json.dumps(preview).encode(), aad + ":pv")),
                keys=keys)


def open_key(env, reader, conv, uid):
    eph = load_public(env["eph"])
    spk = load_public(env["spk"])
    z1 = reader.exchange(ec.ECDH(), eph)
    z2 = reader.exchange(ec.ECDH(), spk)
    wk = hkdf(z1 + z2, unb64(env["eph"]), f"momentum-wrap-v1:{conv}:{uid}")
    return unseal(wk, unb64(env["key"]), None)


def decrypt(env, reader, conv, uid, part="ct"):
    """A reader's envelope (with "key") -> the body (or the preview: part="pv")."""
    k = open_key(env, reader, conv, uid)
    aad = f"momentum-msg-v1:{conv}" + (":pv" if part == "pv" else "")
    return json.loads(unseal(k, unb64(env[part]), aad))


def safety_code(pub_b64):
    """30 digits from a public key (people compare them in person)."""
    import hashlib
    h = hashlib.sha256(unb64(pub_b64)).digest()
    return " ".join(f"{int.from_bytes(h[i * 5:i * 5 + 5], 'big') % 100000:05d}" for i in range(6))


def vector():
    """A fixed message for the apps' tests: they must open it with the reader's key and get the body."""
    alice, bob = new_key(), new_key()
    recovery = os.urandom(32)
    env = encrypt(alice, 7, {3: public_b64(alice), 5: public_b64(bob)},
                  {"t": "Hallo Bob ✓ – grüß dich 👋", "m": None}, {"t": "Hallo Bob ✓ – grüß dich 👋", "k": None})
    media_key = os.urandom(32)
    return dict(
        conversation=7, reader=5, sender=3,
        reader_pkcs8=b64(bob.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption())),
        reader_public=public_b64(bob), sender_public=public_b64(alice), reader_safety_code=safety_code(public_b64(bob)),
        envelope=dict(v=1, eph=env["eph"], spk=env["spk"], ct=env["ct"], pv=env["pv"], key=env["keys"]["5"]),
        text="Hallo Bob ✓ – grüß dich 👋",
        recovery=b64(recovery), backup=backup(bob, recovery),
        media_key=b64(media_key), media=b64(seal(media_key, b"pixel bytes", None)), media_plain=b64(b"pixel bytes"),
    )


if __name__ == "__main__":       # python -m server.tests.e2e_crypto > vector.json
    print(json.dumps(vector(), indent=1, ensure_ascii=False))
