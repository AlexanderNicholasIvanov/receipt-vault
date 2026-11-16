package com.alexivanov.receiptvault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A tablet or an open foldable: receipts open beside the list instead of over it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w900dp-h700dp")
class TwoPaneTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = (compose.activity.application as ReceiptVaultApp).container

    private fun addReceipt(merchant: String, cents: Long, item: String) = runBlocking {
        app.receipts.save(
            Receipt(merchant = merchant, category = "GROCERIES", purchasedAt = System.currentTimeMillis(), totalCents = cents, currency = "USD", createdAt = 1, updatedAt = 1),
            listOf(LineItem(receiptId = 0, position = 0, name = item, priceCents = cents)),
        )
    }

    @Test
    fun `a receipt opens beside the list, and stays open when the phone folds`() {
        compose.waitForEmptyHome()
        compose.onNodeWithText("Choose a receipt to see it here.").assertExists()
        compose.onNode(hasStateDescription(OPEN)).assertDoesNotExist()
        addReceipt("Corner Store", 899, "Oat Milk")
        addReceipt("Bakery", 450, "Sourdough")
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), 10_000)

        compose.onNodeWithText("Corner Store").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Oat Milk"), 10_000)
        // The list is still there beside it, with the open receipt marked.
        compose.onNodeWithText("Bakery").assertExists()
        compose.onNode(hasStateDescription(OPEN)).assert(hasText("Corner Store", substring = true))

        // Another receipt replaces it in the same pane.
        compose.onNodeWithText("Bakery").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sourdough"), 10_000)
        compose.onNodeWithText("Oat Milk").assertDoesNotExist()
        compose.waitUntilExactlyOneExists(hasStateDescription(OPEN), 10_000)
        compose.onNode(hasStateDescription(OPEN)).assert(hasText("Bakery", substring = true))

        // Folded to a phone-sized screen, the open receipt fills it.
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        compose.activityRule.scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasText("Sourdough"), 10_000)
        compose.onNodeWithText("Corner Store").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), 10_000)
        // One pane again: nothing is marked open.
        compose.onNode(hasStateDescription(OPEN)).assertDoesNotExist()
    }

    private companion object {
        const val OPEN = "Open beside the list"
    }
}
