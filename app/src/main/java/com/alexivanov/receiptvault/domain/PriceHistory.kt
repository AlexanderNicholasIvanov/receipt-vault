package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

/** One purchase of an item: the price of a single unit on that day, and where. */
data class PricePoint(val date: LocalDate, val unitCents: Long, val merchant: String)

/** Every purchase of one item, oldest first. */
data class ItemHistory(val name: String, val currency: String, val points: List<PricePoint>) {
    /** From the first price to the latest, rounded to a whole percent; null with one price. */
    val changePercent: Int?
        get() {
            if (points.size < 2) return null
            val first = points.first().unitCents
            if (first <= 0) return null
            return Math.floorDiv((points.last().unitCents - first) * 200 + first, first * 2).toInt()
        }
}

/** How fast the prices of the things this user buys again and again are rising. */
data class PersonalInflation(
    /** A year, in hundredths of a percent: 420 is 4.2 %. */
    val rateBp: Int,
    /** Items the rate is built from. */
    val items: Int,
    /** The oldest purchase counted. */
    val since: LocalDate,
) {
    /** The rate rounded to a tenth of a percent, the precision it is shown with. */
    val roundedBp: Int
        get() = Math.floorDiv(rateBp + 5, 10) * 10

    /** [roundedBp] if the retirement estimate can take it, null when prices fall or rise over 20 % a year. */
    val usableBp: Int?
        get() = roundedBp.takeIf { it in 0..2_000 }
}

object PriceHistory {

    /** An item needs prices this many days apart to say anything about a yearly rate. */
    const val MIN_SPAN_DAYS = 120L

    /** Only the last 2 years count: older prices say little about prices now. */
    private const val WINDOW_DAYS = 730L

    /** The start and end price are the median of the purchases in the first and last 60 days. */
    private const val EDGE_DAYS = 60L

    /** Items needed before the rate means anything. */
    const val MIN_ITEMS = 3

    /** Every purchase of the item whose [SpendingBreakdown.itemKey] is [key]; null if never bought. */
    fun of(
        key: String,
        receipts: List<Receipt>,
        items: List<LineItem>,
        fallbackCurrency: String,
        rates: ExchangeRates? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ItemHistory? {
        val (currency, priced) = pricedItems(receipts, items, fallbackCurrency, rates)
        val mine = priced.filter { SpendingBreakdown.itemKey(it.first.name) == key }
        if (mine.isEmpty()) return null
        val latest = mine.maxBy { it.third.purchasedAt }
        val name = mine.groupingBy { it.first.name.trim() }.eachCount()
            .maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key == latest.first.name.trim() }).key
        return ItemHistory(name, currency, points(mine, zone))
    }

    /**
     * The yearly price rise of the items bought again and again over the last 2 years, each item
     * weighted by what was spent on it, like an official price index weights its basket. Each
     * item's start and end prices are medians, so one sale price doesn't swing the result.
     * Null until [MIN_ITEMS] items have prices [MIN_SPAN_DAYS] or more apart.
     */
    fun inflation(
        receipts: List<Receipt>,
        items: List<LineItem>,
        fallbackCurrency: String,
        today: LocalDate,
        rates: ExchangeRates? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PersonalInflation? {
        val from = today.minusDays(WINDOW_DAYS)
        val (_, priced) = pricedItems(receipts, items, fallbackCurrency, rates)
        var weightSum = 0.0
        var rateSum = 0.0
        var count = 0
        var since: LocalDate? = null
        priced.filter { !Insights.localDate(it.third.purchasedAt, zone).isBefore(from) }
            .groupBy { SpendingBreakdown.itemKey(it.first.name) }
            .filterKeys { it.isNotEmpty() }
            .forEach { (_, list) ->
                val pts = points(list, zone)
                val first = pts.first().date
                val last = pts.last().date
                val span = ChronoUnit.DAYS.between(first, last)
                if (span < MIN_SPAN_DAYS) return@forEach
                val start = median(pts.filter { !it.date.isAfter(first.plusDays(EDGE_DAYS)) }.map { it.unitCents })
                val end = median(pts.filter { !it.date.isBefore(last.minusDays(EDGE_DAYS)) }.map { it.unitCents })
                if (start <= 0 || end <= 0) return@forEach
                // A rate, not an amount of money, so a Double is fine. Capped so one odd item can't dominate.
                val yearly = ((end.toDouble() / start).pow(365.25 / span) - 1).coerceIn(-0.5, 1.0)
                val weight = list.sumOf { it.second }.toDouble()
                if (weight <= 0) return@forEach
                rateSum += yearly * weight
                weightSum += weight
                count++
                since = minOf(since ?: first, first)
            }
        if (count < MIN_ITEMS || weightSum <= 0) return null
        return PersonalInflation((rateSum / weightSum * 10_000).roundToInt(), count, since!!)
    }

    /** One price per receipt (a line repeated on one receipt is one purchase), oldest first. */
    private fun points(list: List<Triple<LineItem, Long, Receipt>>, zone: ZoneId): List<PricePoint> =
        list.groupBy { it.third.id }
            .map { (_, lines) ->
                val units = lines.sumOf { it.first.quantity.coerceAtLeast(1) }
                val receipt = lines.first().third
                PricePoint(
                    date = Insights.localDate(receipt.purchasedAt, zone),
                    unitCents = SpendingBreakdown.unitPrice(lines.sumOf { it.second }, units),
                    merchant = receipt.merchant.trim(),
                )
            }
            .sortedBy { it.date }

    /** Line items with prices in the main currency, the same one the monthly costs use. */
    private fun pricedItems(receipts: List<Receipt>, items: List<LineItem>, fallbackCurrency: String, rates: ExchangeRates?): Pair<String, List<Triple<LineItem, Long, Receipt>>> {
        val converted = Conversion.toBase(receipts, rates)
        val currency = Insights.primaryCurrency(converted, fallbackCurrency)
        return currency to SpendingBreakdown.priced(converted.filter { it.currency == currency }, receipts, items, rates)
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
