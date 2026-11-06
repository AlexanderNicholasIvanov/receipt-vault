package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReceiptParserTest {

    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun `grocery receipt with items, tax and card`() {
        val text = """
            TRADER JOE'S
            1234 Market Street
            San Francisco, CA 94103
            (415) 555-0134
            BANANAS                0.29
            ORGANIC MILK           4.49
            2 @ 3.99
            GREEK YOGURT           7.98
            SUBTOTAL              12.76
            TAX                    0.42
            TOTAL                 13.18
            VISA ************4821 13.18
            11/02/2026 05:41 PM
            THANK YOU FOR SHOPPING
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals("Trader Joe's", r.merchant)
        assertEquals("1234 Market Street, San Francisco, CA 94103", r.address)
        assertEquals("(415) 555-0134", r.phone)
        assertEquals(1276L, r.subtotalCents)
        assertEquals(42L, r.taxCents)
        assertEquals(1318L, r.totalCents)
        assertNull(r.tipCents)
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(LocalTime.of(17, 41), r.time)
        assertEquals("Visa", r.paymentMethod)
        assertEquals("4821", r.cardLast4)
        assertEquals(Category.GROCERIES, r.category)
        assertEquals(listOf("Bananas", "Organic Milk", "Greek Yogurt"), r.items.map { it.name })
        assertEquals(798L, r.items.last().priceCents)
    }

    @Test
    fun `restaurant receipt ignores the suggested tip table`() {
        val text = """
            The Blue Door Bistro
            88 Harbor Ave
            Portland, ME 04101
            Server: Dana   Table 12
            Date: Oct 30, 2026  7:12 PM
            Burger                 16.00
            Caesar Salad           12.00
            Iced Tea                3.50
            Subtotal               31.50
            Sales Tax               2.52
            Total                  34.02
            Suggested Gratuity
            18% = 5.67  Total 39.69
            20% = 6.30  Total 40.32
            22% = 6.93  Total 40.95
            Tip                     6.50
            Grand Total            40.52
            MASTERCARD XXXX1111
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals("The Blue Door Bistro", r.merchant)
        assertEquals(3150L, r.subtotalCents)
        assertEquals(252L, r.taxCents)
        assertEquals(650L, r.tipCents)
        assertEquals(4052L, r.totalCents)
        assertEquals(LocalDate.of(2026, 10, 30), r.date)
        assertEquals(LocalTime.of(19, 12), r.time)
        assertEquals("Mastercard", r.paymentMethod)
        assertEquals("1111", r.cardLast4)
        assertEquals(Category.DINING, r.category)
        assertEquals(3, r.items.size)
    }

    @Test
    fun `tip is inferred from two totals when the tip line was handwritten`() {
        val text = """
            LUIGI'S PIZZERIA
            Subtotal 40.00
            Tax 3.20
            Total 43.20
            Tip ________
            Total 52.20
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals(900L, r.tipCents)
        assertEquals(5220L, r.totalCents)
        assertEquals(4000L, r.subtotalCents)
    }

    @Test
    fun `label and amount split across rows are paired`() {
        val text = """
            CORNER MART
            TOTAL
            $ 9.87
        """.trimIndent()

        assertEquals(987L, ReceiptParser.parseText(text, today).totalCents)
    }

    @Test
    fun `gas station receipt`() {
        val text = """
            SHELL
            Pump # 4
            Unleaded  10.512 GAL
            Price/Gal 3.49
            FUEL TOTAL $36.69
            DEBIT
            2026-11-05 08:03
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals("Shell", r.merchant)
        assertEquals(3669L, r.totalCents)
        assertEquals(Category.FUEL, r.category)
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
        assertEquals("Debit", r.paymentMethod)
    }

    @Test
    fun `european receipt with comma decimals and day first date`() {
        val text = """
            Café Lumière
            Rue de Rivoli 10, Paris
            2 x Croissant   4,80
            Café crème      3,50
            TOTAL TTC       8,30 €
            dont TVA 10%    0,75
            Le 03.11.2026 à 09:15
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today, preferDayFirst = true)

        assertEquals("Café Lumière", r.merchant)
        assertEquals(830L, r.totalCents)
        assertEquals("EUR", r.currencyCode)
        assertEquals(LocalDate.of(2026, 11, 3), r.date)
        assertEquals(2, r.items.first { it.name == "Croissant" }.quantity)
    }

    @Test
    fun `total savings is a discount, not the total`() {
        val text = """
            TARGET
            T-SHIRT        12.00
            COUPON         -2.00
            SUBTOTAL       10.00
            TAX             0.80
            TOTAL          10.80
            TOTAL SAVINGS   2.00
            CASH           20.00
            CHANGE          9.20
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals(1080L, r.totalCents)
        assertEquals(200L, r.discountCents)
        assertEquals("Cash", r.paymentMethod)
        assertEquals(Category.SHOPPING, r.category)
    }

    @Test
    fun `return-by and future dates are not the purchase date`() {
        val text = """
            STORE
            RETURN BY 12/30/2026
            10/01/2026
            TOTAL 5.00
        """.trimIndent()

        assertEquals(LocalDate.of(2026, 10, 1), ReceiptParser.parseText(text, today).date)
    }

    @Test
    fun `missing total falls back to subtotal plus tax`() {
        val r = ReceiptParser.parseText("SHOP\nSUBTOTAL 10.00\nTAX 1.00", today)
        assertEquals(1100L, r.totalCents)
    }

    @Test
    fun `nothing recognizable yields an empty result rather than a crash`() {
        val r = ReceiptParser.parseText("", today)
        assertNull(r.totalCents)
        assertNull(r.merchant)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `assembler joins label and price printed in separate columns`() {
        val rows = LineAssembler.assemble(
            listOf(
                OcrLine("$13.18", left = 400, top = 302, right = 480, bottom = 330),
                OcrLine("TOTAL", left = 20, top = 300, right = 120, bottom = 328),
                OcrLine("TAX", left = 20, top = 260, right = 80, bottom = 288),
                OcrLine("0.42", left = 410, top = 262, right = 480, bottom = 290),
            ),
        )
        assertEquals(listOf("TAX  0.42", "TOTAL  $13.18"), rows.map { it.text })
        assertEquals(1318L, ReceiptParser.parse(rows, today).totalCents)
    }
}
