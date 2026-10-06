package vn.hoavien.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import vn.hoavien.auto.vision.Templates
import java.util.concurrent.Executors
import kotlin.coroutines.resume

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
    private lateinit var probes: Probes
    private var overlay: Overlay? = null
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var lastShotAt = 0L

    override fun onServiceConnected() {
        templates = Templates.load { name -> assets.open(name).use { it.readBytes() } }
        probes = Probes(this)
        overlay = Overlay(this, probes).also { it.show() }
        instance = this
        AppLog.i("Đã bật dịch vụ Trợ năng. Mở game ở bảng Nhiệm Vụ rồi dùng bảng nổi.")
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

    /** Ẩn bảng nổi khi màn hình chính của app đang mở (để không che nội dung), hiện lại khi rời đi. */
    fun setOverlayVisible(visible: Boolean) {
        overlay?.setVisible(visible)
    }

    /** Vùng nút ≡ của bảng nổi trên màn hình, để kiểm tra bảng nổi có lọt vào ảnh chụp không. */
    fun overlayHandle() = overlay?.handleOnScreen()

    // ---------------------------------------------------------------- Chụp màn hình

    suspend fun screenshot(): Shot {
        var retries = 0
        while (true) {
            val t0 = SystemClock.uptimeMillis()
            val result = requestScreenshot()
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

    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
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

    /** Các cửa sổ đang hiện (loại:tiêu đề), dùng để nhận ra lớp khóa của Game Booster. */
    fun windowSummary(): List<String> = try {
        windows.map { w ->
            val type = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "ứng dụng"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "hệ thống"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "bàn phím"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "trợ năng"
                else -> "loại ${w.type}"
            }
            "$type:${w.title ?: "?"}"
        }
    } catch (e: RuntimeException) {
        listOf("lỗi đọc cửa sổ: ${e.message}")
    }
}
