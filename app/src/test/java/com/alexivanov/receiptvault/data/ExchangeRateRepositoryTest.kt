package com.alexivanov.receiptvault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.domain.ExchangeRates
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExchangeRateRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(context)
    private var now = 10_000_000L
    private val source = FakeSource()
    private val repo = ExchangeRateRepository(settings, source) { now }

    @Before
    @After
    fun reset() {
        runBlocking {
            settings.setConvertCurrencies(false)
            settings.setExchangeRates(null)
            settings.setDefaultCurrency("USD")
        }
    }

    @Test
    fun `nothing is downloaded while conversion is off`() = runBlocking {
        assertFalse(repo.refreshIfNeeded())
        assertTrue(source.asked.isEmpty())
        assertNull(settings.current().activeRates)
    }

    @Test
    fun `turning it on downloads once, then again when stale or the currency changes`() = runBlocking {
        settings.setConvertCurrencies(true)
        assertTrue(repo.refreshIfNeeded())
        assertEquals("USD", settings.current().activeRates?.base)

        now += 60_000
        assertFalse("fresh rates are reused", repo.refreshIfNeeded())
        assertEquals(listOf("USD"), source.asked)

        now += ExchangeRates.REFRESH_AFTER_MS
        assertTrue(repo.refreshIfNeeded())

        settings.setDefaultCurrency("EUR")
        assertNull("old USD rates aren't used for EUR", settings.current().activeRates)
        assertTrue(repo.refreshIfNeeded())
        assertEquals(listOf("USD", "USD", "EUR"), source.asked)
        assertEquals("EUR", settings.current().activeRates?.base)
        assertEquals(RatesStatus.Idle, repo.status.value)
    }

    @Test
    fun `a failed download keeps the old rates and explains`() = runBlocking {
        settings.setConvertCurrencies(true)
        repo.refreshIfNeeded()
        val saved = settings.current().exchangeRates

        source.failWith = IOException("offline")
        assertFalse(repo.refreshIfNeeded(force = true))
        assertEquals(saved, settings.current().exchangeRates)
        assertEquals(RatesStatus.Failed("Couldn't reach the rate service. Check your connection."), repo.status.value)

        source.failWith = FileNotFoundException("The European Central Bank doesn't publish rates for XYZ.")
        repo.refreshIfNeeded(force = true)
        assertEquals(RatesStatus.Failed("The European Central Bank doesn't publish rates for XYZ."), repo.status.value)

        source.failWith = null
        assertTrue(repo.refreshIfNeeded(force = true))
        assertEquals(RatesStatus.Idle, repo.status.value)
    }

    private inner class FakeSource : RateSource {
        val asked = mutableListOf<String>()
        var failWith: Exception? = null
        override suspend fun latest(base: String): ExchangeRates {
            failWith?.let { throw it }
            asked += base
            return ExchangeRates(base, LocalDate.parse("2026-11-05"), mapOf("GBP" to BigDecimal("0.8")), now)
        }
    }
}
