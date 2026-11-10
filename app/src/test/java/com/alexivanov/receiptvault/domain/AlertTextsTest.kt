package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class AlertTextsTest {

    private val reminder = Reminder(receiptId = 7, merchant = "Best Electronics", kind = ReminderKind.RETURN, date = LocalDate.of(2026, 11, 10), daysLeft = 1)
    private val over = BudgetUse(category = Category.DINING, spentCents = 31_250, budgetCents = 30_000)

    @Test
    fun `without app lock the notification names the receipt and amounts`() {
        assertEquals(AlertText("Return window closing: Best Electronics", "Tomorrow is the last day to return it."), AlertTexts.reminder(reminder, hideDetails = false))
        assertEquals(AlertText("Over budget: ${Category.DINING.label}", "$312.50 of $300.00 spent this month."), AlertTexts.budget(over, "USD", hideDetails = false))
    }

    @Test
    fun `with app lock on nothing private is in the notification`() {
        val texts = listOf(
            AlertTexts.reminder(reminder, hideDetails = true),
            AlertTexts.reminder(reminder.copy(kind = ReminderKind.WARRANTY), hideDetails = true),
            AlertTexts.budget(over, "USD", hideDetails = true),
            AlertTexts.budget(over.copy(spentCents = 25_000), "USD", hideDetails = true),
        )
        assertEquals("A return window is closing", texts[0].title)
        assertEquals("Over budget", texts[2].title)
        for (t in texts + AlertTexts.lockScreen) {
            val all = t.title + " " + t.text
            assertFalse(all, "Best Electronics" in all || "$" in all || Category.DINING.label in all)
        }
    }
}
