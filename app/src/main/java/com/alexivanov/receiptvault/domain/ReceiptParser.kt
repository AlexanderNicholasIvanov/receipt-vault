package com.alexivanov.receiptvault.domain

import java.time.LocalDate
import java.time.LocalTime

data class ParsedItem(val name: String, val quantity: Int, val priceCents: Long)

/** Everything the parser could pull off a receipt. Any field may be missing; the user reviews it. */
data class ParsedReceipt(
    val merchant: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val tipCents: Long? = null,
    val discountCents: Long? = null,
    val totalCents: Long? = null,
    val currencyCode: String? = null,
    val paymentMethod: String? = null,
    val cardLast4: String? = null,
    val items: List<ParsedItem> = emptyList(),
    val category: Category = Category.OTHER,
)

/**
 * Heuristic receipt reader. Input is the receipt's rows top to bottom (see
 * [LineAssembler]); output is a best guess the user confirms before saving.
 *
 * Pure Kotlin with no Android dependencies so it is fully unit tested.
 */
object ReceiptParser {

    private enum class Kind { SUBTOTAL, TAX, TIP, DISCOUNT, TOTAL, TENDER, NONE }

    private data class Row(
        val index: Int,
        val text: String,
        val lower: String,
        val height: Int,
        val amounts: List<Money.Found>,
        val kind: Kind,
        val inTipGuide: Boolean,
    ) {
        /** The price column is the right-most amount. */
        val amount: Long? get() = amounts.lastOrNull()?.cents

        /** Text left of the first amount, i.e. the row's label. */
        val label: String get() = (amounts.firstOrNull()?.let { text.substring(0, it.start) } ?: text).trim()
    }

    fun parse(rows: List<ReceiptRow>, today: LocalDate = LocalDate.now(), preferDayFirst: Boolean = false): ParsedReceipt {
        val normalized = rows.map { it.copy(text = normalizeOcr(it.text)) }.filter { it.text.isNotBlank() }
        val classified = classify(normalized)
        val fullText = normalized.joinToString("\n") { it.text }

        val merchant = findMerchant(classified)
        val (subtotal, tax, tip, discount, total) = findAmounts(classified)
        val (date, time) = findDateTime(normalized.map { it.text }, today, preferDayFirst)
        val (payment, last4) = findPayment(fullText)

        return ParsedReceipt(
            merchant = merchant,
            address = findAddress(normalized.map { it.text }),
            phone = PHONE.find(fullText)?.value?.trim(),
            date = date,
            time = time,
            subtotalCents = subtotal,
            taxCents = tax,
            tipCents = tip,
            discountCents = discount,
            totalCents = total,
            currencyCode = findCurrency(fullText),
            paymentMethod = payment,
            cardLast4 = last4,
            items = findItems(classified),
            category = Category.guess(merchant, fullText),
        )
    }

    /** Convenience for plain text (tests, re-parsing stored OCR text). */
    fun parseText(text: String, today: LocalDate = LocalDate.now(), preferDayFirst: Boolean = false): ParsedReceipt =
        parse(text.lines().map { ReceiptRow(it, 0) }, today, preferDayFirst)

    // ---------------------------------------------------------------- classification

    private val SUBTOTAL_RE = Regex("""\bsub\s*-?\s*tota?l\b|\bsubtotal\b|\bnet\s+(amount|total|sales)\b|\b(pre-?tax|before\s+tax)\b|\bmerchandise\s+total\b|\bfood\s*(&|and)?\s*bev\w*\s+total\b|\bitems?\s+total\b""")
    private val TAX_RE = Regex("""\b(tax|taxes|vat|gst|hst|pst|qst|iva|mwst)\b""")
    private val TAX_INCLUDED_RE = Regex("""\b(incl\.?|including|inc\.?|w/)\s*(sales\s+)?(tax|vat|gst)\b""")
    private val TIP_RE = Regex("""\b(tip|tips|gratuity|grat)\b""")
    private val TIP_GUIDE_RE = Regex("""\b(suggest\w*|tip\s+guide|guide|for\s+your\s+convenience|tip\s+calculat\w*)\b""")
    private val DISCOUNT_RE = Regex("""\b(discount|savings|coupon|you\s+saved|promo\w*|markdown|rebate)\b|%\s*off\b""")
    private val TOTAL_RE = Regex("""\b(grand\s+total|total|amount\s+due|balance\s+due|total\s+due|amount\s+paid|to\s+pay|amt\s+due|total\s+amount|amount|balance|sale\s+amount|purchase)\b""")
    private val TOTAL_EXCLUDE_RE = Regex("""\b(total\s+(savings|saved|items?|qty|quantity|units|points|discounts?|number)|items?\s+sold|#\s*of\s+items|points|rewards?)\b""")
    private val TENDER_RE = Regex("""\b(cash|change|tendered|tend|change\s+due|payment|paid|visa|mastercard|master\s*card|amex|american\s+express|discover|debit|credit|card)\b""")

