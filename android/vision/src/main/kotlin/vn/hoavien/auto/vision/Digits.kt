package vn.hoavien.auto.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Đọc số điểm bằng mẫu chữ số, chuyển từ xoa_nhiem_vu.py.
const val DIGIT_SIZE = 32
const val DIGIT_THRESHOLD = 0.65        // dưới mức này coi như không phải chữ số
const val DIGIT_MIN_MARGIN = 0.10       // chữ số khác giống ngang ngửa trong khoảng này vẫn bị coi là khả dĩ

/** value: cách đọc giống nhất; low/high: nhỏ nhất/lớn nhất trong các cách đọc còn khả dĩ; null nếu không đọc được. */
data class Reading(val value: Int?, val low: Int?, val high: Int?, val detail: String)

enum class Verdict { DELETE, KEEP, UNSURE }

/** DELETE nếu mọi cách đọc khả dĩ đều <= ngưỡng, KEEP nếu đều > ngưỡng, còn lại UNSURE. */
fun decide(r: Reading, maxPoints: Int): Verdict = when {
    r.value == null || r.low == null || r.high == null -> Verdict.UNSURE
    r.high <= maxPoints -> Verdict.DELETE
    r.low > maxPoints -> Verdict.KEEP
    else -> Verdict.UNSURE
}

/** Phân vị kiểu numpy.percentile (nội suy tuyến tính) của mảng đã sắp xếp. */
private fun percentile(sorted: FloatArray, q: Double): Double {
    val pos = q / 100 * (sorted.size - 1)
    val lo = floor(pos).toInt()
    val hi = min(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

/** Độ đậm của nét chữ 0..1 so với nền sáng (giữ mép mờ thay vì cắt ngưỡng cứng). Mat CV_32F. */
private fun inkMap(roi: Mat): Mat {
    val gray = Mat()
    Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
    val bytes = ByteArray(gray.rows() * gray.cols())
    gray.get(0, 0, bytes)
    val values = FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF).toFloat() }
    val sorted = values.sortedArray()
    val bg = percentile(sorted, 90.0)
    val dark = percentile(sorted, 3.0)
    val ink = FloatArray(values.size)
    if (bg - dark >= 40) {                                  // dưới 40: không có chữ
        val range = (bg - dark).toFloat()
        for (i in values.indices) ink[i] = ((bg.toFloat() - values[i]) / range).coerceIn(0f, 1f)
    }
    val out = Mat(gray.rows(), gray.cols(), CvType.CV_32F)
    out.put(0, 0, ink)
    gray.release()
    return out
}

