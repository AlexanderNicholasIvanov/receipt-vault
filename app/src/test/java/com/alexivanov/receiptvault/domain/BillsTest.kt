package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BillsTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 16)

    private fun bill(merchant: String, last: LocalDate, everyDays: Int, cents: Long = 1_000) = RecurringCost(
        merchant = merchant, category = Category.OTHER, receipts = 3, typicalCents = cents, everyDays = everyDays,
        monthlyCents = CostEstimate.perMonthEvery(cents, everyDays.toLong()), lastDate = last,
        nextDate = CostEstimate.dueDate(last, everyDays, 1),
    )

    private fun receipt(date: LocalDate, total: Long) = Receipt(
        merchant = "Shop", category = Category.OTHER.name, totalCents = total, currency = "USD",
        purchasedAt = date.atTime(12, 0).toInstant(utc).toEpochMilli(), createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `a calendar bill keeps its day of the month`() {
        assertEquals(LocalDate.of(2026, 12, 15), CostEstimate.dueDate(LocalDate.of(2026, 11, 15), 30, 1))
        // The 31st becomes the last day of a shorter month, and comes back after it.
        assertEquals(LocalDate.of(2027, 2, 28), CostEstimate.dueDate(LocalDate.of(2027, 1, 31), 31, 1))
        assertEquals(LocalDate.of(2027, 3, 31), CostEstimate.dueDate(LocalDate.of(2027, 1, 31), 31, 2))
        assertEquals(LocalDate.of(2027, 11, 15), CostEstimate.dueDate(LocalDate.of(2026, 11, 15), 365, 1))
        // Other cycles step by days.
        assertEquals(LocalDate.of(2026, 11, 29), CostEstimate.dueDate(LocalDate.of(2026, 11, 15), 7, 2))
    }

    @Test
    fun `upcoming lists each payment in the next week, soonest first`() {
        val netflix = bill("Netflix", LocalDate.of(2026, 10, 18), 30, 1_599)
        val gym = bill("Gym", LocalDate.of(2026, 10, 30), 30, 4_000)
        val shop = bill("Weekly shop", LocalDate.of(2026, 11, 14), 7, 6_000)
        val insurance = bill("Insurance", LocalDate.of(2026, 9, 1), 91, 30_000)

        val due = Bills.upcoming(listOf(netflix, gym, shop, insurance), today)

        assertEquals(
            listOf("Netflix" to LocalDate.of(2026, 11, 18), "Weekly shop" to LocalDate.of(2026, 11, 21)),
            due.map { it.bill.merchant to it.date },
        )
        // Two weeks ahead, the weekly shop comes twice.
        assertEquals(2, Bills.upcoming(listOf(shop), today, days = 14).size)
        // However far off, the next one is known.
        assertEquals(DueBill(gym, LocalDate.of(2026, 11, 30)), Bills.next(listOf(insurance, gym), today))
        assertEquals(DueBill(insurance, LocalDate.of(2026, 12, 1)), Bills.next(listOf(insurance), today))
        assertNull(Bills.next(emptyList(), today))
    }

    @Test
    fun `today is included and a late bill moves to its next payment`() {
        val dueToday = bill("Phone", LocalDate.of(2026, 10, 16), 30)
        assertEquals(listOf(today), Bills.upcoming(listOf(dueToday), today).map { it.date })

        val late = bill("Water", LocalDate.of(2026, 11, 2), 7)
        assertEquals(listOf(LocalDate.of(2026, 11, 16)), Bills.upcoming(listOf(late), today).map { it.date })
    }

    @Test
    fun `this month counts paid bills against the bills due`() {
        val paid = bill("Netflix", LocalDate.of(2026, 11, 3), 30, 1_599)
        val coming = bill("Gym", LocalDate.of(2026, 10, 28), 30, 4_000)
        val late = bill("Phone", LocalDate.of(2026, 9, 25), 30, 5_000) // was due Oct 25
        val notThisMonth = bill("Insurance", LocalDate.of(2026, 10, 1), 91, 30_000) // due Jan 1

        val m = Bills.thisMonth(listOf(paid, coming, late, notThisMonth), today)

        assertEquals(1, m.paid)
        assertEquals(3, m.due)
        assertEquals(9_000, m.leftCents)
    }

    @Test
    fun `the trend compares the last 3 full months with the 3 before`() {
        // Since May: $100 in each of May to July, $120 in each of August to October, and some of November.
        val receipts = (5..10).map { m -> receipt(LocalDate.of(2026, m, 3), if (m <= 7) 10_000 else 12_000) } +
            receipt(LocalDate.of(2026, 11, 2), 500)

        val t = Insights.trend(receipts, "USD", today, utc)!!

        assertEquals(20, t.percent)
        assertEquals(12_000, t.recentMonthlyCents)
        assertEquals(10_000, t.earlierMonthlyCents)
        // Overview carries it, in the monthly-cost currency.
        assertEquals(t, Insights.overview(receipts, Period.THIS_YEAR, "USD", today, zone = utc).trend)
    }

    @Test
    fun `no trend without six full months of receipts`() {
        // The first receipt is May 20: May is a part month, so a comparison would be misleading.
        val late = (5..10).map { m -> receipt(LocalDate.of(2026, m, 20), 10_000) }
        assertNull(Insights.trend(late, "USD", today, utc))
        assertNull(Insights.trend(emptyList(), "USD", today, utc))
        // A drop rounds to the nearest percent.
        val down = (5..10).map { m -> receipt(LocalDate.of(2026, m, 1), if (m <= 7) 30_000 else 20_000) }
        assertEquals(-33, Insights.trend(down, "USD", today, utc)!!.percent)
    }
}