    private fun classify(rows: List<ReceiptRow>): List<Row> {
        var tipGuideUntil = -1
        return rows.mapIndexed { i, r ->
            val lower = r.text.lowercase()
            val amounts = Money.findAll(r.text)
            if (TIP_GUIDE_RE.containsMatchIn(lower) && (TIP_RE.containsMatchIn(lower) || lower.contains('%'))) {
                tipGuideUntil = i + 6
            }
            // Suggested-tip tables: "18% = $8.10  Total $53.10". Any percent row inside one is noise.
            val inGuide = i <= tipGuideUntil && (lower.contains('%') || TIP_GUIDE_RE.containsMatchIn(lower))
            Row(i, r.text, lower, r.height, amounts, kindOf(lower), inGuide)
        }
    }

    private fun kindOf(lower: String): Kind {
        val label = lower.replace(Regex("""\d+([.,]\d+)?"""), " ")
        return when {
            SUBTOTAL_RE.containsMatchIn(label) -> Kind.SUBTOTAL
            TIP_RE.containsMatchIn(label) && !Regex("""\btotal\b""").containsMatchIn(label) -> Kind.TIP
            TAX_INCLUDED_RE.containsMatchIn(label) && TOTAL_RE.containsMatchIn(label) -> Kind.TOTAL
            TAX_RE.containsMatchIn(label) -> Kind.TAX
            DISCOUNT_RE.containsMatchIn(label) -> Kind.DISCOUNT
            TOTAL_RE.containsMatchIn(label) && !TOTAL_EXCLUDE_RE.containsMatchIn(label) &&
                !Regex("""\b(cash|change|tendered)\b""").containsMatchIn(label) -> Kind.TOTAL
            TENDER_RE.containsMatchIn(label) -> Kind.TENDER
            else -> Kind.NONE
        }
    }

    /** The amount for a labelled row: on the row itself, else alone on the next row. */
    private fun amountFor(row: Row, all: List<Row>): Long? {
        row.amount?.let { return it }
        val next = all.getOrNull(row.index + 1) ?: return null
        return if (next.kind == Kind.NONE && next.amounts.size == 1 && next.label.count { it.isLetter() } < 3) next.amount else null
    }

    // ---------------------------------------------------------------- amounts

    private data class Amounts(val subtotal: Long?, val tax: Long?, val tip: Long?, val discount: Long?, val total: Long?)

