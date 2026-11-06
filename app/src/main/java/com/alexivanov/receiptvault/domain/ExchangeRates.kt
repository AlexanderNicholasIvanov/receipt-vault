package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Daily reference rates: [rates] maps a currency to how many units of it one
 * unit of [base] buys (the European Central Bank's convention). Amounts stay
 * in Long cents; the division is done in BigDecimal, never floating point.
 */
data class ExchangeRates(
    val base: String,
    /** The working day the rates are for. */
    val date: LocalDate,
    val rates: Map<String, BigDecimal>,
    /** When they were downloaded (epoch millis). */
    val fetchedAt: Long,
) {
    fun canConvert(currency: String): Boolean = currency == base || (rates[currency]?.signum() ?: 0) > 0

    /** [cents] in [currency] restated in [base], or null when there's no rate for it. */
    fun toBase(cents: Long, currency: String): Long? {
        if (currency == base) return cents
        val rate = rates[currency]?.takeIf { it.signum() > 0 } ?: return null
        return BigDecimal.valueOf(cents).divide(rate, 0, RoundingMode.HALF_EVEN).longValueExact()
    }

    /** Old enough that a new download is worth trying (ECB publishes once each working day). */
    fun isStale(now: Long): Boolean = now - fetchedAt !in 0 until REFRESH_AFTER_MS

    fun encode(): String =
        "$base|$date|$fetchedAt|" + rates.toSortedMap().entries.joinToString(",") { (c, r) -> "$c=${r.toPlainString()}" }

    companion object {
        const val REFRESH_AFTER_MS = 12 * 60 * 60 * 1000L

        private val CODE = Regex("[A-Z]{3}")

        fun decode(text: String?): ExchangeRates? = runCatching {
            val (base, date, fetchedAt, list) = text!!.split('|', limit = 4)
            require(CODE.matches(base))
            val rates = list.split(',').filter { it.isNotBlank() }.associate { pair ->
                val (code, rate) = pair.split('=', limit = 2)
                require(CODE.matches(code))
                code to BigDecimal(rate)
            }
            ExchangeRates(base, LocalDate.parse(date), rates, fetchedAt.toLong())
        }.getOrNull()

        /** Frankfurter's `/v1/latest?base=XXX` response. */
        fun parseFrankfurter(body: String, fetchedAt: Long): ExchangeRates {
            val root = Json.parseToJsonElement(body).jsonObject
            val base = root["base"]?.jsonPrimitive?.content.orEmpty()
            require(CODE.matches(base)) { "Unexpected base currency." }
            val date = LocalDate.parse(root["date"]?.jsonPrimitive?.content)
            // A number's content is its literal text, so rates go into BigDecimal without a Double in between.
            val rates = root["rates"]?.jsonObject.orEmpty()
                .filterKeys { CODE.matches(it) }
                .mapNotNull { (code, value) -> value.jsonPrimitive.content.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { code to it } }
                .toMap()
            require(rates.isNotEmpty()) { "No rates in the response." }
            return ExchangeRates(base, date, rates, fetchedAt)
        }
    }
}

/** Restating receipts in one currency so they can be added up. */
object Conversion {

    /**
     * Receipts in another currency are copied with every amount in [ExchangeRates.base];
     * receipts with no rate (or no [rates] at all) come back unchanged.
     */
    fun toBase(receipts: List<Receipt>, rates: ExchangeRates?): List<Receipt> {
        if (rates == null) return receipts
        return receipts.map { r ->
            if (r.currency == rates.base || !rates.canConvert(r.currency)) {
                r
            } else {
                fun c(cents: Long?) = cents?.let { rates.toBase(it, r.currency) }
                r.copy(
                    currency = rates.base,
                    totalCents = c(r.totalCents)!!,
                    subtotalCents = c(r.subtotalCents),
                    taxCents = c(r.taxCents),
                    tipCents = c(r.tipCents),
                    discountCents = c(r.discountCents),
                )
            }
        }
    }
}
