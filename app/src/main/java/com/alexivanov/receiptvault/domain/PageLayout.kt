package com.alexivanov.receiptvault.domain

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How several scanned pages are stacked into one tall image: every page is
 * scaled to a common width, then the whole stack is shrunk if it would be
 * taller than [maxHeight] (long receipts can run to many pages).
 */
data class PageLayout(val width: Int, val pageHeights: List<Int>) {
    val height: Int get() = pageHeights.sum() + GAP * (pageHeights.size - 1).coerceAtLeast(0)

    /** Top edge of each page. */
    val tops: List<Int> get() = pageHeights.runningFold(0) { y, h -> y + h + GAP }.dropLast(1)

    companion object {
        /** A thin white band between pages, so the joins are visible. */
        const val GAP = 12

        fun of(sizes: List<Pair<Int, Int>>, maxWidth: Int, maxHeight: Int): PageLayout {
            require(sizes.isNotEmpty()) { "No pages." }
            require(sizes.all { (w, h) -> w > 0 && h > 0 }) { "A page has no size." }
            var width = minOf(sizes.maxOf { it.first }, maxWidth)
            var heights = sizes.map { (w, h) -> (h.toDouble() * width / w).roundToInt().coerceAtLeast(1) }
            val total = heights.sum() + GAP * (sizes.size - 1)
            if (total > maxHeight) {
                val scale = (maxHeight - GAP * (sizes.size - 1)).toDouble() / heights.sum()
                // Rounded down so the stack never ends up taller than the limit.
                width = (width * scale).toInt().coerceAtLeast(1)
                heights = heights.map { (it * scale).toInt().coerceAtLeast(1) }
            }
            return PageLayout(width, heights)
        }
    }
}

/** Several page URIs travel through navigation as one argument. */
object PageUris {
    fun encode(uris: List<String>): String = uris.joinToString("\n")
    fun decode(arg: String?): List<String> = arg.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** How much to enlarge a PDF page so text reads well, within what a phone's memory can hold. */
object PdfRaster {
    /** About 100 MB as a bitmap; a page in a crafted PDF can claim to be any size. */
    const val MAX_PIXELS = 25_000_000f

    fun scale(width: Int, height: Int, targetWidth: Int): Float {
        val wanted = (targetWidth.toFloat() / width).coerceIn(1f, 4f)
        return minOf(wanted, sqrt(MAX_PIXELS / (width.toLong() * height)))
    }
}
