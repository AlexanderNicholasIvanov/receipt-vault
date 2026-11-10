package com.alexivanov.receiptvault

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/**
 * Waits for the empty Home screen. On CI it has, twice, not appeared within 10 s for a reason
 * that never showed up locally; on a timeout this prints what is on screen and where every
 * thread is, so the test report shows what it was stuck on.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.waitForEmptyHome(timeoutMillis: Long = 10_000) {
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
