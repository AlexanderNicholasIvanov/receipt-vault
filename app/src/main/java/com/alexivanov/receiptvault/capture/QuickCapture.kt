package com.alexivanov.receiptvault.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.alexivanov.receiptvault.MainActivity

/** Ways to start a receipt without going through Home: app shortcuts, the Quick Settings tile and the widget. */
enum class QuickAction(val intentAction: String) {
    SCAN("com.alexivanov.receiptvault.action.SCAN"),
    MANUAL("com.alexivanov.receiptvault.action.ADD_MANUALLY"),
    ;

    companion object {
        fun from(intent: Intent?): QuickAction? = entries.firstOrNull { it.intentAction == intent?.action }
    }
}

object QuickCapture {

    fun intent(context: Context, action: QuickAction): Intent =
        Intent(action.intentAction)
            .setClass(context, MainActivity::class.java)
            // Bring the existing task forward instead of stacking a second copy of the app.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context, action: QuickAction): PendingIntent = PendingIntent.getActivity(
        context,
        action.ordinal,
        intent(context, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
