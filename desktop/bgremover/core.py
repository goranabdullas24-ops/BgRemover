"""
BgRemover Desktop — بەشی ناوەکی (بێ ڕووکار).
هەمان لۆجیکی وەشانی ئەندرۆید:
  • IS-Net 1024 بۆ لابردنی باکگراوند
  • MODNet بۆ «تەنها مرۆڤ» (لۆگۆ و شتی پشتەوە بە تەواوی لادەبات)
  • کەسی سەرەکی (فۆکس) ئەگەر چەند کەس هەبن
  • وردکردنەوەی لێوار + لابردنی هالۆ
  • ئەنجام بە قەبارە و کوالیتی تەواوی ئەسڵی
  • Real-ESRGAN (سروشتی) بۆ Upscale ×4
  • گەڕانی وێنە (ویکیپیدیا، Commons، Openverse، Google/Serper) و داگرتن بە قەبارەی ئەسڵی
"""
from __future__ import annotations

import io
import json
import os
import sys
import threading
import urllib.parse
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Callable, Optional

import numpy as np
import requests
from PIL import Image, ImageOps
from scipy import ndimage as nd

# پشتگیری HEIC (ئایفۆن) و AVIF
try:
    from pillow_heif import register_heif_opener
    register_heif_opener()
except Exception:  # noqa: BLE001
    pass
try:
    import pillow_avif  # noqa: F401
except Exception:  # noqa: BLE001
    pass

APP_NAME = "BgRemover"
VERSION = "1.1"

# ───────────────────────── شوێنی فایلەکان ─────────────────────────

def data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or os.path.join(Path.home(), ".local", "share")
    p = Path(base) / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p


def models_dir() -> Path:
    p = data_dir() / "models"
    p.mkdir(parents=True, exist_ok=True)
    return p


def default_save_dir() -> Path:
    pics = Path.home() / "Pictures"
    p = (pics if pics.exists() else Path.home()) / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p


ProgressFn = Callable[[str], None]


def _noop(_: str) -> None:
    pass


# ───────────────────────── تۆڕ ─────────────────────────

