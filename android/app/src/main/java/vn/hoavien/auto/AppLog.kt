package vn.hoavien.auto

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Nhật ký dùng chung cho màn hình chính và bảng nổi; ghi thêm ra file nhat_ky.txt (xoay vòng 2MB). */
object AppLog {
    private const val TAG = "HoaVien"
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null), "nhat_ky.txt")
    }

    fun i(msg: String) = add(msg, null)

    fun e(msg: String, t: Throwable) = add(msg, t)

    private fun add(msg: String, t: Throwable?) {
        val line = "${LocalTime.now().format(timeFormat)} $msg"
        if (t == null) Log.i(TAG, msg) else Log.e(TAG, msg, t)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        file?.let { f ->
            io.execute {
                runCatching {
                    if (f.length() > 2_000_000) f.renameTo(File(f.path + ".1"))
                    f.appendText(line + "\n" + (t?.stackTraceToString() ?: ""))
                }
            }
        }
        notifyListeners()
    }

    fun recent(n: Int): List<String> = synchronized(lines) { lines.takeLast(n) }

    fun all(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        notifyListeners()
    }

    /** Listener luôn được gọi trên luồng chính. */
    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }
}
