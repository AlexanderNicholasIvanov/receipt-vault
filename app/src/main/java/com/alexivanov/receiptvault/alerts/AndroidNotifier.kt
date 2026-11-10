package com.alexivanov.receiptvault.alerts

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alexivanov.receiptvault.MainActivity
import com.alexivanov.receiptvault.R
import com.alexivanov.receiptvault.domain.AlertText
import com.alexivanov.receiptvault.domain.AlertTexts
import com.alexivanov.receiptvault.domain.BudgetUse
import com.alexivanov.receiptvault.domain.Reminder

class AndroidNotifier(private val context: Context) : Notifier {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannelsCompat(
            listOf(
                channel(CHANNEL_REMINDERS, "Return & warranty reminders", "Before a return window or warranty runs out."),
                channel(CHANNEL_BUDGETS, "Budget warnings", "When a monthly budget is nearly or fully used."),
            ),
        )
    }

    override fun enabled(): Boolean =
        manager.areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun reminder(reminder: Reminder, hideDetails: Boolean) {
        post(
            id = reminder.key.hashCode(),
            notification = build(CHANNEL_REMINDERS, AlertTexts.reminder(reminder, hideDetails), open(reminder.receiptId)),
        )
    }

    override fun budget(use: BudgetUse, currency: String, hideDetails: Boolean) {
        post(
            id = "budget:${use.category?.name}".hashCode(),
            notification = build(CHANNEL_BUDGETS, AlertTexts.budget(use, currency, hideDetails), open(null)),
        )
    }

    // Private: a secure lock screen shows the generic public version, never merchants or amounts.
    private fun build(channel: String, text: AlertText, intent: PendingIntent): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(AlertTexts.lockScreen.title)
                    .setContentText(AlertTexts.lockScreen.text)
                    .build(),
            )
            .build()

    private fun post(id: Int, notification: Notification) {
        // Checked again here: the permission can be withdrawn at any moment.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(id, notification)
    }

    private fun open(receiptId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (receiptId != null) putExtra(MainActivity.EXTRA_RECEIPT_ID, receiptId) }
        return PendingIntent.getActivity(
            context,
            (receiptId ?: -1L).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun channel(id: String, name: String, description: String) =
        NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(name).setDescription(description).build()

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_BUDGETS = "budgets"
    }
}
