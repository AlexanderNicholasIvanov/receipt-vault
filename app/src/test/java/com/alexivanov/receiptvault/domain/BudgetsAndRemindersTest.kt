package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class BudgetsAndRemindersTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-11-06")

    private fun r(
        id: Long,
        cents: Long,
        date: String = "2026-11-02",
        currency: String = "USD",
        category: Category = Category.DINING,
        returnBy: String? = null,
        warranty: String? = null,
    ) = Receipt(
        id = id, merchant = "Shop $id", category = category.name, totalCents = cents, currency = currency,
        purchasedAt = LocalDate.parse(date).atTime(12, 0).toInstant(zone).toEpochMilli(), createdAt = 0, updatedAt = 0,
        returnBy = returnBy?.let { LocalDate.parse(it).toEpochDay() }, warrantyUntil = warranty?.let { LocalDate.parse(it).toEpochDay() },
    )

    // ------------------------------------------------------------------ budgets

    @Test
    fun `budgets round trip and ignore junk`() {
        val b = Budgets(50_000, mapOf(Category.FUEL to 10_000, Category.DINING to 20_000))
        assertEquals("ALL=50000;DINING=20000;FUEL=10000", b.encode())
        assertEquals(b, Budgets.decode(b.encode()))
        assertEquals(Budgets(byCategory = mapOf(Category.FUEL to 5)), Budgets.decode("ALL=-3;NOPE=7;FUEL=5;=;DINING=x"))
        assertTrue(Budgets.decode(null).isEmpty)
    }

    @Test
    fun `levels switch at 80 percent and above the limit`() {
        assertEquals(BudgetLevel.OK, BudgetUse(null, 7_999, 10_000).level)
        assertEquals(BudgetLevel.NEAR, BudgetUse(null, 8_000, 10_000).level)
        assertEquals(BudgetLevel.NEAR, BudgetUse(null, 10_000, 10_000).level)
        assertEquals(BudgetLevel.OVER, BudgetUse(null, 10_001, 10_000).level)
        assertEquals(1f, BudgetUse(null, 50_000, 10_000).fraction)
    }

    @Test
    fun `usage counts this month in the budget currency only`() {
        val receipts = listOf(
            r(1, 3_000),
            r(2, 4_000, category = Category.FUEL),
            r(3, 9_999, currency = "EUR"),
            r(4, 9_999, date = "2026-10-31"),
        )
        val uses = BudgetMath.usage(Budgets(10_000, mapOf(Category.FUEL to 3_000)), receipts, "USD", YearMonth.of(2026, 11), zone)
        assertEquals(listOf(null, Category.FUEL), uses.map { it.category })
        assertEquals(7_000L, uses[0].spentCents)
        assertEquals(4_000L, uses[1].spentCents)
        assertEquals(BudgetLevel.OVER, uses[1].level)
    }

    @Test
    fun `alert keys are per month, budget and level`() {
        val month = YearMonth.of(2026, 11)
        assertNull(BudgetMath.alertKey(month, BudgetUse(null, 1, 100)))
        assertEquals("budget:2026-11:ALL:NEAR", BudgetMath.alertKey(month, BudgetUse(null, 90, 100)))
        assertEquals("budget:2026-11:FUEL:OVER", BudgetMath.alertKey(month, BudgetUse(Category.FUEL, 101, 100)))
    }

    // ------------------------------------------------------------------ reminders

    @Test
    fun `reminders are due inside their lead time and not after the date`() {
        val receipts = listOf(
            r(1, 100, returnBy = "2026-11-08"), // 2 days: due
            r(2, 100, returnBy = "2026-11-09"), // 3 days: not yet
            r(3, 100, returnBy = "2026-11-05"), // passed: never
            r(4, 100, warranty = "2026-12-06"), // 30 days: due
            r(5, 100, warranty = "2026-12-07"), // 31 days: not yet
            r(6, 100, returnBy = "2026-11-06", warranty = "2027-11-06"), // today
        )
        val due = Reminders.due(receipts, today)
        assertEquals(listOf(6L to ReminderKind.RETURN, 1L to ReminderKind.RETURN, 4L to ReminderKind.WARRANTY), due.map { it.receiptId to it.kind })
        assertEquals(listOf(0L, 2L, 30L), due.map { it.daysLeft })
        assertEquals(6, Reminders.all(receipts, today).size)
    }

    @Test
    fun `reminder text and key`() {
        val due = Reminders.due(listOf(r(1, 100, returnBy = "2026-11-07"), r(2, 100, warranty = "2026-11-26")), today)
        assertEquals("return:1:2026-11-07", due[0].key)
        assertEquals("Return window closing: Shop 1", due[0].title)
        assertEquals("Tomorrow is the last day to return it.", due[0].text)
        assertEquals("Warranty ending: Shop 2", due[1].title)
        assertEquals("The warranty runs out in 20 days.", due[1].text)
    }
}
