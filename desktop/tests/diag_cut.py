"""A/B: لابردنی باکگراوند بە/بێ پاراستنی جلوبەرگ لەسەر وێنەی ڕاستەقینە."""
import os, sys, io
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from PIL import Image, ImageDraw
from bgremover import core

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
queries = ["businessman portrait white background tie", "man suit white shirt tie portrait",
           "politician portrait suit tie", "official portrait suit white tie"]
seen, items = set(), []
for q in queries:
    try:
        _, res = core.search(q)
    except Exception as e:
        print("search fail", q, e); continue
    for r in res[:8]:
        k = r.full_url.split("?")[0]
        if k not in seen:
            seen.add(k); items.append(r)
print(len(items), "images")
rows = []
for i, r in enumerate(items[:18]):
    try:
        b, _ = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
        src = core.open_image(b)
        src.thumbnail((900, 900))
        cuts = []
        for flag in ("1", "0"):
            os.environ["SG_NO_HOLEFILL"] = flag
            cut, _ = core.remove_background(src, core.CutOptions())
            bg = Image.new("RGB", cut.size, (255, 80, 160))
            bg.paste(cut, (0, 0), cut)
            cuts.append(bg)
        H = 300
        tiles = [im.resize((max(1, im.width * H // im.height), H)) for im in [src.convert("RGB")] + cuts]
        row = Image.new("RGB", (sum(t.width for t in tiles) + 20, H), "white")
        x = 0
        for t in tiles:
            row.paste(t, (x, 0)); x += t.width + 10
        row.save(f"{out}/{i:02d}.jpg", quality=85)
        print("ok", i, r.full_url[:90])
    except Exception as e:
        print("fail", i, e)
