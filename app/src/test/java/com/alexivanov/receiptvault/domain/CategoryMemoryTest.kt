package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryMemoryTest {

    private var nextId = 1L

    private fun receipt(merchant: String, category: Category, updatedAt: Long) = Receipt(
        id = nextId++, merchant = merchant, category = category.name, totalCents = 1_000, currency = "USD",
        purchasedAt = 0, createdAt = 0, updatedAt = updatedAt,
    )

    @Test
    fun `the latest category given to a store is remembered, across store numbers`() {
        val memory = CategoryMemory.learn(
            listOf(
                receipt("Shell #4521", Category.FUEL, updatedAt = 1),
                // Corrected later: the station's shop, bought as groceries.
                receipt("SHELL #330", Category.GROCERIES, updatedAt = 5),
                receipt("Joe's Diner", Category.DINING, updatedAt = 2),
            ),
        )
        assertEquals(mapOf("shell" to Category.GROCERIES, "joe s diner" to Category.DINING), memory)
    }

    @Test
    fun `Other and blank stores teach nothing`() {
        val memory = CategoryMemory.learn(
            listOf(
                receipt("Corner Shop", Category.OTHER, updatedAt = 9),
                receipt("Corner Shop", Category.SHOPPING, updatedAt = 1),
                receipt("  ", Category.DINING, updatedAt = 1),
            ),
        )
        assertEquals(mapOf("corner shop" to Category.SHOPPING), memory)
    }
}
