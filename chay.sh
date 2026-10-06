#!/data/data/com.termux/files/usr/bin/bash
# Chạy chương trình ngay trên máy tính bảng (Termux), không cần máy tính.
# Cần bật: Cài đặt > Tùy chọn nhà phát triển > Gỡ lỗi không dây (đã ghép nối Termux một lần bằng "adb pair").
# Dùng: ./chay.sh            (chạy liên tục, tham số khác giống xoa_nhiem_vu.py, ví dụ ./chay.sh --interval 600)
cd "$(dirname "$0")" || exit 1

termux-wake-lock 2>/dev/null    # giữ Termux chạy khi màn hình đang mở game

connected() { adb devices | awk 'NR > 1 && $2 == "device" {print $1; exit}'; }

serial=$(connected)
if [ -z "$serial" ]; then
    # Cổng của Gỡ lỗi không dây đổi mỗi lần bật lại: dò cổng trên chính máy rồi kết nối.
    echo "Đang tìm cổng Gỡ lỗi không dây..."
    serial=$(python ket_noi.py)
fi
if [ -z "$serial" ]; then
    echo "Chưa kết nối được ADB tới máy này. Kiểm tra đã bật Cài đặt > Tùy chọn nhà phát triển > Gỡ lỗi không dây,"
    echo "hoặc xem dòng 'Địa chỉ IP và cổng' rồi gõ:  adb connect 127.0.0.1:<cổng>  và chạy lại ./chay.sh"
    exit 1
fi

echo "Đã kết nối ADB: $serial. Hãy chuyển sang game và mở bảng Nhiệm Vụ."
exec python xoa_nhiem_vu.py --serial "$serial" --tu-ket-noi "$@"
