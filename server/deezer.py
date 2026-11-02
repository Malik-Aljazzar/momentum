"""Songs for a post's sound (like Instagram's music): search Deezer's catalogue and fetch a song's
30 s preview. Deezer's API can't be called from browsers (no CORS) and its preview links expire
after a while - so the server asks and passes the MP3 on; the client then uses it like an own
sound (picked part, uploaded with the post, stored with our media)."""
import json
import urllib.parse
import urllib.request

API = "https://api.deezer.com"
HEADERS = {"User-Agent": "Momentum/1.0 (momentum.mal-zeta.de)"}
MAX_PREVIEW = 3_000_000          # a 30 s MP3 is ~0.5 MB


def _get(url, timeout=8):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=timeout) as r:
        return r.read(MAX_PREVIEW + 1)


def search(q):
    """[{id, title, artist, cover, duration}] - only songs that have a preview; [] if Deezer is unreachable."""
    try:
        data = json.loads(_get(f"{API}/search?" + urllib.parse.urlencode({"q": q, "limit": 25})))
    except (OSError, ValueError):
        return []
    out = []
    for t in data.get("data", []):
        if not t.get("preview") or t.get("type", "track") != "track":
            continue
        out.append(dict(id=int(t["id"]), title=str(t.get("title_short") or t.get("title") or "")[:80],
                        artist=str((t.get("artist") or {}).get("name") or "")[:60],
                        cover=(t.get("album") or {}).get("cover_medium") or (t.get("album") or {}).get("cover"),
                        duration=30))
    return out


def preview(track_id):
    """(mp3 bytes, "Artist – Title") of a song's 30 s preview, or None."""
    try:
        t = json.loads(_get(f"{API}/track/{int(track_id)}"))
        url = t.get("preview")
        if not url or "error" in t:
            return None
        data = _get(url, timeout=15)
    except (OSError, ValueError):
        return None
    if not data or len(data) > MAX_PREVIEW:
        return None
    artist = (t.get("artist") or {}).get("name") or ""
    title = t.get("title_short") or t.get("title") or ""
    return data, (f"{artist} – {title}" if artist else title)[:60]
