"""Place search for a post's location (like Instagram): OpenStreetMap data via Photon (komoot, free,
no key). The server asks, so the clients don't need their own network rules for it."""
import json
import urllib.parse
import urllib.request

PHOTON = "https://photon.komoot.io/api/"
_cache: dict = {}


def _label(p):
    name = p.get("name") or p.get("street") or ""
    where = [p.get(k) for k in ("city", "state", "country") if p.get(k) and p.get(k) != name]
    return name, ", ".join(dict.fromkeys(where))


def fetch_places(q, lang):
    """[{name, detail, lat, lon}] for a search text; [] if Photon can't be reached."""
    key = (q.lower(), lang)
    if key in _cache:
        return _cache[key]
    url = PHOTON + "?" + urllib.parse.urlencode({"q": q, "limit": 8, "lang": "de" if lang == "de" else "en"})
    req = urllib.request.Request(url, headers={"User-Agent": "Momentum/1.0 (momentum.mal-zeta.de)"})
    try:
        with urllib.request.urlopen(req, timeout=6) as r:
            data = json.load(r)
    except (OSError, ValueError):
        return []
    out, seen = [], set()
    for f in data.get("features", []):
        name, detail = _label(f.get("properties", {}))
        lon, lat = (f.get("geometry", {}).get("coordinates") or [None, None])[:2]
        if not name or (name, detail) in seen:
            continue
        seen.add((name, detail))
        out.append(dict(name=name[:80], detail=detail[:120], lat=lat, lon=lon))
    if len(_cache) > 500:
        _cache.clear()
    _cache[key] = out
    return out
