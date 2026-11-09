package com.alexivanov.receiptvault.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * All money is stored as a Long count of minor units (cents). Floating point
 * never touches an amount.
 */
object Money {

    /** One amount as printed on a receipt: "12.34", "1,234.56", "12,34", "$ 5.00-", "(3.00)". */
    data class Found(val cents: Long, val start: Int, val end: Int)

    // Integer part (with optional thousands separators), a decimal separator and exactly two decimals.
    // The lookahead stops dates like 11.06.26 from reading as 11.06, and tax rates like 8.25% from reading as money.
    private val AMOUNT = Regex(
        """(?<![\d.,])(\(?)(-?)\s?[${'$'}€£¥]?\s?(\d{1,3}(?:[,.']\d{3})+|\d+)([.,])(\d{2})(?!\d|[.,]\d|\s?%)(\)?)(-?)""",
    )

    // "1 234,56": spaces as thousands separators, which only comma-decimal countries use.
    private val SPACED_AMOUNT = Regex(
        """(?<![\d.,])(\(?)(-?)\s?[${'$'}€£¥]?\s?(\d{1,3}(?:[ \u00A0\u202F]\d{3})+)(,)(\d{2})(?!\d|[.,]\d|\s?%)(\)?)(-?)""",
    )

    /**
     * Every amount on a line, left to right.
     *
     * @param spacedThousands read "1 234,56" as one amount. Only for rows that hold a single
     *   labelled amount such as a total: on an item row "2 100,00" is two of something at 100.00.
     */
    fun findAll(line: String, spacedThousands: Boolean = false): List<Found> {
        val spaced = if (spacedThousands) SPACED_AMOUNT.findAll(line).mapNotNull(::found).toList() else emptyList()
        val plain = AMOUNT.findAll(line).mapNotNull(::found).filter { p -> spaced.none { p.start < it.end && it.start < p.end } }
        return (spaced + plain).sortedBy { it.start }
    }

    private fun found(m: MatchResult): Found? {
        val (open, minus, intPart, _, decimals, close, trailingMinus) = m.destructured
        val whole = intPart.filter { it.isDigit() }.toLongOrNull() ?: return null
        if (whole > 9_999_999) return null
        val cents = whole * 100 + decimals.toLong()
        val negative = minus.isNotEmpty() || trailingMinus.isNotEmpty() || (open.isNotEmpty() && close.isNotEmpty())
        return Found(if (negative) -cents else cents, m.range.first, m.range.last + 1)
    }

    /** Parses what a user typed into a money field. Blank or junk -> null. */
    fun parseUserInput(text: String): Long? {
        val cleaned = text.trim().replace(Regex("""[^\d.,-]"""), "")
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        // Treat the last separator as the decimal point when it is followed by 1-2 digits.
        val lastSep = cleaned.indexOfLast { it == '.' || it == ',' }
        val normalized = if (lastSep >= 0 && cleaned.length - lastSep - 1 in 1..2) {
            cleaned.substring(0, lastSep).replace(Regex("[.,]"), "") + "." + cleaned.substring(lastSep + 1)
        } else {
            cleaned.replace(Regex("[.,]"), "")
        }
        return try {
            BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    /** "12.34" style for editable text fields. */
    fun toPlain(cents: Long?): String = cents?.let {
        BigDecimal.valueOf(it).movePointLeft(2).setScale(2).toPlainString()
    } ?: ""

    fun format(cents: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(currencyCode) }
        return nf.format(BigDecimal.valueOf(cents).movePointLeft(2))
    }

    fun defaultCurrency(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrNull() ?: "USD"
}
