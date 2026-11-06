package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.ZoneId

/** What goes into an expense report PDF, worked out without any Android drawing code. */
data class ExpenseReport(
    val generatedOn: LocalDate,
    val from: LocalDate?,
    val to: LocalDate?,
    val lines: List<Line>,
    /** One block per currency, the most used first. Amounts are never mixed across currencies. */
    val totals: List<CurrencyTotal>,
) {
    data class Line(
        val receiptId: Long,
        val date: LocalDate,
        val merchant: String,
        val category: Category,
        val tags: List<String>,
        val taxCents: Long?,
        val tipCents: Long?,
        val totalCents: Long,
        val currency: String,
        val imagePath: String?,
    )

    data class CurrencyTotal(
        val currency: String,
        val count: Int,
        val totalCents: Long,
        val taxCents: Long,
        val tipCents: Long,
        val byCategory: List<Pair<Category, Long>>,
    )

    val count: Int get() = lines.size

    companion object {
        fun build(receipts: List<Receipt>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ExpenseReport {
            val lines = receipts
                .map { r ->
                    Line(
                        receiptId = r.id,
                        date = Insights.localDate(r.purchasedAt, zone),
                        merchant = r.merchant,
                        category = Category.fromName(r.category),
                        tags = Tags.decode(r.tags),
                        taxCents = r.taxCents,
                        tipCents = r.tipCents,
                        totalCents = r.totalCents,
                        currency = r.currency,
                        imagePath = r.imagePath,
                    )
                }
                // Chronological, like a statement.
                .sortedWith(compareBy<Line> { it.date }.thenBy { it.receiptId })

            val totals = lines.groupBy { it.currency }
                .map { (currency, ls) ->
                    CurrencyTotal(
                        currency = currency,
                        count = ls.size,
                        totalCents = ls.sumOf { it.totalCents },
                        taxCents = ls.sumOf { it.taxCents ?: 0 },
                        tipCents = ls.sumOf { it.tipCents ?: 0 },
                        byCategory = ls.groupBy { it.category }
                            .map { (c, cs) -> c to cs.sumOf { it.totalCents } }
                            .sortedByDescending { it.second },
                    )
                }
                .sortedWith(compareByDescending<CurrencyTotal> { it.count }.thenBy { it.currency })

            return ExpenseReport(
                generatedOn = today,
                from = lines.firstOrNull()?.date,
                to = lines.lastOrNull()?.date,
                lines = lines,
                totals = totals,
            )
        }

        fun fileName(today: LocalDate): String = "expense-report-$today.pdf"
    }
}
