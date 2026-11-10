package com.alexivanov.receiptvault

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.alexivanov.receiptvault.alerts.AlertChecker
import com.alexivanov.receiptvault.alerts.AlertWorker
import com.alexivanov.receiptvault.alerts.AndroidNotifier
import com.alexivanov.receiptvault.data.AppDatabase
import com.alexivanov.receiptvault.data.BackupManager
import com.alexivanov.receiptvault.data.BackupTasks
import com.alexivanov.receiptvault.data.ExchangeRateRepository
import com.alexivanov.receiptvault.data.FrankfurterRateSource
import com.alexivanov.receiptvault.data.ReceiptRepository
import com.alexivanov.receiptvault.data.SettingsRepository
import com.alexivanov.receiptvault.scan.ImageStore
import com.alexivanov.receiptvault.scan.PdfPages
import com.alexivanov.receiptvault.scan.TextReader
import com.alexivanov.receiptvault.update.GitHubApkDownloader
import com.alexivanov.receiptvault.update.UpdateChecker
import com.alexivanov.receiptvault.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ReceiptVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // WorkManager may be unavailable in some test environments; reminders then run at app start only.
        runCatching { AlertWorker.schedule(this) }
    }
}

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = AppDatabase.create(context)
    val images = ImageStore(context)
    val receipts = ReceiptRepository(database.receiptDao(), images)
    val settings = SettingsRepository(context)
    val backup = BackupManager(receipts, images, context.cacheDir, BuildConfig.VERSION_NAME)

    /** Backups and restores outlive the Settings screen; a result nobody saw becomes a toast. */
    val backupTasks = BackupTasks(appScope) { message ->
        ContextCompat.getMainExecutor(context).execute { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
    val textReader = TextReader(context)
    val pdfPages = PdfPages(context)
    val alerts = AlertChecker(receipts, settings, AndroidNotifier(context))
    val exchangeRates = ExchangeRateRepository(settings, FrankfurterRateSource())

    private val apkDownloader = GitHubApkDownloader(context).also { it.clearDownloads() }

    /** Check GitHub Releases, download the newer APK in-app, hand it to the installer. */
    val updates = UpdateController(
        scope = appScope,
        source = UpdateChecker(BuildConfig.UPDATE_REPO),
        downloader = apkDownloader,
        currentVersionCode = BuildConfig.VERSION_CODE,
    )

    init {
        appScope.launch {
            runCatching { settings.pinDefaultCurrency() }
            runCatching { receipts.purgeExpiredTrash() }
            runCatching { receipts.sweepOrphanImages() }
            runCatching { alerts.run() }
        }
        // Download rates when conversion is turned on or the default currency changes, and once a day.
        appScope.launch {
            settings.settings.map { it.convertCurrencies to it.defaultCurrency }.distinctUntilChanged().collect { (on, _) ->
                if (on && runCatching { exchangeRates.refreshIfNeeded() }.getOrDefault(false)) runCatching { alerts.run() }
            }
        }
    }

    /** After a receipt or budget changes: a reminder may be due today or a budget just crossed. */
    fun checkAlertsSoon() {
        appScope.launch { runCatching { alerts.run() } }
    }

    /** Launch-time check, unless the user turned it off in Settings. */
    fun checkForUpdatesOnLaunch() {
        appScope.launch { if (settings.current().autoCheckUpdates) updates.checkAutomatically() }
    }
}
