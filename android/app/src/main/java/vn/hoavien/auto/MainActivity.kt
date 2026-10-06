package vn.hoavien.auto

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val logListener: () -> Unit = { logView.text = AppLog.all() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
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
        bindNumber(R.id.dragDistance, prefs::dragDistance, 50..1200)
        bindNumber(R.id.dragMs, prefs::dragMs, 20..5000)
        bindNumber(R.id.holdMs, prefs::holdMs, 0..3000)
        findViewById<CheckBox>(R.id.saveImages).apply {
            isChecked = prefs.saveImages
            setOnCheckedChangeListener { _, checked -> prefs.saveImages = checked }
        }
        findViewById<TextView>(R.id.filesDir).text =
            "Ảnh chụp và nhật ký lưu tại: ${getExternalFilesDir(null)} (thư mục debug/ và nhat_ky.txt)"
        findViewById<View>(R.id.copyLog).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nhật ký", AppLog.all()))
            Toast.makeText(this, "Đã sao chép nhật ký", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.clearLog).setOnClickListener { AppLog.clear() }
    }

    override fun onResume() {
        super.onResume()
        val on = BotService.instance != null
        BotService.instance?.setOverlayVisible(false)
        status.text = if (on) "Trợ năng: ĐANG BẬT ✓ — bảng nổi hiện khi rời màn hình này" else "Trợ năng: CHƯA BẬT"
        status.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        AppLog.addListener(logListener)
        logListener()
    }

    override fun onPause() {
        BotService.instance?.setOverlayVisible(true)
        AppLog.removeListener(logListener)
        super.onPause()
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
