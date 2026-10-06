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

/** Thông số thử nghiệm, chỉnh ở màn hình chính. Quãng kéo tính theo px của máy gốc (rộng 1080). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("thu_nghiem", Context.MODE_PRIVATE)

    var dragDistance: Int
        get() = p.getInt("drag_distance", 540)
        set(v) = p.edit().putInt("drag_distance", v).apply()

    var dragMs: Int
        get() = p.getInt("drag_ms", 500)
        set(v) = p.edit().putInt("drag_ms", v).apply()

    var holdMs: Int
        get() = p.getInt("hold_ms", 150)
        set(v) = p.edit().putInt("hold_ms", v).apply()

    var saveImages: Boolean
        get() = p.getBoolean("save_images", true)
        set(v) = p.edit().putBoolean("save_images", v).apply()
}
