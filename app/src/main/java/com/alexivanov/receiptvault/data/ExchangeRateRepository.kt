package com.alexivanov.receiptvault.data

import com.alexivanov.receiptvault.domain.ExchangeRates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** Where daily rates come from; faked in tests. */
fun interface RateSource {
    suspend fun latest(base: String): ExchangeRates
}

/**
 * European Central Bank reference rates via frankfurter.dev: free, no key, and
 * the only thing sent is the base currency code.
 */
class FrankfurterRateSource(private val clock: () -> Long = System::currentTimeMillis) : RateSource {
    override suspend fun latest(base: String): ExchangeRates = withContext(Dispatchers.IO) {
        val conn = URL("https://api.frankfurter.dev/v1/latest?base=$base").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            when (conn.responseCode) {
                in 200..299 -> ExchangeRates.parseFrankfurter(conn.inputStream.bufferedReader().use { it.readText() }, clock())
                404, 422 -> throw FileNotFoundException("The European Central Bank doesn't publish rates for $base.")
                else -> error("The rate service answered ${conn.responseCode}.")
            }
        } finally {
            conn.disconnect()
        }
    }
}

sealed interface RatesStatus {
    data object Idle : RatesStatus
    data object Loading : RatesStatus
    data class Failed(val message: String) : RatesStatus
}

/**
 * Keeps the cached rates in step with the setting: downloads them when
 * conversion is on and the cache is missing, stale or for another base
 * currency. A failed download keeps the previous rates.
 */
class ExchangeRateRepository(
    private val settings: SettingsRepository,
    private val source: RateSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<RatesStatus>(RatesStatus.Idle)
    val status: StateFlow<RatesStatus> = _status

    /** @return true when new rates were saved. */
    suspend fun refreshIfNeeded(force: Boolean = false): Boolean = mutex.withLock {
        val s = settings.current()
        if (!s.convertCurrencies) {
            _status.value = RatesStatus.Idle
            return false
        }
        val cached = s.exchangeRates
        if (!force && cached != null && cached.base == s.defaultCurrency && !cached.isStale(clock())) return false
        _status.value = RatesStatus.Loading
        try {
            val fresh = source.latest(s.defaultCurrency)
            settings.setExchangeRates(fresh)
            _status.value = RatesStatus.Idle
            true
        } catch (e: Exception) {
            _status.value = RatesStatus.Failed(
                when (e) {
                    is FileNotFoundException -> e.message ?: "No rates for ${s.defaultCurrency}."
                    is java.io.IOException -> "Couldn't reach the rate service. Check your connection."
                    else -> "Couldn't read the exchange rates."
                },
            )
            false
        }
    }
}
