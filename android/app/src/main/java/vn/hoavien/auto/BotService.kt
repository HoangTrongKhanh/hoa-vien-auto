package vn.hoavien.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import vn.hoavien.auto.vision.Templates
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.coroutines.resume

// Hệ thống có lúc không bao giờ trả lời yêu cầu chụp màn hình / thao tác (ví dụ khi lớp "Chống chạm nhầm" của Samsung
// đang che màn hình). Quá hạn thì coi là thất bại thay vì chờ mãi.
private const val SCREENSHOT_TIMEOUT = 5000L
private const val GESTURE_TIMEOUT_EXTRA = 2000L     // cộng thêm vào thời lượng cử chỉ
const val SCREENSHOT_NO_RESPONSE = -1               // mã lỗi riêng: hệ thống không trả lời yêu cầu chụp

private const val MAX_DEBUG_IMAGES = 100       // ~3MB/ảnh trên máy 1600x2560
private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

/** Bot tự dừng hẳn vì không thể làm được gì (ví dụ tài khoản không có nút Xóa); reason hiện cho người dùng. */
class StopBot(val reason: String) : Exception(reason)

class ScreenshotFailed(val code: Int) : RuntimeException("chụp màn hình lỗi (mã $code)")

/** Ảnh chụp màn hình (BGR) và số đo: thời gian chờ hệ thống, thời gian chuyển ảnh,
 *  số lần bị từ chối vì chụp quá dày, khoảng cách tới lần chụp thành công trước. */
class Shot(val img: Mat, val waitMs: Long, val convertMs: Long, val retries: Int, val sincePrevMs: Long)

