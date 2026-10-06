package vn.hoavien.auto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import vn.hoavien.auto.vision.Ui
import vn.hoavien.auto.vision.findLockIcon
import vn.hoavien.auto.vision.tabBrightness

// Lớp "GameBooster Lock Screen" của Samsung Game Booster: tự bật khi để yên lâu trong game, làm tối màn hình
// và chặn mọi thao tác chạm cho tới khi kéo biểu tượng ổ khóa. Trợ năng không thấy cửa sổ này trong danh sách
// cửa sổ, nên nhận ra bằng độ sáng: tab "Nhiệm Vụ" sáng ~1 bình thường, ~0.4 khi có hộp thoại của game,
// ~0.2-0.3 khi bị khóa (đo trên Tab S7).
const val LOCKED_BRIGHTNESS = 0.35

/** Độ sáng tab "Nhiệm Vụ" so với mẫu, null nếu không thấy bảng Nhiệm Vụ. */
suspend fun BotService.brightness(img: Mat): Double? = withContext(Dispatchers.Default) {
    Ui.detect(img, templates)?.let { tabBrightness(img, it) }
}

/** Mở lớp khóa: chạm một lần cho ổ khóa hiện ra rồi kéo ổ khóa lên một đoạn.
 *  Lớp khóa chặn mọi thao tác chạm nên lần chạm này không tác động vào game. */
suspend fun BotService.unlockGameBooster(): Boolean {
    AppLog.i("Màn hình đang bị che tối (Game Booster khóa?) - thử mở khóa.")
    for (attempt in 1..3) {
        val img = screenshot().img
        val w = img.cols()
        val h = img.rows()
        var lock = withContext(Dispatchers.Default) { findLockIcon(img) }
        img.release()
        if (lock == null) {
            tap(w / 2, h / 2)
            delay(600)
            val again = screenshot().img
            lock = withContext(Dispatchers.Default) { findLockIcon(again) }
            saveImage(again, "khoa-sau-cham")
            again.release()
        }
        val (x, y) = lock ?: (w / 2 to h / 2)
        swipe(x, y, x, y - h / 8, 400)
        delay(800)
        val after = screenshot().img
        val bright = brightness(after)
        saveImage(after, "khoa-sau-keo")
        after.release()
        if (bright == null || bright >= LOCKED_BRIGHTNESS) {
            AppLog.i("Đã mở khóa màn hình (lần $attempt).")
            return true
        }
        AppLog.i("  Lần $attempt: kéo ổ khóa xong vẫn còn khóa.")
    }
    return false
}