/** Khung (x, y, w, h) của từng chữ số, trái sang phải. */
private fun digitBoxes(ink: Mat, minHeight: Int): List<IntArray> {
    val mask = Mat()
    Imgproc.threshold(ink, mask, 0.5, 1.0, Imgproc.THRESH_BINARY)
    mask.convertTo(mask, CvType.CV_8U)
    val labels = Mat()
    val stats = Mat()
    val centroids = Mat()
    val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
    val boxes = mutableListOf<IntArray>()
    val row = IntArray(5)
    for (i in 1 until n) {
        stats.get(i, 0, row)
        val (x, y, w, h, area) = row
        if (h >= minHeight && area >= 40 && x > 0) boxes += intArrayOf(x, y, w, h)  // x > 0: bỏ mép icon bị cắt ở biên trái
    }
    for (m in listOf(mask, labels, stats, centroids)) m.release()
    return boxes.sortedWith(compareBy<IntArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
}

/** Co về chiều cao DIGIT_SIZE giữ tỉ lệ, đặt giữa khung vuông. Trả về DIGIT_SIZE*DIGIT_SIZE giá trị 0..1. */
private fun normalizeDigit(ink: Mat, box: IntArray): FloatArray {
    val (x, y, w, h) = box
    val nw = max(1, min(DIGIT_SIZE, pyRound(w * DIGIT_SIZE.toDouble() / h)))
    val r = Mat()
    ink.region(Box(x, y, x + w, y + h)) { Imgproc.resize(it, r, Size(nw.toDouble(), DIGIT_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) }
    val vals = FloatArray(nw * DIGIT_SIZE)
    r.get(0, 0, vals)
    r.release()
    val out = FloatArray(DIGIT_SIZE * DIGIT_SIZE)
    val off = (DIGIT_SIZE - nw) / 2
    for (row in 0 until DIGIT_SIZE) for (col in 0 until nw) out[row * DIGIT_SIZE + off + col] = vals[row * nw + col]
    return out
}

private fun digitComponents(roi: Mat, minHeight: Int): List<FloatArray> {
    val ink = inkMap(roi)
    val comps = digitBoxes(ink, minHeight).map { normalizeDigit(ink, it) }
    ink.release()
    return comps
}

/** Độ giống (1 = giống hệt) của comp với từng chữ số 0-9, theo thứ tự chữ số xuất hiện trong mẫu. */
private fun digitScores(comp: FloatArray, digits: List<DigitTemplate>): LinkedHashMap<Int, Double> {
    val best = LinkedHashMap<Int, Double>()
    for (t in digits) {
        var diff = 0
        for (i in comp.indices) if ((comp[i] > 0.5f) != t.mask[i]) diff++
        val s = 1.0 - diff.toDouble() / comp.size * 2.5
        best[t.digit] = max(best[t.digit] ?: -9.0, s)
    }
    return best
}

/** Đọc số nguyên trong box. Mỗi chữ số giữ lại mọi chữ số gần giống ngang ngửa (chênh < DIGIT_MIN_MARGIN)
 *  để biết khoảng giá trị khả dĩ; quyết định xóa chỉ dựa trên cả khoảng đó. */
fun readNumber(img: Mat, box: Box, ui: Ui): Reading {
    val b = box.clip(img.cols(), img.rows())
    if (b.width <= 0 || b.height <= 0) return Reading(null, null, null, "vùng số nằm ngoài ảnh")
    val comps = img.region(b) { digitComponents(it, ui.d(16)) }
    if (comps.isEmpty() || comps.size > 3) return Reading(null, null, null, "${comps.size} thành phần")
    var readings = listOf("")
    val best = StringBuilder()
    val parts = mutableListOf<String>()
    for (c in comps) {
        val scores = digitScores(c, ui.digits)
        val (d1, s1) = scores.entries.maxBy { it.value }.toPair()
        parts += String.format(Locale.ROOT, "%d(%.2f)", d1, s1)
        if (s1 < DIGIT_THRESHOLD) return Reading(null, null, null, parts.joinToString(" ") + " - không giống chữ số nào")
        val alts = scores.filter { it.value >= s1 - DIGIT_MIN_MARGIN }.keys.map { it.toString() }
        if (alts.size > 1) parts[parts.size - 1] += "~" + alts.filter { it != d1.toString() }.joinToString("/")
        best.append(d1)
        readings = readings.flatMap { r -> alts.map { r + it } }
    }
    val values = readings.map { it.toInt() }
    return Reading(best.toString().toInt(), values.min(), values.max(), parts.joinToString(" "))
}

/** Ảnh đánh dấu để tra lỗi: khung lưới (xanh dương) và từng ô theo quyết định (đỏ = xóa, xanh lá = giữ,
 *  cam = không chắc) kèm số điểm đọc được. */
fun annotate(img: Mat, tasks: List<Task>, maxPoints: Int, ui: Ui?): Mat {
    val out = img.clone()
    if (ui == null) return out
    Imgproc.rectangle(out, Point(ui.grid.x0.toDouble(), ui.grid.y0.toDouble()),
        Point(ui.grid.x1.toDouble(), ui.grid.y1.toDouble()), Scalar(255.0, 0.0, 0.0), 2)
    for (t in tasks) {
        val color = when (decide(t.reading, maxPoints)) {
            Verdict.DELETE -> Scalar(0.0, 0.0, 255.0)
            Verdict.KEEP -> Scalar(0.0, 180.0, 0.0)
            Verdict.UNSURE -> Scalar(0.0, 200.0, 255.0)
        }
        Imgproc.rectangle(out, Point((t.x - ui.d(4)).toDouble(), (t.y - ui.d(10)).toDouble()),
            Point((t.x + ui.d(116)).toDouble(), (t.y + ui.d(58)).toDouble()), color, 3)
        Imgproc.putText(out, t.reading.value?.toString() ?: "?", Point(t.x.toDouble(), (t.y - ui.d(14)).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX, ui.s, color, 3)
    }
    return out
}
