package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ExchangeRatesTest {

    private val rates = ExchangeRates(
        base = "USD",
        date = LocalDate.parse("2026-11-05"),
        rates = mapOf("EUR" to BigDecimal("0.92"), "JPY" to BigDecimal("150.25"), "GBP" to BigDecimal("0.79123")),
        fetchedAt = 1_000_000,
    )

    private fun r(id: Long, cents: Long, currency: String, tip: Long? = null) = Receipt(
        id = id, merchant = "Shop $id", category = Category.DINING.name, totalCents = cents, currency = currency,
        tipCents = tip, subtotalCents = tip?.let { cents - it },
        purchasedAt = 0, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `amounts are divided by the rate and rounded half-even to the cent`() {
        assertEquals(1087L, rates.toBase(1000, "EUR")) // 10.869… -> 10.87
        assertEquals(998L, rates.toBase(150_000, "JPY")) // ¥1,500 -> $9.98
        assertEquals(500L, rates.toBase(500, "USD"))
        assertEquals(0L, rates.toBase(0, "EUR"))
        assertEquals(-1087L, rates.toBase(-1000, "EUR"))
        assertNull(rates.toBase(1000, "CHF"))
        assertTrue(rates.canConvert("USD") && rates.canConvert("GBP"))
        assertFalse(rates.canConvert("CHF"))
    }

    @Test
    fun `rates survive the settings round trip exactly`() {
        val decoded = ExchangeRates.decode(rates.encode())
        assertEquals(rates, decoded)
        assertEquals("USD|2026-11-05|1000000|EUR=0.92,GBP=0.79123,JPY=150.25", rates.encode())
        assertNull(ExchangeRates.decode(null))
        assertNull(ExchangeRates.decode("garbage"))
        assertNull(ExchangeRates.decode("usd|2026-11-05|1|EUR=1"))
        assertNull(ExchangeRates.decode("USD|2026-13-05|1|EUR=1"))
    }

    @Test
    fun `the Frankfurter response is read without losing digits`() {
        val body = """{"amount":1.0,"base":"USD","date":"2026-11-05","rates":{"EUR":0.91234567,"JPY":150.2,"XAU":"x","bad":1,"ZZZ":0}}"""
        val parsed = ExchangeRates.parseFrankfurter(body, fetchedAt = 42)
        assertEquals("USD", parsed.base)
        assertEquals(LocalDate.parse("2026-11-05"), parsed.date)
        assertEquals(mapOf("EUR" to BigDecimal("0.91234567"), "JPY" to BigDecimal("150.2")), parsed.rates)
        assertEquals(42L, parsed.fetchedAt)
        assertTrue(runCatching { ExchangeRates.parseFrankfurter("""{"message":"not found"}""", 0) }.isFailure)
        assertTrue(runCatching { ExchangeRates.parseFrankfurter("""{"base":"USD","date":"2026-11-05","rates":{}}""", 0) }.isFailure)
    }

    @Test
    fun `rates go stale after twelve hours, or if the clock goes backwards`() {
        assertFalse(rates.isStale(1_000_000 + ExchangeRates.REFRESH_AFTER_MS - 1))
        assertTrue(rates.isStale(1_000_000 + ExchangeRates.REFRESH_AFTER_MS))
        assertTrue(rates.isStale(999_999))
    }

    @Test
    fun `conversion restates every amount and leaves unknown currencies alone`() {
        val eur = r(1, 1000, "EUR", tip = 100)
        val chf = r(2, 1000, "CHF")
        val usd = r(3, 1000, "USD")
        val out = Conversion.toBase(listOf(eur, chf, usd), rates)
        assertEquals("USD", out[0].currency)
        assertEquals(1087L, out[0].totalCents)
        assertEquals(109L, out[0].tipCents)
        assertEquals(978L, out[0].subtotalCents)
        assertNull(out[0].taxCents)
        assertSame(chf, out[1])
        assertSame(usd, out[2])
        assertEquals(listOf(eur, chf), Conversion.toBase(listOf(eur, chf), null))
    }

    @Test
    fun `summaries include converted receipts and say so`() {
        val list = listOf(r(1, 1000, "EUR"), r(2, 1000, "EUR"), r(3, 500, "USD"), r(4, 700, "CHF"))
        val plain = Insights.summarize(list, "USD")
        assertEquals("EUR", plain.currency)
        assertEquals(2000L, plain.totalCents)
        assertEquals(0, plain.convertedCount)

        val converted = Insights.summarize(list, "USD", rates)
        assertEquals("USD", converted.currency)
        assertEquals(1087L * 2 + 500, converted.totalCents)
        assertEquals(3, converted.count)
        assertEquals(2, converted.convertedCount)
        assertEquals(1, converted.otherCurrencyCount)
        assertEquals(LocalDate.parse("2026-11-05"), converted.ratesDate)

        assertNull(Insights.summarize(listOf(r(5, 1, "USD")), "USD", rates).ratesDate)
    }
}
