package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Spreadsheet-friendly export of every receipt (one row each, line items folded into one cell). */
object CsvExport {

    private val HEADER = listOf(
        "id", "date", "time", "merchant", "category", "subtotal", "tax", "tip", "discount", "total", "currency",
        "receipt_address", "notes", "items", "tags",
    )

    fun build(receipts: List<Receipt>, items: List<LineItem>, zone: ZoneId = ZoneId.systemDefault()): String {
        val itemsByReceipt = items.groupBy { it.receiptId }
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (r in receipts) {
            val at = Instant.ofEpochMilli(r.purchasedAt).atZone(zone)
            val itemCell = itemsByReceipt[r.id].orEmpty().sortedBy { it.position }.joinToString("; ") {
                val qty = if (it.quantity > 1) " x${it.quantity}" else ""
                "${it.name}$qty = ${Money.toPlain(it.priceCents)}"
            }
            val row = listOf(
                r.id.toString(),
                at.format(DateTimeFormatter.ISO_LOCAL_DATE),
                at.format(DateTimeFormatter.ofPattern("HH:mm")),
                r.merchant,
                Category.fromName(r.category).label,
                Money.toPlain(r.subtotalCents),
                Money.toPlain(r.taxCents),
                Money.toPlain(r.tipCents),
                Money.toPlain(r.discountCents),
                Money.toPlain(r.totalCents),
                r.currency,
                r.address.orEmpty(),
                r.notes,
                itemCell,
                Tags.decode(r.tags).joinToString(", "),
            )
            sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** RFC 4180 quoting, plus a guard against spreadsheet formula injection. */
    internal fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
