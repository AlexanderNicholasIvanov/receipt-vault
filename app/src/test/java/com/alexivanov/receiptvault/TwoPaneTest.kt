package com.alexivanov.receiptvault

import androidx.compose.ui.test.ExperimentalTestApi
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
        addReceipt("Corner Store", 899, "Oat Milk")
        addReceipt("Bakery", 450, "Sourdough")
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), UI_WAIT)

        compose.onNodeWithText("Corner Store").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Oat Milk"), UI_WAIT)
        // The list is still there beside it.
        compose.onNodeWithText("Bakery").assertExists()

        // Another receipt replaces it in the same pane.
        compose.onNodeWithText("Bakery").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sourdough"), UI_WAIT)
        compose.onNodeWithText("Oat Milk").assertDoesNotExist()

        // Folded to a phone-sized screen, the open receipt fills it.
        RuntimeEnvironment.setQualifiers("w411dp-h900dp")
        compose.activityRule.scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasText("Sourdough"), UI_WAIT)
        compose.onNodeWithText("Corner Store").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), UI_WAIT)
    }
}
