package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ExpenseReportTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-11-06")

    private fun r(id: Long, date: String, cents: Long, currency: String = "USD", category: Category = Category.DINING, tax: Long? = null, tip: Long? = null) =
        Receipt(
            id = id, merchant = "M$id", category = category.name, totalCents = cents, currency = currency, taxCents = tax, tipCents = tip,
            purchasedAt = LocalDate.parse(date).atTime(9, 0).toInstant(zone).toEpochMilli(), createdAt = 0, updatedAt = 0, tags = "work",
        )

    @Test
    fun `lines are chronological and the range spans them`() {
        val report = ExpenseReport.build(listOf(r(1, "2026-11-03", 100), r(2, "2026-10-01", 200), r(3, "2026-11-05", 300)), today, zone)
        assertEquals(listOf(2L, 1L, 3L), report.lines.map { it.receiptId })
        assertEquals(LocalDate.parse("2026-10-01"), report.from)
        assertEquals(LocalDate.parse("2026-11-05"), report.to)
        assertEquals(listOf("work"), report.lines.first().tags)
    }

    @Test
    fun `totals are kept per currency, never mixed`() {
        val report = ExpenseReport.build(
            listOf(
                r(1, "2026-11-01", 2560, tax = 160, tip = 400),
                r(2, "2026-11-02", 4000, category = Category.FUEL, tax = 300),
                r(3, "2026-11-03", 1000, currency = "EUR"),
                r(4, "2026-11-04", 1440, tip = 100),
            ),
            today, zone,
        )
        val usd = report.totals[0]
        assertEquals("USD", usd.currency)
        assertEquals(3, usd.count)
        assertEquals(8000L, usd.totalCents)
        assertEquals(460L, usd.taxCents)
        assertEquals(500L, usd.tipCents)
        assertEquals(listOf(Category.FUEL to 4000L, Category.DINING to 4000L).toSet(), usd.byCategory.toSet())

        val eur = report.totals[1]
        assertEquals("EUR", eur.currency)
        assertEquals(1000L, eur.totalCents)
    }

    @Test
    fun `an empty report has no range`() {
        val report = ExpenseReport.build(emptyList(), today, zone)
        assertNull(report.from)
        assertEquals(0, report.count)
        assertEquals("expense-report-2026-11-06.pdf", ExpenseReport.fileName(today))
    }
}