/** Dịch vụ Trợ năng thay cho ADB: chụp màn hình bằng takeScreenshot(), chạm/kéo bằng dispatchGesture(). */
class BotService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: BotService? = null
            private set
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var templates: Templates
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var bot: Bot
        private set
    lateinit var probes: Probes
        private set
    private var overlay: Overlay? = null
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var lastShotAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null

    /** Gọi trên luồng chính với tên tác vụ đang chạy (bot hoặc bài thử), null khi rảnh. */
    var onJobChanged: (String?) -> Unit = {}

    override fun onServiceConnected() {
        templates = Templates.load(
            read = { name -> assets.open(name).use { it.readBytes() } },
            list = { dir -> assets.list(dir).orEmpty().toList() },
        )
        prefs = Prefs(this)
        bot = Bot(this)
        probes = Probes(this)
        overlay = Overlay(this).also { it.show() }
        applyOverlayVisibility()
        instance = this
        AppLog.i("Đã bật dịch vụ Trợ năng. Mở game ở bảng Nhiệm Vụ rồi bấm ▶ trên bảng nổi.")
        if (prefs.running && !prefs.overlayClosed) {
            AppLog.i("Tiếp tục chạy (dịch vụ vừa khởi động lại).")
            startBot()
        } else {
            BotStatus.set("Sẵn sàng · hôm nay đã xóa ${prefs.deletedToday()}")
        }
    }

    /** ▶ Chạy: quét liên tục theo chu kỳ; nhớ trạng thái để dịch vụ khởi động lại thì chạy tiếp. */
    fun startBot() {
        prefs.running = true
        runJob("Chạy (mỗi ${prefs.intervalMin} phút)") { bot.run(once = false, dryRun = false) }
    }

    /** Thử 1 lượt: chỉ báo những nhiệm vụ sẽ xóa, không xóa thật. */
    fun startDryRun() {
        runJob("Chạy thử 1 lượt") { bot.run(once = true, dryRun = true) }
    }

    // ---------------------------------------------------------------- Tác vụ

    /** Chạy một tác vụ (bot hoặc bài thử); mỗi lúc chỉ một tác vụ. */
    fun runJob(name: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        val j = scope.launch {
            AppLog.i("▶ $name")
            try {
                block()
            } catch (e: CancellationException) {
                AppLog.i("Đã dừng.")
                throw e
            } catch (e: StopBot) {
                prefs.running = false
                AppLog.i(e.reason)
                Notifier.show(this@BotService, Notifier.ID_STOPPED, "Hoa Viên Auto đã dừng", e.reason)
            } catch (e: Exception) {
                prefs.running = false
                AppLog.e("Lỗi: ${e.message ?: e}", e)
                Notifier.show(this@BotService, Notifier.ID_STOPPED, "Hoa Viên Auto dừng vì lỗi",
                    "${e.message ?: e}. Mở app, bấm Sao chép nhật ký và gửi cho người cài app.")
            }
        }
        job = j
        onJobChanged(name)
        j.invokeOnCompletion {
            main.post {
                if (job === j) {
                    onJobChanged(null)
                    val what = if (prefs.overlayClosed) "Bảng nổi đã đóng" else "Đã dừng"
                    BotStatus.set("$what · hôm nay đã xóa ${prefs.deletedToday()}")
                }
            }
        }
    }

    /** ■: dừng tác vụ đang chạy (người dùng chủ động dừng nên không tự chạy tiếp nữa). */
    fun stopJob() {
        prefs.running = false
        job?.cancel()
    }

    /** Lưu ảnh vào files/debug/ (nếu bật trong màn hình chính) để tra lỗi; giữ tối đa MAX_DEBUG_IMAGES ảnh mới nhất. */
    fun saveImage(img: Mat, name: String) {
        if (!prefs.saveImages) return
        val copy = img.clone()
        scope.launch(Dispatchers.IO) {
            val dir = File(getExternalFilesDir(null), "debug").apply { mkdirs() }
            Imgcodecs.imwrite(File(dir, "${LocalDateTime.now().format(FILE_TIME)}-$name.png").path, copy)
            copy.release()
            dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_DEBUG_IMAGES)?.forEach { it.delete() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance == null) return
        instance = null
        scope.cancel()
        overlay?.hide()
        overlay = null
        shotExecutor.shutdown()
        AppLog.i("Đã tắt dịch vụ Trợ năng.")
    }

    private var appScreenOpen = false       // màn hình chính của app đang mở: ẩn bảng nổi để không che nội dung
    private var overlayTempHidden = false   // ẩn tạm (bài thử kiểm tra bảng nổi có lọt vào ảnh chụp không)

    private fun applyOverlayVisibility() {
        overlay?.setVisible(!prefs.overlayClosed && !appScreenOpen && !overlayTempHidden)
    }

    fun setAppScreenOpen(open: Boolean) {
        appScreenOpen = open
        applyOverlayVisibility()
    }

    fun setOverlayTempHidden(hidden: Boolean) {
        overlayTempHidden = hidden
        applyOverlayVisibility()
    }

    /** Nút ✕: đóng bảng nổi. Bảng nổi giữ màn hình sáng cho bot nên đóng nó thì dừng bot luôn. */
    fun closeOverlay() {
        if (job?.isActive == true) {
            stopJob()
            AppLog.i("Đã đóng bảng nổi và dừng bot. Mở lại bằng nút Hiện bảng nổi trong app.")
        } else {
            AppLog.i("Đã đóng bảng nổi. Mở lại bằng nút Hiện bảng nổi trong app.")
        }
        prefs.overlayClosed = true
        applyOverlayVisibility()
        BotStatus.set("Bảng nổi đã đóng · hôm nay đã xóa ${prefs.deletedToday()}")
    }

    /** Nút Hiện bảng nổi trong app. */
    fun reopenOverlay() {
        prefs.overlayClosed = false
        applyOverlayVisibility()
        BotStatus.set("Sẵn sàng · hôm nay đã xóa ${prefs.deletedToday()}")
    }

    /** Vùng nút ≡ của bảng nổi trên màn hình, để kiểm tra bảng nổi có lọt vào ảnh chụp không. */
    fun overlayHandle() = overlay?.handleOnScreen()

    fun overlayBounds() = overlay?.boundsOnScreen()

    fun resetOverlayPosition() {
        overlay?.resetPosition()
    }

    fun refreshOverlay() {
        overlay?.refreshOptions()
    }

    // ---------------------------------------------------------------- Chụp màn hình

    suspend fun screenshot(): Shot {
        var retries = 0
        while (true) {
            val t0 = SystemClock.uptimeMillis()
            val result = withTimeoutOrNull(SCREENSHOT_TIMEOUT) { requestScreenshot() } ?: SCREENSHOT_NO_RESPONSE
            val t1 = SystemClock.uptimeMillis()
            if (result is ScreenshotResult) {
                val img = withContext(Dispatchers.Default) { toMat(result) }
                val since = if (lastShotAt == 0L) 0L else t1 - lastShotAt
                lastShotAt = t1
                return Shot(img, t1 - t0, SystemClock.uptimeMillis() - t1, retries, since)
            }
            val code = result as Int
            // Hệ thống giới hạn số lần chụp mỗi giây: chờ một chút rồi chụp lại.
            if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retries < 100) {
                retries++
                delay(40)
                continue
            }
            throw ScreenshotFailed(code)
        }
    }

    /** ScreenshotResult nếu chụp được, mã lỗi (Int) nếu không. */
    private suspend fun requestScreenshot(): Any = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                if (cont.isActive) cont.resume(result) else result.hardwareBuffer.close()
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(errorCode)
            }
        })
    }

    private fun toMat(result: ScreenshotResult): Mat {
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw IllegalStateException("không đọc được ảnh chụp màn hình")
            val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            return bgr
        } finally {
            buffer.close()
        }
    }

    // ---------------------------------------------------------------- Thao tác

    /** Thực hiện cử chỉ; false nếu bị huỷ hoặc hệ thống không trả lời trong thời lượng cử chỉ + GESTURE_TIMEOUT_EXTRA. */
    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val duration = (0 until gesture.strokeCount).maxOf { gesture.getStroke(it).let { s -> s.startTime + s.duration } }
        val result = withTimeoutOrNull(duration + GESTURE_TIMEOUT_EXTRA) {
            suspendCancellableCoroutine { cont ->
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                if (!dispatchGesture(gesture, callback, null)) cont.resume(false)
            }
        }
        if (result == null) AppLog.i("  Hệ thống không phản hồi thao tác chạm (màn hình đang bị che?).")
        return result == true
    }

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int) = Path().apply {
        moveTo(x0.toFloat(), y0.toFloat())
        if (x1 != x0 || y1 != y0) lineTo(x1.toFloat(), y1.toFloat())
    }

    private fun single(stroke: StrokeDescription) = GestureDescription.Builder().addStroke(stroke).build()

    suspend fun tap(x: Int, y: Int) = dispatch(single(StrokeDescription(line(x, y, x, y), 0, 60)))

    suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, ms: Long) =
        dispatch(single(StrokeDescription(line(x0, y0, x1, y1), 0, ms)))

    /** Kéo dọc rồi giữ ngón tay gần như yên holdMs trước khi nhả để danh sách không trôi theo quán tính.
     *  Đoạn giữ nhích 1px chứ không đứng hẳn: nét đứng yên không sinh sự kiện MOVE nào, khi đó Android vẫn tính
     *  vận tốc từ đoạn kéo trước và danh sách trôi tiếp (đo trên máy ảo: trôi thêm ~20% quãng kéo). */
    suspend fun drag(x: Int, yFrom: Int, yTo: Int, moveMs: Long, holdMs: Long): Boolean {
        val move = StrokeDescription(line(x, yFrom, x, yTo), 0, moveMs, holdMs > 0)
        if (!dispatch(single(move))) return false
        if (holdMs <= 0) return true
        val nudge = if (yTo < yFrom) -1 else 1
        return dispatch(single(move.continueStroke(line(x, yTo, x, yTo + nudge), 0, holdMs, false)))
    }

    // ---------------------------------------------------------------- Trạng thái máy

    fun screenOn() = getSystemService(PowerManager::class.java).isInteractive
}
