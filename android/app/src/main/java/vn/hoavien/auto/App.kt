package vn.hoavien.auto

import android.app.Application
import android.content.Context
import org.opencv.android.OpenCVLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        if (!OpenCVLoader.initLocal()) AppLog.i("Không nạp được thư viện OpenCV.")
    }
}

/** Cài đặt, chỉnh ở màn hình chính. Bot đọc lại ở đầu mỗi lượt quét. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("thu_nghiem", Context.MODE_PRIVATE)

    /** Xóa nhiệm vụ có điểm <= số này. */
    var maxPoints: Int
        get() = p.getInt("max_points", 12)
        set(v) = p.edit().putInt("max_points", v).apply()

    /** Số phút giữa 2 lượt quét. */
    var intervalMin: Int
        get() = p.getInt("interval_min", 15)
        set(v) = p.edit().putInt("interval_min", v).apply()

    /** Xóa tối đa bấy nhiêu nhiệm vụ mỗi lượt, 0 = không giới hạn. */
    var maxDeletes: Int
        get() = p.getInt("max_deletes", 0)
        set(v) = p.edit().putInt("max_deletes", v).apply()

    // Thông số bài thử kéo. Quãng kéo tính theo px của máy gốc (rộng 1080).
    var dragDistance: Int
        get() = p.getInt("drag_distance", DRAG_DISTANCE)
        set(v) = p.edit().putInt("drag_distance", v).apply()

    var dragMs: Int
        get() = p.getInt("drag_ms", DRAG_MOVE_MS.toInt())
        set(v) = p.edit().putInt("drag_ms", v).apply()

    var holdMs: Int
        get() = p.getInt("hold_ms", DRAG_HOLD_MS.toInt())
        set(v) = p.edit().putInt("hold_ms", v).apply()

    var saveImages: Boolean
        get() = p.getBoolean("save_images", false)
        set(v) = p.edit().putBoolean("save_images", v).apply()

    /** Hiện nút ⋯ (các bài thử) trên bảng nổi. */
    var showProbes: Boolean
        get() = p.getBoolean("show_probes", false)
        set(v) = p.edit().putBoolean("show_probes", v).apply()

    /** Bot đang chạy liên tục (▶): dịch vụ khởi động lại thì chạy tiếp. Tắt khi người dùng bấm ■. */
    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()

    /** Người dùng đã đóng bảng nổi (nút ✕); mở lại bằng nút Hiện bảng nổi trong app. */
    var overlayClosed: Boolean
        get() = p.getBoolean("overlay_closed", false)
        set(v) = p.edit().putBoolean("overlay_closed", v).apply()

    /** Vị trí bảng nổi người dùng đã kéo tới, -1 = mặc định. */
    var overlayX: Int
        get() = p.getInt("overlay_x", -1)
        set(v) = p.edit().putInt("overlay_x", v).apply()

    var overlayY: Int
        get() = p.getInt("overlay_y", -1)
        set(v) = p.edit().putInt("overlay_y", v).apply()

    // Thống kê số nhiệm vụ đã xóa trong ngày (xem deletedToday()).
    var statDate: String
        get() = p.getString("stat_date", "") ?: ""
        set(v) = p.edit().putString("stat_date", v).apply()

    var statDeleted: Int
        get() = p.getInt("stat_deleted", 0)
        set(v) = p.edit().putInt("stat_deleted", v).apply()

    /** Số thứ tự ô lớn nhất từng thấy (tổng số ô của danh sách), 0 = chưa biết. */
    var knownLastCard: Int
        get() = p.getInt("known_last_card", 0)
        set(v) = p.edit().putInt("known_last_card", v).apply()
}
