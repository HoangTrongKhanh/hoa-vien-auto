package vn.hoavien.auto.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min

// Huy hiệu số thứ tự ô (1, 2, 3...) ở góc trên-trái mỗi ô: vòng tròn cam, chữ số ruột kem viền nâu.
// Chuyển từ find_badges/read_badge trong xoa_nhiem_vu.py.
val BADGE_COLS = intArrayOf(150, 438, 726)  // x góc trên-trái huy hiệu ở 3 cột, px máy gốc
const val BADGE_TO_COMPASS_X = 5            // la bàn của ô nằm lệch so với huy hiệu bấy nhiêu px máy gốc
const val BADGE_TO_COMPASS_Y = 166
const val BADGE_THRESHOLD = 0.5             // khớp viền tròn (khớp đúng >= 0.6)
const val BADGE_DIGIT_THRESHOLD = 0.6       // chữ số trong huy hiệu (đúng thường >= 0.75)
const val BADGES_PER_ROW = 3

/** Huy hiệu tìm thấy: góc trên-trái và số thứ tự (null nếu không đọc chắc được). */
data class Badge(val x: Int, val y: Int, val number: Int?)

private fun circleMask(h: Int, w: Int, r: Double): Mat {
    val m = Mat.zeros(h, w, CvType.CV_8U)
    Imgproc.circle(m, Point((w / 2).toDouble(), (h / 2).toDouble()), pyRound(min(w, h) * r), Scalar(1.0), -1)
    return m
}

/** Mask 0/1 (độ phân giải gốc, w*h) của một chữ số. */
private class Comp(val x: Int, val w: Int, val h: Int, val px: ByteArray)

/** Từng chữ số trong ảnh huy hiệu c, trái sang phải. Tách theo phần ruột kem: viền nâu của 2 chữ số hay dính nhau
 *  nhưng ruột thì luôn rời. Ruột = phần "trắng" nhất (kênh nhỏ nhất cao) trong vòng tròn; ngưỡng ở giữa nền tròn
 *  (trung vị) và mức sáng nhất, vì màu ruột khác nhau giữa các máy. */
private fun badgeComponents(c: Mat): List<Comp> {
    val h = c.rows()
    val w = c.cols()
    val ch = ArrayList<Mat>(3)
    Core.split(c, ch)
    val mn = Mat()
    Core.min(ch[0], ch[1], mn)
    Core.min(mn, ch[2], mn)
    ch.forEach { it.release() }
    val mnPx = ByteArray(h * w)
    mn.get(0, 0, mnPx)
    mn.release()
    val circ = circleMask(h, w, 0.42)
    val circPx = ByteArray(h * w)
    circ.get(0, 0, circPx)
    circ.release()
    val vals = FloatArray(circPx.count { it.toInt() != 0 })
    var k = 0
    for (i in mnPx.indices) if (circPx[i].toInt() != 0) vals[k++] = (mnPx[i].toInt() and 0xFF).toFloat()
    vals.sort()
    val bg = percentile(vals, 50.0)
    val hi = percentile(vals, 97.0)
    if (hi - bg < 30) return emptyList()
    val thr = (bg + hi) / 2
    val fillPx = ByteArray(h * w) { i -> if (circPx[i].toInt() != 0 && (mnPx[i].toInt() and 0xFF) > thr) 1 else 0 }
    val fill = Mat(h, w, CvType.CV_8U)
    fill.put(0, 0, fillPx)
    val labels = Mat()
    val stats = Mat()
    val centroids = Mat()
    val n = Imgproc.connectedComponentsWithStats(fill, labels, stats, centroids, 8)
    val lab = IntArray(h * w)
    labels.get(0, 0, lab)
    val out = mutableListOf<Comp>()
    val row = IntArray(5)
    for (i in 1 until n) {
        stats.get(i, 0, row)
        val (x, y, bw, bh, a) = row
        if (bh >= h * 0.35 && a >= 12 * (h / 44.0) * (h / 44.0)) {
            val px = ByteArray(bw * bh) { j -> if (lab[(y + j / bw) * w + x + j % bw] == i) 1 else 0 }
            out += Comp(x, bw, bh, px)
        }
    }
    for (m in listOf(fill, labels, stats, centroids)) m.release()
    return out.sortedBy { it.x }
}

/** Số lỗ của mask (vùng nền không chạm biên). */
private fun holes(c: Comp): Int {
    val pw = c.w + 2
    val ph = c.h + 2
    val inv = ByteArray(pw * ph) { j ->
        val x = j % pw - 1
        val y = j / pw - 1
        if (x < 0 || y < 0 || x >= c.w || y >= c.h) 1 else (1 - c.px[y * c.w + x]).toByte()
    }
    val m = Mat(ph, pw, CvType.CV_8U)
    m.put(0, 0, inv)
    val labels = Mat()
    val n = Imgproc.connectedComponents(m, labels, 4)
    m.release()
    labels.release()
    return n - 2
}

