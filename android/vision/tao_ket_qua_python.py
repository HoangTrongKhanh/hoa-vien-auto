#!/usr/bin/env python3
"""Ghi kết quả nhận diện của bản Python (xoa_nhiem_vu.py) trên mọi ảnh trong samples/ ra
src/test/resources/ket_qua_python.tsv, để kiểm thử bản Kotlin cho ra kết quả giống hệt.

Chạy lại mỗi khi sửa thuật toán bên Python hoặc thêm ảnh mẫu:
    ../../.venv/bin/python tao_ket_qua_python.py
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT))

import xoa_nhiem_vu as x  # noqa: E402

OUT = HERE / "src/test/resources/ket_qua_python.tsv"


def num(v):
    return "-" if v is None else str(v)


def pos(p):
    return "none" if p is None else f"{p[0]},{p[1]}"


def main():
    tpl = x.Templates()
    rows = []
    images = sorted(p for p in (ROOT / "samples").rglob("*.png"))
    for path in images:
        rel = path.relative_to(ROOT).as_posix()
        img = x.load_image(path)
        lock = x.find_lock_icon(img)
        rows.append(["lock", rel, "none" if lock is None else f"{lock[0]},{lock[1]}"])
        ui = x.UI.detect(img, tpl)
        if ui is None:
            rows.append(["ui", rel, "none"])
            continue
        rows.append(["ui", rel, f"{ui.s:.6f}", f"{ui.dx:.1f}", f"{ui.dy:.1f}"])
        tasks = x.find_tasks(img, ui)
        rows.append(["tasks", rel, ";".join(
            f"{t.x},{t.y},{int(x.is_selected(img, t, ui))},{num(t.reading.value)},{num(t.reading.low)},{num(t.reading.high)}"
            for t in tasks)])
        p = x.read_panel_points(img, ui)
        rows.append(["panel", rel, num(p.value), num(p.low), num(p.high)])
        rows.append(["delete", rel, pos(x.has_delete_button(img, ui))])
        rows.append(["dialog", rel, pos(x.find_dialog_button(img, ui.btn_confirm, ui)),
                     pos(x.find_dialog_button(img, ui.btn_cancel, ui))])
        rows.append(["attop", rel, str(int(x.at_top(img, ui)))])
        th, tw = ui.tab.shape[:2]
        tx, ty = ui.x(x.REF_TAB_POS[0]), ui.y(x.REF_TAB_POS[1])
        gray = x.cv2.cvtColor
        ratio = (gray(img[ty:ty + th, tx:tx + tw], x.cv2.COLOR_BGR2GRAY).mean()
                 / gray(ui.tab, x.cv2.COLOR_BGR2GRAY).mean())
        rows.append(["bright", rel, f"{ratio:.4f}"])

    pages = sorted((ROOT / "samples/pages").glob("p*.png"))
    for a, b in zip(pages, pages[1:]):
        before, after = x.load_image(a), x.load_image(b)
        ui = x.UI.detect(after, tpl)
        for band in (250, 150):
            x.SHIFT_BAND = band
            shift, score = x.grid_shift(before, after, ui)
            rows.append(["shift", a.relative_to(ROOT).as_posix(), b.relative_to(ROOT).as_posix(),
                         str(band), str(shift), f"{score:.4f}"])
        x.SHIFT_BAND = 250
        rows.append(["unchanged", a.relative_to(ROOT).as_posix(), b.relative_to(ROOT).as_posix(),
                     str(int(x.grid_unchanged(before, after, ui)))])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")
    print(f"Đã ghi {len(rows)} dòng vào {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
