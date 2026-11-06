package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ImageNamesTest {
    private val utc = ZoneOffset.UTC
    private val nov6 = LocalDateTime.of(2026, 11, 6, 23, 30).toInstant(utc).toEpochMilli()

    @Test
    fun `date first, then merchant`() {
        assertEquals("2026-11-06 Corner Cafe.jpg", ImageNames.forReceipt("Corner Cafe", nov6, utc))
    }

    @Test
    fun `characters that are illegal in file names are replaced`() {
        assertEquals("2026-11-06 AT T Store 12.jpg", ImageNames.forReceipt("AT/T  Store: 12?", nov6, utc))
        assertEquals("2026-11-06 Joe's Diner.jpg", ImageNames.forReceipt("  Joe's\tDiner\n ", nov6, utc))
    }

    @Test
    fun `blank or dot-only merchants fall back, long ones are cut`() {
        assertEquals("2026-11-06 Receipt.jpg", ImageNames.forReceipt("  ", nov6, utc))
        assertEquals("2026-11-06 Receipt.jpg", ImageNames.forReceipt("...", nov6, utc))
        val long = ImageNames.forReceipt("A".repeat(200), nov6, utc)
        assertEquals("2026-11-06 ".length + 40 + ".jpg".length, long.length)
        assertTrue(long.endsWith("A.jpg"))
    }

    @Test
    fun `uses the receipt's local date`() {
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        assertEquals("2026-11-07 Shop.jpg", ImageNames.forReceipt("Shop", nov6, tokyo))
    }
}
