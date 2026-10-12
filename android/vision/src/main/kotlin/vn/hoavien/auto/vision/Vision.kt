package vn.hoavien.auto.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Chuyển từ xoa_nhiem_vu.py, giữ nguyên thuật toán và ngưỡng. Ảnh dùng thứ tự kênh BGR như cv2 trong Python.
//
// Toạ độ đo trên máy gốc 1080x2340 (Samsung A54). Máy khác được quy đổi tự động (lớp Ui):
// game co giãn giao diện theo chiều ngang (tỉ lệ = rộng màn hình / 1080) và lệch theo chiều dọc,
// độ lệch lấy từ vị trí tab "Nhiệm Vụ".
const val REF_WIDTH = 1080
const val REF_TAB_X = 160                   // góc trên-trái mẫu tab "Nhiệm Vụ" trên máy gốc
const val REF_TAB_Y = 650
val GRID = Box(100, 800, 980, 1632)         // vùng lưới ô nhiệm vụ nhìn thấy
val PANEL = Box(90, 1700, 1000, 2010)       // khung chi tiết nhiệm vụ đang chọn
val PANEL_POINTS = Box(90, 1700, 1000, 1900)        // dải chứa la bàn + điểm trong khung chi tiết
val DELETE_BTN_REGION = Box(200, 1860, 560, 2010)
val ACCEPT_BTN_ZONE = Box(560, 1860, 900, 2010)     // nút "Nhận nhiệm vụ" - không bao giờ bấm
val DIALOG_REGION = Box(100, 1150, 1000, 1550)      // hộp thoại "Nhắc Nhở - Xác định xóa nhiệm vụ?"
const val TOP_BADGE_X = 150                 // huy hiệu "1" của ô đầu tiên khi danh sách ở trên cùng
const val TOP_BADGE_Y = 828
// Khe trống giữa các cột (kéo ở đây không bị hiểu là chạm vào ô): cột 1|2 (x 384-408), cột 2|3 (x 673-691).
val DRAG_GAPS = intArrayOf(396, 682)
const val SHIFT_BAND = 250                  // dải dưới cùng dùng để đo độ cuộn (đo được tới 582px)
// Đo độ cuộn trên ảnh màu thu nhỏ về SHIFT_SCALE (theo máy gốc): khớp đúng >= 0.90, khớp nhầm <= 0.80.
const val SHIFT_SCALE = 0.25
const val SHIFT_MATCH_THRESHOLD = 0.85
// Tìm mẫu trên ảnh thu nhỏ về MATCH_SCALE rồi tinh chỉnh ở độ phân giải gốc: nhanh hơn ~7 lần.
const val MATCH_SCALE = 0.5
// Mỗi ô ghi số điểm 2 lần: bên phải la bàn và bên phải viên kim cương (lệch thêm bấy nhiêu px máy gốc). Hai số này
// luôn bằng nhau (đã kiểm trên mọi ảnh mẫu), nên đọc cả hai để tự kiểm tra.
const val SECOND_NUMBER_DX = 100
const val COMPASS_THRESHOLD = 0.80
const val BUTTON_THRESHOLD = 0.80

data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0

    fun contains(x: Int, y: Int) = x in x0 until x1 && y in y0 until y1

    fun intersects(o: Box) = x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1

    /** Phần nằm trong ảnh rộng w, cao h (như cắt mảng numpy). */
    fun clip(w: Int, h: Int) = Box(x0.coerceIn(0, w), y0.coerceIn(0, h), max(x0, x1).coerceIn(0, w), max(y0, y1).coerceIn(0, h))
}

/** Vị trí khớp mẫu (góc trên-trái hoặc tâm, tuỳ hàm) và điểm khớp. */
data class Match(val x: Int, val y: Int, val score: Double)

/** Độ cuộn đo được (px màn hình, dương = lưới trôi lên) và điểm khớp. */
data class Shift(val px: Int, val score: Double)

/** Làm tròn như round() của Python (nửa về số chẵn) để kết quả trùng với bản Python. */
internal fun pyRound(v: Double) = Math.rint(v).toInt()