UA_MOBILE = ("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
UA_DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/128.0 Safari/537.36")
UA_WIKI = f"BgRemover-Desktop/{VERSION} (https://github.com/goranabdullas24-ops/BgRemover; desktop image app)"

_session = requests.Session()


def _ua_for(url: str) -> str:
    host = urllib.parse.urlparse(url).hostname or ""
    if host.endswith(("wikimedia.org", "wikipedia.org", "wikidata.org")):
        return UA_WIKI
    return UA_DESKTOP


def http_get(url: str, *, params=None, headers=None, timeout=45) -> bytes:
    h = {"User-Agent": _ua_for(url), "Accept": "*/*"}
    if headers:
        h.update(headers)
    r = _session.get(url, params=params, headers=h, timeout=timeout)
    if r.status_code >= 400:
        hint = {401: "ڕێگە نەدرا", 403: "ڕێگە نەدرا", 404: "نەدۆزرایەوە",
                429: "داواکاری زۆرە، کەمێک چاوەڕێ بکە"}.get(r.status_code,
                "سێرڤەر کێشەی هەیە" if r.status_code >= 500 else "هەڵەی تۆڕ")
        raise IOError(f"{hint} (HTTP {r.status_code})")
    return r.content


def download_file(url: str, dest: Path, progress: Callable[[int], None] = lambda p: None) -> Path:
    tmp = dest.with_suffix(dest.suffix + ".part")
    with _session.get(url, stream=True, timeout=60, headers={"User-Agent": UA_DESKTOP}) as r:
        r.raise_for_status()
        total = int(r.headers.get("content-length", 0))
        done = 0
        last = -1
        with open(tmp, "wb") as f:
            for chunk in r.iter_content(256 * 1024):
                f.write(chunk)
                done += len(chunk)
                if total:
                    p = done * 100 // total
                    if p != last:
                        last = p
                        progress(p)
    tmp.replace(dest)
    return dest


# ───────────────────────── گەڕانی وێنە ─────────────────────────

@dataclass
class ImageResult:
    full_url: str
    thumb_url: str
    title: str = ""
    page_url: str = ""
    width: int = 0
    height: int = 0


def _wikipedia(lang: str, q: str, page: int) -> list[ImageResult]:
    js = json.loads(http_get(f"https://{lang}.wikipedia.org/w/api.php", params={
        "action": "query", "format": "json", "generator": "search", "gsrsearch": q,
        "gsrlimit": 20, "gsroffset": (page - 1) * 20, "prop": "pageimages",
        "piprop": "original|thumbnail", "pithumbsize": 400}))
    pages = (js.get("query") or {}).get("pages") or {}
    items = []
    for p in pages.values():
        o = p.get("original") or {}
        full = o.get("source", "")
        if not full or full.lower().endswith(".svg"):
            continue
        thumb = (p.get("thumbnail") or {}).get("source") or full
        items.append((p.get("index", 999), ImageResult(full, thumb, p.get("title", ""), "",
                                                       o.get("width", 0), o.get("height", 0))))
    return [r for _, r in sorted(items, key=lambda t: t[0])]


def _commons(q: str, page: int) -> list[ImageResult]:
    js = json.loads(http_get("https://commons.wikimedia.org/w/api.php", params={
        "action": "query", "format": "json", "generator": "search",
        "gsrsearch": f"{q} filetype:bitmap", "gsrnamespace": 6, "gsrlimit": 50,
        "gsroffset": (page - 1) * 50, "prop": "imageinfo", "iiprop": "url|mime|size",
        "iiurlwidth": 400}))
    pages = (js.get("query") or {}).get("pages") or {}
    items = []
    for p in pages.values():
        info = (p.get("imageinfo") or [{}])[0]
        if info.get("mime") not in ("image/jpeg", "image/png", "image/webp"):
            continue
        full = info.get("url", "")
        items.append((p.get("index", 999), ImageResult(full, info.get("thumburl") or full,
                                                       p.get("title", ""), "",
                                                       info.get("width", 0), info.get("height", 0))))
    return [r for _, r in sorted(items, key=lambda t: t[0])]


def _openverse(q: str, page: int) -> list[ImageResult]:
    js = json.loads(http_get("https://api.openverse.org/v1/images/", params={
        "q": q, "page": page, "page_size": 20, "mature": "false"},
        headers={"User-Agent": UA_MOBILE}))
    out = []
    for o in js.get("results", []):
        full = o.get("url", "")
        if not full or full.lower().endswith(".svg"):
            continue
        out.append(ImageResult(full, o.get("thumbnail") or full, o.get("title", ""),
                               o.get("foreign_landing_url", ""), o.get("width") or 0, o.get("height") or 0))
    return out


def _serper(q: str, key: str, page: int) -> list[ImageResult]:
    r = _session.post("https://google.serper.dev/images", timeout=45,
                      headers={"X-API-KEY": key, "Content-Type": "application/json"},
                      data=json.dumps({"q": q, "num": 100, "page": page}))
    if r.status_code >= 400:
        raise IOError(f"Serper: HTTP {r.status_code}")
    out = []
    for o in r.json().get("images", []):
        full = o.get("imageUrl", "")
        if full:
            out.append(ImageResult(full, o.get("thumbnailUrl") or full, o.get("title", ""),
                                   o.get("link", ""), o.get("imageWidth") or 0, o.get("imageHeight") or 0))
    return out


def search(q: str, serper_key: str = "", page: int = 1) -> tuple[str, list[ImageResult]]:
    """دەگەڕێتەوە: (سەرچاوە، لیست). بە Serper → گۆگڵ؛ بەبێ کلیل → سەرچاوە بەخۆڕاییەکان."""
    if serper_key.strip():
        return "Google", _serper(q, serper_key.strip(), page)
    from concurrent.futures import ThreadPoolExecutor
    fns = []
    if page <= 3:
        fns += [lambda: _wikipedia("ckb", q, page), lambda: _wikipedia("en", q, page),
                lambda: _wikipedia("ar", q, page)]
    fns += [lambda: _commons(q, page), lambda: _openverse(q, page)]
    lists, errors = [], []
    with ThreadPoolExecutor(len(fns)) as ex:
        for fut in [ex.submit(f) for f in fns]:
            try:
                lists.append(fut.result())
            except Exception as e:  # noqa: BLE001
                errors.append(e)
    if not lists and errors:
        raise errors[0]
    seen, out = set(), []
    for i in range(max((len(l) for l in lists), default=0)):
        for l in lists:
            if i < len(l) and l[i].full_url not in seen:
                seen.add(l[i].full_url)
                out.append(l[i])
    return "free", out


# ───────────────────────── داگرتنی وێنە بە قەبارەی ئەسڵی ─────────────────────────

def proxied(url: str) -> str:
    return "https://wsrv.nl/?url=" + urllib.parse.quote(url, safe="") + "&q=100"


def sniff_ext(b: bytes) -> Optional[str]:
    if b[:2] == b"\xff\xd8":
        return "jpg"
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        return "webp"
    if b[:3] == b"GIF":
        return "gif"
    if b[4:12] in (b"ftypavif", b"ftypheic", b"ftypmif1"):
        return "avif"
    return None


def fetch_image_bytes(url: str, fallback: str = "", referer: str = "") -> tuple[bytes, bool]:
    """
    وێنە ئەسڵییەکە دادەبەزێنێت. هەوڵەکان: ڕاستەوخۆ → وێبگەڕی کۆمپیوتەر + Referer → پرۆکسی.
    تەنها لە کۆتاییدا وێنە بچووکەکە. دەگەڕێتەوە: (bytes، low_quality)
    """
    ref = referer or "{0.scheme}://{0.netloc}/".format(urllib.parse.urlparse(url))
    attempts = [
        lambda: http_get(url, headers={"Accept": "image/avif,image/webp,image/*,*/*;q=0.8"}),
        lambda: http_get(url, headers={"Referer": ref, "User-Agent": UA_DESKTOP}),
        lambda: http_get(proxied(url)),
    ]
    for a in attempts:
        try:
            b = a()
            if sniff_ext(b):
                return b, False
        except Exception:  # noqa: BLE001
            pass
    if fallback and fallback != url:
        b = http_get(fallback)
        if sniff_ext(b):
            return b, True
    raise IOError("ئەم وێنەیە دانابەزێت")


MAX_PIXELS = 24_000_000


def open_image(data: bytes | str | Path) -> Image.Image:
    im = Image.open(io.BytesIO(data) if isinstance(data, (bytes, bytearray)) else data)
    im = ImageOps.exif_transpose(im)
    if im.width * im.height > MAX_PIXELS:
        s = (MAX_PIXELS / (im.width * im.height)) ** 0.5
        im = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))), Image.LANCZOS)
    return im.convert("RGB")


