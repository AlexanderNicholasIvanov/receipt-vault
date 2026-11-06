package com.alexivanov.receiptvault.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** File names for receipt photos saved to the phone's Pictures folder. */
object ImageNames {

    private const val MAX_MERCHANT_CHARS = 40

    /**
     * "2026-11-06 Corner Cafe.jpg": date first so a file manager sorts them in
     * order. Android adds " (1)" itself if the same name is saved twice.
     */
    fun forReceipt(merchant: String, purchasedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(purchasedAt).atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val name = merchant
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.')
            .take(MAX_MERCHANT_CHARS)
            .trimEnd(' ', '.')
            .ifEmpty { "Receipt" }
        return "$date $name.jpg"
    }
}
