package com.alexivanov.receiptvault

import android.app.Application
import android.content.Context
import com.alexivanov.receiptvault.data.AppDatabase
import com.alexivanov.receiptvault.data.BackupManager
import com.alexivanov.receiptvault.data.ReceiptRepository
import com.alexivanov.receiptvault.data.SettingsRepository
import com.alexivanov.receiptvault.scan.ImageStore
import com.alexivanov.receiptvault.scan.TextReader
import com.alexivanov.receiptvault.update.GitHubApkDownloader
import com.alexivanov.receiptvault.update.UpdateChecker
import com.alexivanov.receiptvault.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReceiptVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = AppDatabase.create(context)
    val images = ImageStore(context)
    val receipts = ReceiptRepository(database.receiptDao(), images)
    val settings = SettingsRepository(context)
    val backup = BackupManager(receipts, images, context.cacheDir, BuildConfig.VERSION_NAME)
    val textReader = TextReader(context)

    private val apkDownloader = GitHubApkDownloader(context).also { it.clearDownloads() }

    /** Check GitHub Releases, download the newer APK in-app, hand it to the installer. */
    val updates = UpdateController(
        scope = appScope,
        source = UpdateChecker(BuildConfig.UPDATE_REPO),
        downloader = apkDownloader,
        currentVersionCode = BuildConfig.VERSION_CODE,
    )

    /** Launch-time check, unless the user turned it off in Settings. */
    fun checkForUpdatesOnLaunch() {
        appScope.launch { if (settings.current().autoCheckUpdates) updates.checkAutomatically() }
    }
}
