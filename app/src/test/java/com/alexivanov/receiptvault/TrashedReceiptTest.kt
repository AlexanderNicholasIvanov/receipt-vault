package com.alexivanov.receiptvault

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.ui.review.ReviewState
import com.alexivanov.receiptvault.ui.review.ReviewViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A receipt reached from an old reminder after it was moved to the trash or deleted for good. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class TrashedReceiptTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = (context as ReceiptVaultApp).container

    private fun open(id: Long) = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECEIPT_ID, id)

    private fun trashedReceipt(): Long = runBlocking {
        val id = app.receipts.save(
            Receipt(merchant = "Corner Cafe", category = "DINING", purchasedAt = 1_700_000_000_000, totalCents = 2560, currency = "USD", createdAt = 1, updatedAt = 1),
            emptyList(),
        )
        app.receipts.moveToTrash(listOf(id))
        id
    }

    @Test
    fun `a trashed receipt says so and can be restored, not edited`() {
        val id = trashedReceipt()
        ActivityScenario.launch<MainActivity>(open(id)).use {
            compose.waitUntilAtLeastOneExists(hasText("In the trash", substring = true), 10_000)
            compose.onNodeWithContentDescription("Edit").assertDoesNotExist()
            compose.onNodeWithContentDescription("Move to trash").assertDoesNotExist()

            compose.onNodeWithText("Restore").performClick()
            compose.waitUntilDoesNotExist(hasText("In the trash", substring = true), 10_000)
            compose.onNodeWithContentDescription("Edit").assertExists()
        }
    }

    @Test
    fun `a receipt deleted for good explains itself`() {
        ActivityScenario.launch<MainActivity>(open(987_654)).use {
            compose.waitUntilAtLeastOneExists(hasText("This receipt no longer exists."), 10_000)
        }
    }

    @Test
    fun `saving edits to a trashed receipt leaves it in the trash`() {
        val id = trashedReceipt()
        val vm = ReviewViewModel(app, SavedStateHandle(mapOf("id" to id.toString())))
        waitFor { vm.state.value is ReviewState.Editing }

        vm.edit { it.copy(merchant = "Corner Cafe Downtown") }
        vm.save()
        waitFor { vm.saved.value != null }

        val saved = runBlocking { app.receipts.receipt(id).first() }
        assertEquals("Corner Cafe Downtown", saved?.merchant)
        assertNotNull("still in the trash", saved?.deletedAt)
        assertTrue(runBlocking { app.receipts.getAll() }.isEmpty())
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
