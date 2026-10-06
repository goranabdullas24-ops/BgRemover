"""وێنەی مرۆڤی ڕاستەقینە: لێواری کۆن بەراورد بە نوێ (قژ، چوارچێوە، هالۆ) لەسەر باکگراوندی تاریک و ڕەنگاوڕەنگ."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from PIL import Image
from bgremover import core

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
queries = os.environ.get("Q", "woman long hair portrait|curly hair portrait|man portrait white background|"
                         "woman portrait outdoor|bride portrait|child portrait").split("|")
seen, items = set(), []
for q in queries:
    try:
        _, res = core.search(q)
    except Exception as e:
        print("search fail", q, e); continue
    n = 0
    for r in res:
        k = r.full_url.split("?")[0]
        if k not in seen and n < 3:
            seen.add(k); items.append(r); n += 1
print(len(items), "images")
for i, r in enumerate(items[:18]):
    try:
        b, _ = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
        src = core.open_image(b)
        src.thumbnail((1400, 1400))
        cuts = []
        for flag in ("1", "0"):
            os.environ["SG_OLD_EDGE"] = flag
            c, _ = core.remove_background(src, core.CutOptions())
            cuts.append(c)
        H = 520
        tiles = [src.convert("RGB")]
        for c in cuts:
            for col in ((18, 18, 28), (60, 170, 90)):
                bg = Image.new("RGB", c.size, col); bg.paste(c, (0, 0), c); tiles.append(bg)
        tiles = [t.resize((max(1, t.width * H // t.height), H), Image.LANCZOS) for t in tiles]
        row = Image.new("RGB", (sum(t.width for t in tiles) + 60, H), "white")
        x = 0
        for t in tiles:
            row.paste(t, (x, 0)); x += t.width + 12
        row.save(f"{out}/{i:02d}.jpg", quality=88)
        print("ok", i, r.full_url[:90])
    except Exception as e:
        print("fail", i, e)