    private fun findAmounts(rows: List<Row>): Amounts {
        val usable = rows.filterNot { it.inTipGuide }

        val subtotal = usable.filter { it.kind == Kind.SUBTOTAL }.firstNotNullOfOrNull { amountFor(it, rows) }?.takeIf { it > 0 }

        // Several tax lines (state + city) add up; a "total tax" row replaces them.
        val taxRows = usable.filter { it.kind == Kind.TAX }.mapNotNull { r -> amountFor(r, rows)?.let { r to it } }
            .filter { it.second >= 0 }
        val tax = taxRows.firstOrNull { Regex("""\btotal\b""").containsMatchIn(it.first.lower) }?.second
            ?: taxRows.takeIf { it.isNotEmpty() }?.sumOf { it.second }

        val tip = usable.filter { it.kind == Kind.TIP && !it.lower.contains('%') }
            .firstNotNullOfOrNull { amountFor(it, rows) }
            ?: usable.filter { it.kind == Kind.TIP && it.amounts.size == 1 }.firstNotNullOfOrNull { it.amount }
        val tipValue = tip?.takeIf { it > 0 }

        val discountRows = usable.filter { it.kind == Kind.DISCOUNT }.mapNotNull { amountFor(it, rows) }.map { kotlin.math.abs(it) }
        val discount = usable.firstOrNull { it.kind == Kind.DISCOUNT && Regex("""total|you\s+saved""").containsMatchIn(it.lower) }
            ?.let { amountFor(it, rows) }?.let { kotlin.math.abs(it) }
            ?: discountRows.takeIf { it.isNotEmpty() }?.sum()

        val totalCandidates = usable.filter { it.kind == Kind.TOTAL }.mapNotNull { amountFor(it, rows) }.filter { it > 0 }
        val grand = usable.filter { it.kind == Kind.TOTAL && it.lower.contains("grand") }.firstNotNullOfOrNull { amountFor(it, rows) }
        var total: Long? = grand ?: totalCandidates.maxOrNull()

        if (total == null) {
            // A card line "VISA  45.00" usually carries the charged amount.
            total = usable.filter { it.kind == Kind.TENDER && !Regex("""\b(cash|change|tendered)\b""").containsMatchIn(it.lower) }
                .firstNotNullOfOrNull { it.amount }?.takeIf { it > 0 }
        }
        if (total == null && subtotal != null) {
            total = subtotal + (tax ?: 0) + (tipValue ?: 0)
        }
        if (total == null) {
            // Last resort: the largest amount not on a cash/change line.
            total = usable.filter { it.kind != Kind.TENDER && it.kind != Kind.DISCOUNT }
                .flatMap { r -> r.amounts.map { it.cents } }.filter { it > 0 }.maxOrNull()
        }

        var finalTip = tipValue
        if (finalTip == null && subtotal != null && totalCandidates.distinct().size >= 2) {
            // "Total 45.00 ... (handwritten tip) ... Total 54.00": when the smaller total equals
            // subtotal + tax, the difference to the larger one is the tip.
            val low = totalCandidates.min()
            val high = totalCandidates.max()
            if (kotlin.math.abs(low - (subtotal + (tax ?: 0))) <= 1 && high > low) finalTip = high - low
        }

        val finalSubtotal = subtotal ?: total?.let { t ->
            if (tax != null) (t - tax - (finalTip ?: 0)).takeIf { it > 0 } else null
        }

        return Amounts(finalSubtotal, tax, finalTip, discount?.takeIf { it > 0 }, total)
    }

    // ---------------------------------------------------------------- merchant

    private val MERCHANT_SKIP = Regex("""\b(receipt|welcome|thank|thanks|store\s*#|store\s+no|tel|phone|fax|www|http|\.com|order|server|table|guest|check\s*#|cashier|invoice|transaction|trans\b|terminal|reg\b|register|date|time|copy|customer|merchant\s+id|mid\b|tid\b|ticket|host|station)\b""")

    private fun findMerchant(rows: List<Row>): String? {
        val head = rows.take(8)
        val candidates = head.filter { r ->
            val letters = r.text.count { it.isLetter() }
            letters >= 3 &&
                letters >= r.text.count { it.isDigit() } &&
                r.amounts.isEmpty() &&
                r.kind == Kind.NONE &&
                !MERCHANT_SKIP.containsMatchIn(r.lower) &&
                !PHONE.containsMatchIn(r.text) &&
                !STREET.containsMatchIn(r.lower) &&
                !CITY_STATE_ZIP.containsMatchIn(r.text) &&
                DATE_PATTERNS.none { it.containsMatchIn(r.lower) }
        }
        if (candidates.isEmpty()) return null
        val heights = rows.map { it.height }.filter { it > 0 }.sorted()
        val median = heights.getOrNull(heights.size / 2) ?: 0
        // Store names are usually printed larger than the body text.
        val tall = if (median > 0) candidates.take(5).filter { it.height >= median * 1.25f }.maxByOrNull { it.height } else null
        return tidyName((tall ?: candidates.first()).text)
    }

