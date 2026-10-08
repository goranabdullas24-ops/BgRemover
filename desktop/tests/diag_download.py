"""Live test of the per-image ⬇ (download without background removal) button, through the real GUI."""
import os, sys, time, tempfile, traceback
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtWidgets import QApplication
from PySide6.QtTest import QTest
from PySide6.QtCore import Qt
app = QApplication(sys.argv)
from bgremover import app as A, core

w = A.MainWindow(); w.show()
for _ in range(20):
    app.processEvents(); time.sleep(0.05)
d = tempfile.mkdtemp(); w.settings.save_dir = d
key = os.environ.get("SERPER_KEY", "")
total = ok = 0
for q, k in [("Lionel Messi", ""), ("بافڵ تاڵەبانی", ""), ("white tiger", ""), ("Lionel Messi", key), ("cristiano ronaldo", key)]:
    if k is None or (k == "" and q == "cristiano ronaldo"):
        continue
    try:
        src, res = core.search(q, k)
    except Exception as e:
        print(f"SEARCH FAIL {q!r} key={bool(k)}: {e}"); continue
    print(f"== {q!r} src={src} n={len(res)}")
    w.results = res[:8]; w._populate_grid()
    for _ in range(10):
        app.processEvents(); time.sleep(0.05)
    before = sum(len(f) for _, _, f in os.walk(d))
    msgs = []
    w._on_dl_done_orig = w._on_dl_done
    for t in list(w.grid.items):
        w.download_one(t.r)
    t0 = time.time()
    while w.downloading and time.time() - t0 < 180:
        app.processEvents(); time.sleep(0.1)
    after = sum(len(f) for _, _, f in os.walk(d))
    n = len(w.grid.items)
    total += n; ok += after - before
    print(f"   downloaded {after - before}/{n}; last msg: {w.statusBar().currentMessage()}")
    for r in res[:8]:
        try:
            b, low = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
            print(f"   ok  {core.sniff_ext(b)} {len(b)} low={low} {r.full_url[:110]}")
        except Exception as e:
            print(f"   ERR {e} {r.full_url[:110]}")
for root, _, files in os.walk(d):
    for f in files: print("  file:", f)
print(f"RESULT {ok}/{total}")
