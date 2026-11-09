package com.alexivanov.receiptvault.ui.review

import com.alexivanov.receiptvault.domain.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

/**
 * A receipt being filled in, in the form Android keeps while the app is closed in the
 * background, so coming back resumes the form instead of reading the photo again.
 */
@Serializable
internal data class SavedDraft(
    val merchant: String,
    val category: String,
    val date: Long,
    val secondOfDay: Int?,
    val subtotal: String,
    val tax: String,
    val tip: String,
    val discount: String,
    val total: String,
    val currency: String,
    val address: String,
    val phone: String,
    val notes: String,
    val tags: String,
    val returnBy: Long?,
    val warrantyUntil: Long?,
    val items: List<ItemDraft>,
    val imagePath: String?,
    val ocrText: String,
    val notice: String?,
) {
    val draft: ReceiptDraft
        get() = ReceiptDraft(
            merchant = merchant,
            category = Category.fromName(category),
            date = LocalDate.ofEpochDay(date),
            time = secondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) },
            subtotal = subtotal,
            tax = tax,
            tip = tip,
            discount = discount,
            total = total,
            currency = currency,
            address = address,
            phone = phone,
            notes = notes,
            tags = tags,
            returnBy = returnBy?.let(LocalDate::ofEpochDay),
            warrantyUntil = warrantyUntil?.let(LocalDate::ofEpochDay),
            items = items,
        )

    fun encode(): String = json.encodeToString(this)

    companion object {
        /** Android caps saved state, and very long scans can hold a lot of text. Only search uses it. */
        private const val MAX_OCR_CHARS = 20_000

        private val json = Json { ignoreUnknownKeys = true }

        fun of(d: ReceiptDraft, imagePath: String?, ocrText: String, notice: String?) = SavedDraft(
            merchant = d.merchant,
            category = d.category.name,
            date = d.date.toEpochDay(),
            secondOfDay = d.time?.toSecondOfDay(),
            subtotal = d.subtotal,
            tax = d.tax,
            tip = d.tip,
            discount = d.discount,
            total = d.total,
            currency = d.currency,
            address = d.address,
            phone = d.phone,
            notes = d.notes,
            tags = d.tags,
            returnBy = d.returnBy?.toEpochDay(),
            warrantyUntil = d.warrantyUntil?.toEpochDay(),
            items = d.items,
            imagePath = imagePath,
            ocrText = ocrText.take(MAX_OCR_CHARS),
            notice = notice,
        )

        /** Null if it can't be read, for instance after an app update changed the format. */
        fun decode(text: String): SavedDraft? = runCatching { json.decodeFromString<SavedDraft>(text) }.getOrNull()
    }
}