/** Chạy block trên vùng box của ảnh (không sao chép) rồi giải phóng vùng đó. */
inline fun <R> Mat.region(b: Box, block: (Mat) -> R): R {
    val roi = submat(b.y0, b.y1, b.x0, b.x1)
    try {
        return block(roi)
    } finally {
        roi.release()
    }
}

fun decodeImage(bytes: ByteArray): Mat {
    val buf = MatOfByte(*bytes)
    val img = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
    buf.release()
    require(!img.empty()) { "không giải mã được ảnh" }
    return img
}

/** Mẫu chữ số: chữ số và mask nhị phân DIGIT_SIZE x DIGIT_SIZE (true = nét chữ). */
class DigitTemplate(val digit: Int, val mask: BooleanArray)

/** Mẫu chữ số trong huy hiệu số thứ tự ô: chữ số, số lỗ của nét (0/4/6/9 có 1, 8 có 2), mask chuẩn hoá. */
class BadgeDigit(val digit: Int, val holes: Int, val mask: BooleanArray)

class Templates(
    val tab: Mat, val compass: Mat, val badge1: Mat,
    val btnDelete: Mat, val btnConfirm: Mat, val btnCancel: Mat,
    val digits: List<DigitTemplate>,
    val badgeBlank: Mat, val badgeDigits: List<BadgeDigit>,
) {
    companion object {
        /** read(đường dẫn) trả về nội dung một file trong thư mục templates/, list(thư mục con) liệt kê tên file. */
        fun load(read: (String) -> ByteArray, list: (String) -> List<String>): Templates {
            val digits = list("digits").filter { it.endsWith(".png") }.sorted().map { name ->
                val buf = MatOfByte(*read("digits/$name"))
                val m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE)
                buf.release()
                require(m.rows() == DIGIT_SIZE && m.cols() == DIGIT_SIZE) { "mẫu chữ số $name phải là ${DIGIT_SIZE}x$DIGIT_SIZE" }
                val px = ByteArray(DIGIT_SIZE * DIGIT_SIZE)
                m.get(0, 0, px)
                m.release()
                DigitTemplate(name[0].digitToInt(), BooleanArray(px.size) { (px[it].toInt() and 0xFF) > 127 })
            }
            require(digits.isNotEmpty()) { "thiếu mẫu chữ số trong templates/digits" }
            // Tên file <chữ số>_<số lỗ>_<thứ tự>.png (tạo bằng tao_mau_huy_hieu.py).
            val badgeDigits = list("badge_digits").filter { it.endsWith(".png") }.sorted().map { name ->
                val (d, h) = name.removeSuffix(".png").split("_")
                val buf = MatOfByte(*read("badge_digits/$name"))
                val m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE)
                buf.release()
                val px = ByteArray(DIGIT_SIZE * DIGIT_SIZE)
                m.get(0, 0, px)
                m.release()
                BadgeDigit(d.toInt(), h.toInt(), BooleanArray(px.size) { (px[it].toInt() and 0xFF) > 127 })
            }
            return Templates(
                tab = decodeImage(read("tab_nhiem_vu.png")),
                compass = decodeImage(read("compass.png")),
                badge1 = decodeImage(read("badge_1.png")),
                btnDelete = decodeImage(read("btn_xoa.png")),
                btnConfirm = decodeImage(read("btn_xac_nhan.png")),
                btnCancel = decodeImage(read("btn_huy.png")),
                digits = digits,
                badgeBlank = decodeImage(read("badge_blank.png")),
                badgeDigits = badgeDigits,
            )
        }
    }
}

/** Mẫu ảnh và toạ độ đã quy đổi từ máy gốc sang màn hình đang dùng: x' = s*x + dx, y' = s*y + dy. */
class Ui(base: Templates, val s: Double, val dx: Double = 0.0, val dy: Double = 0.0) {
    val tab = scaled(base.tab)
    val compass = scaled(base.compass)
    val badge1 = scaled(base.badge1)
    val btnDelete = scaled(base.btnDelete)
    val btnConfirm = scaled(base.btnConfirm)
    val btnCancel = scaled(base.btnCancel)
    val digits = base.digits                    // chữ số đã chuẩn hoá kích thước, không cần co giãn
    val badgeBlank = scaled(base.badgeBlank)
    val badgeDigits = base.badgeDigits
    val grid = box(GRID)
    val panelPoints = box(PANEL_POINTS)
    val deleteRegion = box(DELETE_BTN_REGION)
    val acceptZone = box(ACCEPT_BTN_ZONE)
    val dialogRegion = box(DIALOG_REGION)
    val dragGaps = DRAG_GAPS.map { x(it) }

