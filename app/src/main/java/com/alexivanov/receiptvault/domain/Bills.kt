package com.alexivanov.receiptvault.domain

import java.time.LocalDate
import java.time.YearMonth

/** One expected payment of a recurring bill. */
data class DueBill(val bill: RecurringCost, val date: LocalDate)

/** This calendar month's recurring bills: how many are paid, and what is still to come. */
data class MonthOfBills(
    val paid: Int,
    /** Bills paid this month plus bills still expected this month. */
    val due: Int,
    /** The usual amount of the bills still expected this month. */
    val leftCents: Long,
)

/** What the recurring costs found in the receipts mean for the days and weeks ahead. */
object Bills {

    /**
     * Payments expected from [today] through the [days] days that follow it, today included,
     * soonest first. A bill that comes more than once in that time, like a weekly one, is listed
     * once per payment. A bill whose payment is late is expected at its next one.
     */
    fun upcoming(recurring: List<RecurringCost>, today: LocalDate, days: Int = 7): List<DueBill> {
        val end = today.plusDays(days.toLong())
        return recurring.flatMap { bill ->
            generateSequence(1L) { it + 1 }
                .map { n -> CostEstimate.dueDate(bill.lastDate, bill.everyDays, n) }
                .dropWhile { it.isBefore(today) }
                .takeWhile { it.isBefore(end) }
                .map { DueBill(bill, it) }
                .toList()
        }.sortedWith(compareBy<DueBill> { it.date }.thenByDescending { it.bill.typicalCents })
    }

    /** The soonest payment from [today] on, of any bill; null without recurring bills. */
    fun next(recurring: List<RecurringCost>, today: LocalDate): DueBill? =
        recurring.map { bill ->
            val date = generateSequence(1L) { it + 1 }
                .map { n -> CostEstimate.dueDate(bill.lastDate, bill.everyDays, n) }
                .first { !it.isBefore(today) }
            DueBill(bill, date)
        }.minWithOrNull(compareBy<DueBill> { it.date }.thenByDescending { it.bill.typicalCents })

    /**
     * Bills paid this month (their latest receipt is from this month) against the bills due this
     * month. A bill that is late from an earlier month still counts as due.
     */
    fun thisMonth(recurring: List<RecurringCost>, today: LocalDate): MonthOfBills {
        val month = YearMonth.from(today)
        val (paid, unpaid) = recurring.partition { YearMonth.from(it.lastDate) == month }
        val expected = unpaid.filter { YearMonth.from(it.nextDate) <= month }
        return MonthOfBills(paid = paid.size, due = paid.size + expected.size, leftCents = expected.sumOf { it.typicalCents })
    }
}
