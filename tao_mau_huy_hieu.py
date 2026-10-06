#!/usr/bin/env python3
"""Tạo mẫu huy hiệu số thứ tự ô (templates/badge_blank.png, templates/badge_digits/) từ samples/pages.

Các trang p00..p14 là ảnh chụp liên tiếp khi cuộn danh sách: ghép vùng lưới của chúng theo độ cuộn đo được thành
một dải dài, trong đó huy hiệu chạy liền 1, 2, 3... theo hàng 3 ô - tức là biết sẵn đáp án của từng huy hiệu.
Chạy lại khi thêm ảnh mẫu:  .venv/bin/python tao_mau_huy_hieu.py
"""
import shutil
from pathlib import Path

import cv2
import numpy as np

import xoa_nhiem_vu as x

BASE = Path(__file__).resolve().parent
OUT = BASE / "templates"
PAGES = 15                      # p00..p14 (p14 là cuối danh sách lúc chụp)
FIRST_Y, ROW_PITCH = 28, 266    # huy hiệu hàng đầu và khoảng cách hàng trong dải (px máy gốc)
COLS = [c - x.GRID[0] for c in x.BADGE_COLS]
TW, TH = 43, 44
PER_DIGIT = 8                   # giữ tối đa bấy nhiêu mẫu mỗi chữ số


def main():
    base = x.Templates()
    pages = [x.load_image(BASE / f"samples/pages/p{i:02d}.png") for i in range(PAGES)]
    ui = x.UI.detect(pages[0], base)
    gx0, gy0, gx1, gy1 = x.GRID
    strip = [pages[0][gy0:gy1, gx0:gx1]]
    for a, b in zip(pages, pages[1:]):
        shift, _ = x.grid_shift(a, b, ui)
        strip.append(b[gy1 - shift:gy1, gx0:gx1])
    strip = np.vstack(strip)
    count = (strip.shape[0] - FIRST_Y - TH) // ROW_PITCH * 3 + 3

    crops = {}
    for n in range(1, count + 1):
        r, c = divmod(n - 1, 3)
        y = FIRST_Y + ROW_PITCH * r
        crops[n] = strip[y:y + TH, COLS[c]:COLS[c] + TW]
    blank = np.mean([c.astype(np.float32) for c in crops.values()], axis=0).astype(np.uint8)
    cv2.imwrite(str(OUT / "badge_blank.png"), blank)

    shutil.rmtree(OUT / "badge_digits", ignore_errors=True)
    (OUT / "badge_digits").mkdir()
    kept = {}
    for n, c in crops.items():
        comps = x.badge_components(c)
        if len(comps) != len(str(n)):
            print(f"bỏ qua huy hiệu {n}: tách được {len(comps)} chữ số")
            continue
        for d, m in zip(str(n), comps):
            mk = x.normalize_mask(m)
            lst = kept.setdefault(int(d), [])
            if len(lst) < PER_DIGIT and not any(np.array_equal(mk, k) for k, _ in lst):
                lst.append((mk, x.mask_holes(m)))
    for d, lst in sorted(kept.items()):
        for i, (mk, h) in enumerate(lst):
            cv2.imwrite(str(OUT / "badge_digits" / f"{d}_{h}_{i}.png"), mk.astype(np.uint8) * 255)
    print(f"Dải {strip.shape[0]}px, {count} huy hiệu; mẫu chữ số: {({d: len(v) for d, v in sorted(kept.items())})}")


if __name__ == "__main__":
    main()
