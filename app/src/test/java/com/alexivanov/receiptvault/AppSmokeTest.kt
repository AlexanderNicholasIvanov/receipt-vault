package com.alexivanov.receiptvault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Launches the real app on the JVM (Robolectric, Android 16) and walks the
 * core flow: empty home -> manual entry -> save -> detail -> back to a home
 * list that shows the receipt and updated totals.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
// A tall screen so every field of the lazy edit form is composed.
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class AppSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `manual receipt is saved, shown and totalled`() {
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)

        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), 5_000)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()

        compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
        compose.onNodeWithText("Merchant").performTextInput("Corner Cafe")
        compose.onNodeWithText("Subtotal").performTextInput("20.00")
        compose.onNodeWithText("Tax").performTextInput("1.60")
        compose.onNodeWithText("Tip").performTextInput("4.00")
        compose.onNodeWithText("Total").performTextInput("25.60")
        compose.onNodeWithText("Tip is 20.0% of the subtotal").assertExists()

        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()

        // Detail screen for the new receipt.
        compose.waitUntilAtLeastOneExists(hasText("Tip (20%)"), 10_000)
        compose.onNodeWithText("Corner Cafe").assertExists()
        compose.waitUntilAtLeastOneExists(hasText("$25.60"), 5_000)

        // Back to the list: the receipt and this month's tips are there.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), 10_000)
        compose.waitUntilAtLeastOneExists(hasText("$4.00"), 5_000)
    }

    @Test
    fun `deleting goes to the trash with undo, and the trash can restore`() {
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)
        addManualReceipt("Corner Cafe", "25.60")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), 10_000)

        // Long-press selects; the bar offers bulk actions.
        compose.onNodeWithText("Corner Cafe").performTouchInput { longClick() }
        compose.waitUntilAtLeastOneExists(hasText("1 selected"), 5_000)
        compose.onNodeWithContentDescription("Move selected to trash").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Moved to trash"), 5_000)
        compose.waitUntilDoesNotExist(hasText("Corner Cafe"), 5_000)

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), 5_000)

        // Deleting from the receipt's own screen also lands on Home with Undo.
        compose.onNodeWithText("Corner Cafe").performClick()
        compose.waitUntilAtLeastOneExists(hasText("$25.60"), 10_000)
        compose.onNodeWithContentDescription("Move to trash").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Moved to trash"), 10_000)
        compose.waitUntilDoesNotExist(hasText("Corner Cafe"), 5_000)

        // The trash lists it and can put it back.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("1 deleted receipt, kept for 30 days."), 5_000)
        compose.onNodeWithText("Trash", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), 5_000)
        compose.onNodeWithText("30 days left", substring = true).assertExists()
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntilAtLeastOneExists(hasText("The trash is empty."), 5_000)
    }

    private fun addManualReceipt(merchant: String, total: String) {
        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), 5_000)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
        compose.onNodeWithText("Merchant").performTextInput(merchant)
        compose.onNodeWithText("Total").performTextInput(total)
        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("$" + total), 10_000)
    }

    @Test
    fun `insights and settings screens open`() {
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)

        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Monthly spending"), 5_000)
        compose.onNodeWithText("Average tip").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 5_000)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Export to CSV"), 5_000)
        compose.onNodeWithText("Day-first dates").assertExists()
        compose.onNodeWithText("Save scan location").assertDoesNotExist()
        compose.onNodeWithText("Back up receipts", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Restore from backup", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Trash", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Check for updates", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Check automatically", useUnmergedTree = true).assertExists()
    }
}
