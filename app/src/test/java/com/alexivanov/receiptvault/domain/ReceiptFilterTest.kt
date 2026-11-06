package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReceiptFilterTest {

    private val zone = ZoneOffset.UTC

    private fun r(id: Long, merchant: String, cents: Long, date: String, category: Category = Category.DINING) = Receipt(
        id = id, merchant = merchant, category = category.name, totalCents = cents, currency = "USD",
        purchasedAt = LocalDate.parse(date).atTime(12, 0).toInstant(zone).toEpochMilli(), createdAt = 0, updatedAt = 0,
    )

    private val cafe = r(1, "Corner Cafe", 2560, "2026-11-03")
    private val shell = r(2, "shell", 4000, "2026-10-20", Category.FUEL)
    private val market = r(3, "Aldi", 1999, "2026-11-05", Category.GROCERIES)
    private val bistro = r(4, "Bistro", 2560, "2026-09-01")
    private val all = listOf(cafe, shell, market, bistro)

    private fun ids(f: ReceiptFilter) = f.apply(all, zone).map { it.id }

    @Test
    fun `no filter keeps everything newest first`() {
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(ReceiptFilter()))
        assertEquals(0, ReceiptFilter().activeCount)
    }

    @Test
    fun `sort orders, with ties broken by date`() {
        assertEquals(listOf(4L, 2L, 1L, 3L), ids(ReceiptFilter(sort = SortOrder.OLDEST)))
        assertEquals(listOf(2L, 1L, 4L, 3L), ids(ReceiptFilter(sort = SortOrder.HIGHEST)))
        assertEquals(listOf(3L, 1L, 4L, 2L), ids(ReceiptFilter(sort = SortOrder.LOWEST)))
        // Case-insensitive: "shell" sorts after "Corner Cafe".
        assertEquals(listOf(3L, 4L, 1L, 2L), ids(ReceiptFilter(sort = SortOrder.MERCHANT)))
    }

    @Test
    fun `categories keep any of the picked ones`() {
        val f = ReceiptFilter(categories = setOf(Category.FUEL, Category.GROCERIES))
        assertEquals(listOf(3L, 2L), ids(f))
        assertEquals(1, f.activeCount)
    }

    @Test
    fun `amount bounds are inclusive and either side may be open`() {
        assertEquals(listOf(1L, 2L, 4L), ids(ReceiptFilter(minCents = 2560)))
        assertEquals(listOf(3L, 1L, 4L), ids(ReceiptFilter(maxCents = 2560)))
        assertEquals(listOf(1L, 4L), ids(ReceiptFilter(minCents = 2000, maxCents = 3000)))
        assertEquals(1, ReceiptFilter(minCents = 1).activeCount)
    }

    @Test
    fun `a backwards amount range is swapped`() {
        assertEquals(1000L to 5000L, ReceiptFilter.amountRange(5000, 1000))
        assertEquals(1000L to null, ReceiptFilter.amountRange(1000, null))
    }

    @Test
    fun `date range includes both end days`() {
        val f = ReceiptFilter(from = LocalDate.parse("2026-10-20"), to = LocalDate.parse("2026-11-03"))
        assertEquals(listOf(1L, 2L), ids(f))
        // Half a range is ignored rather than treated as open-ended.
        assertEquals(4, ids(ReceiptFilter(from = LocalDate.parse("2026-11-01"))).size)
    }

    @Test
    fun `filters combine`() {
        val f = ReceiptFilter(
            categories = setOf(Category.DINING),
            minCents = 2000,
            from = LocalDate.parse("2026-11-01"),
            to = LocalDate.parse("2026-11-30"),
        )
        assertEquals(listOf(1L), ids(f))
        assertEquals(3, f.activeCount)
    }
}
