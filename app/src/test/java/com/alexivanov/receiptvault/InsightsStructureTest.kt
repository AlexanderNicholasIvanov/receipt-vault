package com.alexivanov.receiptvault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.LineItem
import com.alexivanov.receiptvault.data.Receipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Insights holds averages and ways in; Home holds the totals for a period. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class InsightsStructureTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = (compose.activity.application as ReceiptVaultApp).container

    private fun save(day: LocalDate, merchant: String, category: String, cents: Long, tip: Long? = null, items: List<Pair<String, Long>> = emptyList()) = runBlocking {
        val at = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        app.receipts.save(
            Receipt(merchant = merchant, category = category, totalCents = cents, tipCents = tip, currency = "USD", purchasedAt = at, createdAt = at, updatedAt = at),
            items.mapIndexed { i, (name, price) -> LineItem(receiptId = 0, position = i, name = name, priceCents = price) },
        )
    }

    /** Netflix every month and Kroger every week, for the last half year. */
    private fun billsAndShopping() {
        val today = LocalDate.now()
        for (i in 0..5) save(today.minusDays(3).minusMonths(i.toLong()), "Netflix", "ENTERTAINMENT", 1_599)
        for (i in 0..20) save(today.minusDays(1).minusWeeks(i.toLong()), "Kroger", "GROCERIES", 5_000, tip = if (i == 0) 200 else null)
        compose.waitUntilAtLeastOneExists(hasText("Kroger"), UI_WAIT)
    }

    @Test
    fun `the totals for a period open from Home, not Insights`() {
        billsAndShopping()
        compose.onNodeWithText("Details").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Average receipt"), UI_WAIT)
        compose.onNodeWithText("By category").assertExists()
        compose.onNodeWithText("Top merchants").assertExists()

        // A category closes the sheet and opens its breakdown.
        compose.onNodeWithText("🛒 Groceries").performClick()
        compose.waitUntilAtLeastOneExists(hasText("What you usually buy here"), UI_WAIT)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Kroger"), UI_WAIT)
        compose.onNodeWithText("Average receipt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("A month, by category"), UI_WAIT)
        compose.onNodeWithText("Average receipt").assertDoesNotExist()
        compose.onNodeWithText("Top merchants").assertDoesNotExist()
    }

    @Test
    fun `bills and regular shopping are listed apart, and only bills come due`() {
        billsAndShopping()
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Bills"), UI_WAIT)
        compose.onNodeWithText("Bills").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Due in the next 7 days"), UI_WAIT)
        // Kroger's next shop is within the week, but it's no bill.
        compose.onNodeWithText("Nothing due this week.").assertExists()
        compose.onNodeWithText("1 of 1 paid").assertExists()
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        list.performScrollToNode(hasText("Subscriptions & bills"))
        list.performScrollToNode(hasText("Regular shopping"))
        compose.onNodeWithText("Every week · usually $50.00", substring = true).assertExists()
    }

    @Test
    fun `your inflation is a tile once there are prices to compare`() {
        val today = LocalDate.now()
        val before = listOf("Milk" to 300L, "Bread" to 400L, "Eggs" to 500L)
        save(today.minusDays(200), "Corner Store", "GROCERIES", 1_200, items = before)
        save(today.minusDays(2), "Corner Store", "GROCERIES", 1_320, items = before.map { (n, p) -> n to p * 11 / 10 })
        compose.waitUntilAtLeastOneExists(hasText("Corner Store"), UI_WAIT)
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Your inflation"), UI_WAIT)
        compose.onNodeWithText("% a year", substring = true).assertExists()
        // It opens the Future tab, where the rate can go into the retirement estimate.
        compose.onNodeWithText("Your inflation").performClick()
        compose.waitUntilAtLeastOneExists(hasText("At this pace"), UI_WAIT)
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun `on a wide screen the dashboard is two columns`() {
        billsAndShopping()
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Recurring"), UI_WAIT)
        val hero = compose.onNodeWithText("You spend about").getUnclippedBoundsInRoot()
        val tile = compose.onNodeWithText("Recurring").getUnclippedBoundsInRoot()
        assertTrue("the tiles sit beside the hero, not under it: $hero, $tile", tile.left > hero.right && tile.top < hero.bottom)
    }
}
