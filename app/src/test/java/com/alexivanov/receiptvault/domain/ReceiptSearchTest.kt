package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptSearchTest {

    // Typed in by hand, so the item isn't in any text read from a photo.
    private val receipt = Receipt(id = 4, merchant = "Corner Store", category = "GROCERIES", purchasedAt = 1, totalCents = 899, currency = "USD", createdAt = 1, updatedAt = 1)

    @Test
    fun `a line item finds its receipt`() {
        val items = ReceiptSearch.itemText(listOf(4L to "Oat Milk", 4L to "Bread", 9L to "Stapler"))
        assertEquals("oat milk\nbread", items[4])
        assertTrue(ReceiptSearch.matches(receipt, "oat", items[4]))
        assertFalse(ReceiptSearch.matches(receipt, "stapler", items[4]))
        assertTrue(ReceiptSearch.matches(receipt, "corner", null))
    }

    @Test
    fun `item names don't run into each other`() {
        val items = ReceiptSearch.itemText(listOf(4L to "Tea", 4L to "Cups"))
        assertFalse(ReceiptSearch.matches(receipt, "teacups", items[4]))
    }
}