# ───────────────────────── مۆدێلەکان ─────────────────────────

MODEL_BASE = "https://github.com/goranabdullas24-ops/BgRemover/raw/main/model/"
MODELS = {
    "isnet": ("isnet_w8.onnx", 40_000_000),
    "modnet": ("modnet.onnx", 25_000_000),
    "esrgan": ("esrgan_x4_natural.onnx", 4_000_000),
    "yolo": ("yolo11n_seg.onnx", 10_000_000),
}
_sessions: dict = {}
_locks = {k: threading.Lock() for k in MODELS}


def _bundled_model(name: str) -> Optional[Path]:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    p = base / "model" / name
    return p if p.exists() else None


def ensure_model(key: str, progress: ProgressFn = _noop) -> Path:
    fname, min_size = MODELS[key]
    b = _bundled_model(fname)
    if b:
        return b
    p = models_dir() / fname
    if p.exists() and p.stat().st_size > min_size:
        return p
    label = {"isnet": "IS-Net", "modnet": "MODNet (مرۆڤ)", "esrgan": "Upscale", "yolo": "جیاکردنەوەی کەسەکان"}[key]
    download_file(MODEL_BASE + fname, p, lambda pc: progress(f"داگرتنی مۆدێلی {label} (تەنها یەک جار): {pc}%"))
    if p.stat().st_size < min_size:
        p.unlink(missing_ok=True)
        raise IOError("فایلی مۆدێل تەواو دانەبەزی")
    return p


