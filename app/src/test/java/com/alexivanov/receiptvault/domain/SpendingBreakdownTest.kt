package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class SpendingBreakdownTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 6)
    private var nextId = 1L
    private val receipts = mutableListOf<Receipt>()
    private val items = mutableListOf<LineItem>()

    private fun receipt(
        date: LocalDate, total: Long, merchant: String, category: Category,
        currency: String = "USD", vararg lines: Triple<String, Int, Long>,
    ): Receipt {
        val r = Receipt(
            id = nextId++, merchant = merchant, category = category.name,
            purchasedAt = date.atTime(12, 0).toInstant(utc).toEpochMilli(),
            totalCents = total, currency = currency, createdAt = 0, updatedAt = 0,
        )
        receipts += r
        lines.forEachIndexed { i, (name, qty, price) ->
            items += LineItem(id = nextId++, receiptId = r.id, position = i, name = name, quantity = qty, priceCents = price)
        }
        return r
    }

    private fun of(category: Category?, rates: ExchangeRates? = null) =
        SpendingBreakdown.of(category, receipts, items, "USD", today, rates, utc)

    @Test
    fun `groceries show what is usually bought, its usual price and where`() {
        receipt(today.minusDays(21), 1_000, "Kroger", Category.GROCERIES, "USD", Triple("bananas.", 1, 129), Triple("Milk 1 gal", 1, 349))
        receipt(today.minusDays(14), 1_500, "Kroger #512", Category.GROCERIES, "USD", Triple("BANANAS", 1, 139), Triple("Milk 1 gal", 2, 698), Triple("Bread", 1, 299))
        receipt(today.minusDays(7), 2_000, "Aldi", Category.GROCERIES, "USD", Triple("Bananas", 1, 99), Triple("Milk 1 gal", 1, 379))
        receipt(today, 9_000, "Shell", Category.FUEL)

        val b = of(Category.GROCERIES)
        assertEquals(3, b.receipts)
        assertEquals(4_500, b.totalCents)
        assertEquals(1_500, b.typicalCents)
        assertEquals(1_500, b.averageCents)
        assertEquals(1_000, b.lowestCents)
        assertEquals(2_000, b.highestCents)
        assertEquals(3, b.itemizedReceipts)

        // Spellings differ: the most used one is shown, then the latest.
        assertEquals(listOf("Milk 1 gal", "Bananas", "Bread"), b.items.map { it.name })
        val milk = b.items[0]
        assertEquals(3, milk.timesBought)
        assertEquals(4, milk.quantity)
        assertEquals(349, milk.typicalUnitCents) // 3.49, 3.49 (6.98 for two), 3.79
        assertEquals(379, milk.lastUnitCents)
        assertEquals(349, milk.lowestUnitCents)
        assertEquals(379, milk.highestUnitCents)
        assertEquals(1_426, milk.totalCents)
        assertEquals(today.minusDays(7), milk.lastBought)
        val bananas = b.items[1]
        assertEquals(3, bananas.timesBought)
        assertEquals(129, bananas.typicalUnitCents)
        assertEquals(99, bananas.lastUnitCents)

        // Store numbers don't split a store; the most spent comes first.
        assertEquals(listOf("Kroger #512", "Aldi"), b.places.map { it.merchant })
        assertEquals(2, b.places[0].visits)
        assertEquals(1_250, b.places[0].typicalCents)
        assertEquals(2_500, b.places[0].totalCents)
    }

    @Test
    fun `fuel shows the usual fill-up even without line items`() {
        receipt(today.minusDays(40), 4_000, "Shell", Category.FUEL)
        receipt(today.minusDays(20), 5_200, "Chevron", Category.FUEL)
        receipt(today, 4_600, "Shell", Category.FUEL)
        receipt(today, 1_000, "Kroger", Category.GROCERIES, "USD", Triple("Eggs", 1, 1_000))

        val b = of(Category.FUEL)
        assertEquals(4_600, b.typicalCents)
        assertTrue(b.items.isEmpty())
        assertEquals(0, b.itemizedReceipts)
        // The same monthly amount as the monthly cost card.
        val card = CostEstimate.monthly(receipts, "USD", today, null, utc).byCategory.single { it.category == Category.FUEL }
        assertEquals(card.monthlyCents, b.monthlyCents)
        assertEquals(card.timesPerMonth, b.timesPerMonth, 0.0)
    }

    @Test
    fun `everything covers all categories and matches the monthly cost`() {
        receipt(today.minusDays(30), 3_000, "Kroger", Category.GROCERIES, "USD", Triple("Eggs", 1, 300))
        receipt(today, 2_000, "CVS", Category.HEALTH, "USD", Triple("Vitamins", 2, 1_800))

        val b = of(null)
        assertEquals(2, b.receipts)
        assertEquals(5_000, b.totalCents)
        assertEquals(CostEstimate.monthly(receipts, "USD", today, null, utc).monthlyCents, b.monthlyCents)
        assertEquals(listOf("Vitamins", "Eggs"), b.items.map { it.name }) // both bought once: most spent first
        assertEquals(900, b.items[0].typicalUnitCents)
    }

    @Test
    fun `item prices follow their receipt's currency`() {
        receipt(today, 1_000, "Kroger", Category.GROCERIES, "USD", Triple("Coffee", 1, 1_000))
        receipt(today, 1_000, "Kroger", Category.GROCERIES, "USD", Triple("Coffee", 1, 1_000))
        receipt(today.minusDays(1), 500, "Carrefour", Category.GROCERIES, "EUR", Triple("Coffee", 1, 500))

        val plain = of(Category.GROCERIES)
        assertEquals(1, plain.otherCurrencyCount)
        assertEquals(2, plain.items.single().timesBought)

        val rates = ExchangeRates("USD", today, mapOf("EUR" to BigDecimal("0.5")), 0)
        val converted = of(Category.GROCERIES, rates)
        assertEquals(0, converted.otherCurrencyCount)
        val coffee = converted.items.single()
        assertEquals(3, coffee.timesBought)
        assertEquals(1_000, coffee.lowestUnitCents) // €5.00 is $10.00
        assertEquals(3_000, coffee.totalCents)
    }

    @Test
    fun `an empty category is all zeros`() {
        receipt(today, 1_000, "Kroger", Category.GROCERIES)
        val b = of(Category.TRAVEL)
        assertEquals(0, b.receipts)
        assertEquals(0, b.monthlyCents)
        assertEquals(0, b.typicalCents)
        assertTrue(b.items.isEmpty() && b.places.isEmpty())
    }

    @Test
    fun `item names match without case or punctuation`() {
        assertEquals("milk 2%", SpendingBreakdown.itemKey("MILK 2% "))
        assertEquals("bananas", SpendingBreakdown.itemKey("Bananas."))
    }
}
