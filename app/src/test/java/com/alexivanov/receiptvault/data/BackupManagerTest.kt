package com.alexivanov.receiptvault.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.domain.BackupException
import com.alexivanov.receiptvault.domain.BackupFormat
import com.alexivanov.receiptvault.scan.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BackupManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val images = ImageStore(context)
    private val databases = mutableListOf<AppDatabase>()

    private fun freshPhone(): Pair<ReceiptRepository, BackupManager> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        databases += db
        val repo = ReceiptRepository(db.receiptDao(), images)
        return repo to BackupManager(repo, images, context.cacheDir, "1.0.9", clock = { 1_000L })
    }

    @After
    fun close() = databases.forEach { it.close() }

    private fun receipt(merchant: String, total: Long, photo: File? = null) = Receipt(
        merchant = merchant, category = "DINING", purchasedAt = 1_700_000_000_000 + total, totalCents = total,
        tipCents = 300, currency = "USD", notes = "note $merchant", imagePath = photo?.absolutePath,
        ocrText = "OCR $merchant", createdAt = 5, updatedAt = 6, tags = "work,$merchant", returnBy = 20_400, warrantyUntil = 21_000,
    )

    @Test
    fun `backup then restore on a new phone brings back receipts, items and photos`() = runBlocking {
        val (oldPhone, oldBackup) = freshPhone()
        val photoBytes = byteArrayOf(9, 8, 7, 6, 5)
        val photo = File(context.filesDir, "receipts/original.jpg").apply { parentFile!!.mkdirs(); writeBytes(photoBytes) }
        oldPhone.save(receipt("Corner Cafe", 2560, photo), listOf(LineItem(receiptId = 0, position = 0, name = "Latte", quantity = 2, priceCents = 900)))
        oldPhone.save(receipt("Shell", 4000), emptyList())

        val zip = ByteArrayOutputStream().also { assertEquals(2, oldBackup.export(it)) }.toByteArray()

        val (newPhone, newBackup) = freshPhone()
        val result = newBackup.restore(ByteArrayInputStream(zip))
        assertEquals(RestoreResult(added = 2, skipped = 0), result)

        val restored = newPhone.getAll().associateBy { it.merchant }
        val cafe = restored.getValue("Corner Cafe")
        assertEquals(2560L, cafe.totalCents)
        assertEquals(300L, cafe.tipCents)
        assertEquals("note Corner Cafe", cafe.notes)
        assertEquals("OCR Corner Cafe", cafe.ocrText)
        assertEquals("work,Corner Cafe", cafe.tags)
        assertEquals(20_400L, cafe.returnBy)
        assertEquals(21_000L, cafe.warrantyUntil)
        assertEquals(5L, cafe.createdAt)
        assertEquals(listOf("Latte" to 2), newPhone.getItems(cafe.id).map { it.name to it.quantity })
        // The photo is a new file in app storage with the same bytes.
        assertNotEquals(photo.absolutePath, cafe.imagePath)
        assertArrayEquals(photoBytes, File(cafe.imagePath!!).readBytes())
        assertNull(restored.getValue("Shell").imagePath)
    }

    @Test
    fun `restoring twice adds nothing the second time`() = runBlocking {
        val (phone, backup) = freshPhone()
        phone.save(receipt("Corner Cafe", 2560), emptyList())
        val zip = ByteArrayOutputStream().also { backup.export(it) }.toByteArray()

        assertEquals(RestoreResult(added = 0, skipped = 1), backup.restore(ByteArrayInputStream(zip)))
        assertEquals(1, phone.getAll().size)
    }

    @Test
    fun `two identical receipts in a backup both come back`() = runBlocking {
        val (oldPhone, oldBackup) = freshPhone()
        oldPhone.save(receipt("Corner Cafe", 1250), emptyList())
        oldPhone.save(receipt("Corner Cafe", 1250), emptyList())
        val zip = ByteArrayOutputStream().also { assertEquals(2, oldBackup.export(it)) }.toByteArray()

        val (newPhone, newBackup) = freshPhone()
        assertEquals(RestoreResult(added = 2, skipped = 0), newBackup.restore(ByteArrayInputStream(zip)))
        assertEquals(2, newPhone.getAll().size)

        assertEquals("restoring again still adds nothing", RestoreResult(added = 0, skipped = 2), newBackup.restore(ByteArrayInputStream(zip)))
        assertEquals(2, newPhone.getAll().size)
    }

    @Test
    fun `a phone with one of two identical receipts gets only the missing one`() = runBlocking {
        val (oldPhone, oldBackup) = freshPhone()
        oldPhone.save(receipt("Corner Cafe", 1250), emptyList())
        oldPhone.save(receipt("Corner Cafe", 1250), emptyList())
        val zip = ByteArrayOutputStream().also { oldBackup.export(it) }.toByteArray()

        val (newPhone, newBackup) = freshPhone()
        newPhone.save(receipt("Corner Cafe", 1250), emptyList())
        assertEquals(RestoreResult(added = 1, skipped = 1), newBackup.restore(ByteArrayInputStream(zip)))
        assertEquals(2, newPhone.getAll().size)
    }

    @Test
    fun `files that are not backups are rejected with a readable message`() = runBlocking {
        val (_, backup) = freshPhone()
        val notZip = runCatching { backup.restore(ByteArrayInputStream("hello".toByteArray())) }.exceptionOrNull()
        assertTrue(notZip is BackupException)

        val otherApp = zipOf(BackupFormat.MANIFEST to """{"app":"something-else","receipts":[]}""".toByteArray())
        val e = runCatching { backup.restore(ByteArrayInputStream(otherApp)) }.exceptionOrNull()
        assertEquals("This isn't a Receipt Vault backup.", e?.message)
    }

    @Test
    fun `entries outside photos are ignored, so a crafted zip can't write files`() = runBlocking {
        val (phone, backup) = freshPhone()
        val manifest = """{"app":"receipt-vault","format":1,"receipts":[
            {"merchant":"Evil","purchasedAt":1,"totalCents":1,"currency":"USD","photo":"../../evil.jpg"}]}"""
        val zip = zipOf(BackupFormat.MANIFEST to manifest.toByteArray(), "../../evil.jpg" to byteArrayOf(1))

        assertEquals(RestoreResult(1, 0), backup.restore(ByteArrayInputStream(zip)))
        assertNull(phone.getAll().single().imagePath)
        assertTrue(!File(context.cacheDir.parentFile, "evil.jpg").exists())
    }

    @Test
    fun `backup zip holds the manifest first, then one entry per photo`() = runBlocking {
        val (phone, backup) = freshPhone()
        val photo = File(context.filesDir, "receipts/p.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val id = phone.save(receipt("A", 1, photo), emptyList())
        val zip = ByteArrayOutputStream().also { backup.export(it) }.toByteArray()
        val names = ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf(BackupFormat.MANIFEST, BackupFormat.photoEntry(id)), names)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()
}
