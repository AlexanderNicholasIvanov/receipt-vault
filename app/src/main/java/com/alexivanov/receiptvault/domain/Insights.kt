package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class Period(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_YEAR("This year"),
    ALL("All time");

    /** [start, end) as local dates; null means unbounded. */
    fun range(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (this) {
        THIS_MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(1).plusMonths(1)
        LAST_MONTH -> today.withDayOfMonth(1).minusMonths(1) to today.withDayOfMonth(1)
        THIS_YEAR -> today.withDayOfYear(1) to today.withDayOfYear(1).plusYears(1)
        ALL -> null to null
    }
}

data class Summary(
    val currency: String,
    val count: Int,
    val totalCents: Long,
    val subtotalCents: Long,
    val taxCents: Long,
    val tipCents: Long,
    val discountCents: Long,
    /** Tip as a share of the pre-tax subtotal, over receipts that have both. */
    val averageTipPercent: Double?,
    val averageReceiptCents: Long,
    val byCategory: List<Pair<Category, Long>>,
    val topMerchants: List<Pair<String, Long>>,
    /** Receipts in the period that use another currency and are left out of the sums. */
    val otherCurrencyCount: Int,
    /** Receipts in another currency that are included, converted at [ratesDate]'s rates. */
    val convertedCount: Int = 0,
    val ratesDate: LocalDate? = null,
)

object Insights {

    fun localDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun inPeriod(receipts: List<Receipt>, period: Period, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Receipt> {
        val (start, end) = period.range(today)
        return receipts.filter { r ->
            val d = localDate(r.purchasedAt, zone)
            (start == null || !d.isBefore(start)) && (end == null || d.isBefore(end))
        }
    }

    /** The currency most receipts use, so mixed-currency sums never happen. */
    fun primaryCurrency(receipts: List<Receipt>, fallback: String): String =
        receipts.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key ?: fallback

    /** With [rates], receipts in other currencies are converted to the rates' base before adding up. */
    fun summarize(original: List<Receipt>, fallbackCurrency: String, rates: ExchangeRates? = null): Summary {
        val receipts = Conversion.toBase(original, rates)
        val currency = primaryCurrency(receipts, fallbackCurrency)
        val same = receipts.filter { it.currency == currency }
        val converted = original.zip(receipts).count { (o, c) -> c.currency == currency && o.currency != currency }
        val tipped = same.filter { (it.tipCents ?: 0) > 0 && (it.subtotalCents ?: 0) > 0 }
        val total = same.sumOf { it.totalCents }
        return Summary(
            currency = currency,
            count = same.size,
            totalCents = total,
            subtotalCents = same.sumOf { it.subtotalCents ?: 0 },
            taxCents = same.sumOf { it.taxCents ?: 0 },
            tipCents = same.sumOf { it.tipCents ?: 0 },
            discountCents = same.sumOf { it.discountCents ?: 0 },
            averageTipPercent = tipped.takeIf { it.isNotEmpty() }?.let { list ->
                100.0 * list.sumOf { it.tipCents!! } / list.sumOf { it.subtotalCents!! }
            },
            averageReceiptCents = if (same.isEmpty()) 0 else total / same.size,
            byCategory = same.groupBy { Category.fromName(it.category) }
                .map { (c, list) -> c to list.sumOf { it.totalCents } }
                .sortedByDescending { it.second },
            topMerchants = same.groupBy { it.merchant.trim().lowercase() }
                .map { (_, list) -> list.first().merchant.trim() to list.sumOf { it.totalCents } }
                .sortedByDescending { it.second }
                .take(5),
            otherCurrencyCount = receipts.size - same.size,
            convertedCount = converted,
            ratesDate = rates?.date?.takeIf { converted > 0 },
        )
    }

    /** Spend per month for the last [months] months ending with the current one, oldest first. */
    fun monthly(receipts: List<Receipt>, currency: String, today: LocalDate, months: Int = 6, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, Long>> {
        val current = YearMonth.from(today)
        val buckets = (months - 1 downTo 0).map { current.minusMonths(it.toLong()) }
        val sums = receipts.filter { it.currency == currency }
            .groupBy { YearMonth.from(localDate(it.purchasedAt, zone)) }
            .mapValues { (_, list) -> list.sumOf { it.totalCents } }
        return buckets.map { it to (sums[it] ?: 0L) }
    }
}
