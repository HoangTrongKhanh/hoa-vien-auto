package vn.hoavien.auto

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import vn.hoavien.auto.vision.SHIFT_MATCH_THRESHOLD
import vn.hoavien.auto.vision.Shift
import vn.hoavien.auto.vision.Ui
import vn.hoavien.auto.vision.atTop
import vn.hoavien.auto.vision.findCards
import vn.hoavien.auto.vision.findLockIcon
import vn.hoavien.auto.vision.gridShift
import vn.hoavien.auto.vision.gridUnchanged
import vn.hoavien.auto.vision.isSelected
import vn.hoavien.auto.vision.region
import vn.hoavien.auto.vision.tabBrightness
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DRAG_FROM_Y = 1610            // điểm bắt đầu kéo (px máy gốc), như bản Python
// Dải dùng để đo độ cuộn trong bài thử kéo: hẹp hơn bản chính (250) để đo được tới ~680px máy gốc,
// vì chưa biết cử chỉ Trợ năng cuộn được bao xa.
private const val PROBE_SHIFT_BAND = 150
private const val SETTLE_TIMEOUT_MS = 4500L
private const val LOCK_WATCH_MS = 15 * 60_000L
private const val LOCKED_BRIGHTNESS = 0.35      // tab sáng ~1 bình thường, ~0.4 có hộp thoại, ~0.2-0.3 Game Booster khóa
private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** Các bài đo của bản thử. Mỗi bài trả lời một câu hỏi: điều khiển game bằng Trợ năng có thay được ADB không. */
class Probes(private val svc: BotService) {
    private val prefs = Prefs(svc)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var gap = 0                         // khe dùng cho lần kéo tới (đổi khe sau mỗi lần kéo)

    /** Gọi trên luồng chính với tên bài đang chạy, null khi đã xong. */
    var onStateChanged: (String?) -> Unit = {}

    private fun start(name: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        val j = svc.scope.launch {
            AppLog.i("▶ $name")
            try {
                block()
            } catch (e: CancellationException) {
                AppLog.i("Đã dừng.")
                throw e
            } catch (e: Exception) {
                AppLog.e("Lỗi: ${e.message ?: e}", e)
            }
        }
        job = j
        onStateChanged(name)
        j.invokeOnCompletion { main.post { if (job === j) onStateChanged(null) } }
    }

    fun stop() {
        job?.cancel()
    }

    // ---------------------------------------------------------------- Tiện ích

    private fun now() = SystemClock.uptimeMillis()

