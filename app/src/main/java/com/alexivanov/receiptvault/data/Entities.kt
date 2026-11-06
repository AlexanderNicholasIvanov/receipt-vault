package com.alexivanov.receiptvault.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "receipts", indices = [Index("purchasedAt")])
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    val address: String? = null,
    val phone: String? = null,
    /** [com.alexivanov.receiptvault.domain.Category] name. */
    val category: String,
    /** When the purchase happened (epoch millis), from the receipt when readable. */
    val purchasedAt: Long,
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val tipCents: Long? = null,
    val discountCents: Long? = null,
    val totalCents: Long,
    val currency: String,
    val notes: String = "",
    /** Absolute path of the stored photo inside app-private storage. */
    val imagePath: String? = null,
    /** Raw OCR text, kept for search and re-parsing. */
    val ocrText: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** Set while the receipt is in the trash (epoch millis); null for normal receipts. */
    val deletedAt: Long? = null,
    /** Comma-separated labels; see [com.alexivanov.receiptvault.domain.Tags]. */
    @ColumnInfo(defaultValue = "")
    val tags: String = "",
    /** Last day to return it to the store (epoch day), for a reminder. */
    val returnBy: Long? = null,
    /** Last day of the warranty (epoch day), for a reminder. */
    val warrantyUntil: Long? = null,
)

@Entity(
    tableName = "line_items",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("receiptId")],
)
data class LineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val position: Int,
    val name: String,
    val quantity: Int = 1,
    val priceCents: Long,
)
