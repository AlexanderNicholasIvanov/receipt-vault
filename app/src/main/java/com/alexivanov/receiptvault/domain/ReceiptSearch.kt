package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt

/** The home search box: merchant, category, note, tag, address, line items or any text read from the photo. */
object ReceiptSearch {

    /** Line item names per receipt id, lowercased and joined, ready for [matches]. */
    fun itemText(items: List<Pair<Long, String>>): Map<Long, String> =
        items.groupBy({ it.first }, { it.second.lowercase() }).mapValues { (_, names) -> names.joinToString("\n") }

    /** [needle] is the trimmed, lowercased query. */
    fun matches(r: Receipt, needle: String, itemText: String?): Boolean =
        r.merchant.lowercase().contains(needle) ||
            Category.fromName(r.category).label.lowercase().contains(needle) ||
            r.notes.lowercase().contains(needle) ||
            r.tags.lowercase().contains(needle) ||
            r.address.orEmpty().lowercase().contains(needle) ||
            itemText.orEmpty().contains(needle) ||
            r.ocrText.lowercase().contains(needle)
}
