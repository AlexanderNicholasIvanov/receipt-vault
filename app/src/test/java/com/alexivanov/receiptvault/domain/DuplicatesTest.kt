package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DuplicatesTest {

    private val zone = ZoneOffset.UTC

    private fun r(id: Long, merchant: String, cents: Long = 2560, at: String = "2026-11-06T12:00", currency: String = "USD", deletedAt: Long? = null) = Receipt(
        id = id, merchant = merchant, category = Category.DINING.name, totalCents = cents, currency = currency,
        purchasedAt = LocalDateTime.parse(at).toInstant(zone).toEpochMilli(), createdAt = 0, updatedAt = 0, deletedAt = deletedAt,
    )

    @Test
    fun `same merchant, total and day is a duplicate, whatever the time`() {
        val saved = listOf(r(1, "Shell", 4000), r(2, "Corner Cafe", at = "2026-11-06T08:15"))
        assertEquals(2L, Duplicates.find(r(0, "corner café".replace("é", "e"), at = "2026-11-06T21:40"), saved, zone)?.id)
    }

    @Test
    fun `a different total, currency or day is not`() {
        val saved = listOf(r(1, "Corner Cafe"))
        assertNull(Duplicates.find(r(0, "Corner Cafe", cents = 2561), saved, zone))
        assertNull(Duplicates.find(r(0, "Corner Cafe", currency = "EUR"), saved, zone))
        assertNull(Duplicates.find(r(0, "Corner Cafe", at = "2026-11-07T12:00"), saved, zone))
        assertNull(Duplicates.find(r(0, "Shell"), saved, zone))
    }

    @Test
    fun `the receipt itself and trashed receipts don't count`() {
        assertNull(Duplicates.find(r(1, "Corner Cafe"), listOf(r(1, "Corner Cafe")), zone))
        assertNull(Duplicates.find(r(0, "Corner Cafe"), listOf(r(2, "Corner Cafe", deletedAt = 5)), zone))
    }

    @Test
    fun `merchant names match loosely`() {
        assertTrue(Duplicates.sameMerchant("CORNER CAFE", "Corner-Cafe"))
        assertTrue(Duplicates.sameMerchant("Corner Cafe", "Corner Cafe #12"))
        assertTrue(Duplicates.sameMerchant("Unknown merchant", "Shell"))
        assertTrue(Duplicates.sameMerchant("", "Shell"))
        assertFalse(Duplicates.sameMerchant("Shell", "Shell Beach Bar".reversed()))
        assertFalse("too short to call a prefix", Duplicates.sameMerchant("Ab", "Abc Market"))
        assertFalse(Duplicates.sameMerchant("Target", "Walmart"))
    }
}
