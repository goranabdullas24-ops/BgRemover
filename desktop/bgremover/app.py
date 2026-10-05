"""
BgRemover Desktop — ڕووکاری کوردی (ڕاست بۆ چەپ) بە PySide6.
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import traceback
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Callable, Optional

from PIL import Image
from PySide6.QtCore import (QObject, QRunnable, QRect, QSize, Qt, QThreadPool, QTimer, Signal, QUrl, QPoint,
                            QMimeData, QEventLoop)
from PySide6.QtGui import (QAction, QColor, QDesktopServices, QGuiApplication, QIcon, QImage,
                           QKeySequence, QPainter, QPixmap, QShortcut, QBrush, QDrag, QCursor)
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QColorDialog, QDialog,
                               QDialogButtonBox, QFileDialog, QFrame, QGridLayout, QHBoxLayout,
                               QLabel, QLineEdit, QMainWindow, QMenu, QMessageBox, QProgressBar,
                               QPushButton, QRadioButton, QScrollArea, QSizePolicy, QSplitter,
                               QStatusBar, QToolButton, QVBoxLayout, QWidget, QGroupBox)

from . import core

# ───────────────────────── ڕووکار (تێمی ڕووناک + ڕەنگی وەنەوشەیی/پەمەیی) ─────────────────────────
APP_TITLE = "SG search"
BG = "#F6F4FF"          # باکگراوندی پەنجەرە
SURFACE = "#FFFFFF"     # بەشەکان
CARD = "#FFFFFF"        # کارتەکان
CARD2 = "#F2EFFD"       # دوگمە / hover
LINE = "#E3DEF6"        # هێڵ
TEXT = "#1D1B33"
MUTED = "#6B6890"
ACCENT = "#7B5CF0"      # وەنەوشەیی
ACCENT2 = "#F0508F"     # پەمەیی
OK = "#14A86A"
ERR = "#E5484D"
PURPLE = ACCENT
GRAD = f"qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 {ACCENT}, stop:1 {ACCENT2})"
GRAD_H = "qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #8C70FF, stop:1 #FF62A0)"

STYLE = f"""
QWidget {{ font-size: 10.5pt; color: {TEXT}; }}
QMainWindow, QDialog {{ background: {BG}; }}
QScrollArea, QScrollArea > QWidget > QWidget {{ background: transparent; border: none; }}
QWidget#root {{ background: {BG}; }}
QFrame#header {{ background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 {ACCENT}, stop:1 {ACCENT2});
                 border-radius: 20px; }}
