package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PriceHistoryTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 16)
    private var nextId = 1L
    private val receipts = mutableListOf<Receipt>()
    private val items = mutableListOf<LineItem>()

    /** One receipt on [date] at [merchant] with the given (name, line price, quantity) lines. */
    private fun buy(date: LocalDate, merchant: String, vararg lines: Triple<String, Long, Int>) {
        val id = nextId++
        receipts += Receipt(
            id = id, merchant = merchant, category = Category.GROCERIES.name, totalCents = lines.sumOf { it.second }, currency = "USD",
            purchasedAt = date.atTime(12, 0).toInstant(utc).toEpochMilli(), createdAt = 0, updatedAt = 0,
        )
        lines.forEachIndexed { i, (name, price, qty) -> items += LineItem(receiptId = id, position = i, name = name, quantity = qty, priceCents = price) }
    }

    private fun line(name: String, cents: Long, qty: Int = 1) = Triple(name, cents, qty)

    @Test
    fun `an item's history is one unit price per receipt, oldest first`() {
        buy(LocalDate.of(2026, 3, 2), "Kroger", line("Milk 1 gal", 349))
        buy(LocalDate.of(2026, 6, 9), "Aldi", line("MILK 1 GAL.", 698, qty = 2))
        buy(LocalDate.of(2026, 11, 1), "Kroger", line("Milk 1 gal", 379), line("Bananas", 129))

        val h = PriceHistory.of("milk 1 gal", receipts, items, "USD", zone = utc)!!

        assertEquals("Milk 1 gal", h.name)
        assertEquals(
            listOf(PricePoint(LocalDate.of(2026, 3, 2), 349, "Kroger"), PricePoint(LocalDate.of(2026, 6, 9), 349, "Aldi"), PricePoint(LocalDate.of(2026, 11, 1), 379, "Kroger")),
            h.points,
        )
        assertEquals(9, h.changePercent)
        assertNull(PriceHistory.of("eggs", receipts, items, "USD", zone = utc))
        assertNull(PriceHistory.of("bananas", receipts, items, "USD", zone = utc)!!.changePercent)
    }

    @Test
    fun `personal inflation weights each item by what was spent on it`() {
        // A year apart: milk +10 %, bread +0 %, coffee +20 %, with three times as much spent on coffee.
        val start = LocalDate.of(2025, 11, 16)
        buy(start, "Kroger", line("Milk", 300), line("Bread", 250), line("Coffee", 1_000))
        buy(start.plusDays(365), "Kroger", line("Milk", 330), line("Bread", 250), line("Coffee", 1_200))

        val inflation = PriceHistory.inflation(receipts, items, "USD", today, zone = utc)!!

        assertEquals(3, inflation.items)
        assertEquals(start, inflation.since)
        // (10 % x 630 + 0 % x 500 + 20 % x 2,200) / 3,330 = 15.1 %, a touch more once 365 days
        // is stretched to a 365.25-day year: 15.12 %.
        assertEquals(1512, inflation.rateBp)
        assertEquals(1510, inflation.usableBp)
        // Falling prices, or a rise over 20 %, can't go in the retirement estimate.
        assertNull(inflation.copy(rateBp = -120).usableBp)
        assertNull(inflation.copy(rateBp = 2_006).usableBp)
        assertEquals(2_000, inflation.copy(rateBp = 2_004).usableBp)
    }

    @Test
    fun `no rate from short spans, too few items, or one odd price`() {
        val start = LocalDate.of(2026, 9, 1)
        // Two months apart: too short to say anything about a year.
        buy(start, "Kroger", line("Milk", 300), line("Bread", 250), line("Coffee", 1_000))
        buy(start.plusDays(60), "Kroger", line("Milk", 330), line("Bread", 250), line("Coffee", 1_200))
        assertNull(PriceHistory.inflation(receipts, items, "USD", today, zone = utc))

        // Long enough, but only two items.
        receipts.clear(); items.clear()
        buy(LocalDate.of(2025, 12, 1), "Kroger", line("Milk", 300), line("Bread", 250))
        buy(LocalDate.of(2026, 11, 1), "Kroger", line("Milk", 330), line("Bread", 250))
        assertNull(PriceHistory.inflation(receipts, items, "USD", today, zone = utc))
    }

    @Test
    fun `a single sale price at the end doesn't swing the rate`() {
        val start = LocalDate.of(2025, 11, 20)
        for (name in listOf("Milk", "Bread", "Eggs")) {
            buy(start, "Kroger", line(name, 400))
            buy(start.plusDays(300), "Kroger", line(name, 400))
            buy(start.plusDays(330), "Kroger", line(name, 400))
            // Half price once, the last time.
            buy(start.plusDays(360), "Kroger", line(name, 200))
        }
        assertEquals(0, PriceHistory.inflation(receipts, items, "USD", today, zone = utc)!!.rateBp)
    }
}
