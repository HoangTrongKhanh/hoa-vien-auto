package vn.hoavien.auto

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import vn.hoavien.auto.vision.GRID
import java.io.File

/** Game giả cho máy ảo (không có game thật), chỉ có trong bản debug. Hiển thị đúng 1 px ảnh = 1 px màn hình:
 *  - frame.png: ảnh chụp bảng Nhiệm Vụ làm khung tĩnh;
 *  - strip.png: dải ô nhiệm vụ dài (ghép từ nhiều ảnh trang), cuộn được trong vùng lưới GRID như game thật.
 *  Ghi vị trí cuộn và các lần chạm ra logcat (thẻ FakeGame) để so với số đo của app.
 *  Đẩy ảnh vào bộ nhớ trong của app:  adb shell run-as vn.hoavien.auto sh -c "cat > files/fake/frame.png" < frame.png
 *  Mở bằng:  adb shell am start -n vn.hoavien.auto/.FakeGameActivity */
class FakeGameActivity : Activity() {
    private var moves = 0
    private var lastMoveAt = 0L
    private var lastMoveY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, "fake")
        val root = FrameLayout(this)
        val frame = image(File(dir, "frame.png"))
        root.addView(frame, FrameLayout.LayoutParams(frame.drawable.intrinsicWidth, frame.drawable.intrinsicHeight))
        val strip = image(File(dir, "strip.png"))
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(strip, FrameLayout.LayoutParams(strip.drawable.intrinsicWidth, strip.drawable.intrinsicHeight))
        }
        root.addView(scroll, FrameLayout.LayoutParams(GRID.width, GRID.height).apply {
            leftMargin = GRID.x0
            topMargin = GRID.y0
        })
        var settle: Runnable? = null
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            settle?.let(scroll::removeCallbacks)
            settle = Runnable { Log.i("FakeGame", "scrollY=$y") }.also { scroll.postDelayed(it, 300) }
        }
        setContentView(root)
    }

    /** Ảnh hiển thị đúng kích thước điểm ảnh (không co giãn theo mật độ màn hình hay theo khung chứa). */
    private fun image(f: File) = ImageView(this).apply {
        setImageBitmap(BitmapFactory.decodeFile(f.path).apply { density = resources.displayMetrics.densityDpi })
        scaleType = ImageView.ScaleType.MATRIX
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moves = 0
                Log.i("FakeGame", "chạm DOWN (${ev.rawX.toInt()},${ev.rawY.toInt()})")
            }
            MotionEvent.ACTION_MOVE -> {
                moves++
                lastMoveAt = ev.eventTime
                lastMoveY = ev.rawY
            }
            MotionEvent.ACTION_UP -> Log.i("FakeGame", "chạm UP (${ev.rawX.toInt()},${ev.rawY.toInt()}): $moves MOVE, " +
                "MOVE cuối ở y=${lastMoveY.toInt()} cách UP ${ev.eventTime - lastMoveAt}ms")
        }
        return super.dispatchTouchEvent(ev)
    }
}
