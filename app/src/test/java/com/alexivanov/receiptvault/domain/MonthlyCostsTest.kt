package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class MonthlyCostsTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 6)
    private var nextId = 1L

    private fun receipt(date: LocalDate, total: Long, merchant: String = "Shop", category: Category = Category.OTHER, currency: String = "USD") =
        Receipt(
            id = nextId++, merchant = merchant, category = category.name,
            purchasedAt = date.atTime(12, 0).toInstant(utc).toEpochMilli(),
            totalCents = total, currency = currency, createdAt = 0, updatedAt = 0,
        )

    private fun costs(receipts: List<Receipt>, rates: ExchangeRates? = null) =
        CostEstimate.monthly(receipts, "USD", today, rates, utc)

    @Test
    fun `everything spent is spread over the months since the first receipt`() {
        // 60 days, including both ends: $600 is $304.38 in an average month of 30.4375 days.
        val c = costs(listOf(receipt(today.minusDays(59), 40_000), receipt(today.minusDays(10), 20_000)))
        assertEquals(30_438, c.monthlyCents)
        assertEquals(30_438 * 12L, c.yearlyCents)
        assertEquals(today.minusDays(59), c.since)
        assertEquals(2, c.receipts)
    }

    @Test
    fun `less than a month of receipts counts as one month, and none is nothing`() {
        assertEquals(2_500, costs(listOf(receipt(today, 1_000), receipt(today.minusDays(20), 1_500))).monthlyCents)
        val empty = costs(emptyList())
        assertEquals(0, empty.monthlyCents)
        assertNull(empty.since)
        assertTrue(empty.byCategory.isEmpty())
    }

    @Test
    fun `each category gets its monthly cost and how often it comes up`() {
        val start = today.minusDays(181) // 182 days, about 6 months
        val receipts = buildList {
            // Groceries twice a week, $60 each.
            var d = start
            while (!d.isAfter(today)) {
                add(receipt(d, 6_000, if (d.dayOfMonth % 2 == 0) "Aldi" else "Kroger", Category.GROCERIES))
                add(receipt(d.plusDays(3).coerceAtMost(today), 6_000, "Kroger", Category.GROCERIES))
                d = d.plusDays(7)
            }
            // Two flights in six months.
            add(receipt(start, 45_000, "United Airlines", Category.TRAVEL))
            add(receipt(start.plusDays(100), 39_000, "Delta Air Lines", Category.TRAVEL))
        }
        val c = costs(receipts)
        assertEquals(listOf(Category.GROCERIES, Category.TRAVEL), c.byCategory.map { it.category })
        val groceries = c.byCategory[0]
        assertEquals("About 2 times a week", CostEstimate.howOften(groceries.timesPerMonth))
        assertEquals(CostEstimate.perMonth(groceries.receipts * 6_000L, 182), groceries.monthlyCents)
        val travel = c.byCategory[1]
        assertEquals(CostEstimate.perMonth(84_000, 182), travel.monthlyCents)
        assertEquals("About every 3 months", CostEstimate.howOften(travel.timesPerMonth))
        assertEquals(c.monthlyCents, CostEstimate.perMonth(receipts.sumOf { it.totalCents }, 182))
    }

    @Test
    fun `a monthly subscription is recurring at its own price`() {
        val receipts = (5 downTo 0).map { receipt(LocalDate.of(2026, 11 - it, 3), 1_599, "Netflix", Category.ENTERTAINMENT) }
        val r = costs(receipts).recurring.single()
        assertEquals("Netflix", r.merchant)
        assertEquals(Category.ENTERTAINMENT, r.category)
        assertEquals(6, r.receipts)
        assertEquals(1_599, r.typicalCents)
        assertEquals("Every month", CostEstimate.everyLabel(r.everyDays))
        assertEquals(1_599, r.monthlyCents)
        assertEquals(LocalDate.of(2026, 11, 3), r.lastDate)
        // A monthly bill keeps its day of the month, whatever the month's length.
        assertEquals(LocalDate.of(2026, 12, 3), r.nextDate)
    }

    /** A monthly bill on the 3rd, oldest first, one payment per amount, the last on Nov 3. */
    private fun monthly(vararg cents: Long, merchant: String = "Netflix") =
        cents.mapIndexed { i, c -> receipt(LocalDate.of(2026, 11, 3).minusMonths((cents.size - 1 - i).toLong()), c, merchant, Category.ENTERTAINMENT) }

    @Test
    fun `after a price rise a subscription costs its new price, not the median of old and new`() {
        // Four months at $15.99, then three at $17.99: the median of all seven is still $15.99.
        val r = costs(monthly(1_599, 1_599, 1_599, 1_599, 1_799, 1_799, 1_799)).recurring.single()
        assertEquals(1_799, r.typicalCents)
        assertEquals(1_799, r.monthlyCents)
        assertEquals(PriceChange(fromCents = 1_599, toCents = 1_799, since = LocalDate.of(2026, 9, 3), paymentsSince = 3), r.priceChange)
        assertTrue(r.priceChange!!.up)
        assertTrue(r.priceChange!!.recent)
        // The alert looks at the last two payments only: same price, no news.
        assertFalse(r.priceUp)

        // A fourth bill at the new price: still the price, no longer news.
        val later = costs(monthly(1_599, 1_599, 1_599, 1_799, 1_799, 1_799, 1_799)).recurring.single()
        assertEquals(1_799, later.typicalCents)
        assertFalse(later.priceChange!!.recent)
    }

    @Test
    fun `the first bill at a new price counts at once, up or down`() {
        val up = costs(monthly(1_099, 1_099, 1_099, 1_299)).recurring.single()
        assertEquals(1_299, up.typicalCents)
        assertTrue(up.priceUp)
        val down = costs(monthly(2_000, 2_000, 2_000, 1_500)).recurring.single()
        assertEquals(1_500, down.typicalCents)
        assertFalse(down.priceChange!!.up)
        assertFalse(down.priceUp)
    }

    @Test
    fun `a one-off charge, a bill that varies, and a weekly shop have no price change`() {
        // An extra charge one month, then the usual price again.
        val oneOff = costs(monthly(1_599, 1_599, 1_599, 2_500, 1_599)).recurring.single()
        assertNull(oneOff.priceChange)
        assertEquals(1_599, oneOff.typicalCents)
        // Electricity: different every month, so there is no price to change. The usual amount is the median.
        val power = costs(monthly(8_000, 9_500, 7_000, 11_000, 8_800, merchant = "City Power")).recurring.single()
        assertNull(power.priceChange)
        assertEquals(8_800, power.typicalCents)
        // Weekly shopping is never a price change, however it moves.
        val shop = (0..5).map { w -> receipt(today.minusWeeks(5L - w), if (w >= 3) 9_000 else 6_000, "Kroger", Category.GROCERIES) }
        assertNull(costs(shop).recurring.single().priceChange)
    }

    @Test
    fun `a weekly shop is recurring, with store numbers and case ignored`() {
        val receipts = (0..7).map { w ->
            receipt(today.minusWeeks(w.toLong()), if (w % 2 == 0) 5_000 else 7_000, if (w % 2 == 0) "Shell #4521" else "SHELL #330", Category.FUEL)
        }
        val r = costs(receipts).recurring.single()
        assertEquals("Shell #4521", r.merchant) // the name on the latest receipt
        assertEquals("Every week", CostEstimate.everyLabel(r.everyDays))
        assertEquals(6_000, r.typicalCents)
        // $60 a week is $260.89 in an average month.
        assertEquals(26_089, r.monthlyCents)
        assertEquals(26_089, costs(receipts).recurringMonthlyCents)
    }

    @Test
    fun `merchant names match without store numbers, case or punctuation`() {
        assertEquals("shell", CostEstimate.merchantKey("SHELL #330"))
        assertEquals("walmart supercenter", CostEstimate.merchantKey("Walmart Supercenter Store 1234"))
        assertEquals("trader joe s", CostEstimate.merchantKey("Trader Joe's No. 42"))
        assertEquals("casino 5", CostEstimate.merchantKey("Casino 5"))
    }

    @Test
    fun `irregular, stopped and too-rare merchants are not recurring`() {
        val receipts = listOf(
            // Irregular: gaps of 2, 40 and 3 days.
            receipt(today.minusDays(45), 1_000, "Corner Cafe"),
            receipt(today.minusDays(43), 1_000, "Corner Cafe"),
            receipt(today.minusDays(3), 1_000, "Corner Cafe"),
            receipt(today, 1_000, "Corner Cafe"),
            // Was monthly, but the last payment was four months ago.
            receipt(LocalDate.of(2026, 5, 1), 999, "Old Gym"),
            receipt(LocalDate.of(2026, 6, 1), 999, "Old Gym"),
            receipt(LocalDate.of(2026, 7, 1), 999, "Old Gym"),
            // Only twice.
            receipt(today.minusDays(30), 2_000, "Barber"),
            receipt(today, 2_000, "Barber"),
            // Three receipts, but all from one visit.
            receipt(today, 300, "Kiosk"),
            receipt(today, 300, "Kiosk"),
            receipt(today, 300, "Kiosk"),
        )
        assertTrue(costs(receipts).recurring.isEmpty())
    }

    @Test
    fun `other currencies are left out unless they can be converted`() {
        val receipts = listOf(
            receipt(today, 1_000),
            receipt(today, 2_000, currency = "USD"),
            receipt(today, 1_000, currency = "EUR"),
        )
        val plain = costs(receipts)
        assertEquals(3_000, plain.monthlyCents)
        assertEquals(1, plain.otherCurrencyCount)

        val rates = ExchangeRates("USD", today, mapOf("EUR" to BigDecimal("0.5")), 0)
        val converted = costs(receipts, rates)
        assertEquals(5_000, converted.monthlyCents) // €10.00 at 0.5 is $20.00
        assertEquals(0, converted.otherCurrencyCount)
    }

    @Test
    fun `cycles and frequencies read as plain words`() {
        assertEquals("Every day", CostEstimate.everyLabel(1))
        assertEquals("Every 4 days", CostEstimate.everyLabel(4))
        assertEquals("Every 2 weeks", CostEstimate.everyLabel(14))
        assertEquals("About every 3 weeks", CostEstimate.everyLabel(21))
        assertEquals("Every 3 months", CostEstimate.everyLabel(91))
        assertEquals("Every year", CostEstimate.everyLabel(365))
        assertEquals("About every 2 years", CostEstimate.everyLabel(730))

        assertEquals("About once a week", CostEstimate.howOften(4.0))
        assertEquals("About 2 times a month", CostEstimate.howOften(2.0))
        assertEquals("About once a month", CostEstimate.howOften(1.0))
        assertEquals("About every 6 months", CostEstimate.howOften(1 / 6.0))
        assertEquals("About once a year", CostEstimate.howOften(1 / 12.0))

        // Quarterly and yearly bills count in whole months.
        assertEquals(3_000, CostEstimate.perMonthEvery(9_000, 91))
        assertEquals(1_000, CostEstimate.perMonthEvery(12_000, 365))
    }
}
