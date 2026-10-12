package vn.hoavien.auto

import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import vn.hoavien.auto.vision.BADGES_PER_ROW
import vn.hoavien.auto.vision.Box
import vn.hoavien.auto.vision.GRID
import vn.hoavien.auto.vision.Reading
import vn.hoavien.auto.vision.SHIFT_BAND
import vn.hoavien.auto.vision.SHIFT_MATCH_THRESHOLD
import vn.hoavien.auto.vision.Task
import vn.hoavien.auto.vision.Ui
import vn.hoavien.auto.vision.Verdict
import vn.hoavien.auto.vision.annotate
import vn.hoavien.auto.vision.atTop
import vn.hoavien.auto.vision.decide
import vn.hoavien.auto.vision.findBadges
import vn.hoavien.auto.vision.findCompasses
import vn.hoavien.auto.vision.findDeleteButton
import vn.hoavien.auto.vision.findDialogButton
import vn.hoavien.auto.vision.findTasks
import vn.hoavien.auto.vision.gridShift
import vn.hoavien.auto.vision.gridUnchanged
import vn.hoavien.auto.vision.isSelected
import vn.hoavien.auto.vision.readPanelPoints
import vn.hoavien.auto.vision.tabBrightness
import vn.hoavien.auto.vision.taskNumber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// Kéo cuộn một bước: đặt tay ở DRAG_FROM_Y (px máy gốc) kéo lên một quãng rồi giữ một lúc trước khi nhả để danh sách
// không trôi. Quãng kéo, thời gian kéo, thời gian giữ chỉnh ở mục Nâng cao (Prefs); dưới đây là mặc định.
// Đo trên Tab S7: game bỏ qua ~DRAG_DEAD_ZONE px đầu nên kéo 548px cuộn ~520px.
const val DRAG_FROM_Y = 1610
const val DRAG_DISTANCE = 548
const val DRAG_MOVE_MS = 500L
const val DRAG_HOLD_MS = 150L
const val DRAG_DEAD_ZONE = 28
// Khoảng cho phép của các thông số kéo. Quãng kéo bị giới hạn bởi cách đo độ cuộn: cuộn được tới (cao lưới 832 -
// dải đo >= MIN_SHIFT_BAND) - SHIFT_ABOVE, và phải < ~770px (khung nhìn trừ dải điểm 60px) để ô nào cũng có lúc hiện
// trọn dải điểm. Giữ tay > 0 để danh sách không trôi theo quán tính.
val DRAG_DISTANCE_RANGE = 300..660
val DRAG_MS_RANGE = 200..2000
val HOLD_MS_RANGE = 50..1000
// Cửa sổ tìm độ cuộn quanh mức mong đợi (px máy gốc): đủ hẹp để loại vị trí lệch đúng 1 hàng (±269px).
private const val SHIFT_ABOVE = 45
private const val SHIFT_BELOW = 200
private const val MIN_SHIFT_BAND = 150

