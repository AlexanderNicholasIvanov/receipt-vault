package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.YearMonth
import java.time.ZoneId

/**
 * Monthly spending limits in the default currency: one for everything and,
 * optionally, one per category. Stored as "ALL=50000;DINING=20000".
 */
data class Budgets(
    val monthlyCents: Long? = null,
    val byCategory: Map<Category, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = monthlyCents == null && byCategory.isEmpty()

    fun encode(): String = buildList {
        monthlyCents?.let { add("$ALL=$it") }
        Category.entries.forEach { c -> byCategory[c]?.let { add("${c.name}=$it") } }
    }.joinToString(";")

    companion object {
        private const val ALL = "ALL"

        fun decode(text: String?): Budgets {
            if (text.isNullOrBlank()) return Budgets()
            var monthly: Long? = null
            val categories = LinkedHashMap<Category, Long>()
            for (part in text.split(';')) {
                val key = part.substringBefore('=', "").trim()
                val cents = part.substringAfter('=', "").trim().toLongOrNull()?.takeIf { it > 0 } ?: continue
                if (key == ALL) {
                    monthly = cents
                } else {
                    Category.entries.firstOrNull { it.name == key }?.let { categories[it] = cents }
                }
            }
            return Budgets(monthly, categories)
        }
    }
}

enum class BudgetLevel { OK, NEAR, OVER }

/** How much of one budget this month has used. [category] null is the overall budget. */
data class BudgetUse(val category: Category?, val spentCents: Long, val budgetCents: Long) {
    val level: BudgetLevel
        get() = when {
            spentCents > budgetCents -> BudgetLevel.OVER
            // Integer maths: spent >= 80 % of budget.
            spentCents * 100 >= budgetCents * NEAR_PERCENT -> BudgetLevel.NEAR
            else -> BudgetLevel.OK
        }

    /** 0..1 for progress bars. */
    val fraction: Float get() = if (budgetCents <= 0) 0f else (spentCents.toDouble() / budgetCents).toFloat().coerceIn(0f, 1f)

    val label: String get() = category?.label ?: "Monthly budget"

    companion object {
        const val NEAR_PERCENT = 80
    }
}

object BudgetMath {

    /** Overall budget first, then categories in their usual order. Only receipts in [currency] count. */
    fun usage(
        budgets: Budgets,
        receipts: List<Receipt>,
        currency: String,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BudgetUse> {
        val inMonth = receipts.filter { it.currency == currency && YearMonth.from(Insights.localDate(it.purchasedAt, zone)) == month }
        val uses = mutableListOf<BudgetUse>()
        budgets.monthlyCents?.let { uses += BudgetUse(null, inMonth.sumOf { r -> r.totalCents }, it) }
        for (c in Category.entries) {
            val limit = budgets.byCategory[c] ?: continue
            uses += BudgetUse(c, inMonth.filter { Category.fromName(it.category) == c }.sumOf { it.totalCents }, limit)
        }
        return uses
    }

    /**
     * Identifies an alert so each budget warns once per month per level:
     * "budget:2026-11:ALL:NEAR". Null when the budget is fine.
     */
    fun alertKey(month: YearMonth, use: BudgetUse): String? {
        if (use.level == BudgetLevel.OK) return null
        return "budget:$month:${use.category?.name ?: "ALL"}:${use.level.name}"
    }
}
