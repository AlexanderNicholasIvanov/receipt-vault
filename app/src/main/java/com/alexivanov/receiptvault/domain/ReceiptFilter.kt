package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.ZoneId

enum class SortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    HIGHEST("Highest total"),
    LOWEST("Lowest total"),
    MERCHANT("Merchant A–Z"),
}

/**
 * The home list's extra filters, on top of the period chips and search.
 * Amounts compare the receipt total in its own currency.
 */
data class ReceiptFilter(
    val categories: Set<Category> = emptySet(),
    val minCents: Long? = null,
    val maxCents: Long? = null,
    /** Inclusive custom date range; when set it replaces the period chips. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sort: SortOrder = SortOrder.NEWEST,
) {
    val hasDates: Boolean get() = from != null && to != null

    /** How many filters narrow the list (sorting doesn't count). */
    val activeCount: Int
        get() = listOf(categories.isNotEmpty(), minCents != null || maxCents != null, hasDates).count { it }

    fun matches(r: Receipt, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (categories.isNotEmpty() && Category.fromName(r.category) !in categories) return false
        if (minCents != null && r.totalCents < minCents) return false
        if (maxCents != null && r.totalCents > maxCents) return false
        if (hasDates) {
            val d = Insights.localDate(r.purchasedAt, zone)
            if (d.isBefore(from) || d.isAfter(to)) return false
        }
        return true
    }

    fun sort(receipts: List<Receipt>): List<Receipt> = when (sort) {
        // Ties fall back to newest first, the list's natural order.
        SortOrder.NEWEST -> receipts.sortedWith(compareByDescending<Receipt> { it.purchasedAt }.thenByDescending { it.id })
        SortOrder.OLDEST -> receipts.sortedWith(compareBy<Receipt> { it.purchasedAt }.thenBy { it.id })
        SortOrder.HIGHEST -> receipts.sortedWith(compareByDescending<Receipt> { it.totalCents }.thenByDescending { it.purchasedAt })
        SortOrder.LOWEST -> receipts.sortedWith(compareBy<Receipt> { it.totalCents }.thenByDescending { it.purchasedAt })
        SortOrder.MERCHANT -> receipts.sortedWith(
            compareBy<Receipt, String>(String.CASE_INSENSITIVE_ORDER) { it.merchant.trim() }.thenByDescending { it.purchasedAt },
        )
    }

    fun apply(receipts: List<Receipt>, zone: ZoneId = ZoneId.systemDefault()): List<Receipt> =
        sort(receipts.filter { matches(it, zone) })

    companion object {
        /** A range typed backwards (max below min) is read the right way round. */
        fun amountRange(min: Long?, max: Long?): Pair<Long?, Long?> =
            if (min != null && max != null && max < min) max to min else min to max
    }
}
