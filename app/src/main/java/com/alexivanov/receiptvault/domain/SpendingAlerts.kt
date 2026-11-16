package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Something worth a notification in the user's spending. [key] identifies it, so each one is
 * sent once; it ends with a date (or a month) so old keys can be pruned.
 */
sealed interface SpendingAlert {
    val key: String

    /** A subscription or bill charged more than last time. */
    data class PriceUp(val bill: RecurringCost) : SpendingAlert {
        override val key get() = "price:${CostEstimate.merchantKey(bill.merchant)}:${bill.lastDate}"
    }

    /** A bill is due tomorrow (only when the user asked to be reminded). */
    data class DueTomorrow(val bill: RecurringCost, val date: LocalDate) : SpendingAlert {
        override val key get() = "due:${CostEstimate.merchantKey(bill.merchant)}:$date"
    }

    /** A subscription or bill just reached three steady payments. */
    data class NewRegular(val bill: RecurringCost) : SpendingAlert {
        override val key get() = "regular:${CostEstimate.merchantKey(bill.merchant)}:${bill.lastDate}"
    }

    /** A category's spending so far this month is well above its usual month. */
    data class AboveUsual(val category: Category, val spentCents: Long, val usualCents: Long, val month: YearMonth) : SpendingAlert {
        override val key get() = "category:${category.name}:$month"
    }
}

object SpendingAlerts {

    /** Price rises and new bills are news for a week after the payment that shows them. */
    private const val FRESH_DAYS = 7L

    /** Months of receipts needed before "usual" means anything. */
    private const val HISTORY_MONTHS = 2L

    /**
     * Everything worth telling the user today. Only calendar-cycle bills (monthly, quarterly,
     * yearly) are bills here: the weekly shop is recurring but is no bill to be reminded of.
     */
    fun find(
        receipts: List<Receipt>,
        fallbackCurrency: String,
        today: LocalDate,
        dueReminders: Boolean,
        rates: ExchangeRates? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SpendingAlert> {
        val costs = CostEstimate.monthly(receipts, fallbackCurrency, today, rates, zone)
        val bills = costs.recurring.filter { CostEstimate.isCalendarCycle(it.everyDays) }
        val fresh = today.minusDays(FRESH_DAYS)
        val alerts = mutableListOf<SpendingAlert>()

        bills.filter { it.priceUp && !it.lastDate.isBefore(fresh) }.mapTo(alerts) { SpendingAlert.PriceUp(it) }
        bills.filter { it.receipts == CostEstimate.MIN_PAYMENTS && !it.lastDate.isBefore(fresh) }
            .mapTo(alerts) { SpendingAlert.NewRegular(it) }
        if (dueReminders) {
            Bills.upcoming(bills, today.plusDays(1), days = 1).mapTo(alerts) { SpendingAlert.DueTomorrow(it.bill, it.date) }
        }
        alerts += aboveUsual(receipts, fallbackCurrency, today, rates, zone)
        return alerts
    }

    /**
     * Categories where this month so far is at least a quarter, and 20 currency units, above the
     * average month before it. Needs two months of receipts before this one.
     */
    fun aboveUsual(
        receipts: List<Receipt>,
        fallbackCurrency: String,
        today: LocalDate,
        rates: ExchangeRates? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SpendingAlert.AboveUsual> {
        val month = YearMonth.from(today)
        val start = month.atDay(1)
        val (before, during) = receipts.partition { Insights.localDate(it.purchasedAt, zone).isBefore(start) }
        val usual = CostEstimate.monthly(before, fallbackCurrency, start.minusDays(1), rates, zone)
        val since = usual.since ?: return emptyList()
        if (since.isAfter(start.minusMonths(HISTORY_MONTHS))) return emptyList()

        val usualBy = usual.byCategory.associate { it.category to it.monthlyCents }
        return Conversion.toBase(during, rates)
            .filter { it.currency == usual.currency }
            .groupBy { Category.fromName(it.category) }
            .mapNotNull { (category, list) ->
                val spent = list.sumOf { it.totalCents }
                val normal = usualBy[category] ?: return@mapNotNull null
                if (spent * 4 < normal * 5 || spent - normal < 2_000) return@mapNotNull null
                SpendingAlert.AboveUsual(category, spent, normal, month)
            }
            .sortedByDescending { it.spentCents - it.usualCents }
    }
}