    private fun tidyName(raw: String): String {
        val trimmed = raw.trim().trim('*', '-', '=', '_', '.', ',', ':', '#', ' ').replace(Regex("""\s{2,}"""), " ")
        val letters = trimmed.filter { it.isLetter() }
        return if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            trimmed.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
        } else {
            trimmed
        }
    }

    // ---------------------------------------------------------------- address / phone

    private val PHONE = Regex("""(?<!\d)(\+?1[\s.-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]\d{4}(?!\d)""")
    private val STREET = Regex("""^\s*\d{1,6}\s+([a-z0-9.'-]+\s+){0,5}(st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|way|hwy|highway|pkwy|parkway|ct|court|pl|place|plaza|sq|square|cir|circle|trl|trail|ter|terrace|pike|route|rte)\b""")
    private val CITY_STATE_ZIP = Regex("""[A-Za-z][A-Za-z .'-]+,?\s+[A-Z]{2}\s+\d{5}(-\d{4})?\b""")

    private fun findAddress(lines: List<String>): String? {
        val head = lines.take(12)
        val streetIdx = head.indexOfFirst { STREET.containsMatchIn(it.lowercase()) }
        val cityIdx = head.indexOfFirst { CITY_STATE_ZIP.containsMatchIn(it) }
        val parts = buildList {
            if (streetIdx >= 0) add(head[streetIdx].trim())
            if (cityIdx >= 0 && cityIdx != streetIdx) add(CITY_STATE_ZIP.find(head[cityIdx])!!.value.trim())
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replace(Regex("""\s{2,}"""), " ")
    }

    // ---------------------------------------------------------------- date / time

    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
    private val ISO_DATE = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val NUMERIC_DATE = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)""")
    private val MONTH_FIRST = Regex("""\b($MONTHS)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""")
    private val DAY_FIRST = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s-]+($MONTHS)[a-z]*\.?,?[\s-]+(\d{4}|\d{2})\b""")
    private val DATE_PATTERNS = listOf(ISO_DATE, NUMERIC_DATE, MONTH_FIRST, DAY_FIRST)
    private val TIME = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?\s*([ap])?\.?\s?m?\.?(?![a-z])""", RegexOption.IGNORE_CASE)

    private fun monthIndex(name: String): Int = MONTHS.split('|').indexOfFirst { name.startsWith(it) }.let {
        // "sept" sits after "sep" in the list; both are September.
        if (it >= 9) it else it + 1
    }

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length == 2) 2000 + it else it }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun findDateTime(lines: List<String>, today: LocalDate, preferDayFirst: Boolean): Pair<LocalDate?, LocalTime?> {
        fun plausible(d: LocalDate?) = d != null && !d.isAfter(today.plusDays(1)) && d.isAfter(today.minusYears(20))
        var date: LocalDate? = null
        for (line in lines) {
            val lower = line.lowercase()
            // Return-by / expiry dates are not the purchase date.
            if (Regex("""\b(return|exp|expires|expiry|valid|due)\b""").containsMatchIn(lower)) continue
            val candidates = sequence {
                ISO_DATE.findAll(lower).forEach { m ->
                    yield(safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
                }
                MONTH_FIRST.findAll(lower).forEach { m ->
                    yield(safeDate(m.groupValues[3].toInt(), monthIndex(m.groupValues[1]), m.groupValues[2].toInt()))
                }
                DAY_FIRST.findAll(lower).forEach { m ->
                    yield(safeDate(year(m.groupValues[3]), monthIndex(m.groupValues[2]), m.groupValues[1].toInt()))
                }
                NUMERIC_DATE.findAll(lower).forEach { m ->
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toInt()
                    val y = year(m.groupValues[3])
                    val dayFirst = when {
                        a > 12 -> true
                        b > 12 -> false
                        else -> preferDayFirst
                    }
                    yield(if (dayFirst) safeDate(y, b, a) else safeDate(y, a, b))
                }
            }
            date = candidates.firstOrNull { plausible(it) }
            if (date != null) break
        }

        var time: LocalTime? = null
        for (line in lines) {
            val m = TIME.find(line) ?: continue
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            when (m.groupValues[4].lowercase()) {
                "p" -> if (h < 12) h += 12
                "a" -> if (h == 12) h = 0
            }
            time = runCatching { LocalTime.of(h, min) }.getOrNull()
            if (time != null) break
        }
        return date to time
    }

    // ---------------------------------------------------------------- payment / currency

    private val CARD_BRANDS = listOf(
        "american express" to "Amex", "amex" to "Amex", "visa" to "Visa", "mastercard" to "Mastercard",
        "master card" to "Mastercard", "discover" to "Discover", "apple pay" to "Apple Pay",
        "google pay" to "Google Pay", "samsung pay" to "Samsung Pay", "debit" to "Debit", "cash" to "Cash",
    )
    private val LAST4 = Regex("""(?:[*xX#•]{2,}[\s-]*)+(\d{4})(?!\d)|ending\s+(?:in\s+)?(\d{4})(?!\d)|acct(?:ount)?\s*#?\s*:?\s*\D{0,8}(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)

    private fun findPayment(text: String): Pair<String?, String?> {
        val lower = text.lowercase()
        val method = CARD_BRANDS.firstOrNull { (key, _) -> Regex("""\b${Regex.escape(key)}\b""").containsMatchIn(lower) }?.second
        val last4 = LAST4.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        return (method ?: if (last4 != null) "Card" else null) to last4
    }

    private fun findCurrency(text: String): String? = when {
        text.contains('€') || Regex("""\bEUR\b""").containsMatchIn(text) -> "EUR"
        text.contains('£') || Regex("""\bGBP\b""").containsMatchIn(text) -> "GBP"
        Regex("""\bCAD\b""").containsMatchIn(text) -> "CAD"
        Regex("""\bAUD\b""").containsMatchIn(text) -> "AUD"
        text.contains('¥') || Regex("""\bJPY\b""").containsMatchIn(text) -> "JPY"
        Regex("""\bUSD\b""").containsMatchIn(text) -> "USD"
        else -> null
    }

    // ---------------------------------------------------------------- line items

    private val QTY_PREFIX = Regex("""^\s*(\d{1,3})\s*(?:x|@|×)\s+""", RegexOption.IGNORE_CASE)
    private val QTY_AT = Regex("""\b(\d{1,3})\s*@\s*[$]?\d""")
    private val SKU_PREFIX = Regex("""^\s*\d{4,}\s+""")
    private val TRAILING_FLAGS = Regex("""(\s+[A-Z]{1,2})+\s*$""")

    private fun findItems(rows: List<Row>): List<ParsedItem> {
        val stop = rows.indexOfFirst { it.kind in setOf(Kind.SUBTOTAL, Kind.TOTAL, Kind.TAX) && it.amount != null }
        val body = if (stop >= 0) rows.subList(0, stop) else emptyList()
        return body.asSequence()
            .filter { it.kind == Kind.NONE && it.amounts.isNotEmpty() && !it.inTipGuide }
            .filter { it.label.count { c -> c.isLetter() } >= 2 }
            .filterNot { PHONE.containsMatchIn(it.text) || DATE_PATTERNS.any { p -> p.containsMatchIn(it.lower) } }
            .mapNotNull { r ->
                var name = r.label
                var qty = 1
                QTY_PREFIX.find(name)?.let { qty = it.groupValues[1].toInt(); name = name.substring(it.range.last + 1) }
                QTY_AT.find(r.text)?.let { qty = it.groupValues[1].toInt() }
                name = name.replace(Regex("""\s*\d{1,3}\s*@.*$"""), "")
                name = SKU_PREFIX.replace(name, "")
                name = name.trim().trimEnd('.', ':', '-', '$', '@').trim()
                if (name.length > 3) name = TRAILING_FLAGS.replace(name, "").ifBlank { name }
                val price = r.amount ?: return@mapNotNull null
                if (name.count { it.isLetter() } < 2) null else ParsedItem(tidyName(name), qty.coerceIn(1, 999), price)
            }
            .take(80)
            .toList()
    }

    // ---------------------------------------------------------------- OCR cleanup

    private fun normalizeOcr(text: String): String = text
        // "12. 34" / "12 .34" -> "12.34"
        .replace(Regex("""(\d)\s?([.,])\s(\d{2})(?!\d)"""), "$1$2$3")
        .replace(Regex("""(\d)\s([.,])(\d{2})(?!\d)"""), "$1$2$3")
        // Letter O / lowercase l misread inside numbers: "1O.5O" -> "10.50"
        .replace(Regex("""(?<=\d)[Oo](?=[\d.,])|(?<=[\d.,])[Oo](?=\d|\b)""")) { "0" }
        .replace(Regex("""\s+"""), " ")
        .trim()
}
