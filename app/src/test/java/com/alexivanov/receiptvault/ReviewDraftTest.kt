package com.alexivanov.receiptvault

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.core.graphics.createBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.AppDatabase
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.data.ReceiptRepository
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.scan.ImageStore
import com.alexivanov.receiptvault.ui.review.ItemDraft
import com.alexivanov.receiptvault.ui.review.ReceiptDraft
import com.alexivanov.receiptvault.ui.review.ReviewState
import com.alexivanov.receiptvault.ui.review.ReviewViewModel
import com.alexivanov.receiptvault.ui.review.SavedDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Android closing the app behind the user's back mustn't lose a receipt being filled in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewDraftTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = (context as ReceiptVaultApp).container
    private val photos get() = File(context.filesDir, "receipts").listFiles().orEmpty().toList()

    private fun editing(vm: ReviewViewModel): ReviewState.Editing {
        waitFor { vm.state.value is ReviewState.Editing }
        return vm.state.value as ReviewState.Editing
    }

    private fun photoUri(): Uri {
        val file = File(context.cacheDir, "shared.jpg")
        file.outputStream().use { createBitmap(400, 600).apply { eraseColor(Color.LTGRAY) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun `a draft survives encoding field by field`() {
        val draft = ReceiptDraft(
            merchant = "Corner Cafe", category = Category.DINING, date = LocalDate.of(2026, 11, 5), time = LocalTime.of(8, 30),
            subtotal = "10.00", tax = "0.83", tip = "2.00", discount = "1.00", total = "11.83", currency = "EUR",
            address = "1 Main St", phone = "555", notes = "lunch", tags = "work, trip",
            returnBy = LocalDate.of(2026, 12, 1), warrantyUntil = LocalDate.of(2027, 1, 1),
            items = listOf(ItemDraft(3, "Latte", "2", "4.50")),
        )
        val back = SavedDraft.decode(SavedDraft.of(draft, "/p.jpg", "OCR", "Check it").encode())!!
        assertEquals(draft, back.draft)
        assertEquals("/p.jpg", back.imagePath)
        assertEquals("OCR", back.ocrText)
        assertEquals("Check it", back.notice)
        assertNull("unreadable state is ignored", SavedDraft.decode("{not json"))
    }

    @Test
    fun `a new photo receipt resumes with its edits and without reading the photo again`() {
        val uri = photoUri()
        val handle = SavedStateHandle(mapOf("uri" to uri.toString()))
        val first = ReviewViewModel(app, handle)
        val path = editing(first).imagePath!!
        first.edit { it.copy(merchant = "Typed by hand") }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, photos.size)

        // The process dies here: nothing is cleaned up, and the source photo is no longer readable.
        File(uri.path!!).delete()
        val second = ReviewViewModel(app, handle)
        val resumed = editing(second)

        assertEquals("Typed by hand", resumed.draft.merchant)
        assertEquals(path, resumed.imagePath)
        assertTrue(resumed.isNew)
        assertEquals(1, photos.size)
    }

    @Test
    fun `edits to a saved receipt resume too`() {
        val id = runBlocking {
            app.receipts.save(
                Receipt(merchant = "Original", category = "DINING", purchasedAt = 1_700_000_000_000, totalCents = 2560, currency = "USD", createdAt = 7, updatedAt = 7),
                emptyList(),
            )
        }
        val handle = SavedStateHandle(mapOf("id" to id.toString()))
        val first = ReviewViewModel(app, handle)
        editing(first)
        first.edit { it.copy(merchant = "Changed") }
        shadowOf(Looper.getMainLooper()).idle()

        val second = ReviewViewModel(app, handle)
        assertEquals("Changed", editing(second).draft.merchant)
        second.save()
        waitFor { second.saved.value != null }
        val saved = runBlocking { app.receipts.get(id) }!!
        assertEquals("the saved receipt is updated, not copied", 7L, saved.createdAt)
        assertEquals(1, runBlocking { app.receipts.getAll() }.size)
    }

    @Test
    fun `a saved receipt doesn't resume as a draft`() {
        val handle = SavedStateHandle()
        val first = ReviewViewModel(app, handle)
        editing(first)
        first.edit { it.copy(merchant = "Cafe", total = "3.50") }
        first.save()
        waitFor { first.saved.value != null }
        assertFalse(handle.contains(ReviewViewModel.KEY_DRAFT))
    }

    // A plain Application and its own database: the real app sweeps at startup, on another thread.
    @Test
    @Config(application = Application::class)
    fun `old photos no receipt uses are removed, others are kept`() = runBlocking {
        fun photo(name: String, age: Long) = File(context.filesDir, "receipts/$name.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - age)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = ReceiptRepository(db.receiptDao(), ImageStore(context))
        val days8 = TimeUnit.DAYS.toMillis(8)
        val orphan = photo("orphan", days8)
        val draftPhoto = photo("recent-draft", TimeUnit.DAYS.toMillis(1))
        val saved = photo("saved", days8)
        val trashed = photo("trashed", days8)
        fun receipt(photo: File) = Receipt(merchant = photo.name, category = "DINING", purchasedAt = 1, totalCents = 1, currency = "USD", imagePath = photo.absolutePath, createdAt = 1, updatedAt = 1)
        repo.save(receipt(saved), emptyList())
        repo.moveToTrash(listOf(repo.save(receipt(trashed), emptyList())))

        assertEquals(1, repo.sweepOrphanImages())
        db.close()

        assertFalse(orphan.exists())
        assertTrue(draftPhoto.exists())
        assertTrue(saved.exists())
        assertTrue(trashed.exists())
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }
}
