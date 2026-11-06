package com.alexivanov.receiptvault.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alexivanov.receiptvault.domain.Budgets
import com.alexivanov.receiptvault.domain.ExchangeRates
import com.alexivanov.receiptvault.domain.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    /** Currency used when a receipt does not show one. */
    val defaultCurrency: String = Money.defaultCurrency(),
    /** Read 03/04/2026 as 3 April instead of March 4. */
    val dayFirstDates: Boolean = Locale.getDefault().country !in setOf("US", "CA", "PH"),
    val dismissedUpdateVersionCode: Int = 0,
    /** Look for a newer release on GitHub each time the app starts. */
    val autoCheckUpdates: Boolean = true,
    /** Ask for fingerprint/face/screen lock when opening the app. */
    val appLock: Boolean = false,
    /** When the last backup was saved (epoch millis), or 0 if never. */
    val lastBackupAt: Long = 0,
    /** Monthly spending limits in [defaultCurrency]. */
    val budgets: Budgets = Budgets(),
    /** Count receipts in other currencies in totals and budgets, at downloaded rates. Off unless turned on. */
    val convertCurrencies: Boolean = false,
    /** Last downloaded rates, kept for offline use. */
    val exchangeRates: ExchangeRates? = null,
) {
    /** The rates to convert with right now: on, downloaded, and for the current default currency. */
    val activeRates: ExchangeRates?
        get() = exchangeRates?.takeIf { convertCurrencies && it.base == defaultCurrency }
}

class SettingsRepository(private val context: Context) {
    private val currency = stringPreferencesKey("default_currency")
    private val dayFirst = booleanPreferencesKey("day_first_dates")
    private val dismissedUpdate = intPreferencesKey("dismissed_update")
    private val autoCheck = booleanPreferencesKey("auto_check_updates")
    private val appLockKey = booleanPreferencesKey("app_lock")
    private val lastBackup = longPreferencesKey("last_backup_at")
    private val budgetsKey = stringPreferencesKey("budgets")
    private val sentAlerts = stringSetPreferencesKey("sent_alerts")
    private val convertKey = booleanPreferencesKey("convert_currencies")
    private val ratesKey = stringPreferencesKey("exchange_rates")

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            defaultCurrency = p[currency] ?: defaults.defaultCurrency,
            dayFirstDates = p[dayFirst] ?: defaults.dayFirstDates,
            dismissedUpdateVersionCode = p[dismissedUpdate] ?: 0,
            autoCheckUpdates = p[autoCheck] ?: defaults.autoCheckUpdates,
            appLock = p[appLockKey] ?: false,
            lastBackupAt = p[lastBackup] ?: 0,
            budgets = Budgets.decode(p[budgetsKey]),
            convertCurrencies = p[convertKey] ?: false,
            exchangeRates = ExchangeRates.decode(p[ratesKey]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDefaultCurrency(value: String) = context.dataStore.edit { it[currency] = value }
    suspend fun setDayFirstDates(value: Boolean) = context.dataStore.edit { it[dayFirst] = value }
    suspend fun setDismissedUpdate(value: Int) = context.dataStore.edit { it[dismissedUpdate] = value }
    suspend fun setAutoCheckUpdates(value: Boolean) = context.dataStore.edit { it[autoCheck] = value }
    suspend fun setAppLock(value: Boolean) = context.dataStore.edit { it[appLockKey] = value }
    suspend fun setLastBackupAt(value: Long) = context.dataStore.edit { it[lastBackup] = value }
    suspend fun setBudgets(value: Budgets) = context.dataStore.edit { it[budgetsKey] = value.encode() }
    suspend fun setConvertCurrencies(value: Boolean) = context.dataStore.edit { it[convertKey] = value }
    suspend fun setExchangeRates(value: ExchangeRates?) = context.dataStore.edit {
        if (value == null) it.remove(ratesKey) else it[ratesKey] = value.encode()
    }

    /** Keys of reminder and budget notifications already shown, so each one is sent once. */
    suspend fun sentAlerts(): Set<String> = context.dataStore.data.first()[sentAlerts].orEmpty()

    suspend fun setSentAlerts(keys: Set<String>) = context.dataStore.edit { it[sentAlerts] = keys }
}
