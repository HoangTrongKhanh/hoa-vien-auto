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
        templates = Templates.load { File(root, "templates/$it").readBytes() }
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

    @Test
    fun cardsAndSelection() {
        for (r in rows("cards")) {
            val img = image(r[1])
            val ui = uiOf(r[1])
            val want = r.getOrElse(2) { "" }.split(";").filter { it.isNotEmpty() }.map { c ->
                c.split(",").map(String::toInt)
            }
            val got = findCards(img, ui)
            assertEquals(want.size, got.size, "${r[1]}: số ô có la bàn (Python ${want.map { it.take(2) }}, Kotlin ${got.map { it.x to it.y }})")
            for ((w, g) in want.zip(got)) {
                assertTrue(abs(w[0] - g.x) <= 1 && abs(w[1] - g.y) <= 1, "${r[1]}: vị trí ô ${w.take(2)} != (${g.x},${g.y})")
                assertEquals(w[2] == 1, isSelected(img, g, ui), "${r[1]}: ô (${g.x},${g.y}) đang chọn")
            }
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