// Sau mỗi lần bấm không chờ cố định mà chụp lặp lại tới khi giao diện đổi xong (mỗi lần chụp ~0.33s).
private const val POLL_FIRST_WAIT = 150L    // chờ trước lần chụp đầu để game kịp phản hồi
private const val SELECT_TIMEOUT = 1200L    // chọn ô: khung chi tiết hiện đúng ô vừa bấm
private const val DIALOG_TIMEOUT = 3000L    // bấm Xóa: hộp thoại xác nhận hiện xong (đôi khi hiện chậm)
private const val DELETE_TAPS = 2           // bấm Xóa mà không có hộp thoại nào thì bấm lại, tối đa bấy nhiêu lần
private const val DELETED_TIMEOUT = 2500L   // bấm Xác nhận: ô chuyển thành đồng hồ
private const val CONFIRM_TAPS = 3          // bấm Xác nhận mà hộp thoại vẫn còn thì bấm lại, tối đa bấy nhiêu lần
// Hộp thoại xóa làm tối nền (độ sáng tab ~0.4), kể cả lúc đang hiện dở khi chưa nhận ra được nút nào.
private const val NO_DIALOG_BRIGHTNESS = 0.85
private const val SCROLL_WAIT = 50L         // kéo có dừng trước khi nhả nên danh sách đứng yên ngay
// Kéo mà danh sách không cuộn chưa chắc đã tới cuối: game nạp dần các ô, có lúc danh sách tạm cụt (ví dụ dừng ở ô 27
// trong khi có 66 ô) rồi mới hiện tiếp. Chỉ coi là hết khi kéo bấy nhiêu lần, cách nhau END_RETRY_WAIT, đều không cuộn.
private const val END_CONFIRM_DRAGS = 3
private const val END_CONFIRM_DRAGS_KNOWN_END = 1    // đã thấy ô cuối (số thứ tự lớn nhất từng gặp): không cần kéo lại
private const val END_CONFIRM_DRAGS_EARLY = 6         // chưa tới số ô lớn nhất từng gặp: kéo thêm, chờ game nạp
private const val END_RETRIES_EARLY = 2               // ...và nếu vẫn bị cho là cuối thì kéo lại thêm bấy nhiêu đợt
private const val END_RETRY_WAIT = 1000L
private const val SETTLE_TIMEOUT = 4500L    // ở cuối danh sách: chờ hiệu ứng nảy đàn hồi kết thúc
private const val TOP_TIMEOUT = 2000L       // vuốt lên đầu: chờ danh sách dừng ở trên cùng
private const val NOT_READY_RETRY = 20_000L // chưa thấy bảng Nhiệm Vụ: thử lại sau bấy nhiêu ms
private const val ONCE_WAIT_LIMIT = 180_000L    // chạy 1 lượt: chờ tối đa bấy nhiêu ms để bảng Nhiệm Vụ hiện ra
private const val NOT_READY_NOTIFY = 3      // bấy nhiêu lần liền không quét được (~1 phút) thì gửi thông báo
private const val LOW_BATTERY = 20          // % pin coi là yếu (khi không sạc)
// Bấy nhiêu ô liền có điểm thấp mà không có nút Xóa, và chưa xóa được ô nào: tài khoản không có chức năng xóa.
private const val NO_DELETE_FEATURE = 3
// Vùng game dùng để nhận diện (tab "Nhiệm Vụ" tới hết khung chi tiết), px máy gốc: bảng nổi không được đè lên.
private val RECOGNITION_AREA = Box(90, 620, 1000, 2010)

private enum class Scan { OK, END, LOST, ABORT, BLOCKED }

/** Bot xóa nhiệm vụ Đấu hội có điểm <= ngưỡng, chuyển từ lớp Bot của xoa_nhiem_vu.py.
 *  Quy ước ảnh: hàm nhận ảnh rồi trả về ảnh mới thì đã giải phóng ảnh cũ; ảnh trả về do người gọi giải phóng. */
class Bot(private val svc: BotService) {
    private var ui: Ui? = null              // bố cục màn hình, nhận diện lại mỗi lượt
    private var gap = 0                     // khe dùng cho lần kéo tới (đổi khe sau mỗi lần kéo)
    private var totalDeleted = 0

    // Cài đặt và thống kê của lượt đang chạy.
    private var maxPoints = 12
    private var maxDeletes: Int? = null
    private var dryRun = false
    private var deleted = 0
    private var unreadable = 0
    private var failures = 0
    private var maxCard = 0                 // số thứ tự ô lớn nhất đã thấy trong lượt
    private var lowerSeen = 0               // lượt trước kết thúc ở số ô thấp hơn tổng đã biết (để xác nhận lần 2)
    private var noDeleteButton = 0          // số ô liên tiếp không có nút Xóa (tài khoản không có chức năng xóa?)
    private var notReadyReason = ""         // lý do lượt gần nhất không quét được, để báo cho người dùng
    private var batteryWarned = false

    // Thông số kéo cuộn của lượt đang chạy (đọc lại từ Nâng cao mỗi lượt).
    private var dragDistance = DRAG_DISTANCE
    private var dragMs = DRAG_MOVE_MS
    private var holdMs = DRAG_HOLD_MS

    private fun now() = SystemClock.uptimeMillis()

