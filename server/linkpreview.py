"""Link previews in chats (like WhatsApp): while a player types a message with a web link, the app asks
GET /api/link-preview?url=..., the server fetches the page and answers its title, description and a small
copy of its picture (Open Graph / Twitter tags, else <title> / <meta name=description>). The sender's app
sends that preview along with the message: a plain chat keeps it in Message.link, an encrypted one inside
the envelope's body ("l", server/e2e.py) - the server can't read it there, readers fetch nothing.
Clients show it only for a link that is in the message's text, with the host taken from that link.

Fetching pages for others is a classic hole (SSRF): only http(s) on the usual ports, only public addresses
(every redirect is checked again, and the connection goes to the address that was checked - a second DNS
answer can't point it inside), small reads, short timeouts. Results stay in memory for a while; nothing
about who shared what is stored or logged."""
import asyncio
import base64
import http.client
import io
import ipaddress
import re
import socket
import ssl
import time
import zlib
from html.parser import HTMLParser
from urllib.parse import quote, urljoin, urlsplit

PORTS = {80, 443, 8080, 8443}
TIMEOUT = 5                  # seconds per connect / read
DEADLINE = 9                 # seconds for a whole preview (page + picture)
MAX_REDIRECTS = 5
MAX_HTML = 1_500_000         # bytes of a page read up to its </head> (YouTube's tags come after ~800 kB of script)
MAX_IMAGE = 6_000_000        # bytes of a picture downloaded
IMAGE_SIDE = 480             # the small copy's longest side
IMAGE_BYTES = 20_000         # ... and its size (it travels inside every message that has the link)
MAX_IMAGE_B64 = 30_000       # what a message may carry (base64)
TITLE, DESCRIPTION, SITE, MAX_URL = 200, 400, 100, 2000
CACHE_SECONDS, FAIL_SECONDS, CACHE_SIZE = 3600, 600, 400
PER_MINUTE = 30              # previews one player may ask for
AGENT = "Mozilla/5.0 (compatible; MomentumLinkPreview/1.0; +https://momentum.mal-zeta.de/legal)"

# the same links the apps make tappable (web/src/links.ts = android/.../ui/Links.kt)
LINK = re.compile(r"(?:https?://|www\.)[^\s<>\"]+", re.I)
TRAILING = re.compile(r"[.,;:!?'\")\]}]+$")
DOMAIN = re.compile(r"[A-Za-z0-9]\.[A-Za-z]{2,}")


class Blocked(Exception):
    """The address isn't one we fetch (not public, odd port / scheme, too many redirects)."""


def links_in(text):
    """The web links of a text as the apps open them ("www.x.de" -> "https://www.x.de")."""
    out = []
    for m in LINK.finditer(text or ""):
        link = TRAILING.sub("", m.group())
        if DOMAIN.search(link):
            out.append(link if re.match(r"https?://", link, re.I) else "https://" + link)
    return out


