package com.alexivanov.receiptvault

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexivanov.receiptvault.capture.QuickAction
import com.alexivanov.receiptvault.capture.QuickCapture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Starting a receipt from an app shortcut, the Quick Settings tile or the widget. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class QuickCaptureFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the Add manually shortcut opens an empty receipt`() {
        ActivityScenario.launch<MainActivity>(QuickCapture.intent(context, QuickAction.MANUAL)).use {
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
            compose.onNodeWithText("Merchant").performTextInput("Kiosk")
            compose.onNodeWithText("Total").performTextInput("3.50")
            compose.onNodeWithText("Save receipt", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasText("$3.50"), 10_000)
        }
    }

    @Test
    fun `Back on an unsaved receipt asks first, Back on an untouched one just leaves`() {
        ActivityScenario.launch<MainActivity>(QuickCapture.intent(context, QuickAction.MANUAL)).use { scenario ->
            fun pressBack() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
            compose.onNodeWithText("Merchant").performTextInput("Half typed")
            compose.waitForIdle()

            pressBack()
            compose.waitUntilAtLeastOneExists(hasText("Discard this receipt?"), 5_000)
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Half typed").assertExists()

            pressBack()
            compose.waitUntilAtLeastOneExists(hasText("Discard this receipt?"), 5_000)
            compose.onNodeWithText("Discard").performClick()
            compose.waitForEmptyHome()

            compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Enter manually"), 5_000)
            compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
            pressBack()
            compose.waitForEmptyHome()
            compose.onNodeWithText("Discard this receipt?").assertDoesNotExist()
        }
    }

    @Test
    fun `the Scan shortcut starts the scanner from Home`() {
        ActivityScenario.launch<MainActivity>(QuickCapture.intent(context, QuickAction.SCAN)).use {
            // No Google Play services here, so the scanner falls back to the camera, which says so.
            compose.waitUntilAtLeastOneExists(hasText("Document scanner unavailable", substring = true), 10_000)
        }
    }

    @Test
    fun `a shortcut tapped during an unsaved receipt asks before discarding it`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForEmptyHome()
            compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Enter manually"), 5_000)
            compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
            compose.onNodeWithText("Merchant").performTextInput("Half typed")
            compose.waitForIdle()

            fun tapShortcut() = scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, QuickCapture.intent(context, QuickAction.MANUAL)) }

            tapShortcut()
            compose.waitUntilAtLeastOneExists(hasText("Discard this receipt?"), 10_000)
            compose.onNodeWithText("Keep editing").performClick()
            compose.waitUntilDoesNotExist(hasText("Discard this receipt?"), 5_000)
            compose.onNodeWithText("Half typed").assertExists()

            tapShortcut()
            compose.waitUntilAtLeastOneExists(hasText("Discard this receipt?"), 10_000)
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntilDoesNotExist(hasText("Half typed"), 10_000)
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
        }
    }
}
