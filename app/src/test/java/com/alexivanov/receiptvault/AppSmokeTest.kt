package com.alexivanov.receiptvault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import com.alexivanov.receiptvault.domain.Category
import com.alexivanov.receiptvault.domain.ExchangeRates
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

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
        compose.waitForEmptyHome()

        // TalkBack has a name for the main action (Material hides the extended FAB's label).
        compose.onNodeWithContentDescription("Add receipt").assertExists()
        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), UI_WAIT)
        compose.onNodeWithText("Import a PDF", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()

        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        compose.onNodeWithText("Merchant").performTextInput("Corner Cafe")
        compose.onNodeWithText("Subtotal").performTextInput("20.00")
        compose.onNodeWithText("Tax").performTextInput("1.60")
        compose.onNodeWithText("Tip").performTextInput("4.00")
        compose.onNodeWithText("Total").performTextInput("25.60")
        compose.onNodeWithText("Tip is 20.0% of the subtotal").assertExists()

        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()

        // Detail screen for the new receipt.
        compose.waitUntilAtLeastOneExists(hasText("Tip (20%)"), UI_WAIT)
        compose.onNodeWithText("Corner Cafe").assertExists()
        compose.waitUntilAtLeastOneExists(hasText("$25.60"), UI_WAIT)

        // Back to the list: the receipt and this month's tips are there.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)
        compose.waitUntilAtLeastOneExists(hasText("$4.00"), UI_WAIT)
    }

    @Test
    fun `deleting goes to the trash with undo, and the trash can restore`() {
        compose.waitForEmptyHome()
        addManualReceipt("Corner Cafe", "25.60")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)

        // Long-press selects; the bar offers bulk actions.
        compose.onNodeWithText("Corner Cafe").performTouchInput { longClick() }
        compose.waitUntilAtLeastOneExists(hasText("1 selected"), UI_WAIT)
        compose.onNodeWithContentDescription("Move selected to trash").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Moved to trash"), UI_WAIT)
        compose.waitUntilDoesNotExist(hasText("Corner Cafe"), UI_WAIT)

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)

        // Deleting from the receipt's own screen also lands on Home with Undo.
        compose.onNodeWithText("Corner Cafe").performClick()
        compose.waitUntilAtLeastOneExists(hasText("$25.60"), UI_WAIT)
        compose.onNodeWithContentDescription("Move to trash").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Moved to trash"), UI_WAIT)
        compose.waitUntilDoesNotExist(hasText("Corner Cafe"), UI_WAIT)

        // The trash lists it and can put it back.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("1 deleted receipt."), UI_WAIT)
        compose.onNodeWithText("Trash", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)
        compose.onNodeWithText("Deleted receipts stay here until you restore them or delete them for good.").assertExists()
        compose.onNodeWithText(" · deleted ", substring = true).assertExists()
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntilAtLeastOneExists(hasText("The trash is empty."), UI_WAIT)
    }

    @Test
    fun `search keeps every typed letter and narrows the list after a pause`() {
        compose.waitForEmptyHome()
        addManualReceipt("Corner Cafe", "25.60")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)

        val box = hasSetTextAction()
        "zzz".forEach { compose.onNode(box).performTextInput(it.toString()) }
        compose.waitUntilAtLeastOneExists(hasText("No receipts match", substring = true), UI_WAIT)
        compose.onNode(box).assert(hasText("zzz"))

        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)
    }

    @Test
    fun `filters narrow and sort the list`() {
        compose.waitForEmptyHome()
        addManualReceipt("Corner Cafe", "25.60")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)
        addManualReceipt("Shell", "40.00")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)

        compose.onNodeWithText("Filters").performClick()
        compose.waitUntilAtLeastOneExists(hasText("At least"), UI_WAIT)
        compose.onNodeWithText("At least").performTextInput("30")
        compose.onNodeWithText("Show results").performScrollTo().performClick()

        compose.waitUntilAtLeastOneExists(hasText("Filters (1)"), UI_WAIT)
        compose.waitUntilDoesNotExist(hasText("Corner Cafe"), UI_WAIT)
        compose.onNodeWithText("Shell").assertExists()

        compose.onNodeWithText("Filters (1)").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Reset"), UI_WAIT)
        compose.onNodeWithText("Reset").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)
        compose.onNodeWithText("Filters").assertExists()
    }

    @Test
    fun `tagged receipts can be filtered by tag and reported`() {
        compose.waitForEmptyHome()
        addManualReceipt("Corner Cafe", "25.60", tags = "work, #Berlin trip")
        compose.onNodeWithText("#work   #Berlin trip").performScrollTo().assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)
        addManualReceipt("Shell", "40.00")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Shell"), UI_WAIT)
        compose.onNodeWithContentDescription("Expense report").assertExists()

        compose.onNodeWithText("Filters").performClick()
        compose.waitUntilAtLeastOneExists(hasText("#Berlin trip"), UI_WAIT)
        compose.onNodeWithText("#work").performClick()
        compose.onNodeWithText("Show results").performScrollTo().performClick()

        compose.waitUntilAtLeastOneExists(hasText("Filters (1)"), UI_WAIT)
        compose.waitUntilDoesNotExist(hasText("Shell"), UI_WAIT)
        compose.onNodeWithText("Corner Cafe").assertExists()
    }

    @Test
    fun `reminders and budgets`() {
        compose.waitForEmptyHome()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Monthly budgets"), UI_WAIT)
        compose.onNodeWithText("Monthly budgets").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Save budgets"), UI_WAIT)
        compose.onNodeWithText("Everything").performTextInput("30")
        compose.onNodeWithText("Save budgets").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Budgets saved."), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Monthly budgets"), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // A receipt with a 14-day return window, from today.
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), UI_WAIT)
        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), UI_WAIT)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        compose.onNodeWithText("Merchant").performTextInput("Hardware Store")
        compose.onNodeWithText("Total").performTextInput("25.00")
        compose.onNodeWithText("14 days").performScrollTo().performClick()
        compose.onNodeWithText("Return by:", substring = true).assertExists()
        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()

        compose.waitUntilAtLeastOneExists(hasText("(in 14 days)", substring = true), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("left of", substring = true), UI_WAIT)
        compose.onNodeWithText("$5.00 left of $30.00 budget").assertExists()
    }

    @Test
    fun `saving the same receipt twice asks first`() {
        compose.waitForEmptyHome()
        addManualReceipt("Corner Cafe", "25.60")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Corner Cafe"), UI_WAIT)

        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), UI_WAIT)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        compose.onNodeWithText("Merchant").performTextInput("corner cafe")
        compose.onNodeWithText("Total").performTextInput("25.60")
        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()

        compose.waitUntilAtLeastOneExists(hasText("Already saved?"), UI_WAIT)
        compose.onNodeWithText("You already have a receipt from Corner Cafe for $25.60", substring = true).assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.waitUntilDoesNotExist(hasText("Already saved?"), UI_WAIT)

        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Save anyway"), UI_WAIT)
        compose.onNodeWithText("Save anyway").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Edit"), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("$51.20"), UI_WAIT)
    }

    @Test
    fun `other currencies count once conversion is on`() {
        val app = ApplicationProvider.getApplicationContext<ReceiptVaultApp>().container
        compose.waitForEmptyHome()
        try {
            runBlocking {
                app.settings.setDefaultCurrency("USD")
                // Fresh rates, so nothing is downloaded during the test.
                app.settings.setExchangeRates(
                    ExchangeRates("USD", LocalDate.parse("2026-11-05"), mapOf("EUR" to BigDecimal("0.5")), System.currentTimeMillis()),
                )
                val now = System.currentTimeMillis()
                app.receipts.save(Receipt(merchant = "Paris Bistro", category = Category.DINING.name, totalCents = 1000, currency = "EUR", purchasedAt = now, createdAt = now, updatedAt = now), emptyList())
                app.receipts.save(Receipt(merchant = "Corner Cafe", category = Category.DINING.name, totalCents = 500, currency = "USD", purchasedAt = now, createdAt = now, updatedAt = now), emptyList())
            }
            compose.waitUntilAtLeastOneExists(hasText("Paris Bistro"), UI_WAIT)
            compose.waitUntilAtLeastOneExists(hasText("in other currencies not included", substring = true), UI_WAIT)

            compose.onNodeWithContentDescription("Settings").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Convert other currencies"), UI_WAIT)
            compose.onNodeWithText("frankfurter.dev", substring = true).assertExists()
            runBlocking { app.settings.setConvertCurrencies(true) }
            compose.waitUntilAtLeastOneExists(hasText("at the European Central Bank rates of 2026-11-05", substring = true), UI_WAIT)
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            // €10.00 at 0.5 is $20.00, plus $5.00.
            compose.waitUntilAtLeastOneExists(hasText("$25.00"), UI_WAIT)
            compose.onNodeWithText("Includes 1 receipt(s) in other currencies", substring = true).assertExists()
        } finally {
            runBlocking {
                app.settings.setConvertCurrencies(false)
                app.settings.setExchangeRates(null)
            }
        }
    }

    @Test
    fun `insights show the monthly cost and recurring payments`() {
        val app = ApplicationProvider.getApplicationContext<ReceiptVaultApp>().container
        compose.waitForEmptyHome()
        runBlocking {
            app.settings.setDefaultCurrency("USD")
            val today = LocalDate.now()
            listOf(60L, 30L, 0L).forEach { daysAgo ->
                val at = today.minusDays(daysAgo).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                app.receipts.save(
                    Receipt(merchant = "Netflix", category = Category.ENTERTAINMENT.name, totalCents = 1599, currency = "USD", purchasedAt = at, createdAt = at, updatedAt = at),
                    emptyList(),
                )
            }
        }
        compose.waitUntilAtLeastOneExists(hasText("Netflix"), UI_WAIT)

        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("You spend about"), UI_WAIT)
        compose.onNodeWithText("3 receipts since", substring = true).assertExists()
        // The current month is labelled as unfinished, for TalkBack too.
        compose.onNodeWithContentDescription(", so far: ", substring = true).assertExists()
        compose.onNodeWithText("Table").performClick()
        compose.onNodeWithText("(so far)", substring = true).assertExists()

        // The Recurring tile opens the Bills tab.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("1 regular payment"))
        compose.onNodeWithText("1 regular payment").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Due in the next 7 days"), UI_WAIT)
        compose.onNodeWithText("Nothing due this week.").assertExists()
        // Paid today; next month's is the only one due.
        compose.onNodeWithText("1 of 1 paid").assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Subscriptions & bills"))
        compose.onNodeWithText("$15.99 a month from 1 place").assertExists()
        compose.onNodeWithText("Every month · usually $15.99", substring = true).assertExists()

        compose.onNodeWithText("Future").performClick()
        // No line items, so no rate yet: the card says what it needs.
        compose.waitUntilAtLeastOneExists(hasText("Your inflation"), UI_WAIT)
        compose.onNodeWithText("Keep line items on your receipts", substring = true).assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("At this pace"))
    }

    @Test
    fun `a category shows what is usually bought and where`() {
        val app = ApplicationProvider.getApplicationContext<ReceiptVaultApp>().container
        compose.waitForEmptyHome()
        runBlocking {
            app.settings.setDefaultCurrency("USD")
            val now = System.currentTimeMillis()
            listOf(349L, 379L).forEach { milk ->
                app.receipts.save(
                    Receipt(merchant = "Kroger", category = Category.GROCERIES.name, totalCents = milk + 129, currency = "USD", purchasedAt = now, createdAt = now, updatedAt = now),
                    listOf(
                        LineItem(receiptId = 0, position = 0, name = "Milk 1 gal", priceCents = milk),
                        LineItem(receiptId = 0, position = 1, name = "Bananas", priceCents = 129),
                    ),
                )
            }
        }
        compose.waitUntilAtLeastOneExists(hasText("Kroger"), UI_WAIT)

        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("You spend about"), UI_WAIT)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("A month, by category"))
        compose.onAllNodesWithText("🛒 Groceries")[0].performClick()
        compose.waitUntilAtLeastOneExists(hasText("What you usually buy here"), UI_WAIT)
        compose.onNodeWithText("You usually pay").assertExists()
        compose.onNodeWithText("Milk 1 gal").assertExists()
        compose.onNodeWithText("Bought 2 times · $3.49–$3.79", substring = true).assertExists()
        compose.onNodeWithText("2 visits · usually", substring = true).assertExists()

        // An item opens its price over time.
        compose.onNodeWithText("Milk 1 gal").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Every purchase"), UI_WAIT)
        compose.onNodeWithText("Last paid").assertExists()
        compose.onNodeWithText("Bought 2 times · cheapest $3.49 at Kroger").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("What you usually buy here"), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntilAtLeastOneExists(hasText("You spend about"), UI_WAIT)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("See everything you buy"))
        compose.onNodeWithText("See everything you buy").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Everything you buy"), UI_WAIT)
        compose.onNodeWithText("Bananas").assertExists()
    }

    @Test
    fun `retirement estimate uses the receipts and remembers the age`() {
        val app = ApplicationProvider.getApplicationContext<ReceiptVaultApp>().container
        compose.waitForEmptyHome()
        try {
            runBlocking {
                app.settings.setDefaultCurrency("USD")
                val now = System.currentTimeMillis()
                app.receipts.save(Receipt(merchant = "Kroger", category = Category.GROCERIES.name, totalCents = 100_000, currency = "USD", purchasedAt = now, createdAt = now, updatedAt = now), emptyList())
            }
            compose.waitUntilAtLeastOneExists(hasText("Kroger"), UI_WAIT)

            compose.onNodeWithContentDescription("Insights").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Future"), UI_WAIT)
            compose.onNodeWithText("Future").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Retirement"), UI_WAIT)
            compose.onNodeWithText("Retirement").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Spending in retirement"), UI_WAIT)
            // $1,000 a month from receipts; 25 years of it is the rule of thumb.
            compose.onNodeWithText("Rule of thumb (25 times a year's spending): $300,000 in today's money.").assertExists()
            compose.onNodeWithText("Add your age below", substring = true).assertExists()

            compose.onNodeWithText("Your age").performScrollTo().performTextInput("40")
            compose.waitUntilAtLeastOneExists(hasText("To retire at 65, in 25 years, save about"), UI_WAIT)
            compose.onNodeWithText("when you retire at 65, in 25 years", substring = true).assertExists()
            compose.waitUntil(UI_WAIT) { runBlocking { app.settings.current().retirement.birthYear } == LocalDate.now().year - 40 }

            // The range, and retiring at another age.
            val list = compose.onAllNodes(hasScrollAction()).onFirst()
            list.performScrollToNode(hasText("A range, not one number"))
            compose.onNodeWithText("Comfortable").assertExists()
            list.performScrollToNode(hasText("At 67"))
            compose.onNodeWithText("At 67").performScrollTo().performClick()
            list.performScrollToIndex(0)
            compose.waitUntilAtLeastOneExists(hasText("when you retire at 67, in 27 years", substring = true), UI_WAIT)

            // A US healthcare figure, in one tap.
            list.performScrollToNode(hasText("Use about", substring = true))
            compose.onNodeWithText("Use about", substring = true).performScrollTo().performClick()
            // Let the screen recompose and start saving before polling what was saved: polling alone
            // doesn't always run the main looper, and on CI the save then never started.
            compose.waitForIdle()
            compose.waitUntil(UI_WAIT) { runBlocking { app.settings.current().retirement.healthcareMonthlyCents } > 0 }

            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntilAtLeastOneExists(hasText("by 67 (", substring = true), UI_WAIT)
        } finally {
            runBlocking { app.settings.setRetirementPlan(com.alexivanov.receiptvault.domain.RetirementPlan()) }
        }
    }

    @Test
    fun `a new receipt from a known store starts with its category, with undo`() {
        val app = ApplicationProvider.getApplicationContext<ReceiptVaultApp>().container
        compose.waitForEmptyHome()
        runBlocking {
            val now = System.currentTimeMillis()
            app.receipts.save(Receipt(merchant = "Joe's Diner", category = Category.DINING.name, totalCents = 2_000, currency = "USD", purchasedAt = now, createdAt = now, updatedAt = now), emptyList())
        }
        compose.waitUntilAtLeastOneExists(hasText("Joe's Diner"), UI_WAIT)

        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), UI_WAIT)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        val dining = "${Category.DINING.emoji} ${Category.DINING.label}"
        val other = "${Category.OTHER.emoji} ${Category.OTHER.label}"
        compose.onNodeWithText(other).assertIsSelected()

        compose.onNodeWithText("Merchant").performTextInput("JOE'S DINER")
        compose.waitUntilAtLeastOneExists(hasText("Dining, as on your other receipts from JOE'S DINER."), UI_WAIT)
        compose.onNodeWithText(dining).assertIsSelected()

        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText(other).assertIsSelected()
        compose.onNodeWithText("Dining, as on your", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a pile of receipts can be typed in one after another`() {
        compose.waitForEmptyHome()
        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), UI_WAIT)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        compose.onNodeWithText("Merchant").performTextInput("First Shop")
        compose.onNodeWithText("Total").performTextInput("12.00")
        compose.onNodeWithText("Save and add another").performScrollTo().performClick()

        // Straight into an empty form for the next one.
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText("First Shop").fetchSemanticsNodes().isEmpty() }
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        compose.onNodeWithText("Merchant").performTextInput("Second Shop")
        compose.onNodeWithText("Total").performTextInput("7.50")
        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()

        // The last one opens as usual; both are saved.
        compose.waitUntilAtLeastOneExists(hasText("$7.50"), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("First Shop"), UI_WAIT)
        compose.onNodeWithText("Second Shop").assertExists()
    }

    private fun addManualReceipt(merchant: String, total: String, tags: String? = null) {
        compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), UI_WAIT)
        compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Merchant"), UI_WAIT)
        compose.onNodeWithText("Merchant").performTextInput(merchant)
        compose.onNodeWithText("Total").performTextInput(total)
        if (tags != null) compose.onNodeWithText("Tags").performScrollTo().performTextInput(tags)
        compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("$" + total), UI_WAIT)
    }

    @Test
    fun `insights and settings screens open`() {
        compose.waitForEmptyHome()

        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Monthly spending"), UI_WAIT)
        compose.onNodeWithText("Bills").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Nothing repeats regularly yet", substring = true), UI_WAIT)
        compose.onNodeWithText("Future").performClick()
        compose.waitUntilAtLeastOneExists(hasText("projected forward here", substring = true), UI_WAIT)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), UI_WAIT)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Export to CSV"), UI_WAIT)
        compose.onNodeWithText("Day-first dates").assertExists()
        compose.onNodeWithText("App lock").assertExists()
        compose.onNodeWithText("Save scan location").assertDoesNotExist()
        compose.onNodeWithText("Spending changes", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Remind me the day before a bill", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Back up receipts", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Restore from backup", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Trash", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Check for updates", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Check automatically", useUnmergedTree = true).assertExists()
    }
}
