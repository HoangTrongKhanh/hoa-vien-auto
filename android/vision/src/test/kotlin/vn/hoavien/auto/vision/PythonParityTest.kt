package vn.hoavien.auto.vision

import nu.pattern.OpenCV
import org.opencv.core.Mat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** So kết quả nhận diện của bản Kotlin với bản Python (ket_qua_python.tsv, tạo bằng tao_ket_qua_python.py)
 *  trên mọi ảnh trong samples/. */
class PythonParityTest {
    private val root = File(System.getProperty("hoavien.root") ?: "../..")
    private val templates: Templates
    private val images = mutableMapOf<String, Mat>()
    private val expected: List<List<String>>

    init {
        OpenCV.loadLocally()
        templates = Templates.load(
            read = { File(root, "templates/$it").readBytes() },
            list = { File(root, "templates/$it").list()!!.toList() },
        )
        expected = javaClass.getResource("/ket_qua_python.tsv")!!.readText().lines()
            .filter { it.isNotBlank() }.map { it.split("\t") }
    }

    private fun image(rel: String) = images.getOrPut(rel) { decodeImage(File(root, rel).readBytes()) }

    private fun rows(kind: String) = expected.filter { it[0] == kind }.also { assertTrue(it.isNotEmpty(), "thiếu dòng '$kind'") }

    private fun uiOf(rel: String) = assertNotNull(Ui.detect(image(rel), templates), "$rel: không thấy bảng Nhiệm Vụ")

    @Test
    fun layoutDetection() {
        for (r in rows("ui")) {
            val ui = Ui.detect(image(r[1]), templates)
            if (r[2] == "none") {
                assertNull(ui, "${r[1]}: Python không thấy bảng Nhiệm Vụ")
                continue
            }
            assertNotNull(ui, "${r[1]}: không thấy bảng Nhiệm Vụ")
            assertEquals(r[2].toDouble(), ui.s, 1e-5, "${r[1]}: tỉ lệ")
            assertEquals(r[3].toDouble(), ui.dx, 1.0, "${r[1]}: lệch ngang")
            assertEquals(r[4].toDouble(), ui.dy, 1.0, "${r[1]}: lệch dọc")
        }
    }

    private fun num(s: String) = if (s == "-") null else s.toInt()

    private fun assertReading(want: List<String>, got: Reading, msg: String) =
        assertEquals(want.map(::num), listOf(got.value, got.low, got.high), "$msg [${got.detail}]")

    private fun pos(s: String) = if (s == "none") null else s.split(",").map(String::toInt).let { it[0] to it[1] }

    private fun assertNear(want: Pair<Int, Int>?, got: Pair<Int, Int>?, msg: String) {
        if (want == null || got == null) return assertEquals(want, got, msg)
        assertTrue(abs(want.first - got.first) <= 1 && abs(want.second - got.second) <= 1, "$msg: $want != $got")
    }

    @Test
    fun tasksReadingsAndSelection() {
        var readings = 0
        for (r in rows("tasks")) {
            val img = image(r[1])
            val ui = uiOf(r[1])
            val want = r.getOrElse(2) { "" }.split(";").filter { it.isNotEmpty() }.map { it.split(",") }
            val got = findTasks(img, ui)
            assertEquals(want.size, got.size, "${r[1]}: số ô có la bàn (Python ${want.map { it.take(2) }}, Kotlin ${got.map { it.x to it.y }})")
            for ((w, g) in want.zip(got)) {
                assertTrue(abs(w[0].toInt() - g.x) <= 1 && abs(w[1].toInt() - g.y) <= 1, "${r[1]}: vị trí ô ${w.take(2)} != (${g.x},${g.y})")
                assertEquals(w[2] == "1", isSelected(img, g, ui), "${r[1]}: ô (${g.x},${g.y}) đang chọn")
                assertReading(w.subList(3, 6), g.reading, "${r[1]}: điểm ô (${g.x},${g.y})")
                readings++
            }
        }
        println("Đã so $readings ô nhiệm vụ")
    }