def _session_for(key: str, progress: ProgressFn):
    if key in _sessions:
        return _sessions[key]
    import onnxruntime as ort
    path = ensure_model(key, progress)
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.intra_op_num_threads = max(1, min(8, os.cpu_count() or 4))
    s = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    _sessions[key] = s
    return s


def release_models() -> None:
    _sessions.clear()


def _resize_mask(m: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    im = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8))
    return np.asarray(im.resize(size, Image.BILINEAR), dtype=np.float32) / 255.0


def isnet_mask(im: Image.Image, progress: ProgressFn = _noop) -> np.ndarray:
    with _locks["isnet"]:
        s = _session_for("isnet", progress)
        progress("لابردنی باکگراوند بە IS-Net...")
        w, h = im.size
        side = max(w, h)
        mean = tuple(int(v) for v in np.asarray(im.resize((16, 16))).reshape(-1, 3).mean(0))
        sq = Image.new("RGB", (side, side), mean)
        ox, oy = (side - w) // 2, (side - h) // 2
        sq.paste(im, (ox, oy))
        x = np.asarray(sq.resize((1024, 1024), Image.BILINEAR), dtype=np.float32)
        x = x / max(float(x.max()), 1.0) - 0.5
        p = s.run(None, {s.get_inputs()[0].name: x.transpose(2, 0, 1)[None]})[0][0, 0]
        p = (p - p.min()) / max(float(p.max() - p.min()), 1e-6)
        big = _resize_mask(p, (side, side))
        return big[oy:oy + h, ox:ox + w]


def modnet_mask(im: Image.Image, progress: ProgressFn = _noop, ref: int = 512) -> np.ndarray:
    with _locks["modnet"]:
        s = _session_for("modnet", progress)
        progress("جیاکردنەوەی کەسەکە (MODNet)...")
        w, h = im.size
        if max(w, h) < ref or min(w, h) > ref:
            if w >= h:
                rh, rw = ref, int(w / h * ref)
            else:
                rw, rh = ref, int(h / w * ref)
        else:
            rw, rh = w, h
        rw, rh = max(32, rw - rw % 32), max(32, rh - rh % 32)
        x = np.asarray(im.resize((rw, rh), Image.BILINEAR), dtype=np.float32) / 127.5 - 1.0
        m = s.run(None, {s.get_inputs()[0].name: x.transpose(2, 0, 1)[None]})[0][0, 0]
        return _resize_mask(m, (w, h))


