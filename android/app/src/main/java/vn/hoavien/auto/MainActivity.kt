package vn.hoavien.auto

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlin.reflect.KMutableProperty0

private const val GAME_PACKAGE = "com.myflower.gp"
private const val DONE_COLOR = 0xFF2E7D32.toInt()
private const val TODO_COLOR = 0xFF212121.toInt()

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var botStatus: TextView
    private lateinit var logView: TextView
    private val logListener: () -> Unit = { logView.text = AppLog.recent(200).joinToString("\n") }
    private val statusListener: () -> Unit = { showStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        botStatus = findViewById(R.id.botStatus)
        logView = findViewById(R.id.log)
        findViewById<View>(R.id.scroll).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.openA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.openAppInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        findViewById<View>(R.id.allowNotify).setOnClickListener { askNotifications() }
        findViewById<View>(R.id.reopenOverlay).setOnClickListener {
            BotService.instance?.reopenOverlay()
            Toast.makeText(this, "Bảng nổi sẽ hiện khi bạn rời màn hình này", Toast.LENGTH_SHORT).show()
            showSteps()
        }
        findViewById<View>(R.id.openGame).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Không tìm thấy game trên máy này", Toast.LENGTH_SHORT).show()
        }

        bindNumber(R.id.maxPoints, prefs::maxPoints, 1..999)
        bindNumber(R.id.intervalMin, prefs::intervalMin, 1..1440)
        bindNumber(R.id.maxDeletes, prefs::maxDeletes, 0..999)
        bindNumber(R.id.dragDistance, prefs::dragDistance, 50..1200)
        bindNumber(R.id.dragMs, prefs::dragMs, 20..5000)
        bindNumber(R.id.holdMs, prefs::holdMs, 0..3000)
        bindCheck(R.id.saveImages, prefs::saveImages)
        bindCheck(R.id.showProbes, prefs::showProbes)

        val advanced = findViewById<View>(R.id.advanced)
        val toggle = findViewById<TextView>(R.id.toggleAdvanced)
        toggle.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "Nâng cao ▴" else "Nâng cao ▾"
        }
        findViewById<TextView>(R.id.filesDir).text =
            "Ảnh (tối đa 100 ảnh mới nhất) và nhật ký lưu tại: ${getExternalFilesDir(null)} (thư mục debug/ và nhat_ky.txt)"
        findViewById<View>(R.id.copyLog).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nhật ký", AppLog.all()))
            Toast.makeText(this, "Đã sao chép nhật ký", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.clearLog).setOnClickListener { AppLog.clear() }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = "Hoa Viên Auto $version"
    }

    override fun onResume() {
        super.onResume()
        BotService.instance?.setAppScreenOpen(true)
        showSteps()
        showStatus()
        AppLog.addListener(logListener)
        BotStatus.addListener(statusListener)
        logListener()
    }

    override fun onPause() {
        BotService.instance?.let {
            it.refreshOverlay()
            it.setAppScreenOpen(false)
        }
        AppLog.removeListener(logListener)
        BotStatus.removeListener(statusListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showSteps()
    }

    private fun showStatus() {
        botStatus.text = if (BotService.instance == null) "Chưa bật Trợ năng - làm theo các bước bên dưới."
                         else BotStatus.text
    }

    /** Đánh dấu ✓ các bước đã xong, ẩn nút của bước đã xong. */
    private fun showSteps() {
        val a11y = BotService.instance != null
        val notify = Notifier.allowed(this)
        mark(R.id.step1, a11y)
        for (id in listOf(R.id.step1Hint, R.id.openA11y)) findViewById<View>(id).visibility = if (a11y) View.GONE else View.VISIBLE
        // "Cài đặt bị hạn chế" chỉ có từ Android 13, với app cài từ file APK.
        val restricted = !a11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        for (id in listOf(R.id.restrictedHint, R.id.openAppInfo)) findViewById<View>(id).visibility = if (restricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.reopenOverlay).visibility = if (a11y && prefs.overlayClosed) View.VISIBLE else View.GONE
        mark(R.id.step2, notify)
        findViewById<View>(R.id.allowNotify).visibility = if (notify) View.GONE else View.VISIBLE
    }

    private fun mark(id: Int, done: Boolean) {
        val v = findViewById<TextView>(id)
        val text = v.text.toString().removePrefix("✓ ")
        v.text = if (done) "✓ $text" else text
        v.setTextColor(if (done) DONE_COLOR else TODO_COLOR)
    }

    /** Xin quyền thông báo; đã từ chối hẳn thì mở trang cài đặt thông báo của app. */
    private fun askNotifications() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !prefsAskedNotify()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Lần đầu bấm thì xin quyền bằng hộp thoại; các lần sau (đã bị từ chối hẳn) mở trang cài đặt. */
    private fun prefsAskedNotify(): Boolean {
        val p = getSharedPreferences("thu_nghiem", MODE_PRIVATE)
        val asked = p.getBoolean("asked_notify", false)
        p.edit().putBoolean("asked_notify", true).apply()
        return asked
    }

    private fun bindCheck(id: Int, prop: KMutableProperty0<Boolean>) {
        findViewById<CheckBox>(id).apply {
            isChecked = prop.get()
            setOnCheckedChangeListener { _, checked -> prop.set(checked) }
        }
    }

    /** Ô nhập số gắn với một thông số; chỉ lưu khi giá trị nằm trong khoảng cho phép. */
    private fun bindNumber(id: Int, prop: KMutableProperty0<Int>, range: IntRange) {
        val edit = findViewById<EditText>(id)
        edit.setText(prop.get().toString())
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null && v in range) {
                    prop.set(v)
                    edit.error = null
                } else {
                    edit.error = "${range.first}–${range.last}"
                }
            }
        })
    }
}
