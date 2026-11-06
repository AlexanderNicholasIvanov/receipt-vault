package com.alexivanov.receiptvault.data

import com.alexivanov.receiptvault.domain.BackupException
import com.alexivanov.receiptvault.domain.BackupFormat
import com.alexivanov.receiptvault.domain.BackupItem
import com.alexivanov.receiptvault.domain.BackupManifest
import com.alexivanov.receiptvault.domain.BackupReceipt
import com.alexivanov.receiptvault.scan.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestoreResult(val added: Int, val skipped: Int)

/**
 * Writes every receipt, its line items and its photo into one zip the user
 * keeps wherever they like (Drive, a USB stick…), and restores from it.
 * Restore only adds: receipts already on the phone are left alone.
 */
class BackupManager(
    private val receipts: ReceiptRepository,
    private val images: ImageStore,
    private val scratchDir: File,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Returns how many receipts were written. */
    suspend fun export(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val all = receipts.getAll()
        val itemsByReceipt = receipts.getAllItems().groupBy { it.receiptId }
        val photos = mutableListOf<Pair<String, File>>()
        val entries = all.map { r ->
            val photo = r.imagePath?.let(::File)?.takeIf { it.isFile }?.let { file ->
                BackupFormat.photoEntry(r.id).also { photos += it to file }
            }
            BackupReceipt(
                merchant = r.merchant, address = r.address, phone = r.phone, category = r.category,
                purchasedAt = r.purchasedAt, subtotalCents = r.subtotalCents, taxCents = r.taxCents,
                tipCents = r.tipCents, discountCents = r.discountCents, totalCents = r.totalCents,
                currency = r.currency, notes = r.notes, ocrText = r.ocrText,
                createdAt = r.createdAt, updatedAt = r.updatedAt, photo = photo,
                items = itemsByReceipt[r.id].orEmpty().sortedBy { it.position }
                    .map { BackupItem(it.name, it.quantity, it.priceCents) },
            )
        }
        val manifest = BackupManifest(appVersion = appVersion, exportedAt = clock(), receipts = entries)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
            zip.write(BackupFormat.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, file) in photos) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        entries.size
    }

    suspend fun restore(input: InputStream): RestoreResult = withContext(Dispatchers.IO) {
        val spool = File(scratchDir, "restore-${clock()}").apply { deleteRecursively(); mkdirs() }
        try {
            var manifestText: String? = null
            val photos = mutableMapOf<String, File>()
            var entries = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw BackupException("The backup has too many files.")
                    when {
                        entry.isDirectory -> Unit
                        entry.name == BackupFormat.MANIFEST ->
                            manifestText = zip.readCapped(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                        // Entry names are only ever used as map keys, never as paths, so a
                        // crafted name can't write outside the spool folder.
                        BackupFormat.isPhotoEntry(entry.name) -> {
                            val target = File(spool, "${photos.size}.jpg")
                            target.outputStream().use { zip.copyCapped(it, MAX_PHOTO_BYTES) }
                            photos[entry.name] = target
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = BackupFormat.decode(
                manifestText ?: throw BackupException("This isn't a Receipt Vault backup (receipts.json is missing)."),
            )

            val existing = receipts.getAll().mapTo(HashSet()) {
                BackupFormat.sameReceiptKey(it.merchant, it.purchasedAt, it.totalCents, it.currency)
            }
            var added = 0
            var skipped = 0
            for (b in manifest.receipts) {
                val key = BackupFormat.sameReceiptKey(b.merchant, b.purchasedAt, b.totalCents, b.currency)
                if (!existing.add(key)) {
                    skipped++
                    continue
                }
                val imagePath = b.photo?.let(photos::get)?.let { images.adopt(it).absolutePath }
                val now = clock()
                val receipt = Receipt(
                    merchant = b.merchant, address = b.address, phone = b.phone, category = b.category,
                    purchasedAt = b.purchasedAt, subtotalCents = b.subtotalCents, taxCents = b.taxCents,
                    tipCents = b.tipCents, discountCents = b.discountCents, totalCents = b.totalCents,
                    currency = b.currency, notes = b.notes, imagePath = imagePath, ocrText = b.ocrText,
                    createdAt = b.createdAt.takeIf { it > 0 } ?: now, updatedAt = b.updatedAt.takeIf { it > 0 } ?: now,
                )
                val items = b.items.map { LineItem(receiptId = 0, position = 0, name = it.name, quantity = it.quantity, priceCents = it.priceCents) }
                receipts.save(receipt, items)
                added++
            }
            RestoreResult(added, skipped)
        } finally {
            spool.deleteRecursively()
        }
    }

    private fun InputStream.readCapped(max: Long): ByteArray =
        java.io.ByteArrayOutputStream().also { copyCapped(it, max) }.toByteArray()

    private fun InputStream.copyCapped(out: OutputStream, max: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw BackupException("A file in the backup is larger than expected. Is it damaged?")
            out.write(buffer, 0, n)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_MANIFEST_BYTES = 200L * 1024 * 1024
        const val MAX_PHOTO_BYTES = 60L * 1024 * 1024
    }
}
