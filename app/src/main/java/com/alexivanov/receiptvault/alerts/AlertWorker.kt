package com.alexivanov.receiptvault.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alexivanov.receiptvault.ReceiptVaultApp
import java.util.concurrent.TimeUnit

/** Checks reminders and budgets once a day, even when the app isn't opened. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ReceiptVaultApp ?: return Result.failure()
        return try {
            app.container.alerts.run()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "daily-alerts"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
