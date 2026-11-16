package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt

/**
 * The category the user last gave each store, so a new receipt from the same place starts with it
 * rather than with a guess from the text. Learned from the saved receipts; nothing extra is stored.
 */
object CategoryMemory {

    /**
     * Each merchant (by [CostEstimate.merchantKey], so "Shell #4521" and "SHELL #330" are one) to the
     * category of its most recently edited receipt: a correction made today wins over older ones.
     * "Other" is never learned; it says nothing about the store.
     */
    fun learn(receipts: List<Receipt>): Map<String, Category> =
        receipts.asSequence()
            .filter { Category.fromName(it.category) != Category.OTHER }
            .groupBy { CostEstimate.merchantKey(it.merchant) }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, list) -> Category.fromName(list.maxWith(compareBy({ it.updatedAt }, { it.purchasedAt }, { it.id })).category) }
}
