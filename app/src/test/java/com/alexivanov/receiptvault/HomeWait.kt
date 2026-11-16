package com.alexivanov.receiptvault

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/**
 * How long a test waits for something to appear. Locally a screen shows in well under a second,
 * but on a busy CI runner the first sheet or screen in a fresh JVM has taken over 5 s, failing
 * tests that were fine. A long wait costs nothing when the test passes.
 */
const val UI_WAIT = 30_000L

/**
 * Waits for the empty Home screen. On CI it has, twice, not appeared within 10 s for a reason
 * that never showed up locally; on a timeout this prints what is on screen and where every
 * thread is, so the test report shows what it was stuck on.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.waitForEmptyHome(timeoutMillis: Long = UI_WAIT) {
    try {
        waitUntilAtLeastOneExists(hasText("No receipts yet"), timeoutMillis)
    } catch (e: ComposeTimeoutException) {
        println("Screen when Home didn't appear:\n" + runCatching { onRoot(useUnmergedTree = true).printToString() }.getOrElse { it.toString() })
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            println("Thread ${thread.name} (${thread.state})\n    " + stack.take(30).joinToString("\n    "))
        }
        throw e
    }
}

/** Opens one of the places in the navigation bar or rail: "Receipts", "Insights" or "Settings". */
fun ComposeTestRule.goTo(place: String) = onNode(hasText(place) and isSelectable()).performClick()
