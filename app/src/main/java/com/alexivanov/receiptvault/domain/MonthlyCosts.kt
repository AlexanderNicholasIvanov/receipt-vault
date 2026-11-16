package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What one kind of spending costs in an average month, and how often it happens. */
data class CategoryCost(
    val category: Category,
    val receipts: Int,
    val monthlyCents: Long,
    val timesPerMonth: Double,
)

/**
 * A bill on a calendar cycle that now charges a different amount: [fromCents] before, [toCents]
 * from [since] on, for [paymentsSince] payments so far.
 */
data class PriceChange(
    val fromCents: Long,
    val toCents: Long,
    val since: LocalDate,
    val paymentsSince: Int,
) {
    val up: Boolean get() = toCents > fromCents

    /** News while the new price is on its first few bills; after that it's just the price. */
    val recent: Boolean get() = paymentsSince <= RECENT_PAYMENTS

    companion object {
        const val RECENT_PAYMENTS = 3
    }
}

/** A merchant paid at a steady pace: a subscription, a monthly bill, the weekly shop. */
data class RecurringCost(
    val merchant: String,
    val category: Category,
    val receipts: Int,
    /**
     * The usual amount of one payment: the median, or for a bill whose price changed, the
     * median since the change.
     */
    val typicalCents: Long,
    /** The usual number of days between payments (the median). */
    val everyDays: Int,
    val monthlyCents: Long,
    val lastDate: LocalDate,
    val nextDate: LocalDate,
    /** The most recent payment. */
    val latestCents: Long = typicalCents,
    /** The payment before it. */
    val previousCents: Long? = null,
    /** The last change of price, for bills on a calendar cycle; null when the price has held. */
    val priceChange: PriceChange? = null,
) {
    /**
     * The latest payment of a bill on a calendar cycle (a subscription, a monthly bill) is at
     * least 5 % and one currency unit more than the one before. Weekly shopping varies too much
     * for that to mean a price rise, so it never counts.
     */
    val priceUp: Boolean
        get() = previousCents != null && CostEstimate.isCalendarCycle(everyDays) &&
            CostEstimate.isPriceStep(previousCents, latestCents) && latestCents > previousCents
}

data class MonthlyCosts(
    val currency: String,
    /** Receipts the averages are built from. */
    val receipts: Int,
    /** Date of the oldest of those receipts; null when there are none. */
    val since: LocalDate?,
    /** Everything spent, spread evenly over the months since [since]. */
    val monthlyCents: Long,
    val byCategory: List<CategoryCost>,
    val recurring: List<RecurringCost>,
    /** Receipts in another currency, left out of the averages. */
    val otherCurrencyCount: Int,
) {
    val yearlyCents: Long get() = monthlyCents * 12
    val recurringMonthlyCents: Long get() = recurring.sumOf { it.monthlyCents }
}

/**
 * Turns the receipts collected so far into an average monthly cost. Nothing is stored: every
 * new receipt changes the averages and can reveal a new recurring cost, so the estimate keeps
 * up with the user going forward.
 */
object CostEstimate {

    /** Average days in a month (365.25 / 12), as a fraction: 487 / 16 = 30.4375. */
    private const val MONTH_NUM = 487L
    private const val MONTH_DEN = 16L

    /** A merchant needs this many separate days of payments before it counts as recurring. */
    const val MIN_PAYMENTS = 3

