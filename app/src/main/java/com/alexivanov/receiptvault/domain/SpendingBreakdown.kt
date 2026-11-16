package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import java.time.LocalDate
import java.time.ZoneId

/** Something bought, gathered across every receipt that lists it. */
data class ItemStat(
    val name: String,
    /** Receipts it appears on. */
    val timesBought: Int,
    /** Units bought in all. */
    val quantity: Int,
    /** The usual price of one unit (the median). */
    val typicalUnitCents: Long,
    /** The price of one unit on the latest receipt. */
    val lastUnitCents: Long,
    val lowestUnitCents: Long,
    val highestUnitCents: Long,
    val totalCents: Long,
    val lastBought: LocalDate,
)

/** A place the money goes, with the usual amount of one visit. */
data class PlaceStat(
    val merchant: String,
    val visits: Int,
    /** The usual amount of one receipt (the median). */
    val typicalCents: Long,
    val totalCents: Long,
)

/** What one category (or everything, when [category] is null) costs and what it is made of. */
data class CategoryBreakdown(
    val category: Category?,
    val currency: String,
    val receipts: Int,
    val since: LocalDate?,
    val totalCents: Long,
    val monthlyCents: Long,
    val timesPerMonth: Double,
    /** The usual amount of one receipt (the median). */
    val typicalCents: Long,
    val averageCents: Long,
    val lowestCents: Long,
    val highestCents: Long,
    val items: List<ItemStat>,
    val places: List<PlaceStat>,
    /** Receipts that have line items; only these can say what was bought. */
    val itemizedReceipts: Int,
    val otherCurrencyCount: Int,
)

/**
 * Breaks the spending of one category down into what is usually paid, what is usually bought
 * and where. Built from every receipt so far, so it follows along as receipts are added.
 */
object SpendingBreakdown {

    /**
     * @param category null for everything.
     * @param items line items of [receipts]; items of other receipts are ignored.
     */
    fun of(
        category: Category?,
        receipts: List<Receipt>,
        items: List<LineItem>,
        fallbackCurrency: String,
        today: LocalDate,
        rates: ExchangeRates? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CategoryBreakdown {
        // The same month count and currency as the monthly cost, so the numbers agree.
        val costs = CostEstimate.monthly(receipts, fallbackCurrency, today, rates, zone)
        val currency = costs.currency
        val converted = Conversion.toBase(receipts, rates)
        val inCategory = { r: Receipt -> category == null || Category.fromName(r.category) == category }
        val chosen = converted.filter { it.currency == currency && inCategory(it) }
        val others = converted.count { it.currency != currency && inCategory(it) }
        val totals = chosen.map { it.totalCents }
        val total = totals.sum()
        val line = costs.byCategory.firstOrNull { it.category == category }

        // Item prices follow their receipt into the base currency.
        val originalCurrency = receipts.associate { it.id to it.currency }
        val byId = chosen.associateBy { it.id }
        val chosenItems = items.mapNotNull { item ->
            val receipt = byId[item.receiptId] ?: return@mapNotNull null
            val from = originalCurrency.getValue(item.receiptId)
            val price = if (from == receipt.currency) item.priceCents else rates?.toBase(item.priceCents, from) ?: return@mapNotNull null
            Triple(item, price, receipt)
        }

        return CategoryBreakdown(
            category = category,
            currency = currency,
            receipts = chosen.size,
            since = chosen.minOfOrNull { Insights.localDate(it.purchasedAt, zone) },
            totalCents = total,
            monthlyCents = if (category == null) costs.monthlyCents else line?.monthlyCents ?: 0,
            timesPerMonth = if (category == null) costs.byCategory.sumOf { it.timesPerMonth } else line?.timesPerMonth ?: 0.0,
            typicalCents = median(totals),
            averageCents = if (chosen.isEmpty()) 0 else Math.floorDiv(total + chosen.size / 2, chosen.size.toLong()),
            lowestCents = totals.minOrNull() ?: 0,
            highestCents = totals.maxOrNull() ?: 0,
            items = itemStats(chosenItems, zone),
            places = chosen.groupBy { CostEstimate.merchantKey(it.merchant).ifEmpty { it.merchant.trim().lowercase() } }
                .map { (_, list) ->
                    PlaceStat(
                        merchant = list.maxBy { it.purchasedAt }.merchant.trim(),
                        visits = list.size,
                        typicalCents = median(list.map { it.totalCents }),
                        totalCents = list.sumOf { it.totalCents },
                    )
                }
                .sortedWith(compareByDescending<PlaceStat> { it.totalCents }.thenByDescending { it.visits }),
            itemizedReceipts = chosenItems.map { it.third.id }.distinct().size,
            otherCurrencyCount = others,
        )
    }

    /** Most often bought first, then by money spent. */
    private fun itemStats(items: List<Triple<LineItem, Long, Receipt>>, zone: ZoneId): List<ItemStat> =
        items.groupBy { (item, _, _) -> itemKey(item.name) }
            .filterKeys { it.isNotEmpty() }
            .map { (_, list) ->
                val units = list.map { (item, price, _) -> unitPrice(price, item.quantity) }
                val latest = list.maxWith(compareBy<Triple<LineItem, Long, Receipt>> { it.third.purchasedAt }.thenBy { it.first.id })
                ItemStat(
                    // The spelling used most, then the latest one.
                    name = list.groupingBy { it.first.name.trim() }.eachCount()
                        .maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key == latest.first.name.trim() }).key,
                    timesBought = list.map { it.third.id }.distinct().size,
                    quantity = list.sumOf { it.first.quantity.coerceAtLeast(1) },
                    typicalUnitCents = median(units),
                    lastUnitCents = unitPrice(latest.second, latest.first.quantity),
                    lowestUnitCents = units.min(),
                    highestUnitCents = units.max(),
                    totalCents = list.sumOf { it.second },
                    lastBought = Insights.localDate(latest.third.purchasedAt, zone),
                )
            }
            .sortedWith(compareByDescending<ItemStat> { it.timesBought }.thenByDescending { it.totalCents }.thenBy { it.name })

    /** A line's price is for all its units. */
    private fun unitPrice(lineCents: Long, quantity: Int): Long {
        val q = quantity.coerceAtLeast(1).toLong()
        return Math.floorDiv(lineCents + q / 2, q)
    }

    /** "Bananas", "BANANAS" and "bananas." are one item. */
    fun itemKey(name: String): String = name.lowercase().replace(Regex("""[^\p{L}\p{N}%]+"""), " ").trim()

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
