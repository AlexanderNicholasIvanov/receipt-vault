package com.alexivanov.receiptvault

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** With app lock on, the app opens behind the lock screen and none of its content is reachable. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class AppLockTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun lockOn() {
        AppLockSession.unlocked = false
        AppLockSession.backgroundedAt = null
        runBlocking { SettingsRepository(context).setAppLock(true) }
    }

    // DataStore is process-wide in tests too; don't leave other test classes locked.
    @After
    fun lockOff() {
        runBlocking { SettingsRepository(context).setAppLock(false) }
        AppLockSession.unlocked = false
    }

    @Test
    fun `locked app shows only the lock screen`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasText("Receipt Vault is locked"), 10_000)
            compose.onNodeWithText("Unlock").assertExists()
            // The home screen is composed underneath but hidden from the screen and accessibility.
            compose.onNodeWithText("No receipts yet").assertDoesNotExist()
            compose.onNodeWithText("Add receipt").assertDoesNotExist()
        }
    }

    @Test
    fun `an unlocked session survives activity recreation`() {
        AppLockSession.unlocked = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)
            scenario.recreate()
            compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)
            compose.onNodeWithText("Receipt Vault is locked").assertDoesNotExist()
        }
    }
}
