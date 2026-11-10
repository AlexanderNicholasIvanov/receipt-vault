package com.alexivanov.receiptvault

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class NavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = (compose.activity.application as ReceiptVaultApp).container

    private fun addReceipt() = runBlocking {
        app.receipts.save(
            Receipt(merchant = "Corner Store", category = "GROCERIES", purchasedAt = System.currentTimeMillis(), totalCents = 899, currency = "USD", createdAt = 1, updatedAt = 1),
            listOf(LineItem(receiptId = 0, position = 0, name = "Oat Milk", priceCents = 899)),
        )
    }

    @Test
    fun `a double tap on Back leaves one screen, not two`() {
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Restore from backup"), 5_000)

        // Both taps land before the screen has gone.
        val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode()
        compose.runOnUiThread { repeat(2) { back.config[SemanticsActions.OnClick].action!!.invoke() } }

        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 5_000)
    }

    @Test
    fun `filter choices not yet applied survive turning the phone`() {
        addReceipt()
        compose.waitUntilAtLeastOneExists(hasText("Filters"), 10_000)
        compose.onNodeWithText("Filters").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Oldest first"), 5_000)
        compose.onNodeWithText("Oldest first").performClick()

        compose.activityRule.scenario.recreate()

        compose.waitUntilAtLeastOneExists(hasText("Oldest first"), 5_000)
        compose.onNodeWithText("Oldest first").assertIsSelected()
    }

    @Test
    fun `search finds a receipt by an item typed in by hand`() {
        addReceipt()
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), 10_000)
        val box = compose.onNode(hasSetTextAction())
        box.performTextInput("oat milk")
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), 5_000)
        box.performTextReplacement("stapler")
        compose.waitUntilDoesNotExist(hasText("Corner Store"), 5_000)
    }
}
