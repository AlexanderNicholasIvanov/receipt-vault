package com.alexivanov.receiptvault.domain

/** One line of recognized text with its bounding box in image pixels. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Float get() = (top + bottom) / 2f
}

/** A physical row on the receipt after side-by-side fragments were joined. */
data class ReceiptRow(val text: String, val height: Int)

/**
 * OCR engines often return the label ("TOTAL") and the price ("$12.34") of a
 * receipt row as separate lines because of the gap between them. This joins
 * fragments whose vertical extents mostly overlap into one row, left to right,
 * and orders rows top to bottom.
 */
object LineAssembler {

    fun assemble(lines: List<OcrLine>): List<ReceiptRow> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            if (row != null && row.any { sameRow(it, line) }) row += line else rows += mutableListOf(line)
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.left }
            ReceiptRow(
                text = ordered.joinToString("  ") { it.text.trim() },
                height = ordered.maxOf { it.height },
            )
        }
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0) return false
        return overlap >= 0.5f * minOf(a.height, b.height)
    }
}
