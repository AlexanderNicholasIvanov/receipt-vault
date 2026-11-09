package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MoneyTest {

    private fun cents(line: String) = Money.findAll(line).map { it.cents }

    @Test
    fun `finds plain, thousands, symbol and negative amounts`() {
        assertEquals(listOf(1234L), cents("Total 12.34"))
        assertEquals(listOf(123456L), cents("Total 1,234.56"))
        assertEquals(listOf(123456L), cents("Summe 1.234,56"))
        assertEquals(listOf(500L), cents("Cash $5.00"))
        assertEquals(listOf(-200L), cents("Coupon -2.00"))
        assertEquals(listOf(-200L), cents("Coupon 2.00-"))
        assertEquals(listOf(-300L), cents("Refund (3.00)"))
        assertEquals(listOf(480L), cents("Croissant 4,80"))
    }

    @Test
    fun `ignores dates, phone numbers and quantities`() {
        assertTrue(cents("11.06.26").isEmpty())
        assertTrue(cents("Call 555.123.4567").isEmpty())
        assertTrue(cents("Qty 2").isEmpty())
        assertEquals(listOf(10000L), cents("Qty 2 100.00"))
    }

    @Test
    fun `parses user input`() {
        assertEquals(1234L, Money.parseUserInput("12.34"))
        assertEquals(1234L, Money.parseUserInput("$12.34"))
        assertEquals(1200L, Money.parseUserInput("12"))
        assertEquals(1250L, Money.parseUserInput("12.5"))
        assertEquals(123456L, Money.parseUserInput("1,234.56"))
        assertEquals(1250L, Money.parseUserInput("12,50"))
        assertNull(Money.parseUserInput(""))
        assertNull(Money.parseUserInput("abc"))
    }

    @Test
    fun `round trips to plain text and formats currency`() {
        assertEquals("12.30", Money.toPlain(1230))
        assertEquals("", Money.toPlain(null))
        assertEquals("$1,234.50", Money.format(123450, "USD", Locale.US))
    }

    @Test
    fun `percentages aren't money`() {
        assertEquals(listOf(83L), cents("Tax 0.83 (8.25%)"))
        assertEquals(listOf(83L), cents("Tax 8.25 % 0.83"))
        assertTrue(cents("VAT 19,00%").isEmpty())
    }

    @Test
    fun `spaced thousands are read only when asked for`() {
        assertEquals(listOf(123456L), Money.findAll("TOTAL 1 234,56", spacedThousands = true).map { it.cents })
        assertEquals(listOf(123456L), Money.findAll("TOTAL 1\u00A0234,56", spacedThousands = true).map { it.cents })
        assertEquals(listOf(23456L), cents("Coffee 1 234,56"))
        assertEquals(listOf(10000L), Money.findAll("Qty 2 100.00", spacedThousands = true).map { it.cents })
    }
}
