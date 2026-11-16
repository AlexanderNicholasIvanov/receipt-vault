package com.alexivanov.receiptvault

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.Receipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
// Real text measurement, so a label that doesn't fit shows up.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = (compose.activity.application as ReceiptVaultApp).container

    private fun openInsights() {
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Bills"), UI_WAIT)
    }

    /** Lines [text] takes; a label cut off at the bottom fails here too. */
    private fun lines(text: String): Int {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        // Width overflow is no signal here: a scrollable tab row measures its tabs at unbounded width.
        assertFalse("$text is cut off", layout.didOverflowHeight)
        return layout.lineCount
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at the largest text size every tab label stays on one line`() {
        compose.waitForEmptyHome()
        openInsights()
        listOf("Spending", "Bills", "Future").forEach { assertEquals("$it lines", 1, lines(it)) }
    }

    @Test
    fun `a subscription that went up shows its new price and the old one`() {
        val last = LocalDate.now().minusDays(3)
        val zone = ZoneId.systemDefault()
        runBlocking {
            // Four months at $15.99, then three at $17.99.
            listOf(1_599L, 1_599L, 1_599L, 1_599L, 1_799L, 1_799L, 1_799L).forEachIndexed { i, cents ->
                val day = last.minusMonths(6L - i)
                app.receipts.save(
                    Receipt(
                        merchant = "Netflix", category = "ENTERTAINMENT", totalCents = cents, currency = "USD",
                        purchasedAt = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), createdAt = 1, updatedAt = 1,
                    ),
                    emptyList(),
                )
            }
        }
        compose.waitUntilAtLeastOneExists(hasText("Netflix"), UI_WAIT)
        openInsights()
        compose.onNodeWithText("Bills").performClick()
        compose.waitUntilAtLeastOneExists(hasText("usually $17.99", substring = true), UI_WAIT)
        compose.onNodeWithText("▲ Price up from $15.99", substring = true).assertExists()
    }
}
