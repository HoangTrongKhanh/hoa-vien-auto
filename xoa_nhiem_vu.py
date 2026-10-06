#!/usr/bin/env python3
"""Tự động xóa nhiệm vụ Đấu hội có điểm <= ngưỡng.

Điều khiển điện thoại qua ADB, nhận diện bằng ảnh chụp màn hình (OpenCV).
Màn hình điện thoại phải đang mở bảng Nhiệm Vụ của Đấu hội.

Ví dụ:
    .venv/bin/python xoa_nhiem_vu.py                 # chạy liên tục, 15 phút/lượt
    .venv/bin/python xoa_nhiem_vu.py --once --dry-run
    .venv/bin/python xoa_nhiem_vu.py --from-image samples/screen1.png
"""
import argparse
import logging
import logging.handlers
import signal
import struct
import subprocess
import sys
import time
import zlib
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

BASE = Path(__file__).resolve().parent
TPL_DIR = BASE / "templates"
DEBUG_DIR = BASE / "debug"
_SDK_ADB = Path.home() / "Android/Sdk/platform-tools/adb"
DEFAULT_ADB = str(_SDK_ADB) if _SDK_ADB.exists() else "adb"    # Termux: gói android-tools

# Toạ độ đo trên máy gốc 1080x2340 (Samsung A54). Máy khác được quy đổi tự động (lớp UI):
# game co giãn giao diện theo chiều ngang (tỉ lệ = rộng màn hình / 1080) và lệch theo chiều dọc,
# độ lệch lấy từ vị trí tab "Nhiệm Vụ".
REF_WIDTH = 1080
REF_TAB_POS = (160, 650)            # góc trên-trái mẫu tab "Nhiệm Vụ" trên máy gốc
GRID = (100, 800, 980, 1632)        # x0, y0, x1, y1 vùng lưới ô nhiệm vụ nhìn thấy
PANEL = (90, 1700, 1000, 2010)      # khung chi tiết nhiệm vụ đang chọn
PANEL_POINTS_Y = (1700, 1900)       # dải chứa la bàn + điểm trong khung chi tiết
DELETE_BTN_REGION = (200, 1860, 560, 2010)
ACCEPT_BTN_ZONE = (560, 1860, 900, 2010)   # nút "Nhận nhiệm vụ" - không bao giờ bấm
TAB_REGION = (120, 620, 380, 730)
DIALOG_REGION = (100, 1150, 1000, 1550)   # hộp thoại "Nhắc Nhở - Xác định xóa nhiệm vụ?"
COMPASS_SIZE = (44, 48)                    # rộng, cao của mẫu la bàn
TOP_BADGE_POS = (150, 828)                 # huy hiệu "1" của ô đầu tiên khi danh sách ở trên cùng
# Khe trống giữa các cột (kéo ở đây không bị hiểu là chạm vào ô): cột 1|2 (x 384-408), cột 2|3 (x 673-691).
# Mỗi lần vuốt đổi sang khe còn lại; nếu kéo không nhúc nhích thì thử ngay khe kia.
DRAG_GAPS = (396, 682)
# Game bỏ qua 2 bước di chuyển đầu của mỗi lần kéo: 780px chia 6 bước -> lưới cuộn 780*4/6 = 520px.
# Phải < ~770px (khung nhìn 832px trừ dải điểm 60px) để ô nào cũng có lúc hiện trọn dải điểm.
DRAG_FROM_Y, DRAG_TO_Y, DRAG_STEPS = 1610, 830, 6
DRAG_RELEASE_PAUSE = 0.15
EXPECTED_SHIFT = 520
SHIFT_BAND = 250                           # dải dưới cùng dùng để đo độ cuộn (đo được tới 582px)
# Đo độ cuộn trên ảnh màu thu nhỏ về SHIFT_SCALE (theo máy gốc): khớp đúng >= 0.90, khớp nhầm <= 0.80.
SHIFT_SCALE = 0.25
SHIFT_MATCH_THRESHOLD = 0.85
# Tìm mẫu (la bàn, nút, tab) trên ảnh thu nhỏ về MATCH_SCALE rồi tinh chỉnh ở độ phân giải gốc: nhanh hơn ~7 lần.
MATCH_SCALE = 0.5

COMPASS_THRESHOLD = 0.80
BUTTON_THRESHOLD = 0.80
DIGIT_THRESHOLD = 0.65      # dưới mức này coi như không phải chữ số
DIGIT_MIN_MARGIN = 0.10     # chữ số khác giống ngang ngửa trong khoảng này vẫn bị coi là khả dĩ
DIGIT_SIZE = 32

log = logging.getLogger("xoa_nhiem_vu")


# ---------------------------------------------------------------- ADB

class AdbError(RuntimeError):
    pass