    private fun scaled(im: Mat): Mat {
        if (s == 1.0) return im
        val out = Mat()
        Imgproc.resize(im, out, Size(), s, s, if (s < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
        return out
    }

    fun x(v: Int) = pyRound(s * v + dx)
    fun y(v: Int) = pyRound(s * v + dy)

    /** Độ dài (không cộng độ lệch). */
    fun d(v: Int) = pyRound(s * v)
    fun box(b: Box) = Box(x(b.x0), y(b.y0), x(b.x1), y(b.y1))

    override fun toString() = "Ui(tỉ lệ %.4f, lệch %+.0f,%+.0f)".format(s, dx, dy)

    companion object {
        /** Tìm tab "Nhiệm Vụ" để suy ra bố cục; null nếu không thấy (không ở màn hình Nhiệm Vụ). */
        fun detect(img: Mat, base: Templates): Ui? {
            val w = img.cols()
            val h = img.rows()
            val probe = Ui(base, w.toDouble() / REF_WIDTH)
            val m = matchBest(img, probe.tab, Box(0, 0, w, h / 2), probe.s)
            if (m.score < BUTTON_THRESHOLD) return null
            val tx = m.x - probe.tab.cols() / 2                // góc trên-trái
            val ty = m.y - probe.tab.rows() / 2
            return Ui(base, probe.s, tx - probe.s * REF_TAB_X, ty - probe.s * REF_TAB_Y)
        }
    }
}

// ---------------------------------------------------------------- Khớp mẫu

private fun shrink(im: Mat, f: Double): Mat {
    val out = Mat()
    Imgproc.resize(im, out, Size(), f, f, Imgproc.INTER_AREA)
    return out
}

private fun matchMap(img: Mat, tpl: Mat): Mat {
    val res = Mat()
    Imgproc.matchTemplate(img, tpl, res, Imgproc.TM_CCOEFF_NORMED)
    return res
}

private fun Mat.floats(): FloatArray {
    val out = FloatArray(rows() * cols())
    get(0, 0, out)
    return out
}

private fun fits(tpl: Mat, w: Int, h: Int) = tpl.cols() <= w && tpl.rows() <= h

/** Khớp lại mẫu ở độ phân giải gốc quanh (x, y) ± pad, trong giới hạn bounds. Trả về góc trên-trái. */
private fun refine(img: Mat, tpl: Mat, x: Int, y: Int, pad: Int, bounds: Box): Match {
    val b = Box(max(x - pad, bounds.x0), max(y - pad, bounds.y0),
        min(x + tpl.cols() + pad, bounds.x1), min(y + tpl.rows() + pad, bounds.y1))
    if (!fits(tpl, b.width, b.height)) return Match(x, y, -1.0)
    val res = img.region(b) { matchMap(it, tpl) }
    val mm = Core.minMaxLoc(res)
    res.release()
    return Match(b.x0 + mm.maxLoc.x.toInt(), b.y0 + mm.maxLoc.y.toInt(), mm.maxVal)
}

/** Mọi vị trí khớp mẫu trong box (đã nằm trong ảnh). Trả về góc trên-trái, sắp theo hàng rồi cột. */
private fun matchAll(img: Mat, tpl: Mat, box: Box, threshold: Double, minDist: Int): List<Match> {
    if (!fits(tpl, box.width, box.height)) return emptyList()
    val res = img.region(box) { matchMap(it, tpl) }
    val w = res.cols()
    val vals = res.floats()
    res.release()
    val found = mutableListOf<Match>()
    for (i in vals.indices.filter { vals[it] >= threshold }.sortedByDescending { vals[it] }) {
        val x = i % w
        val y = i / w
        if (found.all { abs(y - it.y) > minDist || abs(x - it.x) > minDist }) found += Match(x, y, vals[i].toDouble())
    }
    return found.map { Match(it.x + box.x0, it.y + box.y0, it.score) }.sortedWith(compareBy({ it.y }, { it.x }))
}

/** Mọi vị trí khớp mẫu tpl (đã ở tỉ lệ màn hình s) trong box, góc trên-trái.
 *  Dò trên ảnh thu nhỏ về MATCH_SCALE (theo máy gốc) rồi tinh chỉnh từng vị trí ở độ phân giải gốc. */
fun findTemplate(img: Mat, tpl: Mat, box: Box, s: Double, threshold: Double, minDist: Int): List<Match> {
    val b = box.clip(img.cols(), img.rows())
    val f = MATCH_SCALE / s
    if (f >= 1) return matchAll(img, tpl, b, threshold, minDist)
    val small = img.region(b) { shrink(it, f) }
    val smallTpl = shrink(tpl, f)
    val coarse = matchAll(small, smallTpl, Box(0, 0, small.cols(), small.rows()),
        threshold - 0.15, max(1, pyRound(minDist * f)))
    small.release()
    smallTpl.release()
    val found = mutableListOf<Match>()
    for (c in coarse) {
        val m = refine(img, tpl, b.x0 + pyRound(c.x / f), b.y0 + pyRound(c.y / f), pyRound(2 / f) + 2, b)
        if (m.score >= threshold && found.all { abs(m.x - it.x) > minDist || abs(m.y - it.y) > minDist }) found += m
    }
    return found.sortedWith(compareBy({ it.y }, { it.x }))
}

/** Vị trí khớp tốt nhất của mẫu trong box: tâm (x, y) và điểm khớp. */
fun matchBest(img: Mat, tpl: Mat, box: Box, s: Double = 1.0): Match {
    val b = box.clip(img.cols(), img.rows())
    if (!fits(tpl, b.width, b.height)) return Match(b.x0, b.y0, -1.0)
    val f = MATCH_SCALE / s
    val m = if (f < 1) {
        val small = img.region(b) { shrink(it, f) }
        val smallTpl = shrink(tpl, f)
        val coarse = if (fits(smallTpl, small.cols(), small.rows())) {
            val res = matchMap(small, smallTpl)
            Core.minMaxLoc(res).also { res.release() }.maxLoc
        } else null
        small.release()
        smallTpl.release()
        if (coarse == null) Match(b.x0, b.y0, -1.0)
        else refine(img, tpl, b.x0 + pyRound(coarse.x / f), b.y0 + pyRound(coarse.y / f), pyRound(2 / f) + 2, b)
    } else {
        val res = img.region(b) { matchMap(it, tpl) }
        val mm = Core.minMaxLoc(res)
        res.release()
        Match(b.x0 + mm.maxLoc.x.toInt(), b.y0 + mm.maxLoc.y.toInt(), mm.maxVal)
    }
    return Match(m.x + tpl.cols() / 2, m.y + tpl.rows() / 2, m.score)
}

// ---------------------------------------------------------------- Nhận diện

/** Ô nhiệm vụ còn trống (có la bàn). x, y: góc trên-trái icon la bàn; chạm vào giữa icon luôn trúng ô
 *  và luôn nằm trong khung nhìn. reading: số điểm, đã đối chiếu số cạnh la bàn với số cạnh kim cương. */
data class Task(val x: Int, val y: Int, val reading: Reading, val tapX: Int, val tapY: Int)

fun findCompasses(img: Mat, box: Box, ui: Ui) = findTemplate(img, ui.compass, box, ui.s, COMPASS_THRESHOLD, ui.d(20))

/** Vùng chứa số điểm nằm bên phải icon la bàn ở (x, y); dx: dời sang phải bấy nhiêu px máy gốc. */
fun numberBoxRightOf(x: Int, y: Int, ui: Ui, dx: Int = 0) =
    Box(x + ui.d(50 + dx), y - ui.d(6), x + ui.d(108 + dx), y + ui.d(54))

/** Các ô nhiệm vụ còn trống nằm trọn trong khung nhìn của lưới, kèm số điểm. */
fun findTasks(img: Mat, ui: Ui): List<Task> {
    val th = ui.compass.rows()
    val tw = ui.compass.cols()
    return findCompasses(img, ui.grid, ui)
        .filter { it.y - ui.d(6) >= ui.grid.y0 && it.y + th + ui.d(6) <= ui.grid.y1 }   // không bị khung nhìn cắt
        .map {
            val reading = crossCheck(readNumber(img, numberBoxRightOf(it.x, it.y, ui), ui),
                readNumber(img, numberBoxRightOf(it.x, it.y, ui, SECOND_NUMBER_DX), ui))
            Task(it.x, it.y, reading, it.x + tw / 2, it.y + th / 2)
        }
}

/** Điểm của nhiệm vụ đang chọn trong khung chi tiết. */
fun readPanelPoints(img: Mat, ui: Ui): Reading {
    val hit = findCompasses(img, ui.panelPoints, ui).minByOrNull { it.x }
        ?: return Reading(null, null, null, "không thấy la bàn trong khung chi tiết")
    return readNumber(img, numberBoxRightOf(hit.x, hit.y, ui), ui)
}

/** Tâm nút "Xóa nhiệm vụ" trong khung chi tiết, null nếu không có. */
fun findDeleteButton(img: Mat, ui: Ui): Pair<Int, Int>? =
    matchBest(img, ui.btnDelete, ui.deleteRegion, ui.s).takeIf { it.score >= BUTTON_THRESHOLD }?.let { it.x to it.y }

/** Tâm nút Xác nhận/Hủy (tpl) của hộp thoại xóa, null nếu hộp thoại không hiện. */
fun findDialogButton(img: Mat, tpl: Mat, ui: Ui): Pair<Int, Int>? =
    matchBest(img, tpl, ui.dialogRegion, ui.s).takeIf { it.score >= BUTTON_THRESHOLD }?.let { it.x to it.y }

/** Điểm ảnh BGR liên tiếp của vùng box (đã cắt theo ảnh), cùng chiều rộng vùng; null nếu vùng rỗng. */
private fun pixels(img: Mat, box: Box): Pair<ByteArray, Int>? {
    val b = box.clip(img.cols(), img.rows())
    if (b.width <= 0 || b.height <= 0) return null
    val copy = img.region(b) { it.clone() }
    val out = ByteArray(b.width * b.height * 3)
    copy.get(0, 0, out)
    copy.release()
    return out to b.width
}

/** Ô có viền xanh lá (đang được chọn) không: xét viền trái và viền đáy quanh icon la bàn. */
fun isSelected(img: Mat, task: Task, ui: Ui): Boolean {
    val x = task.x
    val y = task.y
    val regions = listOf(
        Box(x - ui.d(55), y - ui.d(40), x - ui.d(20), y + ui.d(40)),
        Box(x, y + ui.d(56), x + ui.d(60), y + ui.d(76)),
    )
    return regions.any { region ->
        val (px, _) = pixels(img, region) ?: return@any false
        var n = 0
        for (i in px.indices step 3) {
            val b = px[i].toInt() and 0xFF
            val g = px[i + 1].toInt() and 0xFF
            val r = px[i + 2].toInt() and 0xFF
            if (g > 190 && r < 200 && b < 130 && g - r > 40) n++
        }
        n.toDouble() / (px.size / 3) > 0.1
    }
}

/** Lưới đã cuộn lên bao nhiêu px giữa 2 ảnh: tìm dải dưới cùng (cao band px máy gốc) của ảnh trước trong ảnh sau,
 *  chỉ xét độ cuộn trong [lo, hi] nếu có. Điểm khớp thấp nghĩa là dải đó đã trôi khỏi màn hình (cuộn quá đà).
 *  Đo được tới khoảng (cao lưới - band) px máy gốc. Làm trên ảnh màu thu nhỏ về SHIFT_SCALE. */
fun gridShift(before: Mat, after: Mat, ui: Ui, lo: Int? = null, hi: Int? = null, band: Int = SHIFT_BAND): Shift {
    val f = SHIFT_SCALE / ui.s
    val b = before.region(ui.grid) { shrink(it, f) }
    val a = after.region(ui.grid) { shrink(it, f) }
    val bandY0 = b.rows() - pyRound(band * SHIFT_SCALE)
    val bandMat = b.submat(bandY0, b.rows() - pyRound(20 * SHIFT_SCALE), 0, b.cols())
    val res = matchMap(a, bandMat)
    val vals = res.floats()
    for (m in listOf(res, bandMat, a, b)) m.release()
    var best = Shift(0, -1.0)
    for (i in vals.indices) {
        val shift = Math.rint((bandY0 - i) / f).toInt()
        if ((lo == null || shift >= lo) && (hi == null || shift <= hi) && vals[i] > best.score) best = Shift(shift, vals[i].toDouble())
    }
    return best
}

/** Lưới đứng yên giữa 2 ảnh (chỉ đồng hồ nhảy số). */
fun gridUnchanged(a: Mat, b: Mat, ui: Ui): Boolean {
    val diff = Mat()
    a.region(ui.grid) { ra -> b.region(ui.grid) { rb -> Core.absdiff(ra, rb, diff) } }
    val m = Core.mean(diff).`val`
    diff.release()
    return (m[0] + m[1] + m[2]) / 3 < 3.0
}

/** Danh sách đang ở trên cùng và đứng yên: huy hiệu "1" nằm đúng vị trí (khớp ~0.98, vị trí khác <= 0.62). */
fun atTop(img: Mat, ui: Ui): Boolean {
    val th = ui.badge1.rows()
    val tw = ui.badge1.cols()
    val x = ui.x(TOP_BADGE_X)
    val y = ui.y(TOP_BADGE_Y)
    val m = ui.d(12)
    return matchBest(img, ui.badge1, Box(x - m, y - m, x + tw + m, y + th + m), ui.s).score >= 0.85
}

/** Tâm biểu tượng ổ khóa (xanh dương sáng) của Game Booster gần giữa màn hình, null nếu chưa hiện.
 *  Chỉ dùng khi lớp khóa đang hiện: lúc đó game phía sau bị làm tối nên không còn điểm xanh sáng nào khác. */
fun findLockIcon(img: Mat): Pair<Int, Int>? {
    val w = img.cols()
    val h = img.rows()
    val box = Box((w * 0.25).toInt(), (h * 0.3).toInt(), (w * 0.75).toInt(), (h * 0.7).toInt())
    val (px, bw) = pixels(img, box) ?: return null
    var n = 0
    var sx = 0L
    var sy = 0L
    for (k in 0 until px.size / 3) {
        val b = px[3 * k].toInt() and 0xFF
        val g = px[3 * k + 1].toInt() and 0xFF
        val r = px[3 * k + 2].toInt() and 0xFF
        if (b > 180 && r < 90 && b - g > 40) {
            n++
            sx += k % bw
            sy += k / bw
        }
    }
    if (n < (w * h) / 20000) return null       // ~200 điểm ảnh trên màn 1600x2560 (ổ khóa có ~1500)
    return (sx / n).toInt() + box.x0 to (sy / n).toInt() + box.y0
}

private fun grayMean(img: Mat): Double {
    val gray = Mat()
    Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
    val v = Core.mean(gray).`val`[0]
    gray.release()
    return v
}

/** Độ sáng vùng tab "Nhiệm Vụ" so với mẫu: ~1 khi bình thường, ~0.4 khi có hộp thoại của game,
 *  ~0.2-0.3 khi Game Booster khóa màn hình (lớp khóa làm tối mọi thứ phía sau). */
fun tabBrightness(img: Mat, ui: Ui): Double {
    val x = ui.x(REF_TAB_X)
    val y = ui.y(REF_TAB_Y)
    val b = Box(x, y, x + ui.tab.cols(), y + ui.tab.rows()).clip(img.cols(), img.rows())
    if (b.width <= 0 || b.height <= 0) return 0.0
    return img.region(b) { grayMean(it) } / grayMean(ui.tab)
}
