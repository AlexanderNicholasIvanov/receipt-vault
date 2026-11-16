package com.alexivanov.receiptvault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.Receipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Receipts, Insights and Settings sit in a bar on a phone and a rail on a wide screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class NavigationBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = (compose.activity.application as ReceiptVaultApp).container

    private fun place(name: String) = compose.onNode(hasText(name) and isSelectable())

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `the bar moves between places, and Back from any of them goes to Receipts`() {
        compose.waitForEmptyHome()
        place("Receipts").assertIsSelected()
        compose.goTo("Insights")
        compose.waitUntilAtLeastOneExists(hasText("Spending"), UI_WAIT)
        place("Insights").assertIsSelected()
        compose.goTo("Settings")
        compose.waitUntilAtLeastOneExists(hasText("Restore from backup"), UI_WAIT)
        // A second tap on the open place leaves it as it is.
        compose.goTo("Settings")
        place("Settings").assertIsSelected()

        back()
        compose.waitForEmptyHome()
        place("Receipts").assertIsSelected()
    }

    @Test
    fun `a receipt's own screen has no bar`() {
        runBlocking {
            app.receipts.save(
                Receipt(merchant = "Corner Store", category = "GROCERIES", purchasedAt = System.currentTimeMillis(), totalCents = 899, currency = "USD", createdAt = 1, updatedAt = 1),
                emptyList(),
            )
        }
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), UI_WAIT)
        compose.onNodeWithText("Corner Store").performClick()
        compose.waitUntilDoesNotExist(hasText("Insights") and isSelectable(), UI_WAIT)
        back()
        compose.waitUntilAtLeastOneExists(hasText("Insights") and isSelectable(), UI_WAIT)
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun `on a wide screen the places are a rail down the side`() {
        compose.waitForEmptyHome()
        val receipts = place("Receipts").getUnclippedBoundsInRoot()
        val settings = place("Settings").getUnclippedBoundsInRoot()
        assertTrue("a rail at the left edge: $receipts", receipts.right < 120.dp)
        assertTrue("stacked, not side by side: $receipts, $settings", settings.top > receipts.bottom && settings.left == receipts.left)
    }
}