class Adb:
    def __init__(self, adb_path, serial=None):
        self.adb_path = adb_path
        self.set_serial(serial)

    def set_serial(self, serial):
        self.serial = serial
        self.cmd = [self.adb_path] + (["-s", serial] if serial else [])

    def run(self, *args, timeout=30, binary=False):
        try:
            p = subprocess.run(self.cmd + list(args), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbError(f"adb hết thời gian chờ: {' '.join(args)}") from e
        if p.returncode != 0:
            raise AdbError(f"adb lỗi ({' '.join(args)}): {p.stderr.decode(errors='replace').strip()}")
        return p.stdout if binary else p.stdout.decode(errors="replace").strip()

    def shell(self, command, timeout=30):
        return self.run("shell", command, timeout=timeout)

    def connected(self):
        try:
            return self.run("get-state", timeout=10) == "device"
        except AdbError:
            return False

    # Ảnh thô RGBA: nén gzip trên máy (đỡ truyền qua USB/Wi-Fi) hoặc không nén (nhanh hơn khi chạy ngay trên máy,
    # ví dụ Termux). Mỗi cách được đo thử vài lần đầu rồi dùng cách nhanh hơn; lỗi thì lùi về PNG.
    CAPTURE_MODES = {"gzip": "screencap | gzip -1", "raw": "screencap"}
    TRIALS_PER_MODE = 2
    capture_times = None
    capture_mode = None

    def screenshot(self):
        if self.capture_times is None:
            self.capture_times = {m: [] for m in self.CAPTURE_MODES}
        mode = self.capture_mode
        if mode is None:
            mode = min(self.capture_times, key=lambda m: len(self.capture_times[m]))
        if mode != "png":
            try:
                t = time.perf_counter()
                img = self._screenshot_raw(mode)
                if self.capture_mode is None:
                    self.capture_times[mode].append(time.perf_counter() - t)
                    if all(len(v) >= self.TRIALS_PER_MODE for v in self.capture_times.values()):
                        self.capture_mode = min(self.capture_times, key=lambda m: sum(self.capture_times[m]))
                        log.debug("Chụp màn hình bằng cách '%s' (%s).", self.capture_mode, {
                            m: f"{1000 * sum(v) / len(v):.0f}ms" for m, v in self.capture_times.items()})
                return img
            except (AdbError, zlib.error, ValueError) as e:
                log.debug("Chụp '%s' lỗi (%s), chuyển sang PNG.", mode, e)
                self.capture_mode = "png"
        data = self.run("exec-out", "screencap", "-p", binary=True, timeout=30)
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise AdbError("không giải mã được ảnh chụp màn hình")
        return img

    def _screenshot_raw(self, mode):
        buf = self.run("exec-out", self.CAPTURE_MODES[mode], binary=True, timeout=30)
        if mode == "gzip":
            buf = zlib.decompress(buf, 31)
        w, h = struct.unpack_from("<II", buf, 0)
        size = w * h * 4
        if not (0 < size <= len(buf) - 12):
            raise ValueError("kích thước ảnh thô không hợp lệ")
        px = np.frombuffer(buf, np.uint8, count=size, offset=len(buf) - size).reshape(h, w, 4)
        return cv2.cvtColor(px, cv2.COLOR_RGBA2BGR)

    def tap(self, x, y):
        self.shell(f"input tap {int(x)} {int(y)}")

    def drag(self, x, y_from, y_to, steps=DRAG_STEPS):
        """Kéo và dừng một chút trước khi nhả tay để danh sách không trượt quán tính
        (đo trên máy: dừng 0.1-0.4s đều không trượt; không dừng thì mất bước cuối)."""
        cmds = [f"input motionevent DOWN {x} {y_from}"]
        for i in range(1, steps + 1):
            cmds.append(f"input motionevent MOVE {x} {int(y_from + (y_to - y_from) * i / steps)}")
        cmds += [f"sleep {DRAG_RELEASE_PAUSE}", f"input motionevent UP {x} {y_to}"]
        self.shell("; ".join(cmds), timeout=60)

    def flick(self, x, y_from, y_to):
        self.shell(f"input swipe {x} {y_from} {x} {y_to} 120")

    def swipe(self, x0, y0, x1, y1, ms):
        self.shell(f"input swipe {int(x0)} {int(y0)} {int(x1)} {int(y1)} {int(ms)}")

    def game_booster_locked(self):
        """Lớp "GameBooster Lock Screen" của Samsung Game Booster đang hiện: tự bật khi để yên lâu trong game,
        làm tối màn hình và chặn mọi thao tác chạm cho tới khi kéo biểu tượng ổ khóa."""
        out = self.shell("dumpsys window windows | awk '/Window #/ {p = /GameBooster Lock Screen/} p' "
                         "| grep -m1 'isVisible=true'; true")
        return "isVisible=true" in out

    def screen_awake(self):
        out = self.shell("dumpsys power | grep -m1 mWakefulness=")
        return "Awake" in out

    def charging(self):
        return "true" in self.shell("dumpsys battery | grep -E '(AC|USB|Wireless) powered'")

    def get_stay_on(self):
        return self.shell("settings get global stay_on_while_plugged_in")

    def set_stay_on(self, value):
        self.shell(f"settings put global stay_on_while_plugged_in {value}")


def in_box(x, y, box):
    x0, y0, x1, y1 = box
    return x0 <= x < x1 and y0 <= y < y1


# ---------------------------------------------------------------- Nhận diện

def load_image(path):
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return img


class Templates:
    def __init__(self, tpl_dir=TPL_DIR):
        self.compass = load_image(tpl_dir / "compass.png")
        self.btn_delete = load_image(tpl_dir / "btn_xoa.png")
        self.tab = load_image(tpl_dir / "tab_nhiem_vu.png")
        self.btn_confirm = load_image(tpl_dir / "btn_xac_nhan.png")
        self.btn_cancel = load_image(tpl_dir / "btn_huy.png")
        self.badge_1 = load_image(tpl_dir / "badge_1.png")
        self.digits = []    # (chữ số, mask nhị phân DIGIT_SIZE x DIGIT_SIZE)
        for f in sorted((tpl_dir / "digits").glob("*.png")):
            m = cv2.imread(str(f), cv2.IMREAD_GRAYSCALE)
            self.digits.append((int(f.name[0]), m > 127))
        if not self.digits:
            raise RuntimeError("thiếu mẫu chữ số trong templates/digits")


class UI:
    """Mẫu ảnh và toạ độ đã quy đổi từ máy gốc sang màn hình đang dùng: x' = s*x + dx, y' = s*y + dy."""

    def __init__(self, base, s=1.0, dx=0.0, dy=0.0):
        self.s, self.dx, self.dy = s, dx, dy

        def scale(im):
            if s == 1.0:
                return im
            return cv2.resize(im, None, fx=s, fy=s, interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)

        self.compass = scale(base.compass)
        self.btn_delete = scale(base.btn_delete)
        self.tab = scale(base.tab)
        self.btn_confirm = scale(base.btn_confirm)
        self.btn_cancel = scale(base.btn_cancel)
        self.badge_1 = scale(base.badge_1)
        self.digits = base.digits               # chữ số đã chuẩn hoá kích thước, không cần co giãn
        self.grid = self.box(GRID)
        self.panel_points = self.box((PANEL[0], PANEL_POINTS_Y[0], PANEL[2], PANEL_POINTS_Y[1]))
        self.delete_region = self.box(DELETE_BTN_REGION)
        self.accept_zone = self.box(ACCEPT_BTN_ZONE)
        self.dialog_region = self.box(DIALOG_REGION)
        self.drag_gaps = [self.x(g) for g in DRAG_GAPS]

    def x(self, v):
        return round(self.s * v + self.dx)

    def y(self, v):
        return round(self.s * v + self.dy)

    def d(self, v):
        """Độ dài (không cộng độ lệch)."""
        return round(self.s * v)

    def box(self, b):
        return self.x(b[0]), self.y(b[1]), self.x(b[2]), self.y(b[3])

    @classmethod
    def detect(cls, img, base):
        """Tìm tab "Nhiệm Vụ" để suy ra bố cục; None nếu không thấy (không ở màn hình Nhiệm Vụ)."""
        h, w = img.shape[:2]
        probe = cls(base, w / REF_WIDTH)
        cx, cy, score = match_best(img, probe.tab, (0, 0, w, h // 2), probe.s)
        if score < BUTTON_THRESHOLD:
            return None
        th, tw = probe.tab.shape[:2]
        tx, ty = cx - tw // 2, cy - th // 2                     # góc trên-trái
        return cls(base, probe.s, tx - probe.s * REF_TAB_POS[0], ty - probe.s * REF_TAB_POS[1])

    def __repr__(self):
        return f"UI(tỉ lệ {self.s:.4f}, lệch {self.dx:+.0f},{self.dy:+.0f})"


def match_all(img, tpl, box, threshold, min_dist=20):
    """Tìm mọi vị trí khớp mẫu trong box. Trả về [(x, y, điểm)] (góc trên-trái)."""
    x0, y0, x1, y1 = box
    res = cv2.matchTemplate(img[y0:y1, x0:x1], tpl, cv2.TM_CCOEFF_NORMED)
    ys, xs = np.where(res >= threshold)
    found = []
    for y, x in sorted(zip(ys, xs), key=lambda p: -res[p[0], p[1]]):
        if all(abs(y - fy) > min_dist or abs(x - fx) > min_dist for fx, fy, _ in found):
            found.append((int(x), int(y), float(res[y, x])))
    return sorted(((x + x0, y + y0, s) for x, y, s in found), key=lambda p: (p[1], p[0]))


def _shrink(im, f):
    return cv2.resize(im, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)


def _refine(img, tpl, x, y, pad, bounds):
    """Khớp lại mẫu ở độ phân giải gốc quanh (x, y) ± pad, trong giới hạn bounds. Trả về (x, y, điểm) góc trên-trái."""
    th, tw = tpl.shape[:2]
    bx0, by0, bx1, by1 = bounds
    x0, y0 = max(x - pad, bx0), max(y - pad, by0)
    x1, y1 = min(x + tw + pad, bx1), min(y + th + pad, by1)
    if x1 - x0 < tw or y1 - y0 < th:
        return x, y, -1.0
    res = cv2.matchTemplate(img[y0:y1, x0:x1], tpl, cv2.TM_CCOEFF_NORMED)
    _, score, _, (rx, ry) = cv2.minMaxLoc(res)
    return x0 + rx, y0 + ry, float(score)


def find_template(img, tpl, box, s, threshold, min_dist):
    """Mọi vị trí khớp mẫu tpl (đã ở tỉ lệ màn hình s) trong box, dạng [(x, y, điểm)] góc trên-trái.
    Dò trên ảnh thu nhỏ về MATCH_SCALE (theo máy gốc) rồi tinh chỉnh từng vị trí ở độ phân giải gốc."""
    f = MATCH_SCALE / s
    if f >= 1:
        return match_all(img, tpl, box, threshold, min_dist)
    x0, y0, x1, y1 = box
    small = _shrink(img[y0:y1, x0:x1], f)
    coarse = match_all(small, _shrink(tpl, f), (0, 0, small.shape[1], small.shape[0]),
                       threshold - 0.15, max(1, round(min_dist * f)))
    found = []
    for cx, cy, _ in coarse:
        x, y, score = _refine(img, tpl, x0 + round(cx / f), y0 + round(cy / f), round(2 / f) + 2, box)
        if score >= threshold and all(abs(x - fx) > min_dist or abs(y - fy) > min_dist for fx, fy, _ in found):
            found.append((x, y, score))
    return sorted(found, key=lambda p: (p[1], p[0]))


def match_best(img, tpl, box, s=1.0):
    """Vị trí khớp tốt nhất của mẫu trong box: (x tâm, y tâm, điểm)."""
    h, w = tpl.shape[:2]
    f = MATCH_SCALE / s
    x0, y0, x1, y1 = box
    if f < 1:
        res = cv2.matchTemplate(_shrink(img[y0:y1, x0:x1], f), _shrink(tpl, f), cv2.TM_CCOEFF_NORMED)
        _, _, _, (cx, cy) = cv2.minMaxLoc(res)
        x, y, score = _refine(img, tpl, x0 + round(cx / f), y0 + round(cy / f), round(2 / f) + 2, box)
    else:
        res = cv2.matchTemplate(img[y0:y1, x0:x1], tpl, cv2.TM_CCOEFF_NORMED)
        _, score, _, (x, y) = cv2.minMaxLoc(res)
        x, y, score = x + x0, y + y0, float(score)
    return x + w // 2, y + h // 2, score


def ink_map(roi):
    """Độ đậm của nét chữ 0..1 so với nền sáng (giữ mép mờ thay vì cắt ngưỡng cứng)."""
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY).astype(np.float32)
    bg = float(np.percentile(gray, 90))
    dark = float(np.percentile(gray, 3))
    if bg - dark < 40:                      # không có chữ
        return np.zeros_like(gray)
    return np.clip((bg - gray) / (bg - dark), 0.0, 1.0)


def digit_boxes(ink, min_height=16):
    """Khung (x, y, w, h) của từng chữ số, trái sang phải."""
    mask = (ink > 0.5).astype(np.uint8)
    n, _, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    boxes = []
    for i in range(1, n):
        x, y, w, h, area = (int(v) for v in stats[i])
        if h >= min_height and area >= 40 and x > 0:     # x > 0: bỏ mép icon bị cắt ở biên trái
            boxes.append((x, y, w, h))
    return sorted(boxes)


def normalize_digit(ink, box):
    """Co về chiều cao DIGIT_SIZE giữ tỉ lệ, đặt giữa khung vuông."""
    x, y, w, h = box
    nw = max(1, min(DIGIT_SIZE, round(w * DIGIT_SIZE / h)))
    r = cv2.resize(ink[y:y + h, x:x + w], (nw, DIGIT_SIZE), interpolation=cv2.INTER_AREA)
    out = np.zeros((DIGIT_SIZE, DIGIT_SIZE), np.float32)
    off = (DIGIT_SIZE - nw) // 2
    out[:, off:off + nw] = r
    return out


def digit_components(roi, min_height=16):
    ink = ink_map(roi)
    return [normalize_digit(ink, b) for b in digit_boxes(ink, min_height)]


def digit_scores(comp, digits):
    """Độ giống (1 = giống hệt) của comp với từng chữ số 0-9."""
    c = comp > 0.5
    best = {}
    for d, t in digits:
        s = 1.0 - float(np.mean(c != t)) * 2.5
        best[d] = max(best.get(d, -9.0), s)
    return best


@dataclass
class Reading:
    value: int | None       # cách đọc giống nhất
    low: int | None         # nhỏ nhất / lớn nhất trong các cách đọc còn khả dĩ
    high: int | None
    detail: str


def read_number(img, box, ui):
    """Đọc số nguyên trong box. Mỗi chữ số giữ lại mọi chữ số gần giống ngang ngửa (chênh < DIGIT_MIN_MARGIN)
    để biết khoảng giá trị khả dĩ; quyết định xóa chỉ dựa trên cả khoảng đó."""
    x0, y0, x1, y1 = box
    comps = digit_components(img[y0:y1, x0:x1], ui.d(16))
    if not comps or len(comps) > 3:
        return Reading(None, None, None, f"{len(comps)} thành phần")
    readings = [""]
    best_digits, parts = [], []
    for c in comps:
        scores = digit_scores(c, ui.digits)
        d1, s1 = max(scores.items(), key=lambda p: p[1])
        parts.append(f"{d1}({s1:.2f})")
        if s1 < DIGIT_THRESHOLD:
            return Reading(None, None, None, " ".join(parts) + " - không giống chữ số nào")
        alts = [str(d) for d, s in scores.items() if s >= s1 - DIGIT_MIN_MARGIN]
        if len(alts) > 1:
            parts[-1] += "~" + "/".join(a for a in alts if a != str(d1))
        best_digits.append(str(d1))
        readings = [r + a for r in readings for a in alts]
    values = [int(r) for r in readings]
    return Reading(int("".join(best_digits)), min(values), max(values), " ".join(parts))


def decide(reading, max_points):
    """'xoa' nếu mọi cách đọc khả dĩ đều <= ngưỡng, 'giu' nếu đều > ngưỡng, còn lại 'khong-chac'."""
    if reading.value is None:
        return "khong-chac"
    if reading.high <= max_points:
        return "xoa"
    if reading.low > max_points:
        return "giu"
    return "khong-chac"


@dataclass
class Task:
    x: int          # góc trên-trái icon la bàn
    y: int
    reading: Reading
    tap_point: tuple    # giữa icon la bàn: luôn nằm trong ô và trong khung nhìn


def number_box_right_of(x, y, ui):
    """Vùng chứa số điểm nằm bên phải icon la bàn ở (x, y)."""
    return x + ui.d(50), y - ui.d(6), x + ui.d(108), y + ui.d(54)


def find_compasses(img, box, ui):
    return find_template(img, ui.compass, box, ui.s, COMPASS_THRESHOLD, ui.d(20))


def find_tasks(img, ui):
    """Các ô nhiệm vụ còn trống (có la bàn) nằm trọn trong khung nhìn của lưới."""
    th, tw = ui.compass.shape[:2]
    gx0, gy0, gx1, gy1 = ui.grid
    tasks = []
    for x, y, _ in find_compasses(img, ui.grid, ui):
        if y - ui.d(6) < gy0 or y + th + ui.d(6) > gy1:
            continue                                # bị khung nhìn cắt mất một phần
        reading = read_number(img, number_box_right_of(x, y, ui), ui)
        tasks.append(Task(x, y, reading, (x + tw // 2, y + th // 2)))
    return tasks


def is_selected(img, task, ui):
    """Ô có viền xanh lá (đang được chọn) không: xét viền trái và viền đáy quanh icon la bàn."""
    x, y, d = task.x, task.y, ui.d
    for x0, x1, y0, y1 in ((x - d(55), x - d(20), y - d(40), y + d(40)),
                           (x, x + d(60), y + d(56), y + d(76))):
        r = img[max(y0, 0):y1, max(x0, 0):x1].astype(np.int16)
        b, g, red = r[..., 0], r[..., 1], r[..., 2]
        if r.size and float(((g > 190) & (red < 200) & (b < 130) & (g - red > 40)).mean()) > 0.1:
            return True
    return False


def read_panel_points(img, ui):
    """Điểm của nhiệm vụ đang chọn trong khung chi tiết."""
    hits = find_compasses(img, ui.panel_points, ui)
    if not hits:
        return Reading(None, None, None, "không thấy la bàn trong khung chi tiết")
    x, y, _ = min(hits, key=lambda p: p[0])
    return read_number(img, number_box_right_of(x, y, ui), ui)


def has_delete_button(img, ui):
    x, y, s = match_best(img, ui.btn_delete, ui.delete_region, ui.s)
    return (x, y) if s >= BUTTON_THRESHOLD else None


def find_dialog_button(img, tpl, ui):
    """Nút Xác nhận/Hủy của hộp thoại xóa (None nếu hộp thoại không hiện)."""
    x, y, s = match_best(img, tpl, ui.dialog_region, ui.s)
    return (x, y) if s >= BUTTON_THRESHOLD else None


def grid_shift(before, after, ui, lo=None, hi=None):
    """Lưới đã cuộn lên bao nhiêu px giữa 2 ảnh: tìm dải dưới cùng của ảnh trước trong ảnh sau,
    chỉ xét độ cuộn trong [lo, hi] nếu có. Trả về (độ cuộn, điểm khớp).
    Điểm khớp thấp nghĩa là dải đó đã trôi khỏi màn hình (cuộn quá đà).
    Làm trên ảnh màu thu nhỏ về SHIFT_SCALE: nhanh hơn ~10 lần và phân biệt khớp đúng/nhầm còn rõ hơn."""
    f = SHIFT_SCALE / ui.s
    gx0, gy0, gx1, gy1 = ui.grid
    b, a = _shrink(before[gy0:gy1, gx0:gx1], f), _shrink(after[gy0:gy1, gx0:gx1], f)
    band_y0 = b.shape[0] - round(SHIFT_BAND * SHIFT_SCALE)
    band = b[band_y0:b.shape[0] - round(20 * SHIFT_SCALE)]
    res = cv2.matchTemplate(a, band, cv2.TM_CCOEFF_NORMED)[:, 0]
    shifts = np.round((band_y0 - np.arange(len(res))) / f)
    ok = np.ones(len(res), bool)
    if lo is not None:
        ok &= shifts >= lo
    if hi is not None:
        ok &= shifts <= hi
    if not ok.any():
        return 0, -1.0
    i = int(np.argmax(np.where(ok, res, -2.0)))
    return int(shifts[i]), float(res[i])


def find_lock_icon(img):
    """Tâm biểu tượng ổ khóa (xanh dương sáng) của Game Booster gần giữa màn hình, None nếu chưa hiện.
    Chỉ dùng khi lớp khóa đang hiện: lúc đó game phía sau bị làm tối nên không còn điểm xanh sáng nào khác."""
    h, w = img.shape[:2]
    x0, y0 = int(w * 0.25), int(h * 0.3)
    roi = img[y0:int(h * 0.7), x0:int(w * 0.75)].astype(np.int16)
    b, g, r = roi[..., 0], roi[..., 1], roi[..., 2]
    ys, xs = np.nonzero((b > 180) & (r < 90) & (b - g > 40))
    if len(xs) < (w * h) // 20000:             # ~200 điểm ảnh trên màn 1600x2560 (ổ khóa có ~1500)
        return None
    return int(xs.mean()) + x0, int(ys.mean()) + y0


def at_top(img, ui):
    """Danh sách đang ở trên cùng và đứng yên: huy hiệu "1" nằm đúng vị trí (khớp ~0.98, vị trí khác <= 0.62)."""
    th, tw = ui.badge_1.shape[:2]
    x, y, m = ui.x(TOP_BADGE_POS[0]), ui.y(TOP_BADGE_POS[1]), ui.d(12)
    return match_best(img, ui.badge_1, (x - m, y - m, x + tw + m, y + th + m), ui.s)[2] >= 0.85


def grid_unchanged(a, b, ui):
    """Lưới đứng yên (chỉ đồng hồ nhảy số)."""
    gx0, gy0, gx1, gy1 = ui.grid
    diff = np.abs(a[gy0:gy1, gx0:gx1].astype(np.int16) - b[gy0:gy1, gx0:gx1].astype(np.int16))
    return float(diff.mean()) < 3.0


def save_debug(img, tasks, name, max_points, ui=None):
    DEBUG_DIR.mkdir(exist_ok=True)
    out = img.copy()
    if ui is not None:
        cv2.rectangle(out, ui.grid[:2], ui.grid[2:], (255, 0, 0), 2)
        colors = {"xoa": (0, 0, 255), "giu": (0, 180, 0), "khong-chac": (0, 200, 255)}
        for t in tasks:
            color = colors[decide(t.reading, max_points)]
            cv2.rectangle(out, (t.x - ui.d(4), t.y - ui.d(10)), (t.x + ui.d(116), t.y + ui.d(58)), color, 3)
            cv2.putText(out, str(t.reading.value), (t.x, t.y - ui.d(14)),
                        cv2.FONT_HERSHEY_SIMPLEX, ui.s, color, 3)
    path = DEBUG_DIR / f"{time.strftime('%Y%m%d-%H%M%S')}-{name}.png"
    cv2.imwrite(str(path), out)
    return path


# ---------------------------------------------------------------- Điều khiển

# Sau mỗi lần bấm không chờ cố định mà chụp lặp lại tới khi giao diện đổi xong (mỗi lần chụp ~0.5s).
POLL_FIRST_WAIT = 0.15  # chờ trước lần chụp đầu để game kịp phản hồi
SELECT_TIMEOUT = 1.2    # chọn ô: khung chi tiết hiện đúng ô vừa bấm
DIALOG_TIMEOUT = 2.0    # bấm Xóa: hộp thoại xác nhận hiện ra
DELETED_TIMEOUT = 2.5   # bấm Xác nhận: ô chuyển thành đồng hồ
SCROLL_WAIT = 0.05      # kéo có dừng trước khi nhả tay nên danh sách đứng yên ngay
SETTLE_TIMEOUT = 4.5    # ở cuối danh sách: chờ hiệu ứng nảy đàn hồi (~1.5-2s trên máy tính bảng) kết thúc
TOP_TIMEOUT = 2.0       # vuốt lên đầu: chờ danh sách dừng ở trên cùng
NOT_READY_RETRY = 20    # chưa thấy bảng Nhiệm Vụ: thử lại sau bấy nhiêu giây
ONCE_WAIT_LIMIT = 180   # --once: chờ tối đa bấy nhiêu giây để bảng Nhiệm Vụ hiện ra


class Bot:
    def __init__(self, adb, templates, max_points, dry_run, debug, max_deletes=None, reconnect=False):
        self.adb = adb
        self.reconnect = reconnect  # chạy trong Termux: tự tìm lại cổng Gỡ lỗi không dây khi mất kết nối
        self.tpl = templates
        self.ui = None          # bố cục màn hình, nhận diện lại mỗi lượt
        self.max_points = max_points
        self.dry_run = dry_run
        self.debug = debug
        self.max_deletes = max_deletes
        self.total_deleted = 0
        self.gap = 0            # chỉ số khe dùng cho lần vuốt tới trong DRAG_GAPS (đổi khe sau mỗi lần vuốt)

    def tap(self, x, y):
        if in_box(x, y, self.ui.accept_zone):
            raise RuntimeError(f"chặn bấm vào vùng nút Nhận nhiệm vụ ({x},{y})")
        self.adb.tap(x, y)

    def ensure_connected(self):
        if self.adb.connected():
            return True
        if self.reconnect:
            import ket_noi                      # cùng thư mục; chỉ cần khi chạy trong Termux
            serial = ket_noi.connect()
            if serial:
                self.adb.set_serial(serial)
                log.info("Đã kết nối lại ADB: %s", serial)
                return self.adb.connected()
        return False

    def check_ready(self):
        if not self.ensure_connected():
            log.warning("Mất kết nối ADB với điện thoại.")
            return None
        if not self.adb.screen_awake():
            log.warning("Màn hình điện thoại đang tắt - hãy mở máy và mở lại bảng Nhiệm Vụ "
                        "(chế độ luôn sáng chỉ có tác dụng khi đang cắm sạc).")
            return None
        if self.adb.game_booster_locked() and not self.unlock_game_booster():
            log.warning("Không mở được lớp khóa màn hình của Game Booster.")
            return None
        img = self.adb.screenshot()
        ui = UI.detect(img, self.tpl)
        if ui is None:
            log.warning("Không thấy màn hình Nhiệm Vụ của Đấu hội - hãy mở lại bảng Nhiệm Vụ.")
            if self.debug:
                save_debug(img, [], "khong-dung-man-hinh", self.max_points)
            return None
        if self.ui is None or (self.ui.s, self.ui.dx, self.ui.dy) != (ui.s, ui.dx, ui.dy):
            log.info("Bố cục màn hình %sx%s: %s", img.shape[1], img.shape[0], ui)
        self.ui = ui
        cancel = find_dialog_button(img, ui.btn_cancel, ui)
        if cancel is not None:
            log.info("Đang có hộp thoại xóa còn mở từ trước - bấm Hủy.")
            self.tap(*cancel)
            img, _ = self.poll(lambda im: find_dialog_button(im, ui.btn_cancel, ui) is None, DIALOG_TIMEOUT)
        return img

    def unlock_game_booster(self):
        """Mở lớp khóa của Game Booster: chạm một lần cho ổ khóa hiện ra rồi kéo ổ khóa ra một đoạn.
        Lớp khóa chặn mọi thao tác chạm nên lần chạm này không tác động vào game."""
        log.info("Màn hình đang bị Game Booster khóa - mở khóa.")
        for _ in range(3):
            img = self.adb.screenshot()
            h, w = img.shape[:2]
            lock = find_lock_icon(img)
            if lock is None:
                self.adb.tap(w // 2, h // 2)
                time.sleep(0.6)
                lock = find_lock_icon(self.adb.screenshot())
            x, y = lock or (w // 2, h // 2)
            self.adb.swipe(x, y, x, y - h // 8, 400)
            time.sleep(0.8)
            if not self.adb.game_booster_locked():
                log.info("Đã mở khóa màn hình.")
                return True
        return False

    def wait_still(self, img):
        """Chụp lặp lại tới khi lưới không đổi giữa 2 lần chụp liên tiếp. Trả về ảnh cuối."""
        last = [img]

        def still(im):
            same = grid_unchanged(last[0], im, self.ui)
            last[0] = im
            return same
        return self.poll(still, SETTLE_TIMEOUT)[0]

    def poll(self, check, timeout):
        """Chụp màn hình lặp lại tới khi check(ảnh) cho kết quả (khác None/False) hoặc hết giờ.
        Trả về (ảnh mới nhất, kết quả)."""
        time.sleep(POLL_FIRST_WAIT)
        deadline = time.monotonic() + timeout
        while True:
            img = self.adb.screenshot()
            result = check(img)
            if result or time.monotonic() >= deadline:
                return img, result

    def scroll_to_top(self, img):
        ui = self.ui
        if at_top(img, ui):
            return img
        for _ in range(8):
            before = img
            for _ in range(3):
                self.adb.flick(ui.drag_gaps[self.gap], ui.y(950), ui.y(1700))
                self.gap = (self.gap + 1) % len(ui.drag_gaps)
                time.sleep(0.1)
            # Chờ tới khi thấy ô số 1 ở vị trí trên cùng (đã hết nảy); nếu danh sách đã dừng mà chưa tới
            # đầu thì vuốt tiếp ngay chứ không chờ hết giờ.
            last = [before]

            def state(im):
                if at_top(im, ui):
                    return "top"
                stopped = grid_unchanged(last[0], im, ui)
                last[0] = im
                return "stopped" if stopped else None
            img, st = self.poll(state, TOP_TIMEOUT)
            if st == "top" or grid_unchanged(before, img, ui):   # dự phòng: vuốt mà không nhúc nhích -> đã ở đầu
                return img
        log.warning("Cuộn lên đầu chưa ổn định, vẫn tiếp tục.")
        return img

    def scroll_down(self, img):
        """Cuộn xuống một bước. Trả về (ảnh mới, trạng thái 'ok' | 'end' | 'lost')."""
        ui = self.ui
        for attempt in range(len(ui.drag_gaps)):
            gap = (self.gap + attempt) % len(ui.drag_gaps)
            self.adb.drag(ui.drag_gaps[gap], ui.y(DRAG_FROM_Y), ui.y(DRAG_TO_Y))
            time.sleep(SCROLL_WAIT)
            new = self.adb.screenshot()
            if not grid_unchanged(img, new, ui):
                if gap != self.gap:
                    log.debug("Khe x=%s không kéo được, đã dùng khe x=%s.",
                              ui.drag_gaps[self.gap], ui.drag_gaps[gap])
                self.gap = (gap + 1) % len(ui.drag_gaps)   # lần vuốt sau dùng khe còn lại
                break
        else:
            return new, "end"                  # kéo ở cả 2 khe vẫn đứng yên: đã tới cuối
        # Bước cuộn bình thường: ~EXPECTED_SHIFT. Khoảng tìm loại trừ vị trí lệch đúng 1 hàng (±269px).
        expected = ui.d(EXPECTED_SHIFT)
        shift, score = grid_shift(img, new, ui, expected - ui.d(200), expected + ui.d(45))
        if score >= SHIFT_MATCH_THRESHOLD:
            log.debug("Đã cuộn %spx (khớp %.3f)", shift, score)
            if shift < expected - ui.d(20):
                # Cuộn không đủ một bước: đã chạm đáy và danh sách đang nảy về. Chờ đứng yên rồi mới dùng ảnh,
                # vì ảnh này còn làm mốc đo cho lần kéo sau.
                new = self.wait_still(new)
            return new, "ok"
        # Cuộn ít hơn một bước: đang chạm cuối danh sách. Ở cuối, danh sách bị kéo lố rồi nảy về (máy tính bảng
        # mất ~1.5-2s), nên chỉ đo khi độ cuộn đo được hợp lệ và giống nhau ở 2 lần chụp liên tiếp.
        last = [None]

        def settled(im):
            shift, score = grid_shift(img, im, ui, -ui.d(20), expected - ui.d(200))
            valid = score >= SHIFT_MATCH_THRESHOLD
            done = valid and last[0] is not None and abs(shift - last[0]) <= ui.d(3)
            last[0] = shift if valid else None
            return (shift, score) if done else None
        new, measured = self.poll(settled, SETTLE_TIMEOUT)
        if measured:
            shift, score = measured
            log.debug("Cuộn ngắn %spx (khớp %.3f)", shift, score)
            return new, ("end" if shift < ui.d(60) else "ok")   # nhích < 1/4 hàng: chỉ là nảy ở cuối
        log.debug("Không đo được độ cuộn sau khi chờ đứng yên.")
        return new, "lost"                     # trượt quá đà, có thể sót hàng

    def delete_task(self, task):
        """Chọn ô, kiểm tra lại trong khung chi tiết, bấm Xóa + Xác nhận.
        Trả về (đã xóa hay chưa, ảnh màn hình mới nhất - dùng tiếp làm trạng thái hiện tại)."""
        ui = self.ui

        def selected(im):
            panel = read_panel_points(im, ui)
            return panel if is_selected(im, task, ui) and panel.value == task.reading.value else None

        def card_gone(im):
            tol = ui.d(15)
            return (find_dialog_button(im, ui.btn_cancel, ui) is None and
                    not any(abs(x - task.x) < tol and abs(y - task.y) < tol
                            for x, y, _ in find_compasses(im, ui.grid, ui)))

        def after_delete_tap(im):
            confirm = find_dialog_button(im, ui.btn_confirm, ui)
            if confirm is not None:
                return "confirm", confirm
            # Không có hộp thoại: có thể đã tick "Hôm nay không nhắc nữa" -> xóa luôn.
            return ("gone", None) if card_gone(im) else None

        for attempt in (1, 2):                  # game đôi khi bỏ qua một lần chạm
            self.tap(*task.tap_point)
            img, panel = self.poll(selected, SELECT_TIMEOUT)
            if panel:
                break
            log.debug("  Lần chạm %s chưa chọn được ô.", attempt)
        else:
            log.info("  Bỏ qua: không chọn được ô này (khung chi tiết hiện %s điểm).",
                     read_panel_points(img, ui).value)
            return False, img
        btn = has_delete_button(img, ui)
        if btn is None:
            log.info("  Bỏ qua: khung chi tiết không có nút Xóa (có thể vừa có người nhận).")
            return False, img
        if decide(panel, self.max_points) != "xoa":
            log.info("  Bỏ qua: khung chi tiết đọc được %s điểm [%s].", panel.value, panel.detail)
            return False, img
        if self.dry_run:
            log.info("  [chạy thử] Sẽ xóa nhiệm vụ %s điểm.", panel.value)
            return False, img
        self.tap(*btn)
        img, step = self.poll(after_delete_tap, DIALOG_TIMEOUT)
        if step and step[0] == "confirm":
            self.tap(*step[1])
            img, gone = self.poll(card_gone, DELETED_TIMEOUT)
        else:
            gone = bool(step)
        if not gone:
            log.warning("  Đã bấm Xóa%s nhưng ô vẫn còn nhiệm vụ.", " + Xác nhận" if step else "")
            if self.debug:
                save_debug(img, [], "xoa-khong-thanh-cong", self.max_points, ui)
            return False, img
        log.info("  Đã xóa nhiệm vụ %s điểm.", panel.value)
        return True, img

    def run_cycle(self):
        """Quét một lượt. Trả về False nếu chưa quét được (chưa ở bảng Nhiệm Vụ, mất kết nối...)."""
        img = self.check_ready()
        if img is None:
            return False
        self.stats = {"deleted": 0, "unreadable": 0, "failures": 0}
        for restart in range(3):
            img = self.scroll_to_top(img)
            status = self.scan_pages(img)
            if status != "lost":
                break
            log.info("Danh sách bị trượt quá đà, quét lại từ đầu để không sót ô.")
            img = self.adb.screenshot()
        else:
            log.warning("Cuộn không ổn định, lượt này có thể đã sót một số ô.")
        deleted = self.stats["deleted"]
        self.total_deleted += deleted
        log.info("Xong lượt: đã xóa %s, không đọc được %s. Tổng đã xóa từ lúc chạy: %s.",
                 deleted, self.stats["unreadable"], self.total_deleted)
        return True

    def scan_pages(self, img):
        """Duyệt từ vị trí hiện tại tới cuối danh sách. Trả về 'end' | 'lost' | 'abort'."""
        for page in range(60):
            tasks = find_tasks(img, self.ui)
            if self.debug:
                save_debug(img, tasks, f"trang{page:02d}", self.max_points, self.ui)
            touched = False                    # đã bấm vào ô nào trên trang này chưa
            for t in tasks:
                verdict = decide(t.reading, self.max_points)
                if verdict == "khong-chac":
                    self.stats["unreadable"] += 1
                    log.info("Không chắc điểm ở ô tại (%s,%s) [%s] - bỏ qua.", t.x, t.y, t.reading.detail)
                    continue
                if verdict == "giu":
                    continue
                log.info("Tìm thấy nhiệm vụ %s điểm tại (%s,%s).", t.reading.value, t.x, t.y)
                touched = True
                deleted, latest = self.delete_task(t)
                if deleted:
                    self.stats["deleted"] += 1
                    self.stats["failures"] = 0
                    if self.max_deletes is not None and self.stats["deleted"] >= self.max_deletes:
                        log.info("Đã đủ %s lần xóa cho lượt này (--max-deletes).", self.max_deletes)
                        return "abort"
                elif not self.dry_run:
                    self.stats["failures"] += 1
                    if self.stats["failures"] >= 3:
                        log.error("3 lần xóa liên tiếp không thành công - dừng lượt này.")
                        return "abort"
            img, status = self.scroll_down(latest if touched else img)
            if status != "ok":
                return status
        return "end"


# ---------------------------------------------------------------- main

def analyze_image(path, templates, max_points):
    img = load_image(path)
    ui = UI.detect(img, templates)
    if ui is None:
        print("KHÔNG thấy màn hình Nhiệm Vụ (không tìm được tab).")
        return
    print(f"Màn hình Nhiệm Vụ {img.shape[1]}x{img.shape[0]}: {ui}, "
          f"hộp thoại xóa: Xác nhận={find_dialog_button(img, ui.btn_confirm, ui)} "
          f"Hủy={find_dialog_button(img, ui.btn_cancel, ui)}")
    tasks = find_tasks(img, ui)
    for t in tasks:
        r = t.reading
        print(f"  ô la bàn ({t.x},{t.y}): {r.value} điểm (khả dĩ {r.low}..{r.high}) [{r.detail}]"
              f" -> {decide(r, max_points)}, đang chọn={is_selected(img, t, ui)}")
    p = read_panel_points(img, ui)
    print(f"Khung chi tiết: {p.value} điểm [{p.detail}] -> {decide(p, max_points)}, "
          f"nút Xóa: {has_delete_button(img, ui)}")
    print(f"Ảnh đánh dấu: {save_debug(img, tasks, Path(path).stem, max_points, ui)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--interval", type=int, default=900, help="giây giữa 2 lượt quét (mặc định 900)")
    ap.add_argument("--max-points", type=int, default=12, help="xóa nhiệm vụ có điểm <= số này (mặc định 12)")
    ap.add_argument("--once", action="store_true", help="chỉ quét 1 lượt rồi thoát")
    ap.add_argument("--dry-run", action="store_true", help="chạy thử: chỉ báo, không bấm Xóa")
    ap.add_argument("--max-deletes", type=int, help="xóa tối đa bấy nhiêu nhiệm vụ mỗi lượt")
    ap.add_argument("--debug", action="store_true", help="lưu ảnh đánh dấu vào debug/")
    ap.add_argument("--serial", help="serial thiết bị (adb devices)")
    ap.add_argument("--adb", default=DEFAULT_ADB, help="đường dẫn adb")
    ap.add_argument("--tu-ket-noi", action="store_true",
                    help="chạy ngay trên máy (Termux): tự tìm lại cổng Gỡ lỗi không dây khi mất kết nối")
    ap.add_argument("--from-image", help="chỉ nhận diện trên 1 ảnh có sẵn rồi thoát")
    args = ap.parse_args()

    # Màn hình: thông tin chính (chi tiết khi --debug). File log: luôn ghi chi tiết để tra lỗi, xoay vòng 3 x 2MB.
    console = logging.StreamHandler(sys.stdout)
    console.setLevel(logging.DEBUG if args.debug else logging.INFO)
    logfile = logging.handlers.RotatingFileHandler(BASE / "xoa_nhiem_vu.log", maxBytes=2_000_000,
                                                   backupCount=2, encoding="utf-8")
    logfile.setLevel(logging.DEBUG)
    logging.basicConfig(level=logging.DEBUG, format="%(asctime)s %(message)s", datefmt="%H:%M:%S",
                        handlers=[console, logfile])

    templates = Templates()
    if args.from_image:
        analyze_image(args.from_image, templates, args.max_points)
        return

    adb = Adb(args.adb, args.serial)
    bot = Bot(adb, templates, args.max_points, args.dry_run, args.debug, args.max_deletes, args.tu_ket_noi)
    if not bot.ensure_connected():
        sys.exit("Không thấy điện thoại qua ADB. Kiểm tra 'adb devices'.")

    # Giữ màn hình sáng trong lúc chạy, trả lại cài đặt cũ khi thoát.
    old_stay_on = adb.get_stay_on()
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    adb.shell("svc power stayon true")
    if not adb.charging():
        log.warning("Điện thoại không cắm sạc: màn hình vẫn có thể tự tắt. Nên cắm sạc khi chạy lâu.")
    log.info("Bắt đầu. Xóa nhiệm vụ <= %s điểm%s. Bấm Ctrl+C để dừng.",
             args.max_points, " (CHẠY THỬ)" if args.dry_run else "")
    try:
        started = time.monotonic()
        while True:
            try:
                ran = bot.run_cycle()
            except AdbError as e:
                log.warning("Lỗi ADB: %s", e)
                ran = False
            if args.once and (ran or time.monotonic() - started > ONCE_WAIT_LIMIT):
                break
            # Chưa quét được (vd. vừa khởi động trong Termux, game chưa ở bảng Nhiệm Vụ): thử lại sớm.
            delay = args.interval if ran else NOT_READY_RETRY
            if ran:
                log.info("Lượt sau lúc %s.", time.strftime("%H:%M:%S", time.localtime(time.time() + delay)))
            else:
                log.info("Thử lại sau %s giây.", delay)
            time.sleep(delay)
    except KeyboardInterrupt:
        log.info("Dừng theo yêu cầu.")
    finally:
        try:
            adb.set_stay_on(old_stay_on if old_stay_on not in ("", "null") else 0)
            log.info("Đã trả lại cài đặt giữ màn hình sáng (%s).", old_stay_on)
        except AdbError as e:
            log.warning("Không trả lại được cài đặt màn hình: %s", e)


if __name__ == "__main__":
    main()
