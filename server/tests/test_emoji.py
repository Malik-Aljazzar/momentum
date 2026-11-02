"""Pixel emojis: as rule icons and burned into text overlays (server/emoji.py, pixelart/emoji.py)."""
from server import emoji

from .test_api import make_app, register, run


def test_emoji_detection_and_sprites():
    assert emoji.is_emoji("🔥") and emoji.is_emoji("❤️") and emoji.is_emoji("❤") and emoji.is_emoji("👍🏽")
    assert emoji.is_emoji("👨‍👩‍👧") and emoji.is_emoji("🇩🇪")
    assert not emoji.is_emoji("") and not emoji.is_emoji("star") and not emoji.is_emoji("🔥🔥") and not emoji.is_emoji("a")
    assert emoji.split("Lauf 🔥 jetzt👍🏽!") == [(False, "Lauf "), (True, "🔥"), (False, " jetzt"), (True, "👍🏽"), (False, "!")]
    assert emoji.split("nur text") == [(False, "nur text")]
    s = emoji.sprite("🔥")
    assert s.size == (18, 18) and s.getextrema()[3][1] == 255        # something opaque in it
    assert emoji.sprite("❤") is not None and emoji.sprite("x") is None


def test_rule_icon_may_be_an_emoji(tmp_path):
    async def go():
        app = make_app(tmp_path)
        async with app.test_app() as ta:
            c = ta.test_client()
            h = await register(c)
            rule = {"kind": "acc", "name": "Feuer", "icon": "🔥", "mode": "constant", "base": 10}
            st = (await (await c.post("/api/rules", headers=h, json=rule)).get_json())["state"]
            assert next(r for r in st["rules"] if r["name"] == "Feuer")["icon"] == "🔥"
            assert (await c.post("/api/rules", headers=h, json=dict(rule, icon="🔥🔥"))).status_code == 400
            assert (await c.post("/api/rules", headers=h, json=dict(rule, icon="nope"))).status_code == 400
            assert (await c.post("/api/rules", headers=h, json=dict(rule, icon="star"))).status_code == 200
    run(go())


def test_overlay_burns_pixel_emojis():
    from server.overlay import text_layer
    layer = text_layer({"text": "Go 🔥", "x": .5, "y": .5, "color": "white", "size": .1}, 400, 300)
    only_text = text_layer({"text": "Go", "x": .5, "y": .5, "color": "white", "size": .1}, 400, 300)
    # the emoji adds coloured (not white) pixels: orange / yellow flames
    colored = sum(1 for r, g, b, a in layer.getdata() if a > 200 and (max(r, g, b) - min(r, g, b)) > 60)
    colored_before = sum(1 for r, g, b, a in only_text.getdata() if a > 200 and (max(r, g, b) - min(r, g, b)) > 60)
    assert colored > colored_before + 100
