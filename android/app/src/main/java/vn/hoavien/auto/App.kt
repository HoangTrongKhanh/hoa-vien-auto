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
}