    private suspend fun <T> cpu(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    private suspend fun shot(): Mat = svc.screenshot().img

    /** Ảnh chụp màn hình và bố cục; null (đã báo) nếu không ở bảng Nhiệm Vụ. Người gọi giải phóng ảnh. */
    private suspend fun screenWithUi(): Pair<Mat, Ui>? {
        val img = shot()
        val ui = cpu { Ui.detect(img, svc.templates) }
        if (ui == null) {
            AppLog.i("Không thấy bảng Nhiệm Vụ của Đấu hội - hãy mở game ở bảng Nhiệm Vụ rồi thử lại.")
            save(img, "khong-thay-bang")
            img.release()
            return null
        }
        return img to ui
    }

    /** Chụp lặp lại tới khi lưới không đổi giữa 2 lần chụp liên tiếp (so với prev ở lần đầu) hoặc hết giờ.
     *  Không giải phóng prev. Trả về (ảnh cuối, đã đứng yên chưa). */
    private suspend fun waitStill(prev: Mat, ui: Ui, timeoutMs: Long = SETTLE_TIMEOUT_MS): Pair<Mat, Boolean> {
        val deadline = now() + timeoutMs
        var last: Mat? = null
        while (true) {
            val img = shot()
            val still = cpu { gridUnchanged(last ?: prev, img, ui) }
            last?.release()
            last = img
            if (still || now() >= deadline) return img to still
        }
    }

    /** Lưu ảnh vào files/debug/ (nếu bật trong màn hình chính) để phân tích sau bằng bản Python. */
    private fun save(img: Mat, name: String) {
        if (!prefs.saveImages) return
        val copy = img.clone()
        svc.scope.launch(Dispatchers.IO) {
            val dir = File(svc.getExternalFilesDir(null), "debug").apply { mkdirs() }
            Imgcodecs.imwrite(File(dir, "${LocalDateTime.now().format(FILE_TIME)}-$name.png").path, copy)
            copy.release()
        }
    }

    private fun refPx(ui: Ui, px: Int) = (px / ui.s).roundToInt()

    // ---------------------------------------------------------------- 1. Chụp màn hình

    /** Tốc độ chụp màn hình (ADB trước đây ~700ms/ảnh), nhận diện bảng, bảng nổi có lọt vào ảnh không. */
    fun captureProbe() = start("Đo chụp màn hình") {
        val dm = svc.resources.displayMetrics
        AppLog.i("Máy ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "màn hình ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
        val n = 8
        val t0 = now()
        var img: Mat? = null
        for (i in 1..n) {
            val s = svc.screenshot()
            img?.release()
            img = s.img
            AppLog.i("  Ảnh $i: ${s.img.cols()}x${s.img.rows()}, chờ hệ thống ${s.waitMs}ms, chuyển ảnh ${s.convertMs}ms" +
                (if (i > 1) ", cách ảnh trước ${s.sincePrevMs}ms" else "") +
                (if (s.retries > 0) ", bị từ chối ${s.retries} lần vì chụp quá dày" else ""))
        }
        val last = img!!
        AppLog.i("→ Chụp liên tục: trung bình ${(now() - t0) / n}ms/ảnh (ADB qua Termux trước đây ~700ms/ảnh).")

        val t1 = now()
        val ui = cpu { Ui.detect(last, svc.templates) }
        AppLog.i("Nhận diện bố cục (${now() - t1}ms): ${ui ?: "KHÔNG thấy bảng Nhiệm Vụ"}")
        if (ui != null) {
            val t2 = now()
            val cards = cpu { findCards(last, ui) }
            val top = cpu { atTop(last, ui) }
            val bright = cpu { tabBrightness(last, ui) }
            AppLog.i("Tìm ô nhiệm vụ (${now() - t2}ms): ${cards.size} ô có la bàn; " +
                "đang ở đầu danh sách: ${if (top) "có" else "không"}; độ sáng tab ${"%.2f".format(bright)}.")
        }
        save(last, "chup")
        last.release()
        overlayInShotCheck()
    }

    /** Chụp khi ẩn rồi khi hiện bảng nổi, so vùng nút ≡ giữa 2 ảnh. (Không so màu cố định được: máy Samsung vẽ
     *  bảng nổi hơi trong suốt nên màu trong ảnh bị trộn với game phía sau.) */
    private suspend fun overlayInShotCheck() {
        val box = svc.overlayHandle() ?: return
        svc.setOverlayVisible(false)
        val hidden = try {
            delay(200)
            shot()
        } finally {
            svc.setOverlayVisible(true)
        }
        delay(200)
        val shown = shot()
        val diff = cpu {
            val d = Mat()
            hidden.region(box) { a -> shown.region(box) { b -> Core.absdiff(a, b, d) } }
            Core.mean(d).`val`.take(3).average().also { d.release() }
        }
        hidden.release()
        shown.release()
        AppLog.i(if (diff > 8) "Bảng nổi CÓ lọt vào ảnh chụp (chênh ${"%.0f".format(diff)}): phải đặt bảng nổi ngoài vùng tab, lưới và khung chi tiết."
                 else "Bảng nổi KHÔNG lọt vào ảnh chụp (chênh ${"%.0f".format(diff)}).")
    }

    // ---------------------------------------------------------------- 2. Chạm

    /** Chạm vào một ô nhiệm vụ (chỉ chọn ô, không xóa, không nhận) và chờ viền xanh hiện ra. */
    fun tapProbe() = start("Thử chạm chọn ô") {
        val (img, ui) = screenWithUi() ?: return@start
        val cards = cpu { findCards(img, ui) }
        val card = cpu { cards.firstOrNull { !isSelected(img, it, ui) } }
        img.release()
        if (card == null) {
            AppLog.i(if (cards.isEmpty()) "Không có ô nhiệm vụ (có la bàn) nào trên màn hình. Hãy cuộn tới chỗ có ô nhiệm vụ."
                     else "Chỉ có ô đang được chọn sẵn. Hãy cuộn tới chỗ có ô nhiệm vụ khác.")
            return@start
        }
        if (ui.acceptZone.contains(card.tapX, card.tapY)) {
            AppLog.i("Chặn: điểm chạm (${card.tapX},${card.tapY}) nằm trong vùng nút Nhận nhiệm vụ.")
            return@start
        }
        val t0 = now()
        val ok = svc.tap(card.tapX, card.tapY)
        val gestureMs = now() - t0
        delay(150)
        val deadline = now() + 2000
        var selected: Boolean
        var after: Mat
        do {
            val im = shot()
            after = im
            selected = cpu { isSelected(im, card, ui) }
            if (selected || now() >= deadline) break
            im.release()
        } while (true)
        AppLog.i(if (selected) "→ Chạm ĐƯỢC: ô tại (${card.x},${card.y}) đã được chọn sau ${now() - t0}ms (cử chỉ ${gestureMs}ms)."
                 else "→ Chạm KHÔNG ăn: sau 2 giây ô tại (${card.x},${card.y}) vẫn chưa được chọn" +
                     (if (!ok) " (hệ thống huỷ cử chỉ)." else "."))
        save(after, "cham")
        after.release()
    }

    // ---------------------------------------------------------------- 3. Kéo

    /** Kéo cuộn danh sách một bước theo thông số ở màn hình chính, đo độ cuộn ngay khi nhả và khi đã đứng yên. */
    fun dragProbe() = start("Thử kéo cuộn danh sách") {
        val (first, ui) = screenWithUi() ?: return@start
        val (before, _) = waitStill(first, ui)      // đo từ trạng thái đứng yên
        first.release()
        val dist = prefs.dragDistance
        val moveMs = prefs.dragMs.toLong()
        val holdMs = prefs.holdMs.toLong()
        val y0 = ui.y(DRAG_FROM_Y)
        val y1 = ui.y(DRAG_FROM_Y - dist)

        var moved: Mat? = null
        var x = 0
        var gestureMs = 0L
        for (attempt in ui.dragGaps.indices) {
            val gi = (gap + attempt) % ui.dragGaps.size
            x = ui.dragGaps[gi]
            val t0 = now()
            val ok = svc.drag(x, y0, y1, moveMs, holdMs)
            gestureMs = now() - t0
            if (!ok) AppLog.i("  Hệ thống huỷ cử chỉ kéo ở khe x=$x.")
            val img = shot()
            if (!cpu { gridUnchanged(before, img, ui) }) {
                moved = img
                gap = (gi + 1) % ui.dragGaps.size
                break
            }
            AppLog.i("  Kéo ở khe x=$x không làm danh sách nhúc nhích.")
            img.release()
        }
        val after = moved ?: run {
            AppLog.i("→ Kéo ở cả 2 khe đều không cuộn: đang ở cuối danh sách, hoặc game không nhận cử chỉ kéo.")
            save(before, "keo-khong-cuon")
            before.release()
            return@start
        }
        val atRelease = cpu { gridShift(before, after, ui, lo = -ui.d(20), band = PROBE_SHIFT_BAND) }
        val (settled, still) = waitStill(after, ui)
        val atRest = cpu { gridShift(before, settled, ui, lo = -ui.d(20), band = PROBE_SHIFT_BAND) }

        fun describe(s: Shift) = if (s.score >= SHIFT_MATCH_THRESHOLD) "${refPx(ui, s.px)}px máy gốc (khớp ${"%.2f".format(s.score)})"
                                 else "không đo được (khớp ${"%.2f".format(s.score)}, có thể cuộn xa hơn tầm đo ~680px máy gốc)"
        AppLog.i("→ Kéo ${dist}px máy gốc (${y0 - y1}px thật) ở khe x=$x: di ${moveMs}ms + giữ ${holdMs}ms " +
            "(cử chỉ thực tế ${gestureMs}ms).")
        AppLog.i("  Ngay sau khi nhả: cuộn ${describe(atRelease)}.")
        AppLog.i("  Khi đứng yên: cuộn ${describe(atRest)}" + (if (!still) " - chưa đứng yên sau ${SETTLE_TIMEOUT_MS / 1000.0}s" else "") + ".")
        if (atRelease.score >= SHIFT_MATCH_THRESHOLD && atRest.score >= SHIFT_MATCH_THRESHOLD) {
            val endRef = refPx(ui, atRest.px)
            val drift = endRef - refPx(ui, atRelease.px)
            val lost = if (endRef <= dist) "game bỏ qua ~${dist - endRef}px đầu" else "cuộn quá quãng kéo ${endRef - dist}px"
            AppLog.i("  Tỉ lệ cuộn/kéo ${"%.2f".format(endRef.toDouble() / dist)} ($lost)" +
                (if (abs(drift) > 3) ", trôi thêm ${drift}px sau khi nhả." else ", không trôi sau khi nhả."))
        }
        save(before, "keo-truoc")
        save(settled, "keo-sau")
        for (m in listOf(before, after, settled)) m.release()
    }

    // ---------------------------------------------------------------- 4. Lên đầu

    /** Vuốt nhanh về đầu danh sách như bản Python (3 lần vuốt mỗi lượt) và chờ thấy ô số 1 ở trên cùng. */
    fun topProbe() = start("Thử vuốt về đầu danh sách") {
        val (first, ui) = screenWithUi() ?: return@start
        if (cpu { atTop(first, ui) }) {
            AppLog.i("Danh sách đang ở trên cùng sẵn. Hãy cuộn xuống rồi thử lại.")
            first.release()
            return@start
        }
        val t0 = now()
        var img = first
        var finished = false
        for (round in 1..8) {
            val before = img
            repeat(3) {
                val x = ui.dragGaps[gap]
                svc.swipe(x, ui.y(950), x, ui.y(1700), 120)
                gap = (gap + 1) % ui.dragGaps.size
                delay(100)
            }
            // Chờ tới khi thấy ô số 1 ở trên cùng (đã hết nảy), hoặc danh sách đã dừng mà chưa tới đầu.
            delay(150)
            val deadline = now() + 2000
            var prev = before
            var state: String? = null
            while (state == null && now() < deadline) {
                val im = shot()
                state = cpu { if (atTop(im, ui)) "top" else if (gridUnchanged(prev, im, ui)) "stopped" else null }
                if (prev !== before) prev.release()
                prev = im
            }
            img = prev
            val noMove = img !== before && cpu { gridUnchanged(before, img, ui) }
            if (img !== before) before.release()
            if (state == "top" || noMove) {
                val reached = state == "top" || cpu { atTop(img, ui) }
                finished = true
                AppLog.i("→ ${if (reached) "Đã về đầu danh sách" else "Danh sách đứng yên nhưng không thấy ô số 1"} " +
                    "sau ${now() - t0}ms, $round lượt (${round * 3} lần vuốt).")
                break
            }
        }
        if (!finished) AppLog.i("→ Chưa về được đầu danh sách sau ${now() - t0}ms (8 lượt vuốt).")
        save(img, "len-dau")
        img.release()
    }

    // ---------------------------------------------------------------- 5. Game Booster

    private fun lockedByWindow(windows: List<String>) = windows.any { it.contains("GameBooster", ignoreCase = true) }

    /** Để yên máy chờ lớp khóa của Samsung Game Booster hiện ra, rồi thử mở bằng cử chỉ Trợ năng như bản Python.
     *  Cũng kiểm tra bảng nổi có giữ được màn hình sáng không. */
    fun lockProbe() = start("Chờ Game Booster khóa màn hình") {
        AppLog.i("Để yên máy, không chạm vào màn hình. Theo dõi tối đa ${LOCK_WATCH_MS / 60_000} phút.")
        val t0 = now()
        var lastWindows: List<String>? = null
        var lastAwake: Boolean? = null
        var lastMinute = 0L
        while (now() - t0 < LOCK_WATCH_MS) {
            val awake = svc.screenOn()
            if (awake != lastAwake) {
                AppLog.i(if (awake) "  Màn hình đang sáng." else "  Màn hình đã TẮT dù bảng nổi đang giữ sáng.")
                lastAwake = awake
            }
            val windows = svc.windowSummary()
            if (windows != lastWindows) {
                AppLog.i("  Cửa sổ: ${windows.joinToString(" | ")}")
                lastWindows = windows
            }
            val img = try {
                shot()
            } catch (e: ScreenshotFailed) {
                AppLog.i("  Không chụp được màn hình (mã ${e.code}).")
                delay(5000)
                continue
            }
            val ui = cpu { Ui.detect(img, svc.templates) }
            val bright = ui?.let { cpu { tabBrightness(img, it) } }
            val byWindow = lockedByWindow(windows)
            val byImage = bright != null && bright < LOCKED_BRIGHTNESS
            if (byWindow || byImage) {
                AppLog.i("Phát hiện khóa sau ${(now() - t0) / 1000}s - theo cửa sổ: ${if (byWindow) "có" else "không"}, " +
                    "theo độ sáng: ${if (byImage) "có" else "không"} (độ sáng tab ${"%.2f".format(bright ?: -1.0)}).")
                save(img, "khoa-truoc")
                img.release()
                unlockGameBooster()
                return@start
            }
            img.release()
            val minute = (now() - t0) / 60_000
            if (minute > lastMinute) {
                AppLog.i("  Đã chờ $minute phút, chưa bị khóa (độ sáng tab ${bright?.let { "%.2f".format(it) } ?: "?"}).")
                lastMinute = minute
            }
            delay(5000)
        }
        AppLog.i("→ Sau ${LOCK_WATCH_MS / 60_000} phút màn hình không bị khóa.")
    }

    private suspend fun stillLocked(): Boolean {
        val img = shot()
        val ui = cpu { Ui.detect(img, svc.templates) }
        val bright = ui?.let { cpu { tabBrightness(img, it) } }
        save(img, "khoa-sau")
        img.release()
        return lockedByWindow(svc.windowSummary()) || (bright != null && bright < LOCKED_BRIGHTNESS)
    }

    /** Chạm một lần cho ổ khóa hiện ra rồi kéo ổ khóa lên một đoạn (lớp khóa chặn mọi thao tác chạm vào game). */
    private suspend fun unlockGameBooster() {
        for (attempt in 1..3) {
            val img = shot()
            val w = img.cols()
            val h = img.rows()
            var lock = cpu { findLockIcon(img) }
            img.release()
            if (lock == null) {
                svc.tap(w / 2, h / 2)
                delay(600)
                val again = shot()
                lock = cpu { findLockIcon(again) }
                save(again, "khoa-sau-cham")
                again.release()
                AppLog.i("  Lần $attempt: chạm giữa màn hình → ${lock?.let { "thấy ổ khóa tại $it" } ?: "không thấy ổ khóa"}.")
            }
            val (x, y) = lock ?: (w / 2 to h / 2)
            svc.swipe(x, y, x, y - h / 8, 400)
            delay(800)
            if (!stillLocked()) {
                AppLog.i("→ ĐÃ MỞ KHÓA Game Booster bằng cử chỉ Trợ năng (lần $attempt).")
                return
            }
            AppLog.i("  Lần $attempt: kéo ổ khóa xong vẫn còn khóa.")
        }
        AppLog.i("→ KHÔNG mở được khóa bằng cử chỉ Trợ năng. Cách khác: tắt khóa tự động trong Game Booster.")
    }
}
