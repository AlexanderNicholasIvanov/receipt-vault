package com.alexivanov.receiptvault.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The backup file is a zip: [BackupFormat.MANIFEST] (this JSON) plus one JPEG
 * per receipt photo under `photos/`. JSON rather than a copy of the database
 * so a backup restores into any later app version, whatever its schema.
 * New fields must get defaults so older backups keep restoring.
 */
@Serializable
data class BackupManifest(
    val app: String = BackupFormat.APP_ID,
    val format: Int = BackupFormat.FORMAT,
    val appVersion: String = "",
    val exportedAt: Long = 0,
    val receipts: List<BackupReceipt> = emptyList(),
)

@Serializable
data class BackupReceipt(
    val merchant: String,
    val address: String? = null,
    val phone: String? = null,
    val category: String = Category.OTHER.name,
    val purchasedAt: Long,
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val tipCents: Long? = null,
    val discountCents: Long? = null,
    val totalCents: Long,
    val currency: String,
    val notes: String = "",
    val ocrText: String = "",
    /** Added after format 1 shipped; older backups simply have none. */
    val tags: List<String> = emptyList(),
    /** Epoch days; also added after format 1. */
    val returnBy: Long? = null,
    val warrantyUntil: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Zip entry holding the photo, e.g. "photos/12.jpg". */
    val photo: String? = null,
    val items: List<BackupItem> = emptyList(),
)

@Serializable
data class BackupItem(val name: String, val quantity: Int = 1, val priceCents: Long)

class BackupException(message: String) : Exception(message)

object BackupFormat {
    const val APP_ID = "receipt-vault"
    const val FORMAT = 1
    const val MANIFEST = "receipts.json"
    const val PHOTO_DIR = "photos/"

    /** Photo entry names we write and the only ones we read back. */
    private val PHOTO_NAME = Regex("""photos/\d{1,19}\.jpg""")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(manifest: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), manifest)

    fun decode(text: String): BackupManifest {
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("This isn't a Receipt Vault backup, or the file is damaged.")
        } catch (e: IllegalArgumentException) {
            throw BackupException("This isn't a Receipt Vault backup, or the file is damaged.")
        }
        if (manifest.app != APP_ID) throw BackupException("This isn't a Receipt Vault backup.")
        if (manifest.format > FORMAT) {
            throw BackupException("This backup was made by a newer version of Receipt Vault. Update the app, then restore.")
        }
        return manifest
    }

    fun photoEntry(id: Long): String = "${PHOTO_DIR}$id.jpg"

    fun isPhotoEntry(name: String): Boolean = PHOTO_NAME.matches(name)

    /**
     * Two receipts are the same purchase when merchant, time, total and currency
     * all match. Restore skips those, so restoring twice never duplicates.
     */
    fun sameReceiptKey(merchant: String, purchasedAt: Long, totalCents: Long, currency: String): String =
        "${merchant.trim().lowercase()}|$purchasedAt|$totalCents|${currency.uppercase()}"

    fun fileName(date: LocalDate): String = "ReceiptVault-backup-$date.zip"
}
