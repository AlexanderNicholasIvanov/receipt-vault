package com.alexivanov.receiptvault.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.scan.ImageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TrashTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = ReceiptRepository(db.receiptDao(), ImageStore(context))

    @After
    fun close() = db.close()

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000

    private fun photo(name: String) =
        File(context.filesDir, "receipts/$name.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

    private suspend fun add(merchant: String, photo: File? = null): Long = repo.save(
        Receipt(
            merchant = merchant, category = "DINING", purchasedAt = 1_700_000_000_000, totalCents = 1000, currency = "USD",
            imagePath = photo?.absolutePath, createdAt = 1, updatedAt = 1,
        ),
        listOf(LineItem(receiptId = 0, position = 0, name = "Item $merchant", quantity = 1, priceCents = 1000)),
    )

    @Test
    fun `trashed receipts leave the list, totals and CSV but can be restored`() = runBlocking {
        val cafe = add("Cafe")
        val shell = add("Shell")

        repo.moveToTrash(listOf(cafe), now)

        assertEquals(listOf("Shell"), repo.receipts.first().map { it.merchant })
        assertEquals(listOf("Shell"), repo.getAll().map { it.merchant })
        assertEquals(listOf("Item Shell"), repo.getAllItems().map { it.name })
        assertEquals(listOf("Cafe"), repo.trash.first().map { it.merchant })
        assertEquals(now, repo.get(cafe)!!.deletedAt)
        assertEquals(listOf(cafe), repo.pendingUndo.value)

        repo.restoreFromTrash(listOf(cafe))

        assertEquals(setOf(cafe, shell), repo.getAll().map { it.id }.toSet())
        assertTrue(repo.trash.first().isEmpty())
        assertNull(repo.get(cafe)!!.deletedAt)
    }

    @Test
    fun `undo is offered once per batch`() = runBlocking {
        val a = add("A")
        val b = add("B")
        repo.moveToTrash(listOf(a), now)
        repo.moveToTrash(listOf(b), now)
        // A stale acknowledgement must not swallow the newer batch.
        repo.undoOffered(listOf(a))
        assertEquals(listOf(b), repo.pendingUndo.value)
        repo.undoOffered(listOf(b))
        assertNull(repo.pendingUndo.value)
    }

    // A plain Application: the real app sweeps at startup on another thread, against its own empty database.
    @Test
    @Config(application = Application::class)
    fun `trashed receipts and their photos are kept however long ago they were deleted`() = runBlocking {
        // Old enough that the photo sweep would take it if no receipt claimed it.
        val oldPhoto = photo("old").apply { setLastModified(System.currentTimeMillis() - 365 * day) }
        val old = add("Old", oldPhoto)
        val recent = add("Recent")
        repo.moveToTrash(listOf(old), now - 5 * 365 * day)
        repo.moveToTrash(listOf(recent), now - day)

        // What the app does at every start: only photos that belong to no receipt are swept.
        repo.sweepOrphanImages()

        assertEquals(setOf("Old", "Recent"), repo.trash.first().map { it.merchant }.toSet())
        assertTrue(oldPhoto.exists())

        // Only the user removes them for good.
        repo.emptyTrash()
        assertTrue(repo.trash.first().isEmpty())
        assertNull(repo.get(old))
        assertFalse(oldPhoto.exists())
    }

    @Test
    fun `emptying the trash deletes only trashed receipts`() = runBlocking {
        val gonePhoto = photo("gone")
        val gone = add("Gone", gonePhoto)
        val kept = add("Kept")
        repo.moveToTrash(listOf(gone), now)

        repo.emptyTrash()

        assertNull(repo.get(gone))
        assertFalse(gonePhoto.exists())
        assertEquals(listOf(kept), repo.getAll().map { it.id })
        assertTrue(repo.trash.first().isEmpty())
    }

    @Test
    fun `restoring a backup skips receipts that are in the trash`() = runBlocking {
        val backup = BackupManager(repo, ImageStore(context), context.cacheDir, "test")
        val id = add("Cafe")
        val zip = java.io.ByteArrayOutputStream().also { backup.export(it) }.toByteArray()
        repo.moveToTrash(listOf(id), now)

        val result = backup.restore(java.io.ByteArrayInputStream(zip))

        // A copy must not reappear in the list while the original waits in the trash.
        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertTrue(repo.getAll().isEmpty())
        assertEquals(listOf(id), repo.trash.first().map { it.id })
    }
}
