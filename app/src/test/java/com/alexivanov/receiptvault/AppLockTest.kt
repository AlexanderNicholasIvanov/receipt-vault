package com.alexivanov.receiptvault

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import com.alexivanov.receiptvault.domain.AppLockPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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
        AppLockSession.reset()
        runBlocking { SettingsRepository(context).setAppLock(true) }
    }

    // DataStore is process-wide in tests too; don't leave other test classes locked.
    @After
    fun lockOff() {
        runBlocking { SettingsRepository(context).setAppLock(false) }
        AppLockSession.reset()
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

    // A share opens a second copy of the app in the sharing app's task. If that copy notices the
    // time away, the copy the person left behind has to lock as well.
    @Test
    fun `a copy of the app opened by a share locks the original too`() {
        AppLockSession.unlocked = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("No receipts yet"), 10_000)
            scenario.moveToState(Lifecycle.State.CREATED)
            shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)

            val shareCopy = Robolectric.buildActivity(MainActivity::class.java).setup()
            shareCopy.pause().stop().destroy()

            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntilAtLeastOneExists(hasText("Receipt Vault is locked"), 10_000)
            compose.onNodeWithText("No receipts yet").assertDoesNotExist()
        }
    }

    @Test
    fun `moving between two copies of the app isn't leaving it`() {
        AppLockSession.unlocked = true
        AppLockSession.activityStarted(enabled = true, now = 0)
        AppLockSession.activityStarted(enabled = true, now = 100_000) // the share copy comes up first
        AppLockSession.activityStopped(changingConfigurations = false, now = 100_001) // then the other stops
        assertEquals(null, AppLockSession.backgroundedAt)
        AppLockSession.activityStopped(changingConfigurations = false, now = 200_000)
        assertEquals(200_000L, AppLockSession.backgroundedAt)
        AppLockSession.activityStarted(enabled = true, now = 200_000 + AppLockPolicy.GRACE_MILLIS)
        assertFalse(AppLockSession.unlocked)
    }
}
