package com.alexivanov.receiptvault

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** A restore of a big backup takes a while; leaving Settings meanwhile mustn't cut it short. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class BackupInBackgroundTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = (compose.activity.application as ReceiptVaultApp).container

    @Test
    fun `a restore keeps going after leaving Settings and still reports how it went`() {
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), UI_WAIT)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Restore from backup"), UI_WAIT)

        val finish = CompletableDeferred<Unit>()
        assertTrue(app.backupTasks.run { finish.await(); "Restored 2 receipts." })
        compose.waitUntil(UI_WAIT) { app.backupTasks.busy.value }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), UI_WAIT)
        compose.waitUntilDoesNotExist(hasText("Restore from backup"), UI_WAIT)
        assertTrue("still running", app.backupTasks.busy.value)

        finish.complete(Unit)
        compose.waitUntil(UI_WAIT) { !app.backupTasks.busy.value }
        // The toast is posted to the main thread, which only node lookups run on their own.
        compose.waitUntil(UI_WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Restored 2 receipts."
        }
        assertFalse(app.backupTasks.busy.value)
        assertEquals(null, app.backupTasks.result.value)
    }

    @Test
    fun `with Settings open, the outcome shows there`() {
        compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), UI_WAIT)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Restore from backup"), UI_WAIT)

        app.backupTasks.run { "Backed up 4 receipts." }
        compose.waitUntilAtLeastOneExists(hasText("Backed up 4 receipts."), UI_WAIT)
        assertEquals(null, ShadowToast.getTextOfLatestToast())
    }
}
