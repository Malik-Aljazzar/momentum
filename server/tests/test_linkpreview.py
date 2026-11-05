"""Link previews in chats (server/linkpreview.py): fetching a page safely, its tags, the endpoint, and the
preview a plain message carries (encrypted ones carry it inside the envelope)."""
import asyncio
import base64
import io
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

from .. import linkpreview as lp
from ..app import create_app
from ..blobs import MemoryStore
from . import e2e_crypto as ec
from .test_e2e import ids, keyed
from .test_social import user


def picture(w=1200, h=630, fmt="PNG"):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (200, 80, 40)).save(buf, fmt)
    return buf.getvalue()


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<title>  Fallback   title </title>
<meta property="og:title" content="Caf&eacute; &amp; Bar">
<meta property="og:description" content="The best
   coffee in town">
<meta property="og:site_name" content="Coffee Blog">
<meta property="og:image" content="/img/cover.png">
</head><body><p>hello</p></body></html>"""


def test_links_like_the_apps():
    assert lp.links_in("see www.example.com/a. and https://x.de/b?c=1, ok") == ["https://www.example.com/a", "https://x.de/b?c=1"]
    assert lp.links_in("www. or http://x alone") == []
    assert lp.links_in("") == []


def test_only_public_addresses():
    for ip in ("127.0.0.1", "10.1.2.3", "192.168.0.10", "172.17.0.2", "169.254.169.254", "100.64.0.1", "0.0.0.0",
               "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "224.0.0.1", "not an ip"):
        assert not lp.public_ip(ip), ip
    for ip in ("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946", "1.1.1.1"):
        assert lp.public_ip(ip), ip
    # names that point inside, odd ports and schemes are never fetched
    assert lp.fetch_preview("http://localhost/") is None
    assert lp.fetch_preview("http://127.0.0.1/") is None
    assert lp.fetch_preview("ftp://example.com/") is None
    assert lp.fetch_preview("http://example.com:22/") is None
    assert lp.fetch_preview("http://user:pw@example.com/") is None


def test_page_tags():
    page = lp.parse_page(PAGE, "https://blog.example.com/posts/1")
    assert page == dict(title="Café & Bar", description="The best coffee in town", site="Coffee Blog",
                        image="https://blog.example.com/img/cover.png")
    # without Open Graph: <title>, <meta name=description>, the touch icon
    bare = lp.parse_page('<title>Just a page</title><meta name="description" content="Words">'
                         '<link rel="apple-touch-icon" href="icon.png">', "https://x.de/a/")
    assert bare == dict(title="Just a page", description="Words", site="", image="https://x.de/a/icon.png")
    long = lp.parse_page(f'<meta property="og:title" content="{"x" * 500}">', "https://x.de")
    assert len(long["title"]) == lp.TITLE and long["title"].endswith("…")
    assert lp.parse_page("<html><head><meta", "https://x.de")["title"] == ""          # broken markup: no crash


def test_small_picture():
    b64, w, h = lp.small_image(picture())
    assert (w, h) == (480, 252) and len(base64.b64decode(b64)) <= lp.IMAGE_BYTES
    assert Image.open(io.BytesIO(base64.b64decode(b64))).format == "WEBP"
    # noise doesn't compress: it gets smaller until it fits
    import os
    noisy = io.BytesIO()
    Image.frombytes("RGB", (1000, 1000), os.urandom(3_000_000)).save(noisy, "PNG")
    got = lp.small_image(noisy.getvalue())
    assert got is None or len(base64.b64decode(got[0])) <= lp.IMAGE_BYTES
    assert lp.small_image(b"<svg/>") is None
    assert lp.small_image(picture(8, 8)) is None                  # a tracking pixel


class Site(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        routes = {
            "/page": (200, "text/html; charset=utf-8", PAGE.encode()),
            "/img/cover.png": (200, "image/png", picture()),
            "/photo.jpg": (200, "image/jpeg", picture(800, 800, "JPEG")),
            "/moved": (301, None, b""),
            "/inside": (302, None, b""),
            "/file.zip": (200, "application/zip", b"PK..."),
            "/latin": (200, "text/html", '<meta charset="iso-8859-1"><title>Grüße</title>'.encode("latin-1")),
        }
        status, ctype, body = routes.get(self.path, (404, "text/plain", b"no"))
        self.send_response(status)
        if self.path == "/moved":
            self.send_header("Location", "/page")
        if self.path == "/inside":
            self.send_header("Location", f"http://127.0.0.1:{self.server.server_port}/page")
        if ctype:
            self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def serve():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Site)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def test_fetching_a_page(monkeypatch):
    srv = serve()
    port = srv.server_port
    monkeypatch.setattr(lp, "PORTS", lp.PORTS | {port})
    base = f"http://127.0.0.1:{port}"
    try:
        got = lp.fetch_preview(f"{base}/moved", allow_private=True)          # redirects are followed
        assert got["url"] == f"{base}/moved" and got["title"] == "Café & Bar" and got["site"] == "Coffee Blog"
        assert (got["width"], got["height"]) == (480, 252) and base64.b64decode(got["image"])
        photo = lp.fetch_preview(f"{base}/photo.jpg", allow_private=True)    # a picture's own address
        assert photo["title"] == "photo.jpg" and (photo["width"], photo["height"]) == (480, 480)
        assert lp.fetch_preview(f"{base}/latin", allow_private=True)["title"] == "Grüße"
        assert lp.fetch_preview(f"{base}/file.zip", allow_private=True) is None
        assert lp.fetch_preview(f"{base}/missing", allow_private=True) is None
        # a public name whose redirect points inside: refused at the redirect (each hop is checked again)
        def resolve(host, p):
            return "127.0.0.1" if host == "pub.test" else lp._resolve(host, p)
        assert lp.fetch_preview(f"http://pub.test:{port}/page", resolve=resolve)["title"] == "Café & Bar"
        assert lp.fetch_preview(f"http://pub.test:{port}/inside", resolve=resolve) is None
        # the page's picture inside: the text comes, the picture doesn't
        monkeypatch.setattr(lp, "parse_page", lambda html, base_url: dict(
            title="T", description="", site="", image=f"http://127.0.0.1:{port}/img/cover.png"))
        no_pic = lp.fetch_preview(f"http://pub.test:{port}/page", resolve=resolve)
        assert no_pic["title"] == "T" and "image" not in no_pic
    finally:
        srv.shutdown()


def test_clean_link():
    text = "look www.example.com/x!"
    good = dict(url="https://www.example.com/x", title=" A  title ", description="d" * 900, site="S",
                image=base64.b64encode(b"webp").decode(), width=480, height=252, extra="dropped")
    out = lp.clean_link(good, text)
    assert out == dict(url="https://www.example.com/x", title="A title", description="d" * (lp.DESCRIPTION - 1) + "…",
                       site="S", image=good["image"], width=480, height=252)
    assert lp.clean_link({**good, "url": "https://evil.example/"}, text) is None        # not a link of the text
    assert "image" not in lp.clean_link({**good, "image": "not base64!"}, text)
    assert "image" not in lp.clean_link({**good, "image": "A" * (lp.MAX_IMAGE_B64 + 4)}, text)
    assert lp.clean_link(dict(url="https://www.example.com/x"), text) is None            # nothing to show
    assert lp.clean_link("nope", text) is None


def run(tmp_path, scenario, fetch, e2e=False):
    async def go():
        app = create_app(f"sqlite+aiosqlite:///{(tmp_path / 't.db').as_posix()}", blobs=MemoryStore(),
                         link_preview=fetch, e2e_default=e2e)
        async with app.test_app() as ta:
            await scenario(app, ta.test_client())
    asyncio.run(go())


def test_endpoint_and_plain_messages(tmp_path):
    asked = []

    def fetch(url):
        asked.append(url)
        return dict(url=url, title="Example", description="", site="") if "example.com" in url else None

    async def scenario(app, c):
        ann, ben = await user(c, "ann"), await user(c, "ben")
        got = await ann.req("get", "/api/link-preview?url=https://example.com/a")
        assert got == {"preview": {"url": "https://example.com/a", "title": "Example", "description": "", "site": ""}}
        await ann.req("get", "/api/link-preview?url=https://example.com/a")
        assert asked == ["https://example.com/a"]                    # kept a while
        assert (await ann.req("get", "/api/link-preview?url=https://nothing.test/"))["preview"] is None
        # only a link as the apps find it in a text
        from urllib.parse import quote
        for bad in ("", "notalink", "javascript:alert(1)", "https://example.com/a and more"):
            assert (await ann.req("get", f"/api/link-preview?url={quote(bad)}"))["preview"] is None
        assert len(asked) == 2
        # a player asks only so often a minute
        for i in range(lp.PER_MINUTE):
            assert (await ben.req("get", f"/api/link-preview?url=https://example.com/{i}"))["preview"]
        assert (await ben.req("get", "/api/link-preview?url=https://example.com/last"))["preview"] is None
        assert (await c.get("/api/link-preview?url=https://example.com/a")).status_code == 401

        # the preview goes with a plain message
        await ben.req("post", "/api/users/ann/follow")
        cid = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        pic = base64.b64encode(picture(40, 20, "WEBP")).decode()
        link = dict(url="https://example.com/a", title="Example", description="Words", site="Ex", image=pic, width=40, height=20)
        sent = (await ann.req("post", f"/api/conversations/{cid}/messages", json={"text": "look https://example.com/a", "link": link}))["message"]
        assert sent["link"] == link
        # a preview of a link that isn't in the text is dropped, the message still goes
        other = (await ann.req("post", f"/api/conversations/{cid}/messages",
                               json={"text": "no link here", "link": link}))["message"]
        assert other["link"] is None
        msgs = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"]
        assert [m["link"] for m in msgs] == [link, None]
    run(tmp_path, scenario, fetch)


def test_encrypted_messages_carry_it_inside(tmp_path):
    async def scenario(app, c):
        ann, ben = await keyed(c, "ann"), await keyed(c, "ben")
        await ben.req("post", "/api/users/ann/follow")
        cid = (await ann.req("post", "/api/conversations/direct", json={"username": "ben"}))["conversation"]["id"]
        keys = await ids(ann, cid)
        readers = {k["user_id"]: k["public"] for k in keys.values() if k["public"]}
        # the small picture is inside the body: a long envelope still fits
        link = dict(url="https://example.com/a", title="Example", image=base64.b64encode(b"x" * 48_000).decode())
        env = ec.encrypt(ann.key, cid, readers, {"t": "look https://example.com/a", "m": None, "l": link}, {"t": "look", "k": None})
        assert len(env["ct"]) > 64_000                # more than before link previews
        sent = (await ann.req("post", f"/api/conversations/{cid}/messages",
                              json={"e2e": env, "link": link}))["message"]
        assert sent["link"] is None and sent["text"] == ""         # a plain copy beside the envelope is ignored
        got = (await ben.req("get", f"/api/conversations/{cid}/messages"))["messages"][-1]
        assert ec.decrypt(got["e2e"], ben.key, cid, keys["ben"]["user_id"])["l"] == link
    run(tmp_path, scenario, lambda url: None, e2e=True)
