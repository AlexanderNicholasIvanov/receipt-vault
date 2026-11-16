package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class SpendingAlertsTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 16)

    private fun receipt(date: LocalDate, total: Long, merchant: String, category: Category = Category.ENTERTAINMENT) = Receipt(
        merchant = merchant, category = category.name, totalCents = total, currency = "USD",
        purchasedAt = date.atTime(12, 0).toInstant(utc).toEpochMilli(), createdAt = 0, updatedAt = 0,
    )

    private fun find(receipts: List<Receipt>, dueReminders: Boolean = false, on: LocalDate = today) =
        SpendingAlerts.find(receipts, "USD", on, dueReminders, zone = utc)

    /** A monthly payment on the 14th, oldest first, one per amount. */
    private fun monthly(vararg cents: Long, merchant: String = "Netflix", last: LocalDate = LocalDate.of(2026, 11, 14)) =
        cents.mapIndexed { i, c -> receipt(last.minusMonths((cents.size - 1 - i).toLong()), c, merchant) }

    @Test
    fun `a subscription charged more than last time is a price rise`() {
        val receipts = monthly(1_599, 1_599, 1_599, 1_799)
        val bill = CostEstimate.monthly(receipts, "USD", today, zone = utc).recurring.single()
        assertTrue(bill.priceUp)
        assertEquals(1_799, bill.latestCents)
        assertEquals(1_599L, bill.previousCents)

        val alert = find(receipts).single() as SpendingAlert.PriceUp
        assertEquals("price:netflix:2026-11-14", alert.key)
        assertEquals("Price up: Netflix", AlertTexts.spending(alert, "USD", hideDetails = false).title)
        assertEquals("$15.99 → $17.99 (+13%) on Nov 14, 2026.", AlertTexts.spending(alert, "USD", hideDetails = false).text)
        // With app lock on, nothing identifying.
        assertEquals(AlertText("A regular payment went up", "Open Receipt Vault to see the details."), AlertTexts.spending(alert, "USD", hideDetails = true))
    }

    @Test
    fun `small changes, old rises and weekly shopping are not price rises`() {
        // 4 % more: under the 5 % threshold.
        assertTrue(find(monthly(10_000, 10_000, 10_000, 10_400)).isEmpty())
        // A rise more than a week ago was news then, not now.
        assertTrue(find(monthly(1_599, 1_599, 1_599, 1_799), on = today.plusDays(10)).isEmpty())
        // A dearer weekly shop is not a price rise.
        val shop = (0..5).map { w -> receipt(today.minusWeeks(5L - w), if (w == 5) 9_000 else 6_000, "Kroger", Category.GROCERIES) }
        assertFalse(CostEstimate.monthly(shop, "USD", today, zone = utc).recurring.single().priceUp)
        assertTrue(find(shop).none { it is SpendingAlert.PriceUp })
    }

    @Test
    fun `a third steady monthly payment is a new regular payment, once`() {
        val alert = find(monthly(1_099, 1_099, 1_099, merchant = "Spotify")).single() as SpendingAlert.NewRegular
        assertEquals("regular:spotify:2026-11-14", alert.key)
        assertEquals(
            AlertText("New regular payment: Spotify", "$10.99 every month, about $131.88 a year."),
            AlertTexts.spending(alert, "USD", hideDetails = false),
        )
        // By the fourth payment it isn't new.
        assertTrue(find(monthly(1_099, 1_099, 1_099, 1_099, merchant = "Spotify")).isEmpty())
    }

    @Test
    fun `bills due tomorrow only when reminders are on`() {
        val receipts = monthly(1_599, 1_599, 1_599, 1_599, last = LocalDate.of(2026, 10, 17))
        assertTrue(find(receipts).isEmpty())
        val due = find(receipts, dueReminders = true).single() as SpendingAlert.DueTomorrow
        assertEquals(LocalDate.of(2026, 11, 17), due.date)
        assertEquals("due:netflix:2026-11-17", due.key)
        assertEquals(AlertText("Due tomorrow: Netflix", "Usually $15.99."), AlertTexts.spending(due, "USD", hideDetails = false))
    }

    @Test
    fun `a category well above its usual month`() {
        // $100 of dining every 10 days from July to October: about $322 a month.
        val history = generateSequence(LocalDate.of(2026, 7, 1)) { it.plusDays(10) }
            .takeWhile { it.isBefore(LocalDate.of(2026, 11, 1)) }
            .map { receipt(it, 10_000, "Bistro ${it.dayOfYear}", Category.DINING) }
            .toList()
        val usual = SpendingAlerts.aboveUsual(history, "USD", today, zone = utc)
        assertTrue("nothing this month yet", usual.isEmpty())

        val big = history + receipt(LocalDate.of(2026, 11, 10), 45_000, "Steakhouse", Category.DINING)
        val alert = SpendingAlerts.aboveUsual(big, "USD", today, zone = utc).single()
        assertEquals(Category.DINING, alert.category)
        assertEquals(45_000, alert.spentCents)
        assertEquals(32_170, alert.usualCents)
        assertEquals(YearMonth.of(2026, 11), alert.month)
        assertEquals("category:DINING:2026-11", alert.key)
        assertEquals("Dining is above usual", AlertTexts.spending(alert, "USD", hideDetails = false).title)

        // A quarter over is the line: $380 is not enough above about $322.
        val near = history + receipt(LocalDate.of(2026, 11, 10), 38_000, "Steakhouse", Category.DINING)
        assertTrue(SpendingAlerts.aboveUsual(near, "USD", today, zone = utc).isEmpty())
        // Without two months of history there is no usual month.
        val short = big.filter { Insights.localDate(it.purchasedAt, utc) >= LocalDate.of(2026, 9, 15) }
        assertTrue(SpendingAlerts.aboveUsual(short, "USD", today, zone = utc).isEmpty())
    }
}