def person_instances(im: Image.Image, progress: ProgressFn = _noop, conf: float = 0.25) -> list[tuple[np.ndarray, float, tuple]]:
    """
    YOLO11-seg: هەر کەسێک بە جیا (تەنانەت ئەگەر بە یەکەوە لکابن).
    دەگەڕێتەوە: [(ماسک 0..1 بە قەبارەی وێنە، متمانە، box)]
    """
    with _locks["yolo"]:
        s = _session_for("yolo", progress)
        progress("جیاکردنەوەی کەسەکان لە یەکتر...")
        w, h = im.size
        S = 640
        sc = S / max(w, h)
        nw, nh = round(w * sc), round(h * sc)
        canvas = Image.new("RGB", (S, S), (114, 114, 114))
        px, py = (S - nw) // 2, (S - nh) // 2
        canvas.paste(im.resize((nw, nh), Image.BILINEAR), (px, py))
        x = (np.asarray(canvas, np.float32) / 255.0).transpose(2, 0, 1)[None]
        out0, protos = s.run(None, {s.get_inputs()[0].name: x})
        pred = out0[0].T                       # 8400 × 116
        scores = pred[:, 4]                    # کلاسی 0 = مرۆڤ
        keep = scores > conf
        if not keep.any():
            return []
        pred, scores = pred[keep], scores[keep]
        boxes = pred[:, :4].copy()             # cx, cy, w, h
        xyxy = np.stack([boxes[:, 0] - boxes[:, 2] / 2, boxes[:, 1] - boxes[:, 3] / 2,
                         boxes[:, 0] + boxes[:, 2] / 2, boxes[:, 1] + boxes[:, 3] / 2], 1)
        order = np.argsort(-scores)
        chosen = []
        while order.size:
            i = order[0]
            chosen.append(i)
            xx1 = np.maximum(xyxy[i, 0], xyxy[order[1:], 0]); yy1 = np.maximum(xyxy[i, 1], xyxy[order[1:], 1])
            xx2 = np.minimum(xyxy[i, 2], xyxy[order[1:], 2]); yy2 = np.minimum(xyxy[i, 3], xyxy[order[1:], 3])
            inter = np.clip(xx2 - xx1, 0, None) * np.clip(yy2 - yy1, 0, None)
            area = lambda b: (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
            iou = inter / (area(xyxy[[i]]) + area(xyxy[order[1:]]) - inter + 1e-6)
            order = order[1:][iou < 0.5]
        P = protos[0].reshape(32, -1)          # 32 × 25600
        res = []
        for i in chosen[:12]:
            m = 1 / (1 + np.exp(-(pred[i, 84:] @ P)))
            m = m.reshape(160, 160)
            # بڕینی ماسک بە box
            b = xyxy[i] / 4.0
            # box کەمێک فراوان دەکرێت بۆ ئەوەی ماسکی کەسە پشتەوەکان لە شوێنی پێکەوەلکان نەبڕدرێت
            bw, bh = b[2] - b[0], b[3] - b[1]
            b = b + np.array([-0.15 * bw, -0.08 * bh, 0.15 * bw, 0.08 * bh])
            yy, xx = np.mgrid[:160, :160]
            m = m * ((xx >= b[0]) & (xx <= b[2]) & (yy >= b[1]) & (yy <= b[3]))
            # لابردنی padding و گەڕاندنەوە بۆ قەبارەی وێنە
            mi = Image.fromarray((m * 255).astype(np.uint8)).resize((S, S), Image.BILINEAR)
            mi = mi.crop((px, py, px + nw, py + nh)).resize((w, h), Image.BILINEAR)
            box = tuple(((xyxy[i] - [px, py, px, py]) / sc).tolist())
            res.append((np.asarray(mi, np.float32) / 255.0, float(scores[i]), box))
        return res


def main_person_gate(instances, rgb: np.ndarray) -> Optional[np.ndarray]:
    """
    کەسی سەرەکی هەڵدەبژێرێت (گەورەتر، ڕوونتر/فۆکس، نزیکتر لە ناوەڕاست، متمانەی زیاتر)
    و دەروازەیەک دەگەڕێنێتەوە کە کەسانی تر (تەنانەت ئەوانەی پێوەی لکاون) لادەبات.
    """
    if len(instances) < 2:
        return None
    h, w = rgb.shape[:2]
    gray = rgb.astype(np.float32).mean(2)
    lap = np.abs(nd.laplace(gray))
    best, best_s = None, -1.0
    for k, (m, c, _) in enumerate(instances):
        mm = m > 0.5
        if c < 0.35 or mm.sum() < 50:      # کەسی کەم-متمانە تەنها وەک «کەسی تر» بەکاردێت
            continue
        sharp = float(lap[nd.binary_erosion(mm, iterations=2)].mean()) if mm.sum() > 200 else 0.0
        ys, xs = np.nonzero(mm)
        dc = np.hypot(xs.mean() / w - 0.5, ys.mean() / h - 0.5)
        sc = np.sqrt(mm.sum()) * (sharp + 1.0) * (1.2 - dc) * c
        if sc > best_s:
            best, best_s = k, sc
    if best is None:
        return None
    main = instances[best][0]
    others = np.zeros_like(main)
    for k, (m, _, _) in enumerate(instances):
        if k != best:
            others = np.maximum(others, m)
    if (others > 0.5).sum() < 50:
        return None
    r = max(2, int(min(w, h) * 0.012))
    core = main > 0.85
    near = _box(nd.binary_dilation(core, iterations=r).astype(np.float32), r)   # بۆ قژ و لێوار
    gate = np.clip(near * 1.6, 0, 1)
    # ئەو شوێنانەی کەسێکی تر زیاتر هی خۆیەتی، بە تەواوی لادەبرێن
    gate = np.where((others > 0.25) & (others * 1.6 > main), 0.0, gate)
    return nd.gaussian_filter(gate.astype(np.float32), 1.0)


# ───────────────────────── پاککردنەوەی ماسک ─────────────────────────

def _box(a: np.ndarray, r: int) -> np.ndarray:
    return nd.uniform_filter(a, size=2 * r + 1, mode="nearest")


def keep_main(a: np.ndarray, frac: float = 0.08) -> np.ndarray:
    lab, n = nd.label(a > 0.5)
    if n <= 1:
        return a
    sizes = nd.sum(np.ones_like(a), lab, range(1, n + 1))
    keep = np.isin(lab, [i + 1 for i, s in enumerate(sizes) if s >= frac * sizes.max()])
    return a * nd.binary_dilation(keep, iterations=3)


def focus_select(a: np.ndarray, rgb: np.ndarray) -> np.ndarray:
    """ئەگەر چەند کەس/بابەت هەبن: ئەوەی ڕوونترە، گەورەترە و نزیکترە لە ناوەڕاست دەمێنێتەوە."""
    lab, n = nd.label(a > 0.5)
    if n <= 1:
        return a
    h, w = a.shape
    sizes = nd.sum(np.ones_like(a), lab, range(1, n + 1))
    big = sizes.max()
    cands = [i + 1 for i, s in enumerate(sizes) if s >= 0.15 * big]
    if len(cands) <= 1:
        return a
    gray = rgb.astype(np.float32).mean(2)
    lap = np.abs(nd.laplace(gray))
    best, best_score = None, -1.0
    for c in cands:
        m = lab == c
        inner = nd.binary_erosion(m, iterations=2)
        sharp = float(lap[inner].mean()) if inner.any() else 0.0
        ys, xs = np.nonzero(m)
        dc = np.hypot(xs.mean() / w - 0.5, ys.mean() / h - 0.5)
        score = np.sqrt(m.sum()) * (sharp + 1.0) * (1.2 - dc)
        if score > best_score:
            best, best_score = c, score
    r = max(4, int(min(w, h) * 0.03))
    gate = _box(nd.binary_dilation(lab == best, iterations=r).astype(np.float32), r)
    return a * np.clip(gate * 1.5, 0, 1)


def solidify(a: np.ndarray) -> np.ndarray:
    h, w = a.shape
    d = nd.distance_transform_edt(a > 0.08)
    deep = d > max(3.0, min(w, h) * 0.015)
    return np.where(deep & (a > 0.15), 1.0, a).astype(np.float32)


def _guided(I: np.ndarray, p: np.ndarray, r: int, eps: float) -> np.ndarray:
    mI, mP = _box(I, r), _box(p, r)
    cIP, cII = _box(I * p, r), _box(I * I, r)
    A = (cIP - mI * mP) / (cII - mI * mI + eps)
    B = mP - A * mI
    return np.clip(_box(A, r) * I + _box(B, r), 0, 1)


def refine(rgb: np.ndarray, raw: np.ndarray) -> np.ndarray:
    """وردکردنەوەی سووکی لێوار بۆ IS-Net/MODNet (تاڵی قژ دەپارێزرێت)."""
    h, w = raw.shape
    gray = (rgb.astype(np.float32) / 255.0) @ np.array([0.299, 0.587, 0.114], np.float32)
    r = max(2, round(min(w, h) / 160))
    a = _guided(gray, raw, max(1, r // 2), 1e-4)
    a = 0.6 * raw + 0.4 * a
    band = _box(raw, r * 3)
    a = np.where((raw > 0.985) & (a > 0.5), 1.0, a)
    a = np.where(band < 0.01, 0.0, a)
    return np.clip((a - 0.03) / 0.94, 0, 1).astype(np.float32)


# ───────────────────────── لابردنی باکگراوند ─────────────────────────

WORK_SIDE = 1600


@dataclass
class CutOptions:
    person_only: bool = True
    focus_only: bool = True
    engine: str = "isnet"        # isnet | removebg
    removebg_key: str = ""


def _work_copy(im: Image.Image) -> Image.Image:
    m = max(im.size)
    if m <= WORK_SIDE:
        return im
    s = WORK_SIDE / m
    return im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)


def compute_alpha(im: Image.Image, opts: CutOptions, progress: ProgressFn = _noop) -> np.ndarray:
    """ماسکی کۆتایی (0..1) بە قەبارەی کۆپی کارکردن."""
    work = _work_copy(im)
    rgb = np.asarray(work)
    a = isnet_mask(work, progress)
    used_person = False
    if opts.person_only:
        try:
            mn = modnet_mask(work, progress)
            if (mn > 0.5).mean() >= 0.01:
                # MODNet بڕیار دەدات چی مرۆڤە؛ IS-Net تەنها لە ناو ئەو ناوچەیەدا لێوار ورد دەکات
                a = np.maximum(mn, np.minimum(a, np.clip(mn * 1.3, 0, 1)))
                used_person = True
        except Exception:  # noqa: BLE001
            pass
    if opts.focus_only:
        gated = False
        try:
            inst = person_instances(work, progress)
            g = main_person_gate(inst, rgb)
            if g is not None:
                a = a * g
                gated = True
        except Exception:  # noqa: BLE001
            pass
        progress("پاککردنەوەی دەوروبەر و لێوارەکان...")
        if not gated:
            a = focus_select(a, rgb)
    else:
        progress("پاککردنەوەی دەوروبەر و لێوارەکان...")
    a = keep_main(a)
    a = solidify(a)
    return refine(rgb, a)


def apply_alpha(src: Image.Image, alpha: np.ndarray) -> Image.Image:
    """ماسکەکە دەخاتە سەر وێنە ئەسڵییەکە بە قەبارەی تەواو؛ ڕەنگەکان دەستکاری ناکرێن."""
    a = alpha if alpha.shape == (src.height, src.width) else _resize_mask(alpha, src.size)
    out = src.convert("RGB").copy()
    out.putalpha(Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)))
    return out


