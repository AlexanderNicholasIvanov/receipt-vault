package com.alexivanov.receiptvault

import android.app.Application
import android.content.Context
import com.alexivanov.receiptvault.data.AppDatabase
import com.alexivanov.receiptvault.data.ReceiptRepository
import com.alexivanov.receiptvault.data.SettingsRepository
import com.alexivanov.receiptvault.scan.ImageStore
import com.alexivanov.receiptvault.scan.LocationService
import com.alexivanov.receiptvault.scan.TextReader
import com.alexivanov.receiptvault.update.UpdateChecker
import com.alexivanov.receiptvault.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val textReader = TextReader(context)
    val location = LocationService(context)

    private val updateChecker = UpdateChecker(BuildConfig.UPDATE_REPO)
    private val _update = MutableStateFlow<UpdateInfo?>(null)

    /** A newer release on GitHub, if the last check found one. */
    val update: StateFlow<UpdateInfo?> = _update
    private var checkedThisLaunch = false

    /** Automatic check, at most once per process. */
    fun checkForUpdateOnce() {
        if (checkedThisLaunch) return
        checkedThisLaunch = true
        appScope.launch { checkForUpdate() }
    }

    suspend fun checkForUpdate(): UpdateChecker.Outcome {
        val outcome = updateChecker.check(BuildConfig.VERSION_CODE)
        if (outcome is UpdateChecker.Outcome.Newer) _update.value = outcome.info
        return outcome
    }
}