    private suspend fun <T> cpu(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    private suspend fun shot(): Mat = svc.screenshot().img

    private suspend fun tap(x: Int, y: Int) {
        check(ui?.acceptZone?.contains(x, y) != true) { "chặn bấm vào vùng nút Nhận nhiệm vụ ($x,$y)" }
        svc.tap(x, y)
    }

    /** Chụp lặp lại tới khi check(ảnh) khác null hoặc hết giờ. Trả về (ảnh mới nhất, kết quả). */
    private suspend fun <T : Any> poll(timeoutMs: Long, check: (Mat) -> T?): Pair<Mat, T?> {
        delay(POLL_FIRST_WAIT)
        val deadline = now() + timeoutMs
        while (true) {
            val img = shot()
            val result = cpu { check(img) }
            if (result != null || now() >= deadline) return img to result
            img.release()
        }
    }

    /** Như poll nhưng chỉ nhận kết quả khi 2 lần chụp liên tiếp cho kết quả giống nhau (same): giao diện đã
     *  hiện xong, không còn đang chạy hiệu ứng. */
    private suspend fun <T : Any> pollStable(timeoutMs: Long, same: (T, T) -> Boolean = { a, b -> a == b },
                                             check: (Mat) -> T?): Pair<Mat, T?> {
        delay(POLL_FIRST_WAIT)
        val deadline = now() + timeoutMs
        var prev: T? = null
        while (true) {
            val img = shot()
            val result = cpu { check(img) }
            if (result != null && prev != null && same(prev, result)) return img to result
            if (now() >= deadline) return img to null
            prev = result
            img.release()
        }
    }

    /** Chụp lặp lại tới khi lưới không đổi giữa 2 lần chụp liên tiếp. Không giải phóng prev. */
    private suspend fun waitStill(prev: Mat): Mat {
        val u = ui!!
        delay(POLL_FIRST_WAIT)
        val deadline = now() + SETTLE_TIMEOUT
        var last: Mat? = null
        while (true) {
            val img = shot()
            val still = cpu { gridUnchanged(last ?: prev, img, u) }
            last?.release()
            last = img
            if (still || now() >= deadline) return img
        }
    }

    // ---------------------------------------------------------------- Vòng chạy

    /** Chạy liên tục mỗi intervalMin phút (once: chỉ 1 lượt). dryRun: chỉ báo, không bấm Xóa. */
    suspend fun run(once: Boolean, dryRun: Boolean) {
        this.dryRun = dryRun
        AppLog.i("Bắt đầu. Xóa nhiệm vụ <= ${svc.prefs.maxPoints} điểm${if (dryRun) " (CHẠY THỬ: chỉ báo, không xóa)" else ""}.")
        if (!once && !battery().second) AppLog.i("Máy không cắm sạc: nên cắm sạc khi để bot chạy lâu.")
        val started = now()
        var notReady = 0
        try {
            while (true) {
                checkBattery()
                BotStatus.set(if (dryRun) "Đang chạy thử…" else "Đang quét… · hôm nay đã xóa ${svc.prefs.deletedToday()}")
                notReadyReason = "không rõ lý do"
                val ran = try {
                    runCycle()
                } catch (e: ScreenshotFailed) {
                    AppLog.i("Không chụp được màn hình (mã ${e.code}).")
                    notReadyReason = "không chụp được màn hình"
                    false
                }
                if (ran) {
                    notReady = 0
                    Notifier.cancel(svc, Notifier.ID_WAITING)
                } else if (++notReady == NOT_READY_NOTIFY) {
                    // Không tự mở lại game: báo để người dùng xử lý. Bot vẫn tự chạy tiếp khi mọi thứ ổn lại.
                    Notifier.show(svc, Notifier.ID_WAITING, "Hoa Viên Auto đang chờ bạn",
                        "Đã ${notReady} lần không quét được: $notReadyReason. Bot sẽ tự chạy tiếp khi ổn lại.")
                }
                if (once && (ran || now() - started > ONCE_WAIT_LIMIT)) break
                // Chưa quét được (game chưa ở bảng Nhiệm Vụ, màn hình tắt...): thử lại sớm.
                val wait = if (ran) svc.prefs.intervalMin * 60_000L else NOT_READY_RETRY
                val next = LocalTime.now().plusSeconds(wait / 1000).format(CLOCK)
                if (ran) {
                    AppLog.i("Lượt sau lúc $next.")
                    BotStatus.set("Chờ lượt sau lúc $next · hôm nay đã xóa ${svc.prefs.deletedToday()}")
                } else {
                    AppLog.i("Thử lại sau ${wait / 1000} giây.")
                    BotStatus.set("Đang chờ: $notReadyReason · thử lại lúc $next")
                }
                delay(wait)
            }
        } finally {
            Notifier.cancel(svc, Notifier.ID_WAITING)
        }
    }

    /** (mức pin %, đang sạc). */
    private fun battery(): Pair<Int, Boolean> {
        val bm = svc.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    /** Pin yếu mà không sạc thì báo (một lần cho tới khi cắm sạc hoặc pin lên lại). */
    private fun checkBattery() {
        val (level, charging) = battery()
        if (!charging && level in 0..LOW_BATTERY) {
            if (!batteryWarned) {
                AppLog.i("Pin còn $level% và không sạc.")
                Notifier.show(svc, Notifier.ID_BATTERY, "Pin yếu ($level%)",
                    "Hãy cắm sạc để bot chạy tiếp; hết pin máy sẽ tắt và bot dừng.")
                batteryWarned = true
            }
        } else if (charging || level > LOW_BATTERY + 5) {
            if (batteryWarned) Notifier.cancel(svc, Notifier.ID_BATTERY)
            batteryWarned = false
        }
    }

    /** Quét một lượt. Trả về false nếu chưa quét được (chưa ở bảng Nhiệm Vụ, màn hình tắt...). */
    private suspend fun runCycle(): Boolean {
        var img = checkReady() ?: return false
        maxPoints = svc.prefs.maxPoints
        maxDeletes = svc.prefs.maxDeletes.takeIf { it > 0 }
        loadDragSettings()
        deleted = 0
        unreadable = 0
        failures = 0
        noDeleteButton = 0
        maxCard = 0
        var complete = false
        for (restart in 1..3) {
            val (last, status) = scanPages(scrollToTop(img))
            last.release()
            if (status == Scan.BLOCKED) {
                totalDeleted += deleted
                AppLog.i("Dừng lượt giữa chừng: đã xóa $deleted. Thử lại khi màn hình hết bị che.")
                return false
            }
            if (status != Scan.LOST) {
                complete = true
                break
            }
            AppLog.i("Danh sách bị trượt quá đà, quét lại từ đầu để không sót ô.")
            if (restart < 3) img = shot()
        }
        if (!complete) AppLog.i("Cuộn không ổn định, lượt này có thể đã sót một số ô.")
        val known = svc.prefs.knownLastCard
        if (complete && maxCard > known) {
            if (known > 0) AppLog.i("Danh sách giờ có $maxCard ô (lần trước $known).")
            svc.prefs.knownLastCard = maxCard
            lowerSeen = 0
        } else if (complete && maxCard in 1 until known) {
            // Chỉ hạ tổng số ô khi 2 lượt liền cùng kết thúc ở một số thấp hơn (tránh một lần danh sách tạm cụt).
            if (lowerSeen == maxCard) {
                AppLog.i("Danh sách giờ có $maxCard ô (lần trước $known).")
                svc.prefs.knownLastCard = maxCard
                lowerSeen = 0
            } else {
                lowerSeen = maxCard
            }
        }
        totalDeleted += deleted
        val reach = if (maxCard > 0) " Đã quét tới ô $maxCard." else ""
        AppLog.i("Xong lượt: đã xóa $deleted, không đọc được $unreadable.$reach Tổng đã xóa từ lúc chạy: $totalDeleted.")
        return true
    }

    /** Đọc thông số kéo ở Nâng cao (Prefs đã giới hạn trong khoảng cho phép); báo khi khác lượt trước. */
    private fun loadDragSettings() {
        val p = svc.prefs
        val dist = p.dragDistance
        val move = p.dragMs.toLong()
        val hold = p.holdMs.toLong()
        if (dist != dragDistance || move != dragMs || hold != holdMs) {
            AppLog.i("Kéo cuộn: ${dist}px máy gốc trong $move ms, giữ $hold ms (danh sách cuộn ~${dist - DRAG_DEAD_ZONE}px).")
        }
        dragDistance = dist
        dragMs = move
        holdMs = hold
    }

    /** Màn hình sáng, không bị Game Booster khóa, đang ở bảng Nhiệm Vụ, không còn hộp thoại xóa mở dở.
     *  Trả về ảnh màn hình hiện tại, null (đã báo) nếu chưa sẵn sàng. */
    private suspend fun checkReady(): Mat? {
        if (!svc.screenOn()) {
            AppLog.i("Màn hình đang tắt - hãy mở máy và mở lại bảng Nhiệm Vụ.")
            notReadyReason = "màn hình đang tắt"
            return null
        }
        var img = shot()
        var u = cpu { Ui.detect(img, svc.templates) }
        if (u != null && cpu { tabBrightness(img, u!!) } < LOCKED_BRIGHTNESS) {
            img.release()
            if (!svc.unlockGameBooster()) {
                notReadyReason = "màn hình bị che tối (Game Booster khóa hoặc chống chạm nhầm)"
                AppLog.i("Không mở được lớp che tối màn hình. Nếu là \"Chống chạm nhầm\" của Samsung: đừng che cạnh trên " +
                    "của máy (cảm biến tiệm cận), hoặc tắt nó trong Cài đặt > Màn hình.")
                return null
            }
            img = shot()
            u = cpu { Ui.detect(img, svc.templates) }
        }
        if (u == null) {
            AppLog.i("Không thấy màn hình Nhiệm Vụ của Đấu hội - hãy mở lại bảng Nhiệm Vụ.")
            notReadyReason = "không thấy bảng Nhiệm Vụ của Đấu hội (game bị thoát hoặc đang ở màn hình khác)"
            svc.saveImage(img, "khong-dung-man-hinh")
            img.release()
            return null
        }
        val old = ui
        if (old == null || old.s != u.s || old.dx != u.dx || old.dy != u.dy) {
            AppLog.i("Bố cục màn hình ${img.cols()}x${img.rows()}: $u")
        }
        ui = u
        // Bảng nổi lọt vào ảnh chụp: nếu bị kéo đè lên vùng nhận diện thì dời về mép trên.
        val overlay = svc.overlayBounds()
        if (overlay != null && overlay.intersects(u.box(RECOGNITION_AREA))) {
            AppLog.i("Bảng nổi đang che bảng game - đã dời về mép trên.")
            svc.resetOverlayPosition()
            delay(300)
            img.release()
            img = shot()
        }
        val cancel = cpu { findDialogButton(img, u.btnCancel, u) }
        if (cancel != null) {
            AppLog.i("Đang có hộp thoại xóa còn mở từ trước - bấm Hủy.")
            tap(cancel.first, cancel.second)
            img.release()
            img = poll(DIALOG_TIMEOUT) { im -> if (findDialogButton(im, u.btnCancel, u) == null) true else null }.first
        }
        return img
    }

    // ---------------------------------------------------------------- Duyệt danh sách

    /** Duyệt từ vị trí hiện tại tới cuối danh sách. Trả về (ảnh mới nhất, trạng thái). */
    private suspend fun scanPages(start: Mat): Pair<Mat, Scan> {
        val u = ui!!
        var img = start
        var prevMax = 0                         // số ô lớn nhất của trang trước, 0 = không đọc được
        for (page in 0 until 60) {
            // Màn hình bị che tối giữa lượt (Game Booster khóa, "Chống chạm nhầm"...): mọi thao tác đều vô tác dụng.
            if (cpu { tabBrightness(img, u) } < LOCKED_BRIGHTNESS) {
                AppLog.i("Màn hình bị che tối giữa lượt (Game Booster khóa hoặc chống chạm nhầm).")
                return img to Scan.BLOCKED
            }
            val tasks = cpu { findTasks(img, u) }
            val badges = cpu { findBadges(img, u) }
            val numbers = badges.mapNotNull { it.number }
            // Trang sau phải nối tiếp trang trước (mỗi lần cuộn ít hơn 1 khung nhìn nên luôn có hàng chung hoặc kề nhau).
            if (prevMax > 0 && numbers.isNotEmpty() && rowOf(numbers.min()) > rowOf(prevMax) + 1) {
                AppLog.i("Bỏ sót hàng: trang trước tới ô $prevMax, trang này bắt đầu từ ô ${numbers.min()}.")
                return img to Scan.LOST
            }
            if (numbers.isNotEmpty()) {
                prevMax = numbers.max()
                maxCard = maxOf(maxCard, prevMax)
            }
            if (svc.prefs.saveImages) {
                val marked = cpu { annotate(img, tasks, maxPoints, u) }
                svc.saveImage(marked, "trang%02d".format(page))
                marked.release()
            }
            var latest: Mat? = null             // ảnh mới nhất sau khi bấm vào ô trên trang này
            for (t in tasks) {
                when (decide(t.reading, maxPoints)) {
                    Verdict.UNSURE -> {
                        unreadable++
                        val where = taskNumber(t, badges, u)?.let { "ô số $it" } ?: "ô tại (${t.x},${t.y})"
                        AppLog.i("Không chắc điểm ở $where [${t.reading.detail}] - bỏ qua.")
                        continue
                    }
                    Verdict.KEEP -> continue
                    Verdict.DELETE -> {}
                }
                val where = taskNumber(t, badges, u)?.let { "ô số $it" } ?: "ô tại (${t.x},${t.y})"
                AppLog.i("Tìm thấy nhiệm vụ ${t.reading.value} điểm ở $where.")
                val (ok, im) = deleteTask(t)
                latest?.release()
                latest = im
                if (noDeleteButton >= NO_DELETE_FEATURE && totalDeleted + deleted == 0) {
                    val msg = "Tài khoản này không có nút Xóa nhiệm vụ (chỉ có Nhận nhiệm vụ), bot không xóa được."
                    if (!dryRun) {
                        im.release()
                        img.release()
                        throw StopBot("$msg Bot đã dừng.")
                    }
                    if (noDeleteButton == NO_DELETE_FEATURE) AppLog.i("  $msg")
                }
                if (ok) {
                    deleted++
                    svc.prefs.addDeletedToday()
                    failures = 0
                    if (maxDeletes != null && deleted >= maxDeletes!!) {
                        AppLog.i("Đã đủ $maxDeletes lần xóa cho lượt này.")
                        img.release()
                        return im to Scan.ABORT
                    }
                } else if (!dryRun) {
                    failures++
                    if (failures >= 3) {
                        AppLog.i("3 lần xóa liên tiếp không thành công - dừng lượt này.")
                        img.release()
                        return im to Scan.ABORT
                    }
                }
            }
            val base = latest?.also { img.release() } ?: img
            // Biết tổng số ô (từ các lượt trước): đã thấy ô cuối thì kéo 1 lần là đủ; chưa tới thì kéo thêm cho game nạp.
            val known = svc.prefs.knownLastCard
            val confirm = when {
                known <= 0 || maxCard <= 0 -> END_CONFIRM_DRAGS
                maxCard >= known -> END_CONFIRM_DRAGS_KNOWN_END
                else -> END_CONFIRM_DRAGS_EARLY
            }
            var (next, status) = scrollDown(base, confirm)
            // Chưa tới tổng số ô đã biết mà bị cho là cuối danh sách: chờ rồi kéo lại vài lần nữa cho chắc.
            var endRetries = 0
            while (status == Scan.END && known > 0 && maxCard in 1 until known && endRetries < END_RETRIES_EARLY) {
                endRetries++
                delay(END_RETRY_WAIT)
                val r = scrollDown(next, END_CONFIRM_DRAGS)
                next = r.first
                status = r.second
            }
            img = next
            if (status == Scan.END && known > 0 && maxCard in 1 until known) {
                AppLog.i("Danh sách chỉ hiện tới ô $maxCard (lần trước có $known ô).")
            }
            if (status == Scan.LOST && prevMax > 0) {
                // Không đo được độ cuộn, nhưng số thứ tự ô vẫn nối tiếp thì cứ quét tiếp, không cần quét lại từ đầu.
                val nextNums = cpu { findBadges(img, u) }.mapNotNull { it.number }
                if (nextNums.isNotEmpty() && nextNums.max() > prevMax && rowOf(nextNums.min()) <= rowOf(prevMax) + 1) {
                    AppLog.i("  Không đo được độ cuộn nhưng số thứ tự ô vẫn liền mạch (${nextNums.min()}–${nextNums.max()}), quét tiếp.")
                    continue
                }
            }
            if (status != Scan.OK) return img to status
        }
        return img to Scan.END
    }

    private fun rowOf(card: Int) = (card - 1) / BADGES_PER_ROW

    /** Vuốt nhanh nhiều lần về đầu danh sách. Nhận ảnh hiện tại, trả về ảnh mới. */
    private suspend fun scrollToTop(start: Mat): Mat {
        val u = ui!!
        if (cpu { atTop(start, u) }) return start
        var img = start
        repeat(8) {
            val before = img
            repeat(3) {
                val x = u.dragGaps[gap]
                svc.swipe(x, u.y(950), x, u.y(1700), 120)
                gap = (gap + 1) % u.dragGaps.size
                delay(100)
            }
            // Chờ tới khi thấy ô số 1 ở trên cùng (đã hết nảy); nếu danh sách đã dừng mà chưa tới đầu thì vuốt tiếp
            // ngay chứ không chờ hết giờ.
            delay(POLL_FIRST_WAIT)
            val deadline = now() + TOP_TIMEOUT
            var last = before
            var state: String?
            do {
                val im = shot()
                val prev = last
                state = cpu { if (atTop(im, u)) "top" else if (gridUnchanged(prev, im, u)) "stopped" else null }
                if (last !== before) last.release()
                last = im
            } while (state == null && now() < deadline)
            img = last
            val noMove = cpu { gridUnchanged(before, img, u) }  // vuốt mà không nhúc nhích -> đã ở đầu
            before.release()
            if (state == "top" || noMove) {
                // Thấy ô số 1 có thể là lúc danh sách còn đang nảy ở đầu: chờ đứng yên rồi mới kéo trang đầu.
                val still = waitStill(img)
                img.release()
                return still
            }
        }
        AppLog.i("Cuộn lên đầu chưa ổn định, vẫn tiếp tục.")
        return img
    }

    /** Cuộn xuống một bước. Nhận ảnh hiện tại (làm mốc đo), trả về (ảnh mới, OK | END | LOST).
     *  Khác bản Python: chụp màn hình giờ nhanh (~0.1s sau khi nhả tay) nên ở cuối danh sách hay bắt đúng lúc danh sách
     *  đang bị kéo lố và nảy về, đo nhầm thành đã cuộn được. Vì vậy luôn chờ lưới đứng yên rồi mới đo. */
    private suspend fun scrollDown(img: Mat, confirmDrags: Int = END_CONFIRM_DRAGS): Pair<Mat, Scan> {
        val u = ui!!
        var settled: Mat? = null
        for (attempt in 0 until confirmDrags) {
            val g = (gap + attempt) % u.dragGaps.size
            if (attempt > 0) delay(END_RETRY_WAIT)  // cho game kịp nạp thêm ô
            svc.drag(u.dragGaps[g], u.y(DRAG_FROM_Y), u.y(DRAG_FROM_Y - dragDistance), dragMs, holdMs)
            delay(SCROLL_WAIT)
            val first = shot()
            val moved = !cpu { gridUnchanged(img, first, u) }
            val still = if (moved) waitStill(first).also { first.release() } else first
            if (!cpu { gridUnchanged(img, still, u) }) {
                if (attempt > 0) AppLog.i("  Kéo $attempt lần không cuộn, lần ${attempt + 1} (khe x=${u.dragGaps[g]}) thì cuộn được.")
                gap = (g + 1) % u.dragGaps.size       // lần kéo sau dùng khe còn lại
                settled = still
                break
            }
            // Đứng yên hoặc nhúc nhích rồi nảy về đúng chỗ cũ: có thể đã ở cuối, kéo lại cho chắc.
            if (attempt == confirmDrags - 1) {
                img.release()
                return still to Scan.END
            }
            still.release()
        }
        val cur = settled!!
        // Bước cuộn bình thường: quãng kéo trừ đoạn game bỏ qua. Khoảng tìm loại trừ vị trí lệch đúng 1 hàng (±269px).
        val step = dragDistance - DRAG_DEAD_ZONE
        val expected = u.d(step)
        // Dải đo càng cao thì đo được càng ngắn (tới cao lưới - dải): kéo xa thì dùng dải thấp hơn, tối thiểu MIN_SHIFT_BAND.
        val band = (GRID.height - SHIFT_ABOVE - step).coerceIn(MIN_SHIFT_BAND, SHIFT_BAND)
        val full = cpu { gridShift(img, cur, u, expected - u.d(SHIFT_BELOW), expected + u.d(SHIFT_ABOVE), band) }
        val result = if (full.score >= SHIFT_MATCH_THRESHOLD) {
            Scan.OK
        } else {
            // Cuộn ít hơn một bước: đã chạm cuối danh sách.
            val short = cpu { gridShift(img, cur, u, -u.d(20), expected - u.d(SHIFT_BELOW), band) }
            when {
                short.score < SHIFT_MATCH_THRESHOLD -> Scan.LOST        // trượt quá đà, có thể sót hàng
                short.px < u.d(60) -> Scan.END                          // nhích < 1/4 hàng: chỉ là nảy ở cuối
                else -> Scan.OK
            }
        }
        img.release()
        return cur to result
    }

    // ---------------------------------------------------------------- Xóa

    /** Chọn ô, kiểm tra lại trong khung chi tiết, bấm Xóa + Xác nhận.
     *  Trả về (đã xóa hay chưa, ảnh màn hình mới nhất - dùng tiếp làm trạng thái hiện tại). */
    private suspend fun deleteTask(task: Task): Pair<Boolean, Mat> {
        val u = ui!!

        // Ô đã thành đồng hồ: không còn la bàn ở chỗ cũ, và không có hộp thoại nào (kể cả hộp thoại đang hiện dở:
        // lúc đó nó đã che mất la bàn nhưng chưa nhận ra được nút Hủy, chỉ thấy nền bị làm tối).
        fun cardGone(im: Mat): Boolean {
            val tol = u.d(15)
            return tabBrightness(im, u) >= NO_DIALOG_BRIGHTNESS && findDialogButton(im, u.btnCancel, u) == null &&
                findCompasses(im, u.grid, u).none { abs(it.x - task.x) < tol && abs(it.y - task.y) < tol }
        }

        fun samePos(a: Pair<Int, Int>, b: Pair<Int, Int>) = abs(a.first - b.first) <= 3 && abs(a.second - b.second) <= 3

        var img: Mat? = null
        var panel: Reading? = null
        for (attempt in 1..2) {                 // game đôi khi bỏ qua một lần chạm
            tap(task.tapX, task.tapY)
            img?.release()
            // Khung chi tiết phải đọc được cùng một số điểm ở 2 ảnh liên tiếp (đã chuyển xong sang ô này): bấm Xóa
            // khi khung chi tiết còn đang đổi thì game có lúc bỏ qua.
            val (im, p) = pollStable(SELECT_TIMEOUT, { a, b -> a.value == b.value }) { im ->
                if (!isSelected(im, task, u)) null
                else readPanelPoints(im, u).takeIf { it.value == task.reading.value }
            }
            img = im
            panel = p
            if (p != null) break
            AppLog.i("  Lần chạm $attempt chưa chọn được ô.")
        }
        val cur = img!!
        if (panel == null) {
            AppLog.i("  Bỏ qua: không chọn được ô này (khung chi tiết hiện ${cpu { readPanelPoints(cur, u) }.value} điểm).")
            return false to cur
        }
        val btn = cpu { findDeleteButton(cur, u) }
        if (btn == null) {
            noDeleteButton++
            AppLog.i("  Bỏ qua: khung chi tiết không có nút Xóa (vừa có người nhận, hoặc tài khoản không có chức năng xóa nhiệm vụ).")
            return false to cur
        }
        noDeleteButton = 0
        if (decide(panel, maxPoints) != Verdict.DELETE) {
            AppLog.i("  Bỏ qua: khung chi tiết đọc được ${panel.value} điểm [${panel.detail}].")
            return false to cur
        }
        if (dryRun) {
            AppLog.i("  [chạy thử] Sẽ xóa nhiệm vụ ${panel.value} điểm.")
            return false to cur
        }
        // Chờ hộp thoại hiện xong (nút Xác nhận đứng yên ở 2 ảnh liên tiếp), hoặc ô mất hẳn khi không có hộp thoại
        // (đã tick "Hôm nay không nhắc nữa").
        suspend fun waitDialog() = pollStable(DIALOG_TIMEOUT, { a, b ->
            a.first == b.first && (a.second == null || samePos(a.second!!, b.second!!))
        }) { im ->
            findDialogButton(im, u.btnConfirm, u)?.let { "confirm" to it }
                ?: if (cardGone(im)) ("gone" to null) else null
        }

        var latest = cur
        var step: Pair<String, Pair<Int, Int>?>? = null
        var deleteBtn: Pair<Int, Int> = btn
        for (deleteTap in 1..DELETE_TAPS) {
            tap(deleteBtn.first, deleteBtn.second)
            latest.release()
            val (im, st) = waitDialog()
            latest = im
            step = st
            if (st != null) break
            if (cpu { tabBrightness(latest, u) } < NO_DIALOG_BRIGHTNESS) {
                // Nền đã tối: hộp thoại đang hiện (chậm). Chờ thêm, không bấm Xóa lại.
                val (im2, st2) = waitDialog()
                latest.release()
                latest = im2
                step = st2
                break
            }
            // Không có hộp thoại nào: game đã bỏ qua lần bấm Xóa. Bấm lại nếu nút Xóa vẫn còn.
            if (deleteTap == DELETE_TAPS) break
            deleteBtn = cpu { findDeleteButton(latest, u) } ?: break
            AppLog.i("  Bấm Xóa lần $deleteTap không thấy hộp thoại - bấm lại.")
        }
        var gone = step?.first == "gone"
        var confirm = step?.second
        var taps = 0
        while (!gone && confirm != null && taps < CONFIRM_TAPS) {
            tap(confirm.first, confirm.second)
            taps++
            latest.release()
            val (im, g) = pollStable(DELETED_TIMEOUT) { im -> if (cardGone(im)) true else null }
            latest = im
            gone = g == true
            if (!gone) {
                confirm = cpu { findDialogButton(latest, u.btnConfirm, u) }
                if (confirm != null) AppLog.i("  Bấm Xác nhận lần $taps mà hộp thoại vẫn còn - bấm lại.")
            }
        }
        if (!gone) {
            AppLog.i("  Đã bấm Xóa${if (taps > 0) " + Xác nhận ($taps lần)" else ""} nhưng ô vẫn còn nhiệm vụ.")
            svc.saveImage(latest, "xoa-khong-thanh-cong")
            // Đóng hộp thoại còn mở (nếu có) để không chặn các thao tác sau.
            val cancel = cpu { findDialogButton(latest, u.btnCancel, u) }
            if (cancel != null) {
                tap(cancel.first, cancel.second)
                latest.release()
                latest = poll(DIALOG_TIMEOUT) { im -> if (findDialogButton(im, u.btnCancel, u) == null) true else null }.first
            }
            return false to latest
        }
        AppLog.i("  Đã xóa nhiệm vụ ${panel.value} điểm.")
        return true to latest
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
