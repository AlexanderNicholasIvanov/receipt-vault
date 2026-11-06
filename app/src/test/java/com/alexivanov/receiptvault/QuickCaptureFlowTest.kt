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
    fun `the Scan shortcut starts the scanner from Home`() {
        ActivityScenario.launch<MainActivity>(QuickCapture.intent(context, QuickAction.SCAN)).use {
            // No Google Play services here, so the scanner falls back to the camera, which says so.
            compose.waitUntilAtLeastOneExists(hasText("Document scanner unavailable", substring = true), 10_000)
        }
    }

    @Test
    fun `a shortcut tapped while the app is open goes back to Home first`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)
            compose.onNodeWithText("Add receipt", useUnmergedTree = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Enter manually"), 5_000)
            compose.onNodeWithText("Enter manually", useUnmergedTree = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
            compose.onNodeWithText("Merchant").performTextInput("Half typed")

            scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, QuickCapture.intent(context, QuickAction.MANUAL)) }
            compose.waitUntilDoesNotExist(hasText("Half typed"), 10_000)
            compose.waitUntilAtLeastOneExists(hasText("Merchant"), 10_000)
        }
    }
}
