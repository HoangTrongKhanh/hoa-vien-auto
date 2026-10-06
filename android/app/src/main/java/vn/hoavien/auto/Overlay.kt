package vn.hoavien.auto

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Màu đặc của nút ≡ trên bảng nổi; bài đo chụp màn hình dò màu này để biết bảng nổi có lọt vào ảnh chụp không. */
const val OVERLAY_MARKER_COLOR = 0xFF37474F.toInt()
private const val LOG_LINES = 4

/** Bảng nổi gọn (1 hàng nút + vài dòng nhật ký) ở mép trên màn hình, nằm trên vùng tiêu đề của game để không che
 *  tab, lưới và khung chi tiết. Là cửa sổ Trợ năng nên không cần quyền "hiển thị trên ứng dụng khác".
 *  Giữ màn hình luôn sáng khi đang hiện (FLAG_KEEP_SCREEN_ON), thay cho "svc power stayon" của bản ADB. */
class Overlay(private val svc: BotService, private val probes: Probes) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val density = svc.resources.displayMetrics.density
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(4)
        y = dp(28)
    }
    private val root = LinearLayout(svc)
    private val handle = TextView(svc)
    private val logView = TextView(svc)
    private val actions = mutableListOf<TextView>()
    private lateinit var stopButton: TextView
    private var collapsed = false
    private val logListener: () -> Unit = {
        logView.text = AppLog.recent(6).joinToString("\n") { it.substringAfter(' ').trim() }   // bỏ giờ cho gọn
        logView.post { scrollLogToEnd() }
    }

    /** Ô nhật ký cao cố định LOG_LINES dòng; dòng dài xuống hàng thì cuộn để luôn thấy dòng mới nhất. */
    private fun scrollLogToEnd() {
        val layout = logView.layout ?: return
        val visible = logView.height - logView.totalPaddingTop - logView.totalPaddingBottom
        val overflow = layout.height - visible
        if (overflow <= 0) return logView.scrollTo(0, 0)
        var first = layout.getLineForVertical(overflow)
        if (layout.getLineTop(first) < overflow) first++         // không để dòng trên cùng bị cắt nửa
        logView.scrollTo(0, layout.getLineTop(first))
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    init {
        root.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        handle.apply {
            text = "≡"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(OVERLAY_MARKER_COLOR)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnTouchListener(DragToMove())
        }
        row.addView(handle, margins())
        for ((label, action) in listOf(
            "Chụp" to probes::captureProbe,
            "Chạm" to probes::tapProbe,
            "Kéo" to probes::dragProbe,
            "Đầu" to probes::topProbe,
            "Khóa" to probes::lockProbe,
        )) {
            actions += chip(label, 0xFF455A64.toInt()) { action() }.also { row.addView(it, margins()) }
        }
        stopButton = chip("■", 0xFFC62828.toInt()) { probes.stop() }.also { row.addView(it, margins()) }
        root.addView(row)
        logView.apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            setLines(LOG_LINES)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = rounded(0xCC000000.toInt())
        }
        root.addView(logView, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        probes.onStateChanged = ::showState
        showState(null)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6).toFloat()
    }

    private fun margins() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }

    private fun chip(label: String, color: Int, onClick: () -> Unit) = TextView(svc).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(color)
        setPadding(dp(9), dp(6), dp(9), dp(6))
        setOnClickListener { onClick() }
    }

    private fun showState(running: String?) {
        for (b in actions) {
            b.isEnabled = running == null
            b.alpha = if (running == null) 1f else 0.4f
        }
        stopButton.isEnabled = running != null
        stopButton.alpha = if (running != null) 1f else 0.4f
    }

    private fun setCollapsed(c: Boolean) {
        collapsed = c
        val vis = if (c) View.GONE else View.VISIBLE
        for (b in actions) b.visibility = vis
        stopButton.visibility = vis
        logView.visibility = vis
    }

    fun show() {
        wm.addView(root, params)
        AppLog.addListener(logListener)
        logListener()
    }

    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun hide() {
        AppLog.removeListener(logListener)
        runCatching { wm.removeView(root) }
    }

    fun markerOnScreen(): Pair<Int, Int>? {
        if (!handle.isAttachedToWindow || handle.height == 0) return null
        val loc = IntArray(2)
        handle.getLocationOnScreen(loc)
        return loc[0] + dp(3) to loc[1] + handle.height / 2     // mép trái nút ≡: chỉ có màu nền, không có chữ
    }

    /** Kéo nút ≡ để di chuyển bảng nổi; chạm (không kéo) để thu gọn/mở rộng. */
    private inner class DragToMove : View.OnTouchListener {
        private val slop = ViewConfiguration.get(svc).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) setCollapsed(!collapsed)
            }
            return true
        }
    }
}
