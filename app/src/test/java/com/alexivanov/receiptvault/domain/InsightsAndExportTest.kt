package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class InsightsAndExportTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 6)

    private fun receipt(
        id: Long, date: LocalDate, total: Long, merchant: String = "Shop", category: Category = Category.OTHER,
        subtotal: Long? = null, tip: Long? = null, tax: Long? = null, currency: String = "USD",
    ) = Receipt(
        id = id, merchant = merchant, category = category.name,
        purchasedAt = date.atTime(12, 0).toInstant(utc).toEpochMilli(),
        subtotalCents = subtotal, taxCents = tax, tipCents = tip, totalCents = total, currency = currency,
        createdAt = 0, updatedAt = 0,
    )

    private val receipts = listOf(
        receipt(1, LocalDate.of(2026, 11, 2), 4052, "Bistro", Category.DINING, subtotal = 3150, tip = 650, tax = 252),
        receipt(2, LocalDate.of(2026, 11, 5), 1318, "Trader Joe's", Category.GROCERIES, subtotal = 1276, tax = 42),
        receipt(3, LocalDate.of(2026, 10, 20), 3669, "Shell", Category.FUEL),
        receipt(4, LocalDate.of(2026, 11, 3), 830, "Café", Category.DINING, currency = "EUR"),
        receipt(5, LocalDate.of(2025, 12, 31), 1000, "Bistro", Category.DINING, subtotal = 800, tip = 200),
    )

    @Test
    fun `period filters by local purchase date`() {
        assertEquals(setOf(1L, 2L, 4L), Insights.inPeriod(receipts, Period.THIS_MONTH, today, utc).map { it.id }.toSet())
        assertEquals(listOf(3L), Insights.inPeriod(receipts, Period.LAST_MONTH, today, utc).map { it.id })
        assertEquals(4, Insights.inPeriod(receipts, Period.THIS_YEAR, today, utc).size)
        assertEquals(5, Insights.inPeriod(receipts, Period.ALL, today, utc).size)
    }

    @Test
    fun `summary sums one currency and reports the rest`() {
        val s = Insights.summarize(Insights.inPeriod(receipts, Period.THIS_MONTH, today, utc), "USD")
        assertEquals("USD", s.currency)
        assertEquals(2, s.count)
        assertEquals(5370L, s.totalCents)
        assertEquals(650L, s.tipCents)
        assertEquals(294L, s.taxCents)
        assertEquals(1, s.otherCurrencyCount)
        assertEquals(650.0 * 100 / 3150, s.averageTipPercent!!, 0.001)
        assertEquals(Category.DINING, s.byCategory.first().first)
    }

    @Test
    fun `monthly buckets cover the last six months including empty ones`() {
        val months = Insights.monthly(receipts, "USD", today, months = 6, zone = utc)
        assertEquals(6, months.size)
        assertEquals(YearMonth.of(2026, 6), months.first().first)
        assertEquals(YearMonth.of(2026, 11) to 5370L, months.last())
        assertEquals(YearMonth.of(2026, 10) to 3669L, months[4])
        assertEquals(0L, months[0].second)
    }

    @Test
    fun `csv quotes, escapes and folds line items`() {
        val r = receipt(7, LocalDate.of(2026, 11, 1), 1234, merchant = "Joe's, \"Best\" Diner").copy(notes = "=SUM(A1)", tags = "work,Berlin trip", returnBy = LocalDate.of(2026, 11, 30).toEpochDay())
        val csv = CsvExport.build(listOf(r), listOf(LineItem(receiptId = 7, position = 0, name = "Pie", quantity = 2, priceCents = 800)), utc)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("id,date,time,merchant"))
        assertTrue(lines[1].contains("\"Joe's, \"\"Best\"\" Diner\""))
        assertTrue(lines[1].contains(",'=SUM(A1),"))
        assertTrue(lines[1].contains("Pie x2 = 8.00"))
        assertTrue(lines[1].contains(",12.34,USD,"))
        assertTrue(lines[0].endsWith(",items,tags,return_by,warranty_until"))
        assertTrue(lines[1].endsWith(",\"work, Berlin trip\",2026-11-30,"))
    }

    @Test
    fun `update checker compares version codes and needs an apk asset`() {
        val newer = """{"tag_name":"v1.0.12","html_url":"https://x","body":"version_code: 12",
            "assets":[{"name":"ReceiptVault.apk","browser_download_url":"https://x/a.apk","size":10}]}"""
        val outcome = UpdateChecker.parse(newer, currentVersionCode = 5)
        assertTrue(outcome is UpdateChecker.Outcome.Newer)
        assertEquals(12, (outcome as UpdateChecker.Outcome.Newer).info.versionCode)
        assertEquals(UpdateChecker.Outcome.UpToDate, UpdateChecker.parse(newer, currentVersionCode = 12))
        assertEquals(UpdateChecker.Outcome.UpToDate, UpdateChecker.parse("""{"tag_name":"v1.0.20","assets":[]}""", 5))
        assertEquals(UpdateChecker.Outcome.Failed, UpdateChecker.parse("not json", 5))
    }

    @Test
    fun `cells starting with tab or carriage return can't become formulas`() {
        assertEquals("'\t=1+2", CsvExport.escape("\t=1+2"))
        assertEquals("\"'\r=1+2\"", CsvExport.escape("\r=1+2"))
        assertEquals("-12.50", CsvExport.escape("-12.50"))
    }
}
