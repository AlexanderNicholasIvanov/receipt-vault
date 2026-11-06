package com.alexivanov.receiptvault.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.ExpenseReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PdfReportWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun receipt(id: Long, photo: File? = null) = Receipt(
        id = id, merchant = "A merchant with a rather long name that will not fit the column $id", category = "DINING",
        purchasedAt = 1_800_000_000_000 + id * 86_400_000, totalCents = 1000 + id, currency = "USD",
        imagePath = photo?.absolutePath, createdAt = 0, updatedAt = 0, tags = "work",
    )

    @Test
    fun `long reports flow onto more pages and photos get a page each`() {
        val photo = File(context.cacheDir, "photo.jpg").apply {
            outputStream().use { Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        val receipts = (1L..120L).map { receipt(it, photo.takeIf { _ -> it <= 2 }) }
        val report = ExpenseReport.build(receipts, LocalDate.parse("2026-11-06"))

        val sink = RecordingSink()
        val pages = PdfReportWriter(includePhotos = true).render(report, sink)

        // ~45 rows fit a page, so 120 receipts need several table pages, plus two photo pages.
        assertTrue("pages=$pages", pages in 4..6)
        assertEquals(pages, sink.started)
        assertEquals("every started page is finished", sink.started, sink.finished)
        assertEquals(listOf(595 to 842), sink.sizes.distinct())
        assertEquals(pages - 2, PdfReportWriter(includePhotos = false).render(report, RecordingSink()))
    }

    @Test
    fun `an empty report is still one page`() {
        val sink = RecordingSink()
        assertEquals(1, PdfReportWriter().render(ExpenseReport.build(emptyList(), LocalDate.parse("2026-11-06")), sink))
        assertEquals(1, sink.finished)
    }

    /** Stands in for PdfDocument, which has no Robolectric implementation. */
    private class RecordingSink : PdfReportWriter.PageSink {
        var started = 0
        var finished = 0
        val sizes = mutableListOf<Pair<Int, Int>>()
        private var open = false

        override fun start(number: Int, width: Int, height: Int): Canvas {
            check(!open) { "page $number started before the previous one finished" }
            check(number == started + 1)
            open = true
            started++
            sizes += width to height
            return Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }

        override fun finish() {
            check(open)
            open = false
            finished++
        }
    }
}
