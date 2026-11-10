package com.alexivanov.receiptvault.alerts

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexivanov.receiptvault.domain.Reminder
import com.alexivanov.receiptvault.domain.ReminderKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Receipt details stay off the lock screen, and with app lock on, out of the notification shade too. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AndroidNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val reminder = Reminder(receiptId = 7, merchant = "Best Electronics", kind = ReminderKind.RETURN, date = LocalDate.of(2026, 11, 10), daysLeft = 1)

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    @Test
    fun `the lock screen gets a version without the receipt`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidNotifier(context).reminder(reminder, hideDetails = false)
        val n = posted()

        assertEquals("Return window closing: Best Electronics", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Receipt Vault", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Unlock your phone to see this alert.", n.publicVersion.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `with app lock on the notification itself names nothing`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidNotifier(context).reminder(reminder, hideDetails = true)
        val n = posted()

        assertEquals("A return window is closing", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Open Receipt Vault to see the details.", n.extras.getString(Notification.EXTRA_TEXT))
    }
}
