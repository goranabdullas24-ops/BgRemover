"""لابردنی باکگراوند لەسەر وێنەی ڕاستەقینە + ماسکەکانی ناوەڕاست بۆ شیکردنەوە."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import numpy as np
from PIL import Image
from bgremover import core

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
queries = os.environ.get("Q", "قوباد تاڵەبانی|Qubad Talabani|Qubad Talabany").split("|")
seen, items = set(), []
for q in queries:
    try:
        _, res = core.search(q)
    except Exception as e:
        print("search fail", q, e); continue
    for r in res[:10]:
        k = r.full_url.split("?")[0]
        if k not in seen:
            seen.add(k); items.append(r)
print(len(items), "images")

def g(m):  # mask → grey image
    return Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8)).convert("RGB")

for i, r in enumerate(items[:14]):
    try:
        b, _ = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
        src = core.open_image(b)
        work = core._work_copy(src)
        isn = core.isnet_mask(work)
        mn = core.modnet_mask(work)
        inst = core.person_instances(work)
        pm = core.main_person_mask(inst) if inst else np.zeros_like(mn)
        cut, _ = core.remove_background(src, core.CutOptions())
        bg = Image.new("RGB", cut.size, (255, 80, 160)); bg.paste(cut, (0, 0), cut)
        H = 360
        ims = [work.convert("RGB"), g(isn), g(mn), g(pm), bg]
        tiles = [im.resize((max(1, im.width * H // im.height), H)) for im in ims]
        row = Image.new("RGB", (sum(t.width for t in tiles) + 40, H), "white")
        x = 0
        for t in tiles:
            row.paste(t, (x, 0)); x += t.width + 10
        row.save(f"{out}/{i:02d}.jpg", quality=85)
        print("ok", i, r.full_url[:100])
    except Exception as e:
        print("fail", i, e)
# rerun