    @Test
    fun panelAndButtons() {
        for (r in rows("panel")) assertReading(r.subList(2, 5), readPanelPoints(image(r[1]), uiOf(r[1])), "${r[1]}: khung chi tiết")
        for (r in rows("delete")) assertNear(pos(r[2]), findDeleteButton(image(r[1]), uiOf(r[1])), "${r[1]}: nút Xóa")
        for (r in rows("dialog")) {
            val ui = uiOf(r[1])
            assertNear(pos(r[2]), findDialogButton(image(r[1]), ui.btnConfirm, ui), "${r[1]}: nút Xác nhận")
            assertNear(pos(r[3]), findDialogButton(image(r[1]), ui.btnCancel, ui), "${r[1]}: nút Hủy")
        }
    }

    @Test
    fun badgeNumbers() {
        var count = 0
        for (r in rows("badges")) {
            val img = image(r[1])
            val ui = uiOf(r[1])
            val want = r.getOrElse(2) { "" }.split(";").filter { it.isNotEmpty() }.map { it.split(",") }
            val got = findBadges(img, ui)
            assertEquals(want.map { num(it[2]) }, got.map { it.number }, "${r[1]}: số thứ tự ô")
            for ((w, g) in want.zip(got)) {
                assertTrue(abs(w[0].toInt() - g.x) <= 1 && abs(w[1].toInt() - g.y) <= 1, "${r[1]}: huy hiệu ${w.take(2)} != (${g.x},${g.y})")
            }
            count += got.size
        }
        for (r in rows("tasknum")) {
            val img = image(r[1])
            val ui = uiOf(r[1])
            val badges = findBadges(img, ui)
            val want = r.getOrElse(2) { "" }.split(";").filter { it.isNotEmpty() }.map(::num)
            assertEquals(want, findTasks(img, ui).map { taskNumber(it, badges, ui) }, "${r[1]}: số thứ tự ô của nhiệm vụ")
        }
        println("Đã so $count huy hiệu")
    }

    /** Nhãn do người xem ảnh ghi tay (samples/labels.txt: ảnh, x, y gần ô, điểm đúng). */
    @Test
    fun handLabels() {
        for (line in File(root, "samples/labels.txt").readLines().filter { it.isNotBlank() }) {
            val (rel, xs, ys, value) = line.split(" ")
            val img = image(rel)
            val ui = uiOf(rel)
            val t = findTasks(img, ui).minByOrNull { abs(it.x - xs.toInt()) + abs(it.y - ys.toInt()) }
            assertNotNull(t, "$rel: không thấy ô gần ($xs,$ys)")
            assertEquals(value.toInt(), t.reading.value, "$rel: ô gần ($xs,$ys) [${t.reading.detail}]")
        }
    }

    @Test
    fun topAndBrightness() {
        for (r in rows("attop")) assertEquals(r[2] == "1", atTop(image(r[1]), uiOf(r[1])), "${r[1]}: ở trên cùng")
        for (r in rows("bright")) {
            assertEquals(r[2].toDouble(), tabBrightness(image(r[1]), uiOf(r[1])), 0.02, "${r[1]}: độ sáng tab")
        }
    }

    @Test
    fun lockIcon() {
        for (r in rows("lock")) {
            val got = findLockIcon(image(r[1]))
            if (r[2] == "none") {
                assertNull(got, "${r[1]}: Python không thấy ổ khóa")
            } else {
                val (x, y) = r[2].split(",").map(String::toInt)
                assertNotNull(got, "${r[1]}: không thấy ổ khóa")
                assertTrue(abs(got.first - x) <= 2 && abs(got.second - y) <= 2, "${r[1]}: ổ khóa ($x,$y) != $got")
            }
        }
    }

    @Test
    fun scrollMeasurement() {
        for (r in rows("shift")) {
            val ui = uiOf(r[2])
            val got = gridShift(image(r[1]), image(r[2]), ui, band = r[3].toInt())
            val msg = "${r[1]} -> ${r[2]} (dải ${r[3]}): Python ${r[4]}px/${r[5]}, Kotlin ${got.px}px/${"%.4f".format(got.score)}"
            if (abs(got.px - r[4].toInt()) > 1 || abs(got.score - r[5].toDouble()) > 0.01) fail(msg)
        }
        for (r in rows("unchanged")) {
            assertEquals(r[3] == "1", gridUnchanged(image(r[1]), image(r[2]), uiOf(r[2])), "${r[1]} -> ${r[2]}: đứng yên")
        }
    }
}