    fun monthly(
        original: List<Receipt>,
        fallbackCurrency: String,
        today: LocalDate,
        rates: ExchangeRates? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MonthlyCosts {
        val all = Conversion.toBase(original, rates)
        val currency = Insights.primaryCurrency(all, fallbackCurrency)
        val receipts = all.filter { it.currency == currency }
        val dates = receipts.map { Insights.localDate(it.purchasedAt, zone) }
        val since = dates.minOrNull()
        // Inclusive of both ends, never shorter than the receipts (a date typed in the future).
        val days = if (since == null) 0L else ChronoUnit.DAYS.between(since, maxOf(today, dates.max())) + 1

        return MonthlyCosts(
            currency = currency,
            receipts = receipts.size,
            since = since,
            monthlyCents = perMonth(receipts.sumOf { it.totalCents }, days),
            byCategory = receipts.groupBy { Category.fromName(it.category) }
                .map { (category, list) ->
                    CategoryCost(category, list.size, perMonth(list.sumOf { it.totalCents }, days), timesPerMonth(list.size, days))
                }
                .sortedByDescending { it.monthlyCents },
            recurring = recurring(receipts, today, zone),
            otherCurrencyCount = all.size - receipts.size,
        )
    }

    /** [cents] spent over [days] days, as a monthly amount. Less than a month of receipts counts as one month. */
    fun perMonth(cents: Long, days: Long): Long {
        if (days <= 0) return 0
        val denominator = days * MONTH_DEN
        if (denominator <= MONTH_NUM) return cents
        return Math.floorDiv(cents * MONTH_NUM + denominator / 2, denominator)
    }

    private fun timesPerMonth(count: Int, days: Long): Double =
        if (days <= 0) 0.0 else count * (MONTH_NUM.toDouble() / MONTH_DEN) / maxOf(days.toDouble(), MONTH_NUM.toDouble() / MONTH_DEN)

    /** Store numbers and punctuation dropped, so "Shell #4521" and "SHELL #330" are one merchant. */
    fun merchantKey(merchant: String): String = merchant.lowercase()
        .replace(Regex("""#\s*\d+|\b(no\.?|store)\s*\d+"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun recurring(receipts: List<Receipt>, today: LocalDate, zone: ZoneId): List<RecurringCost> =
        receipts.groupBy { merchantKey(it.merchant) }
            .filterKeys { it.isNotEmpty() }
            .mapNotNull { (_, list) ->
                // One payment per day: two receipts from the same visit are one payment.
                val byDay = list.groupBy { Insights.localDate(it.purchasedAt, zone) }.toSortedMap()
                if (byDay.size < MIN_PAYMENTS) return@mapNotNull null
                val gaps = byDay.keys.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
                val every = median(gaps)
                // Steady: at least three in four gaps are within half to one and a half times the usual one.
                val steady = gaps.count { it * 2 >= every && it * 2 <= every * 3 } * 4 >= gaps.size * 3
                val last = byDay.lastKey()
                // Stopped: more than two missed payments ago, with a week's slack.
                val stopped = ChronoUnit.DAYS.between(last, today) > every * 2 + 7
                if (!steady || stopped) return@mapNotNull null
                val payments = byDay.values.map { day -> day.sumOf { it.totalCents } }
                val change = if (isCalendarCycle(every.toInt())) priceChange(payments, byDay.keys.toList()) else null
                val typical = change?.toCents ?: median(payments)
                val latest = list.maxBy { it.purchasedAt }
                RecurringCost(
                    merchant = latest.merchant.trim(),
                    category = list.groupingBy { Category.fromName(it.category) }.eachCount().maxBy { it.value }.key,
                    receipts = list.size,
                    typicalCents = typical,
                    everyDays = every.toInt(),
                    monthlyCents = perMonthEvery(typical, every),
                    lastDate = last,
                    nextDate = dueDate(last, every.toInt(), 1),
                    latestCents = payments.last(),
                    previousCents = payments[payments.size - 2],
                    priceChange = change,
                )
            }
            .sortedByDescending { it.monthlyCents }

    /**
     * At least 5 % and one currency unit apart: a new price, not rounding or a small fee.
     */
    fun isPriceStep(a: Long, b: Long): Boolean {
        val lower = minOf(a, b)
        return maxOf(a, b) - lower >= maxOf(100L, Math.floorDiv(lower + 19, 20))
    }

    /**
     * The latest change of price in [payments] (oldest first, paid on [dates]): the last step
     * between two payments, after a price that held for at least two payments. A bill that varies
     * every month (electricity) has no price to change, and a one-off charge followed by the usual
     * one is not a change.
     */
    internal fun priceChange(payments: List<Long>, dates: List<LocalDate>): PriceChange? {
        fun stepAt(i: Int) = isPriceStep(payments[i - 1], payments[i])
        val at = (payments.size - 1 downTo 1).firstOrNull(::stepAt) ?: return null
        val held = (at - 1 downTo 1).firstOrNull(::stepAt) ?: 0
        if (at - held < 2) return null
        val before = median(payments.subList(held, at))
        val after = median(payments.subList(at, payments.size))
        if (!isPriceStep(before, after)) return null
        return PriceChange(fromCents = before, toCents = after, since = dates[at], paymentsSince = payments.size - at)
    }

    /**
     * A payment of [cents] every [days] days, as a monthly amount. Bills on a calendar cycle count
     * in whole months, so a $15.99 monthly subscription costs $15.99 a month whatever the month's length.
     */
    fun perMonthEvery(cents: Long, days: Long): Long {
        val months = calendarMonths(days.toInt())
        val denominator = if (months != null) months.toLong() else days * MONTH_DEN
        val numerator = if (months != null) cents else cents * MONTH_NUM
        return Math.floorDiv(numerator + denominator / 2, denominator)
    }

    /**
     * The [n]th payment after [last] for a bill paid every [everyDays] days. Bills on a calendar
     * cycle keep their day of the month (the 15th stays the 15th, and the 31st becomes the last
     * day of a shorter month); other bills step by days.
     */
    fun dueDate(last: LocalDate, everyDays: Int, n: Long): LocalDate {
        val months = calendarMonths(everyDays)
        return if (months != null) last.plusMonths(months * n) else last.plusDays(everyDays * n)
    }

    /** A gap of [days] days is a calendar cycle: monthly, every 2, 3 or 6 months, or yearly. */
    fun isCalendarCycle(days: Int): Boolean = calendarMonths(days) != null

    /** The calendar cycle a gap of [days] days stands for: 1, 2, 3, 6 or 12 months; null for other gaps. */
    private fun calendarMonths(days: Int): Int? = when (days) {
        in 27..34 -> 1
        in 56..65 -> 2
        in 85..98 -> 3
        in 175..190 -> 6
        in 350..380 -> 12
        else -> null
    }

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /** "Every month", "Every 2 weeks", "About every 5 weeks". */
    fun everyLabel(days: Int): String = when (val months = calendarMonths(days)) {
        1 -> "Every month"
        12 -> "Every year"
        null -> everyDaysLabel(days)
        else -> "Every $months months"
    }

    private fun everyDaysLabel(days: Int): String = when {
        days <= 1 -> "Every day"
        days in 6..8 -> "Every week"
        days in 13..16 -> "Every 2 weeks"
        days < 13 -> "Every $days days"
        days < 56 -> "About every ${(days / 7.0).roundToInt()} weeks"
        days < 350 -> "About every ${(days / 30.4375).roundToInt()} months"
        else -> "About every ${(days / 365.25).roundToInt()} years"
    }

    /** "About 3 times a week", "About once a month", "About every 4 months". */
    fun howOften(timesPerMonth: Double): String {
        val perWeek = timesPerMonth * 7 / 30.4375
        return when {
            timesPerMonth <= 0.0 -> "Not yet"
            perWeek >= 1.5 -> "About ${perWeek.roundToLong()} times a week"
            perWeek >= 0.75 -> "About once a week"
            timesPerMonth >= 1.5 -> "About ${timesPerMonth.roundToLong()} times a month"
            timesPerMonth >= 0.75 -> "About once a month"
            else -> {
                val months = (1 / timesPerMonth).roundToLong()
                when {
                    months < 2 -> "About once a month"
                    months < 12 -> "About every $months months"
                    months < 18 -> "About once a year"
                    else -> "About every ${(months / 12.0).roundToLong()} years"
                }
            }
        }
    }
}
