package com.alexivanov.receiptvault.domain

import com.alexivanov.receiptvault.data.Receipt
import java.time.ZoneId

/**
 * Spots a receipt that is probably already saved: same total and currency,
 * same day, and a merchant name that matches (ignoring case, spaces and
 * punctuation, and allowing "Corner Cafe" vs "Corner Cafe #12"). Scanning the
 * same paper twice, or importing the emailed PDF of a receipt you already
 * photographed, is the usual cause.
 */
object Duplicates {

    const val UNKNOWN_MERCHANT = "Unknown merchant"

    fun find(candidate: Receipt, saved: List<Receipt>, zone: ZoneId = ZoneId.systemDefault()): Receipt? {
        val day = Insights.localDate(candidate.purchasedAt, zone)
        return saved.firstOrNull { r ->
            r.id != candidate.id &&
                r.deletedAt == null &&
                r.totalCents == candidate.totalCents &&
                r.currency == candidate.currency &&
                Insights.localDate(r.purchasedAt, zone) == day &&
                sameMerchant(r.merchant, candidate.merchant)
        }
    }

    fun sameMerchant(a: String, b: String): Boolean {
        val x = key(a)
        val y = key(b)
        // A name the OCR couldn't read says nothing either way; amount and day decide.
        if (x.isEmpty() || y.isEmpty() || x == UNKNOWN_KEY || y == UNKNOWN_KEY) return true
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 4 && long.startsWith(short)
    }

    private val UNKNOWN_KEY = key(UNKNOWN_MERCHANT)

    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
}
