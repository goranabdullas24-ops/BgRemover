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
from PySide6.QtCore import (QObject, QRunnable, QRect, QSize, Qt, QThreadPool, QTimer, Signal, QUrl, QPoint)
from PySide6.QtGui import (QAction, QColor, QDesktopServices, QGuiApplication, QIcon, QImage,
                           QKeySequence, QPainter, QPixmap, QShortcut, QBrush)
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QColorDialog, QDialog,
                               QDialogButtonBox, QFileDialog, QFrame, QGridLayout, QHBoxLayout,
                               QLabel, QLineEdit, QMainWindow, QMenu, QMessageBox, QProgressBar,
                               QPushButton, QRadioButton, QScrollArea, QSizePolicy, QSplitter,
                               QStatusBar, QToolButton, QVBoxLayout, QWidget, QGroupBox)

from . import core

PURPLE = "#5A5A96"
STYLE = f"""
QWidget {{ font-size: 10.5pt; color: #1C1B1F; }}
QMainWindow, QScrollArea, QScrollArea > QWidget > QWidget {{ background: #FBF9FF; }}
QFrame#card {{ background: #F1EFF7; border-radius: 14px; }}
QLabel#cardTitle {{ font-weight: 600; font-size: 11pt; }}
QPushButton {{ border: 1px solid #8C8AAE; border-radius: 16px; padding: 7px 12px; background: white; color: {PURPLE}; }}
QPushButton:hover {{ background: #ECEBF7; }}
QPushButton:disabled {{ color: #AAA; border-color: #DDD; }}
QPushButton#primary {{ background: {PURPLE}; color: white; border: none; }}
QPushButton#primary:hover {{ background: #4B4B85; }}
QPushButton#primary:disabled {{ background: #B9B8D3; }}
QLineEdit {{ border: 1.5px solid #8C8AAE; border-radius: 14px; padding: 8px 12px; background: white; color: #1C1B1F; font-size: 11.5pt; selection-background-color: {PURPLE}; selection-color: white; }}
QLabel {{ color: #1C1B1F; background: transparent; }}
QCheckBox, QRadioButton, QGroupBox {{ color: #1C1B1F; }}
QMenu {{ background: white; color: #1C1B1F; }}
QMenu::item:selected {{ background: #ECEBF7; }}
QDialog {{ background: #FBF9FF; }}
QLineEdit:focus {{ border-color: {PURPLE}; }}
QToolButton#tileBtn {{ background: rgba(0,0,0,170); color: white; border-radius: 15px; font-size: 12pt; }}
QToolButton#tileBtn:hover {{ background: {PURPLE}; }}
QProgressBar {{ border: none; background: #E3E1EF; height: 6px; border-radius: 3px; }}
QProgressBar::chunk {{ background: {PURPLE}; border-radius: 3px; }}
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


class Checker(QLabel):
    """پیشاندانی وێنە لەسەر خانەخانە (بۆ ڕوونی) یان ڕەنگ."""

    def __init__(self, min_h=260):
        super().__init__()
        self.setAlignment(Qt.AlignCenter)
        self.setMinimumHeight(min_h)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self._img: Optional[QImage] = None
        self.bg: Optional[tuple[int, int, int]] = None
        self.checker = True

    def set_image(self, img: Optional[QImage]):
        self._img = img
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        r = self.rect()
        if self.checker:
            if self.bg is None:
                s = 12
                for y in range(0, r.height(), s):
                    for x in range(0, r.width(), s):
                        p.fillRect(x, y, s, s, QColor("#FFFFFF") if (x // s + y // s) % 2 else QColor("#DADADA"))
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
    border = f"3px solid {PURPLE}" if selected else "1px solid #888"
    if color is None:
        b.setText("▦")
        b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{border};background:white;color:#999;font-size:14pt;}}")
        b.setToolTip("ڕوون (بێ باکگراوند)")
    else:
        b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{border};background:rgb{color};}}")
    b.clicked.connect(cb)
    return b


# ───────────────────────── خانەی وێنەی گەڕان ─────────────────────────

class Tile(QFrame):
    clicked = Signal(object, bool)   # result, ctrl
    remove = Signal(object)
    download = Signal(object)
    context = Signal(object, QPoint)

    SIZE = 140

    def __init__(self, r: core.ImageResult):
        super().__init__()
        self.r = r
        self.setFixedSize(self.SIZE, self.SIZE)
        self.setCursor(Qt.PointingHandCursor)
        self.setStyleSheet("Tile{background:#EDEDF4;border-radius:10px;}")
        self.img = QLabel(self)
        self.img.setGeometry(0, 0, self.SIZE, self.SIZE)
        self.img.setAlignment(Qt.AlignCenter)
        self.img.setStyleSheet("border-radius:10px;")
        self.sel = False
        self.select_mode = False
        self.size_lbl = QLabel(self)
        if r.width and r.height:
            self.size_lbl.setText(f"{r.width}×{r.height}")
            self.size_lbl.setStyleSheet("background:rgba(0,0,0,150);color:white;border-radius:4px;padding:1px 4px;font-size:8.5pt;")
            self.size_lbl.adjustSize()
            self.size_lbl.move(4, self.SIZE - self.size_lbl.height() - 4)
        else:
            self.size_lbl.hide()
        self.b_rm = QToolButton(self)
        self.b_rm.setObjectName("tileBtn")
        self.b_rm.setText("✨")
        self.b_rm.setToolTip("لابردنی باکگراوند")
        self.b_rm.setFixedSize(30, 30)
        self.b_rm.clicked.connect(lambda: self.remove.emit(self.r))
        self.b_dl = QToolButton(self)
        self.b_dl.setObjectName("tileBtn")
        self.b_dl.setText("⬇")
        self.b_dl.setToolTip("داگرتن بەبێ لابردنی باکگراوند")
        self.b_dl.setFixedSize(30, 30)
        self.b_dl.clicked.connect(lambda: self.download.emit(self.r))
        self.check = QLabel(self)
        self.check.setFixedSize(26, 26)
        self.check.setAlignment(Qt.AlignCenter)
        self._layout_buttons()
        self.update_sel()

    def _layout_buttons(self):
        # لە ڕاست بۆ چەپ دوگمەکان لە گۆشەی سەرەوەی چەپ دادەنرێن (وەک ئەندرۆید: TopEnd)
        self.b_rm.move(5, 5)
        self.b_dl.move(5, 40)
        self.check.move(self.SIZE - 31, 5)

    def set_pixmap(self, pm: QPixmap):
        self.img.setPixmap(pm.scaled(self.SIZE, self.SIZE, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
                           .copy(0, 0, self.SIZE, self.SIZE))

    def set_loading_dl(self, loading: bool):
        self.b_dl.setText("…" if loading else "⬇")
        self.b_dl.setEnabled(not loading)

    def update_sel(self):
        self.b_rm.setVisible(not self.select_mode)
        self.b_dl.setVisible(not self.select_mode)
        self.check.setVisible(self.select_mode)
        self.check.setText("✓" if self.sel else "")
        self.check.setStyleSheet(
            f"border-radius:13px;font-weight:bold;color:white;"
            f"background:{PURPLE if self.sel else 'rgba(0,0,0,90)'};border:2px solid white;")
        self.setStyleSheet(f"Tile{{background:#EDEDF4;border-radius:10px;border:{'3px solid ' + PURPLE if self.sel else 'none'};}}")

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self.r, bool(e.modifiers() & Qt.ControlModifier))

    def contextMenuEvent(self, e):
        self.context.emit(self.r, e.globalPos())


class FlowGrid(QWidget):
    """تۆڕی خانەکان کە ژمارەی ستوونەکان بەپێی پانی دەگۆڕێت."""

    def __init__(self):
        super().__init__()
        self.grid = QGridLayout(self)
        self.grid.setSpacing(8)
        self.grid.setContentsMargins(0, 0, 0, 0)
        self.grid.setAlignment(Qt.AlignTop | Qt.AlignRight)
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
        cols = max(1, (width + 8) // (cell + 8))
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


class BatchTile(QFrame):
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
        self.lbl.setStyleSheet("color:#D93025;font-size:9pt;background:rgba(255,255,255,170);" if st == "ERROR"
                               else "font-size:18pt;background:transparent;")

    def mouseReleaseEvent(self, e):
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
        v.addWidget(self.person)
        v.addWidget(self.focus)

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
        s.removebg_key = self.rb_key.text().strip()
        s.engine = "removebg" if self.r_rb.isChecked() and s.removebg_key else "isnet"
        s.serper_key = self.serper.text().strip()
        s.save_dir = self.dir.text().strip()
        return s


# ───────────────────────── پەنجەرەی سەرەکی ─────────────────────────

class MainWindow(QMainWindow):
    thumb_ready = Signal(str, QImage)
    dl_done = Signal(str, bool, str)
    batch_update = Signal(int)
    batch_finished = Signal(str)

    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"داگرتن و لابردنی باکگراوند — BgRemover {core.VERSION}")
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
        self.dl_pool = ThreadPoolExecutor(3)
        self.dl_count = 0
        self.dl_shown_folder = False

        self._tasks: set = set()
        self._bridge = Signals()
        self._bridge.progress.connect(lambda m: self.set_busy(m))
        self._build()
        self.thumb_ready.connect(self._on_thumb)
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
        self.setCentralWidget(root)
        rv = QVBoxLayout(root)
        rv.setContentsMargins(16, 12, 16, 8)
        rv.setSpacing(10)

        # شریتی سەرەوە
        top = QHBoxLayout()
        self.q = QLineEdit()
        self.q.setPlaceholderText("ناو یان لینکی وێنە — بۆ نموونە: گوڵی نێرگز")
        self.q.returnPressed.connect(self.submit)
        self.q.setClearButtonEnabled(True)
        top.addWidget(self.q, 1)
        self.b_search = btn("🔍  گەڕان و داگرتنی وێنە", True, self.submit)
        top.addWidget(self.b_search)
        top.addWidget(btn("🖼  کردنەوەی وێنە (Ctrl+O)", cb=self.open_files))
        top.addWidget(btn("📋  لکاندن (Ctrl+V)", cb=self.paste_image))
        b_set = btn("⚙", cb=self.open_settings)
        b_set.setToolTip("ڕێکخستن")
        b_set.setFixedWidth(48)
        top.addWidget(b_set)
        rv.addLayout(top)

        self.prog = QProgressBar()
        self.prog.setRange(0, 0)
        self.prog.setTextVisible(False)
        self.prog.setFixedHeight(6)
        self.prog.hide()
        self.prog_lbl = QLabel("")
        self.prog_lbl.setStyleSheet("color:#555;")
        self.prog_lbl.hide()
        rv.addWidget(self.prog)
        rv.addWidget(self.prog_lbl)

        split = QSplitter(Qt.Horizontal)
        rv.addWidget(split, 1)

        # ── لای ڕاست: کار (سەرەکی، ئەنجام، بەکۆمەڵ) ──
        work_scroll = QScrollArea()
        work_scroll.setWidgetResizable(True)
        work_scroll.setFrameShape(QFrame.NoFrame)
        work_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        work = QWidget()
        self.work_v = QVBoxLayout(work)
        self.work_v.setContentsMargins(0, 0, 8, 0)
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
        self.orig_view = Checker(320)
        self.orig_card.setMinimumWidth(300)
        self.orig_view.checker = False
        ov.addWidget(self.orig_view, 1)
        self.low_lbl = QLabel("⚠ ئەم ماڵپەڕە ڕێگەی بە داگرتنی وێنە ئەسڵییەکە نەدا؛ تەنها وێنە بچووکەکەی بەردەستە.")
        self.low_lbl.setStyleSheet("color:#D93025;")
        self.low_lbl.setWordWrap(True)
        self.low_lbl.hide()
        ov.addWidget(self.low_lbl)
        orow = QHBoxLayout()
        orow.addWidget(btn("⬇  داگرتنی ئەسڵی (بێ لابردن)", cb=self.save_original))
        self.b_redo = btn("↻  دووبارە", cb=self.remove_background)
        orow.addWidget(self.b_redo)
        ov.addLayout(orow)

        self.res_card, resv, self.res_title = card("ئەنجام — بێ باکگراوند")
        self.res_view = Checker(320)
        self.res_card.setMinimumWidth(300)
        resv.addWidget(self.res_view, 1)
        crow = QHBoxLayout()
        crow.addWidget(QLabel("ڕەنگی باکگراوند:"))
        self.chips_box = QHBoxLayout()
        crow.addLayout(self.chips_box)
        crow.addStretch(1)
        resv.addLayout(crow)
        self.b_up = btn("✨  Upscale ×٤ بە AI", cb=self.upscale_now)
        resv.addWidget(self.b_up)
        rrow = QHBoxLayout()
        rrow.addWidget(btn("💾  پاشەکەوت", True, self.save_result))
        rrow.addWidget(btn("📋  کۆپی", cb=self.copy_result))
        rrow.addWidget(btn("📂  فۆڵدەر", cb=self.open_save_dir))
        resv.addLayout(rrow)

        pair.addWidget(self.orig_card, 1)
        pair.addWidget(self.res_card, 1)
        self.pair_w = QWidget()
        self.pair_w.setLayout(pair)
        self.work_v.addWidget(self.pair_w)
        self.orig_card.hide()
        self.res_card.hide()

        self.hint = QLabel("ناوێک بنووسە و بگەڕێ، یان وێنەیەک بکەرەوە، ڕایبکێشە ناو پەنجەرەکە (Drag & Drop)، یان Ctrl+V.\n\n"
                           "✨ = لابردنی باکگراوند   ⬇ = داگرتن بە قەبارەی ئەسڵی   Ctrl+کلیک = هەڵبژاردنی چەند وێنەیەک")
        self.hint.setAlignment(Qt.AlignCenter)
        self.hint.setStyleSheet("color:#777;font-size:11.5pt;padding:60px;")
        self.work_v.addWidget(self.hint)
        self.work_v.addStretch(1)
        split.addWidget(work_scroll)

        # ── لای چەپ: ئەنجامەکانی گەڕان ──
        res_panel = QWidget()
        rp = QVBoxLayout(res_panel)
        rp.setContentsMargins(8, 0, 0, 0)
        self.res_head = QLabel("")
        self.res_head.setObjectName("cardTitle")
        self.res_head.setWordWrap(True)
        rp.addWidget(self.res_head)
        hrow = QHBoxLayout()
        self.b_selmode = btn("☑  هەڵبژاردنی چەند وێنەیەک", cb=lambda: self.set_select_mode(not self.select_mode))
        self.b_selall = btn("هەمووی", cb=self.select_all)
        hrow.addWidget(self.b_selmode)
        hrow.addWidget(self.b_selall)
        hrow.addStretch(1)
        rp.addLayout(hrow)
        self.grid_scroll = QScrollArea()
        self.grid_scroll.setWidgetResizable(True)
        self.grid_scroll.setFrameShape(QFrame.NoFrame)
        self.grid_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        gw = QWidget()
        gv = QVBoxLayout(gw)
        gv.setContentsMargins(0, 0, 0, 0)
        self.grid = FlowGrid()
        gv.addWidget(self.grid)
        self.b_more = btn("وێنەی زیاتر", cb=self.load_more)
        gv.addWidget(self.b_more)
        self.src_lbl = QLabel("")
        self.src_lbl.setWordWrap(True)
        self.src_lbl.setStyleSheet(f"color:{PURPLE};")
        gv.addWidget(self.src_lbl)
        gv.addStretch(1)
        self.grid_scroll.setWidget(gw)
        rp.addWidget(self.grid_scroll, 1)
        split.addWidget(res_panel)
        split.setSizes([880, 480])
        split.setStretchFactor(0, 3)
        split.setStretchFactor(1, 2)
        self.res_panel = res_panel
        self._update_results_ui()

        self.setStatusBar(QStatusBar())
        self._build_chips()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        QTimer.singleShot(0, self._relayout)

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
        for b in (self.b_search, self.b_redo, self.b_up):
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
        t = Tile(r)
        t.select_mode = self.select_mode
        t.sel = r.full_url in self.selected
        t.update_sel()
        t.clicked.connect(self._tile_clicked)
        t.remove.connect(lambda rr: self.pick(rr))
        t.download.connect(self.download_one)
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
            im.thumbnail((360, 360))
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
        elif not self.busy:
            self.pick(r)

    def _tile_menu(self, r, pos):
        m = QMenu(self)
        m.setLayoutDirection(Qt.RightToLeft)
        m.addAction("✨  لابردنی باکگراوند", lambda: self.pick(r))
        m.addAction("⬇  داگرتن بەبێ لابردنی باکگراوند", lambda: self.download_one(r))
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
            self.res_head.setText(f"{len(self.results)} وێنە — ✨ لابردنی باکگراوند، ⬇ داگرتن، Ctrl+کلیک بۆ چەندان")
        else:
            self.res_head.setText("ئەنجامەکانی گەڕان لێرە دەردەکەون")
        self.b_selmode.setText("هەڵوەشاندنەوە" if self.select_mode else "☑  هەڵبژاردنی چەند وێنەیەک")
        self.b_selmode.setVisible(has)
        self.b_selall.setVisible(has and self.select_mode)
        self.b_more.setVisible(has and self.can_more)
        self.src_lbl.setVisible(has and self.source == "free")
        self.src_lbl.setText("سەرچاوە: ویکیپیدیا، Wikimedia Commons، Openverse. بۆ ئەنجامی ڕاستەوخۆی گۆگڵ، کلیلی Serper لە ⚙ دابنێ.")
        n = len(self.selected)
        self.sel_bar.setVisible(self.select_mode and n > 0)
        self.b_sel_rm.setText(f"✨  لابردنی باکگراوندی {n} وێنە")
        self.b_sel_dl.setText(f"⬇  داگرتنی {n} وێنە بەبێ لابردنی باکگراوند")
        QTimer.singleShot(0, self._relayout)

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
        self.hint.setVisible(not (has_o or has_c or self.batch))
        if has_o:
            self.orig_title.setText(f"وێنەی سەرەکی — {self.original.width}×{self.original.height} پیکسڵ")
            self.orig_view.set_image(pil_to_qimage(thumb(self.original, 1400)))
        self.low_lbl.setVisible(has_o and self.low_quality)
        if has_c:
            self.res_title.setText(f"ئەنجام — بێ باکگراوند ({self.cutout.width}×{self.cutout.height})"
                                   + ("  ✨ Upscale" if self.upscaled else ""))
            self.res_view.set_image(pil_to_qimage(thumb(self.cutout, 1400)))
        else:
            self.res_title.setText("ئەنجام — چاوەڕێ بکە...")
            self.res_view.set_image(None)
        self.res_view.bg = self.bg
        self.res_view.update()
        self.b_up.setVisible(has_c and not self.upscaled and max(self.cutout.size) < 2048 if has_c else False)
        self.work_scroll.verticalScrollBar().setValue(0)

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
        self.run(work, done, label=f"٢/٢ لابردنی باکگراوند ({src.width}×{src.height})...")

    def upscale_now(self):
        if self.cutout is None or self.busy:
            return
        orig, cut = self.original, self.cutout

        def work(progress):
            return core.upscale_cutout(orig, cut, progress)

        def done(up):
            old = self.cutout.size
            self.cutout = up
            self.upscaled = True
            self._show_work()
            self.message(f"Upscale کرا: {old[0]}×{old[1]} → {up.width}×{up.height}")
        self.run(work, done, label="Upscale ×4 بە AI...")

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
        b.setStyleSheet(f"QToolButton{{border-radius:17px;border:{'3px solid ' + PURPLE if custom else '1px solid #888'};"
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
            self.batch.append(BatchItem(self.next_id, url=r.full_url, fallback=r.thumb_url, referer=r.page_url))
            self.next_id += 1
        self.run_batch()

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


def make_icon() -> QIcon:
    pm = QPixmap(256, 256)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QBrush(QColor(PURPLE)))
    p.setPen(Qt.NoPen)
    p.drawRoundedRect(8, 8, 240, 240, 56, 56)
    p.setBrush(QBrush(QColor("white")))
    p.drawEllipse(88, 46, 80, 80)
    p.drawRoundedRect(58, 136, 140, 90, 60, 60)
    p.end()
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
    app.setApplicationName(core.APP_NAME)
    app.setLayoutDirection(Qt.RightToLeft)
    if sys.platform == "win32":
        f = app.font()
        f.setFamily("Segoe UI")
        app.setFont(f)
    # ڕووکاری ڕووناک بەبێ گوێدانە Dark Mode ی ویندۆز (نووسینی ڕەش لەسەر باکگراوندی سپی)
    app.setStyle("Fusion")
    from PySide6.QtGui import QPalette
    pal = QPalette()
    for role, col in [(QPalette.Window, "#FBF9FF"), (QPalette.WindowText, "#1C1B1F"),
                      (QPalette.Base, "#FFFFFF"), (QPalette.AlternateBase, "#F1EFF7"),
                      (QPalette.Text, "#1C1B1F"), (QPalette.Button, "#FFFFFF"),
                      (QPalette.ButtonText, PURPLE), (QPalette.ToolTipBase, "#FFFFFF"),
                      (QPalette.ToolTipText, "#1C1B1F"), (QPalette.PlaceholderText, "#8A8899"),
                      (QPalette.Highlight, PURPLE), (QPalette.HighlightedText, "#FFFFFF")]:
        pal.setColor(role, QColor(col))
    app.setPalette(pal)
    app.setStyleSheet(STYLE)
    app.setWindowIcon(make_icon())
    w = MainWindow()
    w.show()
    if "--smoke-test" in sys.argv:
        QTimer.singleShot(1500, app.quit)
    sys.exit(app.exec())
