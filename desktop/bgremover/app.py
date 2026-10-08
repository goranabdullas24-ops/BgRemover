"""
SG search — ڕووکاری کوردی (ڕاست بۆ چەپ) بە PySide6.
وەشانی ٤: ڕووکاری نوێ و ئاسان:
  • پەڕەی سەرەکی: گەڕانی گەورە لە ناوەڕاست + کارتی «وێنەی خۆم» و «لکاندن»
  • ئەنجامەکان: تۆڕی وێنەی گەورە (بێ بڕین)، دوگمەکان تەنها کاتی ماوس لەسەر
  • هەر وێنەیەک (گەڕان یان هی خۆت) لە شوێنی خۆی باکگراوندی لادەبرێت
  • پەنجەرەی بینین: بەراوردی پێش/دوای بە سلایدەر، ڕەنگی باکگراوند، Upscale، پاشەکەوت
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import sys
import time
import traceback
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from PIL import Image, ImageOps
from PySide6.QtCore import (QObject, QRunnable, QRect, QRectF, QSize, Qt, QThreadPool, QTimer, Signal, QUrl,
                            QPoint, QMimeData, QEventLoop, QEvent)
from PySide6.QtGui import (QColor, QDesktopServices, QGuiApplication, QIcon, QImage, QKeySequence, QPainter,
                           QPainterPath, QPixmap, QShortcut, QBrush, QDrag, QCursor, QPen, QFont,
                           QLinearGradient)
from PySide6.QtWidgets import (QApplication, QCheckBox, QColorDialog, QDialog, QDialogButtonBox, QFileDialog,
                               QFrame, QGraphicsDropShadowEffect, QGroupBox, QHBoxLayout, QLabel, QLineEdit,
                               QMainWindow, QMenu, QProgressBar, QPushButton, QRadioButton, QScrollArea,
                               QSizePolicy, QStackedWidget, QStatusBar, QToolButton, QVBoxLayout, QWidget)

from . import core

# ───────────────────────── ڕەنگ و ستایل ─────────────────────────
APP_TITLE = "SG search"
BG = "#F7F8FC"
SURFACE = "#FFFFFF"
CARD2 = "#F1F3F9"
LINE = "#E3E6EF"
TEXT = "#1B1E2B"
MUTED = "#6A7086"
ACCENT = "#5B5BF0"       # شینی وەنەوشەیی (هاوڕەنگی لۆگۆ)
ACCENT_H = "#4A4AE0"
ACCENT2 = "#D94F9C"
OK = "#12A150"
ERR = "#E5484D"
PURPLE = ACCENT

STYLE = f"""
QWidget {{ font-size: 10.5pt; color: {TEXT}; }}
QMainWindow, QDialog, QWidget#root, QWidget#page {{ background: {BG}; }}
QScrollArea, QScrollArea > QWidget > QWidget {{ background: transparent; border: none; }}
QLabel {{ background: transparent; }}
QLabel#h1 {{ font-size: 30pt; font-weight: 800; color: {TEXT}; }}
QLabel#h2 {{ font-size: 13pt; font-weight: 700; color: {TEXT}; }}
QLabel#muted {{ color: {MUTED}; }}
QFrame#topbar {{ background: {SURFACE}; border-bottom: 1px solid {LINE}; }}
QFrame#card {{ background: {SURFACE}; border: 1px solid {LINE}; border-radius: 16px; }}
QFrame#action {{ background: {SURFACE}; border: 1px solid {LINE}; border-radius: 18px; }}
QFrame#action:hover {{ border: 1.5px solid {ACCENT}; background: #FBFBFF; }}
QLineEdit#search {{ background: {SURFACE}; border: 1.5px solid {LINE}; border-radius: 26px; padding: 12px 24px;
                   font-size: 13pt; color: {TEXT}; selection-background-color: {ACCENT}; selection-color: white; }}
QLineEdit#search:hover {{ border-color: #CDD2E1; }}
QLineEdit#search:focus {{ border: 2px solid {ACCENT}; }}
QLineEdit {{ background: {SURFACE}; border: 1px solid {LINE}; border-radius: 10px; padding: 8px 12px; color: {TEXT}; }}
QPushButton {{ background: {SURFACE}; border: 1px solid {LINE}; border-radius: 20px; padding: 9px 18px;
               color: {TEXT}; font-weight: 600; }}