QFrame#header QPushButton {{ background: rgba(255,255,255,40); border: 1px solid rgba(255,255,255,90); color: white; }}
QFrame#header QPushButton:hover {{ background: rgba(255,255,255,75); }}
QFrame#header QPushButton#primary {{ background: white; color: {ACCENT}; border: none; }}
QFrame#header QPushButton#primary:hover {{ background: #F4F0FF; }}
QFrame#header QLineEdit {{ background: white; border: none; color: {TEXT}; }}
QFrame#header QLineEdit:focus {{ background: white; border: 2px solid rgba(255,255,255,200); }}
QLabel#appTitle {{ font-size: 17pt; font-weight: 800; color: white; }}
QLabel#appSub {{ color: rgba(255,255,255,215); font-size: 9.5pt; }}
QFrame#card {{ background: {CARD}; border-radius: 18px; border: 1px solid {LINE}; }}
QFrame#panel {{ background: {SURFACE}; border-radius: 18px; border: 1px solid {LINE}; }}
QLabel#cardTitle {{ font-weight: 700; font-size: 11.5pt; color: {TEXT}; }}
QLabel#muted {{ color: {MUTED}; }}
QLabel {{ background: transparent; }}
QPushButton {{ border: 1px solid {LINE}; border-radius: 12px; padding: 8px 14px; background: {CARD2}; color: {TEXT}; font-weight: 600; }}
QPushButton:hover {{ background: #E9E3FF; border-color: {ACCENT}; color: {ACCENT}; }}
QPushButton:pressed {{ background: #DDD4FF; }}
QPushButton:disabled {{ color: #B4B0CC; background: #F7F6FB; border-color: #ECE9F6; }}
QPushButton#primary {{ background: {GRAD}; color: white; border: none; }}
QPushButton#primary:hover {{ background: {GRAD_H}; }}
QPushButton#primary:disabled {{ background: #D9D2F5; color: white; }}
QPushButton#ghost {{ background: transparent; border: 1px solid {LINE}; color: {MUTED}; }}
QPushButton#ghost:hover {{ color: white; border-color: {ACCENT}; }}
QLineEdit {{ border: 1.5px solid {LINE}; border-radius: 14px; padding: 10px 16px; background: white; color: {TEXT};
             font-size: 12pt; selection-background-color: {ACCENT}; selection-color: white; }}
QLineEdit:focus {{ border-color: {ACCENT}; background: white; }}
QCheckBox, QRadioButton {{ color: {TEXT}; spacing: 8px; }}
QGroupBox {{ color: {TEXT}; border: 1px solid {LINE}; border-radius: 12px; margin-top: 14px; padding: 10px; font-weight: 600; }}
QGroupBox::title {{ subcontrol-origin: margin; subcontrol-position: top right; padding: 0 8px; }}
QMenu {{ background: {CARD}; color: {TEXT}; border: 1px solid {LINE}; border-radius: 10px; padding: 6px; }}
QMenu::item {{ padding: 7px 18px; border-radius: 6px; }}
QMenu::item:selected {{ background: {ACCENT}; color: white; }}
QToolTip {{ background: white; color: {TEXT}; border: 1px solid {ACCENT}; padding: 5px; border-radius: 6px; }}
QToolButton#tileBtn {{ background: rgba(255,255,255,235); color: {ACCENT}; border-radius: 17px; font-size: 12.5pt; font-weight: 800;
                       border: 1px solid rgba(123,92,240,60); }}
QToolButton#tileBtn:hover {{ background: {GRAD}; color: white; border: none; }}
QToolButton#tileBtn:disabled {{ color: #777; }}
QProgressBar {{ border: none; background: #E9E5F8; height: 6px; border-radius: 3px; }}
QProgressBar::chunk {{ background: {GRAD}; border-radius: 3px; }}
QStatusBar {{ background: {SURFACE}; color: {MUTED}; border-top: 1px solid {LINE}; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: #D3CCEE; border-radius: 4px; min-height: 40px; }}
QScrollBar::handle:vertical:hover {{ background: {ACCENT}; }}
QScrollBar::add-line, QScrollBar::sub-line, QScrollBar::add-page, QScrollBar::sub-page {{ background: none; height: 0; }}
QSplitter::handle {{ background: transparent; }}
QSplitter::handle:hover {{ background: {LINE}; border-radius: 3px; }}
"""

COLORS: list[Optional[tuple[int, int, int]]] = [None, (255, 255, 255), (0, 0, 0), (30, 111, 217),
                                                (217, 48, 37), (46, 125, 50)]


# ───────────────────────── یارمەتیدەرەکان ─────────────────────────

def pil_to_qimage(im: Image.Image) -> QImage:
    im = im.convert("RGBA")
    data = im.tobytes("raw", "RGBA")
    return QImage(data, im.width, im.height, im.width * 4, QImage.Format_RGBA8888).copy()


def thumb(im: Image.Image, side: int) -> Image.Image:
    t = im.copy()
    t.thumbnail((side, side), Image.LANCZOS)
    return t


class Signals(QObject):
    done = Signal(object)
    error = Signal(str)
    progress = Signal(str)


class Task(QRunnable):
    """کارێک لە پشتەوە؛ ئەنجام بە سیگناڵ دەگەڕێتەوە بۆ ڕووکار."""

    def __init__(self, fn: Callable, *args):
        super().__init__()
        self.fn, self.args = fn, args
        self.s = Signals()

    def run(self):
        try:
            res = self.fn(*self.args, self.s.progress.emit)
            self.s.done.emit(res)
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            self.s.error.emit(str(e) or e.__class__.__name__)


def dims(w, h) -> str:
    """قەبارە بە ڕیزبەندی دروست لەناو نووسینی کوردی (RTL): 1920×1080"""
    return f"\u200e{w}×{h}\u200e"


def drag_dir() -> Path:
    p = core.data_dir() / "drag"
    p.mkdir(parents=True, exist_ok=True)
    return p


def start_file_drag(widget: QWidget, path: Optional[Path], preview: Optional[QImage] = None):
    """فایلەکە ڕادەکێشرێتە ناو بەرنامەیەکی تر (Photoshop، Premiere، Word، تێلێگرام، Explorer...)."""
    if path is None or not Path(path).exists():
        return
    md = QMimeData()
    md.setUrls([QUrl.fromLocalFile(str(path))])
    d = QDrag(widget)
    d.setMimeData(md)
    if preview is not None and not preview.isNull():
        pm = QPixmap.fromImage(preview.scaled(120, 120, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        d.setPixmap(pm)
        d.setHotSpot(QPoint(pm.width() // 2, pm.height() // 2))
    d.exec(Qt.CopyAction)


class DragSource:
    """کلیک = وەک پێشوو؛ ڕاکێشان = ناردنی وێنەکە بۆ بەرنامەیەکی تر."""
    _press_pos: Optional[QPoint] = None
    _dragged = False

    def drag_path(self) -> Optional[Path]:  # override
        return None

    def drag_preview(self) -> Optional[QImage]:
        return None

    def ds_press(self, e):
        self._dragged = False
        self._press_pos = e.position().toPoint() if e.button() == Qt.LeftButton else None

    def ds_move(self, e):
        if self._press_pos is None or not (e.buttons() & Qt.LeftButton):
            return
        if (e.position().toPoint() - self._press_pos).manhattanLength() < QApplication.startDragDistance():
            return
        self._press_pos = None
        self._dragged = True
        p = self.drag_path()
        if p is not None:
            start_file_drag(self, p, self.drag_preview())

    def ds_was_drag(self) -> bool:
        d = self._dragged
        self._dragged = False
        return d


class Checker(DragSource, QLabel):
    """پیشاندانی وێنە لەسەر خانەخانە (بۆ ڕوونی) یان ڕەنگ. دەتوانرێت ڕابکێشرێتە دەرەوە."""

    def __init__(self, min_h=260):
        super().__init__()
        self.path_provider: Optional[Callable[[], Optional[Path]]] = None
        self.setAlignment(Qt.AlignCenter)
        self.setMinimumHeight(min_h)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self._img: Optional[QImage] = None
        self.bg: Optional[tuple[int, int, int]] = None
        self.checker = True

    def set_image(self, img: Optional[QImage]):
        self._img = img
        if self.path_provider:
            self.setCursor(Qt.OpenHandCursor if img is not None else Qt.ArrowCursor)
            self.setToolTip("ڕایبکێشە ناو هەر بەرنامەیەک (Photoshop، Premiere، Word...)" if img is not None else "")
        self.update()

    def drag_path(self):
        return self.path_provider() if (self.path_provider and self._img is not None) else None

    def drag_preview(self):
        return self._img

    def mousePressEvent(self, e):
        self.ds_press(e)
        super().mousePressEvent(e)

    def mouseMoveEvent(self, e):
        self.ds_move(e)
        super().mouseMoveEvent(e)

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        r = self.rect()
        if self.checker:
            if self.bg is None:
                s = 12
                for y in range(0, r.height(), s):
                    for x in range(0, r.width(), s):
                        p.fillRect(x, y, s, s, QColor("#FFFFFF") if (x // s + y // s) % 2 else QColor("#ECE8F7"))
            else:
                p.fillRect(r, QColor(*self.bg))
        if self._img is not None and not self._img.isNull():
            sz = self._img.size().scaled(r.size(), Qt.KeepAspectRatio)
            if sz.width() > self._img.width() * 2:
                sz = self._img.size() * 2
            x = (r.width() - sz.width()) // 2
            y = (r.height() - sz.height()) // 2
            p.drawImage(QRect(x, y, sz.width(), sz.height()), self._img)
        p.end()


def card(title: str) -> tuple[QFrame, QVBoxLayout, QLabel]:
    f = QFrame()
    f.setObjectName("card")
    v = QVBoxLayout(f)
    v.setContentsMargins(14, 12, 14, 14)
    t = QLabel(title)
    t.setObjectName("cardTitle")
    t.setAlignment(Qt.AlignCenter)
    v.addWidget(t)
    return f, v, t


def btn(text: str, primary=False, cb=None) -> QPushButton:
    b = QPushButton(text)
    if primary:
        b.setObjectName("primary")
    b.setCursor(Qt.PointingHandCursor)
    if cb:
        b.clicked.connect(cb)
    return b


def color_chip(color, selected: bool, cb) -> QToolButton:
    b = QToolButton()
    b.setFixedSize(34, 34)
    b.setCursor(Qt.PointingHandCursor)
    border = f"3px solid {ACCENT2}" if selected else f"1px solid {LINE}"
    if color is None:
        b.setText("▦")
        b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{border};background:white;color:{MUTED};font-size:14pt;}}")
        b.setToolTip("ڕوون (بێ باکگراوند)")
    else:
        b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{border};background:rgb{color};}}")
    b.clicked.connect(cb)
    return b


# ───────────────────────── خانەی وێنەی گەڕان ─────────────────────────

@dataclass
class TileState:
    """دۆخی کارکردنی «لە شوێنی خۆی» بۆ هەر وێنەیەکی گەڕان."""
    status: str = ""          # "" | WAITING | WORKING | DONE | ERROR
    msg: str = ""
    file: str = ""            # وێنەی بێ باکگراوند (PNG)
    orig: str = ""            # وێنە ئەسڵییەکە
    size: tuple = (0, 0)
    enhanced: bool = False
    thumb: Optional[QImage] = None


class Tile(DragSource, QFrame):
    clicked = Signal(object, bool)   # result, ctrl
    remove = Signal(object)          # ✨ لابردن لە شوێنی خۆی
    download = Signal(object)
    upscale = Signal(object)
    save = Signal(object)
    open_editor = Signal(object)
    undo = Signal(object)
    context = Signal(object, QPoint)

    SIZE = 280
    RADIUS = 16

    def __init__(self, r: core.ImageResult, state: TileState):
        super().__init__()
        self.r = r
        self.state = state
        self.pm: Optional[QPixmap] = None
        self.hover = False
        self.setFixedSize(self.SIZE, self.SIZE)
        self.setCursor(Qt.PointingHandCursor)
        self.setAttribute(Qt.WA_Hover, True)
        self.sel = False
        self.select_mode = False

        def mk(text, tip, sig):
            b = QToolButton(self)
            b.setObjectName("tileBtn")
            b.setText(text)
            b.setToolTip(tip)
            b.setFixedSize(34, 34)
            b.setCursor(Qt.PointingHandCursor)
            b.clicked.connect(lambda: sig.emit(self.r))
            return b
        self.b_rm = mk("✨", "لابردنی باکگراوند لێرە (+ بەرزکردنەوەی کوالیتی)", self.remove)
        self.b_dl = mk("⬇", "داگرتنی وێنەی ئەسڵی (بەبێ لابردن)", self.download)
        self.b_up = mk("2×", "Upscale ×2 بە AI", self.upscale)
        self.b_save = mk("💾", "پاشەکەوتی وێنەی بێ باکگراوند", self.save)
        self.b_open = mk("⤢", "کردنەوە لە بەشی سەرەوە (ڕەنگ، Upscale...)", self.open_editor)
        self.b_undo = mk("↺", "گەڕانەوە بۆ وێنە ئەسڵییەکە", self.undo)
        self.refresh()

    # ── ڕووکار ──
    def _layout_buttons(self):
        done = self.state.status == "DONE"
        top = [self.b_save, self.b_open, self.b_undo] if done else [self.b_rm, self.b_dl, self.b_up]
        for b in (self.b_rm, self.b_dl, self.b_up, self.b_save, self.b_open, self.b_undo):
            b.setVisible(False)
        if self.select_mode or self.state.status in ("WAITING", "WORKING"):
            return
        for i, b in enumerate(top):
            b.move(8, 8 + i * 40)
            b.setVisible(True)

    def refresh(self):
        self._layout_buttons()
        tip = self.r.title or ""
        if self.state.status == "DONE":
            tip = "ڕایبکێشە ناو هەر بەرنامەیەک — کلیک بۆ کردنەوە"
        self.setToolTip(tip)
        self.update()

    def set_pixmap(self, pm: QPixmap):
        self.pm = pm
        self.update()

    def set_loading_dl(self, loading: bool):
        self.b_dl.setText("…" if loading else "⬇")
        self.b_dl.setEnabled(not loading)

    def update_sel(self):
        self._layout_buttons()
        self.update()

    def event(self, e):
        from PySide6.QtCore import QEvent
        if e.type() == QEvent.HoverEnter:
            self.hover = True
            self.update()
        elif e.type() == QEvent.HoverLeave:
            self.hover = False
            self.update()
        return super().event(e)

    def paintEvent(self, _):
        from PySide6.QtGui import QPainterPath, QLinearGradient, QPen, QFont
        S, R = self.SIZE, self.RADIUS
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        path = QPainterPath()
        path.addRoundedRect(1, 1, S - 2, S - 2, R, R)
        p.setClipPath(path)
        st = self.state
        if st.status == "DONE" and st.thumb is not None:
            c = 14
            for y in range(0, S, c):
                for x in range(0, S, c):
                    p.fillRect(x, y, c, c, QColor("#FFFFFF") if (x // c + y // c) % 2 else QColor("#ECE8F7"))
            img = st.thumb
            sz = img.size().scaled(S - 16, S - 34, Qt.KeepAspectRatio)
            p.drawImage(QRect((S - sz.width()) // 2, 8 + (S - 34 - sz.height()) // 2, sz.width(), sz.height()), img)
        elif self.pm is not None and not self.pm.isNull():
            pm = self.pm.scaled(S, S, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
            p.drawPixmap(0, 0, pm, (pm.width() - S) // 2, (pm.height() - S) // 2, S, S)
        else:
            g = QLinearGradient(0, 0, S, S)
            g.setColorAt(0, QColor("#EFEBFF"))
            g.setColorAt(1, QColor("#FCEAF3"))
            p.fillRect(0, 0, S, S, g)
            p.setPen(QColor(MUTED))
            p.drawText(QRect(0, 0, S, S), Qt.AlignCenter, "…")
        # شریتی خوارەوە (بۆ وێنەی تەواوبوو: شریتی سپی)
        if st.status == "DONE":
            p.fillRect(0, S - 30, S, 30, QColor(255, 255, 255, 235))
        else:
            g = QLinearGradient(0, S - 60, 0, S)
            g.setColorAt(0, QColor(0, 0, 0, 0))
            g.setColorAt(1, QColor(8, 8, 18, 215))
            p.fillRect(0, S - 60, S, 60, g)
        f = QFont(self.font())
        f.setPointSizeF(8.8)
        f.setBold(True)
        p.setFont(f)
        if st.status == "DONE":
            p.setPen(QColor(OK))
            label = f"✓ {dims(st.size[0], st.size[1])}" + ("  ✨ HD" if st.enhanced else "  بێ باکگراوند")
        elif self.r.width and self.r.height:
            p.setPen(QColor("#E6E8FF"))
            label = f"{dims(self.r.width, self.r.height)}"
        else:
            label = ""
        if label:
            p.drawText(QRect(10, S - 25, S - 20, 20), Qt.AlignVCenter | Qt.AlignLeft, label)
        # دۆخی کارکردن
        if st.status in ("WAITING", "WORKING", "ERROR"):
            p.fillRect(0, 0, S, S, QColor(10, 10, 24, 175 if st.status != "ERROR" else 140))
            if st.status == "WORKING":
                ang = int((time.time() * 360) % 360) * 16
                pen = QPen(QColor(ACCENT2), 5)
                pen.setCapStyle(Qt.RoundCap)
                p.setPen(pen)
                p.drawArc(QRect(S // 2 - 24, S // 2 - 46, 48, 48), -ang, 100 * 16)
                pen.setColor(QColor(ACCENT))
                p.setPen(pen)
                p.drawArc(QRect(S // 2 - 24, S // 2 - 46, 48, 48), -ang + 180 * 16, 100 * 16)
            p.setPen(QColor("white") if st.status != "ERROR" else QColor(ERR))
            f.setPointSizeF(9)
            p.setFont(f)
            txt = {"WAITING": "⏳ لە ڕیزدایە...", "WORKING": st.msg or "کار دەکات...",
                   "ERROR": "✗ " + (st.msg or "هەڵە")}[st.status]
            p.drawText(QRect(10, S // 2 + 8, S - 20, 60), Qt.AlignHCenter | Qt.AlignTop | Qt.TextWordWrap, txt)
        p.setClipping(False)
        # چوارچێوە
        if self.sel or self.hover or st.status == "DONE":
            gp = QLinearGradient(0, 0, S, S)
            gp.setColorAt(0, QColor(ACCENT))
            gp.setColorAt(1, QColor(ACCENT2))
            w = 3 if self.sel else 2
            col = QBrush(gp) if (self.sel or self.hover) else QBrush(QColor(OK))
            p.setPen(QPen(col, w))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(QRect(1, 1, S - 2, S - 2), R, R)
        if self.select_mode:
            cr = QRect(S - 36, 8, 28, 28)
            p.setPen(QPen(QColor("white"), 2))
            p.setBrush(QBrush(QColor(ACCENT2)) if self.sel else QBrush(QColor(0, 0, 0, 110)))
            p.drawEllipse(cr)
            if self.sel:
                p.drawText(cr, Qt.AlignCenter, "✓")
        p.end()

    drag_request = None  # Callable[[ImageResult], Optional[Path]] — لە MainWindow دادەنرێت

    def drag_path(self):
        if self.state.status == "DONE" and self.state.file:
            return Path(self.state.file)
        return Tile.drag_request(self.r) if Tile.drag_request else None

    def drag_preview(self):
        if self.state.status == "DONE" and self.state.thumb is not None:
            return self.state.thumb
        return self.pm.toImage() if self.pm is not None and not self.pm.isNull() else None

    def mousePressEvent(self, e):
        self.ds_press(e)

    def mouseMoveEvent(self, e):
        self.ds_move(e)

    def mouseReleaseEvent(self, e):
        if self.ds_was_drag():
            return
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self.r, bool(e.modifiers() & Qt.ControlModifier))

    def contextMenuEvent(self, e):
        self.context.emit(self.r, e.globalPos())


class FlowGrid(QWidget):
    """تۆڕی خانەکان کە ژمارەی ستوونەکان بەپێی پانی دەگۆڕێت."""

    def __init__(self):
        super().__init__()
        self.grid = QGridLayout(self)
        self.grid.setSpacing(14)
        self.grid.setContentsMargins(0, 0, 0, 0)
        self.grid.setAlignment(Qt.AlignTop | Qt.AlignHCenter)
        self.items: list[QWidget] = []
        self.cols = 3

    def clear(self):
        for w in self.items:
            w.setParent(None)
            w.deleteLater()
        self.items = []

    def add(self, w: QWidget):
        i = len(self.items)
        self.items.append(w)
        self.grid.addWidget(w, i // self.cols, i % self.cols)

    def relayout(self, width: int, cell: int):
        cols = max(1, (width + 14) // (cell + 14))
        if cols == self.cols:
            return
        self.cols = cols
        for w in self.items:
            self.grid.removeWidget(w)
        for i, w in enumerate(self.items):
            self.grid.addWidget(w, i // cols, i % cols)


# ───────────────────────── کاری بەکۆمەڵ ─────────────────────────

@dataclass
class BatchItem:
    id: int
    url: str = ""
    fallback: str = ""
    referer: str = ""
    path: str = ""          # فایلی سەر کۆمپیوتەر
    status: str = "WAITING"  # WAITING, WORKING, DONE, ERROR
    file: str = ""
    error: str = ""


class BatchTile(DragSource, QFrame):
    def __init__(self, item: BatchItem, open_cb):
        super().__init__()
        self.item = item
        self.setFixedSize(132, 132)
        self.view = Checker(min_h=0)
        self.view.setFixedSize(132, 132)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(self.view)
        self.lbl = QLabel(self.view)
        self.lbl.setAlignment(Qt.AlignCenter)
        self.lbl.setGeometry(0, 0, 132, 132)
        self.lbl.setWordWrap(True)
        self.open_cb = open_cb
        self.setCursor(Qt.PointingHandCursor)

    def refresh(self, qimg: Optional[QImage], bg):
        self.view.bg = bg
        self.view.set_image(qimg)
        st = self.item.status
        txt = {"WAITING": "⏳", "WORKING": "⚙️…", "ERROR": f"✗\n{self.item.error}", "DONE": ""}[st]
        self.lbl.setText(txt)
        self.lbl.setStyleSheet(f"color:{ERR};font-size:9pt;background:rgba(14,15,26,190);" if st == "ERROR"
                               else "font-size:18pt;background:transparent;")

    def drag_path(self):
        return Path(self.item.file) if self.item.status == "DONE" and self.item.file else None

    def drag_preview(self):
        return self.view._img

    def mousePressEvent(self, e):
        self.ds_press(e)

    def mouseMoveEvent(self, e):
        self.ds_move(e)

    def mouseReleaseEvent(self, e):
        if self.ds_was_drag():
            return
        if self.item.status == "DONE":
            self.open_cb(self.item)


# ───────────────────────── ڕێکخستن ─────────────────────────

class SettingsDialog(QDialog):
    def __init__(self, parent, s: core.Settings):
        super().__init__(parent)
        self.setWindowTitle("ڕێکخستن")
        self.setLayoutDirection(Qt.RightToLeft)
        self.s = s
        v = QVBoxLayout(self)
        self.person = QCheckBox("تەنها مرۆڤ — شتی زیادە (لۆگۆ، بانەر...) لادەبات")
        self.person.setChecked(s.person_only)
        self.focus = QCheckBox("تەنها کەسی سەرەکی (فۆکس) — ئەگەر چەند کەس هەبن")
        self.focus.setChecked(s.focus_only)
        self.enh = QCheckBox("بەرزکردنەوەی کوالیتی دوای لابردن — وێنەی بچووک (کەمتر لە 1800px) ×2 بە AI ڕوون دەکرێتەوە")
        self.enh.setChecked(s.auto_enhance)
        v.addWidget(self.person)
        v.addWidget(self.focus)
        v.addWidget(self.enh)

        g = QGroupBox("شێوازی لابردنی باکگراوند")
        gv = QVBoxLayout(g)
        self.r_is = QRadioButton("IS-Net + MODNet (وردترین، بەخۆڕایی، بێ ئینتەرنێت)")
        self.r_rb = QRadioButton("remove.bg (پێویستی بە کلیل و ئینتەرنێتە)")
        (self.r_rb if s.engine == "removebg" else self.r_is).setChecked(True)
        gv.addWidget(self.r_is)
        gv.addWidget(self.r_rb)
        self.rb_key = QLineEdit(s.removebg_key)
        self.rb_key.setPlaceholderText("remove.bg API Key")
        self.rb_key.setEchoMode(QLineEdit.Password)
        gv.addWidget(self.rb_key)
        v.addWidget(g)

        g2 = QGroupBox("گەڕانی گۆگڵ (ئارەزوومەندانە)")
        g2v = QVBoxLayout(g2)
        g2v.addWidget(QLabel("کلیلی بەخۆڕایی لە serper.dev — بەبێ کلیل لە ویکیپیدیا و Openverse دەگەڕێت."))
        self.serper = QLineEdit(s.serper_key)
        self.serper.setPlaceholderText("Serper API Key")
        self.serper.setEchoMode(QLineEdit.Password)
        g2v.addWidget(self.serper)
        v.addWidget(g2)

        g3 = QGroupBox("شوێنی پاشەکەوت")
        g3h = QHBoxLayout(g3)
        self.dir = QLineEdit(s.save_dir or str(core.default_save_dir()))
        g3h.addWidget(self.dir)
        g3h.addWidget(btn("گۆڕین...", cb=self._pick_dir))
        v.addWidget(g3)

        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Save).setText("پاشەکەوت")
        bb.button(QDialogButtonBox.Cancel).setText("داخستن")
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        v.addWidget(bb)
        self.resize(560, 0)

    def _pick_dir(self):
        d = QFileDialog.getExistingDirectory(self, "شوێنی پاشەکەوت", self.dir.text())
        if d:
            self.dir.setText(d)

    def result_settings(self) -> core.Settings:
        s = self.s
        s.person_only = self.person.isChecked()
        s.focus_only = self.focus.isChecked()
        s.auto_enhance = self.enh.isChecked()
        s.removebg_key = self.rb_key.text().strip()
        s.engine = "removebg" if self.r_rb.isChecked() and s.removebg_key else "isnet"
        s.serper_key = self.serper.text().strip()
        s.save_dir = self.dir.text().strip()
        return s


# ───────────────────────── پەنجەرەی سەرەکی ─────────────────────────

class MainWindow(QMainWindow):
    thumb_ready = Signal(str, QImage)
    tile_changed = Signal(str)
    dl_done = Signal(str, bool, str)
    batch_update = Signal(int)
    batch_finished = Signal(str)

    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_TITLE} {core.VERSION} — گەڕان و لابردنی باکگراوند")
        self.setLayoutDirection(Qt.RightToLeft)
        self.resize(1360, 860)
        self.settings = core.Settings.load()
        self.pool = QThreadPool.globalInstance()
        self.thumb_pool = ThreadPoolExecutor(8)
        self.thumb_cache: dict[str, QImage] = {}

        # دۆخ
        self.results: list[core.ImageResult] = []
        self.source = ""
        self.last_query = ""
        self.page = 1
        self.can_more = False
        self.selected: set[str] = set()
        self.select_mode = False
        self.original: Optional[Image.Image] = None
        self.orig_bytes: Optional[bytes] = None
        self.orig_name = "image"
        self.cutout: Optional[Image.Image] = None
        self.upscaled = False
        self.low_quality = False
        self.bg: Optional[tuple[int, int, int]] = None
        self.busy = False
        self.batch: list[BatchItem] = []
        self.batch_tiles: dict[int, BatchTile] = {}
        self.batch_thumbs: dict[int, QImage] = {}
        self.batch_running = False
        self.batch_stop = False
        self.next_id = 1
        self.downloading: set[str] = set()
        self.tstate: dict[str, TileState] = {}
        self.inplace_pool = ThreadPoolExecutor(1)    # مۆدێلەکان قورسن → یەک بە یەک
        self.spin = QTimer(self)
        self.spin.setInterval(40)
        self.spin.timeout.connect(self._spin_tick)
        self.dl_pool = ThreadPoolExecutor(3)
        self._tile_drag_cache: dict[str, Path] = {}
        Tile.drag_request = self._drag_tile_path
        self.dl_count = 0
        self.dl_shown_folder = False

        self._tasks: set = set()
        self._bridge = Signals()
        self._bridge.progress.connect(lambda m: self.set_busy(m))
        self._build()
        self.thumb_ready.connect(self._on_thumb)
        self.tile_changed.connect(self._on_tile_changed, Qt.QueuedConnection)
        self.dl_done.connect(self._on_dl_done)
        self.batch_update.connect(self._refresh_batch_tile)
        self.batch_finished.connect(self._on_batch_finished)
        self.setAcceptDrops(True)
        QShortcut(QKeySequence.Paste, self, activated=self.paste_image)
        QShortcut(QKeySequence("Ctrl+O"), self, activated=self.open_files)
        QShortcut(QKeySequence("Ctrl+S"), self, activated=self.save_result)
        # هەر جارێک بەرنامەکە دەکرێتەوە لە سەرەتاوە دەست پێدەکات (تەنها قەبارەی پەنجەرە دەمێنێتەوە)
        QTimer.singleShot(0, self.fresh_start)

    # ───── ڕووکار ─────
    def _build(self):
        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)
        rv = QVBoxLayout(root)
        rv.setContentsMargins(18, 14, 18, 10)
        rv.setSpacing(12)

        # ── سەردێڕ: ناو + گەڕان ──
        header = QFrame()
        header.setObjectName("header")
        hl = QHBoxLayout(header)
        hl.setContentsMargins(18, 14, 18, 14)
        hl.setSpacing(12)
        logo = QLabel()
        lp = logo_path()
        logo.setPixmap(QPixmap(str(lp)).scaled(48, 48, Qt.KeepAspectRatio, Qt.SmoothTransformation)
                       if lp else make_icon().pixmap(48, 48))
        logo.setFixedSize(54, 54)
        logo.setAlignment(Qt.AlignCenter)
        logo.setStyleSheet("background: rgba(255,255,255,60); border: 2px solid rgba(255,255,255,170); border-radius: 15px;")
        hl.addWidget(logo)
        tbox = QVBoxLayout()
        tbox.setSpacing(0)
        t1 = QLabel(APP_TITLE)
        t1.setObjectName("appTitle")
        t2 = QLabel("گەڕان · لابردنی باکگراوند · Upscale بە AI")
        t2.setObjectName("appSub")
        tbox.addWidget(t1)
        tbox.addWidget(t2)
        hl.addLayout(tbox)
        hl.addSpacing(10)
        self.q = QLineEdit()
        self.q.setPlaceholderText("🔍  ناوی کەس، شوێن یان هەر شتێک بنووسە... یان لینکی وێنە")
        self.q.returnPressed.connect(self.submit)
        self.q.setClearButtonEnabled(True)
        self.q.setMinimumHeight(46)
        hl.addWidget(self.q, 1)
        self.b_search = btn("گەڕان", True, self.submit)
        self.b_search.setMinimumHeight(46)
        self.b_search.setMinimumWidth(110)
        hl.addWidget(self.b_search)
        for text, tip, cb in (("🖼", "کردنەوەی وێنە لە کۆمپیوتەر (Ctrl+O)", self.open_files),
                              ("📋", "لکاندنی وێنە (Ctrl+V)", self.paste_image),
                              ("⚙", "ڕێکخستن", self.open_settings)):
            b = btn(text, cb=cb)
            b.setToolTip(tip)
            b.setFixedSize(46, 46)
            b.setStyleSheet("font-size:14pt;padding:0;")
            hl.addWidget(b)
        rv.addWidget(header)
        self.top_header = header

        self.prog = QProgressBar()
        self.prog.setRange(0, 0)
        self.prog.setTextVisible(False)
        self.prog.setFixedHeight(6)
        self.prog.hide()
        self.prog_lbl = QLabel("")
        self.prog_lbl.setStyleSheet(f"color:{MUTED};")
        self.prog_lbl.hide()
        rv.addWidget(self.prog)
        rv.addWidget(self.prog_lbl)

        # وەک مۆبایل: لابردن و داگرتن لە سەرەوە، وێنەکانی گەڕان لە خوارەوە
        split = QSplitter(Qt.Vertical)
        split.setChildrenCollapsible(False)
        split.setHandleWidth(8)
        self.split = split
        rv.addWidget(split, 1)

        # ── لای ڕاست: کار (سەرەکی، ئەنجام، بەکۆمەڵ) ──
        work_scroll = QScrollArea()
        work_scroll.setWidgetResizable(True)
        work_scroll.setFrameShape(QFrame.NoFrame)
        work_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        work = QWidget()
        self.work_v = QVBoxLayout(work)
        self.work_v.setContentsMargins(0, 0, 8, 4)
        self.work_v.setSpacing(12)
        work_scroll.setWidget(work)
        self.work_scroll = work_scroll

        # دوگمەکانی هەڵبژاردن (لە سەرەوە)
        self.sel_bar = QWidget()
        sb = QHBoxLayout(self.sel_bar)
        sb.setContentsMargins(0, 0, 0, 0)
        self.b_sel_rm = btn("", True, self.batch_from_selection)
        self.b_sel_dl = btn("", False, self.download_selected)
        sb.addWidget(self.b_sel_rm)
        sb.addWidget(self.b_sel_dl)
        self.work_v.addWidget(self.sel_bar)
        self.sel_bar.hide()

        # بەکۆمەڵ
        self.batch_card, bv, self.batch_title = card("بەکۆمەڵ")
        self.batch_prog = QProgressBar()
        self.batch_prog.setTextVisible(False)
        self.batch_prog.setFixedHeight(6)
        bv.addWidget(self.batch_prog)
        self.batch_status = QLabel("")
        bv.addWidget(self.batch_status)
        self.batch_grid = FlowGrid()
        bv.addWidget(self.batch_grid)
        brow = QHBoxLayout()
        self.b_save_all = btn("💾  پاشەکەوتی هەموو", True, self.save_all)
        brow.addWidget(self.b_save_all)
        self.b_stop = btn("وەستاندن", cb=self.stop_batch)
        self.b_retry = btn("دووبارە هەوڵدانەوە", cb=self.retry_failed)
        brow.addWidget(self.b_stop)
        brow.addWidget(self.b_retry)
        brow.addStretch(1)
        brow.addWidget(btn("سڕینەوەی لیست", cb=self.clear_batch))
        bv.addLayout(brow)
        self.work_v.addWidget(self.batch_card)
        self.batch_card.hide()

        # سەرەکی + ئەنجام بە لای یەکەوە
        pair = QHBoxLayout()
        pair.setSpacing(12)
        self.orig_card, ov, self.orig_title = card("وێنەی سەرەکی")
        self.orig_view = Checker(220)
        self.orig_card.setMinimumWidth(300)
        self.orig_view.checker = False
        self.orig_view.path_provider = self._drag_orig_path
        ov.addWidget(self.orig_view, 1)
        self.low_lbl = QLabel("⚠ ئەم ماڵپەڕە ڕێگەی بە داگرتنی وێنە ئەسڵییەکە نەدا؛ تەنها وێنە بچووکەکەی بەردەستە.")
        self.low_lbl.setStyleSheet(f"color:{ERR};")
        self.low_lbl.setWordWrap(True)
        self.low_lbl.hide()
        ov.addWidget(self.low_lbl)
        orow = QHBoxLayout()
        orow.addWidget(btn("⬇  داگرتنی ئەسڵی (بێ لابردن)", cb=self.save_original))
        orow.addWidget(btn("2×  Upscale", cb=lambda: self.upscale_from("original", 2)))
        self.b_redo = btn("↻  دووبارە", cb=self.remove_background)
        orow.addWidget(self.b_redo)
        ov.addLayout(orow)

        self.res_card, resv, self.res_title = card("ئەنجام — بێ باکگراوند")
        self.res_view = Checker(220)
        self.res_view.path_provider = self._drag_result_path
        self.res_card.setMinimumWidth(300)
        resv.addWidget(self.res_view, 1)
        crow = QHBoxLayout()
        crow.addWidget(QLabel("ڕەنگی باکگراوند:"))
        self.chips_box = QHBoxLayout()
        crow.addLayout(self.chips_box)
        crow.addStretch(1)
        resv.addLayout(crow)
        self.b_up = btn("Upscale ×2 بە AI", cb=lambda: self.upscale_from("cutout", 2))
        resv.addWidget(self.b_up)
        rrow = QHBoxLayout()
        rrow.addWidget(btn("💾  پاشەکەوت", True, self.save_result))
        rrow.addWidget(btn("📋  کۆپی", cb=self.copy_result))
        rrow.addWidget(btn("📂  فۆڵدەر", cb=self.open_save_dir))
        rrow.addWidget(btn("✕  داخستن", cb=self.close_editor))
        resv.addLayout(rrow)

        pair.addWidget(self.orig_card, 1)
        pair.addWidget(self.res_card, 1)
        self.pair_w = QWidget()
        self.pair_w.setLayout(pair)
        self.work_v.addWidget(self.pair_w)
        self.orig_card.hide()
        self.res_card.hide()

        # ── بەشی Upscale (جیا) ──
        self.up_card, uv, self.up_title = card("2×  Upscale — گەورەکردن بە AI")
        urow = QHBoxLayout()
        self.up_before = Checker(200)
        self.up_after = Checker(200)
        self.up_after.path_provider = self._drag_upscaled_path
        for lbl, view in (("پێش", self.up_before), ("دوای Upscale", self.up_after)):
            col = QVBoxLayout()
            t = QLabel(lbl)
            t.setAlignment(Qt.AlignCenter)
            t.setStyleSheet(f"color:{MUTED};")
            col.addWidget(t)
            col.addWidget(view, 1)
            urow.addLayout(col, 1)
        uv.addLayout(urow, 1)
        self.up_info = QLabel("")
        self.up_info.setWordWrap(True)
        self.up_info.setStyleSheet(f"color:{MUTED};")
        uv.addWidget(self.up_info)
        ubtn = QHBoxLayout()
        self.b_up2 = btn("2×  Upscale ×2", True, lambda: self.run_upscale(2))
        self.b_up4 = btn("4×  Upscale ×4", cb=lambda: self.run_upscale(4))
        ubtn.addWidget(self.b_up2)
        ubtn.addWidget(self.b_up4)
        ubtn.addWidget(btn("🖼  وێنەیەکی تر...", cb=self.open_for_upscale))
        ubtn.addStretch(1)
        self.b_up_save = btn("💾  پاشەکەوت", True, self.save_upscaled)
        self.b_up_copy = btn("📋  کۆپی", cb=self.copy_upscaled)
        ubtn.addWidget(self.b_up_save)
        ubtn.addWidget(self.b_up_copy)
        ubtn.addWidget(btn("✕", cb=self.close_upscale))
        uv.addLayout(ubtn)
        self.work_v.addWidget(self.up_card)
        self.up_card.hide()
        self.up_src: Optional[Image.Image] = None
        self.up_res: Optional[Image.Image] = None
        self.up_name = "image"

        self.hint = QLabel("ناوێک بنووسە و بگەڕێ، یان وێنەیەک بکەرەوە، ڕایبکێشە ناو پەنجەرەکە (Drag & Drop)، یان Ctrl+V.\n\n"
                           "✨ = لابردنی باکگراوند   ⬇ = داگرتن بە قەبارەی ئەسڵی   Ctrl+کلیک = هەڵبژاردنی چەند وێنەیەک\n"
                           "هەر وێنەیەک (ئەنجام یان گەڕان) ڕابکێشە ناو Photoshop، Premiere، Word یان هەر بەرنامەیەکی تر")
        self.hint.setAlignment(Qt.AlignCenter)
        self.hint.setStyleSheet(f"color:{MUTED};font-size:11.5pt;padding:14px;")
        self.work_v.addWidget(self.hint)
        self.work_v.addStretch(1)
        split.addWidget(work_scroll)

        # ── لای چەپ: ئەنجامەکانی گەڕان ──
        res_panel = QFrame()
        res_panel.setObjectName("panel")
        rp = QVBoxLayout(res_panel)
        rp.setContentsMargins(16, 12, 8, 8)
        self.res_head = QLabel("")
        self.res_head.setObjectName("cardTitle")
        self.res_head.setWordWrap(True)
        rp.addWidget(self.res_head)
        hrow = QHBoxLayout()
        self.b_selmode = btn("☑  هەڵبژاردنی چەند وێنەیەک", cb=lambda: self.set_select_mode(not self.select_mode))
        self.b_selall = btn("هەمووی", cb=self.select_all)
        self.b_rm_all = btn("✨  لابردنی باکگراوندی هەموو", cb=self.inplace_all)
        self.b_save_done = btn("", True, self.save_all_inplace)
        hrow.addWidget(self.b_selmode)
        hrow.addWidget(self.b_selall)
        hrow.addWidget(self.b_rm_all)
        hrow.addStretch(1)
        hrow.addWidget(self.b_save_done)
        rp.addLayout(hrow)
        self.grid_scroll = QScrollArea()
        self.grid_scroll.setWidgetResizable(True)
        self.grid_scroll.setFrameShape(QFrame.NoFrame)
        self.grid_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.grid_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOn)   # پانی جێگیر → خانەکان نابڕدرێن
        gw = QWidget()
        gv = QVBoxLayout(gw)
        gv.setContentsMargins(0, 0, 0, 0)
        self.grid = FlowGrid()
        gv.addWidget(self.grid)
        self.b_more = btn("وێنەی زیاتر", cb=self.load_more)
        gv.addWidget(self.b_more)
        self.src_lbl = QLabel("")
        self.src_lbl.setWordWrap(True)
        self.src_lbl.setStyleSheet(f"color:{MUTED};")
        gv.addWidget(self.src_lbl)
        gv.addStretch(1)
        self.grid_scroll.setWidget(gw)
        rp.addWidget(self.grid_scroll, 1)
        split.addWidget(res_panel)
        split.setStretchFactor(0, 1)
        split.setStretchFactor(1, 1)
        self.res_panel = res_panel
        self._update_results_ui()

        self.setStatusBar(QStatusBar())
        self._build_chips()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        QTimer.singleShot(0, self._relayout)
        QTimer.singleShot(0, self._fit_split)

    def _relayout(self):
        self.grid.relayout(self.grid_scroll.viewport().width() - 4, Tile.SIZE)
        self.batch_grid.relayout(self.work_scroll.viewport().width() - 40, 132)

    def message(self, text: str, ms=7000):
        self.statusBar().showMessage(text, ms)

    def set_busy(self, text: Optional[str]):
        self.busy = text is not None
        self.prog.setVisible(self.busy)
        self.prog_lbl.setVisible(self.busy)
        self.prog_lbl.setText(text or "")
        for b in (self.b_search, self.b_redo, self.b_up, self.b_up2, self.b_up4):
            b.setEnabled(not self.busy)

    def run(self, fn, on_done, *args, on_error=None, label="..."):
        t = Task(fn, *args)
        t.setAutoDelete(False)
        self._tasks.add(t)
        Q = Qt.QueuedConnection

        def finish():
            self._tasks.discard(t)
            self.set_busy(None)
        t.s.progress.connect(self._bridge.progress.emit, Q)
        t.s.done.connect(lambda r: (finish(), on_done(r)), Q)
        t.s.error.connect(lambda e: (finish(), (on_error or (lambda m: self.message("هەڵە: " + m, 10000)))(e)), Q)
        self.set_busy(label)
        self.pool.start(t)

    # ───── گەڕان ─────
    def submit(self):
        q = self.q.text().strip()
        if not q or self.busy:
            return
        if q.lower().startswith(("http://", "https://")):
            self.load_url(q, "", "")
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
            self._populate_grid()
            if not self.results:
                self.message("هیچ وێنەیەک نەدۆزرایەوە، ناوێکی تر تاقی بکەرەوە")
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
                self.message("وێنەی زیاتر نییە")
        self.run(work, done, label="هێنانی وێنەی زیاتر...")

    def _populate_grid(self):
        self.grid.clear()
        for r in self.results:
            self._add_tile(r)
        self._update_results_ui()
        self.grid_scroll.verticalScrollBar().setValue(0)

    def _add_tile(self, r: core.ImageResult):
        st = self.tstate.setdefault(r.full_url, TileState())
        t = Tile(r, st)
        t.select_mode = self.select_mode
        t.sel = r.full_url in self.selected
        t.update_sel()
        t.clicked.connect(self._tile_clicked)
        t.remove.connect(self.inplace)
        t.download.connect(self.download_one)
        t.upscale.connect(self.upscale_tile)
        t.save.connect(self.save_inplace)
        t.open_editor.connect(self.open_inplace)
        t.undo.connect(self.undo_inplace)
        t.context.connect(self._tile_menu)
        self.grid.add(t)
        if r.thumb_url in self.thumb_cache:
            t.set_pixmap(QPixmap.fromImage(self.thumb_cache[r.thumb_url]))
        else:
            self.thumb_pool.submit(self._load_thumb, r.thumb_url)

    def _load_thumb(self, url: str):
        try:
            b = core.http_get(url, timeout=30)
            im = Image.open(io.BytesIO(b))
            im.thumbnail((560, 560))
            self.thumb_ready.emit(url, pil_to_qimage(im))
        except Exception:  # noqa: BLE001
            pass

    def _on_thumb(self, url: str, img: QImage):
        self.thumb_cache[url] = img
        pm = QPixmap.fromImage(img)
        for t in self.grid.items:
            if t.r.thumb_url == url:
                t.set_pixmap(pm)

    def _tile_clicked(self, r, ctrl):
        if self.select_mode or ctrl:
            if not self.select_mode:
                self.set_select_mode(True)
            self.toggle_select(r)
        elif self.tstate.get(r.full_url, TileState()).status == "DONE":
            self.open_inplace(r)
        elif not self.busy:
            self.pick(r)

    def _tile_menu(self, r, pos):
        m = QMenu(self)
        m.setLayoutDirection(Qt.RightToLeft)
        st = self.tstate.get(r.full_url, TileState())
        if st.status == "DONE":
            m.addAction("💾  پاشەکەوتی وێنەی بێ باکگراوند", lambda: self.save_inplace(r))
            m.addAction("⤢  کردنەوە لە بەشی سەرەوە", lambda: self.open_inplace(r))
            m.addAction("↺  گەڕانەوە بۆ ئەسڵی", lambda: self.undo_inplace(r))
        else:
            m.addAction("✨  لابردنی باکگراوند لێرە", lambda: self.inplace(r))
        m.addAction("🖼  کردنەوە لە بەشی سەرەوە (لابردن + ڕەنگ)", lambda: self.pick(r))
        m.addAction("⬇  داگرتن بەبێ لابردنی باکگراوند", lambda: self.download_one(r))
        m.addAction("2×  Upscale ×2 بە AI", lambda: self.upscale_tile(r))
        m.addAction("☑  هەڵبژاردن", lambda: (self.set_select_mode(True), self.toggle_select(r)))
        if r.page_url:
            m.addAction("🌐  کردنەوەی پەڕەی سەرچاوە", lambda: webbrowser.open(r.page_url))
        m.addAction("🔗  کۆپیکردنی لینکی وێنە", lambda: QGuiApplication.clipboard().setText(r.full_url))
        m.exec(pos)

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
        self.selected = {r.full_url for r in self.results}
        self.set_select_mode(True)

    def _update_results_ui(self):
        has = bool(self.results)
        self.res_panel.setVisible(True)
        if self.select_mode:
            self.res_head.setText(f"{len(self.selected)} وێنە هەڵبژێردراوە")
        elif has:
            self.res_head.setText(f"{len(self.results)} وێنە  ·  ✨ لابردن لە شوێنی خۆی  ·  ⬇ داگرتن  ·  2× Upscale  ·  "
                                  "ڕاکێشان بۆ بەرنامەکانی تر")
        else:
            self.res_head.setText("ئەنجامەکانی گەڕان لێرە دەردەکەون")
        self.b_selmode.setText("هەڵوەشاندنەوە" if self.select_mode else "☑  هەڵبژاردنی چەند وێنەیەک")
        self.b_selmode.setVisible(has)
        self.b_selall.setVisible(has and self.select_mode)
        self.b_rm_all.setVisible(has and not self.select_mode)
        ndone = sum(1 for r in self.results if self.tstate.get(r.full_url, TileState()).status == "DONE")
        self.b_save_done.setText(f"💾  پاشەکەوتی {ndone} وێنەی ئامادە")
        self.b_save_done.setVisible(ndone > 0)
        self.b_more.setVisible(has and self.can_more)
        self.src_lbl.setVisible(has and self.source == "free")
        self.src_lbl.setText("سەرچاوە: ویکیپیدیا، Wikimedia Commons، Openverse. بۆ ئەنجامی ڕاستەوخۆی گۆگڵ، کلیلی Serper لە ⚙ دابنێ.")
        n = len(self.selected)
        self.sel_bar.setVisible(self.select_mode and n > 0)
        self.b_sel_rm.setText(f"✨  لابردنی باکگراوندی {n} وێنە")
        self.b_sel_dl.setText(f"⬇  داگرتنی {n} وێنە بەبێ لابردنی باکگراوند")
        QTimer.singleShot(0, self._relayout)
        QTimer.singleShot(0, self._fit_split)

    # ───── لابردنی باکگراوند «لە شوێنی خۆی» لەسەر هەر وێنەیەک ─────
    def _tiles_for(self, url: str):
        return [t for t in self.grid.items if t.r.full_url == url]

    def _on_tile_changed(self, url: str):
        for t in self._tiles_for(url):
            t.refresh()
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

    def inplace(self, r: core.ImageResult):
        st = self.tstate.setdefault(r.full_url, TileState())
        if st.status in ("WAITING", "WORKING", "DONE"):
            return
        st.status, st.msg = "WAITING", ""
        self.tile_changed.emit(r.full_url)
        opts = self.cut_opts()
        enhance = self.settings.auto_enhance
        self.inplace_pool.submit(self._inplace_job, r, st, opts, enhance)

    def inplace_all(self):
        n = 0
        for r in self.results:
            if self.tstate.get(r.full_url, TileState()).status in ("", "ERROR"):
                self.tstate.setdefault(r.full_url, TileState()).status = ""
                self.inplace(r)
                n += 1
        self.message(f"{n} وێنە خرانە ڕیز")

    def _inplace_job(self, r: core.ImageResult, st: TileState, opts, enhance: bool):
        import hashlib
        url = r.full_url
        last = [0.0]

        def prog(m: str):
            st.msg = m.replace("داگرتنی مۆدێلی", "مۆدێل")[:48]
            if time.time() - last[0] > 0.25:
                last[0] = time.time()
                self.tile_changed.emit(url)
        try:
            st.status = "WORKING"
            prog("داگرتنی وێنە بە قەبارەی تەواو...")
            self.tile_changed.emit(url)
            b, _ = core.fetch_image_bytes(url, r.thumb_url, r.page_url)
            src = core.open_image(b)
            out, _ = core.remove_background(src, opts, prog)
            enhanced = False
            if enhance and max(out.size) < 1800:
                try:
                    out = core.upscale_image(out, 2, lambda m: prog(m.replace("Upscale ×2 بە AI", "بەرزکردنەوەی کوالیتی")))
                    enhanced = True
                except Exception:  # noqa: BLE001
                    pass
            d = core.data_dir() / "inplace"
            d.mkdir(parents=True, exist_ok=True)
            h = hashlib.md5(url.encode("utf-8")).hexdigest()[:16]
            f = d / f"{h}.png"
            out.save(f)
            of = d / f"{h}_orig.{core.sniff_ext(b) or 'png'}"
            of.write_bytes(b)
            st.file, st.orig, st.size, st.enhanced = str(f), str(of), out.size, enhanced
            st.thumb = pil_to_qimage(thumb(out, 560))
            st.status, st.msg = "DONE", ""
        except Exception as e:  # noqa: BLE001
            st.status, st.msg = "ERROR", (str(e) or "هەڵە")[:50]
        self.tile_changed.emit(url)

    def _inplace_name(self, r: core.ImageResult) -> str:
        return core.safe_filename(Path(urllib_unquote(r.full_url.split("?")[0])).stem or r.title or "image")

    def save_inplace(self, r: core.ImageResult, quiet=False) -> Optional[Path]:
        st = self.tstate.get(r.full_url)
        if not st or st.status != "DONE":
            return None
        p = unique(self.save_dir() / f"{self._inplace_name(r)}_nobg.png")
        try:
            import shutil
            shutil.copyfile(st.file, p)
        except Exception as e:  # noqa: BLE001
            self.message(f"پاشەکەوت سەرنەکەوت: {e}")
            return None
        if not quiet:
            self.message(f"✔ پاشەکەوت کرا: {p}", 10000)
            if not self.dl_shown_folder:
                self.dl_shown_folder = True
                reveal_in_folder(p)
        return p

    def save_all_inplace(self):
        done = [r for r in self.results if self.tstate.get(r.full_url, TileState()).status == "DONE"]
        last = None
        for r in done:
            last = self.save_inplace(r, quiet=True) or last
        if last:
            self.message(f"✔ {len(done)} وێنە پاشەکەوت کران لە {last.parent}", 12000)
            reveal_in_folder(last)

    def open_inplace(self, r: core.ImageResult):
        st = self.tstate.get(r.full_url)
        if not st or st.status != "DONE" or self.busy:
            return
        try:
            raw = Path(st.orig).read_bytes()
            self.original, self.orig_bytes = core.open_image(raw), raw
            self.orig_name = self._inplace_name(r)
            self.cutout = Image.open(st.file).convert("RGBA")
            self.low_quality = False
            self.upscaled = st.enhanced
            self._show_work()
        except Exception as e:  # noqa: BLE001
            self.message(f"نەتوانرا بکرێتەوە: {e}")

    def undo_inplace(self, r: core.ImageResult):
        st = self.tstate.get(r.full_url)
        if st and st.status == "DONE":
            self.tstate[r.full_url] = TileState()
            for t in self._tiles_for(r.full_url):
                t.state = self.tstate[r.full_url]
            self.tile_changed.emit(r.full_url)

    # ───── یەک وێنە ─────
    def pick(self, r: core.ImageResult):
        if self.busy:
            return
        self.load_url(r.full_url, r.thumb_url, r.page_url, r.title)

    def load_url(self, url, fallback, referer, title=""):
        def work(progress):
            progress("١/٢ داگرتنی وێنە بە قەبارە و کوالیتی تەواو...")
            b, low = core.fetch_image_bytes(url, fallback, referer)
            return b, low

        def done(res):
            b, low = res
            name = Path(urllib_unquote(url.split("?")[0])).stem or "image"
            self.set_image(core.open_image(b), b, name, low)
        self.run(work, done, label="١/٢ داگرتنی وێنە بە قەبارە و کوالیتی تەواو...",
                 on_error=lambda e: self.message("ئەم وێنەیە دانابەزێت، یەکێکی تر هەڵبژێرە"))

    def set_image(self, im: Image.Image, raw: Optional[bytes], name: str, low=False):
        self.original, self.orig_bytes, self.orig_name = im, raw, core.safe_filename(name)
        self.low_quality = low
        self.cutout = None
        self.upscaled = False
        self._show_work()
        self.remove_background()

    def _show_work(self):
        has_o, has_c = self.original is not None, self.cutout is not None
        self.orig_card.setVisible(has_o)
        self.res_card.setVisible(has_c or has_o)
        self.hint.setVisible(not (has_o or has_c or self.batch or self.up_src is not None))
        if has_o:
            self.orig_title.setText(f"وێنەی سەرەکی — {dims(self.original.width, self.original.height)} پیکسڵ")
            self.orig_view.set_image(pil_to_qimage(thumb(self.original, 1400)))
        self.low_lbl.setVisible(has_o and self.low_quality)
        if has_c:
            self.res_title.setText(f"ئەنجام — بێ باکگراوند ({dims(self.cutout.width, self.cutout.height)})"
                                   + ("  ✨ Upscale" if self.upscaled else ""))
            self.res_view.set_image(pil_to_qimage(thumb(self.cutout, 1400)))
        else:
            self.res_title.setText("ئەنجام — چاوەڕێ بکە...")
            self.res_view.set_image(None)
        self.res_view.bg = self.bg
        self.res_view.update()
        self.b_up.setVisible(has_c)
        self.work_scroll.verticalScrollBar().setValue(0)
        self._fit_split()

    def _fit_split(self):
        """کاتێک وێنەیەک یان بەکۆمەڵ هەیە بەشی سەرەوە گەورە دەبێت؛ ئەگەرنا بچووک."""
        total = max(400, self.split.height())
        has_up = getattr(self, "up_src", None) is not None
        busy_top = (self.original is not None or self.cutout is not None or bool(self.batch)
                    or self.sel_bar.isVisible() or has_up)
        if busy_top:
            want = int(total * 0.62) if (self.original is not None or self.batch or has_up) else 90
        else:
            want = 150 if self.results else int(total * 0.5)
        self.work_scroll.setVisible(busy_top or not self.results)
        key = (busy_top, self.original is not None, bool(self.batch), bool(self.results), has_up, total)
        if getattr(self, "_split_key", None) != key:
            self._split_key = key
            self.split.setSizes([want, total - want])

    def cut_opts(self) -> core.CutOptions:
        s = self.settings
        return core.CutOptions(s.person_only, s.focus_only, s.engine, s.removebg_key)

    def remove_background(self):
        src = self.original
        if src is None or self.busy:
            return
        opts = self.cut_opts()

        def work(progress):
            return core.remove_background(src, opts, progress)

        def done(res):
            self.cutout, note = res
            self.upscaled = False
            self._show_work()
            if note:
                self.message(note)
        self.run(work, done, label=f"٢/٢ لابردنی باکگراوند ({dims(src.width, src.height)})...")

    # ───── Upscale (بەشی جیا) ─────
    def _set_up_source(self, im: Image.Image, name: str):
        self.up_src, self.up_res, self.up_name = im, None, core.safe_filename(name)
        self.up_before.bg = None
        self.up_before.checker = im.mode == "RGBA"
        self.up_before.set_image(pil_to_qimage(thumb(im, 1000)))
        self.up_after.set_image(None)
        self.up_info.setText(f"وێنە: {dims(im.width, im.height)} پیکسڵ — دوگمەی 2× یان 4× دابگرە")
        self._refresh_up_ui()
        self.hint.hide()
        self.up_card.show()
        self._split_key = None
        self._fit_split()
        QTimer.singleShot(50, lambda: self.work_scroll.ensureWidgetVisible(self.up_card))

    def _refresh_up_ui(self):
        has = self.up_res is not None
        self.b_up_save.setEnabled(has)
        self.b_up_copy.setEnabled(has)

    def upscale_from(self, what: str, scale: int):
        if self.busy:
            return
        if what == "cutout" and self.cutout is not None:
            self._set_up_source(self.final_image(self.cutout), f"{self.orig_name}_nobg")
        elif what == "original" and self.original is not None:
            self._set_up_source(self.original, self.orig_name)
        else:
            return
        self.run_upscale(scale)

    def upscale_tile(self, r: core.ImageResult):
        if self.busy:
            return

        def work(progress):
            progress("داگرتنی وێنەکە بە قەبارەی تەواو...")
            b, _ = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
            return b

        def done(b):
            name = Path(urllib_unquote(r.full_url.split("?")[0])).stem or r.title or "image"
            im = Image.open(io.BytesIO(b))
            from PIL import ImageOps
            im = ImageOps.exif_transpose(im)
            im = im.convert("RGBA") if im.mode in ("RGBA", "LA", "P") and ("A" in im.getbands() or "transparency" in im.info) else im.convert("RGB")
            self._set_up_source(im, name)
            self.run_upscale(2)
        self.run(work, done, label="داگرتنی وێنەکە بۆ Upscale...",
                 on_error=lambda e: self.message(f"ئەم وێنەیە دانابەزێت ({e})", 10000))

    def open_for_upscale(self):
        f, _ = QFileDialog.getOpenFileName(self, "وێنەیەک بۆ Upscale", str(self.save_dir()),
                                           "وێنە (*.png *.jpg *.jpeg *.webp *.bmp *.gif *.tif *.tiff *.avif *.heic)")
        if not f:
            return
        try:
            from PIL import ImageOps
            im = ImageOps.exif_transpose(Image.open(f))
            im = im.convert("RGBA") if ("A" in im.getbands() or "transparency" in im.info) else im.convert("RGB")
        except Exception as e:  # noqa: BLE001
            self.message(f"نەتوانرا وێنەکە بکرێتەوە: {e}")
            return
        self._set_up_source(im, Path(f).stem)

    def run_upscale(self, scale: int):
        if self.up_src is None or self.busy:
            return
        src = self.up_src

        def work(progress):
            return core.upscale_image(src, scale, progress)

        def done(up):
            self.up_res = up
            self.up_scale = scale
            self.up_after.checker = up.mode == "RGBA"
            self.up_after.set_image(pil_to_qimage(thumb(up, 1400)))
            self.up_info.setText(f"✔ Upscale ×{scale}: {dims(src.width, src.height)} → {dims(up.width, up.height)} پیکسڵ — "
                                 "پاشەکەوتی بکە یان ڕایبکێشە ناو هەر بەرنامەیەک")
            self._refresh_up_ui()
            self.message(f"Upscale کرا: {dims(src.width, src.height)} → {dims(up.width, up.height)}", 10000)
        self.run(work, done, label=f"Upscale ×{scale} بە AI...",
                 on_error=lambda e: self.message(f"Upscale سەرنەکەوت: {e}", 12000))

    def _upscaled_name(self) -> str:
        return f"{self.up_name}_x{getattr(self, 'up_scale', 2)}"

    def save_upscaled(self):
        if self.up_res is None:
            return
        default = self.save_dir() / f"{self._upscaled_name()}.png"
        path, _ = QFileDialog.getSaveFileName(self, "پاشەکەوتکردن", str(default), "PNG (*.png);;JPEG (*.jpg)")
        if not path:
            return
        try:
            if Path(path).suffix.lower() in (".jpg", ".jpeg"):
                core.with_background(self.up_res, (255, 255, 255)).convert("RGB").save(path, quality=98, subsampling=0) \
                    if self.up_res.mode == "RGBA" else self.up_res.save(path, quality=98, subsampling=0)
            else:
                if Path(path).suffix.lower() != ".png":
                    path += ".png"
                self.up_res.save(path)
        except Exception as e:  # noqa: BLE001
            self.message(f"پاشەکەوت سەرنەکەوت: {e}", 10000)
            return
        self.message(f"✔ پاشەکەوت کرا: {path}", 12000)

    def copy_upscaled(self):
        if self.up_res is not None:
            QGuiApplication.clipboard().setImage(pil_to_qimage(self.up_res))
            self.message("کۆپی کرا")

    def _drag_upscaled_path(self) -> Optional[Path]:
        if self.up_res is None:
            return None
        p = drag_dir() / f"{self._upscaled_name()}.png"
        if getattr(self, "_up_drag_key", None) != id(self.up_res) or not p.exists():
            self.up_res.save(p)
            self._up_drag_key = id(self.up_res)
        return p

    def close_editor(self):
        if self.busy:
            return
        self.original = self.cutout = None
        self.orig_bytes = None
        self.upscaled = False
        self._show_work()
        self._split_key = None
        self._fit_split()

    def close_upscale(self):
        self.up_src = self.up_res = None
        self.up_card.hide()
        self._show_work()

    def _build_chips(self):
        while self.chips_box.count():
            w = self.chips_box.takeAt(0).widget()
            if w:
                w.deleteLater()
        for c in COLORS:
            self.chips_box.addWidget(color_chip(c, self.bg == c, lambda _=False, cc=c: self.set_bg(cc)))
        custom = self.bg is not None and self.bg not in COLORS
        b = QToolButton()
        b.setText("🎨")
        b.setToolTip("ڕەنگی تر...")
        b.setFixedSize(34, 34)
        b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{'3px solid ' + ACCENT2 if custom else '1px solid ' + LINE};"
                        f"background:{'rgb' + str(self.bg) if custom else 'white'};}}")
        b.clicked.connect(self.pick_color)
        self.chips_box.addWidget(b)

    def pick_color(self):
        c = QColorDialog.getColor(QColor(*(self.bg or (255, 255, 255))), self, "ڕەنگی باکگراوند")
        if c.isValid():
            self.set_bg((c.red(), c.green(), c.blue()))

    def set_bg(self, c):
        self.bg = c
        self.res_view.bg = c
        self.res_view.update()
        self._build_chips()
        for it in self.batch:
            self._refresh_batch_tile(it.id)

    def final_image(self, cut: Image.Image) -> Image.Image:
        return core.with_background(cut, self.bg)

    def save_dir(self) -> Path:
        p = Path(self.settings.save_dir) if self.settings.save_dir else core.default_save_dir()
        p.mkdir(parents=True, exist_ok=True)
        return p

    def save_result(self):
        if self.cutout is None:
            return
        default = self.save_dir() / f"{self.orig_name}_nobg.png"
        path, _ = QFileDialog.getSaveFileName(self, "پاشەکەوتکردن", str(default),
                                              "PNG (*.png);;JPEG (*.jpg);;WEBP (*.webp)")
        if not path:
            return
        img = self.final_image(self.cutout)
        ext = Path(path).suffix.lower()
        try:
            if ext in (".jpg", ".jpeg"):
                core.with_background(self.cutout, self.bg or (255, 255, 255)).convert("RGB").save(path, quality=100, subsampling=0)
            elif ext == ".webp":
                img.save(path, lossless=True)
            else:
                if ext != ".png":
                    path += ".png"
                img.save(path)
        except Exception as e:  # noqa: BLE001
            self.message(f"پاشەکەوت سەرنەکەوت: {e}", 10000)
            return
        self.message(f"✔ پاشەکەوت کرا: {path}", 12000)

    # ───── ڕاکێشان بۆ بەرنامەکانی تر ─────
    def _drag_result_path(self) -> Optional[Path]:
        if self.cutout is None:
            return None
        key = (id(self.cutout), self.bg)
        if getattr(self, "_drag_cache_key", None) == key and self._drag_cache_path.exists():
            return self._drag_cache_path
        p = drag_dir() / f"{core.safe_filename(self.orig_name)}_nobg.png"
        try:
            self.final_image(self.cutout).save(p)
        except Exception as e:  # noqa: BLE001
            self.message(f"ڕاکێشان سەرنەکەوت: {e}")
            return None
        self._drag_cache_key, self._drag_cache_path = key, p
        return p

    def _drag_orig_path(self) -> Optional[Path]:
        if self.original is None:
            return None
        d = drag_dir() / "original"
        for f in d.glob("*"):
            f.unlink(missing_ok=True)
        try:
            if self.orig_bytes and core.sniff_ext(self.orig_bytes):
                return core.save_downloaded(self.orig_bytes, d, self.orig_name)
            d.mkdir(parents=True, exist_ok=True)
            p = d / f"{core.safe_filename(self.orig_name)}.png"
            self.original.save(p)
            return p
        except Exception as e:  # noqa: BLE001
            self.message(f"ڕاکێشان سەرنەکەوت: {e}")
            return None

    def _drag_tile_path(self, r: core.ImageResult) -> Optional[Path]:
        """وێنە ئەسڵییەکە (قەبارەی تەواو) دادەبەزێنێت ئینجا ڕایدەکێشێت."""
        cache = self._tile_drag_cache.get(r.full_url)
        if cache and cache.exists():
            return cache
        box: dict = {}

        def job():
            try:
                b, _ = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
                name = Path(urllib_unquote(r.full_url.split("?")[0])).stem or r.title or "image"
                box["p"] = core.save_downloaded(b, drag_dir() / "web", name)
            except Exception as e:  # noqa: BLE001
                box["e"] = str(e)
        fut = self.dl_pool.submit(job)
        QApplication.setOverrideCursor(QCursor(Qt.WaitCursor))
        self.message("داگرتنی وێنەکە بە قەبارەی تەواو بۆ ڕاکێشان...", 30000)
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
        self.message("✔ ئێستا ڕایبکێشە ناو بەرنامەکە", 5000)
        self._tile_drag_cache[r.full_url] = box["p"]
        return box["p"]

    def copy_result(self):
        if self.cutout is not None:
            QGuiApplication.clipboard().setImage(pil_to_qimage(self.final_image(self.cutout)))
            self.message("کۆپی کرا — دەتوانیت لە هەر بەرنامەیەکدا Paste ی بکەیت")

    def open_save_dir(self):
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.save_dir())))

    def save_original(self):
        if self.original is None:
            return
        d = self.save_dir() / "Original"
        d.mkdir(exist_ok=True)
        try:
            if self.orig_bytes and core.sniff_ext(self.orig_bytes):
                p = core.save_downloaded(self.orig_bytes, d, self.orig_name)
            else:
                p = unique(d / f"{core.safe_filename(self.orig_name)}.png")
                self.original.save(p)
        except Exception as e:  # noqa: BLE001
            self.message(f"پاشەکەوت سەرنەکەوت: {e}", 10000)
            return
        self.message(f"✔ پاشەکەوت کرا: {p}", 12000)
        reveal_in_folder(p)

    # ───── داگرتن بەبێ لابردن ─────
    def download_one(self, r: core.ImageResult):
        if r.full_url in self.downloading:
            return
        self.downloading.add(r.full_url)
        self._tile_loading(r.full_url, True)
        d = self.save_dir() / "Original"

        def job():
            try:
                b, low = core.fetch_image_bytes(r.full_url, r.thumb_url, r.page_url)
                name = Path(urllib_unquote(r.full_url.split("?")[0])).stem or r.title or "image"
                p = core.save_downloaded(b, d, name)
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
        self.message(f"✔ دابەزی ({self.dl_count}): {path}" + ("  — ماڵپەڕەکە تەنها وێنەی بچووکی دا" if low else ""), 12000)
        # یەکەم جار فۆڵدەرەکە دەکاتەوە بۆ ئەوەی بزانیت وێنەکان لە کوێن
        if not self.dl_shown_folder:
            self.dl_shown_folder = True
            reveal_in_folder(Path(path))

    def download_selected(self):
        picked = [r for r in self.results if r.full_url in self.selected]
        self.set_select_mode(False)
        for r in picked:
            self.download_one(r)
        self.message(f"داگرتنی {len(picked)} وێنە دەستی پێکرد › {self.save_dir() / 'Original'}")

    # ───── کردنەوەی فایل / لکاندن / ڕاکێشان ─────
    def open_files(self):
        files, _ = QFileDialog.getOpenFileNames(self, "کردنەوەی وێنە", str(Path.home() / "Pictures"),
                                                "وێنە (*.png *.jpg *.jpeg *.webp *.bmp *.gif *.tif *.tiff *.avif *.heic)")
        self.open_paths(files)

    def open_paths(self, files: list[str]):
        files = [f for f in files if Path(f).is_file()]
        if not files:
            return
        if len(files) == 1:
            try:
                raw = Path(files[0]).read_bytes()
                self.set_image(core.open_image(raw), raw, Path(files[0]).stem)
            except Exception:  # noqa: BLE001
                self.message("نەتوانرا وێنەکە بکرێتەوە")
        else:
            for f in files:
                self.batch.append(BatchItem(self.next_id, path=f))
                self.next_id += 1
            self.run_batch()

    def paste_image(self):
        if self.focusWidget() is self.q and QGuiApplication.clipboard().mimeData().hasText() \
                and not QGuiApplication.clipboard().mimeData().hasImage():
            self.q.paste()
            return
        md = QGuiApplication.clipboard().mimeData()
        if md.hasImage():
            qimg = QGuiApplication.clipboard().image().convertToFormat(QImage.Format_RGBA8888)
            ptr = qimg.constBits()
            im = Image.frombuffer("RGBA", (qimg.width(), qimg.height()), bytes(ptr), "raw", "RGBA",
                                  qimg.bytesPerLine(), 1).convert("RGB")
            self.set_image(im, None, "pasted")
        elif md.hasUrls():
            self.open_paths([u.toLocalFile() for u in md.urls() if u.isLocalFile()])
        elif md.hasText() and md.text().strip().lower().startswith("http"):
            self.q.setText(md.text().strip())
            self.submit()

    def dragEnterEvent(self, e):
        if e.source() is not None:   # ڕاکێشان لەناو خودی بەرنامەکەوە
            return
        if e.mimeData().hasUrls() or e.mimeData().hasImage():
            e.acceptProposedAction()

    def dropEvent(self, e):
        md = e.mimeData()
        local = [u.toLocalFile() for u in md.urls() if u.isLocalFile()]
        if local:
            self.open_paths(local)
        else:
            web = [u.toString() for u in md.urls() if u.scheme().startswith("http")]
            if web:
                self.load_url(web[0], "", "")

    # ───── بەکۆمەڵ ─────
    def batch_from_selection(self):
        picked = [r for r in self.results if r.full_url in self.selected]
        self.set_select_mode(False)
        for r in picked:
            self.inplace(r)
        self.message(f"{len(picked)} وێنە خرانە ڕیز — هەر یەکە لە شوێنی خۆی باکگراوندی لادەبرێت")

    def _batch_dir(self) -> Path:
        p = core.data_dir() / "batch"
        p.mkdir(exist_ok=True)
        return p

    def _rebuild_batch_grid(self):
        self.batch_grid.clear()
        self.batch_tiles = {}
        for it in self.batch:
            t = BatchTile(it, self.open_batch_item)
            self.batch_tiles[it.id] = t
            self.batch_grid.add(t)
            self._refresh_batch_tile(it.id)
        QTimer.singleShot(0, self._relayout)
        QTimer.singleShot(0, self._fit_split)

    def _refresh_batch_tile(self, bid: int):
        it = next((b for b in self.batch if b.id == bid), None)
        t = self.batch_tiles.get(bid)
        if it is None or t is None:
            return
        if it.status == "DONE" and bid not in self.batch_thumbs and it.file and Path(it.file).exists():
            try:
                self.batch_thumbs[bid] = pil_to_qimage(thumb(Image.open(it.file), 264))
            except Exception:  # noqa: BLE001
                pass
        t.refresh(self.batch_thumbs.get(bid), self.bg)
        done = sum(b.status == "DONE" for b in self.batch)
        failed = sum(b.status == "ERROR" for b in self.batch)
        self.batch_title.setText(f"بەکۆمەڵ: {done} لە {len(self.batch)} تەواو بوو" + (f" ({failed} سەرنەکەوت)" if failed else ""))
        self.batch_prog.setMaximum(max(1, len(self.batch)))
        self.batch_prog.setValue(done + failed)
        self.b_save_all.setEnabled(done > 0)
        self.b_stop.setVisible(self.batch_running)
        self.b_retry.setVisible(not self.batch_running and failed > 0)

    def run_batch(self):
        self.batch_card.setVisible(bool(self.batch))
        self.hint.hide()
        self._rebuild_batch_grid()
        if self.batch_running:
            return
        self.batch_running = True
        self.batch_stop = False
        opts = self.cut_opts()
        bdir = self._batch_dir()

        def worker():
            while not self.batch_stop:
                it = next((b for b in self.batch if b.status == "WAITING"), None)
                if it is None:
                    break
                n_done = sum(b.status in ("DONE", "ERROR") for b in self.batch)
                prefix = f"وێنەی {n_done + 1} لە {len(self.batch)}"
                it.status = "WORKING"
                self.batch_update.emit(it.id)
                self._batch_msg = prefix
                try:
                    if it.path:
                        src = core.open_image(it.path)
                    else:
                        b, _ = core.fetch_image_bytes(it.url, it.fallback, it.referer)
                        src = core.open_image(b)
                    out, _ = core.remove_background(src, opts, lambda s, p=prefix: self._set_batch_status(f"{p} — {s}"))
                    f = bdir / f"cut_{it.id}.png"
                    out.save(f)
                    it.file, it.status = str(f), "DONE"
                except Exception as e:  # noqa: BLE001
                    it.status, it.error = "ERROR", (str(e) or "هەڵە")[:60]
                self.batch_update.emit(it.id)
            for b in self.batch:
                if b.status == "WORKING":
                    b.status = "WAITING"
            ok = sum(b.status == "DONE" for b in self.batch)
            self.batch_finished.emit(f"تەواو بوو: {ok} لە {len(self.batch)} وێنە")

        self.batch_status.setText("دەستپێکردن...")
        self.thumb_pool.submit(worker)

    def _set_batch_status(self, s: str):
        QTimer.singleShot(0, lambda: self.batch_status.setText(s))

    def _on_batch_finished(self, msg):
        self.batch_running = False
        self.batch_status.setText("")
        for it in self.batch:
            self._refresh_batch_tile(it.id)
        self.message(msg)

    def stop_batch(self):
        self.batch_stop = True

    def retry_failed(self):
        for b in self.batch:
            if b.status == "ERROR":
                b.status, b.error = "WAITING", ""
        self.run_batch()

    def clear_batch(self):
        self.batch_stop = True
        for b in self.batch:
            if b.file:
                Path(b.file).unlink(missing_ok=True)
        self.batch = []
        self.batch_thumbs = {}
        self._rebuild_batch_grid()
        self.batch_card.hide()
        self._show_work()

    def open_batch_item(self, it: BatchItem):
        try:
            self.cutout = Image.open(it.file).convert("RGBA")
            self.original = None
            self.orig_bytes = None
            self.orig_name = f"batch_{it.id}"
            self.upscaled = False
            self._show_work()
        except Exception:  # noqa: BLE001
            pass

    def save_all(self):
        files = [b for b in self.batch if b.status == "DONE" and b.file]
        if not files:
            return
        d = QFileDialog.getExistingDirectory(self, "فۆڵدەری پاشەکەوت", str(self.save_dir()))
        if not d:
            return
        n = 0
        for b in files:
            try:
                img = self.final_image(Image.open(b.file).convert("RGBA"))
                img.save(unique(Path(d) / f"bg_{b.id}.png"))
                n += 1
            except Exception:  # noqa: BLE001
                pass
        self.message(f"{n} وێنە پاشەکەوت کرا لە {d}")

    # ───── ڕێکخستن ─────
    def open_settings(self):
        dlg = SettingsDialog(self, self.settings)
        if dlg.exec():
            self.settings = dlg.result_settings()
            self.settings.save()
            self.message("ڕێکخستن پاشەکەوت کرا")

    # ───── پاشەکەوت/گەڕاندنەوەی دۆخ ─────
    def _state_dir(self) -> Path:
        p = core.data_dir() / "state"
        p.mkdir(exist_ok=True)
        return p

    def closeEvent(self, e):
        self.batch_stop = True
        try:
            d = self._state_dir()
            (d / "state.json").write_text(json.dumps({"geometry": [self.x(), self.y(), self.width(), self.height()]}), "utf-8")
        except Exception:  # noqa: BLE001
            pass
        self.thumb_pool.shutdown(wait=False, cancel_futures=True)
        super().closeEvent(e)

    def fresh_start(self):
        import shutil
        d = self._state_dir()
        try:
            g = json.loads((d / "state.json").read_text("utf-8")).get("geometry")
            if g:
                self.setGeometry(*g)
        except Exception:  # noqa: BLE001
            pass
        shutil.rmtree(d, ignore_errors=True)
        shutil.rmtree(core.data_dir() / "batch", ignore_errors=True)
        shutil.rmtree(core.data_dir() / "drag", ignore_errors=True)
        shutil.rmtree(core.data_dir() / "inplace", ignore_errors=True)
        self._build_chips()
        self._populate_grid()
        self._show_work()

    def restore_state(self):
        d = self._state_dir()
        try:
            st = json.loads((d / "state.json").read_text("utf-8"))
        except Exception:  # noqa: BLE001
            self._show_work()
            return
        try:
            g = st.get("geometry")
            if g:
                self.setGeometry(*g)
            self.q.setText(st.get("query", ""))
            self.last_query, self.page = st.get("last_query", ""), st.get("page", 1)
            self.source, self.can_more = st.get("source", ""), st.get("can_more", False)
            bg = st.get("bg")
            self.bg = tuple(bg) if bg else None
            self.results = [core.ImageResult(**r) for r in st.get("results", [])]
            self.orig_name = st.get("orig_name", "image")
            self.low_quality = st.get("low", False)
            if st.get("has_orig") and (d / "original.png").exists():
                self.original = Image.open(d / "original.png").convert("RGB")
                raw = d / "original.raw"
                self.orig_bytes = raw.read_bytes() if raw.exists() else None
            if st.get("has_cut") and (d / "cutout.png").exists():
                self.cutout = Image.open(d / "cutout.png").convert("RGBA")
                self.upscaled = st.get("upscaled", False)
            self.next_id = st.get("next_id", 1)
            self.batch = [BatchItem(**b) for b in st.get("batch", [])]
            for b in self.batch:
                if b.status == "DONE" and not Path(b.file).exists():
                    b.status = "WAITING"
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        self._build_chips()
        self._populate_grid()
        self._show_work()
        if self.batch:
            self.batch_card.show()
            self._rebuild_batch_grid()
            if any(b.status == "WAITING" for b in self.batch):
                self.run_batch()


def urllib_unquote(s: str) -> str:
    import urllib.parse
    return urllib.parse.unquote(s)


def reveal_in_folder(p: Path):
    """فۆڵدەرەکە دەکاتەوە و فایلەکە دیاری دەکات."""
    try:
        if sys.platform == "win32":
            import subprocess
            subprocess.Popen(["explorer", "/select,", str(p)])
        else:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(p.parent)))
    except Exception:  # noqa: BLE001
        pass


def unique(p: Path) -> Path:
    if not p.exists():
        return p
    i = 1
    while True:
        q = p.with_name(f"{p.stem} ({i}){p.suffix}")
        if not q.exists():
            return q
        i += 1


def logo_path() -> Optional[Path]:
    """لۆگۆی SG search (هەمان وێنەی ئایکۆن)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    for p in (base / "bgremover" / "assets" / "logo.png", Path(__file__).resolve().parent / "assets" / "logo.png"):
        if p.exists():
            return p
    return None


def make_icon() -> QIcon:
    p = logo_path()
    if p is not None:
        ic = QIcon(str(p))
        if not ic.isNull():
            return ic
    from PySide6.QtGui import QLinearGradient, QFont
    pm = QPixmap(256, 256)
    pm.fill(Qt.transparent)
    pa = QPainter(pm)
    pa.setRenderHint(QPainter.Antialiasing)
    g = QLinearGradient(0, 0, 256, 256)
    g.setColorAt(0, QColor(ACCENT))
    g.setColorAt(1, QColor(ACCENT2))
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
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("krd.bgremover.desktop")
        except Exception:  # noqa: BLE001
            pass
    app = QApplication(sys.argv)
    app.setApplicationName(APP_TITLE)
    app.setLayoutDirection(Qt.RightToLeft)
    if sys.platform == "win32":
        f = app.font()
        f.setFamily("Segoe UI")
        app.setFont(f)
    # ڕووکاری ڕووناکی تایبەت (بەبێ گوێدانە ڕێکخستنی ویندۆز، هەموو ڕەنگەکان دیاریکراون)
    app.setStyle("Fusion")
    from PySide6.QtGui import QPalette
    pal = QPalette()
    for role, col in [(QPalette.Window, BG), (QPalette.WindowText, TEXT),
                      (QPalette.Base, "#FFFFFF"), (QPalette.AlternateBase, CARD),
                      (QPalette.Text, TEXT), (QPalette.Button, CARD2),
                      (QPalette.ButtonText, TEXT), (QPalette.ToolTipBase, CARD2),
                      (QPalette.ToolTipText, TEXT), (QPalette.PlaceholderText, "#9A97B5"),
                      (QPalette.Highlight, ACCENT), (QPalette.HighlightedText, "#FFFFFF"),
                      (QPalette.Light, "#FFFFFF"), (QPalette.Mid, LINE), (QPalette.Dark, "#CFC8EA"),
                      (QPalette.Shadow, "#B8B2D6"), (QPalette.BrightText, TEXT), (QPalette.Link, ACCENT2)]:
        pal.setColor(role, QColor(col))
    app.setPalette(pal)
    app.setStyleSheet(STYLE)
    app.setWindowIcon(make_icon())
    w = MainWindow()
    w.show()
    if "--smoke-test" in sys.argv:
        QTimer.singleShot(1500, app.quit)
    sys.exit(app.exec())