def public_ip(value):
    """True for an address on the open internet (not loopback, private, link-local, reserved, ...)."""
    try:
        ip = ipaddress.ip_address(value.split("%")[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_global and not ip.is_multicast


def _resolve(host, port):
    """The first public address of a host - Blocked if any answer points inside."""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except (socket.gaierror, UnicodeError):
        raise Blocked("dns")
    ips = [i[4][0] for i in infos]
    if not ips or not all(public_ip(ip) for ip in ips):
        raise Blocked("address")
    return ips[0]


class _Pinned(http.client.HTTPConnection):
    def __init__(self, host, port, ip):
        super().__init__(host, port, timeout=TIMEOUT)
        self._ip = ip

    def connect(self):
        self.sock = socket.create_connection((self._ip, self.port), self.timeout)


class _PinnedTLS(http.client.HTTPSConnection):
    def __init__(self, host, port, ip):
        super().__init__(host, port, timeout=TIMEOUT, context=ssl.create_default_context())
        self._ip = ip

    def connect(self):
        sock = socket.create_connection((self._ip, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def _target(url):
    """(scheme, host, port, path) of an address we may fetch."""
    parts = urlsplit(url)
    scheme = parts.scheme.lower()
    if scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        raise Blocked("scheme")
    try:
        port = parts.port or (443 if scheme == "https" else 80)
    except ValueError:
        raise Blocked("port")
    if port not in PORTS:
        raise Blocked("port")
    try:
        host = parts.hostname.encode("idna").decode("ascii")         # "münchen.de" -> "xn--mnchen-3ya.de"
    except UnicodeError:
        raise Blocked("host")
    path = quote((parts.path or "/") + (f"?{parts.query}" if parts.query else ""), safe="/?&=%:@!$'()*+,;~[]-._")
    return scheme, host, port, path


def _read(resp, limit, deadline, stop=None):
    """At most limit bytes of the body (unpacked if it came compressed) - a page only up to its </head>."""
    enc = (resp.getheader("Content-Encoding") or "").lower()
    d = zlib.decompressobj(zlib.MAX_WBITS | 32) if enc in ("gzip", "deflate") else None
    out = bytearray()
    while len(out) < limit and time.monotonic() < deadline:
        chunk = resp.read(65_536)
        if not chunk:
            break
        got = d.decompress(chunk, limit - len(out)) if d else chunk[: limit - len(out)]
        out += got
        if stop and stop in out[-len(got) - len(stop):].lower():
            break
    return bytes(out)


def _get(url, accept, limit, deadline, resolve, allow_private=False):
    """(final url, content type, body) - redirects followed (each one checked again)."""
    for _ in range(MAX_REDIRECTS + 1):
        if time.monotonic() > deadline:
            raise Blocked("slow")
        scheme, host, port, path = _target(url)
        ip = host if allow_private else resolve(host, port)
        conn = (_PinnedTLS if scheme == "https" else _Pinned)(host, port, ip)
        try:
            conn.request("GET", path, headers={"User-Agent": AGENT, "Accept": accept, "Accept-Encoding": "gzip, deflate",
                                               "Accept-Language": "de,en;q=0.8"})
            resp = conn.getresponse()
            if resp.status in (301, 302, 303, 307, 308) and resp.getheader("Location"):
                url = urljoin(url, resp.getheader("Location").strip())
                continue
            if resp.status != 200:
                raise Blocked(f"status {resp.status}")
            ctype = (resp.getheader("Content-Type") or "").lower()
            return url, ctype, _read(resp, limit, deadline, b"</head>" if "html" in ctype else None)
        finally:
            conn.close()
    raise Blocked("redirects")


class _Meta(HTMLParser):
    """The tags a preview needs, from a page's head."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.meta, self.title, self.icon = {}, "", None
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "meta":
            key = (a.get("property") or a.get("name") or a.get("itemprop") or "").strip().lower()
            if key and a.get("content", "").strip() and key not in self.meta:
                self.meta[key] = a["content"].strip()
        elif tag == "title" and not self.title:
            self._in_title = True
        elif tag == "link" and self.icon is None and a.get("href"):
            rel = a.get("rel", "").lower().split()
            if "apple-touch-icon" in rel or "apple-touch-icon-precomposed" in rel or "image_src" in rel:
                self.icon = a["href"].strip()

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title and len(self.title) < 500:
            self.title += data


def _charset(ctype, raw):
    m = re.search(r"charset=[\"']?([\w-]+)", ctype) or re.search(rb"<meta[^>]+charset=[\"']?([\w-]+)", raw[:4096], re.I)
    name = m.group(1) if m else "utf-8"
    name = name.decode("ascii", "ignore") if isinstance(name, bytes) else name
    try:
        "".encode(name)
    except LookupError:
        name = "utf-8"
    return name


def _clip(text, n):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text if len(text) <= n else text[: n - 1].rstrip() + "…"


def parse_page(html, base):
    """{title, description, site, image (an absolute url)} of a page's markup."""
    p = _Meta()
    try:
        p.feed(html)
        p.close()
    except Exception:          # broken markup: whatever was read so far
        pass
    m = p.meta
    image = m.get("og:image:secure_url") or m.get("og:image") or m.get("og:image:url") or m.get("twitter:image") \
        or m.get("twitter:image:src") or p.icon
    if image and not image.lower().startswith("data:"):
        image = urljoin(base, image)
    else:
        image = None
    return dict(title=_clip(m.get("og:title") or m.get("twitter:title") or p.title, TITLE),
                description=_clip(m.get("og:description") or m.get("twitter:description") or m.get("description"), DESCRIPTION),
                site=_clip(m.get("og:site_name") or m.get("application-name"), SITE),
                image=image)


def small_image(raw):
    """(base64 WebP, width, height) of a picture, at most IMAGE_SIDE and IMAGE_BYTES - or None."""
    from PIL import Image, ImageOps
    try:
        img = Image.open(io.BytesIO(raw))
        if img.width * img.height > 40_000_000 or min(img.width, img.height) < 16:
            return None
        img.seek(0)
        img = ImageOps.exif_transpose(img)
        clear = img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info)    # logos: keep it see-through
        img = img.convert("RGBA" if clear else "RGB")
        for side, quality in ((IMAGE_SIDE, 70), (IMAGE_SIDE, 52), (360, 50), (260, 45)):
            copy = img.copy()
            copy.thumbnail((side, side))
            out = io.BytesIO()
            copy.save(out, "WEBP", quality=quality, method=4)
            if out.tell() <= IMAGE_BYTES:
                return base64.b64encode(out.getvalue()).decode(), copy.width, copy.height
    except Exception:          # not a picture Pillow knows (SVG, broken, ...)
        return None
    return None


def fetch_preview(url, allow_private=False, resolve=_resolve):
    """The preview of a web page (or of a picture's address) - None if there's nothing to show."""
    deadline = time.monotonic() + DEADLINE
    try:
        final, ctype, raw = _get(url, "text/html,application/xhtml+xml;q=0.9,image/*;q=0.8,*/*;q=0.5", MAX_HTML,
                                 deadline, resolve, allow_private)
    except Exception:          # unreachable, refused, broken: no preview
        return None
    out = dict(url=url, title="", description="", site="")
    image_url = None
    if ctype.startswith("image/"):
        out["title"] = _clip(urlsplit(final).path.rsplit("/", 1)[-1], TITLE)
        image_raw = raw if len(raw) < MAX_HTML else None
        image_url = None if image_raw else final
    elif "html" in ctype or not ctype:
        page = parse_page(raw.decode(_charset(ctype, raw), "replace"), final)
        out.update(title=page["title"], description=page["description"], site=page["site"])
        image_url, image_raw = page["image"], None
    else:
        return None
    if image_url:
        try:
            _, itype, image_raw = _get(image_url, "image/*", MAX_IMAGE, deadline, resolve, allow_private)
            if not itype.startswith("image/"):
                image_raw = None
        except Exception:
            image_raw = None
    small = small_image(image_raw) if image_raw else None
    if small:
        out["image"], out["width"], out["height"] = small
    if not out["title"] and not out["description"] and not small:
        return None
    return out


def clean_link(data, text):
    """The preview a sender attached to a plain message, checked: its link must be in the text, sizes are
    capped, a damaged picture is dropped. None: nothing to keep."""
    if not isinstance(data, dict):
        return None
    url = data.get("url")
    if not isinstance(url, str) or len(url) > MAX_URL or url not in links_in(text):
        return None
    out = dict(url=url)
    for key, n in (("title", TITLE), ("description", DESCRIPTION), ("site", SITE)):
        if isinstance(data.get(key), str) and data[key].strip():
            out[key] = _clip(data[key], n)
    image = data.get("image")
    if isinstance(image, str) and 0 < len(image) <= MAX_IMAGE_B64:
        try:
            base64.b64decode(image, validate=True)
            out["image"] = image
            out["width"] = max(0, min(4096, int(data.get("width") or 0)))
            out["height"] = max(0, min(4096, int(data.get("height") or 0)))
        except (ValueError, TypeError):
            out.pop("image", None)
    if not any(k in out for k in ("title", "description", "image")):
        return None
    return out


class Previews:
    """Fetched previews, kept a while in memory; a player asks for a limited number a minute."""

    def __init__(self, fetch=None):
        self.fetch = fetch or fetch_preview
        self.cache = {}          # url -> (until, preview or None)
        self.asked = {}          # user id -> [times]

    def allowed(self, user_id, now=None):
        now = time.monotonic() if now is None else now
        recent = [t for t in self.asked.get(user_id, []) if now - t < 60]
        if len(recent) >= PER_MINUTE:
            self.asked[user_id] = recent
            return False
        self.asked[user_id] = recent + [now]
        if len(self.asked) > 5000:
            self.asked = {u: ts for u, ts in self.asked.items() if ts and now - ts[-1] < 60}
        return True

    async def get(self, url):
        now = time.monotonic()
        hit = self.cache.get(url)
        if hit and hit[0] > now:
            return hit[1]
        got = await asyncio.to_thread(self.fetch, url)
        if len(self.cache) >= CACHE_SIZE:
            self.cache = {k: v for k, v in self.cache.items() if v[0] > now}
            while len(self.cache) >= CACHE_SIZE:
                self.cache.pop(next(iter(self.cache)))
        self.cache[url] = (now + (CACHE_SECONDS if got else FAIL_SECONDS), got)
        return got


def register(app, authed, previews):
    from quart import g, jsonify, request

    @app.get("/api/link-preview")
    @authed
    async def link_preview():
        """?url=<a link from the message being typed>: {preview: {url, title, description, site, image (base64
        WebP), width, height} or null} - null too when the page has nothing to show or can't be reached."""
        url = request.args.get("url", "").strip()
        if not url or len(url) > MAX_URL or not links_in(url) or links_in(url)[0] != url \
                or not previews.allowed(g.user.id):
            return jsonify(preview=None)
        return jsonify(preview=await previews.get(url))