QPushButton:hover {{ background: {CARD2}; border-color: #CDD2E1; }}
QPushButton:pressed {{ background: #E6E9F4; }}
QPushButton:disabled {{ color: #A9AEC0; background: #F6F7FA; border-color: #EEF0F5; }}
QPushButton#primary {{ background: {ACCENT}; color: white; border: none; }}
QPushButton#primary:hover {{ background: {ACCENT_H}; }}
QPushButton#primary:disabled {{ background: #B9B9F6; color: white; }}
QPushButton#ghost {{ background: transparent; border: none; color: {MUTED}; padding: 8px 10px; }}
QPushButton#ghost:hover {{ background: {CARD2}; color: {TEXT}; }}
QPushButton#chip {{ border-radius: 16px; padding: 7px 14px; font-weight: 600; }}
QPushButton#chip:checked {{ background: #ECECFE; border-color: {ACCENT}; color: {ACCENT}; }}
QPushButton#icon {{ border-radius: 22px; padding: 0; font-size: 14pt; }}
QPushButton#tile {{ background: rgba(255,255,255,240); border: none; border-radius: 15px; padding: 6px 12px;
                    font-weight: 700; font-size: 9.5pt; color: {TEXT}; }}
QPushButton#tile:hover {{ background: {ACCENT}; color: white; }}
QCheckBox, QRadioButton {{ spacing: 8px; }}
QGroupBox {{ border: 1px solid {LINE}; border-radius: 12px; margin-top: 16px; padding: 12px; font-weight: 700; }}
QGroupBox::title {{ subcontrol-origin: margin; subcontrol-position: top right; padding: 0 8px; }}
QMenu {{ background: {SURFACE}; border: 1px solid {LINE}; border-radius: 12px; padding: 6px; }}
QMenu::item {{ padding: 8px 20px; border-radius: 8px; }}
QMenu::item:selected {{ background: #ECECFE; color: {ACCENT}; }}
QToolTip {{ background: {TEXT}; color: white; border: none; padding: 6px 8px; border-radius: 6px; }}
QProgressBar {{ border: none; background: #E8E9FB; border-radius: 3px; }}
QProgressBar::chunk {{ background: {ACCENT}; border-radius: 3px; }}
QStatusBar {{ background: {SURFACE}; color: {MUTED}; border-top: 1px solid {LINE}; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: #D5D9E6; border-radius: 4px; min-height: 40px; }}
QScrollBar::handle:vertical:hover {{ background: #B7BCD0; }}
QScrollBar::add-line, QScrollBar::sub-line, QScrollBar::add-page, QScrollBar::sub-page {{ background: none; height: 0; }}
"""

BG_CHOICES: list[Optional[tuple[int, int, int]]] = [None, (255, 255, 255), (0, 0, 0), (91, 91, 240),
                                                     (229, 72, 77), (18, 161, 80), (245, 245, 245)]


# ───────────────────────── یارمەتیدەرەکان ─────────────────────────

def pil_to_qimage(im: Image.Image) -> QImage:
    im = im.convert("RGBA")
    data = im.tobytes("raw", "RGBA")
    return QImage(data, im.width, im.height, im.width * 4, QImage.Format_RGBA8888).copy()


def thumb(im: Image.Image, side: int) -> Image.Image:
    t = im.copy()
    t.thumbnail((side, side), Image.LANCZOS)
    return t


def dims(w, h) -> str:
    """قەبارە بە ڕیزبەندی دروست لەناو نووسینی کوردی (RTL)."""
    return f"{w}x{h}"    # پیتی x (نەک ×) بۆ ئەوەی لە نووسینی RTL هەڵنەگەڕێتەوە


def urllib_unquote(s: str) -> str:
    import urllib.parse
    return urllib.parse.unquote(s)


def unique(p: Path) -> Path:
    if not p.exists():
        return p
    i = 1
    while True:
        q = p.with_name(f"{p.stem} ({i}){p.suffix}")
        if not q.exists():
            return q
        i += 1


def reveal_in_folder(p: Path):
    try:
        if sys.platform == "win32":
            import subprocess
            subprocess.Popen(["explorer", "/select,", str(p)])
        else:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(p.parent)))
    except Exception:  # noqa: BLE001
        pass


def drag_dir() -> Path:
    p = core.data_dir() / "drag"
    p.mkdir(parents=True, exist_ok=True)
    return p


def btn(text: str, kind: str = "", cb=None, tip: str = "") -> QPushButton:
    b = QPushButton(text)
    if kind == "primary" or kind is True:
        b.setObjectName("primary")
    elif kind:
        b.setObjectName(kind)
    b.setCursor(Qt.PointingHandCursor)
    if tip:
        b.setToolTip(tip)
    if cb:
        b.clicked.connect(cb)
    return b


def shadow(w: QWidget, blur=24, y=4, alpha=28):
    e = QGraphicsDropShadowEffect(w)
    e.setBlurRadius(blur)
    e.setOffset(0, y)
    e.setColor(QColor(20, 24, 60, alpha))
    w.setGraphicsEffect(e)


def is_local(url: str) -> bool:
    return url.startswith("file:")


def local_path(url: str) -> Path:
    return Path(QUrl(url).toLocalFile())


def fetch_bytes(r: core.ImageResult) -> tuple[bytes, bool]:
    if is_local(r.full_url):
        return local_path(r.full_url).read_bytes(), False
    return core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)


def result_name(r: core.ImageResult) -> str:
    if is_local(r.full_url):
        return core.safe_filename(local_path(r.full_url).stem)
    return core.safe_filename(Path(urllib_unquote(r.full_url.split("?")[0])).stem or r.title or "image")


class Signals(QObject):
    done = Signal(object)
    error = Signal(str)
    progress = Signal(str)


class Task(QRunnable):
    def __init__(self, fn: Callable, *args):
        super().__init__()
        self.fn, self.args, self.s = fn, args, Signals()

    def run(self):
        try:
            res = self.fn(*self.args, self.s.progress.emit)
            self.s.done.emit(res)
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            self.s.error.emit(str(e) or e.__class__.__name__)


def start_file_drag(widget: QWidget, path: Optional[Path], preview: Optional[QImage] = None):
    """فایلەکە ڕادەکێشرێتە ناو بەرنامەیەکی تر (Photoshop، Premiere، Word، Explorer...)."""
    if path is None or not Path(path).exists():
        return
    md = QMimeData()
    md.setUrls([QUrl.fromLocalFile(str(path))])
    d = QDrag(widget)
    d.setMimeData(md)
    if preview is not None and not preview.isNull():
        pm = QPixmap.fromImage(preview.scaled(140, 140, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        d.setPixmap(pm)
        d.setHotSpot(QPoint(pm.width() // 2, pm.height() // 2))
    d.exec(Qt.CopyAction)


def paint_checker(p: QPainter, r: QRect, cell: int = 12):
    p.save()
    p.setClipRect(r)
    p.fillRect(r, QColor("#FFFFFF"))
    c2 = QColor("#ECEEF4")
    y = r.top()
    while y < r.bottom():
        x = r.left()
        while x < r.right():
            if ((x - r.left()) // cell + (y - r.top()) // cell) % 2:
                p.fillRect(x, y, cell, cell, c2)
            x += cell
        y += cell
    p.restore()


# ───────────────────────── دۆخی هەر وێنەیەک ─────────────────────────

@dataclass
class TileState:
    status: str = ""          # "" | WAITING | WORKING | DONE | ERROR
    msg: str = ""
    file: str = ""            # وێنەی بێ باکگراوند (PNG)
    orig: str = ""            # وێنە ئەسڵییەکە
    size: tuple = (0, 0)
    enhanced: bool = False
    thumb: Optional[QImage] = None


# ───────────────────────── خانەی وێنە ─────────────────────────

class Tile(QFrame):
    clicked = Signal(object, bool)
    remove = Signal(object)
    download = Signal(object)
    upscale = Signal(object)
    save = Signal(object)
    open_editor = Signal(object)
    undo = Signal(object)
    context = Signal(object, QPoint)

    SIZE = 300
    RADIUS = 14
    drag_request = None  # Callable[[ImageResult], Optional[Path]]

    def __init__(self, r: core.ImageResult, state: TileState):
        super().__init__()
        self.r = r
        self.state = state
        self.pm: Optional[QPixmap] = None
        self.hover = False
        self.sel = False
        self.select_mode = False
        self._press: Optional[QPoint] = None
        self._dragged = False
        self.setCursor(Qt.PointingHandCursor)
        self.setAttribute(Qt.WA_Hover, True)
        self.resize(self.SIZE, self.SIZE)

        def mk(text, tip, sig):
            b = btn(text, "tile", tip=tip)
            b.setParent(self)
            b.clicked.connect(lambda: sig.emit(self.r))
            b.hide()
            return b
        self.b_rm = mk("✨ لابردن", "لابردنی باکگراوند لێرە", self.remove)
        self.b_dl = mk("⬇", "داگرتنی وێنەی ئەسڵی بە قەبارەی تەواو", self.download)
        self.b_open = mk("⤢", "کردنەوە (بەراورد، ڕەنگ، Upscale، پاشەکەوت)", self.open_editor)
        self.b_save = mk("💾 پاشەکەوت", "پاشەکەوتی وێنەی بێ باکگراوند", self.save)
        self.b_undo = mk("↺", "گەڕانەوە بۆ وێنە ئەسڵییەکە", self.undo)

    # ── ڕێکخستنی دوگمەکان ──
    def _buttons(self) -> list[QPushButton]:
        if self.state.status == "DONE":
            return [self.b_save, self.b_open, self.b_undo]
        return [self.b_rm, self.b_dl, self.b_open]

    def _layout_buttons(self):
        for b in (self.b_rm, self.b_dl, self.b_open, self.b_save, self.b_undo):
            b.hide()
        if self.select_mode or self.state.status in ("WAITING", "WORKING") or not self.hover:
            return
        bs = self._buttons()
        x = self.width() - 10
        for b in bs:                       # ڕاست بۆ چەپ
            b.adjustSize()
            b.setFixedHeight(30)
            w = max(34, b.sizeHint().width())
            x -= w
            b.setGeometry(x, self.height() - 40, w, 30)
            b.show()
            x -= 6

    def refresh(self):
        self._layout_buttons()
        self.setToolTip(self.r.title or "")
        self.update()

    def update_sel(self):
        self.refresh()

    def set_pixmap(self, pm: QPixmap):
        self.pm = pm
        self.update()

    def set_loading_dl(self, loading: bool):
        self.b_dl.setText("…" if loading else "⬇")
        self.b_dl.setEnabled(not loading)

    def aspect(self) -> float:
        st = self.state
        if st.status == "DONE" and st.size[1]:
            return max(0.5, min(2.2, st.size[0] / st.size[1]))
        if self.r.width and self.r.height:
            return max(0.5, min(2.2, self.r.width / self.r.height))
        if self.pm is not None and not self.pm.isNull() and self.pm.height():
            return max(0.5, min(2.2, self.pm.width() / self.pm.height()))
        return 1.0

    def event(self, e):
        if e.type() == QEvent.HoverEnter:
            self.hover = True
            self.refresh()
        elif e.type() == QEvent.HoverLeave:
            self.hover = False
            self.refresh()
        return super().event(e)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self._layout_buttons()

    def paintEvent(self, _):
        W, H, R = self.width(), self.height(), self.RADIUS
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        path = QPainterPath()
        path.addRoundedRect(QRectF(0, 0, W, H), R, R)
        p.setClipPath(path)
        st = self.state
        if st.status == "DONE" and st.thumb is not None:
            paint_checker(p, QRect(0, 0, W, H), 12)
            sz = st.thumb.size().scaled(W, H, Qt.KeepAspectRatio)
            p.drawImage(QRect((W - sz.width()) // 2, (H - sz.height()) // 2, sz.width(), sz.height()), st.thumb)
        elif self.pm is not None and not self.pm.isNull():
            pm = self.pm.scaled(W, H, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
            p.drawPixmap(0, 0, pm, (pm.width() - W) // 2, (pm.height() - H) // 2, W, H)
        else:
            p.fillRect(0, 0, W, H, QColor("#EEF0F6"))
        if self.hover and st.status not in ("WAITING", "WORKING"):
            g = QLinearGradient(0, H - 90, 0, H)
            g.setColorAt(0, QColor(0, 0, 0, 0))
            g.setColorAt(1, QColor(10, 12, 30, 150))
            p.fillRect(0, H - 90, W, 90, g)
        f = QFont(self.font())
        f.setPointSizeF(8.5)
        f.setBold(True)
        p.setFont(f)
        # نیشانەکان لە سەرەوەی چەپ
        badge = ""
        if st.status == "DONE":
            badge = "✓ " + dims(st.size[0], st.size[1]) + ("  HD" if st.enhanced else "")
        elif self.hover and self.r.width and self.r.height:
            badge = dims(self.r.width, self.r.height)
        if badge:
            fm = p.fontMetrics()
            tw = fm.horizontalAdvance(badge) + 16
            box = QRect(10, 10, tw, 22)
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(OK) if st.status == "DONE" else QColor(0, 0, 0, 140))
            p.drawRoundedRect(box, 11, 11)
            p.setPen(QColor("white"))
            p.drawText(box, Qt.AlignCenter, badge)
        if is_local(self.r.full_url) and st.status != "DONE":
            box = QRect(W - 70, 10, 60, 22)
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(ACCENT))
            p.drawRoundedRect(box, 11, 11)
            p.setPen(QColor("white"))
            p.drawText(box, Qt.AlignCenter, "هی خۆم")
        if st.status in ("WAITING", "WORKING", "ERROR"):
            p.fillRect(0, 0, W, H, QColor(255, 255, 255, 215))
            cy = H // 2 - 34
            if st.status == "WORKING":
                ang = int((time.time() * 360) % 360) * 16
                p.setPen(QPen(QColor("#E3E3FD"), 4))
                p.drawEllipse(QRect(W // 2 - 20, cy, 40, 40))
                pen = QPen(QColor(ACCENT), 4)
                pen.setCapStyle(Qt.RoundCap)
                p.setPen(pen)
                p.drawArc(QRect(W // 2 - 20, cy, 40, 40), -ang, 100 * 16)
            p.setPen(QColor(TEXT) if st.status != "ERROR" else QColor(ERR))
            f.setPointSizeF(9)
            f.setBold(False)
            p.setFont(f)
            txt = {"WAITING": "⏳ لە ڕیزدایە...", "WORKING": st.msg or "کار دەکات...",
                   "ERROR": "✗ " + (st.msg or "هەڵە")}[st.status]
            p.drawText(QRect(12, cy + 50, W - 24, 70), Qt.AlignHCenter | Qt.AlignTop | Qt.TextWordWrap, txt)
        p.setClipping(False)
        if self.sel:
            p.setPen(QPen(QColor(ACCENT), 4))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(QRectF(2, 2, W - 4, H - 4), R, R)
        if self.select_mode:
            cr = QRect(W - 38, H - 38, 28, 28)
            p.setPen(QPen(QColor("white"), 2))
            p.setBrush(QBrush(QColor(ACCENT)) if self.sel else QBrush(QColor(0, 0, 0, 90)))
            p.drawEllipse(cr)
            if self.sel:
                p.drawText(cr, Qt.AlignCenter, "✓")
        p.end()

    # ── ڕاکێشان بۆ دەرەوە ──
    def drag_path(self):
        if self.state.status == "DONE" and self.state.file:
            return Path(self.state.file)
        return Tile.drag_request(self.r) if Tile.drag_request else None

    def drag_preview(self):
        if self.state.status == "DONE" and self.state.thumb is not None:
            return self.state.thumb
        return self.pm.toImage() if self.pm is not None and not self.pm.isNull() else None

    def mousePressEvent(self, e):
        self._dragged = False
        self._press = e.position().toPoint() if e.button() == Qt.LeftButton else None

    def mouseMoveEvent(self, e):
        if self._press is None or not (e.buttons() & Qt.LeftButton):
            return
        if (e.position().toPoint() - self._press).manhattanLength() < QApplication.startDragDistance():
            return
        self._press = None
        self._dragged = True
        p = self.drag_path()
        if p is not None:
            start_file_drag(self, p, self.drag_preview())

    def mouseReleaseEvent(self, e):
        if self._dragged:
            self._dragged = False
            return
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self.r, bool(e.modifiers() & Qt.ControlModifier))

    def contextMenuEvent(self, e):
        self.context.emit(self.r, e.globalPos())


class JustifiedGrid(QWidget):
    """تۆڕی وێنە وەک گۆگڵ: هەر ڕیزێک پانی تەواو پڕ دەکات، وێنەکان بە ڕێژەی خۆیان (بێ بڕین)."""
    GAP = 12

    def __init__(self):
        super().__init__()
        self.items: list[Tile] = []
        self._w = 900
        self._h = Tile.SIZE

    def clear(self):
        for w in self.items:
            w.setParent(None)
            w.deleteLater()
        self.items = []
        self.setMinimumHeight(0)

    def add(self, w: Tile):
        w.setParent(self)
        w.show()
        self.items.append(w)
        self._place()

    def relayout(self, width: int, cell: int):
        self._w, self._h = max(240, width), cell
        self._place()

    def _place(self):
        W, H, G = self._w, self._h, self.GAP
        y = 0

        def flush(row, last=False):
            nonlocal y
            asp = [t.aspect() for t in row]
            total = sum(asp)
            h = (W - G * (len(row) - 1)) / total if total else H
            if last and h > H:
                h = H
            h = int(min(h, H * 1.5))
            x = W
            for t, a in zip(row, asp):
                tw = max(80, int(a * h))
                x -= tw
                t.setFixedSize(tw, h)
                t.move(max(0, x), y)
                t.refresh()
                x -= G
            y += h + G
        row, acc = [], 0.0
        for t in self.items:
            row.append(t)
            acc += t.aspect()
            if acc * H + G * (len(row) - 1) >= W:
                flush(row)
                row, acc = [], 0.0
        if row:
            flush(row, last=True)
        self.setMinimumHeight(y)


# ───────────────────────── بەراوردی پێش/دوای ─────────────────────────

class CompareView(QWidget):
    """وێنەی ئەسڵی و ئەنجام لەسەر یەک — سلایدەرێک ڕادەکێشیت بۆ بەراورد. لە دەرەوەی سلایدەر = ڕاکێشانی فایل."""

    def __init__(self):
        super().__init__()
        self.setMinimumSize(420, 360)
        self.orig: Optional[QImage] = None
        self.cut: Optional[QImage] = None
        self.bg: Optional[tuple[int, int, int]] = None
        self.split = 0.5
        self.busy_text = ""
        self.path_provider: Optional[Callable[[], Optional[Path]]] = None
        self._drag_slider = False
        self._press: Optional[QPoint] = None
        self.setMouseTracking(True)
        self.setCursor(Qt.SizeHorCursor)

    def set_images(self, orig: Optional[QImage], cut: Optional[QImage]):
        self.orig, self.cut = orig, cut
        self.update()

    def _fit(self) -> QRect:
        img = self.cut or self.orig
        if img is None or img.isNull():
            return self.rect().adjusted(20, 20, -20, -20)
        r = self.rect().adjusted(16, 16, -16, -16)
        sz = img.size().scaled(r.size(), Qt.KeepAspectRatio)
        return QRect(r.left() + (r.width() - sz.width()) // 2, r.top() + (r.height() - sz.height()) // 2,
                     sz.width(), sz.height())

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        fr = self._fit()
        if self.cut is None:
            if self.orig is not None:
                p.drawImage(fr, self.orig)
            if self.busy_text:
                p.fillRect(fr, QColor(255, 255, 255, 170))
                ang = int((time.time() * 360) % 360) * 16
                c = QRect(fr.center().x() - 24, fr.center().y() - 50, 48, 48)
                p.setPen(QPen(QColor("#E3E3FD"), 5))
                p.drawEllipse(c)
                pen = QPen(QColor(ACCENT), 5)
                pen.setCapStyle(Qt.RoundCap)
                p.setPen(pen)
                p.drawArc(c, -ang, 100 * 16)
                p.setPen(QColor(TEXT))
                f = QFont(self.font())
                f.setPointSizeF(11)
                p.setFont(f)
                p.drawText(QRect(fr.left(), c.bottom() + 12, fr.width(), 60),
                           Qt.AlignHCenter | Qt.AlignTop | Qt.TextWordWrap, self.busy_text)
            p.end()
            return
        sx = fr.left() + int(fr.width() * self.split)
        # لای ڕاست: ئەنجام (لەسەر باکگراوندی هەڵبژێردراو)
        right = QRect(sx, fr.top(), fr.right() - sx + 1, fr.height())
        p.save()
        p.setClipRect(right)
        if self.bg is None:
            paint_checker(p, fr, 14)
        else:
            p.fillRect(fr, QColor(*self.bg))
        p.drawImage(fr, self.cut)
        p.restore()
        # لای چەپ: ئەسڵی
        if self.orig is not None:
            p.save()
            p.setClipRect(QRect(fr.left(), fr.top(), sx - fr.left(), fr.height()))
            p.drawImage(fr, self.orig)
            p.restore()
        # هێڵ و دەسکی سلایدەر
        p.setPen(QPen(QColor("white"), 3))
        p.drawLine(sx, fr.top(), sx, fr.bottom())
        knob = QRect(sx - 18, fr.center().y() - 18, 36, 36)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(ACCENT))
        p.drawEllipse(knob)
        p.setPen(QPen(QColor("white"), 2))
        p.drawText(knob, Qt.AlignCenter, "⇆")
        # ناونیشانەکان
        f = QFont(self.font())
        f.setPointSizeF(9)
        f.setBold(True)
        p.setFont(f)
        for text, rect in (("پێش", QRect(fr.left() + 10, fr.top() + 10, 60, 24)),
                           ("دوای", QRect(fr.right() - 70, fr.top() + 10, 60, 24))):
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(0, 0, 0, 120))
            p.drawRoundedRect(rect, 12, 12)
            p.setPen(QColor("white"))
            p.drawText(rect, Qt.AlignCenter, text)
        p.end()

    def _set_split(self, x: int):
        fr = self._fit()
        if fr.width() > 0:
            self.split = max(0.0, min(1.0, (x - fr.left()) / fr.width()))
            self.update()

    def mousePressEvent(self, e):
        if e.button() != Qt.LeftButton:
            return
        x = e.position().toPoint().x()
        fr = self._fit()
        sx = fr.left() + int(fr.width() * self.split)
        self._press = e.position().toPoint()
        # نزیک لە سلایدەر یان کلیک = گۆڕینی سلایدەر؛ ئەگەرنا ڕاکێشانی فایل
        self._drag_slider = self.cut is not None and abs(x - sx) < 40
        if self._drag_slider:
            self._set_split(x)

    def mouseMoveEvent(self, e):
        if not (e.buttons() & Qt.LeftButton) or self._press is None:
            return
        if self._drag_slider:
            self._set_split(e.position().toPoint().x())
            return
        if (e.position().toPoint() - self._press).manhattanLength() >= QApplication.startDragDistance():
            self._press = None
            p = self.path_provider() if self.path_provider else None
            if p is not None:
                start_file_drag(self, p, self.cut or self.orig)

    def mouseReleaseEvent(self, e):
        if self._press is not None and not self._drag_slider and self.cut is not None:
            self._set_split(e.position().toPoint().x())
        self._press = None
        self._drag_slider = False


# ───────────────────────── پەنجەرەی بینین/دەستکاری ─────────────────────────

class Viewer(QDialog):
    """بینینی گەورە: بەراوردی پێش/دوای، ڕەنگی باکگراوند، Upscale، پاشەکەوت، کۆپی."""

    def __init__(self, win: "MainWindow", r: core.ImageResult):
        super().__init__(win)
        self.win, self.r = win, r
        self.setWindowTitle(f"{APP_TITLE} — {result_name(r)}")
        self.setLayoutDirection(Qt.RightToLeft)
        self.setModal(True)
        g = win.geometry()
        self.resize(int(g.width() * 0.88), int(g.height() * 0.9))
        self.cut_img: Optional[Image.Image] = None
        self.orig_img: Optional[Image.Image] = None
        self.upscaled: Optional[Image.Image] = None
        self.up_scale = 0
        self.bg: Optional[tuple[int, int, int]] = None
        self.busy = False

        v = QVBoxLayout(self)
        v.setContentsMargins(20, 16, 20, 16)
        v.setSpacing(12)
        head = QHBoxLayout()
        self.title = QLabel(result_name(r))
        self.title.setObjectName("h2")
        self.title.setAlignment(Qt.AlignRight | Qt.AlignAbsolute | Qt.AlignVCenter)
        self.info = QLabel("")
        self.info.setObjectName("muted")
        self.info.setAlignment(Qt.AlignRight | Qt.AlignAbsolute | Qt.AlignVCenter)
        tb = QVBoxLayout()
        tb.setSpacing(0)
        tb.addWidget(self.title)
        tb.addWidget(self.info)
        head.addLayout(tb, 1)
        head.addWidget(btn("✕", "icon", self.close, "داخستن (Esc)"))
        head.itemAt(head.count() - 1).widget().setFixedSize(44, 44)
        v.addLayout(head)

        body = QHBoxLayout()
        body.setSpacing(16)
        self.view = CompareView()
        self.view.path_provider = self._drag_path
        canvas = QFrame()
        canvas.setObjectName("card")
        cl = QVBoxLayout(canvas)
        cl.setContentsMargins(6, 6, 6, 6)
        cl.addWidget(self.view)
        body.addWidget(canvas, 1)

        side = QFrame()
        side.setObjectName("card")
        side.setFixedWidth(300)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(18, 18, 18, 18)
        sl.setSpacing(10)
        lab = QLabel("ڕەنگی باکگراوند")
        lab.setObjectName("h2")
        sl.addWidget(lab)
        self.chips = QHBoxLayout()
        self.chips.setSpacing(6)
        sl.addLayout(self.chips)
        sl.addSpacing(6)
        self.b_save = btn("💾  پاشەکەوت", "primary", self.save)
        self.b_save.setMinimumHeight(46)
        sl.addWidget(self.b_save)
        row = QHBoxLayout()
        self.b_copy = btn("📋 کۆپی", "", self.copy)
        self.b_folder = btn("📂 فۆڵدەر", "", lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(win.save_dir()))))
        row.addWidget(self.b_copy)
        row.addWidget(self.b_folder)
        sl.addLayout(row)
        sl.addSpacing(10)
        lab2 = QLabel("کوالیتی")
        lab2.setObjectName("h2")
        sl.addWidget(lab2)
        row2 = QHBoxLayout()
        self.b_up2 = btn("Upscale ×2", "", lambda: self.upscale(2), "گەورەکردن و ڕوونکردنەوە بە AI")
        self.b_up4 = btn("×4", "", lambda: self.upscale(4))
        row2.addWidget(self.b_up2, 2)
        row2.addWidget(self.b_up4, 1)
        sl.addLayout(row2)
        sl.addSpacing(10)
        lab3 = QLabel("زیاتر")
        lab3.setObjectName("h2")
        sl.addWidget(lab3)
        self.b_orig = btn("⬇  داگرتنی وێنەی ئەسڵی", "", self.save_original)
        self.b_redo = btn("↻  دووبارە لابردنەوە", "", self.redo)
        sl.addWidget(self.b_orig)
        sl.addWidget(self.b_redo)
        sl.addStretch(1)
        hint = QLabel("سلایدەرەکە ڕابکێشە بۆ بەراوردی پێش و دوای.\nبۆ بەکارهێنان لە بەرنامەیەکی تر، وێنەکە ڕابکێشە دەرەوە.")
        hint.setObjectName("muted")
        hint.setWordWrap(True)
        sl.addWidget(hint)
        self.prog = QProgressBar()
        self.prog.setRange(0, 0)
        self.prog.setFixedHeight(5)
        self.prog.setTextVisible(False)
        self.prog.hide()
        sl.addWidget(self.prog)
        body.addWidget(side)
        v.addLayout(body, 1)

        self._build_chips()
        self.anim = QTimer(self)
        self.anim.setInterval(40)
        self.anim.timeout.connect(self.view.update)
        win.tile_changed.connect(self._on_state)
        QShortcut(QKeySequence("Ctrl+S"), self, activated=self.save)
        QShortcut(QKeySequence("Ctrl+C"), self, activated=self.copy)
        self._load()

    # ── بارکردن ──
    def _state(self) -> TileState:
        return self.win.tstate.setdefault(self.r.full_url, TileState())

    def _load(self):
        st = self._state()
        if self.orig_img is None and st.orig and Path(st.orig).exists():
            try:
                self.orig_img = core.open_image(Path(st.orig).read_bytes())
            except Exception:  # noqa: BLE001
                pass
        if self.orig_img is None:
            pm = self.win.thumb_cache.get(self.r.thumb_url)
            if pm is not None:
                self.view.set_images(pm, None)
        if st.status == "DONE":
            self.cut_img = Image.open(st.file).convert("RGBA")
            self.upscaled, self.up_scale = None, 0
            self._show()
        else:
            if st.status in ("", "ERROR"):
                st.status = ""
                self.win.inplace(self.r)
            self._show()

    def _on_state(self, url: str):
        if url != self.r.full_url:
            return
        st = self._state()
        if st.status == "DONE" and self.cut_img is None:
            self._load()
        else:
            self._show()

    def _show(self):
        st = self._state()
        working = st.status in ("WAITING", "WORKING")
        cur = self.upscaled or self.cut_img
        orig_q = pil_to_qimage(thumb(self.orig_img, 1600)) if self.orig_img is not None else self.view.orig
        cut_q = pil_to_qimage(thumb(cur, 1600)) if cur is not None else None
        self.view.bg = self.bg
        self.view.busy_text = (st.msg or "لابردنی باکگراوند...") if working else ("✗ " + st.msg if st.status == "ERROR" else "")
        self.view.set_images(orig_q, cut_q)
        if working:
            self.anim.start()
        else:
            self.anim.stop()
        has = cur is not None
        for b in (self.b_save, self.b_copy, self.b_up2, self.b_up4):
            b.setEnabled(has and not self.busy)
        self.b_redo.setEnabled(not working and not self.busy)
        self.b_orig.setEnabled(st.orig != "" or not is_local(self.r.full_url))
        if cur is not None:
            extra = f"  ·  Upscale ×{self.up_scale}" if self.upscaled is not None else ("  ·  HD" if st.enhanced else "")
            self.info.setText(f"بێ باکگراوند  ·  {dims(cur.width, cur.height)} پیکسڵ{extra}")
        elif working:
            self.info.setText(st.msg or "کار دەکات...")
        else:
            self.info.setText("")

    def _build_chips(self):
        while self.chips.count():
            w = self.chips.takeAt(0).widget()
            if w:
                w.deleteLater()
        for c in BG_CHOICES[:6]:
            b = QToolButton()
            b.setFixedSize(34, 34)
            b.setCursor(Qt.PointingHandCursor)
            sel = c == self.bg
            border = f"3px solid {ACCENT}" if sel else f"1px solid {LINE}"
            if c is None:
                b.setText("▦")
                b.setToolTip("ڕوون (بێ باکگراوند)")
                b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{border};background:white;color:{MUTED};font-size:13pt;}}")
            else:
                b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{border};background:rgb{c};}}")
            b.clicked.connect(lambda _=False, cc=c: self.set_bg(cc))
            self.chips.addWidget(b)
        pick = QToolButton()
        pick.setFixedSize(34, 34)
        pick.setText("🎨")
        pick.setToolTip("ڕەنگی تر")
        pick.setCursor(Qt.PointingHandCursor)
        custom = self.bg is not None and self.bg not in BG_CHOICES
        pick.setStyleSheet(f"QToolButton{{border-radius:17px;border:{'3px solid ' + ACCENT if custom else '1px solid ' + LINE};"
                           f"background:{'rgb' + str(self.bg) if custom else 'white'};}}")
        pick.clicked.connect(self.pick_color)
        self.chips.addWidget(pick)
        self.chips.addStretch(1)

    def set_bg(self, c):
        self.bg = c
        self._build_chips()
        self._show()

    def pick_color(self):
        c = QColorDialog.getColor(QColor(*(self.bg or (255, 255, 255))), self, "ڕەنگی باکگراوند")
        if c.isValid():
            self.set_bg((c.red(), c.green(), c.blue()))

    def final(self) -> Optional[Image.Image]:
        cur = self.upscaled or self.cut_img
        if cur is None:
            return None
        return core.with_background(cur, self.bg)

    # ── کردارەکان ──
    def _name(self) -> str:
        n = result_name(self.r) + "_nobg"
        return n + (f"_x{self.up_scale}" if self.upscaled is not None else "")

    def save(self):
        img = self.final()
        if img is None:
            return
        default = self.win.save_dir() / f"{self._name()}.png"
        path, _ = QFileDialog.getSaveFileName(self, "پاشەکەوتکردن", str(default), "PNG (*.png);;JPEG (*.jpg);;WEBP (*.webp)")
        if not path:
            return
        ext = Path(path).suffix.lower()
        try:
            if ext in (".jpg", ".jpeg"):
                core.with_background(img, self.bg or (255, 255, 255)).convert("RGB").save(path, quality=100, subsampling=0)
            elif ext == ".webp":
                img.save(path, lossless=True)
            else:
                if ext != ".png":
                    path += ".png"
                img.save(path)
        except Exception as e:  # noqa: BLE001
            self.win.message(f"پاشەکەوت سەرنەکەوت: {e}", 10000)
            return
        self.win.toast(f"✔ پاشەکەوت کرا: {Path(path).name}")

    def copy(self):
        img = self.final()
        if img is not None:
            QGuiApplication.clipboard().setImage(pil_to_qimage(img))
            self.win.toast("✔ کۆپی کرا — لە هەر بەرنامەیەکدا Paste بکە")

    def _drag_path(self) -> Optional[Path]:
        img = self.final()
        if img is None:
            return None
        p = drag_dir() / f"{self._name()}.png"
        img.save(p)
        return p

    def save_original(self):
        st = self._state()
        if st.orig and Path(st.orig).exists():
            d = self.win.save_dir() / "Original"
            p = core.save_downloaded(Path(st.orig).read_bytes(), d, result_name(self.r))
            self.win.toast(f"✔ وێنەی ئەسڵی پاشەکەوت کرا: {p.name}")
            reveal_in_folder(p)
        else:
            self.win.download_one(self.r)

    def redo(self):
        st = self._state()
        if st.status in ("WAITING", "WORKING"):
            return
        self.cut_img, self.upscaled, self.up_scale = None, None, 0
        self.win.tstate[self.r.full_url] = TileState(orig=st.orig)
        for t in self.win._tiles_for(self.r.full_url):
            t.state = self.win.tstate[self.r.full_url]
        self.win.inplace(self.r, enhance=False)
        self._show()

    def upscale(self, scale: int):
        if self.cut_img is None or self.busy:
            return
        src = self.cut_img
        st = self._state()
        if self.orig_img is None and st.orig and Path(st.orig).exists():
            self.orig_img = core.open_image(Path(st.orig).read_bytes())
        self.busy = True
        self.prog.show()
        self._show()
        self.view.busy_text = ""

        def work(progress):
            return core.upscale_image(src, scale, progress)

        def done(up):
            self.busy = False
            self.prog.hide()
            self.upscaled, self.up_scale = up, scale
            self._show()
            self.win.toast(f"✔ Upscale ×{scale}: {dims(src.width, src.height)} → {dims(up.width, up.height)}")

        def err(m):
            self.busy = False
            self.prog.hide()
            self._show()
            self.win.message(f"Upscale سەرنەکەوت: {m}", 10000)
        t = Task(work)
        t.setAutoDelete(False)
        self._task = t
        t.s.progress.connect(lambda m: self.info.setText(m), Qt.QueuedConnection)
        t.s.done.connect(done, Qt.QueuedConnection)
        t.s.error.connect(err, Qt.QueuedConnection)
        QThreadPool.globalInstance().start(t)

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.close()
        else:
            super().keyPressEvent(e)

    def closeEvent(self, e):
        try:
            self.win.tile_changed.disconnect(self._on_state)
        except Exception:  # noqa: BLE001
            pass
        self.anim.stop()
        super().closeEvent(e)


# ───────────────────────── ڕێکخستن ─────────────────────────

class SettingsDialog(QDialog):
    def __init__(self, parent, s: core.Settings):
        super().__init__(parent)
        self.setWindowTitle("ڕێکخستن")
        self.setLayoutDirection(Qt.RightToLeft)
        self.s = s
        v = QVBoxLayout(self)
        v.setContentsMargins(20, 18, 20, 18)
        v.setSpacing(10)
        t = QLabel("ڕێکخستن")
        t.setObjectName("h2")
        v.addWidget(t)

        g0 = QGroupBox("لابردنی باکگراوند")
        g0v = QVBoxLayout(g0)
        self.person = QCheckBox("تەنها مرۆڤ — شتی زیادە (لۆگۆ، بانەر...) لادەبات")
        self.person.setChecked(s.person_only)
        self.focus = QCheckBox("تەنها کەسی سەرەکی — ئەگەر چەند کەس هەبن")
        self.focus.setChecked(s.focus_only)
        self.enh = QCheckBox("بەرزکردنەوەی کوالیتی لەگەڵ لابردن (×2 بە AI) — بە شێوەی بنەڕەت کوژاوەیە")
        self.enh.setChecked(s.enhance_after_cut)
        for w in (self.person, self.focus, self.enh):
            g0v.addWidget(w)
        self.r_is = QRadioButton("IS-Net + MODNet — وردترین، بەخۆڕایی، بێ ئینتەرنێت")
        self.r_rb = QRadioButton("remove.bg — پێویستی بە کلیل و ئینتەرنێتە")
        (self.r_rb if s.engine == "removebg" else self.r_is).setChecked(True)
        g0v.addWidget(self.r_is)
        g0v.addWidget(self.r_rb)
        self.rb_key = QLineEdit(s.removebg_key)
        self.rb_key.setPlaceholderText("remove.bg API Key")
        self.rb_key.setEchoMode(QLineEdit.Password)
        g0v.addWidget(self.rb_key)
        v.addWidget(g0)

        g2 = QGroupBox("گەڕانی گۆگڵ (ئارەزوومەندانە)")
        g2v = QVBoxLayout(g2)
        lab = QLabel("کلیلی بەخۆڕایی لە serper.dev — بەبێ کلیل لە ویکیپیدیا، Commons و Openverse دەگەڕێت.")
        lab.setObjectName("muted")
        lab.setWordWrap(True)
        g2v.addWidget(lab)
        self.serper = QLineEdit(s.serper_key)
        self.serper.setPlaceholderText("Serper API Key")
        self.serper.setEchoMode(QLineEdit.Password)
        g2v.addWidget(self.serper)
        v.addWidget(g2)

        g3 = QGroupBox("شوێنی پاشەکەوت")
        g3h = QHBoxLayout(g3)
        self.dir = QLineEdit(s.save_dir or str(core.default_save_dir()))
        g3h.addWidget(self.dir)
        g3h.addWidget(btn("گۆڕین...", "", self._pick_dir))
        v.addWidget(g3)

        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Save).setText("پاشەکەوت")
        bb.button(QDialogButtonBox.Save).setObjectName("primary")
        bb.button(QDialogButtonBox.Cancel).setText("داخستن")
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        v.addWidget(bb)
        self.resize(600, 0)

    def _pick_dir(self):
        d = QFileDialog.getExistingDirectory(self, "شوێنی پاشەکەوت", self.dir.text())
        if d:
            self.dir.setText(d)

    def result_settings(self) -> core.Settings:
        s = self.s
        s.person_only = self.person.isChecked()
        s.focus_only = self.focus.isChecked()
        s.enhance_after_cut = self.enh.isChecked()
        s.removebg_key = self.rb_key.text().strip()
        s.engine = "removebg" if self.r_rb.isChecked() and s.removebg_key else "isnet"
        s.serper_key = self.serper.text().strip()
        s.save_dir = self.dir.text().strip()
        return s


# ───────────────────────── پەنجەرەی سەرەکی ─────────────────────────

class ActionCard(QFrame):
    def __init__(self, icon: str, title: str, sub: str, cb):
        super().__init__()
        self.setObjectName("action")
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(240, 128)
        self.cb = cb
        v = QVBoxLayout(self)
        v.setContentsMargins(18, 16, 18, 16)
        v.setSpacing(4)
        i = QLabel(icon)
        i.setStyleSheet("font-size: 22pt;")
        t = QLabel(title)
        t.setObjectName("h2")
        s = QLabel(sub)
        s.setObjectName("muted")
        s.setWordWrap(True)
        v.addWidget(i)
        v.addWidget(t)
        v.addWidget(s)
        shadow(self, 22, 3, 18)

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.cb()


class Toast(QLabel):
    def __init__(self, parent):
        super().__init__(parent)
        self.setStyleSheet(f"background:{TEXT}; color:white; border-radius:18px; padding:10px 20px; font-weight:600;")
        self.hide()
        self.t = QTimer(self)
        self.t.setSingleShot(True)
        self.t.timeout.connect(self.hide)

    def show_text(self, text: str, ms=3500):
        self.setText(text)
        self.adjustSize()
        p = self.parentWidget()
        self.move((p.width() - self.width()) // 2, p.height() - self.height() - 46)
        self.raise_()
        self.show()
        self.t.start(ms)


class MainWindow(QMainWindow):
    thumb_ready = Signal(str, QImage)
    dl_done = Signal(str, bool, str)
    tile_changed = Signal(str)

    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_TITLE} — گەڕان و لابردنی باکگراوند")
        self.setLayoutDirection(Qt.RightToLeft)
        self.resize(1400, 900)
        self.settings = core.Settings.load()
        self.pool = QThreadPool.globalInstance()
        self.thumb_pool = ThreadPoolExecutor(8)
        self.dl_pool = ThreadPoolExecutor(3)
        self.inplace_pool = ThreadPoolExecutor(1)    # مۆدێلەکان قورسن → یەک بە یەک
        self.thumb_cache: dict[str, QImage] = {}
        self.results: list[core.ImageResult] = []
        self.mine: list[core.ImageResult] = []       # وێنەکانی خۆم (کردنەوە/لکاندن/ڕاکێشان)
        self.tstate: dict[str, TileState] = {}
        self.source = ""
        self.last_query = ""
        self.page = 1
        self.can_more = False
        self.selected: set[str] = set()
        self.select_mode = False
        self.busy = False
        self.downloading: set[str] = set()
        self.dl_count = 0
        self.dl_shown_folder = False
        self._tile_drag_cache: dict[str, Path] = {}
        self._tasks: set = set()
        Tile.drag_request = self._drag_tile_path
        self.spin = QTimer(self)
        self.spin.setInterval(40)
        self.spin.timeout.connect(self._spin_tick)

        self._build()
        self.thumb_ready.connect(self._on_thumb)
        self.dl_done.connect(self._on_dl_done)
        self.tile_changed.connect(self._on_tile_changed, Qt.QueuedConnection)
        self.setAcceptDrops(True)
        QShortcut(QKeySequence.Paste, self, activated=self.paste_image)
        QShortcut(QKeySequence("Ctrl+O"), self, activated=self.open_files)
        QShortcut(QKeySequence("Ctrl+F"), self, activated=lambda: self._focus_search())
        QTimer.singleShot(0, self.fresh_start)

    # ───── ڕووکار ─────
    def _build(self):
        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)
        rv = QVBoxLayout(root)
        rv.setContentsMargins(0, 0, 0, 0)
        rv.setSpacing(0)

        # شریتی سەرەوە (کاتێک ئەنجام هەیە)
        self.topbar = QFrame()
        self.topbar.setObjectName("topbar")
        tl = QHBoxLayout(self.topbar)
        tl.setContentsMargins(24, 12, 24, 12)
        tl.setSpacing(12)
        logo = QLabel()
        logo.setPixmap(make_icon().pixmap(40, 40))
        logo.setCursor(Qt.PointingHandCursor)
        logo.mouseReleaseEvent = lambda e: self.go_home()
        logo.setToolTip("گەڕانەوە بۆ سەرەتا")
        tl.addWidget(logo)
        name = QLabel(APP_TITLE)
        name.setStyleSheet("font-size: 15pt; font-weight: 800;")
        tl.addWidget(name)
        tl.addSpacing(16)
        self.q2 = QLineEdit()
        self.q2.setObjectName("search")
        self.q2.setPlaceholderText("🔍  گەڕان...")
        self.q2.setClearButtonEnabled(True)
        self.q2.setMaximumWidth(760)
        self.q2.setMinimumHeight(48)
        self.q2.returnPressed.connect(lambda: self.submit(self.q2.text()))
        tl.addWidget(self.q2, 1)
        b = btn("گەڕان", "primary", lambda: self.submit(self.q2.text()))
        b.setMinimumHeight(46)
        b.setFixedWidth(110)
        tl.addWidget(b)
        tl.addStretch(0)
        for text, tip, cb in (("🖼", "کردنەوەی وێنەی خۆم (Ctrl+O)", self.open_files),
                              ("📋", "لکاندنی وێنە (Ctrl+V)", self.paste_image),
                              ("⚙", "ڕێکخستن", self.open_settings)):
            ib = btn(text, "icon", cb, tip)
            ib.setFixedSize(44, 44)
            tl.addWidget(ib)
        rv.addWidget(self.topbar)
        self.topbar.hide()

        self.prog = QProgressBar()
        self.prog.setRange(0, 0)
        self.prog.setTextVisible(False)
        self.prog.setFixedHeight(3)
        self.prog.hide()
        rv.addWidget(self.prog)

        self.stack = QStackedWidget()
        rv.addWidget(self.stack, 1)

        # ── پەڕەی سەرەکی ──
        home = QWidget()
        home.setObjectName("page")
        hv = QVBoxLayout(home)
        hv.setContentsMargins(40, 30, 40, 30)
        hv.addStretch(2)
        lg = QLabel()
        lg.setPixmap(make_icon().pixmap(104, 104))
        lg.setAlignment(Qt.AlignCenter)
        hv.addWidget(lg)
        h1 = QLabel(APP_TITLE)
        h1.setObjectName("h1")
        h1.setAlignment(Qt.AlignCenter)
        hv.addWidget(h1)
        sub = QLabel("وێنە بدۆزەرەوە، باکگراوندەکەی لاببە، و بە کوالیتی تەواو دایبگرە")
        sub.setObjectName("muted")
        sub.setAlignment(Qt.AlignCenter)
        sub.setStyleSheet("font-size: 11.5pt;")
        hv.addWidget(sub)
        hv.addSpacing(22)
        srow = QHBoxLayout()
        srow.addStretch(1)
        self.q = QLineEdit()
        self.q.setObjectName("search")
        self.q.setPlaceholderText("🔍  ناوی کەس، شوێن یان هەر شتێک بنووسە... یان لینکی وێنە")
        self.q.setClearButtonEnabled(True)
        self.q.setMinimumHeight(58)
        self.q.setFixedWidth(680)
        self.q.returnPressed.connect(lambda: self.submit(self.q.text()))
        shadow(self.q, 30, 4, 22)
        srow.addWidget(self.q)
        self.b_search = btn("گەڕان", "primary", lambda: self.submit(self.q.text()))
        self.b_search.setMinimumHeight(56)
        self.b_search.setFixedWidth(120)
        srow.addWidget(self.b_search)
        srow.addStretch(1)
        hv.addLayout(srow)
        hv.addSpacing(30)
        crow = QHBoxLayout()
        crow.setSpacing(16)
        crow.addStretch(1)
        crow.addWidget(ActionCard("🖼", "وێنەی خۆم", "وێنەیەک یان چەند وێنەیەک لە کۆمپیوتەرەکەت بکەرەوە", self.open_files))
        crow.addWidget(ActionCard("📋", "لکاندن", "وێنەیەکی کۆپیکراو Paste بکە (Ctrl+V)", self.paste_image))
        crow.addWidget(ActionCard("⚙", "ڕێکخستن", "شێوازی لابردن، کلیلی گۆگڵ، شوێنی پاشەکەوت", self.open_settings))
        crow.addStretch(1)
        hv.addLayout(crow)
        hv.addSpacing(18)
        dnd = QLabel("یان وێنەیەک ڕابکێشە ناو ئەم پەنجەرەیە")
        dnd.setObjectName("muted")
        dnd.setAlignment(Qt.AlignCenter)
        hv.addWidget(dnd)
        hv.addStretch(3)
        self.stack.addWidget(home)

        # ── پەڕەی ئەنجامەکان ──
        resp = QWidget()
        resp.setObjectName("page")
        rl = QVBoxLayout(resp)
        rl.setContentsMargins(24, 14, 24, 0)
        rl.setSpacing(10)
        bar = QHBoxLayout()
        bar.setSpacing(8)
        self.res_head = QLabel("")
        self.res_head.setObjectName("h2")
        bar.addWidget(self.res_head)
        bar.addStretch(1)
        self.b_rm_all = btn("✨  لابردنی باکگراوندی هەموو", "chip", self.inplace_all)
        self.b_selmode = btn("☑  هەڵبژاردن", "chip", lambda: self.set_select_mode(not self.select_mode))
        self.b_selall = btn("هەمووی", "chip", self.select_all)
        self.b_save_done = btn("", "primary", self.save_all_inplace)
        for w in (self.b_selall, self.b_selmode, self.b_rm_all, self.b_save_done):
            bar.addWidget(w)
        rl.addLayout(bar)
        self.sel_bar = QFrame()
        self.sel_bar.setObjectName("card")
        sb = QHBoxLayout(self.sel_bar)
        sb.setContentsMargins(14, 8, 14, 8)
        self.sel_lbl = QLabel("")
        sb.addWidget(self.sel_lbl)
        sb.addStretch(1)
        self.b_sel_rm = btn("✨  لابردنی باکگراوند", "primary", self.batch_from_selection)
        self.b_sel_dl = btn("⬇  داگرتن بەبێ لابردن", "", self.download_selected)
        sb.addWidget(self.b_sel_dl)
        sb.addWidget(self.b_sel_rm)
        self.sel_bar.hide()
        rl.addWidget(self.sel_bar)

        self.grid_scroll = QScrollArea()
        self.grid_scroll.setWidgetResizable(True)
        self.grid_scroll.setFrameShape(QFrame.NoFrame)
        self.grid_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.grid_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOn)
        gw = QWidget()
        gv = QVBoxLayout(gw)
        gv.setContentsMargins(0, 4, 0, 24)
        gv.setSpacing(14)
        self.grid = JustifiedGrid()
        gv.addWidget(self.grid)
        self.b_more = btn("وێنەی زیاتر", "", self.load_more)
        self.b_more.setFixedWidth(220)
        mrow = QHBoxLayout()
        mrow.addStretch(1)
        mrow.addWidget(self.b_more)
        mrow.addStretch(1)
        gv.addLayout(mrow)
        self.src_lbl = QLabel("")
        self.src_lbl.setObjectName("muted")
        self.src_lbl.setAlignment(Qt.AlignCenter)
        self.src_lbl.setWordWrap(True)
        gv.addWidget(self.src_lbl)
        gv.addStretch(1)
        self.grid_scroll.setWidget(gw)
        rl.addWidget(self.grid_scroll, 1)
        self.stack.addWidget(resp)

        self.setStatusBar(QStatusBar())
        self.statusBar().setSizeGripEnabled(False)
        self.toast_w = Toast(root)

    def _focus_search(self):
        (self.q2 if self.stack.currentIndex() == 1 else self.q).setFocus()

    def go_home(self):
        self.stack.setCurrentIndex(0)
        self.topbar.hide()
        self.q.setText(self.q2.text())
        self.q.setFocus()

    def show_results_page(self):
        self.stack.setCurrentIndex(1)
        self.topbar.show()
        QTimer.singleShot(0, self._relayout)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        QTimer.singleShot(0, self._relayout)

    def _relayout(self):
        self.grid.relayout(self.grid_scroll.viewport().width() - 4, Tile.SIZE)

    def message(self, text: str, ms=7000):
        self.statusBar().showMessage(text, ms)

    def toast(self, text: str, ms=3500):
        self.message(text, ms + 2000)
        self.toast_w.show_text(text, ms)

    def set_busy(self, text: Optional[str]):
        self.busy = text is not None
        self.prog.setVisible(self.busy)
        if text:
            self.message(text, 60000)
        else:
            self.statusBar().clearMessage()
        self.b_search.setEnabled(not self.busy)

    def run(self, fn, on_done, *args, on_error=None, label="..."):
        t = Task(fn, *args)
        t.setAutoDelete(False)
        self._tasks.add(t)
        Q = Qt.QueuedConnection

        def finish():
            self._tasks.discard(t)
            self.set_busy(None)
        t.s.progress.connect(lambda m: self.message(m, 60000), Q)
        t.s.done.connect(lambda r: (finish(), on_done(r)), Q)
        t.s.error.connect(lambda e: (finish(), (on_error or (lambda m: self.message("هەڵە: " + m, 10000)))(e)), Q)
        self.set_busy(label)
        self.pool.start(t)

    # ───── گەڕان ─────
    def submit(self, q: str = None):
        q = (q if q is not None else self.q.text()).strip()
        if not q or self.busy:
            return
        self.q.setText(q)
        self.q2.setText(q)
        if q.lower().startswith(("http://", "https://")):
            r = core.ImageResult(q, q, Path(q.split("?")[0]).stem)
            self._add_mine(r)
            self.open_viewer(r)
        else:
            self.search(q)

    def search(self, q: str):
        def work(progress):
            return core.search(q, self.settings.serper_key, 1)

        def done(res):
            self.source, self.results = res
            self.last_query, self.page = q, 1
            self.can_more = bool(self.results)
            self.selected.clear()
            self.select_mode = False
            self._populate_grid()
            self.show_results_page()
            if not self.results and not self.mine:
                self.toast("هیچ وێنەیەک نەدۆزرایەوە، ناوێکی تر تاقی بکەرەوە")
        self.run(work, done, label=f"گەڕان بۆ «{q}»...")

    def load_more(self):
        if not self.last_query or self.busy:
            return

        def work(progress):
            return core.search(self.last_query, self.settings.serper_key, self.page + 1)

        def done(res):
            _, lst = res
            known = {r.full_url for r in self.results}
            fresh = [r for r in lst if r.full_url not in known]
            self.page += 1
            self.results += fresh
            self.can_more = bool(fresh)
            for r in fresh:
                self._add_tile(r)
            self._update_results_ui()
            if not fresh:
                self.toast("وێنەی زیاتر نییە")
        self.run(work, done, label="هێنانی وێنەی زیاتر...")

    def all_items(self) -> list[core.ImageResult]:
        return self.mine + self.results

    def _populate_grid(self):
        self.grid.clear()
        for r in self.all_items():
            self._add_tile(r)
        self._update_results_ui()
        self.grid_scroll.verticalScrollBar().setValue(0)

    def _add_tile(self, r: core.ImageResult):
        st = self.tstate.setdefault(r.full_url, TileState())
        t = Tile(r, st)
        t.select_mode = self.select_mode
        t.sel = r.full_url in self.selected
        t.clicked.connect(self._tile_clicked)
        t.remove.connect(self.inplace)
        t.download.connect(self.download_one)
        t.upscale.connect(self.open_viewer)
        t.save.connect(self.save_inplace)
        t.open_editor.connect(self.open_viewer)
        t.undo.connect(self.undo_inplace)
        t.context.connect(self._tile_menu)
        self.grid.add(t)
        if r.thumb_url in self.thumb_cache:
            t.set_pixmap(QPixmap.fromImage(self.thumb_cache[r.thumb_url]))
        else:
            self.thumb_pool.submit(self._load_thumb, r.thumb_url)

    def _load_thumb(self, url: str):
        try:
            if is_local(url):
                im = Image.open(local_path(url))
                im = ImageOps.exif_transpose(im)
            else:
                b = core.http_get(url, timeout=30)
                im = Image.open(io.BytesIO(b))
            im.thumbnail((640, 640))
            self.thumb_ready.emit(url, pil_to_qimage(im))
        except Exception:  # noqa: BLE001
            pass

    def _on_thumb(self, url: str, img: QImage):
        self.thumb_cache[url] = img
        pm = QPixmap.fromImage(img)
        relayout = False
        for t in self.grid.items:
            if t.r.thumb_url == url:
                t.set_pixmap(pm)
                if not (t.r.width and t.r.height):
                    relayout = True
        if relayout:
            self.grid._place()

    def _tile_clicked(self, r, ctrl):
        if self.select_mode or ctrl:
            if not self.select_mode:
                self.set_select_mode(True)
            self.toggle_select(r)
        else:
            self.open_viewer(r)

    def _tile_menu(self, r, pos):
        m = QMenu(self)
        m.setLayoutDirection(Qt.RightToLeft)
        st = self.tstate.get(r.full_url, TileState())
        m.addAction("⤢  کردنەوە", lambda: self.open_viewer(r))
        if st.status == "DONE":
            m.addAction("💾  پاشەکەوتی وێنەی بێ باکگراوند", lambda: self.save_inplace(r))
            m.addAction("↺  گەڕانەوە بۆ ئەسڵی", lambda: self.undo_inplace(r))
        else:
            m.addAction("✨  لابردنی باکگراوند لێرە", lambda: self.inplace(r))
        m.addAction("⬇  داگرتنی ئەسڵی (بێ لابردن)", lambda: self.download_one(r))
        m.addSeparator()
        m.addAction("☑  هەڵبژاردن", lambda: (self.set_select_mode(True), self.toggle_select(r)))
        if r.page_url:
            m.addAction("🌐  پەڕەی سەرچاوە", lambda: webbrowser.open(r.page_url))
        if not is_local(r.full_url):
            m.addAction("🔗  کۆپیکردنی لینک", lambda: QGuiApplication.clipboard().setText(r.full_url))
        else:
            m.addAction("✕  لابردن لە لیست", lambda: self.remove_mine(r))
        m.exec(pos)

    # ───── هەڵبژاردن ─────
    def set_select_mode(self, on: bool):
        self.select_mode = on
        if not on:
            self.selected.clear()
        for t in self.grid.items:
            t.select_mode = on
            t.sel = t.r.full_url in self.selected
            t.update_sel()
        self._update_results_ui()

    def toggle_select(self, r):
        if r.full_url in self.selected:
            self.selected.discard(r.full_url)
        else:
            self.selected.add(r.full_url)
        for t in self.grid.items:
            if t.r.full_url == r.full_url:
                t.sel = r.full_url in self.selected
                t.update_sel()
        self._update_results_ui()

    def select_all(self):
        self.selected = {r.full_url for r in self.all_items()}
        self.set_select_mode(True)

    def _update_results_ui(self):
        items = self.all_items()
        has = bool(items)
        if self.select_mode:
            self.res_head.setText(f"{len(self.selected)} وێنە هەڵبژێردراوە")
        elif self.results and self.last_query:
            self.res_head.setText(f"«{self.last_query}» — {len(self.results)} وێنە")
        else:
            self.res_head.setText(f"{len(items)} وێنە")
        self.b_selmode.setText("✕  هەڵوەشاندنەوە" if self.select_mode else "☑  هەڵبژاردن")
        self.b_selmode.setVisible(has)
        self.b_selall.setVisible(has and self.select_mode)
        self.b_rm_all.setVisible(has and not self.select_mode)
        ndone = sum(1 for r in items if self.tstate.get(r.full_url, TileState()).status == "DONE")
        self.b_save_done.setText(f"💾  پاشەکەوتی {ndone} وێنەی ئامادە")
        self.b_save_done.setVisible(ndone > 0 and not self.select_mode)
        self.b_more.setVisible(bool(self.results) and self.can_more)
        self.src_lbl.setVisible(bool(self.results) and self.source == "free")
        self.src_lbl.setText("سەرچاوە: ویکیپیدیا، Wikimedia Commons، Openverse — بۆ ئەنجامی گۆگڵ کلیلی Serper لە ⚙ دابنێ.")
        n = len(self.selected)
        self.sel_bar.setVisible(self.select_mode and n > 0)
        self.sel_lbl.setText(f"{n} وێنە هەڵبژێردراوە")
        QTimer.singleShot(0, self._relayout)

    # ───── لابردنی باکگراوند «لە شوێنی خۆی» ─────
    def _tiles_for(self, url: str):
        return [t for t in self.grid.items if t.r.full_url == url]

    def _on_tile_changed(self, url: str):
        for t in self._tiles_for(url):
            t.state = self.tstate.get(url, t.state)
            t.refresh()
        if self.tstate.get(url, TileState()).status in ("DONE", ""):
            self.grid._place()
        working = any(st.status in ("WAITING", "WORKING") for st in self.tstate.values())
        if working and not self.spin.isActive():
            self.spin.start()
        elif not working:
            self.spin.stop()
        self._update_results_ui()

    def _spin_tick(self):
        for t in self.grid.items:
            if t.state.status == "WORKING":
                t.update()

    def cut_opts(self) -> core.CutOptions:
        s = self.settings
        return core.CutOptions(s.person_only, s.focus_only, s.engine, s.removebg_key)

    def inplace(self, r: core.ImageResult, enhance: Optional[bool] = None):
        st = self.tstate.setdefault(r.full_url, TileState())
        if st.status in ("WAITING", "WORKING", "DONE"):
            return
        st.status, st.msg = "WAITING", ""
        self.tile_changed.emit(r.full_url)
        opts = self.cut_opts()
        enh = self.settings.enhance_after_cut if enhance is None else enhance
        self.inplace_pool.submit(self._inplace_job, r, st, opts, enh)

    def inplace_all(self):
        n = 0
        for r in self.all_items():
            st = self.tstate.setdefault(r.full_url, TileState())
            if st.status in ("", "ERROR"):
                st.status = ""
                self.inplace(r)
                n += 1
        self.toast(f"✨ {n} وێنە خرانە ڕیز — یەک بە یەک باکگراوندیان لادەبرێت")

    def _inplace_job(self, r: core.ImageResult, st: TileState, opts, enhance: bool):
        url = r.full_url
        last = [0.0]

        def prog(m: str):
            st.msg = m.replace("داگرتنی مۆدێلی", "مۆدێل")[:52]
            if time.time() - last[0] > 0.25:
                last[0] = time.time()
                self.tile_changed.emit(url)
        try:
            st.status = "WORKING"
            d = core.data_dir() / "inplace"
            d.mkdir(parents=True, exist_ok=True)
            h = hashlib.md5(url.encode("utf-8")).hexdigest()[:16]
            if st.orig and Path(st.orig).exists():
                b = Path(st.orig).read_bytes()
            else:
                prog("داگرتنی وێنە بە قەبارەی تەواو...")
                self.tile_changed.emit(url)
                b, _ = fetch_bytes(r)
                of = d / f"{h}_orig.{core.sniff_ext(b) or 'png'}"
                of.write_bytes(b)
                st.orig = str(of)
            src = core.open_image(b)
            out, _ = core.remove_background(src, opts, prog)
            enhanced = False
            if enhance and max(out.size) < 1800:
                try:
                    out = core.upscale_image(out, 2, lambda m: prog(m.replace("Upscale ×2 بە AI", "بەرزکردنەوەی کوالیتی")))
                    enhanced = True
                except Exception:  # noqa: BLE001
                    pass
            f = d / f"{h}.png"
            out.save(f)
            st.file, st.size, st.enhanced = str(f), out.size, enhanced
            st.thumb = pil_to_qimage(thumb(out, 640))
            st.status, st.msg = "DONE", ""
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            st.status, st.msg = "ERROR", (str(e) or "هەڵە")[:60]
        self.tile_changed.emit(url)

    def save_inplace(self, r: core.ImageResult, quiet=False) -> Optional[Path]:
        st = self.tstate.get(r.full_url)
        if not st or st.status != "DONE":
            return None
        p = unique(self.save_dir() / f"{result_name(r)}_nobg.png")
        try:
            shutil.copyfile(st.file, p)
        except Exception as e:  # noqa: BLE001
            self.message(f"پاشەکەوت سەرنەکەوت: {e}")
            return None
        if not quiet:
            self.toast(f"✔ پاشەکەوت کرا: {p.name}")
            if not self.dl_shown_folder:
                self.dl_shown_folder = True
                reveal_in_folder(p)
        return p

    def save_all_inplace(self):
        done = [r for r in self.all_items() if self.tstate.get(r.full_url, TileState()).status == "DONE"]
        last = None
        for r in done:
            last = self.save_inplace(r, quiet=True) or last
        if last:
            self.toast(f"✔ {len(done)} وێنە پاشەکەوت کران")
            reveal_in_folder(last)

    def undo_inplace(self, r: core.ImageResult):
        st = self.tstate.get(r.full_url)
        if st and st.status == "DONE":
            self.tstate[r.full_url] = TileState(orig=st.orig)
            self.tile_changed.emit(r.full_url)

    def batch_from_selection(self):
        picked = [r for r in self.all_items() if r.full_url in self.selected]
        self.set_select_mode(False)
        for r in picked:
            st = self.tstate.setdefault(r.full_url, TileState())
            if st.status == "ERROR":
                st.status = ""
            self.inplace(r)
        self.toast(f"✨ {len(picked)} وێنە خرانە ڕیز")

    # ───── پەنجەرەی بینین ─────
    def open_viewer(self, r: core.ImageResult):
        dlg = Viewer(self, r)
        self._viewer = dlg
        dlg.exec()
        self._viewer = None

    # ───── داگرتن بەبێ لابردن ─────
    def save_dir(self) -> Path:
        p = Path(self.settings.save_dir) if self.settings.save_dir else core.default_save_dir()
        p.mkdir(parents=True, exist_ok=True)
        return p

    def download_one(self, r: core.ImageResult):
        if r.full_url in self.downloading:
            return
        self.downloading.add(r.full_url)
        self._tile_loading(r.full_url, True)
        d = self.save_dir() / "Original"

        def job():
            try:
                b, low = fetch_bytes(r)
                p = core.save_downloaded(b, d, result_name(r))
                self.dl_done.emit(r.full_url, True, str(p) + ("|low" if low else ""))
            except Exception as e:  # noqa: BLE001
                self.dl_done.emit(r.full_url, False, str(e))
        self.dl_pool.submit(job)

    def _tile_loading(self, url, on):
        for t in self.grid.items:
            if t.r.full_url == url:
                t.set_loading_dl(on)

    def _on_dl_done(self, url, ok, info):
        self.downloading.discard(url)
        self._tile_loading(url, False)
        if not ok:
            self.message(f"ئەم وێنەیە دانابەزێت ({info}) — یەکێکی تر هەڵبژێرە", 10000)
            return
        path, low = info.split("|")[0], info.endswith("|low")
        self.dl_count += 1
        self.toast(f"✔ دابەزی ({self.dl_count}): {Path(path).name}" + ("  — تەنها وێنەی بچووک بەردەست بوو" if low else ""))
        self.message(f"✔ دابەزی ({self.dl_count}): {path}", 12000)
        if not self.dl_shown_folder:
            self.dl_shown_folder = True
            reveal_in_folder(Path(path))

    def download_selected(self):
        picked = [r for r in self.all_items() if r.full_url in self.selected]
        self.set_select_mode(False)
        for r in picked:
            self.download_one(r)
        self.toast(f"⬇ داگرتنی {len(picked)} وێنە دەستی پێکرد")

    def _drag_tile_path(self, r: core.ImageResult) -> Optional[Path]:
        if is_local(r.full_url):
            return local_path(r.full_url)
        cache = self._tile_drag_cache.get(r.full_url)
        if cache and cache.exists():
            return cache
        box: dict = {}

        def job():
            try:
                b, _ = fetch_bytes(r)
                box["p"] = core.save_downloaded(b, drag_dir() / "web", result_name(r))
            except Exception as e:  # noqa: BLE001
                box["e"] = str(e)
        fut = self.dl_pool.submit(job)
        QApplication.setOverrideCursor(QCursor(Qt.WaitCursor))
        try:
            t0 = time.time()
            while not fut.done() and time.time() - t0 < 60:
                QApplication.processEvents(QEventLoop.ExcludeUserInputEvents, 50)
                time.sleep(0.02)
        finally:
            QApplication.restoreOverrideCursor()
        if "p" not in box:
            self.message(f"ئەم وێنەیە دانابەزێت ({box.get('e', 'کات تەواو بوو')})", 10000)
            return None
        self._tile_drag_cache[r.full_url] = box["p"]
        return box["p"]

    # ───── وێنەکانی خۆم ─────
    def _add_mine(self, r: core.ImageResult):
        if any(m.full_url == r.full_url for m in self.mine):
            return
        self.mine.insert(0, r)
        self._populate_grid()
        self.show_results_page()

    def remove_mine(self, r: core.ImageResult):
        self.mine = [m for m in self.mine if m.full_url != r.full_url]
        self._populate_grid()

    def open_files(self):
        files, _ = QFileDialog.getOpenFileNames(self, "کردنەوەی وێنە", str(Path.home() / "Pictures"),
                                                "وێنە (*.png *.jpg *.jpeg *.webp *.bmp *.gif *.tif *.tiff *.avif *.heic)")
        self.open_paths(files)

    def open_paths(self, files: list[str]):
        files = [f for f in files if Path(f).is_file()]
        if not files:
            return
        new = []
        for f in files:
            url = QUrl.fromLocalFile(str(Path(f).resolve())).toString()
            w = h = 0
            try:
                with Image.open(f) as im:
                    w, h = ImageOps.exif_transpose(im).size
            except Exception:  # noqa: BLE001
                pass
            r = core.ImageResult(url, url, Path(f).stem, "", w, h)
            if not any(m.full_url == url for m in self.mine):
                self.mine.insert(0, r)
                new.append(r)
        self._populate_grid()
        self.show_results_page()
        if len(new) == 1 and len(files) == 1:
            self.open_viewer(new[0])
        else:
            for r in new:
                self.inplace(r)
            if new:
                self.toast(f"✨ {len(new)} وێنە زیادکران — باکگراوندیان لادەبرێت")

    def paste_image(self):
        md = QGuiApplication.clipboard().mimeData()
        if md.hasImage():
            img = QGuiApplication.clipboard().image()
            if not img.isNull():
                d = core.data_dir() / "pasted"
                d.mkdir(parents=True, exist_ok=True)
                p = d / f"paste_{int(time.time())}.png"
                img.save(str(p))
                self.open_paths([str(p)])
                return
        if md.hasUrls():
            local = [u.toLocalFile() for u in md.urls() if u.isLocalFile()]
            if local:
                self.open_paths(local)
                return
        txt = QGuiApplication.clipboard().text().strip()
        if txt.lower().startswith(("http://", "https://")):
            self.submit(txt)
        else:
            self.toast("هیچ وێنەیەک لە کلیپبۆرد نییە")

    def dragEnterEvent(self, e):
        if e.source() is not None:
            return
        if e.mimeData().hasUrls() or e.mimeData().hasImage():
            e.acceptProposedAction()

    def dropEvent(self, e):
        md = e.mimeData()
        local = [u.toLocalFile() for u in md.urls() if u.isLocalFile()]
        if local:
            self.open_paths(local)
            return
        web = [u.toString() for u in md.urls() if u.scheme().startswith("http")]
        if web:
            self.submit(web[0])
        elif md.hasImage():
            QGuiApplication.clipboard().setImage(md.imageData())
            self.paste_image()

    # ───── ڕێکخستن و دۆخ ─────
    def open_settings(self):
        dlg = SettingsDialog(self, self.settings)
        if dlg.exec():
            self.settings = dlg.result_settings()
            self.settings.save()
            self.toast("✔ ڕێکخستن پاشەکەوت کرا")

    def _state_dir(self) -> Path:
        p = core.data_dir() / "state"
        p.mkdir(exist_ok=True)
        return p

    def closeEvent(self, e):
        try:
            d = self._state_dir()
            (d / "state.json").write_text(json.dumps({"geometry": [self.x(), self.y(), self.width(), self.height()]}), "utf-8")
        except Exception:  # noqa: BLE001
            pass
        self.thumb_pool.shutdown(wait=False, cancel_futures=True)
        super().closeEvent(e)

    def fresh_start(self):
        """هەر جارێک بەرنامەکە دەکرێتەوە لە سەرەتاوە دەست پێدەکات (تەنها قەبارەی پەنجەرە دەمێنێتەوە)."""
        d = self._state_dir()
        try:
            g = json.loads((d / "state.json").read_text("utf-8")).get("geometry")
            if g:
                self.setGeometry(*g)
        except Exception:  # noqa: BLE001
            pass
        for sub in ("batch", "drag", "inplace", "pasted"):
            shutil.rmtree(core.data_dir() / sub, ignore_errors=True)
        self.stack.setCurrentIndex(0)
        self.q.setFocus()


def make_icon() -> QIcon:
    p = logo_path()
    if p is not None:
        ic = QIcon(str(p))
        if not ic.isNull():
            return ic
    pm = QPixmap(256, 256)
    pm.fill(Qt.transparent)
    pa = QPainter(pm)
    pa.setRenderHint(QPainter.Antialiasing)
    g = QLinearGradient(0, 0, 256, 256)
    g.setColorAt(0, QColor("#7B5CF0"))
    g.setColorAt(1, QColor("#F0508F"))
    pa.setBrush(QBrush(g))
    pa.setPen(Qt.NoPen)
    pa.drawRoundedRect(8, 8, 240, 240, 60, 60)
    f = QFont("Segoe UI")
    f.setPixelSize(118)
    f.setWeight(QFont.Black)
    pa.setFont(f)
    pa.setPen(QColor("white"))
    pa.drawText(QRect(0, 0, 256, 236), Qt.AlignCenter, "SG")
    pa.end()
    return QIcon(pm)


def logo_path() -> Optional[Path]:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    for p in (base / "bgremover" / "assets" / "logo.png", Path(__file__).resolve().parent / "assets" / "logo.png"):
        if p.exists():
            return p
    return None


def _selftest(inp: str, outp: str) -> None:
    """پشکنینی ئۆتۆماتیکی بێ ڕووکار. ئەنجام لە selftest.log دەنووسرێت."""
    log = open(Path(outp).with_name("selftest.log"), "w", encoding="utf-8")
    try:
        src = core.open_image(inp)
        out, _ = core.remove_background(src, core.CutOptions(), lambda m: print(m, file=log, flush=True))
        out.save(outp)
        up = core.upscale(src.resize((160, 120)), 4096, lambda m: None)
        print("SELFTEST OK", out.size, up.size, file=log, flush=True)
    except Exception:  # noqa: BLE001
        traceback.print_exc(file=log)
    finally:
        log.close()


def main():
    if "--selftest" in sys.argv:
        i = sys.argv.index("--selftest")
        _selftest(sys.argv[i + 1], sys.argv[i + 2])
        return
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("krd.sgsearch.desktop")
        except Exception:  # noqa: BLE001
            pass
    app = QApplication(sys.argv)
    app.setApplicationName(APP_TITLE)
    app.setLayoutDirection(Qt.RightToLeft)
    if sys.platform == "win32":
        f = app.font()
        f.setFamily("Segoe UI")
        app.setFont(f)
    # ڕووکاری ڕووناکی تایبەت (بەبێ گوێدانە Dark Mode ی ویندۆز)
    app.setStyle("Fusion")
    from PySide6.QtGui import QPalette
    pal = QPalette()
    for role, col in [(QPalette.Window, BG), (QPalette.WindowText, TEXT), (QPalette.Base, "#FFFFFF"),
                      (QPalette.AlternateBase, CARD2), (QPalette.Text, TEXT), (QPalette.Button, "#FFFFFF"),
                      (QPalette.ButtonText, TEXT), (QPalette.ToolTipBase, TEXT), (QPalette.ToolTipText, "#FFFFFF"),
                      (QPalette.PlaceholderText, "#9AA0B4"), (QPalette.Highlight, ACCENT),
                      (QPalette.HighlightedText, "#FFFFFF"), (QPalette.Light, "#FFFFFF"), (QPalette.Mid, LINE),
                      (QPalette.Dark, "#C9CEDC"), (QPalette.Shadow, "#B8BED0"), (QPalette.BrightText, TEXT),
                      (QPalette.Link, ACCENT)]:
        pal.setColor(role, QColor(col))
    app.setPalette(pal)
    app.setStyleSheet(STYLE)
    app.setWindowIcon(make_icon())
    w = MainWindow()
    w.show()
    if "--smoke-test" in sys.argv:
        QTimer.singleShot(1500, app.quit)
    sys.exit(app.exec())