private fun normalizeComp(c: Comp): BooleanArray {
    val ink = Mat(c.h, c.w, CvType.CV_32F)
    ink.put(0, 0, FloatArray(c.px.size) { c.px[it].toFloat() })
    val v = normalizeDigit(ink, intArrayOf(0, 0, c.w, c.h))
    ink.release()
    return BooleanArray(v.size) { v[it] > 0.5f }
}

/** Số thứ tự trong ảnh huy hiệu c, null nếu không đọc chắc được. */
fun readBadge(c: Mat, ui: Ui): Int? {
    val comps = badgeComponents(c)
    if (comps.isEmpty() || comps.size > 2) return null
    var s = ""
    for (comp in comps) {
        val mk = normalizeComp(comp)
        val h = holes(comp)
        var best: Pair<Double, Int>? = null          // (điểm, chữ số); bằng điểm thì lấy chữ số lớn hơn như max() của Python
        for (t in ui.badgeDigits) {
            if (t.holes != h) continue
            var diff = 0
            for (i in mk.indices) if (mk[i] != t.mask[i]) diff++
            val score = 1.0 - diff.toDouble() / mk.size * 2.5
            if (best == null || score > best.first || (score == best.first && t.digit > best.second)) best = score to t.digit
        }
        if (best == null || best.first < BADGE_DIGIT_THRESHOLD) return null
        s += best.second
    }
    return s.toInt()
}

/** Huy hiệu nằm trọn trong lưới, theo hàng rồi cột. Chỉ khớp phần viền tròn (che vùng chữ số ở giữa) nên không
 *  phụ thuộc số. */
fun findBadges(img: Mat, ui: Ui): List<Badge> {
    val th = ui.badgeBlank.rows()
    val tw = ui.badgeBlank.cols()
    val ring1 = Mat()
    circleMask(th, tw, 0.36).let { Core.subtract(Mat.ones(th, tw, CvType.CV_8U), it, ring1); it.release() }
    val ring = Mat()
    Core.merge(listOf(ring1, ring1, ring1), ring)
    ring1.release()
    val g = ui.grid
    val found = mutableListOf<Badge>()
    for (cx in BADGE_COLS) {
        val b = Box(ui.x(cx) - ui.d(10), g.y0, ui.x(cx) + tw + ui.d(10), g.y1).clip(img.cols(), img.rows())
        if (b.width < tw || b.height < th) continue
        val res = Mat()
        img.region(b) { Imgproc.matchTemplate(it, ui.badgeBlank, res, Imgproc.TM_CCOEFF_NORMED, ring) }
        val rw = res.cols()
        val rh = res.rows()
        val vals = FloatArray(rh * rw)
        res.get(0, 0, vals)
        res.release()
        val col = FloatArray(rh)
        val arg = IntArray(rh)
        for (y in col.indices) {
            var best = -1f
            var bx = 0
            for (x in 0 until rw) {
                val v = vals[y * rw + x].let { if (it.isNaN() || it.isInfinite()) -1f else it }
                if (v > best) { best = v; bx = x }
            }
            col[y] = best
            arg[y] = bx
        }
        val keep = mutableListOf<Int>()
        for (y in col.indices.sortedByDescending { col[it] }) {
            if (col[y] < BADGE_THRESHOLD) break
            if (keep.all { abs(y - it) > ui.d(120) }) keep += y
        }
        for (y in keep) {
            val bx = b.x0 + arg[y]
            val by = b.y0 + y
            found += Badge(bx, by, img.region(Box(bx, by, bx + tw, by + th)) { readBadge(it, ui) })
        }
    }
    ring.release()
    val rows = mutableListOf<MutableList<Badge>>()     // gom theo hàng (lệch nhau vài px giữa các cột)
    for (bd in found.sortedBy { it.y }) {
        if (rows.isNotEmpty() && abs(bd.y - rows.last()[0].y) < ui.d(40)) rows.last() += bd else rows += mutableListOf(bd)
    }
    return rows.flatMap { r -> r.sortedBy { it.x } }
}

/** Số thứ tự ô của nhiệm vụ (huy hiệu cùng ô), null nếu huy hiệu không hiện hoặc không đọc được. */
fun taskNumber(task: Task, badges: List<Badge>, ui: Ui): Int? {
    val dx = ui.d(BADGE_TO_COMPASS_X)
    val dy = ui.d(BADGE_TO_COMPASS_Y)
    return badges.firstOrNull { abs(task.x - it.x - dx) <= ui.d(20) && abs(task.y - it.y - dy) <= ui.d(20) }?.number
}