def remove_background(src: Image.Image, opts: CutOptions, progress: ProgressFn = _noop) -> tuple[Image.Image, str]:
    """دەگەڕێتەوە: (وێنەی RGBA، پەیام بۆ بەکارهێنەر یان '')"""
    note = ""
    if opts.engine == "removebg" and opts.removebg_key.strip():
        try:
            progress("لابردنی باکگراوند بە remove.bg...")
            buf = io.BytesIO()
            src.save(buf, "JPEG", quality=100)
            r = _session.post("https://api.remove.bg/v1.0/removebg", timeout=120,
                              headers={"X-Api-Key": opts.removebg_key.strip()},
                              files={"image_file": ("image.jpg", buf.getvalue(), "image/jpeg")},
                              data={"size": "auto", "format": "png"})
            if r.status_code >= 400:
                raise IOError(f"HTTP {r.status_code}")
            out = Image.open(io.BytesIO(r.content)).convert("RGBA")
            if out.size != src.size:
                out = apply_alpha(src, np.asarray(out.split()[-1], np.float32) / 255.0)
            return out, ""
        except Exception:  # noqa: BLE001
            note = "remove.bg سەرنەکەوت — بە IS-Net کرا"
    a = compute_alpha(src, opts, progress)
    progress(f"جێبەجێکردن لەسەر کوالیتی تەواو ({src.width}×{src.height})...")
    return apply_alpha(src, a), note


