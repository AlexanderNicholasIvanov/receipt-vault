package com.alexivanov.receiptvault.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.alexivanov.receiptvault.domain.ExpenseReport
import com.alexivanov.receiptvault.domain.Money
import java.io.File
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Draws an [ExpenseReport] as an A4 PDF: a summary per currency, a line per
 * receipt and, optionally, one page per receipt photo for whoever has to
 * approve the expenses.
 */
class PdfReportWriter(private val includePhotos: Boolean = true) {

    private val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    private val title = paint(18f, bold = true)
    private val heading = paint(12f, bold = true)
    private val body = paint(9.5f)
    private val bodyBold = paint(9.5f, bold = true)
    private val muted = paint(8.5f).apply { color = Color.rgb(96, 96, 96) }
    private val rule = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.6f }

    /** Where pages go: a real PDF in the app, plain canvases in tests. */
    internal interface PageSink {
        fun start(number: Int, width: Int, height: Int): Canvas
        fun finish()
    }

    /** Writes the report as a PDF and returns the number of pages. */
    fun write(report: ExpenseReport, out: OutputStream): Int {
        val doc = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            val pages = render(
                report,
                object : PageSink {
                    override fun start(number: Int, width: Int, height: Int): Canvas =
                        doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create()).also { page = it }.canvas

                    override fun finish() {
                        page?.let(doc::finishPage)
                        page = null
                    }
                },
            )
            doc.writeTo(out)
            return pages
        } finally {
            doc.close()
        }
    }

    /** Lays the report out onto [sink]; returns the number of pages. */
    internal fun render(report: ExpenseReport, sink: PageSink): Int {
        val pager = Pager(sink)
        summary(report, pager)
        table(report, pager)
        pager.finish()
        if (includePhotos) photos(report, pager)
        pager.finish()
        return pager.pages
    }

    // ------------------------------------------------------------------ sections

    private fun summary(report: ExpenseReport, p: Pager) {
        p.text("Expense report", title, MARGIN, gap = 26f)
        val range = when {
            report.from == null || report.to == null -> "No receipts"
            report.from == report.to -> report.from.format(dates)
            else -> "${report.from.format(dates)} – ${report.to.format(dates)}"
        }
        p.text("$range · ${report.count} receipt${if (report.count == 1) "" else "s"}", body, MARGIN, gap = 14f)
        p.text("Generated ${report.generatedOn.format(dates)} with Receipt Vault", muted, MARGIN, gap = 22f)

        for (t in report.totals) {
            p.ensure(40f + t.byCategory.size * 14f)
            p.text("Total ${t.currency}: ${Money.format(t.totalCents, t.currency)}", heading, MARGIN, gap = 16f)
            p.text(
                "${t.count} receipt${if (t.count == 1) "" else "s"} · tax ${Money.format(t.taxCents, t.currency)} · tips ${Money.format(t.tipCents, t.currency)}",
                body, MARGIN, gap = 14f,
            )
            for ((category, cents) in t.byCategory) {
                p.row(
                    listOf(Cell(category.label, MARGIN + 12f, 200f, body), Cell(Money.format(cents, t.currency), MARGIN + 220f, 110f, body, right = true)),
                    gap = 13f,
                )
            }
            p.space(10f)
        }
        p.space(6f)
    }

    private fun table(report: ExpenseReport, p: Pager) {
        if (report.lines.isEmpty()) return
        fun header() {
            p.row(COLUMNS.map { (label, x, w, right) -> Cell(label, x, w, bodyBold, right) }, gap = 6f)
            p.line()
            p.space(12f)
        }
        p.ensure(60f)
        header()
        for (l in report.lines) {
            if (p.ensure(16f)) header()
            val cells = listOf(
                l.date.format(dates),
                l.merchant + if (l.tags.isNotEmpty()) "  " + l.tags.joinToString(" ") { "#$it" } else "",
                l.category.label,
                l.taxCents?.let { Money.format(it, l.currency) } ?: "",
                l.tipCents?.let { Money.format(it, l.currency) } ?: "",
                Money.format(l.totalCents, l.currency),
            )
            p.row(cells.zip(COLUMNS) { text, (_, x, w, right) -> Cell(text, x, w, body, right) }, gap = 15f)
        }
    }

    private fun photos(report: ExpenseReport, p: Pager) {
        for (l in report.lines) {
            val path = l.imagePath ?: continue
            val bitmap = decode(File(path)) ?: continue
            try {
                p.newPage()
                p.text("${l.date.format(dates)} · ${l.merchant} · ${Money.format(l.totalCents, l.currency)}", heading, MARGIN, gap = 18f)
                val top = p.y
                val maxW = PAGE_W - 2 * MARGIN
                val maxH = PAGE_H - MARGIN - top
                val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val left = MARGIN + (maxW - w) / 2
                p.canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                bitmap.recycle()
            }
            p.finish()
        }
    }

    /** Big enough to read a receipt on a printed page, small enough to keep the PDF light. */
    private fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_EDGE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ------------------------------------------------------------------ layout

    private data class Cell(val text: String, val x: Float, val width: Float, val paint: Paint, val right: Boolean = false)

    /** Hands out A4 pages and tracks the pen's vertical position. */
    private inner class Pager(private val sink: PageSink) {
        private var page: Canvas? = null
        var pages = 0
            private set
        var y = MARGIN
            private set
        val canvas: Canvas get() = page ?: newPage()

        fun newPage(): Canvas {
            finish()
            pages++
            return sink.start(pages, PAGE_W.toInt(), PAGE_H.toInt()).also {
                page = it
                y = MARGIN + 10f
                it.drawText("Page $pages", PAGE_W - MARGIN, PAGE_H - MARGIN / 2, muted.right())
            }
        }

        fun finish() {
            if (page != null) sink.finish()
            page = null
        }

        /** Starts a new page when [height] doesn't fit; returns true when it did. */
        fun ensure(height: Float): Boolean {
            if (page != null && y + height <= PAGE_H - MARGIN) return false
            newPage()
            return true
        }

        fun space(h: Float) { y += h }

        fun text(s: String, paint: Paint, x: Float, gap: Float) {
            ensure(gap)
            canvas.drawText(fit(s, paint, PAGE_W - MARGIN - x), x, y, paint)
            y += gap
        }

        fun row(cells: List<Cell>, gap: Float) {
            ensure(gap)
            for (c in cells) {
                val s = fit(c.text, c.paint, c.width)
                if (c.right) canvas.drawText(s, c.x + c.width, y, c.paint.right()) else canvas.drawText(s, c.x, y, c.paint)
            }
            y += gap
        }

        fun line() {
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
        }
    }

    private fun Paint.right(): Paint = Paint(this).apply { textAlign = Paint.Align.RIGHT }

    /** Cuts [s] with an ellipsis so it fits [width] points. */
    private fun fit(s: String, paint: Paint, width: Float): String {
        if (paint.measureText(s) <= width) return s
        val n = paint.breakText(s, true, width - paint.measureText("…"), null)
        return s.take(n.coerceAtLeast(0)).trimEnd() + "…"
    }

    private fun paint(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private companion object {
        // A4 in PostScript points.
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val MARGIN = 40f
        const val PHOTO_EDGE = 1600

        /** label, x, width, right-aligned */
        val COLUMNS = listOf(
            Column("Date", MARGIN, 72f, false),
            Column("Merchant", MARGIN + 76f, 178f, false),
            Column("Category", MARGIN + 258f, 72f, false),
            Column("Tax", MARGIN + 332f, 56f, true),
            Column("Tip", MARGIN + 390f, 56f, true),
            Column("Total", MARGIN + 448f, 67f, true),
        )
    }

    private data class Column(val label: String, val x: Float, val width: Float, val right: Boolean)
}
