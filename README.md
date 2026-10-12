# Hoa Viên Auto

Tự động xóa các nhiệm vụ Đấu hội có điểm thấp trong game Hoa Viên (`com.myflower.gp`), để game làm mới ô đó bằng nhiệm vụ khác.

Cứ mỗi 15 phút, app chụp màn hình, tìm các ô có số điểm ≤ ngưỡng (mặc định 12), rồi bấm **Xóa nhiệm vụ** và **Xác nhận** thay người chơi.

Repo có hai bản dùng chung một thuật toán nhận diện:

| Bản | Thư mục / file | Điều khiển máy bằng | Dành cho |
|---|---|---|---|
| **App Android** (bản chính, v1.0) | [`android/`](android/) | Dịch vụ Trợ năng (AccessibilityService) | Người dùng cuối, cài bằng file APK |
| **Script Python** (bản gốc) | [`xoa_nhiem_vu.py`](xoa_nhiem_vu.py) | ADB, từ máy tính hoặc Termux | Phát triển; làm bản tham chiếu để kiểm thử bản Android |

## Cách hoạt động

- **Nhìn bằng ảnh chụp.** App không đọc dữ liệu bên trong game. Nó chụp màn hình rồi nhận diện bằng OpenCV: khớp ảnh mẫu để tìm ô, nút và hộp thoại; so khuôn chữ số để đọc điểm. Không dùng thư viện OCR hay mô hình học máy.
- **Đo một lần, quy đổi cho mọi máy.** Mọi tọa độ được đo trên máy gốc rộng 1080 px (Galaxy A54). Trên máy khác, app tìm tab “Nhiệm Vụ” rồi tự tính hệ số phóng to và độ dịch.
- **Bấm xong luôn chụp lại để kiểm tra.** App không chờ một khoảng cố định sau mỗi cú chạm. Nó chụp liên tục tới khi màn hình đổi đúng như mong đợi, hoặc hết giờ.
- **Không chắc thì không xóa.** Một ô chỉ bị xóa khi:
  - số cạnh la bàn và số cạnh viên kim cương đọc ra giống nhau và đều ≤ ngưỡng;
  - đọc lại ở khung chi tiết vẫn cho kết quả đó.

  App không bao giờ bấm vào vùng nút **Nhận nhiệm vụ**.

## Yêu cầu

- Android 11 trở lên (API 30).
- Đã thử trên Galaxy Tab S7 (Android 13, 1600×2560) và Galaxy A54 (Android 15, 1080×2340).
- Màn hình phải luôn mở game ở bảng Nhiệm Vụ của Đấu hội. App tự giữ màn hình sáng khi bảng nổi đang hiện.

## Cài đặt cho người dùng

1. Cài file `HoaVienAuto-<phiên bản>.apk`. Trên máy Samsung sẽ có cảnh báo “Không nên cài đặt” và Play Protect; chọn tiếp tục cài.
2. Mở app, bấm nút mở Cài đặt Trợ năng, rồi bật **Hoa Viên Auto**.
   - Trên Android 13+, lần đầu có thể bị chặn với thông báo “Ứng dụng đã bị từ chối cấp quyền”. Khi đó vào Thông tin ứng dụng → menu ⋮ → **Cho phép cài đặt bị hạn chế**, rồi bật lại.
3. Cho phép thông báo, để app báo khi cần bạn xử lý (game bị thoát, pin yếu…).
4. Mở game ở bảng Nhiệm Vụ. Bấm **Thử 1 lượt** trên bảng nổi để xem app định xóa những ô nào (không xóa thật). Khi thấy đúng, bấm **▶ Chạy**.

Hướng dẫn có hình nằm ở [`huong_dan/index.html`](huong_dan/index.html).

## Cấu trúc thư mục

```
android/                  App Android (Gradle)
  app/                    Dịch vụ Trợ năng, logic bot, bảng nổi, màn hình cài đặt
  vision/                 Nhận diện ảnh (Kotlin/JVM thuần, test được trên máy tính)
templates/                Ảnh mẫu dùng chung cho cả hai bản (đóng gói làm assets của app)
samples/                  Ảnh chụp thật để kiểm thử; labels.txt là nhãn ghi tay
huong_dan/                Trang hướng dẫn cài đặt cho người dùng
xoa_nhiem_vu.py           Bản Python gốc
tao_mau_huy_hieu.py       Sinh mẫu chữ số của huy hiệu số thứ tự ô từ samples/pages
ket_noi.py, chay.sh       Chạy bản Python ngay trên máy qua Termux + Gỡ lỗi không dây
```

## Phát triển app Android

Cần JDK 17 và Android SDK (compileSdk 36).

```bash
cd android
./gradlew assembleDebug                 # build bản debug
./gradlew :vision:test                  # kiểm thử phần nhận diện, chạy trên máy tính
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s HoaVien                   # xem nhật ký của app
```

### Kiểm thử đối chiếu với bản Python

Phần nhận diện của app được chuyển 1:1 từ bản Python. Bài test `PythonParityTest` chạy bản Kotlin trên mọi ảnh trong `samples/` và bắt kết quả phải giống bản Python. Kết quả của bản Python lưu sẵn trong `android/vision/src/test/resources/ket_qua_python.tsv`.

Khi sửa thuật toán nhận diện:

1. Sửa trong `xoa_nhiem_vu.py` trước.
2. Sinh lại kết quả tham chiếu: `cd android && ../.venv/bin/python vision/tao_ket_qua_python.py`
3. Sửa bản Kotlin trong `android/vision/` cho giống.
4. Chạy `./gradlew :vision:test` cho tới khi qua hết.

### Phát hành

1. Tăng `versionCode` và `versionName` trong `android/app/build.gradle.kts`.
2. Tạo file `android/keystore.properties` trỏ tới khóa ký `.jks`, với các khóa `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
   - Khóa ký và file này **không** nằm trong repo. Mất khóa thì người dùng không cài đè được bản mới.
3. Chạy `./gradlew phatHanh`. File `HoaVienAuto-<phiên bản>.apk` sẽ xuất hiện ở thư mục gốc repo.

## Bản Python (ADB)

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python xoa_nhiem_vu.py --once --dry-run          # quét 1 lượt, chỉ báo, không xóa
.venv/bin/python xoa_nhiem_vu.py                           # chạy liên tục, 15 phút/lượt
.venv/bin/python xoa_nhiem_vu.py --from-image samples/screen1.png   # chỉ nhận diện trên 1 ảnh
```

Xem `--help` để biết các tùy chọn khác (`--max-points`, `--interval`, `--max-deletes`, `--debug`, `--serial`). Muốn chạy ngay trên máy tính bảng thì dùng `./chay.sh` trong Termux, sau khi đã ghép nối Gỡ lỗi không dây.