def with_background(rgba: Image.Image, color: Optional[tuple[int, int, int]]) -> Image.Image:
    if color is None:
        return rgba
    bg = Image.new("RGBA", rgba.size, color + (255,))
    bg.alpha_composite(rgba)
    return bg.convert("RGB")


# ───────────────────────── Upscale ─────────────────────────

AI_WEIGHT = 0.7


def upscale(src_rgb: Image.Image, max_side: int = 4096, progress: ProgressFn = _noop) -> Image.Image:
    """Real-ESRGAN ×4 (کەمترین نەرمکردنەوە) + تێکەڵکردنی ٧٠/٣٠ بۆ ئەنجامێکی سروشتی."""
    with _locks["esrgan"]:
        s = _session_for("esrgan", progress)
        im = src_rgb.convert("RGB")
        max_in = max(1, max_side // 4)
        budget = MAX_PIXELS // 16
        m, px = max(im.size), im.width * im.height
        if m > max_in * 2 or px > budget:
            sc = min(max_in * 2 / m, (budget / px) ** 0.5)
            im = im.resize((max(1, round(im.width * sc)), max(1, round(im.height * sc))), Image.LANCZOS)
        a = np.asarray(im, dtype=np.float32) / 255.0
        h, w, _ = a.shape
        out = np.zeros((h * 4, w * 4, 3), np.float32)
        tile, pad = 192, 12
        tiles = [(ty, tx) for ty in range(0, h, tile) for tx in range(0, w, tile)]
        name = s.get_inputs()[0].name
        for k, (ty, tx) in enumerate(tiles):
            progress(f"Upscale ×4 بە AI: {k * 100 // len(tiles)}%")
            x0, y0 = max(0, tx - pad), max(0, ty - pad)
            x1, y1 = min(w, tx + tile + pad), min(h, ty + tile + pad)
            r = s.run(None, {name: a[y0:y1, x0:x1].transpose(2, 0, 1)[None]})[0][0].transpose(1, 2, 0)
            cx0, cy0 = (tx - x0) * 4, (ty - y0) * 4
            cw, ch = (min(tx + tile, w) - tx) * 4, (min(ty + tile, h) - ty) * 4
            out[ty * 4:ty * 4 + ch, tx * 4:tx * 4 + cw] = r[cy0:cy0 + ch, cx0:cx0 + cw]
        progress("Upscale: سروشتیکردن...")
        base = np.asarray(im.resize((w * 4, h * 4), Image.BICUBIC), np.float32) / 255.0
        res = np.clip(AI_WEIGHT * np.clip(out, 0, 1) + (1 - AI_WEIGHT) * base, 0, 1)
        result = Image.fromarray((res * 255 + 0.5).astype(np.uint8))
        if max(result.size) > max_side:
            sc = max_side / max(result.size)
            result = result.resize((round(result.width * sc), round(result.height * sc)), Image.LANCZOS)
        return result


def upscale_cutout(original: Optional[Image.Image], cutout: Image.Image, progress: ProgressFn = _noop) -> Image.Image:
    """ڕەنگ لە وێنە ئەسڵییەکەوە (بۆ ئەوەی لێوار تاریک نەبێت)، ڕوونی لە ئەنجامەکەوە."""
    if original is not None and original.size == cutout.size:
        rgb = original.convert("RGB")
    else:
        rgb = with_background(cutout, (255, 255, 255))
    up = upscale(rgb, 4096, progress)
    alpha = np.asarray(cutout.split()[-1], np.float32) / 255.0
    progress("Upscale: جێبەجێکردنی ڕوونی...")
    return apply_alpha(up, alpha)


# ───────────────────────── ڕێکخستن ─────────────────────────

@dataclass
class Settings:
    serper_key: str = ""
    removebg_key: str = ""
    engine: str = "isnet"
    person_only: bool = True
    focus_only: bool = True
    save_dir: str = ""

    @staticmethod
    def path() -> Path:
        return data_dir() / "settings.json"

    @classmethod
    def load(cls) -> "Settings":
        try:
            d = json.loads(cls.path().read_text("utf-8"))
            return cls(**{k: v for k, v in d.items() if k in cls.__dataclass_fields__})
        except Exception:  # noqa: BLE001
            return cls()

    def save(self) -> None:
        self.path().write_text(json.dumps(asdict(self), ensure_ascii=False, indent=1), "utf-8")
