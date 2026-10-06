package vn.hoavien.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArraySet

/** Một dòng trạng thái của bot, hiện trên bảng nổi và màn hình chính. Listener luôn được gọi trên luồng chính. */
object BotStatus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var text: String = "Chưa chạy"
        private set

    fun set(text: String) {
        this.text = text
        main.post { listeners.forEach { it() } }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)
}

/** Số nhiệm vụ đã xóa trong ngày (theo lịch máy), lưu lại qua các lần chạy. */
fun Prefs.deletedToday(): Int = if (statDate == LocalDate.now().toString()) statDeleted else 0

fun Prefs.addDeletedToday() {
    val today = LocalDate.now().toString()
    statDeleted = (if (statDate == today) statDeleted else 0) + 1
    statDate = today
}

/** Thông báo cho người dùng khi bot cần người can thiệp (game bị thoát, màn hình bị che, pin yếu...). */
object Notifier {
    private const val CHANNEL = "can_nguoi"
    const val ID_WAITING = 1        // bot đang chờ (không thấy bảng Nhiệm Vụ, màn hình bị che...)
    const val ID_STOPPED = 2        // bot đã dừng (lỗi, tài khoản không xóa được)
    const val ID_BATTERY = 3

    fun allowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, id: Int, title: String, text: String) {
        if (!allowed(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cần bạn xử lý", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Báo khi bot không tự làm tiếp được" })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
