package vn.hoavien.auto.vision

import kotlin.test.Test
import kotlin.test.assertEquals

/** Gộp số cạnh la bàn với số cạnh kim cương: chỉ tin khi khớp, không bao giờ chắc chắn hơn từng lần đọc. */
class CrossCheckTest {
    private fun r(value: Int?, low: Int? = value, high: Int? = value) = Reading(value, low, high, "")

    private fun verdict(first: Reading, second: Reading) = decide(crossCheck(first, second), 12)

    @Test
    fun matchingReadingsKeepTheirValue() {
        val got = crossCheck(r(12), r(12))
        assertEquals(listOf(12, 12, 12), listOf(got.value, got.low, got.high))
        assertEquals(Verdict.DELETE, verdict(r(12), r(12)))
        assertEquals(Verdict.KEEP, verdict(r(28), r(28)))
    }

    @Test
    fun rangeIsTheUnionOfBothReadings() {
        // Lần đọc chắc chắn không được "gỡ" sự không chắc của lần đọc kia.
        val got = crossCheck(r(12, 12, 17), r(12))
        assertEquals(listOf(12, 12, 17), listOf(got.value, got.low, got.high))
        assertEquals(Verdict.UNSURE, verdict(r(12, 12, 17), r(12)))
        assertEquals(Verdict.UNSURE, verdict(r(12), r(12, 12, 17)))
    }

    @Test
    fun mismatchOrUnreadableIsUnsure() {
        assertEquals(Verdict.UNSURE, verdict(r(12), r(28)))
        assertEquals(Verdict.UNSURE, verdict(r(9), r(12)))        // cả hai đều thấp nhưng không khớp: vẫn không tin
        assertEquals(Verdict.UNSURE, verdict(r(null), r(12)))
        assertEquals(Verdict.UNSURE, verdict(r(12), r(null)))
    }
}
